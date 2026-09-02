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

import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.ObjectStore.ConditionalRead;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.LogRef;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable protobuf manifest and immutable transaction-log storage for one repository.
 *
 * <p>Manifest reads go through the node-wide {@link ManifestCache}. {@link #refresh()} performs one
 * conditional read against the newest version this node has observed, so an unchanged manifest
 * costs a round trip without a body; {@link #current()} serves the cached manifest without touching
 * the object store at all. Callers choose the freshness boundary; this class never reads the
 * manifest on its own initiative except to establish a CAS base.
 */
final class ManifestStore {
  static final String MANIFEST_FILE = "manifest.pb";

  private static final int FORMAT_VERSION = 1;
  private static final int MAX_CAS_ATTEMPTS = 64;
  private static final String OBJECT_FORMAT = "sha1";
  private static final String LOG_DIRECTORY = "log";
  private static final String STAGING_DIRECTORY = "staging";
  private static final String WAL_DIRECTORY = "wal";

  private final ObjectStore objectStore;
  private final ObjectStore manifestObjects;
  private final Path repositoryPath;
  private final Path indexCursorPath;
  private final Path stagingPath;
  private final Path walPath;
  private final String repositoryName;
  private final Clock clock;
  private final IoConsumer<String> afterManifestCas;
  private final ManifestCache cache;
  private final String cacheKey;

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
      IoConsumer<Path> afterManifestCas) {
    this(
        new FileObjectStore(repositoryPath),
        repositoryPath,
        repositoryPath.resolve("index-events.cursor"),
        repositoryName,
        clock,
        ignored -> afterManifestCas.accept(repositoryPath.resolve(MANIFEST_FILE)));
  }

  ManifestStore(
      ObjectStore objectStore,
      Path repositoryPath,
      String repositoryName,
      Clock clock,
      IoConsumer<String> afterManifestCas) {
    this(
        objectStore,
        repositoryPath,
        repositoryPath.resolve("index-events.cursor"),
        repositoryName,
        clock,
        afterManifestCas);
  }

  ManifestStore(
      ObjectStore objectStore,
      Path repositoryPath,
      Path indexCursorPath,
      String repositoryName,
      Clock clock,
      IoConsumer<String> afterManifestCas) {
    this(
        objectStore,
        objectStore,
        repositoryPath,
        indexCursorPath,
        repositoryName,
        clock,
        afterManifestCas,
        new ManifestCache(),
        repositoryName);
  }

  /**
   * @param objectStore holds the repository's immutable pack, index, reftable and log objects
   * @param manifestObjects holds {@code manifest.pb}; kept apart so that all manifests share one
   *     listable prefix
   */
  ManifestStore(
      ObjectStore objectStore,
      ObjectStore manifestObjects,
      Path repositoryPath,
      Path indexCursorPath,
      String repositoryName,
      Clock clock,
      IoConsumer<String> afterManifestCas,
      ManifestCache cache,
      String cacheKey) {
    this.objectStore = objectStore;
    this.manifestObjects = manifestObjects;
    this.repositoryPath = repositoryPath.toAbsolutePath().normalize();
    this.indexCursorPath = indexCursorPath.toAbsolutePath().normalize();
    this.repositoryName = repositoryName;
    this.clock = clock;
    this.afterManifestCas = afterManifestCas;
    this.cache = cache;
    this.cacheKey = cacheKey;
    stagingPath = this.repositoryPath.resolve(STAGING_DIRECTORY);
    walPath = this.repositoryPath.resolve(WAL_DIRECTORY);
  }

  /** Conditionally re-reads the manifest and reports whether the repository exists. */
  boolean exists() throws IOException {
    return refreshIfPresent().isPresent();
  }

  boolean create() throws IOException {
    createCacheDirectories();
    long now = clock.millis();
    Manifest manifest =
        Manifest.newBuilder()
            .setFormatVersion(FORMAT_VERSION)
            .setRepo(repositoryName)
            .setObjectFormat(OBJECT_FORMAT)
            .setUpdatedAtEpochMillis(now)
            .setWriter(writerIdentity())
            .build();
    try {
      ObjectStore.StoredObject stored =
          manifestObjects.putIfAbsent(MANIFEST_FILE, manifest.toByteArray());
      cache.offer(cacheKey, new VersionedManifest(manifest, stored.version()));
      return true;
    } catch (ObjectAlreadyExistsException exception) {
      return false;
    }
  }

  /** Same as {@link #refresh()}; kept for callers that want the "read from the store" wording. */
  Manifest read() throws IOException {
    return refresh();
  }

  /**
   * Re-reads the manifest from the object store, conditionally on the newest version this node has
   * observed, and returns the newest manifest known afterwards.
   */
  Manifest refresh() throws IOException {
    return refreshVersioned().orElseThrow(this::notFound).manifest();
  }

  Optional<Manifest> refreshIfPresent() throws IOException {
    return refreshVersioned().map(VersionedManifest::manifest);
  }

  /** The newest manifest this node has observed; reads from the store only if nothing is cached. */
  Manifest current() throws IOException {
    return currentVersioned().manifest();
  }

  /** One conditional read; the result carries the version the store reported for it. */
  VersionedManifest refreshVersionedManifest() throws IOException {
    return refreshVersioned().orElseThrow(this::notFound);
  }

  /**
   * The cached manifest when this node already holds exactly {@code version}, as reported by a
   * listing; otherwise one conditional read. The result carries its own version so a caller can
   * record precisely which manifest it acted on.
   */
  VersionedManifest currentOrRefresh(String version) throws IOException {
    VersionedManifest known = cache.get(cacheKey);
    if (known != null && known.version().equals(version)) {
      return known;
    }
    return refreshVersionedManifest();
  }

  List<LogEntry> readLogEntriesAfter(long sequence, Manifest manifest) throws IOException {
    if (sequence < 0 || sequence > manifest.getHeadSeq()) {
      throw new IOException(
          "Invalid WAL cursor " + sequence + " for head " + manifest.getHeadSeq());
    }
    List<LogRef> segments = new ArrayList<>(manifest.getLogSegmentsList());
    segments.sort(Comparator.comparingLong(LogRef::getFirstSeq));
    List<LogEntry> entries = new ArrayList<>();
    long expected = sequence + 1;
    for (LogRef segment : segments) {
      if (segment.getLastSeq() <= sequence) {
        continue;
      }
      if (segment.getFirstSeq() != segment.getLastSeq()) {
        throw new IOException("Batched WAL segments are not supported yet: " + segment.getKey());
      }
      ObjectStore.StoredObject stored =
          objectStore
              .get(segment.getKey())
              .orElseThrow(() -> new IOException("WAL segment not found: " + segment.getKey()));
      LogEntry entry = LogEntry.parseFrom(stored.bytes());
      if (entry.getSeq() != segment.getFirstSeq()) {
        throw new IOException(
            "WAL segment sequence mismatch for "
                + segment.getKey()
                + ": expected "
                + segment.getFirstSeq()
                + " but found "
                + entry.getSeq());
      }
      if (entry.getSeq() != expected) {
        throw new IOException(
            "WAL gap for "
                + repositoryName
                + ": expected sequence "
                + expected
                + " but found "
                + entry.getSeq());
      }
      entries.add(entry);
      expected++;
    }
    if (expected != manifest.getHeadSeq() + 1) {
      throw new IOException(
          "WAL gap for "
              + repositoryName
              + ": expected through "
              + manifest.getHeadSeq()
              + " but reached "
              + (expected - 1));
    }
    return entries;
  }

  String logKeyForSequence(Manifest manifest, long sequence) throws IOException {
    if (sequence == 0) {
      return "";
    }
    return manifest.getLogSegmentsList().stream()
        .filter(segment -> segment.getFirstSeq() <= sequence && segment.getLastSeq() >= sequence)
        .map(LogRef::getKey)
        .findFirst()
        .orElseThrow(
            () ->
                new IOException(
                    "Manifest has no WAL segment for sequence "
                        + sequence
                        + " in "
                        + repositoryName));
  }

  Manifest publish(
      long expectedRefRevision,
      Collection<PackRef> additions,
      Collection<String> supersedes,
      boolean requireExactRefRevision)
      throws IOException {
    return publish(
        expectedRefRevision,
        additions,
        supersedes,
        requireExactRefRevision,
        null);
  }

  Manifest publish(
      long expectedRefRevision,
      Collection<PackRef> additions,
      Collection<String> supersedes,
      boolean requireExactRefRevision,
      RefTransaction refTransaction)
      throws IOException {
    createCacheDirectories();
    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      // The first attempt is optimistic against the manifest this node already observed; the
      // conditional write rejects a stale version, and every retry re-reads conditionally.
      VersionedManifest versioned =
          attempt == 0 ? currentVersioned() : refreshVersioned().orElseThrow(this::notFound);
      Manifest current = versioned.manifest();
      if (requireExactRefRevision && current.getRefRevision() != expectedRefRevision) {
        throw new ManifestConflictException(expectedRefRevision, current.getRefRevision());
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
          throw new IOException("Cannot supersede a pack that is no longer live: " + superseded);
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
      if (refTransaction != null) {
        entry.setRefTransaction(refTransaction);
      }
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
      objectStore.putIfAbsent(logKey, logBytes);

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
              .setRefRevision(current.getRefRevision() + (changesRefs ? 1 : 0))
              .setUpdatedAtEpochMillis(now)
              .setWriter(writer)
              .build();

      try {
        ObjectStore.StoredObject stored =
            manifestObjects.compareAndSwap(
                MANIFEST_FILE, versioned.version(), updated.toByteArray());
        cache.offer(cacheKey, new VersionedManifest(updated, stored.version()));
        afterManifestCas.accept(logKey);
        return updated;
      } catch (ObjectStoreConflictException conflict) {
        // An object-only publication may merge a concurrent ref/object append.
        // A ref publication retries only while ref_revision remains unchanged.
      } catch (IOException ambiguous) {
        try {
          Manifest fresh = refresh();
          if (containsLog(fresh, logKey)) {
            return fresh;
          }
        } catch (IOException verificationFailure) {
          ambiguous.addSuppressed(verificationFailure);
        }
        throw ambiguous;
      }
    }
    throw new IOException("Manifest CAS did not converge after " + MAX_CAS_ATTEMPTS + " attempts");
  }

  Path stagingFile(String fileName) throws IOException {
    createCacheDirectories();
    return stagingPath.resolve(fileName);
  }

  Path immutableFile(String fileName) throws IOException {
    createCacheDirectories();
    Path target = walPath.resolve(fileName);
    if (!Files.isRegularFile(target)) {
      objectStore.download(WAL_DIRECTORY + "/" + fileName, target);
    }
    return target;
  }

  void publishImmutableFile(String fileName) throws IOException {
    createCacheDirectories();
    Path source = stagingPath.resolve(fileName);
    String key = WAL_DIRECTORY + "/" + fileName;
    objectStore.uploadIfAbsent(key, source);

    Path target = walPath.resolve(fileName);
    if (Files.isRegularFile(target)) {
      Files.deleteIfExists(source);
      return;
    }
    moveAtomic(source, target);
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

  Path indexCursorPath() {
    return indexCursorPath;
  }

  private VersionedManifest currentVersioned() throws IOException {
    VersionedManifest known = cache.get(cacheKey);
    if (known != null) {
      return known;
    }
    return refreshVersioned().orElseThrow(this::notFound);
  }

  private Optional<VersionedManifest> refreshVersioned() throws IOException {
    VersionedManifest known = cache.get(cacheKey);
    ConditionalRead read =
        manifestObjects.getIfChanged(MANIFEST_FILE, known == null ? null : known.version());
    return switch (read.state()) {
      case UNCHANGED -> {
        if (known == null) {
          throw new IOException(
              "Object store reported an unchanged manifest without a known version: "
                  + repositoryName);
        }
        yield Optional.of(known);
      }
      case ABSENT -> {
        cache.evict(cacheKey);
        yield Optional.empty();
      }
      case CHANGED -> {
        Manifest manifest = Manifest.parseFrom(read.object().bytes());
        validate(manifest);
        yield Optional.of(
            cache.offer(cacheKey, new VersionedManifest(manifest, read.object().version())));
      }
    };
  }

  private IOException notFound() {
    return new IOException("Manifest not found: " + repositoryName);
  }

  private void createCacheDirectories() throws IOException {
    Files.createDirectories(repositoryPath);
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

  private static boolean containsLog(Manifest manifest, String logKey) {
    return manifest.getLogSegmentsList().stream()
        .anyMatch(segment -> segment.getKey().equals(logKey));
  }

  private static LogEntry.Kind entryKind(
      Collection<String> supersedes, boolean logicalRefUpdate) {
    if (logicalRefUpdate) {
      return LogEntry.Kind.REF_UPDATE;
    }
    return supersedes.isEmpty() ? LogEntry.Kind.PACK : LogEntry.Kind.COMPACT;
  }

  private static boolean changesRefs(
      Collection<PackRef> additions, Collection<String> supersedes, Manifest current) {
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

  private static void moveAtomic(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      throw new IOException(
          "Cache filesystem does not support atomic materialization: " + target, exception);
    }
  }

  private static void forceDirectory(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException ignored) {
      // Some filesystems do not expose directory fsync through FileChannel.
    }
  }

  private static String writerIdentity() {
    String host = System.getenv().getOrDefault("HOSTNAME", "localhost");
    return host + ":" + ProcessHandle.current().pid();
  }

  @FunctionalInterface
  interface IoConsumer<T> {
    void accept(T value) throws IOException;
  }
}
