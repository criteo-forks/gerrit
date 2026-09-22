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
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamespaceTest {
  private static final String MAIN = Constants.R_HEADS + "main";
  private static final Project.NameKey OLD = Project.nameKey("platform/old");
  private static final Project.NameKey NEW = Project.nameKey("platform/new");

  @TempDir Path root;

  @Test
  void renameServesTheSameRepositoryUnderTheNewNameOnly() throws Exception {
    WalGitRepositoryManager node = node("a");
    ObjectId commit;
    try (Repository repository = node.createRepository(OLD)) {
      commit = publish(repository, "before the rename");
    }
    RepositoryId id = node.idOf(OLD);

    node.namespace().rename(OLD, NEW);

    assertEquals(id, node.idOf(NEW), "the repository kept its id");
    try (Repository renamed = node.openRepository(NEW)) {
      assertEquals(commit, renamed.exactRef(MAIN).getObjectId());
    }
    RepositoryNotFoundException gone =
        assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(OLD));
    assertTrue(gone.getMessage().contains("was renamed to platform/new"), gone.getMessage());
    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(OLD));
    assertEquals(GitRepositoryManager.Status.ACTIVE, node.getRepositoryStatus(NEW));
    assertEquals(Set.of(NEW), node.list());
    Manifest manifest = node.storage().manifestStore(id).refresh();
    assertEquals(1, manifest.getWriteEpoch(), "the fence advanced the epoch once");
    assertFalse(manifest.getFenceOperation().isEmpty());
    assertFalse(manifest.getDeleted());
    assertTrue(node.namespace().pending().isEmpty());

    // Another node with cold caches resolves through the same catalog.
    try (Repository elsewhere = node("b").openRepository(NEW)) {
      assertEquals(commit, elsewhere.exactRef(MAIN).getObjectId());
    }
    assertThrows(RepositoryNotFoundException.class, () -> node("c").openRepository(OLD));
  }

  @Test
  void aWriterAdmittedBeforeARenameCannotPublishAfterIt() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(OLD).close();
    ObjectId fresh;
    try (Repository before = node.openRepository(OLD)) {
      ObjectId first = publish(before, "admitted under epoch 0");

      node.namespace().rename(OLD, NEW);
      long headAfterRename = node.storage().manifestStore(node.idOf(NEW)).refresh().getHeadSeq();

      ObjectId stale = WalGitRepositoryManagerTest.insertCommit(before, "after the fence");
      RefUpdate update = before.updateRef(Constants.R_HEADS + "stale");
      update.setNewObjectId(stale);
      RefUpdate.Result result = update.update();
      assertTrue(
          result != RefUpdate.Result.NEW
              && result != RefUpdate.Result.FAST_FORWARD
              && result != RefUpdate.Result.FORCED,
          "a fenced handle cannot publish: " + result);
      Manifest manifest = node.storage().manifestStore(node.idOf(NEW)).refresh();
      assertEquals(headAfterRename, manifest.getHeadSeq(), "no ref update landed");

      try (Repository after = node.openRepository(NEW)) {
        assertEquals(first, after.exactRef(MAIN).getObjectId());
        fresh = publish(after, "admitted under epoch 1");
        assertEquals(fresh, after.exactRef(MAIN).getObjectId());
      }
    }
    // Closing the fenced handle may publish the objects it deferred, never its refs.
    try (Repository after = node.openRepository(NEW)) {
      assertEquals(fresh, after.exactRef(MAIN).getObjectId());
      assertNull(after.exactRef(Constants.R_HEADS + "stale"));
    }
  }

  @Test
  void deleteRetiresTheNameForGoodAndKeepsTheRepositoryUnderItsId() throws Exception {
    WalGitRepositoryManager node = node("a");
    ObjectId commit;
    try (Repository repository = node.createRepository(OLD)) {
      commit = publish(repository, "kept under the id");
    }
    RepositoryId id = node.idOf(OLD);
    try (Repository before = node.openRepository(OLD)) {
      node.namespace().delete(OLD);

      assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(OLD));
      RepositoryNotFoundException gone =
          assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(OLD));
      assertTrue(gone.getMessage().contains("was deleted"), gone.getMessage());
      assertThrows(RepositoryExistsException.class, () -> node.createRepository(OLD));
      assertTrue(node.list().isEmpty());

      ObjectId late = WalGitRepositoryManagerTest.insertCommit(before, "into a deleted repository");
      RefUpdate update = before.updateRef(Constants.R_HEADS + "late");
      update.setNewObjectId(late);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update.update());
    }
    Manifest manifest = node.storage().manifestStore(id).refresh();
    assertTrue(manifest.getDeleted());
    assertEquals(1, manifest.getWriteEpoch());
    try (Repository byId = node.openById(id, OLD, ManifestStore.UNFENCED)) {
      assertEquals(commit, byId.exactRef(MAIN).getObjectId(), "the data is retained");
    }
    assertThrows(RepositoryNotFoundException.class, () -> node.idOf(OLD));
  }

  @Test
  void namesThatArePrefixesOfEachOtherCoexist() throws Exception {
    WalGitRepositoryManager node = node("a");
    Project.NameKey team = Project.nameKey("team");
    Project.NameKey service = Project.nameKey("team/service");
    node.createRepository(team).close();
    node.createRepository(service).close();
    assertEquals(Set.of(team, service), node.list());

    node.namespace().rename(team, Project.nameKey("org"));

    assertEquals(Set.of(Project.nameKey("org"), service), node.list());
    node.openRepository(service).close();
  }

  @Test
  void renameRefusesNamesThatAreTakenOrWereEverUsed() throws Exception {
    WalGitRepositoryManager node = node("a");
    Project.NameKey a = Project.nameKey("a");
    Project.NameKey b = Project.nameKey("b");
    node.createRepository(a).close();
    node.createRepository(b).close();

    IOException taken = assertThrows(IOException.class, () -> node.namespace().rename(a, b));
    assertTrue(taken.getMessage().contains("b exists"), taken.getMessage());

    node.namespace().delete(b);
    IOException retired = assertThrows(IOException.class, () -> node.namespace().rename(a, b));
    assertTrue(retired.getMessage().contains("never reused"), retired.getMessage());
    assertThrows(RepositoryExistsException.class, () -> node.createRepository(b));

    IOException missing =
        assertThrows(IOException.class, () -> node.namespace().rename(Project.nameKey("x"), a));
    assertTrue(missing.getMessage().contains("No project x"), missing.getMessage());

    Project.NameKey allUsers = Project.nameKey("All-Users");
    node.createRepository(allUsers).close();
    IOException system =
        assertThrows(IOException.class, () -> node.namespace().rename(allUsers, Project.nameKey("y")));
    assertTrue(system.getMessage().contains("system project"), system.getMessage());
    assertThrows(IOException.class, () -> node.namespace().delete(allUsers));
    node.openRepository(allUsers).close();
    assertTrue(node.namespace().pending().isEmpty(), "refusals leave nothing in flight");
  }

  @Test
  void anOperationInterruptedAfterPrepareOrAfterFenceIsFinishedByResume() throws Exception {
    WalGitRepositoryManager node = node("a");
    ObjectId commit;
    try (Repository repository = node.createRepository(OLD)) {
      commit = publish(repository, "content");
    }
    RepositoryId id = node.idOf(OLD);
    prepareRename(node, "op-prepare", OLD, NEW);

    assertEquals(Set.of("op-prepare"), node.namespace().pending());
    RepositoryNotFoundException source =
        assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(OLD));
    assertTrue(source.getMessage().contains("is being renamed to platform/new"), source.getMessage());
    RepositoryNotFoundException destination =
        assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(NEW));
    assertTrue(
        destination.getMessage().contains("is being created by renaming"), destination.getMessage());
    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(OLD));
    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, node.getRepositoryStatus(NEW));
    try (Repository byId = node.openById(id, OLD, ManifestStore.UNFENCED)) {
      assertEquals(commit, byId.exactRef(MAIN).getObjectId(), "the data waits under its id");
    }

    // Another node finishes it, from the prepare.
    node("b").namespace().resume("op-prepare");
    assertEquals(id, node.idOf(NEW));
    assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(OLD));
    assertEquals(1, node.storage().manifestStore(id).refresh().getWriteEpoch());
    assertTrue(node.namespace().pending().isEmpty());

    // The same, interrupted after the fence this time.
    Project.NameKey third = Project.nameKey("platform/third");
    prepareRename(node, "op-fence", NEW, third);
    node.storage().manifestStore(id).fence(1, "op-fence", false);
    node.namespace().resume("op-fence");
    assertEquals(id, node.idOf(third));
    assertEquals(2, node.storage().manifestStore(id).refresh().getWriteEpoch(), "fenced once");

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
  void theTailerHandsTouchedNamesToTheApplierAndReplaysUnderTheCurrentName() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(OLD).close();
    RepositoryId id = node.idOf(OLD);
    RecordingApplier applier = new RecordingApplier();
    IndexEventTailer tailer = new IndexEventTailer(node, applier, GerritRuntime.DAEMON);
    tailer.runOnce();
    applier.clear();

    try (Repository admittedBefore = node.openRepository(OLD)) {
      prepareRename(node, "op-tail", OLD, NEW);
      publish(admittedBefore, "written while the rename is in flight");
    }
    assertEquals(0, tailer.catchUp(id), "an id whose name is in flight waits");
    tailer.catchUp(RepositoryId.CATALOG);
    assertEquals(
        List.of(pending(NEW, id), pending(OLD, id)), applier.namespaceChanges, "both names pending");
    applier.clear();

    node.namespace().resume("op-tail");
    tailer.runOnce();
    assertEquals(
        List.of(
            new NamespaceChange(NEW, id, Catalog.State.ACTIVE, null),
            new NamespaceChange(OLD, id, Catalog.State.RETIRED, NEW)),
        applier.namespaceChanges);
    assertEquals(List.of(NEW), applier.projects, "the in-flight write is indexed under the new name");
    applier.clear();

    try (Repository repository = node.openRepository(NEW)) {
      publish(repository, "after the rename");
    }
    tailer.runOnce();
    assertEquals(List.of(NEW), applier.projects);

    node.namespace().delete(NEW);
    tailer.runOnce();
    assertTrue(
        applier.namespaceChanges.contains(new NamespaceChange(NEW, id, Catalog.State.RETIRED, null)),
        applier.namespaceChanges.toString());
  }

  @Test
  void theProgramRenamesListsPendingOperationsAndResumesThem() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(OLD).close();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8);

    assertEquals(0, NamespaceProgram.run(node, List.of("rename", OLD.get(), NEW.get()), printer));
    assertEquals("Renamed platform/old to platform/new\n", out.toString(StandardCharsets.UTF_8));
    node.openRepository(NEW).close();

    prepareRename(node, "op-cli", NEW, Project.nameKey("platform/third"));
    out.reset();
    assertEquals(0, NamespaceProgram.run(node, List.of("pending"), printer));
    assertEquals(
        """
        op-cli  platform/new is being renamed to platform/third
        op-cli  platform/third is being created by renaming platform/new
        """,
        out.toString(StandardCharsets.UTF_8));

    out.reset();
    assertEquals(0, NamespaceProgram.run(node, List.of("resume", "op-cli"), printer));
    assertEquals("Finished op-cli\n", out.toString(StandardCharsets.UTF_8));
    node.openRepository(Project.nameKey("platform/third")).close();

    out.reset();
    assertEquals(0, NamespaceProgram.run(node, List.of("delete", "platform/third"), printer));
    assertEquals("Deleted platform/third\n", out.toString(StandardCharsets.UTF_8));
    assertTrue(node.list().isEmpty());

    assertEquals(2, NamespaceProgram.run(node, List.of(), printer));
    assertThrows(
        IllegalArgumentException.class,
        () -> NamespaceProgram.run(node, List.of("rename", "only-one"), printer));
    assertThrows(
        IllegalArgumentException.class, () -> NamespaceProgram.run(node, List.of("frobnicate"), printer));
  }

  private static NamespaceChange pending(Project.NameKey name, RepositoryId id) {
    return new NamespaceChange(name, id, Catalog.State.PENDING, name.equals(OLD) ? NEW : null);
  }

  /** The first transition of a rename, as {@link Namespace#rename} makes it, and nothing more. */
  private static void prepareRename(
      WalGitRepositoryManager node, String operation, Project.NameKey from, Project.NameKey to)
      throws IOException {
    node.catalog()
        .commit(
            "Rename " + from.get() + " to " + to.get(),
            snapshot -> {
              Catalog.Binding source = snapshot.byName().get(from);
              long target = source.epoch() + 1;
              return List.of(
                  source.with(
                      Catalog.State.PENDING,
                      source.epoch(),
                      new Catalog.Operation(operation, Catalog.Kind.RENAME, source.epoch(), target, to),
                      to,
                      1),
                  new Catalog.Binding(
                      to,
                      source.id(),
                      Catalog.State.PENDING,
                      target,
                      new Catalog.Operation(operation, Catalog.Kind.RENAME, source.epoch(), target, from),
                      null,
                      1));
            });
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
