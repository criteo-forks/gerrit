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

package com.google.gerrit.acceptance.server.index;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.change.IndexOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.MoveInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.server.index.change.ChangeIndexFreshness;
import com.google.gerrit.server.index.change.ChangeIndexNotFreshException;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.multibindings.OptionalBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;

/**
 * Decisions made on the change index, on a server whose index lacks changes its repositories hold.
 *
 * <p>That is the state of a node whose peers write to shared repositories while each node keeps its
 * own index: a change another node created is in the repository at once and in this node's index
 * only after the node caught up. Here the change is pushed with index writes off, and the barrier
 * the test binds stands in for the backend's: when asked it indexes what the repositories hold, or
 * says it cannot.
 */
public class ChangeIndexFreshnessIT extends AbstractDaemonTest {
  private static final String CANNOT = "the other nodes' writes are out of reach";

  /** What the bound barrier does when Gerrit asks; set by each test. */
  private static volatile Function<Optional<String>, Optional<String>> barrier;

  /** The scopes Gerrit asked for, in order: a project name, or empty for every project. */
  private static final List<Optional<String>> asked = new CopyOnWriteArrayList<>();

  @Inject private ChangeIndexer changeIndexer;
  @Inject private IndexOperations.Change changeIndexOperations;
  @Inject private ProjectOperations projectOperations;

