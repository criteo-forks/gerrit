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

package com.google.gerrit.pgm;

import com.google.gerrit.common.SiteLibraryLoaderUtil;
import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.pgm.util.SiteProgram;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Injector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;

/**
 * Imports a tree of bare repositories into the WalGerrit storage backend.
 *
 * <p>Usage: {@code java -jar gerrit.war walgerrit-import -d SITE --source DIR [options]}. The site
 * must install {@code dev.walgerrit.WalGitModule} as its database module and carry the WalGerrit
 * library in {@code lib/}; the importer itself lives in that library and is loaded from it, the
 * same way the module is, so this program has no compile-time dependency on it. Run {@code --help}
 * after the site path for the importer's own options.
 */
public class WalgerritImport extends SiteProgram {
  private static final String IMPORTER = "dev.walgerrit.RepositoryImporter";

  @Argument(index = 0, multiValued = true, metaVar = "IMPORTER-ARG", usage = "see --help")
  private List<String> importerArgs = new ArrayList<>();

  @Override
  public int run() throws Exception {
    mustHaveValidSite();
    Injector dbInjector = createDbInjector();
    SiteLibraryLoaderUtil.loadSiteLib(dbInjector.getInstance(SitePaths.class).lib_dir);
    LifecycleManager manager = new LifecycleManager();
    manager.add(dbInjector);
    manager.start();
    try {
      GitRepositoryManager repositories = dbInjector.getInstance(GitRepositoryManager.class);
      Class<?> importer = Class.forName(IMPORTER, true, Thread.currentThread().getContextClassLoader());
      Method entry = importer.getMethod("run", GitRepositoryManager.class, String[].class);
      Object exitCode = entry.invoke(null, repositories, importerArgs.toArray(new String[0]));
      return (Integer) exitCode;
    } finally {
      manager.stop();
    }
  }
}
