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
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Replays committed WAL ref transactions into this node's derived Gerrit indexes and caches. */
@Singleton
final class IndexEventTailer implements LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(IndexEventTailer.class);

  private final WalGitRepositoryManager repositories;
  private final IndexEventApplier applier;
  private final GerritRuntime runtime;
  private final String indexType;
  private final Config serverConfig;
  private final IndexEventReadiness readiness;
  private ScheduledExecutorService executor;
  private boolean participating;

  @Inject
  IndexEventTailer(
      GitRepositoryManager repositories,
      GerritIndexEventApplier applier,
      GerritRuntime runtime,
      IndexConfig indexConfig,
      @GerritServerConfig Config serverConfig,
      IndexEventReadiness readiness) {
    this(
        asWalGit(repositories),
        (IndexEventApplier) applier,
        runtime,
        indexConfig.type(),
        serverConfig,
        readiness);
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
            repositories.configuration().indexCursorPath().resolve("READY")));
  }

  IndexEventTailer(
      WalGitRepositoryManager repositories,
      IndexEventApplier applier,
      GerritRuntime runtime,
      String indexType,
      Config serverConfig) {
    this(
        repositories,
        applier,
        runtime,
        indexType,
        serverConfig,
        new IndexEventReadiness(
            repositories.configuration().indexCursorPath().resolve("READY")));
  }

  IndexEventTailer(
      WalGitRepositoryManager repositories,
      IndexEventApplier applier,
      GerritRuntime runtime,
      String indexType,
      Config serverConfig,
      IndexEventReadiness readiness) {
    this.repositories = repositories;
    this.applier = applier;
    this.runtime = runtime;
    this.indexType = indexType;
    this.serverConfig = serverConfig;
    this.readiness = readiness;
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

  void runOnce() throws IOException {
    Exception firstFailure = null;
    for (Project.NameKey project : repositories.list()) {
      try {
        catchUp(project);
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
  }

  int catchUp(Project.NameKey project) throws IOException {
    ManifestStore manifestStore = repositories.storage().manifestStore(project);
    Manifest manifest = manifestStore.read();
    IndexCursorStore cursorStore = new IndexCursorStore(manifestStore.indexCursorPath());
    IndexCursor cursor = cursorStore.read();
    validateCursor(project, manifestStore, manifest, cursor);

    List<LogEntry> entries =
        manifestStore.readLogEntriesAfter(cursor.getSequence(), manifest);
    int applied = 0;
    for (LogEntry entry : entries) {
      if (entry.getKind() == LogEntry.Kind.REF_UPDATE) {
        if (!entry.hasRefTransaction()) {
          throw new IOException(
              "REF_UPDATE WAL entry "
                  + entry.getSeq()
                  + " for "
                  + project.get()
                  + " predates durable index events; run a full reindex and seed the cursor");
        }
        applier.apply(project, entry.getRefTransaction());
        applied++;
      }
      cursorStore.write(
          entry.getSeq(), manifestStore.logKeyForSequence(manifest, entry.getSeq()));
    }
    if (!entries.isEmpty()) {
      logger.info(
          "WalGerrit index-event replay advanced {} from {} to {} ({} ref transactions)",
          project.get(),
          cursor.getSequence(),
          manifest.getHeadSeq(),
          applied);
    }
    return applied;
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

  private static void validateCursor(
      Project.NameKey project,
      ManifestStore manifestStore,
      Manifest manifest,
      IndexCursor cursor)
      throws IOException {
    if (cursor.getSequence() > manifest.getHeadSeq()) {
      throw new IOException(
          "Index cursor for "
              + project.get()
              + " is ahead of the WAL: "
              + cursor.getSequence()
              + " > "
              + manifest.getHeadSeq());
    }
    String expectedLogKey =
        manifestStore.logKeyForSequence(manifest, cursor.getSequence());
    if (!cursor.getLogKey().equals(expectedLogKey)) {
      throw new IOException(
          "Index cursor history mismatch for "
              + project.get()
              + " at sequence "
              + cursor.getSequence());
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
