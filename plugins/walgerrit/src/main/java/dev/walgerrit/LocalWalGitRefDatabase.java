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

import dev.walgerrit.proto.StorageProto.ChangeKeyCheck;
import dev.walgerrit.proto.StorageProto.ChangeKeyMutation;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableBatchRefUpdate;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.reftable.MergedReftable;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reftable storage makes each Gerrit batch ref update one immutable DFS file. */
final class LocalWalGitRefDatabase extends DfsReftableDatabase {
  private final LocalWalGitObjectDatabase objectDatabase;
  private final boolean allUsers;

  /** The manifest revision the cached reftable stack was built from. */
  private volatile long stackRevision = -1;

  /** Counts the times the reftable stack was dropped; see {@link #revalidatedGeneration}. */
  private final AtomicLong generation = new AtomicLong();

  LocalWalGitRefDatabase(
      DfsRepository repository, LocalWalGitObjectDatabase objectDatabase, boolean allUsers) {
    super(repository);
    this.objectDatabase = objectDatabase;
    this.allUsers = allUsers;
  }

  @Override
  public BatchRefUpdate newBatchUpdate() {
    return new WalGitBatchRefUpdate(this, objectDatabase);
  }

  void guard(BatchRefUpdate batch, List<ChangeKeyCheck> checks, Runnable onConflict) {
    if (!(batch instanceof WalGitBatchRefUpdate wal) || wal.refDatabase != this) {
      throw new IllegalStateException(
          "Change-key lookup and ref update belong to different repositories");
    }
    wal.checks = List.copyOf(checks);
    wal.onKeyConflict = onConflict;
  }

  @Override
  public Ref exactRef(String name) throws IOException {
    Manifest validated = revalidate();
    return allUsers
        ? AuthRefDirectory.exactRef(
            validated, objectDatabase.manifestStore(), name, super::exactRef)
        : super.exactRef(name);
  }

  /** Called under the JGit ref lock, after expected-value checks and before the table write. */
  private AuthRefDirectory.Seed authSeed() throws IOException {
    Manifest base = objectDatabase.manifestStore().current();
    if (AuthRefDirectory.complete(base)) {
      return null;
    }
    var stack = stack();
    Set<String> tables = new HashSet<>();
    for (var pack : base.getPacksList()) {
      if (ManifestStore.hasReftable(pack)) {
        tables.add(pack.getName());
      }
    }
    // The handle may have validated before this node adopted the CAS base.
    for (var file : stack.files()) {
      if (!tables.remove(LocalWalGitObjectDatabase.packName(file.getPackDescription()))) {
        return null;
      }
    }
    if (!tables.isEmpty()) {
      return null;
    }
    return AuthRefDirectory.scan(base.getRefRevision(), new MergedReftable(stack.readers()));
  }

  @Override
  public Map<String, Ref> getRefs(String prefix) throws IOException {
    revalidate();
    return super.getRefs(prefix);
  }

  @Override
  public List<Ref> getRefsByPrefix(String prefix) throws IOException {
    revalidate();
    return super.getRefsByPrefix(prefix);
  }

  @Override
  public List<Ref> getRefsByPrefixWithExclusions(String include, Set<String> excludes)
      throws IOException {
    revalidate();
    return super.getRefsByPrefixWithExclusions(include, excludes);
  }

  @Override
  public boolean isNameConflicting(String refName) throws IOException {
    revalidate();
    return super.isNameConflicting(refName);
  }

  @Override
  public ReflogReader getReflogReader(Ref ref) throws IOException {
    revalidate();
    return super.getReflogReader(ref);
  }

  @Override
  public Set<Ref> getTipsWithSha1(ObjectId id) throws IOException {
    revalidate();
    return super.getTipsWithSha1(id);
  }

  /**
   * Adopts a newer manifest if this node has observed one; a network read happens only when the
   * handle exceeded its revalidation interval. The reftable stack is a cache of its own, separate
   * from the object database's pack list, so it is reloaded whenever the manifest revision it was
   * built from differs from the one the object database now mirrors, regardless of which read
   * path adopted the newer manifest first.
   */
  private Manifest revalidate() throws IOException {
    Manifest validated = objectDatabase.revalidateIfStale();
    if (objectDatabase.observedManifestRevision() != stackRevision) {
      refresh();
    }
    return validated;
  }

