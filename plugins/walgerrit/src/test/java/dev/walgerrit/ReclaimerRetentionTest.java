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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReclaimerRetentionTest {
  private static final Project.NameKey PROJECT = Project.nameKey("retention");
  private static final String WAL = "repos/retention.git/wal/";
  @TempDir Path root;

  @Test
  void oldFileMustGetAReaderGracePeriodAfterCompaction() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager a = node("a", shared);
    ObjectId first;
    try (Repository repo = a.createRepository(PROJECT)) {
      first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      RefUpdate update = repo.updateRef("refs/heads/first");
      update.setNewObjectId(first);
      assertEquals(RefUpdate.Result.NEW, update.update());
    }
    Set<String> before = new HashSet<>(shared.list(WAL));
    for (String file : before) {
      Files.setLastModifiedTime(
          root.resolve("store").resolve(file),
          FileTime.from(Instant.now().minus(Duration.ofDays(2))));
    }
    // This node keeps the old manifest. No subsequent lookup performs a revalidation.
    try (Repository oldReader = node("b", shared).openRepository(PROJECT)) {
      assertEquals(first, oldReader.exactRef("refs/heads/first").getObjectId());
      try (LocalWalGitRepository compacting = (LocalWalGitRepository) a.openRepository(PROJECT)) {
        WalGitRepositoryManagerTest.insertCommit(compacting, "second pack");
        DfsPackCompactor compactor = new DfsPackCompactor(compacting);
        for (var pack : compacting.getObjectDatabase().getPacks()) compactor.add(pack);
        compactor.compact(NullProgressMonitor.INSTANCE);
      }
      Set<String> retired = new HashSet<>(before);
      for (String live :
          ManifestStore.liveFileNames(a.storage().manifestStore(PROJECT).refresh())) {
        retired.remove(WAL + live);
      }
      assertFalse(retired.isEmpty());
      SteppingClock clock = new SteppingClock(Instant.now());
      Reclaimer reclaimer = new Reclaimer(a, clock, Duration.ofDays(1), 0);
      assertEquals(
          0,
          reclaimer.reclaim(PROJECT).deleted(),
          "upload age is not time since retirement; old readers still need these files");
      assertTrue(shared.list(WAL).containsAll(retired));
      try (var files = Files.walk(root.resolve("b-cache"))) {
        for (Path path : files.filter(Files::isRegularFile).toList()) Files.delete(path);
      }
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
      assertNotNull(oldReader.open(first), "old manifest remains readable during retirement grace");
      clock.advance(Duration.ofDays(2));
      assertTrue(reclaimer.reclaim(PROJECT).deleted() >= retired.size());
      assertTrue(java.util.Collections.disjoint(shared.list(WAL), retired));
    }
  }

  @Test
  void aRestartForgetsDeletionTimersAndWaitsAgain() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager a = node("a", shared);
    a.createRepository(PROJECT).close();
    shared.putIfAbsent(WAL + "pack-orphan.idx", new byte[] {1});
    SteppingClock clock = new SteppingClock(Instant.now().plus(Duration.ofDays(2)));
    Reclaimer first = new Reclaimer(a, clock, Duration.ofDays(1), 0);
    assertEquals(0, first.reclaim(PROJECT).deleted());
    clock.advance(Duration.ofDays(2));
    Reclaimer restarted = new Reclaimer(a, clock, Duration.ofDays(1), 0);
    assertEquals(0, restarted.reclaim(PROJECT).deleted(), "no timer is inferred from file age");
    clock.advance(Duration.ofDays(2));
    assertEquals(1, restarted.reclaim(PROJECT).deleted());
    assertTrue(shared.get(WAL + "pack-orphan.idx").isEmpty());
  }

  @Test
  void uploadGraceMustExpireBeforeTheAbsenceTimerStarts() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager a = node("a", shared);
    a.createRepository(PROJECT).close();
    shared.putIfAbsent(WAL + "pack-orphan.idx", new byte[] {1});
    SteppingClock clock = new SteppingClock(Instant.now());
    Reclaimer reclaimer = new Reclaimer(a, clock, Duration.ofDays(1), 0);
    assertEquals(0, reclaimer.reclaim(PROJECT).deleted());
    clock.advance(Duration.ofDays(2));
    assertEquals(
        0,
        reclaimer.reclaim(PROJECT).deleted(),
        "a fresh upload could have published and retired between those sweeps");
    clock.advance(Duration.ofHours(1));
    assertEquals(0, reclaimer.reclaim(PROJECT).deleted());
    clock.advance(Duration.ofDays(1));
    assertEquals(1, reclaimer.reclaim(PROJECT).deleted());
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }
}
