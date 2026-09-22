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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Finds the one project repository of a test's store, whatever id it drew. */
final class TestStores {
  private TestStores() {}

  /** The single repository in the store apart from the catalog. */
  static RepositoryId onlyRepository(ObjectStore store) {
    try {
      RepositoryId found = null;
      for (ObjectStore.ObjectSummary summary : store.listWithVersions("manifests/")) {
        String key = summary.key();
        String id = key.substring("manifests/".length(), key.indexOf('/', "manifests/".length()));
        if (RepositoryId.CATALOG.value().equals(id)) {
          continue;
        }
        if (found != null && !found.value().equals(id)) {
          throw new IllegalStateException("More than one repository in the store");
        }
        found = new RepositoryId(id);
      }
      if (found == null) {
        throw new IllegalStateException("No repository in the store");
      }
      return found;
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /** The store prefix of that repository's immutable files, with its trailing slash. */
  static String wal(ObjectStore store) {
    return "repos/" + onlyRepository(store) + "/wal/";
  }

  /** That repository's immutable-file cache below a node's cache root. */
  static Path cacheWal(Path cacheRoot, ObjectStore store) {
    return cacheRoot.resolve("repos").resolve(onlyRepository(store).value()).resolve("wal");
  }
}
