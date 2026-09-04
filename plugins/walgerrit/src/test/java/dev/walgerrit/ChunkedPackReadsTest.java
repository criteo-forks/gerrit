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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.PartialFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
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

class ChunkedPackReadsTest {
  private static final int CHUNK = 64 * 1024;

  @TempDir Path root;

  @Test
  void aColdNodeFetchesOnlyTheChunksItReadsAndCompletesThePackOnDemand() throws Exception {
    CountingStore store = new CountingStore(new FileObjectStore(root.resolve("store")));
    WalGitRepositoryManager writer = node("writer", store);
    WalGitRepositoryManager cold = node("cold", store);
    Project.NameKey project = Project.nameKey("chunked");
    byte[] payload = new byte[1 << 20];
    new Random(7).nextBytes(payload);
    ObjectId commit;
    ObjectId blob;
    try (Repository repository = writer.createRepository(project)) {
      ObjectId[] ids = commitWithBlob(repository, payload, "one megabyte");
      commit = ids[0];
      blob = ids[1];
    }
    store.reset();
    forgetBlockCache(); // both nodes share this JVM's block cache; the cold node must read

    try (Repository repository = cold.openRepository(project);
        RevWalk walk = new RevWalk(repository)) {
      RevCommit parsed = walk.parseCommit(commit);
      assertEquals("one megabyte", parsed.getFullMessage());
    }
    Path pack = onlyPack(walDirectory("cold"));
    Path sidecar = ChunkedFile.sidecarFor(pack);
    assertTrue(Files.exists(sidecar), "the pack is being fetched in chunks");
    int total = ChunkedFile.chunkCount(Files.size(pack), CHUNK);
    int present = presentChunks(sidecar);
    assertTrue(present >= 1 && present < total, present + " of " + total + " chunks present");
    assertTrue(store.rangeReads.get() >= 1, "chunks came through range reads");
    assertTrue(
        store.downloads.stream().noneMatch(key -> key.endsWith(".pack")),
        "the pack itself was not downloaded whole: " + store.downloads);
    assertTrue(store.downloads.stream().anyMatch(key -> key.endsWith(".idx")), "the index is read whole");

    try (Repository repository = cold.openRepository(project)) {
      assertArrayEquals(payload, repository.open(blob).getBytes(), "blob bytes through chunks");
    }
    assertTrue(!Files.exists(sidecar) || presentChunks(sidecar) > present, "reading the blob fetched more");

    // What needs every byte, such as compaction, completes the pack; the sidecar goes away.
    Path whole = cold.storage().manifestStore(project).immutableFile(pack.getFileName().toString());
    assertEquals(pack, whole);
    assertFalse(Files.exists(sidecar), "complete packs carry no sidecar");
    byte[] original =
        Files.readAllBytes(root.resolve("store/repos/chunked.git/wal").resolve(pack.getFileName()));
    assertArrayEquals(original, Files.readAllBytes(pack), "the chunked copy equals the store object");

    store.reset();
    try (Repository repository = cold.openRepository(project)) {
      assertArrayEquals(payload, repository.open(blob).getBytes());
    }
    assertEquals(0, store.rangeReads.get(), "a complete pack is read locally");
    assertTrue(store.downloads.isEmpty(), "nothing is downloaded again");
  }

  @Test
  void aSequentialReadFetchesRunsOfChunksInOneRequest() throws Exception {
    byte[] content = new byte[10 * CHUNK + 123];
    new Random(11).nextBytes(content);
    AtomicInteger fetches = new AtomicInteger();
    Path data = root.resolve("runs/pack-run.pack");
    ChunkedFile file =
        ChunkedFile.open(
            data,
            content.length,
            CHUNK,
            (offset, length) -> {
              fetches.incrementAndGet();
              return java.util.Arrays.copyOfRange(content, (int) offset, (int) offset + length);
            });
    RangedReadableChannel channel = new RangedReadableChannel(file);
    channel.setReadAheadBytes(4 * CHUNK);
    ByteBuffer out = ByteBuffer.allocate(content.length);
    ByteBuffer buffer = ByteBuffer.allocate(1000);
    while (channel.read(buffer) > 0) {
      buffer.flip();
      out.put(buffer);
      buffer.clear();
    }
    assertArrayEquals(content, out.array());
    assertEquals(content.length, channel.position());
    assertEquals(content.length, channel.size());
    assertTrue(file.isComplete());
    assertFalse(Files.exists(ChunkedFile.sidecarFor(data)));
    assertTrue(fetches.get() <= 4, "read-ahead merges chunks into few requests: " + fetches.get());
  }

  @Test
  void resumesFromItsSidecarAndResetsAMismatchedOne() throws Exception {
    byte[] content = new byte[3 * CHUNK];
    new Random(3).nextBytes(content);
    Path data = root.resolve("resume/pack-x.pack");
    ChunkedFile.Fetcher fetcher =
        (offset, length) -> java.util.Arrays.copyOfRange(content, (int) offset, (int) offset + length);

    ChunkedFile first = ChunkedFile.open(data, content.length, CHUNK, fetcher);
    first.ensure(CHUNK + 10, 5);
    assertEquals(1, first.presentChunks());

    ChunkedFile resumed = ChunkedFile.open(data, content.length, CHUNK, fetcher);
    assertEquals(1, resumed.presentChunks(), "the sidecar names the chunk already present");
    ByteBuffer buffer = ByteBuffer.allocate(5);
    resumed.read(buffer, CHUNK + 10);
    assertArrayEquals(java.util.Arrays.copyOfRange(content, CHUNK + 10, CHUNK + 15), buffer.array());

    ChunkedFile mismatched = ChunkedFile.open(data, content.length + 1, CHUNK, fetcher);
    assertEquals(0, mismatched.presentChunks(), "another size means another object; start over");
    assertEquals(content.length + 1, Files.size(data));
  }

