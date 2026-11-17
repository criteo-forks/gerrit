// Copyright (C) 2026 The Android Open Source Project
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

package com.google.gerrit.acceptance.server.change;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.google.gerrit.testing.TestActionRefUpdateContext.testRefAction;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationInfo;
import com.google.gerrit.server.git.validators.CommitValidationInfoListener;
import com.google.gerrit.server.git.validators.OnSubmitValidationListener;
import com.google.gerrit.server.restapi.change.Submit;
import com.google.gerrit.server.update.RetryListener;
import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

/** Updates of a change that another update of the same change overtakes. */
public class ConcurrentChangeUpdateIT extends AbstractDaemonTest {
  @Inject private ExtensionRegistry extensionRegistry;
  @Inject private Submit submit;
  @Inject private ProjectOperations projectOperations;

  // The timeout only bounds how long this takes where the push is retried without end.
  @Test
  @GerritConfig(name = "retry.timeout", value = "2s")
  public void pushIsRejectedWhenItsPatchSetNumberIsTakenMeanwhile() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    List<ObjectId> leftBehind = new ArrayList<>();
    DuringFirstAttempt race =
        new DuringFirstAttempt(id, psId -> leftBehind.add(createRefWithoutPatchSet(psId)));
    List<RevisionCreatedListener.Event> created = new ArrayList<>();

    PushOneCommit.Result rejected;
    try (Registration registration =
        extensionRegistry.newRegistration().add(race).add((RevisionCreatedListener) created::add)) {
      rejected = amendChange(r.getChangeId());
    }

    assertThat(race.failure).isNull();
    rejected.assertErrorStatus(
        "patch set 2 of change "
            + id
            + " was created by another update while this push was processed; push again");
    assertThat(race.attempts).isEqualTo(1);
    assertThat(created).isEmpty();
    assertThat(patchSetNumbers(id)).containsExactly(1);
    assertThat(leftBehind).hasSize(1);
    assertThat(refTarget(PatchSet.id(id, 2))).isEqualTo(leftBehind.get(0));

