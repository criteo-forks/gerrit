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
import java.util.NavigableSet;
import java.util.TreeSet;

/** Maps Gerrit project names onto object-store keys and node-local cache paths. */
final class StorageLayout {
  private static final String REPOSITORIES_DIRECTORY = "repos";
  private static final String REPOSITORY_SUFFIX = ".git";

  private final ObjectStore objectStore;
  private final Path cacheRepositoriesPath;
  private final Path indexCursorRepositoriesPath;
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
    repositoriesPrefix =
        normalizedPrefix.isEmpty()
            ? REPOSITORIES_DIRECTORY
            : normalizedPrefix + "/" + REPOSITORIES_DIRECTORY;
  }

  ManifestStore manifestStore(Project.NameKey name) throws IOException {
    String relative = repositoryRelativePath(name);
    String objectPrefix = repositoriesPrefix + "/" + relative;
    return new ManifestStore(
        new PrefixedObjectStore(objectStore, objectPrefix),
        cacheRepositoriesPath.resolve(relative),
        indexCursorRepositoriesPath.resolve(relative + ".cursor"),
        name.get(),
        Clock.systemUTC(),
        ignored -> {},
        manifestCache,
        objectPrefix);
  }

  NavigableSet<Project.NameKey> listProjects() throws IOException {
    NavigableSet<Project.NameKey> projects = new TreeSet<>();
    String prefix = repositoriesPrefix + "/";
    String manifestSuffix = "/" + ManifestStore.MANIFEST_FILE;
    objectStore.list(prefix).stream()
        .filter(key -> key.startsWith(prefix) && key.endsWith(manifestSuffix))
        .map(key -> key.substring(prefix.length(), key.length() - manifestSuffix.length()))
        .filter(path -> path.endsWith(REPOSITORY_SUFFIX))
        .map(path -> path.substring(0, path.length() - REPOSITORY_SUFFIX.length()))
        .map(Project::nameKey)
        .forEach(projects::add);
    return projects;
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
