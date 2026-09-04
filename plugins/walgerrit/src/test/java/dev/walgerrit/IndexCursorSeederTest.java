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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.GerritRuntime;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexCursorSeederTest {
  @TempDir Path root;

  @Test
  void aFreshNodeReplaysOnlyWhatFollowsTheSeededHead() throws Exception {
    // An imported repository written to afterwards: the case that made a daemon rebuild everything.
    Path source = root.resolve("basePath");
    try (Repository bare =
        Git.init().setBare(true).setDirectory(source.resolve("imported.git").toFile()).call()
            .getRepository()) {
      publish(bare, Constants.R_HEADS + "main", "imported history");
      GC gc = new GC((FileRepository) bare);
      gc.setExpireAgeMillis(0);
      gc.gc().get();
    }
    WalGitRepositoryManager writer = node("writer");
    Project.NameKey project = Project.nameKey("imported");
    assertTrue(
        new RepositoryImporter(writer, new PrintStream(System.out), false)
            .importAll(source, Set.of(), 1)
            .ok());
    try (Repository repository = writer.openRepository(project)) {
      for (int i = 0; i < 4; i++) {
        publish(repository, Constants.R_HEADS + "after-import-" + i, "written after the import");
      }
    }

    // A node whose replay limit these writes exceed: without a cursor it would rebuild everything.
    WalGitRepositoryManager fresh = node("fresh", 2);
    IndexEventTailer tailer =
        new IndexEventTailer(fresh, (ignoredProject, transaction) -> {}, GerritRuntime.DAEMON);
    assertThrows(
        IndexRebuildRequiredException.class,
        () -> tailer.catchUp(project),
        "without a cursor the log is further behind than the node may replay");

    assertEquals(1, new IndexCursorSeeder(fresh, new PrintStream(System.out)).seedAll());

    Manifest head = fresh.storage().manifestStore(project).read();
    IndexCursor cursor =
        new IndexCursorStore(fresh.storage().manifestStore(project).indexCursorPath()).read();
    assertEquals(head.getHeadSeq(), cursor.getSequence());
    assertEquals(head.getHeadTransactionId(), cursor.getTransactionId());
    assertEquals(
        fresh.storage().listManifestVersions().get(project),
        cursor.getManifestVersion(),
        "the cursor names the manifest version it is at, so a sweep can skip the read");
    assertEquals(0, tailer.catchUp(project), "nothing to replay at the seeded head");

    try (Repository repository = writer.openRepository(project)) {
      publish(repository, Constants.R_HEADS + "later", "published after seeding");
    }
    assertEquals(1, tailer.catchUp(project), "only what follows the seed is replayed");
    assertEquals(0, IndexCursorSeeder.run(fresh, new String[0]));
    assertThrows(
        IllegalArgumentException.class, () -> IndexCursorSeeder.run(fresh, new String[] {"--x"}));
  }

  private static ObjectId publish(Repository repository, String ref, String message)
      throws Exception {
    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
    RefUpdate update = repository.updateRef(ref);
    update.setNewObjectId(commit);
    update.setForceUpdate(true);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED, result.name());
    return commit;
  }

  private WalGitRepositoryManager node(String name) {
    return node(name, WalGitConfiguration.DEFAULT_INDEX_REPLAY_LIMIT);
  }

  private WalGitRepositoryManager node(String name, long replayLimit) {
    Config config = new Config();
    config.setLong("walgerrit", null, "indexReplayLimit", replayLimit);
    config.setString("walgerrit", null, "storagePath", root.resolve("store").toString());
    config.setString("walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root.resolve(name));
    StorageLayout layout =
        new StorageLayout(
            new FileObjectStore(root.resolve("store")),
            root.resolve(name + "-cache"),
            root.resolve(name + "-cursors"),
            "");
    return new WalGitRepositoryManager(configuration, layout);
  }
}
