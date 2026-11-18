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
import dev.walgerrit.proto.StorageProto.LogPosition;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.OwedMerge;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
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
 * <p>Before a change lookup, {@link #awaitIndexed} waits for other nodes' writes to one repository
 * to be indexed, and before a topic is submitted {@link #awaitAllIndexed} waits for their writes to
 * every repository. Both are bounded, and both say when they could not get there; see {@link
 * IndexBarrier}.
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
 *
 * <p>A submit fires its change-merged events after it committed, so a process that ends in between
 * leaves merges nobody was told about. The tailer keeps, in each repository's cursor file, the
 * merges that this node's ref transactions made and whose event has not appeared in the log, and
 * fires the events of those whose process has ended; see {@link #fireOwed}.
 */
@Singleton
final class IndexEventTailer implements LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(IndexEventTailer.class);

  /** Merges kept per repository while their event is not in the log. */
  static final int OWED_MERGES_KEPT = 10_000;

  private final WalGitRepositoryManager repositories;
  private final IndexEventApplier applier;
  private final EventReplayer replayer;
  private volatile MergeEvents mergeEvents = MergeEvents.NONE;

  /**
   * What tells the entries this process wrote from those of an earlier process of this node, whose
   * events nobody fires any more.
   */
  private volatile String process = ManifestStore.WRITER_PROCESS;

  /** Repositories whose cursor file holds a merge that is due; see {@link #fireOwed}. */
  private final Set<RepositoryId> owedRepositories = ConcurrentHashMap.newKeySet();

  /** Repositories among them that wait for the delivery thread. */
  private final Set<RepositoryId> queuedDeliveries = ConcurrentHashMap.newKeySet();

  /** The one thread that fires owed events; null until everything that hears events runs. */
  private volatile ExecutorService owedEventDelivery;

  /** Set when delivery ends: the journal is about to stop, nothing more is fired. */
  private volatile boolean owedEventDeliveryStopped;

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

  /** Threads that read the store for the requests waiting in {@link #awaitIndexed}. */
  private static final int BARRIER_READ_THREADS = 16;

  /**
   * Threads that replay for waiting requests. One replay runs at a time on a node, so the others
   * wait for it; they are apart from the reading threads so that a long replay does not keep a
   * request whose repository is not behind from finding that out.
   */
  private static final int BARRIER_REPLAY_THREADS = 4;

  /** Reads or replays that may wait for a barrier thread; a request beyond that is turned away. */
  private static final int BARRIER_QUEUE = 256;

  /** Own publications remembered per repository ahead of its cursor. */
  private static final int OWN_SEQUENCES_REMEMBERED = 4096;

  /** What {@link #awaitIndexed} or {@link #awaitAllIndexed} found or did. */
  enum Indexed {
    /** The index already held every entry other nodes wrote. */
    NOT_BEHIND(true),
    /** The index was behind, and a replay caught it up while the caller waited. */
    REPLAYED(true),
    /** This node does not tail the log, so it has no other node's writes to wait for. */
    DISABLED(true),
    /** The allowed time ran out first. */
    TIMED_OUT(false),
    /** Reading the store or replaying failed. */
    FAILED(false),
    /**
     * The node cannot replay now, or cannot say what its indexes hold: it is catching up after its
     * start, stopping or rebuilding its indexes, a rebuild began during the wait or the last one
     * failed, a repository's name is in flight, a cursor needs a rebuild, or more requests wait
     * than the barrier takes.
     */
    UNAVAILABLE(false);

    private final boolean fresh;

    Indexed(boolean fresh) {
      this.fresh = fresh;
    }

    /** Whether the caller may decide on the index: it holds what other nodes wrote. */
    boolean fresh() {
      return fresh;
    }
  }

  /**
   * What a request waits for: {@code id}'s cursor at {@code sequence}. The catalog comes first when
   * it is behind.
   */
  private record Head(RepositoryId id, long sequence, boolean indexed) {
    /** The name has no repository: there is nothing to index. */
    static final Head NO_REPOSITORY = new Head(null, 0, true);

    /** The name is being created or deleted; its entries cannot be indexed yet. */
    static final Head NAME_IN_FLIGHT = new Head(null, 0, false);
  }

  /**
   * What a replay did. With {@code nameInFlight} it applied nothing, because the repository's name
   * is changing; {@code changesWaiting} then says whether the entries it left touch a change.
   */
  private record Replay(int applied, boolean nameInFlight, boolean changesWaiting) {}

  /** The threads behind the barrier. */
  private record BarrierPools(ExecutorService reads, ExecutorService replays) {
    void shutdown() {
      reads.shutdown();
      replays.shutdown();
    }

    void shutdownNow() {
      reads.shutdownNow();
      replays.shutdownNow();
    }

    boolean awaitTermination(long deadlineNanos) throws InterruptedException {
      return reads.awaitTermination(
              Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
          && replays.awaitTermination(
              Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
  }

  /** A replay on another thread did not end within {@link #replayLockWait}. */
  private static final class ReplayBusyException extends IOException {
    private static final long serialVersionUID = 1L;

    ReplayBusyException(String message) {
      super(message);
    }
  }

  /** A repository's unseen entries cannot be indexed while its name is in flight. */
  private static final class NameInFlightException extends IOException {
    private static final long serialVersionUID = 1L;

    NameInFlightException(String message) {
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

  /** Guards the passes over every repository that {@link #awaitAllIndexed} shares. */
  private final Object allLock = new Object();

  /** The pass that is running; true once it replayed anything. Guarded by {@link #allLock}. */
  private CompletableFuture<Boolean> runningAll;

  /** The pass that starts when the running one ends. Guarded by {@link #allLock}. */
  private CompletableFuture<Boolean> queuedAll;

  private volatile BarrierPools barrierPools;
  private volatile boolean rebuilding;

  /**
   * Set from the moment a rebuild is about to delete the indexes' documents until a rebuild has
   * refilled them. After a rebuild that failed, the cursors still say what the indexes held before
   * it, so nothing is decided on them until a later rebuild completes.
   */
  private volatile boolean indexesIncomplete;

  /**
   * Rebuilds begun. A wait that began under another count than it ends under does not know what the
   * indexes hold: its answer may predate the rebuild.
   */
  private final AtomicLong rebuildsBegun = new AtomicLong();

  /** Set once a tailer that was running is stopped: its index no longer follows the log. */
  private volatile boolean stopped;

  /** Set from {@link #start} until the first clean sweep; see {@link #catchingUp()}. */
  private volatile boolean catchingUp;

  /** Released by the first clean sweep after {@link #start}. */
  private final CountDownLatch startupCatchUp = new CountDownLatch(1);

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

  /**
   * A change that a replayed entry named while this node's change index did not hold it: the
   * repository whose cursor lists it, and when this process first knew of it.
   */
  private record Unindexed(RepositoryId listedBy, long sinceNanos) {}

  /** The changes the cursors list as unindexed; see {@link #retryUnindexed}. */
  private final Map<Integer, Unindexed> unindexedChanges = new ConcurrentHashMap<>();

  /** Repositories whose cursor this process has read for the changes it lists as unindexed. */
  private final Set<RepositoryId> unindexedRead = ConcurrentHashMap.newKeySet();

  /** Repositories whose cursor lists a change that {@link #unindexedChanges} no longer holds. */
  private final Set<RepositoryId> unindexedOutdated = ConcurrentHashMap.newKeySet();

  /**
   * How long a listed change is looked for. An index write that a request of this node began
   * before the entry was replayed is searchable long before this has passed.
   */
  private volatile Duration unindexedGiveUp = Duration.ofHours(1);

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
      GerritMergeEvents mergeEvents,
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
    this.mergeEvents = mergeEvents;
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
    if (!tailing()) {
      return;
    }
    validateIndexDurability(indexType, serverConfig);
    try {
      readiness.beginStartup();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "WalGerrit cannot remove the readiness marker " + readiness.markerPath(), exception);
    }
    participating = true;
    catchingUp = true;
    long pollMillis = Math.max(1, configuration.indexPollInterval().toMillis());
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "WalGerrit-Index-Events");
              thread.setDaemon(true);
              return thread;
            });
    barrierPools =
        new BarrierPools(
            newBarrierPool("WalGerrit-Index-Barrier-Read-", BARRIER_READ_THREADS),
            newBarrierPool("WalGerrit-Index-Barrier-Replay-", BARRIER_REPLAY_THREADS));
    executor.scheduleWithFixedDelay(this::scheduledSweep, 0, pollMillis, TimeUnit.MILLISECONDS);
    logger.info(
        "WalGerrit index-event tailer started its startup catch-up; this node answers no request"
            + " and is not ready until it completes");
  }

  /**
   * Whether this node is still replaying what was published while it was away: from {@link #start}
   * until its first clean sweep. Its indexes are behind until then, so it must not answer requests;
   * {@link StartupGate} is what keeps them out.
   */
  boolean catchingUp() {
    return catchingUp;
  }

  /** Waits up to {@code timeout} for the startup catch-up; true once it has completed. */
  boolean awaitStartupCatchUp(Duration timeout) throws InterruptedException {
    return startupCatchUp.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void stop() {
    ScheduledExecutorService runningExecutor;
    BarrierPools runningBarrierPools;
    synchronized (this) {
      if (!participating) {
        return;
      }
      participating = false;
      stopped = true;
      readiness.markNotReady();
      runningExecutor = executor;
      executor = null;
      runningBarrierPools = barrierPools;
      barrierPools = null;
    }
    stopOwedEventDelivery();
    if (runningBarrierPools != null) {
      // Running reads and replays finish; they are interrupted only if they outlast the wait below.
      runningBarrierPools.shutdown();
    }
    if (runningExecutor == null) {
      return;
    }
    runningExecutor.shutdownNow();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean ended = runningExecutor.awaitTermination(10, TimeUnit.SECONDS);
      if (runningBarrierPools != null && !runningBarrierPools.awaitTermination(deadline)) {
        runningBarrierPools.shutdownNow();
        ended = false;
      }
      if (!ended) {
        logger.warn("WalGerrit index-event tailer did not stop within 10 seconds");
      }
    } catch (InterruptedException interrupted) {
      if (runningBarrierPools != null) {
        runningBarrierPools.shutdownNow();
      }
      Thread.currentThread().interrupt();
    }
  }

  /**
   * A pool whose work waits for a thread, up to {@link #BARRIER_QUEUE} deep. The request that
   * handed the work over waits no longer than its own timeout, whatever the queue holds.
   */
  private static ExecutorService newBarrierPool(String threadPrefix, int size) {
    AtomicInteger threads = new AtomicInteger();
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            size,
            size,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(BARRIER_QUEUE),
            runnable -> {
              Thread thread = new Thread(runnable, threadPrefix + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  Duration indexBarrierTimeout() {
    return repositories.configuration().indexBarrierTimeout();
  }

  /** Whether this node is configured to tail the log, whether or not it has started. */
  boolean tailing() {
    return runtime == GerritRuntime.DAEMON && repositories.configuration().indexTailerEnabled();
  }

  /**
   * Waits up to {@code timeout} for this node's indexes to hold the log entries other nodes
   * published to {@code project}'s repository before the call. Entries this node published are not
   * waited for. When the catalog this node has read is ahead of the one it has replayed, the
   * catalog is replayed first.
   *
   * <p>The store is read and the log replayed on the barrier's threads. Callers for one repository
   * share a replay, and a replay continues when its callers time out. Never throws; an outcome that
   * is not {@linkplain Indexed#fresh() fresh} means the index may lack another node's write.
   */
  Indexed awaitIndexed(Project.NameKey project, Duration timeout) {
    long rebuilds = rebuildsBegun.get();
    return unlessRebuilt(rebuilds, awaitReplayed(project, timeout));
  }

  private Indexed awaitReplayed(Project.NameKey project, Duration timeout) {
    BarrierPools pools = barrierPools;
    if (pools == null) {
      return stopped ? Indexed.UNAVAILABLE : Indexed.DISABLED;
    }
    if (catchingUp || indexesInDoubt() || replayLock.isHeldByCurrentThread()) {
      // The startup catch-up outlasts any request. A replay cannot wait for another one, nor
      // for itself.
      return Indexed.UNAVAILABLE;
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    Future<Head> reading = null;
    try {
      boolean replayed = false;
      // The catalog when it is behind, then the repository. A catalog that moved twice meanwhile
      // is not chased further.
      for (int pass = 0; pass < 3; pass++) {
        reading = pools.reads().submit(() -> head(project));
        Head head = reading.get(remaining(deadline), TimeUnit.NANOSECONDS);
        if (head == Head.NAME_IN_FLIGHT) {
          return Indexed.UNAVAILABLE;
        }
        if (head.indexed()) {
          return replayed ? Indexed.REPLAYED : Indexed.NOT_BEHIND;
        }
        if (!replayTo(pools, head, deadline)) {
          // Replay made no progress: the repository's name is in flight.
          return Indexed.UNAVAILABLE;
        }
        replayed = true;
        if (!head.id().isCatalog()) {
          return Indexed.REPLAYED;
        }
      }
      return Indexed.UNAVAILABLE;
    } catch (TimeoutException late) {
      // A read still waiting for a thread is of no use to anyone else.
      reading.cancel(false);
      return Indexed.TIMED_OUT;
    } catch (InterruptedException | ExecutionException | RuntimeException failure) {
      return failed(failure, project.get());
    }
  }

  /**
   * Waits up to {@code timeout} for this node's indexes to hold the log entries other nodes
   * published to any repository before the call. This is what a decision spanning repositories
   * needs, such as which changes a topic holds: a member this node has not indexed is in a
   * repository the members it has indexed do not lead to.
   *
   * <p>One listing of the manifests is the boundary: it names every repository with the version of
   * its manifest, and every publication acknowledged before the listing began is in it. The catalog
   * is replayed first, then each repository whose listed version this node has not caught up to,
   * unless this node published every entry it is missing. Publications that land after the listing
   * began may or may not be replayed.
   *
   * <p>Callers share passes. One that arrives while a pass runs waits for the next, since the
   * running pass may have listed the store before the caller arrived; however many callers wait,
   * one pass follows another. A pass continues when its callers time out. Never throws; see {@link
   * #awaitIndexed} for the outcomes.
   */
  Indexed awaitAllIndexed(Duration timeout) {
    long rebuilds = rebuildsBegun.get();
    return unlessRebuilt(rebuilds, awaitAllReplayed(timeout));
  }

  private Indexed awaitAllReplayed(Duration timeout) {
    BarrierPools pools = barrierPools;
    if (pools == null) {
      return stopped ? Indexed.UNAVAILABLE : Indexed.DISABLED;
    }
    if (catchingUp || indexesInDoubt() || replayLock.isHeldByCurrentThread()) {
      return Indexed.UNAVAILABLE;
    }
    try {
      CompletableFuture<Boolean> pass;
      synchronized (allLock) {
        if (runningAll == null) {
          pass = new CompletableFuture<>();
          startAll(pools, pass);
          runningAll = pass;
        } else {
          if (queuedAll == null) {
            queuedAll = new CompletableFuture<>();
          }
          pass = queuedAll;
        }
      }
      return pass.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
          ? Indexed.REPLAYED
          : Indexed.NOT_BEHIND;
    } catch (TimeoutException late) {
      return Indexed.TIMED_OUT;
    } catch (InterruptedException | ExecutionException | RuntimeException failure) {
      return failed(failure, "all projects");
    }
  }

  /** Whether a rebuild is running, or the last one failed and none has completed since. */
  private boolean indexesInDoubt() {
    return rebuilding || indexesIncomplete;
  }

  /**
   * {@code outcome}, unless a rebuild began since the caller counted {@code rebuilds}: what the
   * wait found out about the indexes may then describe them as they were before it.
   */
  private Indexed unlessRebuilt(long rebuilds, Indexed outcome) {
    if (outcome == Indexed.DISABLED || !outcome.fresh()) {
      return outcome;
    }
    return indexesInDoubt() || rebuildsBegun.get() != rebuilds ? Indexed.UNAVAILABLE : outcome;
  }

  private static long remaining(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  /** The outcome for a wait that ended with {@code failure}. */
  private Indexed failed(Exception failure, String scope) {
    if (failure instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return Indexed.UNAVAILABLE;
    }
    Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
    if (cause instanceof RejectedExecutionException
        || cause instanceof IndexRebuildRequiredException
        || cause instanceof ReplayBusyException
        || cause instanceof NameInFlightException) {
      logger.warn("WalGerrit index barrier cannot replay for {} now: {}", scope, cause.toString());
      return Indexed.UNAVAILABLE;
    }
    logger.warn("WalGerrit index barrier failed for {}: {}", scope, String.valueOf(cause));
    return Indexed.FAILED;
  }

  /**
   * What a request for {@code project} waits for: the catalog while this node has not replayed the
   * one it knows, otherwise the head of the repository the name is active under.
   */
  private Head head(Project.NameKey project) throws IOException {
    Catalog catalog = repositories.catalog();
    Optional<Binding> binding = catalog.resolve(project, false);
    if (binding.filter(Binding::active).isEmpty()) {
      // This node's view of the catalog may predate what another node did to the name.
      binding = catalog.resolve(project, true);
    }
    if (binding.isEmpty() || binding.get().retired()) {
      return Head.NO_REPOSITORY;
    }
    if (binding.get().pending()) {
      return Head.NAME_IN_FLIGHT;
    }
    // The catalog this node read the binding from; cached, since opening a repository refreshes it.
    long catalogHead =
        repositories.storage().manifestStore(RepositoryId.CATALOG).current().getHeadSeq();
    if (appliedSequence(RepositoryId.CATALOG) < catalogHead) {
      // Until the catalog, including this node's entries, is replayed, the index may hold the
      // repository under another name.
      return new Head(RepositoryId.CATALOG, catalogHead, false);
    }
    RepositoryId id = binding.get().id();
    long sequence =
        repositories.storage().manifestStore(id).refreshVersionedManifest().manifest().getHeadSeq();
    return new Head(id, sequence, indexed(id, sequence));
  }

  /**
   * Replays {@code head}'s repository until the indexes hold its entries up to {@code head}'s
   * sequence. False when two replays did not get there.
   */
  private boolean replayTo(BarrierPools pools, Head head, long deadline)
      throws InterruptedException, ExecutionException, TimeoutException {
    // A replay already running may have read the store before this caller did; the one after it
    // has not.
    for (int attempt = 0; attempt < 2; attempt++) {
      CompletableFuture<Void> replay =
          requestedReplays.computeIfAbsent(head.id(), id -> startReplay(pools.replays(), id));
      replay.get(remaining(deadline), TimeUnit.NANOSECONDS);
      // From memory only: this thread reads neither the store nor the cursor file.
      Long applied = appliedSequences.get(head.id());
      if (applied == null) {
        continue;
      }
      // The catalog's own entries are waited for too: its replay is what reconciles the documents
      // of the names they touched.
      if (head.id().isCatalog()
          ? applied >= head.sequence()
          : indexed(head.id(), applied, head.sequence())) {
        return true;
      }
    }
    return false;
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
    if (barrierPools == null || foreignWriter.test(manifest.getWriter())) {
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

  /**
   * Replays {@code id} on a barrier thread.
   *
   * @throws RejectedExecutionException when more replays wait than the barrier takes
   */
  private CompletableFuture<Void> startReplay(ExecutorService pool, RepositoryId id) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    pool.execute(
        () -> {
          Throwable failure = null;
          try {
            replayLocked(id, null);
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
    return done;
  }

  /**
   * Runs a pass over every repository on a barrier thread and completes {@code pass} with whether
   * it replayed anything. The pass queued meanwhile, if any, starts when this one ends. The caller
   * holds {@link #allLock}.
   *
   * @throws RejectedExecutionException when more replays wait than the barrier takes
   */
  private void startAll(BarrierPools pools, CompletableFuture<Boolean> pass) {
    pools
        .replays()
        .execute(
            () -> {
              Boolean replayed = null;
              Throwable failure = null;
              try {
                replayed = replayListed();
              } catch (IOException | RuntimeException thrown) {
                failure = thrown;
              } catch (Error fatal) {
                failure = fatal;
                throw fatal;
              } finally {
                synchronized (allLock) {
                  CompletableFuture<Boolean> next = queuedAll;
                  queuedAll = null;
                  runningAll = next;
                  if (next != null) {
                    try {
                      startAll(pools, next);
                    } catch (RejectedExecutionException busy) {
                      runningAll = null;
                      next.completeExceptionally(busy);
                    }
                  }
                }
                if (failure == null) {
                  pass.complete(replayed);
                } else {
                  pass.completeExceptionally(failure);
                }
              }
            });
  }

  /**
   * Lists every repository and replays those behind the listing, the catalog first. True when
   * anything was replayed.
   *
   * <p>The logs of different repositories have no common order, so an entry's work can need what
   * a repository replayed later in the pass provides. A repository whose replay failed is therefore
   * tried once more after the others.
   */
  private boolean replayListed() throws IOException {
    if (indexesInDoubt()) {
      throw new ReplayBusyException(
          "This node's indexes are being rebuilt, or their last rebuild failed");
    }
    List<Map.Entry<RepositoryId, String>> listed =
        new ArrayList<>(repositories.storage().listManifestVersions().entrySet());
    listed.sort((a, b) -> Boolean.compare(!a.getKey().isCatalog(), !b.getKey().isCatalog()));
    boolean replayed = false;
    List<Map.Entry<RepositoryId, String>> failed = new ArrayList<>();
    for (Map.Entry<RepositoryId, String> head : listed) {
      try {
        replayed |= replayListed(head.getKey(), head.getValue());
      } catch (IndexRebuildRequiredException | ReplayBusyException | NameInFlightException wait) {
        // Trying again within this pass would find the same.
        throw wait;
      } catch (IOException | RuntimeException failure) {
        if (head.getKey().isCatalog()) {
          // Every repository is indexed under the name the catalog gives it.
          throw failure;
        }
        logger.info(
            "WalGerrit index barrier will replay {} once more after the other repositories: {}",
            head.getKey(),
            failure.toString());
        failed.add(head);
      }
    }
    for (Map.Entry<RepositoryId, String> head : failed) {
      replayed |= replayListed(head.getKey(), head.getValue());
    }
    return replayed;
  }

  /**
   * Replays {@code id} when this node's indexes may lack an entry another node published before
   * {@code listedVersion}. True when it replayed.
   */
  private boolean replayListed(RepositoryId id, String listedVersion) throws IOException {
    if (listedVersion.equals(caughtUpVersions.get(id))) {
      return false;
    }
    if (!id.isCatalog()) {
      // From the node cache when this node published or already read that version.
      Manifest listed =
          repositories.storage().manifestStore(id).currentOrRefresh(listedVersion).manifest();
      if (indexed(id, listed.getHeadSeq())) {
        // Only this node's entries are ahead of the cursor; the requests that published them
        // indexed their work, and the tailer replays them later as usual.
        return false;
      }
    }
    Replay replay = replayLocked(id, listedVersion);
    if (replay.nameInFlight() && replay.changesWaiting()) {
      throw new NameInFlightException(
          "The name of repository " + id + " is in flight; its changes cannot be indexed yet");
    }
    return !replay.nameInFlight();
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

  /**
   * Lists every repository and replays those whose manifest changed, the catalog first.
   *
   * <p>The logs of different repositories have no common order, so an entry's work can need what
   * a repository this sweep replays later provides, or what was published after the sweep's
   * listing. A repository whose replay failed is therefore {@linkplain #replayWaiting replayed once
   * more} after the others. What fails then fails the sweep. A change that a star or draft names
   * and the index does not hold is not a failure; see {@link #retryUnindexed}.
   */
  private Map<RepositoryId, IndexRebuildRequiredException> sweep() throws IOException {
    NavigableMap<RepositoryId, String> heads = repositories.storage().listManifestVersions();
    caughtUpVersions.keySet().retainAll(heads.keySet());
    appliedSequences.keySet().retainAll(heads.keySet());
    ownSequences.keySet().retainAll(heads.keySet());
    Map<RepositoryId, IndexRebuildRequiredException> stale = new LinkedHashMap<>();
    List<Exception> failures = new ArrayList<>();
    // Repositories to replay once more, each with the head this node knew when its replay failed.
    Map<RepositoryId, Long> waiting = new LinkedHashMap<>();
    for (Map.Entry<RepositoryId, String> head : catalogFirst(heads)) {
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
        Long known = id.isCatalog() ? null : knownHead(id);
        if (known == null) {
          // Every repository is indexed under the name the catalog gives it; replaying the
          // others first changes nothing for the catalog.
          logger.error("WalGerrit index-event replay failed for {}", id, exception);
          failures.add(exception);
        } else {
          logger.info(
              "WalGerrit index-event sweep will replay {} once more after the other"
                  + " repositories: {}",
              id,
              exception.toString());
          waiting.put(id, known);
        }
      }
    }
    if (!waiting.isEmpty()) {
      replayWaiting(waiting, stale, failures);
    }
    try {
      retryUnindexed();
    } catch (ReplayBusyException busy) {
      throw busy;
    } catch (IOException | RuntimeException exception) {
      logger.error(
          "WalGerrit could not index the changes that stars or drafts named before the index held"
              + " them",
          exception);
      failures.add(exception);
    }
    if (!failures.isEmpty()) {
      Exception first = failures.get(0);
      failures.subList(1, failures.size()).forEach(first::addSuppressed);
      if (first instanceof IOException ioException) {
        throw ioException;
      }
      throw (RuntimeException) first;
    }
    return stale;
  }

  /**
   * Replays the repositories whose replay failed in this sweep, after what their entries may depend
   * on.
   *
   * <p>What an entry depends on was published before it, though perhaps after the sweep's listing,
   * so the store is listed once more and every other repository behind that listing is replayed
   * first. Each waiting repository is then replayed up to the head this node knew when its replay
   * failed: an entry beyond it can depend on a publication this second listing does not hold
   * either, and is left to the next wake-up or sweep rather than chased. Cursors move as in any
   * replay, entry by entry and only over entries that were applied.
   */
  private void replayWaiting(
      Map<RepositoryId, Long> waiting,
      Map<RepositoryId, IndexRebuildRequiredException> stale,
      List<Exception> failures)
      throws IOException {
    for (Map.Entry<RepositoryId, String> head :
        catalogFirst(repositories.storage().listManifestVersions())) {
      RepositoryId id = head.getKey();
      if (waiting.containsKey(id) || head.getValue().equals(caughtUpVersions.get(id))) {
        continue;
      }
      try {
        catchUp(id, head.getValue());
      } catch (IndexRebuildRequiredException rebuildRequired) {
        stale.put(id, rebuildRequired);
      } catch (ReplayBusyException busy) {
        throw busy;
      } catch (IOException | RuntimeException exception) {
        logger.error("WalGerrit index-event replay failed for {}", id, exception);
        failures.add(exception);
      }
    }
    for (Map.Entry<RepositoryId, Long> again : waiting.entrySet()) {
      RepositoryId id = again.getKey();
      try {
        replayLocked(id, null, again.getValue());
      } catch (IndexRebuildRequiredException rebuildRequired) {
        stale.put(id, rebuildRequired);
      } catch (ReplayBusyException busy) {
        throw busy;
      } catch (IOException | RuntimeException exception) {
        logger.error(
            "WalGerrit index-event replay failed for {}, also after the other repositories",
            id,
            exception);
        failures.add(exception);
      }
    }
  }

  /** The listed repositories with the catalog ahead of the others. */
  private static List<Map.Entry<RepositoryId, String>> catalogFirst(
      Map<RepositoryId, String> heads) {
    // The catalog first: a repository's entries are indexed under the name it has now, and that
    // name may be what the catalog's own entries in this sweep established.
    List<Map.Entry<RepositoryId, String>> ordered = new ArrayList<>(heads.entrySet());
    ordered.sort((a, b) -> Boolean.compare(!a.getKey().isCatalog(), !b.getKey().isCatalog()));
    return ordered;
  }

  /** The head of {@code id} in the newest manifest this node has read; null when it has none. */
  private Long knownHead(RepositoryId id) {
    try {
      return repositories.storage().manifestStore(id).current().getHeadSeq();
    } catch (IOException | RuntimeException unreadable) {
      return null;
    }
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
   * flight is left where it is and retried, since its entries cannot be indexed under a name that
   * is not active; one with no active name has its entries acknowledged without indexing, the
   * catalog's own replay having removed its documents.
   *
   * @throws IndexRebuildRequiredException when the cursor cannot be advanced by replay
   */
  int catchUp(RepositoryId id, String listedVersion) throws IOException {
    return replayLocked(id, listedVersion).applied();
  }

  /** Replays {@code id} to its head while no other thread replays and the namespace allows it. */
  private Replay replayLocked(RepositoryId id, String listedVersion) throws IOException {
    return replayLocked(id, listedVersion, Long.MAX_VALUE);
  }

  /** As {@link #replayLocked(RepositoryId, String)}, applying no entry beyond {@code upTo}. */
  private Replay replayLocked(RepositoryId id, String listedVersion, long upTo) throws IOException {
    Lock namespace = id.isCatalog() ? namespaceLock.writeLock() : namespaceLock.readLock();
    lockForReplay(namespace, "replaying " + id);
    try {
      ReentrantLock replaying = lockForReplay(id);
      try {
        return replay(id, listedVersion, upTo);
      } finally {
        replaying.unlock();
      }
    } finally {
      namespace.unlock();
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

  /**
   * Replays the entries of {@code id} after its cursor, up to sequence {@code upTo}. A replay that
   * stops short of the head leaves the repository behind: it is not recorded as caught up, and the
   * next replay continues from the cursor.
   */
  private Replay replay(RepositoryId id, String listedVersion, long upTo) throws IOException {
    ManifestStore manifestStore = repositories.storage().manifestStore(id);
    IndexCursorStore cursorStore = new IndexCursorStore(manifestStore.indexCursorPath());
    IndexCursor cursor = cursorStore.read();
    noteApplied(id, cursor.getSequence());
    if (unindexedRead.add(id)) {
      long now = System.nanoTime();
      for (int change : cursor.getUnindexedList()) {
        unindexedChanges.putIfAbsent(change, new Unindexed(id, now));
      }
    }
    boolean tracking = !id.isCatalog() && tracksMerges();
    if (listedVersion != null
        && !cursor.getManifestVersion().isEmpty()
        && cursor.getManifestVersion().equals(listedVersion)
        && !(tracking && cursor.hasMergesReadTo())) {
      // The cursor reached the head of exactly the manifest the listing reports: nothing was
      // published since, so there is nothing to read or replay. This is what lets a restarted
      // node's first sweep cost one listing rather than one read per repository.
      manifestStore.noteListedVersion(listedVersion);
      caughtUpVersions.put(id, listedVersion);
      if (tracking) {
        // What an earlier process of this node left owed is in the file, not in the log.
        noteOwed(id, cursor.getOwedList());
      }
      return new Replay(0, false, false);
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
    boolean toHead = upTo >= manifest.getHeadSeq();
    if (!toHead) {
      entries = entries.stream().filter(entry -> entry.getSeq() <= upTo).toList();
    }
    // Entries that a rebuild or a reindex moved the cursor over, to be read for merges only.
    // The read above found the cursor's entry in this repository's history.
    List<LogEntry> passedOver =
        tracking && cursor.hasMergesReadTo() ? passedOver(id, manifestStore, cursor) : List.of();
    Project.NameKey project = null;
    if (id.isCatalog()) {
      applyCatalog(entries);
    } else if (!entries.isEmpty() || !passedOver.isEmpty()) {
      Optional<Binding> binding = repositories.catalog().bindingOf(id, false);
      if (binding.isPresent() && binding.get().pending()) {
        logger.info(
            "WalGerrit index-event replay of {} waits: {} {}",
            id,
            binding.get().name().get(),
            Namespace.describe(binding.get()));
        return new Replay(0, true, !changesTouched(entries).isEmpty());
      }
      project = binding.filter(Binding::active).map(Binding::name).orElse(null);
    }
    long replayedFrom = cursor.getSequence();
    List<OwedMerge> owed = new ArrayList<>(cursor.getOwedList());
    if (tracking && cursor.hasMergesReadTo()) {
      if (project != null) {
        for (LogEntry entry : passedOver) {
          trackMerges(id, project, entry, owed);
        }
      }
      cursor = cursor.toBuilder().clearMergesReadTo().clearOwed().addAllOwed(owed).build();
      cursorStore.write(cursor);
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
        noteUnindexed(id, applier.applyReporting(project, entry.getRefTransaction()));
        // This repository's cursor is replaced below.
        unindexedOutdated.remove(id);
        writeOutdatedUnindexedLists();
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
      if (tracking && project != null) {
        trackMerges(id, project, entry, owed);
      }
      // One replacement of the file passes the entry and records what it merged or announced,
      // and the changes it named that the index does not hold.
      cursor =
          IndexCursorStore.at(cursor, entry.getSeq(), entry.getTransactionId(), "").toBuilder()
              .clearOwed()
              .addAllOwed(owed)
              .clearUnindexed()
              .addAllUnindexed(unindexedListedBy(id))
              .build();
      cursorStore.write(cursor);
      noteApplied(id, entry.getSeq());
    }
    if (!entries.isEmpty()) {
      logger.info(
          "WalGerrit index-event replay advanced {} ({}) from {} to {} ({} ref transactions; {}"
              + " event entries and {} index updates from other nodes)",
          id,
          id.isCatalog() ? "the catalog" : project == null ? "no active name" : project.get(),
          replayedFrom,
          entries.get(entries.size() - 1).getSeq(),
          applied,
          replayed,
          reindexed);
    }
    if (!toHead) {
      return new Replay(applied, false, false);
    }
    if (!entries.isEmpty() || !versioned.version().equals(cursor.getManifestVersion())) {
      // At the head now; remember which manifest version that is.
      cursorStore.write(
          IndexCursorStore.at(
              cursor, manifest.getHeadSeq(), manifest.getHeadTransactionId(), versioned.version()));
    }
    // Record the version of the manifest that was actually replayed, never a newer one another
    // handle may have cached meanwhile: the next listing must not skip entries this node has not
    // applied.
    caughtUpVersions.put(id, versioned.version());
    if (tracking) {
      // Every entry an earlier process of this node wrote has been read: what it still owes will
      // not appear by itself.
      noteOwed(id, owed);
    }
    return new Replay(applied, false, false);
  }

  /**
   * Lists the changes that an entry of {@code id} named and the index does not hold, and drops
   * those it indexed from its own repository. The caller saves the list of {@code id} with its
   * cursor.
   */
  private void noteUnindexed(RepositoryId id, IndexEventApplier.Applied applied) {
    applied.indexed().forEach(this::forgetUnindexed);
    long now = System.nanoTime();
    for (int change : applied.unindexed()) {
      unindexedChanges.putIfAbsent(change, new Unindexed(id, now));
    }
  }

  private void forgetUnindexed(int change) {
    Unindexed listed = unindexedChanges.remove(change);
    if (listed != null) {
      unindexedOutdated.add(listed.listedBy());
    }
  }

  /** The changes the cursor of {@code id} is to list as unindexed, in order. */
  private List<Integer> unindexedListedBy(RepositoryId id) {
    if (unindexedChanges.isEmpty()) {
      return List.of();
    }
    return unindexedChanges.entrySet().stream()
        .filter(listed -> listed.getValue().listedBy().equals(id))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  /**
   * Indexes the changes that an entry named while the index did not hold them and that the replay
   * of their own repository has not indexed since.
   *
   * <p>A change another node created is indexed when its repository is replayed up to its
   * creation, with its stars and drafts as they are then, and is dropped from the list there. What
   * is left is a change whose document a request of this node was writing when the entry was
   * replayed, or a change that is deleted. The first is in the index soon after and is indexed
   * once more here. The second never is: nothing in this node's indexes says which repository
   * held a change they do not hold, so it is looked for until {@link #unindexedGiveUp} has passed
   * and is then taken to be deleted.
   */
  private void retryUnindexed() throws IOException {
    if (unindexedChanges.isEmpty() && unindexedOutdated.isEmpty()) {
      return;
    }
    Lock namespace = namespaceLock.readLock();
    lockForReplay(namespace, "indexing changes named before the index held them");
    try {
      lockForReplay(replayLock, "indexing changes named before the index held them");
      try {
        long now = System.nanoTime();
        List<Integer> givenUp = new ArrayList<>();
        for (Map.Entry<Integer, Unindexed> listed : unindexedChanges.entrySet()) {
          if (applier.reindexIfIndexed(listed.getKey())) {
            forgetUnindexed(listed.getKey());
          } else if (now - listed.getValue().sinceNanos() >= unindexedGiveUp.toNanos()) {
            forgetUnindexed(listed.getKey());
            givenUp.add(listed.getKey());
          }
        }
        if (!givenUp.isEmpty()) {
          logger.info(
              "WalGerrit stops looking for {} change(s) that stars or drafts name and the index"
                  + " does not hold; they are taken to be deleted: {}",
              givenUp.size(),
              givenUp);
        }
        writeOutdatedUnindexedLists();
      } finally {
        replayLock.unlock();
      }
    } finally {
      namespace.unlock();
    }
  }

  /**
   * Saves the lists that lost a change, so that a process that starts does not index it again.
   * The caller holds {@link #replayLock}.
   */
  private void writeOutdatedUnindexedLists() throws IOException {
    for (RepositoryId id : List.copyOf(unindexedOutdated)) {
      // Removed first: a change dropped from the list meanwhile marks the cursor again.
      unindexedOutdated.remove(id);
      IndexCursorStore cursorStore =
          new IndexCursorStore(repositories.storage().manifestStore(id).indexCursorPath());
      try {
        if (cursorStore.exists()) {
          cursorStore.write(
              cursorStore.read().toBuilder()
                  .clearUnindexed()
                  .addAllUnindexed(unindexedListedBy(id))
                  .build());
        }
      } catch (IOException | RuntimeException failure) {
        unindexedOutdated.add(id);
        throw failure;
      }
    }
  }

  /** Whether this node records the merges it makes: it has events, and a journal to carry them. */
  private boolean tracksMerges() {
    return mergeEvents != MergeEvents.NONE && repositories.configuration().eventJournalEnabled();
  }

  /**
   * The entries between where merges were read to and the cursor. Empty, with an error, when they
   * cannot be read any more: what they merged is then not known to this node.
   */
  private List<LogEntry> passedOver(
      RepositoryId id, ManifestStore manifestStore, IndexCursor cursor) throws IOException {
    LogPosition readTo = cursor.getMergesReadTo();
    if (readTo.getSequence() >= cursor.getSequence()) {
      return List.of();
    }
    try {
      // Up to the cursor, not the head: what was published since is replayed, and counts against
      // the replay limit, by itself.
      return manifestStore.readLogEntriesBetween(
          readTo.getSequence(),
          readTo.getTransactionId(),
          cursor.getSequence(),
          cursor.getTransactionId(),
          repositories.configuration().indexReplayLimit());
    } catch (IndexRebuildRequiredException unreadable) {
      logger.error(
          "WalGerrit cannot read the entries {} to {} of {} that an index rebuild passed over: {}."
              + " A change-merged event that was lost with a process of this node that merged a"
              + " change in them is not sent again",
          readTo.getSequence() + 1,
          cursor.getSequence(),
          id,
          unreadable.getMessage());
      return List.of();
    }
  }

  /**
   * Brings {@code owed} up to date with one entry: adds the changes a ref transaction of this node
   * merged, and removes those whose change-merged event the entry carries, whoever journaled it.
   */
  private void trackMerges(
      RepositoryId id, Project.NameKey project, LogEntry entry, List<OwedMerge> owed)
      throws IOException {
    if (entry.getKind() == LogEntry.Kind.REF_UPDATE
        && entry.hasRefTransaction()
        && !foreignWriter.test(entry.getWriter())) {
      for (OwedMerge merge : mergeEvents.mergedBy(project, entry)) {
        boolean known =
            owed.stream()
                .anyMatch(
                    other ->
                        other.getChange() == merge.getChange()
                            && other.getMetaRevision().equals(merge.getMetaRevision()));
        if (!known) {
          owed.add(merge);
        }
      }
      if (owed.size() > OWED_MERGES_KEPT) {
        int dropped = owed.size() - OWED_MERGES_KEPT;
        owed.subList(0, dropped).clear();
        logger.error(
            "WalGerrit dropped the {} oldest merge(s) of {} whose change-merged event is not in"
                + " the log: more than {} wait for one",
            dropped,
            id,
            OWED_MERGES_KEPT);
      }
    }
    if (entry.getEventJsonCount() > 0 && !owed.isEmpty()) {
      List<OwedMerge> left = mergeEvents.withoutAnnounced(owed, entry);
      if (left.size() != owed.size()) {
        owed.clear();
        owed.addAll(left);
      }
    }
  }

  /** A merge is due when the process that is to fire its event is not this one: it ended. */
  private boolean due(OwedMerge merge) {
    return !merge.getProcess().equals(process);
  }

  /** Remembers whether {@code id} has merges that are due, and has them fired when it does. */
  private void noteOwed(RepositoryId id, List<OwedMerge> owed) {
    if (owed.stream().anyMatch(this::due)) {
      owedRepositories.add(id);
      queueDelivery(id);
    } else {
      owedRepositories.remove(id);
    }
  }

  /**
   * Begins firing owed events. Called once everything that hears events on this node runs, the
   * journal included: an event fired before that reaches neither the plugins nor the log.
   */
  void startOwedEventDelivery() {
    synchronized (this) {
      if (!participating
          || owedEventDelivery != null
          || owedEventDeliveryStopped
          || !tracksMerges()) {
        return;
      }
      owedEventDelivery =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "WalGerrit-Owed-Events");
                thread.setDaemon(true);
                return thread;
              });
    }
    owedRepositories.forEach(this::queueDelivery);
  }

  /**
   * Ends firing owed events, before the journal stops: an event that is being fired is fired to the
   * end, with up to ten seconds for its listeners, and what was not begun stays owed for the next
   * process. Idempotent.
   */
  void stopOwedEventDelivery() {
    ExecutorService delivery;
    synchronized (this) {
      delivery = owedEventDelivery;
      owedEventDelivery = null;
      owedEventDeliveryStopped = true;
    }
    if (delivery == null) {
      return;
    }
    delivery.shutdown();
    try {
      if (!delivery.awaitTermination(10, TimeUnit.SECONDS)) {
        logger.warn(
            "The listeners of a change-merged event that WalGerrit fired for an earlier process"
                + " did not end within 10 seconds; what they fire from now on may miss the"
                + " journal");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void queueDelivery(RepositoryId id) {
    ExecutorService delivery = owedEventDelivery;
    // Listeners of an owed event look changes up, so it waits for the startup catch-up.
    if (delivery == null || catchingUp || !queuedDeliveries.add(id)) {
      return;
    }
    try {
      delivery.execute(
          () -> {
            queuedDeliveries.remove(id);
            try {
              fireOwed(id);
            } catch (IOException | RuntimeException failure) {
              logger.warn(
                  "WalGerrit could not fire the change-merged events owed for {}; it tries again"
                      + " after the next sweep",
                  id,
                  failure);
            }
          });
    } catch (RejectedExecutionException stopping) {
      queuedDeliveries.remove(id);
    }
  }

  /**
   * Fires the change-merged events of the merges of {@code id} that are due: made by a process of
   * this node that ended, with no such event in the log up to the head this node has replayed.
   *
   * <p>The events are fired on the calling thread with no lock of the tailer held and not as a
   * replay: their listeners run as for any event of this node, may write and may wait for this
   * node's indexes, and the journal records the events and whatever the listeners fire. A merge
   * whose event was fired is handed to this process and stays in the file until the journal's entry
   * comes by; a process that ends before that leaves it due again. Delivery is therefore at least
   * once: this node's listeners can hear an event twice, the log holds it once or, when the process
   * ended between two journal writes, twice.
   */
  void fireOwed(RepositoryId id) throws IOException {
    IndexCursorStore cursorStore =
        new IndexCursorStore(repositories.storage().manifestStore(id).indexCursorPath());
    List<OwedMerge> due;
    // Not in the middle of a replay, which may be about to pass the entry that settles a merge.
    replayLock.lock();
    try {
      IndexCursor cursor = cursorStore.read();
      due = cursor.getOwedList().stream().filter(this::due).toList();
      if (due.isEmpty()) {
        owedRepositories.remove(id);
        return;
      }
      if (cursor.hasMergesReadTo() || cursor.getManifestVersion().isEmpty()) {
        // Entries this node has not read for events yet, passed over by a rebuild or beyond a
        // cursor that is short of the head, may hold the event: wait for the replay that reads
        // them rather than call it missing.
        return;
      }
    } finally {
      replayLock.unlock();
    }
    if (indexesInDoubt()) {
      return; // Listeners look changes up; after the next sweep the indexes may hold them again.
    }
    Optional<Binding> binding = repositories.catalog().bindingOf(id, false);
    if (binding.isPresent() && binding.get().pending()) {
      return; // The listeners would look the changes up under a name that is not served.
    }
    Project.NameKey project = binding.filter(Binding::active).map(Binding::name).orElse(null);
    Map<OwedMerge, MergeEvents.Delivery> delivered = new LinkedHashMap<>();
    for (OwedMerge merge : due) {
      if (owedEventDeliveryStopped) {
        break; // The journal is stopping; the next process fires what is left.
      }
      MergeEvents.Delivery delivery;
      if (project == null) {
        delivery = MergeEvents.Delivery.GONE;
      } else {
        try {
          delivery = mergeEvents.fire(project, merge);
        } catch (RuntimeException failure) {
          logger.warn(
              "WalGerrit could not fire the change-merged event owed for change {} of {}; it"
                  + " tries again after the next sweep",
              merge.getChange(),
              project.get(),
              failure);
          continue;
        }
      }
      delivered.put(merge, delivery);
      String name = project == null ? id.toString() : project.get();
      switch (delivery) {
        case FIRED ->
            logger.info(
                "WalGerrit fired the change-merged event of change {} of {} (patch set {}, {}):"
                    + " the process that merged it at sequence {} ended before the event reached"
                    + " the log",
                merge.getChange(),
                name,
                merge.getPatchSet(),
                merge.getNewRevision(),
                merge.getSequence());
        case GONE ->
            logger.info(
                "WalGerrit owes no change-merged event for change {} of {} any more: the change"
                    + " or its project is gone, or the change's history no longer holds the merge"
                    + " at sequence {}",
                merge.getChange(),
                name,
                merge.getSequence());
        case LOST ->
            logger.error(
                "The change-merged event of change {} of {} is lost: the process that merged it"
                    + " at sequence {} ended before the event reached the log, and the event of a"
                    + " change that was not merged by a submit cannot be rebuilt",
                merge.getChange(),
                name,
                merge.getSequence());
      }
    }
    if (delivered.isEmpty()) {
      return;
    }
    // Replays replace the file too.
    replayLock.lock();
    try {
      IndexCursor cursor = cursorStore.read();
      List<OwedMerge> owed = new ArrayList<>();
      for (OwedMerge merge : cursor.getOwedList()) {
        MergeEvents.Delivery delivery = delivered.get(merge);
        if (delivery == null) {
          owed.add(merge);
        } else if (delivery == MergeEvents.Delivery.FIRED) {
          owed.add(merge.toBuilder().setProcess(process).build());
        }
      }
      cursorStore.write(cursor.toBuilder().clearOwed().addAllOwed(owed).build());
      if (owed.stream().noneMatch(this::due)) {
        owedRepositories.remove(id);
      }
    } finally {
      replayLock.unlock();
    }
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
        changes.add(new NamespaceChange(name, binding.id(), binding.state()));
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
    rebuildsBegun.incrementAndGet();
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
    // The rebuild deletes every document first. Should it fail, the cursors on disk and the
    // sequences in memory go on saying what the indexes held before.
    indexesIncomplete = true;
    rebuilder.rebuildAll();
    // A publication of this node that the rebuild deleted from the indexes is replayed like any
    // other from now on; one indexed after this is in indexes the rebuild no longer touches.
    ownSequences.clear();
    unindexedChanges.clear();
    unindexedOutdated.clear();
    indexesIncomplete = false;

    for (Map.Entry<RepositoryId, IndexCursor> seed : seeds.entrySet()) {
      ReentrantLock replaying = lockForReplay(seed.getKey());
      try {
        IndexCursorStore cursorStore =
            new IndexCursorStore(
                repositories.storage().manifestStore(seed.getKey()).indexCursorPath());
        // The entries this passes over are not indexed again, but the next replay reads them
        // for the merges this node made in them.
        cursorStore.write(
            cursorStore.movedTo(
                seed.getValue().getSequence(),
                seed.getValue().getTransactionId(),
                seed.getValue().getManifestVersion()));
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

  /** Test hook: sets how long a change that the index does not hold is looked for. */
  void unindexedGiveUp(Duration giveUp) {
    this.unindexedGiveUp = giveUp;
  }

  /** Test hook: sets how long replay and rebuild wait for a lock. */
  void replayLockWait(Duration wait) {
    this.replayLockWait = wait;
  }

  /** Test hook: what finds merges and fires their events. */
  void mergeEvents(MergeEvents mergeEvents) {
    this.mergeEvents = mergeEvents;
  }

  /** Test hook: the process this tailer runs in, as log entries name it. */
  void process(String process) {
    this.process = process;
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

  /** An executor drops a periodic task that throws, and nothing else would sweep again. */
  private void scheduledSweep() {
    try {
      runBackgroundSweep();
    } catch (Error logged) {
      // The next run tries again.
    }
  }

  void runBackgroundSweep() {
    try {
      runOnce();
      boolean caughtUp = false;
      synchronized (this) {
        if (participating) {
          readiness.markReady();
          caughtUp = catchingUp;
          catchingUp = false;
        }
      }
      if (caughtUp) {
        logger.info(
            "WalGerrit index-event tailer is ready after its startup catch-up; poll interval {},"
                + " marker {}",
            repositories.configuration().indexPollInterval(),
            readiness.markerPath());
        startupCatchUp.countDown();
      }
      // Events that could not be fired when they became due.
      owedRepositories.forEach(this::queueDelivery);
    } catch (Throwable failure) {
      synchronized (this) {
        if (participating) {
          readiness.markNotReady();
        }
      }
      logger.error(
          catchingUp
              ? "WalGerrit startup catch-up failed; this node stays not ready and tries again"
              : "WalGerrit index-event sweep failed",
          failure);
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
