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

import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.LogRef;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Durable protobuf manifest and immutable transaction-log storage for one repository. */
final class ManifestStore {
  static final String MANIFEST_FILE = "manifest.pb";

  private static final int FORMAT_VERSION = 1;
  private static final String OBJECT_FORMAT = "sha1";
  private static final String LOG_DIRECTORY = "log";
  private static final String STAGING_DIRECTORY = "staging";
  private static final String WAL_DIRECTORY = "wal";
  private static final String LOCK_FILE = "manifest.lock";
  private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

  private final Path repositoryPath;
  private final Path manifestPath;
  private final Path logPath;
  private final Path stagingPath;
  private final Path walPath;
  private final Path lockPath;
  private final String repositoryName;
  private final Clock clock;
  private final IoConsumer<Path> afterAtomicMove;

  ManifestStore(Path repositoryPath, String repositoryName) {
    this(repositoryPath, repositoryName, Clock.systemUTC(), ignored -> {});
  }

  ManifestStore(Path repositoryPath, String repositoryName, Clock clock) {
    this(repositoryPath, repositoryName, clock, ignored -> {});
  }

  ManifestStore(
      Path repositoryPath,
      String repositoryName,
      Clock clock,
      IoConsumer<Path> afterAtomicMove) {
    this.repositoryPath = repositoryPath;
    this.repositoryName = repositoryName;
    this.clock = clock;
    this.afterAtomicMove = afterAtomicMove;
    manifestPath = repositoryPath.resolve(MANIFEST_FILE);
    logPath = repositoryPath.resolve(LOG_DIRECTORY);
    stagingPath = repositoryPath.resolve(STAGING_DIRECTORY);
    walPath = repositoryPath.resolve(WAL_DIRECTORY);
    lockPath = repositoryPath.resolve(LOCK_FILE);
  }

  boolean exists() throws IOException {
    if (!Files.exists(manifestPath)) {
      return false;
    }
    if (!Files.isRegularFile(manifestPath)) {
      throw new IOException("Manifest is not a regular file: " + manifestPath);
    }
    return true;
  }

  boolean create() throws IOException {
    createDirectories();
    return withManifestLock(
        () -> {
          if (Files.exists(manifestPath)) {
            return false;
          }
          long now = clock.millis();
          Manifest manifest =
              Manifest.newBuilder()
                  .setFormatVersion(FORMAT_VERSION)
                  .setRepo(repositoryName)
                  .setObjectFormat(OBJECT_FORMAT)
                  .setUpdatedAtEpochMillis(now)
                  .setWriter(writerIdentity())
                  .build();
          writeManifestAtomic(manifest.toByteArray());
          return true;
        });
  }

  Manifest read() throws IOException {
    Manifest manifest = Manifest.parseFrom(Files.readAllBytes(manifestPath));
    validate(manifest);
    return manifest;
  }

  Manifest publish(
      long expectedRefRevision,
      Collection<PackRef> additions,
      Collection<String> supersedes,
      boolean requireExactRefRevision)
      throws IOException {
    createDirectories();
    return withManifestLock(
        () -> {
          Manifest current = read();
          if (requireExactRefRevision
              && current.getRefRevision() != expectedRefRevision) {
            throw new ManifestConflictException(
                expectedRefRevision, current.getRefRevision());
          }

          long sequence = current.getHeadSeq() + 1;
          long now = clock.millis();
          String writer = writerIdentity();
          Map<String, PackRef> livePacks = new LinkedHashMap<>();
          for (PackRef pack : current.getPacksList()) {
            livePacks.put(pack.getName(), pack);
          }
          for (String superseded : supersedes) {
            if (livePacks.remove(superseded) == null) {
              throw new IOException(
                  "Cannot supersede a pack that is no longer live: " + superseded);
            }
          }

          boolean changesRefs = changesRefs(additions, supersedes, current);

          LogEntry.Builder entry =
              LogEntry.newBuilder()
                  .setSeq(sequence)
                  .setKind(entryKind(supersedes, requireExactRefRevision))
                  .addAllSupersedes(supersedes)
                  .setCreatedAtEpochMillis(now)
                  .setWriter(writer)
                  .setBaseRevision(current.getRevision());
          for (PackRef addition : additions) {
            PackRef published = addition.toBuilder().setSeq(sequence).build();
            if (livePacks.putIfAbsent(published.getName(), published) != null) {
              throw new IOException("Pack already exists in manifest: " + published.getName());
            }
            entry.addAdditions(published);
          }

          byte[] logBytes = entry.build().toByteArray();
          String logKey =
              String.format("%s/%016x-%s.pb", LOG_DIRECTORY, sequence, UUID.randomUUID());
          writeImmutable(repositoryPath.resolve(logKey), logBytes);

          LogRef logRef =
              LogRef.newBuilder()
                  .setKey(logKey)
                  .setFirstSeq(sequence)
                  .setLastSeq(sequence)
                  .setSize(logBytes.length)
                  .setSealed(true)
                  .build();
          Manifest updated =
              current.toBuilder()
                  .setHeadSeq(sequence)
                  .clearPacks()
                  .addAllPacks(livePacks.values())
                  .addLogSegments(logRef)
                  .setRevision(current.getRevision() + 1)
                  .setRefRevision(
                      current.getRefRevision() + (changesRefs ? 1 : 0))
                  .setUpdatedAtEpochMillis(now)
                  .setWriter(writer)
                  .build();
          writeManifestAtomic(updated.toByteArray());
          return updated;
        });
  }