  @Test
  void evictionKeepsTheSidecarOfALivePackAndDropsStalePairsTogether() throws Exception {
    CountingStore store = new CountingStore(new FileObjectStore(root.resolve("store")));
    WalGitRepositoryManager writer = node("writer", store);
    WalGitRepositoryManager cold = node("cold", store);
    Project.NameKey project = Project.nameKey("chunked");
    byte[] payload = new byte[1 << 20];
    new Random(5).nextBytes(payload);
    ObjectId commit;
    try (Repository repository = writer.createRepository(project)) {
      commit = commitWithBlob(repository, payload, "live")[0];
    }
    forgetBlockCache();
    try (Repository repository = cold.openRepository(project);
        RevWalk walk = new RevWalk(repository)) {
      walk.parseCommit(commit);
    }
    Path wal = walDirectory("cold");
    Path livePack = onlyPack(wal);
    Path liveSidecar = ChunkedFile.sidecarFor(livePack);
    assertTrue(Files.exists(liveSidecar));

    Path stale = wal.resolve("pack-0000000000000000000000000000000000000000.pack");
    Files.write(stale, new byte[10]);
    Path staleSidecar = ChunkedFile.sidecarFor(stale);
    Files.write(staleSidecar, PartialFile.newBuilder().setSize(10).setChunkSize(CHUNK).build().toByteArray());
    Path orphanSidecar = wal.resolve("pack-1111111111111111111111111111111111111111.pack.chunks");
    Files.write(orphanSidecar, new byte[] {1});
    FileTime old = FileTime.from(Instant.now().minusSeconds(3600));
    for (Path path : List.of(stale, staleSidecar, orphanSidecar, livePack, liveSidecar)) {
      Files.setLastModifiedTime(path, old);
    }

    ManifestStore manifestStore = cold.storage().manifestStore(project);
    Set<String> live = ManifestStore.liveFileNames(manifestStore.current());
    manifestStore.evictLocalFilesExcept(live);

    assertTrue(Files.exists(livePack) && Files.exists(liveSidecar), "a live pack keeps its sidecar");
    assertFalse(Files.exists(stale) || Files.exists(staleSidecar), "a stale pair goes together");
    assertFalse(Files.exists(orphanSidecar), "a sidecar without data is garbage");
  }

  private static ObjectId[] commitWithBlob(Repository repository, byte[] payload, String message)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);
      TreeFormatter tree = new TreeFormatter();
      tree.append("big.bin", FileMode.REGULAR_FILE, blob);
      tree.append(
          "README.md",
          FileMode.REGULAR_FILE,
          inserter.insert(Constants.OBJ_BLOB, (message + "\n").getBytes(StandardCharsets.UTF_8)));
      ObjectId treeId = inserter.insert(tree);
      PersonIdent author = new PersonIdent("WalGerrit Test", "walgerrit@example.test");
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commitId);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return new ObjectId[] {commitId, blob};
    }
  }

  /** The JGit block cache is JVM-wide and keyed by repository name, so it hides node boundaries. */
  private static void forgetBlockCache() {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
  }

  private Path walDirectory(String node) {
    return root.resolve(node + "-cache/repos/chunked.git/wal");
  }

  private static Path onlyPack(Path wal) throws IOException {
    try (Stream<Path> files = Files.list(wal)) {
      List<Path> packs = files.filter(path -> path.getFileName().toString().endsWith(".pack")).toList();
      assertEquals(1, packs.size(), "one object pack: " + packs);
      return packs.get(0);
    }
  }

  private static int presentChunks(Path sidecar) throws IOException {
    PartialFile partial = PartialFile.parseFrom(Files.readAllBytes(sidecar));
    return BitSet.valueOf(partial.getPresent().toByteArray()).cardinality();
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve(name + "-cache").toString());
    config.setString("walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root.resolve(name));
    StorageLayout layout =
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), "", CHUNK);
    return new WalGitRepositoryManager(configuration, layout);
  }

  /** Delegates everything and counts what the chunked reads are supposed to avoid. */
  private static final class CountingStore implements ObjectStore {
    private final ObjectStore delegate;
    final AtomicInteger rangeReads = new AtomicInteger();
    final List<String> downloads = new CopyOnWriteArrayList<>();

    CountingStore(ObjectStore delegate) {
      this.delegate = delegate;
    }

    void reset() {
      rangeReads.set(0);
      downloads.clear();
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      return delegate.get(key);
    }

    @Override
    public ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
      return delegate.getIfChanged(key, knownVersion);
    }

    @Override
    public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
        throws IOException {
      return delegate.compareAndSwap(key, expectedVersion, bytes);
    }

    @Override
    public void uploadIfAbsent(String key, Path source) throws IOException {
      delegate.uploadIfAbsent(key, source);
    }

    @Override
    public void download(String key, Path target) throws IOException {
      downloads.add(key);
      delegate.download(key, target);
    }

    @Override
    public byte[] getRange(String key, long offset, int length) throws IOException {
      rangeReads.incrementAndGet();
      return delegate.getRange(key, offset, length);
    }

    @Override
    public void delete(String key) throws IOException {
      delegate.delete(key);
    }

    @Override
    public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
      return delegate.listWithVersions(prefix);
    }
  }
}
