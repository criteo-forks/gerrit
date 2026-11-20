// Copyright (C) 2026 The Android Open Source Project
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.ManifestReadCountTest.CountingObjectStore;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests log caching and ref-transaction store reads across nodes. */
class TwoWriterWindowTest {
  private static final Project.NameKey PROJECT = Project.nameKey("review/two-writers");
  private static final String LOG_READS = "GET log/*";
  private static final Set<String> READS = Set.of("get", "getIfChanged", "getRange", "download");

  @TempDir Path root;

  /** Cached entries match the store and need no additional reads. */
  @Test
  void entriesANodeKeepsAreTheEntriesTheStoreHolds() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    CountingObjectStore counting = new CountingObjectStore(shared);
    WalGitRepositoryManager a = node("a", counting);
    WalGitRepositoryManager b = node("b", shared);
    ObjectId commit = publishCommit(a, "base");
    for (int round = 0; round < 6; round++) {
      assertEquals(RefUpdate.Result.NEW, create(a, "a-" + round, commit));
      publishEvents(b, 2);
      assertEquals(RefUpdate.Result.NEW, create(b, "b-" + round, commit));
      publishEvents(a, 1);
      if (round == 3) {
        compact(b);
      }
    }
    Manifest head = a.manifestStore(PROJECT).refresh();
    // The store also holds entries from failed CAS attempts.
    Map<String, LogEntry> stored = storedEntries(shared);

