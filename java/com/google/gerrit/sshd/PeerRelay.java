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
package com.google.gerrit.sshd;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.PeerDaemonUser;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteForwarding.Peer;
import com.google.gerrit.sshd.BaseCommand.Failure;
import com.google.gerrit.sshd.BaseCommand.RelayedExit;
import com.google.gerrit.sshd.BaseCommand.UnloggedFailure;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.io.IoAcceptor;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * Lets the peer daemon that {@link WriteForwarding} prefers for a project run a client's push.
 *
 * <p>This daemon keeps one SSH session to each peer. It logs in as {@link PeerDaemonUser} with its
 * own host keys, which the peer accepts when they are its host keys too or are listed in its {@code
 * etc/peer_keys}, and it accepts the peer's host key under the same rule. A peer is a candidate for
 * a push only while that session is open.
 *
 * <p>A push is handed over as {@code suexec --as ACCOUNT --from CLIENT -- git-receive-pack ...} on
 * a channel of that session: the peer runs it as the account this daemon authenticated, checks that
 * account's permissions itself, and never hands it on.
 *
 * <p>A handed-over push holds the thread of its command until the peer has answered, and the peer
 * needs a thread of the same kind of pool to run it. At most half of a pool's threads hand pushes
 * over at a time, and further pushes run here, so that two daemons handing pushes to each other
 * always keep threads to run them.
 *
 * <p>The push runs here after all when the channel ends before the peer said anything: nothing of
 * the client's has been sent by then, so the peer cannot have acted. After the peer's first byte a
 * broken link fails the push and is never retried; the message says whether any of the client's
 * data had been sent.
 */
