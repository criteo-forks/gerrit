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
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import com.google.common.net.HttpHeaders;
import com.google.gerrit.server.ServingGate;
import com.google.gerrit.util.http.CacheHeaders;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.servlet.ServletModule;
import java.io.IOException;
import java.util.Optional;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Answers {@code 503} while the {@link ServingGate} is closed, to everything but its liveness
 * request. Runs ahead of the other filters Gerrit installs, so a request it refuses reads
 * nothing; a filter configured as {@code httpd.filterClass} runs before it.
 */
@Singleton
public class ServingGateFilter implements Filter {
  private static final String RETRY_AFTER_SECONDS = "30";

  public static Module module() {
    return new ServletModule() {
      @Override
      protected void configureServlets() {
        filter("/*").through(ServingGateFilter.class);
      }
    };
  }

  private final ServingGate gate;

  @Inject
  ServingGateFilter(ServingGate gate) {
    this.gate = gate;
  }

  @Override
  public void init(FilterConfig filterConfig) {}

  @Override
  public void destroy() {}

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse rsp = (HttpServletResponse) response;
    boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
    String path = req.getRequestURI().substring(req.getContextPath().length());
    if (read
        && (path.equals(ServingGate.LIVENESS_PATH)
            || path.equals("/a" + ServingGate.LIVENESS_PATH))) {
      CacheHeaders.setNotCacheable(rsp);
      rsp.setStatus(HttpServletResponse.SC_OK);
      rsp.setContentLength(0);
      return;
    }
    Optional<String> closed = gate.closed();
    // Keep version probes working while the chart and the release roll independently.
    if (closed.isEmpty()
        || (read
            && (path.equals("/config/server/version")
                || path.equals("/a/config/server/version")))) {
      chain.doFilter(request, response);
      return;
    }
    rsp.setStatus(SC_SERVICE_UNAVAILABLE);
    rsp.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    CacheHeaders.setNotCacheable(rsp);
    byte[] body = (closed.get() + "\n").getBytes(UTF_8);
    rsp.setContentType("text/plain");
    rsp.setCharacterEncoding(UTF_8.name());
    rsp.setContentLength(body.length);
    rsp.getOutputStream().write(body);
  }

}
