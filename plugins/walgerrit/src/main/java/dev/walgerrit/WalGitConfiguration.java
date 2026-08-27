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

import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;

/** Immutable WalGerrit configuration read from {@code gerrit.config}. */
record WalGitConfiguration(BackendType backend, Path storagePath) {
  private static final String SECTION = "walgerrit";
  private static final String BACKEND_KEY = "backend";
  private static final String STORAGE_PATH_KEY = "storagePath";

  static WalGitConfiguration from(Config config, Path sitePath) {
    String configuredBackend =
        Optional.ofNullable(config.getString(SECTION, null, BACKEND_KEY)).orElse("local");
    String configuredStoragePath = config.getString(SECTION, null, STORAGE_PATH_KEY);
    Path storagePath =
        configuredStoragePath == null
            ? sitePath.resolve("data/walgerrit")
            : sitePath.resolve(configuredStoragePath).normalize();
    return new WalGitConfiguration(
        BackendType.parse(configuredBackend), storagePath.toAbsolutePath().normalize());
  }
}
