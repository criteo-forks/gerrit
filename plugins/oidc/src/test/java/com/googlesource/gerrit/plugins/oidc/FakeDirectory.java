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

package com.googlesource.gerrit.plugins.oidc;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An LDAP server on a loopback port, speaking just enough of the protocol for a password check.
 * Like Active Directory, it answers searches only after the service account binds.
 */
class FakeDirectory implements AutoCloseable {
  /** How the directory treats a bind as a user. */
  enum UserBinds {
    ANSWER,
    DROP_CONNECTION,
    BUSY,
    NEVER_ANSWER
  }

  static final String ACCOUNT_BASE = "ou=people,dc=example";
  static final String SERVICE_DN = "cn=svc-gerrit,dc=example";
  static final String SERVICE_PASSWORD = "service-password";

  private static final int SEQUENCE = 0x30;
  private static final int INTEGER = 0x02;
  private static final int OCTET_STRING = 0x04;
  private static final int ENUMERATED = 0x0a;
  private static final int BIND_REQUEST = 0x60;
  private static final int BIND_RESPONSE = 0x61;
  private static final int UNBIND_REQUEST = 0x42;
  private static final int SEARCH_REQUEST = 0x63;
  private static final int SEARCH_ENTRY = 0x64;
  private static final int SEARCH_DONE = 0x65;
  private static final int SUCCESS = 0;
  private static final int OPERATIONS_ERROR = 1;
  private static final int INVALID_CREDENTIALS = 49;
  private static final int BUSY = 51;

  private final ServerSocket server;
  private final Map<String, String> passwords = new ConcurrentHashMap<>();
  private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
  private final AtomicInteger userBindCount = new AtomicInteger();
  private volatile UserBinds userBinds = UserBinds.ANSWER;

  FakeDirectory() throws IOException {
    server = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
    daemon(this::accept);
  }

  String url() {
    return "ldap://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort();
  }

  void addUser(String username, String password) {
    passwords.put(username, password);
  }

  void setUserBinds(UserBinds userBinds) {
    this.userBinds = userBinds;
  }

  int userBindCount() {
    return userBindCount.get();
  }

  @Override
  public void close() throws IOException {
    server.close();
    for (Socket connection : connections) {
      connection.close();
    }
  }

  private void accept() {
    try {
      while (true) {
        Socket connection = server.accept();
        connections.add(connection);
        daemon(() -> serve(connection));
      }
    } catch (IOException closed) {
      return;
    }
  }

  private void serve(Socket connection) {
    try (Socket c = connection) {
      DataInputStream in = new DataInputStream(c.getInputStream());
      OutputStream out = c.getOutputStream();
      Session session = new Session();
      while (handle(session, in, out)) {
        out.flush();
      }
    } catch (IOException clientLeft) {
      return;
    } finally {
      connections.remove(connection);
    }
  }

  private static class Session {
    boolean serviceBound;
  }

  /** Answers one request, returning whether to keep the connection. */
  private boolean handle(Session session, DataInputStream in, OutputStream out) throws IOException {
    DataInputStream message = contentOf(read(in));
    Element id = read(message);
    Element operation = read(message);
    return switch (operation.tag()) {
      case BIND_REQUEST -> bind(session, operation, id, out);
      case SEARCH_REQUEST -> {
        search(session, operation, id, out);
        yield true;
      }
      case UNBIND_REQUEST -> false;
      default -> true;
    };
  }

  private boolean bind(Session session, Element request, Element id, OutputStream out)
      throws IOException {
    DataInputStream fields = contentOf(request);
    read(fields); // version
    String name = text(read(fields));
    String password = text(read(fields));
    int result = SUCCESS;
    if (name.endsWith("," + ACCOUNT_BASE)) {
      userBindCount.incrementAndGet();
      if (userBinds == UserBinds.DROP_CONNECTION) {
        return false;
      }
      if (userBinds == UserBinds.NEVER_ANSWER) {
        return true;
      }
      String username = name.substring("uid=".length(), name.indexOf(','));
      result =
          userBinds == UserBinds.BUSY
              ? BUSY
              : password.equals(passwords.get(username)) ? SUCCESS : INVALID_CREDENTIALS;
    } else if (!name.isEmpty()) {
      result =
          name.equals(SERVICE_DN) && password.equals(SERVICE_PASSWORD)
              ? SUCCESS
              : INVALID_CREDENTIALS;
    }
    session.serviceBound = name.equals(SERVICE_DN) && result == SUCCESS;
    respond(out, id, element(BIND_RESPONSE, result(result)));
    return true;
  }

  private void search(Session session, Element request, Element id, OutputStream out)
      throws IOException {
    String base = text(read(contentOf(request)));
    if (!session.serviceBound) {
      respond(out, id, element(SEARCH_DONE, result(OPERATIONS_ERROR)));
      return;
    }
    if (base.isEmpty()) {
      respond(out, id, entry(""));
    } else {
      String filterAndAttributes = text(request);
      for (String username : passwords.keySet()) {
        if (filterAndAttributes.contains(username)) {
          respond(out, id, entry("uid=" + username + "," + base));
        }
      }
    }
    respond(out, id, element(SEARCH_DONE, result(SUCCESS)));
  }

  private static byte[] entry(String name) throws IOException {
    return element(SEARCH_ENTRY, element(OCTET_STRING, name.getBytes(UTF_8)), element(SEQUENCE));
  }

  private static byte[] result(int code) throws IOException {
    return concat(
        element(ENUMERATED, new byte[] {(byte) code}),
        element(OCTET_STRING),
        element(OCTET_STRING));
  }

  private static void respond(OutputStream out, Element id, byte[] operation) throws IOException {
    out.write(element(SEQUENCE, element(INTEGER, id.content()), operation));
  }

  private record Element(int tag, byte[] content) {}

  private static Element read(DataInputStream in) throws IOException {
    int tag = in.readUnsignedByte();
    int length = in.readUnsignedByte();
    if ((length & 0x80) != 0) {
      int octets = length & 0x7f;
      length = 0;
      for (int i = 0; i < octets; i++) {
        length = (length << 8) | in.readUnsignedByte();
      }
    }
    byte[] content = new byte[length];
    in.readFully(content);
    return new Element(tag, content);
  }

  private static DataInputStream contentOf(Element element) {
    return new DataInputStream(new ByteArrayInputStream(element.content()));
  }

  private static String text(Element element) {
    return new String(element.content(), UTF_8);
  }

  private static byte[] element(int tag, byte[]... parts) throws IOException {
    byte[] content = concat(parts);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    if (content.length < 0x80) {
      out.write(content.length);
    } else {
      out.write(0x82);
      out.write(content.length >> 8);
      out.write(content.length);
    }
    out.write(content);
    return out.toByteArray();
  }

  private static byte[] concat(byte[]... parts) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part);
    }
    return out.toByteArray();
  }

  private static void daemon(Runnable work) {
    Thread thread = new Thread(work, "fake-directory");
    thread.setDaemon(true);
    thread.start();
  }
}
