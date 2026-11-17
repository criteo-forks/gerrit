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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every project a request opens is resolved in the catalog. A catalog that did not change is
 * resolved without looking its ref up, and one that changed never from an older state.
 */
class CatalogSnapshotTest {
  @TempDir Path root;

  @Test
  void anUnchangedCatalogIsResolvedWithoutLookingItsRefUp() throws Exception {
    WalGitRepositoryManager node = node("a", "0");
    Project.NameKey project = Project.nameKey("platform/listed");
    node.createRepository(project).close();
    Catalog catalog = node.catalog();
    assertTrue(catalog.resolve(project, false).isPresent());
    long lookups = catalog.refLookups();

    // A project listing opens every repository.
    for (int i = 0; i < 4_000; i++) {
      assertTrue(catalog.resolve(project, false).isPresent());
      assertFalse(catalog.resolve(Project.nameKey("platform/none"), false).isPresent());
    }

    assertEquals(lookups, catalog.refLookups());
  }

  @Test
  void anEmptyCatalogIsResolvedWithoutLookingItsRefUp() throws Exception {
    Catalog catalog = node("a", "0").catalog();
    assertFalse(catalog.resolve(Project.nameKey("platform/none"), false).isPresent());
    long lookups = catalog.refLookups();

    for (int i = 0; i < 100; i++) {
      assertFalse(catalog.resolve(Project.nameKey("platform/none"), false).isPresent());
    }

    assertEquals(lookups, catalog.refLookups());
  }

  @Test
  void aBindingThisNodeCommitsIsResolvedAtOnce() throws Exception {
    WalGitRepositoryManager node = node("a", "0");
    Catalog catalog = node.catalog();
    for (int i = 0; i < 20; i++) {
      Project.NameKey project = Project.nameKey("platform/created-" + i);
      assertFalse(catalog.resolve(project, false).isPresent());

      node.createRepository(project).close();

      assertTrue(catalog.resolve(project, false).isPresent(), project.get());
    }
    long lookups = catalog.refLookups();
    assertTrue(catalog.resolve(Project.nameKey("platform/created-0"), false).isPresent());
    assertEquals(lookups, catalog.refLookups(), "and then stays resolved without a lookup");
  }

  @Test
  void aBindingAnotherNodeCommitsIsResolvedOnceThisNodeHasReadTheStore() throws Exception {
    WalGitRepositoryManager writer = node("writer", "0");
    Project.NameKey first = Project.nameKey("platform/first");
    writer.createRepository(first).close();

    // No reads of the store by time: this node sees what an authoritative read or an open shows.
    Catalog reader = node("reader", "0").catalog();
    assertTrue(reader.resolve(first, false).isPresent());
    Project.NameKey second = Project.nameKey("platform/second");
    writer.createRepository(second).close();
    assertFalse(reader.resolve(second, false).isPresent(), "as before: nothing made it read");
    assertTrue(reader.resolve(second, true).isPresent(), "an authoritative read reads the store");
    assertTrue(reader.resolve(second, false).isPresent(), "and what it found stays");

    // Reads of the store by time: a plain resolve adopts what the store has.
    Catalog timed = node("timed", "1 ms").catalog();
    assertTrue(timed.resolve(second, false).isPresent());
    Project.NameKey third = Project.nameKey("platform/third");
    writer.createRepository(third).close();
    Thread.sleep(20);
    assertTrue(timed.resolve(third, false).isPresent());
    long lookups = timed.refLookups();
    Thread.sleep(20);
    assertTrue(timed.resolve(third, false).isPresent());
    assertEquals(lookups, timed.refLookups(), "an unchanged manifest costs no lookup");
  }

  @Test
  void aBindingAnotherNodeAnnouncesIsResolvedWithoutAnAuthoritativeRead() throws Exception {
    WalGitRepositoryManager writer = node("writer", "0");
    WalGitRepositoryManager reader = node("reader", "0");
    Project.NameKey first = Project.nameKey("platform/first");
    writer.createRepository(first).close();
    Catalog catalog = reader.catalog();
    // Warm: the reader resolves from the snapshot it has seen.
    assertTrue(catalog.resolve(first, false).isPresent());
    long lookups = catalog.refLookups();
    assertTrue(catalog.resolve(first, false).isPresent());
    assertEquals(lookups, catalog.refLookups());

    Project.NameKey second = Project.nameKey("platform/second");
    writer.createRepository(second).close();
    assertFalse(catalog.resolve(second, false).isPresent(), "periodic revalidation is off");

    // What the writer's gossip endpoint tells the reader about the catalog's publication.
    ManifestCache.VersionedManifest published =
        writer.storage().manifestStore(RepositoryId.CATALOG).refreshVersionedManifest();
    assertTrue(
        reader
            .storage()
            .expectManifest(
                RepositoryId.CATALOG, published.version(), published.manifest().getRevision()));

    // The next ordinary resolve reads the store and drops what it had seen.
    assertTrue(catalog.resolve(second, false).isPresent());
    assertEquals(writer.catalog().snapshot(true), catalog.snapshot(false));
  }

  @Test
  void resolvesDoNotSettleOnAnOlderCatalogWhileBindingsAreCommitted() throws Exception {
    WalGitRepositoryManager node = node("a", "0");
    WalGitRepositoryManager other = node("b", "0");
    Catalog catalog = node.catalog();
    List<Project.NameKey> created = new CopyOnWriteArrayList<>();
    List<String> missed = new CopyOnWriteArrayList<>();
    AtomicBoolean done = new AtomicBoolean();
    List<Thread> readers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      Thread reader =
          new Thread(
              () -> {
                while (!done.get()) {
                  // A binding that this node committed, or resolved authoritatively, before the
                  // call is in whatever the call returns.
                  List<Project.NameKey> known = List.copyOf(created);
                  try {
                    Catalog.Snapshot seen = catalog.snapshot(false);
                    for (Project.NameKey project : known) {
                      if (!seen.byName().containsKey(project)) {
                        missed.add(project.get());
                      }
                    }
                  } catch (Exception failure) {
                    missed.add(failure.toString());
                  }
                }
              });
      reader.start();
      readers.add(reader);
    }
    try {
      for (int i = 0; i < 40; i++) {
        // This node's commits and another node's, which this node adopts when it commits next
        // or reads authoritatively.
        Project.NameKey project = Project.nameKey("platform/racing-" + i);
        if (i % 2 == 0) {
          node.createRepository(project).close();
        } else {
          other.createRepository(project).close();
          assertTrue(catalog.resolve(project, true).isPresent());
        }
        created.add(project);
      }
    } finally {
      done.set(true);
      for (Thread reader : readers) {
        reader.join();
      }
    }

    assertEquals(List.of(), missed);
    Catalog.Snapshot end = catalog.snapshot(false);
    for (Project.NameKey project : created) {
      assertTrue(end.byName().containsKey(project), project.get());
    }
    assertEquals(other.catalog().snapshot(true), end);
  }

  private WalGitRepositoryManager node(String name, String revalidateInterval) {
    Config config = new Config();
    config.setString("walgerrit", null, "storagePath", root.resolve("shared-store").toString());
    config.setString(
        "walgerrit", null, "indexCursorPath", root.resolve(name + "-cursors").toString());
    config.setString("walgerrit", null, "manifestRevalidateInterval", revalidateInterval);
    return new WalGitRepositoryManager(WalGitConfiguration.from(config, root.resolve(name)));
  }
}
