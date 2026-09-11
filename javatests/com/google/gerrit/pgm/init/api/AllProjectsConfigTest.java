// Copyright (C) 2018 The Android Open Source Project
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
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.AllProjectsConfigProvider;
import com.google.gerrit.server.config.FileBasedAllProjectsConfigProvider;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.securestore.testing.InMemorySecureStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AllProjectsConfigTest {
  private static final String ALL_PROJECTS = "All-The-Projects";

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Mock ConsoleUI ui;
  @Mock GitRepositoryManager delegate;

  private SitePaths sitePaths;
  private AllProjectsConfig allProjectsConfig;
  private File allProjectsRepoFile;
  private GitRepositoryManagerOnInit repositoryManager;

  @Before
  public void setUp() throws Exception {
    sitePaths = new SitePaths(temporaryFolder.newFolder().toPath());
    Files.createDirectories(sitePaths.etc_dir);

    Path gitPath = sitePaths.resolve("git");

    StoredConfig gerritConfig =
        new FileBasedConfig(
            sitePaths.resolve("etc").resolve("gerrit.config").toFile(), FS.DETECTED);
    gerritConfig.load();
    gerritConfig.setString("gerrit", null, "basePath", gitPath.toAbsolutePath().toString());
    gerritConfig.setString("gerrit", null, "allProjects", ALL_PROJECTS);
    gerritConfig.save();

    Files.createDirectories(sitePaths.resolve("git"));
    allProjectsRepoFile = gitPath.resolve("All-The-Projects.git").toFile();
    try (Repository repo = new FileRepository(allProjectsRepoFile)) {
      repo.create(true);
    }

    InMemorySecureStore secureStore = new InMemorySecureStore();
    InitFlags flags = new InitFlags(sitePaths, secureStore, ImmutableList.of(), false);
    Section.Factory sections =
        (name, subsection) -> new Section(flags, sitePaths, secureStore, ui, name, subsection);
    AllProjectsConfigProvider configProvider = new FileBasedAllProjectsConfigProvider(sitePaths);

    repositoryManager = new GitRepositoryManagerOnInit(flags, sitePaths);
    allProjectsConfig =
        new AllProjectsConfig(
            new AllProjectsNameOnInitProvider(sections), configProvider, flags, repositoryManager);
  }

  @Test
  public void noBaseConfig() throws Exception {
    assertThat(getConfig().getString("foo", null, "bar")).isNull();

    try (Repository repo = new FileRepository(allProjectsRepoFile);
        TestRepository<Repository> tr = new TestRepository<>(repo)) {
      tr.branch("refs/meta/config").commit().add("project.config", "[foo]\nbar = baz").create();
    }

    assertThat(getConfig().getString("foo", null, "bar")).isEqualTo("baz");
  }

  @Test
  public void baseConfig() throws Exception {
    assertThat(getConfig().getString("foo", null, "bar")).isNull();

    Path baseConfigPath = sitePaths.etc_dir.resolve(ALL_PROJECTS).resolve("project.config");
    Files.createDirectories(baseConfigPath.getParent());
    Files.write(baseConfigPath, ImmutableList.of("[foo]", "bar = base"));

    assertThat(getConfig().getString("foo", null, "bar")).isEqualTo("base");

    try (Repository repo = new FileRepository(allProjectsRepoFile);
        TestRepository<Repository> tr = new TestRepository<>(repo)) {
      tr.branch("refs/meta/config").commit().add("project.config", "[foo]\nbar = baz").create();
    }

    assertThat(getConfig().getString("foo", null, "bar")).isEqualTo("baz");
  }

  private Config getConfig() throws IOException, ConfigInvalidException {
    return allProjectsConfig.load().getConfig();
  }

  @Test
  public void readsAndWritesConfiguredRepositoryDespiteStaleLocalCopy() throws Exception {
    ObjectId localRevision;
    try (TestRepository<Repository> local =
        new TestRepository<>(new FileRepository(allProjectsRepoFile))) {
      localRevision =
          local
              .branch("refs/meta/config")
              .commit()
              .add("project.config", "[foo]\nbar = local")
              .create();
    }
    assertThat(getConfig().getString("foo", null, "bar")).isEqualTo("local");

    try (TestRepository<InMemoryRepository> remote =
        new TestRepository<>(new InMemoryRepository(new DfsRepositoryDescription(ALL_PROJECTS)))) {
      remote
          .branch("refs/meta/config")
          .commit()
          .add("project.config", "[foo]\nbar = remote")
          .create();
      when(delegate.openRepository(Project.nameKey(ALL_PROJECTS)))
          .thenAnswer(
              invocation -> {
                remote.getRepository().incrementOpen();
                return remote.getRepository();
              });
      repositoryManager.setDelegate(delegate);

      assertThat(getConfig().getString("foo", null, "bar")).isEqualTo("remote");
      allProjectsConfig.getConfig().setString("foo", null, "bar", "updated");
      allProjectsConfig.save("test", "Update configured repository");
      assertThat(getConfig().getString("foo", null, "bar")).isEqualTo("updated");

      try (Repository local = new FileRepository(allProjectsRepoFile)) {
        assertThat(local.exactRef("refs/meta/config").getObjectId()).isEqualTo(localRevision);
      }
    }
  }

  @Test
  public void configuredRepositoryFailureDoesNotReadLocalCopy() throws Exception {
    IOException failure = new IOException("backend unavailable");
    when(delegate.openRepository(Project.nameKey(ALL_PROJECTS))).thenThrow(failure);
    repositoryManager.setDelegate(delegate);

    assertThat(assertThrows(IOException.class, () -> allProjectsConfig.load()))
        .isSameInstanceAs(failure);
  }
}
