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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.IndexEventTailer.Indexed;
import dev.walgerrit.ManifestReadCountTest.CountingObjectStore;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pushes for review and topic lookups against nodes that share one store and each keep their own
 * change index.
 *
 * <p>A node's index holds the project, branch, Change-Id and topic of each change, taken from its
 * meta ref. {@link Node#push} passes the barrier the way Gerrit's push does, is rejected when the
 * barrier says the index may be stale, and otherwise looks the Change-Id up in the node's index,
 * creates a change or adds a patch set through a ref transaction, and indexes its own write. {@link
 * Node#topic} passes the barrier the way a submission does before it looks a topic up.
 */
class IndexBarrierTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/app");
  private static final Project.NameKey OTHER = Project.nameKey("platform/other");
  private static final String BRANCH = "refs/heads/main";

  @TempDir Path root;

  /** The change-number sequence, which every node draws from. */
  private final AtomicInteger changeNumbers = new AtomicInteger(1000);

  private final List<Node> nodes = new ArrayList<>();
  private ObjectStore bucket;

  @AfterEach
  void stopNodes() {
    for (Node node : nodes) {
      node.close();
    }
  }

  @Test
  void aPushRightAfterAnotherNodesPushFindsItsChange() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);

    for (int i = 0; i < 20; i++) {
      Node creator = i % 2 == 0 ? first : second;
      Node other = i % 2 == 0 ? second : first;
      String changeId = changeId(i);

      Push created = creator.push(changeId);
      assertEquals(Result.NEW_CHANGE, created.result());
      assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), other.push(changeId));
      assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), creator.push(changeId));
    }

    assertEquals(20, first.changesInRepository());
    for (Node node : nodes) {
      node.await();
      assertEquals(20, node.index.size());
      assertTrue(node.index.all().stream().allMatch(change -> change.patchSets() == 3));
      assertTrue(node.barrier.count(Indexed.REPLAYED) >= 20);
      assertEquals(0, node.barrier.count(Indexed.TIMED_OUT));
      assertEquals(0, node.barrier.count(Indexed.FAILED));
      assertEquals(0, node.barrier.count(Indexed.UNAVAILABLE));
    }
  }

  @Test
  void withoutTheBarrierTheOtherNodeCreatesTheChangeAgain() throws Exception {
    Node first = node("first", "0");
    Node second = node("second", "0");
    startWithProject(first, second);

    Push created = first.push(changeId(0));
    Push again = second.push(changeId(0));

    assertEquals(Result.NEW_CHANGE, again.result());
    assertNotEquals(created.number(), again.number());
    assertEquals(0, answered(first) + answered(second));

    first.tailer.catchUp(first.id());
    second.tailer.catchUp(second.id());
    assertEquals(Result.HAS_DUPLICATES, first.push(changeId(0)).result());
    assertEquals(Result.HAS_DUPLICATES, second.push(changeId(0)).result());
  }

  @Test
  void aReplayThatOutlastsTheBoundRejectsThePush() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    startWithProject(first, second);
    Push created = first.push(changeId(0));
    long head = first.headSequence();

    CountDownLatch gate = new CountDownLatch(1);
    second.replayGate = gate;
    try {
      long started = System.nanoTime();
      Push rejected = second.push(changeId(0));
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertEquals(Result.REJECTED, rejected.result(), "no lookup ran on the index as it was");
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertTrue(elapsed >= 300 && elapsed < 3000, "the push waited " + elapsed + " ms");
      assertEquals(1, first.changesInRepository(), "the rejected push wrote nothing");
    } finally {
      gate.countDown();
    }

    // Replay continues after the push times out, and the repeated push finds the change.
    eventually(() -> second.cursorSequence() >= head, "the abandoned replay completes");
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
    assertEquals(1, second.index.byBranchKey(BRANCH, changeId(0)).size());
  }

  @Test
  void aStoreThatDoesNotAnswerOrFailsRejectsTheRequest() throws Exception {
    GatedStore store = new GatedStore(bucket());
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms", "1 hour", store, null);
    startWithProject(first, second);
    Push created = first.push(changeId(0));

    CountDownLatch gate = new CountDownLatch(1);
    store.manifestGate = gate;
    try {
      long started = System.nanoTime();
      Optional<String> notFresh = second.await();
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(notFresh.isPresent(), "the request is told the index may be stale");
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertTrue(elapsed >= 300 && elapsed < 3000, "the request waited " + elapsed + " ms");
    } finally {
      store.manifestGate = null;
      gate.countDown();
    }

    store.failManifestReads = true;
    assertEquals(Result.REJECTED, second.push(changeId(0)).result());
    assertEquals(1, second.barrier.count(Indexed.FAILED));

    store.failManifestReads = false;
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
    assertEquals(1, first.changesInRepository());
  }

  @Test
  void noRequestProceedsWhileEveryBarrierThreadIsBusy() throws Exception {
    GatedStore store = new GatedStore(bucket());
    Node first = node("first", "5 s");
    Node second = node("second", "500 ms", "1 hour", store, null);
    startWithProject(first, second);
    Push created = first.push(changeId(0));

    // More requests than the barrier has threads to read the store with, and none of the reads
    // returns: the requests beyond the threads wait their turn instead of going ahead.
    int requests = 40;
    CountDownLatch gate = new CountDownLatch(1);
    store.manifestGate = gate;
    List<Optional<String>> answers = new CopyOnWriteArrayList<>();
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Thread> threads = new ArrayList<>();
    try {
      for (int i = 0; i < requests; i++) {
        threads.add(thread(() -> answers.add(second.await()), failures));
      }
      threads.forEach(Thread::start);
      for (Thread thread : threads) {
        thread.join(TimeUnit.SECONDS.toMillis(10));
      }
    } finally {
      store.manifestGate = null;
      gate.countDown();
    }

    assertEquals(List.of(), failures);
    assertEquals(requests, answers.size());
    assertTrue(answers.stream().allMatch(Optional::isPresent), "every request was turned away");
    assertEquals(requests, second.barrier.count(Indexed.TIMED_OUT));

    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
  }

  @Test
  void backToBackPushesToOneNodeReplayNothing() throws Exception {
    CountingObjectStore store = new CountingObjectStore(bucket());
    Node node = node("only", "5 s", "1 hour", store, null);
    startWithProject(node);
    node.replayed.clear();
    store.reset();

    Push created = node.push(changeId(0));
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), node.push(changeId(0)));
    assertEquals(Result.NEW_CHANGE, node.push(changeId(1)).result());
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), node.push(changeId(0)));

    assertEquals(4, node.barrier.count(Indexed.NOT_BEHIND));
    assertEquals(4, answered(node));
    assertEquals(List.of(), node.replayed, "the node's own writes were not replayed for a push");
    assertEquals(0, store.count("GET log/*"));

    // The tailer also replays this node's entries.
    assertTrue(node.tailer.catchUp(node.id()) >= 4);
  }

  @Test
  void sweepsWakeUpsAndRequestsReplayOneRepositoryOnceAndInOrder() throws Exception {
    Node writer = node("writer", "5 s");
    Node reader = node("reader", "5 s", "5 ms", bucket(), null);
    startWithProject(writer, reader);
    reader.replayed.clear();

    AtomicBoolean writing = new AtomicBoolean(true);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Thread> threads = new ArrayList<>();
    RepositoryId id = reader.id();
    threads.add(
        new Thread(
            () -> {
              while (writing.get()) {
                reader.tailer.wake(id, null);
                Thread.onSpinWait();
              }
            }));
    for (int i = 0; i < 3; i++) {
      threads.add(
          new Thread(
              () -> {
                try {
                  while (writing.get()) {
                    reader.await();
                  }
                } catch (Throwable failure) {
                  failures.add(failure);
                }
              }));
    }
    threads.forEach(Thread::start);
    List<String> created = new ArrayList<>();
    try {
      for (int i = 0; i < 60; i++) {
        Push push = writer.push(changeId(i));
        created.add(RefNames.changeMetaRef(Change.id(push.number())));
      }
    } finally {
      writing.set(false);
      for (Thread thread : threads) {
        thread.join(TimeUnit.SECONDS.toMillis(30));
      }
    }
    reader.await();

    assertEquals(List.of(), failures);
    assertEquals(0, reader.overlaps.get(), "two threads replayed the repository at once");
    assertEquals(created, reader.replayed);
    assertEquals(60, reader.index.size());
    assertEquals(0, reader.barrier.count(Indexed.FAILED));
    assertEquals(0, reader.barrier.count(Indexed.TIMED_OUT));
    assertTrue(reader.tailer.wakeUpsReplayed() > 0, "wake-ups replayed");
  }

  @Test
  void aNodeThatIsGoneIsStillReplayed() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);

    Push created = first.push(changeId(0));
    first.close();

    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
  }

  @Test
  void aCursorTooFarBehindRejectsThePushAndIsLeftToTheSweep() throws Exception {
    AtomicInteger rebuilds = new AtomicInteger();
    Node first = node("first", "5 s");
    Node second = node("second", "5 s", "1 hour", bucket(), rebuilds::incrementAndGet);
    startWithProject(first, second);
    rebuilds.set(0);
    for (int i = 0; i < 8; i++) {
      first.push(changeId(i));
    }

    assertEquals(Result.REJECTED, second.push(changeId(0)).result());

    assertEquals(1, second.barrier.count(Indexed.UNAVAILABLE));
    assertEquals(1, answered(second));
    assertEquals(0, rebuilds.get(), "only the sweep rebuilds");

    // The sweep rebuilds, and the repeated push finds the change.
    second.tailer.runOnce();
    assertEquals(1, rebuilds.get());
    second.reindexFromRepository();
    assertEquals(Result.NEW_PATCH_SET, second.push(changeId(0)).result());
  }

  @Test
  void replaysOfDifferentRepositoriesDoNotRunSideBySide() throws Exception {
    Project.NameKey other = OTHER;
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    first.repositories.createRepository(other).close();
    startWithProject(first, second);
    first.push(changeId(0));
    long created = first.headSequence();
    try (Repository repository = first.repositories.openRepository(other)) {
      RefUpdate update = repository.updateRef(BRANCH);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commit(repository, null, "Start\n"));
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    CountDownLatch gate = new CountDownLatch(1);
    second.gated = other;
    second.replayGate = gate;
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread sweep = thread(() -> second.tailer.catchUp(second.repositories.idOf(other)), failures);
    sweep.start();
    try {
      eventually(() -> second.applying.get() == 1, "the other repository is being replayed");
      second.await();
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertTrue(second.cursorSequence() < created);
    } finally {
      gate.countDown();
      sweep.join(TimeUnit.SECONDS.toMillis(10));
    }

    eventually(() -> second.cursorSequence() >= created, "the waiting replay completes");
    assertEquals(List.of(), failures);
    assertEquals(0, second.overlaps.get(), "two threads replayed at once");
  }

  @Test
  void aReplayThatDoesNotEndFailsTheSweepUntilItEnds() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    startWithProject(first, second);
    first.push(changeId(0));
    long created = first.headSequence();

    CountDownLatch gate = new CountDownLatch(1);
    second.replayGate = gate;
    second.tailer.replayLockWait(Duration.ofMillis(200));
    try {
      second.await();
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));

      long started = System.nanoTime();
      assertThrows(IOException.class, second.tailer::runOnce);
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertTrue(elapsed < 5000, "the sweep waited " + elapsed + " ms");
      assertTrue(second.cursorSequence() < created);
    } finally {
      gate.countDown();
    }

    eventually(() -> second.cursorSequence() >= created, "the held replay completes");
    second.tailer.runOnce();
  }

  @Test
  void noReplayRunsWhileTheIndexesAreRebuilt() throws Exception {
    AtomicBoolean hold = new AtomicBoolean();
    CountDownLatch rebuilding = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    Node first = node("first", "5 s");
    Node second =
        node(
            "second",
            "5 s",
            "1 hour",
            bucket(),
            () -> {
              if (!hold.get()) {
                return;
              }
              rebuilding.countDown();
              try {
                if (!finish.await(30, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("the test did not release the rebuild");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
            });
    startWithProject(first, second);
    for (int i = 0; i < 8; i++) {
      first.push(changeId(i));
    }

    hold.set(true);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread sweep = thread(second.tailer::runOnce, failures);
    Thread replay = thread(() -> second.tailer.catchUp(second.id()), failures);
    sweep.start();
    try {
      assertTrue(rebuilding.await(10, TimeUnit.SECONDS));
      replay.start();
      eventually(() -> replay.getState() == Thread.State.TIMED_WAITING, "the replay waits");
      assertTrue(second.await().isPresent(), "an index being rebuilt is not one to decide on");
      assertTrue(second.awaitAll().isPresent());
      assertEquals(2, second.barrier.count(Indexed.UNAVAILABLE));
      assertEquals(2, answered(second));
    } finally {
      finish.countDown();
      sweep.join(TimeUnit.SECONDS.toMillis(10));
      replay.join(TimeUnit.SECONDS.toMillis(10));
    }

    assertEquals(List.of(), failures);
    Push created = first.push(changeId(100));
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(100)));
  }

  @Test
  void aFailedRebuildRejectsRequestsUntilARebuildCompletes() throws Exception {
    Node first = node("first", "5 s");
    Node[] rebuilt = new Node[1];
    AtomicBoolean fail = new AtomicBoolean();
    Node second =
        node(
            "second",
            "5 s",
            "1 hour",
            bucket(),
            () -> {
              // A rebuild deletes every document before it indexes the first one again.
              rebuilt[0].index.changes.clear();
              if (fail.get()) {
                throw new IOException("the rebuild failed after deleting the documents");
              }
              rebuilt[0].reindexFromRepository();
            });
    rebuilt[0] = second;
    first.repositories.createRepository(PROJECT).close();
    first.repositories.createRepository(OTHER).close();
    first.tailer.start();
    second.tailer.start();
    Push original = first.push(changeId(0));
    second.tailer.catchUp(second.id());
    assertEquals(1, second.index.byProject(PROJECT).size());

    // More entries in another repository than a node replays: only a rebuild gets past them.
    for (int i = 1; i <= 8; i++) {
      first.push(OTHER, changeId(i), null);
    }
    fail.set(true);
    assertThrows(IOException.class, second.tailer::runOnce);
    assertEquals(0, second.index.size());

    // The cursor of the project still says the index holds the change. It does not.
    assertEquals(new Push(Result.REJECTED, 0), second.push(changeId(0)));
    assertEquals(Optional.empty(), second.topic("any"));
    assertThrows(IOException.class, second.tailer::runOnce);
    assertEquals(new Push(Result.REJECTED, 0), second.push(changeId(0)));
    assertEquals(1, first.changesInRepository(), "no second change for the Change-Id");

    fail.set(false);
    second.tailer.runOnce();

    assertEquals(new Push(Result.NEW_PATCH_SET, original.number()), second.push(changeId(0)));
    assertTrue(second.topic("any").isPresent());
    assertEquals(1, first.changesInRepository());
  }

  @Test
  void aRebuildDuringAWaitRejectsTheRequestThatWaited() throws Exception {
    GatedStore store = new GatedStore(bucket());
    Node first = node("first", "5 s");
    Node[] rebuilt = new Node[1];
    Node second = node("second", "20 s", "1 hour", store, () -> rebuilt[0].reindexFromRepository());
    rebuilt[0] = second;
    first.repositories.createRepository(PROJECT).close();
    first.repositories.createRepository(OTHER).close();
    first.tailer.start();
    second.tailer.start();
    Push original = first.push(changeId(0));
    second.tailer.catchUp(second.id());
    for (int i = 1; i <= 8; i++) {
      first.push(OTHER, changeId(i), null);
    }

    // The push is admitted while no rebuild runs, and is still reading the store when one begins
    // and completes. What it then finds out about the cursor describes another index.
    CountDownLatch gate = new CountDownLatch(1);
    store.gatedThreads = "WalGerrit-Index-Barrier-Read-";
    store.manifestGate = gate;
    List<Push> pushed = new CopyOnWriteArrayList<>();
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread push = thread(() -> pushed.add(second.push(changeId(0))), failures);
    push.start();
    try {
      eventually(() -> store.waitingReads.get() == 1, "the push reads the store");
      second.tailer.runOnce();
    } finally {
      store.manifestGate = null;
      gate.countDown();
      push.join(TimeUnit.SECONDS.toMillis(10));
    }

    assertEquals(List.of(), failures);
    assertEquals(List.of(new Push(Result.REJECTED, 0)), pushed);
    assertEquals(1, second.barrier.count(Indexed.UNAVAILABLE));
    assertEquals(new Push(Result.NEW_PATCH_SET, original.number()), second.push(changeId(0)));
  }

  @Test
  void aPushWaitsForTheCatalogItReadToBeReplayed() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    startWithProject(first, second);
    Push created = first.push(changeId(0));
    first.repositories.createRepository(OTHER).close();
    // Opening a repository shows the node the catalog as it is now.
    second.repositories.openRepository(PROJECT).close();

    CountDownLatch gate = new CountDownLatch(1);
    second.catalogGate = gate;
    try {
      assertEquals(Result.REJECTED, second.push(changeId(0)).result());
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertEquals(1, answered(second));
      assertTrue(second.catalogReplaying.await(10, TimeUnit.SECONDS), "the catalog is replayed");
    } finally {
      gate.countDown();
    }

    // The catalog replay the first push started completes; the repeated push replays the rest.
    second.catalogGate = null;
    eventually(
        () -> second.barrier.apply(Optional.of(PROJECT.get())).isEmpty(),
        "the catalog is replayed");
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
    assertEquals(1, first.changesInRepository());
  }

  @Test
  void aNodesOwnCatalogChangeIsReplayedBeforeItsPushLooksUp() throws Exception {
    Node node = node("only", "5 s");
    startWithProject(node);
    node.repositories.createRepository(OTHER).close();

    assertTrue(node.await().isEmpty());
    assertEquals(1, node.barrier.count(Indexed.REPLAYED));
    assertEquals(1, answered(node));

    assertTrue(node.await().isEmpty());
    assertEquals(1, node.barrier.count(Indexed.NOT_BEHIND));
  }

  @Test
  void aPushThatACatalogReplayOutlastsIsRejected() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    startWithProject(first, second);
    Push created = first.push(changeId(0));
    first.repositories.createRepository(OTHER).close();

    CountDownLatch gate = new CountDownLatch(1);
    second.catalogGate = gate;
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread catalog = thread(() -> second.tailer.catchUp(RepositoryId.CATALOG), failures);
    catalog.start();
    try {
      assertTrue(second.catalogReplaying.await(10, TimeUnit.SECONDS));
      long started = System.nanoTime();
      Push rejected = second.push(changeId(0));
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertEquals(Result.REJECTED, rejected.result(), "no lookup ran on the index as it was");
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertTrue(elapsed < 3000, "the push waited " + elapsed + " ms");
    } finally {
      gate.countDown();
      catalog.join(TimeUnit.SECONDS.toMillis(10));
    }
    assertEquals(List.of(), failures);

    second.catalogGate = null;
    eventually(() -> second.await().isEmpty(), "the repository is replayed after the catalog");
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
  }

  @Test
  void aNameWithoutARepositoryHasNothingToWaitFor() throws Exception {
    Node node = node("only", "5 s");
    startWithProject(node);

    assertTrue(node.barrier.apply(Optional.of("no/such")).isEmpty());
    assertEquals(1, node.barrier.count(Indexed.NOT_BEHIND));
    assertEquals(1, answered(node));
  }

  @Test
  void aRepositoryWhoseNameIsInFlightRejectsThePush() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);
    first.push(changeId(0));
    NamespaceTest.prepareDelete(first.repositories, "op-delete", PROJECT);

    // Whether or not the node has seen the catalog change, the entries cannot be indexed under a
    // name in flight, and the push is not let through.
    assertTrue(second.await().isPresent(), outcomes(second));
    second.repositories.catalog().snapshot(true);
    assertTrue(second.await().isPresent(), outcomes(second));
    assertEquals(
        0, second.barrier.count(Indexed.NOT_BEHIND) + second.barrier.count(Indexed.REPLAYED));
    assertTrue(second.barrier.count(Indexed.UNAVAILABLE) >= 1, outcomes(second));

    // Once the name is retired it has no repository, and nothing of it to wait for or to index.
    first.repositories.namespace().resume("op-delete");
    assertTrue(second.await().isEmpty(), outcomes(second));
    assertTrue(second.awaitAll().isEmpty(), outcomes(second));
    assertTrue(second.index.byProject(PROJECT).isEmpty());
  }

  @Test
  void aNodeThatDoesNotTailHasNothingToWaitForAndAStoppedOneRejects() throws Exception {
    Node batch = node("batch", "5 s", "1 hour", bucket(), null, GerritRuntime.BATCH);
    Node daemon = node("daemon", "5 s");
    startWithProject(daemon, batch);

    assertTrue(batch.await().isEmpty());
    assertTrue(batch.awaitAll().isEmpty());
    assertEquals(2, batch.barrier.count(Indexed.DISABLED));

    daemon.tailer.stop();
    assertTrue(daemon.await().isPresent(), "a stopped tailer no longer follows the log");
    assertTrue(daemon.awaitAll().isPresent());
    assertEquals(2, daemon.barrier.count(Indexed.UNAVAILABLE));
  }

  @Test
  void aNodeRefusesToStartWithoutTheGerritHookUnlessTheBarrierIsOff() throws Exception {
    // These tests run against the upstream plugin API, which has no ChangeIndexFreshness.
    Node tailing = node("tailing", "5 s");
    Node off = node("off", "0");
    Node batch = node("batch", "5 s", "1 hour", bucket(), null, GerritRuntime.BATCH);

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, tailing.barrier::start);
    assertTrue(refused.getMessage().contains("indexBarrierTimeout = 0"), refused.getMessage());
    assertDoesNotThrow(off.barrier::start);
    assertDoesNotThrow(batch.barrier::start);
  }

  @Test
  void aTopicLookupFindsAMemberAnotherNodeJustPushedToAnotherRepository() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    first.repositories.createRepository(OTHER).close();
    startWithProject(first, second);
    Push approved = second.push(PROJECT, changeId(0), "feature");

    // The member lands on the first node, in a repository the second node has no reason to look
    // at: its wake-up is lost, its sweep is an hour away, and nothing it holds names it.
    Push unapproved = first.push(OTHER, changeId(1), "feature");

    // What a barrier for the project of the change being submitted can establish.
    assertTrue(second.await().isEmpty());
    assertEquals(List.of(approved.number()), numbers(second.index.byTopic("feature")));

    assertEquals(
        List.of(approved.number(), unapproved.number()), numbers(second.topic("feature").get()));
    assertEquals(1, second.barrier.count(Indexed.REPLAYED));
    assertEquals(0, second.barrier.count(Indexed.UNAVAILABLE));

    // Nothing is behind now.
    second.replayed.clear();
    assertEquals(2, second.topic("feature").get().size());
    assertEquals(List.of(), second.replayed);
  }

  @Test
  void aTopicLookupIsRefusedWhileAnotherRepositoryCannotBeReplayed() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    first.repositories.createRepository(OTHER).close();
    startWithProject(first, second);
    second.push(PROJECT, changeId(0), "feature");
    first.push(OTHER, changeId(1), "feature");

    CountDownLatch gate = new CountDownLatch(1);
    second.gated = OTHER;
    second.replayGate = gate;
    try {
      assertTrue(second.topic("feature").isEmpty(), "no lookup ran on the index as it was");
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
    } finally {
      gate.countDown();
    }

    second.replayGate = null;
    eventually(() -> second.topic("feature").isPresent(), "the pass completes");
    assertEquals(2, second.topic("feature").get().size());
  }

  @Test
  void aTopicLookupFailsWhenTheStoreCannotBeListed() throws Exception {
    GatedStore store = new GatedStore(bucket());
    Node node = node("only", "5 s", "1 hour", store, null);
    startWithProject(node);

    store.failListings = true;
    assertTrue(node.topic("feature").isEmpty());
    assertEquals(1, node.barrier.count(Indexed.FAILED));

    store.failListings = false;
    assertTrue(node.topic("feature").isPresent());
  }

  @Test
  void aTopicLookupDoesNotReplayTheNodesOwnWrites() throws Exception {
    CountingObjectStore store = new CountingObjectStore(bucket());
    Node node = node("only", "5 s", "1 hour", store, null);
    node.repositories.createRepository(OTHER).close();
    startWithProject(node);
    // The node's own catalog entry is replayed before anything is looked up.
    assertTrue(node.awaitAll().isEmpty());
    node.push(PROJECT, changeId(0), "feature");
    node.push(OTHER, changeId(1), "feature");
    node.replayed.clear();
    store.reset();
    long notBehind = node.barrier.count(Indexed.NOT_BEHIND);

    assertEquals(2, node.topic("feature").get().size());

    assertEquals(notBehind + 1, node.barrier.count(Indexed.NOT_BEHIND));
    assertEquals(List.of(), node.replayed, "the node's own writes were not replayed for a lookup");
    assertEquals(1, store.count("LIST-versions manifests/"), "one listing is the boundary");
    assertEquals(0, store.count("GET log/*"));
    assertEquals(0, store.repositoryManifestReads(), "the node holds the manifests it published");
  }

  @Test
  void topicLookupsThatArriveDuringAPassShareTheNextOne() throws Exception {
    CountingObjectStore store = new CountingObjectStore(bucket());
    Node first = node("first", "5 s");
    Node second = node("second", "5 s", "1 hour", store, null);
    first.repositories.createRepository(OTHER).close();
    startWithProject(first, second);
    assertTrue(second.awaitAll().isEmpty());
    first.push(OTHER, changeId(0), "feature");
    store.reset();

    // The first lookup's pass has listed the store and is held replaying the other repository.
    CountDownLatch gate = new CountDownLatch(1);
    second.gated = OTHER;
    second.replayGate = gate;
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Optional<List<IndexedChange>>> runningFound = new CopyOnWriteArrayList<>();
    List<Optional<List<IndexedChange>>> found = new CopyOnWriteArrayList<>();
    Thread running = thread(() -> runningFound.add(second.topic("feature")), failures);
    running.start();
    List<Thread> waiting = new ArrayList<>();
    Push late;
    try {
      eventually(() -> second.applying.get() == 1, "the first pass is replaying");
      // Published after that pass listed the store, before the next lookups arrive.
      late = first.push(PROJECT, changeId(1), "feature");
      for (int i = 0; i < 5; i++) {
        waiting.add(thread(() -> found.add(second.topic("feature")), failures));
      }
      waiting.forEach(Thread::start);
      eventually(
          () -> waiting.stream().allMatch(t -> t.getState() == Thread.State.TIMED_WAITING),
          "the later lookups wait");
    } finally {
      gate.countDown();
    }
    running.join(TimeUnit.SECONDS.toMillis(10));
    for (Thread thread : waiting) {
      thread.join(TimeUnit.SECONDS.toMillis(10));
    }

    assertEquals(List.of(), failures);
    assertEquals(1, runningFound.size());
    assertTrue(runningFound.get(0).isPresent());
    assertEquals(5, found.size());
    assertTrue(found.stream().allMatch(Optional::isPresent));
    // Later lookups must see the publication; the first may see it too.
    assertEquals(
        5,
        found.stream().filter(changes -> numbers(changes.get()).contains(late.number())).count());
    assertEquals(2, store.count("LIST-versions manifests/"), "six lookups, two passes");
    assertEquals(0, second.overlaps.get(), "two threads replayed at once");
  }

  @Test
  void aRepositoryWhoseReplayNeedsAnothersIsTriedOnceMore() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    first.repositories.createRepository(OTHER).close();
    startWithProject(first, second);
    assertTrue(second.awaitAll().isEmpty());
    first.push(PROJECT, changeId(0), "feature");
    first.push(OTHER, changeId(1), "feature");

    // As with a draft comment in All-Users on a change whose project is replayed later in the
    // pass: the repository listed first cannot be replayed until the other one has been.
    RepositoryId projectId = second.repositories.idOf(PROJECT);
    RepositoryId otherId = second.repositories.idOf(OTHER);
    Project.NameKey listedFirst = projectId.compareTo(otherId) < 0 ? PROJECT : OTHER;
    Project.NameKey listedLast = listedFirst.equals(PROJECT) ? OTHER : PROJECT;
    AtomicInteger refusals = new AtomicInteger();
    second.beforeApply =
        (project, node) -> {
          if (project.equals(listedFirst) && node.index.byProject(listedLast).isEmpty()) {
            refusals.incrementAndGet();
            throw new IllegalStateException(listedFirst + " needs " + listedLast + " indexed");
          }
        };

    assertEquals(2, second.topic("feature").get().size());
    assertEquals(1, refusals.get());
    assertEquals(1, second.barrier.count(Indexed.REPLAYED));
  }

  @Test
  void aNameInFlightBlocksTopicLookupsOnlyWhileItsRepositoryHoldsUnindexedChanges()
      throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);
    assertTrue(second.awaitAll().isEmpty());

    // A member another node pushed just before a deletion began cannot be indexed under a name
    // in flight.
    first.push(PROJECT, changeId(0), "feature");
    NamespaceTest.prepareDelete(first.repositories, "op-delete", PROJECT);
    assertTrue(second.topic("feature").isEmpty());
    assertEquals(1, second.barrier.count(Indexed.UNAVAILABLE));

    first.repositories.namespace().resume("op-delete");
    assertTrue(second.topic("feature").get().isEmpty(), "a retired name has nothing to index");

    // A name in flight whose repository has nothing left to index holds no one up.
    first.repositories.createRepository(OTHER).close();
    first.push(OTHER, changeId(1), "feature");
    assertEquals(1, second.topic("feature").get().size());
    NamespaceTest.prepareDelete(first.repositories, "op-again", OTHER);
    assertEquals(1, second.topic("feature").get().size());
    assertEquals(1, second.barrier.count(Indexed.UNAVAILABLE));
  }

  @Test
  void aReplayCannotWaitForTheBarrier() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);
    first.push(changeId(0));

    // Something a replay triggers asks for an up-to-date index, on the thread that is replaying.
    List<Optional<String>> answers = new CopyOnWriteArrayList<>();
    second.beforeApply =
        (project, node) -> {
          answers.add(node.barrier.apply(Optional.empty()));
          answers.add(node.barrier.apply(Optional.of(PROJECT.get())));
        };
    long started = System.nanoTime();
    second.tailer.catchUp(second.id());
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertEquals(2, answers.size());
    assertTrue(answers.stream().allMatch(Optional::isPresent), "it is refused, not left waiting");
    assertTrue(elapsed < 3000, "the replay took " + elapsed + " ms");
    assertEquals(2, second.barrier.count(Indexed.UNAVAILABLE));
  }

  private enum Result {
    NEW_CHANGE,
    NEW_PATCH_SET,
    HAS_DUPLICATES,
    /** The barrier could not bring the index up to date; nothing was looked up or written. */
    REJECTED
  }

  private record Push(Result result, int number) {}

  /**
   * What the index holds of a change: enough to answer a lookup by branch and Change-Id, and by
   * topic.
   */
  private record IndexedChange(
      int number,
      Project.NameKey project,
      String branch,
      String changeId,
      String topic,
      int patchSets) {}

  private static List<Integer> numbers(List<IndexedChange> changes) {
    return changes.stream().map(IndexedChange::number).toList();
  }

  private static final class ChangeIndex {
    private final Map<Integer, IndexedChange> changes = new ConcurrentHashMap<>();

    List<IndexedChange> byBranchKey(String branch, String changeId) {
      return byBranchKey(PROJECT, branch, changeId);
    }

    List<IndexedChange> byBranchKey(Project.NameKey project, String branch, String changeId) {
      return sorted(
          change ->
              change.project().equals(project)
                  && change.branch().equals(branch)
                  && change.changeId().equals(changeId));
    }

    List<IndexedChange> byTopic(String topic) {
      return sorted(change -> topic.equals(change.topic()));
    }

    List<IndexedChange> byProject(Project.NameKey project) {
      return sorted(change -> change.project().equals(project));
    }

    private List<IndexedChange> sorted(java.util.function.Predicate<IndexedChange> filter) {
      return changes.values().stream()
          .filter(filter)
          .sorted(Comparator.comparingInt(IndexedChange::number))
          .toList();
    }

    List<IndexedChange> all() {
      return List.copyOf(changes.values());
    }

    int size() {
      return changes.size();
    }
  }

  private final class Node implements IndexEventApplier {
    final WalGitRepositoryManager repositories;
    final ChangeIndex index = new ChangeIndex();
    final IndexEventTailer tailer;
    final IndexBarrier barrier;

    /** Meta refs of the changes this node's tailer indexed from the log, in order. */
    final List<String> replayed = new CopyOnWriteArrayList<>();

    final AtomicInteger overlaps = new AtomicInteger();
    private final AtomicInteger applying = new AtomicInteger();

    /** While set, applying a log entry of {@link #gated} waits for it. */
    volatile CountDownLatch replayGate;

    volatile Project.NameKey gated = PROJECT;

    /** While set, reconciling the index with the catalog waits for it. */
    volatile CountDownLatch catalogGate;

    /** Runs before a log entry of a project is applied; it may refuse by throwing. */
    volatile BiConsumer<Project.NameKey, Node> beforeApply;

    final CountDownLatch catalogReplaying = new CountDownLatch(1);

    private boolean closed;

    Node(
        String name,
        String barrierTimeout,
        String pollInterval,
        ObjectStore store,
        IndexRebuilder rebuilder,
        GerritRuntime runtime) {
      Config config = new Config();
      config.setString("walgerrit", null, "storagePath", root.resolve(name + "-cache").toString());
      config.setString(
          "walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
      config.setString("walgerrit", null, "indexPollInterval", pollInterval);
      config.setString("walgerrit", null, "indexBarrierTimeout", barrierTimeout);
      if (rebuilder != null) {
        config.setString("walgerrit", null, "indexReplayLimit", "3");
      }
      WalGitConfiguration configuration = WalGitConfiguration.from(config, root);
      repositories =
          new WalGitRepositoryManager(
              configuration,
              new StorageLayout(
                  store, configuration.storagePath(), configuration.indexCursorPath(), ""));
      tailer =
          new IndexEventTailer(
              repositories,
              this,
              runtime,
              "lucene",
              durableLuceneConfig(),
              new IndexEventReadiness(root.resolve(name + "-ready").resolve("READY")),
              rebuilder);
      barrier = new IndexBarrier(tailer, tailer.indexBarrierTimeout(), new DisabledMetricMaker());
    }

    /** A push of one commit for review on {@link #BRANCH} of {@link #PROJECT}. */
    Push push(String changeId) throws Exception {
      return push(PROJECT, changeId, null);
    }

    /** A push of one commit for review on {@link #BRANCH} of {@code project}. */
    Push push(Project.NameKey project, String changeId, String topic) throws Exception {
      if (barrier.apply(Optional.of(project.get())).isPresent()) {
        return new Push(Result.REJECTED, 0);
      }
      List<IndexedChange> found = index.byBranchKey(project, BRANCH, changeId);
      if (found.size() > 1) {
        return new Push(Result.HAS_DUPLICATES, 0);
      }
      int number;
      Result result;
      try (Repository repository = repositories.openRepository(project)) {
        if (found.isEmpty()) {
          number = changeNumbers.incrementAndGet();
          ObjectId created =
              commit(
                  repository,
                  null,
                  "Create change\n\nChange-id: "
                      + changeId
                      + "\nBranch: "
                      + BRANCH
                      + (topic == null ? "" : "\nTopic: " + topic)
                      + "\n");
          RefUpdate update = repository.updateRef(RefNames.changeMetaRef(Change.id(number)));
          update.setExpectedOldObjectId(ObjectId.zeroId());
          update.setNewObjectId(created);
          assertEquals(RefUpdate.Result.NEW, update.update());
          result = Result.NEW_CHANGE;
        } else {
          number = found.get(0).number();
          String meta = RefNames.changeMetaRef(Change.id(number));
          ObjectId old = repository.exactRef(meta).getObjectId();
          RefUpdate update = repository.updateRef(meta);
          update.setExpectedOldObjectId(old);
          update.setNewObjectId(commit(repository, old, "Create patch set\n"));
          assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
          result = Result.NEW_PATCH_SET;
        }
      }
      // Gerrit indexes a request's own write before it answers.
      index(project, number);
      return new Push(result, number);
    }

    /**
     * The open changes of {@code topic} as a submission looks them up: in an index brought up to
     * date with every repository, or not at all.
     */
    Optional<List<IndexedChange>> topic(String topic) {
      if (awaitAll().isPresent()) {
        return Optional.empty();
      }
      return Optional.of(index.byTopic(topic));
    }

    /** Runs the barrier for a push to {@link #PROJECT}; the reason when the index may be stale. */
    Optional<String> await() {
      return barrier.apply(Optional.of(PROJECT.get()));
    }

    /** Runs the barrier for every repository; the reason when the index may be stale. */
    Optional<String> awaitAll() {
      return barrier.apply(Optional.empty());
    }

    /** Refills the index from the repositories, as a rebuild does. */
    void reindexFromRepository() throws IOException {
      index.changes.clear();
      try (Repository repository = repositories.openRepository(PROJECT)) {
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES)) {
          if (ref.getName().endsWith(RefNames.META_SUFFIX)) {
            index(PROJECT, Change.Id.fromRef(ref.getName()).get());
          }
        }
      }
    }

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      if (applying.incrementAndGet() > 1) {
        overlaps.incrementAndGet();
      }
      try {
        BiConsumer<Project.NameKey, Node> before = beforeApply;
        if (before != null) {
          before.accept(project, this);
        }
        CountDownLatch gate = project.equals(gated) ? replayGate : null;
        if (gate != null && !gate.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the test did not release the replay");
        }
        for (var update : transaction.getUpdatesList()) {
          Change.Id change = Change.Id.fromRef(update.getName());
          if (change != null && update.getName().endsWith(RefNames.META_SUFFIX)) {
            index(project, change.get());
            if (ObjectId.zeroId().name().equals(update.getOldObjectId())) {
              replayed.add(update.getName());
            }
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      } finally {
        applying.decrementAndGet();
      }
    }

    @Override
    public void namespaceChanged(List<NamespaceChange> changes) {
      CountDownLatch gate = catalogGate;
      if (gate == null) {
        return;
      }
      catalogReplaying.countDown();
      try {
        if (!gate.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the test did not release the catalog replay");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }

    /** Indexes a change of {@code project} as its repository holds it now. */
    private void index(Project.NameKey project, int number) throws IOException {
      try (Repository repository = repositories.openRepository(project);
          RevWalk walk = new RevWalk(repository)) {
        Ref meta = repository.exactRef(RefNames.changeMetaRef(Change.id(number)));
        if (meta == null) {
          index.changes.remove(number);
          return;
        }
        walk.markStart(walk.parseCommit(meta.getObjectId()));
        int patchSets = 0;
        RevCommit first = null;
        for (RevCommit commit : walk) {
          patchSets++;
          first = commit;
        }
        List<String> topic = first.getFooterLines("Topic");
        index.changes.put(
            number,
            new IndexedChange(
                number,
                project,
                first.getFooterLines("Branch").get(0),
                first.getFooterLines("Change-id").get(0),
                topic.isEmpty() ? null : topic.get(0),
                patchSets));
      }
    }

    int changesInRepository() throws IOException {
      try (Repository repository = repositories.openRepository(PROJECT)) {
        return (int)
            repository.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES).stream()
                .filter(ref -> ref.getName().endsWith(RefNames.META_SUFFIX))
                .count();
      }
    }

    RepositoryId id() throws IOException {
      return repositories.idOf(PROJECT);
    }

    long headSequence() throws IOException {
      return repositories.manifestStore(PROJECT).refresh().getHeadSeq();
    }

    long cursorSequence() {
      try {
        return new IndexCursorStore(repositories.manifestStore(PROJECT).indexCursorPath())
            .read()
            .getSequence();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    void close() {
      if (!closed) {
        closed = true;
        tailer.stop();
        repositories.stop();
      }
    }
  }

  /** A store whose reads of repository manifests can be held back or made to fail. */
  private static final class GatedStore implements ObjectStore {
    private final ObjectStore delegate;
    volatile CountDownLatch manifestGate;
    volatile boolean failManifestReads;
    volatile boolean failListings;

    /** While set, only threads whose name starts with it wait for {@link #manifestGate}. */
    volatile String gatedThreads;

    final AtomicInteger waitingReads = new AtomicInteger();

    GatedStore(ObjectStore delegate) {
      this.delegate = delegate;
    }

    private void beforeRead(String key) throws IOException {
      if (!key.endsWith("/" + ManifestStore.MANIFEST_FILE)
          || key.contains("/" + RepositoryId.CATALOG.value() + "/")) {
        return;
      }
      if (failManifestReads) {
        throw new IOException("the store refused to read " + key);
      }
      CountDownLatch gate = manifestGate;
      String gated = gatedThreads;
      if (gate == null || (gated != null && !Thread.currentThread().getName().startsWith(gated))) {
        return;
      }
      waitingReads.incrementAndGet();
      try {
        if (!gate.await(30, TimeUnit.SECONDS)) {
          throw new IOException("the test did not release the read of " + key);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      beforeRead(key);
      return delegate.get(key);
    }

    @Override
    public ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
      beforeRead(key);
      return delegate.getIfChanged(key, knownVersion);
    }

    @Override
    public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
        throws IOException {
      return delegate.compareAndSwap(key, expectedVersion, bytes);
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
    public void delete(String key) throws IOException {
      delegate.delete(key);
    }

    @Override
    public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
      if (failListings) {
        throw new IOException("the store refused to list " + prefix);
      }
      return delegate.listWithVersions(prefix);
    }
  }

  private ObjectStore bucket() {
    if (bucket == null) {
      bucket = new FileObjectStore(root.resolve("bucket"));
    }
    return bucket;
  }

  /** A node with a one-hour sweep interval. */
  private Node node(String name, String barrierTimeout) {
    return node(name, barrierTimeout, "1 hour", bucket(), null);
  }

  private Node node(
      String name,
      String barrierTimeout,
      String pollInterval,
      ObjectStore store,
      IndexRebuilder rebuilder) {
    return node(name, barrierTimeout, pollInterval, store, rebuilder, GerritRuntime.DAEMON);
  }

  private Node node(
      String name,
      String barrierTimeout,
      String pollInterval,
      ObjectStore store,
      IndexRebuilder rebuilder,
      GerritRuntime runtime) {
    Node node = new Node(name, barrierTimeout, pollInterval, store, rebuilder, runtime);
    nodes.add(node);
    return node;
  }

  private static void startWithProject(Node... started) throws Exception {
    started[0].repositories.createRepository(PROJECT).close();
    for (Node node : started) {
      node.tailer.start();
    }
  }

  private static String outcomes(Node node) {
    StringBuilder counts = new StringBuilder();
    for (Indexed outcome : Indexed.values()) {
      counts.append(outcome).append('=').append(node.barrier.count(outcome)).append(' ');
    }
    return counts.toString().trim();
  }

  private static long answered(Node node) {
    long answered = 0;
    for (Indexed outcome : Indexed.values()) {
      answered += node.barrier.count(outcome);
    }
    return answered;
  }

  private static String changeId(int i) {
    return String.format("I%040x", i + 1);
  }

  private static ObjectId commit(Repository repository, ObjectId parent, String message)
      throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      PersonIdent author = new PersonIdent("WalGerrit Test", "walgerrit@example.test");
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      if (parent != null) {
        commit.setParentId(parent);
      }
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private interface Step {
    void run() throws Exception;
  }

  private static Thread thread(Step step, List<Throwable> failures) {
    return new Thread(
        () -> {
          try {
            step.run();
          } catch (Throwable failure) {
            failures.add(failure);
          }
        });
  }

  private static void eventually(BooleanSupplier condition, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, what);
      Thread.sleep(10);
    }
  }

  private static Config durableLuceneConfig() {
    Config config = new Config();
    for (String name :
        List.of("accounts", "changes_open", "changes_closed", "groups", "projects")) {
      config.setString("index", name, "commitWithin", "0");
    }
    return config;
  }
}
