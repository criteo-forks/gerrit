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
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.RepositoryExistsException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.Catalog.Binding;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

/**
 * Gerrit's repository-manager entry point for WalGit-backed storage. Names are resolved through
 * the {@link Catalog}; everything under a repository is keyed by its id. As the daemon's lifecycle
 * listener it also runs this node's compactor; batch programs never compact.
 */
@Singleton
public final class WalGitRepositoryManager implements GitRepositoryManager, LifecycleListener {
  private final WalGitConfiguration configuration;
  private final StorageLayout storage;
  private final Catalog catalog;
  private final Namespace namespace;
  private final Compactor compactor;
  private final GerritRuntime runtime;
  private final AuthRefDirectory.Reader authRefs;

  /** Whether Gerrit prefers this node to execute writes to the named project. */
  private volatile Predicate<String> writeExecutor = project -> true;

  /** The name each repository id was last seen under; an id never changes its name. */
  private final Map<RepositoryId, String> names = new ConcurrentHashMap<>();

  /** The repository whose log entry the thread is applying; see {@link #applyingReplay}. */
  private final ThreadLocal<RepositoryId> applyingReplay = new ThreadLocal<>();

  private final ThreadLocal<ReplayHandle> replayHandle = new ThreadLocal<>();

  /** One reference for a replay pass; each NoteDb caller borrows and closes its own reference. */
  final class ReplayHandle implements AutoCloseable {
    final RepositoryId id;
    final ReplayHandle outer = replayHandle.get();
    Binding binding;
    LocalWalGitRepository repository;

    ReplayHandle(RepositoryId id) {
      this.id = id;
      replayHandle.set(this);
    }

    @Override
    public void close() {
      replayHandle.set(outer);
      if (repository != null) {
        repository.close();
      }
    }
  }

  ReplayHandle reuseReplayHandle(RepositoryId id) {
    return new ReplayHandle(id);
  }

  @Inject
  WalGitRepositoryManager(
      @GerritServerConfig Config serverConfig,
      @SitePath Path sitePath,
      GerritRuntime runtime,
      MetricMaker metrics) {
    this(WalGitConfiguration.from(serverConfig, sitePath), null, runtime, metrics);
    configureBlockCache(serverConfig);
  }

  WalGitRepositoryManager(WalGitConfiguration configuration) {
    this(configuration, null);
  }

  WalGitRepositoryManager(WalGitConfiguration configuration, StorageLayout storage) {
    this(configuration, storage, null, new DisabledMetricMaker());
  }

