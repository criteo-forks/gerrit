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
package com.google.gerrit.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.WriteForwarding.Peer;
import com.google.gerrit.server.config.GerritRuntime;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WriteForwardingTest {
  private static final List<String> NAMES = List.of("gerrit-0", "gerrit-1", "gerrit-2", "gerrit-3");
  private static final List<String> PROJECTS = List.of("a", "platform/monorepo", "tools/build");

  /** Answers the hello of one node; {@code status} is what it answers with. */
  private static final class Hello {
    final HttpServer server;
    final AtomicInteger status = new AtomicInteger(200);

    Hello(String name, int sshPort) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          WriteForwarding.HELLO_PATH,
          exchange -> {
            byte[] body = (name + "\n" + sshPort + "\n").getBytes(UTF_8);
            if (status.get() == 200) {
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } else {
              exchange.sendResponseHeaders(status.get(), -1);
            }
            exchange.close();
          });
      server.start();
    }

    String address() {
      return "127.0.0.1:" + server.getAddress().getPort();
    }
  }

  private final List<Hello> hellos = new ArrayList<>();
  private final List<String> addresses = new ArrayList<>();

  @Before
  public void startNodes() throws Exception {
    for (int i = 0; i < NAMES.size(); i++) {
      Hello hello = new Hello(NAMES.get(i), 29418 + i);
      hellos.add(hello);
      addresses.add(hello.address());
    }
  }

  @After
  public void stopNodes() {
    hellos.forEach(hello -> hello.server.stop(0));
  }

  @Test
  public void nodesThatSeeEachOtherAgree() {
    List<WriteForwarding> nodes = new ArrayList<>();
    for (String name : NAMES) {
      nodes.add(probed(name));
    }
    Set<String> executors = new HashSet<>();
    for (int i = 0; i < 300; i++) {
      String project = "team-" + i % 11 + "/repo-" + i;
      Set<String> chosen = new HashSet<>();
      for (int from = 0; from < nodes.size(); from++) {
        chosen.add(executor(nodes.get(from), NAMES.get(from), project, any -> true));
      }
      assertWithMessage(project).that(chosen).hasSize(1);
      executors.addAll(chosen);
    }
    assertThat(executors).containsExactlyElementsIn(NAMES);
  }

  /**
   * Every combination of the links one node holds: the write stays, or crosses a link the node
   * holds to the best node it can reach.
   */
  @Test
  public void aWriteOnlyCrossesALinkItsNodeHolds() {
    for (int self = 0; self < NAMES.size(); self++) {
      String name = NAMES.get(self);
      WriteForwarding node = probed(name);
      for (int links = 0; links < 1 << NAMES.size(); links++) {
        Set<String> held = new HashSet<>();
        for (int to = 0; to < NAMES.size(); to++) {
          if (to != self && (links >> to & 1) == 1) {
            held.add(NAMES.get(to));
          }
        }
        for (String project : PROJECTS) {
          Optional<Peer> target = node.preferredPeer(project, peer -> held.contains(peer.name()));
          if (target.isPresent()) {
            assertThat(held).contains(target.get().name());
            assertThat(WriteRouter.outranks(project, target.get().name(), name)).isTrue();
          }
          for (String other : held) {
            String executor = target.map(Peer::name).orElse(name);
            assertWithMessage("%s for %s seen from %s", other, project, name)
                .that(WriteRouter.outranks(project, other, executor))
                .isFalse();
          }
        }
      }
    }
  }

  @Test
  public void aNodeIsNotItsOwnPeer() {
    WriteForwarding node = probed("gerrit-0");
    assertThat(names(node.peers())).containsExactly("gerrit-1", "gerrit-2", "gerrit-3");
    Peer peer = node.peers().iterator().next();
    assertThat(peer.sshPort()).isEqualTo(29419);
    assertThat(peer.httpAuthority()).isEqualTo(addresses.get(1));
  }

  @Test
  public void aPeerThatDoesNotAnswerIsNoCandidate() {
    WriteForwarding node = probed("gerrit-0");
    hellos.get(1).status.set(503);
    hellos.get(2).server.stop(0);
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-3");
    hellos.get(1).status.set(200);
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-1", "gerrit-3");
  }

  @Test
  public void aFailedPeerIsNoCandidateUntilItAnswersAgain() {
    WriteForwarding node = probed("gerrit-0");
    Peer failed = node.peers().iterator().next();
    node.failed(failed);
    assertThat(names(node.peers())).containsExactly("gerrit-2", "gerrit-3");
    for (String project : PROJECTS) {
      assertThat(node.preferredPeer(project, any -> true).map(Peer::name))
          .isNotEqualTo(Optional.of(failed.name()));
    }
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-1", "gerrit-2", "gerrit-3");
  }

  @Test
  public void aNodeWithoutANameOrWithoutPeersKeepsEveryWrite() {
    WriteForwarding unnamed = node("", addresses);
    unnamed.probe();
    assertThat(unnamed.peers()).isEmpty();
    WriteForwarding alone = node("gerrit-0", List.of());
    alone.probe();
    WriteForwarding unprobed = node("gerrit-0", addresses);
    for (String project : PROJECTS) {
      assertThat(unnamed.preferredPeer(project, any -> true)).isEmpty();
      assertThat(alone.preferredPeer(project, any -> true)).isEmpty();
      assertThat(unprobed.preferredPeer(project, any -> true)).isEmpty();
    }
  }

  @Test
  public void aPeerWithoutAPortTakesThisNodesPort() throws Exception {
    Config config = new Config();
    int port = hellos.get(1).server.getAddress().getPort();
    config.setString("httpd", null, "listenUrl", "proxy-https://*:" + port + "/");
    WriteForwarding node =
        new WriteForwarding(
            () -> "gerrit-0",
            () -> List.of("127.0.0.1"),
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker());
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-1");
  }

  @Test
  public void aListenerThatTerminatesTlsHasNoPeers() {
    Config config = new Config();
    config.setString("httpd", null, "listenUrl", "https://*:8443/");
    WriteForwarding node =
        new WriteForwarding(
            () -> "gerrit-0",
            () -> addresses,
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker());
    node.probe();
    assertThat(node.peers()).isEmpty();
  }

  @Test
  public void probeListenersSeeThePeers() {
    WriteForwarding node = node("gerrit-0", addresses);
    List<Collection<Peer>> seen = new ArrayList<>();
    node.onProbe(seen::add);
    node.probe();
    assertThat(seen).hasSize(1);
    assertThat(names(seen.get(0))).containsExactly("gerrit-1", "gerrit-2", "gerrit-3");
  }

  private WriteForwarding probed(String name) {
    WriteForwarding node = node(name, addresses);
    node.probe();
    return node;
  }

  private static WriteForwarding node(String name, List<String> addresses) {
    return new WriteForwarding(
        () -> name, () -> addresses, new Config(), GerritRuntime.DAEMON, new DisabledMetricMaker());
  }

  private static String executor(
      WriteForwarding node, String name, String project, Predicate<Peer> usable) {
    return node.preferredPeer(project, usable).map(Peer::name).orElse(name);
  }

  private static List<String> names(Collection<Peer> peers) {
    return peers.stream().map(Peer::name).toList();
  }
}
