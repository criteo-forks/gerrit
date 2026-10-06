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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.EventLog.Cursor;
import dev.walgerrit.EventLog.Entries;
import dev.walgerrit.EventLog.Head;
import dev.walgerrit.EventLog.Position;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The journaled events read as a log per repository, with shared cursors and a lease. */
class WalEventLogTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/log");
  private static final String READER = "kafka-gerrit-events";
  @TempDir Path root;

  @Test
  void listsProjectRepositoriesWithTheirManifestVersionsAndNotTheCatalog() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("a", shared);
    node.createRepository(PROJECT).close();
    node.createRepository(Project.nameKey("platform/other")).close();
    WalEventLog log = new WalEventLog(node, java.time.Clock.systemUTC());

    var repositories = log.repositories();
    assertEquals(2, repositories.size());
    assertFalse(repositories.containsKey(RepositoryId.CATALOG.value()));
    String id = node.idOf(PROJECT).value();
    String before = repositories.get(id);
    node.manifestStore(PROJECT).publishEvents(List.of(event("one")));
    assertNotEquals(before, log.repositories().get(id), "a publication changes the version");
    assertEquals(log.head(id).version(), log.repositories().get(id));
  }

  @Test
  void readsOnlyTheEntriesThatCarryEventsInOrderUpToTheHead() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("a", shared);
    node.createRepository(PROJECT).close();
    String id = node.idOf(PROJECT).value();
    WalEventLog log = new WalEventLog(node, java.time.Clock.systemUTC());
    ManifestStore store = node.manifestStore(PROJECT);
    Head start = log.head(id);

    store.publishEvents(List.of(event("one"), event("two")));
    push(node, "refs/heads/a"); // a ref transaction in between carries no events
    store.publishEvents(List.of(event("three")));

    Entries entries = log.readAfter(id, start.position());
    assertEquals(Optional.of(PROJECT.get()), entries.projectName());
    assertEquals(2, entries.entries().size());
    assertEquals(List.of(event("one"), event("two")), entries.entries().get(0).events());
    assertEquals(List.of(event("three")), entries.entries().get(1).events());
    assertTrue(
        entries.entries().get(0).position().sequence()
            < entries.entries().get(1).position().sequence());
    assertTrue(entries.entries().get(0).writer().contains(":"), "host:pid");
    Head head = log.head(id);
    assertEquals(head, entries.head());
    assertEquals(
        entries.entries().get(1).position(), head.position(), "the last entry is the head");

    assertTrue(log.readAfter(id, head.position()).entries().isEmpty(), "nothing after the head");
    Entries fromMiddle = log.readAfter(id, entries.entries().get(0).position());
    assertEquals(List.of(event("three")), fromMiddle.entries().get(0).events());
    assertEquals(1, fromMiddle.entries().size());
  }

  @Test
  void aNodeReadsWhatAnotherNodeJournaled() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager writer = node("a", shared);
    writer.createRepository(PROJECT).close();
    String id = writer.idOf(PROJECT).value();
    writer.manifestStore(PROJECT).publishEvents(List.of(event("from-a")));

    WalEventLog readerLog = new WalEventLog(node("b", shared), java.time.Clock.systemUTC());
    Entries entries = readerLog.readAfter(id, Position.START);
    assertEquals(List.of(event("from-a")), entries.entries().get(0).events());
  }

  @Test
  void namesARepositoryAnotherNodeCreatedSinceThisNodeLastReadTheCatalog() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager reader = node("a", shared);
    reader.createRepository(PROJECT).close();
    WalEventLog log = new WalEventLog(reader, java.time.Clock.systemUTC());
    assertEquals(1, reader.list().size(), "this node has read the catalog");

    WalGitRepositoryManager writer = node("b", shared);
    Project.NameKey created = Project.nameKey("platform/created-elsewhere");
    writer.createRepository(created).close();
    writer.manifestStore(created).publishEvents(List.of(event("elsewhere")));
    String id = writer.idOf(created).value();

    assertEquals(Optional.of(created.get()), log.readAfter(id, Position.START).projectName());
  }

  @Test
  void aPositionOutsideTheHistoryIsRefused() throws Exception {
    WalGitRepositoryManager node = node("a", new FileObjectStore(root.resolve("store")));
    node.createRepository(PROJECT).close();
    String id = node.idOf(PROJECT).value();
    WalEventLog log = new WalEventLog(node, java.time.Clock.systemUTC());
    Head head = log.head(id);

    assertThrows(
        EventLog.HistoryChangedException.class,
        () -> log.readAfter(id, new Position(head.position().sequence() + 5, "ahead")));
    assertThrows(
        EventLog.HistoryChangedException.class,
        () -> log.readAfter(id, new Position(head.position().sequence(), "not-this-transaction")));
  }

  @Test
  void cursorsAreCreatedOnceAndMovedOnlyFromTheVersionLastRead() throws Exception {
    WalGitRepositoryManager node = node("a", new FileObjectStore(root.resolve("store")));
    node.createRepository(PROJECT).close();
    String id = node.idOf(PROJECT).value();
    WalEventLog log = new WalEventLog(node, java.time.Clock.systemUTC());
    Head head = log.head(id);

    assertTrue(log.cursor(READER, id).isEmpty());
    assertTrue(log.cursors(READER).isEmpty());
    Cursor first = log.saveCursor(READER, id, Position.START, "", Optional.empty());
    assertThrows(
        EventLog.CursorConflictException.class,
        () -> log.saveCursor(READER, id, Position.START, "", Optional.empty()),
        "a second creation loses");

    Cursor moved = log.saveCursor(READER, id, head.position(), head.version(), Optional.of(first));
    assertEquals(Optional.of(moved), log.cursor(READER, id));
    assertEquals(head.version(), log.cursor(READER, id).orElseThrow().headVersion());
    assertThrows(
        EventLog.CursorConflictException.class,
        () -> log.saveCursor(READER, id, Position.START, "", Optional.of(first)),
        "a holder that read the old cursor cannot move it back");

    assertEquals(List.of(id), List.copyOf(log.cursors(READER).keySet()));
    assertEquals(moved.storeVersion(), log.cursors(READER).get(id));
    assertTrue(log.cursors("another-reader").isEmpty(), "readers do not share cursors");
  }

  @Test
  void oneNodeHoldsAReadersLeaseUntilItExpiresOrIsReleased() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalEventLog a = new WalEventLog(node("a", shared), java.time.Clock.systemUTC());
    SteppingClock clock = new SteppingClock(Instant.now());
    WalEventLog b = new WalEventLog(node("b", shared), clock);
    Duration term = Duration.ofSeconds(30);

    EventLog.Lease held = a.acquireLease(READER, term).orElseThrow();
    assertTrue(b.acquireLease(READER, term).isEmpty(), "an unexpired lease is not taken over");
    assertTrue(b.leaseOwner(READER).orElseThrow().contains(":"));
    assertTrue(b.acquireLease("another-reader", term).isPresent(), "leases are per reader");

    held.close();
    assertTrue(b.leaseOwner(READER).isEmpty(), "a released lease has no owner");
    EventLog.Lease taken = b.acquireLease(READER, term).orElseThrow();
    taken.renew(term);
    taken.close();
  }

  @Test
  void announcesThisNodesPublicationsUntilTheListenerIsRemoved() throws Exception {
    WalGitRepositoryManager node = node("a", new FileObjectStore(root.resolve("store")));
    node.createRepository(PROJECT).close();
    String id = node.idOf(PROJECT).value();
    WalEventLog log = new WalEventLog(node, java.time.Clock.systemUTC());
    List<String> announced = new ArrayList<>();
    EventLog.Registration registration = log.onChange(announced::add);

    node.manifestStore(PROJECT).publishEvents(List.of(event("one")));
    assertEquals(List.of(id), announced);
    push(node, "refs/heads/b");
    assertEquals(List.of(id, id), announced, "every publication, events or not");

    registration.close();
    registration.close();
    node.manifestStore(PROJECT).publishEvents(List.of(event("two")));
    assertEquals(2, announced.size());
  }

  @Test
  void readerNamesAndIdsAreValidated() throws Exception {
    WalEventLog log =
        new WalEventLog(node("a", new FileObjectStore(root.resolve("store"))), java.time.Clock.systemUTC());
    assertThrows(IllegalArgumentException.class, () -> log.cursors("Kafka"));
    assertThrows(IllegalArgumentException.class, () -> log.cursors("a/b"));
    assertThrows(IllegalArgumentException.class, () -> log.cursor(READER, "catalog"));
    assertThrows(IllegalArgumentException.class, () -> new Position(-1, ""));
    assertThrows(IllegalArgumentException.class, () -> new Position(0, "x"));
  }

  private static String event(String marker) {
    return "{\"type\":\"comment-added\",\"marker\":\"" + marker + "\"}";
  }

  private static void push(WalGitRepositoryManager node, String ref) throws Exception {
    try (Repository repository = node.openRepository(PROJECT)) {
      RefUpdate update = repository.updateRef(ref);
      update.setNewObjectId(WalGitRepositoryManagerTest.insertCommit(repository, ref));
      assertEquals(RefUpdate.Result.NEW, update.update());
    }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }
}
