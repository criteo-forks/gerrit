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


import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elects one node for work the deployment should do once, such as deleting unreferenced files from
 * the store. Leadership is a {@link StoreLease} on {@code leases/cluster/leader}: the holder renews
 * it every third of its term, another node takes it over once it has expired, and a node that fails
 * to renew stops leading at once. Between two ticks a node can therefore believe it leads for up to
 * one term after it lost the lease, so leader-only work must itself be safe to run twice.
 */
final class ClusterLeader {
  private static final Logger logger = LoggerFactory.getLogger(ClusterLeader.class);

  private final StoreLease lease;
  private final Duration term;
  private StoreLease.Held held;

  ClusterLeader(StoreLease lease, Duration term) {
    this.lease = lease;
    this.term = term;
  }

  /** Renews or tries to acquire the lease; returns whether this node leads now. */
  synchronized boolean tick() {
    if (held != null) {
      try {
        held.renew(term);
        return true;
      } catch (IOException lost) {
        held = null;
        logger.info("WalGerrit: this node no longer leads ({})", lost.getMessage());
      }
    }
    try {
      Optional<StoreLease.Held> acquired = lease.acquire(term);
      if (acquired.isPresent()) {
        held = acquired.get();
        logger.info("WalGerrit: this node leads the deployment for the next {}", term);
      }
    } catch (IOException unreachable) {
      logger.warn("WalGerrit could not read the leader lease", unreachable);
    }
    return held != null;
  }

  synchronized boolean isLeader() {
    return held != null;
  }

  synchronized void resign() {
    if (held != null) {
      held.close();
      held = null;
      logger.info("WalGerrit: this node stopped leading");
    }
  }

  Duration tickInterval() {
    return Duration.ofMillis(Math.max(1000, term.toMillis() / 3));
  }
}
