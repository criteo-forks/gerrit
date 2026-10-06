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
import com.google.gerrit.extensions.events.AccountIndexedListener;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.extensions.events.GroupIndexedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectIndexedListener;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventGsonProvider;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journals what this node does that every other node must repeat: the Gerrit events it fires, for
 * their stream-events subscribers and plugins, and the documents it reindexes with no ref update
 * behind them, for their own indexes. Ref updates need no journal; every node derives that index
 * work from REF_UPDATE entries.
 *
 * <p>Both are collected for a short interval and published as one entry per repository: one object
 * write and one manifest CAS per batch, on a background thread, never on the request that did the
 * work. An event without a project goes into the All-Projects log, accounts and groups into
 * All-Users', projects into All-Projects'. A batch whose publication fails is retried with the next
 * one, events ahead of those fired since, because the log is what other nodes and any external
 * publisher read: an event that never reaches it is lost to all of them. A publication whose
 * outcome is unknown is settled from the log first, so a batch that did land is not written twice.
 *
 * <p>Work done on another node's behalf, replaying an entry or rebuilding the indexes, runs under
 * {@link EventReplay} and is not journaled again. A rebuild covers the whole process because its
 * indexers fan out over a pool; it suppresses only reindexed documents, never the events this node
 * fires meanwhile.
 */
@Singleton
final class WalJournal
    implements EventListener,
        ChangeIndexedListener,
        AccountIndexedListener,
        GroupIndexedListener,
        ProjectIndexedListener,
        LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(WalJournal.class);
  static final Duration FLUSH_INTERVAL = Duration.ofMillis(200);
  static final int FLUSH_AT = 100;

  private final WalGitRepositoryManager repositories;
  // Gerrit's event Gson, built here because only the daemon binds @EventGson and init/reindex
  // load this library too.
  private final Gson gson = new EventGsonProvider().get();
  private final Project.NameKey allProjects;
  private final Project.NameKey allUsers;
  private final GerritRuntime runtime;
  private final JournalBuffer buffer = new JournalBuffer();
  private volatile ScheduledExecutorService executor;

  @Inject
  WalJournal(
      GitRepositoryManager repositories,
      AllProjectsName allProjects,
      AllUsersName allUsers,
      GerritRuntime runtime) {
    this.repositories = asWalGit(repositories);
    this.allProjects = allProjects;
    this.allUsers = allUsers;
    this.runtime = runtime;
  }

  @Override
  public void onEvent(Event event) {
    if (!journalingEvents()) {
      return;
    }
    String json;
    try {
      json = gson.toJson(event);
    } catch (RuntimeException unserializable) {
      logger.warn("WalGerrit cannot journal a {} event", event.type, unserializable);
      return;
    }
    if (buffer.add(projectOf(event), json) >= FLUSH_AT) {
      flushSoon();
    }
  }

  @Override
  public void onChangeIndexed(String projectName, int id) {
    if (journaling()) {
      buffer.addChange(Project.nameKey(projectName), id);
    }
  }

  @Override
  public void onChangeDeleted(int id) {
    // A deletion is a ref update; every node deletes the document when it replays it.
  }

  @Override
  public void onAccountIndexed(int id) {
    if (journaling()) {
      buffer.addAccount(allUsers, id);
    }
  }

  @Override
  public void onGroupIndexed(String uuid) {
    if (journaling()) {
      buffer.addGroup(allUsers, uuid);
    }
  }

  @Override
  public void onProjectIndexed(String project) {
    if (journaling()) {
      buffer.addProject(allProjects, project);
    }
  }

  /** Whether reindexed documents are journaled: not while this node repeats another's work. */
  private boolean journaling() {
    return executor != null && !EventReplay.isReplaying();
  }

  /**
   * Whether events are journaled: not while this thread replays another node's. An index rebuild
   * does not stop them, so an event fired on this node during a rebuild still reaches the log.
   */
  private boolean journalingEvents() {
    return executor != null && !EventReplay.isReplayingOnThisThread();
  }

  private void flushSoon() {
    ScheduledExecutorService running = executor;
    if (running == null) {
      return;
    }
    try {
      running.execute(this::flush);
    } catch (RejectedExecutionException stopping) {
      // The scheduled flush or stop() drains what is left.
    }
  }

  private Project.NameKey projectOf(Event event) {
    if (event instanceof ProjectEvent projectEvent && projectEvent.getProjectNameKey() != null) {
      return projectEvent.getProjectNameKey();
    }
    return allProjects;
  }

  @Override
  public synchronized void start() {
    if (runtime != GerritRuntime.DAEMON
        || !repositories.configuration().eventJournalEnabled()
        || executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "WalGerrit-Journal");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(
        this::flush, FLUSH_INTERVAL.toMillis(), FLUSH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    logger.info(
        "WalGerrit journals events and reindexed documents into the WAL every {} ms for the other"
            + " nodes",
        FLUSH_INTERVAL.toMillis());
  }

  @Override
  public synchronized void stop() {
    ScheduledExecutorService running = executor;
    executor = null;
    if (running == null) {
      return;
    }
    running.shutdown();
    try {
      running.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    flush();
  }

  /** Publishes every waiting batch; visible for tests. */
  void flush() {
    for (Map.Entry<Project.NameKey, JournalBuffer.Batch> entry : buffer.drain().entrySet()) {
      JournalBuffer.Batch batch = entry.getValue();
      try {
        Optional<ManifestStore> store = repositories.manifestStoreFor(entry.getKey());
        if (store.isEmpty()) {
          // Renamed or deleted since the work was done: the other nodes reconcile the project
          // from the catalog instead, and nothing of this batch can apply under the old name.
          logger.info(
              "WalGerrit drops a journal batch for {}: no longer an active project",
              entry.getKey().get());
          continue;
        }
        publish(store.get(), batch);
      } catch (IOException | RuntimeException failure) {
        int discarded = buffer.requeue(entry.getKey(), batch.events(), batch.index());
        if (discarded > 0) {
          logger.error(
              "WalGerrit discarded the {} oldest unjournaled event(s) for {}: its log has refused"
                  + " writes for longer than {} events can wait",
              discarded,
              entry.getKey().get(),
              JournalBuffer.MAX_EVENTS);
        }
        logger.warn(
            "WalGerrit could not journal {} event(s) and {} index update for {}; both are retried"
                + " with the next batch",
            batch.events().size(),
            batch.index() == null ? "no" : "an",
            entry.getKey().get(),
            failure);
      }
    }
  }

  /**
   * Publishes one batch. When the store lost the response, the log says whether the entry landed:
   * one that did is done, one that did not is retried, and only a log that cannot be read leaves
   * the outcome open, in which case the batch is retried and may be journaled twice.
   */
  private static void publish(ManifestStore store, JournalBuffer.Batch batch) throws IOException {
    try {
      store.publishJournal(batch.events(), batch.index());
    } catch (AmbiguousPublicationException unknown) {
      ManifestStore.Resolution resolution =
          store.resolvePublication(unknown.sequence(), unknown.transactionId());
      if (!resolution.landed()) {
        throw unknown;
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
