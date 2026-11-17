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

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.testing.TestActionRefUpdateContext.testRefAction;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.HashtagsInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventGsonProvider;
import com.google.gerrit.server.events.EventListener;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import dev.walgerrit.ChangeMergedReconstruction.Fired;
import dev.walgerrit.ChangeMergedReconstruction.Merge;
import dev.walgerrit.ChangeMergedReconstruction.RefChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * WalGerrit fires the change-merged event of a submit again when the process that submitted ended
 * before the event reached the log. It rebuilds the event from the ref transaction the submit
 * committed. These tests take the transactions of real submits, as Gerrit hands them to the ref
 * database, and hold the event that is rebuilt from them against the one the submit fired.
 */
@NoHttpd
public class ChangeMergedReconstructionIT extends AbstractDaemonTest {
  @Inject private ExtensionRegistry extensionRegistry;
  @Inject private DynamicSet<EventListener> eventListeners;
  @Inject private ProjectOperations projectOperations;
  @Inject private ChangeMergedReconstruction reconstruction;

  private final Gson gson = new EventGsonProvider().get();

  /** The ref transactions of {@link #project}, in the order they were committed. */
  private final List<List<RefChange>> transactions = new ArrayList<>();

  /** The change-merged events that were fired, as the journal writes them. */
  private final List<JsonObject> mergedEvents = new ArrayList<>();

  private Registration transactionListener;
  private RegistrationHandle eventListener;

  @Before
  public void listen() {
    transactionListener =
        extensionRegistry
            .newRegistration()
            .add(
                (GitBatchRefUpdateListener)
                    event -> {
                      if (event.getProjectName().equals(project.get())) {
                        transactions.add(transaction(event.getUpdatedRefs()));
                      }
                    });
    eventListener =
        eventListeners.add(
            "gerrit",
            (Event event) -> {
              if (event instanceof ChangeMergedEvent) {
                JsonObject json = gson.toJsonTree(event).getAsJsonObject();
                json.remove("eventCreatedOn");
                mergedEvents.add(json);
              }
            });
  }

  @After
  public void stopListening() {
    transactionListener.close();
    eventListener.remove();
  }

  @Test
  public void eventOfASubmitThatMergesIsRebuiltFromItsTransaction() throws Exception {
    assertEventOfASubmitIsRebuiltFromItsTransaction();
  }

  @Test
  @TestProjectInput(submitType = SubmitType.FAST_FORWARD_ONLY)
  public void eventOfASubmitThatFastForwardsIsRebuiltFromItsTransaction() throws Exception {
    assertEventOfASubmitIsRebuiltFromItsTransaction();
  }

  @Test
  @TestProjectInput(submitType = SubmitType.CHERRY_PICK)
  public void eventOfASubmitThatCherryPicksIsRebuiltFromItsTransaction() throws Exception {
    assertEventOfASubmitIsRebuiltFromItsTransaction();
  }

  @Test
  @TestProjectInput(submitType = SubmitType.REBASE_ALWAYS)
  public void eventOfASubmitThatRebasesIsRebuiltFromItsTransaction() throws Exception {
    assertEventOfASubmitIsRebuiltFromItsTransaction();
  }

  private void assertEventOfASubmitIsRebuiltFromItsTransaction() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    approve(r.getChangeId());
    transactions.clear();

    gApi.changes().id(project.get(), id.get()).current().submit();

    // What the submit did to the repository is one transaction: the branch and the change.
    List<RefChange> submit = transactionOf(id);
    ChangeInfo merged = gApi.changes().id(project.get(), id.get()).get();
    ObjectId branchTip = branchTip("refs/heads/master");
    assertThat(submit.stream().map(RefChange::name)).contains("refs/heads/master");

    Merge merge = onlyMergeIn(submit);
    assertThat(merge.change()).isEqualTo(id);
    assertThat(merge.patchSet()).isEqualTo(merged.currentRevisionNumber);
    assertThat(merge.patchSetRevision().name()).isEqualTo(merged.currentRevision);
    assertThat(merge.newRevision()).isEqualTo(branchTip);
    assertThat(merge.submitter()).isEqualTo(admin.id());
    assertThat(merge.bySubmit()).isTrue();

