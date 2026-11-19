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

/**
 * A stand-in for another node of a cluster, listening for HTTP on a loopback port.
 *
 * <p>It answers the hello that names a node and its SSH port. What it does with any other request
 * is chosen by the test: answer it, pass the connection through to a real server, hang up after
 * reading the request's head, or refuse as not serving. It records the requests it received.
 */
public final class FakePeer implements AutoCloseable {
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
    RELAY,
    HANG_UP
  }

  private final String name;
  private final ServerSocket listener;
  private final List<Request> requests = new ArrayList<>();
  private final Thread acceptor;

  private volatile int sshPort;
  private volatile Mode mode = Mode.ANSWER;
  private volatile int status = 200;
  private volatile ImmutableMap<String, String> answerHeaders = ImmutableMap.of();
  private volatile byte[] answerBody = new byte[0];
  private volatile InetSocketAddress relayTo;

  public FakePeer(String name) throws IOException {
    this.name = name;
    this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    this.acceptor = new Thread(this::accept, "FakePeer-" + name);
    acceptor.setDaemon(true);
    acceptor.start();
  }

  /** {@code host:port}, as a node's peer list names it. */
  public String address() {
    return listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort();
  }

  public String name() {
    return name;
  }

  /** The SSH port the hello announces; 0, the default, for none. */
  public FakePeer sshPort(int port) {
    sshPort = port;
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

  /** Passes every connection through to the server at {@code target}, byte for byte. */
  public FakePeer relayTo(InetSocketAddress target) {
    relayTo = target;
    mode = Mode.RELAY;
    return this;
  }

  /** Closes every connection once the request's head has arrived, without answering. */
  public FakePeer hangUp() {
    mode = Mode.HANG_UP;
    return this;
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
        respond(out, 200, ImmutableMap.of(), (name + "\n" + sshPort + "\n").getBytes(UTF_8));
        return;
      }
      Mode now = mode;
      if (now == Mode.RELAY) {
        record(method, target, headers, null);
        relay(socket, head);
        return;
      }
      if (now == Mode.HANG_UP) {
        record(method, target, headers, null);
        return;
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

  private void relay(Socket client, byte[] head) throws IOException {
    try (Socket server = new Socket(relayTo.getAddress(), relayTo.getPort())) {
      server.getOutputStream().write(head);
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
        server.getInputStream().transferTo(client.getOutputStream());
      } finally {
        client.shutdownOutput();
      }
    }
  }
}
