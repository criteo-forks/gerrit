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
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes files the manifest no longer references, and bounds the node-local cache.
 *
 * <p>A file must be old enough that its upload cannot still be awaiting publication, then remain
 * unreferenced for a further grace period observed by this reclaimer. File age alone does not
 * protect a reader of an old manifest: a years-old pack may have been superseded only a moment ago.
 * Observation state is node memory only; a restart waits again. A published pack is never re-added,
 * so an eligible absent file cannot become live again. Upload-to-publication latency and reader
 * lifetime must each be shorter than the configured grace period. Log objects are never touched.
 *
 * <p>The local cache is only a cache: files the manifest no longer lists are removed at once, and
 * when a size limit is configured the oldest cached files are dropped first, to be fetched again on
 * demand.
 */
final class Reclaimer {
  private static final Logger logger = LoggerFactory.getLogger(Reclaimer.class);
  private static final String WAL_DIRECTORY = "wal";

  /** Counts of one pass. */
  record Report(int repositories, int deleted, int evicted, long trimmedBytes) {
    Report plus(Report other) {
      return new Report(
          repositories + other.repositories,
          deleted + other.deleted,
          evicted + other.evicted,
          trimmedBytes + other.trimmedBytes);
    }
  }

  private final WalGitRepositoryManager repositories;
  private final Clock clock;
  private final Duration grace;
  private final long cacheSizeLimit;

  private record Candidate(String version, long modified, long observedAt) {}

  // Guarded by this. Lost observations postpone deletion; they never make a file eligible sooner.
  private final Map<Project.NameKey, Map<String, Candidate>> candidates = new HashMap<>();

  Reclaimer(WalGitRepositoryManager repositories, Clock clock, Duration grace, long cacheSizeLimit) {
    this.repositories = repositories;
    this.clock = clock;
    this.grace = grace;
    this.cacheSizeLimit = cacheSizeLimit;
  }

  /** Every repository from one manifests listing, then the local cache limit. */
  Report reclaimAll() throws IOException {
    return reclaimAll((project, manifest) -> {});
  }

  /**
   * Every repository from one manifests listing, then the local cache limit. Each repository's
   * fresh manifest is also handed to {@code observer}, which lets the compactor evaluate its
   * policy for repositories nobody has written to since it last ran, at no extra read.
   */
  Report reclaimAll(BiConsumer<Project.NameKey, Manifest> observer) throws IOException {
    return reclaimAll(observer, true);
  }

  /**
   * Sweeps every repository. With {@code deleteFromStore} false only this node's cached copies of
   * unreferenced files go, which is what a node that does not lead the deployment does: the store
   * is shared, so one node deleting from it is enough, and the grace period makes it safe to wait.
   */
  synchronized Report reclaimAll(
      BiConsumer<Project.NameKey, Manifest> observer, boolean deleteFromStore) throws IOException {
    Report total = new Report(0, 0, 0, 0);
    Set<Project.NameKey> projects = repositories.storage().listProjects();
    candidates.keySet().retainAll(projects);
    for (Project.NameKey project : projects) {
      try {
        total = total.plus(reclaim(project, observer, deleteFromStore));
      } catch (IOException exception) {
        logger.warn("WalGerrit could not reclaim files of {}", project.get(), exception);
      }
    }
    return total.plus(new Report(0, 0, 0, enforceCacheLimit()));
  }

  /** Reads every repository's manifest for {@code observer} without deleting anything. */
  Report observeAll(BiConsumer<Project.NameKey, Manifest> observer) throws IOException {
    int seen = 0;
    for (Project.NameKey project : repositories.storage().listProjects()) {
      try {
        observer.accept(project, repositories.storage().manifestStore(project).refresh());
        seen++;
      } catch (IOException exception) {
        logger.warn("WalGerrit could not read the manifest of {}", project.get(), exception);
      }
    }
    return new Report(seen, 0, 0, 0);
  }

  /** One repository: store files past the grace period, then the local cache. */
  Report reclaim(Project.NameKey project) throws IOException {
    return reclaim(project, (name, manifest) -> {}, true);
  }