@Singleton
public class PeerRelay implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String GIT_PROTOCOL = "GIT_PROTOCOL";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration HEARTBEAT = Duration.ofSeconds(10);
  private static final int MAX_STDERR_BYTES = 64 * 1024;

  static final String NOT_APPLIED =
      "fatal: the server handling this push stopped answering before it received anything."
          + " Nothing was written. Please retry.";
  static final String AMBIGUOUS =
      "fatal: the server handling this push stopped answering. The push may or may not have been"
          + " applied. Fetch before you push again.";

  private final WriteForwarding forwarding;
  private final SshDaemon daemon;
  private final KeyPairProvider hostKeys;
  private final DatabasePubKeyAuth peerKeys;
  private final Map<String, Link> links = new ConcurrentHashMap<>();
  private final Map<ThreadPoolExecutor, AtomicInteger> handingOver = new ConcurrentHashMap<>();

  private volatile SshClient client;
  private volatile boolean stopped;
  private volatile int port;
  private volatile ExecutorService inputPumps;

  @Inject
  PeerRelay(
      WriteForwarding forwarding,
      SshDaemon daemon,
      KeyPairProvider hostKeys,
      DatabasePubKeyAuth peerKeys) {
    this.forwarding = forwarding;
    this.daemon = daemon;
    this.hostKeys = hostKeys;
    this.peerKeys = peerKeys;
  }

  @Override
  public void start() {
    forwarding.sshPort(this::boundPort);
    forwarding.onProbe(this::link);
  }

  /** The client that logs in to peers, started when the first peer appears. */
  @Nullable
  private synchronized SshClient client() {
    if (client != null || stopped) {
      return client;
    }
    SshClient starting = SshClient.setUpDefaultClient();
    starting.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
    starting.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
    starting.setKeyIdentityProvider(hostKeys);
    starting.setServerKeyVerifier((session, address, key) -> peerKeys.isPeerKey(key));
    CoreModuleProperties.IO_CONNECT_TIMEOUT.set(starting, CONNECT_TIMEOUT);
    CoreModuleProperties.AUTH_TIMEOUT.set(starting, AUTH_TIMEOUT);
    CoreModuleProperties.HEARTBEAT_INTERVAL.set(starting, HEARTBEAT);
    CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(starting, 2);
    starting.start();
    inputPumps =
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "SSH-PeerRelay-Input");
              thread.setDaemon(true);
              return thread;
            });
    client = starting;
    return client;
  }

  @Override
  public synchronized void stop() {
    stopped = true;
    SshClient stopping = client;
    client = null;
    if (stopping != null) {
      links.values().forEach(Link::close);
      links.clear();
      stopping.stop();
      inputPumps.shutdownNow();
    }
  }

  private int boundPort() {
    if (port == 0) {
      IoAcceptor acceptor = daemon.getIoAcceptor();
      if (acceptor != null) {
        for (SocketAddress bound : acceptor.getBoundAddresses()) {
          if (bound instanceof InetSocketAddress) {
            port = ((InetSocketAddress) bound).getPort();
            break;
          }
        }
      }
    }
    return port;
  }

  /** Opens a session to each peer that has none, and drops the sessions of peers that left. */
  private void link(Collection<Peer> peers) {
    links
        .entrySet()
        .removeIf(
            entry -> {
              if (peers.contains(entry.getValue().peer)) {
                return false;
              }
              entry.getValue().close();
              return true;
            });
    SshClient connector = null;
    for (Peer peer : peers) {
      if (peer.sshPort() > 0) {
        connector = connector == null ? client() : connector;
        if (connector == null) {
          return;
        }
        links.computeIfAbsent(peer.name(), name -> new Link(peer)).ensureOpen(connector);
      }
    }
  }

  /** Whether this daemon holds an open session to the peer named {@code name}. */
  public boolean isLinked(String name) {
    Link link = links.get(name);
    return link != null && link.session() != null;
  }

  private boolean linked(Peer peer) {
    Link link = links.get(peer.name());
    return link != null && link.peer.equals(peer) && link.session() != null;
  }

  /**
   * Runs {@code git-receive-pack} for {@code project} on the preferred peer, if there is one.
   *
   * @param arguments the command's arguments as the client gave them
   * @param gitProtocol the client's {@code GIT_PROTOCOL} values
   * @param pool the pool whose thread runs the client's command
   * @return false when the push must run on this daemon; nothing was read from {@code in} and
   *     nothing was written to {@code out} or {@code err}
   * @throws Failure with the peer's exit code when that is not 0, or when the link broke after the
   *     peer started to answer
   */
  public boolean receivePack(
      String project,
      Account.Id account,
      SocketAddress from,
      String[] arguments,
      @Nullable String[] gitProtocol,
      ThreadPoolExecutor pool,
      InputStream in,
      OutputStream out,
      OutputStream err)
      throws IOException, Failure {
    Optional<Peer> preferred = forwarding.preferredPeer(project, this::linked);
    Link link = preferred.map(peer -> links.get(peer.name())).orElse(null);
    ClientSession session = link == null ? null : link.session();
    if (session == null) {
      return false;
    }
    AtomicInteger inFlight = handingOver.computeIfAbsent(pool, any -> new AtomicInteger());
    if (inFlight.incrementAndGet() > pool.getCorePoolSize() / 2) {
      inFlight.decrementAndGet();
      forwarding.count("ssh", "busy");
      return false;
    }
    try {
      return handOver(
          preferred.get(), session, project, account, from, arguments, gitProtocol, in, out, err);
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private boolean handOver(
      Peer peer,
      ClientSession session,
      String project,
      Account.Id account,
      SocketAddress from,
      String[] arguments,
      @Nullable String[] gitProtocol,
      InputStream in,
      OutputStream out,
      OutputStream err)
      throws IOException, Failure {
    BoundedBuffer errors = new BoundedBuffer(MAX_STDERR_BYTES);
    ChannelExec channel;
    try {
      channel = session.createExecChannel(command(account, from, arguments));
      if (gitProtocol != null) {
        channel.setEnv(GIT_PROTOCOL, String.join(":", gitProtocol));
      }
      channel.setErr(errors);
      channel.open().verify(OPEN_TIMEOUT);
    } catch (IOException | RuntimeException failure) {
      return runHere(peer, failure);
    }
    try {
      InputStream answer = channel.getInvertedOut();
      byte[] buffer = new byte[16 * 1024];
      int read;
      try {
        read = answer.read(buffer);
      } catch (IOException failure) {
        return runHere(peer, failure);
      }
      if (read < 0) {
        Integer status = exitStatus(channel);
        if (status == null) {
          return runHere(peer, null);
        }
        return answered(status, errors, err);
      }

      AtomicLong sent = new AtomicLong();
      OutputStream toPeer = channel.getInvertedIn();
      Future<?> input = inputPumps.submit(() -> pump(in, toPeer, sent));
      try {
        do {
          out.write(buffer, 0, read);
          if (answer.available() == 0) {
            out.flush();
          }
          try {
            read = answer.read(buffer);
          } catch (IOException failure) {
            logger.atWarning().withCause(failure).log(
                "Write forwarding: the link to %s broke during a push to %s", peer.name(), project);
            read = -1;
          }
        } while (read >= 0);
        out.flush();
        Integer status = exitStatus(channel);
        if (status == null) {
          forwarding.count("ssh", "failed");
          errors.writeTo(err);
          throw new UnloggedFailure(1, sent.get() == 0 ? NOT_APPLIED : AMBIGUOUS);
        }
        return answered(status, errors, err);
      } finally {
        input.cancel(true);
      }
    } finally {
      channel.close(true);
    }
  }

  private boolean runHere(Peer peer, @Nullable Throwable failure) {
    logger.atInfo().withCause(failure).log(
        "Write forwarding: running a push here; %s did not answer", peer.name());
    forwarding.count("ssh", "fallback");
    return false;
  }

  private boolean answered(int status, BoundedBuffer errors, OutputStream err)
      throws IOException, Failure {
    forwarding.count("ssh", "forwarded");
    errors.writeTo(err);
    err.flush();
    if (status != 0) {
      throw new RelayedExit(status);
    }
    return true;
  }

  @Nullable
  private static Integer exitStatus(ChannelExec channel) {
    channel.waitFor(
        EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS), EXIT_TIMEOUT);
    return channel.getExitStatus();
  }

  private static void pump(InputStream from, OutputStream to, AtomicLong sent) {
    byte[] buffer = new byte[16 * 1024];
    try (to) {
      int read;
      while ((read = from.read(buffer)) >= 0) {
        sent.addAndGet(read);
        to.write(buffer, 0, read);
        to.flush();
      }
    } catch (IOException | RuntimeException ended) {
      logger.atFine().withCause(ended).log("Write forwarding: a push's input ended");
    }
  }

  static String command(Account.Id account, SocketAddress from, String[] arguments) {
    StringBuilder command = new StringBuilder("suexec --as ").append(account.get());
    if (from instanceof InetSocketAddress) {
      InetSocketAddress client = (InetSocketAddress) from;
      if (client.getAddress() != null) {
        String host = client.getAddress().getHostAddress();
        int scope = host.indexOf('%');
        host = scope < 0 ? host : host.substring(0, scope);
        command
            .append(" --from ")
            .append(host.indexOf(':') >= 0 ? "[" + host + "]" : host)
            .append(':')
            .append(client.getPort());
      }
    }
    command.append(" -- git-receive-pack");
    for (String argument : arguments) {
      command.append(' ').append(quote(argument));
    }
    return command.toString();
  }

  /** Quotes for {@link CommandFactoryProvider#split}. */
  private static String quote(String argument) {
    return '"' + argument.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  /**
   * The session to one peer, reopened after each probe that finds it closed. A session that stops
   * answering is closed by its heartbeat.
   */
  private static final class Link {
    final Peer peer;
    private ClientSession session;
    private boolean connecting;
    private boolean closed;
    private String lastFailure;

    Link(Peer peer) {
      this.peer = peer;
    }

    @Nullable
    synchronized ClientSession session() {
      return session != null && session.isOpen() && session.isAuthenticated() ? session : null;
    }

    synchronized void ensureOpen(SshClient connector) {
      if (connecting || session() != null) {
        return;
      }
      connecting = true;
      session = null;
      try {
        ConnectFuture connect =
            connector.connect(PeerDaemonUser.USER_NAME, peer.host(), peer.sshPort());
        connect.addListener(
            connected -> {
              if (!connected.isConnected()) {
                failed(connected.getException());
                return;
              }
              ClientSession opened = connected.getSession();
              try {
                opened
                    .auth()
                    .addListener(
                        auth -> {
                          if (auth.isSuccess()) {
                            opened(opened);
                          } else {
                            opened.close(true);
                            failed(auth.getException());
                          }
                        });
              } catch (IOException | RuntimeException failure) {
                opened.close(true);
                failed(failure);
              }
            });
      } catch (IOException | RuntimeException failure) {
        failed(failure);
      }
    }

    private synchronized void opened(ClientSession opened) {
      connecting = false;
      if (closed) {
        opened.close(true);
        return;
      }
      session = opened;
      lastFailure = null;
      logger.atInfo().log(
          "Write forwarding: SSH link to %s at %s:%d is open",
          peer.name(), peer.host(), peer.sshPort());
    }

    private synchronized void failed(@Nullable Throwable failure) {
      connecting = false;
      String reason = String.valueOf(failure);
      if (!reason.equals(lastFailure)) {
        lastFailure = reason;
        logger.atWarning().log(
            "Write forwarding: no SSH link to %s at %s:%d, so SSH pushes to its projects run"
                + " here: %s",
            peer.name(), peer.host(), peer.sshPort(), reason);
      }
    }

    synchronized void close() {
      closed = true;
      if (session != null) {
        session.close(true);
        session = null;
      }
    }
  }

  /** Keeps the first {@code limit} bytes written to it. */
  private static final class BoundedBuffer extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int limit;

    BoundedBuffer(int limit) {
      this.limit = limit;
    }

    @Override
    public synchronized void write(int b) {
      if (bytes.size() < limit) {
        bytes.write(b);
      }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      bytes.write(b, off, Math.max(0, Math.min(len, limit - bytes.size())));
    }

    synchronized void writeTo(OutputStream to) throws IOException {
      bytes.writeTo(to);
      bytes.reset();
    }
  }
}
