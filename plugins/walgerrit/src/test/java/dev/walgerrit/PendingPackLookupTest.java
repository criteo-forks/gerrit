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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.ManifestReadCountTest.CountingObjectStore;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What it costs readers on a node that a pack of the node is pending behind a publication attempt:
 * log reads are counted, with another node publishing and compacting meanwhile.
 */
class PendingPackLookupTest {
  private static final Project.NameKey PROJECT = Project.nameKey("review/pending-pack");
  private static final String LOG_READS = "GET log/*";
  private static final int READERS = 16;

  @TempDir Path root;

  /**
   * A ref transaction that lost its CAS and then its ref leaves its object pack pending with an
   * attempt the store has refuted. The other node's log then grows; no reader walks it.
   */
  @Test
  void readersDoNotWalkTheLogForARefutedAttempt() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    ObjectId theirs;
    try (Repository repo = b.openRepository(PROJECT)) {
      theirs = WalGitRepositoryManagerTest.insertCommit(repo, "theirs");
    }
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            // The other node creates the same ref first: this node's CAS loses, and so does the
            // transaction when it is validated again.
            () ->
                unchecked(
                    () -> assertEquals(RefUpdate.Result.NEW, create(b, "contested", theirs))));
    CountingObjectStore counting = new CountingObjectStore(hooked);
    WalGitRepositoryManager a = node("a", counting);

    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId mine = WalGitRepositoryManagerTest.insertCommit(writer, "mine");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, create(writer, "contested", mine));
      assertEquals(1, hooked.matchedCasAttempts.get(), "the transaction reached its CAS once");
      GroupPublisher publisher = a.manifestStore(PROJECT).publisher();
      assertTrue(publisher.hasPending(), "the object pack waits for the next publication");

      publishEvents(b, 40);
      a.manifestStore(PROJECT).refresh();
      counting.reset();

      AtomicBoolean stop = new AtomicBoolean();
      ExecutorService competitor = Executors.newSingleThreadExecutor();
      Future<?> competing =
          competitor.submit(
              () -> {
                // Publications and a compaction while the readers run.
                for (int round = 0; !stop.get(); round++) {
                  publishEvents(b, 5);
                  assertEquals(RefUpdate.Result.NEW, create(b, "other-" + round, theirs));
                  if (round == 1) {
                    compact(b);
                  }
                  a.manifestStore(PROJECT).refresh();
                }
                return null;
              });
      try {
        for (int wave = 0; wave < 3; wave++) {
          List<Boolean> listed =
              concurrently(
                  READERS,
                  () -> {
                    try (Repository reader = a.openRepository(PROJECT)) {
                      assertNotNull(reader.open(mine), "the pending pack is listed for readers");
                      return true;
                    }
                  });
          assertEquals(READERS, listed.size());
        }
      } finally {
        stop.set(true);
        competing.get(30, TimeUnit.SECONDS);
        competitor.shutdownNow();
      }
      assertEquals(
          0,
          counting.count(LOG_READS),
          "a refuted attempt is settled where it was refuted: " + counting);
      assertEquals(
          List.of(packOf(publisher)),
          publisher.snapshot(a.manifestStore(PROJECT), true).unpublished().stream()
              .map(PackRef::getName)
              .toList());

      // The pack is still published by the next transaction of the node.
      assertEquals(RefUpdate.Result.NEW, create(writer, "mine", mine));
      assertFalse(publisher.hasPending());
    }
    try (Repository cold = node("cold", shared).openRepository(PROJECT)) {
      assertNotNull(cold.open(cold.exactRef("refs/heads/mine").getObjectId()));
    }
  }

  /**
   * A publication whose response and verification were both lost is unknown to the publisher. The
   * readers that arrive together make one walk of the log between them, and none later, however far
   * the other node moves the head and although it compacts the pack away.
   */
  @Test
  void concurrentReadersShareOneLookupOfAnUnknownAttempt() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicBoolean failRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              failRead.set(true);
              throw new IOException("lost response");
            });
    CountingObjectStore counting = new CountingObjectStore(failingManifestRead(hooked, failRead));
    WalGitRepositoryManager a = node("a", counting);

    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(writer, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, create(writer, "first", first));
      GroupPublisher publisher = a.manifestStore(PROJECT).publisher();
      assertTrue(publisher.hasPending(), "the outcome is unknown, so the pack stays pending");
      long attempted = hooked.firstMatchingProposal.getHeadSeq();

      int published = 40;
      publishEvents(b, published);
      compact(b); // Retires the pack the attempt carried: it is in the history, not in the list.
      Manifest before = a.manifestStore(PROJECT).refresh();
      long behind = before.getHeadSeq() - attempted;
      assertTrue(behind >= published, "the head is " + behind + " entries past the attempt");
      counting.reset();

      List<List<String>> unpublished =
          concurrently(
              READERS,
              () ->
                  publisher.snapshot(a.manifestStore(PROJECT), false).unpublished().stream()
                      .map(PackRef::getName)
                      .toList());
      for (List<String> seen : unpublished) {
        assertEquals(List.of(), seen, "the attempt landed, so its pack is not unpublished");
      }
      long walked = counting.count(LOG_READS);
      assertTrue(walked > 0, "the attempt is below the head, so someone had to look");
      assertTrue(
          walked <= behind,
          READERS + " readers read " + walked + " log entries for an attempt " + behind + " back");

      // Later readers pay nothing, wherever the head has got to.
      publishEvents(b, 25);
      a.manifestStore(PROJECT).refresh();
      counting.reset();
      concurrently(
          READERS,
          () -> {
            try (Repository reader = a.openRepository(PROJECT)) {
              assertEquals(first, reader.exactRef("refs/heads/first").getObjectId());
              assertNotNull(reader.open(first));
              return true;
            }
          });
      assertEquals(0, counting.count(LOG_READS), "the outcome is known: " + counting);

      // The publisher settles the same attempt without another walk either.
      counting.reset();
      assertEquals(RefUpdate.Result.NEW, create(writer, "second", first));
      assertEquals(
          0, counting.count(LOG_READS), "recovery reuses the readers' answer: " + counting);
      assertFalse(publisher.hasPending());
    }
  }

  /**
   * An attempt the head has not reached is not known to have failed. Its pack is listed as
   * unpublished without a read, and found published once the delayed request lands.
   */
  @Test
  void anAttemptTheHeadHasNotReachedIsAskedAgain() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicReference<Object[]> delayed = new AtomicReference<>();
    AtomicBoolean failNextLog = new AtomicBoolean();
    ObjectStore lagging =
        intercept(
            shared,
            (method, args) -> {
              if (method.getName().equals("compareAndSwap")
                  && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
                  && delayed.compareAndSet(null, args.clone())) {
                throw new IOException("client timed out; the request is still in flight");
              }
              if (method.getName().equals("putIfAbsent")
                  && ((String) args[0]).contains("/log/")
                  && failNextLog.compareAndSet(true, false)) {
                throw new IOException("the fence could not be written");
              }
              return invoke(shared, method, args);
            });
    CountingObjectStore counting = new CountingObjectStore(lagging);
    WalGitRepositoryManager a = node("a", counting);

    Repository writer = a.openRepository(PROJECT);
    ObjectId first = WalGitRepositoryManagerTest.insertCommit(writer, "first");
    assertEquals(RefUpdate.Result.LOCK_FAILURE, create(writer, "first", first));
    GroupPublisher publisher = a.manifestStore(PROJECT).publisher();
    String pack = packOf(publisher);
    // Closing tries to settle the attempt with a fence, which fails: the attempt stays unknown.
    failNextLog.set(true);
    writer.close();
    assertTrue(publisher.hasPending());

    counting.reset();
    for (int i = 0; i < 3; i++) {
      assertEquals(
          List.of(pack),
          publisher.snapshot(a.manifestStore(PROJECT), true).unpublished().stream()
              .map(PackRef::getName)
              .toList(),
          "not in the history yet");
    }
    assertEquals(0, counting.count(LOG_READS), "nothing to walk below the attempt: " + counting);

    // The request the client gave up on arrives after all, and the other node moves on.
    Object[] original = delayed.get();
    shared.compareAndSwap((String) original[0], (String) original[1], (byte[]) original[2]);
    publishEvents(b, 7);

    GroupPublisher.Snapshot landed = publisher.snapshot(a.manifestStore(PROJECT), true);
    assertEquals(List.of(), landed.unpublished(), "the attempt is in the history now");
    assertTrue(
        landed.manifest().getPacksList().stream().anyMatch(p -> p.getName().equals(pack)),
        "and the manifest lists its pack");
    assertEquals(7, counting.count(LOG_READS), "one walk from the head to the attempt");
    publishEvents(b, 5);
    assertEquals(List.of(), publisher.snapshot(a.manifestStore(PROJECT), true).unpublished());
    assertEquals(7, counting.count(LOG_READS), "and none after it");
  }

  /** A lookup that fails tells the readers waiting for it, and is made again by the next one. */
  @Test
  void aFailedLookupIsNotRemembered() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicBoolean failRead = new AtomicBoolean();
    AtomicBoolean failLog = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              failRead.set(true);
              throw new IOException("lost response");
            });
    ObjectStore flaky =
        intercept(
            failingManifestRead(hooked, failRead),
            (method, args) -> {
              if (method.getName().equals("get")
                  && ((String) args[0]).contains("/log/")
                  && failLog.get()) {
                throw new IOException("log GET failed");
              }
              return invoke(failingManifestRead(hooked, failRead), method, args);
            });
    WalGitRepositoryManager a = node("a", flaky);

    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(writer, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, create(writer, "first", first));
      GroupPublisher publisher = a.manifestStore(PROJECT).publisher();
      publishEvents(b, 3);

      failLog.set(true);
      assertThrows(IOException.class, () -> publisher.snapshot(a.manifestStore(PROJECT), true));
      failLog.set(false);
      assertEquals(List.of(), publisher.snapshot(a.manifestStore(PROJECT), true).unpublished());
    }
  }

  private static String packOf(GroupPublisher publisher) {
    List<PackRef> pending = publisher.pending();
    assertEquals(1, pending.size(), "one pack is pending");
    return pending.get(0).getName();
  }

  private static void publishEvents(WalGitRepositoryManager node, int count) throws IOException {
    for (int i = 0; i < count; i++) {
      node.manifestStore(PROJECT).publishEvents(List.of("{}"));
    }
  }

  private static void compact(WalGitRepositoryManager node) throws Exception {
    try (LocalWalGitRepository repo = (LocalWalGitRepository) node.openRepository(PROJECT)) {
      WalGitRepositoryManagerTest.insertCommit(repo, "another pack");
      DfsPackCompactor compactor = new DfsPackCompactor(repo);
      for (var pack : repo.getObjectDatabase().getPacks()) {
        compactor.add(pack);
      }
      compactor.compact(NullProgressMonitor.INSTANCE);
    }
  }

  /** Runs {@code work} on {@code threads} threads released together. */
  private static <T> List<T> concurrently(int threads, Callable<T> work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier together = new CyclicBarrier(threads);
      List<Future<T>> running = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        running.add(
            pool.submit(
                () -> {
                  together.await(30, TimeUnit.SECONDS);
                  return work.call();
                }));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> one : running) {
        results.add(one.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static RefUpdate.Result create(Repository repo, String name, ObjectId id)
      throws IOException {
    RefUpdate update = repo.updateRef("refs/heads/" + name);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(id);
    return update.update();
  }

  private static RefUpdate.Result create(WalGitRepositoryManager node, String name, ObjectId id)
      throws IOException {
    try (Repository repo = node.openRepository(PROJECT)) {
      return create(repo, name, id);
    }
  }

  private static ObjectStore failingManifestRead(ObjectStore delegate, AtomicBoolean failOnce) {
    return intercept(
        delegate,
        (method, args) -> {
          if ((method.getName().equals("get") || method.getName().equals("getIfChanged"))
              && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
              && failOnce.compareAndSet(true, false)) {
            throw new IOException("manifest read failed");
          }
          return invoke(delegate, method, args);
        });
  }

  @FunctionalInterface
  private interface Invocation {
    Object call(Method method, Object[] args) throws Throwable;
  }

  @FunctionalInterface
  private interface Failing {
    void run() throws Exception;
  }

  private static void unchecked(Failing work) {
    try {
      work.run();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static ObjectStore intercept(ObjectStore delegate, Invocation invocation) {
    return (ObjectStore)
        Proxy.newProxyInstance(
            ObjectStore.class.getClassLoader(),
            new Class<?>[] {ObjectStore.class},
            (proxy, method, args) -> invocation.call(method, args));
  }

  private static Object invoke(ObjectStore delegate, Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }
}
