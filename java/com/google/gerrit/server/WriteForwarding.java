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

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HostAndPort;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.eclipse.jgit.lib.Config;

/**
 * Chooses the node of a cluster that should execute a write to a project.
 *
 * <p>Nodes that share their repositories all accept writes, and one node executing a project's
 * writes avoids the waits that interleaved writers cause each other. A node that receives a write
 * therefore hands it to the node {@link WriteRouter} prefers for the project, when it has a working
 * link to that node, and executes it itself otherwise. A node always executes what a peer handed
 * it, so a write crosses at most one link however the nodes' views of each other differ.
 *
 * <p>The plugin supplies a stable node name and peer addresses. A peer joins the candidate set when
 * its private TLS listener answers a hello while the serving gate is open.
 */
@Singleton
public class WriteForwarding implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String NODE = "writeForwardingNode";
  public static final String PEERS = "writeForwardingPeers";

  public static final Key<Supplier<String>> NODE_KEY =
      Key.get(new TypeLiteral<Supplier<String>>() {}, Names.named(NODE));

  public static final Key<Supplier<Collection<String>>> PEERS_KEY =
      Key.get(new TypeLiteral<Supplier<Collection<String>>>() {}, Names.named(PEERS));

  /** Private readiness and node identity endpoint. */
  public static final String HELLO_PATH = "/peerz";

  /** Set by Jetty on requests from the mutually authenticated peer connector. */
  public static final String PEER_CONNECTOR = WriteForwarding.class.getName() + ".connector";

  public static final String ACCOUNT_HEADER = "X-Gerrit-Peer-Account";
  public static final String ADDRESS_HEADER = "X-Gerrit-Peer-Address";
  public static final String NATIVE_HEADER = "X-Gerrit-Peer-Receive";
  public static final String NATIVE_TYPE = "application/x-walgerrit-duplex-receive-pack";

  public static final String FORWARDED_HEADER = "X-Gerrit-Forwarded-By";

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
      listener().to(WriteForwarding.class);
    }
  }

  private final Supplier<String> node;
  private final Supplier<Collection<String>> addresses;
  private final String contextPath;
  private final int port;
  @Nullable private final SSLContext tls;
  private final GerritRuntime runtime;
  private final Counter2<String, String> requests;

  private volatile ImmutableMap<String, Peer> peers = ImmutableMap.of();
  private ScheduledExecutorService prober;

  @Inject
  public WriteForwarding(
      @Named(NODE) Supplier<String> node,
      @Named(PEERS) Supplier<Collection<String>> addresses,
      @GerritServerConfig Config config,
      GerritRuntime runtime,
      MetricMaker metrics) {
    this.node = node;
    this.addresses = addresses;
    this.tls = configuredTls(config);
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
                "forwarded: the peer answered; fallback: executed here because the peer could not"
                    + " be reached; busy: executed here because too many threads were handing"
                    + " writes over; inbound_busy: destination admission limit reached; failed: the"
                    + " link broke after the peer may have acted"));
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
   * The peer that should execute a write to {@code project}, or empty when this node should.
   *
   * <p>The caller must not ask for a write that a peer handed to this node: that one is executed
   * here.
   *
   * @param usable whether the caller's transport has a working link to the peer
   */
  public Optional<Peer> preferredPeer(String project, Predicate<Peer> usable) {
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
          && (best == null || WriteRouter.outranks(project, peer.name(), best.name()))) {
        best = peer;
      }
    }
    if (best == null || !WriteRouter.outranks(project, best.name(), self)) {
      return Optional.empty();
    }
    return Optional.of(best);
  }

  /** Stops preferring {@code peer} until it answers a probe again. */
  public synchronized void failed(Peer peer) {
    if (peer.equals(peers.get(peer.name()))) {
      Map<String, Peer> rest = new LinkedHashMap<>(peers);
      rest.remove(peer.name());
      peers = ImmutableMap.copyOf(rest);
      logger.atWarning().log(
          "Write forwarding: peer %s at %s failed; its projects are written here until it answers",
          peer.name(), peer.httpAuthority());
    }
  }

  /** Counts one write by its transport, {@code ssh} or {@code http}, and how handing it went. */
  public void count(String transport, String outcome) {
    requests.increment(transport, outcome);
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
    ImmutableMap<String, Peer> before = peers;
    ImmutableMap<String, Peer> now = ImmutableMap.copyOf(answered);
    peers = now;
    if (!before.equals(now)) {
      logger.atInfo().log("Write forwarding: node %s has peers %s", self, now.values());
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
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
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
