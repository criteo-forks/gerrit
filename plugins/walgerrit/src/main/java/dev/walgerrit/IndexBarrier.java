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
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.RequestInfo;
import com.google.gerrit.server.RequestInfo.RequestType;
import com.google.gerrit.server.RequestListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.IndexEventTailer.Indexed;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waits for other nodes' writes to a project to be indexed before a request looks a Change-Id up,
 * so the lookup finds a change another node just created.
 *
 * <p>It applies to pushes and to the REST calls that move, cherry-pick or create a change under a
 * project. The wait is bounded by {@code walgerrit.indexBarrierTimeout}; when it cannot be
 * satisfied in time the request continues with the index as it is. A timeout of zero turns the
 * barrier off.
 */
@Singleton
final class IndexBarrier implements RequestListener {
  private static final Logger logger = LoggerFactory.getLogger(IndexBarrier.class);

  /** Last path segment of the REST calls that look a change up by Change-Id. */
  private static final List<String> CHANGE_KEY_LOOKUPS =
      List.of("/move", "/cherrypick", "/create.change");

  private final IndexEventTailer tailer;
  private final Duration timeout;
  private final Timer1<String> latency;
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
            new Description("Time a request spent in the index barrier, by outcome")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            Field.ofString("outcome", (metadata, value) -> {})
                .description("not_behind, replayed, timed_out, failed or skipped")
                .build());
  }

  @Override
  public void onRequest(RequestInfo request) {
    if (timeout.isZero() || request.project().isEmpty() || !looksUpChangeKeys(request)) {
      return;
    }
    Project.NameKey project = request.project().get();
    long started = System.nanoTime();
    Indexed outcome = tailer.awaitIndexed(project, timeout);
    long elapsed = System.nanoTime() - started;
    outcomes.get(outcome).incrementAndGet();
    latency.record(outcome.name().toLowerCase(Locale.ROOT), elapsed, TimeUnit.NANOSECONDS);
    if (outcome == Indexed.TIMED_OUT) {
      logger.warn(
          "WalGerrit index barrier waited {} ms for {} without its index catching up; the"
              + " request continues",
          TimeUnit.NANOSECONDS.toMillis(elapsed),
          project.get());
    }
  }

  /** Requests the barrier answered with {@code outcome}. */
  long count(Indexed outcome) {
    return outcomes.get(outcome).get();
  }

  private static boolean looksUpChangeKeys(RequestInfo request) {
    String type = request.requestType();
    if (RequestType.GIT_RECEIVE.name().equals(type)) {
      return true;
    }
    if (!RequestType.REST.name().equals(type)) {
      return false;
    }
    String uri = request.requestUri().orElse("");
    while (uri.endsWith("/")) {
      uri = uri.substring(0, uri.length() - 1);
    }
    return CHANGE_KEY_LOOKUPS.stream().anyMatch(uri::endsWith);
  }
}
