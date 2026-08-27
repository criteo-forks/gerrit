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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Maps Gerrit project names onto the durable WalGerrit object-store layout. */
final class StorageLayout {
  private static final String REPOSITORIES_DIRECTORY = "repos";
  private static final String REPOSITORY_SUFFIX = ".git";

  private final Path repositoriesPath;

  StorageLayout(Path root) {
    repositoriesPath = root.resolve(REPOSITORIES_DIRECTORY).toAbsolutePath().normalize();
  }

  ManifestStore manifestStore(Project.NameKey name) throws IOException {
    return new ManifestStore(repositoryPath(name), name.get());
  }

  NavigableSet<Project.NameKey> listProjects() throws IOException {
    NavigableSet<Project.NameKey> projects = new TreeSet<>();
    if (!Files.isDirectory(repositoriesPath)) {
      return projects;
    }

    try (Stream<Path> paths = Files.walk(repositoriesPath)) {
      paths
          .filter(path -> path.getFileName().toString().equals(ManifestStore.MANIFEST_FILE))
          .map(Path::getParent)
          .map(repositoriesPath::relativize)
          .map(Path::toString)
          .filter(path -> path.endsWith(REPOSITORY_SUFFIX))
          .map(path -> path.substring(0, path.length() - REPOSITORY_SUFFIX.length()))
          .map(path -> path.replace(File.separatorChar, '/'))
          .map(Project::nameKey)
          .forEach(projects::add);
    }
    return projects;
  }

  private Path repositoryPath(Project.NameKey name) throws IOException {
    String projectName = name.get();
    if (projectName.isBlank() || projectName.indexOf('\\') >= 0) {
      throw new IOException("Invalid project name: " + projectName);
    }

    Path relative = Path.of(projectName + REPOSITORY_SUFFIX);
    for (Path component : relative) {
      String value = component.toString();
      if (value.equals(".") || value.equals("..")) {
        throw new IOException("Invalid project name: " + projectName);
      }
    }

    Path resolved = repositoriesPath.resolve(relative).normalize();
    if (!resolved.startsWith(repositoriesPath)) {
      throw new IOException("Project escapes storage root: " + projectName);
    }
    return resolved;
  }
}
