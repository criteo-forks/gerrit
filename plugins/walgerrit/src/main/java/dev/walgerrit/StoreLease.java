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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.walgerrit.proto.StorageProto.CompactionLease;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * One repository's compaction lease: a small object whose expiry says which node is compacting.
 *
 * <p>The lease only prevents duplicate work. Correctness comes from the manifest compare-and-swap,
 * which refuses a compaction whose inputs are no longer live, so a lease that is lost or expires
 * mid-compaction can waste an upload but never corrupt anything. A lease is acquired by creating
 * the object or by replacing an expired one through a compare-and-swap on its version; releasing
 * writes an expiry of zero rather than deleting, so the object never churns delete markers.
 */
final class StoreLease {
  static final String FILE = "compaction";

  private final ObjectStore store;
  private final String key;
  private final Clock clock;
  private final String owner;

  private final Cache<String, Boolean> abandoned;

  // A live process can relinquish its own abandoned claims after connectivity returns. Eviction
  // or a process restart falls back to the normal lease expiry.
  static Cache<String, Boolean> abandonedClaims() {
    return CacheBuilder.newBuilder().maximumSize(4096).build();
  }

  StoreLease(ObjectStore store, String key, Clock clock, String owner) {
    this(store, key, clock, owner, abandonedClaims());
  }

  StoreLease(
      ObjectStore store, String key, Clock clock, String owner, Cache<String, Boolean> abandoned) {
    this.store = store;
    this.key = key;
    this.clock = clock;
    this.owner = owner;
    this.abandoned = abandoned;
  }

  Optional<Held> acquire(Duration duration) throws IOException {
    Optional<ObjectStore.StoredObject> current = store.get(key);
    long now = clock.millis();
    if (current.isPresent()) {
      CompactionLease existing = CompactionLease.parseFrom(current.get().bytes());
      if (existing.getExpiresAtEpochMillis() > now
          && abandoned.getIfPresent(existing.getAcquisitionId()) == null) {
        return Optional.empty();
      }
    }
    String id = UUID.randomUUID().toString();
    CompactionLease claim = lease(id, now + duration.toMillis());
    try {
      return writeClaim(current.map(ObjectStore.StoredObject::version), claim)
          .map(version -> new Held(id, version));
    } catch (IOException unavailable) {
      // The original request may still be in flight. No worker will use this acquisition.
      abandoned.put(id, true);
      throw unavailable;
    }
  }

  /** Resolves an ambiguous conditional write with one authoritative read of its exact claim. */
  private Optional<String> writeClaim(Optional<String> expectedVersion, CompactionLease claim)
      throws IOException {
    byte[] bytes = claim.toByteArray();
    ObjectStore.StoredObject applied;
    try {
      applied =
          expectedVersion.isEmpty()
              ? store.putIfAbsent(key, bytes)
              : store.compareAndSwap(key, expectedVersion.get(), bytes);
    } catch (IOException failure) {
      Optional<ObjectStore.StoredObject> found;
      try {
        found = store.get(key).filter(stored -> Arrays.equals(stored.bytes(), bytes));
      } catch (IOException unreadable) {
        if (unreadable != failure) {
          failure.addSuppressed(unreadable);
        }
        throw failure;
      }
      if (found.isEmpty()) {
        if (failure instanceof ObjectAlreadyExistsException
            || failure instanceof ObjectStoreConflictException) {
          return Optional.empty();
        }
        throw failure;
      }
      applied = found.get();
    }
    return clock.millis() < claim.getExpiresAtEpochMillis()
        ? Optional.of(applied.version())
        : Optional.empty();
  }

  Optional<CompactionLease> current() throws IOException {
    Optional<ObjectStore.StoredObject> stored = store.get(key);
    return stored.isEmpty()
        ? Optional.empty()
        : Optional.of(CompactionLease.parseFrom(stored.get().bytes()));
  }

  private CompactionLease lease(String id, long expiresAt) {
    return CompactionLease.newBuilder()
        .setOwner(owner)
        .setAcquisitionId(id)
        .setExpiresAtEpochMillis(expiresAt)
        .build();
  }

  final class Held implements AutoCloseable {
    private final String id;
    private String version;

    private Held(String id, String version) {
      this.id = id;
      this.version = version;
    }

    synchronized void renew(Duration duration) throws IOException {
      if (version == null) {
        throw new IOException("Lease " + key + " is closed");
      }
      CompactionLease claim = lease(id, clock.millis() + duration.toMillis());
      version =
          writeClaim(Optional.of(version), claim)
              .orElseThrow(() -> new IOException("This process no longer holds lease " + key));
    }

    @Override
    public synchronized void close() {
      if (version == null) {
        return;
      }
      byte[] released = lease(id, 0).toByteArray();
      try {
        store.compareAndSwap(key, version, released);
      } catch (IOException uncertain) {
        try {
          Optional<ObjectStore.StoredObject> current = store.get(key);
          if (current.isPresent()) {
            CompactionLease held = CompactionLease.parseFrom(current.get().bytes());
            if (id.equals(held.getAcquisitionId()) && held.getExpiresAtEpochMillis() > 0) {
              store.compareAndSwap(key, current.get().version(), released);
            }
          }
        } catch (IOException unavailable) {
          abandoned.put(id, true);
        }
      } finally {
        version = null;
      }
    }
  }
}
