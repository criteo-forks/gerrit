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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.proto.StorageProto.LogEntry;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies store health to Gerrit's {@code WriteForwarding} hook for peer hellos, local placement
 * and incoming forwarded writes.
 *
 * <p>A forwarding daemon conditionally reads the catalog manifest every {@code
 * walgerrit.storeProbeInterval} on a dedicated thread. With no successful read for {@code
 * walgerrit.storeProbeTimeout}, the node is ineligible, even if a read hangs.
 *
 * <p>After {@value #WRITE_FAILURES} consecutive publication write failures, the node is ineligible
 * until a publication lands or the timeout passes without another failure. Lost races do not
 * count. A zero timeout disables both checks; probes never write to the store.
 */
@Singleton
final class StoreHealth
    implements Supplier<Optional<String>>, LifecycleListener, StorageLayout.PublicationObserver {
  private static final Logger logger = LoggerFactory.getLogger(StoreHealth.class);

  /** Publication writes that fail in a row before the node is no candidate. */
  static final int WRITE_FAILURES = 3;

  @FunctionalInterface
  interface Probe {
    void run() throws IOException;
  }

  private final boolean active;
  private final Duration interval;
  private final Duration timeout;
  private final Probe probe;
  private final LongSupplier nanoTime;
  private volatile long lastSuccess;
  private final AtomicReference<WriteFailures> writeFailures =
      new AtomicReference<>(WriteFailures.NONE);
  private volatile ScheduledExecutorService executor;

  @Inject
  StoreHealth(GitRepositoryManager repositories, GerritRuntime runtime, MetricMaker metrics) {
    this(
        runtime == GerritRuntime.DAEMON && asWalGit(repositories).configuration().forwardingActive(),
        asWalGit(repositories).configuration().storeProbeInterval(),
        asWalGit(repositories).configuration().storeProbeTimeout(),
        () ->
            asWalGit(repositories)
                .storage()
                .manifestStore(RepositoryId.CATALOG)
                .refreshIfPresent(),
        System::nanoTime);
    metrics.newCallbackMetric(
        "walgerrit/store/reachable",
        Boolean.class,
        new Description(
                "Whether this node read the store within walgerrit.storeProbeTimeout and its"
                    + " publication writes are not failing")
            .setGauge(),
        () -> get().isEmpty());
    asWalGit(repositories).storage().observePublications(this);
  }

  StoreHealth(
      boolean active, Duration interval, Duration timeout, Probe probe, LongSupplier nanoTime) {
    this.active = active && !timeout.isZero();
    this.interval = interval;
    this.timeout = timeout;
    this.probe = probe;
    this.nanoTime = nanoTime;
    lastSuccess = nanoTime.getAsLong();
  }

  @Override
  public synchronized void start() {
    if (!active || executor != null) {
      return;
    }
    lastSuccess = nanoTime.getAsLong();
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "WalGerrit-Store-Health");
              thread.setDaemon(true);
              return thread;
            });
    long millis = Math.max(1, interval.toMillis());
    executor.scheduleWithFixedDelay(this::check, millis, millis, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  /** Empty while the node may execute writes; otherwise why it may not. */
  @Override
  public Optional<String> get() {
    if (!active) {
      return Optional.empty();
    }
    long now = nanoTime.getAsLong();
    long age = now - lastSuccess;
    if (age >= timeout.toNanos()) {
      return Optional.of(
          "the store has not answered for " + TimeUnit.NANOSECONDS.toSeconds(age) + " s");
    }
    WriteFailures failures = writeFailures.get();
    if (failures.count() >= WRITE_FAILURES && now - failures.last() < timeout.toNanos()) {
      return Optional.of(
          "the last " + failures.count() + " publication writes to the store failed");
    }
    return Optional.empty();
  }

  @Override
  public void published(RepositoryId id, LogEntry.Kind kind) {
    writeFailures.set(WriteFailures.NONE);
  }

  @Override
  public void conflicted(RepositoryId id) {}

  @Override
  public void writeFailed(RepositoryId id, Exception failure) {
    WriteFailures failures =
        writeFailures.updateAndGet(
            previous -> new WriteFailures(previous.count() + 1, nanoTime.getAsLong()));
    if (failures.count() == WRITE_FAILURES) {
      logger.warn(
          "WalGerrit publication writes to the store failed {} times in a row, the last for {}: {}",
          WRITE_FAILURES,
          id,
          failure.toString());
    }
  }

  /** Publication writes that failed in a row, and when the last of them failed. */
  private record WriteFailures(int count, long last) {
    static final WriteFailures NONE = new WriteFailures(0, 0);
  }

  /** One read of the store; catches failures so the executor keeps scheduling checks. */
  void check() {
    try {
      probe.run();
      lastSuccess = nanoTime.getAsLong();
    } catch (IOException | RuntimeException failure) {
      logger.warn("WalGerrit store check failed: {}", failure.toString());
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
