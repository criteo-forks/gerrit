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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HostAndPort;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.WriteEligibility.Placement;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.eclipse.jgit.lib.Config;

/**
 * Chooses the node of a cluster that should execute a write.
 *
 * <p>Nodes that share their repositories all accept writes, and one node executing a repository's
 * writes avoids the waits that interleaved writers cause each other. A node that receives a write
 * therefore hands it to the node {@link WriteRouter} prefers, when it has a working link to that
 * node, and executes it itself otherwise. {@link Placement} says whether the nodes are ranked for
 * each project or once for every write. A node always executes what a peer handed it, so a write
 * crosses at most one link however the nodes' views of each other differ.
 *
 * <p>The plugin supplies a stable node name, peer addresses and store health. A peer can join
 * when its hello has a matching protocol version, its serving gate is open, it can reach its
 * store and it is not stopping.
 *
 * <p>Background work that writes asks {@link #executes}. Code built against the plugin API reads
 * the same answer from {@link #EXECUTOR_KEY}:
 *
 * <pre>{@code
 * @Inject @Named("writeExecutor") Predicate<String> executor;
 * ...
 * if (executor.test(project.get())) { // act }
 * }</pre>
 */
@Singleton
public class WriteForwarding implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String NODE = "writeForwardingNode";
  public static final String PEERS = "writeForwardingPeers";
  public static final String HEALTH = "writeForwardingHealth";
  public static final String EXECUTOR = "writeExecutor";

  public static final Key<Supplier<String>> NODE_KEY =
      Key.get(new TypeLiteral<Supplier<String>>() {}, Names.named(NODE));

  public static final Key<Supplier<Collection<String>>> PEERS_KEY =
      Key.get(new TypeLiteral<Supplier<Collection<String>>>() {}, Names.named(PEERS));

  /** Empty while this node can reach its store; otherwise the reason it cannot. */
  public static final Key<Supplier<Optional<String>>> HEALTH_KEY =
      Key.get(new TypeLiteral<Supplier<Optional<String>>>() {}, Names.named(HEALTH));

  /** Whether this node is the preferred executor of writes to the named project. */
  public static final Key<Predicate<String>> EXECUTOR_KEY =
      Key.get(new TypeLiteral<Predicate<String>>() {}, Names.named(EXECUTOR));

  /** Private readiness and node identity endpoint. */
  public static final String HELLO_PATH = "/peerz";

  /** Set by Jetty on requests from the mutually authenticated peer connector. */
  public static final String PEER_CONNECTOR = WriteForwarding.class.getName() + ".connector";

  public static final String ACCOUNT_HEADER = "X-Gerrit-Peer-Account";
  public static final String ADDRESS_HEADER = "X-Gerrit-Peer-Address";
  public static final String NATIVE_HEADER = "X-Gerrit-Peer-Receive";
  public static final String NATIVE_TYPE = "application/x-walgerrit-duplex-receive-pack";

  public static final String FORWARDED_HEADER = "X-Gerrit-Forwarded-By";

  /** On a destination's answer: it refused the hand-over and executed nothing. */
  public static final String REFUSED_HEADER = "X-Gerrit-Peer-Refused";

  /**
   * The refusals after which the entry node executes the request itself: the destination judged
   * the request or itself. Any other refusal is about the caller, or unknown, and stands.
   */
  private static final ImmutableSet<String> EXECUTED_AT_ENTRY =
      ImmutableSet.of("destination", "ineligible", "unavailable");

  /** The refusals that deny the caller. */
  private static final ImmutableSet<String> DENIALS =
      ImmutableSet.of("account", "credentials", "identity");

  /** Whether the entry node may execute a request that its destination refused for this reason. */
  public static boolean executedAtEntry(@Nullable String refusal) {
    return refusal != null && EXECUTED_AT_ENTRY.contains(refusal);
  }

  /** Whether a refusal for this reason denies the caller; any other is not one this node knows. */
  public static boolean denial(@Nullable String refusal) {
    return refusal != null && DENIALS.contains(refusal);
  }

  /** On a hello: the hand-over rules the node applies. A node only joins nodes with its own. */
  public static final String PROTOCOL_HEADER = "X-Gerrit-Peer-Protocol";

  public static final String PROTOCOL = "2";

  private static final int PROBE_INTERVAL_MILLIS = 1000;
  private static final int PROBE_TIMEOUT_MILLIS = 1000;
  private static final int MAX_HELLO_BYTES = 512;
  private static final Pattern NODE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,252}");

  public record Peer(String name, String host, int httpPort) {
    /** The peer's address for a URL or a log line. */
    public String httpAuthority() {
      return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + httpPort;
    }
  }

  public static class WriteForwardingModule extends LifecycleModule {
    @Override
    protected void configure() {
      OptionalBinder.newOptionalBinder(binder(), NODE_KEY).setDefault().toInstance(() -> "");
      OptionalBinder.newOptionalBinder(binder(), PEERS_KEY).setDefault().toInstance(List::of);
      OptionalBinder.newOptionalBinder(binder(), HEALTH_KEY)
          .setDefault()
          .toInstance(Optional::empty);
      bind(EXECUTOR_KEY).toProvider(ExecutorProvider.class);
      listener().to(WriteForwarding.class);
    }
  }

  private static class ExecutorProvider implements com.google.inject.Provider<Predicate<String>> {
    private final WriteForwarding forwarding;

    @Inject
    ExecutorProvider(WriteForwarding forwarding) {
      this.forwarding = forwarding;
    }

    @Override
    public Predicate<String> get() {
      return forwarding::executes;
    }
  }

  private final Supplier<String> node;
  private final Supplier<Collection<String>> addresses;
  private final Supplier<Optional<String>> health;
  private final Placement placement;
  private final int joinProbes;
  private final long drainMillis;
  private final String contextPath;
  private final int port;
  @Nullable private final SSLContext tls;
  private final GerritRuntime runtime;
  private final Counter2<String, String> requests;
  private final Counter2<String, String> ineligible;

  private volatile ImmutableMap<String, Peer> peers = ImmutableMap.of();
  private final LongSupplier nanoTime;
  private volatile boolean draining;
  private volatile String unhealthy;

  /** When a peer first found this node able to execute writes; zero while none has. */
  private volatile long candidateSince;

  /** Consecutive hellos each peer answered. */
  private final Map<String, Integer> answers = new HashMap<>();

  private ScheduledExecutorService prober;

  @Inject
  public WriteForwarding(
      @Named(NODE) Supplier<String> node,
      @Named(PEERS) Supplier<Collection<String>> addresses,
      @Named(HEALTH) Supplier<Optional<String>> health,
      @GerritServerConfig Config config,
      GerritRuntime runtime,
      MetricMaker metrics) {
    this(node, addresses, health, config, runtime, metrics, System::nanoTime);
  }

  @VisibleForTesting
  public WriteForwarding(
      Supplier<String> node,
      Supplier<Collection<String>> addresses,
      Supplier<Optional<String>> health,
      Config config,
      GerritRuntime runtime,
      MetricMaker metrics,
      LongSupplier nanoTime) {
    this.node = node;
    this.nanoTime = nanoTime;
    this.addresses = addresses;
    this.health = health;
    this.tls = configuredTls(config);
    placement = Placement.parse(config.getString("walgerrit", null, "placement"));
    joinProbes = config.getIntInRange("walgerrit", null, "forwardingJoinProbes", 1, 3600, 1);
    drainMillis =
        config.getTimeUnit("walgerrit", null, "drainOnStop", 0, TimeUnit.MILLISECONDS);
    port = config.getIntInRange("walgerrit", null, "forwardingPort", 1, 65535, 9443);
    String[] urls = config.getStringList("httpd", null, "listenUrl");
    String path = urls.length == 0 ? "" : URI.create(urls[0]).getRawPath();
    contextPath = path == null ? "" : path.replaceFirst("/+$", "");
    this.runtime = runtime;
    this.requests =
        metrics.newCounter(
            "write_forwarding/requests",
            new Description("Writes that this node handed, or tried to hand, to a peer").setRate(),
            field("transport", "ssh or http"),
            field(
                "outcome",
                "forwarded: the peer answered; local: this node is the preferred executor;"
                    + " ineligible: executed here because the request may not be handed over;"
                    + " fallback: executed here because the peer could not be reached; refused:"
                    + " executed here because the peer refused the hand-over; rejected: the peer"
                    + " refused after the request body was sent, and the client was told to"
                    + " retry; busy: executed here because too many threads were handing writes"
                    + " over; inbound_busy: destination admission limit reached; failed: the link"
                    + " broke after the peer may have acted"));
    this.ineligible =
        metrics.newCounter(
            "write_forwarding/ineligible",
            new Description("Writes executed where they arrived because they may not be handed over")
                .setRate(),
            field("transport", "ssh or http"),
            field("reason", "why the write may not be handed over"));
    metrics.newCallbackMetric(
        "write_forwarding/peers",
        Integer.class,
        new Description("Peers this node may hand writes to").setGauge(),
        () -> peers.size());
    metrics.newCallbackMetric(
        "write_forwarding/cluster_executor",
        Boolean.class,
        new Description(
                "Whether this node is the preferred executor of every write; false unless"
                    + " walgerrit.placement is cluster")
            .setGauge(),
        () -> placement == Placement.CLUSTER && active() && executes(""));
  }

  @Nullable
  private static SSLContext configuredTls(Config config) {
    String secret = config.getString("walgerrit", null, "forwardingSecret");
    if (secret == null
        || secret.isBlank()
        || !config.getBoolean("walgerrit", "forwardWrites", true)
        || config.getStringList("httpd", null, "filterClass").length > 0) {
      return null;
    }
    try {
      return WritePeerTls.fromSecret(secret);
    } catch (IllegalArgumentException malformed) {
      logger.atWarning().log(
          "Invalid walgerrit.forwardingSecret encoding. Executing writes locally.");
      return null;
    }
  }

  private static Field<String> field(String name, String description) {
    return Field.ofString(
            name,
            (metadata, value) -> metadata.addPluginMetadata(PluginMetadata.create(name, value)))
        .description(description)
        .build();
  }

  @Override
  public synchronized void start() {
    if (tls == null || runtime != GerritRuntime.DAEMON || prober != null) {
      return;
    }
    prober =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "WriteForwarding-Probe");
              thread.setDaemon(true);
              return thread;
            });
    @SuppressWarnings("unused")
    var unused =
        prober.scheduleWithFixedDelay(
            this::probeQuietly,
            PROBE_INTERVAL_MILLIS,
            PROBE_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (prober != null) {
      prober.shutdownNow();
      prober = null;
    }
  }

  /** Whether this node hands writes over at all: it has a cluster key and a name. */
  public boolean active() {
    return tls != null && !name().isEmpty();
  }

  public Placement placement() {
    return placement;
  }

  /**
   * Stops answering hellos and hands new writes to peers; returns after {@code
   * walgerrit.drainOnStop}.
   */
  public void drain() {
    if (!active() || drainMillis <= 0 || draining) {
      return;
    }
    draining = true;
    logger.atInfo().log(
        "Write forwarding: node %s is stopping; handing new writes to its peers for %d ms",
        name(), drainMillis);
    try {
      Thread.sleep(drainMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Why this node must not be chosen to execute writes, or empty when it may be: it is stopping,
   * or it cannot reach its store.
   */
  public Optional<String> unavailable() {
    if (draining) {
      candidateSince = 0;
      return Optional.of("stopping");
    }
    Optional<String> reason;
    try {
      reason = health.get();
    } catch (RuntimeException failure) {
      logger.atWarning().withCause(failure).log("Cannot tell whether this node reaches its store");
      reason = Optional.empty();
    }
    String now = reason.orElse(null);
    if (now != null) {
      candidateSince = 0;
    }
    if ((now == null) != (unhealthy == null)) {
      unhealthy = now;
      if (now == null) {
        logger.atInfo().log("Write forwarding: node %s is a candidate again", name());
      } else {
        logger.atWarning().log(
            "Write forwarding: node %s is no candidate and hands its writes to peers: %s",
            name(), now);
      }
    }
    return reason;
  }

  /**
   * Whether this node is the preferred executor of writes to {@code project}: no peer it can reach
   * ranks above it. Background work that writes runs where this is true.
   */
  public boolean executes(String project) {
    return preferredPeer(placement == Placement.CLUSTER ? WriteEligibility.CLUSTER_KEY : project)
        .isEmpty();
  }

  /** The peer that should execute a write ranked by {@code key}, or empty when this node should. */
  public Optional<Peer> preferredPeer(String key) {
    return preferredPeer(key, any -> true);
  }

  /** This node's name; empty when it has none, and then it neither forwards nor is a candidate. */
  public String name() {
    try {
      String name = node.get();
      return name == null ? "" : name.trim();
    } catch (RuntimeException failure) {
      logger.atWarning().withCause(failure).log("Cannot tell this node's name");
      return "";
    }
  }

  @Nullable
  public SSLContext tls() {
    return tls;
  }

  public int port() {
    return port;
  }

  public String contextPath() {
    return contextPath;
  }

  /** The peers that answered the last probe and have not failed a hand-over since. */
  public Collection<Peer> peers() {
    return peers.values();
  }

  /**
   * The peer that should execute a write ranked by {@code key}, or empty when this node should.
   * The key is a {@link WriteEligibility.Decision}'s. A node that is {@linkplain #unavailable() no
   * candidate} itself chooses the best peer it can reach.
   *
   * <p>The caller must not ask for a write that a peer handed to this node: that one is executed
   * here.
   *
   * @param usable whether the caller's transport has a working link to the peer
   */
  public Optional<Peer> preferredPeer(String key, Predicate<Peer> usable) {
    ImmutableMap<String, Peer> view = peers;
    if (view.isEmpty()) {
      return Optional.empty();
    }
    String self = name();
    if (self.isEmpty()) {
      return Optional.empty();
    }
    Peer best = null;
    for (Peer peer : view.values()) {
      if (usable.test(peer)
          && (best == null || WriteRouter.outranks(key, peer.name(), best.name()))) {
        best = peer;
      }
    }
    if (best == null
        || (!WriteRouter.outranks(key, best.name(), self)
            && unavailable().isEmpty()
            && !settling())) {
      return Optional.empty();
    }
    return Optional.of(best);
  }

  /** Notes that this node answered a peer's hello: that peer now counts its hellos. */
  public void helloAnswered() {
    if (candidateSince == 0) {
      candidateSince = nanoTime.getAsLong() | 1;
    }
  }

  /**
   * Whether to leave writes with peers while they count {@code walgerrit.forwardingJoinProbes}
   * hellos, so both sides change executor at about the same time.
   */
  private boolean settling() {
    if (joinProbes <= 1) {
      return false;
    }
    long since = candidateSince;
    return since == 0
        || nanoTime.getAsLong() - since
            < (joinProbes - 1) * TimeUnit.MILLISECONDS.toNanos(PROBE_INTERVAL_MILLIS);
  }

  /** Stops preferring {@code peer} until it answers a probe again. */
  public synchronized void failed(Peer peer) {
    if (peer.equals(peers.get(peer.name()))) {
      Map<String, Peer> rest = new LinkedHashMap<>(peers);
      rest.remove(peer.name());
      peers = ImmutableMap.copyOf(rest);
      answers.remove(peer.name());
      logger.atWarning().log(
          "Write forwarding: peer %s at %s failed; its writes are executed here until it answers",
          peer.name(), peer.httpAuthority());
    }
  }

  /** Counts one write by its transport, {@code ssh} or {@code http}, and how handing it went. */
  public void count(String transport, String outcome) {
    requests.increment(transport, outcome);
  }

  /** Counts one write that is executed where it arrived, and why it may not be handed over. */
  public void ineligible(String transport, String reason) {
    requests.increment(transport, "ineligible");
    ineligible.increment(transport, reason);
  }

  private void probeQuietly() {
    try {
      probe();
    } catch (RuntimeException failure) {
      logger.atWarning().withCause(failure).log("Write forwarding: probing the peers failed");
    }
  }

  /** Asks every configured peer for its name and replaces the candidates with those that answer. */
  public synchronized void probe() {
    String self = name();
    Collection<String> configured = tls == null || self.isEmpty() ? List.of() : addresses.get();
    Map<String, Peer> answered = new LinkedHashMap<>();
    if (configured != null && !configured.isEmpty()) {
      for (String address : configured) {
        Peer peer = hello(address);
        if (peer != null && !peer.name().equals(self)) {
          answered.putIfAbsent(peer.name(), peer);
        }
      }
    }
    answers.keySet().retainAll(answered.keySet());
    ImmutableMap<String, Peer> before = peers;
    // A node that is settling leaves its writes to its peers, so it joins them at once.
    int needed = settling() ? 1 : joinProbes;
    Map<String, Peer> joined = new LinkedHashMap<>();
    for (Peer peer : answered.values()) {
      // A peer that keeps answering stays; one that returns waits for its hellos in a row.
      int streak = Math.min(joinProbes, answers.getOrDefault(peer.name(), 0) + 1);
      answers.put(peer.name(), streak);
      if (streak >= needed || peer.equals(before.get(peer.name()))) {
        joined.put(peer.name(), peer);
      }
    }
    ImmutableMap<String, Peer> now = ImmutableMap.copyOf(joined);
    peers = now;
    if (!before.equals(now)) {
      logger.atInfo().log(
          "Write forwarding: node %s has peers %s%s",
          self,
          now.values(),
          placement == Placement.CLUSTER
              ? "; "
                  + preferredPeer(WriteEligibility.CLUSTER_KEY).map(Peer::name).orElse(self)
                  + " executes every write"
              : "");
    }
  }

  @Nullable
  private Peer hello(String address) {
    HttpsURLConnection connection = null;
    try {
      HostAndPort parsed = HostAndPort.fromString(address.trim()).withDefaultPort(port);
      String host = parsed.getHost();
      int port = parsed.getPort();
      String authority = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
      connection =
          (HttpsURLConnection)
              URI.create("https://" + authority + contextPath + HELLO_PATH)
                  .toURL()
                  .openConnection(Proxy.NO_PROXY);
      connection.setSSLSocketFactory(tls.getSocketFactory());
      // Membership is the private trust anchor. Discovery addresses are pod IPs.
      connection.setHostnameVerifier((ignored, session) -> true);
      connection.setConnectTimeout(PROBE_TIMEOUT_MILLIS);
      connection.setReadTimeout(PROBE_TIMEOUT_MILLIS);
      connection.setUseCaches(false);
      connection.setInstanceFollowRedirects(false);
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
          || !PROTOCOL.equals(connection.getHeaderField(PROTOCOL_HEADER))) {
        return null;
      }
      String body;
      try (InputStream in = connection.getInputStream()) {
        body = new String(in.readNBytes(MAX_HELLO_BYTES), UTF_8);
      }
      if (!NODE_NAME.matcher(body).matches()) {
        return null;
      }
      return new Peer(body, host, port);
    } catch (IOException | RuntimeException unreachable) {
      logger.atFine().withCause(unreachable).log("Write forwarding: no hello from %s", address);
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
