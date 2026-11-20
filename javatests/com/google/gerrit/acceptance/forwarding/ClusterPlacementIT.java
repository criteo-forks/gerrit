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
package com.google.gerrit.acceptance.forwarding;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestMetricMaker;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.TopicInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.NameInput;
import com.google.gerrit.server.WriteEligibility;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteRouter;
import com.google.gerrit.testing.ConfigSuite;
import com.google.gerrit.util.http.testutil.FakePeer;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.multibindings.OptionalBinder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A real Gerrit daemon that places every write on one node of its cluster. */
@UseSsh
public class ClusterPlacementIT extends AbstractDaemonTest {
  private static final String SELF = "gerrit-self";
  private static final String COUNTER = "write_forwarding/requests";
  private static final String KEPT = "write_forwarding/ineligible";

  private static final AtomicReference<Collection<String>> PEERS = new AtomicReference<>(List.of());
  private static final AtomicReference<Optional<String>> HEALTH =
      new AtomicReference<>(Optional.empty());

  @ConfigSuite.Default
  public static Config clusterConfig() throws IOException {
    Config cfg = FakePeer.config();
    cfg.setInt("httpd", null, "maxThreads", 12);
    cfg.setString("walgerrit", null, "placement", "cluster");
    cfg.setString("auth", null, "type", "DEVELOPMENT_BECOME_ANY_ACCOUNT");
    cfg.setString("site", null, "allowOriginRegex", "https?://(.+[.])?example[.]com");
    try (ServerSocket free = new ServerSocket(0)) {
      cfg.setInt("walgerrit", null, "forwardingPort", free.getLocalPort());
    }
    return cfg;
  }

  @Inject private WriteForwarding forwarding;
  @Inject private ProjectOperations projectOperations;

  private final TestMetricMaker metrics = TestMetricMaker.getInstance();
  private FakePeer peer;

  private static boolean backendDescribesTheCluster() {
    return System.getenv("GERRIT_WALGERRIT_JAR") != null;
  }

