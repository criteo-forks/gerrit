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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.IndexUpdate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JournalBufferTest {
  @Test
  void groupsByProjectInArrivalOrderAndDrainsEverything() {
    JournalBuffer buffer = new JournalBuffer();
    Project.NameKey a = Project.nameKey("a");
    Project.NameKey b = Project.nameKey("b");

    assertEquals(1, buffer.add(a, "a1"));
    assertEquals(1, buffer.add(b, "b1"));
    assertEquals(2, buffer.add(a, "a2"));

    Map<Project.NameKey, JournalBuffer.Batch> drained = buffer.drain();
    assertEquals(List.of(a, b), List.copyOf(drained.keySet()));
    assertEquals(List.of("a1", "a2"), drained.get(a).events());
    assertNull(drained.get(a).index());
    assertEquals(List.of("b1"), drained.get(b).events());
    assertTrue(buffer.isEmpty());
    assertTrue(buffer.drain().isEmpty());
  }

  @Test
  void collectsReindexedDocumentsOncePerBatchAndRequeuesAfterAFailure() {
    JournalBuffer buffer = new JournalBuffer();
    Project.NameKey project = Project.nameKey("p");
    Project.NameKey allUsers = Project.nameKey("All-Users");

    buffer.addChange(project, 7);
    buffer.addChange(project, 9);
    buffer.addChange(project, 7);
    buffer.addAccount(allUsers, 1000042);
    buffer.addGroup(allUsers, "uuid-1");

    Map<Project.NameKey, JournalBuffer.Batch> drained = buffer.drain();
    IndexUpdate changes = drained.get(project).index();
    assertEquals(List.of(7, 9), changes.getChangesList(), "a document reindexed twice is journaled once");
    assertTrue(drained.get(project).events().isEmpty());
    IndexUpdate users = drained.get(allUsers).index();
    assertEquals(List.of(1000042), users.getAccountsList());
    assertEquals(List.of("uuid-1"), users.getGroupsList());
    assertTrue(buffer.isEmpty());

    buffer.requeue(project, changes);
    buffer.addChange(project, 11);
    assertEquals(List.of(7, 9, 11), buffer.drain().get(project).index().getChangesList());
  }
}
