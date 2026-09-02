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
import dev.walgerrit.CompactionPolicy.Plan;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsPackFile;
import org.eclipse.jgit.internal.storage.dfs.DfsReftable;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This node's compactor: rewrites a repository's small packs into larger ones and its reftable
 * stack into one table, publishing each result as one add-and-supersede manifest transaction.
 *
 * <p>The node that writes compacts. After every publication the policy is evaluated on the new
 * manifest, and a repository whose packs are due is queued for one compaction at a time on a
 * single background thread. The compactor takes the repository's lease so other nodes skip the
 * same work, re-reads the manifest, and repeats passes until the policy is satisfied. JGit's
 * {@code DfsPackCompactor} is the repacking engine; WalGerrit supplies the inputs it selected and
 * publishes the outputs through the manifest CAS, which refuses the result if any input was
 * superseded meanwhile. A compaction that loses that race deletes its own unreferenced output.
 * Everything else that is left behind is the reclaimer's job, which also runs on this thread.
 */
final class Compactor {
  private static final Logger logger = LoggerFactory.getLogger(Compactor.class);
  private static final int MAX_PASSES = 4;

  enum Outcome {
    COMPACTED,
    NOTHING_TO_DO,
    LEASED_ELSEWHERE
  }

  private final WalGitRepositoryManager repositories;
  private final WalGitConfiguration configuration;
  private final CompactionPolicy policy;
  private final Reclaimer reclaimer;
  private final Set<Project.NameKey> queued = ConcurrentHashMap.newKeySet();
  private volatile ScheduledExecutorService executor;

  Compactor(WalGitRepositoryManager repositories) {
    this(repositories, Clock.systemUTC());
  }

  Compactor(WalGitRepositoryManager repositories, Clock clock) {
    this.repositories = repositories;
    this.configuration = repositories.configuration();
    this.policy = CompactionPolicy.of(configuration);
    this.reclaimer =
        new Reclaimer(
            repositories, clock, configuration.reclaimGrace(), configuration.cacheSizeLimit());
  }

  CompactionPolicy policy() {
    return policy;
  }

  Reclaimer reclaimer() {
    return reclaimer;
  }

