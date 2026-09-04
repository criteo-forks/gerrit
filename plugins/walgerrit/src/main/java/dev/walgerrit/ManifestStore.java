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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

  private static final int FORMAT_VERSION = 3;
  /** Cached files younger than this are never evicted: they may await their publication. */
  static final java.time.Duration EVICTION_MIN_AGE = java.time.Duration.ofMinutes(10);
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
  private final Consumer<Manifest> afterPublish;
  private final ManifestCache cache;
  private final String cacheKey;
  private final ReentrantLock writeLock;
  private final boolean cacheIsStore;

  ManifestStore(Path repositoryPath, String repositoryName) {
    this(new FileObjectStore(repositoryPath), repositoryPath, repositoryName);
  }

  /** A store of its own for tests and tools: fresh node-wide caches, no publication listener. */
  ManifestStore(ObjectStore objectStore, Path repositoryPath, String repositoryName) {
    this(
        objectStore,
        objectStore,
        repositoryPath,
        repositoryPath.resolve("index-events.cursor"),
        repositoryName,
        Clock.systemUTC(),
        ignored -> {},
        new ManifestCache(),
        new RepositoryLocks(),
        repositoryName,
        objectStore instanceof FileObjectStore files
            && files.root().equals(repositoryPath.toAbsolutePath().normalize()));
  }

  /**
   * @param objectStore holds the repository's immutable pack, index, reftable and log objects
   * @param manifestObjects holds {@code manifest.pb}; kept apart so that all manifests share one
   *     listable prefix
   * @param afterPublish sees every manifest this store publishes, after the CAS; the compactor
   *     evaluates its policy there
   * @param cacheIsStore whether the cache directory is the store itself, so cached files are the
   *     only copies and eviction must leave them alone
   */
  ManifestStore(
      ObjectStore objectStore,
      ObjectStore manifestObjects,
      Path repositoryPath,
      Path indexCursorPath,
      String repositoryName,
      Clock clock,
      Consumer<Manifest> afterPublish,
      ManifestCache cache,
      RepositoryLocks locks,
      String cacheKey,
      boolean cacheIsStore) {
    this.objectStore = objectStore;
    this.manifestObjects = manifestObjects;
    this.repositoryPath = repositoryPath.toAbsolutePath().normalize();
    this.indexCursorPath = indexCursorPath.toAbsolutePath().normalize();
    this.repositoryName = repositoryName;
    this.clock = clock;
    this.afterPublish = afterPublish;
    this.cache = cache;
    this.cacheKey = cacheKey;
    this.writeLock = locks.forRepository(cacheKey);
    this.cacheIsStore = cacheIsStore;
    stagingPath = this.repositoryPath.resolve(STAGING_DIRECTORY);
    walPath = this.repositoryPath.resolve(WAL_DIRECTORY);
  }

  /** This node's write lock for the repository; ref transactions hold it end to end. */
  ReentrantLock writeLock() {
    return writeLock;
  }

  /** Conditionally re-reads the manifest and reports whether the repository exists. */
  boolean exists() throws IOException {
    return refreshIfPresent().isPresent();
  }

  /**
   * Whether the repository exists, answered without a round trip when this node compared its view
   * of the manifest with the store less than {@code maxAge} ago, and with one conditional read
   * otherwise. Only opening a repository with {@code walgerrit.manifestRevalidateOnOpen} off uses
   * this; writers and explicit refreshes always read.
   */
  boolean existsUnlessRecentlyValidated(Duration maxAge) throws IOException {
    if (cache.validatedWithin(cacheKey, maxAge.toMillis(), clock.millis())) {
      return true;
    }
    return exists();
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
      cache.markValidated(cacheKey, clock.millis());
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

  /** The key of the log entry with this sequence and transaction id. */
  static String logKey(long sequence, String transactionId) {
    return String.format("%s/%016x-%s.pb", LOG_DIRECTORY, sequence, transactionId);
  }

  /**
   * Returns the entries after a follower's cursor, oldest first, by walking the chain back from
   * the manifest's head: each entry names the transaction id of the one before it, so every key is
   * known without a listing, and the id the walk arrives at for the cursor's sequence must be the
   * one the cursor recorded. One object is read per entry behind the cursor.
   *
   * @throws IndexRebuildRequiredException when the cursor is ahead of the head (the manifest was
   *     rolled back), names a transaction the chain does not (history diverged), or is more than
   *     {@code limit} entries behind, where rebuilding is cheaper than replaying
   */
  List<LogEntry> readLogEntriesAfter(
      long sequence, String transactionId, Manifest manifest, long limit) throws IOException {
    long head = manifest.getHeadSeq();
    if (sequence > head) {
      throw new IndexRebuildRequiredException(
          repositoryName,
          "cursor " + sequence + " is ahead of head " + head + ", so the manifest was rolled back");
    }
    if (head - sequence > limit) {
      throw new IndexRebuildRequiredException(
          repositoryName,
          "cursor "
              + sequence
              + " is "
              + (head - sequence)
              + " entries behind head "
              + head
              + ", more than the replay limit of "
              + limit);
    }
    Deque<LogEntry> entries = new ArrayDeque<>();
    String expectedId = manifest.getHeadTransactionId();
    for (long seq = head; seq > sequence; seq--) {
      LogEntry entry = readEntry(seq, expectedId);
      entries.addFirst(entry);
      expectedId = entry.getPreviousTransactionId();
    }
    if (!expectedId.equals(transactionId == null ? "" : transactionId)) {
      throw new IndexRebuildRequiredException(
          repositoryName, "history mismatch at sequence " + sequence);
    }
    return List.copyOf(entries);
  }

  private LogEntry readEntry(long sequence, String transactionId) throws IOException {
    String key = logKey(sequence, transactionId);
    ObjectStore.StoredObject stored =
        objectStore.get(key).orElseThrow(() -> new IOException("WAL entry not found: " + key));
    LogEntry entry = LogEntry.parseFrom(stored.bytes());
    if (entry.getSeq() != sequence || !entry.getTransactionId().equals(transactionId)) {
      throw new IOException("WAL entry " + key + " does not describe itself");
    }
    return entry;
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
      String transactionId = UUID.randomUUID().toString();
      Map<String, PackRef> livePacks = new LinkedHashMap<>();
      for (PackRef pack : current.getPacksList()) {
        livePacks.put(pack.getName(), pack);
      }
      for (String superseded : supersedes) {
        if (livePacks.remove(superseded) == null) {
          throw new StaleCompactionInputException(superseded);
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
              .setBaseRevision(current.getRevision())
              .setTransactionId(transactionId)
              .setPreviousTransactionId(current.getHeadTransactionId());
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

      objectStore.putIfAbsent(logKey(sequence, transactionId), entry.build().toByteArray());

      Manifest updated =
          current.toBuilder()
              .setHeadSeq(sequence)
              .setHeadTransactionId(transactionId)
              .clearPacks()
              .addAllPacks(livePacks.values())
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
        afterPublish.accept(updated);
        return updated;
      } catch (ObjectStoreConflictException conflict) {
        // Usually another writer got there first, and the loop merges an object-only publication
        // or lets a ref publication fail on the ref revision. But the HTTP client retries a
        // request whose response was lost, and a retried CAS that had already landed is refused
        // by its own precondition, so check for this attempt before treating it as lost.
        if (transactionLanded(refresh(), sequence, transactionId)) {
          Manifest landed = current();
          afterPublish.accept(landed);
          return landed;
        }
      } catch (IOException ambiguous) {
        try {
          Manifest fresh = refresh();
          if (transactionLanded(fresh, sequence, transactionId)) {
            afterPublish.accept(fresh);
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

  /** Every file name, extension included, that the manifest references beneath {@code wal/}. */
  static java.util.Set<String> liveFileNames(Manifest manifest) {
    java.util.Set<String> names = new java.util.HashSet<>();
    for (PackRef pack : manifest.getPacksList()) {
      for (var file : pack.getFilesList()) {
        names.add(pack.getName() + "." + file.getExtension());
      }
    }
    return names;
  }

  /** Every object beneath {@code wal/}, keyed by file name, with the store's timestamps. */
  List<ObjectStore.ObjectSummary> listWalObjects() throws IOException {
    String prefix = WAL_DIRECTORY + "/";
    List<ObjectStore.ObjectSummary> files = new ArrayList<>();
    for (ObjectStore.ObjectSummary object : objectStore.listWithVersions(prefix)) {
      if (object.key().startsWith(prefix) && object.key().length() > prefix.length()) {
        files.add(
            new ObjectStore.ObjectSummary(
                object.key().substring(prefix.length()),
                object.version(),
                object.lastModifiedEpochMillis()));
      }
    }
    return files;
  }

  void deleteWalObject(String fileName) throws IOException {
    objectStore.delete(WAL_DIRECTORY + "/" + fileName);
  }

  /**
   * Deletes cached files that are not in {@code live}, except files written within the last
   * {@link #EVICTION_MIN_AGE}, which may be uploads whose publication has not landed yet. A handle
   * that still needs an evicted file fetches it again from the store, which keeps it for the
   * reclamation grace period. Does nothing when the cache is the store: there the copy is the only
   * one, and only reclamation, with its grace period, may delete it.
   */
  int evictLocalFilesExcept(java.util.Set<String> live) throws IOException {
    if (cacheIsStore || !Files.isDirectory(walPath)) {
      return 0;
    }
    long youngest = clock.millis() - EVICTION_MIN_AGE.toMillis();
    int evicted = 0;
    try (Stream<Path> files = Files.list(walPath)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        String name = file.getFileName().toString();
        if (live.contains(name) || !Files.isRegularFile(file)) {
          continue;
        }
        if (Files.getLastModifiedTime(file).toMillis() > youngest) {
          continue;
        }
        if (Files.deleteIfExists(file)) {
          evicted++;
        }
      }
    }
    return evicted;
  }

  /**
   * Publishes a file that lives outside this node's cache, such as a pack imported from another
   * repository, without copying it into the cache; readers materialize it on demand.
   */
  void publishExternalFile(String fileName, Path source) throws IOException {
    objectStore.uploadIfAbsent(WAL_DIRECTORY + "/" + fileName, source);
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
        cache.markValidated(cacheKey, clock.millis());
        yield Optional.of(known);
      }
      case ABSENT -> {
        cache.evict(cacheKey);
        yield Optional.empty();
      }
      case CHANGED -> {
        Manifest manifest = Manifest.parseFrom(read.object().bytes());
        validate(manifest);
        VersionedManifest newest =
            cache.offer(cacheKey, new VersionedManifest(manifest, read.object().version()));
        cache.markValidated(cacheKey, clock.millis());
        yield Optional.of(newest);
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

  /**
   * Whether the manifest's history contains this publication attempt: the chain from the head
   * passes through the attempt's transaction id at its sequence. No read when the attempt is the
   * head itself.
   */
  private boolean transactionLanded(Manifest manifest, long sequence, String transactionId)
      throws IOException {
    if (manifest.getHeadSeq() < sequence) {
      return false;
    }
    String id = manifest.getHeadTransactionId();
    for (long seq = manifest.getHeadSeq(); seq > sequence; seq--) {
      id = readEntry(seq, id).getPreviousTransactionId();
    }
    return id.equals(transactionId);
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

  static String writerIdentity() {
    String host = System.getenv().getOrDefault("HOSTNAME", "localhost");
    return host + ":" + ProcessHandle.current().pid();
  }
}
