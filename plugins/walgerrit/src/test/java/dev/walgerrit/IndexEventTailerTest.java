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
import dev.walgerrit.ManifestReadCountTest.CountingObjectStore;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    ManifestStore store = manager.manifestStore(project);
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
    ManifestStore store = manager.manifestStore(project);
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
    long cursorAfterFailure = new IndexCursorStore(store.indexCursorPath()).read().getSequence();
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

    ManifestStore firstStore = first.manifestStore(project);
    ManifestStore secondStore = second.manifestStore(project);
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

      ManifestStore store = manager.manifestStore(project);
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

  @Test
  void wakeUpReplaysOneRepositoryWithoutASweep() throws Exception {
    Path sharedWal = storagePath.resolve("shared-wal");
    WalGitRepositoryManager writer = configuredManager(sharedWal, storagePath.resolve("writer"));
    org.eclipse.jgit.lib.Config readerConfig = new org.eclipse.jgit.lib.Config();
    readerConfig.setString("walgerrit", null, "storagePath", sharedWal.toString());
    readerConfig.setString("walgerrit", null, "indexPollInterval", "1 hour");
    WalGitRepositoryManager reader =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(readerConfig, storagePath.resolve("reader")));
    Project.NameKey project = Project.nameKey("platform/wake-up");
    writer.createRepository(project).close();

    RecordingApplier applier = new RecordingApplier();
    IndexEventTailer tailer = startingTailer(reader, applier, readiness("wake-up-node"));
    tailer.wake(reader.idOf(project), null); // No tailer thread yet: the startup sweep covers this.
    tailer.start();
    try {
      ObjectId commit;
      try (Repository repository = writer.openRepository(project)) {
        commit = WalGitRepositoryManagerTest.insertCommit(repository, "woken");
        RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
        update.setNewObjectId(commit);
        assertEquals(RefUpdate.Result.NEW, update.update());
      }
      String version = writer.manifestStore(project).refreshVersionedManifest().version();
      assertFalse(applier.sawUpdate(Constants.R_HEADS + "main", commit));

      tailer.wake(reader.idOf(project), version);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (tailer.wakeUpsReplayed() < 1 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(1, tailer.wakeUpsReplayed());
      assertTrue(applier.sawUpdate(Constants.R_HEADS + "main", commit));

      // The node replayed exactly that manifest: another wake-up for it is a no-op.
      tailer.wake(reader.idOf(project), version);
      assertEquals(1, tailer.wakeUpsReplayed());
    } finally {
      tailer.stop();
    }
    tailer.wake(reader.idOf(project), null); // Stopped: ignored rather than rejected.
    assertEquals(1, tailer.wakeUpsReplayed());
  }

  @Test
  void queuedWakeUpReadsPublicationsAnnouncedWhileItWasWaiting() throws Exception {
    Path shared = storagePath.resolve("shared-wal");
    WalGitRepositoryManager writer = configuredManager(shared, storagePath.resolve("writer"));
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", shared.toString());
    config.setString("walgerrit", null, "indexPollInterval", "1 hour");
    WalGitRepositoryManager reader =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(config, storagePath.resolve("reader")));
    Project.NameKey blocker = Project.nameKey("platform/blocker");
    Project.NameKey project = Project.nameKey("platform/queued");
    writer.createRepository(blocker).close();
    writer.createRepository(project).close();
    CountDownLatch replayStarted = new CountDownLatch(1);
    CountDownLatch releaseReplay = new CountDownLatch(1);
    AtomicBoolean block = new AtomicBoolean();
    RecordingApplier applied = new RecordingApplier();
    IndexEventTailer tailer =
        startingTailer(
            reader,
            (name, transaction) -> {
              if (name.equals(blocker) && block.get()) {
                replayStarted.countDown();
                try {
                  if (!releaseReplay.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release replay");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
              }
              applied.apply(name, transaction);
            },
            readiness("queued"));
    tailer.start();
    try {
      block.set(true);
      publishRef(writer, blocker, "refs/heads/block");
      tailer.wake(reader.idOf(blocker), null);
      assertTrue(replayStarted.await(10, TimeUnit.SECONDS));

      publishRef(writer, project, "refs/heads/first");
      String firstVersion =
          reader.manifestStore(project).refreshVersionedManifest().version();
      tailer.wake(reader.idOf(project), firstVersion);
      ObjectId second = publishRef(writer, project, "refs/heads/second");
      var latest = writer.manifestStore(project).refreshVersionedManifest();
      reader.storage().expectManifest(reader.idOf(project), latest.version(), latest.manifest().getRevision());
      tailer.wake(reader.idOf(project), latest.version());
      releaseReplay.countDown();

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (tailer.wakeUpsReplayed() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(2, tailer.wakeUpsReplayed());
      assertTrue(
          applied.sawUpdate("refs/heads/second", second),
          "a coalesced hint must not leave replay at the first announced version");
    } finally {
      releaseReplay.countDown();
      tailer.stop();
    }
  }

  @Test
  void concurrentCatchUpsApplyEachTransactionOnceInOrder() throws Exception {
    Path shared = storagePath.resolve("shared-wal");
    WalGitRepositoryManager writer = configuredManager(shared, storagePath.resolve("writer"));
    WalGitRepositoryManager reader = configuredManager(shared, storagePath.resolve("reader"));
    Project.NameKey project = Project.nameKey("platform/concurrent-replay");
    writer.createRepository(project).close();
    AtomicInteger applying = new AtomicInteger();
    AtomicInteger overlaps = new AtomicInteger();
    List<String> applied = new CopyOnWriteArrayList<>();
    IndexEventTailer tailer =
        tailer(
            reader,
            (name, transaction) -> {
              if (applying.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
              }
              try {
                transaction.getUpdatesList().forEach(update -> applied.add(update.getName()));
                Thread.sleep(1);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                applying.decrementAndGet();
              }
            });
    RepositoryId id = reader.idOf(project);
    tailer.catchUp(id);
    applied.clear();

    AtomicBoolean publishing = new AtomicBoolean(true);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Thread> replayers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      Thread replayer =
          new Thread(
              () -> {
                try {
                  while (publishing.get()) {
                    tailer.catchUp(id);
                  }
                  tailer.catchUp(id);
                } catch (Throwable failure) {
                  failures.add(failure);
                }
              });
      replayer.start();
      replayers.add(replayer);
    }
    List<String> published = new ArrayList<>();
    try {
      for (int i = 0; i < 40; i++) {
        String ref = Constants.R_HEADS + "branch-" + i;
        publishRef(writer, project, ref);
        published.add(ref);
      }
    } finally {
      publishing.set(false);
      for (Thread replayer : replayers) {
        replayer.join(TimeUnit.SECONDS.toMillis(30));
      }
    }

    assertEquals(List.of(), failures);
    assertEquals(0, overlaps.get(), "two threads replayed the repository at once");
    assertEquals(published, applied);
  }

  @Test
  void repositoryReplayWaitsForCatalogReplay() throws Exception {
    Path shared = storagePath.resolve("shared-wal");
    WalGitRepositoryManager writer = configuredManager(shared, storagePath.resolve("writer"));
    WalGitRepositoryManager reader = configuredManager(shared, storagePath.resolve("reader"));
    Project.NameKey project = Project.nameKey("platform/existing");
    writer.createRepository(project).close();
    CountDownLatch catalogReplaying = new CountDownLatch(1);
    CountDownLatch releaseCatalog = new CountDownLatch(1);
    AtomicBoolean block = new AtomicBoolean();
    RecordingApplier applied = new RecordingApplier();
    IndexEventTailer tailer =
        tailer(
            reader,
            new IndexEventApplier() {
              @Override
              public void apply(Project.NameKey name, RefTransaction transaction) {
                applied.apply(name, transaction);
              }

              @Override
              public void namespaceChanged(List<NamespaceChange> changes) {
                if (!block.get()) {
                  return;
                }
                catalogReplaying.countDown();
                try {
                  if (!releaseCatalog.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the catalog replay");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
              }
            });
    RepositoryId id = reader.idOf(project);
    tailer.catchUp(RepositoryId.CATALOG);
    tailer.catchUp(id);

    ObjectId commit = publishRef(writer, project, Constants.R_HEADS + "main");
    writer.createRepository(Project.nameKey("platform/created")).close();
    block.set(true);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread catalog = replayer(tailer, RepositoryId.CATALOG, failures);
    catalog.start();
    try {
      assertTrue(catalogReplaying.await(10, TimeUnit.SECONDS));
      Thread repository = replayer(tailer, id, failures);
      repository.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (repository.getState() != Thread.State.TIMED_WAITING) {
        assertTrue(System.nanoTime() < deadline, "the repository replay waits");
        Thread.sleep(10);
      }
      assertFalse(applied.sawUpdate(Constants.R_HEADS + "main", commit));

      releaseCatalog.countDown();
      repository.join(TimeUnit.SECONDS.toMillis(10));
    } finally {
      releaseCatalog.countDown();
      catalog.join(TimeUnit.SECONDS.toMillis(10));
    }

    assertEquals(List.of(), failures);
    assertTrue(applied.sawUpdate(Constants.R_HEADS + "main", commit));
  }

  private static Thread replayer(
      IndexEventTailer tailer, RepositoryId id, List<Throwable> failures) {
    return new Thread(
        () -> {
          try {
            tailer.catchUp(id);
          } catch (Throwable failure) {
            failures.add(failure);
          }
        });
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
      WalGitRepositoryManager manager, IndexEventApplier applier, IndexEventReadiness readiness) {
    return new IndexEventTailer(
        manager, applier, GerritRuntime.DAEMON, "lucene", durableLuceneConfig(), readiness, null);
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
    ManifestStore storeA = nodeA.manifestStore(project);
    Manifest head = storeA.read();
    assertTrue(head.getHeadSeq() > 3, "more entries than a fresh node may replay");

    WalGitRepositoryManager nodeB = limitedManager(shared, "node-b");
    RecordingApplier applierB = new RecordingApplier();
    FakeRebuilder rebuilder = new FakeRebuilder();
    IndexEventReadiness readiness = readiness("floor-node-b");
    IndexEventTailer tailerB =
        new IndexEventTailer(
            nodeB,
            applierB,
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            readiness,
            rebuilder);
    tailerB.start();
    try {
      assertEquals(1, rebuilder.rebuilds.get(), "a fresh node too far behind rebuilds once");
      assertTrue(applierB.transactions.isEmpty(), "history was rebuilt, not replayed");
      IndexCursor cursor =
          new IndexCursorStore(nodeB.manifestStore(project).indexCursorPath()).read();
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
            nodeB,
            applierB,
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            readiness("during-node-b"),
            rebuilder);
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
            manager,
            applier,
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            readiness("mismatch-node"),
            rebuilder);
    tailer.runOnce();
    assertEquals(0, rebuilder.rebuilds.get());

    ManifestStore store = manager.manifestStore(project);
    IndexCursorStore cursorStore = new IndexCursorStore(store.indexCursorPath());
    cursorStore.write(
        IndexCursorStore.at(
            cursorStore.read(),
            cursorStore.read().getSequence(),
            "not-the-transaction-that-happened",
            ""));

    // A sweep skips repositories whose manifest did not change, so a cursor is re-examined when
    // the node restarts, which a fresh tailer models.
    IndexEventTailer restarted =
        new IndexEventTailer(
            manager,
            applier,
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            readiness("mismatch-node"),
            rebuilder);
    restarted.runOnce();
    assertEquals(1, rebuilder.rebuilds.get(), "a cursor naming an unknown transaction rebuilds");
    assertEquals(store.read().getHeadTransactionId(), cursorStore.read().getTransactionId());
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
            limitedManager(shared, "node-b"),
            new RecordingApplier(),
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            readiness,
            null);
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
            nodeC,
            new RecordingApplier(),
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            readiness("closed-node-c"),
            new FakeRebuilder());
    failure = assertThrows(IllegalStateException.class, disabled::start);
    assertTrue(failure.getCause().getMessage().contains("disabled"));
  }

  @ParameterizedTest(name = "entries of another node: {0}")
  @ValueSource(booleans = {false, true})
  void sweepReplaysARepositoryAgainAfterTheOneItsEntryDependsOn(boolean foreign) throws Exception {
    CountingObjectStore store = new CountingObjectStore(new FileObjectStore(storagePath));
    WalGitRepositoryManager manager = sweptManager(store);
    InSweepOrder repos = inSweepOrder(manager);
    StarApplier applier = new StarApplier(repos.first());
    IndexEventReadiness readiness = readiness("dependent-node");
    IndexEventTailer tailer = startingTailer(manager, applier, readiness);
    // The tailer applies this node's own ref transactions like any other node's.
    tailer.foreignWriter(writer -> foreign);
    tailer.start();
    try {
      // The change is created before it is starred, but the sweep reaches the star first.
      publishRef(manager, repos.second(), CHANGE_1);
      publishRef(manager, repos.first(), STAR_1);
      store.reset();

      tailer.runBackgroundSweep();

      assertTrue(readiness.isReady(), "the star was only waiting for its change");
      assertEquals(List.of("no change 1", "change 1", "star 1"), applier.applied);
      assertAtHead(manager, repos.first());
      assertAtHead(manager, repos.second());
      assertEquals(2, store.count(LISTINGS), "one more listing, because a replay failed");

      // A sweep in which nothing fails costs one listing, as before.
      publishRef(manager, repos.second(), "refs/changes/02/2/meta");
      store.reset();
      tailer.runBackgroundSweep();
      assertTrue(readiness.isReady());
      assertEquals(1, store.count(LISTINGS));
    } finally {
      tailer.stop();
    }
  }

  @Test
  void sweepFindsADependencyPublishedAfterItsListing() throws Exception {
    WalGitRepositoryManager manager = sweptManager(new FileObjectStore(storagePath));
    InSweepOrder repos = inSweepOrder(manager);
    StarApplier applier = new StarApplier(repos.first());
    IndexEventReadiness readiness = readiness("late-dependency-node");
    IndexEventTailer tailer = startingTailer(manager, applier, readiness);
    tailer.start();
    try {
      publishRef(manager, repos.first(), STAR_1);
      // The sweep has listed the change's repository as unchanged when the change is created.
      applier.onMissing = once(() -> publishRef(manager, repos.second(), CHANGE_1));

      tailer.runBackgroundSweep();

      assertTrue(readiness.isReady());
      assertEquals(List.of("no change 1", "change 1", "star 1"), applier.applied);
      assertAtHead(manager, repos.first());
      assertAtHead(manager, repos.second());
    } finally {
      tailer.stop();
    }
  }

  @Test
  void sweepDoesNotChaseEntriesPublishedDuringItAndFailsOnOnesThatStayUnresolved()
      throws Exception {
    WalGitRepositoryManager manager = sweptManager(new FileObjectStore(storagePath));
    InSweepOrder repos = inSweepOrder(manager);
    StarApplier applier = new StarApplier(repos.first());
    IndexEventReadiness readiness = readiness("chasing-node");
    IndexEventTailer tailer = startingTailer(manager, applier, readiness);
    tailer.start();
    try {
      publishRef(manager, repos.second(), CHANGE_1);
      publishRef(manager, repos.first(), STAR_1);
      long starred = manager.manifestStore(repos.first()).read().getHeadSeq();
      // While the sweep replays the change's repository, a star arrives for a change that no
      // repository holds.
      applier.onChange = once(() -> publishRef(manager, repos.first(), STAR_2));

      tailer.runBackgroundSweep();

      assertTrue(readiness.isReady(), "the entry this sweep failed on was resolved");
      assertEquals(List.of("no change 1", "change 1", "star 1"), applier.applied);
      assertEquals(starred, cursor(manager, repos.first()), "the later entry was left alone");
      assertTrue(manager.manifestStore(repos.first()).read().getHeadSeq() > starred);

      // The next sweep meets it, and what stays unresolved after the retry fails the sweep.
      applier.applied.clear();
      tailer.runBackgroundSweep();
      assertFalse(readiness.isReady());
      assertTrue(Files.notExists(readiness.markerPath()));
      assertEquals(List.of("no change 2", "no change 2"), applier.applied);
      assertEquals(starred, cursor(manager, repos.first()), "the cursor waits at the failed entry");

      // Until its change exists.
      applier.applied.clear();
      publishRef(manager, repos.second(), CHANGE_2);
      tailer.runBackgroundSweep();
      assertTrue(readiness.isReady());
      assertEquals(List.of("no change 2", "change 2", "star 2"), applier.applied);
      assertAtHead(manager, repos.first());
    } finally {
      tailer.stop();
    }
  }

  private static final String CHANGE_1 = "refs/changes/01/1/meta";
  private static final String CHANGE_2 = "refs/changes/02/2/meta";
  private static final String STAR_1 = "refs/starred-changes/01/1/1000001";
  private static final String STAR_2 = "refs/starred-changes/02/2/1000001";
  private static final String LISTINGS = "LIST-versions manifests/";

  /** Two repositories, named in the order a sweep replays them. */
  private record InSweepOrder(Project.NameKey first, Project.NameKey second) {}

  private static InSweepOrder inSweepOrder(WalGitRepositoryManager manager) throws Exception {
    Project.NameKey one = Project.nameKey("sweep/one");
    Project.NameKey two = Project.nameKey("sweep/two");
    manager.createRepository(one).close();
    manager.createRepository(two).close();
    RepositoryId oneId = manager.catalog().resolve(one, true).orElseThrow().id();
    RepositoryId twoId = manager.catalog().resolve(two, true).orElseThrow().id();
    return oneId.compareTo(twoId) < 0 ? new InSweepOrder(one, two) : new InSweepOrder(two, one);
  }

  /** A node that sweeps only when the test says so. */
  private WalGitRepositoryManager sweptManager(ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "indexPollInterval", "1 hour");
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    Path site = storagePath.resolveSibling(storagePath.getFileName() + "-site");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, site),
        new StorageLayout(store, site.resolve("cache"), site.resolve("cursors"), ""));
  }

  private static long cursor(WalGitRepositoryManager manager, Project.NameKey project)
      throws IOException {
    return new IndexCursorStore(manager.manifestStore(project).indexCursorPath())
        .read()
        .getSequence();
  }

  private static void assertAtHead(WalGitRepositoryManager manager, Project.NameKey project)
      throws IOException {
    assertEquals(manager.manifestStore(project).read().getHeadSeq(), cursor(manager, project));
  }

  @FunctionalInterface
  private interface Publication {
    void run() throws Exception;
  }

  private static Runnable once(Publication publication) {
    AtomicBoolean done = new AtomicBoolean();
    return () -> {
      if (done.compareAndSet(false, true)) {
        try {
          publication.run();
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      }
    };
  }

  /**
   * Stands in for the change index: a star in the users repository is applied only once the
   * change's own repository has been replayed, as in {@code GerritIndexEventApplier}.
   */
  private static final class StarApplier implements IndexEventApplier {
    private final Project.NameKey users;
    private final java.util.Set<Integer> indexed =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    final List<String> applied = new CopyOnWriteArrayList<>();
    volatile Runnable onMissing = () -> {};
    volatile Runnable onChange = () -> {};

    StarApplier(Project.NameKey users) {
      this.users = users;
    }

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      for (var update : transaction.getUpdatesList()) {
        if (project.equals(users)) {
          com.google.gerrit.entities.Change.Id starred =
              com.google.gerrit.entities.Change.Id.fromAllUsersRef(update.getName());
          if (starred == null) {
            continue;
          }
          if (!indexed.contains(starred.get())) {
            applied.add("no change " + starred.get());
            onMissing.run();
            throw new IllegalStateException("No such change: " + starred.get());
          }
          applied.add("star " + starred.get());
        } else {
          com.google.gerrit.entities.Change.Id change =
              com.google.gerrit.entities.Change.Id.fromRef(update.getName());
          if (change != null) {
            indexed.add(change.get());
            applied.add("change " + change.get());
            onChange.run();
          }
        }
      }
    }
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
