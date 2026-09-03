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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gerrit.server.config.GerritRuntime;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the object-store round-trip budget of common Gerrit operations. In {@code s3} mode every
 * counted operation is one network round trip, so these bounds are the performance contract.
 */
class ManifestReadCountTest {
  private static final int COMMITS = 200;

  @TempDir Path root;

  @Test
  void handlesRevalidateOnceAndServeLookupsFromMemory() throws Exception {
    CountingObjectStore store = new CountingObjectStore(new FileObjectStore(root.resolve("bucket")));
    WalGitRepositoryManager manager = manager(store, "0");
    Project.NameKey project = Project.nameKey("platform/budget");
    manager.createRepository(project).close();
    store.reset();

    ObjectId tip;
    try (Repository repository = manager.openRepository(project)) {
      assertEquals(1, store.manifestReads(), "opening a handle revalidates exactly once");
      store.reset();

      tip = insertChain(repository, COMMITS);
      assertEquals(
          0,
          store.manifestReads(),
          "object insertion consults JGit's in-memory pack list, not the object store");
      assertEquals(1, store.count("CAS manifest.pb"), "one flush publishes with one CAS");
      store.reset();

      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(tip);
      assertEquals(RefUpdate.Result.NEW, update.update());
      assertEquals(
          1,
          store.manifestReads(),
          "a ref transaction revalidates once before validating expected values");
      assertEquals(1, store.count("CAS manifest.pb"));
      store.reset();

      assertEquals(tip, repository.exactRef(Constants.R_HEADS + "main").getObjectId());
      repository.getRefDatabase().getRefs();
      assertEquals(COMMITS, walk(repository, tip));
      assertEquals(0, store.manifestReads(), "reads on an open handle never touch the store");
    }

    store.reset();
    try (Repository fresh = manager.openRepository(project)) {
      assertTrue(fresh.exactRef(Constants.HEAD).isSymbolic());
      assertEquals(tip, fresh.exactRef(Constants.R_HEADS + "main").getObjectId());
      try (RevWalk walk = new RevWalk(fresh)) {
        RevCommit commit = walk.parseCommit(tip);
        assertTrue(fresh.open(commit.getTree()).getBytes().length > 0);
      }
      assertEquals(1, store.manifestReads(), "a new handle costs exactly one conditional read");
      assertEquals(0, store.writes());
    }
  }