  Path stagingFile(String fileName) throws IOException {
    createDirectories();
    return stagingPath.resolve(fileName);
  }

  Path immutableFile(String fileName) {
    return walPath.resolve(fileName);
  }

  void publishImmutableFile(String fileName) throws IOException {
    Path source = stagingPath.resolve(fileName);
    Path target = walPath.resolve(fileName);
    forceFile(source);
    moveAtomic(source, target, false);
    forceDirectory(walPath);
  }

  void discardStagingFile(String fileName) {
    try {
      Files.deleteIfExists(stagingPath.resolve(fileName));
    } catch (IOException ignored) {
      // Rollback is best effort because another exception is already in flight.
    }
  }

  Path repositoryPath() {
    return repositoryPath;
  }

  private void createDirectories() throws IOException {
    Files.createDirectories(repositoryPath);
    Files.createDirectories(logPath);
    Files.createDirectories(stagingPath);
    Files.createDirectories(walPath);
  }

  private void validate(Manifest manifest) throws IOException {
    if (manifest.getFormatVersion() != FORMAT_VERSION) {
      throw new IOException("Unsupported manifest format: " + manifest.getFormatVersion());
    }
    if (!manifest.getRepo().equals(repositoryName)) {
      throw new IOException(
          "Manifest repository mismatch: expected "
              + repositoryName
              + " but found "
              + manifest.getRepo());
    }
    if (!manifest.getObjectFormat().equals(OBJECT_FORMAT)) {
      throw new IOException("Unsupported object format: " + manifest.getObjectFormat());
    }
  }

  private <T> T withManifestLock(IoSupplier<T> operation) throws IOException {
    Path normalizedLockPath = lockPath.toAbsolutePath().normalize();
    ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(normalizedLockPath, ignored -> new ReentrantLock());
    jvmLock.lock();
    try (FileChannel channel =
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock ignored = channel.lock()) {
      return operation.get();
    } finally {
      jvmLock.unlock();
    }
  }

  private void writeImmutable(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.getParent());
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      writeAndForce(temporary, bytes);
      moveAtomic(temporary, target, false);
      forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void writeManifestAtomic(byte[] bytes) throws IOException {
    try {
      writeAtomic(manifestPath, bytes);
    } catch (IOException publicationFailure) {
      // A conditional object-store write may land and lose its response. A
      // local atomic rename has the same ambiguity if the following directory
      // fsync reports an error. The committed manifest is the source of truth:
      // never report failure for a transaction that is already visible.
      try {
        if (Arrays.equals(Files.readAllBytes(manifestPath), bytes)) {
          return;
        }
      } catch (IOException verificationFailure) {
        publicationFailure.addSuppressed(verificationFailure);
      }
      throw publicationFailure;
    }
  }

  private void writeAtomic(Path target, byte[] bytes) throws IOException {
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      writeAndForce(temporary, bytes);
      moveAtomic(temporary, target, true);
      afterAtomicMove.accept(target);
      forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void writeAndForce(Path path, byte[] bytes) throws IOException {
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
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
      throw new IOException("Storage filesystem does not support atomic publication: " + target, exception);
    }
  }

  private static LogEntry.Kind entryKind(
      Collection<String> supersedes, boolean logicalRefUpdate) {
    if (logicalRefUpdate) {
      return LogEntry.Kind.REF_UPDATE;
    }
    return supersedes.isEmpty() ? LogEntry.Kind.PACK : LogEntry.Kind.COMPACT;
  }

  private static boolean changesRefs(
      Collection<PackRef> additions,
      Collection<String> supersedes,
      Manifest current) {
    if (additions.stream().anyMatch(ManifestStore::hasReftable)) {
      return true;
    }
    if (supersedes.isEmpty()) {
      return false;
    }
    return current.getPacksList().stream()
        .anyMatch(pack -> supersedes.contains(pack.getName()) && hasReftable(pack));
  }

  private static boolean hasReftable(PackRef pack) {
    return pack.getFilesList().stream()
        .anyMatch(file -> file.getExtension().equals("ref"));
  }

  private static String writerIdentity() {
    String host = System.getenv().getOrDefault("HOSTNAME", "localhost");
    return host + ":" + ProcessHandle.current().pid();
  }

  @FunctionalInterface
  private interface IoSupplier<T> {
    T get() throws IOException;
  }

  @FunctionalInterface
  interface IoConsumer<T> {
    void accept(T value) throws IOException;
  }
}
