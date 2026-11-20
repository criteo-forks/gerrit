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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Counter3;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.Field;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a node publishes is counted against Gerrit's choice of executor, and compaction obeys it. */
class WriteRoleTest {
  private static final Project.NameKey ALL_USERS = Project.nameKey("All-Users");
  private static final Project.NameKey PROJECT = Project.nameKey("platform/placed");
  private static final Consumer<Config> PROJECT_PLACEMENT =
      config -> config.setString("walgerrit", null, "placement", "project");

  @TempDir Path root;

  /** Counts increments by metric name and field values. */
  private static final class Counters extends DisabledMetricMaker {
    final Map<String, Long> counts = new ConcurrentHashMap<>();

    long count(String... key) {
      return counts.getOrDefault(String.join("/", key), 0L);
    }

    @Override
    public <F1, F2> Counter2<F1, F2> newCounter(
        String name, Description desc, Field<F1> field1, Field<F2> field2) {
      return new Counter2<>() {
        @Override
        public void incrementBy(F1 value1, F2 value2, long value) {
          counts.merge(name + "/" + value1 + "/" + value2, value, Long::sum);
        }

        @Override
        public void remove() {}
      };
    }

    @Override
    public <F1, F2, F3> Counter3<F1, F2, F3> newCounter(
        String name, Description desc, Field<F1> field1, Field<F2> field2, Field<F3> field3) {
      return new Counter3<>() {
        @Override
        public void incrementBy(F1 value1, F2 value2, F3 value3, long value) {
          counts.merge(name + "/" + value1 + "/" + value2 + "/" + value3, value, Long::sum);
        }

        @Override
        public void remove() {}
      };
    }
  }

  @Test
  void publicationsAreCountedByRepositoryKindAndWhetherThisNodeIsThePreferredExecutor()
      throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    Counters writerCounts = new Counters();
    Counters readerCounts = new Counters();
    WalGitRepositoryManager writer = node("writer", store, PROJECT_PLACEMENT);
    new WriteRole(writer, project -> true, writerCounts);
    WalGitRepositoryManager reader = node("reader", store, PROJECT_PLACEMENT);
    // Gerrit prefers the other node for every project but one.
    new WriteRole(reader, project -> project.equals("reader/own"), readerCounts);

    writer.createRepository(ALL_USERS).close();
    writer.createRepository(PROJECT).close();
    long created = writerCounts.count("walgerrit/publications", "other", "ref_update", "preferred");
    try (Repository repository = writer.openRepository(PROJECT)) {
      publish(repository, head("forwarded"), "a write that was handed to the writer");
    }
    try (Repository repository = reader.openRepository(PROJECT)) {
      publish(repository, head("kept"), "a write the reader could not hand over");
    }
    try (Repository repository = reader.openRepository(ALL_USERS)) {
      publish(repository, "refs/users/00/1000000", "an account created at login");
    }
    reader.createRepository(Project.nameKey("reader/own")).close();

    assertEquals(
        created + 1,
        writerCounts.count("walgerrit/publications", "other", "ref_update", "preferred"));
    assertEquals(0, writerCounts.count("walgerrit/publications", "other", "ref_update", "other"));
    assertEquals(1, readerCounts.count("walgerrit/publications", "other", "ref_update", "other"));
    assertEquals(
        1, readerCounts.count("walgerrit/publications", "all_users", "ref_update", "other"));
    assertTrue(
        readerCounts.count("walgerrit/publications", "other", "ref_update", "preferred") >= 1,
        "the reader is the preferred executor of its own project");
    assertTrue(
        readerCounts.count("walgerrit/publications", "catalog", "ref_update", "other") >= 1,
        "binding a name is a publication to the catalog");
  }

  @Test
  void aRefTransactionThatLostToAnotherNodeIsCounted() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager writer = node("writer", shared, ignored -> {});
    writer.createRepository(PROJECT).close();
    // Right before the reader's compare-and-swap, the writer lands a ref change of its own.
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {
              try (Repository repository = writer.openRepository(PROJECT)) {
                publish(repository, head("first"), "the writer's change");
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    Counters counts = new Counters();
    WalGitRepositoryManager reader = node("reader", hooked, ignored -> {});
    new WriteRole(reader, project -> false, counts);

    try (Repository repository = reader.openRepository(PROJECT)) {
      publish(repository, head("second"), "the reader's change");
    }

    assertEquals(2, hooked.matchedCasAttempts.get(), "the ref transaction ran again");
    assertEquals(1, counts.count("walgerrit/ref_transaction_conflicts", "other", "other"));
    assertEquals(1, counts.count("walgerrit/publications", "other", "ref_update", "other"));
  }

  @Test
  void withOneExecutorForEveryWriteOnlyThatNodeCompacts() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager reader = node("reader", store, ignored -> {});
    new WriteRole(reader, project -> false, new DisabledMetricMaker());
    reader.compactor().start();
    try {
      // Writes the reader could not hand over leave the repository due for compaction.
      try (Repository repository = reader.createRepository(PROJECT)) {
        for (int i = 0; i < 4; i++) {
          publish(repository, head("b" + i), "commit " + i);
        }
      }
      assertFalse(reader.compactor().isQueued(PROJECT), "the reader leaves it to the executor");
      reader.compactor().sweep();
      assertFalse(reader.compactor().isQueued(PROJECT), "and so does its sweep");
      assertFalse(reader.compactor().policy().plan(manifest(reader)).isEmpty());

      WalGitRepositoryManager writer = node("writer", store, ignored -> {});
      new WriteRole(writer, project -> true, new DisabledMetricMaker());
      writer.compactor().start();
      try {
        awaitCompacted(writer);
      } finally {
        writer.compactor().stop();
      }
    } finally {
      reader.compactor().stop();
    }
  }

  @Test
  void withAnExecutorPerProjectEveryNodeCompactsWhatItWrites() throws Exception {
    FileObjectStore store = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node = node("node", store, PROJECT_PLACEMENT);
    new WriteRole(node, project -> false, new DisabledMetricMaker());
    node.compactor().start();
    try {
      try (Repository repository = node.createRepository(PROJECT)) {
        for (int i = 0; i < 4; i++) {
          publish(repository, head("b" + i), "commit " + i);
        }
      }
      awaitCompacted(node);
    } finally {
      node.compactor().stop();
    }
  }

  private static void awaitCompacted(WalGitRepositoryManager node) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline
        && (node.compactor().isQueued(PROJECT)
            || !node.compactor().policy().plan(manifest(node)).isEmpty())) {
      Thread.sleep(50);
    }
    assertTrue(node.compactor().policy().plan(manifest(node)).isEmpty(), "compacted");
  }

  private WalGitRepositoryManager node(String name, ObjectStore store, Consumer<Config> tweak) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    config.setInt("walgerrit", null, "compactMinPacks", 3);
    config.setInt("walgerrit", null, "compactMinReftables", 3);
    tweak.accept(config);
    WalGitConfiguration configuration = WalGitConfiguration.from(config, root.resolve(name));
    StorageLayout layout =
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), "");
    return new WalGitRepositoryManager(configuration, layout);
  }

  private static Manifest manifest(WalGitRepositoryManager node) {
    try {
      return node.manifestStore(PROJECT).refresh();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static String head(String name) {
    return Constants.R_HEADS + name;
  }

  private static ObjectId publish(Repository repository, String ref, String message)
      throws Exception {
    ObjectId commit = WalGitRepositoryManagerTest.insertCommit(repository, message);
    RefUpdate update = repository.updateRef(ref);
    update.setNewObjectId(commit);
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD, result.name());
    return commit;
  }

}
