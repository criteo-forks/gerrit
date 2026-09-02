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
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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
import org.eclipse.jgit.transport.ReceiveCommand;

/**
 * JGit DFS object database backed by immutable local files and a CAS manifest.
 *
 * <p>Freshness contract: the manifest is revalidated with a conditional read when the handle is
 * opened, when a ref transaction begins, when {@code scanForRepoChanges} is requested, and at most
 * once per {@code manifestRevalidateInterval} in between. Every JGit lookup in between is served
 * from JGit's in-memory pack list, which mirrors the newest manifest this node has observed.
 */
final class LocalWalGitObjectDatabase extends DfsObjDatabase {
  private static final int SHA1_BYTES = 20;

  private final ManifestStore manifestStore;
  private final long revalidateIntervalNanos;
  private final ThreadLocal<Long> refTransactionRevision = new ThreadLocal<>();
  private final ThreadLocal<Boolean> refTransactionCommitted = new ThreadLocal<>();
  private final ThreadLocal<RefTransaction> refTransaction = new ThreadLocal<>();
  private volatile Set<ObjectId> shallowCommits = Collections.emptySet();
  private volatile long observedManifestRevision = -1;
  private volatile long lastRevalidationNanos;

  LocalWalGitObjectDatabase(
      DfsRepository repository, ManifestStore manifestStore, Duration revalidateInterval)
      throws IOException {
    super(repository, new DfsReaderOptions());
    this.manifestStore = manifestStore;
    this.revalidateIntervalNanos = revalidateInterval.toNanos();
    // JGit 7.7 can synthesize multi-pack-index descriptions. WalGerrit's manifest currently
    // records independent immutable pack families, not MIDX coverage, so keep this representation
    // disabled even if a future repository config attempts to enable it.
    setUseMultipackIndex(false);
    // The manager revalidated the manifest when it opened this handle; start from that view.
    observedManifestRevision = manifestStore.current().getRevision();
    lastRevalidationNanos = System.nanoTime();
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
      Collection<DfsPackDescription> descriptions, Collection<DfsPackDescription> replacements)
      throws IOException {
    boolean compaction = false;
    for (DfsPackDescription description : descriptions) {
      switch (description.getPackSource()) {
        case GC:
        case GC_REST:
        case UNREACHABLE_GARBAGE:
          throw new IOException(
              "DfsGarbageCollector publication is disabled; use the leased DfsPackCompactor path");
        case COMPACT:
          compaction = true;
          break;
        case INSERT:
        case RECEIVE:
          break;
      }
    }

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
    RefTransaction logicalTransaction = refTransaction.get();
    if (logicalRefUpdate && logicalTransaction == null) {
      throw new IOException("Reftable publication has no recorded ref transaction");
    }
    Manifest updated =
        manifestStore.publish(
            expectedRefRevision == null ? 0 : expectedRefRevision,
            additions,
            supersedes,
            logicalRefUpdate,
            logicalTransaction);
    afterOwnPublication(updated, compaction);
    if (logicalRefUpdate) {
      refTransactionCommitted.set(true);
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

  /** Enumerates the newest manifest this node has observed; never a network read by itself. */
  @Override
  protected List<DfsPackDescription> listPacks() throws IOException {
    Manifest manifest = manifestStore.current();
    List<DfsPackDescription> descriptions = new ArrayList<>(manifest.getPacksCount());
    for (PackRef pack : manifest.getPacksList()) {
      descriptions.add(fromPackRef(pack));
    }
    return descriptions;
  }

  @Override
  public PackList getPackList() throws IOException {
    revalidateIfStale();
    return super.getPackList();
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
    return new FileDfsOutputStream(manifestStore.stagingFile(description.getFileName(extension)));
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
      return manifestStore.current().getPacksList().stream()
          .mapToLong(PackRef::getObjectCount)
          .sum();
    } catch (IOException exception) {
      return -1;
    }
  }

  /**
   * Starts a ref transaction: one conditional manifest read so expected-value checks run against
   * the current global state, then remember the reftable-stack generation the CAS must match.
   */
  void beginRefTransaction() throws IOException {
    revalidateNow();
    refTransactionRevision.set(manifestStore.current().getRefRevision());
    refTransactionCommitted.set(false);
  }

  void recordRefTransaction(Collection<ReceiveCommand> commands) {
    RefTransaction.Builder transaction = RefTransaction.newBuilder();
    for (ReceiveCommand command : commands) {
      RefUpdate.Builder update =
          RefUpdate.newBuilder()
              .setName(command.getRefName())
              .setOldObjectId(command.getOldId().name())
              .setNewObjectId(command.getNewId().name());
      if (command.getNewSymref() != null) {
        update.setNewSymbolicTarget(command.getNewSymref());
      }
      transaction.addUpdates(update);
    }
    refTransaction.set(transaction.build());
  }

  /**
   * Adopts the newest manifest this node has observed, after a conditional read if the handle has
   * gone longer than the configured interval without one. Returns whether the view changed.
   */
  boolean revalidateIfStale() throws IOException {
    long now = System.nanoTime();
    if (revalidateIntervalNanos > 0 && now - lastRevalidationNanos >= revalidateIntervalNanos) {
      manifestStore.refresh();
      lastRevalidationNanos = now;
    }
    return adoptObservedManifest();
  }

  /** Forces one conditional manifest read and adopts the result. Returns whether the view changed. */
  boolean revalidateNow() throws IOException {
    manifestStore.refresh();
    lastRevalidationNanos = System.nanoTime();
    return adoptObservedManifest();
  }

  void invalidateCaches() {
    clearCache();
    observedManifestRevision = -1;
  }

  void endRefTransaction() {
    refTransactionRevision.remove();
    refTransactionCommitted.remove();
    refTransaction.remove();
  }

  boolean refTransactionCommitted() {
    return Boolean.TRUE.equals(refTransactionCommitted.get());
  }

  private boolean adoptObservedManifest() throws IOException {
    long latest = manifestStore.current().getRevision();
    if (latest == observedManifestRevision) {
      return false;
    }
    synchronized (this) {
      if (latest == observedManifestRevision) {
        return false;
      }
      clearCache();
      observedManifestRevision = latest;
      return true;
    }
  }

  /**
   * After this handle published, JGit adds the new pack or reftable to its own in-memory list, so
   * the list still mirrors the manifest when the publication was the only change. A compaction
   * (JGit does not update the list itself) or a manifest that absorbed other writers' work in the
   * meantime requires a rescan from the cached manifest; no network read is involved either way.
   */
  private void afterOwnPublication(Manifest updated, boolean compaction) {
    synchronized (this) {
      long previous = observedManifestRevision;
      observedManifestRevision = updated.getRevision();
      lastRevalidationNanos = System.nanoTime();
      if (compaction || updated.getRevision() != previous + 1) {
        clearCache();
      }
    }
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
