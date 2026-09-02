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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Independent ref updates to one repository must all land whether they race within a node or
 * across nodes, as they do on Gerrit's file-based backends where each ref has its own lock.
 */
class ConcurrentWritersTest {
  @TempDir Path root;

  @Test
  void writersOnOneNodeTakeTurnsAndAllSucceed() throws Exception {
    WalGitRepositoryManager node = node("node-a", new FileObjectStore(root.resolve("store")));
    Project.NameKey project = Project.nameKey("platform/parallel");
    node.createRepository(project).close();

    int writers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<RefUpdate.Result>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        String ref = Constants.R_HEADS + "writer-" + i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try (Repository handle = node.openRepository(project)) {
                    return createRef(handle, ref);
                  }
                }));
      }
      start.countDown();
      for (Future<RefUpdate.Result> result : results) {
        assertEquals(RefUpdate.Result.NEW, result.get());
      }
    } finally {
      pool.shutdownNow();
    }

    try (Repository verify = node.openRepository(project)) {
      assertEquals(writers, verify.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).size());
    }
  }

  @Test
  void transactionThatLosesTheManifestCasToAnotherNodeRetriesAndSucceeds() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager nodeB = node("node-b", shared);
    Project.NameKey project = Project.nameKey("platform/cross-node");
    nodeB.createRepository(project).close();

    // Node B moves a different ref right before node A's ref transaction publishes.
    InterferingStore store =
        new InterferingStore(
            shared,
            () -> {
              try (Repository other = nodeB.openRepository(project)) {
                assertEquals(RefUpdate.Result.NEW, createRef(other, Constants.R_HEADS + "from-b"));
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            });
    WalGitRepositoryManager nodeA = node("node-a", store);

    try (Repository handle = nodeA.openRepository(project)) {
      assertEquals(RefUpdate.Result.NEW, createRef(handle, Constants.R_HEADS + "from-a"));
      assertEquals(2, store.refCasAttempts.get(), "the lost CAS was retried once");
      assertNotNull(handle.exactRef(Constants.R_HEADS + "from-a"));
      assertNotNull(handle.exactRef(Constants.R_HEADS + "from-b"));
    }
    try (Repository verify = nodeB.openRepository(project)) {
      assertEquals(2, verify.getRefDatabase().getRefsByPrefix(Constants.R_HEADS).size());
    }
  }

  @Test
  void retryStillRefusesAnUpdateWhoseRefMovedOnAnotherNode() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager nodeB = node("node-b", shared);
    Project.NameKey project = Project.nameKey("platform/moved-ref");
    nodeB.createRepository(project).close();
    String main = Constants.R_HEADS + "main";

    InterferingStore store =
        new InterferingStore(
            shared,
            () -> {
              try (Repository other = nodeB.openRepository(project)) {
                assertEquals(RefUpdate.Result.NEW, createRef(other, main));
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            });
    WalGitRepositoryManager nodeA = node("node-a", store);

    try (Repository handle = nodeA.openRepository(project)) {
      // The re-run checks expected values against the newer manifest: main now exists, so the
      // create is refused before it publishes anything.
      assertEquals(RefUpdate.Result.LOCK_FAILURE, createRef(handle, main));
      assertEquals(1, store.refCasAttempts.get());
      assertNotNull(handle.exactRef(main));
    }
  }

  private static RefUpdate.Result createRef(Repository repository, String name) throws IOException {
    ObjectId commit;
    try {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "for " + name);
    } catch (Exception exception) {
      throw new IOException(exception);
    }
    RefUpdate update = repository.updateRef(name);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(commit);
    return update.update();
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root.resolve(name));
    StorageLayout layout =
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), "");
    return new WalGitRepositoryManager(configuration, layout);
  }

  /**
   * Counts manifest compare-and-swaps that publish a ref change, and runs a hook right before the
   * first of them: the window in which another node's write makes the CAS fail.
   */
  private static final class InterferingStore implements ObjectStore {
    final AtomicInteger refCasAttempts = new AtomicInteger();
    private final ObjectStore delegate;
    private final Runnable beforeFirstRefCas;

    InterferingStore(ObjectStore delegate, Runnable beforeFirstRefCas) {
      this.delegate = delegate;
      this.beforeFirstRefCas = beforeFirstRefCas;
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      return delegate.get(key);
    }

    @Override
    public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
        throws IOException {
      if (key.endsWith(ManifestStore.MANIFEST_FILE) && publishesRefChange(key, bytes)) {
        if (refCasAttempts.getAndIncrement() == 0) {
          beforeFirstRefCas.run();
        }
      }
      return delegate.compareAndSwap(key, expectedVersion, bytes);
    }

    private boolean publishesRefChange(String key, byte[] proposed) throws IOException {
      Manifest current = Manifest.parseFrom(delegate.get(key).orElseThrow().bytes());
      return Manifest.parseFrom(proposed).getRefRevision() > current.getRefRevision();
    }

    @Override
    public void uploadIfAbsent(String key, Path source) throws IOException {
      delegate.uploadIfAbsent(key, source);
    }

    @Override
    public void download(String key, Path target) throws IOException {
      delegate.download(key, target);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
      return delegate.list(prefix);
    }

    @Override
    public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
      return delegate.listWithVersions(prefix);
    }
  }
}
