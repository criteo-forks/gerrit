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
package com.google.gerrit.httpd;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.entities.Account;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.WriteEligibility;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WritePeerTls;
import com.google.gerrit.server.WriteRouter;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.gerrit.util.http.testutil.FakeHttpServletRequest;
import com.google.gerrit.util.http.testutil.FakeHttpServletResponse;
import com.google.gerrit.util.http.testutil.FakePeer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

public class WriteForwardingFilterTest {
  private static final String SELF = "gerrit-0";
  private static final String PEER = "gerrit-1";

  /** A request with a body, which counts how much of the body was read. */
  private static final class Request extends FakeHttpServletRequest {
    private final byte[] body;
    final AtomicInteger bodyReads = new AtomicInteger();
    boolean lengthKnown = true;
    boolean declaresNoBody;

    Request(String method, String path, String body) {
      super("gerrit.example.com", 443, "", "");
      setMethod(method);
      int query = path.indexOf('?');
      setPathInfo(query < 0 ? path : path.substring(0, query));
      if (query >= 0) {
        setQueryString(path.substring(query + 1));
      }
      this.body = body.getBytes(UTF_8);
      addHeader("Host", "gerrit.example.com");
      if (!body.isEmpty()) {
        addHeader(
            "Content-Type",
            path.endsWith("/git-receive-pack")
                ? "application/x-git-receive-pack-request"
                : "application/json");
      }
    }

