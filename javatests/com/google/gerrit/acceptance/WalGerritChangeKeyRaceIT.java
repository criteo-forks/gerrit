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
package com.google.gerrit.acceptance;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.testing.TestActionRefUpdateContext.testRefAction;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.MoveInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationInfo;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.index.change.ChangeKeyLookup;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.testing.ConfigSuite;
import com.google.inject.Inject;
import com.google.inject.Module;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Before;
import org.junit.Test;

/** Concurrent smart-HTTP and REST writes through the daemon and its real WalGerrit ref backend. */
public class WalGerritChangeKeyRaceIT extends AbstractDaemonTest {
  private static final String KEY = "I0123456789012345678901234567890123456789";
  private static final String GIT = "Git review";
  private static final String REST = "REST review";
  private static final String DESTINATION = "refs/for/master";

  @Inject private ChangeKeyLookup lookup;
  @Inject private ExtensionRegistry extensions;
  private ObjectId gitCommit;

  @ConfigSuite.Config
  public static Config storageModuleOnly() {
    Config config = new Config();
    config.setBoolean("walgerrit", null, "authoritativeChangeKeys", false);
    return config;
  }

  @Override
  public Module createModule() {
    return ConfigSuite.DEFAULT.equals(configName)
        ? WalGerritTestSupport.indexModule()
        : super.createModule();
  }

