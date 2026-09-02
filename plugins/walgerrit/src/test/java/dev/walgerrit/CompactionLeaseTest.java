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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactionLeaseTest {
  private static final String KEY = "leases/platform/repo.git/compaction";
  private static final Duration TTL = Duration.ofMinutes(10);

  @TempDir Path root;

  @Test
  void onlyOneNodeHoldsTheLeaseUntilItIsReleased() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-02T12:00:00Z"));
    CompactionLease nodeA = new CompactionLease(store, KEY, clock, "node-a");
    CompactionLease nodeB = new CompactionLease(store, KEY, clock, "node-b");

    Optional<CompactionLease.Held> held = nodeA.acquire(TTL);
    assertTrue(held.isPresent());
    assertTrue(nodeB.acquire(TTL).isEmpty(), "an unexpired lease is not taken over");
    assertEquals("node-a", nodeA.current().orElseThrow().getOwner());

    held.get().close();
    assertEquals(0, nodeA.current().orElseThrow().getExpiresAtEpochMillis());
    assertTrue(nodeB.acquire(TTL).isPresent(), "a released lease is free");
  }

  @Test
  void anExpiredLeaseIsTakenOverAndTheOldHolderCannotRenew() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-02T12:00:00Z"));
    CompactionLease nodeA = new CompactionLease(store, KEY, clock, "node-a");
    CompactionLease nodeB = new CompactionLease(store, KEY, clock, "node-b");

    CompactionLease.Held byA = nodeA.acquire(TTL).orElseThrow();
    clock.advance(TTL.plusSeconds(1));
    CompactionLease.Held byB = nodeB.acquire(TTL).orElseThrow();
    assertEquals("node-b", nodeB.current().orElseThrow().getOwner());

    assertThrows(IOException.class, () -> byA.renew(TTL));
    byB.renew(TTL);
    assertEquals(
        clock.millis() + TTL.toMillis(), nodeB.current().orElseThrow().getExpiresAtEpochMillis());
    byA.close();
    assertEquals("node-b", nodeB.current().orElseThrow().getOwner(), "a lost lease is not released");
  }
}
