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
import dev.walgerrit.proto.StorageProto.AccountRefPage;
import dev.walgerrit.proto.StorageProto.ChangeKeyPage;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.LogPosition;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node-wide record of the newest manifest observed per repository.
 *
 * <p>Every {@link ManifestStore} on a node shares one cache, so a conditional read by any handle,
 * including the index-event tailer, lets every other open handle adopt the newer manifest without
 * another round trip. Entries only move forward in revision.
 *
 * <p>A peer's wake-up (see {@link GossipEndpoint}) can announce a manifest this node has not read
 * yet. The cache then holds an expectation for the key: the node's view no longer counts as
 * validated, so the next open, lookup or existence check makes a conditional read. The expectation
 * ends when a manifest at that revision arrives, or when the store itself confirms the node's view
 * is current.
 */
final class ManifestCache {
  private static final Logger logger = LoggerFactory.getLogger(ManifestCache.class);

  record Page(String repo, LogPosition position, int shard) {}

  // Immutable WAL pages, shared by repository handles on this node.
  final Cache<Page, ChangeKeyPage> keyPages =
      CacheBuilder.newBuilder()
          .maximumWeight(16L << 20)
          .weigher((Page key, ChangeKeyPage page) -> 128 + page.getSerializedSize())
          .build();

  final Cache<String, AccountRefPage> accountPages =
      CacheBuilder.newBuilder()
          .maximumWeight(16L << 20)
          .weigher((String pointer, AccountRefPage page) -> 128 + page.getSerializedSize())
          .build();

  final Cache<String, Boolean> accountPageWarnings =
      CacheBuilder.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(1)).build();

  record Entry(String repo, long sequence, String transactionId) {}

  // The limits bound estimated weight, not heap usage.
  static final long LOG_ENTRIES_WEIGHT = 64L << 20;
  static final long LOG_ENTRY_MAX_WEIGHT = LOG_ENTRIES_WEIGHT / 8;

  // Log entries are immutable under their keys.
  final Cache<Entry, LogEntry> logEntries =
      CacheBuilder.newBuilder()
          .maximumWeight(LOG_ENTRIES_WEIGHT)
          .weigher((Entry key, LogEntry entry) -> (int) Math.min(Integer.MAX_VALUE, weight(entry)))
          .build();

  static long weight(LogEntry entry) {
    return 256 + 2L * entry.getSerializedSize();
  }

  /** Caches a log entry unless it is oversized; never throws. */
  void keep(Entry key, LogEntry entry) {
    try {
      if (weight(entry) <= LOG_ENTRY_MAX_WEIGHT) {
        logEntries.put(key, entry);
      }
    } catch (RuntimeException failure) {
      logger.warn("Log entry {} of {} not kept", key.sequence(), key.repo(), failure);
    }
  }

  record VersionedManifest(Manifest manifest, String version) {}

  /** A manifest a peer announced and this node has not observed yet. */
  record Expectation(String version, long revision) {}

  private final ConcurrentHashMap<String, VersionedManifest> latest = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> validatedAtMillis = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Expectation> expected = new ConcurrentHashMap<>();

  VersionedManifest get(String key) {
    return latest.get(key);
  }

  /**
   * Records that this node compared its view of {@code key} with the store at {@code nowMillis}.
   */
  void markValidated(String key, long nowMillis) {
    if (latest.containsKey(key)) {
      validatedAtMillis.put(key, nowMillis);
    }
  }

  /**
   * Records an unchanged store response, clearing only the expectation captured before the read. A
   * hint received during the request may describe a later publication and must remain pending.
   */
  void markCurrent(String key, Expectation beforeRead, long nowMillis) {
    markValidated(key, nowMillis);
    if (beforeRead != null) {
      expected.remove(key, beforeRead);
    }
  }

  Expectation expectation(String key) {
    return expected.get(key);
  }

  /**
   * Whether a manifest for {@code key} is cached, was compared with the store less than {@code
   * maxAgeMillis} ago, and no peer has announced a newer one since; never true for a non-positive
   * age.
   */
  boolean validatedWithin(String key, long maxAgeMillis, long nowMillis) {
    if (expected.containsKey(key)) {
      return false;
    }
    Long at = validatedAtMillis.get(key);
    return at != null
        && latest.containsKey(key)
        && maxAgeMillis > 0
        && nowMillis - at < maxAgeMillis;
  }

  /**
   * Records that a peer published {@code version} at {@code revision} for {@code key}. Returns
   * false, recording nothing, when this node already holds that manifest or a newer one.
   */
  boolean expect(String key, String version, long revision) {
    VersionedManifest known = latest.get(key);
    if (known != null
        && (known.version().equals(version) || known.manifest().getRevision() >= revision)) {
      return false;
    }
    expected.merge(
        key,
        new Expectation(version, revision),
        (current, announced) -> announced.revision() >= current.revision() ? announced : current);
    return true;
  }

  /** Whether a peer announced a manifest for {@code key} that this node has not observed yet. */
  boolean expecting(String key) {
    return expected.containsKey(key);
  }

  /** Records {@code candidate} unless a newer revision is already known; returns the newest. */
  VersionedManifest offer(String key, VersionedManifest candidate) {
    VersionedManifest newest =
        latest.merge(
            key,
            candidate,
            (known, fresh) ->
                fresh.manifest().getRevision() >= known.manifest().getRevision() ? fresh : known);
    Expectation pending = expected.get(key);
    if (pending != null
        && (newest.version().equals(pending.version())
            || newest.manifest().getRevision() >= pending.revision())) {
      expected.remove(key, pending);
    }
    return newest;
  }

  void evict(String key) {
    latest.remove(key);
    validatedAtMillis.remove(key);
    expected.remove(key);
  }
}