    counting.reset();
    List<LogEntry> kept = a.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 10_000);
    long read = counting.count(LOG_READS);

    assertEquals(head.getHeadSeq(), kept.size());
    for (LogEntry entry : kept) {
      assertEquals(
          stored.get(ManifestStore.logKey(entry.getSeq(), entry.getTransactionId())),
          entry,
          "entry " + entry.getSeq());
    }
    assertTrue(
        read < kept.size(),
        "the node read " + read + " of " + kept.size() + " entries; it published the others");
    counting.reset();
    assertEquals(kept, a.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 10_000));
    assertEquals(0, counting.count(LOG_READS), "a second walk reads nothing: " + counting);

    CountingObjectStore coldStore = new CountingObjectStore(shared);
    WalGitRepositoryManager cold = node("cold", coldStore);
    cold.manifestStore(PROJECT).refresh();
    coldStore.reset();
    assertEquals(kept, cold.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 10_000));
    assertEquals(kept.size(), coldStore.count(LOG_READS), "a node that kept nothing reads each");
  }

  /** Oversized entries are published but read from the store instead of cached. */
  @Test
  void anEntryTooLargeToKeepIsReadFromTheStore() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    CountingObjectStore counting = new CountingObjectStore(shared);
    WalGitRepositoryManager a = node("a", counting);
    publishEvents(a, 2);
    String large = "\"" + "x".repeat((int) ManifestCache.LOG_ENTRY_MAX_WEIGHT / 2) + "\"";

    a.manifestStore(PROJECT).publishEvents(List.of(large));

    Manifest head = a.manifestStore(PROJECT).current();
    List<LogEntry> entries = a.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 100);
    assertEquals(head.getHeadSeq(), entries.size());
    for (int walk = 0; walk < 2; walk++) {
      counting.reset();
      assertEquals(entries, a.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 100));
      assertEquals(1, counting.count(LOG_READS), "only the large entry is read: " + counting);
    }
    assertEquals(
        head.getHeadSeq(),
        node("cold", shared).manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 100).size());
  }

  /** Concurrent writers on two nodes leave one history containing all acknowledged updates. */
  @Test
  void concurrentWritersOnTwoNodesLeaveOneHistory() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager a = node("a", shared);
    WalGitRepositoryManager b = node("b", shared);
    ObjectId commit = publishCommit(a, "base");
    b.manifestStore(PROJECT).refresh();
    int writersPerNode = 3;
    int rounds = 5;
    List<String> acknowledged = new CopyOnWriteArrayList<>();
    List<WalGitRepositoryManager> nodes = List.of(a, b);
    AtomicLong wins = new AtomicLong();

    concurrently(
        writersPerNode * nodes.size(),
        writer -> {
          WalGitRepositoryManager node = nodes.get(writer % nodes.size());
          for (int round = 0; round < rounds; round++) {
            String own = "writer-" + writer + "-" + round;
            assertEquals(RefUpdate.Result.NEW, createEventually(node, own, commit));
            acknowledged.add(own);
            if (createEventually(node, "contested-" + round, commit) == RefUpdate.Result.NEW) {
              wins.incrementAndGet();
            }
          }
          return null;
        });

    assertEquals(rounds, wins.get(), "one writer wins each contested ref");
    Manifest head = a.manifestStore(PROJECT).refresh();
    assertEquals(head, b.manifestStore(PROJECT).refresh());
    WalGitRepositoryManager cold = node("cold", shared);
    try (Repository repo = cold.openRepository(PROJECT)) {
      for (String name : acknowledged) {
        Ref ref = repo.exactRef("refs/heads/" + name);
        assertNotNull(ref, name);
        assertEquals(commit, ref.getObjectId());
      }
      for (int round = 0; round < rounds; round++) {
        assertNotNull(repo.exactRef("refs/heads/contested-" + round));
      }
    }
    List<LogEntry> history = cold.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 10_000);
    assertEquals(history, a.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 10_000));
    assertEquals(history, b.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 10_000));
    Map<String, LogEntry> stored = storedEntries(shared);
    for (LogEntry entry : history) {
      assertEquals(
          stored.get(ManifestStore.logKey(entry.getSeq(), entry.getTransactionId())), entry);
    }
  }

  /**
   * Cached history resolves an ambiguous publication without more log reads, matching a cold node.
   */
  @Test
  void aLostAnswerIsSettledFromTheEntriesTheNodeKept() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicBoolean failRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              failRead.set(true);
              throw new IOException("lost response");
            });
    CountingObjectStore counting =
        new CountingObjectStore(
            intercept(
                hooked,
                (method, args) -> {
                  if (READS.contains(method.getName())
                      && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
                      && failRead.compareAndSet(true, false)) {
                    throw new IOException("manifest read failed");
                  }
                  return invoke(hooked, method, args);
                }));
    WalGitRepositoryManager a = node("a", counting);

    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(writer, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, create(writer, "first", first));
      GroupPublisher publisher = a.manifestStore(PROJECT).publisher();
      assertTrue(publisher.hasPending(), "the outcome is unknown, so the pack stays pending");
      long attempted = hooked.firstMatchingProposal.getHeadSeq();
      String attemptedId = hooked.firstMatchingProposal.getHeadTransactionId();
      publishEvents(b, 12);

      // The node follows the log, as its index tailer does.
      Manifest head = a.manifestStore(PROJECT).refresh();
      a.manifestStore(PROJECT).readLogEntriesAfter(attempted - 1, previous(shared, attempted), head, 100);
      counting.reset();

      List<PackRef> unpublished = publisher.snapshot(a.manifestStore(PROJECT), false).unpublished();

      assertEquals(List.of(), unpublished, "the attempt landed, so its pack is not unpublished");
      assertEquals(0, counting.count(LOG_READS), "nothing left to read: " + counting);
      assertTrue(
          node("cold", shared)
              .manifestStore(PROJECT)
              .transactionLanded(head, attempted, attemptedId),
          "the store agrees");
      assertEquals(RefUpdate.Result.NEW, create(writer, "second", first));
      assertEquals(0, counting.count(LOG_READS), "nor for the next publication: " + counting);
    }
  }

  /** A CAS lost to a peer is checked against the log, and the entries are reused for replay. */
  @Test
  void aLostCasIsRefutedFromTheLogAndItsEntriesAreKept() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    ObjectId theirs = publishCommit(b, "theirs");
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {
              try {
                assertEquals(RefUpdate.Result.NEW, create(b, "contested", theirs));
                publishEvents(b, 5);
              } catch (IOException failure) {
                throw new IllegalStateException(failure);
              }
            });
    CountingObjectStore counting = new CountingObjectStore(hooked);
    WalGitRepositoryManager a = node("a", counting);

    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId mine = WalGitRepositoryManagerTest.insertCommit(writer, "mine");
      counting.reset();
      assertEquals(RefUpdate.Result.LOCK_FAILURE, create(writer, "contested", mine));
      assertEquals(1, hooked.matchedCasAttempts.get(), "the transaction reached its CAS once");
      assertEquals(5, counting.count(LOG_READS), "the entries above the attempt: " + counting);
      long attempted = hooked.firstMatchingProposal.getHeadSeq();
      String attemptedId = hooked.firstMatchingProposal.getHeadTransactionId();
      GroupPublisher publisher = a.manifestStore(PROJECT).publisher();
      Manifest head = a.manifestStore(PROJECT).current();
      WalGitRepositoryManager cold = node("cold", shared);
      List<LogEntry> history = cold.manifestStore(PROJECT).readLogEntriesAfter(0, "", head, 100);

      assertEquals(
          false, cold.manifestStore(PROJECT).transactionLanded(head, attempted, attemptedId));
      assertEquals(
          1,
          publisher.snapshot(a.manifestStore(PROJECT), false).unpublished().size(),
          "the attempt did not land, so its pack is still unpublished");

      counting.reset();
      List<LogEntry> replayed =
          a.manifestStore(PROJECT)
              .readLogEntriesAfter(
                  attempted, history.get((int) attempted - 1).getTransactionId(), head, 100);
      assertEquals(history.subList((int) attempted, history.size()), replayed);
      assertEquals(0, counting.count(LOG_READS), "replay reads none of them again: " + counting);
      assertEquals(theirs, writer.exactRef("refs/heads/contested").getObjectId());
    }
  }

  /**
   * A transaction behind a peer's publications prefetches before the lock, so a slow store delays
   * it and not the node's other writers.
   */
  @Test
  void aTransactionReadsTheStoreBeforeItTakesTheNodeLock() throws Exception {
    long latencyMillis = 150;
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicBoolean slow = new AtomicBoolean();
    List<String> readUnderLock = new CopyOnWriteArrayList<>();
    List<String> readOutside = new CopyOnWriteArrayList<>();
    ReentrantLock[] nodeLock = new ReentrantLock[1];
    WalGitRepositoryManager a =
        node(
            "a",
            intercept(
                shared,
                (method, args) -> {
                  if (slow.get() && READS.contains(method.getName())) {
                    (nodeLock[0].isHeldByCurrentThread() ? readUnderLock : readOutside)
                        .add(method.getName() + " " + args[0]);
                    Thread.sleep(latencyMillis);
                  }
                  return invoke(shared, method, args);
                }));
    ObjectId commit = publishCommit(a, "base");
    nodeLock[0] = a.manifestStore(PROJECT).publisher().nodeLock();

    try (Repository writer = a.openRepository(PROJECT)) {
      for (int i = 0; i < 4; i++) {
        assertEquals(RefUpdate.Result.NEW, create(b, "from-b-" + i, commit));
      }
      LockWatch watch = new LockWatch(nodeLock[0]);
      slow.set(true);
      long started = System.nanoTime();

      assertEquals(RefUpdate.Result.NEW, create(writer, "from-a", commit));

      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      slow.set(false);
      long heldMillis = watch.stop();
      String measured =
          readUnderLock.size()
              + " store reads under the node lock ("
              + readUnderLock.size() * latencyMillis
              + " ms of store latency), "
              + readOutside.size()
              + " outside it; lock seen held for about "
              + heldMillis
              + " ms of a "
              + elapsedMillis
              + " ms transaction";
      System.out.println("TwoWriterWindowTest lock hold: " + measured);
      assertTrue(
          readOutside.stream().anyMatch(read -> read.endsWith(".ref")),
          "the other node's reftable was fetched: " + readOutside);
      assertEquals(List.of(), readUnderLock, measured);
    }
    try (Repository repo = node("cold", shared).openRepository(PROJECT)) {
      assertEquals(commit, repo.exactRef("refs/heads/from-a").getObjectId());
      assertEquals(commit, repo.exactRef("refs/heads/from-b-0").getObjectId());
    }
  }

  /**
   * A transaction validates against changes learned while it waits, fetching any missing manifest
   * or reftable under the lock after a peer announcement or another thread's manifest read.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aManifestThatArrivesWhileATransactionWaitsForTheLockIsValidatedAgainst(boolean announced)
      throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    List<String> readUnderLock = new CopyOnWriteArrayList<>();
    ReentrantLock[] nodeLock = new ReentrantLock[1];
    WalGitRepositoryManager a =
        node(
            "a",
            intercept(
                shared,
                (method, args) -> {
                  if (READS.contains(method.getName())
                      && nodeLock[0] != null
                      && nodeLock[0].isHeldByCurrentThread()) {
                    readUnderLock.add(method.getName() + " " + args[0]);
                  }
                  return invoke(shared, method, args);
                }));
    publishCommit(a, "x");
    ExecutorService pool = Executors.newSingleThreadExecutor();

    try (Repository writer = a.openRepository(PROJECT);
        RevWalk walk = new RevWalk(writer)) {
      ObjectId mine = WalGitRepositoryManagerTest.insertCommit(writer, "mine");
      ReceiveCommand create = new ReceiveCommand(ObjectId.zeroId(), mine, "refs/heads/x");
      BatchRefUpdate batch = writer.getRefDatabase().newBatchUpdate();
      batch.addCommand(create);
      ReentrantLock lock = a.manifestStore(PROJECT).publisher().nodeLock();
      Future<?> transaction;
      lock.lock();
      try {
        transaction =
            pool.submit(
                () -> {
                  batch.execute(walk, NullProgressMonitor.INSTANCE);
                  return null;
                });
        while (!lock.hasQueuedThreads()) {
          assertFalse(transaction.isDone(), "the transaction waits for the node lock");
          Thread.sleep(1);
        }
        try (Repository peer = b.openRepository(PROJECT)) {
          RefUpdate delete = peer.updateRef("refs/heads/x");
          delete.setForceUpdate(true);
          assertEquals(RefUpdate.Result.FORCED, delete.delete());
        }
        VersionedManifest published = b.manifestStore(PROJECT).refreshVersionedManifest();
        if (announced) {
          assertTrue(
              a.storage()
                  .expectManifest(
                      a.idOf(PROJECT), published.version(), published.manifest().getRevision()));
        } else {
          assertEquals(published.manifest(), a.manifestStore(PROJECT).refresh());
        }
        nodeLock[0] = lock;
      } finally {
        lock.unlock();
      }
      transaction.get(60, TimeUnit.SECONDS);

      assertEquals(ReceiveCommand.Result.OK, create.getResult(), create.getMessage());
      assertEquals(
          announced,
          readUnderLock.stream().anyMatch(read -> read.endsWith(ManifestStore.MANIFEST_FILE)),
          "an announced manifest is read under the lock: " + readUnderLock);
      assertTrue(
          readUnderLock.stream().anyMatch(read -> read.endsWith(".ref")),
          "the table that deletes the ref was fetched under the lock: " + readUnderLock);
      try (Repository repo = node("cold", shared).openRepository(PROJECT)) {
        assertEquals(mine, repo.exactRef("refs/heads/x").getObjectId());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** A cold node checks fast-forwards using history fetched from the store. */
  @Test
  void aColdNodeDecidesAFastForwardFromHistoryItFetches() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    ObjectId tip;
    try (Repository peer = b.openRepository(PROJECT)) {
      tip = WalGitRepositoryManagerTest.insertCommit(peer, "root");
      assertEquals(RefUpdate.Result.NEW, create(peer, "x", tip));
      for (int i = 0; i < 4; i++) {
        tip = commitOn(peer, tip, "history " + i);
        RefUpdate advance = peer.updateRef("refs/heads/x");
        advance.setNewObjectId(tip);
        assertEquals(RefUpdate.Result.FAST_FORWARD, advance.update());
      }
    }
    List<String> readUnderLock = new CopyOnWriteArrayList<>();
    List<String> readOutside = new CopyOnWriteArrayList<>();
    ReentrantLock[] nodeLock = new ReentrantLock[1];
    WalGitRepositoryManager a =
        node(
            "a",
            intercept(
                shared,
                (method, args) -> {
                  if (nodeLock[0] != null && READS.contains(method.getName())) {
                    (nodeLock[0].isHeldByCurrentThread() ? readUnderLock : readOutside)
                        .add(method.getName() + " " + args[0]);
                  }
                  return invoke(shared, method, args);
                }));
    ObjectId child;

    try (Repository writer = a.openRepository(PROJECT);
        RevWalk walk = new RevWalk(writer)) {
      ObjectId unrelated = WalGitRepositoryManagerTest.insertCommit(writer, "unrelated");
      child = commitOn(writer, tip, "child");
      nodeLock[0] = a.manifestStore(PROJECT).publisher().nodeLock();

      ReceiveCommand rewind = new ReceiveCommand(tip, unrelated, "refs/heads/x");
      BatchRefUpdate refused = writer.getRefDatabase().newBatchUpdate();
      refused.addCommand(rewind);
      refused.execute(walk, NullProgressMonitor.INSTANCE);
      assertEquals(
          ReceiveCommand.Result.REJECTED_NONFASTFORWARD, rewind.getResult(), rewind.getMessage());
      assertEquals(tip, writer.exactRef("refs/heads/x").getObjectId());

      ReceiveCommand forward = new ReceiveCommand(tip, child, "refs/heads/x");
      BatchRefUpdate accepted = writer.getRefDatabase().newBatchUpdate();
      accepted.addCommand(forward);
      accepted.execute(walk, NullProgressMonitor.INSTANCE);
      assertEquals(ReceiveCommand.Result.OK, forward.getResult(), forward.getMessage());
    }
    System.out.println(
        "TwoWriterWindowTest cold fast-forward checks: "
            + readUnderLock.size()
            + " store reads under the node lock, "
            + readOutside.size()
            + " outside it");
    assertTrue(
        readUnderLock.size() + readOutside.size() > 0, "the other node's history was fetched");
    try (Repository repo = node("cold", shared).openRepository(PROJECT);
        RevWalk walk = new RevWalk(repo)) {
      assertEquals(child, repo.exactRef("refs/heads/x").getObjectId());
      assertEquals(tip, walk.parseCommit(child).getParent(0));
    }
  }

  private static ObjectId commitOn(Repository repository, ObjectId parent, String message)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      PersonIdent author = new PersonIdent("WalGerrit Test", "walgerrit@example.test");
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      commit.setParentId(parent);
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  /** Samples how long a lock is held by anyone. */
  private static final class LockWatch {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong heldNanos = new AtomicLong();
    private final Thread sampler;

    LockWatch(ReentrantLock lock) {
      sampler =
          new Thread(
              () -> {
                long last = System.nanoTime();
                while (running.get()) {
                  boolean held = lock.isLocked();
                  Thread.onSpinWait();
                  long now = System.nanoTime();
                  if (held) {
                    heldNanos.addAndGet(now - last);
                  }
                  last = now;
                }
              },
              "lock-watch");
      sampler.setDaemon(true);
      sampler.start();
    }

    long stop() throws InterruptedException {
      running.set(false);
      sampler.join();
      return TimeUnit.NANOSECONDS.toMillis(heldNanos.get());
    }
  }

  /** Every log entry in the store, by its key below the repository. */
  private static Map<String, LogEntry> storedEntries(ObjectStore store) throws IOException {
    String repository = "repos/" + TestStores.onlyRepository(store) + "/";
    Map<String, LogEntry> entries = new HashMap<>();
    for (ObjectStore.ObjectSummary object : store.listWithVersions(repository + "log/")) {
      entries.put(
          object.key().substring(repository.length()),
          LogEntry.parseFrom(store.get(object.key()).orElseThrow().bytes()));
    }
    return entries;
  }

  /** The transaction id of the entry before {@code sequence}, read from the store. */
  private static String previous(ObjectStore store, long sequence) throws IOException {
    return storedEntries(store).values().stream()
        .filter(entry -> entry.getSeq() == sequence)
        .map(LogEntry::getPreviousTransactionId)
        .findFirst()
        .orElseThrow();
  }

  private static ObjectId publishCommit(WalGitRepositoryManager node, String name)
      throws Exception {
    try (Repository repo = node.openRepository(PROJECT)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repo, name);
      assertEquals(RefUpdate.Result.NEW, create(repo, name, commit));
      return commit;
    }
  }

  private static void publishEvents(WalGitRepositoryManager node, int count) throws IOException {
    for (int i = 0; i < count; i++) {
      node.manifestStore(PROJECT).publishEvents(List.of("{}"));
    }
  }

  private static void compact(WalGitRepositoryManager node) throws Exception {
    try (LocalWalGitRepository repo = (LocalWalGitRepository) node.openRepository(PROJECT)) {
      WalGitRepositoryManagerTest.insertCommit(repo, "another pack");
      DfsPackCompactor compactor = new DfsPackCompactor(repo);
      for (var pack : repo.getObjectDatabase().getPacks()) {
        compactor.add(pack);
      }
      compactor.compact(NullProgressMonitor.INSTANCE);
    }
  }

  private static RefUpdate.Result create(Repository repo, String name, ObjectId id)
      throws IOException {
    RefUpdate update = repo.updateRef("refs/heads/" + name);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(id);
    return update.update();
  }

  private static RefUpdate.Result create(WalGitRepositoryManager node, String name, ObjectId id)
      throws IOException {
    try (Repository repo = node.openRepository(PROJECT)) {
      return create(repo, name, id);
    }
  }

  /** Creates the ref as a client does that retries a lock failure until the ref exists. */
  private static RefUpdate.Result createEventually(
      WalGitRepositoryManager node, String name, ObjectId id) throws IOException {
    for (int attempt = 0; attempt < 50; attempt++) {
      try (Repository repo = node.openRepository(PROJECT)) {
        RefUpdate.Result result = create(repo, name, id);
        if (result == RefUpdate.Result.NEW || repo.exactRef("refs/heads/" + name) != null) {
          return result;
        }
      }
    }
    throw new AssertionError("refs/heads/" + name + " was never created");
  }

  @FunctionalInterface
  private interface Work<T> {
    T call(int worker) throws Exception;
  }

  /** Runs {@code work} on {@code threads} threads released together. */
  private static <T> List<T> concurrently(int threads, Work<T> work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier together = new CyclicBarrier(threads);
      List<Future<T>> running = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        int worker = i;
        Callable<T> task =
            () -> {
              together.await(30, TimeUnit.SECONDS);
              return work.call(worker);
            };
        running.add(pool.submit(task));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> one : running) {
        results.add(one.get(120, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface Invocation {
    Object call(Method method, Object[] args) throws Throwable;
  }

  private static ObjectStore intercept(ObjectStore delegate, Invocation invocation) {
    return (ObjectStore)
        Proxy.newProxyInstance(
            ObjectStore.class.getClassLoader(),
            new Class<?>[] {ObjectStore.class},
            (proxy, method, args) -> invocation.call(method, args));
  }

  private static Object invoke(ObjectStore delegate, Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }
}
