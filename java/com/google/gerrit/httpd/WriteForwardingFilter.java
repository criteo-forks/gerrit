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
package com.google.gerrit.httpd;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_BAD_GATEWAY;
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InetAddresses;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.WriteForwarding.Peer;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.gerrit.util.http.CacheHeaders;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.servlet.ServletModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/** Routes authenticated writes after the entry node's HTTP filters have run. */
@Singleton
public class WriteForwardingFilter implements Filter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String RECEIVE_PACK = "git-receive-pack";
  private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
  private static final int READ_TIMEOUT_MILLIS = 15 * 60 * 1000;
  private static final int MAX_CONNECTIONS = 4096;

  /** Headers that describe one connection, or that the client library sets from the body. */
  private static final ImmutableSet<String> NOT_COPIED =
      ImmutableSet.of(
          "connection",
          "content-length",
          "expect",
          "host",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          WriteForwarding.FORWARDED_HEADER.toLowerCase(Locale.US));

  private static final ImmutableSet<String> REQUEST_HEADERS =
      ImmutableSet.of(
          "accept",
          "accept-encoding",
          "content-type",
          "content-encoding",
          "git-protocol",
          "if-match",
          "if-none-match",
          "if-modified-since",
          "if-unmodified-since",
          "user-agent",
          "accept-language",
          "x-request-id",
          "x-gerrit-deadline",
          "x-gerrit-trace");
  private static final ImmutableSet<String> ENTRY_HEADERS =
      ImmutableSet.of(
          "accept-charset",
          "authorization",
          "cookie",
          "x-gerrit-auth",
          "cache-control",
          "pragma",
          "referer",
          "forwarded",
          "via",
          "x-forwarded-for",
          "x-forwarded-host",
          "x-forwarded-port",
          "x-forwarded-proto",
          "x-real-ip");
  private final Provider<WebSession> sessions;

  public static Module module() {
    return new ServletModule() {
      @Override
      protected void configureServlets() {
        filter("/*").through(WriteForwardingFilter.class);
      }
    };
  }

  private final WriteForwarding forwarding;
  private final Semaphore handingOver;
  private final CloseableHttpClient client;

  @Inject
  WriteForwardingFilter(
      WriteForwarding forwarding, ThreadSettingsConfig threads, Provider<WebSession> sessions) {
    this(forwarding, threads.getHttpdMaxThreads() / 3, sessions);
  }

  WriteForwardingFilter(
      WriteForwarding forwarding, int maxHandingOver, Provider<WebSession> sessions) {
    this.sessions = sessions;
    this.forwarding = forwarding;
    this.handingOver = new Semaphore(maxHandingOver);
    this.client =
        HttpClients.custom()
            .setSSLContext(forwarding.tls())
            .setSSLHostnameVerifier((host, session) -> true)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableContentCompression()
            .disableCookieManagement()
            .disableAuthCaching()
            .disableConnectionState()
            .disableDefaultUserAgent()
            // A fresh connection per request: a failure to connect is then the only failure
            // that can happen before the peer has seen the request.
            .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
            .setMaxConnTotal(MAX_CONNECTIONS)
            .setMaxConnPerRoute(MAX_CONNECTIONS)
            .setDefaultSocketConfig(
                SocketConfig.custom().setSoKeepAlive(true).setTcpNoDelay(true).build())
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                    .setSocketTimeout(READ_TIMEOUT_MILLIS)
                    .setNormalizeUri(false)
                    .build())
            .build();
  }

  @Override
  public void init(FilterConfig filterConfig) {}

  @Override
  public void destroy() {
    try {
      client.close();
    } catch (IOException e) {
      logger.atFine().withCause(e).log("Cannot close the peer HTTP client");
    }
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse rsp = (HttpServletResponse) response;
    String method = req.getMethod();
    String path = req.getRequestURI().substring(req.getContextPath().length());

    String project = eligibleProject(req, path);
    if (project != null
        && !PeerWriteFilter.isPeer(req)
        && forwarding.tls() != null
        && req.getHeader("X-Gerrit-RunAs") == null
        && req.getHeader("Origin") == null) {
      Optional<Peer> peer = forwarding.preferredPeer(project, any -> true);
      if (peer.isPresent()
          && Collections.list(req.getHeaderNames()).stream()
              .map(h -> h.toLowerCase(Locale.ROOT))
              .allMatch(
                  h ->
                      REQUEST_HEADERS.contains(h)
                          || ENTRY_HEADERS.contains(h)
                          || NOT_COPIED.contains(h))) {
        WebSession session = sessions.get();
        CurrentUser user = session.getUser();
        if (user.isIdentifiedUser()
            && !user.isImpersonated()
            && (gitWrite(method, path) || session.isAccessPathOk(AccessPath.REST_API))
            && handOver(req, rsp, peer.get(), isAdvertisement(method, path), user)) {
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }

  static boolean gitWrite(String method, String path) {
    return isAdvertisement(method, path)
        || ("POST".equals(method) && path.endsWith("/git-receive-pack"));
  }

  @Nullable
  static String eligibleProject(HttpServletRequest req, String path) {
    String method = req.getMethod();
    String query = req.getQueryString();
    String decoded = decode(path);
    if (decoded == null
        || !decoded.startsWith("/")
        || decoded.contains("//")
        || decoded.indexOf('%') >= 0
        || decoded.indexOf(';') >= 0
        || decoded.indexOf('\\') >= 0
        || decoded.chars().anyMatch(Character::isISOControl)
        || Splitter.on('/').splitToList(decoded).stream()
            .anyMatch(p -> p.equals(".") || p.equals(".."))) {
      return null;
    }
    // Form data and query parameters can carry site or Gerrit authentication tokens.
    if (query != null
        && !(isAdvertisement(method, path) && query.equals("service=" + RECEIVE_PACK))) {
      return null;
    }
    String type = req.getContentType();
    if (gitWrite(method, path)) {
      if ("POST".equals(method)
          && !"application/x-git-receive-pack-request".equalsIgnoreCase(type)) {
        return null;
      }
      if ("GET".equals(method)
          && (req.getContentLengthLong() > 0 || req.getHeader("Transfer-Encoding") != null)) {
        return null;
      }
    } else {
      if (!ImmutableSet.of("POST", "PUT", "DELETE").contains(method)) {
        return null;
      }
      if (type == null
          ? req.getContentLengthLong() != 0
          : !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
        return null;
      }
    }
    return project(method, path, query);
  }

  private boolean handOver(
      HttpServletRequest req,
      HttpServletResponse rsp,
      Peer peer,
      boolean advertisement,
      CurrentUser user)
      throws IOException {
    if (!handingOver.tryAcquire()) {
      forwarding.count("http", "busy");
      return false;
    }
    try {
      return forward(req, rsp, peer, advertisement, user);
    } finally {
      handingOver.release();
    }
  }

  /**
   * The project that the request writes to, when its address names one.
   *
   * @param path the undecoded request path after the context path
   */
  @Nullable
  static String project(String method, String path, @Nullable String query) {
    String rest = path.startsWith("/a/") ? path.substring(2) : path;
    if (isAdvertisement(method, path) && hasParameter(query, "service=" + RECEIVE_PACK)) {
      return gitProject(rest, "/info/refs");
    }
    if ("POST".equals(method) && rest.endsWith("/" + RECEIVE_PACK)) {
      return gitProject(rest, "/" + RECEIVE_PACK);
    }
    if ("GET".equals(method) || "HEAD".equals(method)) {
      return null;
    }
    boolean change = rest.startsWith("/changes/");
    if (!change && !rest.startsWith("/projects/")) {
      return null;
    }
    int start = rest.indexOf('/', 1) + 1;
    int end = rest.indexOf('/', start);
    String id = decode(rest.substring(start, end < 0 ? rest.length() : end));
    if (id == null || id.isEmpty()) {
      return null;
    }
    if (!change) {
      return id;
    }
    // PROJECT~NUMBER or PROJECT~BRANCH~CHANGE-ID; other change identifiers name no project.
    int last = id.lastIndexOf('~');
    if (last <= 0) {
      return null;
    }
    if (last + 1 < id.length() && id.substring(last + 1).chars().allMatch(Character::isDigit)) {
      return id.substring(0, last);
    }
    int first = id.lastIndexOf('~', last - 1);
    return first <= 0 ? null : id.substring(0, first);
  }

  private static boolean isAdvertisement(String method, String path) {
    return "GET".equals(method) && path.endsWith("/info/refs");
  }

  private static boolean hasParameter(@Nullable String query, String parameter) {
    if (query != null) {
      for (String part : Splitter.on('&').split(query)) {
        if (part.equals(parameter)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  private static String gitProject(String path, String suffix) {
    String encoded = path.substring(0, path.length() - suffix.length());
    if (encoded.isEmpty()) {
      return null;
    }
    encoded = encoded.startsWith("/p/") ? encoded.substring(3) : encoded.substring(1);
    String name = decode(encoded);
    if (name == null) {
      return null;
    }
    while (name.endsWith("/")) {
      name = name.substring(0, name.length() - 1);
    }
    if (name.endsWith(".git")) {
      name = name.substring(0, name.length() - ".git".length());
    }
    while (name.endsWith("/")) {
      name = name.substring(0, name.length() - 1);
    }
    return name.isEmpty() ? null : name;
  }

  @Nullable
  private static String decode(String encoded) {
    try {
      return Url.decode(encoded);
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  /**
   * Hands the request to {@code peer} and relays its answer.
   *
   * @return false when the request must be executed here instead; nothing was read from it and
   *     nothing was written to the response
   */
  private boolean forward(
      HttpServletRequest req,
      HttpServletResponse rsp,
      Peer peer,
      boolean advertisement,
      CurrentUser user)
      throws IOException {
    HttpUriRequest out;
    ClientBody body = new ClientBody(req);
    try {
      out = copy(req, body, user, peer);
    } catch (IllegalArgumentException unforwardable) {
      logger.atFine().withCause(unforwardable).log("Not forwarding %s", req.getRequestURI());
      return false;
    }
    CloseableHttpResponse answer;
    try {
      answer = client.execute(new HttpHost(peer.host(), peer.httpPort(), "https"), out);
    } catch (IOException failure) {
      if (body.failed) {
        // The client stopped sending; the peer is not at fault and there is nobody to answer.
        throw failure;
      }
      forwarding.failed(peer);
      if (advertisement || (body.in == null && neverConnected(failure))) {
        logger.atInfo().withCause(failure).log(
            "Write forwarding: executing %s %s here; no answer from %s",
            req.getMethod(), req.getRequestURI(), peer.name());
        forwarding.count("http", "fallback");
        return false;
      }
      logger.atWarning().withCause(failure).log(
          "Write forwarding: %s failed while handling %s %s",
          peer.name(), req.getMethod(), req.getRequestURI());
      forwarding.count("http", "failed");
      refuse(rsp, peer);
      return true;
    }
    try (answer) {
      int status = answer.getStatusLine().getStatusCode();
      if (advertisement && status == SC_SERVICE_UNAVAILABLE) {
        forwarding.failed(peer);
        forwarding.count("http", "fallback");
        return false;
      }
      rsp.setStatus(status);
      for (Header header : answer.getAllHeaders()) {
        if (!NOT_COPIED.contains(header.getName().toLowerCase(Locale.US))
            && !header.getName().equalsIgnoreCase("Set-Cookie")) {
          rsp.addHeader(header.getName(), header.getValue());
        }
      }
      HttpEntity entity = answer.getEntity();
      if (entity != null) {
        if (entity.getContentLength() >= 0) {
          rsp.setContentLengthLong(entity.getContentLength());
        }
        try (InputStream content = entity.getContent()) {
          relay(content, rsp.getOutputStream());
        } catch (IOException failure) {
          forwarding.count("http", "failed");
          throw failure;
        }
      }
      forwarding.count("http", "forwarded");
      return true;
    }
  }

  private HttpUriRequest copy(
      HttpServletRequest req, ClientBody body, CurrentUser user, Peer peer) {
    String query = req.getQueryString();
    URI target = URI.create(req.getRequestURI() + (query == null ? "" : "?" + query));
    if (target.getScheme() != null || target.getRawAuthority() != null) {
      // A path that starts with two slashes would be sent as a host and another path.
      throw new IllegalArgumentException("not a path: " + req.getRequestURI());
    }
    RequestBuilder out = RequestBuilder.create(req.getMethod()).setUri(target);
    for (String name : Collections.list(req.getHeaderNames())) {
      if (REQUEST_HEADERS.contains(name.toLowerCase(Locale.US))) {
        for (String value : Collections.list(req.getHeaders(name))) {
          out.addHeader(name, value);
        }
      }
    }
    String address = (String) req.getAttribute(RemoteUserUtil.PROXY_REMOTE_ADDRESS_ATTR);
    if (address == null) {
      address = req.getRemoteAddr();
    }
    if (address == null || !InetAddresses.isInetAddress(address)) {
      throw new IllegalArgumentException("unknown client address");
    }
    out.setHeader(WriteForwarding.ACCOUNT_HEADER, Integer.toString(user.getAccountId().get()));
    out.setHeader(WriteForwarding.ADDRESS_HEADER, address);
    out.setHeader(WriteForwarding.FORWARDED_HEADER, peer.name());
    // A length that is not known is sent in chunks, so a body is never dropped.
    long length = req.getContentLengthLong();
    boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
    if (length > 0 || (length < 0 && !read)) {
      out.setEntity(new InputStreamEntity(body, length));
    }
    return out.build();
  }

  private static boolean neverConnected(IOException failure) {
    return failure instanceof javax.net.ssl.SSLHandshakeException
        || failure instanceof ConnectException
        || failure instanceof ConnectTimeoutException
        || failure instanceof NoRouteToHostException
        || failure instanceof UnknownHostException;
  }

  private static void refuse(HttpServletResponse rsp, Peer peer) throws IOException {
    byte[] body =
        ("Server "
                + peer.name()
                + " stopped answering while it handled this request, which it may or may not"
                + " have applied. Check before you retry.\n")
            .getBytes(UTF_8);
    rsp.setStatus(SC_BAD_GATEWAY);
    CacheHeaders.setNotCacheable(rsp);
    rsp.setContentType("text/plain");
    rsp.setCharacterEncoding(UTF_8.name());
    rsp.setContentLength(body.length);
    rsp.getOutputStream().write(body);
  }

  /** The request's body, opened when the peer connection first asks for it. */
  private static final class ClientBody extends InputStream {
    private final HttpServletRequest req;
    private InputStream in;
    volatile boolean failed;

    ClientBody(HttpServletRequest req) {
      this.req = req;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      try {
        if (in == null) {
          in = req.getInputStream();
        }
        return in.read(buffer, offset, length);
      } catch (IOException | RuntimeException failure) {
        failed = true;
        throw failure;
      }
    }
  }

  private static void relay(InputStream from, OutputStream to) throws IOException {
    byte[] buffer = new byte[16 * 1024];
    int read;
    while ((read = from.read(buffer)) >= 0) {
      to.write(buffer, 0, read);
      if (from.available() == 0) {
        to.flush();
      }
    }
  }
}
