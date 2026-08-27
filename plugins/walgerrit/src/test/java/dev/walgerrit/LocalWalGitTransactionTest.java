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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWalGitTransactionTest {
  @TempDir Path storagePath;

  @Test
  void batchRefUpdatePublishesOneAtomicManifestGeneration() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/atomic");
    ObjectId commit;
    long sequenceBefore;

    try (Repository repository = manager.createRepository(project)) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "atomic");
      sequenceBefore = manifest(project).getHeadSeq();

      ReceiveCommand first =
          new ReceiveCommand(ObjectId.zeroId(), commit, Constants.R_HEADS + "first");
      ReceiveCommand second =
          new ReceiveCommand(ObjectId.zeroId(), commit, Constants.R_HEADS + "second");
      BatchRefUpdate update = repository.getRefDatabase().newBatchUpdate();
      update.setAtomic(true).addCommand(List.of(first, second));
      try (RevWalk walk = new RevWalk(repository)) {
        update.execute(walk, NullProgressMonitor.INSTANCE);
      }

      assertEquals(ReceiveCommand.Result.OK, first.getResult());
      assertEquals(ReceiveCommand.Result.OK, second.getResult());
    }

    Manifest manifest = manifest(project);
    assertEquals(sequenceBefore + 1, manifest.getHeadSeq());
    var lastLog = manifest.getLogSegments(manifest.getLogSegmentsCount() - 1);
    LogEntry entry =
        LogEntry.parseFrom(
            Files.readAllBytes(repositoryPath(project).resolve(lastLog.getKey())));
    assertEquals(LogEntry.Kind.REF_UPDATE, entry.getKind());
    assertEquals(1, entry.getAdditionsCount());

    try (Repository reopened = manager.openRepository(project)) {
      assertEquals(commit, reopened.exactRef(Constants.R_HEADS + "first").getObjectId());
      assertEquals(commit, reopened.exactRef(Constants.R_HEADS + "second").getObjectId());
    }
  }

  @Test
  void repositoryRevalidatesRefsBeforeEveryUpdate() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/concurrent");
    ObjectId commit;
    try (Repository repository = manager.createRepository(project)) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "shared");
    }

    try (Repository first = manager.openRepository(project);
        Repository stale = manager.openRepository(project)) {
      first.getRefDatabase().getRefs();
      stale.getRefDatabase().getRefs();
      long baseRefRevision = manifest(project).getRefRevision();

      RefUpdate winning = first.updateRef(Constants.R_HEADS + "winner");
      winning.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, winning.update());

      RefUpdate losing = stale.updateRef(Constants.R_HEADS + "retry");
      losing.setNewObjectId(commit);
      assertEquals(RefUpdate.Result.NEW, losing.update());
      assertEquals(baseRefRevision + 2, manifest(project).getRefRevision());
    }
  }

  @Test
  void staleExpectedOldValueFailsWithoutChangingTheWinningRef()
      throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/expected-old");
    ObjectId original;
    ObjectId winner;
    ObjectId loser;
    try (Repository repository = manager.createRepository(project)) {
      original = WalGitRepositoryManagerTest.insertCommit(repository, "original");
      winner = WalGitRepositoryManagerTest.insertCommit(repository, "winner");
      loser = WalGitRepositoryManagerTest.insertCommit(repository, "loser");
      RefUpdate create = repository.updateRef(Constants.R_HEADS + "main");
      create.setNewObjectId(original);
      assertEquals(RefUpdate.Result.NEW, create.update());
    }

    try (Repository first = manager.openRepository(project);
        Repository stale = manager.openRepository(project)) {
      RefUpdate winning = first.updateRef(Constants.R_HEADS + "main");
      winning.setExpectedOldObjectId(original);
      winning.setNewObjectId(winner);
      winning.setForceUpdate(true);

      RefUpdate losing = stale.updateRef(Constants.R_HEADS + "main");
      losing.setExpectedOldObjectId(original);
      losing.setNewObjectId(loser);
      losing.setForceUpdate(true);

      assertEquals(RefUpdate.Result.FORCED, winning.update());
      assertEquals(RefUpdate.Result.LOCK_FAILURE, losing.update());
    }

    try (Repository reopened = manager.openRepository(project)) {
      assertEquals(winner, reopened.exactRef(Constants.R_HEADS + "main").getObjectId());
    }
  }

  @Test
  void atomicBatchPublishesNothingWhenOneExpectedValueIsStale()
      throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/atomic-conflict");
    ObjectId original;
    ObjectId winner;
    ObjectId other;
    try (Repository repository = manager.createRepository(project)) {
      original = WalGitRepositoryManagerTest.insertCommit(repository, "original");
      winner = WalGitRepositoryManagerTest.insertCommit(repository, "winner");
      other = WalGitRepositoryManagerTest.insertCommit(repository, "other");
      RefUpdate create = repository.updateRef(Constants.R_HEADS + "main");
      create.setNewObjectId(original);
      assertEquals(RefUpdate.Result.NEW, create.update());
    }

    try (Repository first = manager.openRepository(project);
        Repository stale = manager.openRepository(project)) {
      stale.exactRef(Constants.R_HEADS + "main");

      RefUpdate winning = first.updateRef(Constants.R_HEADS + "main");
      winning.setExpectedOldObjectId(original);
      winning.setNewObjectId(winner);
      winning.setForceUpdate(true);
      assertEquals(RefUpdate.Result.FORCED, winning.update());

      ReceiveCommand staleMain =
          new ReceiveCommand(original, other, Constants.R_HEADS + "main");
      ReceiveCommand mustNotAppear =
          new ReceiveCommand(
              ObjectId.zeroId(), other, Constants.R_HEADS + "must-not-appear");
      BatchRefUpdate update = stale.getRefDatabase().newBatchUpdate();
      update.setAtomic(true);
      update.setAllowNonFastForwards(true);
      update.addCommand(List.of(staleMain, mustNotAppear));
      try (RevWalk walk = new RevWalk(stale)) {
        update.execute(walk, NullProgressMonitor.INSTANCE);
      }

      assertEquals(ReceiveCommand.Result.LOCK_FAILURE, staleMain.getResult());
      assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, mustNotAppear.getResult());
    }

    try (Repository reopened = manager.openRepository(project)) {
      assertEquals(winner, reopened.exactRef(Constants.R_HEADS + "main").getObjectId());
      assertNull(reopened.exactRef(Constants.R_HEADS + "must-not-appear"));
    }
  }

  @Test
  void namespaceRaceCannotCreateConflictingRefs() throws Exception {
    WalGitRepositoryManager manager = manager();
    Project.NameKey project = Project.nameKey("platform/namespace");
    ObjectId commit;
    try (Repository repository = manager.createRepository(project)) {
      commit = WalGitRepositoryManagerTest.insertCommit(repository, "namespace");
    }

    try (Repository first = manager.openRepository(project);
        Repository stale = manager.openRepository(project)) {
      RefUpdate parent = first.updateRef(Constants.R_HEADS + "topic");
      parent.setNewObjectId(commit);
      RefUpdate child = stale.updateRef(Constants.R_HEADS + "topic/child");
      child.setNewObjectId(commit);

      assertEquals(RefUpdate.Result.NEW, parent.update());
      IOException error = assertThrows(IOException.class, child::update);
      assertEquals("transaction aborted", error.getMessage());
    }

    try (Repository reopened = manager.openRepository(project)) {
      assertEquals(commit, reopened.exactRef(Constants.R_HEADS + "topic").getObjectId());
      assertNull(reopened.exactRef(Constants.R_HEADS + "topic/child"));
    }
  }

  private WalGitRepositoryManager manager() {
    return new WalGitRepositoryManager(new WalGitConfiguration(BackendType.LOCAL, storagePath));
  }

  private Manifest manifest(Project.NameKey project) throws Exception {
    Path path = repositoryPath(project).resolve(ManifestStore.MANIFEST_FILE);
    return Manifest.parseFrom(Files.readAllBytes(path));
  }

  private Path repositoryPath(Project.NameKey project) {
    return storagePath.resolve("repos").resolve(project.get() + ".git");
  }
}
