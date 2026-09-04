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
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.config.GerritRuntime;
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
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;

/**
 * Gerrit's repository-manager entry point for WalGit-backed storage. As the daemon's lifecycle
 * listener it also runs this node's compactor; batch programs never compact.
 */
@Singleton
public final class WalGitRepositoryManager implements GitRepositoryManager, LifecycleListener {
  private final WalGitConfiguration configuration;
  private final StorageLayout storage;
  private final Compactor compactor;
  private final GerritRuntime runtime;

  @Inject
  WalGitRepositoryManager(
      @GerritServerConfig Config serverConfig, @SitePath Path sitePath, GerritRuntime runtime) {
    this(WalGitConfiguration.from(serverConfig, sitePath), null, runtime);
    configureBlockCache(serverConfig);
  }

  WalGitRepositoryManager(WalGitConfiguration configuration) {
    this(configuration, null, null);
  }

  WalGitRepositoryManager(WalGitConfiguration configuration, StorageLayout storage) {
    this(configuration, storage, null);
  }

  private WalGitRepositoryManager(
      WalGitConfiguration configuration, StorageLayout storage, GerritRuntime runtime) {
    this.configuration = configuration;
    this.storage = storage == null ? storageFor(configuration) : storage;
    this.runtime = runtime;
    this.compactor = new Compactor(this);
    this.storage.onPublication(compactor::consider);
  }

  @Override
  public void start() {
    if (runtime == GerritRuntime.DAEMON) {
      compactor.start();
    }
  }

  @Override
  public void stop() {
    compactor.stop();
  }

  /**
   * Sizes JGit's process-wide block cache, which holds pack blocks and indexes for every open
   * repository. JGit's default of 32 MB suits a laptop; a server gets a tenth of its heap unless
   * {@code core.dfs.blockLimit} says otherwise.
   */
  static void configureBlockCache(Config serverConfig) {
    DfsBlockCacheConfig cacheConfig = new DfsBlockCacheConfig().fromConfig(serverConfig);
    if (serverConfig.getString("core", "dfs", "blockLimit") == null) {
      long blockSize = cacheConfig.getBlockSize();
      long limit = Math.max(cacheConfig.getBlockLimit(), Runtime.getRuntime().maxMemory() / 10);
      cacheConfig.setBlockLimit(limit - (limit % blockSize));
    }
    DfsBlockCache.reconfigure(cacheConfig);
  }

  @Override
  public Status getRepositoryStatus(Project.NameKey name) {
    try {
      return storage.manifestStore(name).exists() ? Status.ACTIVE : Status.NON_EXISTENT;
    } catch (IOException exception) {
      return Status.UNAVAILABLE;
    }
  }

  /** Opening a handle is the request-level freshness boundary: exactly one conditional read. */
  @Override
  public Repository openRepository(Project.NameKey name)
      throws RepositoryNotFoundException, IOException {
    ManifestStore manifestStore = storage.manifestStore(name);
    boolean exists =
        configuration.manifestRevalidateOnOpen()
            ? manifestStore.exists()
            : manifestStore.existsUnlessRecentlyValidated(
                configuration.manifestRevalidateInterval());
    if (!exists) {
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

  WalGitConfiguration configuration() {
    return configuration;
  }

  Compactor compactor() {
    return compactor;
  }

  StorageLayout storage() {
    return storage;
  }

  private LocalWalGitRepository openInitialized(
      Project.NameKey name, ManifestStore manifestStore) throws IOException {
    LocalWalGitRepository repository =
        new LocalWalGitRepository(
            name, manifestStore, configuration.manifestRevalidateInterval());
    if (!repository.exists()) {
      if (manifestStore.current().getRevision() != 0) {
        repository.close();
        throw new IOException("Repository has a manifest but no ref state: " + name.get());
      }
      // Recover a process death between manifest creation and the initial HEAD transaction.
      repository.create(true);
    }
    return repository;
  }

  private static StorageLayout storageFor(WalGitConfiguration configuration) {
    return switch (configuration.backend()) {
      case LOCAL ->
          new StorageLayout(
              new FileObjectStore(configuration.storagePath()),
              configuration.storagePath(),
              configuration.indexCursorPath(),
              "",
              configuration.rangedPackReads() ? configuration.packFetchChunkSize() : 0);
      case S3 ->
          new StorageLayout(
              new S3ObjectStore(
                  configuration.s3Bucket(),
                  configuration.s3Region(),
                  configuration.s3Endpoint(),
                  configuration.s3PathStyle(),
                  configuration.s3MaxConnections(),
                  configuration.s3ConnectTimeout(),
                  configuration.s3SocketTimeout(),
                  configuration.s3MaxAttempts()),
              configuration.storagePath(),
              configuration.indexCursorPath(),
              configuration.s3Prefix(),
              configuration.rangedPackReads() ? configuration.packFetchChunkSize() : 0);
    };
  }
}
