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

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.LogEntry;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreHealthTest {
  private static final Duration INTERVAL = Duration.ofSeconds(2);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Project.NameKey PROJECT = Project.nameKey("platform/health");

  @TempDir Path root;

  private final AtomicLong nanos = new AtomicLong(1);

  @Test
  void aNodeIsNoCandidateOnceTheStoreHasNotAnsweredForTheTimeout() throws Exception {
    WalGitRepositoryManager node =
        new WalGitRepositoryManager(new WalGitConfiguration(BackendType.LOCAL, root));
    node.createRepository(Project.nameKey("All-Projects")).close();
    AtomicBoolean cutOff = new AtomicBoolean();
    StoreHealth health =
        new StoreHealth(
            true,
            INTERVAL,
            TIMEOUT,
            () -> {
              if (cutOff.get()) {
                throw new IOException("connection timed out");
              }
              node.storage().manifestStore(RepositoryId.CATALOG).refreshIfPresent();
            },
            nanos::get);

    health.check();
    advance(TIMEOUT.minusSeconds(1));
    assertEquals(Optional.empty(), health.get(), "one slow read is not an outage");

    cutOff.set(true);
    health.check();
    advance(Duration.ofSeconds(2));
    assertTrue(health.get().orElseThrow().contains("has not answered for 11 s"));

    cutOff.set(false);
    health.check();
    assertEquals(Optional.empty(), health.get());
  }

  @Test
  void aNodeThatForwardsNothingOrChecksNothingIsAlwaysACandidate() {
    StoreHealth.Probe failing =
        () -> {
          throw new IOException("unreachable");
        };
    for (StoreHealth health :
        new StoreHealth[] {
          new StoreHealth(false, INTERVAL, TIMEOUT, failing, nanos::get),
          new StoreHealth(true, INTERVAL, Duration.ZERO, failing, nanos::get)
        }) {
      health.start();
      health.check();
      advance(Duration.ofHours(1));
      assertEquals(Optional.empty(), health.get());
      health.stop();
    }
  }

  /** Successful reads do not clear publication write failures. */
  @Test
  void failingPublicationWritesMakeANodeNoCandidateUntilOneLandsOrTheTimeoutPasses()
      throws Exception {
    AtomicReference<String> denied = new AtomicReference<>();
    ObjectStore files = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager node =
        node(
            "node",
            withFaults(
                files,
                (method, key) -> {
                  boolean upload = method.equals("uploadIfAbsent") && key.contains("/wal/");
                  boolean logEntry = method.equals("putIfAbsent") && key.contains("/log/");
                  boolean manifest =
                      method.equals("compareAndSwap") && key.endsWith(ManifestStore.MANIFEST_FILE);
                  if ((upload && "upload".equals(denied.get()))
                      || (logEntry && "log".equals(denied.get()))
                      || (manifest && "manifest".equals(denied.get()))) {
                    throw new IOException("Access Denied");
                  }
                }));
    node.createRepository(PROJECT).close();
    StoreHealth health = health(node);
    ManifestStore project = node.manifestStore(PROJECT);

    assertEquals(3, StoreHealth.WRITE_FAILURES);
    denied.set("manifest");
    assertThrows(IOException.class, () -> project.publishEvents(List.of("{}")));
    denied.set("upload");
    assertThrows(IOException.class, () -> commit(node, "a pack the store refuses"));
    assertEquals(Optional.empty(), health.get(), "fewer failures than the limit are no outage");
    denied.set("log");
    assertThrows(IOException.class, () -> project.publishEvents(List.of("{}")));
    assertEquals(
        Optional.of("the last 3 publication writes to the store failed"), health.get());
    health.check();
    assertTrue(health.get().isPresent(), "a read that succeeds says nothing about writes");

    denied.set(null);
    project.publishEvents(List.of("{}"));
    assertEquals(Optional.empty(), health.get(), "a publication landed");

    denied.set("manifest");
    for (int i = 0; i < StoreHealth.WRITE_FAILURES; i++) {
      assertThrows(IOException.class, () -> project.publishEvents(List.of("{}")));
    }
    advance(TIMEOUT.minusSeconds(1));
    health.check();
    assertTrue(health.get().isPresent());
    advance(Duration.ofSeconds(1));
    health.check();
    assertEquals(Optional.empty(), health.get(), "no publication failed for the timeout");
    assertThrows(IOException.class, () -> project.publishEvents(List.of("{}")));
    assertEquals(
        Optional.of("the last 4 publication writes to the store failed"),
        health.get(),
        "no publication landed since");
  }

  @Test
  void failingManifestCreationsMakeANodeNoCandidate() throws Exception {
    AtomicBoolean denied = new AtomicBoolean();
    WalGitRepositoryManager node =
        node(
            "node",
            withFaults(
                new FileObjectStore(root.resolve("store")),
                (method, key) -> {
                  if (denied.get()
                      && method.equals("putIfAbsent")
                      && key.endsWith(ManifestStore.MANIFEST_FILE)) {
                    throw new IOException("Access Denied");
                  }
                }));
    node.createRepository(PROJECT).close();
    StoreHealth health = health(node);

    denied.set(true);
    for (int i = 0; i < StoreHealth.WRITE_FAILURES; i++) {
      ManifestStore created = node.storage().manifestStore(new RepositoryId("created" + i));
      assertThrows(IOException.class, created::create);
    }
    assertEquals(
        Optional.of("the last 3 publication writes to the store failed"), health.get());
  }

  /** A later publication clears failures reported concurrently. */
  @Test
  void concurrentWriteFailuresAreAllCounted() throws Exception {
    StoreHealth health = new StoreHealth(true, INTERVAL, TIMEOUT, () -> {}, nanos::get);
    health.check();
    int threads = 8;
    int each = 500;
    CountDownLatch start = new CountDownLatch(1);
    Thread[] reporters = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      reporters[t] =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < each; i++) {
                  health.writeFailed(RepositoryId.CATALOG, new IOException("Access Denied"));
                }
              });
      reporters[t].start();
    }
    start.countDown();
    for (Thread reporter : reporters) {
      reporter.join();
    }
    assertEquals(
        Optional.of("the last " + threads * each + " publication writes to the store failed"),
        health.get());

    health.published(RepositoryId.CATALOG, LogEntry.Kind.REF_UPDATE);
    assertEquals(Optional.empty(), health.get());
    for (int i = 1; i < StoreHealth.WRITE_FAILURES; i++) {
      health.writeFailed(RepositoryId.CATALOG, new IOException("Access Denied"));
      assertEquals(Optional.empty(), health.get(), "failures before the landing were cleared");
    }
  }

  /** CAS conflicts and existing log entries or manifests are not store failures. */
  @Test
  void lostRacesLeaveANodeACandidate() throws Exception {
    FileObjectStore files = new FileObjectStore(root.resolve("store"));
    WalGitRepositoryManager other = node("other", files);
    other.createRepository(PROJECT).close();
    ObjectId base = commit(other, "base");
    AtomicReference<String> racing = new AtomicReference<>();
    AtomicInteger races = new AtomicInteger();
    AtomicInteger existing = new AtomicInteger();
    WalGitRepositoryManager node =
        node(
            "node",
            withFaults(
                files,
                (method, key) -> {
                  if (method.equals("compareAndSwap")
                      && key.endsWith(ManifestStore.MANIFEST_FILE)
                      && !key.contains("/catalog/")
                      && races.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    if (racing.get() == null) {
                      other.manifestStore(PROJECT).publishEvents(List.of("{}"));
                    } else {
                      assertEquals(
                          RefUpdate.Result.NEW,
                          update(other, racing.get() + races.get(), base));
                    }
                  }
                  if (method.equals("putIfAbsent")
                      && key.contains("/log/")
                      && existing.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new ObjectAlreadyExistsException(key);
                  }
                }));
    StoreHealth health = health(node);
    AtomicInteger failed = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();
    node.storage()
        .observePublications(
            new StorageLayout.PublicationObserver() {
              @Override
              public void published(RepositoryId id, LogEntry.Kind kind) {}

              @Override
              public void conflicted(RepositoryId id) {
                conflicts.incrementAndGet();
              }

              @Override
              public void writeFailed(RepositoryId id, Exception failure) {
                failed.incrementAndGet();
              }
            });

    races.set(5);
    node.manifestStore(PROJECT).publishEvents(List.of("{}"));
    assertEquals(0, races.get());

    racing.set("refs/heads/theirs-");
    races.set(3);
    assertEquals(RefUpdate.Result.NEW, update(node, "refs/heads/mine", base));
    assertEquals(0, races.get());
    assertTrue(conflicts.get() > 0, "a ref transaction ran again");

    existing.set(StoreHealth.WRITE_FAILURES);
    for (int i = 0; i < StoreHealth.WRITE_FAILURES; i++) {
      assertThrows(
          ObjectAlreadyExistsException.class,
          () -> node.manifestStore(PROJECT).publishEvents(List.of("{}")));
    }

    for (int i = 0; i < StoreHealth.WRITE_FAILURES; i++) {
      assertFalse(node.manifestStore(PROJECT).create(), "the manifest already exists");
    }

    assertEquals(0, failed.get());
    assertEquals(Optional.empty(), health.get());
  }

  @FunctionalInterface
  private interface Fault {
    void before(String method, String key) throws Exception;
  }

  /** {@code delegate}, where {@code fault} sees every call by key first and may fail it. */
  private static ObjectStore withFaults(ObjectStore delegate, Fault fault) {
    return (ObjectStore)
        Proxy.newProxyInstance(
            ObjectStore.class.getClassLoader(),
            new Class<?>[] {ObjectStore.class},
            (proxy, method, args) -> {
              if (args != null && args.length > 0 && args[0] instanceof String key) {
                fault.before(method.getName(), key);
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private WalGitRepositoryManager node(String name, ObjectStore store) {
    Config config = new Config();
    config.setString("walgerrit", null, "manifestRevalidateInterval", "0");
    return new WalGitRepositoryManager(
        WalGitConfiguration.from(config, root.resolve(name)),
        new StorageLayout(
            store, root.resolve(name + "-cache"), root.resolve(name + "-cursors"), ""));
  }

  /** Wires the catalog probe and publication observer as the daemon does. */
  private StoreHealth health(WalGitRepositoryManager node) {
    StoreHealth health =
        new StoreHealth(
            true,
            INTERVAL,
            TIMEOUT,
            () -> node.storage().manifestStore(RepositoryId.CATALOG).refreshIfPresent(),
            nanos::get);
    node.storage().observePublications(health);
    health.check();
    return health;
  }

  private static ObjectId commit(WalGitRepositoryManager node, String message) throws Exception {
    try (Repository repo = node.openRepository(PROJECT)) {
      return WalGitRepositoryManagerTest.insertCommit(repo, message);
    }
  }

  private static RefUpdate.Result update(WalGitRepositoryManager node, String ref, ObjectId id)
      throws IOException {
    try (Repository repo = node.openRepository(PROJECT)) {
      RefUpdate update = repo.updateRef(ref);
      update.setNewObjectId(id);
      return update.update();
    }
  }

  private void advance(Duration by) {
    nanos.addAndGet(by.toNanos());
  }
}
