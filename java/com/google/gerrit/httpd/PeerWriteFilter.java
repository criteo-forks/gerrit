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

import com.google.common.net.InetAddresses;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.WriteForwarding;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.servlet.ServletModule;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

/** Accepts identity only on the private TLS connector, for one eligible write. */
@Singleton
public final class PeerWriteFilter implements Filter {
  private static final String PERMIT = PeerWriteFilter.class.getName() + ".permit";
  static final String CLIENT_ADDRESS = PeerWriteFilter.class.getName() + ".clientAddress";
  private static final List<String> HEADERS =
      List.of(
          WriteForwarding.FORWARDED_HEADER,
          WriteForwarding.ACCOUNT_HEADER,
          WriteForwarding.ADDRESS_HEADER,
          WriteForwarding.NATIVE_HEADER);
  private static final List<String> CLIENT_AUTH_HEADERS =
      List.of(
          "Authorization",
          "Proxy-Authorization",
          "Cookie",
          "X-Gerrit-Auth",
          "X-Gerrit-RunAs",
          "Forwarded",
          "X-Forwarded-For",
          "Origin");
  private final WriteForwarding forwarding;
  private final AccountCache accounts;
  private final Provider<WebSession> sessions;
  private final Semaphore inbound;

  public static Module module() {
    return new ServletModule() {
      @Override
      protected void configureServlets() {
        filter("/*").through(PeerWriteFilter.class);
      }
    };
  }

  @Inject
  PeerWriteFilter(
      WriteForwarding forwarding,
      AccountCache accounts,
      Provider<WebSession> sessions,
      ThreadSettingsConfig threads) {
    this.forwarding = forwarding;
    this.accounts = accounts;
    this.sessions = sessions;
    inbound = new Semaphore(threads.getHttpdMaxThreads() / 3);
  }

  static boolean isPeer(ServletRequest req) {
    return Boolean.TRUE.equals(req.getAttribute(WriteForwarding.PEER_CONNECTOR));
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    var req = (HttpServletRequest) request;
    var rsp = (HttpServletResponse) response;
    String path = req.getRequestURI().substring(req.getContextPath().length());
    boolean hello = path.equals(WriteForwarding.HELLO_PATH);
    if (!isPeer(req)) {
      if (hello || HEADERS.stream().anyMatch(header -> req.getHeader(header) != null)) {
        rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
      } else {
        chain.doFilter(req, rsp);
      }
      return;
    }
    String self = forwarding.name();
    if (forwarding.tls() == null || self.isEmpty()) {
      rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (hello && req.getMethod().equals("GET")) {
      rsp.setHeader("Cache-Control", "no-store");
      rsp.setContentType("text/plain");
      rsp.getOutputStream().write(self.getBytes(UTF_8));
      return;
    }
    String address = req.getHeader(WriteForwarding.ADDRESS_HEADER);
    String id = req.getHeader(WriteForwarding.ACCOUNT_HEADER);
    boolean nativeReceive = req.getHeader(WriteForwarding.NATIVE_HEADER) != null;
    if (!self.equals(req.getHeader(WriteForwarding.FORWARDED_HEADER))
        || CLIENT_AUTH_HEADERS.stream().anyMatch(header -> req.getHeader(header) != null)
        || WriteForwardingFilter.eligibleProject(req, path) == null
        || (nativeReceive
            && !(req.getMethod().equals("POST") && path.endsWith("/git-receive-pack")))
        || address == null
        || !InetAddresses.isInetAddress(address)
        || id == null
        || !id.matches("[1-9][0-9]{0,9}")) {
      rsp.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    Account.Id account;
    try {
      account = Account.id(Integer.parseInt(id));
    } catch (NumberFormatException invalid) {
      rsp.sendError(HttpServletResponse.SC_FORBIDDEN);
      return;
    }

    boolean first = req.getAttribute(PERMIT) == null;
    if (first && !inbound.tryAcquire()) {
      forwarding.count(nativeReceive ? "ssh" : "http", "inbound_busy");
      rsp.setHeader("Retry-After", "1");
      rsp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    Permit permit = first ? new Permit() : (Permit) req.getAttribute(PERMIT);
    req.setAttribute(PERMIT, permit);
    try {
      if (accounts.get(account).filter(state -> state.account().isActive()).isEmpty()) {
        rsp.sendError(HttpServletResponse.SC_FORBIDDEN);
        return;
      }
      req.setAttribute(CLIENT_ADDRESS, address);
      WebSession session = sessions.get();
      session.setUserAccountId(account);
      session.setAccessPathOk(
          WriteForwardingFilter.gitWrite(req.getMethod(), path)
              ? AccessPath.GIT
              : AccessPath.REST_API,
          true);
      chain.doFilter(
          new HttpServletRequestWrapper(req) {
            @Override
            public String getRemoteAddr() {
              return address;
            }

            @Override
            public String getRemoteHost() {
              return address;
            }

            @Override
            public int getRemotePort() {
              return 0;
            }

            @Override
            public AsyncContext startAsync() {
              return permit.track(super.startAsync());
            }

            @Override
            public AsyncContext startAsync(ServletRequest r, ServletResponse s) {
              return permit.track(super.startAsync(r, s));
            }
          },
          rsp);
    } finally {
      if (first && !permit.async) {
        permit.release();
      }
    }
  }

  private final class Permit implements AsyncListener {
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile boolean async;

    AsyncContext track(AsyncContext context) {
      context.addListener(this);
      async = true;
      return context;
    }

    void release() {
      if (released.compareAndSet(false, true)) {
        inbound.release();
      }
    }

    @Override
    public void onComplete(AsyncEvent event) {
      release();
    }

    @Override
    public void onTimeout(AsyncEvent event) {}

    @Override
    public void onError(AsyncEvent event) {}

    @Override
    public void onStartAsync(AsyncEvent event) {
      event.getAsyncContext().addListener(this);
    }
  }
}
