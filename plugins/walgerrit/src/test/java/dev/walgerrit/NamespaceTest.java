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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.RepositoryExistsException;
import dev.walgerrit.IndexEventApplier.NamespaceChange;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamespaceTest {
  private static final String MAIN = Constants.R_HEADS + "main";
  private static final Project.NameKey NAME = Project.nameKey("platform/service");
  private static final Project.NameKey OTHER = Project.nameKey("platform/other");
  private static final Project.NameKey OLD = Project.nameKey("platform/old");
  private static final Project.NameKey NEW = Project.nameKey("platform/new");
  private static final String UNSUPPORTED =
      "an operation of kind 'rename', which this version of WalGerrit does not run";

  @TempDir Path root;

  @Test
  void deleteRetiresTheNameForGoodAndKeepsTheRepositoryUnderItsId() throws Exception {
    WalGitRepositoryManager node = node("a");
    ObjectId commit;
    try (Repository repository = node.createRepository(NAME)) {
      commit = publish(repository, "kept under the id");
    }
    RepositoryId id = node.idOf(NAME);
    assertFalse(node.namespace().deleted(NAME));
    try (Repository before = node.openRepository(NAME)) {
      node.namespace().delete(NAME, false);

      assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(NAME));
      RepositoryNotFoundException gone =
          assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(NAME));
      assertTrue(gone.getMessage().contains("was deleted"), gone.getMessage());
      RepositoryExistsException retired =
          assertThrows(RepositoryExistsException.class, () -> node.createRepository(NAME));
      assertTrue(retired.getMessage().contains("never reused"), retired.getMessage());
      assertTrue(node.list().isEmpty());
      assertTrue(node.namespace().pending().isEmpty());

      ObjectId late = WalGitRepositoryManagerTest.insertCommit(before, "into a deleted repository");
      RefUpdate update = before.updateRef(Constants.R_HEADS + "late");
      update.setNewObjectId(late);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update.update());
    }
    Manifest manifest = node.storage().manifestStore(id).refresh();
    assertTrue(manifest.getDeleted());
    assertEquals(1, manifest.getWriteEpoch(), "the fence advanced the epoch once");
    assertFalse(manifest.getFenceOperation().isEmpty());
    try (Repository byId = node.openById(id, NAME, ManifestStore.UNFENCED)) {
      assertEquals(commit, byId.exactRef(MAIN).getObjectId(), "the data is retained");
    }
    assertThrows(RepositoryNotFoundException.class, () -> node.idOf(NAME));
    assertTrue(node.namespace().deleted(NAME));
    assertFalse(node.namespace().deleted(Project.nameKey("platform/unknown")));

    // Another node with cold caches resolves through the same catalog.
    assertThrows(RepositoryNotFoundException.class, () -> node("b").openRepository(NAME));
    assertThrows(RepositoryExistsException.class, () -> node("c").createRepository(NAME));
  }

  @Test
  void namesThatArePrefixesOfEachOtherCoexist() throws Exception {
    WalGitRepositoryManager node = node("a");
    Project.NameKey team = Project.nameKey("team");
    Project.NameKey service = Project.nameKey("team/service");
    node.createRepository(team).close();
    node.createRepository(service).close();
    assertEquals(Set.of(team, service), node.list());

    node.namespace().delete(team, false);

    assertEquals(Set.of(service), node.list());
    node.openRepository(service).close();
  }

  @Test
  void deleteRefusesNamesThatAreUnknownOrNeededByGerritAndCanBeRepeated() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(NAME).close();

    IOException missing =
        assertThrows(IOException.class, () -> node.namespace().delete(Project.nameKey("x"), false));
    assertTrue(missing.getMessage().contains("No project x"), missing.getMessage());

    Project.NameKey allUsers = Project.nameKey("All-Users");
    node.createRepository(allUsers).close();
    IOException system =
        assertThrows(IOException.class, () -> node.namespace().delete(allUsers, false));
    assertTrue(system.getMessage().contains("system project"), system.getMessage());
    node.openRepository(allUsers).close();
    assertTrue(node.namespace().pending().isEmpty(), "refusals leave nothing in flight");

    node.namespace().delete(NAME, false);
    node.namespace().delete(NAME, false); // Deleted already: nothing to do.
    assertEquals(1, node.storage().manifestStore(retiredId(node, NAME)).refresh().getWriteEpoch());
  }

  @Test
  void anOperationInterruptedAfterPrepareOrAfterFenceIsFinishedByResume() throws Exception {
    WalGitRepositoryManager node = node("a");
    ObjectId commit;
    try (Repository repository = node.createRepository(NAME)) {
      commit = publish(repository, "content");
    }
    RepositoryId id = node.idOf(NAME);
    prepareDelete(node, "op-prepare", NAME);

    assertEquals(Set.of("op-prepare"), node.namespace().pending());
    RepositoryNotFoundException pending =
        assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(NAME));
    assertTrue(pending.getMessage().contains("is being deleted"), pending.getMessage());
    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(NAME));
    assertFalse(node.storage().manifestStore(id).refresh().getDeleted());
    try (Repository byId = node.openById(id, NAME, ManifestStore.UNFENCED)) {
      assertEquals(commit, byId.exactRef(MAIN).getObjectId(), "the data waits under its id");
    }

    // Another node finishes it, from the prepare.
    node("b").namespace().resume("op-prepare");
    assertTrue(node.namespace().deleted(NAME));
    assertTrue(node.storage().manifestStore(id).refresh().getDeleted());
    assertEquals(1, node.storage().manifestStore(id).refresh().getWriteEpoch());
    assertTrue(node.namespace().pending().isEmpty());

    // The same, interrupted after the fence this time.
    node.createRepository(OTHER).close();
    RepositoryId other = node.idOf(OTHER);
    prepareDelete(node, "op-fence", OTHER);
    node.storage().manifestStore(other).fence(0, "op-fence", true);
    node.namespace().resume("op-fence");
    assertTrue(node.namespace().deleted(OTHER));
    assertEquals(1, node.storage().manifestStore(other).refresh().getWriteEpoch(), "fenced once");

    node.namespace().resume("op-fence"); // Finalized already: nothing to do.
    IOException unknown =
        assertThrows(IOException.class, () -> node.namespace().resume("op-never"));
    assertTrue(unknown.getMessage().contains("No namespace operation"), unknown.getMessage());
  }

  @Test
  void concurrentCreationOfOneNameYieldsOneRepository() throws Exception {
    Project.NameKey name = Project.nameKey("platform/contended");
    List<WalGitRepositoryManager> nodes = List.of(node("a"), node("b"), node("c"));
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger created = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(nodes.size());
    try {
      List<Future<Void>> futures =
          nodes.stream()
              .map(
                  node ->
                      pool.<Void>submit(
                          () -> {
                            go.await();
                            try {
                              node.createRepository(name).close();
                              created.incrementAndGet();
                            } catch (RepositoryExistsException exists) {
                              assertTrue(exists.getMessage().contains("exists"), exists.getMessage());
                            }
                            return null;
                          }))
              .toList();
      go.countDown();
      for (Future<Void> future : futures) {
        future.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertTrue(created.get() >= 1, "the reservation's CAS admits one creator, who may be joined");
    RepositoryId id = nodes.get(0).idOf(name);
    for (WalGitRepositoryManager node : nodes) {
      assertEquals(id, node.idOf(name));
      node.openRepository(name).close();
    }
    assertEquals(
        2,
        nodes.get(0).storage().listRepositories().size(),
        "the repository and the catalog; the losers reserved nothing");
    assertTrue(nodes.get(0).namespace().pending().isEmpty());
  }

  @Test
  void oneNodesConcurrentCreationsTakeTurnsOnTheCatalog() throws Exception {
    WalGitRepositoryManager node = node("a");
    int threads = 8;
    int perThread = 8;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        String prefix = "platform/t" + t + "-";
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  for (int i = 0; i < perThread; i++) {
                    node.createRepository(Project.nameKey(prefix + i)).close();
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<Void> future : futures) {
        future.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(threads * perThread, node.list().size());
    assertEquals(0, node.catalog().lostRaces(), "no commit of this node lost to another of its own");
  }

  @Test
  void theSnapshotACommitLeavesIsTheOneTheStoreServes() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(NAME).close();
    node.createRepository(OTHER).close();
    node.namespace().delete(OTHER, false);

    assertEquals(node("b").catalog().snapshot(true), node.catalog().snapshot(false));
  }

  @Test
  void theTailerHandsTouchedNamesToTheApplierAndReplaysOnlyAnActiveName() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(NAME).close();
    RepositoryId id = node.idOf(NAME);
    RecordingApplier applier = new RecordingApplier();
    IndexEventTailer tailer = new IndexEventTailer(node, applier, GerritRuntime.DAEMON);
    tailer.runOnce();
    assertEquals(
        List.of(new NamespaceChange(NAME, id, Catalog.State.ACTIVE)), applier.namespaceChanges);
    applier.clear();

    try (Repository repository = node.openRepository(NAME)) {
      publish(repository, "under an active name");
    }
    tailer.runOnce();
    assertEquals(List.of(NAME), applier.projects);
    applier.clear();

    try (Repository admittedBefore = node.openRepository(NAME)) {
      prepareDelete(node, "op-tail", NAME);
      publish(admittedBefore, "written while the deletion is in flight");
    }
    assertEquals(0, tailer.catchUp(id), "an id whose name is in flight waits");
    tailer.catchUp(RepositoryId.CATALOG);
    assertEquals(
        List.of(new NamespaceChange(NAME, id, Catalog.State.PENDING)), applier.namespaceChanges);
    applier.clear();

    node.namespace().resume("op-tail");
    tailer.runOnce();
    assertEquals(
        List.of(new NamespaceChange(NAME, id, Catalog.State.RETIRED)), applier.namespaceChanges);
    assertTrue(applier.projects.isEmpty(), "the in-flight write is passed over, not indexed");
    assertEquals(0, tailer.catchUp(id), "nothing of the deleted repository is left to replay");
  }

  @Test
  void theProgramDeletesListsPendingOperationsAndResumesThem() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(NAME).close();
    node.createRepository(OTHER).close();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    assertEquals(0, NamespaceProgram.run(node, List.of("delete", NAME.get()), printer));
    assertEquals("Deleted platform/service\n", out.toString(StandardCharsets.UTF_8));
    assertEquals(Set.of(OTHER), node.list());

    prepareDelete(node, "op-cli", OTHER);
    out.reset();
    assertEquals(0, NamespaceProgram.run(node, List.of("pending"), printer));
    assertEquals("op-cli  platform/other is being deleted\n", out.toString(StandardCharsets.UTF_8));

    out.reset();
    assertEquals(0, NamespaceProgram.run(node, List.of("resume", "op-cli"), printer));
    assertEquals("Finished op-cli\n", out.toString(StandardCharsets.UTF_8));
    assertTrue(node.list().isEmpty());

    out.reset();
    assertEquals(0, NamespaceProgram.run(node, List.of("pending"), printer));
    assertEquals("", out.toString(StandardCharsets.UTF_8));

    assertEquals(2, NamespaceProgram.run(node, List.of(), printer));
    assertThrows(
        IllegalArgumentException.class, () -> NamespaceProgram.run(node, List.of("delete"), printer));
    assertThrows(
        IllegalArgumentException.class, () -> NamespaceProgram.run(node, List.of("frobnicate"), printer));
  }

  @Test
  void namesAnotherVersionMovedARepositoryBetweenAreServedAsTheCatalogRecordsThem()
      throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(OLD).close();
    RepositoryId id = node.idOf(OLD);
    String retired = foreignBinding(OLD, id, "retired", 1, "op-moved", NEW, true);
    ObjectId fresh;
    try (Repository before = node.openRepository(OLD)) {
      ObjectId first = publish(before, "admitted under epoch 0");
      node.storage().manifestStore(id).fence(0, "op-moved", false);
      writeBinding(node, OLD, retired);
      writeBinding(node, NEW, foreignBinding(NEW, id, "active", 1, "op-moved", OLD, false));
      long headAfterFence = node.storage().manifestStore(id).refresh().getHeadSeq();

      // A writer admitted before the fence cannot publish after it.
      ObjectId stale = WalGitRepositoryManagerTest.insertCommit(before, "after the fence");
      RefUpdate update = before.updateRef(Constants.R_HEADS + "stale");
      update.setNewObjectId(stale);
      RefUpdate.Result result = update.update();
      assertTrue(
          result != RefUpdate.Result.NEW
              && result != RefUpdate.Result.FAST_FORWARD
              && result != RefUpdate.Result.FORCED,
          "a fenced handle cannot publish: " + result);
      assertEquals(
          headAfterFence,
          node.storage().manifestStore(id).refresh().getHeadSeq(),
          "no ref update landed");

      // The name the repository is active under serves it, at the epoch the fence left.
      assertEquals(Set.of(NEW), node.list());
      assertEquals(id, node.idOf(NEW));
      try (Repository repository = node.openRepository(NEW)) {
        assertEquals(first, repository.exactRef(MAIN).getObjectId());
        fresh = publish(repository, "admitted under epoch 1");
      }
    }
    // Closing the fenced handle may publish the objects it deferred, never its refs.
    try (Repository repository = node.openRepository(NEW)) {
      assertEquals(fresh, repository.exactRef(MAIN).getObjectId());
      assertNull(repository.exactRef(Constants.R_HEADS + "stale"));
    }
    assertTrue(node.namespace().pending().isEmpty());

    // The name it left is retired like any other, and is not a deleted one.
    RepositoryNotFoundException gone =
        assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(OLD));
    assertTrue(gone.getMessage().contains("is retired; a name is never reused"), gone.getMessage());
    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(OLD));
    assertThrows(RepositoryExistsException.class, () -> node.createRepository(OLD));
    assertFalse(node.namespace().deleted(OLD));
    IOException notActive = assertThrows(IOException.class, () -> node.namespace().delete(OLD, false));
    assertTrue(notActive.getMessage().contains("is retired"), notActive.getMessage());

    // Indexes follow the active name.
    RecordingApplier applier = new RecordingApplier();
    new IndexEventTailer(node("b"), applier, GerritRuntime.DAEMON).runOnce();
    assertTrue(
        applier.namespaceChanges.containsAll(
            List.of(
                new NamespaceChange(NEW, id, Catalog.State.ACTIVE),
                new NamespaceChange(OLD, id, Catalog.State.RETIRED))),
        applier.namespaceChanges.toString());
    assertEquals(Set.of(NEW), Set.copyOf(applier.projects));

    // Deleting the active name leaves the retired name's binding as it was stored.
    node.namespace().delete(NEW, false);
    assertTrue(node.namespace().deleted(NEW));
    assertTrue(node.list().isEmpty());
    Manifest manifest = node.storage().manifestStore(id).refresh();
    assertTrue(manifest.getDeleted());
    assertEquals(2, manifest.getWriteEpoch());
    assertEquals(retired, readBinding(node, OLD));
  }

  @Test
  void anOperationOfAKindThisVersionDoesNotRunIsListedNotServedAndNotResumed() throws Exception {
    WalGitRepositoryManager node = node("a");
    ObjectId commit;
    try (Repository repository = node.createRepository(OLD)) {
      commit = publish(repository, "content");
    }
    node.createRepository(OTHER).close();
    RepositoryId id = node.idOf(OLD);
    String source = foreignBinding(OLD, id, "pending", 0, "op-held", NEW, true);
    String destination = foreignBinding(NEW, id, "pending", 1, "op-held", OLD, false);
    writeBinding(node, OLD, source);
    writeBinding(node, NEW, destination);
    ObjectId catalogTip = node.catalog().snapshot(true).tip();

    assertEquals(Set.of("op-held"), node.namespace().pending());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    NamespaceProgram.run(node, List.of("pending"), new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(
        "op-held  platform/new is held by " + UNSUPPORTED + "\n"
            + "op-held  platform/old is held by " + UNSUPPORTED + "\n",
        out.toString(StandardCharsets.UTF_8));

    // Neither name is served, created over or deleted.
    assertEquals(Set.of(OTHER), node.list());
    for (Project.NameKey name : List.of(OLD, NEW)) {
      RepositoryNotFoundException held =
          assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(name));
      assertTrue(held.getMessage().contains("is held by " + UNSUPPORTED), held.getMessage());
      assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(name));
      assertThrows(RepositoryExistsException.class, () -> node.createRepository(name));
      assertThrows(IOException.class, () -> node.namespace().delete(name, false));
    }

    // It is not finished here, and holds back the deletions that could run into it.
    IOException refused = assertThrows(IOException.class, () -> node.namespace().resume("op-held"));
    assertTrue(
        refused.getMessage().contains("op-held is " + UNSUPPORTED + "; finish it with a version"),
        refused.getMessage());
    IOException busy = assertThrows(IOException.class, () -> node.namespace().delete(OTHER, false));
    assertTrue(busy.getMessage().contains("op-held is in flight"), busy.getMessage());
    assertEquals(catalogTip, node.catalog().snapshot(true).tip(), "no refusal wrote a binding");
    node.createRepository(Project.nameKey("platform/created-meanwhile")).close();
    RecordingApplier applier = new RecordingApplier();
    IndexEventTailer tailer = new IndexEventTailer(node, applier, GerritRuntime.DAEMON);
    tailer.runOnce();
    assertEquals(0, tailer.catchUp(id), "an id whose names are in flight waits");
    assertFalse(applier.projects.contains(OLD) || applier.projects.contains(NEW));

    // Nothing of it changed.
    assertEquals(source, readBinding(node, OLD));
    assertEquals(destination, readBinding(node, NEW));
    assertEquals(0, node.storage().manifestStore(id).refresh().getWriteEpoch());
    try (Repository byId = node.openById(id, OLD, ManifestStore.UNFENCED)) {
      assertEquals(commit, byId.exactRef(MAIN).getObjectId(), "the data waits under its id");
    }
    assertEquals(Set.of("op-held"), node.namespace().pending());

    // Once a version that runs the operation finished it, the names are served as it left them.
    node.storage().manifestStore(id).fence(0, "op-held", false);
    writeBinding(node, OLD, foreignBinding(OLD, id, "retired", 1, "op-held", NEW, true));
    writeBinding(node, NEW, foreignBinding(NEW, id, "active", 1, "op-held", OLD, false));
    assertTrue(node.namespace().pending().isEmpty());
    try (Repository repository = node.openRepository(NEW)) {
      assertEquals(commit, repository.exactRef(MAIN).getObjectId());
    }
    assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(OLD));
    tailer.runOnce();
    assertTrue(applier.projects.contains(NEW), "the entries that waited are indexed, not skipped");
    assertFalse(applier.projects.contains(OLD));
    node.namespace().delete(OTHER, false);
  }

  /** The first transition of a deletion, as {@link Namespace#delete} makes it, and nothing more. */
  static void prepareDelete(WalGitRepositoryManager node, String operation, Project.NameKey name)
      throws IOException {
    node.catalog()
        .commit(
            "Delete " + name.get(),
            snapshot -> {
              Catalog.Binding active = snapshot.byName().get(name);
              return List.of(
                  active.with(
                      Catalog.State.PENDING,
                      active.epoch(),
                      new Catalog.Operation(
                          operation, Catalog.Kind.DELETE, active.epoch(), active.epoch() + 1, false),
                      1));
            });
  }

  /**
   * A binding as a version of WalGerrit that moves a repository from one name to another stores
   * it: the name the repository leaves records where it goes, and each name records the other.
   */
  private static String foreignBinding(
      Project.NameKey name,
      RepositoryId id,
      String state,
      long epoch,
      String operation,
      Project.NameKey other,
      boolean leaves) {
    return "[binding]\n"
        + ("\tname = " + name.get() + "\n")
        + ("\tid = " + id.value() + "\n")
        + ("\tstate = " + state + "\n")
        + ("\tepoch = " + epoch + "\n")
        + "\tsince = 1\n"
        + (leaves ? "\tmovedTo = " + other.get() + "\n" : "")
        + "[operation]\n"
        + ("\tid = " + operation + "\n")
        + "\tkind = rename\n"
        + "\texpectedEpoch = 0\n"
        + "\ttargetEpoch = 1\n"
        + ("\tother = " + other.get() + "\n");
  }

  /** Stores {@code text} as the binding of {@code name}, past the catalog's own serialization. */
  private static void writeBinding(WalGitRepositoryManager node, Project.NameKey name, String text)
      throws IOException {
    try (Repository catalog = catalog(node)) {
      NameReferencesTest.file(catalog, Catalog.REF, Catalog.leaf(name), text);
    }
  }

  private static String readBinding(WalGitRepositoryManager node, Project.NameKey name)
      throws IOException {
    try (Repository catalog = catalog(node);
        ObjectReader reader = catalog.newObjectReader();
        RevWalk walk = new RevWalk(reader);
        TreeWalk tree =
            TreeWalk.forPath(
                reader,
                Catalog.leaf(name),
                walk.parseCommit(catalog.exactRef(Catalog.REF).getObjectId()).getTree())) {
      return new String(reader.open(tree.getObjectId(0)).getCachedBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Repository catalog(WalGitRepositoryManager node) throws IOException {
    return node.openById(
        RepositoryId.CATALOG, Project.nameKey(RepositoryId.CATALOG.value()), ManifestStore.UNFENCED);
  }

  private static RepositoryId retiredId(WalGitRepositoryManager node, Project.NameKey name)
      throws IOException {
    return node.catalog().resolve(name, true).orElseThrow().id();
  }

  private WalGitRepositoryManager node(String name) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("shared-store").toString());
    config.setString(
        "walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve(name)));
  }

  private static ObjectId publish(Repository repository, String message) throws Exception {
    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
    RefUpdate update = repository.updateRef(MAIN);
    update.setNewObjectId(commit);
    update.setForceUpdate(true);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED, result.name());
    return commit;
  }

  private static final class RecordingApplier implements IndexEventApplier {
    final List<Project.NameKey> projects = new CopyOnWriteArrayList<>();
    final List<NamespaceChange> namespaceChanges = new CopyOnWriteArrayList<>();

    @Override
    public void apply(Project.NameKey project, RefTransaction transaction) {
      projects.add(project);
    }

    @Override
    public void namespaceChanged(List<NamespaceChange> changes) {
      namespaceChanges.addAll(changes);
    }

    void clear() {
      projects.clear();
      namespaceChanges.clear();
    }
  }
}