  @Override
  public void refresh() {
    // Read first: a stack that is rebuilt right after the reset mirrors this revision or a newer
    // one, never an older one than the revision recorded for it.
    long observed = objectDatabase.observedManifestRevision();
    super.refresh();
    stackRevision = observed;
    generation.incrementAndGet();
  }

  /**
   * Adopts a newer manifest as every lookup does, then says which generation of refs this database
   * serves. The number changes whenever the reftable stack is dropped, which is the only way a
   * lookup's answer changes, so a caller that finds the number it saw before knows that every ref
   * has the value it had, without looking it up under the stack's lock.
   */
  long revalidatedGeneration() throws IOException {
    revalidate();
    return generation.get();
  }

  /** The generation as it is, without adopting anything; see {@link #revalidatedGeneration}. */
  long generation() {
    return generation.get();
  }

  /** A ref transaction of this handle ran: JGit dropped the stack on the way. */
  private void refsChanged() {
    generation.incrementAndGet();
  }

  /**
   * JGit folds a small transaction into the small table on top of the stack instead of deepening
   * it. Two concurrent transactions on this node would both fold the same table, so folding is only
   * allowed while this transaction is the only one in flight; otherwise the stack deepens by one
   * and the compactor merges it later.
   */
  @Override
  protected boolean compactDuringCommit() {
    return objectDatabase.publisher().alone();
  }