    // Fired again, it is the event the submit fired.
    assertThat(mergedEvents).hasSize(1);
    JsonObject fired = mergedEvents.get(0);
    assertThat(fired.get("newRev").getAsString()).isEqualTo(branchTip.name());
    mergedEvents.clear();
    assertThat(reconstruction.fire(project, merge)).isEqualTo(Fired.FIRED);
    assertThat(mergedEvents).containsExactly(fired);
  }

  @Test
  public void everyChangeOfASubmitThatMergesSeveralIntoOneBranchNamesTheTipOfTheBranch()
      throws Exception {
    PushOneCommit.Result parent = createChange();
    PushOneCommit.Result child = createChange();
    approve(parent.getChangeId());
    approve(child.getChangeId());
    transactions.clear();

    // Submits its parent with it.
    gApi.changes().id(child.getChangeId()).current().submit();

    List<RefChange> submit = transactionOf(child.getChange().getId());
    List<Merge> merges = reconstruction.mergesIn(project, submit);
    assertThat(merges.stream().map(Merge::change))
        .containsExactly(parent.getChange().getId(), child.getChange().getId());
    ObjectId branchTip = branchTip("refs/heads/master");
    for (Merge merge : merges) {
      assertThat(merge.newRevision()).isEqualTo(branchTip);
    }

    ImmutableList<JsonObject> fired = ImmutableList.copyOf(mergedEvents);
    assertThat(fired).hasSize(2);
    mergedEvents.clear();
    for (Merge merge : merges) {
      assertThat(reconstruction.fire(project, merge)).isEqualTo(Fired.FIRED);
    }
    assertThat(mergedEvents).containsExactlyElementsIn(fired);
  }

  @Test
  public void aTransactionThatDoesNotMergeOwesNoEvent() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();

    // Created, voted on, commented on, a patch set added.
    approve(r.getChangeId());
    gApi.changes().id(project.get(), id.get()).current().review(new ReviewInput().message("x"));
    amendChange(r.getChangeId()).assertOkStatus();
    approve(r.getChangeId());
    gApi.changes().id(project.get(), id.get()).current().submit();
    // And, once merged, commented on again.
    gApi.changes().id(project.get(), id.get()).current().review(new ReviewInput().message("y"));

    int merges = 0;
    for (List<RefChange> transaction : transactions) {
      merges += reconstruction.mergesIn(project, transaction).size();
    }
    assertThat(transactions.size()).isAtLeast(6);
    assertThat(merges).isEqualTo(1);
  }

  @Test
  public void eventOfAChangeThatAPushClosedIsNotRebuilt() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.PUSH).ref("refs/heads/*").group(adminGroupUuid()))
        .update();
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    transactions.clear();

    pushHead(testRepo, "refs/heads/master");

    assertThat(gApi.changes().id(project.get(), id.get()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
    assertThat(mergedEvents).hasSize(1);
    // The branch moved in the push's transaction, the change was closed in a later one. The
    // revision the event names is in neither the change's history nor that later transaction.
    List<RefChange> close = transactionOf(id);
    assertThat(close.stream().map(RefChange::name)).doesNotContain("refs/heads/master");
    Merge merge = onlyMergeIn(close);
    assertThat(merge.bySubmit()).isFalse();
    assertThat(merge.patchSetRevision()).isEqualTo(r.getCommit());

    mergedEvents.clear();
    assertThat(reconstruction.fire(project, merge)).isEqualTo(Fired.NOT_BY_SUBMIT);
    assertThat(mergedEvents).isEmpty();

    // Nor when the transaction of another request was published with it, as the log's entries
    // can hold several: an entry says which update was whose, and each is looked at by itself.
    assertThat(close).hasSize(1);
  }

  @Test
  public void eventNamesTheMergeAsItWasAndTheChangeAsItIsWhenFired() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    approve(r.getChangeId());
    transactions.clear();
    gApi.changes().id(project.get(), id.get()).current().submit();
    Merge merge = onlyMergeIn(transactionOf(id));
    JsonObject fired = mergedEvents.get(0);
    mergedEvents.clear();

    // The change goes on after the merge.
    gApi.changes().id(project.get(), id.get()).current().review(new ReviewInput().message("x"));
    gApi.changes().id(project.get(), id.get()).setHashtags(new HashtagsInput(Set.of("later")));

    assertThat(reconstruction.fire(project, merge)).isEqualTo(Fired.FIRED);
    assertThat(mergedEvents).hasSize(1);
    JsonObject again = mergedEvents.get(0);
    // The merge: what was merged, as what, by whom.
    assertThat(again.get("newRev")).isEqualTo(fired.get("newRev"));
    assertThat(again.get("patchSet")).isEqualTo(fired.get("patchSet"));
    assertThat(again.get("submitter")).isEqualTo(fired.get("submitter"));
    assertThat(again.get("changeKey")).isEqualTo(fired.get("changeKey"));
    assertThat(again.get("refName")).isEqualTo(fired.get("refName"));
    JsonObject change = again.getAsJsonObject("change");
    assertThat(change.get("number")).isEqualTo(fired.getAsJsonObject("change").get("number"));
    assertThat(change.get("status").getAsString()).isEqualTo("MERGED");
    // The rest of the change: read when the event is fired, as Gerrit does for every event.
    assertThat(change.getAsJsonArray("hashtags").toString()).contains("later");
    assertThat(fired.getAsJsonObject("change").has("hashtags")).isFalse();

    // A record of another commit merged under that patch set number is not this change's merge.
    mergedEvents.clear();
    Merge ofAnotherCommit =
        new Merge(
            merge.change(),
            merge.patchSet(),
            merge.metaRevision(),
            merge.metaRevision(),
            merge.newRevision(),
            merge.submitter(),
            merge.mergedAt());
    assertThat(reconstruction.fire(project, ofAnotherCommit)).isEqualTo(Fired.GONE);
    assertThat(mergedEvents).isEmpty();
  }

  @Test
  public void noEventIsFiredForAMergeThatTheChangesHistoryNoLongerHolds() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    approve(r.getChangeId());
    transactions.clear();
    gApi.changes().id(project.get(), id.get()).current().submit();
    List<RefChange> submit = transactionOf(id);
    Merge merge = onlyMergeIn(submit);
    mergedEvents.clear();

    // The repository is put back to before the submit, as a restored store is.
    RefChange meta =
        submit.stream()
            .filter(ref -> ref.name().equals(RefNames.changeMetaRef(id)))
            .findFirst()
            .orElseThrow();
    try (Repository repository = repoManager.openRepository(project)) {
      RefUpdate update = repository.updateRef(meta.name());
      update.setNewObjectId(meta.oldId());
      update.setForceUpdate(true);
      testRefAction(() -> assertThat(update.update()).isEqualTo(RefUpdate.Result.FORCED));
    }
    assertThat(gApi.changes().id(project.get(), id.get()).get().status).isEqualTo(ChangeStatus.NEW);

    assertThat(reconstruction.fire(project, merge)).isEqualTo(Fired.GONE);
    assertThat(mergedEvents).isEmpty();

    // Merged once more, by another submission: still not the merge that was put back.
    gApi.changes().id(project.get(), id.get()).index();
    gApi.changes().id(project.get(), id.get()).current().submit();
    assertThat(gApi.changes().id(project.get(), id.get()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
    mergedEvents.clear();
    assertThat(reconstruction.fire(project, merge)).isEqualTo(Fired.GONE);
    assertThat(mergedEvents).isEmpty();
  }

  private Merge onlyMergeIn(List<RefChange> transaction) throws Exception {
    List<Merge> merges = reconstruction.mergesIn(project, transaction);
    assertThat(merges).hasSize(1);
    return merges.get(0);
  }

  /** The last transaction that updated the meta ref of {@code id}. */
  private List<RefChange> transactionOf(Change.Id id) {
    String meta = RefNames.changeMetaRef(id);
    for (int i = transactions.size() - 1; i >= 0; i--) {
      if (transactions.get(i).stream().anyMatch(ref -> ref.name().equals(meta))) {
        return transactions.get(i);
      }
    }
    throw new AssertionError("no transaction updated " + meta + ": " + transactions);
  }

  private static List<RefChange> transaction(Set<GitBatchRefUpdateListener.UpdatedRef> refs) {
    List<RefChange> transaction = new ArrayList<>();
    for (GitBatchRefUpdateListener.UpdatedRef ref : refs) {
      transaction.add(
          new RefChange(
              ref.getRefName(),
              ObjectId.fromString(ref.getOldObjectId()),
              ObjectId.fromString(ref.getNewObjectId())));
    }
    return transaction;
  }

  private ObjectId branchTip(String branch) throws Exception {
    try (Repository repository = repoManager.openRepository(project)) {
      return repository.exactRef(branch).getObjectId();
    }
  }
}
