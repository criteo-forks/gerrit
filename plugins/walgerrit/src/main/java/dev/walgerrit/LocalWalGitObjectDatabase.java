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

import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.ObjectId;

/** JGit DFS object database backed by immutable local files and a CAS manifest. */
final class LocalWalGitObjectDatabase extends DfsObjDatabase {
  private static final int SHA1_BYTES = 20;

  private final ManifestStore manifestStore;
  private final ThreadLocal<Long> refTransactionRevision = new ThreadLocal<>();
  private final ThreadLocal<Boolean> refTransactionCommitted = new ThreadLocal<>();
  private volatile Set<ObjectId> shallowCommits = Collections.emptySet();

  LocalWalGitObjectDatabase(
      DfsRepository repository, ManifestStore manifestStore) {
    super(repository, new DfsReaderOptions());
    this.manifestStore = manifestStore;
  }

  ManifestStore manifestStore() {
    return manifestStore;
  }

  @Override
  protected DfsPackDescription newPack(PackSource source) {
    String name = "pack-" + UUID.randomUUID();
    return new DfsPackDescription(getRepository().getDescription(), name, source);
  }

  @Override
  protected void commitPackImpl(
      Collection<DfsPackDescription> descriptions,
      Collection<DfsPackDescription> replacements)
      throws IOException {
    for (DfsPackDescription description : descriptions) {
      for (PackExt extension : PackExt.values()) {
        if (description.hasFileExt(extension)) {
          manifestStore.publishImmutableFile(description.getFileName(extension));
        }
      }
    }

    List<PackRef> additions = new ArrayList<>(descriptions.size());
    for (DfsPackDescription description : descriptions) {
      additions.add(toPackRef(description));
    }
    List<String> supersedes = new ArrayList<>();
    if (replacements != null) {
      for (DfsPackDescription replacement : replacements) {
        supersedes.add(packName(replacement));
      }
    }

    boolean writesReftable =
        descriptions.stream()
            .anyMatch(description -> description.hasFileExt(PackExt.REFTABLE));
    boolean logicalRefUpdate =
        descriptions.stream()
            .anyMatch(
                description ->
                    description.hasFileExt(PackExt.REFTABLE)
                        && (description.getPackSource() == PackSource.INSERT
                            || description.getPackSource() == PackSource.RECEIVE));
    Long expectedRefRevision = refTransactionRevision.get();
    if (logicalRefUpdate && expectedRefRevision == null) {
      throw new IOException("Reftable publication is outside a ref transaction");
    }
    manifestStore.publish(
        expectedRefRevision == null ? 0 : expectedRefRevision,
        additions,
        supersedes,
        logicalRefUpdate);
    if (logicalRefUpdate) {
      refTransactionCommitted.set(true);
    } else if (writesReftable) {
      // Representation-only reftable compaction changes the stack generation,
      // but it is not a logical ref transaction and does not require one.
      clearCache();
    }
  }

