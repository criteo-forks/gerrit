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

import dev.walgerrit.ManifestCache.VersionedManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps repository ids onto object-store keys and node-local cache paths.
 *
 * <p>Every manifest lives under one {@code manifests/} prefix, apart from the repository's packs
 * and log entries under {@code repos/}. One paginated listing of that prefix therefore enumerates
 * every repository together with the current version of its manifest, which is how repositories are
 * discovered and how the index-event sweep finds the ones that changed without reading any. Names
 * are the {@link Catalog}'s business: nothing here knows a project name.
 */
final class StorageLayout {
  private static final Logger logger = LoggerFactory.getLogger(StorageLayout.class);
  private static final String MANIFESTS_DIRECTORY = "manifests";
  private static final String REPOSITORIES_DIRECTORY = "repos";
  private static final String LEASES_DIRECTORY = "leases";
  private static final String CLUSTER_DIRECTORY = "cluster";

  private final ObjectStore objectStore;
  private final Path cacheRepositoriesPath;
  private final Path indexCursorRepositoriesPath;
  private final String manifestsPrefix;
  private final String repositoriesPrefix;
  private final String leasesPrefix;
  private final String clusterPrefix;
  private final boolean cacheIsStore;
  private final long packFetchChunkSize;
  private final ManifestCache manifestCache = new ManifestCache();
  private final java.util.concurrent.ConcurrentHashMap<Path, ChunkedFile> chunkedFiles =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final RepositoryLocks repositoryLocks = new RepositoryLocks();
  private final List<BiConsumer<RepositoryId, VersionedManifest>> publicationListeners =
      new CopyOnWriteArrayList<>();

  StorageLayout(Path root) {
    this(new FileObjectStore(root), root, root.resolve("index-events"), "");
  }

  StorageLayout(ObjectStore objectStore, Path cacheRoot, Path indexCursorRoot, String prefix) {
    this(objectStore, cacheRoot, indexCursorRoot, prefix, 0);
  }

  /**
   * @param packFetchChunkSize chunk size for fetching large packs on demand as they are read, or 0
   *     to fetch every pack whole
   */
  StorageLayout(
      ObjectStore objectStore,
      Path cacheRoot,
      Path indexCursorRoot,
      String prefix,
      long packFetchChunkSize) {
    this.objectStore = objectStore;
    this.packFetchChunkSize = packFetchChunkSize;
    cacheRepositoriesPath = cacheRoot.resolve(REPOSITORIES_DIRECTORY).toAbsolutePath().normalize();
    indexCursorRepositoriesPath =
        indexCursorRoot.resolve(REPOSITORIES_DIRECTORY).toAbsolutePath().normalize();
    String normalizedPrefix = prefix == null ? "" : prefix.replaceAll("/+$", "");
    manifestsPrefix = under(normalizedPrefix, MANIFESTS_DIRECTORY);
    repositoriesPrefix = under(normalizedPrefix, REPOSITORIES_DIRECTORY);
    leasesPrefix = under(normalizedPrefix, LEASES_DIRECTORY);
    clusterPrefix = under(normalizedPrefix, CLUSTER_DIRECTORY);
    cacheIsStore =
        objectStore instanceof FileObjectStore files
            && files.root().equals(cacheRoot.toAbsolutePath().normalize());
  }

  /**
   * Whether the node-local cache directory is the store itself, as with the local backend. Then a
   * cached file is the only copy, so nothing may evict it; only reclamation's grace rule deletes.
   */
  boolean cacheIsStore() {
    return cacheIsStore;
  }

  /** Packs still being fetched in chunks on this node; complete ones leave the registry. */
  int packsArrivingInChunks() {
    return chunkedFiles.size();
  }

  /**
   * Observes every manifest a store created by this layout publishes, with the version the store
   * assigned it, after the CAS. Listeners run in registration order on the publishing thread; one
   * that fails is logged and does not stop the others, and none can fail the publication.
   */
  void onPublication(BiConsumer<RepositoryId, VersionedManifest> listener) {
    publicationListeners.add(listener);
  }

