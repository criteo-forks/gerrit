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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.GossipPeers.Peer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GossipPeersTest {
  @Test
  void parsesHostsWithAndWithoutPorts() {
    assertEquals(new Peer("gerrit-1", 29419), GossipPeers.parse("gerrit-1", 29419));
    assertEquals(new Peer("gerrit-1", 30000), GossipPeers.parse(" gerrit-1:30000 ", 29419));
    assertEquals(new Peer("10.0.0.7", 5), GossipPeers.parse("10.0.0.7:5", 29419));
    assertEquals(new Peer("::1", 77), GossipPeers.parse("[::1]:77", 29419));
    assertEquals(new Peer("::1", 29419), GossipPeers.parse("[::1]", 29419));
    assertEquals(new Peer("fe80::1", 29419), GossipPeers.parse("fe80::1", 29419));
    assertEquals("[::1]:77", GossipPeers.parse("[::1]:77", 29419).toString());
    assertEquals("gerrit-1:29419", GossipPeers.parse("gerrit-1", 29419).toString());
  }

  @Test
  void rejectsMalformedPeers() {
    for (String invalid :
        List.of(
            "", "   ", ":29419", "gerrit-1:", "gerrit-1:0", "gerrit-1:65536", "gerrit-1:sshd",
            "[::1", "[::1]x", "[]:5")) {
      assertThrows(
          IllegalArgumentException.class, () -> GossipPeers.parse(invalid, 29419), invalid);
    }
  }

  @Test
  void resolvesFixedPeersAndADnsNameOnTheDefaultPort() {
    SteppingClock clock = new SteppingClock(Instant.EPOCH);
    GossipPeers peers =
        new GossipPeers(
            List.of("127.0.0.1:1000"), "localhost", 3000, Duration.ofSeconds(30), clock);

    List<InetSocketAddress> addresses = peers.addresses();
    assertTrue(addresses.contains(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1000)));
    assertTrue(
        addresses.stream()
            .anyMatch(
                address -> address.getPort() == 3000 && address.getAddress().isLoopbackAddress()),
        "every address record of the name, on the default port");

    assertSame(addresses, peers.addresses(), "cached until the refresh interval passes");
    clock.advance(Duration.ofSeconds(30));
    List<InetSocketAddress> refreshed = peers.addresses();
    assertNotSame(addresses, refreshed);
    assertEquals(addresses, refreshed);
  }

  @Test
  void describesItsPeerSources() {
    // Resolution failures are not asserted: a resolver that answers for every name, as some
    // corporate ones do, would make that depend on the network the tests run in.
    GossipPeers peers =
        new GossipPeers(
            List.of("gerrit-1", "[::1]:77"),
            "gerrit.gerrit-poc.svc",
            3000,
            Duration.ofSeconds(30),
            new SteppingClock(Instant.EPOCH));
    assertEquals(
        "gerrit-1:3000, [::1]:77, gerrit.gerrit-poc.svc (dns), port 3000", peers.toString());
  }
}
