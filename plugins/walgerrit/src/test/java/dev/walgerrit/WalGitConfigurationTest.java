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

import java.nio.file.Path;
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
  void rejectsUnknownBackend() {
    Config config = new Config();
    config.setString("walgerrit", null, "backend", "s3");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> WalGitConfiguration.from(config, sitePath));

    assertTrue(exception.getMessage().contains("Unsupported walgerrit.backend 's3'"));
  }
}