  @Override
  public Module createModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        if (backendDescribesTheCluster()) {
          return;
        }
        OptionalBinder.newOptionalBinder(binder(), WriteForwarding.NODE_KEY)
            .setBinding()
            .toInstance(() -> SELF);
        OptionalBinder.newOptionalBinder(binder(), WriteForwarding.PEERS_KEY)
            .setBinding()
            .toInstance(PEERS::get);
        OptionalBinder.newOptionalBinder(binder(), WriteForwarding.HEALTH_KEY)
            .setBinding()
            .toInstance(HEALTH::get);
      }
    };
  }

  @Before
  public void setUp() throws Exception {
    assume().that(backendDescribesTheCluster()).isFalse();
    // A push that goes out and comes back needs two threads of the pool of its user.
    gApi.groups().id("Service Users").addMembers(user.username());
    peer = peerRanked(true);
    metrics.reset();
  }

  @After
  public void tearDown() throws Exception {
    PEERS.set(List.of());
    HEALTH.set(Optional.empty());
    if (forwarding != null) {
      forwarding.probe();
    }
    if (peer != null) {
      peer.close();
    }
  }

  @Test
  public void writesThatNameNoProjectRunOnTheExecutor() throws Exception {
    join();

    RestResponse created =
        userRestSession.post(
            "/changes/", new ChangeInput(project.get(), "master", "Placed on the executor"));
    created.assertCreated();
    int number = newGson().fromJson(created.getReader(), ChangeInfo.class)._number;
    userRestSession
        .post("/changes/" + number + "/revisions/current/review?notify=NONE", ReviewInput.noScore())
        .assertOK();
    TopicInput topic = new TopicInput();
    topic.topic = "placed";
    userRestSession.put("/changes/" + number + "/topic", topic).assertOK();
    userRestSession.delete("/changes/" + number + "/topic").assertNoContent();
    userRestSession.put("/accounts/self/starred.changes/" + number).assertNoContent();
    NameInput name = new NameInput();
    name.name = "Placed User";
    userRestSession.put("/accounts/self/name", name).assertOK();
    adminRestSession.put("/groups/" + name("placed")).assertCreated();
    userRestSession.post("/changes/" + number + "/abandon").assertOK();

    assertThat(gApi.changes().id(number).get().status).isEqualTo(ChangeStatus.ABANDONED);
    assertThat(gApi.changes().id(number).get().topic).isNull();
    assertThat(gApi.accounts().id(user.id().get()).get().name).isEqualTo("Placed User");
    assertThat(peer.requests()).hasSize(8);
    for (FakePeer.Request request : peer.requests()) {
      assertWithMessage(request.target()).that(request.header("Authorization")).isNull();
      assertThat(request.header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(peer.name());
    }
    assertThat(peer.requests().get(1).target())
        .isEqualTo("/a/changes/" + number + "/revisions/current/review?notify=NONE");
    for (int emptyBody : List.of(3, 4, 7)) {
      assertThat(peer.requests().get(emptyBody).header("Content-Length")).isEqualTo("0");
    }
    assertForwarded("http", 8);
    assertThat(metrics.getCount(COUNTER, "http", "ineligible")).isEqualTo(0);
  }

  @Test
  public void aBrowserWriteCrossesAfterItsCsrfCheckAndWithoutItsSession() throws Exception {
    int number = createChange().getChange().getId().get();
    BasicCookieStore cookies = new BasicCookieStore();
    Executor browser = Executor.newInstance().use(cookies);
    browser
        .execute(Request.Get(web() + "/login/?account_id=" + admin.id().get()))
        .discardContent();
    browser.execute(Request.Get(web() + "/")).discardContent();
    String token =
        cookies.getCookies().stream()
            .filter(cookie -> cookie.getName().equals("XSRF_TOKEN"))
            .map(Cookie::getValue)
            .findFirst()
            .orElseThrow();
    join();

    HttpResponse forged =
        browser
            .execute(
                Request.Put(web() + "/changes/" + number + "/topic")
                    .addHeader("Origin", "https://evil.test")
                    .bodyString("{\"topic\":\"forged\"}", ContentType.APPLICATION_JSON))
            .returnResponse();

    assertThat(forged.getStatusLine().getStatusCode()).isEqualTo(403);
    assertThat(peer.requests()).isEmpty();
    assertThat(metrics.getCount(KEPT, "http", "csrf")).isEqualTo(1);

    HttpResponse written =
        browser
            .execute(
                Request.Put(web() + "/changes/" + number + "/topic")
                    .addHeader("X-Gerrit-Auth", token)
                    .addHeader("Origin", canonicalWebUrl.get())
                    .addHeader("Referer", web() + "/c/" + number)
                    .addHeader("Sec-Fetch-Site", "same-origin")
                    .addHeader("Sec-Fetch-Mode", "cors")
                    .addHeader("Sec-CH-UA", "\"Chromium\";v=\"140\"")
                    .addHeader("Priority", "u=1, i")
                    .bodyString("{\"topic\":\"from-the-ui\"}", ContentType.APPLICATION_JSON))
            .returnResponse();

    assertThat(written.getStatusLine().getStatusCode()).isEqualTo(200);
    assertThat(gApi.changes().id(number).get().topic).isEqualTo("from-the-ui");
    assertThat(peer.requests()).hasSize(1);
    FakePeer.Request handed = peer.requests().get(0);
    for (String name : List.of("Cookie", "X-Gerrit-Auth", "Authorization", "Referer")) {
      assertWithMessage(name).that(handed.header(name)).isNull();
    }
    assertThat(handed.header(WriteForwarding.ACCOUNT_HEADER))
        .isEqualTo(Integer.toString(admin.id().get()));
    assertForwarded("http", 1);
  }

  @Test
  public void whatIsBoundToThisNodeOrToTheCallersSessionRunsWhereItArrived() throws Exception {
    int number = createChange().getChange().getId().get();
    projectOperations
        .allProjectsForUpdate()
        .add(allowCapability(GlobalCapability.RUN_AS).group(adminGroupUuid()))
        .update();
    BasicCookieStore cookies = new BasicCookieStore();
    Executor browser = Executor.newInstance().use(cookies);
    browser
        .execute(Request.Get(web() + "/login/?account_id=" + admin.id().get()))
        .discardContent();
    String session =
        cookies.getCookies().stream()
            .filter(cookie -> cookie.getName().equals("GerritAccount"))
            .map(Cookie::getValue)
            .findFirst()
            .orElseThrow();
    cookies.clear();
    join();

    adminRestSession.post("/config/server/caches/projects/flush").assertOK();
    adminRestSession
        .put("/changes/" + number + "/revisions/1/files/" + PushOneCommit.FILE_NAME + "/reviewed")
        .assertCreated();
    adminRestSession.post("/changes/" + number + "/index").assertNoContent();
    adminRestSession
        .post("/accounts/self/external.ids:delete", List.of("mailto:nobody@example"))
        .assertUnprocessableEntity();
    adminRestSession
        .postWithHeaders(
            "/changes/" + number + "/revisions/current/review",
            ReviewInput.recommend(),
            new BasicHeader("X-Gerrit-RunAs", Integer.toString(user.id().get())))
        .assertOK();
    HttpResponse tokenInQuery =
        browser
            .execute(
                Request.Post(
                        web()
                            + "/changes/"
                            + number
                            + "/topic?%24m=PUT&%24ct=application%2Fjson&access_token="
                            + session)
                    .addHeader("Origin", "http://example.com")
                    .bodyString("{\"topic\":\"cross-domain\"}", ContentType.TEXT_PLAIN))
            .returnResponse();

    assertThat(tokenInQuery.getStatusLine().getStatusCode()).isEqualTo(200);
    assertThat(gApi.changes().id(number).get().topic).isEqualTo("cross-domain");
    assertThat(
            gApi.changes().id(number).get().labels.get("Code-Review").recommended._accountId)
        .isEqualTo(user.id().get());
    assertThat(peer.requests()).isEmpty();
    for (String reason :
        List.of("config", "reviewed", "node_local", "external_ids", "run_as", "token_query")) {
      assertWithMessage(reason).that(metrics.getCount(KEPT, "http", reason)).isEqualTo(1);
    }
    assertThat(metrics.getCount(COUNTER, "http", "ineligible")).isEqualTo(6);
    assertForwarded("http", 0);
  }

  @Test
  public void pushesOverBothTransportsRunOnTheExecutor() throws Exception {
    join();
    TestRepository<InMemoryRepository> overHttp =
        GitUtil.cloneProject(project, user.getHttpUrl(server) + "/" + project.get());
    pushFactory.create(user.newIdent(), overHttp).to("refs/for/master").assertOkStatus();
    // The client takes its key from the home directory of the session opened last.
    userSshSession.close();
    userSshSession.open();
    TestRepository<InMemoryRepository> overSsh =
        GitUtil.cloneProject(project, userSshSession.getUrl() + "/" + project.get());
    pushFactory.create(user.newIdent(), overSsh).to("refs/for/master").assertOkStatus();

    assertForwarded("http", 2);
    assertForwarded("ssh", 1);
    assertThat(gApi.changes().query("project:" + project.get() + " owner:" + user.id()).get())
        .hasSize(2);
  }

  @Test
  public void aWriteTheExecutorRefusesBeforeExecutingRunsHereOnce() throws Exception {
    int number = createChange().getChange().getId().get();
    peer.refuse("ineligible");
    join();

    userRestSession
        .post(
            "/changes/" + number + "/revisions/current/review",
            new ReviewInput().message("refused by the peer"))
        .assertOK();
    userRestSession.put("/accounts/self/starred.changes/" + number).assertNoContent();
    userSshSession.close();
    userSshSession.open();
    TestRepository<InMemoryRepository> overSsh =
        GitUtil.cloneProject(project, userSshSession.getUrl() + "/" + project.get());
    pushFactory.create(user.newIdent(), overSsh).to("refs/for/master").assertOkStatus();

    assertThat(
            gApi.changes().id(number).messages().stream()
                .filter(message -> message.message.contains("refused by the peer"))
                .count())
        .isEqualTo(1);
    assertThat(peer.requests()).hasSize(3);
    assertThat(forwarding.peers()).hasSize(1);
    assertThat(metrics.getCount(COUNTER, "http", "refused")).isEqualTo(2);
    assertThat(metrics.getCount(COUNTER, "ssh", "refused")).isEqualTo(1);
    assertForwarded("http", 0);
  }

  @Test
  public void aCallerTheExecutorDeniesIsNotExecutedHere() throws Exception {
    int number = createChange().getChange().getId().get();
    // What an executor answers that knows the caller's account to be inactive.
    peer.refuse("account");
    join();

    RestResponse review =
        userRestSession.post(
            "/changes/" + number + "/revisions/current/review",
            new ReviewInput().message("denied by the peer"));
    review.assertForbidden();
    assertThat(review.getEntityContent()).contains("does not execute this request");
    userRestSession.put("/accounts/self/starred.changes/" + number).assertForbidden();
    userSshSession.close();
    userSshSession.open();
    TestRepository<InMemoryRepository> overSsh =
        GitUtil.cloneProject(project, userSshSession.getUrl() + "/" + project.get());
    TransportException denied =
        assertThrows(
            TransportException.class,
            () -> pushFactory.create(user.newIdent(), overSsh).to("refs/for/master"));

    assertThat(denied).hasMessageThat().contains("push not permitted");
    assertThat(gApi.changes().id(number).messages().stream().map(message -> message.message))
        .doesNotContain("denied by the peer");
    assertThat(gApi.changes().query("project:" + project.get() + " owner:" + user.id()).get())
        .isEmpty();
    assertThat(peer.requests()).hasSize(3);
    assertThat(forwarding.peers()).hasSize(1);
    assertThat(metrics.getCount(COUNTER, "http", "denied")).isEqualTo(2);
    assertThat(metrics.getCount(COUNTER, "ssh", "denied")).isEqualTo(1);
    for (String transport : List.of("http", "ssh")) {
      assertThat(metrics.getCount(COUNTER, transport, "refused")).isEqualTo(0);
      assertThat(metrics.getCount(COUNTER, transport, "local")).isEqualTo(0);
    }
    assertForwarded("http", 0);
  }

  @Test
  public void whatAPluginServesRunsWhereItArrived() throws Exception {
    int number = createChange().getChange().getId().get();
    join();

    // No plugin of that name is installed; what matters is where the request was answered.
    userRestSession.post("/changes/" + number + "/owners~check").assertNotFound();
    userRestSession
        .put("/projects/" + project.get() + "/checks~checkers/build")
        .assertNotFound();

    assertThat(peer.requests()).isEmpty();
    assertThat(metrics.getCount(KEPT, "http", "plugin")).isEqualTo(2);
    assertForwarded("http", 0);
  }

  @Test
  public void anExecutorWithoutItsStoreHandsBackWhatItIsSentAndIsLeftAlone() throws Exception {
    int number = createChange().getChange().getId().get();
    peer.refuse("unavailable");
    join();
    peer.hello(503, "");

    userRestSession
        .post(
            "/changes/" + number + "/revisions/current/review",
            new ReviewInput().message("while the executor is cut off"))
        .assertOK();
    assertThat(forwarding.peers()).isEmpty();
    forwarding.probe();
    userRestSession.put("/accounts/self/starred.changes/" + number).assertNoContent();
    userSshSession.close();
    userSshSession.open();
    TestRepository<InMemoryRepository> overSsh =
        GitUtil.cloneProject(project, userSshSession.getUrl() + "/" + project.get());
    pushFactory.create(user.newIdent(), overSsh).to("refs/for/master").assertOkStatus();

    assertThat(
            gApi.changes().id(number).messages().stream()
                .filter(message -> message.message.contains("while the executor is cut off"))
                .count())
        .isEqualTo(1);
    assertThat(gApi.changes().query("project:" + project.get() + " owner:" + user.id()).get())
        .hasSize(1);
    assertThat(peer.requests()).hasSize(1);
    assertThat(metrics.getCount(COUNTER, "http", "refused")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "http", "local")).isEqualTo(1);
    assertForwarded("ssh", 0);
  }

  /**
   * Both nodes lose the store: this node hands its writes to its peer, which is this daemon
   * again and refuses them, so each write is tried here once and crosses at most once.
   */
  @Test
  public void whenNoNodeReachesTheStoreAWriteCrossesOnceAndIsTriedWhereItArrived()
      throws Exception {
    int number = createChange().getChange().getId().get();
    peer.close();
    peer = peerRanked(false);
    join();
    HEALTH.set(Optional.of("the store has not answered for 12 s"));

    userRestSession
        .post(
            "/changes/" + number + "/revisions/current/review",
            new ReviewInput().message("tried once"))
        .assertOK();
    userRestSession.put("/accounts/self/starred.changes/" + number).assertNoContent();

    assertThat(
            gApi.changes().id(number).messages().stream()
                .filter(message -> message.message.contains("tried once"))
                .count())
        .isEqualTo(1);
    assertThat(peer.requests()).hasSize(1);
    assertThat(peer.requests().get(0).header(WriteForwarding.FORWARDED_HEADER))
        .isEqualTo(peer.name());
    assertThat(forwarding.peers()).isEmpty();
    assertThat(metrics.getCount(COUNTER, "http", "refused")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "http", "local")).isEqualTo(1);
    assertForwarded("http", 0);
  }

  @Test
  public void theExecutorKeepsEveryWriteUntilItLosesItsStore() throws Exception {
    int number = createChange().getChange().getId().get();
    peer.close();
    peer = peerRanked(false);
    join();
    assertThat(forwarding.executes(project.get())).isTrue();

    userRestSession
        .post("/changes/" + number + "/revisions/current/review", ReviewInput.noScore())
        .assertOK();

    assertThat(peer.requests()).isEmpty();
    assertThat(metrics.getCount(COUNTER, "http", "local")).isEqualTo(1);

    HEALTH.set(Optional.of("the store has not answered for 12 s"));
    // A peer that still reaches the store.
    peer.answer(200, ImmutableMap.of("Content-Type", "application/json"), ")]}'\n{}");
    assertThat(forwarding.executes(project.get())).isFalse();
    userRestSession
        .post("/changes/" + number + "/revisions/current/review", ReviewInput.noScore())
        .assertOK();

    assertThat(peer.requests()).hasSize(1);
    assertForwarded("http", 1);
  }

  /** A hand-over is counted once the peer's answer has reached the client. */
  private void assertForwarded(String transport, long expected) throws InterruptedException {
    for (int i = 0; i < 100 && metrics.getCount(COUNTER, transport, "forwarded") < expected; i++) {
      Thread.sleep(50);
    }
    assertThat(metrics.getCount(COUNTER, transport, "forwarded")).isEqualTo(expected);
  }

  private String web() {
    return CharMatcher.is('/').trimTrailingFrom(canonicalWebUrl.get());
  }

  private void join() {
    PEERS.set(List.of(peer.address()));
    forwarding.probe();
    assertThat(forwarding.peers()).hasSize(1);
  }

  /** A cluster member that relays to this daemon and ranks before or after it for every write. */
  private FakePeer peerRanked(boolean first) throws IOException {
    for (int i = 0; ; i++) {
      String name = "gerrit-peer-" + i;
      if (WriteRouter.outranks(WriteEligibility.CLUSTER_KEY, name, SELF) == first) {
        return new FakePeer(name)
            .relayTo(new InetSocketAddress("127.0.0.1", forwarding.port()), SELF);
      }
    }
  }
}