  private synchronized Report reclaim(
      Project.NameKey project,
      BiConsumer<Project.NameKey, Manifest> observer,
      boolean deleteFromStore)
      throws IOException {
    ManifestStore store = repositories.storage().manifestStore(project);
    GroupPublisher.Snapshot snapshot = store.publisher().snapshot(store, true);
    Manifest manifest = snapshot.manifest();
    observer.accept(project, manifest);
    Set<String> live = new HashSet<>(ManifestStore.liveFileNames(manifest));
    live.addAll(ManifestStore.fileNames(snapshot.unpublished()));
    long now = clock.millis();
    long cutoff = now - grace.toMillis();
    int deleted = 0;
    if (deleteFromStore) {
      Map<String, Candidate> observed =
          candidates.computeIfAbsent(project, ignored -> new HashMap<>());
      Set<String> eligible = new HashSet<>();
      for (ObjectStore.ObjectSummary object : store.listWalObjects()) {
        if (live.contains(object.key()) || object.lastModifiedEpochMillis() > cutoff) {
          observed.remove(object.key());
          continue;
        }
        eligible.add(object.key());
        Candidate candidate = observed.get(object.key());
        if (candidate == null
            || !candidate.version().equals(object.version())
            || candidate.modified() != object.lastModifiedEpochMillis()) {
          candidate = new Candidate(object.version(), object.lastModifiedEpochMillis(), now);
          observed.put(object.key(), candidate);
        }
        if (candidate.observedAt() > cutoff) {
          continue;
        }
        store.deleteWalObject(object.key());
        observed.remove(object.key());
        deleted++;
      }
      observed.keySet().retainAll(eligible);
      if (observed.isEmpty()) {
        candidates.remove(project);
      }
    } else {
      // A node newly elected to reclaim starts a fresh observation interval.
      candidates.remove(project);
    }
    int evicted = store.evictLocalFilesExcept(live);
    if (deleted > 0 || evicted > 0) {
      logger.info(
          "WalGerrit reclaimed {} unreferenced file(s) of {} from the store and {} from the local"
              + " cache",
          deleted,
          project.get(),
          evicted);
    }
    return new Report(1, deleted, evicted, 0);
  }

  /** Drops this node's cached copies of files the repository's manifest no longer lists. */
  int evictLocal(Project.NameKey project) throws IOException {
    ManifestStore store = repositories.storage().manifestStore(project);
    GroupPublisher.Snapshot snapshot = store.publisher().snapshot(store, false);
    Set<String> live = new HashSet<>(ManifestStore.liveFileNames(snapshot.manifest()));
    live.addAll(ManifestStore.fileNames(snapshot.unpublished()));
    return store.evictLocalFilesExcept(live);
  }

  /**
   * Deletes the oldest cached files until the cache fits its limit; returns the bytes freed. Never
   * touches a cache that is the store itself.
   */
  long enforceCacheLimit() throws IOException {
    Path root = repositories.storage().cacheRepositoriesPath();
    if (cacheSizeLimit <= 0 || repositories.storage().cacheIsStore() || !Files.isDirectory(root)) {
      return 0;
    }
    record Cached(Path path, long size, long modified) {}
    List<Cached> cached = new ArrayList<>();
    long total = 0;
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : (Iterable<Path>) paths::iterator) {
        if (!Files.isRegularFile(path) || !WAL_DIRECTORY.equals(path.getParent().getFileName().toString())) {
          continue;
        }
        if (ChunkedFile.isSidecar(path.getFileName().toString())) {
          continue; // goes with its data file, below
        }
        long size = Files.size(path);
        long modified = Files.getLastModifiedTime(path).toMillis();
        total += size;
        if (modified > clock.millis() - ManifestStore.EVICTION_MIN_AGE.toMillis()) {
          continue; // may await its publication; it is counted but never trimmed
        }
        cached.add(new Cached(path, size, modified));
      }
    }
    if (total <= cacheSizeLimit) {
      return 0;
    }
    cached.sort(Comparator.comparingLong(Cached::modified));
    long freed = 0;
    for (Cached file : cached) {
      if (total - freed <= cacheSizeLimit) {
        break;
      }
      if (Files.deleteIfExists(file.path())) {
        freed += file.size();
      }
      Files.deleteIfExists(ChunkedFile.sidecarFor(file.path()));
    }
    logger.info(
        "WalGerrit trimmed {} bytes from the local cache to stay under {} bytes",
        freed,
        cacheSizeLimit);
    return freed;
  }
}
