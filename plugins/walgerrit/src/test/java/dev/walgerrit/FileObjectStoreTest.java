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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileObjectStoreTest {
  @TempDir Path root;

  @Test
  void listingsAreScopedToThePrefixAndHideInternalFiles() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    store.putIfAbsent("manifests/a.git/manifest.pb", "a".getBytes(UTF_8));
    store.putIfAbsent("manifests/b.git/manifest.pb", "b".getBytes(UTF_8));
    store.putIfAbsent("repos/a.git/wal/one.pack", "pack".getBytes(UTF_8));
    Files.writeString(root.resolve("manifests/a.git/manifest.pb.tmp-abandoned"), "staging");
    assertTrue(Files.isDirectory(root.resolve(".object-locks")), "writes take file locks");

    assertEquals(
        List.of("manifests/a.git/manifest.pb", "manifests/b.git/manifest.pb"),
        store.list("manifests/"));
    assertEquals(List.of("manifests/a.git/manifest.pb"), store.list("manifests/a.git/"));
    assertEquals(List.of("repos/a.git/wal/one.pack"), store.list("repos/a.git/wal/on"));
    assertEquals(
        List.of(
            "manifests/a.git/manifest.pb", "manifests/b.git/manifest.pb", "repos/a.git/wal/one.pack"),
        store.list(""));
    assertEquals(List.of(), store.list("missing/"));
    assertEquals(2, store.listWithVersions("manifests/").size());
  }

  @Test
  void lockStateStaysBoundedHoweverManyObjectsAreWritten() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    for (int i = 0; i < 1_000; i++) {
      store.putIfAbsent("repos/p.git/log/" + i + ".pb", new byte[] {(byte) i});
    }

    try (Stream<Path> locks = Files.list(root.resolve(".object-locks"))) {
      assertTrue(locks.count() <= 256, "lock files are striped, not one per key");
    }
  }

  @Test
  void concurrentWritersOfOneKeyAdmitExactlyOne() throws Exception {
    FileObjectStore store = new FileObjectStore(root);
    int writers = 16;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      CountDownLatch start = new CountDownLatch(1);
      AtomicInteger admitted = new AtomicInteger();
      AtomicInteger rejected = new AtomicInteger();
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        byte[] payload = ("writer-" + i).getBytes(UTF_8);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    store.putIfAbsent("manifests/p.git/manifest.pb", payload);
                    admitted.incrementAndGet();
                  } catch (ObjectAlreadyExistsException expected) {
                    rejected.incrementAndGet();
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
      assertEquals(1, admitted.get());
      assertEquals(writers - 1, rejected.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void compareAndSwapOnlyReplacesTheExpectedVersion() throws IOException {
    FileObjectStore store = new FileObjectStore(root);
    ObjectStore.StoredObject first = store.putIfAbsent("manifests/p.git/manifest.pb", "v1".getBytes(UTF_8));

    ObjectStore.StoredObject second =
        store.compareAndSwap("manifests/p.git/manifest.pb", first.version(), "v2".getBytes(UTF_8));

    assertThrows(
        ObjectStoreConflictException.class,
        () ->
            store.compareAndSwap(
                "manifests/p.git/manifest.pb", first.version(), "stale".getBytes(UTF_8)));
    assertEquals(second.version(), store.get("manifests/p.git/manifest.pb").orElseThrow().version());
    assertEquals("v2", new String(store.get("manifests/p.git/manifest.pb").orElseThrow().bytes(), UTF_8));
  }
}
