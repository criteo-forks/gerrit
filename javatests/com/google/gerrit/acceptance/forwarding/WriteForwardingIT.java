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
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteRouter;
import com.google.gerrit.server.util.SocketUtil;
import com.google.gerrit.sshd.PeerRelay;
import com.google.gerrit.util.http.testutil.FakePeer;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.multibindings.OptionalBinder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.PacketLineOut;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Writes that one daemon hands to a peer over real SSH and HTTP links.
 *
 * <p>The daemon is its own peer: a {@link FakePeer} answers the hello under another name and passes
 * HTTP connections back to the daemon, and the hello announces the daemon's own SSH port. A write
 * to a project that ranks the other name first therefore leaves the daemon, comes back marked as
 * handed over, and must be executed. The peers that fail are stand-ins.
 *
 * <p>The test describes the cluster itself, so it is skipped with a backend that describes one.
 */
@UseSsh
public class WriteForwardingIT extends AbstractDaemonTest {
  private static final String SELF = "gerrit-self";
  private static final String PEER = "gerrit-peer";
  private static final String COUNTER = "write_forwarding/requests";

  private static final AtomicReference<Collection<String>> PEERS = new AtomicReference<>(List.of());
  private static final SshSide SSH_SIDE = new SshSide();

  /** What the test needs from the daemon's SSH injector. */
  private static final class SshSide {
    @Inject PeerRelay relay;
    @Inject KeyPairProvider hostKeys;
  }

  /** What a stand-in for a peer's SSH daemon does with a command. */
  private enum Behavior {
    HANG_UP,
    ADVERTISE_THEN_HANG_UP,
    ADVERTISE_AND_HANG_UP_ON_INPUT
  }

  @Inject private WriteForwarding forwarding;
  @Inject private ProjectOperations projectOperations;

  private final TestMetricMaker metrics = TestMetricMaker.getInstance();
  private final List<String> commandsAtFakeSsh = new CopyOnWriteArrayList<>();
  private FakePeer peer;
  private SshServer fakeSsh;
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

