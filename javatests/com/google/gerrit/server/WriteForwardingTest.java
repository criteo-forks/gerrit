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

import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.WriteForwarding.Peer;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.util.http.testutil.FakePeer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WriteForwardingTest {
  private static final List<String> NAMES = List.of("gerrit-0", "gerrit-1", "gerrit-2", "gerrit-3");
  private static final List<String> PROJECTS = List.of("a", "platform/monorepo", "tools/build");

  private final List<FakePeer> hellos = new ArrayList<>();
  private final List<String> addresses = new ArrayList<>();

  @Before
  public void startNodes() throws Exception {
    for (int i = 0; i < NAMES.size(); i++) {
      FakePeer hello = new FakePeer(NAMES.get(i));
      hellos.add(hello);
      addresses.add(hello.address());
    }
  }

  @After
  public void stopNodes() throws IOException {
    for (FakePeer peer : hellos) {
      peer.close();
    }
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
    assertThat(peer.httpPort()).isEqualTo(hellos.get(1).port());
    assertThat(peer.httpAuthority()).isEqualTo(addresses.get(1));
  }

  @Test
  public void aPeerThatDoesNotAnswerIsNoCandidate() throws Exception {
    WriteForwarding node = probed("gerrit-0");
    hellos.get(1).hello(503, "");
    hellos.get(2).close();
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-3");
    hellos.get(1).hello(200, NAMES.get(1));
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
    Config config = FakePeer.config();
    config.setInt("walgerrit", null, "forwardingPort", hellos.get(1).port());
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
  public void aPeerCannotAdvertiseADialPort() {
    hellos.get(1).hello(200, "gerrit-1\n22\n");
    assertThat(names(probed("gerrit-0").peers())).doesNotContain("gerrit-1");
  }

  @Test
  public void absentSecretWrongClusterOffSwitchAndCustomFiltersKeepWritesLocal() {
    Config wrong = FakePeer.config();
    wrong.setString(
        "walgerrit", null, "forwardingSecret", "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    Config invalid = FakePeer.config();
    invalid.setString("walgerrit", null, "forwardingSecret", "short");
    Config off = FakePeer.config();
    off.setBoolean("walgerrit", null, "forwardWrites", false);
    Config custom = FakePeer.config();
    custom.setString("httpd", null, "filterClass", "example.Authentication");
    for (Config config : List.of(new Config(), wrong, invalid, off, custom)) {
      WriteForwarding node =
          new WriteForwarding(
              () -> NAMES.get(0),
              () -> addresses,
              config,
              GerritRuntime.DAEMON,
              new DisabledMetricMaker());
      node.probe();
      assertThat(node.peers()).isEmpty();
    }
  }

  private WriteForwarding probed(String name) {
    WriteForwarding node = node(name, addresses);
    node.probe();
    return node;
  }

  private static WriteForwarding node(String name, List<String> addresses) {
    return new WriteForwarding(
        () -> name,
        () -> addresses,
        FakePeer.config(),
        GerritRuntime.DAEMON,
        new DisabledMetricMaker());
  }

  private static String executor(
      WriteForwarding node, String name, String project, Predicate<Peer> usable) {
    return node.preferredPeer(project, usable).map(Peer::name).orElse(name);
  }

  private static List<String> names(Collection<Peer> peers) {
    return peers.stream().map(Peer::name).toList();
  }
}
