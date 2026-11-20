// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import static com.google.gerrit.entities.RefNames.REFS_EXTERNAL_IDS;
import static com.google.gerrit.entities.RefNames.REFS_USERS_DEFAULT;
import static dev.walgerrit.WalGitRepositoryManagerTest.insertCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.account.AccountConfig;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdReader;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.VersionedDefaultPreferences;
import dev.walgerrit.ManifestReadCountTest.CountingObjectStore;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthRefDirectoryTest {
  private static final Project.NameKey USERS = Project.nameKey("Site-Accounts");
  private static final String USER = "refs/users/01/1001";
  private static final String NEIGHBOR = "refs/users/02/1002";
  private static final String OTHER_PAGE = "refs/users/01/2001";
  private static final String ABSENT = "refs/users/05/1005";
  private static final ObjectId ZERO = ObjectId.zeroId();

  @TempDir Path root;

  @Test
  void warmPeerAcrossUnrelatedPublicationsNeverTouchesAReftable() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager writer = node("writer", bucket);
    CountingObjectStore reads = new CountingObjectStore(bucket);
    WalGitRepositoryManager peer = node("peer", reads);
    try (Repository repo = writer.createRepository(USERS)) {
      ObjectId active = insertCommit(repo, "active");
      set(repo, USER, ZERO, active);
      set(repo, REFS_EXTERNAL_IDS, ZERO, active);
      set(repo, REFS_USERS_DEFAULT, ZERO, active);
      String page = manifest(writer).getAuthRefs().getPagesOrThrow(1001 >>> 8);
      try (Repository reader = peer.openRepository(USERS)) {
        assertEquals(active, reader.exactRef(USER).getObjectId());
      }
      reads.reset();
      ObjectId previous = ZERO;
      for (int i = 0; i < 36; i++) {
        switch (i % 3) {
          case 0 -> set(repo, "refs/draft-comments/01/1001/" + i, ZERO, active);
          case 1 -> set(repo, "refs/starred-changes/01/1001/" + i, ZERO, active);
          default -> {
            ObjectId preferences = insertCommit(repo, "other preferences " + i);
            set(repo, OTHER_PAGE, previous, preferences);
            previous = preferences;
          }
        }
        try (Repository reader = peer.openRepository(USERS)) {
          long before = tableRequests();
          assertEquals(active, reader.exactRef(USER).getObjectId());
          assertEquals(active, reader.exactRef(REFS_EXTERNAL_IDS).getObjectId());
          assertEquals(active, reader.exactRef(REFS_USERS_DEFAULT).getObjectId());
          assertNull(reader.exactRef(ABSENT));
          assertNull(reader.exactRef("refs/users/99/9999"));
          assertEquals(before, tableRequests(), "even a cached JGit block would count");
        }
      }
      assertEquals(page, manifest(writer).getAuthRefs().getPagesOrThrow(1001 >>> 8));
      assertEquals(36, reads.repositoryManifestReads());
      assertEquals(0, reads.count("GET auth-page"), "each handle reused the warmed page");
      assertEquals(0, reads.count("DOWNLOAD wal/*.ref"));

      set(repo, NEIGHBOR, ZERO, active);
      try (Repository reader = peer.openRepository(USERS)) {
        long before = tableRequests();
        assertEquals(active, reader.exactRef(USER).getObjectId());
        assertEquals(active, reader.exactRef(NEIGHBOR).getObjectId());
        assertEquals(before, tableRequests());
        assertEquals(1, reads.count("GET auth-page"), "one cold replacement page");
        assertEquals(0, reads.count("DOWNLOAD wal/*.ref"));
      }
    }
  }

  @Test
  void editsAndRemovalsAreVisibleOnRevalidationAndAfterWriterFailover() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager a = node("a", bucket);
    WalGitRepositoryManager b = node("b", bucket);
    try (Repository writer = a.createRepository(USERS);
        Repository reader = b.openRepository(USERS)) {
      AllUsersName name = new AllUsersName(USERS.get());
      String active = "[account]\n  fullName = Alice\n";
      String preferences = "[general]\n  changesPerPage = 50\n";
      ObjectId created = commit(writer, ZERO, Map.of("account.config", active));
      set(writer, USER, ZERO, created);
      reader.scanForRepoChanges();
      AccountConfig account = new AccountConfig(Account.id(1001), name, reader);
      assertEquals("Alice", account.load().getLoadedAccount().orElseThrow().fullName());
      ObjectId personal =
          commit(
              writer, created, Map.of("account.config", active, "preferences.config", preferences));
      set(writer, USER, created, personal);
      reader.scanForRepoChanges();
      assertEquals(
          50,
          account.load().asCachedPreferences().asConfig().getInt("general", "changesPerPage", 0));
      ObjectId inactive =
          commit(
              writer,
              personal,
              Map.of(
                  "account.config",
                  active + "  active = false\n",
                  "preferences.config",
                  preferences));
      set(writer, USER, personal, inactive);
      reader.scanForRepoChanges();
      assertFalse(account.load().getLoadedAccount().orElseThrow().isActive());

      ObjectId defaults = commit(writer, ZERO, Map.of("preferences.config", preferences));
      set(writer, REFS_USERS_DEFAULT, ZERO, defaults);
      ObjectId note = ExternalId.Key.parse("username:alice", false).sha1();
      ObjectId credentials =
          commit(
              writer,
              ZERO,
              Map.of(note.name(), "[externalId \"username:alice\"]\n  accountId = 1001\n"));
      set(writer, REFS_EXTERNAL_IDS, ZERO, credentials);
      reader.scanForRepoChanges();
      try (RevWalk walk = new RevWalk(reader)) {
        assertTrue(
            ExternalIdReader.readNoteMap(walk, ExternalIdReader.readRevision(reader))
                .contains(note));
      }
      ObjectId revoked = commit(writer, credentials, Map.of());
      set(writer, REFS_EXTERNAL_IDS, credentials, revoked);
      assertEquals(credentials, ExternalIdReader.readRevision(reader));
      try (Repository reopened = b.openRepository(USERS);
          RevWalk walk = new RevWalk(reopened)) {
        assertEquals(revoked, ExternalIdReader.readRevision(reopened));
        assertFalse(
            ExternalIdReader.readNoteMap(walk, ExternalIdReader.readRevision(reopened))
                .contains(note));
      }
      assertEquals(revoked, ExternalIdReader.readRevision(reader));
      assertEquals(
          50, VersionedDefaultPreferences.get(reader, name).getInt("general", "changesPerPage", 0));

      ObjectId changed =
          commit(
              reader, defaults, Map.of("preferences.config", "[general]\n  changesPerPage = 25\n"));
      batch(
          reader,
          List.of(
              new ReceiveCommand(defaults, changed, REFS_USERS_DEFAULT),
              new ReceiveCommand(inactive, ZERO, USER),
              new ReceiveCommand(revoked, ZERO, REFS_EXTERNAL_IDS)));
      writer.scanForRepoChanges();
      assertEquals(
          25, VersionedDefaultPreferences.get(writer, name).getInt("general", "changesPerPage", 0));
      assertNull(writer.exactRef(REFS_EXTERNAL_IDS));
      assertNull(writer.exactRef(USER));
      assertEquals(0, manifest(b).getAuthRefs().getPagesCount());
    }
  }

  @Test
  void seededAndUpdatedTipsMatchJGitIncludingTagsAndSymbolicRefs() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager raw = node("raw", bucket, "Other-Accounts");
    WalGitRepositoryManager upgraded = node("upgraded", bucket);
    String malformed = "refs/users/99/1001";
    String oversized = "refs/users/99/9999999999";
    String target = "refs/heads/target";
    try (Repository legacy = raw.createRepository(USERS)) {
      ObjectId commit = insertCommit(legacy, "account data");
      ObjectId tag = tag(legacy, commit);
      batch(
          legacy,
          List.of(
              new ReceiveCommand(ZERO, tag, USER),
              new ReceiveCommand(ZERO, commit, NEIGHBOR),
              new ReceiveCommand(ZERO, tag, REFS_USERS_DEFAULT),
              new ReceiveCommand(ZERO, commit, malformed),
              new ReceiveCommand(ZERO, commit, oversized),
              ReceiveCommand.link(ZERO, target, REFS_EXTERNAL_IDS)));
      assertFalse(manifest(raw).hasAuthRefs());
      try (Repository writer = upgraded.openRepository(USERS)) {
        Ref expected = legacy.exactRef(USER);
        long before = tableRequests();
        compare(expected, writer.exactRef(USER));
        assertTrue(tableRequests() > before);
        set(writer, target, ZERO, commit);
        assertTrue(AuthRefDirectory.complete(manifest(upgraded)));
        legacy.scanForRepoChanges();
        for (String name :
            List.of(
                USER,
                NEIGHBOR,
                REFS_USERS_DEFAULT,
                REFS_EXTERNAL_IDS,
                malformed,
                oversized,
                ABSENT,
                OTHER_PAGE)) {
          compare(legacy.exactRef(name), writer.exactRef(name));
        }
        set(writer, NEIGHBOR, commit, tag);
        set(writer, target, commit, ZERO);
        legacy.scanForRepoChanges();
        compare(legacy.exactRef(NEIGHBOR), writer.exactRef(NEIGHBOR));
        compare(legacy.exactRef(REFS_EXTERNAL_IDS), writer.exactRef(REFS_EXTERNAL_IDS));
      }
    }
  }

  @Test
  void opaqueDirectoryCarriedByARolledBackWriterCannotServeStalePresenceOrAbsence()
      throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager current = node("current", bucket);
    try (Repository reader = current.createRepository(USERS)) {
      ObjectId first = insertCommit(reader, "before rollback");
      set(reader, USER, ZERO, first);
      var opaque = manifest(current).getAuthRefs();
      ObjectStore rollbackStore =
          new CountingObjectStore(bucket) {
            @Override
            public StoredObject compareAndSwap(String key, String version, byte[] bytes)
                throws IOException {
              if (key.endsWith("/manifest.pb") && !key.startsWith("manifests/catalog/")) {
                bytes =
                    Manifest.parseFrom(bytes).toBuilder().setAuthRefs(opaque).build().toByteArray();
              }
              return super.compareAndSwap(key, version, bytes);
            }
          };
      WalGitRepositoryManager old = node("rollback", rollbackStore, "Other-Accounts");
      try (Repository writer = old.openRepository(USERS)) {
        assertEquals(first, writer.exactRef(USER).getObjectId());
        batch(
            writer,
            List.of(
                new ReceiveCommand(first, ZERO, USER), new ReceiveCommand(ZERO, first, ABSENT)));
      }
      reader.scanForRepoChanges();
      assertFalse(AuthRefDirectory.complete(manifest(current)));
      long before = tableRequests();
      assertNull(reader.exactRef(USER));
      assertEquals(first, reader.exactRef(ABSENT).getObjectId());
      assertTrue(tableRequests() > before);
      set(reader, REFS_USERS_DEFAULT, ZERO, first);
      assertTrue(AuthRefDirectory.complete(manifest(current)));
      before = tableRequests();
      assertNull(reader.exactRef(USER));
      assertEquals(first, reader.exactRef(ABSENT).getObjectId());
      assertEquals(before, tableRequests());
    }
  }

  @Test
  void bootstrapLosingItsManifestCasRescansTheWinningAccountUpdate() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager old = node("old", bucket, "Other-Accounts");
    try (Repository legacy = old.createRepository(USERS)) {
      ObjectId first = insertCommit(legacy, "initial account");
      set(legacy, USER, ZERO, first);
      AtomicReference<ObjectId> winner = new AtomicReference<>();
      HookedObjectStore race =
          new HookedObjectStore(
              bucket,
              (base, next) -> !base.hasAuthRefs() && next.hasAuthRefs(),
              () -> {
                try {
                  winner.set(insertCommit(legacy, "concurrent account edit"));
                  set(legacy, USER, first, winner.get());
                } catch (Exception e) {
                  throw new AssertionError(e);
                }
              });
      WalGitRepositoryManager upgraded = node("new", race);
      try (Repository writer = upgraded.openRepository(USERS)) {
        set(writer, NEIGHBOR, ZERO, first);
      }
      assertEquals(2, race.matchedCasAttempts.get());
      assertTrue(AuthRefDirectory.complete(manifest(upgraded)));
      try (Repository reader = node("fresh", bucket).openRepository(USERS)) {
        assertEquals(winner.get(), reader.exactRef(USER).getObjectId());
        assertEquals(first, reader.exactRef(NEIGHBOR).getObjectId());
      }
    }
  }

  @Test
  void missingReferencedPageFallsBackForBothPresentAndAbsentAccounts() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager a = node("a", bucket);
    ObjectId tip;
    try (Repository repo = a.createRepository(USERS)) {
      tip = insertCommit(repo, "account");
      set(repo, USER, ZERO, tip);
    }
    var reads =
        new CountingObjectStore(bucket) {
          @Override
          public Optional<StoredObject> get(String key) throws IOException {
            return key.contains("/log/auth-refs/") ? Optional.empty() : super.get(key);
          }
        };
    WalGitRepositoryManager b = node("b", reads);
    try (Repository reader = b.openRepository(USERS)) {
      long before = tableRequests();
      assertEquals(tip, reader.exactRef(USER).getObjectId());
      assertNull(reader.exactRef(ABSENT));
      assertTrue(tableRequests() > before);
      set(reader, NEIGHBOR, ZERO, tip);
      assertFalse(manifest(b).hasAuthRefs());
    }
    try (Repository writer = a.openRepository(USERS)) {
      set(writer, REFS_USERS_DEFAULT, ZERO, tip);
      assertTrue(AuthRefDirectory.complete(manifest(a)));
      assertEquals(tip, writer.exactRef(NEIGHBOR).getObjectId());
    }
  }

  @Test
  void acceptedGroupUpdatesOneAccountPageOnce() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    ObjectId tip;
    try (Repository repo = node("setup", bucket).createRepository(USERS)) {
      tip = insertCommit(repo, "base");
      set(repo, REFS_EXTERNAL_IDS, ZERO, tip);
    }
    CountDownLatch inCas = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HookedObjectStore held =
        new HookedObjectStore(
            bucket,
            HookedObjectStore::publishesRefChange,
            () -> {
              inCas.countDown();
              try {
                assertTrue(release.await(30, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
              }
            });
    CountingObjectStore writes = new CountingObjectStore(held);
    WalGitRepositoryManager writer = node("writer", writes);
    GroupPublisher publisher = writer.manifestStore(USERS).publisher();
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = tasks.submit(() -> create(writer, REFS_USERS_DEFAULT, tip));
      try {
        assertTrue(inCas.await(30, TimeUnit.SECONDS));
        var a = tasks.submit(() -> create(writer, USER, tip));
        var b = tasks.submit(() -> create(writer, NEIGHBOR, tip));
        long start = System.nanoTime();
        while (publisher.queuedForTesting() < 2
            && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(20)) {
          Thread.sleep(5);
        }
        assertEquals(2, publisher.queuedForTesting());
        release.countDown();
        a.get(30, TimeUnit.SECONDS);
        b.get(30, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
      first.get(30, TimeUnit.SECONDS);
    }
    assertEquals(2, held.matchedCasAttempts.get());
    assertEquals(1, writes.count("PUT-if-absent auth-page"));
    try (Repository reader = node("peer", bucket).openRepository(USERS)) {
      assertEquals(tip, reader.exactRef(USER).getObjectId());
      assertEquals(tip, reader.exactRef(NEIGHBOR).getObjectId());
    }
  }

  private static Void create(WalGitRepositoryManager writer, String name, ObjectId tip)
      throws Exception {
    try (Repository repo = writer.openRepository(USERS)) {
      set(repo, name, ZERO, tip);
    }
    return null;
  }

  @Test
  void pureCompactionCarriesTheSamePagesAndGlobalTips() throws Exception {
    ObjectStore bucket = new FileObjectStore(root.resolve("bucket"));
    WalGitRepositoryManager a = node("a", bucket);
    try (Repository writer = a.createRepository(USERS)) {
      ObjectId tip = insertCommit(writer, "account");
      for (int i = 0; i < 2; i++) {
        List<ReceiveCommand> commands = new ArrayList<>();
        for (int j = 0; j < 128; j++) {
          commands.add(
              new ReceiveCommand(ZERO, tip, "refs/draft-comments/01/1001/" + (128 * i + j)));
        }
        commands.add(new ReceiveCommand(ZERO, tip, i == 0 ? USER : REFS_EXTERNAL_IDS));
        batch(writer, commands);
      }
      Manifest before = manifest(a);
      assertEquals(Compactor.Outcome.COMPACTED, a.compactor().compact(USERS));
      Manifest after = manifest(a);
      assertTrue(after.getRefRevision() > before.getRefRevision());
      assertEquals(
          before.getAuthRefs().toBuilder().setRefRevision(after.getRefRevision()).build(),
          after.getAuthRefs());
      CountingObjectStore reads = new CountingObjectStore(bucket);
      try (Repository reader = node("peer", reads).openRepository(USERS)) {
        long requests = tableRequests();
        assertEquals(tip, reader.exactRef(USER).getObjectId());
        assertEquals(tip, reader.exactRef(REFS_EXTERNAL_IDS).getObjectId());
        assertNull(reader.exactRef(ABSENT));
        assertEquals(requests, tableRequests());
        assertEquals(0, reads.count("DOWNLOAD wal/*.ref"));
      }
    }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    return node(name, store, USERS.get());
  }

  private WalGitRepositoryManager node(String name, ObjectStore store, String allUsers) {
    Config config = new Config();
    config.setString("gerrit", null, "allUsers", allUsers);
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    config.setInt("walgerrit", null, "compactMinReftables", 2);
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }

  private static long tableRequests() {
    return DfsBlockCache.getInstance().getTotalRequestCount()[PackExt.REFTABLE.getPosition()];
  }

  private static Manifest manifest(WalGitRepositoryManager node) throws IOException {
    return node.manifestStore(USERS).refresh();
  }

  private static void set(Repository repo, String ref, ObjectId before, ObjectId after)
      throws Exception {
    batch(repo, List.of(new ReceiveCommand(before, after, ref)));
  }

  private static void batch(Repository repo, List<ReceiveCommand> commands) throws Exception {
    BatchRefUpdate update = repo.getRefDatabase().newBatchUpdate();
    update.setAllowNonFastForwards(true).addCommand(commands);
    try (RevWalk walk = new RevWalk(repo)) {
      update.execute(walk, NullProgressMonitor.INSTANCE);
    }
    for (ReceiveCommand command : commands) {
      assertEquals(ReceiveCommand.Result.OK, command.getResult(), command.getRefName());
    }
  }

  private static ObjectId commit(Repository repo, ObjectId parent, Map<String, String> files)
      throws IOException {
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      TreeFormatter tree = new TreeFormatter();
      for (var file : new TreeMap<>(files).entrySet()) {
        tree.append(
            file.getKey(),
            FileMode.REGULAR_FILE,
            inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
      }
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (!parent.equals(ZERO)) {
        commit.setParentId(parent);
      }
      PersonIdent author = new PersonIdent("Alice", "alice@example.test");
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage("Update All-Users");
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private static ObjectId tag(Repository repo, ObjectId commit) throws IOException {
    TagBuilder tag = new TagBuilder();
    tag.setObjectId(commit, Constants.OBJ_COMMIT);
    tag.setTag("account");
    tag.setTagger(new PersonIdent("Test", "test@example.com"));
    tag.setMessage("tagged account");
    try (ObjectInserter inserter = repo.newObjectInserter()) {
      ObjectId id = inserter.insert(tag);
      inserter.flush();
      return id;
    }
  }

  private static void compare(Ref jgit, Ref actual) {
    if (jgit == null) {
      assertNull(actual);
      return;
    }
    assertNotNull(actual);
    assertEquals(jgit.getName(), actual.getName());
    assertEquals(jgit.getObjectId(), actual.getObjectId());
    assertEquals(jgit.getStorage(), actual.getStorage());
    assertEquals(jgit.isSymbolic(), actual.isSymbolic());
    assertEquals(jgit.getTarget().getName(), actual.getTarget().getName());
    assertEquals(jgit.isPeeled(), actual.isPeeled());
    assertEquals(jgit.getPeeledObjectId(), actual.getPeeledObjectId());
    assertEquals(jgit.getUpdateIndex(), actual.getUpdateIndex());
  }
}
