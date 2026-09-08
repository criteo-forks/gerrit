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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor;
import org.eclipse.jgit.internal.storage.dfs.DfsPacksChangedListener;
import org.eclipse.jgit.internal.storage.dfs.DfsReader;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Fault boundaries for grouped publication, delayed CAS recovery, and cold readers. */
class PublicationRecoveryTest {
  private static final Project.NameKey PROJECT = Project.nameKey("review/late-cas");
  @TempDir Path root;

  @Test
  void retryConflictWithFailedVerificationMustRememberOriginalTransaction() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicBoolean failNextRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              // The initial request committed; the SDK's retry received a precondition failure.
              failNextRead.set(true);
              throw new ObjectStoreConflictException("simulated retry after lost success response");
            });
    ObjectStore flaky = failingReads(hooked, failNextRead, true);
    WalGitRepositoryManager a = node("a", flaky);
    WalGitRepositoryManager b = node("b", shared);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      RefUpdate update = repo.updateRef("refs/heads/first");
      update.setNewObjectId(first);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update.update());
      compactAndReclaim(b);
      assertEquals(RefUpdate.Result.NEW, update(a, "unrelated", first));
      assertColdReadable(shared, first);
    }
  }

  @Test
  void unresolvedTransactionMustSurviveAFailedReplacementPublication() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<Object[]> delayed = new AtomicReference<>();
    AtomicBoolean failReplacementLog = new AtomicBoolean();
    ObjectStore lagging =
        (ObjectStore)
            Proxy.newProxyInstance(
                ObjectStore.class.getClassLoader(),
                new Class<?>[] {ObjectStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("putIfAbsent")
                      && ((String) args[0]).contains("/log/")
                      && failReplacementLog.compareAndSet(true, false)) {
                    throw new IOException(
                        "replacement log upload failed before any replacement CAS");
                  }
                  if (method.getName().equals("compareAndSwap")
                      && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)) {
                    int attempt = attempts.getAndIncrement();
                    if (attempt == 0) {
                      delayed.set(args.clone());
                      throw new IOException(
                          "client timed out; the original server request is still in flight");
                    }
                  }
                  try {
                    return method.invoke(shared, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    WalGitRepositoryManager a = node("a", lagging);
    ObjectId first;
    try (Repository repo = a.openRepository(PROJECT)) {
      first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      RefUpdate update = repo.updateRef("refs/heads/first");
      update.setNewObjectId(first);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update.update());
      failReplacementLog.set(true);
      // Closing starts recovery. Its GET still shows no original commit; its log PUT fails.
    }
    assertFalse(failReplacementLog.get(), "the replacement attempted its log upload");
    assertEquals(1, attempts.get(), "no replacement CAS has fenced the original request");
    // The original request can still commit after the failed replacement has returned.
    Object[] original = delayed.get();
    shared.compareAndSwap((String) original[0], (String) original[1], (byte[]) original[2]);
    compactAndReclaim(b);
    assertEquals(RefUpdate.Result.NEW, update(a, "unrelated", first));
    assertColdReadable(shared, first);
  }

  @Test
  void failureWhileSettlingUncertaintyMustNotTurnCloseIntoAnUncheckedException() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicBoolean failReads = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              failReads.set(true);
              throw new IOException("lost CAS response during a continuing outage");
            });
    WalGitRepositoryManager a = node("a", failingReads(hooked, failReads, false));
    Repository repo = a.openRepository(PROJECT);
    ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
    RefUpdate update = repo.updateRef("refs/heads/first");
    update.setNewObjectId(first);
    assertEquals(RefUpdate.Result.LOCK_FAILURE, update.update());
    try {
      assertDoesNotThrow(
          repo::close, "close should log the storage failure and leave recovery pending");
    } finally {
      failReads.set(false);
    }
  }

  enum CasFault {
    NONE,
    BEFORE_IO,
    AFTER_IO,
    AFTER_CONFLICT,
    AFTER_RUNTIME
  }

  static Stream<Arguments> casFailures() {
    return Stream.of(CasFault.values())
        .flatMap(
            fault ->
                (fault == CasFault.NONE ? Stream.of(false) : Stream.of(false, true))
                    .map(verificationFails -> Arguments.of(fault, verificationFails)));
  }

  @ParameterizedTest
  @MethodSource("casFailures")
  void casFailureMatrix(CasFault fault, boolean verificationFails) throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    Manifest initial = manifest(shared);
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean failRead = new AtomicBoolean();
    ObjectStore flaky =
        intercept(
            shared,
            (method, args) -> {
              if (isManifestRead(method, args) && failRead.compareAndSet(true, false)) {
                throw new IOException("verification read failed");
              }
              if (isManifestCas(method, args) && attempts.incrementAndGet() == 1) {
                if (fault == CasFault.NONE) return invoke(shared, method, args);
                failRead.set(verificationFails);
                if (fault == CasFault.BEFORE_IO)
                  throw new IOException("request did not execute yet");
                invoke(shared, method, args);
                switch (fault) {
                  case AFTER_CONFLICT ->
                      throw new ObjectStoreConflictException("retry after lost response");
                  case AFTER_RUNTIME -> throw new IllegalStateException("SDK failed after commit");
                  default -> throw new IOException("lost response");
                }
              }
              return invoke(shared, method, args);
            });
    WalGitRepositoryManager a = node("a", flaky);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      boolean unknown = fault == CasFault.BEFORE_IO || verificationFails;
      assertEquals(
          unknown ? RefUpdate.Result.LOCK_FAILURE : RefUpdate.Result.NEW,
          update(repo, "first", first));
      assertEquals(RefUpdate.Result.NEW, update(repo, "second", first));
      assertEquals(
          fault == CasFault.BEFORE_IO ? 3 : 2,
          attempts.get(),
          "only an unoccupied sequence requires a fencing CAS");
      assertFalse(a.storage().manifestStore(PROJECT).publisher().hasPending());
      if (fault == CasFault.BEFORE_IO) {
        List<LogEntry> entries =
            a.storage()
                .manifestStore(PROJECT)
                .readLogEntriesAfter(
                    initial.getHeadSeq(), initial.getHeadTransactionId(), manifest(shared), 100);
        assertEquals(2, entries.size(), "failed proposal is outside the chain");
        LogEntry fence = entries.get(0);
        assertEquals(LogEntry.Kind.PACK, fence.getKind());
        assertEquals(0, fence.getAdditionsCount());
        assertEquals(0, fence.getSupersedesCount());
        assertEquals(
            initial.getRefRevision() + 1,
            manifest(shared).getRefRevision(),
            "the fence does not change refs");
      }
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
      try (Repository cold = node("cold", shared).openRepository(PROJECT)) {
        assertEquals(first, cold.exactRef("refs/heads/second").getObjectId());
        assertNotNull(cold.open(first));
        assertEquals(fault != CasFault.BEFORE_IO, cold.exactRef("refs/heads/first") != null);
      }
    }
  }

  static Stream<Arguments> preparationFailures() {
    return Stream.of("uploadIfAbsent", "putIfAbsent")
        .flatMap(method -> Stream.of(false, true).map(after -> Arguments.of(method, after)));
  }

  @ParameterizedTest
  @MethodSource("preparationFailures")
  void failureBeforeCasKeepsObjectsRetryableWithoutAFence(String failingMethod, boolean after)
      throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicBoolean armed = new AtomicBoolean();
    AtomicInteger casAttempts = new AtomicInteger();
    ObjectStore flaky =
        intercept(
            shared,
            (method, args) -> {
              if (isManifestCas(method, args)) casAttempts.incrementAndGet();
              if (method.getName().equals(failingMethod)
                  && (((String) args[0]).endsWith(".ref") || ((String) args[0]).contains("/log/"))
                  && armed.compareAndSet(true, false)) {
                if (after) invoke(shared, method, args);
                throw new IOException("immutable upload failed before manifest CAS");
              }
              return invoke(shared, method, args);
            });
    WalGitRepositoryManager a = node("a", flaky);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      armed.set(true);
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update(repo, "first", first));
      assertFalse(armed.get());
      assertEquals(0, casAttempts.get());
      assertEquals(RefUpdate.Result.NEW, update(repo, "first", first));
      assertEquals(1, casAttempts.get(), "preparation failure needs no fencing write");
      assertColdReadable(shared, first);
    }
  }

  @Test
  void ambiguousFenceMustNotBeConfusedWithThePublicationWhosePacksItProtects() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicInteger attempts = new AtomicInteger();
    AtomicBoolean failRead = new AtomicBoolean();
    ObjectStore flaky =
        intercept(
            shared,
            (method, args) -> {
              if (isManifestRead(method, args) && failRead.compareAndSet(true, false)) {
                throw new IOException("fence verification failed");
              }
              if (isManifestCas(method, args)) {
                switch (attempts.incrementAndGet()) {
                  case 1 -> throw new IOException("original request did not commit");
                  case 2 -> {
                    invoke(shared, method, args);
                    failRead.set(true);
                    throw new IOException("fence committed but response was lost");
                  }
                }
              }
              return invoke(shared, method, args);
            });
    WalGitRepositoryManager a = node("a", flaky);
    ObjectId first;
    try (Repository repo = a.openRepository(PROJECT)) {
      first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update(repo, "first", first));
      // close attempts the fence, whose outcome also becomes ambiguous.
    }
    assertEquals(2, attempts.get());
    assertTrue(a.storage().manifestStore(PROJECT).publisher().hasPending());
    assertEquals(RefUpdate.Result.NEW, update(a, "first", first));
    assertEquals(3, attempts.get());
    assertColdReadable(shared, first);
  }

  @Test
  void successfulFenceRejectsTheOriginalRequestWhenItArrivesLate() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicReference<Object[]> original = new AtomicReference<>();
    ObjectStore delayed =
        intercept(
            shared,
            (method, args) -> {
              if (isManifestCas(method, args) && original.compareAndSet(null, args.clone())) {
                throw new IOException("original request delayed before execution");
              }
              return invoke(shared, method, args);
            });
    WalGitRepositoryManager a = node("a", delayed);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update(repo, "abandoned", first));
      assertEquals(RefUpdate.Result.NEW, update(repo, "first", first));
      Object[] request = original.get();
      assertThrows(
          ObjectStoreConflictException.class,
          () ->
              shared.compareAndSwap((String) request[0], (String) request[1], (byte[]) request[2]));
      assertNull(repo.exactRef("refs/heads/abandoned"));
      assertColdReadable(shared, first);
    }
  }

  @Test
  void originalRequestCanWinTheRaceAgainstItsFence() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<Object[]> original = new AtomicReference<>();
    ObjectStore delayed =
        intercept(
            shared,
            (method, args) -> {
              if (isManifestCas(method, args)) {
                switch (attempts.incrementAndGet()) {
                  case 1 -> {
                    original.set(args.clone());
                    throw new IOException("request continues on the server");
                  }
                  case 2 -> invoke(shared, method, original.get());
                }
              }
              return invoke(shared, method, args);
            });
    WalGitRepositoryManager a = node("a", delayed);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update(repo, "first", first));
      assertEquals(RefUpdate.Result.NEW, update(repo, "second", first));
      assertEquals(4, attempts.get(), "original, losing fence, winning fence, revalidated ref");
      assertColdReadable(shared, first);
    }
  }

  @Test
  void logReadFailureRetainsTheAttemptIdentity() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    WalGitRepositoryManager b = node("b", shared);
    AtomicBoolean failLogRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              b.storage().manifestStore(PROJECT).publishEvents(List.of("{}"));
              failLogRead.set(true);
              throw new IOException("lost response; another transaction now occupies the head");
            });
    ObjectStore flaky =
        intercept(
            hooked,
            (method, args) -> {
              if (method.getName().equals("get")
                  && ((String) args[0]).contains("/log/")
                  && failLogRead.compareAndSet(true, false))
                throw new IOException("log GET failed");
              return invoke(hooked, method, args);
            });
    WalGitRepositoryManager a = node("a", flaky);
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update(repo, "first", first));
      compactAndReclaim(b);
      assertEquals(RefUpdate.Result.NEW, update(repo, "second", first));
      assertColdReadable(shared, first);
    }
  }

  @Test
  void coldHandleOnOriginalNodeMustIgnoreAlreadyReclaimedUncertainPacks() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    AtomicBoolean failRead = new AtomicBoolean();
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              failRead.set(true);
              throw new IOException("lost response");
            });
    WalGitRepositoryManager a = node("a", failingReads(hooked, failRead, true));
    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(writer, "first");
      assertEquals(RefUpdate.Result.LOCK_FAILURE, update(writer, "first", first));
      compactAndReclaim(node("b", shared));
      a.storage().manifestStore(PROJECT).refresh();
      discardDiskCache("a");
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
      assertTrue(
          a.storage().manifestStore(PROJECT).publisher().hasPending(),
          "no write or close has settled local uncertainty yet");
      try (Repository cold = a.openRepository(PROJECT)) {
        assertEquals(first, cold.exactRef("refs/heads/first").getObjectId());
        assertNotNull(cold.open(first), "original node must rematerialize only live packs");
        assertAllPackIndexesReadable(cold);
      }
    }
  }

  @Test
  void coldReadWhileCommittedResponseIsStillInFlightIgnoresRetiredPacks() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    node("setup", shared).createRepository(PROJECT).close();
    CountDownLatch committed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HookedObjectStore hooked =
        new HookedObjectStore(
            shared,
            HookedObjectStore::publishesRefChange,
            () -> {},
            () -> {
              committed.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IOException("test response not released");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
            });
    WalGitRepositoryManager a = node("a", hooked);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Repository writer = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(writer, "first");
      Future<RefUpdate.Result> writing = executor.submit(() -> update(writer, "first", first));
      try {
        assertTrue(committed.await(10, TimeUnit.SECONDS));
        compactAndReclaim(node("b", shared));
        a.storage().manifestStore(PROJECT).refresh();
        discardDiskCache("a");
        DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
        try (Repository cold = a.openRepository(PROJECT)) {
          assertEquals(first, cold.exactRef("refs/heads/first").getObjectId());
          assertNotNull(cold.open(first));
          assertAllPackIndexesReadable(cold);
        }
      } finally {
        release.countDown();
        assertEquals(RefUpdate.Result.NEW, writing.get(10, TimeUnit.SECONDS));
      }
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void notificationFailureCannotUndoADurableCommit() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager a = node("a", shared);
    a.createRepository(PROJECT).close();
    AtomicInteger notifications = new AtomicInteger();
    a.storage()
        .onPublication(
            (project, manifest) -> {
              notifications.incrementAndGet();
              throw new RejectedExecutionException("maintenance executor stopped");
            });
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      assertEquals(RefUpdate.Result.NEW, update(repo, "first", first));
      assertEquals(1, notifications.get());
      assertColdReadable(shared, first);
    }
  }

  @Test
  void jgitEventFailureCannotUndoADurableCommit() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager a = node("a", shared);
    a.createRepository(PROJECT).close();
    AtomicInteger notifications = new AtomicInteger();
    try (Repository repo = a.openRepository(PROJECT)) {
      ObjectId first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      repo.getListenerList()
          .addListener(
              DfsPacksChangedListener.class,
              event -> {
                notifications.incrementAndGet();
                throw new IllegalStateException("local observer failed after commitPackImpl");
              });
      assertEquals(RefUpdate.Result.NEW, update(repo, "first", first));
      assertTrue(notifications.get() > 0);
      assertColdReadable(shared, first);
    }
  }

  @Test
  void refFoldRacingALocalCompactionRevalidates() throws Exception {
    FileObjectStore shared = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager setup = node("setup", shared);
    ObjectId first;
    try (Repository repo = setup.createRepository(PROJECT)) {
      first = WalGitRepositoryManagerTest.insertCommit(repo, "first");
      assertEquals(RefUpdate.Result.NEW, update(repo, "first", first));
    }
    CountDownLatch uploading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean held = new AtomicBoolean();
    ObjectStore blocking =
        intercept(
            shared,
            (method, args) -> {
              if (method.getName().equals("uploadIfAbsent")
                  && ((String) args[0]).endsWith(".ref")
                  && held.compareAndSet(false, true)) {
                uploading.countDown();
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IOException("test did not release upload");
              }
              return invoke(shared, method, args);
            });
    WalGitRepositoryManager a = node("a", blocking);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<RefUpdate.Result> writing = executor.submit(() -> update(a, "second", first));
      assertTrue(uploading.await(10, TimeUnit.SECONDS));
      try (LocalWalGitRepository repo = (LocalWalGitRepository) a.openRepository(PROJECT)) {
        DfsPackCompactor compactor = new DfsPackCompactor(repo);
        var tables = repo.getObjectDatabase().getReftables();
        assertEquals(1, tables.length);
        compactor.setReftableConfig(
            new org.eclipse.jgit.internal.storage.reftable.ReftableConfig());
        compactor.add(tables[0]);
        compactor.compact(NullProgressMonitor.INSTANCE);
      }
      release.countDown();
      assertEquals(RefUpdate.Result.NEW, writing.get(10, TimeUnit.SECONDS));
      assertColdReadable(shared, first);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface Invocation {
    Object call(Method method, Object[] args) throws Throwable;
  }

  private static ObjectStore intercept(ObjectStore delegate, Invocation invocation) {
    return (ObjectStore)
        Proxy.newProxyInstance(
            ObjectStore.class.getClassLoader(),
            new Class<?>[] {ObjectStore.class},
            (proxy, method, args) -> invocation.call(method, args));
  }

  private static Object invoke(ObjectStore delegate, Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static boolean isManifestCas(Method method, Object[] args) {
    return method.getName().equals("compareAndSwap")
        && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE);
  }

  private static boolean isManifestRead(Method method, Object[] args) {
    return (method.getName().equals("get") || method.getName().equals("getIfChanged"))
        && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE);
  }

  private static Manifest manifest(ObjectStore shared) throws IOException {
    return Manifest.parseFrom(
        shared
            .get("manifests/" + PROJECT.get() + ".git/" + ManifestStore.MANIFEST_FILE)
            .orElseThrow()
            .bytes());
  }

  private static RefUpdate.Result update(Repository repo, String name, ObjectId id)
      throws IOException {
    RefUpdate update = repo.updateRef("refs/heads/" + name);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(id);
    return update.update();
  }

  private ObjectStore failingReads(ObjectStore delegate, AtomicBoolean fail, boolean once) {
    return (ObjectStore)
        Proxy.newProxyInstance(
            ObjectStore.class.getClassLoader(),
            new Class<?>[] {ObjectStore.class},
            (proxy, method, args) -> {
              if ((method.getName().equals("get") || method.getName().equals("getIfChanged"))
                  && ((String) args[0]).endsWith(ManifestStore.MANIFEST_FILE)
                  && (once ? fail.compareAndSet(true, false) : fail.get())) {
                throw new IOException("manifest read failed");
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private void compactAndReclaim(WalGitRepositoryManager b) throws Exception {
    try (LocalWalGitRepository repo = (LocalWalGitRepository) b.openRepository(PROJECT)) {
      assertNotNull(repo.exactRef("refs/heads/first"));
      WalGitRepositoryManagerTest.insertCommit(repo, "another pack");
      DfsPackCompactor compactor = new DfsPackCompactor(repo);
      for (var pack : repo.getObjectDatabase().getPacks()) compactor.add(pack);
      compactor.compact(NullProgressMonitor.INSTANCE);
    }
    SteppingClock afterGrace = new SteppingClock(Instant.now().plus(Duration.ofDays(2)));
    Reclaimer reclaimer = new Reclaimer(b, afterGrace, Duration.ofDays(1), 0);
    reclaimer.reclaim(PROJECT);
    afterGrace.advance(Duration.ofDays(2));
    reclaimer.reclaim(PROJECT);
  }

  private void discardDiskCache(String node) throws IOException {
    try (var files = java.nio.file.Files.walk(root.resolve(node + "-cache"))) {
      for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
        java.nio.file.Files.delete(file);
      }
    }
  }

  private static void assertAllPackIndexesReadable(Repository repository) throws IOException {
    try (DfsReader reader = (DfsReader) repository.newObjectReader()) {
      for (var pack : ((LocalWalGitRepository) repository).getObjectDatabase().getPacks()) {
        assertNotNull(pack.getPackIndex(reader));
      }
    }
  }

  private void assertColdReadable(ObjectStore shared, ObjectId id) throws IOException {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    try (Repository cold = node("cold", shared).openRepository(PROJECT)) {
      assertEquals(id, cold.exactRef("refs/heads/first").getObjectId());
      assertNotNull(cold.open(id), "a cold reader must be able to read the committed ref");
      assertAllPackIndexesReadable(cold);
    }
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }

  private static RefUpdate.Result update(WalGitRepositoryManager node, String name, ObjectId id)
      throws IOException {
    try (Repository repo = node.openRepository(PROJECT)) {
      RefUpdate update = repo.updateRef("refs/heads/" + name);
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(id);
      return update.update();
    }
  }
}