  @Before
  public void prepareClients() throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      assertThat(lookup.open(repo).orElseThrow().members("refs/heads/master", KEY)).isEmpty();
    }
    gitCommit =
        testRepo
            .branch("HEAD")
            .commit()
            .message(GIT + "\n\nChange-Id: " + KEY)
            .author(admin.newIdent())
            .committer(admin.newIdent())
            .add("review.txt", "git")
            .create();
  }

  @Test
  @GerritConfig(name = "index.type", value = "lucene")
  public void restWinsAndGitPublishesPatchSetTwoWithTheWinnerMissingFromLucene() throws Exception {
    PauseValidation git = new PauseValidation(GIT);
    int created;
    try (var worker = Executors.newSingleThreadExecutor();
        var registration = extensions.newRegistration().add(git)) {
      var push = worker.submit(this::push);
      try {
        git.awaitSelection();
        RestResponse rest = create();
        rest.assertCreated();
        created = newGson().fromJson(rest.getReader(), ChangeInfo.class)._number;
        // A peer's ref publication can be visible before its Lucene document reaches the receiver.
        indexer.delete(Change.id(created));
        assertThat(gApi.changes().query("project:" + project.get() + " change:" + KEY).get())
            .isEmpty();
      } finally {
        git.resume.countDown();
      }
      assertPushAccepted(push.get(60, SECONDS));
    }
    assertThat(assertSingleReview(2).get()).isEqualTo(created);
  }

  @Test
  @GerritConfig(name = "index.type", value = "lucene")
  public void gitWinsAndRestReturnsConflictWithoutPublishingAnotherChange() throws Exception {
    PauseValidation rest = new PauseValidation(REST);
    try (var worker = Executors.newSingleThreadExecutor();
        var registration = extensions.newRegistration().add(rest)) {
      var creation = worker.submit(this::create);
      try {
        rest.awaitSelection();
        assertPushAccepted(push());
      } finally {
        rest.resume.countDown();
      }
      RestResponse response = creation.get(60, SECONDS);
      response.assertConflict();
      assertThat(response.getEntityContent()).contains("Another write changed this Change-Id");
    }
    Change.Id winner = assertSingleReview(1);
    assertThat(
            gApi.changes().query("project:" + project.get() + " change:" + KEY).get().stream()
                .map(change -> change._number)
                .toList())
        .containsExactly(winner.get());
  }

  @Test
  @GerritConfig(name = "index.type", value = "lucene")
  public void oversizedRawRefsLeaveReviewPushesAndNoteDbScansUsable() throws Exception {
    List<String> rawRefs =
        List.of("refs/changes/48/2147483648/meta", "refs/changes/01/1/2147483648");
    try (Repository repo = repoManager.openRepository(project)) {
      ObjectId tip = repo.exactRef("refs/heads/master").getObjectId();
      for (String raw : rawRefs) {
        RefUpdate update = repo.updateRef(raw);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(tip);
        testRefAction(() -> assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW));
      }
    }

    assertPushAccepted(push());
    try (Repository repo = repoManager.openRepository(project)) {
      var members = lookup.open(repo).orElseThrow().members("refs/heads/master", KEY);
      Change.Id id = Change.id(getOnlyElement(members));
      assertThat(ChangeNotes.Factory.scanChangeIds(repo).keySet()).containsExactly(id);
      assertThat(
              notesFactory.createChecked(repo, project, id, null).getCurrentPatchSet().commitId())
          .isEqualTo(gitCommit);
      for (String raw : rawRefs) {
        assertThat(repo.exactRef(raw)).isNotNull();
      }
    }
  }

  @Test
  @GerritConfig(name = "index.type", value = "lucene")
  public void moveAndCherryPickPublishTheSameKeyOnTheirRespectiveBranches() throws Exception {
    createBranch(BranchNameKey.create(project, "stable"));
    assertPushAccepted(push());
    Change.Id original = assertSingleReview(1);
    String resource = "/changes/" + original.get();
    MoveInput move = new MoveInput();
    move.destinationBranch = "stable";
    adminRestSession.post(resource + "/move", move).assertOK();
    try (Repository repo = repoManager.openRepository(project)) {
      var keys = lookup.open(repo).orElseThrow();
      assertThat(keys.members("refs/heads/stable", KEY)).containsExactly(original.get());
      assertThat(keys.members("refs/heads/master", KEY)).isEmpty();
    }

    CherryPickInput pick = new CherryPickInput();
    pick.destination = "master";
    pick.message = "Picked review\n\nChange-Id: " + KEY;
    RestResponse response = adminRestSession.post(resource + "/revisions/current/cherrypick", pick);
    response.assertOK();
    Change.Id picked =
        Change.id(newGson().fromJson(response.getReader(), ChangeInfo.class)._number);
    assertThat(picked).isNotEqualTo(original);
    move.destinationBranch = "master";
    adminRestSession.post(resource + "/move", move).assertConflict();
    try (Repository repo = repoManager.openRepository(project)) {
      var keys = lookup.open(repo).orElseThrow();
      for (var entry :
          Map.of("refs/heads/master", picked, "refs/heads/stable", original).entrySet()) {
        assertThat(keys.members(entry.getKey(), KEY)).containsExactly(entry.getValue().get());
        var change = notesFactory.createChecked(repo, project, entry.getValue(), null).getChange();
        assertThat(change.getKey().get()).isEqualTo(KEY);
        assertThat(change.getDest().branch()).isEqualTo(entry.getKey());
      }
    }
  }

  private PushResult push() throws Exception {
    return getOnlyElement(
        testRepo
            .git()
            .push()
            .setRemote(admin.getHttpUrl(server) + "/a/" + project.get())
            .setCredentialsProvider(
                new UsernamePasswordCredentialsProvider(admin.username(), admin.httpPassword()))
            .setRefSpecs(new RefSpec("HEAD:" + DESTINATION))
            .setTimeout(60)
            .call());
  }

  private static void assertPushAccepted(PushResult result) {
    assertWithMessage(result.getMessages())
        .that(result.getRemoteUpdate(DESTINATION).getStatus())
        .isEqualTo(RemoteRefUpdate.Status.OK);
  }

  private RestResponse create() throws Exception {
    return adminRestSession.post(
        "/changes/", new ChangeInput(project.get(), "master", REST + "\n\nChange-Id: " + KEY));
  }

  private Change.Id assertSingleReview(int patchSets) throws Exception {
    try (Repository repo = repoManager.openRepository(project)) {
      List<String> refs =
          repo.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES).stream()
              .map(Ref::getName)
              .toList();
      String meta =
          getOnlyElement(refs.stream().filter(r -> r.endsWith(RefNames.META_SUFFIX)).toList());
      Change.Id id = Change.Id.fromRef(meta);
      ChangeNotes notes = notesFactory.createChecked(repo, project, id, null);
      assertThat(lookup.open(repo).orElseThrow().members("refs/heads/master", KEY))
          .containsExactly(id.get());
      assertThat(notes.getChange().getKey().get()).isEqualTo(KEY);
      assertThat(notes.getChange().getDest().branch()).isEqualTo("refs/heads/master");
      assertThat(notes.getPatchSets()).hasSize(patchSets);
      assertThat(notes.getCurrentPatchSet().commitId()).isEqualTo(gitCommit);
      List<String> expectedRefs = new ArrayList<>();
      expectedRefs.add(meta);
      for (int number = 1; number <= patchSets; number++) {
        PatchSet.Id ps = PatchSet.id(id, number);
        expectedRefs.add(ps.toRefName());
        assertThat(repo.exactRef(ps.toRefName()).getObjectId())
            .isEqualTo(notes.getPatchSets().get(ps).commitId());
      }
      assertThat(refs).containsExactlyElementsIn(expectedRefs);
      if (patchSets == 2) {
        assertThat(
                repo.parseCommit(notes.getPatchSets().get(PatchSet.id(id, 1)).commitId())
                    .getShortMessage())
            .isEqualTo(REST);
      }
      return id;
    }
  }

  /**
   * Both entry points validate the commit after reading the destination key and before publishing.
   */
  private static final class PauseValidation implements CommitValidationListener {
    private final String subject;
    private final CountDownLatch selected = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);

    PauseValidation(String subject) {
      this.subject = subject;
    }

    void awaitSelection() throws InterruptedException {
      assertWithMessage("%s did not reach commit validation", subject)
          .that(selected.await(45, SECONDS))
          .isTrue();
    }

    @Override
    public CommitValidationInfo validateCommit(CommitReceivedEvent event)
        throws CommitValidationException {
      if (event.commit.getShortMessage().equals(subject)) {
        selected.countDown();
        try {
          if (!resume.await(45, SECONDS)) {
            throw new CommitValidationException("Timed out waiting for the other writer");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CommitValidationException("Interrupted waiting for the other writer", e);
        }
      }
      return CommitValidationInfo.passed(CommitValidationInfo.NO_METADATA, ImmutableList.of());
    }
  }
}
