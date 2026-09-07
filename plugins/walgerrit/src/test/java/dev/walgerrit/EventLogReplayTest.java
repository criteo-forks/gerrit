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
import dev.walgerrit.proto.StorageProto.LogEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Events travel in the WAL as EVENT entries and reach the tailers of the other nodes only. */
class EventLogReplayTest {
  @TempDir Path storagePath;

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
