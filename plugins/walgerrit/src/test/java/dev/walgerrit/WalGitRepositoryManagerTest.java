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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.RepositoryExistsException;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalGitRepositoryManagerTest {
  @TempDir Path storagePath;

  @Test
  void createsListsReopensAndReadsRepositoryData() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/example");

    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, manager.getRepositoryStatus(project));
    assertThrows(RepositoryNotFoundException.class, () -> manager.openRepository(project));

    ObjectId commit;
    try (Repository repository = manager.createRepository(project)) {
      Ref head = repository.exactRef(Constants.HEAD);
      assertTrue(head.isSymbolic());
      assertEquals(Constants.R_HEADS + Constants.MASTER, head.getTarget().getName());

      commit = insertCommit(repository, "initial");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    assertEquals(GitRepositoryManager.Status.ACTIVE, manager.getRepositoryStatus(project));
    assertEquals(Project.nameKey("platform/example"), manager.list().first());
    assertThrows(RepositoryExistsException.class, () -> manager.createRepository(project));
    assertFalse(manager.canPerformGC());

    try (Repository reopened = manager.openRepository(project)) {
      assertEquals(commit, reopened.exactRef(Constants.R_HEADS + "main").getObjectId());
      assertNotNull(reopened.open(commit, Constants.OBJ_COMMIT));
    }

    RepositoryId repositoryId = manager.idOf(project);
    Path repositoryPath = storagePath.resolve("repos").resolve(repositoryId.value());
    Path manifestPath =
        storagePath
            .resolve("manifests")
            .resolve(repositoryId.value())
            .resolve(ManifestStore.MANIFEST_FILE);
    Manifest manifest = Manifest.parseFrom(Files.readAllBytes(manifestPath));
    assertEquals(2, manifest.getHeadSeq(), "creation, then one entry for the pack and its ref");
    assertEquals(2, manifest.getRevision());
    assertTrue(manifest.getPacksCount() >= 2);
    for (PackRef pack : manifest.getPacksList()) {
      for (var file : pack.getFilesList()) {
        Path immutable =
            repositoryPath
                .resolve("wal")
                .resolve(pack.getName() + "." + file.getExtension());
        assertTrue(Files.isRegularFile(immutable));
      }
    }
    // The log is a chain from the head: every key is known without a listing.
    String id = manifest.getHeadTransactionId();
    for (long seq = manifest.getHeadSeq(); seq >= 1; seq--) {
      Path logFile = repositoryPath.resolve(ManifestStore.logKey(seq, id));
      assertTrue(Files.isRegularFile(logFile));
      LogEntry entry = LogEntry.parseFrom(Files.readAllBytes(logFile));
      assertEquals(seq, entry.getSeq());
      id = entry.getPreviousTransactionId();
    }
    assertEquals("", id, "the chain ends at the first entry");
  }

  @Test
  void repositoryDeletedHookDoesNotEraseDurableWal() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/retained");
    ObjectId commit;
    try (Repository repository = manager.createRepository(project)) {
      commit = insertCommit(repository, "retained");
      RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }

    manager.repositoryDeleted(project);

    assertEquals(GitRepositoryManager.Status.ACTIVE, manager.getRepositoryStatus(project));
    try (Repository repository = manager.openRepository(project)) {
      assertEquals(commit, repository.exactRef(Constants.R_HEADS + "main").getObjectId());
      assertEquals(Constants.OBJ_COMMIT, repository.open(commit).getType());
    }
  }

  @Test
  void createFinishesACreationThatDiedBeforeActivatingTheName() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/recovered");
    // A process death after the name was reserved and the manifest created, before HEAD and the
    // activation: the name is not served, and the next creation finishes under the reserved id.
    RepositoryId reserved = RepositoryId.random();
    manager
        .catalog()
        .commit(
            "Create platform/recovered",
            ignored ->
                java.util.List.of(
                    new Catalog.Binding(
                        project,
                        reserved,
                        Catalog.State.PENDING,
                        0,
                        new Catalog.Operation("op-1", Catalog.Kind.CREATE, 0, 0, null, false),
                        null,
                        0)));
    ManifestStore manifestStore = manager.storage().manifestStore(reserved);
    assertTrue(manifestStore.create());
    assertEquals(GitRepositoryManager.Status.NON_EXISTENT, manager.getRepositoryStatus(project));
    assertThrows(RepositoryNotFoundException.class, () -> manager.openRepository(project));
    assertEquals(java.util.Set.of("op-1"), manager.namespace().pending());

    try (Repository repository = manager.createRepository(project)) {
      Ref head = repository.exactRef(Constants.HEAD);
      assertTrue(head.isSymbolic());
      assertEquals(Constants.R_HEADS + Constants.MASTER, head.getTarget().getName());
    }

    assertEquals(reserved, manager.idOf(project), "the reservation was finished, not replaced");
    assertEquals(1, manifestStore.read().getRevision());
    assertEquals(GitRepositoryManager.Status.ACTIVE, manager.getRepositoryStatus(project));
    assertTrue(manager.namespace().pending().isEmpty());
  }

  private WalGitRepositoryManager manager() {
    return new WalGitRepositoryManager(new WalGitConfiguration(BackendType.LOCAL, storagePath));
  }

  static ObjectId insertCommit(Repository repository, String message) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, (message + "\n").getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("README.md", FileMode.REGULAR_FILE, blob);
      ObjectId treeId = inserter.insert(tree);

      PersonIdent author = new PersonIdent("WalGerrit Test", "walgerrit@example.test");
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }
}
