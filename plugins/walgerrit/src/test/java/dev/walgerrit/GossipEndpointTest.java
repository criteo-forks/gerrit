// Copyright 2026 The WalGerrit Authors
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

package dev.walgerrit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.proto.StorageProto.GossipHint;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two managers over one shared store stand in for two Gerrit nodes, each with a gossip endpoint on
 * a loopback port of its own. Both run in one JVM and therefore share a writer host name, so the
 * receiving endpoint is told to treat every hint as foreign where the test is not about that.
 */
class GossipEndpointTest {
  private static final String MAIN = Constants.R_HEADS + "main";
  private static final Duration PATIENCE = Duration.ofSeconds(10);

  @TempDir Path root;

  @Test
  void aPublicationOnOneNodeReachesTheOtherAsAHintItActsOn() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a");
    WalGitRepositoryManager nodeB = node("node-b");
    GossipEndpoint receiving = endpoint(nodeB, noPeers(), GossipCodec.unsigned());
    receiving.foreignWriter(writer -> true);
    BlockingQueue<GossipHint> hints = new LinkedBlockingQueue<>();
    receiving.onHint((project, hint) -> hints.add(hint));
    receiving.start();
    GossipEndpoint sending =
        endpoint(nodeA, peers("127.0.0.1:" + receiving.port()), GossipCodec.unsigned());
    sending.start();
    try {
      Project.NameKey project = Project.nameKey("platform/gossip");
      nodeA.createRepository(project).close();
      ObjectId commit;
      try (Repository repository = nodeA.openRepository(project)) {
        commit = publishMain(repository, "from node A");
      }
      Manifest head = nodeA.storage().manifestStore(project).read();

      GossipHint hint =
          awaitHint(hints, candidate -> candidate.getRevision() == head.getRevision());
      assertEquals(project.get(), hint.getRepo());
      assertEquals(head.getHeadSeq(), hint.getHeadSeq());
      assertEquals(head.getHeadTransactionId(), hint.getHeadTransactionId());
      assertEquals(ManifestStore.writerIdentity(), hint.getWriter());
      assertFalse(hint.getManifestVersion().isEmpty());

      // Node B has never read this repository; the hint leaves an expectation its first read
      // settles, and that read sees node A's commit.
      ManifestStore storeB = nodeB.storage().manifestStore(project);
      assertTrue(storeB.expectingNewerManifest());
      try (Repository repository = nodeB.openRepository(project)) {
        assertEquals(commit, repository.exactRef(MAIN).getObjectId());
      }
      assertFalse(storeB.expectingNewerManifest());

      assertEquals(0, receiving.rejectedCount());
      assertEquals(0, receiving.ignoredCount());
      assertTrue(sending.sentCount() >= 1);
      assertEquals(0, sending.droppedCount());
    } finally {
      sending.stop();
      receiving.stop();
    }
  }

  @Test
  void ownHintsAndStrayDatagramsAreCountedButNotActedOn() throws Exception {
    WalGitRepositoryManager nodeB = node("node-b");
    GossipEndpoint receiving = endpoint(nodeB, noPeers(), GossipCodec.unsigned());
    AtomicInteger delivered = new AtomicInteger();
    receiving.onHint((project, hint) -> delivered.incrementAndGet());
    receiving.start();
    try (DatagramSocket stray = new DatagramSocket()) {
      InetSocketAddress target =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), receiving.port());
      GossipCodec codec = GossipCodec.unsigned();
      send(stray, target, codec.encode(hint("platform/own", ManifestStore.writerIdentity())));
      send(stray, target, "not a hint".getBytes(StandardCharsets.UTF_8));
      send(stray, target, codec.encode(hint("../escape", "node-x:1")));
      send(stray, target, codec.encode(hint("platform/peer", "node-x:1")));

      await(() -> receiving.receivedCount() == 4);
      await(() -> outcomes(receiving) == 4);
      assertEquals(1, receiving.ignoredCount(), "this node's own publication");
      assertEquals(2, receiving.rejectedCount(), "garbage and an invalid project name");
      assertEquals(1, receiving.acceptedCount());
      assertEquals(1, delivered.get());
      assertTrue(
          nodeB.storage()
              .manifestStore(Project.nameKey("platform/peer"))
              .expectingNewerManifest());
    } finally {
      receiving.stop();
    }
  }

  @Test
  void aSignedClusterDropsUnsignedHintsAndAcceptsSignedOnes() throws Exception {
    byte[] secret = "s3cret".getBytes(StandardCharsets.UTF_8);
    WalGitRepositoryManager nodeB = node("node-b");
    GossipEndpoint receiving = endpoint(nodeB, noPeers(), new GossipCodec(secret));
    receiving.foreignWriter(writer -> true);
    receiving.start();
    try (DatagramSocket stray = new DatagramSocket()) {
      InetSocketAddress target =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), receiving.port());
      send(stray, target, GossipCodec.unsigned().encode(hint("platform/signed", "node-x:1")));
      send(stray, target, new GossipCodec(secret).encode(hint("platform/signed", "node-x:1")));

      await(() -> receiving.receivedCount() == 2);
      await(() -> outcomes(receiving) == 2);
      assertEquals(1, receiving.rejectedCount());
      assertEquals(1, receiving.acceptedCount());
    } finally {
      receiving.stop();
    }
  }

  @Test
  void aHintWakesTheReceivingTailerBeforeItsNextSweep() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a");
    WalGitRepositoryManager nodeB = node("node-b");
    Project.NameKey project = Project.nameKey("platform/wake-up");
    nodeA.createRepository(project).close();

    RecordingApplier applierB = new RecordingApplier();
    IndexEventTailer tailerB =
        new IndexEventTailer(
            nodeB,
            applierB,
            GerritRuntime.DAEMON,
            "lucene",
            durableLuceneConfig(),
            new IndexEventReadiness(root.resolve("node-b-ready").resolve("READY")),
            null);
    GossipEndpoint receiving = endpoint(nodeB, noPeers(), GossipCodec.unsigned());
    receiving.foreignWriter(writer -> true);
    receiving.onHint((woken, hint) -> tailerB.wake(woken, hint.getManifestVersion()));
    tailerB.start(); // The startup sweep seeds node B's cursor at the current head.
    receiving.start();
    GossipEndpoint sending =
        endpoint(nodeA, peers("127.0.0.1:" + receiving.port()), GossipCodec.unsigned());
    sending.start();
    try {
      ObjectId commit;
      try (Repository repository = nodeA.openRepository(project)) {
        commit = publishMain(repository, "wake up");
      }
      // Node B's poll interval is an hour: only the hint can bring this in time.
      await(() -> applierB.sawUpdate(MAIN, commit));
      await(() -> tailerB.wakeUpsReplayed() >= 1);
    } finally {
      sending.stop();
      receiving.stop();
      tailerB.stop();
    }
  }

  private WalGitRepositoryManager node(String name) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("shared-store").toString());
    config.setString(
        "walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "indexPollInterval", "1 hour");
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve(name)));
  }

  private static GossipEndpoint endpoint(
      WalGitRepositoryManager node, GossipPeers peers, GossipCodec codec) {
    return new GossipEndpoint(
        node,
        GerritRuntime.DAEMON,
        peers,
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        codec,
        Clock.systemUTC());
  }

  private static GossipPeers noPeers() {
    return peers();
  }

  private static GossipPeers peers(String... entries) {
    return new GossipPeers(
        List.of(entries), null, 29419, Duration.ofSeconds(30), Clock.systemUTC());
  }

  /** Datagrams the endpoint has finished classifying, whichever way. */
  private static long outcomes(GossipEndpoint endpoint) {
    return endpoint.acceptedCount() + endpoint.ignoredCount() + endpoint.rejectedCount();
  }

  private static GossipHint hint(String repo, String writer) {
    return GossipHint.newBuilder()
        .setRepo(repo)
        .setManifestVersion("v1")
        .setRevision(1)
        .setHeadSeq(1)
        .setWriter(writer)
        .build();
  }

  private static void send(DatagramSocket socket, InetSocketAddress target, byte[] datagram)
      throws Exception {
    socket.send(new DatagramPacket(datagram, datagram.length, target));
  }

  private static ObjectId publishMain(Repository repository, String message) throws Exception {
    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
    RefUpdate update = repository.updateRef(MAIN);
    update.setNewObjectId(commit);
    assertEquals(RefUpdate.Result.NEW, update.update());
    return commit;
  }

  private static GossipHint awaitHint(BlockingQueue<GossipHint> hints, Predicate<GossipHint> wanted)
      throws InterruptedException {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      GossipHint hint = hints.poll(100, TimeUnit.MILLISECONDS);
      if (hint != null && wanted.test(hint)) {
        return hint;
      }
    }
    return fail("no matching hint within " + PATIENCE);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) {
        fail("condition not met within " + PATIENCE);
      }
      Thread.sleep(10);
    }
  }

  private static Config durableLuceneConfig() {
    Config config = new Config();
    for (String name :
        List.of("accounts", "changes_open", "changes_closed", "groups", "projects")) {
      config.setString("index", name, "commitWithin", "0");
    }
    return config;
  }

  private static final class RecordingApplier implements IndexEventApplier {
    private final List<RefTransaction> transactions = new CopyOnWriteArrayList<>();

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      transactions.add(transaction);
    }

    boolean sawUpdate(String ref, ObjectId newValue) {
      return transactions.stream()
          .flatMap(transaction -> transaction.getUpdatesList().stream())
          .anyMatch(
              update ->
                  update.getName().equals(ref) && update.getNewObjectId().equals(newValue.name()));
    }
  }
}
