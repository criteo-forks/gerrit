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
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer2;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.IndexEventTailer.Indexed;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings this node's change index up to date with other nodes' writes before Gerrit decides
 * something on it, and says so when it cannot.
 *
 * <p>{@link WalGitIndexModule} binds Gerrit's {@code ChangeIndexFreshness} hook to this barrier. A
 * project name scopes the wait to indexed decisions such as finding changes to close after a direct
 * push. An empty argument requests a global wait for whole-topic submission. The result is empty on
 * success and otherwise gives the caller a reason to retry.
 *
 * <p>{@code walgerrit.indexBarrierTimeout} bounds this wait. A zero timeout or a disabled tailer
 * skips the replay wait. Branch/Change-Id membership is still checked at ref publication.
 */
@Singleton
final class IndexBarrier
    implements Function<Optional<String>, Optional<String>>, LongSupplier, LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(IndexBarrier.class);

  /** The Gerrit class that calls the barrier; absent from a build without the hook. */
  private static final String HOOK = "com.google.gerrit.server.index.change.ChangeIndexFreshness";

  private final IndexEventTailer tailer;
  private final Duration timeout;
  private final Timer2<String, String> latency;
  private final Map<Indexed, AtomicLong> outcomes = new EnumMap<>(Indexed.class);

  @Inject
  IndexBarrier(IndexEventTailer tailer, MetricMaker metrics) {
    this(tailer, tailer.indexBarrierTimeout(), metrics);
  }

  IndexBarrier(IndexEventTailer tailer, Duration timeout, MetricMaker metrics) {
    this.tailer = tailer;
    this.timeout = timeout;
    for (Indexed outcome : Indexed.values()) {
      outcomes.put(outcome, new AtomicLong());
    }
    latency =
        metrics.newTimer(
            "walgerrit/index_barrier/latency",
            new Description("Time a request spent in the index barrier, by outcome and scope")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            Field.ofString("outcome", (metadata, value) -> {})
                .description("not_behind, replayed, disabled, timed_out, failed or unavailable")
                .build(),
            Field.ofString("scope", (metadata, value) -> {})
                .description("project for one repository, all for every repository")
                .build());
  }

  /**
   * Without the hook nothing calls the barrier, and a node would decide on a stale index with no
   * sign of it. Refuse to start instead; {@code indexBarrierTimeout = 0} is the way to run without.
   */
  @Override
  public void start() {
    if (timeout.isZero() || !tailer.tailing()) {
      return;
    }
    try {
      Class.forName(HOOK, false, IndexBarrier.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError missing) {
      throw new IllegalStateException(
          "This Gerrit build has no "
              + HOOK
              + ", so nothing would call WalGerrit's index barrier. Deploy the WalGerrit library"
              + " with the fork WAR it was built for, or set walgerrit.indexBarrierTimeout = 0 to"
              + " run without the barrier.",
          missing);
    }
  }

  /** A user search reads the age of the last complete sweep without waiting for the store. */
  @Override
  public long getAsLong() {
    return tailer.searchGeneration();
  }

  @Override
  public void stop() {}

  @Override
  public Optional<String> apply(Optional<String> project) {
    if (timeout.isZero()) {
      return Optional.empty();
    }
    long started = System.nanoTime();
    Indexed outcome =
        project.isPresent()
            ? tailer.awaitIndexed(Project.nameKey(project.get()), timeout)
            : tailer.awaitAllIndexed(timeout);
    long elapsed = System.nanoTime() - started;
    outcomes.get(outcome).incrementAndGet();
    latency.record(
        outcome.name().toLowerCase(Locale.ROOT),
        project.isPresent() ? "project" : "all",
        elapsed,
        TimeUnit.NANOSECONDS);
    if (outcome.fresh()) {
      return Optional.empty();
    }
    String reason = reason(outcome, TimeUnit.NANOSECONDS.toMillis(elapsed));
    logger.warn(
        "WalGerrit index barrier could not bring the index up to date for {}: {}",
        project.orElse("all projects"),
        reason);
    return Optional.of(reason);
  }

  /** Requests the barrier answered with {@code outcome}. */
  long count(Indexed outcome) {
    return outcomes.get(outcome).get();
  }

  private static String reason(Indexed outcome, long waitedMillis) {
    return switch (outcome) {
      case TIMED_OUT -> "other servers' writes were not indexed within " + waitedMillis + " ms";
      case FAILED -> "reading or indexing other servers' writes failed";
      case UNAVAILABLE -> "other servers' writes cannot be indexed right now";
      case NOT_BEHIND, REPLAYED, DISABLED ->
          throw new IllegalArgumentException(outcome + " is not a failure");
    };
  }
}
