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

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.Catalog.Binding;
import dev.walgerrit.IndexEventApplier.NamespaceChange;
import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays committed WAL ref transactions into this node's derived Gerrit indexes and caches.
 *
 * <p>Each sweep is one paginated listing of the manifests prefix, which yields every repository
 * with the current version of its manifest. A repository is replayed only when that version differs
 * from the one this node last caught up to, so an unchanged repository costs nothing beyond its
 * share of the listing. A peer's wake-up (see {@link GossipEndpoint}) replays one repository ahead
 * of the sweep, on the same thread and through the same {@link #catchUp} path. One thread replays
 * at a time. Catalog replay blocks repository replay, and a rebuild blocks both.
 *
 * <p>Before a change lookup, {@link #awaitIndexed} waits for other nodes' repository writes to be
 * indexed, subject to a timeout; see {@link IndexBarrier}.
 *
 * <p>A cursor that cannot be advanced by replay, because it is too far behind, ahead of a
 * rolled-back head, or names a transaction the manifest no longer does, makes the node rebuild all
 * of its indexes from current repository state and reseed every cursor, the way a new node with an
 * empty volume bootstraps. That happens before the node becomes ready.
 *
 * <p>Repositories are replayed by id and indexed under the name the catalog binds the id to now,
 * never the name an entry was written under. The catalog's own transactions are replayed too: the
 * names they touched are handed to the applier with what the catalog says about them now, so every
 * node converges on the current namespace, whatever the order of the transitions it observed.
 */
@Singleton
final class IndexEventTailer implements LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(IndexEventTailer.class);

  private final WalGitRepositoryManager repositories;
  private final IndexEventApplier applier;
  private final EventReplayer replayer;

  /**
   * Whether a log entry's writer is another node; this node's own events were fired here already.
   */
  private volatile Predicate<String> foreignWriter =
      writer -> !ManifestStore.writtenOnThisHost(writer);

  private final GerritRuntime runtime;
  private final String indexType;
  private final Config serverConfig;
  private final IndexEventReadiness readiness;
  private final IndexRebuilder rebuilder;

  /** Manifest version at which this node last confirmed each repository's cursor was at head. */
  private final Map<RepositoryId, String> caughtUpVersions = new ConcurrentHashMap<>();

  /** Threads that read the store and replay for the requests waiting in {@link #awaitIndexed}. */
  private static final int BARRIER_THREADS = 16;

  /** Own publications remembered per repository ahead of its cursor. */
  private static final int OWN_SEQUENCES_REMEMBERED = 4096;

  /** What {@link #awaitIndexed} found or did. */
  enum Indexed {
    /** The index already held every entry other nodes wrote. */
    NOT_BEHIND,
    /** The index was behind, and a replay caught it up while the caller waited. */
    REPLAYED,
    /** The allowed time ran out first. */
    TIMED_OUT,
    /** Reading the store or replaying failed. */
    FAILED,
    /** Nothing was awaited. */
    SKIPPED
  }

  private record Head(RepositoryId id, long sequence, boolean indexed) {}

  /** A replay on another thread did not end within {@link #replayLockWait}. */
  private static final class ReplayBusyException extends IOException {
    private static final long serialVersionUID = 1L;

    ReplayBusyException(String message) {
      super(message);
    }
  }

  /** Sequence of the last log entry applied per repository; what its cursor file holds. */
  private final Map<RepositoryId, Long> appliedSequences = new ConcurrentHashMap<>();

  /**
   * Sequences this node published and its cursor has not passed, per repository. The request that
   * published one indexes its own work, so a lookup on this node need not wait for their replay.
   * Each set is guarded by its own monitor.
   */
  private final Map<RepositoryId, NavigableSet<Long>> ownSequences = new ConcurrentHashMap<>();

  /** Replays running for waiting requests, at most one per repository. */
  private final Map<RepositoryId, CompletableFuture<Void>> requestedReplays =
      new ConcurrentHashMap<>();

  private volatile ExecutorService barrierPool;
  private volatile boolean rebuilding;

  /** Longest a replay or a rebuild waits for the replays other threads are running. */
  private volatile Duration replayLockWait = Duration.ofMinutes(1);

  /**
   * Held by the thread that replays or seeds cursors. Entries of different repositories can index
   * the same change, so replays of different repositories do not run side by side either.
   */
  private final ReentrantLock replayLock = new ReentrantLock();

  /**
   * Blocks repository replay during catalog replay, so entries use reconciled repository names,
   * and every replay during a rebuild.
   */
  private final ReadWriteLock namespaceLock = new ReentrantReadWriteLock();

  /** Repositories a peer's wake-up queued for replay that the tailer thread has not started. */
  private final Set<RepositoryId> pendingWakeUps = ConcurrentHashMap.newKeySet();

  private final AtomicLong wakeUpsReplayed = new AtomicLong();
  private volatile ScheduledExecutorService executor;
  private boolean participating;

  @Inject
  IndexEventTailer(
      GitRepositoryManager repositories,
      GerritIndexEventApplier applier,
      GerritRuntime runtime,
      IndexConfig indexConfig,
      @GerritServerConfig Config serverConfig,
      IndexEventReadiness readiness,
      GerritIndexRebuilder rebuilder,
      GerritEventReplayer replayer,
      GossipEndpoint gossip) {
    this(
        asWalGit(repositories),
        (IndexEventApplier) applier,
        runtime,
        indexConfig.type(),
        serverConfig,
        readiness,
        rebuilder,
        replayer);
    gossip.onHint((id, hint) -> wake(id, hint.getManifestVersion()));
  }

  IndexEventTailer(
      WalGitRepositoryManager repositories, IndexEventApplier applier, GerritRuntime runtime) {
    this(
        repositories,
        applier,
        runtime,
        "lucene",
        new Config(),
        new IndexEventReadiness(repositories.configuration().indexCursorPath().resolve("READY")),
        null);
  }

  IndexEventTailer(
      WalGitRepositoryManager repositories,
      IndexEventApplier applier,
      GerritRuntime runtime,
      String indexType,
      Config serverConfig,
      IndexEventReadiness readiness,
      IndexRebuilder rebuilder) {
    this(
        repositories,
        applier,
        runtime,
        indexType,
        serverConfig,
        readiness,
        rebuilder,
        EventReplayer.NONE);
  }

  IndexEventTailer(
      WalGitRepositoryManager repositories,
      IndexEventApplier applier,
      GerritRuntime runtime,
      String indexType,
      Config serverConfig,
      IndexEventReadiness readiness,
      IndexRebuilder rebuilder,
      EventReplayer replayer) {
    this.repositories = repositories;
    this.applier = applier;
    this.replayer = replayer;
    this.runtime = runtime;
    this.indexType = indexType;
    this.serverConfig = serverConfig;
    this.readiness = readiness;
    this.rebuilder = rebuilder;
    repositories.storage().onPublication(this::notePublished);
  }

  @Override
  public synchronized void start() {
    WalGitConfiguration configuration = repositories.configuration();
    if (runtime != GerritRuntime.DAEMON || !configuration.indexTailerEnabled()) {
      return;
    }
    participating = true;
    try {
      readiness.beginStartup();
      validateIndexDurability(indexType, serverConfig);
      runOnce();

      long pollMillis = Math.max(1, configuration.indexPollInterval().toMillis());
      executor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "WalGerrit-Index-Events");
                thread.setDaemon(true);
                return thread;
              });
      executor.scheduleWithFixedDelay(
          this::runBackgroundSweep, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
      barrierPool = newBarrierPool();
      readiness.markReady();
      logger.info(
          "WalGerrit index-event tailer is ready after synchronous catch-up; poll interval {}, "
              + "marker {}",
          configuration.indexPollInterval(),
          readiness.markerPath());
    } catch (IOException | RuntimeException exception) {
      if (executor != null) {
        executor.shutdownNow();
        executor = null;
      }
      if (barrierPool != null) {
        barrierPool.shutdownNow();
        barrierPool = null;
      }
      participating = false;
      readiness.markNotReady();
      throw new IllegalStateException(
          "WalGerrit initial index-event catch-up failed; refusing to become ready", exception);
    }
  }

  @Override
  public void stop() {
    ScheduledExecutorService runningExecutor;
    ExecutorService runningBarrierPool;
    synchronized (this) {
      if (!participating) {
        return;
      }
      participating = false;
      readiness.markNotReady();
      runningExecutor = executor;
      executor = null;
      runningBarrierPool = barrierPool;
      barrierPool = null;
    }
    if (runningBarrierPool != null) {
      // Running reads and replays finish; they are interrupted only if they outlast the wait below.
      runningBarrierPool.shutdown();
    }
    if (runningExecutor == null) {
      return;
    }
    runningExecutor.shutdownNow();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean stopped = runningExecutor.awaitTermination(10, TimeUnit.SECONDS);
      if (runningBarrierPool != null
          && !runningBarrierPool.awaitTermination(
              Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
        runningBarrierPool.shutdownNow();
        stopped = false;
      }
      if (!stopped) {
        logger.warn("WalGerrit index-event tailer did not stop within 10 seconds");
      }
    } catch (InterruptedException interrupted) {
      if (runningBarrierPool != null) {
        runningBarrierPool.shutdownNow();
      }
      Thread.currentThread().interrupt();
    }
  }

  private static ExecutorService newBarrierPool() {
    AtomicInteger threads = new AtomicInteger();
    // No queue: requests skip the wait when all threads are busy.
    return new ThreadPoolExecutor(
        0,
        BARRIER_THREADS,
        30,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        runnable -> {
          Thread thread =
              new Thread(runnable, "WalGerrit-Index-Barrier-" + threads.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        });
  }

  Duration indexBarrierTimeout() {
    return repositories.configuration().indexBarrierTimeout();
  }

  /**
   * Waits up to {@code timeout} for this node's indexes to hold the log entries other nodes
   * published to {@code project}'s repository before the call. Entries this node published are
   * not waited for.
   *
   * <p>The store is read and the log replayed on the barrier's threads. Callers for one repository
   * share a replay, and a replay continues when its callers time out. Never throws.
   */
  Indexed awaitIndexed(Project.NameKey project, Duration timeout) {
    ExecutorService pool = barrierPool;
    if (pool == null || rebuilding) {
      return Indexed.SKIPPED;
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      Future<Head> reading = pool.submit(() -> head(project));
      Head head = reading.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
      if (head == null) {
        return Indexed.SKIPPED;
      }
      if (head.indexed()) {
        return Indexed.NOT_BEHIND;
      }
      // A replay already running may have read the store before this caller did; the one after
      // it has not.
      for (int attempt = 0; attempt < 2; attempt++) {
        CompletableFuture<Void> replay =
            requestedReplays.computeIfAbsent(head.id(), id -> startReplay(pool, id));
        if (replay == null) {
          return Indexed.SKIPPED;
        }
        replay.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        // From memory only: this thread reads neither the store nor the cursor file.
        Long applied = appliedSequences.get(head.id());
        if (applied != null && indexed(head.id(), applied, head.sequence())) {
          return Indexed.REPLAYED;
        }
      }
      // Replay made no progress: the name is in flight, or the catalog is being replayed.
      return Indexed.SKIPPED;
    } catch (RejectedExecutionException busy) {
      return Indexed.SKIPPED;
    } catch (TimeoutException late) {
      return Indexed.TIMED_OUT;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Indexed.SKIPPED;
    } catch (ExecutionException | RuntimeException failure) {
      Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
      if (cause instanceof IndexRebuildRequiredException) {
        return Indexed.SKIPPED;
      }
      logger.warn(
          "WalGerrit index barrier failed for {}; the request continues: {}",
          project.get(),
          String.valueOf(cause));
      return Indexed.FAILED;
    }
  }

  /**
   * The head of the repository {@code project} is active under; null when it has none, or when
   * this node has not replayed the catalog it knows.
   */
  private Head head(Project.NameKey project) throws IOException {
    Optional<Binding> binding = repositories.catalog().resolve(project, false);
    if (binding.filter(Binding::active).isEmpty()) {
      return null;
    }
    // The catalog this node read the binding from; cached, since opening a repository refreshes it.
    long catalog =
        repositories.storage().manifestStore(RepositoryId.CATALOG).current().getHeadSeq();
    if (appliedSequence(RepositoryId.CATALOG) < catalog) {
      // Until the catalog, including this node's entries, is replayed, the index may hold the
      // repository under another name.
      wake(RepositoryId.CATALOG, null);
      return null;
    }
    RepositoryId id = binding.get().id();
    long sequence =
        repositories.storage().manifestStore(id).refreshVersionedManifest().manifest().getHeadSeq();
    return new Head(id, sequence, indexed(id, sequence));
  }

  /**
   * Whether the indexes hold every entry up to {@code sequence} that another node wrote: the
   * cursor reached it, or this node published every entry between the cursor and it.
   */
  private boolean indexed(RepositoryId id, long sequence) throws IOException {
    return indexed(id, appliedSequence(id), sequence);
  }

  private boolean indexed(RepositoryId id, long applied, long sequence) {
    if (applied >= sequence) {
      return true;
    }
    NavigableSet<Long> own = ownSequences.get(id);
    if (own == null) {
      return false;
    }
    synchronized (own) {
      return own.subSet(applied, false, sequence, true).size() == sequence - applied;
    }
  }

  private long appliedSequence(RepositoryId id) throws IOException {
    Long known = appliedSequences.get(id);
    if (known != null) {
      return known;
    }
    long stored =
        new IndexCursorStore(repositories.storage().manifestStore(id).indexCursorPath())
            .read()
            .getSequence();
    // Keep any later sequence recorded by replay.
    Long raced = appliedSequences.putIfAbsent(id, stored);
    return raced == null ? stored : raced;
  }

  /** Records that the cursor of {@code id} is at {@code sequence}; the replay lock is held. */
  private void noteApplied(RepositoryId id, long sequence) {
    appliedSequences.put(id, sequence);
    NavigableSet<Long> own = ownSequences.get(id);
    if (own != null) {
      synchronized (own) {
        own.headSet(sequence, true).clear();
      }
    }
  }

  /** Remembers the log entry this node just published as its own. */
  private void notePublished(RepositoryId id, VersionedManifest published) {
    Manifest manifest = published.manifest();
    // After a lost response the manifest handed over can have a head another node wrote. Nothing
    // is remembered while the tailer is not running.
    if (barrierPool == null || foreignWriter.test(manifest.getWriter())) {
      return;
    }
    NavigableSet<Long> own = ownSequences.computeIfAbsent(id, ignored -> new TreeSet<>());
    synchronized (own) {
      own.add(manifest.getHeadSeq());
      if (own.size() > OWN_SEQUENCES_REMEMBERED) {
        own.pollFirst();
      }
    }
  }

  /** Replays {@code id} unless the catalog is being replayed; requests do not wait for that. */
  private void replayUnlessCatalogIs(RepositoryId id) throws IOException {
    Lock namespace = namespaceLock.readLock();
    try {
      if (!namespace.tryLock(0, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted before replaying " + id, interrupted);
    }
    try {
      replayExclusively(id, null);
    } finally {
      namespace.unlock();
    }
  }

  /** Replays {@code id} on a barrier thread; null when none is free. */
  private CompletableFuture<Void> startReplay(ExecutorService pool, RepositoryId id) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    try {
      pool.execute(
          () -> {
            Throwable failure = null;
            try {
              replayUnlessCatalogIs(id);
            } catch (IOException | RuntimeException thrown) {
              failure = thrown;
            } catch (Error fatal) {
              failure = fatal;
              throw fatal;
            } finally {
              // Remove the replay before allowing waiters to start another.
              requestedReplays.remove(id, done);
              if (failure == null) {
                done.complete(null);
              } else {
                done.completeExceptionally(failure);
              }
            }
          });
    } catch (RejectedExecutionException busy) {
      return null;
    }
    return done;
  }

  /**
   * One full sweep. If any repository's cursor cannot be advanced by replay, the node's indexes are
   * rebuilt and every cursor reseeded, then the sweep runs once more to replay whatever was
   * published during the rebuild.
   */
  void runOnce() throws IOException {
    Map<RepositoryId, IndexRebuildRequiredException> stale = sweep();
    if (stale.isEmpty()) {
      return;
    }
    rebuildIndexes(stale);
    Map<RepositoryId, IndexRebuildRequiredException> stillStale = sweep();
    if (!stillStale.isEmpty()) {
      IOException failure =
          new IOException(
              "Index cursors still cannot be replayed after rebuilding: " + stillStale.keySet());
      stillStale.values().forEach(failure::addSuppressed);
      throw failure;
    }
  }

  private Map<RepositoryId, IndexRebuildRequiredException> sweep() throws IOException {
    NavigableMap<RepositoryId, String> heads = repositories.storage().listManifestVersions();
    caughtUpVersions.keySet().retainAll(heads.keySet());
    appliedSequences.keySet().retainAll(heads.keySet());
    ownSequences.keySet().retainAll(heads.keySet());
    Map<RepositoryId, IndexRebuildRequiredException> stale = new LinkedHashMap<>();
    Exception firstFailure = null;
    // The catalog first: a repository's entries are indexed under the name it has now, and that
    // name may be what the catalog's own entries in this sweep established.
    List<Map.Entry<RepositoryId, String>> ordered = new ArrayList<>(heads.entrySet());
    ordered.sort((a, b) -> Boolean.compare(!a.getKey().isCatalog(), !b.getKey().isCatalog()));
    for (Map.Entry<RepositoryId, String> head : ordered) {
      RepositoryId id = head.getKey();
      if (head.getValue().equals(caughtUpVersions.get(id))) {
        // Nothing to replay, but the listing just confirmed the node's view of this manifest.
        repositories.storage().manifestStore(id).noteListedVersion(head.getValue());
        continue;
      }
      try {
        catchUp(id, head.getValue());
      } catch (IndexRebuildRequiredException rebuildRequired) {
        stale.put(id, rebuildRequired);
      } catch (ReplayBusyException busy) {
        // Every other repository would wait as long.
        throw busy;
      } catch (IOException | RuntimeException exception) {
        logger.error("WalGerrit index-event replay failed for {}", id, exception);
        if (firstFailure == null) {
          firstFailure = exception;
        } else {
          firstFailure.addSuppressed(exception);
        }
      }
    }
    if (firstFailure instanceof IOException ioException) {
      throw ioException;
    }
    if (firstFailure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    return stale;
  }

  /** Replays the repository {@code project} is bound to; for tests. */
  int catchUp(Project.NameKey project) throws IOException {
    Binding binding =
        repositories
            .catalog()
            .resolve(project, true)
            .orElseThrow(() -> new IOException("No project " + project.get()));
    return catchUp(binding.id(), null);
  }

  int catchUp(RepositoryId id) throws IOException {
    return catchUp(id, null);
  }

  /**
   * Replays this repository's unseen WAL entries. With the version a listing reported, the manifest
   * is taken from the node cache when it already holds that version; otherwise, and always without
   * a listed version, one conditional read is made.
   *
   * <p>Entries are indexed under the name the catalog binds the id to now. An id whose name is in
   * flight is left where it is and retried, since its entries would be indexed under a name that is
   * about to change; one with no active name has its entries acknowledged without indexing, the
   * catalog's own replay having removed or replaced its documents.
   *
   * @throws IndexRebuildRequiredException when the cursor cannot be advanced by replay
   */
  int catchUp(RepositoryId id, String listedVersion) throws IOException {
    Lock namespace = id.isCatalog() ? namespaceLock.writeLock() : namespaceLock.readLock();
    lockForReplay(namespace, "replaying " + id);
    try {
      return replayExclusively(id, listedVersion);
    } finally {
      namespace.unlock();
    }
  }

  /** Replays {@code id} while no other thread does; the caller holds {@link #namespaceLock}. */
  private int replayExclusively(RepositoryId id, String listedVersion) throws IOException {
    ReentrantLock replaying = lockForReplay(id);
    try {
      return replay(id, listedVersion);
    } finally {
      replaying.unlock();
    }
  }

  private ReentrantLock lockForReplay(RepositoryId id) throws IOException {
    lockForReplay(replayLock, "replaying " + id);
    return replayLock;
  }

  /** Waits up to {@link #replayLockWait} for {@code lock}; fails when it is not free by then. */
  private void lockForReplay(Lock lock, String work) throws IOException {
    Duration wait = replayLockWait;
    try {
      if (!lock.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new ReplayBusyException(
            "Waited " + wait.toMillis() + " ms for replays on other threads before " + work);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted before " + work, interrupted);
    }
  }

  private int replay(RepositoryId id, String listedVersion) throws IOException {
    ManifestStore manifestStore = repositories.storage().manifestStore(id);
    IndexCursorStore cursorStore = new IndexCursorStore(manifestStore.indexCursorPath());
    IndexCursor cursor = cursorStore.read();
    noteApplied(id, cursor.getSequence());
    if (listedVersion != null
        && !cursor.getManifestVersion().isEmpty()
        && cursor.getManifestVersion().equals(listedVersion)) {
      // The cursor reached the head of exactly the manifest the listing reports: nothing was
      // published since, so there is nothing to read or replay. This is what lets a restarted
      // node's first sweep cost one listing rather than one read per repository.
      manifestStore.noteListedVersion(listedVersion);
      caughtUpVersions.put(id, listedVersion);
      return 0;
    }
    VersionedManifest versioned =
        listedVersion == null
            ? manifestStore.refreshVersionedManifest()
            : manifestStore.currentOrRefresh(listedVersion);
    Manifest manifest = versioned.manifest();

    List<LogEntry> entries =
        manifestStore.readLogEntriesAfter(
            cursor.getSequence(),
            cursor.getTransactionId(),
            manifest,
            repositories.configuration().indexReplayLimit());
    Project.NameKey project = null;
    if (id.isCatalog()) {
      applyCatalog(entries);
    } else if (!entries.isEmpty()) {
      Optional<Binding> binding = repositories.catalog().bindingOf(id, false);
      if (binding.isPresent() && binding.get().pending()) {
        logger.info(
            "WalGerrit index-event replay of {} waits: {} {}",
            id,
            binding.get().name().get(),
            Namespace.describe(binding.get()));
        return 0;
      }
      project = binding.filter(Binding::active).map(Binding::name).orElse(null);
    }
    Set<Integer> reindexedByRefs = changesTouched(entries);
    int applied = 0;
    int replayed = 0;
    int reindexed = 0;
    for (LogEntry entry : entries) {
      boolean foreign = foreignWriter.test(entry.getWriter());
      if (project != null && entry.getKind() == LogEntry.Kind.REF_UPDATE) {
        if (!entry.hasRefTransaction()) {
          throw new IOException(
              "REF_UPDATE WAL entry "
                  + entry.getSeq()
                  + " for "
                  + project.get()
                  + " has no ref transaction payload");
        }
        applier.apply(project, entry.getRefTransaction());
        applied++;
      }
      if (project != null && foreign && entry.getEventJsonCount() > 0) {
        replayer.replay(project, entry);
        replayed++;
      }
      if (project != null && foreign && entry.hasIndexUpdate()) {
        applier.reindex(project, entry.getIndexUpdate(), reindexedByRefs);
        reindexed++;
      }
      cursorStore.write(entry.getSeq(), entry.getTransactionId());
      noteApplied(id, entry.getSeq());
    }
    if (!entries.isEmpty()) {
      logger.info(
          "WalGerrit index-event replay advanced {} ({}) from {} to {} ({} ref transactions; {}"
              + " event entries and {} index updates from other nodes)",
          id,
          id.isCatalog() ? "the catalog" : project == null ? "no active name" : project.get(),
          cursor.getSequence(),
          manifest.getHeadSeq(),
          applied,
          replayed,
          reindexed);
    }
    if (!entries.isEmpty() || !versioned.version().equals(cursor.getManifestVersion())) {
      // At the head now; remember which manifest version that is.
      cursorStore.write(
          manifest.getHeadSeq(), manifest.getHeadTransactionId(), versioned.version());
    }
    // Record the version of the manifest that was actually replayed, never a newer one another
    // handle may have cached meanwhile: the next listing must not skip entries this node has not
    // applied.
    caughtUpVersions.put(id, versioned.version());
    return applied;
  }

  /**
   * The catalog's ref transactions name the commits that changed bindings; the names those commits
   * touched are reconciled against what the catalog says now, in one call, so a node that missed
   * intermediate transitions still converges.
   */
  private void applyCatalog(List<LogEntry> entries) throws IOException {
    Catalog catalog = repositories.catalog();
    Set<Project.NameKey> touched = new TreeSet<>();
    for (LogEntry entry : entries) {
      if (entry.getKind() != LogEntry.Kind.REF_UPDATE) {
        continue;
      }
      for (RefUpdate update : entry.getRefTransaction().getUpdatesList()) {
        if (!update.getName().equals(Catalog.REF) || !update.getNewSymbolicTarget().isEmpty()) {
          continue;
        }
        touched.addAll(
            catalog.changedNames(
                org.eclipse.jgit.lib.ObjectId.fromString(update.getOldObjectId()),
                org.eclipse.jgit.lib.ObjectId.fromString(update.getNewObjectId())));
      }
    }
    if (touched.isEmpty()) {
      return;
    }
    Catalog.Snapshot now = catalog.snapshot(true);
    List<NamespaceChange> changes = new ArrayList<>();
    for (Project.NameKey name : touched) {
      Binding binding = now.byName().get(name);
      if (binding != null) {
        changes.add(new NamespaceChange(name, binding.id(), binding.state(), binding.movedTo()));
      }
    }
    applier.namespaceChanged(changes);
  }

  /**
   * The changes the ref transactions among {@code entries} reindex on every node. An index update
   * naming them in the same sweep is redundant.
   */
  private static Set<Integer> changesTouched(List<LogEntry> entries) {
    Set<Integer> changes = new HashSet<>();
    for (LogEntry entry : entries) {
      if (entry.getKind() != LogEntry.Kind.REF_UPDATE) {
        continue;
      }
      for (RefUpdate update : entry.getRefTransaction().getUpdatesList()) {
        Change.Id id = Change.Id.fromRef(update.getName());
        if (id == null) {
          id = Change.Id.fromAllUsersRef(update.getName());
        }
        if (id != null) {
          changes.add(id.get());
        }
      }
    }
    return changes;
  }

  /**
   * Rebuilds every index from current repository state and reseeds every cursor at the head each
   * repository had before the rebuild began, so anything published meanwhile is replayed
   * afterwards.
   */
  private void rebuildIndexes(Map<RepositoryId, IndexRebuildRequiredException> stale)
      throws IOException {
    WalGitConfiguration configuration = repositories.configuration();
    if (rebuilder == null || !configuration.indexRebuildOnStaleCursor()) {
      IOException failure =
          new IOException(
              "Index cursors cannot be replayed for "
                  + stale.keySet()
                  + " and automatic rebuild is "
                  + (rebuilder == null ? "unavailable" : "disabled")
                  + "; run the offline reindex, remove the cursors under "
                  + configuration.indexCursorPath()
                  + ", and start again");
      stale.values().forEach(failure::addSuppressed);
      throw failure;
    }
    logger.warn(
        "WalGerrit is rebuilding this node's indexes from current repository state because the "
            + "cursors of {} cannot be replayed: {}",
        stale.keySet(),
        stale.values().stream().map(Throwable::getMessage).toList());
    synchronized (this) {
      if (participating) {
        readiness.markNotReady();
      }
    }
    rebuilding = true;
    try {
      // No replay runs during a rebuild: one that started earlier finishes first.
      Lock namespace = namespaceLock.writeLock();
      lockForReplay(namespace, "rebuilding the indexes");
      try {
        rebuildAndSeed();
      } finally {
        namespace.unlock();
      }
    } finally {
      rebuilding = false;
    }
  }

  private void rebuildAndSeed() throws IOException {
    Map<RepositoryId, IndexCursor> seeds = new LinkedHashMap<>();
    for (Map.Entry<RepositoryId, String> head :
        repositories.storage().listManifestVersions().entrySet()) {
      ManifestStore manifestStore = repositories.storage().manifestStore(head.getKey());
      VersionedManifest versioned = manifestStore.currentOrRefresh(head.getValue());
      seeds.put(
          head.getKey(),
          IndexCursor.newBuilder()
              .setSequence(versioned.manifest().getHeadSeq())
              .setTransactionId(versioned.manifest().getHeadTransactionId())
              .setManifestVersion(versioned.version())
              .build());
    }

    long started = System.nanoTime();
    rebuilder.rebuildAll();

    for (Map.Entry<RepositoryId, IndexCursor> seed : seeds.entrySet()) {
      ReentrantLock replaying = lockForReplay(seed.getKey());
      try {
        new IndexCursorStore(repositories.storage().manifestStore(seed.getKey()).indexCursorPath())
            .write(
                seed.getValue().getSequence(),
                seed.getValue().getTransactionId(),
                seed.getValue().getManifestVersion());
        noteApplied(seed.getKey(), seed.getValue().getSequence());
      } finally {
        replaying.unlock();
      }
    }
    caughtUpVersions.clear();
    logger.info(
        "WalGerrit rebuilt this node's indexes in {} s and seeded cursors for {} repositories",
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started),
        seeds.size());
  }

  /** Test hook: sets how long replay and rebuild wait for a lock. */
  void replayLockWait(Duration wait) {
    this.replayLockWait = wait;
  }

  /** Test hook: decide by writer identity which entries came from another node. */
  void foreignWriter(Predicate<String> foreignWriter) {
    this.foreignWriter = foreignWriter;
  }

  /**
   * A peer's wake-up: replays {@code project} on the tailer thread now rather than at the next
   * sweep. {@code version} is the manifest version the peer published, or null or empty when
   * unknown. Wake-ups for a repository coalesce while one is queued; one that arrives during the
   * replay queues another, since that replay may have read an older manifest. Before start and
   * after stop, wake-ups are ignored and the sweep covers the repository.
   */
  void wake(RepositoryId id, String version) {
    ScheduledExecutorService running = executor;
    if (running == null) {
      return;
    }
    String announced = version == null || version.isEmpty() ? null : version;
    if (announced != null && announced.equals(caughtUpVersions.get(id))) {
      return; // This node already replayed exactly that manifest.
    }
    if (!pendingWakeUps.add(id)) {
      return;
    }
    try {
      running.execute(() -> replayWakeUp(id));
    } catch (RejectedExecutionException stopped) {
      pendingWakeUps.remove(id);
    }
  }

  /** Repositories replayed because a peer asked, rather than found by a sweep. */
  long wakeUpsReplayed() {
    return wakeUpsReplayed.get();
  }

  private void replayWakeUp(RepositoryId id) {
    pendingWakeUps.remove(id);
    try {
      // A queued hint may have coalesced newer publications. Read the current store state.
      catchUp(id);
      wakeUpsReplayed.incrementAndGet();
    } catch (IndexRebuildRequiredException rebuildRequired) {
      // Whether to rebuild is the sweep's decision; it will find the same cursor.
      logger.warn(
          "WalGerrit wake-up for {} cannot be replayed; leaving it to the next sweep: {}",
          id,
          rebuildRequired.getMessage());
    } catch (IOException | RuntimeException failure) {
      logger.warn(
          "WalGerrit wake-up replay failed for {}; the next sweep retries: {}",
          id,
          failure.toString());
    }
  }

  void runBackgroundSweep() {
    try {
      runOnce();
      synchronized (this) {
        if (participating) {
          readiness.markReady();
        }
      }
    } catch (Throwable failure) {
      synchronized (this) {
        if (participating) {
          readiness.markNotReady();
        }
      }
      logger.error("WalGerrit index-event sweep failed", failure);
      if (failure instanceof Error error) {
        throw error;
      }
    }
  }

  static void validateIndexDurability(String indexType, Config config) {
    if (!"lucene".equalsIgnoreCase(indexType)) {
      throw new IllegalStateException(
          "WalGerrit durable index cursors currently require Lucene, found " + indexType);
    }
    for (String name :
        List.of("accounts", "changes_open", "changes_closed", "groups", "projects")) {
      long commitWithinMillis =
          config.getTimeUnit(
              "index", name, "commitWithin", TimeUnit.MINUTES.toMillis(5), TimeUnit.MILLISECONDS);
      if (commitWithinMillis != 0) {
        throw new IllegalStateException(
            "WalGerrit requires index."
                + name
                + ".commitWithin = 0 before durable WAL index replay can start");
      }
    }
  }

  private static WalGitRepositoryManager asWalGit(GitRepositoryManager repositories) {
    if (repositories instanceof WalGitRepositoryManager walGit) {
      return walGit;
    }
    throw new IllegalStateException(
        "WalGitIndexModule requires WalGitRepositoryManager, found "
            + repositories.getClass().getName());
  }
}
