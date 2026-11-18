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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.proto.StorageProto.CompactionLease;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreLeaseTest {
  private static final String KEY = "leases/platform/repo.git/compaction";
  private static final Duration TTL = Duration.ofMinutes(10);

  @TempDir Path root;

  @Test
  void onlyOneNodeHoldsTheLeaseUntilItIsReleased() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-02T12:00:00Z"));
    StoreLease nodeA = new StoreLease(store, KEY, clock, "node-a");
    StoreLease nodeB = new StoreLease(store, KEY, clock, "node-b");

    Optional<StoreLease.Held> held = nodeA.acquire(TTL);
    assertTrue(held.isPresent());
    assertTrue(nodeB.acquire(TTL).isEmpty(), "an unexpired lease is not taken over");
    assertEquals("node-a", nodeA.current().orElseThrow().getOwner());

    held.get().close();
    assertEquals(0, nodeA.current().orElseThrow().getExpiresAtEpochMillis());
    assertTrue(nodeB.acquire(TTL).isPresent(), "a released lease is free");
  }

  @Test
  void renewalWhoseFirstWriteLandedCanStillReleaseItsLease() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-02T12:00:00Z"));
    StoreLease lease = new StoreLease(store, KEY, clock, "node-a");
    StoreLease.Held held = lease.acquire(TTL).orElseThrow();
    clock.advance(Duration.ofSeconds(5));

    // The store applied the renewal, but its reply was lost. Repeating the conditional
    // write with the old version now receives a precondition failure.
    ObjectStore.StoredObject before = store.get(KEY).orElseThrow();
    byte[] renewed =
        CompactionLease.parseFrom(before.bytes()).toBuilder()
            .setExpiresAtEpochMillis(clock.millis() + TTL.toMillis())
            .build()
            .toByteArray();
    store.compareAndSwap(KEY, before.version(), renewed);
    held.renew(TTL);
    held.close();
    assertTrue(new StoreLease(store, KEY, clock, "node-b").acquire(TTL).isPresent());
  }

  @Test
  void lostCreationReplyAndSameNodeTakeoverAreDistinguished() throws IOException {
    UnreliableStore store = new UnreliableStore(root);
    store.loseCreateReply = true;
    SteppingClock clock = new SteppingClock(Instant.EPOCH);
    StoreLease lease = new StoreLease(store, KEY, clock, "same-pod");
    StoreLease.Held first = lease.acquire(TTL).orElseThrow();
    String firstId = lease.current().orElseThrow().getAcquisitionId();
    clock.advance(TTL.plusSeconds(1));
    StoreLease.Held second = lease.acquire(TTL).orElseThrow();
    String secondId = lease.current().orElseThrow().getAcquisitionId();
    assertNotEquals(firstId, secondId);
    assertThrows(IOException.class, () -> first.renew(TTL));
    first.close();
    assertTrue(lease.acquire(TTL).isEmpty());
    assertEquals(secondId, lease.current().orElseThrow().getAcquisitionId());
    second.close();
  }

  @Test
  void anUnreachableReleaseCanBeReclaimedOnlyByTheProcessThatAbandonedIt() throws IOException {
    UnreliableStore store = new UnreliableStore(root);
    SteppingClock clock = new SteppingClock(Instant.EPOCH);
    StoreLease lease = new StoreLease(store, KEY, clock, "pod");
    StoreLease.Held held = lease.acquire(TTL).orElseThrow();
    store.unreachable = true;
    held.close();
    assertThrows(IOException.class, () -> held.renew(TTL));
    store.unreachable = false;
    assertTrue(new StoreLease(store, KEY, clock, "pod").acquire(TTL).isEmpty());
    try (StoreLease.Held replacement = lease.acquire(TTL).orElseThrow()) {
      held.close();
      assertTrue(lease.acquire(TTL).isEmpty());
    }
  }

  private static class UnreliableStore extends ManifestReadCountTest.CountingObjectStore {
    boolean loseCreateReply;
    boolean unreachable;

    UnreliableStore(Path root) {
      super(new FileObjectStore(root));
    }

    @Override
    public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
      StoredObject result = super.putIfAbsent(key, bytes);
      if (loseCreateReply) {
        throw new IOException("Lost conditional create response");
      }
      return result;
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      if (unreachable) {
        throw new IOException("Store unreachable");
      }
      return super.get(key);
    }

    @Override
    public StoredObject compareAndSwap(String key, String version, byte[] bytes)
        throws IOException {
      if (unreachable) {
        throw new IOException("Store unreachable");
      }
      return super.compareAndSwap(key, version, bytes);
    }
  }

  @Test
  void anExpiredLeaseIsTakenOverAndTheOldHolderCannotRenew() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-09-02T12:00:00Z"));
    StoreLease nodeA = new StoreLease(store, KEY, clock, "node-a");
    StoreLease nodeB = new StoreLease(store, KEY, clock, "node-b");

    StoreLease.Held byA = nodeA.acquire(TTL).orElseThrow();
    clock.advance(TTL.plusSeconds(1));
    StoreLease.Held byB = nodeB.acquire(TTL).orElseThrow();
    assertEquals("node-b", nodeB.current().orElseThrow().getOwner());

    assertThrows(IOException.class, () -> byA.renew(TTL));
    byB.renew(TTL);
    assertEquals(
        clock.millis() + TTL.toMillis(), nodeB.current().orElseThrow().getExpiresAtEpochMillis());
    byA.close();
    assertEquals(
        "node-b", nodeB.current().orElseThrow().getOwner(), "a lost lease is not released");
  }
}
