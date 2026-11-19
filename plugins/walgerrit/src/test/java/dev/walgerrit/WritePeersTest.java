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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.lib.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WritePeersTest {
  @TempDir Path sitePath;

  @Test
  void namesEachPeerHostOnceWithoutItsGossipPort() throws Exception {
    Map<String, InetAddress[]> answers =
        Map.of(
            "gerrit-9",
            new InetAddress[] {InetAddress.getByName("192.0.2.9")},
            "gerrit-headless",
            new InetAddress[] {
              InetAddress.getByName("192.0.2.1"),
              InetAddress.getByName("192.0.2.9"),
              InetAddress.getByName("2001:db8::7")
            });
    GossipPeers gossip =
        new GossipPeers(
            List.of("gerrit-9:30000"),
            "gerrit-headless",
            29419,
            Duration.ofSeconds(30),
            Clock.systemUTC(),
            host -> {
              if (!answers.containsKey(host)) {
                throw new UnknownHostException(host);
              }
              return answers.get(host);
            });

    assertEquals(
        List.of("192.0.2.9", "192.0.2.1", "2001:db8:0:0:0:0:0:7"),
        List.copyOf(new WritePeers(true, gossip).get()));
    assertEquals(List.of(), List.copyOf(new WritePeers(false, gossip).get()));
  }

  @Test
  void forwardingNeedsAPeerSourceAndCanBeTurnedOff() {
    WalGitConfiguration alone = WalGitConfiguration.from(new Config(), sitePath);
    assertTrue(alone.forwardWrites());
    assertFalse(alone.forwardingActive());
    assertEquals(List.of(), List.copyOf(new WritePeers(alone, Clock.systemUTC()).get()));

    Config config = new Config();
    config.setString("walgerrit", null, "gossipPeer", "192.0.2.4");
    WalGitConfiguration clustered = WalGitConfiguration.from(config, sitePath);
    assertTrue(clustered.forwardingActive());
    assertEquals(
        List.of("192.0.2.4"), List.copyOf(new WritePeers(clustered, Clock.systemUTC()).get()));

    config.setBoolean("walgerrit", null, "gossipEnabled", false);
    assertTrue(WalGitConfiguration.from(config, sitePath).forwardingActive());

    config.setBoolean("walgerrit", null, "forwardWrites", false);
    WalGitConfiguration off = WalGitConfiguration.from(config, sitePath);
    assertFalse(off.forwardingActive());
    assertEquals(List.of(), List.copyOf(new WritePeers(off, Clock.systemUTC()).get()));
  }
}