  synchronized void start() {
    if (!configuration.compactionEnabled() || executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "WalGerrit-Compaction");
              thread.setDaemon(true);
              return thread;
            });
    // One sweep at start and one every reclaim interval: they reclaim, and they queue compaction
    // for repositories that fell due without this node writing to them, such as repositories
    // imported by a batch program or written while compaction was off.
    if (configuration.cacheSizeLimit() > 0 && repositories.storage().cacheIsStore()) {
      logger.warn(
          "walgerrit.cacheSizeLimit is ignored: with the local backend the cache is the store");
    }
    long interval = Math.max(1, configuration.reclaimInterval().toMillis());
    executor.scheduleWithFixedDelay(this::sweep, 0, interval, TimeUnit.MILLISECONDS);
    logger.info(
        "WalGerrit compaction is on: packs roll up at {} undersized packs (factor {}), the reftable "
            + "stack at {} tables; every {} a sweep queues overdue repositories and {}",
        policy.minPacks(),
        policy.geometricFactor(),
        policy.minReftables(),
        configuration.reclaimInterval(),
        configuration.reclaimEnabled()
            ? "reclaims files unreferenced for " + configuration.reclaimGrace()
            : "reclaims nothing (walgerrit.reclaimEnabled = false)");
  }

  synchronized void stop() {
    ScheduledExecutorService running = executor;
    executor = null;
    if (running == null) {
      return;
    }
    running.shutdownNow();
    try {
      if (!running.awaitTermination(30, TimeUnit.SECONDS)) {
        logger.warn("WalGerrit compaction did not stop within 30 seconds");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Called after this node published a manifest for {@code project}. Evaluates the policy on the
   * manifest in hand, no I/O, and queues one compaction if anything is due.
   */
  void consider(Project.NameKey project, Manifest manifest) {
    ScheduledExecutorService running = executor;
    if (running == null || policy.plan(manifest).isEmpty() || !queued.add(project)) {
      return;
    }
    try {
      running.execute(() -> runQueued(project));
    } catch (RejectedExecutionException stopping) {
      queued.remove(project);
    }
  }

  /** Repositories queued for compaction but not yet finished; for tests. */
  boolean isQueued(Project.NameKey project) {
    return queued.contains(project);
  }

  private void runQueued(Project.NameKey project) {
    try {
      compact(project);
    } catch (IOException | RuntimeException exception) {
      logger.warn("WalGerrit compaction of {} failed; it is retried after the next write", project.get(), exception);
    } finally {
      queued.remove(project);
    }
  }

  /**
   * Compacts {@code project} now, on the calling thread, until the policy is satisfied or the
   * repository's lease is held by another node.
   */
  Outcome compact(Project.NameKey project) throws IOException {
    Duration leaseDuration = configuration.compactionLeaseDuration();
    Optional<CompactionLease.Held> lease =
        repositories.storage().compactionLease(project).acquire(leaseDuration);
    if (lease.isEmpty()) {
      logger.debug("WalGerrit skips compacting {}: another node holds the lease", project.get());
      return Outcome.LEASED_ELSEWHERE;
    }
    boolean compacted = false;
    try (CompactionLease.Held held = lease.get()) {
      for (int pass = 0; pass < MAX_PASSES; pass++) {
        // A fresh handle starts from a conditional manifest read, so every pass plans against the
        // newest manifest: the previous pass's own publication, or whatever another node did to
        // the packs this pass meant to rewrite.
        try (LocalWalGitRepository repository =
            (LocalWalGitRepository) repositories.openRepository(project)) {
          Plan plan = policy.plan(repository.manifestStore().current());
          if (plan.isEmpty()) {
            break;
          }
          if (!plan.packs().isEmpty()) {
            compacted |= compactPacks(repository, plan.packs());
            held.renew(leaseDuration);
          }
          if (plan.reftables()) {
            compacted |= compactReftables(repository);
            held.renew(leaseDuration);
          }
        }
      }
      if (compacted) {
        reclaimer.evictLocal(project);
      }
    }
    return compacted ? Outcome.COMPACTED : Outcome.NOTHING_TO_DO;
  }

  /** Rolls the named packs up into one; false if the manifest moved under the plan. */
  private boolean compactPacks(LocalWalGitRepository repository, List<String> names)
      throws IOException {
    DfsObjDatabase objects = repository.getObjectDatabase();
    Map<String, DfsPackFile> live = new HashMap<>();
    for (DfsPackFile pack : objects.getPacks()) {
      live.put(LocalWalGitObjectDatabase.packName(pack.getPackDescription()), pack);
    }
    DfsPackCompactor compactor = new DfsPackCompactor(repository);
    long inputBytes = 0;
    for (String name : names) {
      DfsPackFile pack = live.get(name);
      if (pack == null) {
        return false;
      }
      compactor.add(pack);
      inputBytes += pack.getPackDescription().getFileSize(PackExt.PACK);
    }
    long started = System.nanoTime();
    try {
      compactor.compact(NullProgressMonitor.INSTANCE);
    } catch (StaleCompactionInputException lost) {
      discardOutputs(repository, compactor.getNewPacks(), lost);
      return false;
    }
    long outputBytes =
        compactor.getNewPacks().stream().mapToLong(pack -> pack.getFileSize(PackExt.PACK)).sum();
    logger.info(
        "WalGerrit compacted {} packs ({} bytes) of {} into one pack of {} bytes in {} ms",
        names.size(),
        inputBytes,
        repository.getDescription().getRepositoryName(),
        outputBytes,
        (System.nanoTime() - started) / 1_000_000);
    return true;
  }

  /** Merges the whole reftable stack into one table; false if it is already shallow enough. */
  private boolean compactReftables(LocalWalGitRepository repository) throws IOException {
    DfsObjDatabase objects = repository.getObjectDatabase();
    DfsReftable[] tables = objects.getReftables();
    if (tables.length < policy.minReftables()) {
      return false;
    }
    DfsPackCompactor compactor =
        new DfsPackCompactor(repository)
            .setReftableConfig(
                ((DfsReftableDatabase) repository.getRefDatabase()).getReftableConfig());
    for (DfsReftable table : tables) {
      compactor.add(table);
    }
    long started = System.nanoTime();
    try {
      compactor.compact(NullProgressMonitor.INSTANCE);
    } catch (StaleCompactionInputException lost) {
      discardOutputs(repository, compactor.getNewPacks(), lost);
      return false;
    }
    logger.info(
        "WalGerrit compacted the {}-table reftable stack of {} into one table in {} ms",
        tables.length,
        repository.getDescription().getRepositoryName(),
        (System.nanoTime() - started) / 1_000_000);
    return true;
  }

  /**
   * The CAS refused the result because another compaction replaced an input first. The uploaded
   * output is referenced by nothing, so it is deleted here rather than left for the reclaimer.
   */
  private static void discardOutputs(
      LocalWalGitRepository repository,
      List<DfsPackDescription> outputs,
      StaleCompactionInputException lost) {
    ManifestStore store = repository.manifestStore();
    for (DfsPackDescription output : outputs) {
      for (PackExt extension : PackExt.values()) {
        if (!output.hasFileExt(extension)) {
          continue;
        }
        String fileName = output.getFileName(extension);
        try {
          store.deleteWalObject(fileName);
          store.deleteLocalFile(fileName);
        } catch (IOException exception) {
          logger.warn(
              "WalGerrit could not remove the unreferenced compaction output {}; the reclaimer will",
              fileName,
              exception);
        }
      }
    }
    logger.info(
        "WalGerrit compaction of {} lost to a concurrent compaction and discarded its output: {}",
        repository.getDescription().getRepositoryName(),
        lost.getMessage());
  }

  /** Visible for tests: one sweep over every repository, as the executor runs it. */
  void sweep() {
    try {
      Reclaimer.Report report =
          configuration.reclaimEnabled()
              ? reclaimer.reclaimAll(this::consider)
              : reclaimer.observeAll(this::consider);
      logger.info(
          "WalGerrit sweep over {} repositories deleted {} store file(s), evicted {} cached file(s) "
              + "and trimmed {} bytes for the cache limit",
          report.repositories(),
          report.deleted(),
          report.evicted(),
          report.trimmedBytes());
    } catch (IOException | RuntimeException exception) {
      logger.warn("WalGerrit sweep failed", exception);
    }
  }
}
