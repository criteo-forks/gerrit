// Copyright (C) 2026 The Android Open Source Project
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

package com.google.gerrit.acceptance;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Strings;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Config;

/**
 * Runs the acceptance suite on the WalGerrit repository backend.
 *
 * <p>When {@code GERRIT_WALGERRIT_JAR} names the built WalGerrit library, the in-memory test
 * server binds {@code GitRepositoryManager} to WalGerrit's local-filesystem backend instead of the
 * in-memory manager. The library is loaded from the jar at runtime so the acceptance framework
 * needs no build dependency on it. {@code GERRIT_WALGERRIT_STORAGE} optionally names a directory,
 * for example a tmpfs, that holds the repositories instead of the test site. The index-event tailer
 * stays off: a single node indexes its own writes. Tests annotated {@code @UseLocalDisk} keep
 * running on the filesystem backend they ask for.
 */
final class WalGerritTestSupport {
  private static final String JAR_ENV = "GERRIT_WALGERRIT_JAR";
  private static final String STORAGE_ENV = "GERRIT_WALGERRIT_STORAGE";
  /** Set to {@code aggressive} to compact after every couple of writes, racing the tests. */
  private static final String COMPACTION_ENV = "GERRIT_WALGERRIT_COMPACTION";
  private static final String DB_MODULE = "dev.walgerrit.WalGitModule";

  /**
   * Loaded once per test JVM. Every server start in a test group runs in the same JVM, and a class
   * loader per start would keep one copy of the library's classes alive per started server.
   */
  private static Class<? extends Module> moduleClass;

  private WalGerritTestSupport() {}

  static boolean enabled() {
    return !Strings.isNullOrEmpty(System.getenv(JAR_ENV));
  }

  /**
   * Wraps the in-memory server's database module so WalGerrit provides the repository manager,
   * and points the server configuration at a WalGerrit store for this site.
   */
  static Module overrideRepositoryManager(Config cfg, Path site, Module databaseModule)
      throws Exception {
    if (!enabled()) {
      return databaseModule;
    }
    configure(cfg, site);
    Module walgerrit = moduleClass().getDeclaredConstructor().newInstance();
    return Modules.override(databaseModule).with(walgerrit);
  }

  private static synchronized Class<? extends Module> moduleClass() throws Exception {
    if (moduleClass == null) {
      URLClassLoader loader =
          new URLClassLoader(
              new URL[] {jar().toUri().toURL()}, WalGerritTestSupport.class.getClassLoader());
      moduleClass = Class.forName(DB_MODULE, true, loader).asSubclass(Module.class);
    }
    return moduleClass;
  }

  private static void configure(Config cfg, Path site) throws IOException {
    Path storage = storageRoot(site);
    cfg.setString("walgerrit", null, "backend", "local");
    cfg.setString("walgerrit", null, "storagePath", storage.toString());
    cfg.setString(
        "walgerrit",
        null,
        "indexCursorPath",
        storage.resolveSibling(storage.getFileName() + "-cursors").toString());
    cfg.setBoolean("walgerrit", null, "indexTailerEnabled", false);
    if ("aggressive".equals(System.getenv(COMPACTION_ENV))) {
      // Every repository is compacted a few writes after it is touched, on the server's own
      // compaction thread, so reads and writes throughout the suite race real compactions.
      cfg.setInt("walgerrit", null, "compactMinPacks", 2);
      cfg.setInt("walgerrit", null, "compactMinReftables", 2);
    }
  }

  private static Path jar() {
    Path jar = Path.of(System.getenv(JAR_ENV));
    checkArgument(Files.isRegularFile(jar), "%s does not name a file: %s", JAR_ENV, jar);
    return jar;
  }

  /** Stable per site so a server restarted within a test keeps its repositories. */
  private static Path storageRoot(Path site) throws IOException {
    String root = System.getenv(STORAGE_ENV);
    if (Strings.isNullOrEmpty(root)) {
      return site.resolve("walgerrit").toAbsolutePath().normalize();
    }
    Path storage = Path.of(root).resolve(site.toAbsolutePath().getFileName().toString());
    Files.createDirectories(storage);
    return storage.toAbsolutePath().normalize();
  }
}