  /**
   * Records a peer's wake-up for {@code id}: it published the manifest at {@code version} and
   * {@code revision}. Until this node reads a manifest at that revision, or the store confirms its
   * view is current, the node's view of the repository does not count as recently validated.
   * Returns false when this node already holds that manifest or a newer one.
   */
  boolean expectManifest(RepositoryId id, String version, long revision) {
    return manifestCache.expect(manifestsPrefix + "/" + id.value(), version, revision);
  }

  private void published(RepositoryId id, VersionedManifest manifest) {
    for (BiConsumer<RepositoryId, VersionedManifest> listener : publicationListeners) {
      try {
        listener.accept(id, manifest);
      } catch (RuntimeException failure) {
        logger.warn("Post-publication listener failed for {}", id, failure);
      }
    }
  }

  Path cacheRepositoriesPath() {
    return cacheRepositoriesPath;
  }

  /**
   * Objects that belong to the deployment as a whole rather than to one repository: keys, leases
   * and cursors every node must agree on. Lives under {@code <prefix>/cluster/}.
   */
  ObjectStore clusterStore() {
    return new PrefixedObjectStore(objectStore, clusterPrefix);
  }

  /**
   * A lease every node competes for, such as {@code sweep}; lives under {@code leases/cluster/}.
   */
  StoreLease clusterLease(String name) {
    return new StoreLease(
        objectStore,
        leasesPrefix + "/" + CLUSTER_DIRECTORY + "/" + name,
        Clock.systemUTC(),
        ManifestStore.writerIdentity());
  }

  StoreLease compactionLease(RepositoryId id) {
    return new StoreLease(
        objectStore,
        leasesPrefix + "/" + id.value() + "/" + StoreLease.FILE,
        Clock.systemUTC(),
        ManifestStore.writerIdentity());
  }

  ManifestStore manifestStore(RepositoryId id) {
    String manifestPrefix = manifestsPrefix + "/" + id.value();
    return new ManifestStore(
        new PrefixedObjectStore(objectStore, repositoriesPrefix + "/" + id.value()),
        new PrefixedObjectStore(objectStore, manifestPrefix),
        cacheRepositoriesPath.resolve(id.value()),
        indexCursorRepositoriesPath.resolve(id.value() + ".cursor"),
        id.value(),
        Clock.systemUTC(),
        manifest -> published(id, manifest),
        manifestCache,
        repositoryLocks,
        chunkedFiles,
        packFetchChunkSize,
        manifestPrefix,
        cacheIsStore);
  }

  /** Every repository, the catalog included, from one listing of the manifests prefix. */
  NavigableSet<RepositoryId> listRepositories() throws IOException {
    return new TreeSet<>(listManifestVersions().keySet());
  }

  /**
   * Every repository with the current version of its manifest, from one paginated listing of the
   * manifests prefix. The version is the same opaque token a read of the manifest returns, so a
   * caller holding that version knows the repository has not changed.
   */
  NavigableMap<RepositoryId, String> listManifestVersions() throws IOException {
    String prefix = manifestsPrefix + "/";
    String suffix = "/" + ManifestStore.MANIFEST_FILE;
    NavigableMap<RepositoryId, String> versions = new TreeMap<>();
    for (ObjectStore.ObjectSummary summary : objectStore.listWithVersions(prefix)) {
      String key = summary.key();
      if (!key.startsWith(prefix)
          || !key.endsWith(suffix)
          || key.length() <= prefix.length() + suffix.length()) {
        continue;
      }
      String id = key.substring(prefix.length(), key.length() - suffix.length());
      try {
        versions.put(new RepositoryId(id), summary.version());
      } catch (IllegalArgumentException notAnId) {
        logger.warn("Ignoring a manifest under a key that is not a repository id: {}", key);
      }
    }
    return versions;
  }

  private static String under(String prefix, String directory) {
    return prefix.isEmpty() ? directory : prefix + "/" + directory;
  }
}
