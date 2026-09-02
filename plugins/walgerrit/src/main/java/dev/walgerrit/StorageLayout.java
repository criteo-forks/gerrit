// Copyright 2026 The WalGerrit Authors
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

package dev.walgerrit;

import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Maps Gerrit project names onto object-store keys and node-local cache paths.
 *
 * <p>Every manifest lives under one {@code manifests/} prefix, apart from the repository's packs
 * and log entries under {@code repos/}. One paginated listing of that prefix therefore enumerates
 * every repository together with the current version of its manifest, which is how repositories
 * are discovered and how the index-event sweep finds the ones that changed without reading any.
 */
final class StorageLayout {
  private static final String MANIFESTS_DIRECTORY = "manifests";
  private static final String REPOSITORIES_DIRECTORY = "repos";
  private static final String REPOSITORY_SUFFIX = ".git";

  private final ObjectStore objectStore;
  private final Path cacheRepositoriesPath;
  private final Path indexCursorRepositoriesPath;
  private final String manifestsPrefix;
  private final String repositoriesPrefix;
  private final ManifestCache manifestCache = new ManifestCache();

  StorageLayout(Path root) {
    this(new FileObjectStore(root), root, root.resolve("index-events"), "");
  }

  StorageLayout(ObjectStore objectStore, Path cacheRoot, Path indexCursorRoot, String prefix) {
    this.objectStore = objectStore;
    cacheRepositoriesPath =
        cacheRoot.resolve(REPOSITORIES_DIRECTORY).toAbsolutePath().normalize();
    indexCursorRepositoriesPath =
        indexCursorRoot.resolve(REPOSITORIES_DIRECTORY).toAbsolutePath().normalize();
    String normalizedPrefix = prefix == null ? "" : prefix.replaceAll("/+$", "");
    manifestsPrefix = under(normalizedPrefix, MANIFESTS_DIRECTORY);
    repositoriesPrefix = under(normalizedPrefix, REPOSITORIES_DIRECTORY);
  }

  ManifestStore manifestStore(Project.NameKey name) throws IOException {
    String relative = repositoryRelativePath(name);
    String manifestPrefix = manifestsPrefix + "/" + relative;
    return new ManifestStore(
        new PrefixedObjectStore(objectStore, repositoriesPrefix + "/" + relative),
        new PrefixedObjectStore(objectStore, manifestPrefix),
        cacheRepositoriesPath.resolve(relative),
        indexCursorRepositoriesPath.resolve(relative + ".cursor"),
        name.get(),
        Clock.systemUTC(),
        ignored -> {},
        manifestCache,
        manifestPrefix);
  }

  /** Every repository, from one listing of the manifests prefix. */
  NavigableSet<Project.NameKey> listProjects() throws IOException {
    return new TreeSet<>(listManifestVersions().keySet());
  }

  /**
   * Every repository with the current version of its manifest, from one paginated listing of the
   * manifests prefix. The version is the same opaque token a read of the manifest returns, so a
   * caller holding that version knows the repository has not changed.
   */
  NavigableMap<Project.NameKey, String> listManifestVersions() throws IOException {
    String prefix = manifestsPrefix + "/";
    String suffix = REPOSITORY_SUFFIX + "/" + ManifestStore.MANIFEST_FILE;
    NavigableMap<Project.NameKey, String> versions = new TreeMap<>();
    for (ObjectStore.ObjectSummary summary : objectStore.listWithVersions(prefix)) {
      String key = summary.key();
      if (!key.startsWith(prefix)
          || !key.endsWith(suffix)
          || key.length() <= prefix.length() + suffix.length()) {
        continue;
      }
      versions.put(
          Project.nameKey(key.substring(prefix.length(), key.length() - suffix.length())),
          summary.version());
    }
    return versions;
  }

  private static String under(String prefix, String directory) {
    return prefix.isEmpty() ? directory : prefix + "/" + directory;
  }

  private String repositoryRelativePath(Project.NameKey name) throws IOException {
    String projectName = name.get();
    if (projectName.isBlank() || projectName.indexOf('\\') >= 0) {
      throw new IOException("Invalid project name: " + projectName);
    }
    for (String component : projectName.split("/")) {
      if (component.isBlank() || component.equals(".") || component.equals("..")) {
        throw new IOException("Invalid project name: " + projectName);
      }
    }
    return projectName + REPOSITORY_SUFFIX;
  }
}
