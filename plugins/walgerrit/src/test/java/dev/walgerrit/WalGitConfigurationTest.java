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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.jgit.lib.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalGitConfigurationTest {
  @TempDir Path sitePath;

  @Test
  void defaultsToLocalWalAndSiteDataDirectory() {
    WalGitConfiguration configuration = WalGitConfiguration.from(new Config(), sitePath);

    assertEquals(BackendType.LOCAL, configuration.backend());
    assertEquals(
        sitePath.resolve("data/walgerrit").toAbsolutePath().normalize(),
        configuration.storagePath());
    assertEquals(
        sitePath.resolve("data/walgerrit-index-events").toAbsolutePath().normalize(),
        configuration.indexCursorPath());
    assertTrue(configuration.indexTailerEnabled());
    assertEquals(Duration.ofSeconds(5), configuration.indexPollInterval());
    assertEquals(Duration.ofSeconds(1), configuration.manifestRevalidateInterval());
    assertEquals(256, configuration.logSegmentEntries());
    assertEquals(Duration.ofDays(30), configuration.logRetention());
    assertEquals(10_000, configuration.logRetainEntries());
    assertTrue(configuration.indexRebuildOnStaleCursor());
  }

  @Test
  void configuresLogFoldingAndRebuildBehaviour() {
    Config config = new Config();
    config.setString("walgerrit", null, "logSegmentEntries", "64");
    config.setString("walgerrit", null, "logRetention", "7 days");
    config.setString("walgerrit", null, "logRetainEntries", "500");
    config.setBoolean("walgerrit", null, "indexRebuildOnStaleCursor", false);

    WalGitConfiguration configuration = WalGitConfiguration.from(config, sitePath);

    assertEquals(64, configuration.logSegmentEntries());
    assertEquals(Duration.ofDays(7), configuration.logRetention());
    assertEquals(500, configuration.logRetainEntries());
    assertFalse(configuration.indexRebuildOnStaleCursor());
  }

  @Test
  void rejectsDegenerateFoldSettings() {
    Config tooSmall = new Config();
    tooSmall.setString("walgerrit", null, "logSegmentEntries", "1");
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> WalGitConfiguration.from(tooSmall, sitePath))
            .getMessage()
            .contains("logSegmentEntries"));

    Config nothingRetained = new Config();
    nothingRetained.setString("walgerrit", null, "logRetainEntries", "0");
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> WalGitConfiguration.from(nothingRetained, sitePath))
            .getMessage()
            .contains("logRetainEntries"));
  }

  @Test
  void acceptsCaseInsensitiveBackendAndRelativeStoragePath() {
    Config config = new Config();
    config.setString("walgerrit", null, "backend", "LoCaL");
    config.setString("walgerrit", null, "storagePath", "var/wal");

    WalGitConfiguration configuration = WalGitConfiguration.from(config, sitePath);

    assertEquals(BackendType.LOCAL, configuration.backend());
    assertEquals(
        sitePath.resolve("var/wal").toAbsolutePath().normalize(), configuration.storagePath());
  }

  @Test
  void configuresS3Backend() {
    Config config = new Config();
    config.setString("walgerrit", null, "backend", "s3");
    config.setString("walgerrit", null, "s3Bucket", "walgerrit-test");
    config.setString("walgerrit", null, "s3Region", "eu-west-3");
    config.setString("walgerrit", null, "s3Endpoint", "http://127.0.0.1:9000");
    config.setString("walgerrit", null, "s3Prefix", "test-prefix");
    config.setBoolean("walgerrit", null, "s3PathStyle", true);
    config.setBoolean("walgerrit", null, "indexTailerEnabled", false);
    config.setString("walgerrit", null, "indexPollInterval", "250 ms");
    config.setString("walgerrit", null, "indexCursorPath", "data/index-cursors");
    config.setString("walgerrit", null, "manifestRevalidateInterval", "250 ms");

    WalGitConfiguration configuration = WalGitConfiguration.from(config, sitePath);

    assertEquals(BackendType.S3, configuration.backend());
    assertEquals("walgerrit-test", configuration.s3Bucket());
    assertEquals("eu-west-3", configuration.s3Region());
    assertEquals("http://127.0.0.1:9000", configuration.s3Endpoint().toString());
    assertEquals("test-prefix", configuration.s3Prefix());
    assertTrue(configuration.s3PathStyle());
    assertEquals(
        sitePath.resolve("data/index-cursors").toAbsolutePath().normalize(),
        configuration.indexCursorPath());
    assertFalse(configuration.indexTailerEnabled());
    assertEquals(Duration.ofMillis(250), configuration.indexPollInterval());
    assertEquals(Duration.ofMillis(250), configuration.manifestRevalidateInterval());
  }

  @Test
  void zeroManifestRevalidateIntervalDisablesPeriodicRevalidation() {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");

    WalGitConfiguration configuration = WalGitConfiguration.from(config, sitePath);

    assertEquals(Duration.ZERO, configuration.manifestRevalidateInterval());
  }

  @Test
  void indexPollIntervalMustBePositive() {
    Config config = new Config();
    config.setString("walgerrit", null, "indexPollInterval", "0 ms");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> WalGitConfiguration.from(config, sitePath));

    assertTrue(exception.getMessage().contains("indexPollInterval must be positive"));
  }

  @Test
  void s3BackendRequiresBucket() {
    Config config = new Config();
    config.setString("walgerrit", null, "backend", "s3");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> WalGitConfiguration.from(config, sitePath));

    assertTrue(exception.getMessage().contains("walgerrit.s3Bucket is required"));
  }

  @Test
  void rejectsUnknownBackend() {
    Config config = new Config();
    config.setString("walgerrit", null, "backend", "unknown");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> WalGitConfiguration.from(config, sitePath));

    assertTrue(exception.getMessage().contains("Unsupported walgerrit.backend 'unknown'"));
  }
}
