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

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.index.change.TestChangeIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchChangeException;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

/** A node replays a repository's WAL entries after later entries already changed the repository. */
class GerritIndexEventApplierChangesTest {
  static final Project.NameKey PROJECT = Project.nameKey("criteo/app");
  static final Change.Id CHANGE = Change.id(2061502);
  static final ObjectId PATCH_SET_1 =
      ObjectId.fromString("1111111111111111111111111111111111111111");
  static final ObjectId PATCH_SET_2 =
      ObjectId.fromString("2222222222222222222222222222222222222222");

  /** Changes whose meta ref the repository holds now. */
  final Set<Change.Id> stored = new HashSet<>();

  /** This node's change documents. */
  final Set<Change.Id> documents = new HashSet<>();

  Change.Id loaded;

  final ChangeNotes.Factory notes =
      new ChangeNotes.Factory(null, null, null) {
        @Override
        public ChangeNotes createChecked(Project.NameKey project, Change.Id id) {
          if (!stored.contains(id)) {
            throw new NoSuchChangeException(id);
          }
          loaded = id;
          return null;
        }
      };

  final TestChangeIndexer indexer =
      new TestChangeIndexer() {
        @Override
        public void index(ChangeNotes ignored) {
          documents.add(loaded);
        }

        @Override
        public void index(Project.NameKey project, Change.Id id) {
          // Like ChangeIndexer, which loads the notes through ChangeData.
          if (!stored.contains(id)) {
            throw new StorageException(
                "Unable to load change " + id, new NoSuchChangeException(id));
          }
          documents.add(id);
        }

        @Override
        public void delete(Change.Id id) {
          documents.remove(id);
        }
      };

  final GerritIndexEventApplier applier =
      new GerritIndexEventApplier(
          null,
          new AllUsersName("All-Users"),
          indexer,
          null,
          null,
          null,
          null,
          notes,
          null,
          null,
          null,
          null);

  void replay(ObjectId oldMeta, ObjectId newMeta) throws Exception {
    RefTransaction transaction =
        RefTransaction.newBuilder()
            .addUpdates(
                RefUpdate.newBuilder()
                    .setName(RefNames.changeMetaRef(CHANGE))
                    .setOldObjectId(oldMeta.name())
                    .setNewObjectId(newMeta.name()))
            .build();
    var method =
        GerritIndexEventApplier.class.getDeclaredMethod(
            "applyInContext", Project.NameKey.class, RefTransaction.class);
    method.setAccessible(true);
    try {
      method.invoke(applier, PROJECT, transaction);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception failure) {
        throw failure;
      }
      throw e;
    }
  }

  @Test
  void replayingAnUpdateOfAChangeDeletedSinceRemovesItsDocument() throws Exception {
    documents.add(CHANGE);

    replay(PATCH_SET_1, PATCH_SET_2);
    replay(PATCH_SET_2, ObjectId.zeroId());

    assertEquals(Set.of(), documents);
  }

  @Test
  void replayingAnUpdateOfAStoredChangeIndexesIt() throws Exception {
    stored.add(CHANGE);

    replay(PATCH_SET_1, PATCH_SET_2);

    assertEquals(Set.of(CHANGE), documents);
  }
}
