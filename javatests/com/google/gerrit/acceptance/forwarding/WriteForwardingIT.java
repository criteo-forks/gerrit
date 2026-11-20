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
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.SshSession;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestMetricMaker;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteRouter;
import com.google.gerrit.server.util.SocketUtil;
import com.google.gerrit.testing.ConfigSuite;
import com.google.gerrit.util.http.testutil.FakePeer;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.multibindings.OptionalBinder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Authenticated HTTPS hops to a real Gerrit daemon through a recording cluster member. */
@UseSsh
public class WriteForwardingIT extends AbstractDaemonTest {
  private static final String SELF = "gerrit-self";
  private static final String PEER = "gerrit-peer";
  private static final String COUNTER = "write_forwarding/requests";

  private static final AtomicReference<Collection<String>> PEERS = new AtomicReference<>(List.of());

  @ConfigSuite.Default
  public static Config peerConfig() throws IOException {
    Config cfg = FakePeer.projectConfig();
    cfg.setInt("httpd", null, "maxThreads", 12);
    try (ServerSocket free = new ServerSocket(0)) {
      cfg.setInt("walgerrit", null, "forwardingPort", free.getLocalPort());
    }
    return cfg;
  }

  @Inject private WriteForwarding forwarding;
  @Inject private ProjectOperations projectOperations;