    PushOneCommit.Result pushedAgain = amendChange(r.getChangeId());
    pushedAgain.assertOkStatus();
    assertThat(patchSetNumbers(id)).containsExactly(1, 3);
    assertThat(refTarget(PatchSet.id(id, 3))).isEqualTo(pushedAgain.getCommit());
  }

  @Test
  public void pushIsRetriedWhenTheRefInItsWayIsGoneOnTheNextAttempt() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    PatchSet.Id next = PatchSet.id(id, 2);
    DuringFirstAttempt race = new DuringFirstAttempt(id, this::createRefWithoutPatchSet);
    List<String> retried = new ArrayList<>();
    RetryListener beforeRetry =
        (actionType, actionName, nextAttempt, cause) -> {
          retried.add(actionName);
          try {
            deleteRef(next);
          } catch (Exception e) {
            race.failure = e;
          }
        };

    PushOneCommit.Result pushed;
    try (Registration registration =
        extensionRegistry.newRegistration().add(race).add(beforeRetry)) {
      pushed = amendChange(r.getChangeId());
    }

    assertThat(race.failure).isNull();
    pushed.assertOkStatus();
    assertThat(retried).containsExactly("insertPatchSets");
    assertThat(race.attempts).isEqualTo(2);
    assertThat(patchSetNumbers(id)).containsExactly(1, 2);
    assertThat(refTarget(next)).isEqualTo(pushed.getCommit());
  }

  @Test
  public void pushIsRejectedWhenTheChangeIsAbandonedMeanwhile() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    DuringFirstAttempt race =
        new DuringFirstAttempt(id, psId -> gApi.changes().id(project.get(), id.get()).abandon());
    List<RevisionCreatedListener.Event> created = new ArrayList<>();

    PushOneCommit.Result rejected;
    try (Registration registration =
        extensionRegistry.newRegistration().add(race).add((RevisionCreatedListener) created::add)) {
      rejected = amendChange(r.getChangeId());
    }

    assertThat(race.failure).isNull();
    rejected.assertErrorStatus("change " + id + " closed");
    assertThat(race.attempts).isEqualTo(1);
    assertThat(created).isEmpty();
    assertThat(refTarget(PatchSet.id(id, 2))).isNull();
    assertThat(patchSetNumbers(id)).containsExactly(1);
    assertThat(gApi.changes().id(project.get(), id.get()).get().status)
        .isEqualTo(ChangeStatus.ABANDONED);

    // The number the rejected push was given is free for the next one.
    gApi.changes().id(project.get(), id.get()).restore();
    PushOneCommit.Result pushedAgain = amendChange(r.getChangeId());
    pushedAgain.assertOkStatus();
    assertThat(patchSetNumbers(id)).containsExactly(1, 2);
    assertThat(refTarget(PatchSet.id(id, 2))).isEqualTo(pushedAgain.getCommit());
  }

  @Test
  public void rejectedPushWithSubmitOptionSubmitsNothing() throws Exception {
    allowSubmitOnPush();
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    DuringFirstAttempt race = new DuringFirstAttempt(id, this::createRefWithoutPatchSet);
    SubmissionsAndReceiveErrors observed = new SubmissionsAndReceiveErrors();

    PushOneCommit.Result rejected;
    try (Registration registration = extensionRegistry.newRegistration().add(race).add(observed);
        AutoCloseable logs = observed.watchReceiveErrors()) {
      rejected = amendChange(r.getChangeId(), "refs/for/master%submit", admin, testRepo);
    }

    assertThat(race.failure).isNull();
    rejected.assertErrorStatus(
        "patch set 2 of change "
            + id
            + " was created by another update while this push was processed; push again");
    assertThat(observed.submissions).isEmpty();
    assertThat(observed.receiveErrors).isEmpty();
    assertThat(patchSetNumbers(id)).containsExactly(1);
    assertThat(gApi.changes().id(project.get(), id.get()).get().status).isEqualTo(ChangeStatus.NEW);
  }

  @Test
  public void pushWithSubmitOptionOfChangeAbandonedMeanwhileSubmitsNothing() throws Exception {
    allowSubmitOnPush();
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    DuringFirstAttempt race =
        new DuringFirstAttempt(id, psId -> gApi.changes().id(project.get(), id.get()).abandon());
    SubmissionsAndReceiveErrors observed = new SubmissionsAndReceiveErrors();

    PushOneCommit.Result rejected;
    try (Registration registration = extensionRegistry.newRegistration().add(race).add(observed);
        AutoCloseable logs = observed.watchReceiveErrors()) {
      rejected = amendChange(r.getChangeId(), "refs/for/master%submit", admin, testRepo);
    }

    assertThat(race.failure).isNull();
    rejected.assertErrorStatus("change " + id + " closed");
    assertThat(observed.submissions).isEmpty();
    assertThat(observed.receiveErrors).isEmpty();
    assertThat(refTarget(PatchSet.id(id, 2))).isNull();
    assertThat(gApi.changes().id(project.get(), id.get()).get().status)
        .isEqualTo(ChangeStatus.ABANDONED);
  }

  @Test
  public void retriedPushWithSubmitOptionSubmitsItsPatchSet() throws Exception {
    allowSubmitOnPush();
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    PatchSet.Id next = PatchSet.id(id, 2);
    DuringFirstAttempt race = new DuringFirstAttempt(id, this::createRefWithoutPatchSet);
    RetryListener beforeRetry =
        (actionType, actionName, nextAttempt, cause) -> {
          try {
            deleteRef(next);
          } catch (Exception e) {
            race.failure = e;
          }
        };
    SubmissionsAndReceiveErrors observed = new SubmissionsAndReceiveErrors();

    PushOneCommit.Result pushed;
    try (Registration registration =
            extensionRegistry.newRegistration().add(race).add(beforeRetry).add(observed);
        AutoCloseable logs = observed.watchReceiveErrors()) {
      pushed = amendChange(r.getChangeId(), "refs/for/master%submit", admin, testRepo);
    }

    assertThat(race.failure).isNull();
    pushed.assertOkStatus();
    assertThat(race.attempts).isEqualTo(2);
    assertThat(observed.submissions).hasSize(1);
    assertThat(observed.receiveErrors).isEmpty();
    ChangeInfo info = gApi.changes().id(project.get(), id.get()).get(ALL_REVISIONS);
    assertThat(info.status).isEqualTo(ChangeStatus.MERGED);
    assertThat(info.currentRevision).isEqualTo(pushed.getCommit().name());
    assertThat(info.currentRevisionNumber).isEqualTo(2);
  }

  @Test
  public void submitOfChangeMergedMeanwhileIsAConflict() throws Exception {
    assertSubmitOfChangeMergedMeanwhileIsAConflict();
  }

  // Submission by cherry pick takes the change it is asked for without looking it up among the
  // open changes, so here it is the reload from NoteDb that finds the change merged.
  @Test
  @TestProjectInput(submitType = SubmitType.CHERRY_PICK)
  public void submitByCherryPickOfChangeMergedMeanwhileIsAConflict() throws Exception {
    assertSubmitOfChangeMergedMeanwhileIsAConflict();
  }

  private void assertSubmitOfChangeMergedMeanwhileIsAConflict() throws Exception {
    PushOneCommit.Result r = createChange();
    approve(r.getChangeId());
    RevisionResource readBefore = parseCurrentRevisionResource(r.getChangeId());
    gApi.changes().id(r.getChangeId()).current().submit();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class, () -> submit.apply(readBefore, new SubmitInput()));

    assertThat(thrown).hasMessageThat().isEqualTo("change is merged");
    assertThat(gApi.changes().id(r.getChangeId()).get().status).isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void submitOfChangeAbandonedMeanwhileIsAConflict() throws Exception {
    PushOneCommit.Result r = createChange();
    approve(r.getChangeId());
    RevisionResource readBefore = parseCurrentRevisionResource(r.getChangeId());
    gApi.changes().id(r.getChangeId()).abandon();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class, () -> submit.apply(readBefore, new SubmitInput()));

    assertThat(thrown).hasMessageThat().isEqualTo("change is abandoned");
    assertThat(gApi.changes().id(r.getChangeId()).get().status).isEqualTo(ChangeStatus.ABANDONED);
  }

  /**
   * Runs an action during the first attempt of a push to create a patch set of a change: after the
   * push was validated and its patch set ref added to the update, before the update reads the
   * change and writes anything.
   */
  private static class DuringFirstAttempt implements CommitValidationInfoListener {
    interface Action {
      void run(PatchSet.Id psId) throws Exception;
    }

    private final Change.Id changeId;
    private final Action action;
    int attempts;
    // The caller of the listener logs what it throws and goes on.
    Exception failure;

    DuringFirstAttempt(Change.Id changeId, Action action) {
      this.changeId = changeId;
      this.action = action;
    }

    @Override
    public void commitValidated(
        ImmutableMap<String, CommitValidationInfo> validationInfoByValidator,
        CommitReceivedEvent receiveEvent,
        @Nullable PatchSet.Id patchSetId) {
      if (patchSetId == null || !patchSetId.changeId().equals(changeId)) {
        return;
      }
      attempts++;
      if (attempts > 1) {
        return;
      }
      try {
        action.run(patchSetId);
      } catch (Exception e) {
        failure = e;
      }
    }
  }

  /** Counts the submissions that got as far as updating a branch, and the errors of pushes. */
  private static class SubmissionsAndReceiveErrors implements OnSubmitValidationListener {
    final List<String> submissions = new CopyOnWriteArrayList<>();
    final List<String> receiveErrors = new CopyOnWriteArrayList<>();

    @Override
    public void preBranchUpdate(Arguments args) {
      submissions.add(args.getProject().get());
    }

    /** Collects what the code that receives pushes logs as an error until closed. */
    AutoCloseable watchReceiveErrors() {
      Logger logger = Logger.getLogger("com.google.gerrit.server.git.receive.ReceiveCommits");
      Handler handler =
          new Handler() {
            @Override
            public void publish(LogRecord record) {
              if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                receiveErrors.add(String.valueOf(record.getMessage()));
              }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
          };
      logger.addHandler(handler);
      return () -> logger.removeHandler(handler);
    }
  }

  private void allowSubmitOnPush() {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT).ref("refs/for/refs/heads/master").group(adminGroupUuid()))
        .update();
  }

  private List<Integer> patchSetNumbers(Change.Id id) throws Exception {
    ChangeInfo info = gApi.changes().id(project.get(), id.get()).get(ALL_REVISIONS);
    return info.revisions.values().stream().map(revision -> revision._number).sorted().toList();
  }

  /**
   * Creates the ref of a patch set the change does not have, as an update that failed leaves it.
   */
  @CanIgnoreReturnValue
  private ObjectId createRefWithoutPatchSet(PatchSet.Id psId) throws Exception {
    try (TestRepository<Repository> repo =
        new TestRepository<>(repoManager.openRepository(project))) {
      ObjectId commit = repo.commit().message("Left behind").create();
      RefUpdate update = repo.getRepository().updateRef(psId.toRefName());
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commit);
      testRefAction(() -> assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW));
      return commit;
    }
  }

  private void deleteRef(PatchSet.Id psId) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      RefUpdate update = repo.updateRef(psId.toRefName());
      update.setForceUpdate(true);
      testRefAction(() -> assertThat(update.delete()).isEqualTo(RefUpdate.Result.FORCED));
    }
  }

  @Nullable
  private ObjectId refTarget(PatchSet.Id psId) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      Ref ref = repo.exactRef(psId.toRefName());
      return ref != null ? ref.getObjectId() : null;
    }
  }
}