  @Test
  void noteDbStyleWriteCyclesCostOneConditionalReadEach() throws Exception {
    CountingObjectStore store = new CountingObjectStore(new FileObjectStore(root.resolve("bucket")));
    WalGitRepositoryManager manager = manager(store, "0");
    Project.NameKey project = Project.nameKey("platform/cycles");
    manager.createRepository(project).close();

    int cycles = 20;
    try (Repository repository = manager.openRepository(project)) {
      store.reset();
      for (int i = 0; i < cycles; i++) {
        ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, "meta " + i);
        RefUpdate update = repository.updateRef("refs/changes/01/" + (100 + i) + "/meta");
        update.setNewObjectId(commit);
        assertEquals(RefUpdate.Result.NEW, update.update());
      }
    }
    assertEquals(cycles, store.manifestReads(), "one conditional read per ref transaction");
    assertEquals(2 * cycles, store.count("CAS manifest.pb"), "one CAS per pack and per reftable");
  }

  @Test
  void sweepListsManifestsOnceAndReadsOnlyThoseThatChanged() throws Exception {
    Path bucket = root.resolve("bucket");
    CountingObjectStore store = new CountingObjectStore(new FileObjectStore(bucket));
    WalGitRepositoryManager nodeA = manager(store, "0");
    WalGitRepositoryManager nodeB =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(new Config(), root.resolve("node-b")),
            new StorageLayout(
                new FileObjectStore(bucket), root.resolve("cache-b"), root.resolve("cursors-b"), ""));
    List<Project.NameKey> projects =
        List.of(
            Project.nameKey("platform/one"),
            Project.nameKey("platform/two"),
            Project.nameKey("platform/three"));
    for (Project.NameKey project : projects) {
      nodeA.createRepository(project).close();
    }
    IndexEventTailer tailer =
        new IndexEventTailer(nodeA, (project, transaction) -> {}, GerritRuntime.DAEMON);

    store.reset();
    tailer.runOnce();
    assertEquals(1, store.count("LIST-versions manifests/"), "one listing enumerates every repository");
    assertEquals(0, store.manifestReads(), "manifests this node published are served from its cache");
    assertEquals(projects.size(), store.count("GET log/*"), "one WAL entry per new repository");

    store.reset();
    tailer.runOnce();
    assertEquals(1, store.count("LIST-versions manifests/"));
    assertEquals(0, store.manifestReads(), "an unchanged repository costs no read at all");
    assertEquals(0, store.count("GET log/*"));

    ObjectId commit;
    try (Repository repository = nodeB.openRepository(projects.get(1))) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "from node b");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    store.reset();
    tailer.runOnce();
    assertEquals(1, store.count("LIST-versions manifests/"));
    assertEquals(1, store.manifestReads(), "only the repository whose manifest version changed is read");
    assertEquals(2, store.count("GET log/*"), "its pack and ref-update entries are replayed");
    assertEquals(0, store.writes());
  }

  private WalGitRepositoryManager manager(ObjectStore store, String revalidateInterval) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("cache").toString());
    config.setString("walgerrit", null, "indexCursorPath", root.resolve("cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", revalidateInterval);
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root);
    return new WalGitRepositoryManager(
        configuration,
        new StorageLayout(
            store, configuration.storagePath(), configuration.indexCursorPath(), ""));
  }

  private static ObjectId insertChain(Repository repository, int commits) throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId parent = null;
      PersonIdent author = new PersonIdent("WalGerrit Test", "walgerrit@example.test");
      for (int i = 0; i < commits; i++) {
        ObjectId blob =
            inserter.insert(
                Constants.OBJ_BLOB, ("commit " + i + "\n").getBytes(StandardCharsets.UTF_8));
        TreeFormatter tree = new TreeFormatter();
        tree.append("README.md", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        commit.setAuthor(author);
        commit.setCommitter(author);
        commit.setMessage("commit " + i);
        if (parent != null) {
          commit.setParentId(parent);
        }
        parent = inserter.insert(commit);
      }
      inserter.flush();
      return parent;
    }
  }

  private static int walk(Repository repository, ObjectId tip) throws IOException {
    int commits = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(tip));
      for (RevCommit commit : walk) {
        walk.parseBody(commit);
        commits++;
      }
    }
    return commits;
  }

  /** Counts every object-store operation by kind and normalized key. */
  static final class CountingObjectStore implements ObjectStore {
    private final ObjectStore delegate;
    private final Map<String, AtomicLong> counts = new ConcurrentSkipListMap<>();

    CountingObjectStore(ObjectStore delegate) {
      this.delegate = delegate;
    }

    long manifestReads() {
      return count("GET manifest.pb") + count("GET-if-changed manifest.pb");
    }

    long writes() {
      return counts.entrySet().stream()
          .filter(entry -> !entry.getKey().startsWith("GET") && !entry.getKey().startsWith("LIST"))
          .mapToLong(entry -> entry.getValue().get())
          .sum();
    }

    long count(String operation) {
      AtomicLong count = counts.get(operation);
      return count == null ? 0 : count.get();
    }

    void reset() {
      counts.clear();
    }

    @Override
    public String toString() {
      return counts.toString();
    }

    private void record(String operation, String key) {
      if (operation.startsWith("LIST")) {
        counts.computeIfAbsent(operation + " " + key, ignored -> new AtomicLong()).incrementAndGet();
        return;
      }
      String normalized = key.substring(key.lastIndexOf('/') + 1);
      if (key.contains("/log/")) {
        normalized = "log/*";
      } else if (key.contains("/wal/")) {
        normalized = "wal/*." + key.substring(key.lastIndexOf('.') + 1);
      }
      counts.computeIfAbsent(operation + " " + normalized, ignored -> new AtomicLong())
          .incrementAndGet();
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      record("GET", key);
      return delegate.get(key);
    }

    @Override
    public ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
      record(knownVersion == null ? "GET" : "GET-if-changed", key);
      return delegate.getIfChanged(key, knownVersion);
    }

    @Override
    public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
      record("PUT-if-absent", key);
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
        throws IOException {
      record("CAS", key);
      return delegate.compareAndSwap(key, expectedVersion, bytes);
    }

    @Override
    public void uploadIfAbsent(String key, Path source) throws IOException {
      record("UPLOAD", key);
      delegate.uploadIfAbsent(key, source);
    }

    @Override
    public void download(String key, Path target) throws IOException {
      record("DOWNLOAD", key);
      delegate.download(key, target);
    }


    @Override
    public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
      record("LIST-versions", prefix);
      return delegate.listWithVersions(prefix);
    }

    @Override
    public void delete(String key) throws IOException {
      record("DELETE", key);
      delegate.delete(key);
    }
  }
}
