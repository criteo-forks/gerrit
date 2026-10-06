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

import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.proto.StorageProto.CompactionLease;
import dev.walgerrit.proto.StorageProto.LogCursor;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EventLog} over the store: the manifests listing, the log chain walk the index tailer
 * uses, cursors under {@code cluster/event-log/<reader>/cursors/}, and a cluster lease named
 * {@code event-log/<reader>}.
 */
@Singleton
final class WalEventLog implements EventLog {
  private static final Logger logger = LoggerFactory.getLogger(WalEventLog.class);
  private static final Pattern READER = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final String DIRECTORY = "event-log";

  private final WalGitRepositoryManager repositories;
  private final Clock clock;
  private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

  @Inject
  WalEventLog(GitRepositoryManager repositories, GossipEndpoint gossip) {
    this(asWalGit(repositories), Clock.systemUTC());
    gossip.onHint((id, hint) -> changed(id));
  }

  /** Without gossip: only this node's own publications are announced; for tests. */
  WalEventLog(WalGitRepositoryManager repositories, Clock clock) {
    this.repositories = repositories;
    this.clock = clock;
    repositories.storage().onPublication((id, published) -> changed(id));
  }

  @Override
  public NavigableMap<String, String> repositories() throws IOException {
    NavigableMap<String, String> versions = new TreeMap<>();
    for (Map.Entry<RepositoryId, String> head :
        repositories.storage().listManifestVersions().entrySet()) {
      if (!head.getKey().isCatalog()) {
        versions.put(head.getKey().value(), head.getValue());
      }
    }
    return versions;
  }

  @Override
  public Head head(String repositoryId) throws IOException {
    VersionedManifest versioned = store(repositoryId).refreshVersionedManifest();
    return head(versioned);
  }

  @Override
  public Entries readAfter(String repositoryId, Position after) throws IOException {
    RepositoryId id = id(repositoryId);
    ManifestStore store = repositories.storage().manifestStore(id);
    VersionedManifest versioned = store.refreshVersionedManifest();
    Manifest manifest = versioned.manifest();
    List<LogEntry> read;
    try {
      read =
          store.readLogEntriesAfter(
              after.sequence(), after.transactionId(), manifest, Long.MAX_VALUE);
    } catch (IndexRebuildRequiredException notInHistory) {
      throw new HistoryChangedException(
          "Position " + after + " is not in the history of repository " + repositoryId,
          notInHistory);
    }
    List<Entry> entries = new ArrayList<>();
    for (LogEntry entry : read) {
      if (entry.getEventJsonCount() > 0) {
        entries.add(
            new Entry(
                new Position(entry.getSeq(), entry.getTransactionId()),
                entry.getCreatedAtEpochMillis(),
                entry.getWriter(),
                List.copyOf(entry.getEventJsonList())));
      }
    }
    return new Entries(repositoryId, nameOf(id), List.copyOf(entries), head(versioned));
  }

  @Override
  public NavigableMap<String, String> cursors(String reader) throws IOException {
    String prefix = cursorsPrefix(reader);
    NavigableMap<String, String> cursors = new TreeMap<>();
    for (ObjectStore.ObjectSummary summary :
        repositories.storage().clusterStore().listWithVersions(prefix)) {
      if (summary.key().startsWith(prefix)) {
        cursors.put(summary.key().substring(prefix.length()), summary.version());
      }
    }
    return cursors;
  }

  @Override
  public Optional<Cursor> cursor(String reader, String repositoryId) throws IOException {
    Optional<ObjectStore.StoredObject> stored =
        repositories.storage().clusterStore().get(cursorKey(reader, repositoryId));
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    LogCursor cursor = LogCursor.parseFrom(stored.get().bytes());
    return Optional.of(
        new Cursor(
            new Position(cursor.getSequence(), cursor.getTransactionId()),
            cursor.getHeadVersion(),
            stored.get().version()));
  }

