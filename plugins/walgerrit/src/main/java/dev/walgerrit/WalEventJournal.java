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
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventGson;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the events this node fires into the WAL of the repository they concern, as EVENT log
 * entries, so every other node's tailer can deliver them to its own stream-events subscribers and
 * plugins. Events are collected for a short interval and published as one entry per repository:
 * one object write and one manifest CAS per batch, on a background thread, never on the request
 * that fired the event. An event without a project goes into the All-Projects log.
 *
 * <p>Delivery is at least once and best effort: a batch whose publication fails is logged and
 * dropped, and an entry a node replayed before a crash may be replayed again after it. Events
 * replayed from another node are recognised through {@link EventReplay} and not journaled again.
 */
@Singleton
final class WalEventJournal implements EventListener, LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(WalEventJournal.class);
  static final Duration FLUSH_INTERVAL = Duration.ofMillis(200);
  static final int FLUSH_AT = 100;

  private final WalGitRepositoryManager repositories;
  private final Gson gson;
  private final Project.NameKey allProjects;
  private final GerritRuntime runtime;
  private final JournalBuffer buffer = new JournalBuffer();
  private volatile ScheduledExecutorService executor;

  @Inject
  WalEventJournal(
      GitRepositoryManager repositories,
      @EventGson Gson gson,
      AllProjectsName allProjects,
      GerritRuntime runtime) {
    this.repositories = asWalGit(repositories);
    this.gson = gson;
    this.allProjects = allProjects;
    this.runtime = runtime;
  }

  @Override
  public void onEvent(Event event) {
    ScheduledExecutorService running = executor;
    if (running == null || EventReplay.isReplaying()) {
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
      try {
        running.execute(this::flush);
      } catch (RejectedExecutionException stopping) {
        // The scheduled flush or stop() drains what is left.
      }
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
              Thread thread = new Thread(runnable, "WalGerrit-Event-Journal");
              thread.setDaemon(true);
              return thread;
            });
    executor.scheduleWithFixedDelay(
        this::flush, FLUSH_INTERVAL.toMillis(), FLUSH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    logger.info(
        "WalGerrit journals Gerrit events into the WAL every {} ms for the other nodes",
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
    for (Map.Entry<Project.NameKey, List<String>> batch : buffer.drain().entrySet()) {
      try {
        repositories.storage().manifestStore(batch.getKey()).publishEvents(batch.getValue());
      } catch (IOException | RuntimeException failure) {
        logger.warn(
            "WalGerrit dropped {} event(s) of {} that other nodes will not see",
            batch.getValue().size(),
            batch.getKey().get(),
            failure);
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
