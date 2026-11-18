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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.change.TestChangeIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchChangeException;
import dev.walgerrit.IndexEventTailer.Indexed;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node that comes back after the other node wrote without it: changes in several repositories,
 * and in the users repository the stars, drafts and account updates that go with them.
 *
 * <p>Both nodes share one store and each has its own site, as two pods do. The returning node runs
 * the tailer and {@link GerritIndexEventApplier} as a daemon does, over a change index that holds,
 * for each change, the stars and drafts the users repository held when the change was indexed.
 */
class ReturningNodeTest {
  private static final Account.Id USER = Account.id(1000001);
  private static final Account.Id OTHER_USER = Account.id(1000002);

  @TempDir Path root;

  private WalGitRepositoryManager writer;

  /** A repository a sweep replays before the users repository, and one it replays after. */
  private Project.NameKey early;

  private Project.NameKey late;

  /** The repository that holds stars, drafts and accounts: the middle one in sweep order. */
  private AllUsersName allUsers;

  /** The returning node's change index and account index; they outlive its processes. */
  private final ChangeIndex index = new ChangeIndex();

  private final List<Account.Id> indexedAccounts = new CopyOnWriteArrayList<>();

  @BeforeEach
  void createRepositories() throws Exception {
    writer = node("writer", new FileObjectStore(root.resolve("store")));
    NavigableMap<RepositoryId, Project.NameKey> inSweepOrder = new TreeMap<>();
    for (int i = 0; i < 3; i++) {
      Project.NameKey name = Project.nameKey("repository-" + i);
      writer.createRepository(name).close();
      inSweepOrder.put(writer.idOf(name), name);
    }
    List<Project.NameKey> names = List.copyOf(inSweepOrder.values());
    early = names.get(0);
    allUsers = new AllUsersName(names.get(1).get());
    late = names.get(2);
  }

  @Test
  void opensItsListenersWhileItCatchesUpAndAnswersNothingUntilItHas() throws Exception {
    Process before = returning();
    change(early, 1);
    before.startAndCatchUp();
    before.stop();

    // The absence: changes in two repositories, a star on a change from before it, and stars,
    // drafts and account updates naming the changes created during it.
    change(early, 2);
    change(late, 3);
    star(1, USER);
    star(2, USER);
    account(USER);
    draft(3, OTHER_USER);
    account(OTHER_USER);
    change(late, 4);
    star(4, OTHER_USER);

    Process back = returning();
    CountDownLatch replaying = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    index.beforeWrite =
        change -> {
          replaying.countDown();
          await(release);
        };
    Thread daemon = new Thread(back.tailer::start, "daemon-start");
    daemon.start();
    try {
      assertTrue(replaying.await(30, TimeUnit.SECONDS), "the catch-up began");
      daemon.join(TimeUnit.SECONDS.toMillis(30));
      assertFalse(
          daemon.isAlive(),
          "start() returns while the backlog is replayed: the daemon's listeners open, and a"
              + " startup or liveness probe is answered however long the replay takes");
      assertFalse(back.readiness.isReady());
      assertTrue(Files.notExists(back.readiness.markerPath()));
      assertEquals(Optional.of(StartupGate.CATCHING_UP), new StartupGate(back.tailer).get());
      assertEquals(Indexed.UNAVAILABLE, back.tailer.awaitIndexed(late, Duration.ofSeconds(5)));
      assertEquals(Indexed.UNAVAILABLE, back.tailer.awaitAllIndexed(Duration.ofSeconds(5)));

      release.countDown();
      assertTrue(back.tailer.awaitStartupCatchUp(Duration.ofSeconds(60)));

      assertTrue(back.readiness.isReady());
      assertTrue(Files.isRegularFile(back.readiness.markerPath()));
      assertEquals(Optional.empty(), new StartupGate(back.tailer).get());
      assertEquals(Indexed.NOT_BEHIND, back.tailer.awaitIndexed(late, Duration.ofSeconds(5)));
      assertIndexAgreesWithTheRepositories(back, 1, 2, 3, 4);
      assertTrue(indexedAccounts.containsAll(List.of(USER, OTHER_USER)));
      back.assertCaughtUp(allUsers, early, late);
    } finally {
      release.countDown();
      back.stop();
    }
  }

