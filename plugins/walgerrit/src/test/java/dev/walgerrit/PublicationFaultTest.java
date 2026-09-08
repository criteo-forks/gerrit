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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Publication under faults: lost CAS responses, failed verification reads, and readers racing a
 * publication in flight. Reproductions from the 2026-09-08 review of the group-commit change.
 */
class PublicationFaultTest {
  private static final Project.NameKey PROJECT = Project.nameKey("review/races");
  @TempDir Path root;

  @Test
  void aFlushedPackStaysReadableWhileAnUnrelatedPublicationCarriesIt() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager setup = node("setup", shared);
    ObjectId existing;
    try (Repository repo = setup.createRepository(PROJECT)) {
      existing = WalGitRepositoryManagerTest.insertCommit(repo, "existing");
    }
    CountDownLatch inCas = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HookedObjectStore hooked = new HookedObjectStore(shared,
        HookedObjectStore::publishesRefChange, () -> { inCas.countDown(); await(release); });
    WalGitRepositoryManager node = node("a", hooked);
    ExecutorService pool = Executors.newCachedThreadPool();
    try (Repository writer = node.openRepository(PROJECT)) {
      ObjectId pending = WalGitRepositoryManagerTest.insertCommit(writer, "not yet referenced");
      try (Repository reader = node.openRepository(PROJECT)) {
        assertNotNull(reader.open(pending));
      }
      Future<RefUpdate.Result> publishing = pool.submit(() -> update(node, "unrelated", ObjectId.zeroId(), existing));
      await(inCas);
      try (Repository reader = node.openRepository(PROJECT)) {
        assertNotNull(reader.open(pending), "the flushed pack must remain readable until publication finishes");
      } finally {
        release.countDown();
        publishing.get(10, TimeUnit.SECONDS);
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void unknownCasOutcomeMustNotPoisonSubsequentIndependentPushes() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    var failNextRead = new java.util.concurrent.atomic.AtomicBoolean();
    HookedObjectStore hooked = new HookedObjectStore(shared,
        HookedObjectStore::publishesRefChange, () -> {},
        () -> {
          failNextRead.set(true);
          throw new IOException("lost CAS response");
        });
    ObjectStore flaky = (ObjectStore) java.lang.reflect.Proxy.newProxyInstance(
        ObjectStore.class.getClassLoader(), new Class<?>[] {ObjectStore.class},
        (proxy, method, args) -> {
          if ((method.getName().equals("get") || method.getName().equals("getIfChanged"))
              && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
              && failNextRead.compareAndSet(true, false)) {
            throw new IOException("outcome-verification GET also failed");
          }
          try { return method.invoke(hooked, args); }
          catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
    WalGitRepositoryManager node = node("a", flaky);
    try (Repository repo = node.openRepository(PROJECT)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repo, "first push");
      RefUpdate first = repo.updateRef("refs/heads/first");
      first.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, first.update(), "first outcome is unknown");
      try (Repository other = node("b", shared).openRepository(PROJECT)) {
        assertEquals(commit, other.exactRef("refs/heads/first").getObjectId(), "first actually landed");
      }
      RefUpdate.Result later = update(node, "unrelated", ObjectId.zeroId(), commit);
      String detail = "";
      if (later != RefUpdate.Result.NEW) {
        ManifestStore store = node.storage().manifestStore(PROJECT);
        try { store.publisher().publish(GroupPublisher.Request.flush(store)); }
        catch (IOException stuck) { detail = "; retry failed with: " + stuck; }
      }
      assertEquals(RefUpdate.Result.NEW, later,
          "the store is healthy again; a disjoint push must succeed" + detail);
    }
  }

  @Test
  void recoveredCasMustNotBlessQueuedTransactionsAcrossAnExternalRefChange() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager setup = node("setup", shared);
    ObjectId old, ours, theirs;
    try (Repository repo = setup.createRepository(PROJECT)) {
      old = WalGitRepositoryManagerTest.insertCommit(repo, "old");
      ours = WalGitRepositoryManagerTest.insertCommit(repo, "ours");
      theirs = WalGitRepositoryManagerTest.insertCommit(repo, "theirs");
    }
    assertEquals(RefUpdate.Result.NEW, update(setup, "target", ObjectId.zeroId(), old));
    WalGitRepositoryManager other = node("b", shared);
    CountDownLatch inCas = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HookedObjectStore hooked = new HookedObjectStore(shared,
        HookedObjectStore::publishesRefChange,
        () -> { inCas.countDown(); await(release); },
        () -> {
          assertEquals(RefUpdate.Result.FORCED, update(other, "target", old, theirs));
          throw new IOException("lost successful CAS response after node B updated target");
        });
    WalGitRepositoryManager node = node("a", hooked);
    ExecutorService pool = Executors.newCachedThreadPool();
    try {
      Future<RefUpdate.Result> lead = pool.submit(() -> update(node, "lead", ObjectId.zeroId(), old));
      await(inCas);
      Future<RefUpdate.Result> queued = pool.submit(() -> update(node, "target", old, ours));
      awaitQueued(node.storage().manifestStore(PROJECT).publisher());
      release.countDown();
      assertEquals(RefUpdate.Result.NEW, lead.get(10, TimeUnit.SECONDS));
      RefUpdate.Result result = queued.get(10, TimeUnit.SECONDS);
      try (Repository reader = other.openRepository(PROJECT)) {
        assertEquals(theirs, reader.exactRef("refs/heads/target").getObjectId(),
            "the external write remains the effective value despite local success");
        assertEquals(RefUpdate.Result.LOCK_FAILURE, result,
            "expected-old no longer matches; actual stored target=" + reader.exactRef("refs/heads/target").getObjectId());
      }
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private static RefUpdate.Result update(WalGitRepositoryManager node, String name,
      ObjectId old, ObjectId next) throws IOException {
    try (Repository repo = node.openRepository(PROJECT)) {
      RefUpdate update = repo.updateRef("refs/heads/" + name);
      update.setExpectedOldObjectId(old);
      update.setNewObjectId(next);
      update.setForceUpdate(true);
      return update.update();
    }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
    catch (InterruptedException e) { throw new RuntimeException(e); }
  }

  private static void awaitQueued(GroupPublisher publisher) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (publisher.queuedForTesting() > 0) {
        return;
      }
      Thread.sleep(5);
    }
    fail("second transaction did not queue");
  }
}
