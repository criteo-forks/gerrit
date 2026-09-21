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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.ManifestCache.VersionedManifest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two managers over one shared store stand in for two Gerrit nodes: each has its own node-wide
 * manifest cache, so a write on one is only visible to the other after a conditional read.
 */
class ManifestFreshnessTest {
  private static final String MAIN = Constants.R_HEADS + "main";

  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unchangedReadPreservesHintsReceivedAfterTheStoreAnswered(boolean alreadyExpecting)
      throws Exception {
    WalGitRepositoryManager writer = node("writer", "0");
    Project.NameKey project = Project.nameKey("platform/in-flight-read");
    writer.createRepository(project).close();
    ObjectStore shared = new FileObjectStore(root.resolve("shared-store"));
    AtomicReference<Executable> afterRead = new AtomicReference<>();
    ObjectStore delayed =
        (ObjectStore)
            Proxy.newProxyInstance(
                ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class},
                (proxy, method, args) -> {
                  Object result;
                  try {
                    result = method.invoke(shared, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if (method.getName().equals("getIfChanged")) {
                    Executable action = afterRead.getAndSet(null);
                    if (action != null) {
                      action.execute();
                    }
                  }
                  return result;
                });
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    WalGitRepositoryManager reader =
        new WalGitRepositoryManager(
            WalGitConfiguration.from(config, root.resolve("reader")),
            new StorageLayout(
                delayed, root.resolve("reader-cache"), root.resolve("reader-cursors"), ""));
    try (Repository writing = writer.openRepository(project);
        Repository reading = reader.openRepository(project)) {
      if (alreadyExpecting) {
        reader.storage().expectManifest(project, "unconfirmed", 1);
      }
      AtomicReference<ObjectId> commit = new AtomicReference<>();
      afterRead.set(
          () -> {
            commit.set(publishMain(writing, "after the read"));
            VersionedManifest published =
                writer.storage().manifestStore(project).refreshVersionedManifest();
            reader
                .storage()
                .expectManifest(project, published.version(), published.manifest().getRevision());
          });
      reader.storage().manifestStore(project).refresh();
      assertTrue(
          reader.storage().manifestStore(project).expectingNewerManifest(),
          "the earlier response cannot settle a later announcement");
      assertEquals(commit.get(), reading.exactRef(MAIN).getObjectId());
    }
  }

  @Test
  void handleServesItsNodeViewUntilItRevalidates() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a", "0");
    WalGitRepositoryManager nodeB = node("node-b", "0");
    Project.NameKey project = Project.nameKey("platform/freshness");
    nodeA.createRepository(project).close();

    try (Repository writer = nodeA.openRepository(project);
        Repository staleReader = nodeB.openRepository(project)) {
      assertNull(staleReader.exactRef(MAIN));

      ObjectId commit = publishMain(writer, "from node A");

      // Periodic revalidation is disabled, so the open handle keeps serving node B's view.
      assertNull(staleReader.exactRef(MAIN));

      // Opening a handle is a freshness boundary: it performs one conditional read.
      try (Repository fresh = nodeB.openRepository(project)) {
        assertEquals(commit, fresh.exactRef(MAIN).getObjectId());
      }

      // The older handle now adopts the manifest its node has already observed, with no read.
      assertEquals(commit, staleReader.exactRef(MAIN).getObjectId());
      assertEquals(Constants.OBJ_COMMIT, staleReader.open(commit).getType());
    }
  }

  @Test
  void peerWakeUpMakesAnOpenHandleRevalidateWhateverItsInterval() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a", "0");
    WalGitRepositoryManager nodeB = node("node-b", "0");
    Project.NameKey project = Project.nameKey("platform/wake-up");
    nodeA.createRepository(project).close();

