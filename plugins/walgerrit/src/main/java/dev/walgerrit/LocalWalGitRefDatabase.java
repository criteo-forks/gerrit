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
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableBatchRefUpdate;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reftable storage makes each Gerrit batch ref update one immutable DFS file. */
final class LocalWalGitRefDatabase extends DfsReftableDatabase {
  private final LocalWalGitObjectDatabase objectDatabase;

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

  private void revalidate() throws IOException {
    if (objectDatabase.revalidateManifest()) {
      super.refresh();
    }
  }

  private static final class WalGitBatchRefUpdate extends DfsReftableBatchRefUpdate {
    private static final Logger logger = LoggerFactory.getLogger(WalGitBatchRefUpdate.class);

    private final LocalWalGitRefDatabase refDatabase;
    private final LocalWalGitObjectDatabase objectDatabase;

    WalGitBatchRefUpdate(
        LocalWalGitRefDatabase refDatabase,
        LocalWalGitObjectDatabase objectDatabase) {
      super(refDatabase, objectDatabase);
      this.refDatabase = refDatabase;
      this.objectDatabase = objectDatabase;
    }

    @Override
    public void execute(
        RevWalk walk, ProgressMonitor monitor, List<String> options) {
      try {
        objectDatabase.beginRefTransaction();
        refDatabase.refresh();
        super.execute(walk, monitor, options);
      } catch (IOException exception) {
        logger.error("WalGerrit could not start a ref transaction", exception);
        List<ReceiveCommand> pending =
            ReceiveCommand.filter(getCommands(), ReceiveCommand.Result.NOT_ATTEMPTED);
        if (!pending.isEmpty()) {
          pending.get(0).setResult(ReceiveCommand.Result.LOCK_FAILURE, "io error");
          ReceiveCommand.abort(pending);
        }
      } finally {
        objectDatabase.endRefTransaction();
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
        logger.error("WalGerrit ref publication failed", exception);
        throw exception;
      }
    }
  }
}
