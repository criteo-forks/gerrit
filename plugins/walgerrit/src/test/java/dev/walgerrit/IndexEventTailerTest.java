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

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    assertEquals(
        store.logKeyForSequence(store.read(), cursor.getSequence()), cursor.getLogKey());
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

  private static final class RecordingApplier implements IndexEventApplier {
    private final List<RefTransaction> transactions = new ArrayList<>();

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      transactions.add(transaction);
    }
  }
}
