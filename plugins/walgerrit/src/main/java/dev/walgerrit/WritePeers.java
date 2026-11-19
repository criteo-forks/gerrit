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

import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Names the hosts of the other nodes to Gerrit, which hands a project's writes to the node it
 * prefers for the project.
 *
 * <p>The hosts are the gossip peers: each {@code gossipPeer} and every address of {@code
 * gossipPeerDnsName}, without their gossip ports. Gerrit reaches each on its configured private
 * HTTPS port, asks it for its name, and ignores the one that is this node. The node's name is
 * {@link ManifestStore#writerHost()}; {@link WalGitIndexModule} binds both. Without a peer source,
 * or with {@code forwardWrites} off, there are no hosts and every write is executed where it
 * arrives.
 */
@Singleton
final class WritePeers implements Supplier<Collection<String>> {
  private final boolean active;
  private final GossipPeers peers;

  @Inject
  WritePeers(GitRepositoryManager repositories) {
    this(configuration(repositories), Clock.systemUTC());
  }

  WritePeers(WalGitConfiguration configuration, Clock clock) {
    this(configuration.forwardingActive(), GossipPeers.fromConfiguration(configuration, clock));
  }

  WritePeers(boolean active, GossipPeers peers) {
    this.active = active;
    this.peers = peers;
  }

  @Override
  public Collection<String> get() {
    if (!active) {
      return List.of();
    }
    Set<String> hosts = new LinkedHashSet<>();
    for (InetSocketAddress address : peers.addresses()) {
      InetAddress resolved = address.getAddress();
      hosts.add(resolved == null ? address.getHostString() : resolved.getHostAddress());
    }
    return List.copyOf(hosts);
  }

  private static WalGitConfiguration configuration(GitRepositoryManager repositories) {
    if (repositories instanceof WalGitRepositoryManager walGit) {
      return walGit.configuration();
    }
    throw new IllegalStateException(
        "WalGitIndexModule requires WalGitRepositoryManager, found "
            + repositories.getClass().getName());
  }
}
