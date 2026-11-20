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

package com.google.gerrit.acceptance.server.httpd;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.cache.Cache;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.ServingGate;
import com.google.gerrit.server.change.ChangeFinder;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.index.change.ChangeIndexFreshness;
import com.google.gerrit.server.index.change.ChangeIndexNotFreshException;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.After;
import org.junit.Test;

/** A server whose backend keeps its serving gate closed, and after the gate opens. */
@UseSsh
public class ServingGateIT extends AbstractDaemonTest {
  private static final String CATCHING_UP = "This server is catching up. Please retry.";

  private static volatile LongSupplier searchGeneration = () -> 0L;
  private static volatile LongSupplier candidateGeneration = () -> 0L;
  private static volatile BiFunction<String, String, Optional<String>> storedRef;
  private static volatile Optional<String> closed = Optional.empty();
  private static volatile boolean lookUpInPreMerge;
  private static final List<Integer> lookedUp = new CopyOnWriteArrayList<>();

  /** A ref as shared storage holds it: the repository's, unless a test answers for storage. */
  private static class StoredRef implements BiFunction<String, String, Optional<String>> {
    private final GitRepositoryManager repositories;

    @Inject
    StoredRef(GitRepositoryManager repositories) {
      this.repositories = repositories;
    }

    @Override
    public Optional<String> apply(String project, String ref) {
      BiFunction<String, String, Optional<String>> answer = storedRef;
      if (answer != null) {
        return answer.apply(project, ref);
      }
      try (Repository repo = repositories.openRepository(Project.nameKey(project))) {
        return Optional.ofNullable(repo.exactRef(ref)).map(found -> found.getObjectId().name());
      } catch (RepositoryNotFoundException absent) {
        return Optional.empty();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
  }

  /** Simulates the automerger's pre-merge number lookup with an empty project cache. */
  private static class LookupByNumber implements MergeValidationListener {
    private final GerritApi gApi;
    private final Cache<Change.Id, String> projectsByNumber;

    @Inject
    LookupByNumber(
        GerritApi gApi, @Named("changeid_project") Cache<Change.Id, String> projectsByNumber) {
      this.gApi = gApi;
      this.projectsByNumber = projectsByNumber;
    }

    @Override
    public void onPreMerge(
        Repository repo,
        CodeReviewRevWalk revWalk,
        CodeReviewCommit commit,
        ProjectState destProject,
        BranchNameKey destBranch,
        PatchSet.Id patchSetId,
        IdentifiedUser caller)
        throws MergeValidationException {
      if (!lookUpInPreMerge) {
        return;
      }
      try {
        projectsByNumber.invalidateAll();
        lookedUp.add(gApi.changes().id(commit.change().getId().get()).get()._number);
      } catch (RestApiException failure) {
        throw new MergeValidationException("Cannot look the change up: " + failure.getMessage());
      }
    }
  }

  @Inject private ChangeIndexer changeIndexer;
  @Inject private ProjectOperations projectOperations;

  @Inject
  @Named("changeid_project")
  private Cache<Change.Id, String> projectsByNumber;

  @Override
  public Module createModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        OptionalBinder.newOptionalBinder(binder(), ChangeIndexFreshness.SEARCH_KEY)
            .setBinding()
            .toInstance(() -> searchGeneration.getAsLong());
        OptionalBinder.newOptionalBinder(binder(), ChangeIndexFreshness.CANDIDATES_KEY)
            .setBinding()
            .toInstance(() -> candidateGeneration.getAsLong());
        bind(new TypeLiteral<BiFunction<String, String, Optional<String>>>() {})
            .annotatedWith(Names.named("authoritativeRef"))
            .to(StoredRef.class);
        OptionalBinder.newOptionalBinder(binder(), ServingGate.GATE_KEY)
            .setBinding()
            .toInstance(() -> closed);
        DynamicSet.bind(binder(), MergeValidationListener.class).to(LookupByNumber.class);
      }
    };
  }

  @After
  public void openGate() {
    searchGeneration = () -> 0L;
    candidateGeneration = () -> 0L;
    storedRef = null;
    closed = Optional.empty();
    lookUpInPreMerge = false;
    lookedUp.clear();
  }

  @Test
  public void aClosedGateAnswersOnlyTheLivenessRequest() throws Exception {
    String change = createChange().getChangeId();
    closed = Optional.of(CATCHING_UP);

    anonymousRestSession.get(ServingGate.LIVENESS_PATH).assertOK();
    adminRestSession.get(ServingGate.LIVENESS_PATH).assertOK();
    anonymousRestSession
        .getWithHeaders("/livez", new BasicHeader("Authorization", "Basic malformed"))
        .assertOK();
    anonymousRestSession.head("/livez").assertOK();
    anonymousRestSession.get("/config/server/version").assertOK();
    anonymousRestSession.get("/livez/other").assertStatus(503);
    anonymousRestSession.post("/livez").assertStatus(503);

    // Index, repository, health-check and page requests.
    for (String path :
        new String[] {
          "/changes/?q=status:open",
          "/changes/" + change,
          "/" + project.get() + "/info/refs?service=git-upload-pack",
          "/config/server/healthcheck~status",
          "/config/server/info",
          "/"
        }) {
      RestResponse refused = anonymousRestSession.get(path);
      refused.assertStatus(503);
      assertThat(refused.getHeader("Retry-After")).isEqualTo("30");
      assertThat(refused.getEntityContent()).contains(CATCHING_UP);
    }
    adminRestSession.get("/changes/" + change).assertStatus(503);
    adminRestSession.post("/changes/" + change + "/abandon").assertStatus(503);
    assertThat(gApi.changes().id(change).get().status.name()).isEqualTo("NEW");

    adminSshSession.exec("gerrit version");
    adminSshSession.assertFailure(CATCHING_UP);
    adminSshSession.exec("gerrit query status:open");
    adminSshSession.assertFailure(CATCHING_UP);
    adminSshSession.exec("git-upload-pack " + project.get());
    adminSshSession.assertFailure(CATCHING_UP);
  }

  @Test
  public void staleChangeSearchRefusesRestAndSshAndResumesOnTheSameConnection() throws Exception {
    PushOneCommit.Result change = createChange();
    String number = change.getChange().getId().toString();
    String search = "/changes/?q=change:" + number;
    adminSshSession.exec("gerrit query change:" + number);
    adminSshSession.assertSuccess();
    searchGeneration = () -> -1L;

    RestResponse refused = anonymousRestSession.get(search);
    refused.assertStatus(503);
    assertThat(refused.getHeader("Retry-After")).isEqualTo("5");
    adminRestSession.get(search).assertStatus(503);
    adminRestSession.get("/changes/" + change.getChangeId()).assertStatus(503);
    assertThat(adminSshSession.exec("gerrit query --format=JSON change:" + number)).isEmpty();
    adminSshSession.assertFailure("Please retry");

    adminRestSession.get("/changes/" + project.get() + "~" + number + "/detail").assertOK();
    anonymousRestSession.get("/" + project.get() + "/info/refs?service=git-upload-pack").assertOK();
    anonymousRestSession.get("/livez").assertOK();

    searchGeneration = () -> 0L;
    adminRestSession.get(search).assertOK();
    assertThat(adminSshSession.exec("gerrit query --format=JSON change:" + number))
        .contains(change.getChangeId());
    adminSshSession.assertSuccess();
  }

  @Test
  @GerritConfig(name = "change.backfillMergeSuperSet", value = "true")
  public void aSubmitValidatedByALookupOfItsNumberNeedsNeitherTheIndexNorTheNumberCache()
      throws Exception {
    PushOneCommit.Result change = createChange();
    Change.Id id = change.getChange().getId();
    approve(change.getChangeId());
    changeIndexer.delete(id);
    searchGeneration = () -> -1L;
    candidateGeneration = () -> -1L;
    lookUpInPreMerge = true;

    adminRestSession.post("/changes/" + Url.encode(project.get()) + "~" + id + "/submit").assertOK();

    assertThat(lookedUp).containsExactly(id.get());
    searchGeneration = () -> 0L;
    assertThat(gApi.changes().id(project.get(), id.get()).get().status)
        .isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void theProjectACallerNamesResolvesItsNumberAloneWithoutTheIndex() throws Exception {
    Change.Id named = createChange().getChange().getId();
    Change.Id other = createChange().getChange().getId();
    changeIndexer.delete(named);
    changeIndexer.delete(other);
    projectsByNumber.invalidateAll();
    searchGeneration = () -> -1L;

    try (ChangeFinder.Hint scope = ChangeFinder.knownProject(project, named)) {
      assertThat(changeFinder.findOne(named).map(notes -> notes.getProjectName()))
          .hasValue(project);
      assertThrows(ChangeIndexNotFreshException.class, () -> changeFinder.findOne(other));
      try (ChangeFinder.Hint inner = ChangeFinder.knownProject(allProjects, other)) {
        // A project that does not hold the change confirms nothing.
        assertThrows(ChangeIndexNotFreshException.class, () -> changeFinder.findOne(other));
      }
      projectsByNumber.invalidateAll();
      assertThat(changeFinder.findOne(named)).isPresent();
    }
    projectsByNumber.invalidateAll();
    assertThrows(ChangeIndexNotFreshException.class, () -> changeFinder.findOne(named));
  }

  @Test
  public void aBareNumberThisServerIndexedResolvesOnAStaleIndexFromAColdCache() throws Exception {
    PushOneCommit.Result change = createChange();
    String number = change.getChange().getId().toString();
    projectsByNumber.invalidateAll();
    searchGeneration = () -> -1L;

    adminRestSession.get("/changes/" + number + "/detail").assertOK();
    projectsByNumber.invalidateAll();
    adminRestSession
        .post("/changes/" + number + "/revisions/current/review", ReviewInput.recommend())
        .assertOK();

    // An answer that depends on the index being complete is still refused.
    adminRestSession.get("/changes/?q=change:" + number).assertStatus(503);
    adminRestSession.get("/changes/" + change.getChangeId()).assertStatus(503);
  }

  @Test
  public void aBareNumberThisServerHasNotIndexedIsRefusedOnAStaleIndexUntilItsProjectIsKnown()
      throws Exception {
    PushOneCommit.Result change = createChange();
    Change.Id id = change.getChange().getId();
    changeIndexer.delete(id);
    projectsByNumber.invalidateAll();
    searchGeneration = () -> -1L;

    RestResponse refused = adminRestSession.get("/changes/" + id);
    refused.assertStatus(503);
    assertThat(refused.getHeader("Retry-After")).isEqualTo("5");
    adminRestSession.post("/changes/" + id + "/abandon").assertStatus(503);

    // A project-qualified read populates the number cache without the index.
    adminRestSession.get("/changes/" + Url.encode(project.get()) + "~" + id).assertOK();
    adminRestSession.get("/changes/" + id).assertOK();
  }

  @Test
  public void anIndexBeingRebuiltNamesNoCandidateForABareNumber() throws Exception {
    PushOneCommit.Result change = createChange();
    String number = change.getChange().getId().toString();
    searchGeneration = () -> -1L;

    projectsByNumber.invalidateAll();
    candidateGeneration = () -> -1L;
    adminRestSession.get("/changes/" + number).assertStatus(503);

    // Discard candidates if a rebuild starts during the query.
    candidateGeneration = new AtomicLong()::getAndIncrement;
    adminRestSession.get("/changes/" + number).assertStatus(503);
    searchGeneration = () -> 0L;
    adminRestSession.get("/changes/" + number).assertStatus(503);

    candidateGeneration = () -> 0L;
    searchGeneration = () -> -1L;
    adminRestSession.get("/changes/" + number).assertOK();
  }

  @Test
  public void aNumberIsAnsweredFromTheProjectAndRevisionStorageHoldsNow() throws Exception {
    PushOneCommit.Result change = createChange();
    Change.Id id = change.getChange().getId();
    String meta;
    try (Repository repo = repoManager.openRepository(project)) {
      meta = repo.exactRef(RefNames.changeMetaRef(id)).getObjectId().name();
    }
    gApi.changes().id(project.get(), id.get()).topic("later");
    adminRestSession.get("/changes/" + id).assertOK();
    assertThat(projectsByNumber.getIfPresent(id)).isNotNull();
    searchGeneration = () -> -1L;
    List<String> asked = new CopyOnWriteArrayList<>();

    // The revision storage names is the one that is read.
    storedRef =
        (name, ref) -> {
          asked.add(name + " " + ref);
          return Optional.of(meta);
        };
    assertThat(changeFinder.findOne(id).get().getChange().getTopic()).isNull();
    assertThat(asked).containsExactly(project.get() + " " + RefNames.changeMetaRef(id));

    // A storage failure must not look like an absent change or stale index.
    storedRef =
        (name, ref) -> {
          throw new UncheckedIOException(new IOException("store unreachable"));
        };
    adminRestSession.get("/changes/" + id).assertStatus(500);
    assertThat(projectsByNumber.getIfPresent(id)).isNotNull();

    // An absent project confirms neither the cached nor indexed candidate.
    storedRef = (name, ref) -> Optional.empty();
    adminRestSession.get("/changes/" + id).assertStatus(503);
    assertThat(projectsByNumber.getIfPresent(id)).isNull();
    try (ChangeFinder.Hint scope = ChangeFinder.knownProject(project, id)) {
      assertThrows(ChangeIndexNotFreshException.class, () -> changeFinder.findOne(id));
    }

    storedRef = null;
    assertThat(changeFinder.findOne(id).get().getChange().getTopic()).isEqualTo("later");
  }

  @Test
  public void aDocumentStorageDoesNotConfirmIsNoAnswerOnAFreshIndex() throws Exception {
    PushOneCommit.Result change = createChange();
    Change.Id id = change.getChange().getId();
    assertThat(gApi.changes().query("change:" + id).get()).hasSize(1);
    projectsByNumber.invalidateAll();
    List<String> asked = new CopyOnWriteArrayList<>();
    storedRef =
        (name, ref) -> {
          asked.add(name + " " + ref);
          return Optional.empty();
        };

    assertThat(changeFinder.findOne(id)).isEmpty();
    assertThat(asked).containsExactly(project.get() + " " + RefNames.changeMetaRef(id));
    adminRestSession.get("/changes/" + id).assertNotFound();
    adminRestSession.post("/changes/" + id + "/abandon").assertNotFound();
    assertThat(projectsByNumber.getIfPresent(id)).isNull();
    assertThat(gApi.changes().query("change:" + id).get()).hasSize(1);

    storedRef = null;
    assertThat(changeFinder.findOne(id).map(notes -> notes.getProjectName())).hasValue(project);
    adminRestSession.post("/changes/" + id + "/abandon").assertOK();
  }

  @Test
  public void aChangeTheCallerMayNotSeeStaysHiddenOnAStaleIndex() throws Exception {
    PushOneCommit.Result change = createChange();
    String number = change.getChange().getId().toString();
    gApi.changes().id(change.getChangeId()).setPrivate(true, null);
    projectsByNumber.invalidateAll();
    searchGeneration = () -> -1L;

    userRestSession.get("/changes/" + number).assertNotFound();
    userRestSession.get("/changes/" + number + "/detail").assertNotFound();
    adminRestSession.get("/changes/" + number).assertOK();
    // A cached project does not bypass visibility checks.
    userRestSession.get("/changes/" + number).assertNotFound();
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server")
  public void whereNumbersMayRepeatABareNumberKeepsDependingOnAFreshIndex() throws Exception {
    PushOneCommit.Result change = createChange();
    Change.Id id = change.getChange().getId();
    approve(change.getChangeId());
    String qualified = "/changes/" + Url.encode(project.get()) + "~" + id;
    adminRestSession.get(qualified).assertOK();
    adminRestSession.get("/changes/" + id).assertOK();
    assertThat(projectsByNumber.getIfPresent(id)).isNull();
    // Ignore the cache when numbers can repeat.
    projectsByNumber.put(id, Url.encode(project.get()));
    searchGeneration = () -> -1L;
    lookUpInPreMerge = true;

    adminRestSession.get(qualified).assertOK();
    adminRestSession.get("/changes/" + id).assertStatus(503);
    adminRestSession.post(qualified + "/submit").assertStatus(503);

    searchGeneration = () -> 0L;
    adminRestSession.get("/changes/" + id).assertOK();
    assertThat(gApi.changes().id(project.get(), id.get()).get().status).isEqualTo(ChangeStatus.NEW);
    adminRestSession.post(qualified + "/submit").assertOK();
    assertThat(lookedUp).containsExactly(id.get());

    Project.NameKey other = importChangeNumbered(id);
    projectsByNumber.put(id, Url.encode(project.get()));
    RestResponse ambiguous = adminRestSession.get("/changes/" + id);
    ambiguous.assertNotFound();
    assertThat(ambiguous.getEntityContent()).contains("Multiple changes found");
    adminRestSession.get(qualified).assertOK();
    adminRestSession.get("/changes/" + Url.encode(other.get()) + "~" + id).assertOK();
    searchGeneration = () -> -1L;
    adminRestSession.get("/changes/" + id).assertStatus(503);
  }

  /** Gives another project a change with {@code id}'s number that came from another server. */
  private Project.NameKey importChangeNumbered(Change.Id id) throws Exception {
    Project.NameKey other = projectOperations.newProject().create();
    PushOneCommit.Result donor =
        pushFactory.create(admin.newIdent(), cloneProject(other)).to("refs/for/master");
    donor.assertOkStatus();
    try (Repository repo = repoManager.openRepository(other);
        ObjectInserter inserter = repo.newObjectInserter();
        RefUpdateContext ctx = RefUpdateContext.openDirectPush(Optional.of("Import a change"))) {
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      commit.setAuthor(
          new PersonIdent("Gerrit User " + admin.id(), admin.id() + "@imported-server"));
      commit.setCommitter(new PersonIdent("Gerrit Code Review", admin.email()));
      commit.setMessage(
          String.join(
              "\n",
              "Create change",
              "",
              "Uploaded patch set 1.",
              "",
              "Patch-set: 1",
              "Change-id: " + donor.getChangeId(),
              "Subject: Imported",
              "Branch: refs/heads/master",
              "Status: new",
              "Topic:",
              "Commit: " + donor.getCommit().name(),
              "Tag: autogenerated:gerrit:newWipPatchSet",
              "Private: false",
              "Work-in-progress: true"));
      ObjectId imported = inserter.insert(commit);
      inserter.flush();
      RefUpdate update = repo.updateRef(RefNames.changeMetaRef(id));
      update.setNewObjectId(imported);
      assertThat(update.forceUpdate()).isEqualTo(RefUpdate.Result.NEW);
    }
    changeIndexer.index(other, id);
    return other;
  }

  @Test
  public void aSearchCrossingARebuildReturnsNoResults() throws Exception {
    createChange();
    searchGeneration = new AtomicLong()::getAndIncrement;
    adminRestSession.get("/changes/?q=status:open").assertStatus(503);
    assertThat(adminSshSession.exec("gerrit query --format=JSON status:open")).isEmpty();
    adminSshSession.assertFailure("Please retry");
  }

  @Test
  public void anOpenedGateAnswersEverything() throws Exception {
    String change = createChange().getChangeId();
    closed = Optional.of(CATCHING_UP);
    adminRestSession.get("/changes/" + change).assertStatus(503);

    closed = Optional.empty();

    adminRestSession.get("/changes/" + change).assertOK();
    anonymousRestSession.get(ServingGate.LIVENESS_PATH).assertOK();
    anonymousRestSession.get("/" + project.get() + "/info/refs?service=git-upload-pack").assertOK();
    adminSshSession.exec("gerrit version");
    adminSshSession.assertSuccess();
    adminRestSession.post("/changes/" + change + "/abandon").assertOK();
  }
}
