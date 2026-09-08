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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery overlapping maintenance: a lost CAS outcome settled after another node compacted and
 * reclaimed, and reclamation racing a publication's handoff. Reproductions from the 2026-09-08
 * review of the group-commit follow-up.
 */
class RecoveryFaultTest {
  private static final Project.NameKey PROJECT = Project.nameKey("review/recovery");
  private static final String WAL = "repos/review/recovery.git/wal/";
  @TempDir Path root;

  @Test
  void retryAfterCompactionMustNotResurrectReclaimedPacks() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    var failNextRead = new java.util.concurrent.atomic.AtomicBoolean();
    HookedObjectStore hooked = new HookedObjectStore(shared,
        HookedObjectStore::publishesRefChange, () -> {},
        () -> { failNextRead.set(true); throw new IOException("lost CAS response"); });
    ObjectStore flaky = (ObjectStore) java.lang.reflect.Proxy.newProxyInstance(
        ObjectStore.class.getClassLoader(), new Class<?>[] {ObjectStore.class},
        (proxy, method, args) -> {
          if ((method.getName().equals("get") || method.getName().equals("getIfChanged"))
              && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
              && failNextRead.compareAndSet(true, false)) {
            throw new IOException("verification read failed");
          }
          try { return method.invoke(hooked, args); }
          catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
    WalGitRepositoryManager a = node("a", flaky);
    WalGitRepositoryManager b = node("b", shared);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      var pending = a.storage().manifestStore(PROJECT).publisher().pending();
      assertEquals(1, pending.size());
      RefUpdate update = repo.updateRef("refs/heads/first");
      update.setNewObjectId(first);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update.update());

      // The successful-but-unacknowledged pack is superseded on another node before A retries.
      try (LocalWalGitRepository compacting = (LocalWalGitRepository) b.openRepository(PROJECT)) {
        assertEquals(first, compacting.exactRef("refs/heads/first").getObjectId());
        WalGitRepositoryManagerTest.insertCommit(compacting, "second pack");
        DfsPackCompactor compactor = new DfsPackCompactor(compacting);
        for (var pack : compacting.getObjectDatabase().getPacks()) compactor.add(pack);
        compactor.compact(NullProgressMonitor.INSTANCE);
      }
      String retired = pending.get(0).getName();
      assertFalse(b.storage().manifestStore(PROJECT).refresh().getPacksList().stream()
          .anyMatch(pack -> pack.getName().equals(retired)));
      SteppingClock afterGrace = new SteppingClock(Instant.now().plus(Duration.ofDays(2)));
      Reclaimer reclaimer = new Reclaimer(b, afterGrace, Duration.ofDays(1), 0);
      reclaimer.reclaim(PROJECT);
      afterGrace.advance(Duration.ofDays(2));
      reclaimer.reclaim(PROJECT);
      assertTrue(
          shared.get(WAL + retired + ".idx").isEmpty(), "retired files are legitimately reclaimed");

      assertEquals(RefUpdate.Result.NEW, update(a, "unrelated", first));
      org.eclipse.jgit.internal.storage.dfs.DfsBlockCache.reconfigure(
          new org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig());
      try (Repository cold = node("cold", shared).openRepository(PROJECT)) {
        assertNotNull(cold.open(first), "an acknowledged ref must remain readable after recovery");
      }
    }
  }

  @Test
  void reclamationSnapshotMustNotLosePacksAtThePublicationHandoff() throws Exception {
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
    WalGitRepositoryManager a = node("a", hooked);
    ExecutorService pool = Executors.newCachedThreadPool();
    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId pending =
          WalGitRepositoryManagerTest.insertCommit(writer, "waiting for publication");
      var packs = a.storage().manifestStore(PROJECT).publisher().pending();
      for (var pack : packs) {
        for (var file : pack.getFilesList()) {
          java.nio.file.Files.setLastModifiedTime(root.resolve("store").resolve(WAL)
              .resolve(pack.getName() + "." + file.getExtension()),
              java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        }
      }
      Future<RefUpdate.Result> publishing = pool.submit(() -> update(a, "unrelated", pending));
      await(inCas);
      try {
        // The reclaimer reads the old manifest, then the publication commits before it samples pending().
        new Reclaimer(a, Clock.systemUTC(), Duration.ofDays(1), 0).reclaimAll((project, manifest) -> {
          release.countDown();
          try { assertEquals(RefUpdate.Result.NEW, publishing.get(10, TimeUnit.SECONDS)); }
          catch (Exception e) { throw new RuntimeException(e); }
        });
        for (String file : ManifestStore.fileNames(packs)) {
          assertTrue(shared.get(WAL + file).isPresent(), "committed file was reclaimed: " + file);
        }
        assertEquals(RefUpdate.Result.NEW, update(a, "pending", pending));
      } finally { release.countDown(); }
    } finally { release.countDown(); pool.shutdownNow(); }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }

  private static RefUpdate.Result update(WalGitRepositoryManager node, String name, ObjectId id) throws IOException {
    try (Repository repo = node.openRepository(PROJECT)) {
      RefUpdate update = repo.updateRef("refs/heads/" + name);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(id);
      return update.update();
    }
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
    catch (InterruptedException e) { throw new RuntimeException(e); }
  }
}
