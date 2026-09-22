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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.proto.StorageProto.GossipHint;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends publication hints to peers and delivers their hints to the cache and index tailer.
 *
 * <p>After a manifest CAS succeeds, a sender thread sends the queued {@link GossipHint} to each
 * peer. The receiver records a cache expectation and calls listeners to queue index replay.
 *
 * <p>Conditional reads and index sweeps recover from missed hints. Network I/O runs outside the
 * publishing thread; a full queue drops hints. Batch programs and nodes without peers stay idle.
 */
@Singleton
final class GossipEndpoint implements LifecycleListener {
  private static final Logger logger = LoggerFactory.getLogger(GossipEndpoint.class);
  private static final int OUTBOX_CAPACITY = 4096;
  private static final long STOP_TIMEOUT_MILLIS = 5_000;

  /** Receives a peer's hint about {@code id} on the receiver thread; must return promptly. */
  interface HintListener {
    void onHint(RepositoryId id, GossipHint hint);
  }

  private final WalGitRepositoryManager repositories;
  private final GerritRuntime runtime;
  private final boolean active;
  private final InetSocketAddress bindAddress;
  private final GossipCodec codec;
  private final Clock clock;
  private final BlockingQueue<GossipHint> outbox = new ArrayBlockingQueue<>(OUTBOX_CAPACITY);
  private final List<HintListener> listeners = new CopyOnWriteArrayList<>();
  private volatile GossipPeers peers;

  /** Filters hints from this node's writer host. */
  private volatile Predicate<String> foreignWriter =
      writer -> !ManifestStore.writtenOnThisHost(writer);

  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong received = new AtomicLong();
  private final AtomicLong accepted = new AtomicLong();
  private final AtomicLong ignored = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();

  private volatile DatagramSocket socket;
  private Thread sender;
  private Thread receiver;

  @Inject
  GossipEndpoint(GitRepositoryManager repositories, GerritRuntime runtime, MetricMaker metrics) {
    this(asWalGit(repositories), runtime);
    registerMetrics(metrics);
  }

  /** As configured for {@code repositories}; idle unless the configuration names a peer source. */
  GossipEndpoint(WalGitRepositoryManager repositories, GerritRuntime runtime) {
    this(
        repositories,
        runtime,
        repositories.configuration().gossipActive(),
        GossipPeers.fromConfiguration(repositories.configuration(), Clock.systemUTC()),
        bindAddress(repositories.configuration()),
        codec(repositories.configuration()),
        Clock.systemUTC());
  }

  /** Explicit peers, socket and codec; active in any daemon. */
  GossipEndpoint(
      WalGitRepositoryManager repositories,
      GerritRuntime runtime,
      GossipPeers peers,
      InetSocketAddress bindAddress,
      GossipCodec codec,
      Clock clock) {
    this(repositories, runtime, true, peers, bindAddress, codec, clock);
  }

  private GossipEndpoint(
      WalGitRepositoryManager repositories,
      GerritRuntime runtime,
      boolean active,
      GossipPeers peers,
      InetSocketAddress bindAddress,
      GossipCodec codec,
      Clock clock) {
    this.repositories = repositories;
    this.runtime = runtime;
    this.active = active;
    this.peers = peers;
    this.bindAddress = bindAddress;
    this.codec = codec;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    if (runtime != GerritRuntime.DAEMON || !active || socket != null) {
      return;
    }
    DatagramSocket opened;
    try {
      opened = new DatagramSocket(bindAddress);
    } catch (SocketException failure) {
      throw new IllegalStateException("WalGerrit gossip cannot bind " + bindAddress, failure);
    }
    socket = opened;
    sender = daemon("WalGerrit-Gossip-Send", () -> sendLoop(opened));
    receiver = daemon("WalGerrit-Gossip-Receive", () -> receiveLoop(opened));
    sender.start();
    receiver.start();
    repositories.storage().onPublication(this::announce);
    logger.info(
        "WalGerrit gossip listening on {} ({}); peers: {}",
        opened.getLocalSocketAddress(),
        codec.signs() ? "signed" : "unsigned",
        peers);
  }

  @Override
  public void stop() {
    DatagramSocket open;
    Thread sending;
    Thread receiving;
    synchronized (this) {
      open = socket;
      sending = sender;
      receiving = receiver;
      socket = null;
      sender = null;
      receiver = null;
    }
    if (open == null) {
      return;
    }
    open.close(); // Unblocks the receiver; later sends fail and end the sender.
    if (sending != null) {
      sending.interrupt();
    }
    join(sending);
    join(receiving);
  }

  /** Calls {@code listener} for every accepted hint from a peer. */
  void onHint(HintListener listener) {
    listeners.add(listener);
  }

  /** Publication listener: queues a hint for every peer. Never blocks or fails the publisher. */
  void announce(RepositoryId id, VersionedManifest published) {
    if (socket == null) {
      return;
    }
    Manifest manifest = published.manifest();
    GossipHint hint =
        GossipHint.newBuilder()
            .setRepo(id.value())
            .setManifestVersion(published.version())
            .setRevision(manifest.getRevision())
            .setHeadSeq(manifest.getHeadSeq())
            .setHeadTransactionId(manifest.getHeadTransactionId())
            .setWriter(ManifestStore.writerIdentity())
            .setSentAtEpochMillis(clock.millis())
            .build();
    if (!outbox.offer(hint)) {
      dropped.incrementAndGet();
    }
  }

