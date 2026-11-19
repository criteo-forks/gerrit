// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.walgerrit.proto.StorageProto.IndexUpdate;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class ChangeReplayBatchTest {
  private static final String FIRST = "refs/changes/01/1/meta";
  private static final String SECOND = "refs/changes/02/2/meta";
  private static final String TIP = "1111111111111111111111111111111111111111";

  private static RefUpdate ref(String name) {
    return RefUpdate.newBuilder().setName(name).setNewObjectId(TIP).build();
  }

  private static LogEntry entry(RefUpdate... refs) {
    return LogEntry.newBuilder()
        .setKind(LogEntry.Kind.REF_UPDATE)
        .setRefTransaction(RefTransaction.newBuilder().addAllUpdates(List.of(refs)))
        .build();
  }

  @Test
  void onlyDistinctDocumentsInTheContiguousPrefixAreEligible() {
    var head = entry(ref(FIRST), ref("refs/changes/01/1/2"));
    var pack = LogEntry.newBuilder().setKind(LogEntry.Kind.PACK).build();
    var second = entry(ref(SECOND), ref("refs/changes/01/1/robot-comments"));
    for (LogEntry boundary :
        List.of(
            entry(ref("refs/changes/03/3/meta"), ref("refs/heads/main")),
            entry(ref("refs/meta/config")),
            entry(ref("refs/changes/01/1/9999999999999999999999")),
            entry(ref(SECOND).toBuilder().setNewObjectId(ObjectId.zeroId().name()).build()),
            entry(ref(SECOND).toBuilder().setNewSymbolicTarget("refs/heads/main").build()),
            entry(ref(SECOND)).toBuilder().addEventJson("event").build(),
            entry(ref(SECOND)).toBuilder().setIndexUpdate(IndexUpdate.getDefaultInstance()).build(),
            LogEntry.newBuilder().setKind(LogEntry.Kind.FENCE).build(),
            LogEntry.newBuilder().setKind(LogEntry.Kind.REF_UPDATE).build())) {
      var batch = ChangeReplayBatch.plan(List.of(head, pack, second, head, boundary, second), 0);
      assertEquals(4, batch.end());
      assertEquals(3, batch.transactions());
      assertEquals(Set.of(1, 2), batch.changes());
      assertNull(ChangeReplayBatch.plan(List.of(boundary, second), 0));
    }
    assertNull(ChangeReplayBatch.plan(List.of(pack, entry(ref("refs/changes/01/1/2"))), 0));
    assertNull(ChangeReplayBatch.plan(List.of(head), 0));
    var many = Collections.nCopies(200, head);
    assertEquals(5 + ChangeReplayBatch.MAX_ENTRIES, ChangeReplayBatch.plan(many, 5).end());
  }
}
