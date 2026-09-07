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
 * The lease for work the deployment should do once, not once per node: deleting unreferenced files
 * from the shared store. It is a {@link StoreLease} on {@code leases/cluster/sweep}: the holder
 * renews it every third of its term, another node takes it over once it has expired, and a node
 * that fails to renew stops at once. Between two ticks a node can believe it still holds the lease
 * for up to one term after losing it, so the work behind it must be safe to run twice. There is no
 * leader in the data path: every node writes through the manifest CAS and converges from the log.
 */
final class SweepLease {
  private static final Logger logger = LoggerFactory.getLogger(SweepLease.class);

  private final StoreLease lease;
  private final Duration term;
  private StoreLease.Held held;

  SweepLease(StoreLease lease, Duration term) {
    this.lease = lease;
    this.term = term;
  }

  /** Renews or tries to acquire the lease; returns whether this node holds it now. */
  synchronized boolean tick() {
    if (held != null) {
      try {
        held.renew(term);
        return true;
      } catch (IOException lost) {
        held = null;
        logger.info("WalGerrit: this node lost the sweep lease ({})", lost.getMessage());
      }
    }
    try {
      Optional<StoreLease.Held> acquired = lease.acquire(term);
      if (acquired.isPresent()) {
        held = acquired.get();
        logger.info(
            "WalGerrit: this node holds the sweep lease for the next {} and deletes unreferenced"
                + " files from the store",
            term);
      }
    } catch (IOException unreachable) {
      logger.warn("WalGerrit could not read the sweep lease", unreachable);
    }
    return held != null;
  }

  synchronized boolean isHeld() {
    return held != null;
  }

  synchronized void release() {
    if (held != null) {
      held.close();
      held = null;
      logger.info("WalGerrit: this node released the sweep lease");
    }
  }

  Duration tickInterval() {
    return Duration.ofMillis(Math.max(1000, term.toMillis() / 3));
  }
}