  private final TestMetricMaker metrics = TestMetricMaker.getInstance();
  private FakePeer peer;
  private Project.NameKey peerProject;
  private Project.NameKey ownProject;

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
      }
    };
  }

  @Before
  public void setUp() throws Exception {
    assume().that(backendDescribesTheCluster()).isFalse();
    peerProject = newProject(true);
    ownProject = newProject(false);
    peer = new FakePeer(PEER).relayTo(new InetSocketAddress("127.0.0.1", forwarding.port()), SELF);
    // A test daemon has one thread for interactive users and two for batch users; a push that
    // goes out and comes back needs two.
    gApi.groups().id("Service Users").addMembers(user.username());
    metrics.reset();
  }

  @After
  public void tearDown() throws Exception {
    PEERS.set(List.of());
    if (forwarding != null) {
      forwarding.probe();
    }
    if (peer != null) {
      peer.close();
    }
  }

  @Test
  public void sshPushIsHandedToThePeerAndRunsAsThePushingUser() throws Exception {
    join();

    PushOneCommit.Result pushed = pushOverSshAsUser(peerProject);

    pushed.assertOkStatus();
    ChangeInfo change = gApi.changes().id(pushed.getChangeId()).get();
    assertThat(change.project).isEqualTo(peerProject.get());
    assertThat(change.topic).isEqualTo("forwarded-topic");
    assertThat(change.owner._accountId).isEqualTo(user.id().get());
    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void thePeerChecksThePushingUsersPermissions() throws Exception {
    projectOperations
        .project(peerProject)
        .forUpdate()
        .add(block(Permission.PUSH).ref("refs/for/*").group(REGISTERED_USERS))
        .update();
    join();

    TransportException refused =
        assertThrows(TransportException.class, () -> pushOverSshAsUser(peerProject));

    assertThat(refused).hasMessageThat().contains("push not permitted");
    assertThat(gApi.changes().query("project:" + peerProject.get()).get()).isEmpty();
    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void sshPushToAProjectThisDaemonRanksFirstRunsHere() throws Exception {
    join();

    pushOverSshAsUser(ownProject).assertOkStatus();

    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(0);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void sshPushRunsHereWhenItsPoolHasNoThreadToSpare() throws Exception {
    join();

    // The admin is an interactive user, and a test daemon has one thread for those.
    pushOverSsh(adminSshSession, admin, peerProject).assertOkStatus();

    assertThat(metrics.getCount(COUNTER, "ssh", "busy")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(0);
  }

  @Test
  public void sshPushRunsHereWhenThePrivateConnectionClosesBeforeTheAdvertisement()
      throws Exception {
    peer.hangUp();
    join();
    pushOverSshAsUser(peerProject).assertOkStatus();
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(1);
    assertThat(peer.requests()).hasSize(1);
  }

  @Test
  public void aLostStatusNeverRetriesAndTheClientCanReconcile() throws Exception {
    peer.breakDuringReceive(false);
    join();
    Exit silent = receivePackAsUser(peerProject, null);
    assertThat(silent.status()).isEqualTo(1);
    assertThat(silent.stderr()).contains("Nothing was written");
    peer.breakDuringReceive(true);
    join();
    Exit sending = receivePackAsUser(peerProject, "0000");
    assertThat(sending.status()).isEqualTo(1);
    assertThat(sending.stderr()).contains("may or may not have been applied");
    assertThat(peer.requests()).hasSize(2);
    assertThat(metrics.getCount(COUNTER, "ssh", "failed")).isEqualTo(2);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void fourWaitingReceivesLeaveCapacityForPublicRequests() throws Exception {
    List<Socket> held = new ArrayList<>();
    try {
      for (int i = 0; i <= 4; i++) {
        var socket =
            forwarding.tls().getSocketFactory().createSocket("127.0.0.1", forwarding.port());
        held.add(socket);
        socket.setSoTimeout(5000);
        String headers =
            "POST /a/"
                + peerProject.get()
                + "/git-receive-pack HTTP/1.1\r\n"
                + "Host: localhost\r\nTransfer-Encoding: chunked\r\n"
                + "Content-Type: application/x-git-receive-pack-request\r\n"
                + WriteForwarding.FORWARDED_HEADER
                + ": "
                + SELF
                + "\r\n"
                + WriteForwarding.ACCOUNT_HEADER
                + ": "
                + user.id().get()
                + "\r\n"
                + WriteForwarding.ADDRESS_HEADER
                + ": 127.0.0.1\r\n"
                + WriteForwarding.NATIVE_HEADER
                + ": 1\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(UTF_8));
        socket.getOutputStream().flush();
        String status =
            new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8)).readLine();
        assertThat(status).startsWith("HTTP/1.1 " + (i < 4 ? "200" : "503"));
      }
      adminRestSession.get("/config/server/info").assertOK();
      assertThat(metrics.getCount(COUNTER, "ssh", "inbound_busy")).isEqualTo(1);
    } finally {
      for (var socket : held) {
        socket.close();
      }
    }
  }

  @Test
  public void peerWithoutAClientKeyCannotReadHelloOrSubmitAWrite() throws Exception {
    for (String path : List.of("/peerz", "/a/" + peerProject.get() + "/git-receive-pack")) {
      var tls = SSLContext.getInstance("TLSv1.3");
      // Trust the server for this test while presenting no client key.
      tls.init(
          new KeyManager[0],
          new TrustManager[] {
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String type) {}

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String type) {}

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          },
          null);
      var conn =
          (HttpsURLConnection)
              URI.create("https://127.0.0.1:" + forwarding.port() + path)
                  .toURL()
                  .openConnection(Proxy.NO_PROXY);
      conn.setSSLSocketFactory(tls.getSocketFactory());
      conn.setHostnameVerifier((host, session) -> true);
      conn.setReadTimeout(5000);
      if (!path.equals("/peerz")) {
        conn.setRequestMethod("POST");
        conn.setRequestProperty(WriteForwarding.ACCOUNT_HEADER, Integer.toString(admin.id().get()));
      }
      try {
        assertThrows(IOException.class, conn::getResponseCode);
      } finally {
        conn.disconnect();
      }
    }
    assertThat(gApi.changes().query("project:" + peerProject.get()).get()).isEmpty();
  }

  @Test
  public void httpPushIsHandedToThePeerAndRunsAsThePushingUser() throws Exception {
    join();
    TestRepository<InMemoryRepository> repo =
        GitUtil.cloneProject(peerProject, user.getHttpUrl(server) + "/" + peerProject.get());

    PushOneCommit.Result pushed = pushFactory.create(user.newIdent(), repo).to("refs/for/master");

    pushed.assertOkStatus();
    ChangeInfo change = gApi.changes().id(pushed.getChangeId()).get();
    assertThat(change.owner._accountId).isEqualTo(user.id().get());
    List<String> handed = new ArrayList<>();
    for (FakePeer.Request request : peer.requests()) {
      assertThat(request.header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(PEER);
      assertThat(request.header("Authorization")).isNull();
      assertThat(request.header("Cookie")).isNull();
      handed.add(request.method() + " " + request.target());
    }
    assertThat(handed)
        .containsAtLeast(
            "GET /" + peerProject.get() + "/info/refs?service=git-receive-pack",
            "POST /" + peerProject.get() + "/git-receive-pack");
    assertThat(handed.stream().filter(line -> line.contains("upload-pack")).toList()).isEmpty();
    assertThat(metrics.getCount(COUNTER, "http", "forwarded")).isEqualTo(handed.size());
  }

  @Test
  public void restWriteIsHandedToThePeerAndReadsAreNot() throws Exception {
    PushOneCommit.Result pushed =
        pushFactory.create(admin.newIdent(), cloneProject(peerProject)).to("refs/for/master");
    pushed.assertOkStatus();
    String id =
        Url.encode(peerProject.get()) + "~" + gApi.changes().id(pushed.getChangeId()).get()._number;
    join();

    userRestSession.get("/changes/" + id + "/detail").assertOK();
    assertThat(peer.requests()).isEmpty();

    userRestSession
        .post("/changes/" + id + "/revisions/current/review", ReviewInput.recommend())
        .assertOK();

    assertThat(peer.requests()).hasSize(1);
    assertThat(peer.requests().get(0).method()).isEqualTo("POST");
    assertThat(peer.requests().get(0).target())
        .isEqualTo("/a/changes/" + id + "/revisions/current/review");
    assertThat(peer.requests().get(0).header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(PEER);
    ChangeInfo change = gApi.changes().id(pushed.getChangeId()).get();
    assertThat(change.labels.get("Code-Review").recommended._accountId).isEqualTo(user.id().get());
  }

  @Test
  public void writesWithoutABodyAreHandedToThePeer() throws Exception {
    PushOneCommit.Result pushed =
        pushFactory.create(user.newIdent(), cloneProject(peerProject)).to("refs/for/master%topic=t");
    pushed.assertOkStatus();
    String id =
        Url.encode(peerProject.get()) + "~" + gApi.changes().id(pushed.getChangeId()).get()._number;
    join();

    adminRestSession.delete("/changes/" + id + "/topic").assertNoContent();
    adminRestSession.post("/changes/" + id + "/abandon").assertOK();

    assertThat(gApi.changes().id(pushed.getChangeId()).get().topic).isNull();
    assertThat(gApi.changes().id(pushed.getChangeId()).get().status)
        .isEqualTo(ChangeStatus.ABANDONED);
    assertThat(peer.requests()).hasSize(2);
    for (FakePeer.Request request : peer.requests()) {
      assertThat(request.header("Content-Length")).isEqualTo("0");
    }
    assertThat(metrics.getCount(COUNTER, "http", "forwarded")).isEqualTo(2);
  }

  @Test
  public void writesThatNameNoProjectRunHereAndAreCountedByReason() throws Exception {
    PushOneCommit.Result pushed =
        pushFactory.create(user.newIdent(), cloneProject(peerProject)).to("refs/for/master");
    pushed.assertOkStatus();
    int number = gApi.changes().id(pushed.getChangeId()).get()._number;
    join();

    userRestSession
        .post("/changes/" + number + "/revisions/current/review", ReviewInput.noScore())
        .assertOK();
    userRestSession.put("/accounts/self/starred.changes/" + number).assertNoContent();

    assertThat(peer.requests()).isEmpty();
    assertThat(metrics.getCount(COUNTER, "http", "ineligible")).isEqualTo(2);
    assertThat(metrics.getCount("write_forwarding/ineligible", "http", "no_project")).isEqualTo(2);
  }

  @Test
  public void aCommittedReviewWithALostReplyIsNotRepeated() throws Exception {
    var push =
        pushFactory.create(admin.newIdent(), cloneProject(peerProject)).to("refs/for/master");
    push.assertOkStatus();
    var change = gApi.changes().id(push.getChangeId());
    String id = Url.encode(peerProject.get()) + "~" + change.get()._number;
    peer.dropReply();
    join();
    String message = "review with a lost acknowledgement";
    userRestSession
        .post("/changes/" + id + "/revisions/current/review", new ReviewInput().message(message))
        .assertStatus(502);
    assertThat(peer.discardedStatus()).isEqualTo(200);
    assertThat(
            gApi.changes().id(push.getChangeId()).messages().stream()
                .filter(m -> m.message.contains(message))
                .count())
        .isEqualTo(1);
    assertThat(peer.requests()).hasSize(1);
    assertThat(metrics.getCount(COUNTER, "http", "failed")).isEqualTo(1);
  }

  @Test
  public void httpPushToAProjectThisDaemonRanksFirstRunsHere() throws Exception {
    join();
    TestRepository<InMemoryRepository> repo =
        GitUtil.cloneProject(ownProject, user.getHttpUrl(server) + "/" + ownProject.get());

    pushFactory.create(user.newIdent(), repo).to("refs/for/master").assertOkStatus();

    assertThat(peer.requests()).isEmpty();
  }

  private void join() {
    PEERS.set(List.of(peer.address()));
    forwarding.probe();
    assertThat(forwarding.peers()).hasSize(1);
  }

  private PushOneCommit.Result pushOverSshAsUser(Project.NameKey project) throws Exception {
    return pushOverSsh(userSshSession, user, project);
  }

  private PushOneCommit.Result pushOverSsh(
      SshSession session, TestAccount account, Project.NameKey project) throws Exception {
    // The client takes its key from the home directory of the session opened last.
    session.close();
    session.open();
    TestRepository<InMemoryRepository> repo =
        GitUtil.cloneProject(project, session.getUrl() + "/" + project.get());
    PushOneCommit push = pushFactory.create(account.newIdent(), repo);
    push.setPushOptions(List.of("topic=forwarded-topic"));
    return push.to("refs/for/master");
  }

  /** How a command run over a plain SSH channel ended. */
  private record Exit(Integer status, String stderr) {}

  /** Runs {@code git-receive-pack} as the user, sends {@code input} if any, and waits. */
  private Exit receivePackAsUser(Project.NameKey project, @Nullable String input) throws Exception {
    Duration patience = Duration.ofSeconds(30);
    try (SshClient client = SshClient.setUpDefaultClient()) {
      client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
      client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
      client.start();
      try (ClientSession session =
          client.connect(user.username(), "127.0.0.1", sshPort()).verify(patience).getSession()) {
        session.addPublicKeyIdentity(sshKeys.getKeyPair(user));
        session.auth().verify(patience);
        try (ChannelExec channel = session.createExecChannel("git-receive-pack " + project.get())) {
          ByteArrayOutputStream stderr = new ByteArrayOutputStream();
          channel.setOut(new ByteArrayOutputStream());
          channel.setErr(stderr);
          channel.open().verify(patience);
          if (input != null) {
            channel.getInvertedIn().write(input.getBytes(UTF_8));
            channel.getInvertedIn().flush();
          }
          channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), patience);
          return new Exit(channel.getExitStatus(), stderr.toString(UTF_8));
        }
      }
    }
  }

  private int sshPort() {
    return SocketUtil.resolve(cfg.getString("sshd", null, "listenAddress"), 0).getPort();
  }

  private Project.NameKey newProject(boolean peerRanksFirst) {
    for (int i = 0; ; i++) {
      String candidate = name("forwarded-" + i);
      if (WriteRouter.outranks(candidate, PEER, SELF) == peerRanksFirst) {
        return projectOperations.newProject().name(candidate).create();
      }
    }
  }
}