  WalGitRepositoryManager(
      WalGitConfiguration configuration,
      StorageLayout storage,
      GerritRuntime runtime,
      MetricMaker metrics) {
    this.configuration = configuration;
    this.storage = storage == null ? storageFor(configuration) : storage;
    this.runtime = runtime;
    this.authRefs = new AuthRefDirectory.Reader(metrics);
    this.catalog = new Catalog(this.storage, configuration.manifestRevalidateInterval());
    Clock clock = Clock.systemUTC();
    this.namespace =
        new Namespace(
            catalog,
            this.storage,
            this::initialize,
            new NameReferences(
                this::openRepository, configuration.allProjects(), configuration.allUsers(), clock),
            configuration.systemProjects(),
            clock);
    this.compactor = new Compactor(this);
    this.storage.onPublication((id, published) -> compactor.consider(id, published.manifest()));
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
      Optional<Binding> binding = catalog.resolve(name, false);
      return binding.filter(Binding::active).isPresent() ? Status.ACTIVE : Status.NON_EXISTENT;
    } catch (IOException exception) {
      return Status.UNAVAILABLE;
    }
  }

  /**
   * Marks the calling thread as applying a log entry of {@code repository} until the scope closes.
   * The replay read that repository's manifest to find the entry, so a handle the thread opens on
   * it starts from a view at least as new as the entry, without a conditional read of its own
   * unless the revalidation interval has passed. The catalog and every other repository are
   * validated as on any open.
   */
  EventReplay.Scope applyingReplay(RepositoryId repository) {
    RepositoryId outer = applyingReplay.get();
    applyingReplay.set(repository);
    return () -> applyingReplay.set(outer);
  }

  boolean isApplyingReplay() {
    return applyingReplay.get() != null;
  }

  /**
   * Opening a handle is the request-level freshness boundary: one conditional read of the catalog,
   * which every repository shares, and one of the repository's manifest.
   */
  @Override
  public Repository openRepository(Project.NameKey name)
      throws RepositoryNotFoundException, IOException {
    boolean revalidate = configuration.manifestRevalidateOnOpen();
    Binding binding =
        catalog
            .resolve(name, revalidate)
            .orElseThrow(() -> new RepositoryNotFoundException(name.get()));
    if (!binding.active()) {
      throw new RepositoryNotFoundException(name.get() + " " + Namespace.describe(binding));
    }
    ManifestStore manifestStore = storage.manifestStore(binding.id());
    boolean exists =
        revalidate && !binding.id().equals(applyingReplay.get())
            ? manifestStore.exists()
            : manifestStore.existsUnlessRecentlyValidated(
                configuration.manifestRevalidateInterval());
    if (!exists) {
      throw new IOException(
          "Catalog binds " + name.get() + " to " + binding.id() + " but it has no manifest");
    }
    ReplayHandle reuse = replayHandle.get();
    if (reuse != null && reuse.id.equals(binding.id()) && reuse.id.equals(applyingReplay.get())) {
      if (!binding.equals(reuse.binding)) {
        if (reuse.repository != null) {
          reuse.repository.close();
          reuse.repository = null;
          reuse.binding = null;
        }
        reuse.repository = openInitialized(name, binding.id(), binding.epoch());
        reuse.binding = binding;
      }
      reuse.repository.incrementOpen();
      return reuse.repository;
    }
    return openInitialized(name, binding.id(), binding.epoch());
  }

  /**
   * Reads the ref's object ID using the catalog and manifest from the store, even during replay or
   * when {@code walgerrit.manifestRevalidateOnOpen} is false. Returns empty for a missing, deleted
   * or fenced project or absent ref; an active project with no manifest fails.
   */
  Optional<ObjectId> storedRef(Project.NameKey name, String ref) throws IOException {
    Binding binding = catalog.resolve(name, true).orElse(null);
    if (binding == null || !binding.active()) {
      return Optional.empty();
    }
    ManifestStore manifestStore = storage.manifestStore(binding.id());
    if (!admits(manifestStore.refresh(), binding)) {
      return Optional.empty();
    }
    Optional<ObjectId> stored;
    try (Repository repository = openInitialized(name, binding.id(), binding.epoch())) {
      stored = Optional.ofNullable(repository.exactRef(ref)).map(Ref::getObjectId);
    }
    // The handle reads the newest manifest this node holds, which can be newer than the one
    // checked. Epochs only advance and a deletion is final, so the newest one decides.
    return admits(manifestStore.current(), binding) ? stored : Optional.empty();
  }

  /** Whether no namespace operation fenced or deleted the repository since {@code binding}. */
  private static boolean admits(Manifest manifest, Binding binding) {
    return !manifest.getDeleted() && manifest.getWriteEpoch() == binding.epoch();
  }

  @Override
  public Repository createRepository(Project.NameKey name)
      throws RepositoryNotFoundException, RepositoryExistsException, IOException {
    return openInitialized(name, namespace.create(name), 0);
  }

  @Override
  public NavigableSet<Project.NameKey> list() {
    try {
      return catalog.activeNames();
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
    // Deletion is a namespace operation with its own protocol; see Namespace#delete. This hook
    // arrives after a plugin already acted on its own and proves nothing.
  }

  /** Takes Gerrit's choice of the node that executes a project's writes; see {@link WriteRole}. */
  void writeExecutor(Predicate<String> executor) {
    writeExecutor = executor;
  }

  /**
   * Whether Gerrit prefers this node to execute writes to {@code id}'s repository; true when
   * nothing chooses or the choice fails.
   */
  boolean executes(RepositoryId id) {
    try {
      return writeExecutor.test(knownName(id));
    } catch (RuntimeException failure) {
      return true;
    }
  }

  /** The project name of {@code id}, or the id itself for the catalog and for unbound ids. */
  String knownName(RepositoryId id) {
    if (id.isCatalog()) {
      return id.value();
    }
    String known = names.get(id);
    if (known != null) {
      return known;
    }
    try {
      Optional<Binding> binding = catalog.bindingOf(id, false);
      if (binding.isPresent()) {
        String name = binding.get().name().get();
        names.put(id, name);
        return name;
      }
    } catch (IOException | RuntimeException unknown) {
      // The next caller asks again.
    }
    return id.value();
  }

  /** Deletes and resumes; the front end for plugins and the batch program. */
  public Namespace namespace() {
    return namespace;
  }

  WalGitConfiguration configuration() {
    return configuration;
  }

  /** Whether this node holds the sweep lease (see {@link SweepLease}). */
  public boolean holdsSweepLease() {
    return compactor.holdsSweepLease();
  }

  Compactor compactor() {
    return compactor;
  }

  StorageLayout storage() {
    return storage;
  }

  Catalog catalog() {
    return catalog;
  }

  /** The store of the repository {@code name} is active under, for node bookkeeping. */
  Optional<ManifestStore> manifestStoreFor(Project.NameKey name) throws IOException {
    return catalog.resolve(name, false).filter(Binding::active).map(b -> storage.manifestStore(b.id()));
  }

  /** The id {@code name} is bound to, active or in flight; for tests and tools. */
  RepositoryId idOf(Project.NameKey name) throws IOException {
    return catalog
        .resolve(name, true)
        .filter(binding -> !binding.retired())
        .map(Binding::id)
        .orElseThrow(() -> new RepositoryNotFoundException(name.get()));
  }

  /** The store of the repository {@code name} is bound to; for tests and tools. */
  ManifestStore manifestStore(Project.NameKey name) throws IOException {
    return storage.manifestStore(idOf(name));
  }

  /**
   * A handle on a repository by id, for maintenance that works below names: compaction, the index
   * tailer, the applier removing a deleted project's documents. {@code writeEpoch} is what the
   * handle's publications carry; maintenance passes the manifest's current epoch, so a fence in
   * between fails it like any other writer.
   */
  LocalWalGitRepository openById(RepositoryId id, Project.NameKey name, long writeEpoch)
      throws IOException {
    return new LocalWalGitRepository(
        name,
        storage.manifestStore(id),
        configuration.manifestRevalidateInterval(),
        writeEpoch,
        configuration.allUsers().equals(name) ? authRefs : null);
  }

  /** The name the catalog binds {@code id} to, for handles and logs; the id itself when none. */
  Project.NameKey nameOf(RepositoryId id) throws IOException {
    return catalog.bindingOf(id, false).map(Binding::name).orElse(Project.nameKey(id.value()));
  }

  private void initialize(RepositoryId id, Project.NameKey name) throws IOException {
    ManifestStore manifestStore = storage.manifestStore(id);
    if (!manifestStore.exists()) {
      manifestStore.create();
    }
    openInitialized(name, id, 0).close();
  }

  private LocalWalGitRepository openInitialized(
      Project.NameKey name, RepositoryId id, long writeEpoch) throws IOException {
    LocalWalGitRepository repository = openById(id, name, writeEpoch);
    ManifestStore manifestStore = repository.manifestStore();
    if (!repository.exists()) {
      if (manifestStore.current().getRevision() != 0) {
        repository.close();
        throw new IOException("Repository has a manifest but no ref state: " + name.get());
      }
      // Recover a process death between manifest creation and the initial HEAD transaction. Of a
      // creator and a resume doing this at once, the CAS refuses one.
      try {
        repository.create(true);
      } catch (IOException lostRace) {
        repository.scanForRepoChanges();
        if (!repository.exists()) {
          repository.close();
          throw lostRace;
        }
      }
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