  /** The port the socket is bound to, or -1 when not listening. */
  int port() {
    DatagramSocket open = socket;
    return open == null ? -1 : open.getLocalPort();
  }

  /** Replaces the peer list; a test's peers exist only once both endpoints are bound. */
  void peers(GossipPeers peers) {
    this.peers = peers;
  }

  /** Test hook: decide by writer identity which hints came from another node. */
  void foreignWriter(Predicate<String> foreignWriter) {
    this.foreignWriter = foreignWriter;
  }

  long sentCount() {
    return sent.get();
  }

  long droppedCount() {
    return dropped.get();
  }

  long receivedCount() {
    return received.get();
  }

  long acceptedCount() {
    return accepted.get();
  }

  long ignoredCount() {
    return ignored.get();
  }

  long rejectedCount() {
    return rejected.get();
  }

  private void sendLoop(DatagramSocket open) {
    while (!open.isClosed()) {
      GossipHint hint;
      try {
        hint = outbox.take();
      } catch (InterruptedException interrupted) {
        return;
      }
      byte[] datagram;
      try {
        datagram = codec.encode(hint);
      } catch (IllegalArgumentException tooLarge) {
        dropped.incrementAndGet();
        logger.warn("WalGerrit gossip dropped a hint: {}", tooLarge.getMessage());
        continue;
      }
      for (InetSocketAddress peer : peers.addresses()) {
        try {
          open.send(new DatagramPacket(datagram, datagram.length, peer));
          sent.incrementAndGet();
        } catch (IOException failure) {
          if (open.isClosed()) {
            return;
          }
          dropped.incrementAndGet();
          logger.debug("WalGerrit gossip could not send to {}", peer, failure);
        }
      }
    }
  }

  private void receiveLoop(DatagramSocket open) {
    // The extra byte lets the codec reject oversized packets even when receive truncates them.
    byte[] buffer = new byte[GossipCodec.MAX_DATAGRAM + 1];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    while (!open.isClosed()) {
      packet.setLength(buffer.length);
      try {
        open.receive(packet);
      } catch (IOException failure) {
        if (open.isClosed()) {
          return;
        }
        logger.warn("WalGerrit gossip receive failed", failure);
        continue;
      }
      received.incrementAndGet();
      Optional<GossipHint> decoded = codec.decode(packet.getData(), packet.getLength());
      if (decoded.isEmpty()) {
        rejected.incrementAndGet();
        continue;
      }
      GossipHint hint = decoded.get();
      if (!foreignWriter.test(hint.getWriter())) {
        ignored.incrementAndGet();
        continue;
      }
      deliver(hint);
    }
  }

  private void deliver(GossipHint hint) {
    RepositoryId id;
    try {
      id = new RepositoryId(hint.getRepo());
    } catch (IllegalArgumentException notAnId) {
      rejected.incrementAndGet();
      return;
    }
    repositories.storage().expectManifest(id, hint.getManifestVersion(), hint.getRevision());
    accepted.incrementAndGet();
    for (HintListener listener : listeners) {
      try {
        listener.onHint(id, hint);
      } catch (RuntimeException failure) {
        logger.warn("WalGerrit gossip listener failed for {}", id, failure);
      }
    }
  }

  private void registerMetrics(MetricMaker metrics) {
    gauge(metrics, "sent", "Hints this node sent, one per peer per publication", sent);
    gauge(metrics, "dropped", "Hints this node could not queue or send", dropped);
    gauge(metrics, "received", "Datagrams this node received on its gossip port", received);
    gauge(metrics, "accepted", "Hints from other nodes this node acted on", accepted);
    gauge(metrics, "ignored", "Hints this node received about its own publications", ignored);
    gauge(metrics, "rejected", "Datagrams that were not hints for this cluster", rejected);
  }

  private static void gauge(
      MetricMaker metrics, String name, String description, AtomicLong value) {
    metrics.newCallbackMetric(
        "walgerrit/gossip/" + name,
        Long.class,
        new Description(description).setCumulative().setUnit("hints"),
        value::get);
  }

  private static Thread daemon(String name, Runnable body) {
    Thread thread = new Thread(body, name);
    thread.setDaemon(true);
    return thread;
  }

  private static void join(Thread thread) {
    if (thread == null) {
      return;
    }
    try {
      thread.join(STOP_TIMEOUT_MILLIS);
      if (thread.isAlive()) {
        logger.warn(
            "WalGerrit gossip thread {} did not stop within {} ms",
            thread.getName(),
            STOP_TIMEOUT_MILLIS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static InetSocketAddress bindAddress(WalGitConfiguration configuration) {
    return configuration.gossipListenAddress() == null
        ? new InetSocketAddress(configuration.gossipPort())
        : new InetSocketAddress(configuration.gossipListenAddress(), configuration.gossipPort());
  }

  private static GossipCodec codec(WalGitConfiguration configuration) {
    return new GossipCodec(
        configuration.gossipSecret() == null
            ? null
            : configuration.gossipSecret().getBytes(StandardCharsets.UTF_8));
  }

  private static WalGitRepositoryManager asWalGit(GitRepositoryManager repositories) {
    if (repositories instanceof WalGitRepositoryManager walGit) {
      return walGit;
    }
    throw new IllegalStateException(
        "WalGitIndexModule requires WalGitRepositoryManager, found "
            + repositories.getClass().getName());
  }
}
