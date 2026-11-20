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
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InetAddresses;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.WriteEligibility;
import com.google.gerrit.server.WriteEligibility.Decision;
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
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/** Routes authenticated writes after the entry node's HTTP filters have run. */
@Singleton
public class WriteForwardingFilter implements Filter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

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

  /** Only these client headers pass; credentials, cookies and CSRF tokens stay at entry. */
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
          "origin",
          "user-agent",
          "accept-language",
          "x-request-id",
          "x-gerrit-deadline",
          "x-gerrit-trace",
          "x-gerrit-updatedref-enabled");

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
                    // Expect-continue lets the peer refuse before the body is read at entry.
                    .setExpectContinueEnabled(true)
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
    if (!PeerWriteFilter.isPeer(req) && forwarding.active() && handedOver(req, rsp)) {
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * Hands an eligible write to its preferred executor.
   *
   * @return true when the peer's answer, or this node's account of its failure, went to the client
   */
  private boolean handedOver(HttpServletRequest req, HttpServletResponse rsp) throws IOException {
    String method = req.getMethod();
    String path = req.getRequestURI().substring(req.getContextPath().length());
    Decision decision = eligibility(forwarding, req, path);
    if (!decision.write()) {
      return false;
    }
    if (!decision.eligible()) {
      forwarding.ineligible("http", decision.reason());
      return false;
    }
    if (req.getHeader("X-Gerrit-RunAs") != null) {
      forwarding.ineligible("http", "run_as");
      return false;
    }
    Optional<Peer> peer = forwarding.preferredPeer(decision.key());
    if (peer.isEmpty()) {
      forwarding.count("http", "local");
      return false;
    }
    WebSession session = sessions.get();
    CurrentUser user = session.getUser();
    boolean git = WriteEligibility.gitWrite(method, path);
    String kept =
        !user.isIdentifiedUser()
            ? "anonymous"
            : user.isImpersonated()
                ? "impersonated"
                // A cookie session has this path only with the session's CSRF token.
                : !git && !session.isAccessPathOk(AccessPath.REST_API) ? "csrf" : null;
    if (kept != null) {
      forwarding.ineligible("http", kept);
      return false;
    }
    if (!handingOver.tryAcquire()) {
      forwarding.count("http", "busy");
      return false;
    }
    try {
      return forward(req, rsp, peer.get(), user);
    } finally {
      handingOver.release();
    }
  }

  /** What both ends of a hand-over ask about a request. */
  static Decision eligibility(WriteForwarding forwarding, HttpServletRequest req, String path) {
    return WriteEligibility.http(
        forwarding.placement(),
        req.getMethod(),
        path,
        req.getQueryString(),
        req.getContentType(),
        req.getContentLengthLong(),
        req.getHeader("Transfer-Encoding") != null);
  }

  /**
   * Hands the request to {@code peer} and relays its answer.
   *
   * @return false when the request must be executed here instead; nothing was read from it and
   *     nothing was written to the response
   */
  private boolean forward(
      HttpServletRequest req, HttpServletResponse rsp, Peer peer, CurrentUser user)
      throws IOException {
    // The only read that is handed over is a receive-pack advertisement.
    boolean advertisement = "GET".equals(req.getMethod());
    HttpUriRequest out;
    ClientBody body = new ClientBody(req);
    try {
      out = copy(req, body, user, peer);
    } catch (IllegalArgumentException unforwardable) {
      logger.atFine().withCause(unforwardable).log("Not forwarding %s", req.getRequestURI());
      forwarding.ineligible("http", "unforwardable");
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
      Header refused = answer.getFirstHeader(WriteForwarding.REFUSED_HEADER);
      if (refused != null) {
        String reason = refused.getValue();
        logger.atInfo().log(
            "Write forwarding: %s refused %s %s (%s)",
            peer.name(), req.getMethod(), req.getRequestURI(), reason);
        if (!WriteForwarding.executedAtEntry(reason)) {
          // Do not execute at entry after a denial or an unknown refusal.
          boolean denial = WriteForwarding.denial(reason);
          forwarding.count("http", denial ? "denied" : "failed");
          refused(rsp, peer, denial);
          return true;
        }
        if ("unavailable".equals(reason)) {
          forwarding.failed(peer);
        }
        // The peer executed nothing. With the body still unread, this node executes the request.
        if (body.in == null) {
          forwarding.count("http", "refused");
          return false;
        }
        forwarding.count("http", "rejected");
        unavailable(rsp, peer);
        return true;
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
    boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
    if (!read) {
      long length = req.getContentLengthLong();
      if (length == 0 || (length < 0 && !hasBody(req))) {
        // An explicit empty body, so the peer sees the length this node saw or inferred.
        out.setEntity(new ByteArrayEntity(new byte[0]));
      } else {
        // A length that is not known is sent in chunks, so a body is never dropped.
        out.setEntity(new InputStreamEntity(body, length));
      }
    }
    return out.build();
  }

  /** Whether a request that declares no length can still carry a body. */
  private static boolean hasBody(HttpServletRequest req) {
    String protocol = req.getProtocol();
    return req.getHeader("Transfer-Encoding") != null
        || protocol == null
        || !protocol.startsWith("HTTP/1.");
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

  /** Answers for a peer that refused to execute a request for its caller. */
  private static void refused(HttpServletResponse rsp, Peer peer, boolean denial)
      throws IOException {
    byte[] body =
        ("Server "
                + peer.name()
                + (denial
                    ? " does not execute this request for your account."
                    : " refused this request.")
                + " Nothing was written.\n")
            .getBytes(UTF_8);
    rsp.setStatus(denial ? SC_FORBIDDEN : SC_BAD_GATEWAY);
    CacheHeaders.setNotCacheable(rsp);
    rsp.setContentType("text/plain");
    rsp.setCharacterEncoding(UTF_8.name());
    rsp.setContentLength(body.length);
    rsp.getOutputStream().write(body);
  }

  /** Answers for a peer that refused a request whose body this node can no longer replay. */
  private static void unavailable(HttpServletResponse rsp, Peer peer) throws IOException {
    byte[] body =
        ("Server " + peer.name() + " did not take this request. Nothing was written. Please retry.\n")
            .getBytes(UTF_8);
    rsp.setStatus(SC_SERVICE_UNAVAILABLE);
    rsp.setHeader("Retry-After", "1");
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
