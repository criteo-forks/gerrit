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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.RequestInfo;
import com.google.gerrit.server.RequestInfo.RequestType;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.logging.TraceContext;
import dev.walgerrit.IndexEventTailer.Indexed;
import dev.walgerrit.ManifestReadCountTest.CountingObjectStore;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.nio.file.Path;
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
 * Pushes for review against nodes that share one store and each keep their own change index.
 *
 * <p>A node's index holds the branch and Change-Id of each change, taken from its meta ref.
 * {@link Node#push} passes the barrier, looks the Change-Id up in the node's index, creates a
 * change or adds a patch set through a ref transaction, and indexes its own write.
 */
class IndexBarrierTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/app");
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
      assertEquals(0, node.barrier.count(Indexed.SKIPPED));
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
  void aReplayThatOutlastsTheBoundLetsThePushProceed() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms");
    startWithProject(first, second);
    first.push(changeId(0));
    long created = first.headSequence();

    CountDownLatch gate = new CountDownLatch(1);
    second.replayGate = gate;
    try {
      long started = System.nanoTime();
      Push proceeded = second.push(changeId(0));
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertEquals(Result.NEW_CHANGE, proceeded.result(), "the lookup ran on the index as it was");
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertTrue(elapsed >= 300 && elapsed < 3000, "the push waited " + elapsed + " ms");
    } finally {
      gate.countDown();
    }

    // Replay continues after the push times out.
    eventually(() -> second.cursorSequence() >= created, "the abandoned replay completes");
    assertEquals(2, second.index.byBranchKey(BRANCH, changeId(0)).size());
  }

  @Test
  void aStoreThatDoesNotAnswerOrFailsLetsTheRequestProceed() throws Exception {
    GatedStore store = new GatedStore(bucket());
    Node first = node("first", "5 s");
    Node second = node("second", "300 ms", "1 hour", store, null);
    startWithProject(first, second);
    first.push(changeId(0));

    CountDownLatch gate = new CountDownLatch(1);
    store.manifestGate = gate;
    try {
      long started = System.nanoTime();
      second.await();
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertEquals(1, second.barrier.count(Indexed.TIMED_OUT));
      assertTrue(elapsed >= 300 && elapsed < 3000, "the request waited " + elapsed + " ms");
    } finally {
      store.manifestGate = null;
      gate.countDown();
    }

    store.failManifestReads = true;
    second.await();
    assertEquals(1, second.barrier.count(Indexed.FAILED));

    store.failManifestReads = false;
    assertEquals(Result.NEW_PATCH_SET, second.push(changeId(0)).result());
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
  void aCursorTooFarBehindIsLeftToTheSweep() throws Exception {
    AtomicInteger rebuilds = new AtomicInteger();
    Node first = node("first", "5 s");
    Node second = node("second", "5 s", "1 hour", bucket(), rebuilds::incrementAndGet);
    startWithProject(first, second);
    rebuilds.set(0);
    for (int i = 0; i < 8; i++) {
      first.push(changeId(i));
    }

    second.await();

    assertEquals(1, second.barrier.count(Indexed.SKIPPED));
    assertEquals(1, answered(second));
    assertEquals(0, rebuilds.get(), "only the sweep rebuilds");
  }

  @Test
  void replaysOfDifferentRepositoriesDoNotRunSideBySide() throws Exception {
    Project.NameKey other = Project.nameKey("platform/other");
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
      second.await();
      assertEquals(1, second.barrier.count(Indexed.SKIPPED));
      assertEquals(1, answered(second));
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
  void aPushSkipsTheBarrierWhileTheCatalogItReadIsNotReplayed() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);
    Push created = first.push(changeId(0));
    first.repositories.createRepository(Project.nameKey("platform/other")).close();
    // Opening a repository shows the node the catalog as it is now.
    second.repositories.openRepository(PROJECT).close();

    CountDownLatch gate = new CountDownLatch(1);
    second.catalogGate = gate;
    try {
      second.await();
      assertEquals(1, second.barrier.count(Indexed.SKIPPED));
      assertEquals(1, answered(second));
      assertTrue(
          second.catalogReplaying.await(10, TimeUnit.SECONDS), "the tailer is asked to replay it");
    } finally {
      gate.countDown();
    }

    eventually(() -> second.tailer.wakeUpsReplayed() == 1, "the catalog is replayed");
    assertEquals(new Push(Result.NEW_PATCH_SET, created.number()), second.push(changeId(0)));
    assertEquals(1, second.barrier.count(Indexed.REPLAYED));
  }

  @Test
  void aNodesOwnCatalogChangeIsReplayedBeforeItsPushesWaitAgain() throws Exception {
    Node node = node("only", "5 s");
    startWithProject(node);
    node.repositories.createRepository(Project.nameKey("platform/other")).close();

    node.await();
    assertEquals(1, node.barrier.count(Indexed.SKIPPED));
    assertEquals(1, answered(node));

    eventually(() -> node.tailer.wakeUpsReplayed() == 1, "the catalog is replayed");
    node.await();
    assertEquals(1, node.barrier.count(Indexed.NOT_BEHIND));
  }

  @Test
  void aPushDoesNotWaitForACatalogReplay() throws Exception {
    Node first = node("first", "5 s");
    Node second = node("second", "5 s");
    startWithProject(first, second);
    first.push(changeId(0));
    first.repositories.createRepository(Project.nameKey("platform/other")).close();

    CountDownLatch gate = new CountDownLatch(1);
    second.catalogGate = gate;
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread catalog =
        new Thread(
            () -> {
              try {
                second.tailer.catchUp(RepositoryId.CATALOG);
              } catch (Throwable failure) {
                failures.add(failure);
              }
            });
    catalog.start();
    try {
      assertTrue(second.catalogReplaying.await(10, TimeUnit.SECONDS));
      long started = System.nanoTime();
      Push proceeded = second.push(changeId(0));
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

      assertEquals(Result.NEW_CHANGE, proceeded.result(), "the lookup ran on the index as it was");
      assertEquals(1, second.barrier.count(Indexed.SKIPPED));
      assertTrue(elapsed < 3000, "the push waited " + elapsed + " ms");
    } finally {
      gate.countDown();
      catalog.join(TimeUnit.SECONDS.toMillis(10));
    }
    assertEquals(List.of(), failures);
  }

  @Test
  void onlyRequestsThatLookUpChangeKeysWait() throws Exception {
    Node node = node("only", "5 s");
    startWithProject(node);

    node.rest("/changes/platform%2Fapp~1001/detail");
    node.rest("/projects/platform%2Fapp/branches/");
    node.request(RequestType.GIT_UPLOAD, Optional.of(PROJECT), null);
    node.request(RequestType.GIT_RECEIVE, Optional.empty(), null);
    assertEquals(0, answered(node));

    node.rest("/changes/platform%2Fapp~1001/move");
    node.rest("/changes/platform%2Fapp~1001/revisions/current/cherrypick");
    node.rest("/projects/platform%2Fapp/commits/abc/cherrypick/");
    node.rest("/projects/platform%2Fapp/create.change");
    node.await();
    assertEquals(5, node.barrier.count(Indexed.NOT_BEHIND));

    node.request(RequestType.GIT_RECEIVE, Optional.of(Project.nameKey("no/such")), null);
    assertEquals(1, node.barrier.count(Indexed.SKIPPED));
    assertEquals(6, answered(node));
  }

  private enum Result {
    NEW_CHANGE,
    NEW_PATCH_SET,
    HAS_DUPLICATES
  }

  private record Push(Result result, int number) {}

  /** What the index holds of a change: enough to answer a lookup by branch and Change-Id. */
  private record IndexedChange(int number, String branch, String changeId, int patchSets) {}

  private static final class ChangeIndex {
    private final Map<Integer, IndexedChange> changes = new ConcurrentHashMap<>();

    List<IndexedChange> byBranchKey(String branch, String changeId) {
      return changes.values().stream()
          .filter(change -> change.branch().equals(branch) && change.changeId().equals(changeId))
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

    final CountDownLatch catalogReplaying = new CountDownLatch(1);

    private boolean closed;

    Node(
        String name,
        String barrierTimeout,
        String pollInterval,
        ObjectStore store,
        IndexRebuilder rebuilder) {
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
              GerritRuntime.DAEMON,
              "lucene",
              durableLuceneConfig(),
              new IndexEventReadiness(root.resolve(name + "-ready").resolve("READY")),
              rebuilder);
      barrier = new IndexBarrier(tailer, tailer.indexBarrierTimeout(), new DisabledMetricMaker());
    }

    /** A push of one commit for review on {@link #BRANCH}. */
    Push push(String changeId) throws Exception {
      await();
      List<IndexedChange> found = index.byBranchKey(BRANCH, changeId);
      if (found.size() > 1) {
        return new Push(Result.HAS_DUPLICATES, 0);
      }
      int number;
      Result result;
      try (Repository repository = repositories.openRepository(PROJECT)) {
        if (found.isEmpty()) {
          number = changeNumbers.incrementAndGet();
          ObjectId created =
              commit(
                  repository,
                  null,
                  "Create change\n\nChange-id: " + changeId + "\nBranch: " + BRANCH + "\n");
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
      index(number);
      return new Push(result, number);
    }

    /** Runs the barrier for a push. */
    void await() {
      request(RequestType.GIT_RECEIVE, Optional.of(PROJECT), null);
    }

    void rest(String uri) {
      request(RequestType.REST, Optional.of(PROJECT), uri);
    }

    void request(RequestType type, Optional<Project.NameKey> project, String uri) {
      try (TraceContext trace = TraceContext.open()) {
        RequestInfo.Builder request = RequestInfo.builder(type, new AnonymousUser(), trace);
        project.ifPresent(request::project);
        if (uri != null) {
          request.requestUri(uri);
        }
        barrier.onRequest(request.build());
      }
    }

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      if (applying.incrementAndGet() > 1) {
        overlaps.incrementAndGet();
      }
      try {
        CountDownLatch gate = project.equals(gated) ? replayGate : null;
        if (gate != null && !gate.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("the test did not release the replay");
        }
        for (var update : transaction.getUpdatesList()) {
          Change.Id change = Change.Id.fromRef(update.getName());
          if (change != null && update.getName().endsWith(RefNames.META_SUFFIX)) {
            index(change.get());
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

    /** Indexes a change as the repository holds it now. */
    private void index(int number) throws IOException {
      try (Repository repository = repositories.openRepository(PROJECT);
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
        index.changes.put(
            number,
            new IndexedChange(
                number,
                first.getFooterLines("Branch").get(0),
                first.getFooterLines("Change-id").get(0),
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
      try {
        if (gate != null && !gate.await(30, TimeUnit.SECONDS)) {
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
    Node node = new Node(name, barrierTimeout, pollInterval, store, rebuilder);
    nodes.add(node);
    return node;
  }

  private static void startWithProject(Node... started) throws Exception {
    started[0].repositories.createRepository(PROJECT).close();
    for (Node node : started) {
      node.tailer.start();
    }
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
