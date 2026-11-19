// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.git.GitUpdateFailureException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.git.RefUpdateUtil;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.update.BatchUpdateListener;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import dev.walgerrit.proto.StorageProto.LogPosition;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewChangeKeysTest {
  private static final Project.NameKey PROJECT = Project.nameKey("keys/test");
  private static final String MAIN = "refs/heads/main";
  private static final String KEY = "I" + "3".repeat(40);
  private static final String FOOTERS = "Branch: main\nChange-id: " + KEY;
  private static final ObjectId ZERO = ObjectId.zeroId();
  @TempDir Path root;

  @Test
  void requiresTheCorePublicationHook() {
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> ReviewChangeKeys.requireCoreHook(ClassLoader.getPlatformClassLoader()));
    assertTrue(failure.getCause() instanceof ClassNotFoundException);
  }

  @Test
  void storageModuleAloneProvidesThePublicationGuardToTheServer() throws Exception {
    var configuration =
        Guice.createInjector(
            binder -> {
              binder
                  .bind(Config.class)
                  .annotatedWith(GerritServerConfig.class)
                  .toInstance(new Config());
              binder.bind(Path.class).annotatedWith(SitePath.class).toInstance(root);
              binder.bind(GerritRuntime.class).toInstance(GerritRuntime.DAEMON);
              binder.bind(MetricMaker.class).toInstance(new DisabledMetricMaker());
            });
    var database = configuration.createChildInjector(new WalGitModule());
    var server = database.createChildInjector();
    var hook =
        server.getInstance(
            Key.get(
                new TypeLiteral<
                    BiFunction<
                        Repository,
                        BiConsumer<BatchUpdateListener, BooleanSupplier>,
                        Optional<BiFunction<String, String, List<Integer>>>>>() {},
                Names.named("reviewChangeKeyLookup")));
    var manager = server.getInstance(GitRepositoryManager.class);
    try (Repository repo = manager.createRepository(PROJECT)) {
      var listener = new AtomicReference<BatchUpdateListener>();
      var lookup = hook.apply(repo, (guard, conflict) -> listener.set(guard)).orElseThrow();
      assertEquals(List.of(), lookup.apply(MAIN, KEY));
      assertNotNull(listener.get());
      var batch = repo.getRefDatabase().newBatchUpdate();
      assertEquals(batch, listener.get().beforeUpdateRefs(batch));
    } finally {
      database.getInstance(ReviewChangeKeys.class).stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusesARepositoryWithoutAStorageGuard(boolean hasObsoleteOptOut) throws Exception {
    Config config = new Config();
    if (hasObsoleteOptOut) {
      config.setBoolean("walgerrit", null, "authoritativeChangeKeys", false);
    }
    var hook = new ReviewChangeKeys(config, node("writer"), new DisabledMetricMaker());
    try (var foreign = new InMemoryRepository(new DfsRepositoryDescription("foreign"))) {
      assertThrows(UncheckedIOException.class, () -> hook.apply(foreign, (guard, conflict) -> {}));
    } finally {
      hook.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rawNumericRefsDoNotBlockPublicationOfARealChange(boolean alreadyCovered) throws Exception {
    var manager = node("a");
    manager.createRepository(PROJECT).close();
    try (var repo = open(manager)) {
      var snapshot = alreadyCovered ? lookup(repo) : null;
      if (snapshot != null) {
        assertEquals(List.of(), snapshot.apply(MAIN, KEY));
      }
      var meta = create(repo, 1, FOOTERS);
      var raw = new ReceiveCommand(ZERO, meta.getNewId(), "refs/changes/01/1/2147483648");
      var oversizedMeta =
          new ReceiveCommand(ZERO, meta.getNewId(), "refs/changes/48/2147483648/meta");
      execute(repo, snapshot, raw, oversizedMeta, meta);
      for (var command : List.of(raw, oversizedMeta, meta)) {
        assertEquals(ReceiveCommand.Result.OK, command.getResult());
        assertEquals(command.getNewId(), repo.exactRef(command.getRefName()).getObjectId());
      }
      assertEquals(alreadyCovered, ChangeKeyDirectory.complete(repo.manifestStore().refresh()));
      assertEquals(List.of(1), lookup(repo).apply(MAIN, KEY));
    }
  }

  @Test
  void twoAbsentLookupsCanPublishOnlyOneCreationAndAnotherPodCanAmendIt() throws Exception {
    WalGitRepositoryManager a = node("a");
    a.createRepository(PROJECT).close();
    try (LocalWalGitRepository first = open(a);
        LocalWalGitRepository second = open(node("b"))) {
      var one = lookup(first);
      var two = lookup(second);
      assertEquals(List.of(), one.apply(MAIN, KEY));
      assertEquals(List.of(), two.apply(MAIN, KEY));
      ReceiveCommand createA = create(first, 101, FOOTERS);
      ReceiveCommand createB = create(second, 102, FOOTERS);
      // An unrelated command in the rejected batch must not be published either.
      ReceiveCommand also = new ReceiveCommand(ZERO, createB.getNewId(), "refs/heads/also");
      execute(first, one, createA);
      var losing = batch(second, two, createB, also);
      assertThrowsExactly(
          GitUpdateFailureException.class, () -> RefUpdateUtil.executeChecked(losing, second));
      assertEquals(ReceiveCommand.Result.OK, createA.getResult());
      assertNotEquals(ReceiveCommand.Result.OK, createB.getResult());
      assertFalse(one.conflicted());
      assertTrue(two.conflicted());
      assertNull(second.exactRef(also.getRefName()));
      assertNull(second.exactRef(createB.getRefName()));
      var fresh = lookup(second);
      assertEquals(List.of(101), fresh.apply(MAIN, KEY));
      ReceiveCommand amend = update(second, 101, createA.getNewId(), "Patch-set: 2");
      execute(second, fresh, amend);
      assertEquals(ReceiveCommand.Result.OK, amend.getResult());
      first.scanForRepoChanges();
      assertEquals(amend.getNewId(), first.exactRef(createA.getRefName()).getObjectId());
      var later = lookup(first);
      assertEquals(List.of(101), later.apply(MAIN, KEY));
      var staleAmend = update(first, 101, createA.getNewId(), "Patch-set: 3");
      var stale = batch(first, later, staleAmend);
      assertThrows(LockFailureException.class, () -> RefUpdateUtil.executeChecked(stale, first));
      assertNotEquals(ReceiveCommand.Result.OK, staleAmend.getResult());
      assertFalse(later.conflicted(), "a stale Git old-id is not a Change-Id membership race");
      assertEquals(amend.getNewId(), first.exactRef(createA.getRefName()).getObjectId());
    }
  }

  @Test
  void samePodGroupingCannotCombineTwoCreationsOfTheSameKey() throws Exception {
    WalGitRepositoryManager a = node("a");
    a.createRepository(PROJECT).close();
    CyclicBarrier bothRead = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      try (var initialize = open(a)) {
        lookup(initialize);
      }
      List<Future<ReceiveCommand.Result>> outcomes = new java.util.ArrayList<>();
      for (int n = 1; n <= 2; n++) {
        int number = n;
        outcomes.add(
            pool.submit(
                () -> {
                  try (var repo = open(a)) {
                    var snapshot = lookup(repo);
                    assertTrue(snapshot.apply(MAIN, KEY).isEmpty());
                    var create = create(repo, number, FOOTERS);
                    bothRead.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    execute(repo, snapshot, create);
                    return create.getResult();
                  }
                }));
      }
      int accepted = 0;
      for (var outcome : outcomes) {
        if (outcome.get() == ReceiveCommand.Result.OK) {
          accepted++;
        }
      }
      assertEquals(1, accepted);
    }
  }

  @Test
  void zeroWaitKeepsOneBackgroundSeedWithoutReturningAnUnguardedLookup() throws Exception {
    var manager = node("a");
    manager.createRepository(PROJECT).close();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var opens = new AtomicInteger();
    var seedManager =
        GerritIndexEventApplierTest.proxy(
            GitRepositoryManager.class,
            (method, args) -> {
              assertEquals("openRepository", method);
              opens.incrementAndGet();
              started.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return manager.openRepository(PROJECT);
              } catch (IOException | InterruptedException failure) {
                throw new AssertionError(failure);
              }
            });
    Config config = new Config();
    config.setString("walgerrit", null, "indexBarrierTimeout", "0");
    var hook = new ReviewChangeKeys(config, seedManager, new DisabledMetricMaker());
    try (var repo = open(manager)) {
      for (int i = 0; i < 2; i++) {
        var failure =
            assertThrows(
                UncheckedIOException.class,
                () ->
                    hook.apply(
                        repo,
                        (guard, conflict) -> {
                          throw new AssertionError("unguarded");
                        }));
        assertTrue(failure.getCause() instanceof ReviewChangeKeys.CoverageUnavailable);
      }
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertEquals(1, opens.get());
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!ChangeKeyDirectory.complete(repo.manifestStore().refresh())) {
        assertTrue(System.nanoTime() < deadline, "seed must finish after its waiters time out");
        Thread.sleep(10);
      }
      assertTrue(hook.apply(repo, (guard, conflict) -> {}).isPresent());
    } finally {
      release.countDown();
      hook.stop();
    }
  }

  @Test
  void firstLookupObtainsACertifiedSnapshotBeforeReturning() throws Exception {
    var manager = node("a");
    manager.createRepository(PROJECT).close();
    var hook = new ReviewChangeKeys(new Config(), manager, new DisabledMetricMaker());
    try (var repo = open(manager)) {
      var existing = create(repo, 1, FOOTERS);
      execute(repo, null, existing);
      assertFalse(ChangeKeyDirectory.complete(repo.manifestStore().refresh()));
      var checks = new AtomicInteger();
      var keys = hook.apply(repo, (guard, conflict) -> checks.incrementAndGet()).orElseThrow();
      assertEquals(1, checks.get());
      assertEquals(List.of(1), keys.apply(MAIN, KEY));
      assertTrue(ChangeKeyDirectory.complete(repo.manifestStore().refresh()));

      var malformed = create(repo, 2, "Patch-set: 1");
      execute(repo, null, malformed);
      var unavailable =
          assertThrows(
              UncheckedIOException.class,
              () -> hook.apply(repo, (guard, conflict) -> checks.incrementAndGet()));
      assertTrue(unavailable.getCause() instanceof ReviewChangeKeys.CoverageUnavailable);
      assertEquals(1, checks.get(), "an uncertified lookup cannot give the caller a write guard");
    } finally {
      hook.stop();
    }
  }

  @Test
  void twoPodsCannotMoveAndCreateIntoTheSameEmptyDestination() throws Exception {
    var a = node("a");
    a.createRepository(PROJECT).close();
    try (var source = open(a);
        var peer = open(node("b"))) {
      var original = create(source, 1, FOOTERS);
      execute(source, null, original);
      var moveRead = lookup(source);
      var createRead = lookup(peer);
      String stable = "refs/heads/stable";
      assertEquals(List.of(), moveRead.apply(stable, KEY));
      assertEquals(List.of(), createRead.apply(stable, KEY));
      var create = create(peer, 2, "Branch: stable\nChange-id: " + KEY);
      execute(peer, createRead, create);
      assertEquals(ReceiveCommand.Result.OK, create.getResult());
      var move = update(source, 1, original.getNewId(), "Branch: stable");
      execute(source, moveRead, move);
      assertTrue(moveRead.conflicted());
      assertNotEquals(ReceiveCommand.Result.OK, move.getResult());
      var actual = lookup(source);
      assertEquals(List.of(1), actual.apply(MAIN, KEY));
      assertEquals(List.of(2), actual.apply(stable, KEY));
      assertEquals(original.getNewId(), source.exactRef(original.getRefName()).getObjectId());
    }
  }

  @Test
  void preservesLegacyDuplicatesAndTracksMovesDeletesAndUnrelatedKeys() throws Exception {
    WalGitRepositoryManager a = node("a");
    a.createRepository(PROJECT).close();
    try (var repo = open(a);
        var other = open(node("b"))) {
      var one = create(repo, 1, FOOTERS);
      var two = create(repo, 2, FOOTERS);
      execute(repo, null, one, two); // Before the directory existed.
      assertFalse(repo.manifestStore().current().hasChangeKeys());
      var pending = lookup(other);
      assertEquals(List.of(1, 2), pending.apply(MAIN, KEY));
      assertEquals(List.of(), pending.apply(MAIN, "Inew"));
      var unrelated = create(repo, 3, "Branch: main\nChange-id: Iother");
      execute(repo, null, unrelated);
      var reserve = create(other, 4, "Branch: main\nChange-id: Inew");
      execute(other, pending, reserve);
      assertEquals(ReceiveCommand.Result.OK, reserve.getResult());
      var stale = lookup(other);
      assertEquals(List.of(1, 2), stale.apply(MAIN, KEY));
      var move = update(repo, 1, one.getNewId(), "Branch: stable");
      execute(repo, null, move);
      var losing = update(other, 2, two.getNewId(), "Patch-set: 2");
      execute(other, stale, losing);
      assertNotEquals(ReceiveCommand.Result.OK, losing.getResult());
      var now = lookup(other);
      assertEquals(List.of(1), now.apply("refs/heads/stable", KEY));
      assertEquals(List.of(2), now.apply(MAIN, KEY));
      var delete = new ReceiveCommand(two.getNewId(), ZERO, two.getRefName());
      execute(other, now, delete);
      assertEquals(ReceiveCommand.Result.OK, delete.getResult());
      assertTrue(lookup(repo).apply(MAIN, KEY).isEmpty());
    }
  }

  @Test
  void seedCannotCertifyAScanOvertakenByARefWrite() throws Exception {
    var a = node("a");
    a.createRepository(PROJECT).close();
    try (var first = open(a);
        var second = open(node("b"))) {
      var base = first.manifestStore().refresh();
      var seed = ChangeKeyDirectory.scan(first, first.manifestStore());
      execute(second, null, create(second, 11, FOOTERS));
      assertThrows(
          ManifestConflictException.class,
          () -> first.manifestStore().installChangeKeys(base.getRefRevision(), seed));
      var afterWrite = first.manifestStore().refresh();
      try (var walk = new RevWalk(first)) {
        assertTrue(
            seed.advanceSeed(
                walk,
                first
                    .manifestStore()
                    .readLogEntriesAfter(
                        base.getHeadSeq(), base.getHeadTransactionId(), afterWrite, 20)));
      }
      first.manifestStore().installChangeKeys(afterWrite.getRefRevision(), seed);
      assertEquals(List.of(11), lookup(first).apply(MAIN, KEY));
    }
    var oldWriter = Manifest.newBuilder().setRefRevision(2);
    oldWriter.getChangeKeysBuilder().setRefRevision(1);
    assertFalse(ChangeKeyDirectory.complete(oldWriter.build()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rawUnguardedUpdateRevokesCoverageAndAWaitingCreationCannotPublish(boolean blob)
      throws Exception {
    var manager = node("a");
    manager.createRepository(PROJECT).close();
    try (var repo = open(manager);
        var peer = open(node("b"))) {
      var snapshot = lookup(peer);
      assertTrue(snapshot.apply(MAIN, KEY).isEmpty());
      ReceiveCommand malformed;
      if (blob) {
        try (var inserter = repo.newObjectInserter()) {
          var object = inserter.insert(Constants.OBJ_BLOB, new byte[] {1});
          inserter.flush();
          malformed = new ReceiveCommand(ZERO, object, RefNames.changeMetaRef(Change.id(1)));
        }
      } else {
        malformed = create(repo, 1, "Patch-set: 1");
      }
      execute(repo, null, malformed);
      assertEquals(ReceiveCommand.Result.OK, malformed.getResult());
      assertFalse(ChangeKeyDirectory.complete(repo.manifestStore().refresh()));
      var pending = create(peer, 2, FOOTERS);
      execute(peer, snapshot, pending);
      assertTrue(snapshot.conflicted());
      assertNotEquals(ReceiveCommand.Result.OK, pending.getResult());
      assertNull(peer.exactRef(pending.getRefName()));
      assertThrows(ChangeKeyDirectory.Unclassified.class, () -> ReviewChangeKeys.open(peer));
    }
  }

  @Test
  void missingCommittedPageCannotAnswerThatAChangeIsAbsent() throws Exception {
    var manager = node("a");
    manager.createRepository(PROJECT).close();
    try (var repo = open(manager)) {
      lookup(repo);
      var store = repo.manifestStore();
      Manifest emptySeed = store.refresh();
      execute(repo, null, create(repo, 1, FOOTERS));
      Manifest.Builder corrupt = store.refresh().toBuilder();
      var pages = corrupt.getChangeKeys().getPagesMap();
      assertEquals(1, pages.size());
      // The entry exists, but it contains no page. A dangling page pointer is a read error.
      corrupt
          .getChangeKeysBuilder()
          .putPages(
              pages.keySet().iterator().next(),
              LogPosition.newBuilder()
                  .setSequence(emptySeed.getHeadSeq())
                  .setTransactionId(emptySeed.getHeadTransactionId())
                  .build());
      var directory = new ChangeKeyDirectory(store, corrupt.build());
      assertThrows(IOException.class, () -> directory.members(ChangeKeyDirectory.key(MAIN, KEY)));
    }
  }

  private WalGitRepositoryManager node(String name) {
    Config cfg = new Config();
    cfg.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    Path dir = root.resolve(name);
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(cfg, dir),
        new StorageLayout(
            new FileObjectStore(root.resolve("shared")),
            dir.resolve("cache"),
            dir.resolve("cursors"),
            ""));
  }

  private static LocalWalGitRepository open(WalGitRepositoryManager node) throws IOException {
    return (LocalWalGitRepository) node.openRepository(PROJECT);
  }

  private static ReviewChangeKeys.Lookup lookup(LocalWalGitRepository repo) throws IOException {
    return ReviewChangeKeys.open(repo).orElseThrow();
  }

  private static void execute(
      Repository repo, ReviewChangeKeys.Lookup lookup, ReceiveCommand... commands)
      throws IOException {
    try (var walk = new RevWalk(repo)) {
      batch(repo, lookup, commands).execute(walk, NullProgressMonitor.INSTANCE);
    }
  }

  private static BatchRefUpdate batch(
      Repository repo, ReviewChangeKeys.Lookup lookup, ReceiveCommand... commands) {
    BatchRefUpdate batch = repo.getRefDatabase().newBatchUpdate();
    batch.setAtomic(true).setAllowNonFastForwards(true).addCommand(commands);
    if (lookup != null) {
      lookup.beforeUpdateRefs(batch);
    }
    return batch;
  }

  private static ReceiveCommand create(Repository repo, int id, String footers) throws IOException {
    return update(repo, id, ZERO, footers);
  }

  private static ReceiveCommand update(Repository repo, int id, ObjectId before, String footers)
      throws IOException {
    try (var inserter = repo.newObjectInserter()) {
      CommitBuilder commit = new CommitBuilder();
      PersonIdent person = new PersonIdent("Test", "test@example.test");
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      if (!before.equals(ZERO)) {
        commit.setParentId(before);
      }
      commit.setAuthor(person);
      commit.setCommitter(person);
      commit.setMessage("Update " + id + "\n\n" + footers + "\n");
      ObjectId after = inserter.insert(commit);
      inserter.flush();
      return new ReceiveCommand(before, after, RefNames.changeMetaRef(Change.id(id)));
    }
  }
}