  @Override
  public Cursor saveCursor(
      String reader,
      String repositoryId,
      Position position,
      String headVersion,
      Optional<Cursor> expected)
      throws IOException {
    String key = cursorKey(reader, repositoryId);
    byte[] bytes =
        LogCursor.newBuilder()
            .setSequence(position.sequence())
            .setTransactionId(position.transactionId())
            .setHeadVersion(headVersion == null ? "" : headVersion)
            .build()
            .toByteArray();
    ObjectStore cluster = repositories.storage().clusterStore();
    ObjectStore.StoredObject stored;
    try {
      stored =
          expected.isEmpty()
              ? cluster.putIfAbsent(key, bytes)
              : cluster.compareAndSwap(key, expected.get().storeVersion(), bytes);
    } catch (ObjectAlreadyExistsException | ObjectStoreConflictException lost) {
      throw new CursorConflictException(
          "Cursor " + key + " was written by another holder of " + reader, lost);
    }
    return new Cursor(position, headVersion == null ? "" : headVersion, stored.version());
  }

  @Override
  public Optional<Lease> acquireLease(String reader, Duration term) throws IOException {
    return lease(reader).acquire(term).map(held -> new Lease() {
      @Override
      public void renew(Duration renewed) throws IOException {
        held.renew(renewed);
      }

      @Override
      public void close() {
        held.close();
      }
    });
  }

  @Override
  public Optional<String> leaseOwner(String reader) throws IOException {
    Optional<CompactionLease> current = lease(reader).current();
    long now = clock.millis();
    return current
        .filter(lease -> lease.getExpiresAtEpochMillis() > now)
        .map(CompactionLease::getOwner);
  }

  @Override
  public Registration onChange(Consumer<String> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  private void changed(RepositoryId id) {
    if (id.isCatalog()) {
      return;
    }
    for (Consumer<String> listener : listeners) {
      try {
        listener.accept(id.value());
      } catch (RuntimeException failure) {
        logger.warn("An event-log listener failed for {}", id, failure);
      }
    }
  }

  /**
   * The name the catalog binds {@code id} to. This node's view of the catalog answers first; a
   * repository another node created since this one last read the catalog is not in it, so an id
   * it does not know is looked up again in the catalog as the store has it now.
   */
  private Optional<String> nameOf(RepositoryId id) throws IOException {
    Optional<Catalog.Binding> binding = repositories.catalog().bindingOf(id, false);
    if (binding.isEmpty()) {
      binding = repositories.catalog().bindingOf(id, true);
    }
    return binding.map(found -> found.name().get());
  }

  private StoreLease lease(String reader) {
    return repositories.storage().clusterLease(DIRECTORY + "/" + checkReader(reader));
  }

  private ManifestStore store(String repositoryId) {
    return repositories.storage().manifestStore(id(repositoryId));
  }

  private static Head head(VersionedManifest versioned) {
    Manifest manifest = versioned.manifest();
    return new Head(
        new Position(manifest.getHeadSeq(), manifest.getHeadTransactionId()), versioned.version());
  }

  private static String cursorsPrefix(String reader) {
    return DIRECTORY + "/" + checkReader(reader) + "/cursors/";
  }

  private static String cursorKey(String reader, String repositoryId) {
    return cursorsPrefix(reader) + id(repositoryId).value();
  }

  private static RepositoryId id(String repositoryId) {
    RepositoryId id = new RepositoryId(repositoryId);
    if (id.isCatalog()) {
      throw new IllegalArgumentException("The catalog has no events");
    }
    return id;
  }

  private static String checkReader(String reader) {
    if (reader == null || !READER.matcher(reader).matches()) {
      throw new IllegalArgumentException("Invalid event-log reader name: " + reader);
    }
    return reader;
  }

  private static WalGitRepositoryManager asWalGit(GitRepositoryManager repositories) {
    if (repositories instanceof WalGitRepositoryManager walGit) {
      return walGit;
    }
    throw new IllegalStateException(
        "WalGitIndexModule requires WalGitRepositoryManager, found "
            + repositories.getClass().getName());
  }
}
