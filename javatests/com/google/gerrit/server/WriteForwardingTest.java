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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
            Optional::empty,
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker());
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-1");
  }

  @Test
  public void byDefaultEveryNodeChoosesTheSameExecutorForEveryWrite() {
    Config cluster = FakePeer.config();
    Set<String> executors = new HashSet<>();
    List<WriteForwarding> nodes = new ArrayList<>();
    for (String name : NAMES) {
      WriteForwarding node = node(name, addresses, Optional::empty, cluster);
      node.probe();
      nodes.add(node);
      for (String project : PROJECTS) {
        String key = WriteEligibility.sshReceivePack(node.placement(), project).key();
        executors.add(node.preferredPeer(key).map(Peer::name).orElse(name));
      }
    }
    assertThat(executors).hasSize(1);
    String executor = executors.iterator().next();
    for (int i = 0; i < NAMES.size(); i++) {
      for (String project : PROJECTS) {
        assertThat(nodes.get(i).executes(project)).isEqualTo(NAMES.get(i).equals(executor));
      }
    }
  }

  @Test
  public void withAProjectPlacementANodeExecutesTheProjectsItRanksFirst() {
    WriteForwarding node = probed("gerrit-0");
    for (String project : PROJECTS) {
      assertThat(node.executes(project))
          .isEqualTo(node.preferredPeer(project, any -> true).isEmpty());
    }
    assertThat(node(NAMES.get(0), List.of()).executes("a")).isTrue();
  }

  @Test
  public void aNodeCutOffFromItsStoreHandsItsWritesToThePeersItReaches() {
    AtomicReference<Optional<String>> health = new AtomicReference<>(Optional.empty());
    for (String name : NAMES) {
      WriteForwarding node = node(name, addresses, health::get, FakePeer.projectConfig());
      node.probe();
      for (String project : PROJECTS) {
        health.set(Optional.empty());
        Optional<Peer> healthy = node.preferredPeer(project, any -> true);
        health.set(Optional.of("the store has not answered for 12 s"));
        Optional<Peer> cutOff = node.preferredPeer(project, any -> true);
        assertThat(cutOff).isPresent();
        assertThat(node.executes(project)).isFalse();
        if (healthy.isPresent()) {
          assertThat(cutOff).isEqualTo(healthy);
        }
        // With nobody to hand it to, the write is still executed here.
        assertThat(node.preferredPeer(project, any -> false)).isEmpty();
      }
    }
  }

  @Test
  public void aStoppingNodeHandsItsWritesToItsPeersForItsDrainTime() {
    Config config = FakePeer.projectConfig();
    config.setString("walgerrit", null, "drainOnStop", "200 ms");
    WriteForwarding node = node("gerrit-0", addresses, Optional::empty, config);
    node.probe();
    String own = ownProject(node);
    assertThat(node.unavailable()).isEmpty();

    long started = System.nanoTime();
    node.drain();

    assertThat(System.nanoTime() - started).isAtLeast(200_000_000L);
    assertThat(node.unavailable()).hasValue("stopping");
    assertThat(node.executes(own)).isFalse();
    assertThat(node.preferredPeer(own)).isPresent();
  }

  @Test
  public void withoutADrainTimeAStoppingNodeKeepsItsWrites() {
    WriteForwarding node = node("gerrit-0", addresses);
    node.probe();
    String own = ownProject(node);

    node.drain();

    assertThat(node.unavailable()).isEmpty();
    assertThat(node.executes(own)).isTrue();
  }

  @Test
  public void aReturningNodeLeavesItsWritesToItsPeersUntilTheyHaveCountedItsHellos() {
    Config config = FakePeer.projectConfig();
    config.setInt("walgerrit", null, "forwardingJoinProbes", 3);
    AtomicLong clock = new AtomicLong(1);
    WriteForwarding node =
        new WriteForwarding(
            () -> "gerrit-0",
            () -> addresses,
            Optional::empty,
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker(),
            clock::get);

    node.probe();

    assertThat(names(node.peers())).containsExactly("gerrit-1", "gerrit-2", "gerrit-3");
    for (int i = 0; i < 100; i++) {
      assertThat(node.executes("project-" + i)).isFalse();
    }

    // Its peers hand it writes after three hellos, two probe intervals after the first.
    node.helloAnswered();
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500));
    node.probe();
    for (int i = 0; i < 100; i++) {
      assertThat(node.executes("project-" + i)).isFalse();
    }
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
    node.probe();

    assertThat(names(node.peers())).containsExactly("gerrit-1", "gerrit-2", "gerrit-3");
    assertThat(ownProject(node)).isNotEmpty();
  }

  /** A node whose peers have counted its hellos. */
  private WriteForwarding settled(String name, Config config, AtomicLong clock) {
    WriteForwarding node =
        new WriteForwarding(
            () -> name,
            () -> addresses,
            Optional::empty,
            config,
            GerritRuntime.DAEMON,
            new DisabledMetricMaker(),
            clock::get);
    node.helloAnswered();
    clock.addAndGet(TimeUnit.HOURS.toNanos(1));
    return node;
  }

  private static String ownProject(WriteForwarding node) {
    return IntStream.range(0, 1000)
        .mapToObj(i -> "project-" + i)
        .filter(node::executes)
        .findFirst()
        .orElseThrow();
  }

  @Test
  public void aNodeOnlyJoinsNodesThatApplyItsHandOverRules() {
    hellos.get(1).helloWithoutProtocol();
    assertThat(names(probed("gerrit-0").peers())).containsExactly("gerrit-2", "gerrit-3");
  }

  @Test
  public void aReturningPeerJoinsAfterItsHellosInARow() throws Exception {
    Config config = FakePeer.config();
    config.setInt("walgerrit", null, "forwardingJoinProbes", 3);
    AtomicLong clock = new AtomicLong(1);
    WriteForwarding node = settled("gerrit-0", config, clock);
    node.probe();
    node.probe();
    assertThat(node.peers()).isEmpty();
    hellos.get(1).hello(503, "");
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-2", "gerrit-3");
    hellos.get(1).hello(200, NAMES.get(1));
    node.probe();
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-2", "gerrit-3");
    node.probe();
    assertThat(names(node.peers())).containsExactly("gerrit-1", "gerrit-2", "gerrit-3");
    Peer failed = node.peers().iterator().next();
    node.failed(failed);
    node.probe();
    assertThat(names(node.peers())).doesNotContain(failed.name());
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
              Optional::empty,
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
    return node(name, addresses, Optional::empty, FakePeer.projectConfig());
  }

  private static WriteForwarding node(
      String name, List<String> addresses, Supplier<Optional<String>> health, Config config) {
    return new WriteForwarding(
        () -> name, () -> addresses, health, config, GerritRuntime.DAEMON, new DisabledMetricMaker());
  }

  private static String executor(
      WriteForwarding node, String name, String project, Predicate<Peer> usable) {
    return node.preferredPeer(project, usable).map(Peer::name).orElse(name);
  }

  private static List<String> names(Collection<Peer> peers) {
    return peers.stream().map(Peer::name).toList();
  }
}
