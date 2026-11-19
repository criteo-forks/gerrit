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

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.PeerGitStream;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteForwarding.Peer;
import com.google.gerrit.sshd.BaseCommand.Failure;
import com.google.gerrit.sshd.BaseCommand.RelayedExit;
import com.google.gerrit.sshd.BaseCommand.UnloggedFailure;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.DefaultBHttpClientConnection;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;

/** Streams a native receive-pack through the peer HTTPS connector. */
@Singleton
public final class PeerRelay {
  static final String NOT_APPLIED =
      "The peer stopped answering before receiving client input. Nothing was written. Please"
          + " retry.";
  static final String AMBIGUOUS =
      "The peer stopped answering. The push may or may not have been applied. Fetch before you push"
          + " again.";
  private final WriteForwarding forwarding;
  private final Map<ThreadPoolExecutor, AtomicInteger> outgoing = new ConcurrentHashMap<>();

  @Inject
  PeerRelay(WriteForwarding forwarding) {
    this.forwarding = forwarding;
  }

  public boolean receivePack(
      String project,
      Account.Id account,
      SocketAddress from,
      ThreadPoolExecutor pool,
      InputStream in,
      OutputStream out,
      OutputStream err)
      throws IOException, Failure {
    Optional<Peer> target = forwarding.preferredPeer(project, any -> true);
    if (target.isEmpty() || !(from instanceof InetSocketAddress client) || client.isUnresolved()) {
      return false;
    }
    AtomicInteger count = outgoing.computeIfAbsent(pool, unused -> new AtomicInteger());
    if (count.incrementAndGet() > pool.getCorePoolSize() / 2) {
      count.decrementAndGet();
      forwarding.count("ssh", "busy");
      return false;
    }
    try {
      return relay(target.get(), project, account, client, in, out, err);
    } finally {
      count.decrementAndGet();
    }
  }

  private boolean relay(
      Peer peer,
      String project,
      Account.Id account,
      InetSocketAddress from,
      InputStream in,
      OutputStream out,
      OutputStream err)
      throws IOException, Failure {
    AtomicBoolean consumed = new AtomicBoolean();
    boolean started = false;
    Thread pump = null;
    try (SSLSocket socket = (SSLSocket) forwarding.tls().getSocketFactory().createSocket();
        var connection = new DefaultBHttpClientConnection(PeerGitStream.MAX_FRAME)) {
      socket.connect(new InetSocketAddress(peer.host(), peer.httpPort()), 2000);
      socket.setSoTimeout(5000);
      socket.setEnabledProtocols(new String[] {"TLSv1.3"});
      socket.startHandshake();
      connection.bind(socket);
      String path = forwarding.contextPath() + "/a/" + Url.encode(project) + "/git-receive-pack";
      var request = new BasicHttpEntityEnclosingRequest("POST", path);
      request.setHeader("Host", peer.httpAuthority());
      request.setHeader("Connection", "close");
      request.setHeader("Transfer-Encoding", "chunked");
      request.setHeader("Content-Type", "application/x-git-receive-pack-request");
      request.setHeader(WriteForwarding.FORWARDED_HEADER, peer.name());
      request.setHeader(WriteForwarding.ACCOUNT_HEADER, Integer.toString(account.get()));
      request.setHeader(WriteForwarding.ADDRESS_HEADER, from.getAddress().getHostAddress());
      request.setHeader(WriteForwarding.NATIVE_HEADER, "1");
      request.setEntity(
          new InputStreamEntity(in, -1) {
            @Override
            public void writeTo(OutputStream dst) throws IOException {
              byte[] buffer = new byte[PeerGitStream.MAX_FRAME];
              int n;
              while ((n = in.read(buffer)) >= 0) {
                if (n > 0) {
                  consumed.set(true);
                  dst.write(buffer, 0, n);
                  dst.flush();
                }
              }
            }
          });
      connection.sendRequestHeader(request);
      connection.flush();
      HttpResponse answer = connection.receiveResponseHeader();
      if (answer.getStatusLine().getStatusCode() == 503) {
        forwarding.count("ssh", "fallback");
        return false;
      }
      if (answer.getStatusLine().getStatusCode() != 200
          || answer.getFirstHeader("Content-Type") == null
          || !WriteForwarding.NATIVE_TYPE.equals(
              answer.getFirstHeader("Content-Type").getValue())) {
        forwarding.count("ssh", "forwarded");
        throw new UnloggedFailure(
            1,
            "fatal: push not permitted or unavailable on peer ("
                + answer.getStatusLine().getStatusCode()
                + ")");
      }
      connection.receiveResponseEntity(answer);
      socket.setSoTimeout(15 * 60 * 1000);
      var reply = new DataInputStream(answer.getEntity().getContent());
      byte[] buffer = new byte[PeerGitStream.MAX_FRAME];
      while (true) {
        int frame = reply.readInt();
        if (frame == 0) {
          int status = reply.readUnsignedByte();
          forwarding.count("ssh", "forwarded");
          if (status != 0) {
            throw new RelayedExit(status);
          }
          return true;
        }
        if (frame < -buffer.length || frame > buffer.length) {
          throw new IOException("Invalid peer receive-pack frame");
        }
        int n = Math.abs(frame);
        reply.readFully(buffer, 0, n);
        started = true;
        OutputStream dst = frame < 0 ? err : out;
        dst.write(buffer, 0, n);
        dst.flush();
        if (frame > 0 && pump == null) {
          pump =
              Thread.ofVirtual()
                  .name("PeerReceivePack-input")
                  .start(
                      () -> {
                        try {
                          connection.sendRequestEntity(request);
                          connection.flush();
                        } catch (IOException | HttpException failure) {
                          try {
                            socket.close();
                          } catch (IOException ignored) {
                            // Closing the socket wakes the response reader.
                          }
                        }
                      });
        }
      }
    } catch (IOException | HttpException failure) {
      forwarding.failed(peer);
      if (!started) {
        forwarding.count("ssh", "fallback");
        return false;
      }
      forwarding.count("ssh", "failed");
      throw new UnloggedFailure(1, consumed.get() ? AMBIGUOUS : NOT_APPLIED, failure);
    } finally {
      if (pump != null) {
        pump.interrupt();
        try {
          in.close();
        } catch (IOException ignored) {
          // The response reader has already determined the result of the push.
        }
      }
    }
  }
}
