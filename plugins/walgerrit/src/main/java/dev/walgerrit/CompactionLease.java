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

import dev.walgerrit.proto.StorageProto;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * One repository's compaction lease: a small object whose expiry says which node is compacting.
 *
 * <p>The lease only prevents duplicate work. Correctness comes from the manifest compare-and-swap,
 * which refuses a compaction whose inputs are no longer live, so a lease that is lost or expires
 * mid-compaction can waste an upload but never corrupt anything. A lease is acquired by creating
 * the object or by replacing an expired one through a compare-and-swap on its version; releasing
 * writes an expiry of zero rather than deleting, so the object never churns delete markers.
 */
final class CompactionLease {
  static final String FILE = "compaction";

  private final ObjectStore store;
  private final String key;
  private final Clock clock;
  private final String owner;

  CompactionLease(ObjectStore store, String key, Clock clock, String owner) {
    this.store = store;
    this.key = key;
    this.clock = clock;
    this.owner = owner;
  }

  /** Takes the lease for {@code duration} unless another owner holds an unexpired one. */
  Optional<Held> acquire(Duration duration) throws IOException {
    long now = clock.millis();
    byte[] claim = lease(now + duration.toMillis()).toByteArray();
    Optional<ObjectStore.StoredObject> current = store.get(key);
    if (current.isEmpty()) {
      try {
        return Optional.of(new Held(store.putIfAbsent(key, claim).version()));
      } catch (ObjectAlreadyExistsException raced) {
        current = store.get(key);
        if (current.isEmpty()) {
          return Optional.empty();
        }
      }
    }
    StorageProto.CompactionLease held =
        StorageProto.CompactionLease.parseFrom(current.get().bytes());
    if (held.getExpiresAtEpochMillis() > now) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Held(store.compareAndSwap(key, current.get().version(), claim).version()));
    } catch (ObjectStoreConflictException raced) {
      return Optional.empty();
    }
  }

  /** The current holder as recorded in the store, for diagnostics and tests. */
  Optional<StorageProto.CompactionLease> current() throws IOException {
    Optional<ObjectStore.StoredObject> stored = store.get(key);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(StorageProto.CompactionLease.parseFrom(stored.get().bytes()));
  }

  private StorageProto.CompactionLease lease(long expiresAt) {
    return StorageProto.CompactionLease.newBuilder()
        .setOwner(owner)
        .setExpiresAtEpochMillis(expiresAt)
        .build();
  }

  /** A lease this node holds; closing it releases the lease. */
  final class Held implements AutoCloseable {
    private String version;

    private Held(String version) {
      this.version = version;
    }

    /** Extends the lease by {@code duration} from now; fails if another owner took it over. */
    void renew(Duration duration) throws IOException {
      byte[] claim = lease(clock.millis() + duration.toMillis()).toByteArray();
      try {
        version = store.compareAndSwap(key, version, claim).version();
      } catch (ObjectStoreConflictException lost) {
        throw new IOException("Compaction lease " + key + " was taken over by another node", lost);
      }
    }

    /** Marks the lease expired so the next compactor can take it immediately; best effort. */
    @Override
    public void close() {
      try {
        store.compareAndSwap(key, version, lease(0).toByteArray());
      } catch (IOException alreadyLost) {
        // Another node took the lease over after it expired; nothing to release.
      }
    }
  }
}
