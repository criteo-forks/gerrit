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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableBatchRefUpdate;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
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
  /** The manifest revision the cached reftable stack was built from. */
  private volatile long stackRevision = -1;

  LocalWalGitRefDatabase(
      DfsRepository repository, LocalWalGitObjectDatabase objectDatabase) {
    super(repository);
    this.objectDatabase = objectDatabase;
  }

  @Override
  public BatchRefUpdate newBatchUpdate() {
    return new WalGitBatchRefUpdate(this, objectDatabase);
  }

  @Override
  public Ref exactRef(String name) throws IOException {
    revalidate();
    return super.exactRef(name);
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
  private void revalidate() throws IOException {
    objectDatabase.revalidateIfStale();
    if (objectDatabase.observedManifestRevision() != stackRevision) {
      refresh();
    }
  }

  @Override
  public void refresh() {
    super.refresh();
    stackRevision = objectDatabase.observedManifestRevision();
  }

  private static final class WalGitBatchRefUpdate extends DfsReftableBatchRefUpdate {
    private static final Logger logger = LoggerFactory.getLogger(WalGitBatchRefUpdate.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_PAUSE_MILLIS = 20;

    private final LocalWalGitRefDatabase refDatabase;
    private final LocalWalGitObjectDatabase objectDatabase;

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
      ReentrantLock nodeLock = objectDatabase.writeLock();
      nodeLock.lock();
      try {
        for (int attempt = 1; ; attempt++) {
          List<ReceiveCommand> pending = pending();
          if (pending.isEmpty()) {
            return;
          }
          try {
            objectDatabase.beginRefTransaction();
            refDatabase.refresh();
            super.execute(walk, monitor, options);
            if (!objectDatabase.refTransactionConflicted() || attempt >= MAX_ATTEMPTS) {
              return;
            }
          } catch (IOException exception) {
            logger.error("WalGerrit could not start a ref transaction", exception);
            abort(pending);
            return;
          } finally {
            objectDatabase.endRefTransaction();
          }
          logger.info(
              "Refs of {} changed on another node during a ref transaction; retrying ({}/{})",
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
      } finally {
        nodeLock.unlock();
      }
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
      objectDatabase.recordRefTransaction(pending);
      try {
        super.applyUpdates(newRefs, pending);
      } catch (IOException exception) {
        if (objectDatabase.refTransactionCommitted()) {
          // The manifest CAS is the commit point. Failure to update this
          // process's JGit cache after it landed must not turn a successful
          // Git transaction into a reported failure; refresh repairs it.
          logger.warn(
              "WalGerrit ref transaction committed, but local cache update failed; refreshing",
              exception);
          try {
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