  @Override
  public Module createModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        OptionalBinder.newOptionalBinder(binder(), ChangeIndexFreshness.BARRIER_KEY)
            .setBinding()
            .toInstance(
                scope -> {
                  asked.add(scope);
                  return barrier.apply(scope);
                });
      }
    };
  }

  @Before
  public void resetBarrier() {
    barrier = scope -> Optional.empty();
    asked.clear();
  }

  @Test
  public void amendmentFindsTheChangeTheIndexDidNotHold() throws Exception {
    PushOneCommit.Result original =
        pushUnindexed(project, testRepo, "refs/for/master", "original", null);
    barrier = this::catchUp;

    // The original commit amended: another commit with its parent and its Change-Id.
    PushOneCommit.Result amended =
        pushFactory
            .create(admin.newIdent(), testRepo, "amended", "a.txt", "2", original.getChangeId())
            .to("refs/for/master");

    amended.assertOkStatus();
    assertThat(asked).contains(Optional.of(project.get()));
    List<ChangeInfo> changes = gApi.changes().query("change:" + original.getChangeId()).get();
    assertThat(changes).hasSize(1);
    assertThat(gApi.changes().id(changes.get(0)._number).get().revisions).hasSize(2);
    assertThat(changesInRepository(project)).hasSize(1);
  }

  @Test
  public void withoutABarrierTheAmendmentBecomesASecondChange() throws Exception {
    // The control for the test above: what the stale index alone leads to.
    PushOneCommit.Result original =
        pushUnindexed(project, testRepo, "refs/for/master", "original", null);

    pushFactory
        .create(admin.newIdent(), testRepo, "amended", "a.txt", "2", original.getChangeId())
        .to("refs/for/master")
        .assertOkStatus();

    assertThat(changesInRepository(project)).hasSize(2);
  }

  @Test
  public void pushIsRejectedWhenTheIndexCannotBeBroughtUpToDate() throws Exception {
    ObjectId base = projectOperations.project(project).getHead("master");
    barrier = scope -> Optional.of(CANNOT);

    PushOneCommit.Result rejected =
        pushFactory.create(admin.newIdent(), testRepo).to("refs/for/master");

    rejected.assertErrorStatus(CANNOT);
    assertThat(rejected.getMessage()).doesNotContain("internal");
    assertThat(asked).containsExactly(Optional.of(project.get()));
    assertThat(changesInRepository(project)).isEmpty();

    // A push repeated once the index can be caught up goes through.
    barrier = scope -> Optional.empty();
    testRepo.reset(base);
    pushFactory.create(admin.newIdent(), testRepo).to("refs/for/master").assertOkStatus();
    assertThat(changesInRepository(project)).hasSize(1);
  }

  @Test
  public void directPushIsRejectedWhenTheIndexCannotBeBroughtUpToDate() throws Exception {
    barrier = scope -> Optional.of(CANNOT);

    pushFactory
        .create(admin.newIdent(), testRepo)
        .to("refs/heads/master")
        .assertErrorStatus(CANNOT);
  }

  @Test
  @GerritConfig(name = "change.submitWholeTopic", value = "true")
  public void submissionIncludesTheTopicMemberTheIndexDidNotHold() throws Exception {
    Project.NameKey other = projectOperations.newProject().create();
    String topic = name("feature");
    PushOneCommit.Result approved = push(testRepo, "refs/for/master", "approved", topic);
    approve(approved.getChangeId());
    // Pushed before the submission, and acknowledged; not approved.
    PushOneCommit.Result unapproved =
        pushUnindexed(other, cloneProject(other), "refs/for/master", "unapproved", topic);
    barrier = this::catchUp;

    ResourceConflictException notReady =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(approved.getChangeId()).current().submit());

    assertThat(notReady).hasMessageThat().contains("must be submitted with change");
    assertThat(asked).contains(Optional.empty());
    assertThat(gApi.changes().id(approved.getChangeId()).get().status).isEqualTo(ChangeStatus.NEW);

    // With the member approved the topic is submitted whole.
    approve(unapproved.getChangeId());
    gApi.changes().id(approved.getChangeId()).current().submit();
    assertThat(gApi.changes().id(approved.getChangeId()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
    assertThat(gApi.changes().id(unapproved.getChangeId()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  @GerritConfig(name = "change.submitWholeTopic", value = "true")
  public void withoutABarrierTheSubmissionLeavesTheUnindexedMemberBehind() throws Exception {
    // The control for the test above: what the stale index alone leads to.
    Project.NameKey other = projectOperations.newProject().create();
    String topic = name("feature");
    PushOneCommit.Result approved = push(testRepo, "refs/for/master", "approved", topic);
    approve(approved.getChangeId());
    pushUnindexed(other, cloneProject(other), "refs/for/master", "unapproved", topic);

    gApi.changes().id(approved.getChangeId()).current().submit();

    assertThat(gApi.changes().id(approved.getChangeId()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
    assertThat(changesInRepository(other)).hasSize(1);
  }

  @Test
  @GerritConfig(name = "change.submitWholeTopic", value = "true")
  public void submissionOfATopicIsRefusedWhenTheIndexCannotBeBroughtUpToDate() throws Exception {
    String topic = name("feature");
    PushOneCommit.Result approved = push(testRepo, "refs/for/master", "approved", topic);
    approve(approved.getChangeId());
    barrier = scope -> scope.isPresent() ? Optional.empty() : Optional.of(CANNOT);

    RestResponse response = adminRestSession.post("/changes/" + approved.getChangeId() + "/submit");
    response.assertStatus(503);
    assertThat(response.getEntityContent()).contains(CANNOT);
    assertThat(response.getEntityContent()).contains("Please retry");

    ChangeIndexNotFreshException refused =
        assertThrows(
            ChangeIndexNotFreshException.class,
            () -> gApi.changes().id(approved.getChangeId()).current().submit());
    assertThat(refused).hasMessageThat().contains(CANNOT);
    assertThat(gApi.changes().id(approved.getChangeId()).get().status).isEqualTo(ChangeStatus.NEW);

    barrier = scope -> Optional.empty();
    gApi.changes().id(approved.getChangeId()).current().submit();
    assertThat(gApi.changes().id(approved.getChangeId()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  @GerritConfig(name = "change.submitWholeTopic", value = "true")
  public void onlyASubmissionThatReachesATopicAsksForEveryProject() throws Exception {
    PushOneCommit.Result plain = push(testRepo, "refs/for/master", "no topic", null);
    PushOneCommit.Result inTopic = push(testRepo, "refs/for/master", "topic", name("feature"));
    approve(plain.getChangeId());
    approve(inTopic.getChangeId());
    asked.clear();

    // Showing what would be submitted reads the index as it is.
    assertThat(gApi.changes().id(inTopic.getChangeId()).submittedTogether()).isNotEmpty();
    assertThat(gApi.changes().id(inTopic.getChangeId()).get().topic).isNotEmpty();
    assertThat(asked).isEmpty();

    gApi.changes().id(plain.getChangeId()).current().submit();
    assertThat(asked).doesNotContain(Optional.empty());

    gApi.changes().id(inTopic.getChangeId()).current().submit();
    assertThat(asked).containsExactly(Optional.empty());
  }

  @Test
  public void withoutSubmitWholeTopicASubmissionDoesNotAsk() throws Exception {
    PushOneCommit.Result inTopic = push(testRepo, "refs/for/master", "topic", name("feature"));
    approve(inTopic.getChangeId());
    asked.clear();

    gApi.changes().id(inTopic.getChangeId()).current().submit();

    assertThat(asked).isEmpty();
  }

  @Test
  public void restCallsThatLookUpAChangeIdAskForTheirProject() throws Exception {
    PushOneCommit.Result change = push(testRepo, "refs/for/master", "change", null);
    int number = change.getChange().getId().get();
    createBranch(BranchNameKey.create(project, "stable"));
    createBranch(BranchNameKey.create(project, "other"));

    asked.clear();
    ChangeInput create = new ChangeInput(project.get(), "master", "A change");
    create.subject = "A change\n\nChange-Id: I0123456789012345678901234567890123456789\n";
    gApi.changes().create(create);
    assertThat(asked).containsExactly(Optional.of(project.get()));

    asked.clear();
    CherryPickInput cherryPick = new CherryPickInput();
    cherryPick.destination = "stable";
    gApi.changes().id(change.getChangeId()).current().cherryPick(cherryPick);
    assertThat(asked).containsExactly(Optional.of(project.get()));

    asked.clear();
    MoveInput move = new MoveInput();
    move.destinationBranch = "other";
    gApi.changes().id(project.get(), number).move(move);
    assertThat(asked).containsExactly(Optional.of(project.get()));
  }

  @Test
  public void restCallsThatLookUpAChangeIdAreRefusedWhenTheIndexCannotBeBroughtUpToDate()
      throws Exception {
    PushOneCommit.Result change = push(testRepo, "refs/for/master", "change", null);
    createBranch(BranchNameKey.create(project, "stable"));
    barrier = scope -> Optional.of(CANNOT);

    ChangeInput create = new ChangeInput(project.get(), "master", "A change");
    create.subject = "A change\n\nChange-Id: I0123456789012345678901234567890123456789\n";
    RestResponse created = adminRestSession.post("/changes/", create);
    created.assertStatus(503);
    assertThat(created.getEntityContent()).contains(CANNOT);

    CherryPickInput cherryPick = new CherryPickInput();
    cherryPick.destination = "stable";
    adminRestSession
        .post("/changes/" + change.getChangeId() + "/revisions/current/cherrypick", cherryPick)
        .assertStatus(503);

    MoveInput move = new MoveInput();
    move.destinationBranch = "stable";
    adminRestSession.post("/changes/" + change.getChangeId() + "/move", move).assertStatus(503);

    assertThat(changesInRepository(project)).hasSize(1);
    assertThat(gApi.changes().id(change.getChangeId()).get().branch).isEqualTo("master");
  }

  /** What a backend's barrier does when it can: index what the repositories hold. */
  private Optional<String> catchUp(Optional<String> scope) {
    try {
      List<Project.NameKey> projects = new ArrayList<>();
      if (scope.isPresent()) {
        projects.add(Project.nameKey(scope.get()));
      } else {
        projects.addAll(repoManager.list());
      }
      for (Project.NameKey name : projects) {
        for (Change.Id id : changesInRepository(name)) {
          changeIndexer.index(name, id);
        }
      }
      return Optional.empty();
    } catch (Exception failure) {
      return Optional.of(failure.toString());
    }
  }

  private List<Change.Id> changesInRepository(Project.NameKey name) throws Exception {
    List<Change.Id> ids = new ArrayList<>();
    try (Repository repository = repoManager.openRepository(name)) {
      for (Ref ref : repository.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES)) {
        if (ref.getName().endsWith(RefNames.META_SUFFIX)) {
          ids.add(Change.Id.fromRef(ref.getName()));
        }
      }
    }
    return ids;
  }

  private PushOneCommit.Result push(
      TestRepository<InMemoryRepository> repository, String ref, String subject, String topic)
      throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(admin.newIdent(), repository, subject, subject + ".txt", "1")
            .to(topic == null ? ref : ref + "%topic=" + topic);
    result.assertOkStatus();
    return result;
  }

  /**
   * A push the repository takes and this server's index does not see, as when a peer took it. The
   * client is told of the index failure; the change is in the repository all the same.
   */
  private PushOneCommit.Result pushUnindexed(
      Project.NameKey name,
      TestRepository<InMemoryRepository> repository,
      String ref,
      String subject,
      String topic)
      throws Exception {
    int before = changesInRepository(name).size();
    PushOneCommit.Result result;
    try (AutoCloseable ignored = changeIndexOperations.disableWrites()) {
      result =
          pushFactory
              .create(admin.newIdent(), repository, subject, subject + ".txt", "1")
              .to(topic == null ? ref : ref + "%topic=" + topic);
    }
    assertThat(changesInRepository(name)).hasSize(before + 1);
    assertThat(gApi.changes().query("change:" + result.getChangeId()).get()).isEmpty();
    return result;
  }
}
