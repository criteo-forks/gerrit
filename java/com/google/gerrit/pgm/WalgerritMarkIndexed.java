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

/**
 * Records on this node that its indexes reflect the current head of every WalGerrit repository.
 *
 * <p>Usage: {@code java -jar gerrit.war walgerrit-mark-indexed -d SITE}, after an offline {@code
 * reindex} and before the daemon starts. Like {@code walgerrit-import}, the implementation lives in
 * the WalGerrit library in the site's {@code lib/} and is loaded from there.
 */
public class WalgerritMarkIndexed extends SiteProgram {
  private static final String SEEDER = "dev.walgerrit.IndexCursorSeeder";

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
      Class<?> seeder = Class.forName(SEEDER, true, Thread.currentThread().getContextClassLoader());
      Method entry = seeder.getMethod("run", GitRepositoryManager.class, String[].class);
      Object exitCode = entry.invoke(null, repositories, new String[0]);
      return (Integer) exitCode;
    } finally {
      manager.stop();
    }
  }
}
