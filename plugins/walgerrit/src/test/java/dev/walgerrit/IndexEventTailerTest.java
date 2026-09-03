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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexEventTailerTest {
  @TempDir Path storagePath;

  @Test
  void replaysEachCommittedRefTransactionOnceAcrossRestart() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/index-events");
    manager.createRepository(project).close();

    RecordingApplier applier = new RecordingApplier();
    IndexEventTailer first = tailer(manager, applier);
    first.catchUp(project); // Seed the cursor after repository initialization.
    applier.transactions.clear();

    ObjectId commit;
    try (Repository repository = manager.openRepository(project)) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "index event");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    assertEquals(1, first.catchUp(project));
    assertEquals(1, applier.transactions.size());
    RefTransaction transaction = applier.transactions.get(0);
    assertEquals(1, transaction.getUpdatesCount());
    assertEquals(Constants.R_HEADS + "main", transaction.getUpdates(0).getName());
    assertEquals(ObjectId.zeroId().name(), transaction.getUpdates(0).getOldObjectId());
    assertEquals(commit.name(), transaction.getUpdates(0).getNewObjectId());

    assertEquals(0, first.catchUp(project));
    assertEquals(0, tailer(manager, new RecordingApplier()).catchUp(project));

    ManifestStore store = manager.storage().manifestStore(project);
    IndexCursor cursor = new IndexCursorStore(store.indexCursorPath()).read();
    assertEquals(store.read().getHeadSeq(), cursor.getSequence());
    assertEquals(store.read().getHeadTransactionId(), cursor.getTransactionId());
  }

  @Test
  void failedIndexApplicationLeavesCursorForRetry() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/index-retry");
    manager.createRepository(project).close();

    RecordingApplier seed = new RecordingApplier();
    tailer(manager, seed).catchUp(project);
    ManifestStore store = manager.storage().manifestStore(project);
    long cursorBefore = new IndexCursorStore(store.indexCursorPath()).read().getSequence();

    try (Repository repository = manager.openRepository(project)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, "retry");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "retry");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    IndexEventTailer failing =
        tailer(
            manager,
            (ignoredProject, ignoredTransaction) -> {
              throw new IllegalStateException("injected index failure");
            });
    assertThrows(IllegalStateException.class, () -> failing.catchUp(project));
    long cursorAfterFailure =
        new IndexCursorStore(store.indexCursorPath()).read().getSequence();
    assertTrue(cursorAfterFailure >= cursorBefore);
    assertEquals(store.read().getHeadSeq() - 1, cursorAfterFailure);

    RecordingApplier retry = new RecordingApplier();
    assertEquals(1, tailer(manager, retry).catchUp(project));
    assertEquals(1, retry.transactions.size());
    assertTrue(
        retry.transactions.get(0).getUpdatesList().stream()
            .anyMatch(update -> update.getName().equals(Constants.R_HEADS + "retry")));
  }

  @Test
  void durableCursorRequiresEveryLuceneIndexToCommitEachWrite() {
    org.eclipse.jgit.lib.Config config = new org.eclipse.jgit.lib.Config();

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> IndexEventTailer.validateIndexDurability("lucene", config));

    assertTrue(exception.getMessage().contains("commitWithin = 0"));
  }

  @Test
  void acceptsDurableLuceneConfiguration() {
    org.eclipse.jgit.lib.Config config = durableLuceneConfig();

    IndexEventTailer.validateIndexDurability("lucene", config);
  }

  @Test
  void twoNodesSharingLocalWalKeepIndependentIndexCursors() throws Exception {
    Path sharedWal = storagePath.resolve("shared-wal");
    WalGitRepositoryManager first = configuredManager(sharedWal, storagePath.resolve("node-1"));
    WalGitRepositoryManager second = configuredManager(sharedWal, storagePath.resolve("node-2"));
    Project.NameKey project = Project.nameKey("platform/independent-cursors");
    first.createRepository(project).close();

    ManifestStore firstStore = first.storage().manifestStore(project);
    ManifestStore secondStore = second.storage().manifestStore(project);
    assertNotEquals(firstStore.indexCursorPath(), secondStore.indexCursorPath());

    tailer(first, new RecordingApplier()).catchUp(project);
    assertTrue(Files.isRegularFile(firstStore.indexCursorPath()));
    assertTrue(Files.notExists(secondStore.indexCursorPath()));

    tailer(second, new RecordingApplier()).catchUp(project);
    assertTrue(Files.isRegularFile(secondStore.indexCursorPath()));
    assertEquals(
        new IndexCursorStore(firstStore.indexCursorPath()).read().getSequence(),
        new IndexCursorStore(secondStore.indexCursorPath()).read().getSequence());
  }

  @Test
  void startupSynchronouslyCatchesUpBeforePublishingReadiness() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/startup-catch-up");
    manager.createRepository(project).close();

    ObjectId commit;
    try (Repository repository = manager.openRepository(project)) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "before startup");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    RecordingApplier applier = new RecordingApplier();
    IndexEventReadiness readiness = readiness("startup-node");
    IndexEventTailer tailer = startingTailer(manager, applier, readiness);
    tailer.start();
    try {
      assertTrue(
          applier.transactions.stream()
              .flatMap(transaction -> transaction.getUpdatesList().stream())
              .anyMatch(
                  update ->
                      update.getName().equals(Constants.R_HEADS + "main")
                          && update.getNewObjectId().equals(commit.name())));
      assertTrue(readiness.isReady());
      assertTrue(Files.isRegularFile(readiness.markerPath()));

      ManifestStore store = manager.storage().manifestStore(project);
      assertEquals(
          store.read().getHeadSeq(),
          new IndexCursorStore(store.indexCursorPath()).read().getSequence());
    } finally {
      tailer.stop();
    }
    assertFalse(readiness.isReady());
    assertTrue(Files.notExists(readiness.markerPath()));
  }

  @Test
  void startupFailureClearsStaleMarkerAndRefusesReadiness() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/startup-failure");
    manager.createRepository(project).close();

    IndexEventReadiness readiness = readiness("failed-node");
    Files.createDirectories(readiness.markerPath().getParent());
    Files.writeString(readiness.markerPath(), "stale\n");
    IndexEventTailer tailer =
        startingTailer(
            manager,
            (ignoredProject, ignoredTransaction) -> {
              throw new IllegalStateException("injected startup failure");
            },
            readiness);

    IllegalStateException failure = assertThrows(IllegalStateException.class, tailer::start);

    assertTrue(failure.getMessage().contains("refusing to become ready"));
    assertFalse(readiness.isReady());
    assertTrue(Files.notExists(readiness.markerPath()));
  }

  @Test
  void failedBackgroundSweepRevokesReadinessUntilAFullSweepSucceeds() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/readiness-recovery");
    manager.createRepository(project).close();

    AtomicBoolean fail = new AtomicBoolean();
    RecordingApplier recorded = new RecordingApplier();
    IndexEventApplier applier =
        (eventProject, transaction) -> {
          if (fail.get()) {
            throw new IllegalStateException("injected background failure");
          }
          recorded.apply(eventProject, transaction);
        };
    IndexEventReadiness readiness = readiness("recovering-node");
    IndexEventTailer tailer = startingTailer(manager, applier, readiness);
    tailer.start();
    try {
      try (Repository repository = manager.openRepository(project)) {
        ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, "during failure");
        RefUpdate update = repository.updateRef(Constants.R_HEADS + "recovery");
        update.setNewObjectId(commit);
        assertEquals(RefUpdate.Result.NEW, update.update());
      }

      fail.set(true);
      tailer.runBackgroundSweep();
      assertFalse(readiness.isReady());
      assertTrue(Files.notExists(readiness.markerPath()));

      fail.set(false);
      tailer.runBackgroundSweep();
      assertTrue(readiness.isReady());
      assertTrue(Files.isRegularFile(readiness.markerPath()));
      assertTrue(
          recorded.transactions.stream()
              .flatMap(transaction -> transaction.getUpdatesList().stream())
              .anyMatch(update -> update.getName().equals(Constants.R_HEADS + "recovery")));
    } finally {
      tailer.stop();
    }
  }

  @Test
  void batchLifecycleDoesNotRemoveDaemonReadinessMarker() throws Exception {
    WalGitRepositoryManager manager = manager();
    IndexEventReadiness readiness = readiness("daemon-owned-marker");
    Files.createDirectories(readiness.markerPath().getParent());
    Files.writeString(readiness.markerPath(), "ready\n");
    IndexEventTailer batch =
        new IndexEventTailer(
            manager,
            new RecordingApplier(),
            GerritRuntime.BATCH,
            "lucene",
            durableLuceneConfig(),
            readiness,
            null);

    batch.start();
    batch.stop();

    assertTrue(Files.isRegularFile(readiness.markerPath()));
  }

  private static org.eclipse.jgit.lib.Config durableLuceneConfig() {
    org.eclipse.jgit.lib.Config config = new org.eclipse.jgit.lib.Config();
    for (String name :
        List.of("accounts", "changes_open", "changes_closed", "groups", "projects")) {
      config.setString("index", name, "commitWithin", "0");
    }
    return config;
  }

  private WalGitRepositoryManager manager() {
    return new WalGitRepositoryManager(new WalGitConfiguration(BackendType.LOCAL, storagePath));
  }

  private static WalGitRepositoryManager configuredManager(Path sharedWal, Path sitePath) {
    org.eclipse.jgit.lib.Config config = new org.eclipse.jgit.lib.Config();
    config.setString("walgerrit", null, "storagePath", sharedWal.toString());
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, sitePath));
  }

  private static IndexEventTailer tailer(
      WalGitRepositoryManager manager, IndexEventApplier applier) {
    return new IndexEventTailer(manager, applier, GerritRuntime.DAEMON);
  }

  private IndexEventReadiness readiness(String node) {
    return new IndexEventReadiness(storagePath.resolve(node).resolve("READY"));
  }

  private static IndexEventTailer startingTailer(
      WalGitRepositoryManager manager,
      IndexEventApplier applier,
      IndexEventReadiness readiness) {
    return new IndexEventTailer(
        manager,
        applier,
        GerritRuntime.DAEMON,
        "lucene",
        durableLuceneConfig(),
        readiness,
        null);
  }

  @Test
  void cursorTooFarBehindRebuildsAllIndexesAndReseedsCursors() throws Exception {
    Path shared = storagePath.resolve("floor-shared");
    WalGitRepositoryManager nodeA = limitedManager(shared, "node-a");
    Project.NameKey project = Project.nameKey("platform/floor");
    nodeA.createRepository(project).close();
    IndexEventTailer tailerA = tailer(nodeA, new RecordingApplier());
    for (int i = 0; i < 6; i++) {
      publishRef(nodeA, project, Constants.R_HEADS + "b" + i);
      tailerA.catchUp(project);
    }
    ManifestStore storeA = nodeA.storage().manifestStore(project);
    Manifest head = storeA.read();
    assertTrue(head.getHeadSeq() > 3, "more entries than a fresh node may replay");

    WalGitRepositoryManager nodeB = limitedManager(shared, "node-b");
    RecordingApplier applierB = new RecordingApplier();
    FakeRebuilder rebuilder = new FakeRebuilder();
    IndexEventReadiness readiness = readiness("floor-node-b");
    IndexEventTailer tailerB =
        new IndexEventTailer(
            nodeB, applierB, GerritRuntime.DAEMON, "lucene", durableLuceneConfig(), readiness,
            rebuilder);
    tailerB.start();
    try {
      assertEquals(1, rebuilder.rebuilds.get(), "a fresh node too far behind rebuilds once");
      assertTrue(applierB.transactions.isEmpty(), "history was rebuilt, not replayed");
      IndexCursor cursor =
          new IndexCursorStore(nodeB.storage().manifestStore(project).indexCursorPath()).read();
      assertEquals(head.getHeadSeq(), cursor.getSequence());
      assertEquals(head.getHeadTransactionId(), cursor.getTransactionId());
      assertTrue(readiness.isReady());

      ObjectId later = publishRef(nodeA, project, Constants.R_HEADS + "after-rebuild");
      tailerB.runBackgroundSweep();
      assertTrue(applierB.sawUpdate(Constants.R_HEADS + "after-rebuild", later));
      assertEquals(1, rebuilder.rebuilds.get(), "later writes replay normally");
    } finally {
      tailerB.stop();
    }
  }

  @Test
  void writesLandingDuringTheRebuildAreReplayedFromTheSeed() throws Exception {
    Path shared = storagePath.resolve("during-shared");
    WalGitRepositoryManager nodeA = limitedManager(shared, "node-a");
    Project.NameKey project = Project.nameKey("platform/during");
    nodeA.createRepository(project).close();
    IndexEventTailer tailerA = tailer(nodeA, new RecordingApplier());
    for (int i = 0; i < 6; i++) {
      publishRef(nodeA, project, Constants.R_HEADS + "b" + i);
      tailerA.catchUp(project);
    }

    WalGitRepositoryManager nodeB = limitedManager(shared, "node-b");
    RecordingApplier applierB = new RecordingApplier();
    ObjectId[] concurrent = new ObjectId[1];
    FakeRebuilder rebuilder = new FakeRebuilder();
    rebuilder.duringRebuild =
        () -> {
          try {
            concurrent[0] = publishRef(nodeA, project, Constants.R_HEADS + "concurrent");
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    IndexEventTailer tailerB =
        new IndexEventTailer(
            nodeB, applierB, GerritRuntime.DAEMON, "lucene", durableLuceneConfig(),
            readiness("during-node-b"), rebuilder);
    tailerB.start();
    try {
      assertEquals(1, rebuilder.rebuilds.get());
      assertTrue(
          applierB.sawUpdate(Constants.R_HEADS + "concurrent", concurrent[0]),
          "the write that landed during the rebuild was replayed from the pre-rebuild seed");
    } finally {
      tailerB.stop();
    }
  }

  @Test
  void historyMismatchTriggersARebuild() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/mismatch");
    manager.createRepository(project).close();
    RecordingApplier applier = new RecordingApplier();
    FakeRebuilder rebuilder = new FakeRebuilder();
    IndexEventTailer tailer =
        new IndexEventTailer(
            manager, applier, GerritRuntime.DAEMON, "lucene", durableLuceneConfig(),
            readiness("mismatch-node"), rebuilder);
    tailer.runOnce();
    assertEquals(0, rebuilder.rebuilds.get());

    ManifestStore store = manager.storage().manifestStore(project);
    IndexCursorStore cursorStore = new IndexCursorStore(store.indexCursorPath());
    cursorStore.write(cursorStore.read().getSequence(), "not-the-transaction-that-happened");

    // A sweep skips repositories whose manifest did not change, so a cursor is re-examined when
    // the node restarts, which a fresh tailer models.
    IndexEventTailer restarted =
        new IndexEventTailer(
            manager, applier, GerritRuntime.DAEMON, "lucene", durableLuceneConfig(),
            readiness("mismatch-node"), rebuilder);
    restarted.runOnce();
    assertEquals(1, rebuilder.rebuilds.get(), "a cursor naming an unknown transaction rebuilds");
    assertEquals(
        store.read().getHeadTransactionId(), cursorStore.read().getTransactionId());
  }

  @Test
  void rebuildDisabledOrUnavailableFailsClosedWithGuidance() throws Exception {
    Path shared = storagePath.resolve("closed-shared");
    WalGitRepositoryManager nodeA = limitedManager(shared, "node-a");
    Project.NameKey project = Project.nameKey("platform/closed");
    nodeA.createRepository(project).close();
    IndexEventTailer tailerA = tailer(nodeA, new RecordingApplier());
    for (int i = 0; i < 6; i++) {
      publishRef(nodeA, project, Constants.R_HEADS + "b" + i);
      tailerA.catchUp(project);
    }

    IndexEventReadiness readiness = readiness("closed-node-b");
    IndexEventTailer noRebuilder =
        new IndexEventTailer(
            limitedManager(shared, "node-b"), new RecordingApplier(), GerritRuntime.DAEMON,
            "lucene", durableLuceneConfig(), readiness, null);
    IllegalStateException failure = assertThrows(IllegalStateException.class, noRebuilder::start);
    assertTrue(failure.getCause().getMessage().contains("offline reindex"));
    assertFalse(readiness.isReady());

    Config config = replayLimitConfig(shared, "node-c");
    config.setBoolean("walgerrit", null, "indexRebuildOnStaleCursor", false);
    WalGitRepositoryManager nodeC =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(config, shared.resolveSibling("node-c-site")));
    IndexEventTailer disabled =
        new IndexEventTailer(
            nodeC, new RecordingApplier(), GerritRuntime.DAEMON, "lucene", durableLuceneConfig(),
            readiness("closed-node-c"), new FakeRebuilder());
    failure = assertThrows(IllegalStateException.class, disabled::start);
    assertTrue(failure.getCause().getMessage().contains("disabled"));
  }

  private static ObjectId publishRef(
      WalGitRepositoryManager manager, Project.NameKey project, String ref) throws Exception {
    try (Repository repository = manager.openRepository(project)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, ref);
      RefUpdate update = repository.updateRef(ref);
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commit;
    }
  }

  /** A replay limit of three entries: a fresh node rebuilds once a repository has more. */
  private static Config replayLimitConfig(Path sharedStore, String node) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", sharedStore.toString());
    config.setString(
        "walgerrit",
        null,
        "indexCursorPath",
        sharedStore.resolveSibling(node + "-cursors").toString());
    config.setString("walgerrit", null, "indexPollInterval", "1 hour");
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    config.setString("walgerrit", null, "indexReplayLimit", "3");
    return config;
  }

  private static WalGitRepositoryManager limitedManager(Path sharedStore, String node) {
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(
            replayLimitConfig(sharedStore, node), sharedStore.resolveSibling(node + "-site")));
  }

  private static final class FakeRebuilder implements IndexRebuilder {
    final AtomicInteger rebuilds = new AtomicInteger();
    Runnable duringRebuild;

    @Override
    public void rebuildAll() throws IOException {
      rebuilds.incrementAndGet();
      if (duringRebuild != null) {
        duringRebuild.run();
      }
    }
  }

  private static final class RecordingApplier implements IndexEventApplier {
    private final List<RefTransaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      transactions.add(transaction);
    }

    boolean sawUpdate(String ref, ObjectId newValue) {
      return transactions.stream()
          .flatMap(transaction -> transaction.getUpdatesList().stream())
          .anyMatch(
              update ->
                  update.getName().equals(ref) && update.getNewObjectId().equals(newValue.name()));
    }
  }
}
