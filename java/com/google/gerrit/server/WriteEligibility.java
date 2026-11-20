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
package com.google.gerrit.server;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.Url;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a write can be forwarded and supplies its routing key.
 *
 * <p>Entry and destination both check, so a request runs for a peer only when both agree.
 * Unlisted requests stay at entry.
 */
public final class WriteEligibility {
  /** How the executor of a write is chosen. */
  public enum Placement {
    /** One executor per project; a write needs an address that names its project. */
    PROJECT,
    /** One executor for all eligible writes. Used when placement is unset or blank. */
    CLUSTER;

    static Placement parse(@Nullable String value) {
      if (value == null || value.isBlank()) {
        return CLUSTER;
      }
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        throw new IllegalArgumentException(
            "walgerrit.placement must be project or cluster, not " + value, unknown);
      }
    }
  }

  /**
   * What a node does with one request.
   *
   * @param key what {@link WriteRouter} ranks the nodes by; null when the request stays here
   * @param reason why a write stays here; null for an eligible write and for a read
   */
  public record Decision(@Nullable String key, @Nullable String reason) {
    public boolean eligible() {
      return key != null;
    }

    /** Whether the request is a write at all; reads are neither forwarded nor counted. */
    public boolean write() {
      return key != null || reason != null;
    }

    static Decision local(String reason) {
      return new Decision(null, reason);
    }
  }

  /** Ranks the nodes for every write alike. No project has this name. */
  public static final String CLUSTER_KEY = "\0cluster";

  private static final Decision READ = new Decision(null, null);
  private static final String RECEIVE_PACK = "git-receive-pack";
  private static final String RECEIVE_PACK_TYPE = "application/x-git-receive-pack-request";
  private static final ImmutableSet<String> MUTATIONS = ImmutableSet.of("POST", "PUT", "DELETE");
  private static final ImmutableSet<String> FORWARDED_COLLECTIONS =
      ImmutableSet.of("changes", "projects", "accounts", "groups");

  /**
   * Plugins allowed to forward qualified views and collections in cluster placement; others may
   * depend on entry-local state.
   */
  private static final ImmutableSet<String> FORWARDED_PLUGINS = ImmutableSet.of();

  /** Query parameters that carry a session token or override the method or body type. */
  private static final ImmutableSet<String> TOKEN_PARAMETERS =
      ImmutableSet.of("access_token", "$m", "$ct");

  private static final ImmutableSet<String> FORM_TYPES =
      ImmutableSet.of("application/x-www-form-urlencoded", "multipart/form-data");

  /**
   * Decides on an HTTP request.
   *
   * @param path the undecoded request path after the context path
   * @param contentLength the declared body length, negative when none was declared
   * @param transferEncoded whether the request declares a transfer encoding
   */
  public static Decision http(
      Placement placement,
      String method,
      String path,
      @Nullable String query,
      @Nullable String contentType,
      long contentLength,
      boolean transferEncoded) {
    boolean advertisement = isAdvertisement(method, path) && hasParameter(query, receiveService());
    boolean push = "POST".equals(method) && path.endsWith("/" + RECEIVE_PACK);
    if (!advertisement
        && !push
        && (!MUTATIONS.contains(method) || path.endsWith("/git-upload-pack"))) {
      return READ;
    }
    List<String> segments = segments(path);
    if (segments == null) {
      return Decision.local("path");
    }
    String type = contentType == null ? null : mediaType(contentType);
    if (advertisement || push) {
      if (advertisement ? !query.equals(receiveService()) : query != null) {
        return Decision.local("query");
      }
      if (push && !RECEIVE_PACK_TYPE.equals(type)) {
        return Decision.local("content_type");
      }
      if (advertisement && (contentLength > 0 || transferEncoded)) {
        return Decision.local("body");
      }
      return keyed(placement, project(method, path, query));
    }

    boolean authenticated = segments.size() > 1 && segments.get(0).equals("a");
    List<String> rest = authenticated ? segments.subList(1, segments.size()) : segments;
    String collection = rest.isEmpty() ? "" : rest.get(0);
    if (collection.equals("config") || collection.equals("plugins")) {
      return Decision.local(collection);
    }
    if (!FORWARDED_COLLECTIONS.contains(collection)) {
      return Decision.local("endpoint");
    }
    String view = rest.size() < 3 ? "" : coreView(rest.get(rest.size() - 1));
    if (collection.equals("changes") && view.equals("reviewed")) {
      return Decision.local("reviewed");
    }
    if (collection.equals("accounts") && view.equals("external.ids:delete")) {
      return Decision.local("external_ids");
    }
    if (view.equals("index") || view.equals("index.changes") || view.equals("gc")) {
      return Decision.local("node_local");
    }
    if (placement == Placement.CLUSTER && namesPlugin(rest)) {
      return Decision.local("plugin");
    }
    if (query != null) {
      for (String pair : Splitter.on('&').split(query)) {
        int equals = pair.indexOf('=');
        String name = decode(equals < 0 ? pair : pair.substring(0, equals));
        if (name == null) {
          return Decision.local("query");
        }
        if (TOKEN_PARAMETERS.contains(name)) {
          return Decision.local("token_query");
        }
      }
    }
    if (type != null && FORM_TYPES.contains(type)) {
      return Decision.local("form");
    }
    return placement == Placement.CLUSTER
        ? new Decision(CLUSTER_KEY, null)
        : keyed(placement, project(method, path, query));
  }

  /** A native receive-pack that an SSH client asked for. */
  public static Decision sshReceivePack(Placement placement, String project) {
    return keyed(placement, project);
  }

  /** Whether the request reads or writes through {@code git-receive-pack}. */
  public static boolean gitWrite(String method, String path) {
    return isAdvertisement(method, path)
        || ("POST".equals(method) && path.endsWith("/" + RECEIVE_PACK));
  }

  /**
   * The project that the request writes to, when its address names one.
   *
   * @param path the undecoded request path after the context path
   */
  @Nullable
  public static String project(String method, String path, @Nullable String query) {
    String rest = path.startsWith("/a/") ? path.substring(2) : path;
    if (isAdvertisement(method, path) && hasParameter(query, receiveService())) {
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

  private static Decision keyed(Placement placement, @Nullable String project) {
    if (project == null) {
      return Decision.local("no_project");
    }
    return new Decision(placement == Placement.CLUSTER ? CLUSTER_KEY : project, null);
  }

  /** The decoded segments of a plain path, without a trailing empty one; null for any other. */
  @Nullable
  private static List<String> segments(String path) {
    String decoded = decode(path);
    if (decoded == null
        || !path.startsWith("/")
        || decoded.contains("//")
        || decoded.indexOf('%') >= 0
        || decoded.indexOf(';') >= 0
        || decoded.indexOf('\\') >= 0
        || decoded.chars().anyMatch(Character::isISOControl)
        || Splitter.on('/').splitToList(decoded).stream()
            .anyMatch(p -> p.equals(".") || p.equals(".."))) {
      return null;
    }
    List<String> segments = new ArrayList<>();
    for (String segment : Splitter.on('/').split(path.substring(1))) {
      String name = decode(segment);
      if (name == null) {
        return null;
      }
      segments.add(name);
    }
    if (!segments.isEmpty() && segments.get(segments.size() - 1).isEmpty()) {
      segments.remove(segments.size() - 1);
    }
    return segments;
  }

  /**
   * Whether the path names a view or a collection with a plugin's name, as {@code PLUGIN~NAME}.
   * Collections and views alternate with the ids of their resources, which may hold any text.
   */
  private static boolean namesPlugin(List<String> rest) {
    for (int i = 2; i < rest.size(); i += 2) {
      int qualifier = rest.get(i).indexOf('~');
      if (qualifier >= 0) {
        String plugin = rest.get(i).substring(0, qualifier);
        if (!plugin.equals("gerrit") && !FORWARDED_PLUGINS.contains(plugin)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The view a path segment selects; core views also answer to {@code gerrit~NAME}. */
  private static String coreView(String segment) {
    return segment.startsWith("gerrit~") ? segment.substring("gerrit~".length()) : segment;
  }

  private static String mediaType(String contentType) {
    int parameters = contentType.indexOf(';');
    return (parameters < 0 ? contentType : contentType.substring(0, parameters))
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  private static String receiveService() {
    return "service=" + RECEIVE_PACK;
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

  private WriteEligibility() {}
}
