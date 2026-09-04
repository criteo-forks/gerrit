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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;

/** Immutable WalGerrit configuration read from the {@code [walgerrit]} section of gerrit.config. */
record WalGitConfiguration(
    BackendType backend,
    Path storagePath,
    String s3Bucket,
    String s3Region,
    URI s3Endpoint,
    String s3Prefix,
    boolean s3PathStyle,
    int s3MaxConnections,
    Duration s3ConnectTimeout,
    Duration s3SocketTimeout,
    int s3MaxAttempts,
    Path indexCursorPath,
    boolean indexTailerEnabled,
    Duration indexPollInterval,
    Duration manifestRevalidateInterval,
    boolean manifestRevalidateOnOpen,
    long indexReplayLimit,
    boolean indexRebuildOnStaleCursor,
    boolean compactionEnabled,
    int compactMinPacks,
    int compactGeometricFactor,
    long compactMaxPackSize,
    int compactMinReftables,
    Duration compactionLeaseDuration,
    boolean reclaimEnabled,
    Duration reclaimGrace,
    Duration reclaimInterval,
    long cacheSizeLimit) {
  /**
   * Longest time an open repository handle serves reads without another conditional manifest
   * read. Every handle also revalidates when it starts a ref transaction, and when it is opened
   * unless {@code manifestRevalidateOnOpen} is off, in which case an open reuses the node's view
   * when it was validated against the store less than this interval ago.
   */
  static final Duration DEFAULT_MANIFEST_REVALIDATE_INTERVAL = Duration.ofSeconds(1);

  /** Entries a node replays into its indexes at most before rebuilding them from scratch instead. */
  static final long DEFAULT_INDEX_REPLAY_LIMIT = 10_000;

  /** Pooled HTTP connections to S3 per node; a write is several requests and reads run in parallel. */
  static final int DEFAULT_S3_MAX_CONNECTIONS = 64;

  /** TCP connect timeout to S3. */
  static final Duration DEFAULT_S3_CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Longest stall on an established connection before the attempt fails; bounds hung transfers. */
  static final Duration DEFAULT_S3_SOCKET_TIMEOUT = Duration.ofSeconds(30);

  /** Attempts per S3 call, with the SDK's standard backoff; throttling and 5xx are retried. */
  static final int DEFAULT_S3_MAX_ATTEMPTS = 4;

  /** Smallest run of undersized packs worth rolling up into one; below it nothing is compacted. */
  static final int DEFAULT_COMPACT_MIN_PACKS = 8;

  /** Each live pack should be at least this many times the combined size of all smaller ones. */
  static final int DEFAULT_COMPACT_GEOMETRIC_FACTOR = 2;

  /** Packs above this size are never rewritten by compaction. */
  static final long DEFAULT_COMPACT_MAX_PACK_SIZE = 8L << 30;

  /** Reftable stack depth at which the whole stack is compacted into one table. */
  static final int DEFAULT_COMPACT_MIN_REFTABLES = 8;

  /** How long one node's compaction lease on a repository lasts without renewal. */
  static final Duration DEFAULT_COMPACTION_LEASE = Duration.ofMinutes(30);

  /**
   * How long a file that the manifest no longer references stays in the store before reclamation
   * deletes it; also the longest a reader may keep using a manifest it read earlier.
   */
  static final Duration DEFAULT_RECLAIM_GRACE = Duration.ofHours(24);

  /** How often a node sweeps every repository to reclaim files and queue overdue compactions. */
  static final Duration DEFAULT_RECLAIM_INTERVAL = Duration.ofHours(6);

  private static final String SECTION = "walgerrit";

  /** The local backend below the site's data directory, with every other setting at its default. */
  WalGitConfiguration(BackendType backend, Path storagePath) {
    this(
        backend,
        storagePath,
        null,
        "us-east-1",
        null,
        "",
        false,
        DEFAULT_S3_MAX_CONNECTIONS,
        DEFAULT_S3_CONNECT_TIMEOUT,
        DEFAULT_S3_SOCKET_TIMEOUT,
        DEFAULT_S3_MAX_ATTEMPTS,
        storagePath.resolve("index-events"),
        true,
        Duration.ofSeconds(5),
        DEFAULT_MANIFEST_REVALIDATE_INTERVAL,
        true,
        DEFAULT_INDEX_REPLAY_LIMIT,
        true,
        true,
        DEFAULT_COMPACT_MIN_PACKS,
        DEFAULT_COMPACT_GEOMETRIC_FACTOR,
        DEFAULT_COMPACT_MAX_PACK_SIZE,
        DEFAULT_COMPACT_MIN_REFTABLES,
        DEFAULT_COMPACTION_LEASE,
        true,
        DEFAULT_RECLAIM_GRACE,
        DEFAULT_RECLAIM_INTERVAL,
        0);
  }

  static WalGitConfiguration from(Config config, Path sitePath) {
    Path storagePath = path(config, sitePath, "storagePath", "data/walgerrit");
    String endpoint = config.getString(SECTION, null, "s3Endpoint");
    WalGitConfiguration configuration =
        new WalGitConfiguration(
            BackendType.parse(Optional.ofNullable(config.getString(SECTION, null, "backend")).orElse("local")),
            storagePath,
            config.getString(SECTION, null, "s3Bucket"),
            Optional.ofNullable(config.getString(SECTION, null, "s3Region")).orElse("us-east-1"),
            endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint),
            Optional.ofNullable(config.getString(SECTION, null, "s3Prefix")).orElse(""),
            config.getBoolean(SECTION, null, "s3PathStyle", false),
            config.getInt(SECTION, null, "s3MaxConnections", DEFAULT_S3_MAX_CONNECTIONS),
            duration(config, "s3ConnectTimeout", DEFAULT_S3_CONNECT_TIMEOUT),
            duration(config, "s3SocketTimeout", DEFAULT_S3_SOCKET_TIMEOUT),
            config.getInt(SECTION, null, "s3MaxAttempts", DEFAULT_S3_MAX_ATTEMPTS),
            path(config, sitePath, "indexCursorPath", "data/walgerrit-index-events"),
            config.getBoolean(SECTION, null, "indexTailerEnabled", true),
            duration(config, "indexPollInterval", Duration.ofSeconds(5)),
            duration(config, "manifestRevalidateInterval", DEFAULT_MANIFEST_REVALIDATE_INTERVAL),
            config.getBoolean(SECTION, null, "manifestRevalidateOnOpen", true),
            config.getLong(SECTION, null, "indexReplayLimit", DEFAULT_INDEX_REPLAY_LIMIT),
            config.getBoolean(SECTION, null, "indexRebuildOnStaleCursor", true),
            config.getBoolean(SECTION, null, "compactionEnabled", true),
            config.getInt(SECTION, null, "compactMinPacks", DEFAULT_COMPACT_MIN_PACKS),
            config.getInt(SECTION, null, "compactGeometricFactor", DEFAULT_COMPACT_GEOMETRIC_FACTOR),
            config.getLong(SECTION, null, "compactMaxPackSize", DEFAULT_COMPACT_MAX_PACK_SIZE),
            config.getInt(SECTION, null, "compactMinReftables", DEFAULT_COMPACT_MIN_REFTABLES),
            duration(config, "compactionLeaseDuration", DEFAULT_COMPACTION_LEASE),
            config.getBoolean(SECTION, null, "reclaimEnabled", true),
            duration(config, "reclaimGrace", DEFAULT_RECLAIM_GRACE),
            duration(config, "reclaimInterval", DEFAULT_RECLAIM_INTERVAL),
            config.getLong(SECTION, null, "cacheSizeLimit", 0));
    configuration.validate();
    return configuration;
  }

  private static Path path(Config config, Path sitePath, String key, String defaultValue) {
    return sitePath
        .resolve(Optional.ofNullable(config.getString(SECTION, null, key)).orElse(defaultValue))
        .toAbsolutePath()
        .normalize();
  }

  private static Duration duration(Config config, String key, Duration defaultValue) {
    return Duration.ofMillis(
        config.getTimeUnit(SECTION, null, key, defaultValue.toMillis(), TimeUnit.MILLISECONDS));
  }

  private void validate() {
    require(backend != BackendType.S3 || (s3Bucket != null && !s3Bucket.isBlank()),
        "s3Bucket is required for the s3 backend");
    require(!s3Prefix.startsWith("/") && !s3Prefix.contains("..") && s3Prefix.indexOf('\\') < 0,
        "s3Prefix is invalid: " + s3Prefix);
    require(s3MaxConnections >= 1, "s3MaxConnections must be at least 1");
    require(isPositive(s3ConnectTimeout), "s3ConnectTimeout must be positive");
    require(isPositive(s3SocketTimeout), "s3SocketTimeout must be positive");
    require(s3MaxAttempts >= 1, "s3MaxAttempts must be at least 1");
    require(isPositive(indexPollInterval), "indexPollInterval must be positive");
    require(!manifestRevalidateInterval.isNegative(), "manifestRevalidateInterval must be zero or positive");
    require(indexReplayLimit >= 1, "indexReplayLimit must be at least 1");
    require(compactMinPacks >= 2, "compactMinPacks must be at least 2");
    require(compactGeometricFactor >= 2, "compactGeometricFactor must be at least 2");
    require(compactMaxPackSize >= 1, "compactMaxPackSize must be positive");
    require(compactMinReftables >= 2, "compactMinReftables must be at least 2");
    require(isPositive(compactionLeaseDuration), "compactionLeaseDuration must be positive");
    require(!reclaimGrace.isNegative(), "reclaimGrace must be zero or positive");
    require(isPositive(reclaimInterval), "reclaimInterval must be positive");
    require(cacheSizeLimit >= 0, "cacheSizeLimit must be zero or positive");
  }

  private static boolean isPositive(Duration duration) {
    return !duration.isZero() && !duration.isNegative();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException("walgerrit." + message);
    }
  }
}