  @Test
  void refusesToStartOnAGerritBuildThatWouldNotAskTheGate() {
    // These tests run against the upstream plugin API, which has no ServingGate.
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, new StartupGate(returning().tailer)::start);
    assertTrue(refused.getMessage().contains("ServingGate"), refused.getMessage());
  }

  @Test
  void aStarReplayedBeforeItsChangeAndOneOnADeletedChangeDoNotKeepItFromBecomingReady()
      throws Exception {
    Process before = returning();
    before.startAndCatchUp();
    before.stop();

    // Change 5 is deleted again during the absence; its draft was written while it existed.
    change(early, 5);
    draft(5, USER);
    delete(early, RefNames.changeMetaRef(Change.id(5)));
    change(late, 6);
    star(6, USER);
    account(USER);

    Process back = returning();
    back.startAndCatchUp();
    try {
      assertTrue(back.readiness.isReady());
      assertIndexAgreesWithTheRepositories(back, 6);
      assertEquals(List.of(USER), indexedAccounts, "the account update is not held back");
      back.assertCaughtUp(allUsers, early, late);

      // The deleted change stays listed and is looked for at every sweep, for a time.
      assertEquals(List.of(5), back.listedAsUnindexed(allUsers));
      int lookups = index.lookups(5);
      back.tailer.runBackgroundSweep();
      assertEquals(lookups + 1, index.lookups(5));
      assertEquals(List.of(5), back.listedAsUnindexed(allUsers));

      back.tailer.unindexedGiveUp(Duration.ZERO);
      back.tailer.runBackgroundSweep();
      assertEquals(List.of(), back.listedAsUnindexed(allUsers));
      lookups = index.lookups(5);
      back.tailer.runBackgroundSweep();
      assertTrue(back.readiness.isReady());
      assertEquals(lookups, index.lookups(5), "the change is taken to be deleted");
    } finally {
      back.stop();
    }
  }

  @Test
  void aWakeUpForAStarThatArrivesBeforeItsChangesIsNotAFailure() throws Exception {
    Process node = returning();
    node.startAndCatchUp();
    try {
      change(late, 7);
      star(7, USER);

      // The hint for the users repository is replayed first.
      node.tailer.catchUp(allUsers);
      node.assertCaughtUp(allUsers);
      assertEquals(Set.of(), index.documents.keySet());

      node.tailer.catchUp(late);
      node.tailer.runBackgroundSweep();
      assertTrue(node.readiness.isReady());
      assertIndexAgreesWithTheRepositories(node, 7);
    } finally {
      node.stop();
    }
  }

  @Test
  void aStarOnAChangeThisNodeIsStillIndexingReachesItsDocument() throws Exception {
    Process node = returning();
    node.startAndCatchUp();
    try {
      // This node created change 8: its repository is replayed, and the request that created it
      // has loaded the change but not written its document when the star is replayed.
      change(early, 8);
      node.tailer.catchUp(early);
      Set<String> loadedBeforeTheStar = index.documents.remove(8);
      star(8, USER);
      node.tailer.catchUp(allUsers);
      index.documents.put(8, loadedBeforeTheStar);

      node.tailer.runBackgroundSweep();

      assertTrue(node.readiness.isReady());
      assertIndexAgreesWithTheRepositories(node, 8);
    } finally {
      node.stop();
    }
  }

  @Test
  void aStarReplayedWhileItsDocumentIsBeingWrittenOutlastsARestart() throws Exception {
    Process node = returning();
    node.startAndCatchUp();
    change(early, 9);
    node.tailer.catchUp(early);
    Set<String> loadedBeforeTheStar = index.documents.remove(9);
    star(9, USER);
    node.tailer.catchUp(allUsers);
    node.assertCaughtUp(allUsers);
    assertEquals(List.of(9), node.listedAsUnindexed(allUsers));
    // The process ends before its next sweep; the index it leaves holds the document as the
    // request loaded it.
    node.stop();
    index.documents.put(9, loadedBeforeTheStar);

    Process restarted = returning();
    restarted.startAndCatchUp();
    try {
      assertTrue(restarted.readiness.isReady());
      assertIndexAgreesWithTheRepositories(restarted, 9);
      assertEquals(List.of(), restarted.listedAsUnindexed(allUsers));
    } finally {
      restarted.stop();
    }
  }

  @Test
  void aDocumentWrittenSeveralSweepsAfterItsStarWasReplayedIsIndexedOnceMore() throws Exception {
    Process node = returning();
    node.startAndCatchUp();
    try {
      change(early, 10);
      node.tailer.catchUp(early);
      Set<String> loadedBeforeTheStar = index.documents.remove(10);
      star(10, USER);
      node.tailer.catchUp(allUsers);
      for (int sweep = 0; sweep < 3; sweep++) {
        node.tailer.runBackgroundSweep();
        assertTrue(node.readiness.isReady());
      }
      index.documents.put(10, loadedBeforeTheStar);

      node.tailer.runBackgroundSweep();

      assertIndexAgreesWithTheRepositories(node, 10);
    } finally {
      node.stop();
    }
  }

  @Test
  void anErrorDuringItsCatchUpDoesNotEndItsSweeps() throws Exception {
    Process before = returning();
    before.startAndCatchUp();
    before.stop();
    change(late, 30);

    pollInterval = "100 ms";
    AtomicBoolean failed = new AtomicBoolean();
    index.beforeWrite =
        change -> {
          if (failed.compareAndSet(false, true)) {
            throw new AssertionError("an indexer's failure");
          }
        };
    Process back = returning();
    back.tailer.start();
    try {
      assertTrue(back.tailer.awaitStartupCatchUp(Duration.ofSeconds(30)));
      assertTrue(failed.get());
      assertTrue(back.readiness.isReady());
      assertIndexAgreesWithTheRepositories(back, 30);
    } finally {
      back.stop();
    }
  }

  @Test
  void killedDuringItsCatchUpItCarriesOnFromItsCursors() throws Exception {
    Process before = returning();
    before.startAndCatchUp();
    before.stop();

    for (int change = 10; change < 14; change++) {
      change(early, change);
      star(change, USER);
    }
    for (int change = 20; change < 24; change++) {
      change(late, change);
      star(change, USER);
      draft(change, OTHER_USER);
    }
    account(USER);

    // The process is killed at its second change of the last repository in sweep order: the
    // users repository and the first one are behind it by then.
    Process killed = returning();
    CountDownLatch dead = new CountDownLatch(1);
    index.beforeWrite =
        change -> {
          if (change.get() == 21) {
            dead.countDown();
            throw new Killed();
          }
        };
    killed.tailer.start();
    assertTrue(dead.await(30, TimeUnit.SECONDS));
    assertFalse(killed.tailer.awaitStartupCatchUp(Duration.ofMillis(200)));
    assertFalse(killed.readiness.isReady());
    killed.assertCaughtUp(allUsers, early);
    index.beforeWrite = change -> {};
    index.writes.clear();
    killed.store.reads.clear();

    Process restarted = returning();
    restarted.startAndCatchUp();
    try {
      assertTrue(restarted.readiness.isReady());
      assertIndexAgreesWithTheRepositories(restarted, 10, 11, 12, 13, 20, 21, 22, 23);
      restarted.assertCaughtUp(allUsers, early, late);
      assertEquals(
          List.of(),
          restarted.logEntriesRead(allUsers),
          "the users repository's log is not read again");
      assertEquals(List.of(), restarted.logEntriesRead(early));
      assertEquals(
          List.of(21, 22, 23),
          index.writes,
          "only what the killed process had not replayed is indexed");
    } finally {
      restarted.stop();
    }
  }

  /** What ends a process without its shutdown: nothing after it runs. */
  private static final class Killed extends Error {
    private static final long serialVersionUID = 1L;
  }

  /**
   * The index agrees with the repositories when it holds exactly {@code changes}, each with the
   * stars and drafts the users repository holds for it now.
   */
  private void assertIndexAgreesWithTheRepositories(Process node, int... changes)
      throws IOException {
    Map<Integer, Set<String>> expected = new ConcurrentHashMap<>();
    for (int change : changes) {
      expected.put(change, node.starsAndDrafts(Change.id(change)));
    }
    assertEquals(expected, index.documents);
  }

  // What the writing node does.

  private void change(Project.NameKey project, int change) throws Exception {
    publish(writer, project, RefNames.changeMetaRef(Change.id(change)));
  }

  private void star(int change, Account.Id account) throws Exception {
    publish(writer, allUsers, RefNames.refsStarredChanges(Change.id(change), account));
  }

  private void draft(int change, Account.Id account) throws Exception {
    publish(writer, allUsers, RefNames.refsDraftComments(Change.id(change), account));
  }

  private void account(Account.Id account) throws Exception {
    publish(writer, allUsers, RefNames.refsUsers(account));
  }

  private static void publish(WalGitRepositoryManager node, Project.NameKey project, String ref)
      throws Exception {
    try (Repository repository = node.openRepository(project)) {
      ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, ref);
      RefUpdate update = repository.updateRef(ref);
      update.setNewObjectId(commit);
      update.setForceUpdate(true);
      RefUpdate.Result result = update.update();
      assertTrue(
          result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED, result.toString());
    }
  }

  private void delete(Project.NameKey project, String ref) throws Exception {
    try (Repository repository = writer.openRepository(project)) {
      RefUpdate update = repository.updateRef(ref);
      update.setForceUpdate(true);
      assertEquals(RefUpdate.Result.FORCED, update.delete());
    }
  }

  // The returning node.

  /** Zero: a handle revalidates at every lookup, so each test reads what was just written. */
  private String revalidateInterval = "0";

  /** An hour: a test runs the sweeps after the first itself. */
  private String pollInterval = "1 hour";

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "indexPollInterval", pollInterval);
    config.setString("walgerrit", null, "manifestRevalidateInterval", revalidateInterval);
    Path site = root.resolve(name);
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, site),
        new StorageLayout(store, site.resolve("cache"), site.resolve("cursors"), ""));
  }

  /** A new process of the returning node, on the site and the indexes the earlier ones left. */
  private Process returning() {
    return new Process();
  }

  private final class Process {
    final ReadRecordingStore store =
        new ReadRecordingStore(new FileObjectStore(root.resolve("store")));
    final WalGitRepositoryManager repositories = node("returning", store);
    final IndexEventReadiness readiness =
        new IndexEventReadiness(root.resolve("returning").resolve("READY"));
    final IndexEventTailer tailer;

    Process() {
      ChangeNotes.Factory notes =
          new ChangeNotes.Factory(null, null, null) {
            @Override
            public ChangeNotes createChecked(Project.NameKey project, Change.Id change) {
              if (!holds(project, RefNames.changeMetaRef(change))) {
                throw new NoSuchChangeException(change);
              }
              index.loaded.set(change);
              return null;
            }

            @Override
            public ChangeNotes createCheckedUsingIndexLookup(Change.Id change) {
              index.lookups.merge(change.get(), 1, Integer::sum);
              if (!index.documents.containsKey(change.get())) {
                throw new NoSuchChangeException(change);
              }
              index.loaded.set(change);
              return null;
            }
          };
      TestChangeIndexer changes =
          new TestChangeIndexer() {
            @Override
            public void index(ChangeNotes ignored) {
              Change.Id change = index.loaded.get();
              index.beforeWrite.accept(change);
              index.documents.put(change.get(), starsAndDrafts(change));
              index.writes.add(change.get());
            }

            @Override
            public void delete(Change.Id change) {
              index.documents.remove(change.get());
            }
          };
      AccountIndexer accounts =
          GerritIndexEventApplierTest.proxy(
              AccountIndexer.class,
              (method, args) -> {
                if (method.equals("index")) {
                  indexedAccounts.add((Account.Id) args[0]);
                }
                return null;
              });
      GerritIndexEventApplier applier =
          new GerritIndexEventApplier(
              repositories,
              allUsers,
              changes,
              accounts,
              null,
              null,
              null,
              notes,
              null,
              null,
              new ReplicatedCacheEvictions(
                  id -> Optional.empty(),
                  null,
                  GerritIndexEventApplierTest.proxy(GroupCache.class, (method, args) -> null),
                  GerritIndexEventApplierTest.proxy(
                      GroupIncludeCache.class, (method, args) -> null),
                  (group, commit) -> Optional.empty()),
              null);
      tailer =
          new IndexEventTailer(
              repositories,
              inContext(applier),
              GerritRuntime.DAEMON,
              "lucene",
              durableLuceneConfig(),
              readiness,
              null);
    }

    void startAndCatchUp() throws InterruptedException {
      TestStores.startCaughtUp(tailer);
    }

    void stop() {
      tailer.stop();
    }

    boolean holds(Project.NameKey project, String ref) {
      try (Repository repository = repositories.openRepository(project)) {
        return repository.exactRef(ref) != null;
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    /** The stars and drafts the users repository holds for {@code change} now. */
    Set<String> starsAndDrafts(Change.Id change) {
      try (Repository users = repositories.openRepository(allUsers)) {
        Set<String> refs = new TreeSet<>();
        for (String prefix :
            List.of(
                RefNames.refsStarredChangesPrefix(change),
                RefNames.refsDraftCommentsPrefix(change))) {
          for (Ref ref : users.getRefDatabase().getRefsByPrefix(prefix)) {
            refs.add(ref.getName());
          }
        }
        return refs;
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    void assertCaughtUp(Project.NameKey... projects) throws IOException {
      for (Project.NameKey project : projects) {
        ManifestStore manifests = repositories.manifestStore(project);
        assertEquals(
            manifests.read().getHeadSeq(),
            new IndexCursorStore(manifests.indexCursorPath()).read().getSequence(),
            project.get());
      }
    }

    /** The changes the cursor of {@code project} lists as named and not in the index. */
    List<Integer> listedAsUnindexed(Project.NameKey project) throws IOException {
      return new IndexCursorStore(repositories.manifestStore(project).indexCursorPath())
          .read()
          .getUnindexedList();
    }

    /** The log entries of {@code project} this process read from the store. */
    List<String> logEntriesRead(Project.NameKey project) throws IOException {
      String log = "repos/" + repositories.idOf(project) + "/log/";
      return store.reads.stream().filter(key -> key.startsWith(log)).toList();
    }
  }

  /**
   * The applier as the tailer calls it, without the request context a daemon opens around each
   * call.
   */
  private static IndexEventApplier inContext(GerritIndexEventApplier applier) {
    return new IndexEventApplier() {
      @Override
      public void apply(Project.NameKey project, RefTransaction transaction) {
        applier.applyInContext(project, transaction);
      }

      @Override
      public Applied applyReporting(Project.NameKey project, RefTransaction transaction) {
        return applier.applyInContext(project, transaction);
      }

      @Override
      public boolean reindexIfIndexed(int change) {
        return applier.reindexIfIndexedInContext(Change.id(change));
      }
    };
  }

  /** A change index: each change number with the stars and drafts it was indexed with. */
  private static final class ChangeIndex {
    final Map<Integer, Set<String>> documents = new ConcurrentHashMap<>();

    /** The changes written, in order. */
    final List<Integer> writes = new CopyOnWriteArrayList<>();

    /** How often each change was looked up by its number alone. */
    final Map<Integer, Integer> lookups = new ConcurrentHashMap<>();

    final ThreadLocal<Change.Id> loaded = new ThreadLocal<>();
    volatile Consumer<Change.Id> beforeWrite = change -> {};

    int lookups(int change) {
      return lookups.getOrDefault(change, 0);
    }
  }

  /** Records the keys read from the store. */
  private static final class ReadRecordingStore implements ObjectStore {
    final List<String> reads = new CopyOnWriteArrayList<>();
    final List<String> conditionalReads = new CopyOnWriteArrayList<>();
    private final ObjectStore delegate;

    ReadRecordingStore(ObjectStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      reads.add(key);
      return delegate.get(key);
    }

    @Override
    public ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
      (knownVersion == null ? reads : conditionalReads).add(key);
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
      delegate.download(key, target);
    }

    @Override
    public byte[] getRange(String key, long offset, int length) throws IOException {
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

  private static Config durableLuceneConfig() {
    Config config = new Config();
    for (String name :
        List.of("accounts", "changes_open", "changes_closed", "groups", "projects")) {
      config.setString("index", name, "commitWithin", "0");
    }
    return config;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
