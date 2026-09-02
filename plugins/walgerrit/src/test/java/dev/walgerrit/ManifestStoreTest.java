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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.LogSegment;
import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void concurrentCreateHasExactlyOneWinner() throws Exception {
    ManifestStore first = store();
    ManifestStore second = store();
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<Callable<Boolean>> attempts = List.of(first::create, second::create);
      var results = executor.invokeAll(attempts);

      int winners = 0;
      for (var result : results) {
        if (result.get()) {
          winners++;
        }
      }
      assertEquals(1, winners);
    }
    assertTrue(first.exists());
    assertEquals(0, first.read().getRevision());
  }

  @Test
  void refCompareAndSwapIgnoresObjectAppendsButRejectsStaleRefs()
      throws Exception {
    ManifestStore first = store();
    ManifestStore stale = store();
    assertTrue(first.create());
    assertFalse(stale.create());

    first.publish(0, List.of(objectPack("objects")), List.of(), false);
    first.publish(0, List.of(reftable("refs-1")), List.of(), true);

    assertThrows(
        ManifestConflictException.class,
        () -> stale.publish(0, List.of(reftable("refs-2")), List.of(), true));
    assertEquals(2, first.read().getRevision());
    assertEquals(1, first.read().getRefRevision());
    assertEquals(2, first.read().getLogSegmentsCount());
  }

  @Test
  void concurrentRefPublishHasExactlyOneWinner() throws Exception {
    ManifestStore first = store();
    ManifestStore second = store();
    assertTrue(first.create());

    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          executor.invokeAll(
              List.of(
                  () -> first.publish(0, List.of(reftable("refs-1")), List.of(), true),
                  () -> second.publish(0, List.of(reftable("refs-2")), List.of(), true)));

      int winners = 0;
      int conflicts = 0;
      for (var result : results) {
        try {
          result.get();
          winners++;
        } catch (ExecutionException exception) {
          if (exception.getCause() instanceof ManifestConflictException) {
            conflicts++;
          } else {
            throw exception;
          }
        }
      }
      assertEquals(1, winners);
      assertEquals(1, conflicts);
    }
    assertEquals(1, first.read().getRefRevision());
    assertEquals(1, first.read().getLogSegmentsCount());
  }

  @Test
  void concurrentObjectAppendAndRefPublishBothSurvive() throws Exception {
    ManifestStore objects = store();
    ManifestStore refs = store();
    assertTrue(objects.create());

    try (var executor = Executors.newFixedThreadPool(2)) {
      var results =
          executor.invokeAll(
              List.of(
                  () -> objects.publish(0, List.of(objectPack("objects")), List.of(), false),
                  () -> refs.publish(0, List.of(reftable("refs")), List.of(), true)));
      for (var result : results) {
        result.get();
      }
    }

    var manifest = objects.read();
    assertEquals(2, manifest.getRevision());
    assertEquals(1, manifest.getRefRevision());
    assertEquals(2, manifest.getPacksCount());
    assertTrue(manifest.getPacksList().stream().anyMatch(pack -> pack.getName().equals("objects")));
    assertTrue(manifest.getPacksList().stream().anyMatch(pack -> pack.getName().equals("refs")));
  }

  @Test
  void compactionPreciselySupersedesInputsAndPreservesConcurrentPush()
      throws Exception {
    ManifestStore store = store();
    assertTrue(store.create());
    store.publish(
        0,
        List.of(objectPack("old-1"), objectPack("old-2")),
        List.of(),
        false);

    store.publish(0, List.of(objectPack("concurrent-push")), List.of(), false);
    store.publish(
        0,
        List.of(pack("compacted", "COMPACT", "pack")),
        List.of("old-1", "old-2"),
        false);

    var manifest = store.read();
    assertEquals(2, manifest.getPacksCount());
    assertTrue(
        manifest.getPacksList().stream()
            .anyMatch(pack -> pack.getName().equals("concurrent-push")));
    assertTrue(
        manifest.getPacksList().stream()
            .anyMatch(pack -> pack.getName().equals("compacted")));
    var lastLog = manifest.getLogSegments(manifest.getLogSegmentsCount() - 1);
    LogEntry entry =
        LogSegment.parseFrom(
            java.nio.file.Files.readAllBytes(
                temporaryDirectory.resolve("repo.git").resolve(lastLog.getKey()))).getEntries(0);
    assertEquals(LogEntry.Kind.COMPACT, entry.getKind());
    assertEquals(List.of("old-1", "old-2"), entry.getSupersedesList());
  }

  @Test
  void compactionRejectsAnInputThatIsNoLongerLive() throws Exception {
    ManifestStore store = store();
    assertTrue(store.create());
    store.publish(0, List.of(objectPack("old")), List.of(), false);
    store.publish(
        0,
        List.of(pack("winner", "COMPACT", "pack")),
        List.of("old"),
        false);

    assertThrows(
        java.io.IOException.class,
        () ->
            store.publish(
                0,
                List.of(pack("stale", "COMPACT", "pack")),
                List.of("old"),
                false));
    assertEquals(2, store.read().getRevision());
  }

  @Test
  void lostSuccessResponseIsVerifiedAsCommitted() throws Exception {
    AtomicBoolean loseResponse = new AtomicBoolean();
    ManifestStore store =
        new ManifestStore(
            temporaryDirectory.resolve("ambiguous.git"),
            "ambiguous",
            Clock.systemUTC(),
            ignored -> {
              if (loseResponse.getAndSet(false)) {
                throw new java.io.IOException("simulated lost success response");
              }
            });
    assertTrue(store.create());

    loseResponse.set(true);
    var committed =
        store.publish(0, List.of(reftable("refs")), List.of(), true);

    assertEquals(1, committed.getRevision());
    assertEquals(1, committed.getRefRevision());
    assertEquals(1, store.read().getLogSegmentsCount());
  }

  private static PackRef objectPack(String name) {
    return pack(name, "INSERT", "pack");
  }

  private static PackRef reftable(String name) {
    return pack(name, "INSERT", "ref");
  }

  private static PackRef pack(String name, String source, String extension) {
    return PackRef.newBuilder()
        .setName(name)
        .setSource(source)
        .addFiles(PackFile.newBuilder().setExtension(extension))
        .build();
  }

  private ManifestStore store() {
    return new ManifestStore(temporaryDirectory.resolve("repo.git"), "repo");
  }
}
