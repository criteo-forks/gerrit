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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.OwedMerge;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A submit commits and then fires its change-merged events; a process that ends in between leaves
 * merges nobody was told about. The node's next process tells.
 *
 * <p>Every entry these tests write carries this JVM's process id, as the entries of one Gerrit
 * process do. A tailer with that id is that process, still running; a tailer with another id is a
 * later process of the same node: same host name, same process id number, same volume.
 */
class OwedMergeEventsTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/merged");
  private static final Change.Id CHANGE = Change.id(2061502);

  @TempDir Path root;

  private final Merges merges = new Merges();

  @Test
  void aMergeOfAProcessThatEndedBeforeItsTailerSawTheCommitIsAnnouncedByTheNextProcess()
      throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);

    // Committed; the process ends before it fires the event and before its tailer comes by.
    ObjectId meta = merge(node, PROJECT, CHANGE);

    IndexEventTailer next = laterProcess(node, "second");
    next.catchUp(PROJECT);
    assertTrue(merges.fired.isEmpty(), "replay itself fires nothing");
    next.fireOwed(node.idOf(PROJECT));

    assertEquals(1, merges.fired.size());
    OwedMerge fired = merges.fired.get(0);
    assertEquals(CHANGE.get(), fired.getChange());
    assertEquals(meta.name(), fired.getMetaRevision(), "the event describes what was committed");
    assertEquals(node.manifestStore(PROJECT).read().getHeadSeq(), fired.getSequence());

    // Fired, and now this process's to see into the log: it does not fire it a second time.
    next.fireOwed(node.idOf(PROJECT));
    assertEquals(1, merges.fired.size());
    assertEquals(1, owed(node, PROJECT).size());

    // The journal's entry settles it, for this process and the ones after it.
    journal(node, PROJECT, CHANGE);
    next.catchUp(PROJECT);
    assertTrue(owed(node, PROJECT).isEmpty());
    IndexEventTailer third = laterProcess(node, "third");
    third.catchUp(PROJECT);
    third.fireOwed(node.idOf(PROJECT));
    assertEquals(1, merges.fired.size());
  }

  @Test
  void aMergeRecordedByTheTailerOfAProcessThatEndedIsAnnouncedThoughNothingWasPublishedSince()
      throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    IndexEventTailer first = tailer(node);
    first.catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);
    // The tailer of the process that merged passes the commit; then the process ends.
    first.catchUp(PROJECT);
    assertEquals(1, owed(node, PROJECT).size());
    assertEquals(
        node.storage().listManifestVersions().get(node.idOf(PROJECT)),
        cursor(node, PROJECT).getManifestVersion(),
        "the next process's sweep finds the cursor at the listed head and reads no entry");

    IndexEventTailer next = started(node, "second");
    try {
      next.startOwedEventDelivery();
      merges.awaitFired(1);
      assertEquals(CHANGE.get(), merges.fired.get(0).getChange());
    } finally {
      next.stop();
    }
  }

  @Test
  void anEventThatReachedTheLogIsNotFiredAgain() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);
    journal(node, PROJECT, CHANGE);

    IndexEventTailer next = laterProcess(node, "second");
    next.catchUp(PROJECT);
    next.fireOwed(node.idOf(PROJECT));

    assertTrue(owed(node, PROJECT).isEmpty());
    assertTrue(merges.fired.isEmpty());
  }

  @Test
  void anEventOfAnotherChangeOrPatchSetDoesNotSettleAMerge() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);
    journal(node, PROJECT, Change.id(CHANGE.get() + 1));
    node.manifestStore(PROJECT)
        .publishEvents(List.of(Merges.event(CHANGE.get(), Merges.PATCH_SET + 1)));

    IndexEventTailer next = laterProcess(node, "second");
    next.catchUp(PROJECT);
    next.fireOwed(node.idOf(PROJECT));

    assertEquals(1, merges.fired.size());
  }

  @Test
  void aProcessThatRunsDoesNotFireTheEventsOfItsOwnMerges() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    IndexEventTailer running = tailer(node);
    running.catchUp(PROJECT);

    // Between the commit and the journal's entry, the tailer comes by.
    merge(node, PROJECT, CHANGE);
    running.catchUp(PROJECT);
    running.fireOwed(node.idOf(PROJECT));
    assertTrue(merges.fired.isEmpty(), "the submit that committed fires its event itself");
    assertEquals(1, owed(node, PROJECT).size(), "kept, should the process end before it does");

    journal(node, PROJECT, CHANGE);
    running.catchUp(PROJECT);
    assertTrue(owed(node, PROJECT).isEmpty());
    assertTrue(merges.fired.isEmpty());
  }

  @Test
  void aTransactionThatDidNotCommitOwesNothing() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);
    Manifest before = node.manifestStore(PROJECT).read();
    try (Repository repository = node.openRepository(PROJECT)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, "merged");
      RefUpdate update = repository.updateRef(RefNames.changeMetaRef(CHANGE));
      // The submit lost a race: the ref is not what it was when the submit read it.
      update.setExpectedOldObjectId(commit);
      update.setNewObjectId(commit);
      assertNotEquals(RefUpdate.Result.NEW, update.update());
    }
    assertEquals(0, refTransactionsSince(node, PROJECT, before), "nothing was committed");

    IndexEventTailer next = laterProcess(node, "second");
    next.catchUp(PROJECT);
    next.fireOwed(node.idOf(PROJECT));

    assertTrue(owed(node, PROJECT).isEmpty());
    assertTrue(merges.fired.isEmpty());
  }

  @Test
  void anEventFiredByAProcessThatEndedBeforeItsJournalWroteItIsFiredAgain() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);

    IndexEventTailer second = laterProcess(node, "second");
    second.catchUp(PROJECT);
    second.fireOwed(node.idOf(PROJECT));
    assertEquals(1, merges.fired.size());

    // The second process ends within the journal's interval; the log never got the event.
    IndexEventTailer third = laterProcess(node, "third");
    third.catchUp(PROJECT);
    third.fireOwed(node.idOf(PROJECT));
    assertEquals(2, merges.fired.size(), "at least once: this node's listeners hear it twice");

    journal(node, PROJECT, CHANGE);
    third.catchUp(PROJECT);
    assertTrue(owed(node, PROJECT).isEmpty());
  }

  @Test
  void listenersOfAnOwedEventCanWaitForTheIndexWhileOtherThreadsReplay() throws Exception {
    WalGitRepositoryManager node = node();
    Project.NameKey other = Project.nameKey("platform/other");
    node.createRepository(PROJECT).close();
    node.createRepository(other).close();
    tailer(node).catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);

    IndexEventTailer next = started(node, "second");
    // A replay that finds the replay lock taken gives up at once instead of waiting a minute.
    next.replayLockWait(Duration.ofMillis(200));
    CompletableFuture<IndexEventTailer.Indexed> waited = new CompletableFuture<>();
    CompletableFuture<Integer> replayedElsewhere = new CompletableFuture<>();
    merges.onFire =
        merge -> {
          try {
            // What the autosubmitter does when it hears of a merge: it looks changes up, which
            // waits for this node's indexes, while other requests' replays go on.
            waited.complete(next.awaitIndexed(PROJECT, Duration.ofSeconds(5)));
            publishRef(node, other, Constants.R_HEADS + "meanwhile");
            replayedElsewhere.complete(
                CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            return next.catchUp(other);
                          } catch (IOException busy) {
                            throw new IllegalStateException(busy);
                          }
                        })
                    .get(5, TimeUnit.SECONDS));
          } catch (Exception failure) {
            waited.completeExceptionally(failure);
            replayedElsewhere.completeExceptionally(failure);
          }
          return MergeEvents.Delivery.FIRED;
        };
    try {
      next.startOwedEventDelivery();

      assertTrue(
          waited.get(10, TimeUnit.SECONDS).fresh(),
          "a thread that holds the replay lock is told the index is unavailable");
      assertEquals(
          1,
          replayedElsewhere.get(10, TimeUnit.SECONDS),
          "another thread replays while the listener runs: no lock of the tailer is held");
    } finally {
      next.stop();
    }
  }

  @Test
  void anEventThatCannotBeFiredNowIsTriedAgainAndOneThatNeverCanIsDropped() throws Exception {
    WalGitRepositoryManager node = node();
    Change.Id closedByPush = Change.id(CHANGE.get() + 1);
    Change.Id deleted = Change.id(CHANGE.get() + 2);
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);
    merge(node, PROJECT, closedByPush);
    merge(node, PROJECT, deleted);

    IndexEventTailer next = laterProcess(node, "second");
    next.catchUp(PROJECT);
    merges.onFire =
        merge -> {
          if (merge.getChange() == closedByPush.get()) {
            return MergeEvents.Delivery.LOST;
          }
          if (merge.getChange() == deleted.get()) {
            return MergeEvents.Delivery.GONE;
          }
          throw new IllegalStateException("the change cannot be read now");
        };
    next.fireOwed(node.idOf(PROJECT));

    List<OwedMerge> left = owed(node, PROJECT);
    assertEquals(1, left.size(), "what cannot be told is not kept");
    assertEquals(CHANGE.get(), left.get(0).getChange());
    assertEquals(
        ManifestStore.WRITER_PROCESS,
        left.get(0).getProcess(),
        "not fired: still the ended process's");

    merges.fired.clear();
    merges.onFire = merge -> MergeEvents.Delivery.FIRED;
    next.fireOwed(node.idOf(PROJECT));
    assertEquals(1, merges.fired.size());
    assertEquals(CHANGE.get(), merges.fired.get(0).getChange());
  }

  @Test
  void anIndexRebuildKeepsWhatIsOwedAndDoesNotPassOverTheMergesOfARepositoryItCouldReplay()
      throws Exception {
    WalGitRepositoryManager node = node();
    Project.NameKey broken = Project.nameKey("platform/broken");
    Change.Id recorded = Change.id(CHANGE.get() + 1);
    node.createRepository(PROJECT).close();
    node.createRepository(broken).close();
    IndexEventTailer first = tailer(node);
    first.catchUp(PROJECT);
    first.catchUp(broken);
    // One merge the first process's tailer recorded, one it never saw.
    merge(node, PROJECT, recorded);
    first.catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);
    // Another repository's cursor cannot be replayed: the node rebuilds and moves every cursor.
    publishRef(node, broken, Constants.R_HEADS + "main");
    IndexCursorStore brokenCursor =
        new IndexCursorStore(node.manifestStore(broken).indexCursorPath());
    brokenCursor.write(
        IndexCursorStore.at(
            brokenCursor.read(), brokenCursor.read().getSequence(), "no-such-transaction", ""));

    List<String> rebuilt = new ArrayList<>();
    IndexEventTailer next =
        new IndexEventTailer(
            node,
            (project, transaction) -> {},
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            new IndexEventReadiness(root.resolve("READY")),
            () -> rebuilt.add("all"));
    next.mergeEvents(merges);
    next.process("second");
    next.start();
    try {
      assertEquals(List.of("all"), rebuilt);
      assertEquals(
          node.manifestStore(PROJECT).read().getHeadSeq(), cursor(node, PROJECT).getSequence());
      assertFalse(cursor(node, PROJECT).hasMergesReadTo(), "read before the node became ready");
      assertEquals(
          List.of(recorded.get(), CHANGE.get()),
          owed(node, PROJECT).stream().map(OwedMerge::getChange).toList());
      // The entries of the cursor that could not be replayed cannot be read for merges either.
      assertFalse(cursor(node, broken).hasMergesReadTo());

      next.startOwedEventDelivery();
      merges.awaitFired(2);
    } finally {
      next.stop();
    }
  }

  @Test
  void anOfflineReindexKeepsWhatIsOwedAndLeavesTheMergesItPassedOverToTheDaemon() throws Exception {
    WalGitRepositoryManager node = node();
    Project.NameKey unseen = Project.nameKey("platform/unseen");
    Change.Id recorded = Change.id(CHANGE.get() + 1);
    node.createRepository(PROJECT).close();
    IndexEventTailer first = tailer(node);
    first.catchUp(PROJECT);
    merge(node, PROJECT, recorded);
    first.catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);
    // A repository this node never had a cursor for: it read nothing of it and owes nothing.
    node.createRepository(unseen).close();
    merge(node, unseen, CHANGE);

    assertTrue(IndexCursorSeeder.reindex(node, () -> true));

    IndexCursor moved = cursor(node, PROJECT);
    assertEquals(node.manifestStore(PROJECT).read().getHeadSeq(), moved.getSequence());
    assertEquals(1, moved.getOwedCount());
    assertTrue(moved.hasMergesReadTo());
    assertFalse(cursor(node, unseen).hasMergesReadTo());

    IndexEventTailer next = laterProcess(node, "second");
    // The entries that were passed over may hold the event of the merge that is on record.
    next.fireOwed(node.idOf(PROJECT));
    assertTrue(merges.fired.isEmpty(), "nothing is called missing before they are read");
    next.runOnce();
    next.fireOwed(node.idOf(PROJECT));
    next.fireOwed(node.idOf(unseen));
    assertEquals(
        List.of(recorded.get(), CHANGE.get()),
        merges.fired.stream().map(OwedMerge::getChange).toList());
    assertFalse(cursor(node, PROJECT).hasMergesReadTo());
  }

  @Test
  void entriesPassedOverAreReadWithinTheReplayLimitWhateverWasPublishedSince() throws Exception {
    WalGitRepositoryManager node = node(3);
    node.createRepository(PROJECT).close();
    IndexEventTailer first = tailer(node);
    first.catchUp(PROJECT);
    long seen = cursor(node, PROJECT).getSequence();
    merge(node, PROJECT, CHANGE);
    merge(node, PROJECT, Change.id(CHANGE.get() + 1));
    long passedOver = node.manifestStore(PROJECT).read().getHeadSeq() - seen;
    assertTrue(IndexCursorSeeder.reindex(node, () -> true));
    // Published after the reindex: replayed as usual, and within the limit by itself.
    publishRef(node, PROJECT, Constants.R_HEADS + "later");
    publishRef(node, PROJECT, Constants.R_HEADS + "still-later");
    long since = node.manifestStore(PROJECT).read().getHeadSeq() - seen - passedOver;
    assertTrue(passedOver <= 3 && since <= 3 && passedOver + since > 3, passedOver + "+" + since);

    IndexEventTailer next = laterProcess(node, "second");
    next.catchUp(PROJECT);
    next.fireOwed(node.idOf(PROJECT));

    assertEquals(
        List.of(CHANGE.get(), CHANGE.get() + 1),
        merges.fired.stream().map(OwedMerge::getChange).toList(),
        "the merges that the reindex passed over were read");
  }

  @Test
  void stoppingWaitsForTheEventBeingFiredAndLeavesTheOthersOwed() throws Exception {
    WalGitRepositoryManager node = node();
    Project.NameKey other = Project.nameKey("platform/other");
    node.createRepository(PROJECT).close();
    node.createRepository(other).close();
    IndexEventTailer first = tailer(node);
    first.catchUp(PROJECT);
    first.catchUp(other);
    merge(node, PROJECT, CHANGE);
    merge(node, other, CHANGE);

    IndexEventTailer next = started(node, "second");
    CountDownLatch firing = new CountDownLatch(1);
    CountDownLatch listenersDone = new CountDownLatch(1);
    List<String> order = new CopyOnWriteArrayList<>();
    merges.onFire =
        merge -> {
          firing.countDown();
          try {
            // Listeners of the event at work: what they fire has to find the journal running.
            assertTrue(listenersDone.await(10, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
          }
          order.add("listeners done");
          return MergeEvents.Delivery.FIRED;
        };
    try {
      next.startOwedEventDelivery();
      assertTrue(firing.await(10, TimeUnit.SECONDS));
      // Gerrit stops: the delivery first, the journal after it.
      CompletableFuture<Void> stopping =
          CompletableFuture.runAsync(
              () -> {
                next.stopOwedEventDelivery();
                order.add("delivery stopped; the journal may stop");
              });
      Thread.sleep(200);
      assertFalse(stopping.isDone(), "the event being fired is fired to the end");
      listenersDone.countDown();
      stopping.get(10, TimeUnit.SECONDS);

      assertEquals(List.of("listeners done", "delivery stopped; the journal may stop"), order);
      assertEquals(1, merges.fired.size(), "the other repository's event was not begun");
      long stillDue =
          java.util.stream.Stream.of(PROJECT, other)
              .flatMap(
                  project -> {
                    try {
                      return owed(node, project).stream();
                    } catch (IOException unreadable) {
                      throw new IllegalStateException(unreadable);
                    }
                  })
              .filter(merge -> merge.getProcess().equals(ManifestStore.WRITER_PROCESS))
              .count();
      assertEquals(1, stillDue, "it stays owed for the next process");
      next.startOwedEventDelivery();
      Thread.sleep(200);
      assertEquals(1, merges.fired.size(), "a delivery that was stopped does not begin again");
    } finally {
      listenersDone.countDown();
      next.stop();
    }
  }

  @Test
  void mergesOfOtherNodesAndOfANodeWithoutJournalAreNotRecorded() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    tailer(node).catchUp(PROJECT);
    merge(node, PROJECT, CHANGE);

    // The node that merged fires and, if need be, makes up for the event; no other node does.
    IndexEventTailer otherNode = laterProcess(node, "other-node");
    otherNode.foreignWriter(writer -> true);
    otherNode.catchUp(PROJECT);
    assertTrue(owed(node, PROJECT).isEmpty());

    // Without a journal no event of this node reaches the log: nothing would ever settle.
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("wal").toString());
    config.setBoolean("walgerrit", null, "eventJournalEnabled", false);
    WalGitRepositoryManager withoutJournal =
        new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve("no-journal")));
    merge(node, PROJECT, Change.id(CHANGE.get() + 1));
    IndexEventTailer silent = laterProcess(withoutJournal, "second");
    silent.catchUp(PROJECT);
    assertTrue(owed(withoutJournal, PROJECT).isEmpty());
  }

  // --- helpers

  private WalGitRepositoryManager node() {
    return node(0);
  }

  /** A node that replays at most {@code replayLimit} entries at once, if that is not 0. */
  private WalGitRepositoryManager node(int replayLimit) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("wal").toString());
    config.setString("walgerrit", null, "indexPollInterval", "1 hour");
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    if (replayLimit > 0) {
      config.setInt("walgerrit", null, "indexReplayLimit", replayLimit);
    }
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve("site")));
  }

  /** The tailer of the process that writes the entries of these tests. */
  private IndexEventTailer tailer(WalGitRepositoryManager node) {
    IndexEventTailer tailer =
        new IndexEventTailer(node, (project, transaction) -> {}, GerritRuntime.DAEMON);
    tailer.mergeEvents(merges);
    return tailer;
  }

  /** The tailer of a process that started after the one that wrote the entries ended. */
  private IndexEventTailer laterProcess(WalGitRepositoryManager node, String process) {
    IndexEventTailer tailer = tailer(node);
    tailer.process(process);
    return tailer;
  }

  /** A later process's tailer after its start-up sweep, as Gerrit starts it. */
  private IndexEventTailer started(WalGitRepositoryManager node, String process) {
    IndexEventTailer tailer =
        new IndexEventTailer(
            node,
            (project, transaction) -> {},
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            new IndexEventReadiness(root.resolve("READY")),
            null);
    tailer.mergeEvents(merges);
    tailer.process(process);
    tailer.start();
    return tailer;
  }

  private static Config durableLuceneConfig() {
    Config config = new Config();
    for (String name :
        List.of("accounts", "changes_open", "changes_closed", "groups", "projects")) {
      config.setString("index", name, "commitWithin", "0");
    }
    return config;
  }

  /** Commits what a submit commits for {@code change}, as far as {@link Merges} looks. */
  private static ObjectId merge(
      WalGitRepositoryManager node, Project.NameKey project, Change.Id change) throws Exception {
    return publishRef(node, project, RefNames.changeMetaRef(change));
  }

  /** What the journal writes once the change-merged event of {@code change} was fired. */
  private static void journal(
      WalGitRepositoryManager node, Project.NameKey project, Change.Id change) throws Exception {
    node.manifestStore(project)
        .publishEvents(
            List.of("{\"type\":\"ref-updated\"}", Merges.event(change.get(), Merges.PATCH_SET)));
  }

  private static ObjectId publishRef(
      WalGitRepositoryManager node, Project.NameKey project, String ref) throws Exception {
    try (Repository repository = node.openRepository(project)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, ref);
      RefUpdate update = repository.updateRef(ref);
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commit;
    }
  }

  /** How many ref transactions the log holds after the head of {@code before}. */
  private static long refTransactionsSince(
      WalGitRepositoryManager node, Project.NameKey project, Manifest before) throws IOException {
    ManifestStore store = node.manifestStore(project);
    return store
        .readLogEntriesAfter(
            before.getHeadSeq(), before.getHeadTransactionId(), store.read(), Long.MAX_VALUE)
        .stream()
        .filter(entry -> entry.getKind() == LogEntry.Kind.REF_UPDATE)
        .count();
  }

  private static IndexCursor cursor(WalGitRepositoryManager node, Project.NameKey project)
      throws IOException {
    return new IndexCursorStore(node.manifestStore(project).indexCursorPath()).read();
  }

  private static List<OwedMerge> owed(WalGitRepositoryManager node, Project.NameKey project)
      throws IOException {
    return cursor(node, project).getOwedList();
  }

  /**
   * Stands in for Gerrit: a transaction that sets a change's meta ref merged the change, an event
   * is a line that names change and patch set, and firing is recorded.
   */
  private static final class Merges implements MergeEvents {
    static final int PATCH_SET = 2;

    final List<OwedMerge> fired = new CopyOnWriteArrayList<>();
    volatile Function<OwedMerge, Delivery> onFire = merge -> Delivery.FIRED;

    static String event(int change, int patchSet) {
      return "change-merged " + change + "/" + patchSet;
    }

    @Override
    public List<OwedMerge> mergedBy(Project.NameKey project, LogEntry entry) {
      List<OwedMerge> merged = new ArrayList<>();
      RefTransaction transaction = entry.getRefTransaction();
      for (var update : transaction.getUpdatesList()) {
        Change.Id change = Change.Id.fromRef(update.getName());
        if (change != null && update.getName().endsWith(RefNames.META_SUFFIX)) {
          merged.add(
              OwedMerge.newBuilder()
                  .setChange(change.get())
                  .setPatchSet(PATCH_SET)
                  .setMetaRevision(update.getNewObjectId())
                  .setNewRevision(update.getNewObjectId())
                  .setSequence(entry.getSeq())
                  .setProcess(entry.getWriterProcess())
                  .build());
        }
      }
      return merged;
    }

    @Override
    public List<OwedMerge> withoutAnnounced(List<OwedMerge> owed, LogEntry entry) {
      return owed.stream()
          .filter(
              merge ->
                  !entry.getEventJsonList().contains(event(merge.getChange(), merge.getPatchSet())))
          .toList();
    }

    @Override
    public Delivery fire(Project.NameKey project, OwedMerge merge) {
      Delivery delivery = onFire.apply(merge);
      fired.add(merge);
      return delivery;
    }

    void awaitFired(int events) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (fired.size() < events && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(events, fired.size());
    }
  }
}
