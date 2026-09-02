// Copyright 2026 The WalGerrit Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.walgerrit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Filesystem implementation of the versioned object-store contract. */
final class FileObjectStore implements ObjectStore {
  /**
   * Writers of the same key serialize on one of these stripes, and the matching lock file extends
   * the exclusion across processes. Striping keeps both the lock objects and the lock files bounded
   * no matter how many objects a store ever holds; a per-key map grew with every object written.
   */
  private static final int LOCK_STRIPES = 256;

  private static final ReentrantLock[] JVM_LOCKS = new ReentrantLock[LOCK_STRIPES];

  static {
    for (int stripe = 0; stripe < LOCK_STRIPES; stripe++) {
      JVM_LOCKS[stripe] = new ReentrantLock();
    }
  }

  private static final String TEMPORARY_MARKER = ".tmp-";

  private final Path root;
  private final Path lockRoot;

  FileObjectStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
    lockRoot = this.root.resolve(".object-locks");
  }

  Path root() {
    return root;
  }

  @Override
  public Optional<StoredObject> get(String key) throws IOException {
    Path path = resolve(key);
    if (!Files.exists(path)) {
      return Optional.empty();
    }
    if (!Files.isRegularFile(path)) {
      throw new IOException("Object is not a regular file: " + key);
    }
    byte[] bytes = Files.readAllBytes(path);
    return Optional.of(new StoredObject(bytes, version(bytes)));
  }

  @Override
  public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
    return withLock(
        key,
        () -> {
          Path target = resolve(key);
          if (Files.exists(target)) {
            throw new ObjectAlreadyExistsException(key);
          }
          writeAtomic(target, bytes, false);
          return new StoredObject(bytes, version(bytes));
        });
  }

  @Override
  public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
      throws IOException {
    return withLock(
        key,
        () -> {
          Path target = resolve(key);
          if (!Files.isRegularFile(target)) {
            throw new ObjectStoreConflictException(key);
          }
          byte[] current = Files.readAllBytes(target);
          if (!version(current).equals(expectedVersion)) {
            throw new ObjectStoreConflictException(key);
          }
          writeAtomic(target, bytes, true);
          return new StoredObject(bytes, version(bytes));
        });
  }

  @Override
  public void uploadIfAbsent(String key, Path source) throws IOException {
    withLock(
        key,
        () -> {
          Path target = resolve(key);
          if (Files.exists(target)) {
            if (Files.size(target) == Files.size(source)
                && digest(target).equals(digest(source))) {
              return null;
            }
            throw new IOException("Immutable object collision: " + key);
          }
          Files.createDirectories(target.getParent());
          Path temporary = temporarySibling(target);
          try {
            Files.copy(source, temporary);
            forceFile(temporary);
            moveAtomic(temporary, target, false);
            forceDirectory(target.getParent());
          } finally {
            Files.deleteIfExists(temporary);
          }
          return null;
        });
  }

  @Override
  public void download(String key, Path target) throws IOException {
    Path source = resolve(key);
    if (source.equals(target.toAbsolutePath().normalize())) {
      if (!Files.isRegularFile(source)) {
        throw new IOException("Object not found: " + key);
      }
      return;
    }
    if (!Files.isRegularFile(source)) {
      throw new IOException("Object not found: " + key);
    }
    Files.createDirectories(target.getParent());
    Path temporary = temporarySibling(target);
    try {
      Files.copy(source, temporary);
      forceFile(temporary);
      moveAtomic(temporary, target, false);
      forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public void delete(String key) throws IOException {
    withLock(
        key,
        () -> {
          Files.deleteIfExists(resolve(key));
          return null;
        });
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    Path start = listingRoot(prefix);
    if (!Files.isDirectory(start)) {
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return directory.equals(lockRoot)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isRegularFile()
                && !file.getFileName().toString().contains(TEMPORARY_MARKER)) {
              Path relative = root.relativize(file);
              String key =
                  relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
              if (key.startsWith(prefix)) {
                keys.add(key);
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException failure) {
            // A staging file that was renamed or removed while the walk was in progress.
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(keys);
    return List.copyOf(keys);
  }

  /**
   * The deepest directory every key with this prefix lives under, so a listing walks only that
   * subtree rather than the whole store.
   */
  private Path listingRoot(String prefix) throws IOException {
    int slash = prefix.lastIndexOf('/');
    if (slash < 0) {
      return root;
    }
    Path start = root.resolve(prefix.substring(0, slash)).normalize();
    if (!start.startsWith(root)) {
      throw new IOException("Listing prefix escapes storage root: " + prefix);
    }
    return start;
  }

  @Override
  public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
    List<ObjectSummary> summaries = new ArrayList<>();
    for (String key : list(prefix)) {
      try {
        Path path = resolve(key);
        summaries.add(
            new ObjectSummary(
                key,
                version(Files.readAllBytes(path)),
                Files.getLastModifiedTime(path).toMillis()));
      } catch (NoSuchFileException vanished) {
        // A staging or temporary file that was renamed or removed after the walk saw it.
      }
    }
    return List.copyOf(summaries);
  }

  private Path resolve(String key) throws IOException {
    if (key.isBlank() || key.startsWith("/") || key.indexOf('\\') >= 0) {
      throw new IOException("Invalid object key: " + key);
    }
    Path resolved = root.resolve(key).normalize();
    if (!resolved.startsWith(root)) {
      throw new IOException("Object key escapes storage root: " + key);
    }
    return resolved;
  }

  private <T> T withLock(String key, IoSupplier<T> operation) throws IOException {
    // String.hashCode is specified by the language, so every process maps a key to the same stripe
    // and therefore to the same lock file.
    int stripe = Math.floorMod(key.hashCode(), LOCK_STRIPES);
    Files.createDirectories(lockRoot);
    Path lockPath = lockRoot.resolve(stripe + ".lock");
    ReentrantLock jvmLock = JVM_LOCKS[stripe];
    jvmLock.lock();
    try (FileChannel channel =
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock()) {
      return operation.get();
    } finally {
      jvmLock.unlock();
    }
  }

  private static void writeAtomic(Path target, byte[] bytes, boolean replace) throws IOException {
    Files.createDirectories(target.getParent());
    Path temporary = temporarySibling(target);
    try {
      try (FileChannel channel =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      moveAtomic(temporary, target, replace);
      forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void moveAtomic(Path source, Path target, boolean replace) throws IOException {
    try {
      if (replace) {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } else {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
      }
    } catch (AtomicMoveNotSupportedException exception) {
      throw new IOException("Filesystem does not support atomic publication: " + target, exception);
    }
  }

  private static Path temporarySibling(Path target) {
    return target.resolveSibling(target.getFileName() + TEMPORARY_MARKER + UUID.randomUUID());
  }

  private static void forceFile(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void forceDirectory(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException ignored) {
      // Some filesystems do not expose directory fsync through FileChannel.
    }
  }

  private static String digest(Path path) throws IOException {
    MessageDigest digest = sha256();
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String version(byte[] bytes) {
    MessageDigest digest = sha256();
    return HexFormat.of().formatHex(digest.digest(bytes));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM has no SHA-256 provider", exception);
    }
  }

  @FunctionalInterface
  private interface IoSupplier<T> {
    T get() throws IOException;
  }
}
