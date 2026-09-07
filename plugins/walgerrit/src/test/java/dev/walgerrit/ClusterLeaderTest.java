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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClusterLeaderTest {
  private static final Duration TERM = Duration.ofSeconds(60);
  @TempDir Path root;

  @Test
  void exactlyOneNodeLeadsAndTheOtherTakesOverAfterTheTermLapses() throws Exception {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-07T12:00:00Z"));
    ClusterLeader a = new ClusterLeader(new StoreLease(store, "leases/cluster/leader", clock, "a"), TERM);
    ClusterLeader b = new ClusterLeader(new StoreLease(store, "leases/cluster/leader", clock, "b"), TERM);

    assertTrue(a.tick());
    assertFalse(b.tick());
    assertTrue(a.isLeader());
    assertFalse(b.isLeader());

    // a keeps renewing: b never gets it.
    clock.advance(TERM.minusSeconds(5));
    assertTrue(a.tick());
    clock.advance(TERM.minusSeconds(5));
    assertFalse(b.tick());

    // a stops ticking (crashed or partitioned); after the term lapses b takes over, and a finds out.
    clock.advance(TERM.plusSeconds(1));
    assertTrue(b.tick());
    assertFalse(a.tick());
    assertFalse(a.isLeader());
    assertTrue(b.isLeader());
  }

  @Test
  void resigningHandsOverImmediately() throws Exception {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-07T12:00:00Z"));
    ClusterLeader a = new ClusterLeader(new StoreLease(store, "leases/cluster/leader", clock, "a"), TERM);
    ClusterLeader b = new ClusterLeader(new StoreLease(store, "leases/cluster/leader", clock, "b"), TERM);
    assertTrue(a.tick());

    a.resign();

    assertFalse(a.isLeader());
    assertTrue(b.tick());
    assertEquals(Duration.ofSeconds(20), b.tickInterval());
  }

  @Test
  void tickIntervalNeverDropsBelowOneSecond() {
    ClusterLeader quick =
        new ClusterLeader(
            new StoreLease(new FileObjectStore(root), "leases/cluster/leader", new SteppingClock(Instant.EPOCH), "a"),
            Duration.ofMillis(900));
    assertEquals(Duration.ofSeconds(1), quick.tickInterval());
  }
}
