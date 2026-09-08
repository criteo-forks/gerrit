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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Group commit and deferred pack publication: what a busy repository costs on one node. */
class GroupCommitTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/busy");
  private static final int WRITERS = 6;

  @TempDir Path root;

  @Test
  void disjointTransactionsOnOneNodeLandInOnePublication() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    CountDownLatch everyoneStarted = new CountDownLatch(WRITERS);
    // The first ref CAS holds until every writer has started and had time to queue behind it.
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {
              await(everyoneStarted);
              pause(500);
            });
    node("setup", shared, ignored -> {}).createRepository(PROJECT).close();
    WalGitRepositoryManager node = node("node-a", hooked, ignored -> {});
    Map<String, ObjectId> expected = createRefsConcurrently(node, everyoneStarted, "t");

    assertEquals(
        2, hooked.matchedCasAttempts.get(), "the held CAS, then one CAS for everybody who queued");
    Manifest manifest = manifest(shared);
    LogEntry group = entry(shared, manifest);
    assertEquals(LogEntry.Kind.REF_UPDATE, group.getKind());
    assertEquals(WRITERS - 1, group.getRefTransaction().getUpdatesCount(), "one entry, five transactions");
    assertEquals(
        WRITERS - 1,
        group.getAdditionsList().stream().filter(CompactionPolicy::hasReftable).count(),
        "every member's reftable travels in the group's entry; packs ride in whichever "
            + "publication came first after their flush");
    assertEquals(expected, allRefs(node("node-b", shared, ignored -> {})));
    try (Repository reader = node("node-c", shared, ignored -> {}).openRepository(PROJECT)) {
      for (ObjectId commit : expected.values()) {
        assertNotNull(reader.open(commit, Constants.OBJ_COMMIT), "objects landed with their refs");
      }
    }
  }

  @Test
  void conflictingNamesWaitForTheEarlierTransactionAndValidateAgainstItsResult() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    CountDownLatch firstInCas = new CountDownLatch(1);
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {
              firstInCas.countDown();
              pause(400);
            });
    node("setup", shared, ignored -> {}).createRepository(PROJECT).close();
    WalGitRepositoryManager node = node("node-a", hooked, ignored -> {});
    ExecutorService pool = Executors.newCachedThreadPool();
    try {
      Future<RefUpdate.Result> first =
          pool.submit(() -> createRef(node, Constants.R_HEADS + "main", "first"));
      await(firstInCas);
      Future<RefUpdate.Result> sameName =
          pool.submit(() -> createRef(node, Constants.R_HEADS + "main", "second"));
      Future<RefUpdate.Result> nested =
          pool.submit(() -> createRef(node, Constants.R_HEADS + "main/nested", "third"));
      assertEquals(RefUpdate.Result.NEW, first.get(30, TimeUnit.SECONDS));
      assertNotEquals(
          RefUpdate.Result.NEW,
          sameName.get(30, TimeUnit.SECONDS),
          "the second creation validated after the first landed and saw the ref exist");
      assertNotEquals(
          RefUpdate.Result.NEW,
          nested.get(30, TimeUnit.SECONDS),
          "a name beneath a ref that just landed is a conflict, not a group member");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, hooked.matchedCasAttempts.get(), "only the winner ever reached the store");
    assertEquals(Set.of(Constants.R_HEADS + "main"), allRefs(node).keySet());
  }

  @Test
  void aGroupThatLosesTheCasToAnotherNodeRerunsEveryMember() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager nodeB = node("node-b", shared, ignored -> {});
    CountDownLatch everyoneStarted = new CountDownLatch(WRITERS);
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {
              await(everyoneStarted);
              pause(400);
              try {
                assertEquals(
                    RefUpdate.Result.NEW, createRef(nodeB, Constants.R_HEADS + "from-b", "from b"));
              } catch (Exception exception) {
                throw new IllegalStateException(exception);
              }
            });
    nodeB.createRepository(PROJECT).close();
    WalGitRepositoryManager nodeA = node("node-a", hooked, ignored -> {});
    Map<String, ObjectId> expected = createRefsConcurrently(nodeA, everyoneStarted, "a");

    assertTrue(hooked.matchedCasAttempts.get() >= 2, "the lost CAS was retried");
    Map<String, ObjectId> landed = allRefs(node("node-c", shared, ignored -> {}));
    assertNotNull(landed.remove(Constants.R_HEADS + "from-b"), "node B's ref survived");
    assertEquals(expected, landed, "every member of the failed group landed on re-run");
  }

  @Test
  void packsCommittedWithoutARefTransactionArePublishedWhenTheHandleCloses() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node-a", shared, ignored -> {});
    node.createRepository(PROJECT).close();
    long sequenceBefore = manifest(shared).getHeadSeq();
    ObjectId commit;
    try (Repository repository = node.openRepository(PROJECT)) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "flushed, never referenced");
      assertEquals(
          sequenceBefore, manifest(shared).getHeadSeq(), "a flush alone publishes nothing");
      assertNotNull(repository.open(commit, Constants.OBJ_COMMIT), "the handle that wrote it reads it");
    }
    Manifest manifest = manifest(shared);
    assertEquals(sequenceBefore + 1, manifest.getHeadSeq(), "closing the handle published the pack");
    LogEntry entry = entry(shared, manifest);
    assertEquals(LogEntry.Kind.PACK, entry.getKind());
    assertEquals(1, entry.getAdditionsCount());
    try (Repository other = node("node-b", shared, ignored -> {}).openRepository(PROJECT)) {
      assertNotNull(other.open(commit, Constants.OBJ_COMMIT));
    }
  }

  @Test
  void reclamationSparesPacksWaitingForTheirRefTransaction() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node =
        node("node-a", shared, config -> config.setString("walgerrit", null, "reclaimGrace", "0"));
    node.createRepository(PROJECT).close();
    try (Repository repository = node.openRepository(PROJECT)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, "pending during a sweep");
      int filesBefore = shared.listWithVersions("repos/" + PROJECT.get() + ".git/wal/").size();
      node.compactor().reclaimer().reclaim(PROJECT);
      assertEquals(
          filesBefore,
          shared.listWithVersions("repos/" + PROJECT.get() + ".git/wal/").size(),
          "an unpublished pack is referenced by the transaction to come, not garbage");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }
    try (Repository other = node("node-b", shared, ignored -> {}).openRepository(PROJECT)) {
      assertNotNull(
          other.open(other.exactRef(Constants.R_HEADS + "main").getObjectId(), Constants.OBJ_COMMIT));
    }
  }

  /** {@value #WRITERS} handles each create one ref; the refs and their commits, by name. */
  private Map<String, ObjectId> createRefsConcurrently(
      WalGitRepositoryManager node, CountDownLatch started, String prefix) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
    Map<String, ObjectId> expected = new TreeMap<>();
    try {
      List<Future<ObjectId>> results = new ArrayList<>();
      for (int i = 0; i < WRITERS; i++) {
        String name = Constants.R_HEADS + prefix + i;
        results.add(
            pool.submit(
                () -> {
                  started.countDown();
                  try (Repository repository = node.openRepository(PROJECT)) {
                    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, name);
                    RefUpdate update = repository.updateRef(name);
                    update.setExpectedOldObjectId(ObjectId.zeroId());
                    update.setNewObjectId(commit);
                    assertEquals(RefUpdate.Result.NEW, update.update(), name);
                    return commit;
                  }
                }));
      }
      for (int i = 0; i < WRITERS; i++) {
        expected.put(Constants.R_HEADS + prefix + i, results.get(i).get(60, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }
    return expected;
  }

  private static RefUpdate.Result createRef(
      WalGitRepositoryManager node, String name, String message) throws Exception {
    try (Repository repository = node.openRepository(PROJECT)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
      RefUpdate update = repository.updateRef(name);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commit);
      try {
        return update.update();
      } catch (IOException nameConflict) {
        // JGit reports a name conflict inside an atomic single-command batch as "transaction
        // aborted", which DfsReftableDatabase.compareAndPut turns into an IOException.
        return RefUpdate.Result.REJECTED_OTHER_REASON;
      }
    }
  }

  private static Manifest manifest(ObjectStore store) throws IOException {
    return Manifest.parseFrom(
        store
            .get("manifests/" + PROJECT.get() + ".git/" + ManifestStore.MANIFEST_FILE)
            .orElseThrow()
            .bytes());
  }

  private static LogEntry entry(ObjectStore store, Manifest manifest) throws IOException {
    String key =
        "repos/"
            + PROJECT.get()
            + ".git/"
            + ManifestStore.logKey(manifest.getHeadSeq(), manifest.getHeadTransactionId());
    return LogEntry.parseFrom(store.get(key).orElseThrow().bytes());
  }

  private static Map<String, ObjectId> allRefs(WalGitRepositoryManager node) throws IOException {
    Map<String, ObjectId> refs = new TreeMap<>();
    try (Repository repository = node.openRepository(PROJECT)) {
      for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
        refs.put(ref.getName(), ref.getObjectId());
      }
    }
    return refs;
  }

  private WalGitRepositoryManager node(String name, ObjectStore store, Consumer<Config> tweak) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    tweak.accept(config);
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root.resolve(name));
    StorageLayout layout =
        new StorageLayout(store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), "");
    return new WalGitRepositoryManager(configuration, layout);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(30, TimeUnit.SECONDS), "writers started in time");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

}
