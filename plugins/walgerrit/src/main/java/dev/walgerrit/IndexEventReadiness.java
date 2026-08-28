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

import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Node-local signal that the index-event consumer has completed a clean full sweep. */
@Singleton
final class IndexEventReadiness {
  private static final Logger logger = LoggerFactory.getLogger(IndexEventReadiness.class);
  private static final String READY_FILE = "READY";

  private final AtomicBoolean ready = new AtomicBoolean();
  private final Path markerPath;

  @Inject
  IndexEventReadiness(GitRepositoryManager repositories, MetricMaker metrics) {
    this(asWalGit(repositories).configuration().indexCursorPath().resolve(READY_FILE));
    metrics.newCallbackMetric(
        "walgerrit/index_events/ready",
        Boolean.class,
        new Description(
                "Whether this node completed a clean full WalGerrit index-event sweep")
            .setGauge(),
        ready::get);
  }

  IndexEventReadiness(Path markerPath) {
    this.markerPath = markerPath.toAbsolutePath().normalize();
  }

  /** Clears a marker left by an earlier process before the startup catch-up begins. */
  void beginStartup() throws IOException {
    ready.set(false);
    Files.deleteIfExists(markerPath);
  }

  /** Publishes readiness only after all repositories completed one clean sweep. */
  void markReady() throws IOException {
    if (ready.get() && Files.isRegularFile(markerPath)) {
      return;
    }
    Files.createDirectories(markerPath.getParent());
    Path temporary =
        Files.createTempFile(markerPath.getParent(), "." + READY_FILE + ".", ".tmp");
    try {
      Files.writeString(temporary, "ready\n", StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary,
            markerPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING);
      }
      ready.set(true);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Revokes readiness after shutdown or any incomplete sweep. */
  void markNotReady() {
    ready.set(false);
    try {
      Files.deleteIfExists(markerPath);
    } catch (IOException exception) {
      logger.error("Could not remove stale WalGerrit readiness marker {}", markerPath, exception);
    }
  }

  boolean isReady() {
    return ready.get();
  }

  Path markerPath() {
    return markerPath;
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
