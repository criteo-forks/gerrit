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
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.COMMIT_FOOTERS;
import static com.google.gerrit.testing.TestActionRefUpdateContext.testRefAction;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

/**
 * Changes whose patch set numbers have a gap: a patch set ref exists that the change has no patch
 * set for, so the patch set created next gets the number after it.
 */
public class PatchSetNumberGapIT extends AbstractDaemonTest {
  @Inject private ExtensionRegistry extensionRegistry;

  @Test
  public void changeWithSkippedPatchSetNumberIsNotifiedAndSerialized() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    createRefWithoutPatchSet(PatchSet.id(id, 2));

    List<RevisionCreatedListener.Event> created = new ArrayList<>();
    try (Registration registration =
        extensionRegistry.newRegistration().add((RevisionCreatedListener) created::add)) {
      amendChange(r.getChangeId()).assertOkStatus();
    }

    assertThat(created).hasSize(1);
    assertThat(created.get(0).getRevision()._number).isEqualTo(3);
    assertThat(created.get(0).getRevision().branch).isEqualTo("refs/heads/master");

    ChangeInfo info = gApi.changes().id(project.get(), id.get()).get(ALL_REVISIONS, COMMIT_FOOTERS);
    assertThat(info.currentRevisionNumber).isEqualTo(3);
    RevisionInfo current = info.revisions.get(info.currentRevision);
    assertThat(current._number).isEqualTo(3);
    assertThat(current.branch).isEqualTo("refs/heads/master");
    assertThat(current.commitWithFooters).contains("Change-Id: " + r.getChangeId());
    assertThat(info.revisions.get(r.getCommit().name()).branch).isEqualTo("refs/heads/master");

    // A patch set keeps the branch the change had when the patch set was the current one.
    createBranch(BranchNameKey.create(project, "other"));
    gApi.changes().id(project.get(), id.get()).move("other");
    info = gApi.changes().id(project.get(), id.get()).get(ALL_REVISIONS, COMMIT_FOOTERS);
    assertThat(info.revisions.get(info.currentRevision).branch).isEqualTo("refs/heads/other");
    assertThat(info.revisions.get(r.getCommit().name()).branch).isEqualTo("refs/heads/master");
  }

  @Test
  public void rebaseSkipsPatchSetNumberOfExistingRef() throws Exception {
    PushOneCommit.Result base = createChange("base", "base.txt", "base");
    testRepo.reset("HEAD~1");
    PushOneCommit.Result r = createChange("rebased", "rebased.txt", "rebased");
    approve(base.getChangeId());
    gApi.changes().id(base.getChangeId()).current().submit();
    Change.Id id = r.getChange().getId();
    ObjectId leftBehind = createRefWithoutPatchSet(PatchSet.id(id, 2));

    gApi.changes().id(project.get(), id.get()).rebase();

    assertCurrentPatchSet(id, 3, ChangeStatus.NEW);
    assertThat(refTarget(PatchSet.id(id, 2))).isEqualTo(leftBehind);
  }

  @Test
  @TestProjectInput(submitType = SubmitType.CHERRY_PICK)
  public void submitByCherryPickSkipsPatchSetNumberOfExistingRef() throws Exception {
    assertSubmitSkipsPatchSetNumberOfExistingRef();
  }

  @Test
  @TestProjectInput(submitType = SubmitType.REBASE_ALWAYS)
  public void submitByRebaseSkipsPatchSetNumberOfExistingRef() throws Exception {
    assertSubmitSkipsPatchSetNumberOfExistingRef();
  }

  private void assertSubmitSkipsPatchSetNumberOfExistingRef() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    ObjectId leftBehind = createRefWithoutPatchSet(PatchSet.id(id, 2));
    approve(r.getChangeId());

    gApi.changes().id(project.get(), id.get()).current().submit();

    assertCurrentPatchSet(id, 3, ChangeStatus.MERGED);
    assertThat(refTarget(PatchSet.id(id, 2))).isEqualTo(leftBehind);
  }

  private void assertCurrentPatchSet(Change.Id id, int number, ChangeStatus status)
      throws Exception {
    ChangeInfo info = gApi.changes().id(project.get(), id.get()).get(ALL_REVISIONS);
    assertThat(info.status).isEqualTo(status);
    assertThat(info.currentRevisionNumber).isEqualTo(number);
    assertThat(info.revisions.values().stream().map(revision -> revision._number))
        .containsExactly(1, number);
    assertThat(refTarget(PatchSet.id(id, number)))
        .isEqualTo(ObjectId.fromString(info.currentRevision));
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

  @Nullable
  private ObjectId refTarget(PatchSet.Id psId) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      Ref ref = repo.exactRef(psId.toRefName());
      return ref != null ? ref.getObjectId() : null;
    }
  }
}