    @Override
    public int getContentLength() {
      return declaresNoBody ? 0 : lengthKnown && body.length > 0 ? body.length : -1;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          bodyReads.incrementAndGet();
          return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          bodyReads.incrementAndGet();
          return in.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {}
      };
    }
  }

  private final WebSession session = mock(WebSession.class);
  private final IdentifiedUser user = mock(IdentifiedUser.class);
  private FakePeer peer;
  private WriteForwarding forwarding;
  private WriteForwardingFilter filter;
  private String peerProject;
  private String ownProject;
  private final AtomicInteger executedHere = new AtomicInteger();
  private final FilterChain chain = (request, response) -> executedHere.incrementAndGet();

  @Before
  public void setUp() throws Exception {
    when(session.getUser()).thenReturn(user);
    when(session.isAccessPathOk(AccessPath.REST_API)).thenReturn(true);
    when(user.isIdentifiedUser()).thenReturn(true);
    when(user.getAccountId()).thenReturn(Account.id(42));
    peer = new FakePeer(PEER);
    forwarding = node(SELF, FakePeer.config());
    filter = new WriteForwardingFilter(forwarding, 2, () -> session);
    for (int i = 0; peerProject == null || ownProject == null; i++) {
      String project = "team/repo-" + i;
      if (WriteRouter.outranks(project, PEER, SELF)) {
        peerProject = peerProject == null ? project : peerProject;
      } else {
        ownProject = ownProject == null ? project : ownProject;
      }
    }
  }

  @After
  public void tearDown() throws Exception {
    filter.destroy();
    peer.close();
  }

  private WriteForwarding node(String name, Config config) {
    WriteForwarding node =
        new WriteForwarding(
            () -> name,
            () -> List.of(peer.address()),
            Optional::empty,
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker());
    node.probe();
    return node;
  }

  @Test
  public void handsAPushToThePreferredPeer() throws Exception {
    peer.answer(
        200, ImmutableMap.of("Content-Type", "application/x-git-receive-pack-result"), "ok");
    Request req = new Request("POST", "/a/" + peerProject + ".git/git-receive-pack", "PACK-DATA");
    req.addHeader("Cookie", "GerritAccount=private");
    req.addHeader("X-Gerrit-Auth", "private");
    req.addHeader("Authorization", "Basic dXNlcjpzZWNyZXQ=");
    req.addHeader("X-Forwarded-For", "203.0.113.9");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(rsp.getStatus()).isEqualTo(200);
    assertThat(rsp.getHeader("Content-Type")).isEqualTo("application/x-git-receive-pack-result");
    assertThat(rsp.getActualBodyString()).isEqualTo("ok");
    FakePeer.Request seen = peer.requests().get(0);
    assertThat(peer.requests()).hasSize(1);
    assertThat(seen.method()).isEqualTo("POST");
    assertThat(seen.target()).isEqualTo("/a/" + peerProject + ".git/git-receive-pack");
    assertThat(new String(seen.body(), UTF_8)).isEqualTo("PACK-DATA");
    for (String name :
        List.of("Authorization", "Cookie", "X-Gerrit-Auth", "X-Site-Identity", "X-Forwarded-For")) {
      assertThat(seen.header(name)).isNull();
    }
    assertThat(seen.header("Host")).isEqualTo(peer.address());
    assertThat(seen.header("Content-Type")).isEqualTo("application/x-git-receive-pack-request");
    assertThat(seen.header(WriteForwarding.ADDRESS_HEADER)).isEqualTo(req.getRemoteAddr());
    assertThat(seen.header(WriteForwarding.ACCOUNT_HEADER)).isEqualTo("42");
    assertThat(seen.header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(PEER);
  }

  @Test
  public void keepsTheEncodedAddressOfARestWrite() throws Exception {
    String encoded = peerProject.replace("/", "%2F");
    Request req =
        new Request("POST", "/a/changes/" + encoded + "~42/revisions/current/review", "{}");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests().get(0).target())
        .isEqualTo("/a/changes/" + encoded + "~42/revisions/current/review");
    assertThat(peer.requests().get(0).header("X-Forwarded-For")).isNull();
  }

  @Test
  public void executesWhatAPeerHandedOver() throws Exception {
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    req.setAttribute(WriteForwarding.PEER_CONNECTOR, Boolean.TRUE);

    filter.doFilter(req, new FakeHttpServletResponse(), chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(peer.requests()).isEmpty();
    assertThat(req.bodyReads.get()).isEqualTo(0);
  }

  @Test
  public void executesTheProjectsItRanksFirst() throws Exception {
    filter.doFilter(
        new Request("POST", "/a/" + ownProject + "/git-receive-pack", "PACK-DATA"),
        new FakeHttpServletResponse(),
        chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void executesReadsAndWritesThatNameNoProject() throws Exception {
    List<Request> kept =
        List.of(
            new Request("GET", "/a/changes/" + peerProject.replace("/", "%2F") + "~42/detail", ""),
            new Request("GET", "/" + peerProject + "/info/refs?service=git-upload-pack", ""),
            new Request("POST", "/" + peerProject + "/git-upload-pack", "want"),
            new Request("POST", "/a/changes/", "{}"),
            new Request("POST", "/a/changes/42/revisions/current/review", "{}"),
            new Request("PUT", "/a/accounts/self/name", "{}"));
    for (Request req : kept) {
      filter.doFilter(req, new FakeHttpServletResponse(), chain);
    }

    assertThat(executedHere.get()).isEqualTo(kept.size());
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void executesAWriteHereWhenItsAddressIsNotAPlainPath() throws Exception {
    List<Request> kept =
        List.of(
            new Request("POST", "//" + peerProject + "/git-receive-pack", "PACK-DATA"),
            new Request("POST", "/a/projects/" + peerProject.replace("/", "%2F") + "/a|b", "{}"));
    for (Request req : kept) {
      assertThat(WriteEligibility.project("POST", req.getRequestURI(), null)).isNotNull();
      filter.doFilter(req, new FakeHttpServletResponse(), chain);
    }

    assertThat(executedHere.get()).isEqualTo(kept.size());
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void executesAWriteHereWhenThePeerCannotBeReached() throws Exception {
    peer.close();
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(req.bodyReads.get()).isEqualTo(0);
    assertThat(rsp.isCommitted()).isFalse();
    assertThat(forwarding.peers()).isEmpty();
  }

  @Test
  public void executesAnAdvertisementHereWhenThePeerHangsUp() throws Exception {
    peer.hangUp();
    Request req = new Request("GET", "/" + peerProject + "/info/refs?service=git-receive-pack", "");

    filter.doFilter(req, new FakeHttpServletResponse(), chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(peer.requests()).hasSize(1);
    assertThat(forwarding.peers()).isEmpty();
  }

  @Test
  public void executesAnAdvertisementHereWhenThePeerIsNotServing() throws Exception {
    peer.answer(503, ImmutableMap.of("Retry-After", "30"), "starting");
    Request req = new Request("GET", "/" + peerProject + "/info/refs?service=git-receive-pack", "");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(rsp.isCommitted()).isFalse();
  }

  @Test
  public void reportsAWriteThatThePeerMayHaveAppliedAndDoesNotRetryIt() throws Exception {
    peer.hangUp();
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(rsp.getStatus()).isEqualTo(502);
    assertThat(rsp.getActualBodyString()).contains("may or may not have applied");
    assertThat(peer.requests()).hasSize(1);
    assertThat(forwarding.peers()).isEmpty();
  }

  @Test
  public void relaysThePeersRefusal() throws Exception {
    peer.answer(403, ImmutableMap.of("Content-Type", "text/plain"), "push not permitted");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(
        new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA"), rsp, chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(rsp.getStatus()).isEqualTo(403);
    assertThat(rsp.getActualBodyString()).isEqualTo("push not permitted");
    assertThat(forwarding.peers()).hasSize(1);
  }

  @Test
  public void publicHelloAndIdentityHeadersAreRejectedBeforeDispatch() throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(6);
    PeerWriteFilter inbound =
        new PeerWriteFilter(forwarding, mock(AccountCache.class), () -> session, threads);
    Request forged = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "body");
    forged.addHeader(WriteForwarding.FORWARDED_HEADER, SELF);
    for (Request req : List.of(forged, new Request("GET", "/peerz", ""))) {
      FakeHttpServletResponse rsp = new FakeHttpServletResponse();
      inbound.doFilter(req, rsp, chain);
      assertThat(rsp.getStatus()).isEqualTo(404);
      assertThat(req.bodyReads.get()).isEqualTo(0);
    }
    assertThat(executedHere.get()).isEqualTo(0);
  }

  @Test
  public void credentialsInQueriesAnonymousUsersAndMissingCsrfStayAtEntry() throws Exception {
    Request token =
        new Request("POST", "/a/projects/" + peerProject + "?access_token=private", "{}");
    filter.doFilter(token, new FakeHttpServletResponse(), chain);
    when(session.isAccessPathOk(AccessPath.REST_API)).thenReturn(false);
    filter.doFilter(
        new Request("POST", "/a/projects/" + peerProject, "{}"),
        new FakeHttpServletResponse(),
        chain);
    when(session.getUser()).thenReturn(mock(CurrentUser.class));
    Request anon = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "slow");
    filter.doFilter(anon, new FakeHttpServletResponse(), chain);
    assertThat(anon.bodyReads.get()).isEqualTo(0);
    assertThat(executedHere.get()).isEqualTo(3);
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void aReplacedDestinationGetsNoIdentityOrBody() throws Exception {
    // The route was selected before the destination changed to a different cluster key.
    var other = WritePeerTls.fromSecret("AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    try (FakePeer replacement = new FakePeer(PEER, other)) {
      var selected = mock(WriteForwarding.class);
      when(selected.tls()).thenReturn(forwarding.tls());
      when(selected.active()).thenReturn(true);
      when(selected.placement()).thenReturn(WriteEligibility.Placement.PROJECT);
      when(selected.preferredPeer(ArgumentMatchers.eq(peerProject)))
          .thenReturn(Optional.of(new WriteForwarding.Peer(PEER, "127.0.0.1", replacement.port())));
      var relay = new WriteForwardingFilter(selected, 1, () -> session);
      try {
        var req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "pack");
        relay.doFilter(req, new FakeHttpServletResponse(), chain);
        assertThat(req.bodyReads.get()).isEqualTo(0);
        assertThat(replacement.requests()).isEmpty();
        assertThat(executedHere.get()).isEqualTo(1);
      } finally {
        relay.destroy();
      }
    }
  }

  @Test
  public void onlyAnActiveAccountAndTheNamedDestinationCanExecuteAnEligibleWrite()
      throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(3);
    AccountCache accounts = mock(AccountCache.class);
    var inbound = new PeerWriteFilter(forwarding, accounts, () -> session, threads);
    var wrongAddress = incoming("client.example");
    var inactive = incoming("192.0.2.1");
    var state = mock(AccountState.class);
    when(accounts.get(Account.id(42))).thenReturn(Optional.of(state));
    when(state.account()).thenReturn(mock(Account.class));
    var cookie = incoming("192.0.2.1");
    cookie.addHeader("Cookie", "unexpected");
    var wrongNode = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "body");
    wrongNode.setAttribute(WriteForwarding.PEER_CONNECTOR, true);
    wrongNode.addHeader(WriteForwarding.FORWARDED_HEADER, PEER);
    wrongNode.addHeader(WriteForwarding.ACCOUNT_HEADER, "42");
    wrongNode.addHeader(WriteForwarding.ADDRESS_HEADER, "192.0.2.1");
    var wrongPath = incoming("192.0.2.1");
    wrongPath.setPathInfo("/a/accounts/self/password.http");
    ImmutableMap<Request, String> refused =
        ImmutableMap.of(
            wrongAddress, "identity",
            wrongNode, "destination",
            wrongPath, "ineligible",
            cookie, "credentials",
            inactive, "account");
    for (var req : refused.keySet()) {
      var rsp = new FakeHttpServletResponse();
      inbound.doFilter(req, rsp, chain);
      assertThat(rsp.getStatus()).isEqualTo(403);
      assertThat(rsp.getHeader(WriteForwarding.REFUSED_HEADER)).isEqualTo(refused.get(req));
      assertThat(req.bodyReads.get()).isEqualTo(0);
      // Only what the destination says about the request or itself lets the entry node execute.
      assertThat(WriteForwarding.executedAtEntry(refused.get(req)))
          .isEqualTo(req == wrongNode || req == wrongPath);
    }
    assertThat(executedHere.get()).isEqualTo(0);
  }

  @Test
  public void formBodiesNeverEnterThePeerChannelEvenOnAGitUrl() throws Exception {
    for (String path :
        List.of("/a/" + peerProject + "/git-receive-pack", "/a/projects/" + peerProject)) {
      var req = new Request("POST", path, "");
      req.addHeader("Content-Type", "application/x-www-form-urlencoded");
      filter.doFilter(req, new FakeHttpServletResponse(), chain);
      assertThat(req.bodyReads.get()).isEqualTo(0);
    }
    assertThat(executedHere.get()).isEqualTo(2);
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void aSiteHeaderStaysAtEntry() throws Exception {
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "data");
    req.addHeader("X-Site-Identity", "private");
    filter.doFilter(req, new FakeHttpServletResponse(), chain);
    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests()).hasSize(1);
    assertThat(peer.requests().get(0).header("X-Site-Identity")).isNull();
  }

  @Test
  public void aBrowserWriteCrossesWithoutItsSessionAndTheAnswerKeepsItsCorsHeaders()
      throws Exception {
    peer.answer(
        200,
        ImmutableMap.of(
            "Content-Type", "application/json",
            "Access-Control-Allow-Origin", "https://gerrit.example.com",
            "Set-Cookie", "GerritAccount=peer"),
        ")]}'\n{}");
    String id = peerProject.replace("/", "%2F") + "~42";
    Request req = new Request("POST", "/changes/" + id + "/revisions/1/review?notify=NONE", "{}");
    req.addHeader("Cookie", "GerritAccount=private; XSRF_TOKEN=private");
    req.addHeader("X-Gerrit-Auth", "private");
    req.addHeader("Origin", "https://gerrit.example.com");
    req.addHeader("Referer", "https://gerrit.example.com/c/42");
    req.addHeader("Sec-Fetch-Site", "same-origin");
    req.addHeader("Sec-CH-UA", "\"Chromium\";v=\"140\"");
    req.addHeader("Priority", "u=1, i");
    req.addHeader("X-Gerrit-UpdatedRef-Enabled", "true");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(0);
    FakePeer.Request seen = peer.requests().get(0);
    assertThat(seen.target()).isEqualTo("/changes/" + id + "/revisions/1/review?notify=NONE");
    for (String name :
        List.of("Cookie", "X-Gerrit-Auth", "Authorization", "Referer", "Sec-Fetch-Site", "Priority")) {
      assertWithMessage(name).that(seen.header(name)).isNull();
    }
    assertThat(seen.header("Origin")).isEqualTo("https://gerrit.example.com");
    assertThat(seen.header("X-Gerrit-UpdatedRef-Enabled")).isEqualTo("true");
    assertThat(seen.header(WriteForwarding.ACCOUNT_HEADER)).isEqualTo("42");
    assertThat(rsp.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://gerrit.example.com");
    assertThat(rsp.getHeader("Set-Cookie")).isNull();
  }

  @Test
  public void aBrowserWriteWithoutTheSessionsCsrfTokenIsNotHandedOver() throws Exception {
    when(session.isAccessPathOk(AccessPath.REST_API)).thenReturn(false);
    Request req = new Request("POST", "/changes/" + peerProject + "~42/abandon", "{}");
    req.addHeader("Cookie", "GerritAccount=private");
    req.addHeader("Origin", "https://evil.example.com");

    filter.doFilter(req, new FakeHttpServletResponse(), chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void runAsImpersonationAndAnonymousWritesStayAtEntry() throws Exception {
    Request runAs = new Request("POST", "/a/projects/" + peerProject + "/branches/x", "{}");
    runAs.addHeader("X-Gerrit-RunAs", "1000001");
    filter.doFilter(runAs, new FakeHttpServletResponse(), chain);
    when(user.isImpersonated()).thenReturn(true);
    filter.doFilter(
        new Request("POST", "/a/projects/" + peerProject + "/branches/x", "{}"),
        new FakeHttpServletResponse(),
        chain);
    when(session.getUser()).thenReturn(mock(CurrentUser.class));
    filter.doFilter(
        new Request("POST", "/a/projects/" + peerProject + "/branches/x", "{}"),
        new FakeHttpServletResponse(),
        chain);

    assertThat(executedHere.get()).isEqualTo(3);
    assertThat(peer.requests()).isEmpty();
  }

  @Test
  public void nodeLocalEndpointsFormsAndTokenQueriesStayAtEntryUnderEitherPlacement()
      throws Exception {
    String change = "/a/changes/" + peerProject.replace("/", "%2F") + "~42";
    List<Request> kept =
        List.of(
            new Request("POST", "/a/config/server/caches/projects/flush", ""),
            new Request("DELETE", "/a/config/server/tasks/1a2b", ""),
            new Request("POST", "/a/plugins/replication/gerrit~reload", ""),
            new Request("PUT", change + "/revisions/1/files/a.txt/reviewed", ""),
            new Request("DELETE", change + "/revisions/1/files/a.txt/gerrit~reviewed", ""),
            new Request("POST", "/a/accounts/self/external.ids:delete", "[]"),
            new Request("POST", change + "/index", ""),
            new Request("POST", change + "/abandon?access_token=private", "{}"),
            new Request("POST", change + "/topic?%24m=PUT&%24ct=application%2Fjson", "{}"),
            form(change + "/abandon"));
    for (WriteForwarding node : List.of(forwarding, clusterNode())) {
      WriteForwardingFilter entry = new WriteForwardingFilter(node, 2, () -> session);
      try {
        executedHere.set(0);
        for (Request req : kept) {
          entry.doFilter(req, new FakeHttpServletResponse(), chain);
          assertThat(req.bodyReads.get()).isEqualTo(0);
        }
        assertThat(executedHere.get()).isEqualTo(kept.size());
        assertThat(peer.requests()).isEmpty();
      } finally {
        entry.destroy();
      }
    }
  }

  @Test
  public void withAClusterPlacementWritesThatNameNoProjectAreHandedOver() throws Exception {
    WriteForwardingFilter entry = new WriteForwardingFilter(clusterNode(), 2, () -> session);
    List<Request> writes =
        List.of(
            new Request("POST", "/a/changes/", "{}"),
            new Request("POST", "/a/changes/42/revisions/current/review", "{}"),
            new Request("POST", "/a/changes/I0123456789012345678901234567890123456789/submit", "{}"),
            new Request("PUT", "/a/accounts/self/name", "{}"),
            new Request("PUT", "/accounts/self/starred.changes/42", ""),
            new Request("PUT", "/a/groups/reviewers/members/7", ""),
            new Request("POST", "/a/" + ownProject + "/git-receive-pack", "PACK-DATA"));
    try {
      for (Request req : writes) {
        entry.doFilter(req, new FakeHttpServletResponse(), chain);
      }
    } finally {
      entry.destroy();
    }

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests()).hasSize(writes.size());
  }

  @Test
  public void anEmptyBodyKeepsItsLengthAndADeleteWithoutOneIsHandedOver() throws Exception {
    String change = "/a/changes/" + peerProject.replace("/", "%2F") + "~42";
    Request empty = new Request("POST", change + "/abandon", "");
    empty.declaresNoBody = true;
    Request delete = new Request("DELETE", change + "/topic", "");

    filter.doFilter(empty, new FakeHttpServletResponse(), chain);
    filter.doFilter(delete, new FakeHttpServletResponse(), chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests()).hasSize(2);
    for (FakePeer.Request seen : peer.requests()) {
      assertThat(seen.header("Content-Length")).isEqualTo("0");
      assertThat(seen.header("Transfer-Encoding")).isNull();
      assertThat(seen.header("Expect")).isNull();
    }
    assertThat(empty.bodyReads.get()).isEqualTo(0);
  }

  @Test
  public void aRequestThePeerRefusedBeforeExecutingRunsHere() throws Exception {
    for (String reason : List.of("ineligible", "destination")) {
      peer.refuse(reason);
      Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
      FakeHttpServletResponse rsp = new FakeHttpServletResponse();
      executedHere.set(0);

      filter.doFilter(req, rsp, chain);

      assertWithMessage(reason).that(executedHere.get()).isEqualTo(1);
      assertThat(req.bodyReads.get()).isEqualTo(0);
      assertThat(rsp.isCommitted()).isFalse();
      assertThat(forwarding.peers()).hasSize(1);
    }
    assertThat(peer.requests()).hasSize(2);
  }

  @Test
  public void aPeerThatCannotExecuteWritesIsLeftUntilItAnswersAHelloAgain() throws Exception {
    peer.refuse("unavailable");
    peer.hello(503, "");
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(req.bodyReads.get()).isEqualTo(0);
    assertThat(rsp.isCommitted()).isFalse();
    assertThat(forwarding.peers()).isEmpty();

    forwarding.probe();
    filter.doFilter(
        new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA"),
        new FakeHttpServletResponse(),
        chain);
    assertThat(executedHere.get()).isEqualTo(2);
    assertThat(peer.requests()).hasSize(1);

    peer.hello(200, PEER);
    peer.answer(200, ImmutableMap.of(), "");
    forwarding.probe();
    filter.doFilter(
        new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA"),
        new FakeHttpServletResponse(),
        chain);
    assertThat(executedHere.get()).isEqualTo(2);
    assertThat(peer.requests()).hasSize(2);
  }

  @Test
  public void aCallerThePeerDeniesIsAnsweredWithItsRefusalAndNotExecutedHere() throws Exception {
    for (String reason : List.of("account", "identity", "credentials")) {
      peer.refuse(reason);
      Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
      FakeHttpServletResponse rsp = new FakeHttpServletResponse();

      filter.doFilter(req, rsp, chain);

      assertWithMessage(reason).that(rsp.getStatus()).isEqualTo(403);
      assertThat(rsp.getActualBodyString()).contains("does not execute this request");
      assertThat(req.bodyReads.get()).isEqualTo(0);
    }
    peer.refuse("a-reason-of-a-later-version");
    FakeHttpServletResponse unknown = new FakeHttpServletResponse();
    filter.doFilter(
        new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA"),
        unknown,
        chain);

    assertThat(unknown.getStatus()).isEqualTo(502);
    assertThat(unknown.getActualBodyString()).contains("Nothing was written");
    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests()).hasSize(4);
    assertThat(forwarding.peers()).hasSize(1);
  }

  /**
   * The peer's refusal arrives after this node stopped waiting for it and began to send the
   * client's body, which it cannot send again.
   */
  @Test
  public void aRefusalThatArrivesAfterTheBodyBeganToFlowIsNotExecutedHere() throws Exception {
    for (String reason : List.of("ineligible", "account")) {
      peer.refuseAfterInput(reason);
      Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
      FakeHttpServletResponse rsp = new FakeHttpServletResponse();

      filter.doFilter(req, rsp, chain);

      assertWithMessage(reason).that(executedHere.get()).isEqualTo(0);
      assertThat(req.bodyReads.get()).isGreaterThan(0);
      assertThat(rsp.getStatus()).isEqualTo(reason.equals("account") ? 403 : 503);
      assertThat(rsp.getActualBodyString()).contains("Nothing was written");
    }
    assertThat(peer.requests()).hasSize(2);
  }

  @Test
  public void theDestinationRefusesWhatItsOwnPlacementKeepsLocalAndSaysSo() throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(30);
    AccountCache accounts = mock(AccountCache.class);
    var state = mock(AccountState.class);
    var account = mock(Account.class);
    when(accounts.get(Account.id(42))).thenReturn(Optional.of(state));
    when(state.account()).thenReturn(account);
    when(account.isActive()).thenReturn(true);
    String change = "/a/changes/" + peerProject.replace("/", "%2F") + "~42";
    var runAs = incoming("POST", change + "/abandon", "{}");
    runAs.addHeader("X-Gerrit-RunAs", "1000001");
    var csrf = incoming("POST", change + "/abandon", "{}");
    csrf.addHeader("X-Gerrit-Auth", "relayed");
    List<Request> refusedEverywhere =
        List.of(
            runAs,
            csrf,
            incoming("GET", change + "/detail", ""),
            incoming("POST", "/a/config/server/caches/projects/flush", ""),
            incoming("POST", "/a/plugins/replication/gerrit~reload", ""),
            incoming("PUT", change + "/revisions/1/files/a.txt/reviewed", ""),
            incoming("POST", "/a/accounts/self/external.ids:delete", "[]"),
            incoming("POST", change + "/abandon?access_token=private", "{}"),
            incoming("POST", change + "/abandon?%24m=PUT", "{}"));
    List<Request> clusterOnly =
        List.of(
            incoming("PUT", "/a/accounts/self/name", "{}"),
            incoming("POST", "/a/changes/42/revisions/current/review", "{}"));
    List<Request> projectOnly =
        List.of(
            incoming("POST", change + "/owners~check", "{}"),
            incoming("PUT", "/a/projects/" + peerProject.replace("/", "%2F") + "/x~rules/1", "{}"));
    var origin = incoming("POST", change + "/abandon", "{}");
    origin.addHeader("Origin", "https://gerrit.example.com");

    Config clusterConfig = FakePeer.config();
    clusterConfig.setString("walgerrit", null, "placement", "cluster");
    for (WriteForwarding node : List.of(forwarding, node(SELF, clusterConfig))) {
      var inbound = new PeerWriteFilter(node, accounts, () -> session, threads);
      for (Request req : refusedEverywhere) {
        var rsp = new FakeHttpServletResponse();
        inbound.doFilter(req, rsp, chain);
        assertWithMessage(req.getRequestURI()).that(rsp.getStatus()).isEqualTo(403);
        assertThat(rsp.getHeader(WriteForwarding.REFUSED_HEADER)).isNotNull();
        assertThat(req.bodyReads.get()).isEqualTo(0);
      }
      assertThat(executedHere.get()).isEqualTo(0);
      boolean cluster = node != forwarding;
      for (Request req : clusterOnly) {
        var rsp = new FakeHttpServletResponse();
        inbound.doFilter(req, rsp, chain);
        assertThat(rsp.getHeader(WriteForwarding.REFUSED_HEADER) == null).isEqualTo(cluster);
      }
      assertThat(executedHere.get()).isEqualTo(cluster ? clusterOnly.size() : 0);
      executedHere.set(0);
      for (Request req : projectOnly) {
        var rsp = new FakeHttpServletResponse();
        inbound.doFilter(req, rsp, chain);
        assertThat(rsp.getHeader(WriteForwarding.REFUSED_HEADER))
            .isEqualTo(cluster ? "ineligible" : null);
      }
      assertThat(executedHere.get()).isEqualTo(cluster ? 0 : projectOnly.size());
      executedHere.set(0);
      inbound.doFilter(origin, new FakeHttpServletResponse(), chain);
      assertThat(executedHere.get()).isEqualTo(1);
      executedHere.set(0);
    }
  }

  @Test
  public void aNodeThatCannotExecuteWritesAnswersNoHello() throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(3);
    java.util.concurrent.atomic.AtomicReference<Optional<String>> health =
        new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
    WriteForwarding node =
        new WriteForwarding(
            () -> SELF,
            () -> List.of(peer.address()),
            health::get,
            FakePeer.config(),
            GerritRuntime.DAEMON,
            new DisabledMetricMaker());
    var inbound = new PeerWriteFilter(node, mock(AccountCache.class), () -> session, threads);

    var ready = new FakeHttpServletResponse();
    inbound.doFilter(hello(), ready, chain);
    assertThat(ready.getStatus()).isEqualTo(200);
    assertThat(ready.getActualBodyString()).isEqualTo(SELF);
    assertThat(ready.getHeader(WriteForwarding.PROTOCOL_HEADER)).isEqualTo(WriteForwarding.PROTOCOL);

    health.set(Optional.of("the store has not answered for 12 s"));
    var cutOff = new FakeHttpServletResponse();
    inbound.doFilter(hello(), cutOff, chain);
    assertThat(cutOff.getStatus()).isEqualTo(503);

    // A peer that has not asked again yet is told so, before anything is read or executed.
    Request late = incoming("POST", "/a/" + ownProject + "/git-receive-pack", "PACK-DATA");
    var refused = new FakeHttpServletResponse();
    inbound.doFilter(late, refused, chain);
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getHeader(WriteForwarding.REFUSED_HEADER)).isEqualTo("unavailable");
    assertThat(late.bodyReads.get()).isEqualTo(0);
    assertThat(executedHere.get()).isEqualTo(0);
  }

  @Test
  public void aNodeTakesItsWritesBackOnceItsPeersHaveCountedItsHellos() throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(3);
    Config config = FakePeer.config();
    config.setInt("walgerrit", null, "forwardingJoinProbes", 2);
    java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1);
    WriteForwarding node =
        new WriteForwarding(
            () -> SELF,
            () -> List.of(peer.address()),
            Optional::empty,
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker(),
            clock::get);
    node.probe();
    var inbound = new PeerWriteFilter(node, mock(AccountCache.class), () -> session, threads);
    assertThat(node.executes(ownProject)).isFalse();

    inbound.doFilter(hello(), new FakeHttpServletResponse(), chain);
    clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(1));

    assertThat(node.executes(ownProject)).isTrue();
    assertThat(node.executes(peerProject)).isFalse();
  }

  private static Request hello() {
    Request hello = new Request("GET", "/peerz", "");
    hello.setAttribute(WriteForwarding.PEER_CONNECTOR, Boolean.TRUE);
    return hello;
  }

  private static Request form(String path) {
    Request form = new Request("POST", path, "");
    form.addHeader("Content-Type", "application/x-www-form-urlencoded");
    return form;
  }

  /** A node for which the peer is the executor of every write. */
  private WriteForwarding clusterNode() {
    Config config = FakePeer.config();
    config.setString("walgerrit", null, "placement", "cluster");
    for (int i = 0; ; i++) {
      WriteForwarding node = node("gerrit-entry-" + i, config);
      if (!node.executes("any")) {
        return node;
      }
    }
  }

  private Request incoming(String method, String path, String body) {
    Request req = new Request(method, path, body);
    req.setAttribute(WriteForwarding.PEER_CONNECTOR, Boolean.TRUE);
    req.addHeader(WriteForwarding.FORWARDED_HEADER, SELF);
    req.addHeader(WriteForwarding.ADDRESS_HEADER, "192.0.2.1");
    req.addHeader(WriteForwarding.ACCOUNT_HEADER, "42");
    return req;
  }

  @Test
  public void inboundSlotIsSharedAcrossSourcesAndReleasedOnFailure() throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(3);
    var accounts = mock(AccountCache.class);
    var state = mock(AccountState.class);
    var account = mock(Account.class);
    when(accounts.get(Account.id(42))).thenReturn(Optional.of(state));
    when(state.account()).thenReturn(account);
    when(account.isActive()).thenReturn(true);
    var inbound = new PeerWriteFilter(forwarding, accounts, () -> session, threads);
    Request first = incoming("192.0.2.1");
    Request second = incoming("192.0.2.2");
    FakeHttpServletResponse busy = new FakeHttpServletResponse();
    Assert.assertThrows(
        IOException.class,
        () ->
            inbound.doFilter(
                first,
                new FakeHttpServletResponse(),
                (req, rsp) -> {
                  assertThat(req.getRemoteAddr()).isEqualTo("192.0.2.1");
                  assertThat(new HttpRemotePeerProvider(first).get())
                      .isEqualTo(new InetSocketAddress("192.0.2.1", 0));
                  inbound.doFilter(second, busy, chain);
                  throw new IOException("client disconnected");
                }));
    assertThat(busy.getStatus()).isEqualTo(503);
    assertThat(second.bodyReads.get()).isEqualTo(0);
    assertThat(executedHere.get()).isEqualTo(0);
    inbound.doFilter(incoming("192.0.2.3"), new FakeHttpServletResponse(), chain);
    assertThat(executedHere.get()).isEqualTo(1);
  }

  private Request incoming(String address) {
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "slow");
    req.setAttribute(WriteForwarding.PEER_CONNECTOR, Boolean.TRUE);
    req.addHeader(WriteForwarding.FORWARDED_HEADER, SELF);
    req.addHeader(WriteForwarding.ADDRESS_HEADER, address);
    req.addHeader(WriteForwarding.ACCOUNT_HEADER, "42");
    return req;
  }

  @Test
  public void inboundLimitRejectsBeforeReadingBodyOrLoadingTheUser() throws Exception {
    ThreadSettingsConfig threads = mock(ThreadSettingsConfig.class);
    when(threads.getHttpdMaxThreads()).thenReturn(2);
    PeerWriteFilter inbound =
        new PeerWriteFilter(forwarding, mock(AccountCache.class), () -> session, threads);
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "slow");
    req.setAttribute(WriteForwarding.PEER_CONNECTOR, Boolean.TRUE);
    req.addHeader(WriteForwarding.FORWARDED_HEADER, SELF);
    req.addHeader(WriteForwarding.ADDRESS_HEADER, "::1");
    req.addHeader(WriteForwarding.ACCOUNT_HEADER, "42");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();
    inbound.doFilter(req, rsp, chain);
    assertThat(rsp.getStatus()).isEqualTo(503);
    assertThat(req.bodyReads.get()).isEqualTo(0);
    assertThat(executedHere.get()).isEqualTo(0);
  }

  @Test
  public void sendsABodyOfUnknownLengthInChunks() throws Exception {
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    req.lengthKnown = false;
    req.addHeader("Transfer-Encoding", "chunked");

    filter.doFilter(req, new FakeHttpServletResponse(), chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests().get(0).header("Transfer-Encoding")).isEqualTo("chunked");
    assertThat(new String(peer.requests().get(0).body(), UTF_8)).isEqualTo("PACK-DATA");
  }

  @Test
  public void executesAWriteHereWhenNoThreadMayHandItOver() throws Exception {
    WriteForwardingFilter saturated = new WriteForwardingFilter(forwarding, 0, () -> session);
    try {
      saturated.doFilter(
          new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA"),
          new FakeHttpServletResponse(),
          chain);
    } finally {
      saturated.destroy();
    }

    assertThat(executedHere.get()).isEqualTo(1);
    assertThat(peer.requests()).isEmpty();
    assertThat(forwarding.peers()).hasSize(1);
  }

  @Test
  public void findsTheProjectOfAWrite() {
    assertProject("POST", "/a/team/repo.git/git-receive-pack", null, "team/repo");
    assertProject("POST", "/team/repo/git-receive-pack", null, "team/repo");
    assertProject("POST", "/p/team/repo/git-receive-pack", null, "team/repo");
    assertProject("POST", "/a/team%2Frepo/git-receive-pack", null, "team/repo");
    assertProject("GET", "/a/team/repo/info/refs", "service=git-receive-pack", "team/repo");
    assertProject("GET", "/team/repo.git/info/refs", "a=b&service=git-receive-pack", "team/repo");
    assertProject("GET", "/team/repo/info/refs", "service=git-upload-pack", null);
    assertProject("GET", "/team/repo/info/refs", null, null);
    assertProject("POST", "/team/repo/git-upload-pack", null, null);
    assertProject("POST", "/a/changes/team%2Frepo~42/revisions/current/review", null, "team/repo");
    assertProject("POST", "/changes/team%2Frepo~master~I0123456789/submit", null, "team/repo");
    assertProject("DELETE", "/a/changes/a~b~42", null, "a~b");
    assertProject("POST", "/a/changes/42/submit", null, null);
    assertProject("POST", "/a/changes/I0123456789/submit", null, null);
    assertProject("POST", "/a/changes/", null, null);
    assertProject("GET", "/a/changes/team%2Frepo~42/detail", null, null);
    assertProject("PUT", "/a/projects/team%2Frepo", null, "team/repo");
    assertProject("PUT", "/a/projects/team%2Frepo/branches/stable", null, "team/repo");
    assertProject("GET", "/a/projects/team%2Frepo/branches/", null, null);
    assertProject("HEAD", "/a/projects/team%2Frepo", null, null);
    assertProject("POST", "/a/projects/%ZZ/branches/x", null, null);
    assertProject("PUT", "/a/accounts/self/name", null, null);
    assertProject("POST", "/git-receive-pack", null, null);
    assertProject("GET", "/info/refs", "service=git-receive-pack", null);
  }

  private static void assertProject(String method, String path, String query, String expected) {
    assertWithMessage("%s %s?%s", method, path, query)
        .that(WriteEligibility.project(method, path, query))
        .isEqualTo(expected);
  }
}
