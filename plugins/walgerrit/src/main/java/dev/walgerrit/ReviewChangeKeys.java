// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.update.BatchUpdateListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.proto.StorageProto.ChangeKeyCheck;
import dev.walgerrit.proto.StorageProto.ChangeMembers;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Each review write reads one storage snapshot and checks its decisions at ref publication. */
@Singleton
final class ReviewChangeKeys
    implements BiFunction<
            Repository,
            BiConsumer<BatchUpdateListener, BooleanSupplier>,
            Optional<BiFunction<String, String, List<Integer>>>>,
        LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(ReviewChangeKeys.class);
  private final GitRepositoryManager repositories;
  private final Counter1<String> decisions;
  private final Map<Project.NameKey, CompletableFuture<Boolean>> seeding =
      new ConcurrentHashMap<>();
  private final long seedWaitMillis;
  private final Cache<Project.NameKey, Boolean> failedSeeds =
      CacheBuilder.newBuilder().maximumSize(1024).expireAfterWrite(30, TimeUnit.SECONDS).build();
  private final ThreadPoolExecutor seeds =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          task -> {
            Thread worker = new Thread(task, "WalGerrit-Change-Key-Seed");
            worker.setDaemon(true);
            return worker;
          });

  @Inject
  ReviewChangeKeys(
      @GerritServerConfig Config config, GitRepositoryManager repositories, MetricMaker metrics) {
    this.repositories = repositories;
    seedWaitMillis =
        config.getTimeUnit(
            "walgerrit",
            null,
            "indexBarrierTimeout",
            WalGitConfiguration.DEFAULT_INDEX_BARRIER_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS);
    decisions =
        metrics.newCounter(
            "walgerrit/change_keys/decisions",
            new Description(
                    "Review lookup snapshots and pre-publication conflicts reported to Gerrit")
                .setCumulative(),
            Field.ofString("outcome", (metadata, value) -> {}).build());
  }

  @Override
  public void start() {
    requireCoreHook(ReviewChangeKeys.class.getClassLoader());
  }

  static void requireCoreHook(ClassLoader loader) {
    try {
      Class.forName("com.google.gerrit.server.index.change.ChangeKeyLookup", false, loader)
          .getMethod("open", Repository.class);
    } catch (ReflectiveOperationException | LinkageError missing) {
      throw new IllegalStateException(
          "WalGerrit storage requires a Gerrit WAR with ChangeKeyLookup", missing);
    }
  }

  @Override
  public void stop() {
    seeds.shutdownNow();
    seeding.values().forEach(job -> job.complete(false));
  }

  static final class CoverageUnavailable extends IOException {
    private static final long serialVersionUID = 1L;

    CoverageUnavailable(Project.NameKey project) {
      super("Authoritative change keys for " + project.get() + " are unavailable. Please retry.");
    }
  }

  @Override
  public Optional<BiFunction<String, String, List<Integer>>> apply(
      Repository repository, BiConsumer<BatchUpdateListener, BooleanSupplier> beforeWrite) {
    if (!(repository instanceof LocalWalGitRepository wal)) {
      decisions.increment("unsupported_repository");
      throw new UncheckedIOException(
          new IOException("Cannot guard ref publication for " + repository.getClass().getName()));
    }
    try {
      Manifest base = wal.manifestStore().refresh();
      Project.NameKey project = Project.nameKey(wal.getDescription().getRepositoryName());
      if (base.getDeleted()) {
        throw new CoverageUnavailable(project);
      }
      if (!ChangeKeyDirectory.complete(base)) {
        decisions.increment("seed_wait");
        if (!awaitSeed(project)) {
          throw new CoverageUnavailable(project);
        }
        // A peer may have published or invalidated the seed while this request waited.
        base = wal.manifestStore().refresh();
        if (base.getDeleted() || !ChangeKeyDirectory.complete(base)) {
          throw new CoverageUnavailable(project);
        }
      }
      Lookup lookup = new Lookup(wal, new ChangeKeyDirectory(wal.manifestStore(), base));
      decisions.increment("covered");
      beforeWrite.accept(
          lookup,
          () -> {
            if (lookup.conflicted()) {
              decisions.increment("publication_conflict");
              return true;
            }
            return false;
          });
      return Optional.of(lookup);
    } catch (CoverageUnavailable failure) {
      decisions.increment("seed_unavailable");
      throw new UncheckedIOException(failure);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private boolean awaitSeed(Project.NameKey project) throws IOException {
    if (failedSeeds.getIfPresent(project) != null) {
      return false;
    }
    var job = new CompletableFuture<Boolean>();
    var running = seeding.putIfAbsent(project, job);
    if (running == null) {
      scheduleSeed(project, job);
      running = job;
    }
    try {
      return running.get(seedWaitMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException expired) {
      return false; // Other requests can still use the seed when it is ready.
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted waiting for change keys of " + project, cancelled);
    } catch (ExecutionException failure) {
      throw new IOException("Could not seed change keys of " + project, failure.getCause());
    }
  }

  private void scheduleSeed(Project.NameKey project, CompletableFuture<Boolean> job) {
    try {
      seeds.execute(
          () -> {
            try (Repository repo = repositories.openRepository(project)) {
              // A clean miss can be a race with a writer. The next request can try immediately.
              job.complete(repo instanceof LocalWalGitRepository wal && open(wal).isPresent());
            } catch (IOException | RuntimeException failure) {
              failedSeeds.put(project, true);
              job.complete(false);
              logger.warn("Could not seed authoritative change keys for {}", project, failure);
            } catch (Error failure) {
              job.completeExceptionally(failure);
              throw failure;
            } finally {
              seeding.remove(project, job);
            }
          });
    } catch (RejectedExecutionException busy) {
      job.complete(false);
      seeding.remove(project, job);
    }
  }

  static Optional<Lookup> open(LocalWalGitRepository repo) throws IOException {
    ManifestStore store = repo.manifestStore();
    Manifest base = store.refresh();
    if (base.getDeleted()) {
      return Optional.empty();
    }
    if (!ChangeKeyDirectory.complete(base)) {
      List<Ref> refs = repo.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES);
      Manifest captured = store.refresh();
      if (captured.getRefRevision() != base.getRefRevision()) {
        return Optional.empty();
      }
      ChangeKeyDirectory seed = ChangeKeyDirectory.scan(repo, store, refs);
      // The expensive part reads immutable tips. Reconcile intervening WAL changes before the
      // CAS, including updates from writers which predate the directory.
      try (RevWalk walk = new RevWalk(repo)) {
        for (int attempt = 0; attempt < 4; attempt++) {
          base = store.refresh();
          if (ChangeKeyDirectory.complete(base)) {
            break;
          }
          if (base.getDeleted()
              || !seed.advanceSeed(
                  walk,
                  store.readLogEntriesAfter(
                      captured.getHeadSeq(), captured.getHeadTransactionId(), base, 10_000))) {
            return Optional.empty();
          }
          try {
            base = store.installChangeKeys(base.getRefRevision(), seed);
            break;
          } catch (ManifestConflictException raced) {
            captured = base;
          }
        }
      }
    }
    if (base.getDeleted() || !ChangeKeyDirectory.complete(base)) {
      return Optional.empty();
    }
    return Optional.of(new Lookup(repo, new ChangeKeyDirectory(store, base)));
  }

  static final class Lookup
      implements BiFunction<String, String, List<Integer>>, BatchUpdateListener {
    private final LocalWalGitRepository repo;
    private final ChangeKeyDirectory directory;
    private final Map<String, ChangeKeyCheck> checks = new LinkedHashMap<>();
    private volatile boolean conflicted;

    Lookup(LocalWalGitRepository repo, ChangeKeyDirectory directory) {
      this.repo = repo;
      this.directory = directory;
    }

    boolean conflicted() {
      return conflicted;
    }

    @Override
    public List<Integer> apply(String branch, String changeId) {
      String key = ChangeKeyDirectory.key(branch, changeId);
      try {
        ChangeMembers members = directory.members(key);
        checks.put(key, ChangeKeyCheck.newBuilder().setKey(key).setExpected(members).build());
        return members.getNumbersList();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    @Override
    public BatchRefUpdate beforeUpdateRefs(BatchRefUpdate batch) {
      conflicted = false;
      ((LocalWalGitRefDatabase) repo.getRefDatabase())
          .guard(batch, List.copyOf(checks.values()), () -> conflicted = true);
      return batch;
    }
  }
}
