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

package com.google.gerrit.pgm.init.api;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.securestore.testing.InMemorySecureStore;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GitRepositoryManagerOnInitTest {
  private static final Project.NameKey PROJECT = Project.nameKey("test/project");

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Mock GitRepositoryManager delegate;
  @Mock Repository repository;

  private GitRepositoryManagerOnInit repositoryManager;

  @Before
  public void setUp() throws Exception {
    SitePaths site = new SitePaths(temporaryFolder.newFolder().toPath());
    InitFlags flags = new InitFlags(site, new InMemorySecureStore(), ImmutableList.of(), false);
    flags.cfg.setString("gerrit", null, "basePath", "git");
    repositoryManager = new GitRepositoryManagerOnInit(flags, site);
  }

  @Test(expected = RepositoryNotFoundException.class)
  public void missingFallbackRepositoryIsReportedAsNotFound() throws Exception {
    repositoryManager.openRepository(PROJECT);
  }

  @Test
  public void delegatesAfterSystemInjectorIsAvailable() throws Exception {
    when(delegate.openRepository(PROJECT)).thenReturn(repository);

    repositoryManager.setDelegate(delegate);

    assertThat(repositoryManager.openRepository(PROJECT)).isSameInstanceAs(repository);
    verify(delegate).openRepository(PROJECT);
  }
}
