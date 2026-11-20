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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.Compactor.Outcome;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.internal.storage.dfs.DfsPackFile;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compaction is representation only: after any pass, every object and every ref a repository had
 * is still there, from every node, and the store holds nothing the manifest or the grace period
 * does not account for.
 */
class CompactorTest {

  private static final Project.NameKey PROJECT = Project.nameKey("platform/compact");
  /** Above 113 refs JGit stops folding a new table into the top of the stack at commit time. */
  private static final int STANDALONE_TABLE_REFS = 120;

  @TempDir Path root;

  @Test
  void rollsUpSmallPacksAndKeepsEveryObjectAndRefReadableFromAnotherNode() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager nodeA = node("node-a", store, ignored -> {});
    Map<String, ObjectId> refs = new TreeMap<>();
    try (Repository repository = nodeA.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        refs.put(head("b" + i), publish(repository, head("b" + i), "commit " + i));
      }
    }
    Manifest before = manifest(nodeA);
    assertTrue(objectPacks(before).size() >= 4, "one pack per write before compaction");
    Set<String> filesBefore = new HashSet<>(store.list(TestStores.wal(store)));
    Path cache = TestStores.cacheWal(root.resolve("node-a-cache"), store);
    ageCachedFiles(cache, Duration.ofHours(1));

    assertEquals(Outcome.COMPACTED, nodeA.compactor().compact(PROJECT));

    Manifest after = manifest(nodeA);
    List<PackRef> objectPacks = objectPacks(after);
    assertEquals(1, objectPacks.size());
    assertEquals("COMPACT", objectPacks.get(0).getSource());
    assertEquals(before.getRefRevision(), after.getRefRevision(), "object compaction moves no ref");
    assertEquals(refs, allRefs(nodeA), "refs are unchanged");

    WalGitRepositoryManager nodeB = node("node-b", store, ignored -> {});
    try (Repository other = nodeB.openRepository(PROJECT)) {
      for (Map.Entry<String, ObjectId> ref : refs.entrySet()) {
        assertEquals(ref.getValue(), other.exactRef(ref.getKey()).getObjectId());
        assertEquals(Constants.OBJ_COMMIT, other.open(ref.getValue()).getType());
      }
    }

    LogEntry compaction = lastEntry(store, after);
    assertEquals(LogEntry.Kind.COMPACT, compaction.getKind());
    assertEquals(
        objectPackNames(before), new HashSet<>(compaction.getSupersedesList()),
        "the log names every superseded pack");
    assertTrue(store.list(TestStores.wal(store)).containsAll(filesBefore), "superseded files stay in the store");
    Set<String> live = ManifestStore.liveFileNames(after);
    try (Stream<Path> cached = Files.list(cache)) {
      assertTrue(
          cached.map(path -> path.getFileName().toString()).allMatch(live::contains),
          "the compacting node evicts superseded files older than the eviction age from its cache");
    }
  }

  @Test
  void freshlyWrittenCachedFilesAreNeverEvicted() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node-a", store, ignored -> {});
    try (Repository repository = node.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        publish(repository, head("b" + i), "commit " + i);
      }
    }
    Set<String> cachedBefore =
        cachedFiles(TestStores.cacheWal(root.resolve("node-a-cache"), store));

    assertEquals(Outcome.COMPACTED, node.compactor().compact(PROJECT));

    // Superseded, but written seconds ago: an upload awaiting its CAS would look the same.
    assertTrue(
        cachedFiles(TestStores.cacheWal(root.resolve("node-a-cache"), store))
            .containsAll(cachedBefore));
  }

  @Test
  void theLocalBackendNeverEvictsBecauseItsCacheIsTheStore() throws Exception {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("local-store").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    config.setInt("walgerrit", null, "compactMinPacks", 3);
    config.setInt("walgerrit", null, "compactMinReftables", 3);
    WalGitRepositoryManager node =
        new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve("site")));
    assertTrue(node.storage().cacheIsStore());
    Map<String, ObjectId> refs = new TreeMap<>();
    try (Repository repository = node.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        refs.put(head("b" + i), publish(repository, head("b" + i), "commit " + i));
      }
    }
    Path wal =
        TestStores.cacheWal(
            root.resolve("local-store"), new FileObjectStore(root.resolve("local-store")));
    ageCachedFiles(wal, Duration.ofHours(1));
    Set<String> before = cachedFiles(wal);

    assertEquals(Outcome.COMPACTED, node.compactor().compact(PROJECT));

    Manifest manifest = manifest(node);
    Set<String> live = ManifestStore.liveFileNames(manifest);
    Set<String> after = cachedFiles(wal);
    assertTrue(after.containsAll(before), "nothing is evicted: the cache is the store");
    assertTrue(after.containsAll(live));
    SteppingClock clock = new SteppingClock(Instant.now().plus(Duration.ofDays(2)));
    Reclaimer reclaimer = new Reclaimer(node, clock, Duration.ofDays(1), 1);
    assertEquals(0, reclaimer.enforceCacheLimit(), "a size limit never trims the store");
    assertEquals(0, reclaimer.reclaim(PROJECT).deleted());
    clock.advance(Duration.ofDays(2));
    reclaimer.reclaim(PROJECT);
    assertEquals(live, cachedFiles(wal), "reclamation with its grace period is the only deletion");
    assertEquals(refs, allRefs(node));
  }

  @Test
  void compactsTheReftableStackIntoOneTableAndPreservesDeletions() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node-a", store, ignored -> {});
    Map<String, ObjectId> expected = new TreeMap<>();
    try (Repository repository = node.createRepository(PROJECT)) {
      for (int batch = 0; batch < 3; batch++) {
        expected.putAll(publishBatch(repository, "batch" + batch, STANDALONE_TABLE_REFS));
      }
      ObjectId doomed = publish(repository, head("doomed"), "to be deleted");
      RefUpdate delete = repository.updateRef(head("doomed"));
      delete.setExpectedOldObjectId(doomed);
      delete.setForceUpdate(true);
      assertEquals(RefUpdate.Result.FORCED, delete.delete());
    }
    Manifest before = manifest(node);
    assertTrue(reftables(before).size() >= 3, "multi-ref batches leave standalone tables");

    assertEquals(Outcome.COMPACTED, node.compactor().compact(PROJECT));

    Manifest after = manifest(node);
    assertEquals(1, reftables(after).size());
    assertEquals("COMPACT", reftables(after).get(0).getSource());
    assertTrue(
        after.getRefRevision() > before.getRefRevision(),
        "a stack change advances the ref revision");
    assertEquals(expected, allRefs(node));
    try (Repository repository = node.openRepository(PROJECT)) {
      assertNull(repository.exactRef(head("doomed")));
      // The compacted stack keeps working as the base of new updates.
      ObjectId later = publish(repository, head("later"), "after compaction");
      assertEquals(later, repository.exactRef(head("later")).getObjectId());
      assertNotNull(repository.exactRef(head("batch0-0")));
    }
  }

  @Test
  void sustainedSmallUpdatesAboveALargeBase() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    AtomicLong uploaded = new AtomicLong();
    ObjectStore store =
        new ManifestReadCountTest.CountingObjectStore(shared) {
          @Override
          public void uploadIfAbsent(String key, Path file) throws IOException {
            super.uploadIfAbsent(key, file);
            if (key.endsWith(".ref")) {
              uploaded.addAndGet(Files.size(file));
            }
          }
        };
    WalGitRepositoryManager node =
        node("writer", store, config -> config.unset("walgerrit", null, "compactMinReftables"));
    ObjectId tip;
    Random random = new Random(42);
    byte[] key = new byte[Constants.OBJECT_ID_LENGTH];
    try (Repository repo = node.createRepository(PROJECT)) {
      tip = WalGitRepositoryManagerTest.insertCommit(repo, "seed");
      // HEAD and seven standalone transactions seed one compacted table.
      for (int batch = 0; batch < 7; batch++) {
        BatchRefUpdate update = repo.getRefDatabase().newBatchUpdate();
        update.disableRefLog();
        for (int i = 0; i < 9_000; i++) {
          random.nextBytes(key);
          String ref = "refs/cache-automerge/" + ObjectId.fromRaw(key).name();
          update.addCommand(new ReceiveCommand(ObjectId.zeroId(), tip, ref));
        }
        try (RevWalk walk = new RevWalk(repo)) {
          update.execute(walk, NullProgressMonitor.INSTANCE);
        }
        for (ReceiveCommand command : update.getCommands()) {
          assertEquals(ReceiveCommand.Result.OK, command.getResult());
        }
      }
    }
    assertEquals(Outcome.COMPACTED, node.compactor().compact(PROJECT));
    List<PackRef> stack = CompactionPolicy.reftableStack(manifest(node));
    assertEquals(1, stack.size());
    long baseBytes = CompactionPolicy.reftableSize(stack.getFirst());
    assertTrue(baseBytes >= 3_000_000 && baseBytes < 5_000_000, "base bytes " + baseBytes);

    AtomicLong downloaded = new AtomicLong();
    ObjectStore peerStore =
        new ManifestReadCountTest.CountingObjectStore(shared) {
          @Override
          public void download(String key, Path file) throws IOException {
            super.download(key, file);
            if (key.endsWith(".ref")) {
              downloaded.addAndGet(Files.size(file));
            }
          }
        };
    WalGitRepositoryManager peer = node("peer", peerStore, ignored -> {});
    String baseRef = "refs/cache-automerge/" + ObjectId.fromRaw(key).name();
    long compactedBytes = 0;
    int baseRewrites = 0;
    int compactions = 0;
    int peakDepth = 0;
    // Disjoint in-flight work keeps each single-ref publication in its own table.
    GroupPublisher.Admission other =
        node.manifestStore(PROJECT).publisher().admit(Set.of(head("busy")));
    // JGit's JVM-wide block cache is keyed by repository name.
    try (Repository repo = node.openRepository(PROJECT);
        Repository reader = peer.openById(peer.idOf(PROJECT), Project.nameKey("peer-view"), 0)) {
      assertEquals(tip, reader.exactRef(baseRef).getObjectId());
      assertEquals(baseBytes, downloaded.getAndSet(0), "the peer warmed the base once");
      for (int i = 0; i < 112; i++) {
        RefUpdate update = repo.updateRef(head("user-" + i));
        update.setNewObjectId(tip);
        update.disableRefLog();
        assertEquals(RefUpdate.Result.NEW, update.update());
        Manifest before = manifest(node);
        peakDepth = Math.max(peakDepth, reftables(before).size());
        if (node.compactor().policy().reftablesToMerge(before).isEmpty()) {
          continue;
        }
        String base = CompactionPolicy.reftableStack(before).getFirst().getName();
        long start = uploaded.get();
        assertEquals(Outcome.COMPACTED, node.compactor().compact(PROJECT));
        compactedBytes += uploaded.get() - start;
        compactions++;
        reader.scanForRepoChanges();
        assertEquals(tip, reader.exactRef(head("user-" + i)).getObjectId());
        assertEquals(tip, reader.exactRef(baseRef).getObjectId());
        stack = CompactionPolicy.reftableStack(manifest(node));
        if (!base.equals(stack.getFirst().getName())) {
          baseRewrites++;
        }
      }
    } finally {
      other.release();
    }
    System.out.printf(
        "Base %d bytes, %d compactions, %d base rewrites, %d bytes uploaded, "
            + "%d bytes downloaded by the warm peer, peak depth %d%n",
        baseBytes, compactions, baseRewrites, compactedBytes, downloaded.get(), peakDepth);
    assertTrue(compactions > 1);
    assertEquals(0, baseRewrites);
    assertTrue(compactedBytes < baseBytes / 10);
    assertEquals(compactedBytes, downloaded.get());
    assertTrue(peakDepth <= node.configuration().compactMinReftables());
    assertEquals(112, allRefs(peer).size());
  }

  @ParameterizedTest(name = "concurrent writer on compacting node = {0}")
  @ValueSource(booleans = {true, false})
  void concurrentUpdatesAndDeletesSurviveAPartialMerge(boolean local) throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    Consumer<Config> settings =
        config -> {
          config.setInt("walgerrit", null, "compactMinReftables", 4);
          config.setInt("walgerrit", null, "compactMaxReftables", 4);
          config.setLong("walgerrit", null, "compactSmallReftableSize", 1);
        };
    WalGitRepositoryManager seed = node("seed", shared, settings);
    Map<String, ObjectId> expected = new TreeMap<>();
    String watched = head("watched");
    ObjectId first;
    ObjectId second;
    try (Repository repo = seed.createRepository(PROJECT)) {
      first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      second = WalGitRepositoryManagerTest.insertCommit(repo, "second");
      for (int batch = 0; batch < 3; batch++) {
        ReceiveCommand[] commands = new ReceiveCommand[STANDALONE_TABLE_REFS];
        for (int i = 0; i < commands.length; i++) {
          String ref = head("batch" + batch + "-" + i);
          commands[i] = new ReceiveCommand(ObjectId.zeroId(), first, ref);
          expected.put(ref, first);
        }
        writeRefs(repo, "seed", commands);
      }
      writeRefs(repo, "create", new ReceiveCommand(ObjectId.zeroId(), first, watched));
      expected.put(watched, first);
    }
    assertEquals(Outcome.COMPACTED, seed.compactor().compact(PROJECT));
    List<PackRef> bases = reftables(manifest(seed));
    assertEquals(1, bases.size());
    PackRef base = bases.getFirst();

    GroupPublisher.Admission other =
        seed.manifestStore(PROJECT).publisher().admit(Set.of(head("busy")));
    try (Repository repo = seed.openRepository(PROJECT)) {
      writeRefs(repo, "retarget", new ReceiveCommand(first, second, watched));
      expected.put(watched, second);
      String doomed = head("batch0-1");
      writeRefs(
          repo, "delete", new ReceiveCommand(expected.remove(doomed), ObjectId.zeroId(), doomed));
      String added = head("added");
      writeRefs(repo, "add", new ReceiveCommand(ObjectId.zeroId(), first, added));
      expected.put(added, first);
    } finally {
      other.release();
    }
    List<PackRef> plannedStack = CompactionPolicy.reftableStack(manifest(seed));
    assertEquals(4, plannedStack.size());
    PackRef top = plannedStack.getLast();
    CompletableFuture<String> firstUpload = new CompletableFuture<>();
    CountDownLatch resume = new CountDownLatch(1);
    ObjectStore gated =
        new ManifestReadCountTest.CountingObjectStore(shared) {
          @Override
          public void uploadIfAbsent(String key, Path source) throws IOException {
            super.uploadIfAbsent(key, source);
            if (key.endsWith(".ref") && firstUpload.complete(key)) {
              try {
                assertTrue(resume.await(15, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
            }
          }
        };
    WalGitRepositoryManager compactor = node("compactor", gated, settings);
    WalGitRepositoryManager writer = local ? compactor : seed;
    ExecutorService background = Executors.newSingleThreadExecutor();
    String output;
    try {
      Future<Outcome> merge = background.submit(() -> compactor.compactor().compact(PROJECT));
      output = Path.of(firstUpload.get(15, TimeUnit.SECONDS)).getFileName().toString();
      try (Repository repo = writer.openRepository(PROJECT)) {
        String doomed = head("batch2-1");
        writeRefs(
            repo,
            "during",
            new ReceiveCommand(second, first, watched),
            new ReceiveCommand(expected.remove(doomed), ObjectId.zeroId(), doomed));
        expected.put(watched, first);
      }
      assertEquals(local, reftables(manifest(writer)).contains(top), "local append or remote fold");
      resume.countDown();
      assertEquals(Outcome.COMPACTED, merge.get(15, TimeUnit.SECONDS));
    } finally {
      resume.countDown();
      background.shutdownNow();
    }
    Manifest after = manifest(compactor);
    assertTrue(reftables(after).contains(base));
    assertEquals(local ? 3 : 2, reftables(after).size());
    assertEquals(
        local,
        ManifestStore.liveFileNames(after).contains(output),
        "a folded input invalidates the uploaded output");
    WalGitRepositoryManager peer = node("failover", shared, settings);
    assertEquals(expected, allRefs(peer));
    try (Repository reader = peer.openRepository(PROJECT)) {
      assertEquals(
          List.of("during", "retarget", "create"),
          reader.getRefDatabase().getReflogReader(watched).getReverseEntries().stream()
              .map(ReflogEntry::getComment)
              .toList());
    }
  }

  private static void writeRefs(Repository repository, String message, ReceiveCommand... commands)
      throws IOException {
    BatchRefUpdate update = repository.getRefDatabase().newBatchUpdate();
    update.setAllowNonFastForwards(true);
    update.setRefLogMessage(message, false);
    update.addCommand(commands);
    try (RevWalk walk = new RevWalk(repository)) {
      update.execute(walk, NullProgressMonitor.INSTANCE);
    }
    for (ReceiveCommand command : commands) {
      assertEquals(ReceiveCommand.Result.OK, command.getResult(), command.getRefName());
    }
  }

  @Test
  void theWritingNodeCompactsInTheBackground() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node-a", store, ignored -> {});
    node.compactor().start();
    try {
      try (Repository repository = node.createRepository(PROJECT)) {
        for (int i = 0; i < 4; i++) {
          publish(repository, head("b" + i), "commit " + i);
        }
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline
          && (node.compactor().isQueued(PROJECT)
              || objectPacks(manifest(node)).stream()
                  .noneMatch(p -> p.getSource().equals("COMPACT")))) {
        Thread.sleep(50);
      }
      Manifest manifest = manifest(node);
      assertTrue(
          objectPacks(manifest).stream().anyMatch(pack -> pack.getSource().equals("COMPACT")),
          "the write that crossed the threshold triggered a compaction");
      assertTrue(node.compactor().policy().plan(manifest).isEmpty(), "the policy is satisfied");
      assertTrue(
          objectPacks(manifest).size() <= 2, "a late write may sit above the compacted pack");
    } finally {
      node.compactor().stop();
    }
  }

  @Test
  void theStartupSweepCompactsRepositoriesThatFellDueWithoutThisNodeWriting() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager writer = node("batch-program", store, ignored -> {});
    try (Repository repository = writer.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        publish(repository, head("b" + i), "commit " + i);
      }
    }
    assertTrue(objectPacks(manifest(writer)).size() >= 4);

    WalGitRepositoryManager daemon = node("daemon", store, ignored -> {});
    daemon.compactor().start();
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline
          && (daemon.compactor().isQueued(PROJECT)
              || !daemon.compactor().policy().plan(manifest(daemon)).isEmpty())) {
        Thread.sleep(50);
      }
      Manifest manifest = manifest(daemon);
      assertTrue(
          daemon.compactor().policy().plan(manifest).isEmpty(), "the sweep found and compacted it");
      assertEquals(1, objectPacks(manifest).size());
    } finally {
      daemon.compactor().stop();
    }
  }

  @Test
  void anotherNodesLeaseDefersCompaction() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager nodeA = node("node-a", store, ignored -> {});
    WalGitRepositoryManager nodeB = node("node-b", store, ignored -> {});
    try (Repository repository = nodeA.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        publish(repository, head("b" + i), "commit " + i);
      }
    }

    try (StoreLease.Held byB =
        nodeB.storage().compactionLease(nodeB.idOf(PROJECT)).acquire(Duration.ofMinutes(5)).orElseThrow()) {
      assertEquals(Outcome.LEASED_ELSEWHERE, nodeA.compactor().compact(PROJECT));
      assertTrue(objectPacks(manifest(nodeA)).size() >= 4, "nothing was rewritten");
    }
    assertEquals(Outcome.COMPACTED, nodeA.compactor().compact(PROJECT));
    assertEquals(Outcome.NOTHING_TO_DO, nodeA.compactor().compact(PROJECT));
  }

  @Test
  void aCompactionThatLosesToAnotherNodeReplansAndLeavesItsOutputToTheReclaimer() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager nodeB = node("node-b", shared, ignored -> {});
    try (Repository repository = nodeB.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        publish(repository, head("b" + i), "commit " + i);
      }
    }
    Set<String> outputOfB = new HashSet<>();
    // Right before node A publishes its compaction, node B compacts two of the same inputs.
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesPackCompaction,
            () -> {
              try (LocalWalGitRepository repository =
                  (LocalWalGitRepository) nodeB.openRepository(PROJECT)) {
                DfsPackCompactor compactor = new DfsPackCompactor(repository);
                int added = 0;
                for (DfsPackFile pack : repository.getObjectDatabase().getPacks()) {
                  if (pack.getPackDescription().hasFileExt(PackExt.PACK) && added < 2) {
                    compactor.add(pack);
                    added++;
                  }
                }
                compactor.compact(NullProgressMonitor.INSTANCE);
                compactor
                    .getNewPacks()
                    .forEach(pack -> outputOfB.add(LocalWalGitObjectDatabase.packName(pack)));
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            });
    WalGitRepositoryManager nodeA = node("node-a", hooked, ignored -> {});
    Map<String, ObjectId> refs = allRefs(nodeB);

    assertEquals(Outcome.COMPACTED, nodeA.compactor().compact(PROJECT));

    assertEquals(
        2,
        hooked.matchedCasAttempts.get(),
        "the first attempt lost its inputs; the next pass re-planned on the fresh manifest");
    Manifest after = manifest(nodeB);
    assertEquals(1, objectPacks(after).size(), "node A finished the roll-up node B started");
    Set<String> lostOutputOfA = new HashSet<>();
    for (PackRef pack : objectPacks(hooked.firstMatchingProposal)) {
      if (pack.getSource().equals("COMPACT") && !outputOfB.contains(pack.getName())) {
        lostOutputOfA.add(pack.getName());
      }
    }
    assertEquals(1, lostOutputOfA.size());
    Set<String> live = ManifestStore.liveFileNames(after);
    String lostPack = lostOutputOfA.iterator().next() + ".pack";
    assertTrue(shared.list(TestStores.wal(shared)).contains(TestStores.wal(shared) + lostPack), "the lost output stays for now");
    assertFalse(live.contains(lostPack), "but nothing references it");
    SteppingClock later = new SteppingClock(Instant.now().plus(Duration.ofDays(2)));
    Reclaimer reclaimer = new Reclaimer(nodeA, later, Duration.ofDays(1), 0);
    assertEquals(0, reclaimer.reclaim(PROJECT).deleted());
    later.advance(Duration.ofDays(2));
    reclaimer.reclaim(PROJECT);
    assertFalse(
        shared.list(TestStores.wal(shared)).contains(TestStores.wal(shared) + lostPack), "reclamation removes it after the grace period");
    assertEquals(refs, allRefs(nodeA));
    try (Repository repository = nodeA.openRepository(PROJECT)) {
      for (ObjectId id : refs.values()) {
        assertEquals(Constants.OBJ_COMMIT, repository.open(id).getType());
      }
    }
  }

  @Test
  void reftableCompactionAndARefUpdateOnAnotherNodeBothLandInEitherOrder() throws Exception {
    // Order one: node B updates a ref right before node A's compaction CAS; the compaction merges.
    {
      FileObjectStore shared = new FileObjectStore(root.resolve("store-1"));
      WalGitRepositoryManager nodeB = node("node-b1", shared, ignored -> {});
      Map<String, ObjectId> expected = new TreeMap<>();
      try (Repository repository = nodeB.createRepository(PROJECT)) {
        for (int batch = 0; batch < 3; batch++) {
          expected.putAll(publishBatch(repository, "batch" + batch, STANDALONE_TABLE_REFS));
        }
      }
      HookedObjectStore hooked =
          new HookedObjectStore(
              shared,
              HookedObjectStore::publishesReftableCompaction,
              () -> {
                try (Repository repository = nodeB.openRepository(PROJECT)) {
                  expected.put(
                      head("during"), publish(repository, head("during"), "during compaction"));
                } catch (Exception exception) {
                  throw new IllegalStateException(exception);
                }
              });
      WalGitRepositoryManager nodeA = node("node-a1", hooked, ignored -> {});
      assertEquals(Outcome.COMPACTED, nodeA.compactor().compact(PROJECT));
      assertEquals(
          2,
          hooked.matchedCasAttempts.get(),
          "the CAS lost once and merged node B's table on retry");
      assertEquals(expected, allRefs(node("node-c1", shared, ignored -> {})));
      assertTrue(reftables(manifest(nodeB)).size() <= 2, "the compacted stack plus node B's table");
    }
    // Order two: node A compacts right before node B's ref CAS; node B's transaction retries.
    {
      FileObjectStore shared = new FileObjectStore(root.resolve("store-2"));
      WalGitRepositoryManager nodeA = node("node-a2", shared, ignored -> {});
      Map<String, ObjectId> expected = new TreeMap<>();
      try (Repository repository = nodeA.createRepository(PROJECT)) {
        for (int batch = 0; batch < 3; batch++) {
          expected.putAll(publishBatch(repository, "batch" + batch, STANDALONE_TABLE_REFS));
        }
      }
      HookedObjectStore hooked =
          new HookedObjectStore(
              shared,
              HookedObjectStore::publishesRefChange,
              () -> {
                try {
                  assertEquals(Outcome.COMPACTED, nodeA.compactor().compact(PROJECT));
                } catch (IOException exception) {
                  throw new UncheckedIOException(exception);
                }
              });
      WalGitRepositoryManager nodeB = node("node-b2", hooked, ignored -> {});
      try (Repository repository = nodeB.openRepository(PROJECT)) {
        expected.put(head("during"), publish(repository, head("during"), "during compaction"));
      }
      assertEquals(2, hooked.matchedCasAttempts.get(), "the ref transaction was re-run once");
      assertEquals(expected, allRefs(node("node-c2", shared, ignored -> {})));
    }
  }

  @Test
  void aLocalReftableCompactionWaitsForTheRefTransactionInFlightInsteadOfFailingIt()
      throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    java.util.concurrent.atomic.AtomicReference<Thread> compaction = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Outcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<WalGitRepositoryManager> nodeRef = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
    // Right before the ref transaction's CAS, the same node starts a compaction on another thread.
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            (current, proposed) -> armed.get() && HookedObjectStore.publishesRefChange(current, proposed),
            () -> {
              Thread thread =
                  new Thread(
                      () -> {
                        try {
                          outcome.set(nodeRef.get().compactor().compact(PROJECT));
                        } catch (IOException exception) {
                          throw new UncheckedIOException(exception);
                        }
                      },
                      "compaction-under-test");
              thread.start();
              compaction.set(thread);
              try {
                thread.join(500);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              assertTrue(thread.isAlive(), "the compaction waits for the write lock");
            });
    WalGitRepositoryManager node = node("node-a", hooked, ignored -> {});
    nodeRef.set(node);
    Map<String, ObjectId> expected = new TreeMap<>();
    try (Repository repository = node.createRepository(PROJECT)) {
      for (int batch = 0; batch < 3; batch++) {
        expected.putAll(publishBatch(repository, "batch" + batch, STANDALONE_TABLE_REFS));
      }
    }
    armed.set(true);
    hooked.uploads.clear();
    String writer = Thread.currentThread().getName();

    try (Repository repository = node.openRepository(PROJECT)) {
      expected.put(head("during"), publish(repository, head("during"), "during compaction"));
    }
    compaction.get().join(30_000);

    // A re-run would have written a second reftable; a CAS retried inside publish reuses the first.
    long reftablesWrittenByTheTransaction =
        hooked.uploads.stream()
            .filter(upload -> upload.startsWith(writer + " ") && upload.endsWith(".ref"))
            .count();
    assertEquals(1, reftablesWrittenByTheTransaction, "the ref transaction was never re-run");
    assertEquals(Outcome.COMPACTED, outcome.get());
    Manifest manifest = manifest(node);
    assertTrue(reftables(manifest).size() <= 2);
    assertEquals(expected, allRefs(node("node-b", shared, ignored -> {})));
  }

  @Test
  void reclamationDeletesOnlyUnreferencedFilesOlderThanTheGracePeriod() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node-a", store, ignored -> {});
    Map<String, ObjectId> refs = new TreeMap<>();
    try (Repository repository = node.createRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        refs.put(head("b" + i), publish(repository, head("b" + i), "commit " + i));
      }
    }
    Set<String> before = new HashSet<>(store.list(TestStores.wal(store)));
    assertEquals(Outcome.COMPACTED, node.compactor().compact(PROJECT));
    Path orphan = root.resolve("orphan.pack");
    Files.writeString(orphan, "uploaded but never published");
    store.uploadIfAbsent(TestStores.wal(store) + "pack-orphan.pack", orphan);
    Manifest manifest = manifest(node);
    Set<String> live = ManifestStore.liveFileNames(manifest);
    String logs = "repos/" + TestStores.onlyRepository(store) + "/log/";
    Set<String> logsBefore = new HashSet<>(store.list(logs));

    SteppingClock clock = new SteppingClock(Instant.now());
    Reclaimer reclaimer = new Reclaimer(node, clock, Duration.ofHours(1), 0);
    Reclaimer.Report young = reclaimer.reclaim(PROJECT);
    assertEquals(0, young.deleted(), "nothing is younger than the grace period yet");
    assertTrue(store.list(TestStores.wal(store)).containsAll(before));

    clock.advance(Duration.ofHours(2));
    assertEquals(
        0, reclaimer.reclaim(PROJECT).deleted(), "first eligible absence starts reader grace");
    clock.advance(Duration.ofHours(2));
    Reclaimer.Report old = reclaimer.reclaim(PROJECT);
    Set<String> remaining = new HashSet<>();
    for (String key : store.list(TestStores.wal(store))) {
      remaining.add(key.substring(TestStores.wal(store).length()));
    }
    assertEquals(live, remaining, "exactly the live files remain");
    long superseded = before.stream().filter(key -> !live.contains(key.substring(TestStores.wal(store).length()))).count();
    assertEquals(superseded + 1, old.deleted(), "the superseded files and the orphan were deleted");
    assertEquals(
        logsBefore,
        new HashSet<>(store.list(logs)),
        "log objects are never touched");
    assertEquals(manifest, manifest(node), "reclamation changes no manifest");
    assertEquals(refs, allRefs(node("node-b", store, ignored -> {})));
  }

  @Test
  void theLocalCacheIsTrimmedToItsLimitAndRefilledOnDemand() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node-a", store, ignored -> {});
    Map<String, ObjectId> refs = new TreeMap<>();
    try (Repository repository = node.createRepository(PROJECT)) {
      for (int i = 0; i < 3; i++) {
        refs.put(head("b" + i), publish(repository, head("b" + i), "commit " + i));
      }
    }
    Path cache = TestStores.cacheWal(root.resolve("node-a-cache"), store);
    long cachedBefore = totalSize(cache);
    assertTrue(cachedBefore > 0);
    ageCachedFiles(cache, Duration.ofHours(1));

    Reclaimer reclaimer = new Reclaimer(node, new SteppingClock(Instant.now()), Duration.ofDays(1), 1);
    long trimmed = reclaimer.enforceCacheLimit();
    assertTrue(trimmed > 0);
    assertTrue(totalSize(cache) <= 1, "the cache respects its limit");

    try (Repository repository = node.openRepository(PROJECT)) {
      for (Map.Entry<String, ObjectId> ref : refs.entrySet()) {
        assertEquals(ref.getValue(), repository.exactRef(ref.getKey()).getObjectId());
        assertEquals(Constants.OBJ_COMMIT, repository.open(ref.getValue()).getType());
      }
    }
    assertTrue(totalSize(cache) > 0, "reads refill the cache from the store");
  }

  private WalGitRepositoryManager node(String name, ObjectStore store, Consumer<Config> tweak) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    config.setInt("walgerrit", null, "compactMinPacks", 3);
    config.setInt("walgerrit", null, "compactMinReftables", 3);
    tweak.accept(config);
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root.resolve(name));
    StorageLayout layout =
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), "");
    return new WalGitRepositoryManager(configuration, layout);
  }

  private static String head(String name) {
    return Constants.R_HEADS + name;
  }

  private static ObjectId publish(Repository repository, String ref, String message)
      throws Exception {
    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
    RefUpdate update = repository.updateRef(ref);
    update.setNewObjectId(commit);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD, result.name());
    return commit;
  }

  /** Five or more refs per batch keep JGit from folding the new table into the top of the stack. */
  private static Map<String, ObjectId> publishBatch(Repository repository, String prefix, int refs)
      throws Exception {
    Map<String, ObjectId> created = new TreeMap<>();
    List<ReceiveCommand> commands = new ArrayList<>();
    for (int i = 0; i < refs; i++) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, prefix + " " + i);
      String name = head(prefix + "-" + i);
      created.put(name, commit);
      commands.add(new ReceiveCommand(ObjectId.zeroId(), commit, name));
    }
    BatchRefUpdate batch = repository.getRefDatabase().newBatchUpdate();
    batch.addCommand(commands);
    try (RevWalk walk = new RevWalk(repository)) {
      batch.execute(walk, NullProgressMonitor.INSTANCE);
    }
    for (ReceiveCommand command : commands) {
      assertEquals(ReceiveCommand.Result.OK, command.getResult(), command.getRefName());
    }
    return created;
  }

  private static Manifest manifest(WalGitRepositoryManager node) throws IOException {
    return node.manifestStore(PROJECT).refresh();
  }

  private static Map<String, ObjectId> allRefs(WalGitRepositoryManager node) throws IOException {
    Map<String, ObjectId> refs = new TreeMap<>();
    try (Repository repository = node.openRepository(PROJECT)) {
      for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
        refs.put(ref.getName(), ref.getObjectId());
      }
    }
    return refs;
  }

  private static List<PackRef> objectPacks(Manifest manifest) {
    return manifest.getPacksList().stream().filter(CompactionPolicy::isObjectPack).toList();
  }

  private static Set<String> objectPackNames(Manifest manifest) {
    Set<String> names = new HashSet<>();
    objectPacks(manifest).forEach(pack -> names.add(pack.getName()));
    return names;
  }

  private static List<PackRef> reftables(Manifest manifest) {
    return manifest.getPacksList().stream().filter(CompactionPolicy::hasReftable).toList();
  }

  private static LogEntry lastEntry(FileObjectStore store, Manifest manifest) throws IOException {
    String key =
        "repos/"
            + TestStores.onlyRepository(store)
            + "/"
            + ManifestStore.logKey(manifest.getHeadSeq(), manifest.getHeadTransactionId());
    return LogEntry.parseFrom(store.get(key).orElseThrow().bytes());
  }

  private static void ageCachedFiles(Path directory, Duration by) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        Files.setLastModifiedTime(
            file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - by.toMillis()));
      }
    }
  }

  private static Set<String> cachedFiles(Path directory) throws IOException {
    Set<String> names = new HashSet<>();
    if (!Files.isDirectory(directory)) {
      return names;
    }
    try (Stream<Path> files = Files.list(directory)) {
      files.forEach(file -> names.add(file.getFileName().toString()));
    }
    return names;
  }

  private static long totalSize(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return 0;
    }
    try (Stream<Path> files = Files.list(directory)) {
      long total = 0;
      for (Path file : (Iterable<Path>) files::iterator) {
        total += Files.size(file);
      }
      return total;
    }
  }
}
