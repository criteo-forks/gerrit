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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Suppliers;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.EventGsonProvider;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import dev.walgerrit.ChangeMergedReconstruction.Fired;
import dev.walgerrit.ChangeMergedReconstruction.Merge;
import dev.walgerrit.ChangeMergedReconstruction.RefChange;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.OwedMerge;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the log's entries say about merges and their events, with Gerrit's NoteDb stood in for. */
class GerritMergeEventsTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/merged");
  private static final Change.Id CHANGE = Change.id(2061502);
  private static final int PATCH_SET = 2;
  private static final String BRANCH = "refs/heads/master";
  private static final ObjectId PATCH_SET_COMMIT = id('a');
  private static final ObjectId META = id('b');
  private static final ObjectId TIP = id('c');
  private static final ObjectId OTHER = id('d');

  @TempDir Path root;

  private Function<Merge, Fired> onFire = merge -> Fired.FIRED;

  /**
   * As the real one: a transaction that sets a change's meta ref merged it, by a submit when the
   * same transaction moved the branch.
   */
  private final ChangeMergedReconstruction reconstruction =
      new ChangeMergedReconstruction(null, null, null, null) {
        @Override
        List<Merge> mergesIn(Project.NameKey project, List<RefChange> transaction) {
          List<Merge> merges = new ArrayList<>();
          for (RefChange update : transaction) {
            if (!update.name().endsWith(RefNames.META_SUFFIX)) {
              continue;
            }
            ObjectId tip =
                transaction.stream()
                    .filter(ref -> ref.name().equals(BRANCH))
                    .map(RefChange::newId)
                    .findFirst()
                    .orElse(null);
            merges.add(
                new Merge(
                    Change.Id.fromRef(update.name()),
                    PATCH_SET,
                    PATCH_SET_COMMIT,
                    update.newId(),
                    tip,
                    tip == null ? null : Account.id(1000),
                    Instant.EPOCH));
          }
          return merges;
        }

        @Override
        Fired fire(Project.NameKey project, Merge merge) {
          return onFire.apply(merge);
        }
      };

  @Test
  void aBranchThatAnotherTransactionOfTheEntryMovedIsNotTheRevisionOfTheMerge() throws Exception {
    GerritMergeEvents events = new GerritMergeEvents(reconstruction, journal(node()));
    RefUpdate closedByPush = update(RefNames.changeMetaRef(CHANGE), ObjectId.zeroId(), META);
    RefUpdate unrelated = update(BRANCH, OTHER, TIP);

    // A push closed the change; another request's branch update was published with it.
    OwedMerge grouped =
        only(events.mergedBy(PROJECT, entry(List.of(1, 1), closedByPush, unrelated)));
    assertEquals("", grouped.getNewRevision(), "not a submit's merge: its event is not rebuilt");
    assertEquals(0, grouped.getSubmitter());
    assertEquals(PATCH_SET_COMMIT.name(), grouped.getPatchSetRevision());

    // One transaction did both: a submit.
    OwedMerge submitted =
        only(events.mergedBy(PROJECT, entry(List.of(2), closedByPush, unrelated)));
    assertEquals(TIP.name(), submitted.getNewRevision());
    assertEquals(1000, submitted.getSubmitter());
    assertEquals(META.name(), submitted.getMetaRevision());

    // An entry from before entries said whose update was whose: nothing can be concluded.
    OwedMerge legacy = only(events.mergedBy(PROJECT, entry(List.of(), closedByPush, unrelated)));
    assertEquals("", legacy.getNewRevision());
  }

  @Test
  void onlyTheEventOfTheSameCommitOnTheSameBranchTipSettlesAMerge() throws Exception {
    GerritMergeEvents events = new GerritMergeEvents(reconstruction, journal(node()));
    OwedMerge submitted =
        only(
            events.mergedBy(
                PROJECT,
                entry(
                    List.of(2),
                    update(RefNames.changeMetaRef(CHANGE), ObjectId.zeroId(), META),
                    update(BRANCH, OTHER, TIP))));
    List<OwedMerge> owed = List.of(submitted);

    assertEquals(owed, events.withoutAnnounced(owed, events(created())));
    assertEquals(
        owed,
        events.withoutAnnounced(
            owed, events(merged(CHANGE.get() + 1, PATCH_SET, PATCH_SET_COMMIT, TIP))),
        "another change");
    assertEquals(
        owed,
        events.withoutAnnounced(
            owed, events(merged(CHANGE.get(), PATCH_SET - 1, PATCH_SET_COMMIT, TIP))),
        "another patch set");
    assertEquals(
        owed,
        events.withoutAnnounced(owed, events(merged(CHANGE.get(), PATCH_SET, OTHER, TIP))),
        "another commit under the same patch set number");
    assertEquals(
        owed,
        events.withoutAnnounced(
            owed, events(merged(CHANGE.get(), PATCH_SET, PATCH_SET_COMMIT, OTHER))),
        "merged as another revision");
    assertEquals(owed, events.withoutAnnounced(owed, events("{\"type\":\"change-merged\"}")));
    assertEquals(
        List.of(),
        events.withoutAnnounced(
            owed, events(created(), merged(CHANGE.get(), PATCH_SET, PATCH_SET_COMMIT, TIP))));

    // What a push closed: the revision its event names is not known here.
    OwedMerge closedByPush =
        only(
            events.mergedBy(
                PROJECT,
                entry(
                    List.of(1), update(RefNames.changeMetaRef(CHANGE), ObjectId.zeroId(), META))));
    assertEquals(
        List.of(closedByPush),
        events.withoutAnnounced(
            List.of(closedByPush), events(merged(CHANGE.get(), PATCH_SET, OTHER, OTHER))));
    assertEquals(
        List.of(),
        events.withoutAnnounced(
            List.of(closedByPush),
            events(merged(CHANGE.get(), PATCH_SET, PATCH_SET_COMMIT, OTHER))));
  }

  @Test
  void anEventCountsAsFiredOnlyWhenItReachedTheJournal() throws Exception {
    WalGitRepositoryManager node = node();
    node.createRepository(PROJECT).close();
    ManifestStore store = node.manifestStore(PROJECT);
    WalJournal journal = journal(node);
    GerritMergeEvents events = new GerritMergeEvents(reconstruction, journal);
    OwedMerge owed =
        only(
            events.mergedBy(
                PROJECT,
                entry(
                    List.of(2),
                    update(RefNames.changeMetaRef(CHANGE), ObjectId.zeroId(), META),
                    update(BRANCH, OTHER, TIP))));
    journal.start();
    try {
      // Gerrit logged what went wrong while it built the event and returned.
      onFire = merge -> Fired.FIRED;
      assertThrows(IllegalStateException.class, () -> events.fire(PROJECT, owed));

      // An event, but of another merge.
      onFire =
          merge -> {
            journal.onEvent(mergedEvent(CHANGE.get(), PATCH_SET, PATCH_SET_COMMIT, OTHER));
            return Fired.FIRED;
          };
      assertThrows(IllegalStateException.class, () -> events.fire(PROJECT, owed));
      journal.flush();
      Manifest before = store.read();

      // The event, and what a listener of it fired in turn.
      onFire =
          merge -> {
            assertEquals(CHANGE, merge.change());
            assertEquals(TIP, merge.newRevision());
            assertEquals(Account.id(1000), merge.submitter());
            journal.onEvent(mergedEvent(CHANGE.get(), PATCH_SET, PATCH_SET_COMMIT, TIP));
            journal.onEvent(createdEvent());
            return Fired.FIRED;
          };
      assertEquals(MergeEvents.Delivery.FIRED, events.fire(PROJECT, owed));
      journal.flush();
      List<LogEntry> journaled =
          store.readLogEntriesAfter(
              before.getHeadSeq(), before.getHeadTransactionId(), store.refresh(), 100);
      assertEquals(1, journaled.size());
      assertEquals(
          2,
          journaled.get(0).getEventJsonCount(),
          "the event and what its listeners fired are journaled like any other");
      assertEquals(List.of(), events.withoutAnnounced(List.of(owed), journaled.get(0)));

      onFire = merge -> Fired.GONE;
      assertEquals(MergeEvents.Delivery.GONE, events.fire(PROJECT, owed));
      onFire = merge -> Fired.NOT_BY_SUBMIT;
      assertEquals(MergeEvents.Delivery.LOST, events.fire(PROJECT, owed));
    } finally {
      journal.stop();
    }
  }

  private WalGitRepositoryManager node() {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("wal").toString());
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve("site")));
  }

  private static WalJournal journal(WalGitRepositoryManager node) {
    return new WalJournal(
        node,
        new AllProjectsName("All-Projects"),
        new AllUsersName("All-Users"),
        GerritRuntime.DAEMON);
  }

  private static OwedMerge only(List<OwedMerge> merges) {
    assertEquals(1, merges.size());
    return merges.get(0);
  }

  private static RefUpdate update(String name, ObjectId oldId, ObjectId newId) {
    return RefUpdate.newBuilder()
        .setName(name)
        .setOldObjectId(oldId.name())
        .setNewObjectId(newId.name())
        .build();
  }

  private static LogEntry entry(List<Integer> members, RefUpdate... updates) {
    return LogEntry.newBuilder()
        .setSeq(7)
        .setKind(LogEntry.Kind.REF_UPDATE)
        .setWriterProcess("the-process-that-merged")
        .setRefTransaction(
            RefTransaction.newBuilder().addAllUpdates(List.of(updates)).addAllMembers(members))
        .build();
  }

  private static LogEntry events(String... json) {
    return LogEntry.newBuilder()
        .setSeq(8)
        .setKind(LogEntry.Kind.EVENT)
        .addAllEventJson(List.of(json))
        .build();
  }

  /** A change-merged event as the journal writes it. */
  private static String merged(int change, int patchSet, ObjectId commit, ObjectId newRevision) {
    String json =
        new EventGsonProvider().get().toJson(mergedEvent(change, patchSet, commit, newRevision));
    assertTrue(json.contains("\"type\":\"change-merged\""), json);
    return json;
  }

  private static ChangeMergedEvent mergedEvent(
      int number, int patchSet, ObjectId commit, ObjectId newRevision) {
    ChangeMergedEvent event =
        new ChangeMergedEvent(
            new Change(
                Change.key("I" + "0".repeat(40)),
                Change.id(number),
                Account.id(1000),
                BranchNameKey.create(PROJECT, "master"),
                Instant.EPOCH));
    ChangeAttribute change = new ChangeAttribute();
    change.project = PROJECT.get();
    change.number = number;
    event.change = Suppliers.ofInstance(change);
    PatchSetAttribute merged = new PatchSetAttribute();
    merged.number = patchSet;
    merged.revision = commit.name();
    event.patchSet = Suppliers.ofInstance(merged);
    event.newRev = newRevision.name();
    return event;
  }

  private static String created() {
    return new EventGsonProvider().get().toJson(createdEvent());
  }

  private static ProjectCreatedEvent createdEvent() {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.projectName = PROJECT.get();
    event.headName = "refs/heads/master";
    return event;
  }

  private static ObjectId id(char digit) {
    return ObjectId.fromString(String.valueOf(digit).repeat(40));
  }
}
