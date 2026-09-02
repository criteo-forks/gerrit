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

/** Immutable WalGerrit configuration read from {@code gerrit.config}. */
record WalGitConfiguration(
    BackendType backend,
    Path storagePath,
    String s3Bucket,
    String s3Region,
    URI s3Endpoint,
    String s3Prefix,
    boolean s3PathStyle,
    Path indexCursorPath,
    boolean indexTailerEnabled,
    Duration indexPollInterval,
    Duration manifestRevalidateInterval,
    int logSegmentEntries,
    Duration logRetention,
    long logRetainEntries,
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
   * read. Every handle also revalidates when it is opened and when it starts a ref transaction.
   */
  static final Duration DEFAULT_MANIFEST_REVALIDATE_INTERVAL = Duration.ofSeconds(1);

  /** Consecutive single-entry log segments merged into one segment by a fold. */
  static final int DEFAULT_LOG_SEGMENT_ENTRIES = 256;

  /** Minimum age of a log segment before retention may drop it below the manifest's floor. */
  static final Duration DEFAULT_LOG_RETENTION = Duration.ofDays(30);

  /** Minimum number of newer entries the manifest keeps referencing when a segment is dropped. */
  static final long DEFAULT_LOG_RETAIN_ENTRIES = 10_000;

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

  /** How often a node scans every repository for reclaimable files. */
  static final Duration DEFAULT_RECLAIM_INTERVAL = Duration.ofHours(6);

  private static final String SECTION = "walgerrit";
  private static final String BACKEND_KEY = "backend";
  private static final String STORAGE_PATH_KEY = "storagePath";

  WalGitConfiguration(BackendType backend, Path storagePath) {
    this(
        backend,
        storagePath,
        null,
        "us-east-1",
        null,
        "",
        false,
        storagePath.resolve("index-events"),
        true,
        Duration.ofSeconds(5),
        DEFAULT_MANIFEST_REVALIDATE_INTERVAL,
        DEFAULT_LOG_SEGMENT_ENTRIES,
        DEFAULT_LOG_RETENTION,
        DEFAULT_LOG_RETAIN_ENTRIES,
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
    String configuredBackend =
        Optional.ofNullable(config.getString(SECTION, null, BACKEND_KEY)).orElse("local");
    String configuredStoragePath = config.getString(SECTION, null, STORAGE_PATH_KEY);
    Path storagePath =
        configuredStoragePath == null
            ? sitePath.resolve("data/walgerrit")
            : sitePath.resolve(configuredStoragePath).normalize();
    String configuredIndexCursorPath = config.getString(SECTION, null, "indexCursorPath");
    Path indexCursorPath =
        configuredIndexCursorPath == null
            ? sitePath.resolve("data/walgerrit-index-events")
            : sitePath.resolve(configuredIndexCursorPath).normalize();
    BackendType backend = BackendType.parse(configuredBackend);
    String endpoint = config.getString(SECTION, null, "s3Endpoint");
    WalGitConfiguration configuration =
        new WalGitConfiguration(
            backend,
            storagePath.toAbsolutePath().normalize(),
            config.getString(SECTION, null, "s3Bucket"),
            Optional.ofNullable(config.getString(SECTION, null, "s3Region"))
                .orElse("us-east-1"),
            endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint),
            Optional.ofNullable(config.getString(SECTION, null, "s3Prefix")).orElse(""),
            config.getBoolean(SECTION, null, "s3PathStyle", false),
            indexCursorPath.toAbsolutePath().normalize(),
            config.getBoolean(SECTION, null, "indexTailerEnabled", true),
            Duration.ofMillis(
                config.getTimeUnit(
                    SECTION,
                    null,
                    "indexPollInterval",
                    TimeUnit.SECONDS.toMillis(5),
                    TimeUnit.MILLISECONDS)),
            Duration.ofMillis(
                config.getTimeUnit(
                    SECTION,
                    null,
                    "manifestRevalidateInterval",
                    DEFAULT_MANIFEST_REVALIDATE_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS)),
            config.getInt(SECTION, null, "logSegmentEntries", DEFAULT_LOG_SEGMENT_ENTRIES),
            Duration.ofMillis(
                config.getTimeUnit(
                    SECTION,
                    null,
                    "logRetention",
                    DEFAULT_LOG_RETENTION.toMillis(),
                    TimeUnit.MILLISECONDS)),
            config.getLong(SECTION, null, "logRetainEntries", DEFAULT_LOG_RETAIN_ENTRIES),
            config.getBoolean(SECTION, null, "indexRebuildOnStaleCursor", true),
            config.getBoolean(SECTION, null, "compactionEnabled", true),
            config.getInt(SECTION, null, "compactMinPacks", DEFAULT_COMPACT_MIN_PACKS),
            config.getInt(
                SECTION, null, "compactGeometricFactor", DEFAULT_COMPACT_GEOMETRIC_FACTOR),
            config.getLong(SECTION, null, "compactMaxPackSize", DEFAULT_COMPACT_MAX_PACK_SIZE),
            config.getInt(SECTION, null, "compactMinReftables", DEFAULT_COMPACT_MIN_REFTABLES),
            Duration.ofMillis(
                config.getTimeUnit(
                    SECTION,
                    null,
                    "compactionLeaseDuration",
                    DEFAULT_COMPACTION_LEASE.toMillis(),
                    TimeUnit.MILLISECONDS)),
            config.getBoolean(SECTION, null, "reclaimEnabled", true),
            Duration.ofMillis(
                config.getTimeUnit(
                    SECTION,
                    null,
                    "reclaimGrace",
                    DEFAULT_RECLAIM_GRACE.toMillis(),
                    TimeUnit.MILLISECONDS)),
            Duration.ofMillis(
                config.getTimeUnit(
                    SECTION,
                    null,
                    "reclaimInterval",
                    DEFAULT_RECLAIM_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS)),
            config.getLong(SECTION, null, "cacheSizeLimit", 0));
    configuration.validate();
    return configuration;
  }

  private void validate() {
    if (backend == BackendType.S3 && (s3Bucket == null || s3Bucket.isBlank())) {
      throw new IllegalArgumentException("walgerrit.s3Bucket is required for the s3 backend");
    }
    if (s3Prefix.startsWith("/") || s3Prefix.contains("..") || s3Prefix.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Invalid walgerrit.s3Prefix: " + s3Prefix);
    }
    if (indexPollInterval.isZero() || indexPollInterval.isNegative()) {
      throw new IllegalArgumentException("walgerrit.indexPollInterval must be positive");
    }
    if (manifestRevalidateInterval.isNegative()) {
      throw new IllegalArgumentException(
          "walgerrit.manifestRevalidateInterval must be zero or positive");
    }
    if (logSegmentEntries < 2) {
      throw new IllegalArgumentException("walgerrit.logSegmentEntries must be at least 2");
    }
    if (logRetention.isNegative()) {
      throw new IllegalArgumentException("walgerrit.logRetention must be zero or positive");
    }
    if (logRetainEntries < 1) {
      throw new IllegalArgumentException("walgerrit.logRetainEntries must be at least 1");
    }
    if (compactMinPacks < 2) {
      throw new IllegalArgumentException("walgerrit.compactMinPacks must be at least 2");
    }
    if (compactGeometricFactor < 2) {
      throw new IllegalArgumentException("walgerrit.compactGeometricFactor must be at least 2");
    }
    if (compactMaxPackSize < 1) {
      throw new IllegalArgumentException("walgerrit.compactMaxPackSize must be positive");
    }
    if (compactMinReftables < 2) {
      throw new IllegalArgumentException("walgerrit.compactMinReftables must be at least 2");
    }
    if (compactionLeaseDuration.isZero() || compactionLeaseDuration.isNegative()) {
      throw new IllegalArgumentException("walgerrit.compactionLeaseDuration must be positive");
    }
    if (reclaimGrace.isNegative()) {
      throw new IllegalArgumentException("walgerrit.reclaimGrace must be zero or positive");
    }
    if (reclaimInterval.isZero() || reclaimInterval.isNegative()) {
      throw new IllegalArgumentException("walgerrit.reclaimInterval must be positive");
    }
    if (cacheSizeLimit < 0) {
      throw new IllegalArgumentException("walgerrit.cacheSizeLimit must be zero or positive");
    }
  }
}
