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
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The journal keeps events whose publication failed and settles publications it lost track of. */
class WalJournalTest {
  private static final Project.NameKey PROJECT = Project.nameKey("platform/journal");
  @TempDir Path root;

  @Test
  void eventsWhoseLogWriteFailedAreJournaledLaterInFiringOrder() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    AtomicInteger failLogWrites = new AtomicInteger();
    ObjectStore flaky =
        intercept(
            shared,
            (method, args) -> {
              if (method.equals("putIfAbsent")
                  && ((String) args[0]).contains("/log/")
                  && failLogWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IOException("store unavailable");
              }
            });
    WalGitRepositoryManager node = node("a", flaky);
    node.createRepository(PROJECT).close();
    ManifestStore store = node.manifestStore(PROJECT);
    Manifest before = store.read();
    WalJournal journal = journal(node);
    journal.start();
    try {
      failLogWrites.set(2);
      journal.onEvent(created("first"));
      journal.flush();
      journal.onEvent(created("second"));
      journal.flush();
      journal.flush();
      assertEquals(0, failLogWrites.get(), "both failures were consumed");
    } finally {
      journal.stop();
    }
    assertEquals(List.of("first", "second"), journaledProjects(store, before));
  }

  @Test
  void aPublicationWhoseResponseWasLostIsSettledFromTheLogAndNotJournaledTwice() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicBoolean failNextManifestRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            WalJournalTest::publishesJournal,
            () -> {},
            () -> {
              failNextManifestRead.set(true);
              throw new IOException("lost CAS response");
            });
    ObjectStore flaky =
        intercept(
            hooked,
            (method, args) -> {
              if ((method.equals("get") || method.equals("getIfChanged"))
                  && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
                  && failNextManifestRead.compareAndSet(true, false)) {
                throw new IOException("verification read failed too");
              }
            });
    WalGitRepositoryManager node = node("a", flaky);
    ManifestStore store = node.manifestStore(PROJECT);
    Manifest before = store.read();
    WalJournal journal = journal(node);
    journal.start();
    try {
      journal.onEvent(created("landed"));
      journal.flush();
      journal.flush();
    } finally {
      journal.stop();
    }
    assertEquals(1, hooked.matchedCasAttempts.get(), "the lost response was for the first CAS");
    assertEquals(List.of("landed"), journaledProjects(store, before));
  }

  @Test
  void aPublicationThatDidNotLandIsRetriedOnce() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicBoolean failNextManifestRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            WalJournalTest::publishesJournal,
            () -> {
              failNextManifestRead.set(true);
              throw new IllegalStateException("connection reset before the CAS was sent");
            });
    ObjectStore flaky =
        intercept(
            hooked,
            (method, args) -> {
              if ((method.equals("get") || method.equals("getIfChanged"))
                  && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
                  && failNextManifestRead.compareAndSet(true, false)) {
                throw new IOException("verification read failed too");
              }
            });
    WalGitRepositoryManager node = node("a", flaky);
    ManifestStore store = node.manifestStore(PROJECT);
    Manifest before = store.read();
    WalJournal journal = journal(node);
    journal.start();
    try {
      journal.onEvent(created("retried"));
      journal.flush();
      journal.flush();
    } finally {
      journal.stop();
    }
    assertEquals(List.of("retried"), journaledProjects(store, before));
  }

  @Test
  void eventsFiredDuringAnIndexRebuildAreJournaledButItsReindexedDocumentsAreNot() throws Exception {
    WalGitRepositoryManager node = node("a", new FileObjectStore(root.resolve("store")));
    node.createRepository(PROJECT).close();
    ManifestStore store = node.manifestStore(PROJECT);
    Manifest before = store.read();
    WalJournal journal = journal(node);
    journal.start();
    try {
      try (EventReplay.Scope rebuilding = EventReplay.enterEverywhere()) {
        journal.onChangeIndexed(PROJECT.get(), 7);
        journal.onEvent(created("during-rebuild"));
      }
      try (EventReplay.Scope replaying = EventReplay.enter()) {
        journal.onEvent(created("replayed"));
      }
      journal.flush();
    } finally {
      journal.stop();
    }
    assertEquals(List.of("during-rebuild"), journaledProjects(store, before));
    for (LogEntry entry :
        store.readLogEntriesAfter(
            before.getHeadSeq(), before.getHeadTransactionId(), store.refresh(), 100)) {
      assertTrue(!entry.hasIndexUpdate(), "the rebuild's own index writes are not journaled");
    }
  }

  /** A journal CAS: the head advances with neither packs nor refs changing. */
  private static boolean publishesJournal(Manifest current, Manifest proposed) {
    return proposed.getHeadSeq() == current.getHeadSeq() + 1
        && proposed.getRefRevision() == current.getRefRevision()
        && proposed.getPacksCount() == current.getPacksCount()
        && proposed.getWriteEpoch() == current.getWriteEpoch();
  }

  private static ProjectCreatedEvent created(String marker) {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.projectName = PROJECT.get();
    event.headName = marker;
    return event;
  }

  /** The headName of every project-created event journaled after {@code before}, in log order. */
  private static List<String> journaledProjects(ManifestStore store, Manifest before)
      throws IOException {
    List<String> markers = new ArrayList<>();
    for (LogEntry entry :
        store.readLogEntriesAfter(
            before.getHeadSeq(), before.getHeadTransactionId(), store.refresh(), 100)) {
      for (String json : entry.getEventJsonList()) {
        markers.add(
            com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("headName").getAsString());
      }
    }
    assertTrue(markers.size() <= 2);
    return markers;
  }

  private WalJournal journal(WalGitRepositoryManager node) {
    return new WalJournal(
        node, new AllProjectsName("All-Projects"), new AllUsersName("All-Users"), GerritRuntime.DAEMON);
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }

  @FunctionalInterface
  private interface Interceptor {
    void before(String method, Object[] args) throws IOException;
  }

  private static ObjectStore intercept(ObjectStore delegate, Interceptor interceptor) {
    return (ObjectStore)
        Proxy.newProxyInstance(
            ObjectStore.class.getClassLoader(),
            new Class<?>[] {ObjectStore.class},
            (proxy, method, args) -> {
              interceptor.before(method.getName(), args);
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
