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
    Duration indexPollInterval) {
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
        Duration.ofSeconds(5));
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
                    TimeUnit.MILLISECONDS)));
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
  }
}
