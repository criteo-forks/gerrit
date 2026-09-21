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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves fixed peers and a DNS service name. Each source keeps its last successful addresses when
 * a refresh fails or returns no records.
 */
final class GossipPeers {
  private static final Logger logger = LoggerFactory.getLogger(GossipPeers.class);

  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /** One configured peer; without an explicit port it takes the cluster's gossip port. */
  record Peer(String host, int port) {
    @Override
    public String toString() {
      return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }
  }

  private final List<Peer> fixed;
  private final String dnsName;
  private final int defaultPort;
  private final long refreshMillis;
  private final Clock clock;
  private final Resolver resolver;
  private final Map<Peer, List<InetSocketAddress>> lastAnswers = new HashMap<>();
  private volatile List<InetSocketAddress> resolved = List.of();
  private volatile long resolvedAtMillis;
  private volatile boolean resolvedOnce;

  /**
   * @param peers fixed {@code host[:port]} entries; see {@link #parse}
   * @param dnsName a name whose every address record is a peer on {@code defaultPort}, or null
   */
  GossipPeers(
      List<String> peers, String dnsName, int defaultPort, Duration refreshInterval, Clock clock) {
    this(peers, dnsName, defaultPort, refreshInterval, clock, InetAddress::getAllByName);
  }

  GossipPeers(
      List<String> peers,
      String dnsName,
      int defaultPort,
      Duration refreshInterval,
      Clock clock,
      Resolver resolver) {
    this.fixed = peers.stream().map(peer -> parse(peer, defaultPort)).toList();
    this.dnsName = dnsName;
    this.defaultPort = defaultPort;
    this.refreshMillis = Math.max(0, refreshInterval.toMillis());
    this.clock = clock;
    this.resolver = resolver;
  }

  static GossipPeers fromConfiguration(WalGitConfiguration configuration, Clock clock) {
    return new GossipPeers(
        configuration.gossipPeers(),
        configuration.gossipPeerDnsName(),
        configuration.gossipPort(),
        configuration.gossipPeerRefreshInterval(),
        clock);
  }

  /**
   * Parses {@code host}, {@code host:port} or {@code [ipv6]:port}; a bare IPv6 address takes the
   * default port.
   *
   * @throws IllegalArgumentException for an empty host or a port outside 1-65535
   */
  static Peer parse(String peer, int defaultPort) {
    String value = peer.trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("empty peer");
    }
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      if (close < 2) {
        throw new IllegalArgumentException("malformed IPv6 peer: " + peer);
      }
      String host = value.substring(1, close);
      String rest = value.substring(close + 1);
      if (rest.isEmpty()) {
        return new Peer(host, defaultPort);
      }
      if (!rest.startsWith(":")) {
        throw new IllegalArgumentException("expected :port after ] in " + peer);
      }
      return new Peer(host, port(rest.substring(1), peer));
    }
    int colon = value.indexOf(':');
    if (colon < 0 || value.indexOf(':', colon + 1) >= 0) {
      // A host name or IPv4 address, or a bare IPv6 address, without a port.
      return new Peer(value, defaultPort);
    }
    String host = value.substring(0, colon);
    if (host.isEmpty()) {
      throw new IllegalArgumentException("empty host in " + peer);
    }
    return new Peer(host, port(value.substring(colon + 1), peer));
  }

  private static int port(String text, String peer) {
    int port;
    try {
      port = Integer.parseInt(text);
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException("invalid port in " + peer, notANumber);
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port out of range in " + peer);
    }
    return port;
  }

  /** Every peer address, resolving names again once the refresh interval has passed. */
  List<InetSocketAddress> addresses() {
    long now = clock.millis();
    if (!resolvedOnce || now - resolvedAtMillis >= refreshMillis) {
      resolve(now);
    }
    return resolved;
  }

  private synchronized void resolve(long now) {
    if (resolvedOnce && now - resolvedAtMillis < refreshMillis) {
      return; // Another thread resolved meanwhile.
    }
    Set<InetSocketAddress> addresses = new LinkedHashSet<>();
    for (Peer peer : fixed) {
      addAll(addresses, peer.host(), peer.port());
    }
    if (dnsName != null) {
      addAll(addresses, dnsName, defaultPort);
    }
    resolved = List.copyOf(addresses);
    resolvedAtMillis = now;
    resolvedOnce = true;
  }

  private void addAll(Set<InetSocketAddress> into, String host, int port) {
    Peer peer = new Peer(host, port);
    try {
      InetAddress[] addresses = resolver.resolve(host);
      if (addresses.length > 0) {
        lastAnswers.put(
            peer,
            java.util.Arrays.stream(addresses)
                .map(address -> new InetSocketAddress(address, port))
                .toList());
      }
    } catch (UnknownHostException unknown) {
      logger.warn("WalGerrit gossip cannot resolve peer {}; keeping the previous answer", host);
    }
    into.addAll(lastAnswers.getOrDefault(peer, List.of()));
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder();
    for (Peer peer : fixed) {
      text.append(text.isEmpty() ? "" : ", ").append(peer);
    }
    if (dnsName != null) {
      text.append(text.isEmpty() ? "" : ", ")
          .append(dnsName)
          .append(" (dns), port ")
          .append(defaultPort);
    }
    return text.toString();
  }
}
