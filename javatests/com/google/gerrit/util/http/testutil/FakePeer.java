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
package com.google.gerrit.util.http.testutil;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.WritePeerTls;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.eclipse.jgit.lib.Config;

/** A loopback mutual TLS peer that records decrypted application requests. */
public final class FakePeer implements AutoCloseable {
  public static final String SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final SSLContext TLS = WritePeerTls.fromSecret(SECRET);

  public static Config config() {
    Config config = new Config();
    config.setString("walgerrit", null, "forwardingSecret", SECRET);
    return config;
  }

  public static Config projectConfig() {
    Config config = config();
    config.setString("walgerrit", null, "placement", "project");
    return config;
  }

  private static final String HELLO = "/peerz";

  /** One request other than a hello; {@code body} is null when the connection was passed on. */
  public record Request(
      String method, String target, ImmutableMap<String, String> headers, @Nullable byte[] body) {
    /** The value of a header, whatever the case of its name; null when absent. */
    @Nullable
    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.US));
    }
  }

  private enum Mode {
    ANSWER,
    REFUSE,
    REFUSE_LATE,
    RELAY,
    DROP_REPLY,
    HANG_UP,
    BREAK_BEFORE_INPUT,
    BREAK_AFTER_INPUT
  }

  private final String name;
  private final ServerSocket listener;
  private final List<Request> requests = new ArrayList<>();
  private final Thread acceptor;

  private volatile String helloBody;
  private volatile int helloStatus = 200;
  private volatile boolean helloProtocol = true;
  private volatile String targetName;
  private volatile int discardedStatus;
  private volatile Mode mode = Mode.ANSWER;
  private volatile int status = 200;
  private volatile ImmutableMap<String, String> answerHeaders = ImmutableMap.of();
  private volatile byte[] answerBody = new byte[0];
  private volatile InetSocketAddress relayTo;

  public FakePeer(String name) throws IOException {
    this(name, TLS);
  }

  public FakePeer(String name, SSLContext tls) throws IOException {
    this.name = name;
    this.helloBody = name;
    SSLServerSocket socket =
        (SSLServerSocket)
            tls.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
    socket.setEnabledProtocols(new String[] {"TLSv1.3"});
    socket.setNeedClientAuth(true);
    this.listener = socket;
    this.acceptor = new Thread(this::accept, "FakePeer-" + name);
    acceptor.setDaemon(true);
    acceptor.start();
  }

  /** {@code host:port}, as a node's peer list names it. */
  public String address() {
    return "127.0.0.1:" + listener.getLocalPort();
  }

  public String name() {
    return name;
  }

  public int port() {
    return listener.getLocalPort();
  }

  public void hello(int status, String body) {
    helloStatus = status;
    helloBody = body;
  }

  /** Answers hellos as a node that predates the hand-over protocol header. */
  public void helloWithoutProtocol() {
    helloProtocol = false;
  }

  /** Refuses every request before reading its body, as a destination that executes nothing. */
  public FakePeer refuse(String reason) {
    status = 403;
    answerHeaders =
        ImmutableMap.of(com.google.gerrit.server.WriteForwarding.REFUSED_HEADER, reason);
    answerBody = new byte[0];
    mode = Mode.REFUSE;
    return this;
  }

  /** Refuses after input starts, simulating a late {@code Expect: 100-continue} response. */
  public FakePeer refuseAfterInput(String reason) {
    refuse(reason);
    mode = Mode.REFUSE_LATE;
    return this;
  }

  /** Answers every request with this status, these headers and this body. */
  public FakePeer answer(int withStatus, Map<String, String> headers, String body) {
    status = withStatus;
    answerHeaders = ImmutableMap.copyOf(headers);
    answerBody = body.getBytes(UTF_8);
    mode = Mode.ANSWER;
    return this;
  }

  /** Relays requests to the daemon at {@code target} using its expected destination name. */
  public FakePeer relayTo(InetSocketAddress target, String destinationName) {
    relayTo = target;
    targetName = destinationName;
    mode = Mode.RELAY;
    return this;
  }

  /** Closes every connection once the request's head has arrived, without answering. */
  public FakePeer hangUp() {
    mode = Mode.HANG_UP;
    return this;
  }

  public int discardedStatus() {
    return discardedStatus;
  }

  public void dropReply() {
    mode = Mode.DROP_REPLY;
  }

  public void breakDuringReceive(boolean afterInput) {
    mode = afterInput ? Mode.BREAK_AFTER_INPUT : Mode.BREAK_BEFORE_INPUT;
  }

  public synchronized ImmutableList<Request> requests() {
    return ImmutableList.copyOf(requests);
  }

  @Override
  public void close() throws IOException {
    listener.close();
  }

  private void accept() {
    while (!listener.isClosed()) {
      Socket socket;
      try {
        socket = listener.accept();
      } catch (IOException closed) {
        return;
      }
      Thread handler = new Thread(() -> handle(socket), "FakePeer-" + name + "-Connection");
      handler.setDaemon(true);
      handler.start();
    }
  }

  private void handle(Socket socket) {
    try (socket) {
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      byte[] head = readHead(in);
      if (head == null) {
        return;
      }
      List<String> lines =
          Splitter.on("\r\n").omitEmptyStrings().splitToList(new String(head, ISO_8859_1));
      List<String> requestLine = Splitter.on(' ').splitToList(lines.get(0));
      String method = requestLine.get(0);
      String target = requestLine.get(1);
      Map<String, String> headers = new LinkedHashMap<>();
      for (String line : lines.subList(1, lines.size())) {
        int colon = line.indexOf(':');
        headers.put(
            line.substring(0, colon).toLowerCase(Locale.US), line.substring(colon + 1).trim());
      }
      if (target.equals(HELLO)) {
        respond(
            out,
            helloStatus,
            helloProtocol
                ? ImmutableMap.of(
                    com.google.gerrit.server.WriteForwarding.PROTOCOL_HEADER,
                    com.google.gerrit.server.WriteForwarding.PROTOCOL)
                : ImmutableMap.of(),
            helloBody.getBytes(UTF_8));
        return;
      }
      Mode now = mode;
      if (now == Mode.RELAY || now == Mode.DROP_REPLY) {
        record(method, target, headers, null);
        relay(socket, head, now == Mode.DROP_REPLY);
        return;
      }
      if (now == Mode.HANG_UP) {
        record(method, target, headers, null);
        return;
      }
      if (now == Mode.BREAK_BEFORE_INPUT || now == Mode.BREAK_AFTER_INPUT) {
        record(method, target, headers, null);
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: "
                    + com.google.gerrit.server.WriteForwarding.NATIVE_TYPE
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(ISO_8859_1));
        var bytes = new ByteArrayOutputStream();
        var linesOut = new org.eclipse.jgit.transport.PacketLineOut(bytes);
        linesOut.writeString(
            org.eclipse.jgit.lib.ObjectId.zeroId().name()
                + " capabilities^{}\0report-status delete-refs ofs-delta\n");
        linesOut.end();
        var frames = new com.google.gerrit.server.PeerGitStream(out);
        frames.stream(false).write(bytes.toByteArray());
        out.flush();
        if (now == Mode.BREAK_AFTER_INPUT) {
          int size = Integer.parseInt(readLine(in), 16);
          if (size > 0) {
            in.readNBytes(size);
          }
        }
        return;
      }
      if (now == Mode.REFUSE) {
        record(method, target, headers, null);
        respond(out, status, answerHeaders, answerBody);
        return;
      }
      if (now == Mode.REFUSE_LATE) {
        record(method, target, headers, null);
        if (in.read() >= 0) {
          respond(out, status, answerHeaders, answerBody);
        }
        return;
      }
      if ("100-continue".equalsIgnoreCase(headers.get("expect"))) {
        out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(ISO_8859_1));
        out.flush();
      }
      record(method, target, headers, readBody(in, headers));
      respond(out, status, answerHeaders, answerBody);
    } catch (IOException | RuntimeException failure) {
      // The connection ended; the test sees what its client saw.
    }
  }

  private synchronized void record(
      String method, String target, Map<String, String> headers, @Nullable byte[] body) {
    requests.add(new Request(method, target, ImmutableMap.copyOf(headers), body));
  }

  private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
    if (!"chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
      return in.readNBytes(Integer.parseInt(headers.getOrDefault("content-length", "0")));
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    while (true) {
      int size = Integer.parseInt(readLine(in), 16);
      body.write(in.readNBytes(size));
      String end = readLine(in);
      if (size == 0) {
        return body.toByteArray();
      }
      if (!end.isEmpty()) {
        throw new IOException("malformed chunk");
      }
    }
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    for (int b = in.read(); b >= 0 && b != '\n'; b = in.read()) {
      if (b != '\r') {
        line.append((char) b);
      }
    }
    return line.toString();
  }

  @Nullable
  private static byte[] readHead(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int matched = 0;
    while (matched < 4) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      head.write(b);
      matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : b == '\r' ? 1 : 0;
    }
    return head.toByteArray();
  }

  private static void respond(
      OutputStream out, int status, Map<String, String> headers, byte[] body) throws IOException {
    StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" Fake\r\n");
    headers.forEach((key, value) -> head.append(key).append(": ").append(value).append("\r\n"));
    head.append("Content-Length: ").append(body.length).append("\r\n");
    head.append("Connection: close\r\n\r\n");
    out.write(head.toString().getBytes(ISO_8859_1));
    out.write(body);
    out.flush();
  }

  private static int statusOf(byte[] responseHead) {
    return Integer.parseInt(new String(responseHead, ISO_8859_1).split(" ", 3)[1]);
  }

  private void relay(Socket client, byte[] head, boolean dropReply) throws IOException {
    try (Socket server =
        TLS.getSocketFactory().createSocket(relayTo.getAddress(), relayTo.getPort())) {
      // A loopback daemon sees its own name as the destination of the private hop.
      String headers =
          new String(head, ISO_8859_1)
              .replace(
                  "X-Gerrit-Forwarded-By: " + name + "\r\n",
                  "X-Gerrit-Forwarded-By: " + targetName + "\r\n");
      if (dropReply) {
        headers =
            headers
                .replaceAll("(?im)^Connection: [^\r\n]*\r\n", "")
                .replace("\r\n\r\n", "\r\nConnection: close\r\n\r\n");
      }
      server.getOutputStream().write(headers.getBytes(ISO_8859_1));
      server.getOutputStream().flush();
      Thread upstream =
          new Thread(
              () -> {
                try {
                  client.getInputStream().transferTo(server.getOutputStream());
                  server.shutdownOutput();
                } catch (IOException ended) {
                  // Either side closed.
                }
              },
              "FakePeer-" + name + "-Upstream");
      upstream.setDaemon(true);
      upstream.start();
      try {
        if (dropReply) {
          InputStream answer = server.getInputStream();
          byte[] responseHead = readHead(answer);
          while (responseHead != null && statusOf(responseHead) < 200) {
            // The client waits for an interim answer before it sends its body.
            client.getOutputStream().write(responseHead);
            client.getOutputStream().flush();
            responseHead = readHead(answer);
          }
          if (responseHead != null) {
            discardedStatus = statusOf(responseHead);
            answer.transferTo(OutputStream.nullOutputStream());
          }
        } else {
          server.getInputStream().transferTo(client.getOutputStream());
        }
      } finally {
        client.shutdownOutput();
      }
    }
  }
}
