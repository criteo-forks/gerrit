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

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replays committed WAL ref transactions into this node's derived Gerrit indexes and caches.
 *
 * <p>Each sweep is one paginated listing of the manifests prefix, which yields every repository
 * with the current version of its manifest. A repository is replayed only when that version
 * differs from the one this node last caught up to, so an unchanged repository costs nothing
 * beyond its share of the listing, and the sweep interval is the cross-node convergence latency.
 *
 * <p>A cursor that cannot be advanced by replay, because it is too far behind, ahead of a
 * rolled-back head, or names a transaction the manifest no longer does, makes the node rebuild all
 * of its indexes from current repository state and reseed every cursor, the way a new node with an
 * empty volume bootstraps. That happens before the node becomes ready.
 */
@Singleton
final class IndexEventTailer implements LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(IndexEventTailer.class);

  private final WalGitRepositoryManager repositories;
  private final IndexEventApplier applier;
  private final GerritRuntime runtime;
  private final String indexType;
  private final Config serverConfig;
  private final IndexEventReadiness readiness;
  private final IndexRebuilder rebuilder;
  /** Manifest version at which this node last confirmed each repository's cursor was at head. */
  private final Map<Project.NameKey, String> caughtUpVersions = new ConcurrentHashMap<>();
  private ScheduledExecutorService executor;
  private boolean participating;

  @Inject
  IndexEventTailer(
      GitRepositoryManager repositories,
      GerritIndexEventApplier applier,
      GerritRuntime runtime,
      IndexConfig indexConfig,
      @GerritServerConfig Config serverConfig,
      IndexEventReadiness readiness,
      GerritIndexRebuilder rebuilder) {
    this(
        asWalGit(repositories),
        (IndexEventApplier) applier,
        runtime,
        indexConfig.type(),
        serverConfig,
        readiness,
        rebuilder);
  }

  IndexEventTailer(
      WalGitRepositoryManager repositories, IndexEventApplier applier, GerritRuntime runtime) {
    this(
        repositories,
        applier,
        runtime,
        "lucene",
        new Config(),
        new IndexEventReadiness(
            repositories.configuration().indexCursorPath().resolve("READY")),
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
    this.repositories = repositories;
    this.applier = applier;
    this.runtime = runtime;
    this.indexType = indexType;
    this.serverConfig = serverConfig;
    this.readiness = readiness;
    this.rebuilder = rebuilder;
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
      participating = false;
      readiness.markNotReady();
      throw new IllegalStateException(
          "WalGerrit initial index-event catch-up failed; refusing to become ready", exception);
    }
  }

  @Override
  public void stop() {
    ScheduledExecutorService runningExecutor;
    synchronized (this) {
      if (!participating) {
        return;
      }
      participating = false;
      readiness.markNotReady();
      runningExecutor = executor;
      executor = null;
    }
    if (runningExecutor == null) {
      return;
    }
    runningExecutor.shutdownNow();
    try {
      if (!runningExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        logger.warn("WalGerrit index-event tailer did not stop within 10 seconds");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * One full sweep. If any repository's cursor cannot be advanced by replay, the node's indexes
   * are rebuilt and every cursor reseeded, then the sweep runs once more to replay whatever was
   * published during the rebuild.
   */
  void runOnce() throws IOException {
    Map<Project.NameKey, IndexRebuildRequiredException> stale = sweep();
    if (stale.isEmpty()) {
      return;
    }
    rebuildIndexes(stale);
    Map<Project.NameKey, IndexRebuildRequiredException> stillStale = sweep();
    if (!stillStale.isEmpty()) {
      IOException failure =
          new IOException(
              "Index cursors still cannot be replayed after rebuilding: " + stillStale.keySet());
      stillStale.values().forEach(failure::addSuppressed);
      throw failure;
    }
  }

  private Map<Project.NameKey, IndexRebuildRequiredException> sweep() throws IOException {
    NavigableMap<Project.NameKey, String> heads = repositories.storage().listManifestVersions();
    caughtUpVersions.keySet().retainAll(heads.keySet());
    Map<Project.NameKey, IndexRebuildRequiredException> stale = new LinkedHashMap<>();
    Exception firstFailure = null;
    for (Map.Entry<Project.NameKey, String> head : heads.entrySet()) {
      Project.NameKey project = head.getKey();
      if (head.getValue().equals(caughtUpVersions.get(project))) {
        // Nothing to replay, but the listing just confirmed the node's view of this manifest.
        repositories.storage().manifestStore(project).noteListedVersion(head.getValue());
        continue;
      }
      try {
        catchUp(project, head.getValue());
      } catch (IndexRebuildRequiredException rebuildRequired) {
        stale.put(project, rebuildRequired);
      } catch (IOException | RuntimeException exception) {
        logger.error("WalGerrit index-event replay failed for {}", project.get(), exception);
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

  int catchUp(Project.NameKey project) throws IOException {
    return catchUp(project, null);
  }

  /**
   * Replays this repository's unseen WAL entries. With the version a listing reported, the
   * manifest is taken from the node cache when it already holds that version; otherwise, and
   * always without a listed version, one conditional read is made.
   *
   * @throws IndexRebuildRequiredException when the cursor cannot be advanced by replay
   */
  int catchUp(Project.NameKey project, String listedVersion) throws IOException {
    ManifestStore manifestStore = repositories.storage().manifestStore(project);
    IndexCursorStore cursorStore = new IndexCursorStore(manifestStore.indexCursorPath());
    IndexCursor cursor = cursorStore.read();
    if (listedVersion != null
        && !cursor.getManifestVersion().isEmpty()
        && cursor.getManifestVersion().equals(listedVersion)) {
      // The cursor reached the head of exactly the manifest the listing reports: nothing was
      // published since, so there is nothing to read or replay. This is what lets a restarted
      // node's first sweep cost one listing rather than one read per repository.
      manifestStore.noteListedVersion(listedVersion);
      caughtUpVersions.put(project, listedVersion);
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
    int applied = 0;
    for (LogEntry entry : entries) {
      if (entry.getKind() == LogEntry.Kind.REF_UPDATE) {
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
      cursorStore.write(entry.getSeq(), entry.getTransactionId());
    }
    if (!entries.isEmpty()) {
      logger.info(
          "WalGerrit index-event replay advanced {} from {} to {} ({} ref transactions)",
          project.get(),
          cursor.getSequence(),
          manifest.getHeadSeq(),
          applied);
    }
    if (!entries.isEmpty() || !versioned.version().equals(cursor.getManifestVersion())) {
      // At the head now; remember which manifest version that is.
      cursorStore.write(
          manifest.getHeadSeq(), manifest.getHeadTransactionId(), versioned.version());
    }
    // Record the version of the manifest that was actually replayed, never a newer one another
    // handle may have cached meanwhile: the next listing must not skip entries this node has not
    // applied.
    caughtUpVersions.put(project, versioned.version());
    return applied;
  }

  /**
   * Rebuilds every index from current repository state and reseeds every cursor at the head each
   * repository had before the rebuild began, so anything published meanwhile is replayed afterwards.
   */
  private void rebuildIndexes(Map<Project.NameKey, IndexRebuildRequiredException> stale)
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

    Map<Project.NameKey, IndexCursor> seeds = new LinkedHashMap<>();
    for (Map.Entry<Project.NameKey, String> head :
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

    for (Map.Entry<Project.NameKey, IndexCursor> seed : seeds.entrySet()) {
      new IndexCursorStore(repositories.storage().manifestStore(seed.getKey()).indexCursorPath())
          .write(
              seed.getValue().getSequence(),
              seed.getValue().getTransactionId(),
              seed.getValue().getManifestVersion());
    }
    caughtUpVersions.clear();
    logger.info(
        "WalGerrit rebuilt this node's indexes in {} s and seeded cursors for {} repositories",
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started),
        seeds.size());
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