  private static final class WalGitBatchRefUpdate extends DfsReftableBatchRefUpdate {
    private static final Logger logger = LoggerFactory.getLogger(WalGitBatchRefUpdate.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_PAUSE_MILLIS = 20;

    private final LocalWalGitRefDatabase refDatabase;
    private final LocalWalGitObjectDatabase objectDatabase;
    private List<ChangeKeyCheck> checks = List.of();
    private Runnable onKeyConflict;
    private final Map<String, ChangeKeyMutation> mutations = new HashMap<>();

    WalGitBatchRefUpdate(
        LocalWalGitRefDatabase refDatabase,
        LocalWalGitObjectDatabase objectDatabase) {
      super(refDatabase, objectDatabase);
      this.refDatabase = refDatabase;
      this.objectDatabase = objectDatabase;
    }

    /**
     * Runs the batch as one ref transaction. Handles on this node take turns per repository, so a
     * transaction always starts from the manifest the previous local one published. A transaction
     * that loses the manifest CAS to another node's ref change is re-run from scratch against the
     * newer manifest, expected-value checks included, a bounded number of times; only a genuine
     * conflict on the refs themselves, or exhausted attempts, surfaces as a lock failure.
     */
    @Override
    public void execute(
        RevWalk walk, ProgressMonitor monitor, List<String> options) {
      GroupPublisher publisher = objectDatabase.publisher();
      ReentrantLock nodeLock = publisher.nodeLock();
      for (int attempt = 1; ; attempt++) {
        List<ReceiveCommand> pending = pending();
        if (pending.isEmpty()) {
          return;
        }
        GroupPublisher.Admission admission = null;
        int heldBefore = nodeLock.getHoldCount();
        try {
          // One conditional manifest read, outside every lock, so expected-value checks run
          // against the current global state.
          objectDatabase.revalidateNow();
          Set<String> names = refNames(pending);
          if (!checks.isEmpty()
              || ChangeKeyDirectory.complete(objectDatabase.manifestStore().current())) {
            // GroupPublisher may combine disjoint refs. Two different numeric meta refs can
            // still change the same logical key. Reserve those names until publication settles.
            for (ChangeKeyCheck check : checks) {
              names.add("\0" + check.getKey());
            }
            for (ReceiveCommand command : pending) {
              if (ChangeKeyDirectory.metaId(command.getRefName()) == null) {
                continue;
              }
              ChangeKeyMutation mutation;
              try {
                mutation = ChangeKeyDirectory.classify(walk, command);
              } catch (ChangeKeyDirectory.Unclassified unclassified) {
                // Leave the delta absent: publication clears coverage, or rejects a guarded batch.
                mutations.remove(command.getRefName());
                continue;
              }
              mutations.put(command.getRefName(), mutation);
              if (!mutation.getBefore().isEmpty()) {
                names.add("\0" + mutation.getBefore());
              }
              if (!mutation.getAfter().isEmpty()) {
                names.add("\0" + mutation.getAfter());
              }
            }
          }
          admission = publisher.admit(names);
          objectDatabase.prefetchRefTransaction(walk, pending);
          nodeLock.lock();
          try {
            objectDatabase.beginRefTransaction();
            refDatabase.refresh();
            try {
              super.execute(walk, monitor, options);
            } finally {
              refDatabase.refsChanged();
            }
          } finally {
            // A publication released the lock as soon as the reftable was written; a transaction
            // that failed validation still holds it.
            while (nodeLock.getHoldCount() > heldBefore) {
              nodeLock.unlock();
            }
          }
          if (!objectDatabase.refTransactionConflicted()) {
            return;
          }
          objectDatabase.manifestStore().conflicted();
          if (attempt >= MAX_ATTEMPTS) {
            return;
          }
        } catch (IOException exception) {
          logger.error("WalGerrit could not start a ref transaction", exception);
          abort(pending);
          return;
        } finally {
          if (onKeyConflict != null && objectDatabase.changeKeyConflict()) {
            // Gerrit retries any exception caused by a JGit lock failure. This batch's key
            // precondition failed and the caller must make a new selection.
            for (ReceiveCommand command : pending) {
              command.setResult(
                  ReceiveCommand.Result.REJECTED_OTHER_REASON,
                  "Change-Id membership changed before ref publication");
            }
            onKeyConflict.run();
          }
          objectDatabase.endRefTransaction();
          if (admission != null) {
            admission.release();
          }
        }
        logger.info(
            "Storage of {} changed while preparing a ref transaction; retrying ({}/{})",
            objectDatabase.repositoryName(),
            attempt,
            MAX_ATTEMPTS);
        for (ReceiveCommand command : pending) {
          command.setResult(ReceiveCommand.Result.NOT_ATTEMPTED);
        }
        if (!pause(attempt)) {
          abort(pending);
          return;
        }
      }
    }

    private static Set<String> refNames(List<ReceiveCommand> commands) {
      Set<String> names = new HashSet<>();
      for (ReceiveCommand command : commands) {
        names.add(command.getRefName());
      }
      return names;
    }

    private List<ReceiveCommand> pending() {
      return ReceiveCommand.filter(getCommands(), ReceiveCommand.Result.NOT_ATTEMPTED);
    }

    private static void abort(List<ReceiveCommand> pending) {
      List<ReceiveCommand> open =
          ReceiveCommand.filter(pending, ReceiveCommand.Result.NOT_ATTEMPTED);
      if (!open.isEmpty()) {
        open.get(0).setResult(ReceiveCommand.Result.LOCK_FAILURE, "io error");
        ReceiveCommand.abort(open);
      }
    }

    /** Brief jittered pause so two nodes hammering one repository do not collide in lockstep. */
    private static boolean pause(int attempt) {
      try {
        Thread.sleep(ThreadLocalRandom.current().nextLong(1, RETRY_PAUSE_MILLIS * attempt + 1));
        return true;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    @Override
    protected void applyUpdates(List<Ref> newRefs, List<ReceiveCommand> pending)
        throws IOException {
      objectDatabase.recordRefTransaction(pending, mutations, checks);
      if (refDatabase.allUsers) {
        objectDatabase.recordAuthRefs(newRefs, refDatabase.authSeed());
      }
      try {
        super.applyUpdates(newRefs, pending);
      } catch (IOException | RuntimeException exception) {
        if (objectDatabase.refTransactionCommitted()) {
          // The manifest CAS is the commit point. Failure to update this
          // process's JGit cache after it landed must not turn a successful
          // Git transaction into a reported failure; refresh repairs it.
          logger.warn(
              "WalGerrit ref transaction committed, but local cache update failed; refreshing",
              exception);
          try {
            objectDatabase.invalidateCaches();
            refDatabase.refresh();
          } catch (RuntimeException repairFailure) {
            objectDatabase.invalidateCaches();
            logger.warn(
                "WalGerrit cache refresh failed after a committed ref transaction; "
                    + "the next read will rematerialize it",
                repairFailure);
          }
          return;
        }
        if (objectDatabase.refTransactionConflicted()) {
          // Nothing landed; execute() re-runs the transaction against the newer manifest.
          throw exception;
        }
        logger.error("WalGerrit ref publication failed", exception);
        throw exception;
      }
    }
  }
}