  @Override
  public Module createSshModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        requestInjection(SSH_SIDE);
      }
    };
  }

  @Before
  public void setUp() throws Exception {
    assume().that(backendDescribesTheCluster()).isFalse();
    peerProject = newProject(true);
    ownProject = newProject(false);
    peer = new FakePeer(PEER).relayTo(httpAddress());
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
    if (fakeSsh != null) {
      fakeSsh.stop(true);
    }
  }

  @Test
  public void sshPushIsHandedToThePeerAndRunsAsThePushingUser() throws Exception {
    join(peer.sshPort(sshPort()), true);

    PushOneCommit.Result pushed = pushOverSshAsUser(peerProject);

    pushed.assertOkStatus();
    ChangeInfo change = gApi.changes().id(pushed.getChangeId()).get();
    assertThat(change.project).isEqualTo(peerProject.get());
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
    join(peer.sshPort(sshPort()), true);

    TransportException refused =
        assertThrows(TransportException.class, () -> pushOverSshAsUser(peerProject));

    assertThat(refused).hasMessageThat().contains("push not permitted");
    assertThat(gApi.changes().query("project:" + peerProject.get()).get()).isEmpty();
    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void sshPushToAProjectThisDaemonRanksFirstRunsHere() throws Exception {
    join(peer.sshPort(sshPort()), true);

    pushOverSshAsUser(ownProject).assertOkStatus();

    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(0);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void sshPushRunsHereWhenItsPoolHasNoThreadToSpare() throws Exception {
    join(peer.sshPort(sshPort()), true);

    // The admin is an interactive user, and a test daemon has one thread for those.
    pushOverSsh(adminSshSession, admin, peerProject).assertOkStatus();

    assertThat(metrics.getCount(COUNTER, "ssh", "busy")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(0);
  }

  @Test
  public void sshPushRunsHereWhileThePeerHasNoSshLink() throws Exception {
    int closedPort;
    try (java.net.ServerSocket unused = new java.net.ServerSocket(0)) {
      closedPort = unused.getLocalPort();
    }
    join(peer.sshPort(closedPort), false);

    pushOverSshAsUser(peerProject).assertOkStatus();

    assertThat(SSH_SIDE.relay.isLinked(PEER)).isFalse();
    assertThat(metrics.getCount(COUNTER, "ssh", "forwarded")).isEqualTo(0);
  }

  @Test
  public void sshPushRunsHereWhenThePeerHangsUpBeforeItAnswers() throws Exception {
    join(peer.sshPort(startFakeSsh(Behavior.HANG_UP)), true);

    PushOneCommit.Result pushed = pushOverSshAsUser(peerProject);

    pushed.assertOkStatus();
    assertThat(commandsAtFakeSsh).hasSize(1);
    assertThat(commandsAtFakeSsh.get(0)).startsWith("suexec --as " + user.id().get() + " --from ");
    assertThat(commandsAtFakeSsh.get(0)).contains(" -- git-receive-pack ");
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(1);
    assertThat(gApi.changes().query("project:" + peerProject.get()).get()).hasSize(1);
  }

  @Test
  public void sshPushFailsOnceWhenThePeerHangsUpAfterItReceivedInput() throws Exception {
    join(peer.sshPort(startFakeSsh(Behavior.ADVERTISE_AND_HANG_UP_ON_INPUT)), true);

    assertThrows(TransportException.class, () -> pushOverSshAsUser(peerProject));

    assertThat(commandsAtFakeSsh).hasSize(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "failed")).isEqualTo(1);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
    assertThat(gApi.changes().query("project:" + peerProject.get()).get()).isEmpty();
  }

  @Test
  public void theClientLearnsWhetherThePeerReceivedAnythingBeforeItHungUp() throws Exception {
    join(peer.sshPort(startFakeSsh(Behavior.ADVERTISE_THEN_HANG_UP)), true);
    Exit silent = receivePackAsUser(peerProject, null);
    assertThat(silent.status).isEqualTo(1);
    assertThat(silent.stderr).contains("Nothing was written");
    assertThat(silent.stderr).doesNotContain("may or may not");

    fakeSsh.stop(true);
    join(peer.sshPort(startFakeSsh(Behavior.ADVERTISE_AND_HANG_UP_ON_INPUT)), true);
    Exit sending = receivePackAsUser(peerProject, "0000");
    assertThat(sending.status).isEqualTo(1);
    assertThat(sending.stderr).contains("may or may not have been applied");

    assertThat(metrics.getCount(COUNTER, "ssh", "failed")).isEqualTo(2);
    assertThat(metrics.getCount(COUNTER, "ssh", "fallback")).isEqualTo(0);
  }

  @Test
  public void httpPushIsHandedToThePeerAndRunsAsThePushingUser() throws Exception {
    join(peer, false);
    TestRepository<InMemoryRepository> repo =
        GitUtil.cloneProject(peerProject, user.getHttpUrl(server) + "/" + peerProject.get());

    PushOneCommit.Result pushed = pushFactory.create(user.newIdent(), repo).to("refs/for/master");

    pushed.assertOkStatus();
    ChangeInfo change = gApi.changes().id(pushed.getChangeId()).get();
    assertThat(change.owner._accountId).isEqualTo(user.id().get());
    List<String> handed = new ArrayList<>();
    for (FakePeer.Request request : peer.requests()) {
      assertThat(request.header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(SELF);
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
    join(peer, false);

    userRestSession.get("/changes/" + id + "/detail").assertOK();
    assertThat(peer.requests()).isEmpty();

    userRestSession
        .post("/changes/" + id + "/revisions/current/review", ReviewInput.recommend())
        .assertOK();

    assertThat(peer.requests()).hasSize(1);
    assertThat(peer.requests().get(0).method()).isEqualTo("POST");
    assertThat(peer.requests().get(0).target())
        .isEqualTo("/a/changes/" + id + "/revisions/current/review");
    assertThat(peer.requests().get(0).header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(SELF);
    ChangeInfo change = gApi.changes().id(pushed.getChangeId()).get();
    assertThat(change.labels.get("Code-Review").recommended._accountId).isEqualTo(user.id().get());
  }

  @Test
  public void httpPushToAProjectThisDaemonRanksFirstRunsHere() throws Exception {
    join(peer, false);
    TestRepository<InMemoryRepository> repo =
        GitUtil.cloneProject(ownProject, user.getHttpUrl(server) + "/" + ownProject.get());

    pushFactory.create(user.newIdent(), repo).to("refs/for/master").assertOkStatus();

    assertThat(peer.requests()).isEmpty();
  }

  /** Makes {@code fake} this daemon's only peer and, if asked, waits for the SSH link to it. */
  private void join(FakePeer fake, boolean awaitSshLink) throws Exception {
    PEERS.set(List.of(fake.address()));
    forwarding.probe();
    assertThat(forwarding.peers()).hasSize(1);
    if (awaitSshLink) {
      long deadline = System.nanoTime() + 20_000_000_000L;
      while (!SSH_SIDE.relay.isLinked(PEER)) {
        assertWithMessage("no SSH link to the peer").that(System.nanoTime()).isLessThan(deadline);
        Thread.sleep(20);
      }
    }
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
    return pushFactory.create(account.newIdent(), repo).to("refs/for/master");
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

  private Project.NameKey newProject(boolean peerRanksFirst) {
    for (int i = 0; ; i++) {
      String candidate = name("forwarded-" + i);
      if (WriteRouter.outranks(candidate, PEER, SELF) == peerRanksFirst) {
        return projectOperations.newProject().name(candidate).create();
      }
    }
  }

  private InetSocketAddress httpAddress() {
    URI url = URI.create(canonicalWebUrl.get());
    return new InetSocketAddress(url.getHost(), url.getPort());
  }

  private int sshPort() {
    return SocketUtil.resolve(cfg.getString("sshd", null, "listenAddress"), 0).getPort();
  }

  /** Starts a stand-in SSH daemon that presents this daemon's host key; returns its port. */
  private int startFakeSsh(Behavior behavior) throws IOException {
    fakeSsh = SshServer.setUpDefaultServer();
    fakeSsh.setHost("127.0.0.1");
    fakeSsh.setPort(0);
    fakeSsh.setKeyPairProvider(SSH_SIDE.hostKeys);
    fakeSsh.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
    fakeSsh.setCommandFactory(
        (channel, command) -> {
          commandsAtFakeSsh.add(command);
          return new FakeCommand(behavior);
        });
    fakeSsh.start();
    return fakeSsh.getPort();
  }

  private static final class FakeCommand implements Command {
    private final Behavior behavior;
    private InputStream in;
    private OutputStream out;

    FakeCommand(Behavior behavior) {
      this.behavior = behavior;
    }

    @Override
    public void setInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
      this.out = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {}

    @Override
    public void setExitCallback(ExitCallback callback) {}

    @Override
    public void start(ChannelSession channel, Environment env) {
      Thread command =
          new Thread(
              () -> {
                try {
                  if (behavior != Behavior.HANG_UP) {
                    out.write(emptyAdvertisement());
                    out.flush();
                  }
                  if (behavior == Behavior.ADVERTISE_AND_HANG_UP_ON_INPUT) {
                    @SuppressWarnings("unused")
                    int first = in.read();
                  } else if (behavior == Behavior.ADVERTISE_THEN_HANG_UP) {
                    // Let the advertisement leave before the session goes.
                    Thread.sleep(200);
                  }
                } catch (IOException | InterruptedException ended) {
                  // Hang up all the same.
                }
                channel.getSession().close(true);
              },
              "FakeSshPeer");
      command.setDaemon(true);
      command.start();
    }

    @Override
    public void destroy(ChannelSession channel) {}

    private static byte[] emptyAdvertisement() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      PacketLineOut lines = new PacketLineOut(bytes);
      lines.writeString(
          ObjectId.zeroId().name() + " capabilities^{}\0report-status delete-refs ofs-delta\n");
      lines.end();
      return bytes.toByteArray();
    }
  }
}
