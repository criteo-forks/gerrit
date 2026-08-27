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

    Path repositoryPath = storagePath.resolve("repos/platform/example.git");
    Manifest manifest =
        Manifest.parseFrom(Files.readAllBytes(repositoryPath.resolve(ManifestStore.MANIFEST_FILE)));
    assertEquals(3, manifest.getHeadSeq());
    assertEquals(3, manifest.getRevision());
    assertEquals(3, manifest.getLogSegmentsCount());
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
    for (var log : manifest.getLogSegmentsList()) {
      Path logFile = repositoryPath.resolve(log.getKey());
      assertTrue(Files.isRegularFile(logFile));
      LogEntry entry = LogEntry.parseFrom(Files.readAllBytes(logFile));
      assertEquals(log.getFirstSeq(), entry.getSeq());
    }
  }

  @Test
  void repositoryDeletedHookDoesNotEraseDurableWal() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/retained");
    manager.createRepository(project).close();

    manager.repositoryDeleted(project);

    assertEquals(GitRepositoryManager.Status.ACTIVE, manager.getRepositoryStatus(project));
  }

  @Test
  void openRecoversInterruptedRepositoryCreation() throws Exception {
    Project.NameKey project = Project.nameKey("platform/recovered");
    ManifestStore manifestStore = new StorageLayout(storagePath).manifestStore(project);
    assertTrue(manifestStore.create());
    assertEquals(0, manifestStore.read().getRevision());

    try (Repository repository = manager().openRepository(project)) {
      Ref head = repository.exactRef(Constants.HEAD);
      assertTrue(head.isSymbolic());
      assertEquals(Constants.R_HEADS + Constants.MASTER, head.getTarget().getName());
    }

    assertEquals(1, manifestStore.read().getRevision());
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
