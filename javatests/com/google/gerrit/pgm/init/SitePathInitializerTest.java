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

package com.google.gerrit.pgm.init;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.GitRepositoryManagerOnInit;
import com.google.gerrit.pgm.init.api.InitFlags;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.gerrit.pgm.init.api.Section;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.securestore.testing.InMemorySecureStore;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SitePathInitializerTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void switchesExistingInitHelpersBeforeInvokingPostRun() throws Exception {
    SitePaths site = new SitePaths(temporaryFolder.newFolder().toPath());
    InitFlags flags = new InitFlags(site, new InMemorySecureStore(), ImmutableList.of(), false);
    Injector initInjector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(SitePaths.class).toInstance(site);
                bind(InitFlags.class).toInstance(flags);
                bind(RepositoryStep.class).in(Singleton.class);
                bind(InitStep.class).annotatedWith(Names.named("test")).to(RepositoryStep.class);
              }
            });
    GitRepositoryManagerOnInit initManager =
        initInjector.getInstance(GitRepositoryManagerOnInit.class);
    RepositoryStep step = initInjector.getInstance(RepositoryStep.class);
    GitRepositoryManager configuredManager = mock(GitRepositoryManager.class);
    when(configuredManager.getRepositoryStatus(Project.nameKey("remote")))
        .thenReturn(GitRepositoryManager.Status.ACTIVE);
    Injector systemInjector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(GitRepositoryManager.class).toInstance(configuredManager);
              }
            });
    SitePathInitializer initializer =
        new SitePathInitializer(
            initInjector,
            ConsoleUI.getInstance(true),
            flags,
            site,
            mock(Section.Factory.class),
            initManager,
            null);

    initializer.postRun(systemInjector);

    assertThat(step.status).isEqualTo(GitRepositoryManager.Status.ACTIVE);
  }

  private static class RepositoryStep implements InitStep {
    private final GitRepositoryManagerOnInit repositoryManager;
    private GitRepositoryManager.Status status;

    @Inject
    RepositoryStep(GitRepositoryManagerOnInit repositoryManager) {
      this.repositoryManager = repositoryManager;
    }

    @Override
    public void run() {}

    @Override
    public void postRun() {
      status = repositoryManager.getRepositoryStatus(Project.nameKey("remote"));
    }
  }
}
