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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.IndexUpdate;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Events travel in the WAL as EVENT entries and reach the tailers of the other nodes only. */
class EventLogReplayTest {
  @TempDir Path storagePath;

  @Test
  void indexUpdatesReachForeignNodesExceptChangesTheirRefUpdatesAlreadyReindex() throws Exception {
    WalGitRepositoryManager manager =
        new WalGitRepositoryManager(new WalGitConfiguration(BackendType.LOCAL, storagePath));
    Project.NameKey project = Project.nameKey("platform/index");
    manager.createRepository(project).close();
    ManifestStore store = manager.storage().manifestStore(project);
    RecordingReindexer home = new RecordingReindexer();
    IndexEventTailer homeTailer = tailer(manager, home, storagePath.resolve("home-READY"), false);
    homeTailer.catchUp(project); // Seed the cursor past the repository's creation.
    home.transactions = 0;

    // One sweep's worth of entries: a ref update to change 7 and a journal entry naming 7 and 9.
    try (org.eclipse.jgit.lib.Repository repository = manager.openRepository(project)) {
      org.eclipse.jgit.lib.RefUpdate update = repository.updateRef("refs/changes/07/7/meta");
      update.setNewObjectId(WalGitRepositoryManagerTest.insertCommit(repository, "change 7"));
      assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW, update.update());
    }
    Manifest before = store.read();
    store.publishJournal(
        List.of(), IndexUpdate.newBuilder().addChanges(7).addChanges(9).addAccounts(3).build());
    LogEntry entry =
        store.readLogEntriesAfter(before.getHeadSeq(), before.getHeadTransactionId(), store.read(), 10)
            .get(0);
    assertEquals(LogEntry.Kind.INDEX, entry.getKind());
    assertEquals(List.of(7, 9), entry.getIndexUpdate().getChangesList());

    // The node that reindexed skips its own entry; the ref update is applied everywhere.
    homeTailer.catchUp(project);
    assertEquals(1, home.transactions);
    assertTrue(home.updates.isEmpty());

    // Another node reindexes the documents, minus change 7, which its ref update already covers.
    WalGitRepositoryManager other =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(localConfig(), storagePath.resolve("other-site")));
    RecordingReindexer foreign = new RecordingReindexer();
    IndexEventTailer foreignTailer = tailer(other, foreign, storagePath.resolve("other-READY"), true);
    foreignTailer.catchUp(project);
    assertEquals(2, foreign.transactions, "the repository's creation and change 7");
    assertEquals(1, foreign.updates.size());
    assertEquals(List.of(7, 9), foreign.updates.get(0).getChangesList());
    assertEquals(Set.of(7), foreign.skipped.get(0));
    foreignTailer.catchUp(project);
    assertEquals(1, foreign.updates.size(), "an acknowledged entry is not replayed again");

    // A batch with both events and reindexed documents is one EVENT entry carrying both.
    before = store.read();
    store.publishJournal(
        List.of("{\"type\":\"comment-added\"}"), IndexUpdate.newBuilder().addChanges(11).build());
    entry =
        store.readLogEntriesAfter(before.getHeadSeq(), before.getHeadTransactionId(), store.read(), 10)
            .get(0);
    assertEquals(LogEntry.Kind.EVENT, entry.getKind());
    assertEquals(List.of(11), entry.getIndexUpdate().getChangesList());
    foreignTailer.catchUp(project);
    assertEquals(List.of(11), foreign.updates.get(1).getChangesList());
  }

  private IndexEventTailer tailer(
      WalGitRepositoryManager manager, IndexEventApplier applier, Path marker, boolean foreign) {
    IndexEventTailer tailer =
        new IndexEventTailer(
            manager, applier, GerritRuntime.DAEMON, "lucene", new org.eclipse.jgit.lib.Config(),
            new IndexEventReadiness(marker), null, EventReplayer.NONE);
    if (foreign) {
      tailer.foreignWriter(writer -> true);
    }
    return tailer;
  }

  private static final class RecordingReindexer implements IndexEventApplier {
    int transactions;
    final List<IndexUpdate> updates = new ArrayList<>();
    final List<Set<Integer>> skipped = new ArrayList<>();

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      transactions++;
    }

    @Override
    public void reindex(Project.NameKey project, IndexUpdate update, Set<Integer> alreadyReindexed) {
      updates.add(update);
      skipped.add(Set.copyOf(alreadyReindexed));
    }
  }

  @Test
  void eventEntriesAreReplayedOnceOnForeignNodesAndSkippedAtHome() throws Exception {
    WalGitRepositoryManager manager =
        new WalGitRepositoryManager(new WalGitConfiguration(BackendType.LOCAL, storagePath));
    Project.NameKey project = Project.nameKey("platform/events");
    manager.createRepository(project).close();
    ManifestStore store = manager.storage().manifestStore(project);
    List<LogEntry> replayed = new ArrayList<>();
    IndexEventTailer home =
        new IndexEventTailer(
            manager, (p, t) -> {}, GerritRuntime.DAEMON, "lucene", new org.eclipse.jgit.lib.Config(),
            new IndexEventReadiness(storagePath.resolve("home-READY")), null,
            (p, entry) -> replayed.add(entry));
    home.catchUp(project);
    long headBefore = store.read().getHeadSeq();
    String transactionBefore = store.read().getHeadTransactionId();

    store.publishEvents(List.of("{\"type\":\"ref-updated\"}", "{\"type\":\"patchset-created\"}"));
    store.publishEvents(List.of());

    assertEquals(headBefore + 1, store.read().getHeadSeq(), "one entry per batch, none for an empty batch");
    LogEntry entry =
        store
            .readLogEntriesAfter(headBefore, transactionBefore, store.read(), 10)
            .get(0);
    assertEquals(LogEntry.Kind.EVENT, entry.getKind());
    assertEquals(2, entry.getEventJsonCount());
    assertTrue(ManifestStore.writtenOnThisHost(entry.getWriter()));

    // The node that fired the events already delivered them: its tailer skips the entry but
    // still advances its cursor past it.
    home.catchUp(project);
    assertTrue(replayed.isEmpty());
    IndexCursor cursor = new IndexCursorStore(store.indexCursorPath()).read();
    assertEquals(store.read().getHeadSeq(), cursor.getSequence());

    // Another node sees the entry as foreign and replays it exactly once.
    WalGitRepositoryManager other =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(localConfig(), storagePath.resolve("other-site")));
    List<LogEntry> replayedElsewhere = new ArrayList<>();
    IndexEventTailer foreign =
        new IndexEventTailer(
            other, (p, t) -> {}, GerritRuntime.DAEMON, "lucene", new org.eclipse.jgit.lib.Config(),
            new IndexEventReadiness(storagePath.resolve("other-READY")), null,
            (p, e) -> replayedElsewhere.add(e));
    foreign.foreignWriter(writer -> true);
    foreign.catchUp(project);
    assertEquals(1, replayedElsewhere.size());
    assertEquals(2, replayedElsewhere.get(0).getEventJsonCount());
    foreign.catchUp(project);
    assertEquals(1, replayedElsewhere.size(), "an acknowledged entry is not replayed again");
  }

  private org.eclipse.jgit.lib.Config localConfig() {
    org.eclipse.jgit.lib.Config config = new org.eclipse.jgit.lib.Config();
    config.setString("walgerrit", null, "storagePath", storagePath.toString());
    return config;
  }
}
