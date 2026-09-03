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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.RepositoryImporter.Outcome;
import dev.walgerrit.RepositoryImporter.Report;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryImporterTest {
  @TempDir Path root;

  @Test
  void importsPacksAndEveryRefAndIsIdempotent() throws Exception {
    Path source = root.resolve("basePath");
    Map<String, ObjectId> expected = new TreeMap<>();
    try (Repository bare = createBare(source.resolve("platform/tools.git"))) {
      expected.put(Constants.R_HEADS + "main", commitAndRef(bare, Constants.R_HEADS + "main", "one"));
      expected.put(Constants.R_HEADS + "dev", commitAndRef(bare, Constants.R_HEADS + "dev", "two"));
      expected.put("refs/changes/01/1/1", commitAndRef(bare, "refs/changes/01/1/1", "patch set"));
      expected.put("refs/changes/01/1/meta", commitAndRef(bare, "refs/changes/01/1/meta", "meta"));
      expected.put("refs/meta/config", commitAndRef(bare, "refs/meta/config", "config"));
      repack(bare);
    }
    WalGitRepositoryManager manager = manager("node-a");
    RepositoryImporter importer = new RepositoryImporter(manager, new PrintStream(System.out), true);

    Report report = importer.importAll(source, Set.of(), 2);

    assertEquals(1, report.imported());
    assertTrue(report.ok(), report.failures().toString());
    Project.NameKey project = Project.nameKey("platform/tools");
    try (Repository imported = manager.openRepository(project)) {
      for (Map.Entry<String, ObjectId> ref : expected.entrySet()) {
        assertEquals(ref.getValue(), imported.exactRef(ref.getKey()).getObjectId(), ref.getKey());
        assertEquals(Constants.OBJ_COMMIT, imported.open(ref.getValue()).getType());
      }
      Ref head = imported.exactRef(Constants.HEAD);
      assertNotNull(head);
      assertTrue(head.isSymbolic());
      assertEquals(Constants.R_HEADS + "main", head.getTarget().getName());
      java.util.Set<String> names = new java.util.TreeSet<>();
      for (Ref ref : imported.getRefDatabase().getRefsByPrefix(RefDatabase.ALL)) {
        if (!ref.getName().equals(Constants.HEAD)) {
          names.add(ref.getName());
        }
      }
      assertEquals(expected.keySet(), names, "no ref beyond the source's was imported");
    }
    Manifest manifest = manager.storage().manifestStore(project).refresh();
    assertTrue(manifest.getPacksList().stream().allMatch(p -> p.getSource().equals("COMPACT")));
    // JGit's GC packs head-reachable and other-ref-reachable objects separately; both come over.
    assertTrue(manifest.getPacksList().stream().filter(CompactionPolicy::isObjectPack).count() >= 1);
    assertEquals(1, manifest.getPacksList().stream().filter(CompactionPolicy::hasReftable).count());
    assertTrue(manifest.getRefRevision() > 0);

    // A second run publishes nothing and verifies instead.
    Report again = importer.importAll(source, Set.of(), 2);
    assertEquals(0, again.imported());
    assertEquals(1, again.alreadyImported());
    assertEquals(manifest, manager.storage().manifestStore(project).refresh());

    // Another node sees the same repository and can write on top of it.
    WalGitRepositoryManager other = manager("node-b");
    try (Repository fromOther = other.openRepository(project)) {
      ObjectId later = WalGitRepositoryManagerTest.insertCommit(fromOther, "after import");
      RefUpdate update = fromOther.updateRef(Constants.R_HEADS + "main");
      update.setNewObjectId(later);
      update.setForceUpdate(true);
      assertEquals(RefUpdate.Result.FORCED, update.update());
      assertEquals(later, fromOther.exactRef(Constants.R_HEADS + "main").getObjectId());
    }
  }

  @Test
  void refusesRepositoriesWithLooseObjectsAndReportsThemAsFailures() throws Exception {
    Path source = root.resolve("basePath");
    try (Repository bare = createBare(source.resolve("loose.git"))) {
      commitAndRef(bare, Constants.R_HEADS + "main", "not repacked");
    }
    WalGitRepositoryManager manager = manager("node-a");
    RepositoryImporter importer = new RepositoryImporter(manager, new PrintStream(System.out), false);

    Report report = importer.importAll(source, Set.of(), 1);

    assertEquals(1, report.failed());
    assertEquals("loose", report.failures().get(0));
    IOException refusal =
        assertThrows(
            IOException.class, () -> importer.importOne("loose", source.resolve("loose.git")));
    assertTrue(refusal.getMessage().contains("git repack -a -d"), refusal.getMessage());
  }

  @Test
  void importsOnlyTheRequestedProjectsAndFailsOnUnknownOnes() throws Exception {
    Path source = root.resolve("basePath");
    for (String name : new String[] {"a", "b"}) {
      try (Repository bare = createBare(source.resolve(name + ".git"))) {
        commitAndRef(bare, Constants.R_HEADS + "main", name);
        repack(bare);
      }
    }
    WalGitRepositoryManager manager = manager("node-a");
    RepositoryImporter importer = new RepositoryImporter(manager, new PrintStream(System.out), false);

    Report report = importer.importAll(source, Set.of("b"), 1);
    assertEquals(1, report.imported());
    assertEquals(Set.of(Project.nameKey("b")), manager.list());
    assertThrows(IOException.class, () -> importer.importAll(source, Set.of("missing"), 1));
  }

  @Test
  void commandLineEntryPointParsesArgumentsAndReturnsAnExitCode() throws Exception {
    Path source = root.resolve("basePath");
    try (Repository bare = createBare(source.resolve("cli.git"))) {
      commitAndRef(bare, Constants.R_HEADS + "main", "cli");
      repack(bare);
    }
    WalGitRepositoryManager manager = manager("node-a");
    assertEquals(
        0,
        RepositoryImporter.run(
            manager, new String[] {"--source", source.toString(), "--threads", "1"}));
    assertEquals(Set.of(Project.nameKey("cli")), manager.list());
    assertThrows(
        IllegalArgumentException.class,
        () -> RepositoryImporter.run(manager, new String[] {"--bogus"}));
    assertThrows(
        IllegalArgumentException.class, () -> RepositoryImporter.run(manager, new String[] {}));
  }

  private WalGitRepositoryManager manager(String name) {
    Config config = new Config();
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

  private static Repository createBare(Path directory) throws Exception {
    Files.createDirectories(directory.getParent());
    Repository repository =
        Git.init().setBare(true).setDirectory(directory.toFile()).call().getRepository();
    RefUpdate head = repository.updateRef(Constants.HEAD);
    assertEquals(RefUpdate.Result.FORCED, head.link(Constants.R_HEADS + "main"));
    return repository;
  }

  private static ObjectId commitAndRef(Repository repository, String ref, String message)
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

  /** What the operator does on the scratch copy: everything into packs, no loose objects. */
  private static void repack(Repository repository) throws Exception {
    GC gc = new GC((FileRepository) repository);
    gc.setExpireAgeMillis(0);
    gc.gc().get();
  }
}