  @Override
  protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
    for (DfsPackDescription description : descriptions) {
      for (PackExt extension : PackExt.values()) {
        manifestStore.discardStagingFile(description.getFileName(extension));
      }
    }
  }

  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    Manifest manifest = manifestStore.read();
    List<DfsPackDescription> descriptions = new ArrayList<>(manifest.getPacksCount());
    for (PackRef pack : manifest.getPacksList()) {
      descriptions.add(fromPackRef(pack));
    }
    return descriptions;
  }

  @Override
  protected ReadableChannel openFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    Path path = manifestStore.immutableFile(description.getFileName(extension));
    if (!Files.isRegularFile(path)) {
      throw new FileNotFoundException(path.toString());
    }
    return new FileReadableChannel(path);
  }

  @Override
  protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension)
      throws IOException {
    return new FileDfsOutputStream(
        manifestStore.stagingFile(description.getFileName(extension)));
  }

  @Override
  public Set<ObjectId> getShallowCommits() {
    return shallowCommits;
  }

  @Override
  public void setShallowCommits(Set<ObjectId> shallowCommits) {
    this.shallowCommits = Collections.unmodifiableSet(new HashSet<>(shallowCommits));
  }

  @Override
  public long getApproximateObjectCount() {
    try {
      return manifestStore.read().getPacksList().stream()
          .mapToLong(PackRef::getObjectCount)
          .sum();
    } catch (IOException exception) {
      return -1;
    }
  }

  void beginRefTransaction() throws IOException {
    Manifest manifest = manifestStore.read();
    refTransactionRevision.set(manifest.getRefRevision());
    refTransactionCommitted.set(false);
    // JGit's DFS pack list is intentionally cached. A new transaction must
    // revalidate the manifest, just like a conditional GET in remote WalGit.
    clearCache();
  }

  void endRefTransaction() {
    refTransactionRevision.remove();
    refTransactionCommitted.remove();
  }

  boolean refTransactionCommitted() {
    return Boolean.TRUE.equals(refTransactionCommitted.get());
  }

  private PackRef toPackRef(DfsPackDescription description) throws IOException {
    PackRef.Builder pack =
        PackRef.newBuilder()
            .setName(packName(description))
            .setSource(description.getPackSource().name())
            .setLastModifiedEpochMillis(description.getLastModified())
            .setObjectCount(description.getObjectCount())
            .setDeltaCount(description.getDeltaCount())
            .setMinUpdateIndex(description.getMinUpdateIndex())
            .setMaxUpdateIndex(description.getMaxUpdateIndex());

    for (PackExt extension : PackExt.values()) {
      if (!description.hasFileExt(extension)) {
        continue;
      }
      Path file = manifestStore.immutableFile(description.getFileName(extension));
      long actualSize = Files.size(file);
      pack.addFiles(
          PackFile.newBuilder()
              .setExtension(extension.getExtension())
              .setSize(actualSize)
              .setBlockSize(description.getBlockSize(extension)));
      if (extension == PackExt.PACK) {
        pack.setPackChecksum(readPackChecksum(file));
      }
    }
    return pack.build();
  }

  private DfsPackDescription fromPackRef(PackRef pack) throws IOException {
    PackSource source;
    try {
      source = PackSource.valueOf(pack.getSource());
    } catch (IllegalArgumentException exception) {
      throw new IOException("Unknown pack source: " + pack.getSource(), exception);
    }

    DfsPackDescription description =
        new DfsPackDescription(getRepository().getDescription(), pack.getName(), source)
            .setLastModified(pack.getLastModifiedEpochMillis())
            .setObjectCount(pack.getObjectCount())
            .setDeltaCount(pack.getDeltaCount())
            .setMinUpdateIndex(pack.getMinUpdateIndex())
            .setMaxUpdateIndex(pack.getMaxUpdateIndex());
    for (PackFile file : pack.getFilesList()) {
      PackExt extension = extension(file.getExtension());
      description.addFileExt(extension);
      description.setFileSize(extension, file.getSize());
      description.setBlockSize(extension, file.getBlockSize());
    }
    return description;
  }

  private static String packName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    String suffix = "." + PackExt.PACK.getExtension();
    return fileName.endsWith(suffix)
        ? fileName.substring(0, fileName.length() - suffix.length())
        : fileName;
  }

  private static PackExt extension(String value) throws IOException {
    for (PackExt extension : PackExt.values()) {
      if (extension.getExtension().equals(value)) {
        return extension;
      }
    }
    throw new IOException("Unknown pack extension: " + value);
  }

  private static String readPackChecksum(Path pack) throws IOException {
    try (FileChannel channel = FileChannel.open(pack, StandardOpenOption.READ)) {
      if (channel.size() < SHA1_BYTES) {
        throw new IOException("Pack is too short to contain a checksum: " + pack);
      }
      ByteBuffer checksum = ByteBuffer.allocate(SHA1_BYTES);
      long position = channel.size() - SHA1_BYTES;
      while (checksum.hasRemaining()) {
        int read = channel.read(checksum, position + checksum.position());
        if (read < 0) {
          throw new IOException("Unexpected end of pack checksum: " + pack);
        }
      }
      return HexFormat.of().formatHex(checksum.array());
    }
  }
}