    try (Repository writer = nodeA.openRepository(project);
        Repository reader = nodeB.openRepository(project)) {
      assertNull(reader.exactRef(MAIN));
      ObjectId commit = publishMain(writer, "from node A");
      assertNull(reader.exactRef(MAIN), "periodic revalidation is off");

      // What node A's gossip endpoint tells node B about the publication.
      VersionedManifest published =
          nodeA.storage().manifestStore(project).refreshVersionedManifest();
      assertTrue(
          nodeB
              .storage()
              .expectManifest(project, published.version(), published.manifest().getRevision()));

      // The open handle's next lookup revalidates, with no open, scan or elapsed interval.
      assertEquals(commit, reader.exactRef(MAIN).getObjectId());
      assertFalse(nodeB.storage().manifestStore(project).expectingNewerManifest());
      assertFalse(
          nodeB
              .storage()
              .expectManifest(project, published.version(), published.manifest().getRevision()),
          "an announcement of what the node already holds changes nothing");
    }
  }

  @Test
  void handleRevalidatesAfterTheConfiguredInterval() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a", "1 ms");
    WalGitRepositoryManager nodeB = node("node-b", "1 ms");
    Project.NameKey project = Project.nameKey("platform/interval");
    nodeA.createRepository(project).close();

    try (Repository writer = nodeA.openRepository(project);
        Repository reader = nodeB.openRepository(project)) {
      assertNull(reader.exactRef(MAIN));
      ObjectId commit = publishMain(writer, "from node A");
      Thread.sleep(20);
      assertEquals(commit, reader.exactRef(MAIN).getObjectId());
    }
  }

  @Test
  void scanForRepoChangesForcesRevalidation() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a", "0");
    WalGitRepositoryManager nodeB = node("node-b", "0");
    Project.NameKey project = Project.nameKey("platform/rescan");
    nodeA.createRepository(project).close();

    try (Repository writer = nodeA.openRepository(project);
        Repository reader = nodeB.openRepository(project)) {
      assertNull(reader.exactRef(MAIN));
      ObjectId commit = publishMain(writer, "from node A");
      assertNull(reader.exactRef(MAIN));
      reader.scanForRepoChanges();
      assertEquals(commit, reader.exactRef(MAIN).getObjectId());
    }
  }

  @Test
  void staleHandleCannotPublishAgainstAnOutdatedRefView() throws Exception {
    WalGitRepositoryManager nodeA = node("node-a", "0");
    WalGitRepositoryManager nodeB = node("node-b", "0");
    Project.NameKey project = Project.nameKey("platform/stale-writer");
    nodeA.createRepository(project).close();

    try (Repository writer = nodeA.openRepository(project);
        Repository stale = nodeB.openRepository(project)) {
      assertNull(stale.exactRef(MAIN));
      ObjectId winner = publishMain(writer, "from node A");

      // The stale handle believes main does not exist and tries to create it. Its own pack
      // publish conflicts with node A's write and retries over the newer manifest, so by the time
      // the ref transaction runs the handle may already see node A's ref; either way the create
      // against an expected old value of "absent" must be refused.
      ObjectId loser = WalGitRepositoryManagerTest.insertCommit(stale, "from node B");
      RefUpdate create = stale.updateRef(MAIN);
      create.setExpectedOldObjectId(ObjectId.zeroId());
      create.setNewObjectId(loser);
      create.setForceUpdate(true);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, create.update());

      // The transaction revalidated, so the handle now sees node A's ref and object.
      assertEquals(winner, stale.exactRef(MAIN).getObjectId());
      assertEquals(Constants.OBJ_COMMIT, stale.open(winner).getType());
    }
    try (Repository verify = nodeB.openRepository(project)) {
      assertEquals(
          ObjectId.fromString(verify.exactRef(MAIN).getObjectId().name()),
          verify.exactRef(MAIN).getObjectId());
    }
  }

  private WalGitRepositoryManager node(String name, String revalidateInterval) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("shared-store").toString());
    config.setString(
        "walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", revalidateInterval);
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve(name)));
  }

  private static ObjectId publishMain(Repository repository, String message) throws Exception {
    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
    RefUpdate update = repository.updateRef(MAIN);
    update.setNewObjectId(commit);
    assertEquals(RefUpdate.Result.NEW, update.update());
    return commit;
  }
}
