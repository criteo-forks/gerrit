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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class GroupPublisherFailureTest {
  @TempDir Path root;

  @Test
  void interruptedQueuedRequestIsRemovedBeforePublication() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    CountDownLatch firstCas = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            (current, proposed) -> true,
            () -> {
              firstCas.countDown();
              await(release);
            });
    ManifestStore store = new ManifestStore(hooked, root.resolve("cache"), "project");
    store.create();
    GroupPublisher publisher = store.publisher();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Outcome> first = executor.submit(() -> publish(publisher, request(store, "first")));
      assertTrue(firstCas.await(10, TimeUnit.SECONDS));
      AtomicReference<Thread> waiterThread = new AtomicReference<>();
      Future<Outcome> waiter =
          executor.submit(
              () -> {
                waiterThread.set(Thread.currentThread());
                return publish(publisher, request(store, "cancelled"));
              });
      awaitQueued(publisher, 1);
      waiterThread.get().interrupt();
      Outcome cancelled = waiter.get(10, TimeUnit.SECONDS);
      assertNotNull(cancelled.failure());
      assertTrue(cancelled.interrupted());
      assertEquals(0, publisher.queuedForTesting());
      release.countDown();
      assertNull(first.get(10, TimeUnit.SECONDS).failure());
      assertEquals(
          List.of("first"), store.refresh().getPacksList().stream().map(PackRef::getName).toList());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void recoveryFailureCompletesEveryTakenWaiterIncludingAnInterruptedOne() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    CountDownLatch firstCas = new CountDownLatch(1);
    CountDownLatch releaseCas = new CountDownLatch(1);
    CountDownLatch settling = new CountDownLatch(1);
    CountDownLatch releaseSettlement = new CountDownLatch(1);
    AtomicBoolean outage = new AtomicBoolean();
    AtomicInteger failingReads = new AtomicInteger();
    AtomicReference<Thread> settlementThread = new AtomicReference<>();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            (current, proposed) -> true,
            () -> {
              firstCas.countDown();
              await(releaseCas);
            },
            () -> {
              outage.set(true);
              throw new IOException("lost response");
            });
    ObjectStore flaky =
        (ObjectStore)
            Proxy.newProxyInstance(
                ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class},
                (proxy, method, args) -> {
                  if (outage.get()
                      && (method.getName().equals("get") || method.getName().equals("getIfChanged"))
                      && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)) {
                    if (failingReads.incrementAndGet() == 2) {
                      settlementThread.set(Thread.currentThread());
                      settling.countDown();
                      await(releaseSettlement);
                    }
                    throw new IOException("manifest unavailable during recovery");
                  }
                  try {
                    return method.invoke(hooked, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    ManifestStore store = new ManifestStore(flaky, root.resolve("cache"), "project");
    store.create();
    GroupPublisher publisher = store.publisher();
    publisher.defer(List.of(pack("pending")));
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      Future<Outcome> first =
          executor.submit(() -> publish(publisher, GroupPublisher.Request.flush(store)));
      assertTrue(firstCas.await(10, TimeUnit.SECONDS));
      AtomicReference<Thread> secondThread = new AtomicReference<>();
      AtomicReference<Thread> thirdThread = new AtomicReference<>();
      Future<Outcome> second =
          executor.submit(
              () -> {
                secondThread.set(Thread.currentThread());
                return publish(publisher, GroupPublisher.Request.flush(store));
              });
      Future<Outcome> third =
          executor.submit(
              () -> {
                thirdThread.set(Thread.currentThread());
                return publish(publisher, GroupPublisher.Request.flush(store));
              });
      awaitQueued(publisher, 2);
      releaseCas.countDown();
      assertTrue(settling.await(10, TimeUnit.SECONDS));
      assertEquals(
          0, publisher.queuedForTesting(), "both followers were taken into the second group");
      boolean interruptSecond = settlementThread.get() != secondThread.get();
      (interruptSecond ? secondThread : thirdThread).get().interrupt();
      releaseSettlement.countDown();
      assertNotNull(first.get(10, TimeUnit.SECONDS).failure());
      Outcome secondResult = second.get(10, TimeUnit.SECONDS);
      Outcome thirdResult = third.get(10, TimeUnit.SECONDS);
      assertNotNull(secondResult.failure());
      assertNotNull(thirdResult.failure());
      assertTrue((interruptSecond ? secondResult : thirdResult).interrupted());
      outage.set(false);
      assertNull(publish(publisher, GroupPublisher.Request.flush(store)).failure());
      assertFalse(publisher.hasPending(), "healthy recovery can follow failed groups");
      assertEquals(
          List.of("pending"),
          store.refresh().getPacksList().stream().map(PackRef::getName).toList());
    } finally {
      releaseCas.countDown();
      releaseSettlement.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void inventorySnapshotRetainsAttemptIdentityAcrossPublicationAndCompaction() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    AtomicBoolean armed = new AtomicBoolean();
    AtomicReference<ManifestStore> owner = new AtomicReference<>();
    ManifestStore other = new ManifestStore(shared, root.resolve("other-cache"), "project");
    ObjectStore interleaving =
        (ObjectStore)
            Proxy.newProxyInstance(
                ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class},
                (proxy, method, args) -> {
                  if ((method.getName().equals("get") || method.getName().equals("getIfChanged"))
                      && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
                      && armed.compareAndSet(true, false)) {
                    ManifestStore store = owner.get();
                    // snapshot() has already copied the pending inventory. Now publish and retire
                    // it,
                    // removing it from the node inventory before that snapshot gets its manifest.
                    store.publisher().publish(GroupPublisher.Request.flush(store));
                    other.refresh();
                    other.publish(0, List.of(pack("replacement")), List.of("original"), false);
                  }
                  try {
                    return method.invoke(shared, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    ManifestStore store = new ManifestStore(interleaving, root.resolve("cache"), "project");
    owner.set(store);
    store.create();
    store.publisher().defer(List.of(pack("original")));
    armed.set(true);
    GroupPublisher.Snapshot snapshot = store.publisher().snapshot(store, true);
    assertFalse(armed.get());
    assertFalse(store.publisher().hasPending());
    assertTrue(
        snapshot.unpublished().isEmpty(), "the saved snapshot must not re-expose a retired pack");
    assertEquals(
        List.of("replacement"),
        snapshot.manifest().getPacksList().stream().map(PackRef::getName).toList());
  }

  private record Outcome(IOException failure, boolean interrupted) {}

  private static Outcome publish(GroupPublisher publisher, GroupPublisher.Request request) {
    try {
      publisher.publish(request);
      return new Outcome(null, Thread.currentThread().isInterrupted());
    } catch (IOException failure) {
      return new Outcome(failure, Thread.currentThread().isInterrupted());
    }
  }

  private static GroupPublisher.Request request(ManifestStore store, String name) {
    return new GroupPublisher.Request(store, List.of(pack(name)), List.of(), null, -1, -1);
  }

  private static PackRef pack(String name) {
    return PackRef.newBuilder()
        .setName(name)
        .setSource("INSERT")
        .addFiles(PackFile.newBuilder().setExtension("pack").setSize(10))
        .build();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "test did not release gate");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void awaitQueued(GroupPublisher publisher, int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (publisher.queuedForTesting() < count && System.nanoTime() < deadline) Thread.sleep(1);
    assertEquals(count, publisher.queuedForTesting());
  }
}
