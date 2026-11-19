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

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteRouter;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.util.http.testutil.FakeHttpServletRequest;
import com.google.gerrit.util.http.testutil.FakeHttpServletResponse;
import com.google.gerrit.util.http.testutil.FakePeer;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WriteForwardingFilterTest {
  private static final String SELF = "gerrit-0";
  private static final String PEER = "gerrit-1";

  /** A request with a body, which counts how much of the body was read. */
  private static final class Request extends FakeHttpServletRequest {
    private final byte[] body;
    final AtomicInteger bodyReads = new AtomicInteger();
    boolean lengthKnown = true;

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
    }

    @Override
    public int getContentLength() {
      return lengthKnown && body.length > 0 ? body.length : -1;
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

  private FakePeer peer;
  private WriteForwarding forwarding;
  private WriteForwardingFilter filter;
  private String peerProject;
  private String ownProject;
  private final AtomicInteger executedHere = new AtomicInteger();
  private final FilterChain chain = (request, response) -> executedHere.incrementAndGet();

  @Before
  public void setUp() throws Exception {
    peer = new FakePeer(PEER).sshPort(29418);
    forwarding =
        new WriteForwarding(
            () -> SELF,
            () -> List.of(peer.address()),
            new Config(),
            GerritRuntime.DAEMON,
            new DisabledMetricMaker());
    forwarding.probe();
    filter = new WriteForwardingFilter(forwarding, 2);
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

  @Test
  public void handsAPushToThePreferredPeer() throws Exception {
    peer.answer(
        200, ImmutableMap.of("Content-Type", "application/x-git-receive-pack-result"), "ok");
    Request req = new Request("POST", "/a/" + peerProject + ".git/git-receive-pack", "PACK-DATA");
    req.addHeader("Authorization", "Basic dXNlcjpzZWNyZXQ=");
    req.addHeader("Content-Type", "application/x-git-receive-pack-request");
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
    assertThat(seen.header("Authorization")).isEqualTo("Basic dXNlcjpzZWNyZXQ=");
    assertThat(seen.header("Host")).isEqualTo("gerrit.example.com");
    assertThat(seen.header("Content-Type")).isEqualTo("application/x-git-receive-pack-request");
    assertThat(seen.header("X-Forwarded-For")).isEqualTo("203.0.113.9");
    assertThat(seen.header(WriteForwarding.FORWARDED_HEADER)).isEqualTo(SELF);
  }

  @Test
  public void keepsTheEncodedAddressOfARestWrite() throws Exception {
    String encoded = peerProject.replace("/", "%2F");
    Request req =
        new Request("POST", "/a/changes/" + encoded + "~42/revisions/current/review?x=%2F", "{}");
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(req, rsp, chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests().get(0).target())
        .isEqualTo("/a/changes/" + encoded + "~42/revisions/current/review?x=%2F");
    assertThat(peer.requests().get(0).header("X-Forwarded-For")).isNotNull();
  }

  @Test
  public void executesWhatAPeerHandedOver() throws Exception {
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    req.addHeader(WriteForwarding.FORWARDED_HEADER, "gerrit-2");

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
      assertThat(WriteForwardingFilter.project("POST", req.getRequestURI(), null)).isNotNull();
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
  public void answersAPeersHello() throws Exception {
    forwarding.sshPort(() -> 29418);
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();

    filter.doFilter(new Request("GET", WriteForwarding.HELLO_PATH, ""), rsp, chain);

    assertThat(rsp.getStatus()).isEqualTo(200);
    assertThat(rsp.getActualBodyString()).isEqualTo(SELF + "\n29418\n");
    assertThat(executedHere.get()).isEqualTo(0);
  }

  @Test
  public void aNodeWithoutANameLeavesTheHelloToGerrit() throws Exception {
    WriteForwarding unnamed =
        new WriteForwarding(
            () -> "", List::of, new Config(), GerritRuntime.DAEMON, new DisabledMetricMaker());
    WriteForwardingFilter plain = new WriteForwardingFilter(unnamed, 2);
    try {
      plain.doFilter(
          new Request("GET", WriteForwarding.HELLO_PATH, ""), new FakeHttpServletResponse(), chain);
    } finally {
      plain.destroy();
    }

    assertThat(executedHere.get()).isEqualTo(1);
  }

  @Test
  public void sendsABodyOfUnknownLengthInChunks() throws Exception {
    Request req = new Request("POST", "/a/" + peerProject + "/git-receive-pack", "PACK-DATA");
    req.lengthKnown = false;

    filter.doFilter(req, new FakeHttpServletResponse(), chain);

    assertThat(executedHere.get()).isEqualTo(0);
    assertThat(peer.requests().get(0).header("Transfer-Encoding")).isEqualTo("chunked");
    assertThat(new String(peer.requests().get(0).body(), UTF_8)).isEqualTo("PACK-DATA");
  }

  @Test
  public void executesAWriteHereWhenNoThreadMayHandItOver() throws Exception {
    WriteForwardingFilter saturated = new WriteForwardingFilter(forwarding, 0);
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
        .that(WriteForwardingFilter.project(method, path, query))
        .isEqualTo(expected);
  }
}
