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

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.RepositoryExistsException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.NavigableSet;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

/** Gerrit's repository-manager entry point for WalGit-backed storage. */
@Singleton
public final class WalGitRepositoryManager implements GitRepositoryManager {
  private final StorageLayout storage;

  @Inject
  WalGitRepositoryManager(@GerritServerConfig Config serverConfig, @SitePath Path sitePath) {
    this(WalGitConfiguration.from(serverConfig, sitePath));
  }

  WalGitRepositoryManager(WalGitConfiguration configuration) {
    if (configuration.backend() != BackendType.LOCAL) {
      throw new IllegalArgumentException("No implementation for " + configuration.backend());
    }
    storage = new StorageLayout(configuration.storagePath());
  }

  @Override
  public Status getRepositoryStatus(Project.NameKey name) {
    try {
      return storage.manifestStore(name).exists() ? Status.ACTIVE : Status.NON_EXISTENT;
    } catch (IOException exception) {
      return Status.UNAVAILABLE;
    }
  }

  @Override
  public Repository openRepository(Project.NameKey name)
      throws RepositoryNotFoundException, IOException {
    ManifestStore manifestStore = storage.manifestStore(name);
    if (!manifestStore.exists()) {
      throw new RepositoryNotFoundException(name.get());
    }
    return openInitialized(name, manifestStore);
  }

  @Override
  public Repository createRepository(Project.NameKey name)
      throws RepositoryNotFoundException, RepositoryExistsException, IOException {
    ManifestStore manifestStore = storage.manifestStore(name);
    if (!manifestStore.create()) {
      throw new RepositoryExistsException(name);
    }

    return openInitialized(name, manifestStore);
  }

  @Override
  public NavigableSet<Project.NameKey> list() {
    try {
      return storage.listProjects();
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot list WalGerrit repositories", exception);
    }
  }

  @Override
  public Boolean canPerformGC() {
    // Compaction must be published through the WAL rather than local Git's GC path.
    return false;
  }

  @Override
  public void repositoryDeleted(Project.NameKey name) {
    // Durable deletion requires a tombstone transaction and is intentionally not inferred from this hook.
  }

  private static LocalWalGitRepository openInitialized(
      Project.NameKey name, ManifestStore manifestStore) throws IOException {
    LocalWalGitRepository repository = new LocalWalGitRepository(name, manifestStore);
    if (!repository.exists()) {
      if (manifestStore.read().getRevision() != 0) {
        repository.close();
        throw new IOException("Repository has a manifest but no ref state: " + name.get());
      }
      // Recover a process death between manifest creation and the initial HEAD transaction.
      repository.create(true);
    }
    return repository;
  }
}
