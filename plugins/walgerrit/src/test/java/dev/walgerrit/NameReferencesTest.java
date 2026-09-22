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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameReferencesTest {
  private static final Project.NameKey ALL_USERS = Project.nameKey("All-Users");
  private static final Project.NameKey P = Project.nameKey("platform/p");
  private static final Project.NameKey P2 = Project.nameKey("platform/p2");
  private static final String ALICE = "refs/users/01/1000001";
  private static final String BOB = "refs/users/02/1000002";
  private static final String WATCHES =
      """
      [project "platform/p"]
      \tnotify = * [ALL_COMMENTS, NEW_CHANGES]
      \tnotify = branch:main [SUBMITTED_CHANGES]
      [project "other"]
      \tnotify = * [NEW_PATCHSETS]
      """;
  private static final String DESTINATIONS =
      "# Ref\tProject\n#\nrefs/heads/main\tplatform/p\nrefs/heads/dev\tother\n";

  @TempDir Path root;

  @Test
  void renameRewritesWatchesDestinationsAndSubscriptionPermissions() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    Project.NameKey submodule = Project.nameKey("platform/lib");
    node.createRepository(submodule).close();
    config(node, submodule, "[allowSuperproject \"platform/p\"]\n\tmatching = refs/heads/*\n");
    node.createRepository(ALL_USERS).close();
    config(node, ALL_USERS, "[allowSuperproject \"platform/p\"]\n\tmatching = refs/heads/*\n");
    file(node, ALL_USERS, ALICE, "watch.config", WATCHES);
    file(node, ALL_USERS, ALICE, "destinations/mine", DESTINATIONS);
    file(node, ALL_USERS, BOB, "watch.config", "[project \"other\"]\n\tnotify = * [ALL_COMMENTS]\n");
    ObjectId bobBefore = tip(node, ALL_USERS, BOB);

    node.namespace().rename(P, P2, false);

    Config alice = parse(read(node, ALL_USERS, ALICE, "watch.config"));
    assertFalse(alice.getSubsections("project").contains("platform/p"));
    assertEquals(
        List.of("* [ALL_COMMENTS, NEW_CHANGES]", "branch:main [SUBMITTED_CHANGES]"),
        List.of(alice.getStringList("project", "platform/p2", "notify")));
    assertEquals(
        List.of("* [NEW_PATCHSETS]"), List.of(alice.getStringList("project", "other", "notify")));
    assertEquals(
        "# Ref\tProject\n#\nrefs/heads/main\tplatform/p2\nrefs/heads/dev\tother\n",
        read(node, ALL_USERS, ALICE, "destinations/mine"));
    assertEquals(bobBefore, tip(node, ALL_USERS, BOB), "an account without a reference is untouched");
    Config lib = parse(read(node, submodule, "refs/meta/config", "project.config"));
    assertEquals(List.of("platform/p2"), List.copyOf(lib.getSubsections("allowSuperproject")));
    assertEquals("refs/heads/*", lib.getString("allowSuperproject", "platform/p2", "matching"));
    Config users = parse(read(node, ALL_USERS, "refs/meta/config", "project.config"));
    assertEquals(List.of("platform/p2"), List.copyOf(users.getSubsections("allowSuperproject")));
    assertTrue(node.namespace().pending().isEmpty());
    node.openRepository(P2).close();
  }

  @Test
  void renameMergesNotifyValuesIntoAnExistingSectionOfTheNewName() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    node.createRepository(ALL_USERS).close();
    file(
        node,
        ALL_USERS,
        ALICE,
        "watch.config",
        "[project \"platform/p\"]\n\tnotify = * [ALL_COMMENTS]\n\tnotify = branch:x [NEW_CHANGES]\n"
            + "[project \"platform/p2\"]\n\tnotify = * [ALL_COMMENTS]\n");

    node.namespace().rename(P, P2, false);

    Config alice = parse(read(node, ALL_USERS, ALICE, "watch.config"));
    assertEquals(List.of("platform/p2"), List.copyOf(alice.getSubsections("project")));
    assertEquals(
        List.of("* [ALL_COMMENTS]", "branch:x [NEW_CHANGES]"),
        List.of(alice.getStringList("project", "platform/p2", "notify")));
  }

  @Test
  void deleteRemovesWatchesDestinationRowsAndSubscriptionPermissions() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    Project.NameKey submodule = Project.nameKey("platform/lib");
    node.createRepository(submodule).close();
    config(node, submodule, "[allowSuperproject \"platform/p\"]\n\tmatching = refs/heads/*\n[access]\n\tinheritFrom = All-Projects\n");
    node.createRepository(ALL_USERS).close();
    config(node, ALL_USERS, "[allowSuperproject \"platform/p\"]\n\tmatching = refs/heads/*\n");
    file(node, ALL_USERS, ALICE, "watch.config", WATCHES);
    file(node, ALL_USERS, ALICE, "destinations/mine", DESTINATIONS);
    file(node, ALL_USERS, BOB, "destinations/only", "refs/heads/main\tplatform/p");

    node.namespace().delete(P, false);

    Config alice = parse(read(node, ALL_USERS, ALICE, "watch.config"));
    assertEquals(List.of("other"), List.copyOf(alice.getSubsections("project")));
    assertEquals(
        "# Ref\tProject\n#\nrefs/heads/dev\tother\n", read(node, ALL_USERS, ALICE, "destinations/mine"));
    assertEquals("", read(node, ALL_USERS, BOB, "destinations/only"), "the only row, without a final line break");
    Config lib = parse(read(node, submodule, "refs/meta/config", "project.config"));
    assertTrue(lib.getSubsections("allowSuperproject").isEmpty());
    assertEquals("All-Projects", lib.getString("access", null, "inheritFrom"), "other keys stay");
    Config users = parse(read(node, ALL_USERS, "refs/meta/config", "project.config"));
    assertTrue(users.getSubsections("allowSuperproject").isEmpty());
  }

  @Test
  void destinationRowsKeepTheirFileShape() {
    assertEquals(
        "refs/heads/main\tplatform/p2", rewriteDestinations("refs/heads/main\tplatform/p", P, P2));
    assertEquals("", rewriteDestinations("refs/heads/main\tplatform/p", P, null));
    assertEquals("", rewriteDestinations("refs/heads/main\tplatform/p\n", P, null));
    assertEquals("a\tx\n", rewriteDestinations("a\tx\nb\tplatform/p\n", P, null));
    assertNull(rewriteDestinations("platform/p has no tab\nrefs/heads/dev\tother\n", P, null));
  }

  @Test
  void aCycleOrAMissingParentInheritsFromAllProjectsAsGerritDoes() throws Exception {
    WalGitRepositoryManager node = node("a");
    Project.NameKey allProjects = Project.nameKey("All-Projects");
    Project.NameKey loopA = Project.nameKey("platform/loop-a");
    Project.NameKey loopB = Project.nameKey("platform/loop-b");
    Project.NameKey leaf = Project.nameKey("platform/leaf");
    Project.NameKey orphan = Project.nameKey("platform/orphan");
    for (Project.NameKey name : List.of(allProjects, loopA, loopB, leaf, orphan)) {
      node.createRepository(name).close();
    }
    config(node, allProjects, "[allowSuperproject \"platform/super\"]\n\tmatching = refs/heads/*\n");
    config(node, loopA, "[access]\n\tinheritFrom = platform/loop-b\n");
    config(node, loopB, "[access]\n\tinheritFrom = platform/loop-a\n");
    config(node, leaf, "[access]\n\tinheritFrom = platform/loop-a\n");
    config(node, orphan, "[access]\n\tinheritFrom = platform/nowhere\n");

    for (Project.NameKey name : List.of(leaf, orphan)) {
      IOException inherited =
          assertThrows(IOException.class, () -> node.namespace().rename(name, P2, false));
      assertTrue(inherited.getMessage().contains("allows superprojects"), inherited.getMessage());
    }
  }

  @Test
  void anAccountRefDeletedUnderTheRewriteIsSettledNotRetried() throws Exception {
    WalGitRepositoryManager nodeA = node("a");
    WalGitRepositoryManager nodeB = node("b");
    nodeA.createRepository(P).close();
    nodeA.createRepository(ALL_USERS).close();
    file(nodeA, ALL_USERS, ALICE, "watch.config", WATCHES);
    file(nodeA, ALL_USERS, BOB, "watch.config", WATCHES);
    boolean[] raced = {false};
    NameReferences.Opener racing =
        name -> {
          Repository repository = nodeA.openRepository(name);
          if (name.equals(ALL_USERS) && !raced[0]) {
            raced[0] = true;
            deleteRef(nodeB, ALL_USERS, BOB); // Node A's handle still lists Bob's ref.
          }
          return repository;
        };

    new NameReferences(racing, Project.nameKey("All-Projects"), ALL_USERS, Clock.systemUTC())
        .rewrite(P, P2, List.of(P, ALL_USERS), "op-race");

    Config alice = parse(read(nodeA, ALL_USERS, ALICE, "watch.config"));
    assertTrue(alice.getSubsections("project").contains("platform/p2"));
    assertNull(tip(nodeA, ALL_USERS, BOB));
  }

  @Test
  void aReplicatedOperationLeavesReferencesAlone() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    node.createRepository(ALL_USERS).close();
    file(node, ALL_USERS, ALICE, "watch.config", WATCHES);
    ObjectId before = tip(node, ALL_USERS, ALICE);

    node.namespace().rename(P, P2, true);

    assertEquals(before, tip(node, ALL_USERS, ALICE));
    node.openRepository(P2).close();
  }

  @Test
  void parentsAndProjectsSuperprojectsMaySubscribeToAreRefused() throws Exception {
    WalGitRepositoryManager node = node("a");
    Project.NameKey parent = Project.nameKey("platform/parent");
    Project.NameKey child = Project.nameKey("platform/child");
    Project.NameKey grandchild = Project.nameKey("platform/grandchild");
    Project.NameKey leaf = Project.nameKey("platform/leaf");
    for (Project.NameKey name : List.of(parent, child, grandchild, leaf)) {
      node.createRepository(name).close();
    }
    config(node, parent, "[allowSuperproject \"platform/super\"]\n\tmatching = refs/heads/*\n");
    config(node, child, "[access]\n\tinheritFrom = platform/parent\n");
    config(node, grandchild, "[access]\n\tinheritFrom = platform/child\n");

    IOException hasChildren =
        assertThrows(IOException.class, () -> node.namespace().rename(parent, P2, false));
    assertTrue(hasChildren.getMessage().contains("parent of [platform/child]"), hasChildren.getMessage());
    assertThrows(IOException.class, () -> node.namespace().delete(parent, false));

    IOException inherited =
        assertThrows(IOException.class, () -> node.namespace().rename(grandchild, P2, false));
    assertTrue(inherited.getMessage().contains("allows superprojects"), inherited.getMessage());

    node.namespace().rename(leaf, P2, false);
    assertTrue(node.namespace().pending().isEmpty());
    for (Project.NameKey name : List.of(parent, child, grandchild, P2)) {
      node.openRepository(name).close();
    }
  }

  @Test
  void oneRenameOrDeletionIsInFlightAtATime() throws Exception {
    WalGitRepositoryManager node = node("a");
    Project.NameKey other = Project.nameKey("platform/other");
    node.createRepository(P).close();
    node.createRepository(other).close();
    NamespaceTest.prepareRename(node, "op-first", P, P2);

    IOException busy =
        assertThrows(
            IOException.class, () -> node.namespace().rename(other, Project.nameKey("platform/x"), false));
    assertTrue(busy.getMessage().contains("op-first is in flight"), busy.getMessage());
    assertThrows(IOException.class, () -> node.namespace().delete(other, false));
    node.createRepository(Project.nameKey("platform/created-meanwhile")).close();

    node.namespace().resume("op-first");
    node.namespace().rename(other, Project.nameKey("platform/x"), false);
  }

  @Test
  void repeatingARenameThatCompletedOrIsInFlightSucceeds() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    node.namespace().rename(P, P2, true);
    node.namespace().rename(P, P2, true);
    assertThrows(IOException.class, () -> node.namespace().rename(P, Project.nameKey("platform/p3"), true));

    Project.NameKey q = Project.nameKey("platform/q");
    node.createRepository(q).close();
    NamespaceTest.prepareRename(node, "op-q", q, Project.nameKey("platform/q2"));
    node.namespace().rename(q, Project.nameKey("platform/q2"), true);
    assertTrue(node.namespace().pending().isEmpty());
    node.openRepository(Project.nameKey("platform/q2")).close();
  }

  @Test
  void resumeAfterTheFenceRewritesReferences() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    node.createRepository(ALL_USERS).close();
    file(node, ALL_USERS, ALICE, "watch.config", WATCHES);
    RepositoryId id = node.idOf(P);
    NamespaceTest.prepareRename(node, "op-fence", P, P2);
    node.storage().manifestStore(id).fence(0, "op-fence", false);

    node("b").namespace().resume("op-fence");

    Config alice = parse(read(node, ALL_USERS, ALICE, "watch.config"));
    assertTrue(alice.getSubsections("project").contains("platform/p2"));
    assertFalse(alice.getSubsections("project").contains("platform/p"));
  }

  @Test
  void aMalformedFileFailsBeforeAnythingChanges() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    node.createRepository(ALL_USERS).close();
    file(node, ALL_USERS, ALICE, "watch.config", "[project \"platform/p\"\n\tnotify = broken\n");

    IOException malformed =
        assertThrows(IOException.class, () -> node.namespace().rename(P, P2, false));
    assertTrue(malformed.getMessage().contains("Unreadable configuration in " + ALICE), malformed.getMessage());
    assertTrue(node.namespace().pending().isEmpty(), "nothing was prepared");
    node.openRepository(P).close();
    assertThrows(RepositoryNotFoundException.class, () -> node.openRepository(P2));
  }

  @Test
  void theReportSeparatesWhatIsRewrittenFromWhatIsOnlyMentioned() throws Exception {
    WalGitRepositoryManager node = node("a");
    node.createRepository(P).close();
    node.createRepository(Project.nameKey("platform/lib")).close();
    config(node, Project.nameKey("platform/lib"), "[allowSuperproject \"platform/p\"]\n\tmatching = refs/heads/*\n");
    node.createRepository(Project.nameKey("All-Projects")).close();
    config(
        node,
        Project.nameKey("All-Projects"),
        "[plugin \"lfs\"]\n\tenabled = true\n[lfs \"platform/p\"]\n\tenabled = true\n");
    node.createRepository(ALL_USERS).close();
    file(
        node,
        ALL_USERS,
        ALICE,
        "watch.config",
        WATCHES
            + "[project \"other\"]\n\tnotify = project:platform/p [ALL_COMMENTS]\n"
            + "[project \"platform/p\"]\n\tnotify = project:platform/p [NEW_CHANGES]\n");
    file(node, ALL_USERS, ALICE, "destinations/mine", DESTINATIONS);
    file(node, ALL_USERS, ALICE, "queries", "mine\tproject:platform/p is:open\n");
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    NamespaceProgram.run(node, List.of("references", P.get()), new PrintStream(out, true, StandardCharsets.UTF_8));

    String report = out.toString(StandardCharsets.UTF_8);
    assertTrue(report.contains("watching accounts: 1"), report);
    assertTrue(report.contains("destination rows: 1"), report);
    assertTrue(report.contains("as a superproject: platform/lib"), report);
    assertTrue(report.contains("children: none"), report);
    assertTrue(report.contains("subscribe to it: no"), report);
    assertTrue(report.contains("All-Projects project.config: [lfs \"platform/p\"]"), report);
    assertTrue(report.contains(ALICE + " watch.config: [project \"other\"] notify = project:platform/p"), report);
    assertTrue(
        report.contains(ALICE + " watch.config: [project \"platform/p\"] notify = project:platform/p [NEW_CHANGES]"),
        "a filter inside the watch being renamed is a reference of its own: " + report);
    assertTrue(report.contains(ALICE + " queries: mine\tproject:platform/p is:open"), report);
  }

  private WalGitRepositoryManager node(String name) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("shared-store").toString());
    config.setString("walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve(name)));
  }

  private static void config(WalGitRepositoryManager node, Project.NameKey project, String text)
      throws IOException {
    file(node, project, "refs/meta/config", "project.config", text);
  }

  /** Commits {@code content} at {@code path} on top of {@code ref}'s tip, creating the ref if needed. */
  static void file(
      WalGitRepositoryManager node, Project.NameKey project, String ref, String path, String content)
      throws IOException {
    try (Repository repository = node.openRepository(project);
        ObjectInserter inserter = repository.newObjectInserter();
        ObjectReader reader = repository.newObjectReader();
        RevWalk walk = new RevWalk(reader)) {
      Ref current = repository.exactRef(ref);
      DirCache index = DirCache.newInCore();
      if (current != null) {
        DirCacheBuilder builder = index.builder();
        builder.addTree(
            new byte[0], DirCacheEntry.STAGE_0, reader, walk.parseCommit(current.getObjectId()).getTree());
        builder.finish();
      }
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      DirCacheEditor editor = index.editor();
      editor.add(
          new DirCacheEditor.PathEdit(path) {
            @Override
            public void apply(DirCacheEntry entry) {
              entry.setFileMode(FileMode.REGULAR_FILE);
              entry.setObjectId(blob);
            }
          });
      editor.finish();
      PersonIdent ident = new PersonIdent("Test", "test@example.test");
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(index.writeTree(inserter));
      if (current != null) {
        commit.setParentId(current.getObjectId());
      }
      commit.setAuthor(ident);
      commit.setCommitter(ident);
      commit.setMessage("Write " + path);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      RefUpdate update = repository.updateRef(ref);
      update.setNewObjectId(commitId);
      update.setForceUpdate(true);
      RefUpdate.Result result = update.update();
      assertTrue(
          result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD || result == RefUpdate.Result.FORCED,
          result.name());
    }
  }

  static String read(WalGitRepositoryManager node, Project.NameKey project, String ref, String path)
      throws IOException {
    try (Repository repository = node.openRepository(project);
        ObjectReader reader = repository.newObjectReader();
        RevWalk walk = new RevWalk(reader)) {
      Ref current = repository.exactRef(ref);
      if (current == null) {
        return null;
      }
      try (TreeWalk tree = TreeWalk.forPath(reader, path, walk.parseCommit(current.getObjectId()).getTree())) {
        if (tree == null) {
          return null;
        }
        return new String(reader.open(tree.getObjectId(0)).getCachedBytes(), StandardCharsets.UTF_8);
      }
    }
  }

  private static ObjectId tip(WalGitRepositoryManager node, Project.NameKey project, String ref)
      throws IOException {
    try (Repository repository = node.openRepository(project)) {
      Ref current = repository.exactRef(ref);
      return current == null ? null : current.getObjectId();
    }
  }

  private static void deleteRef(WalGitRepositoryManager node, Project.NameKey project, String ref)
      throws IOException {
    try (Repository repository = node.openRepository(project)) {
      RefUpdate delete = repository.updateRef(ref);
      delete.setForceUpdate(true);
      RefUpdate.Result result = delete.delete();
      assertTrue(
          result == RefUpdate.Result.FORCED || result == RefUpdate.Result.FAST_FORWARD, result.name());
    }
  }

  private static String rewriteDestinations(String file, Project.NameKey from, Project.NameKey to) {
    byte[] rewritten = NameReferences.rewriteDestinations(file.getBytes(StandardCharsets.UTF_8), from, to);
    return rewritten == null ? null : new String(rewritten, StandardCharsets.UTF_8);
  }

  private static Config parse(String text) throws Exception {
    Config config = new Config();
    config.fromText(text);
    return config;
  }
}
