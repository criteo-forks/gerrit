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
import dev.walgerrit.proto.StorageProto.LogRef;
import dev.walgerrit.proto.StorageProto.LogSegment;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Folding keeps the manifest bounded without losing anything a follower or a recovery needs. */
class ManifestStoreFoldTest {
  private static final Duration LONG = Duration.ofDays(365);

  @TempDir Path directory;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-02T10:00:00Z"));

  @Test
  void publishWritesSingleEntrySegmentsCarryingTransactionIdentity() throws Exception {
    ManifestStore store = store();
    assertTrue(store.create());
    store.publish(0, List.of(objectPack("p1")), List.of(), false);

    Manifest manifest = store.read();
    LogRef segment = manifest.getLogSegments(0);
    assertEquals(1, segment.getFirstSeq());
    assertEquals(1, segment.getLastSeq());
    assertFalse(segment.getLastTransactionId().isEmpty());
    assertTrue(segment.getKey().endsWith(segment.getLastTransactionId() + ".pb"));
    assertEquals(clock.millis(), segment.getLastCreatedAtEpochMillis());
    LogSegment stored = LogSegment.parseFrom(Files.readAllBytes(repository().resolve(segment.getKey())));
    assertEquals(1, stored.getEntriesCount());
    assertEquals(segment.getLastTransactionId(), stored.getEntries(0).getTransactionId());
    assertEquals(0, manifest.getMinSeq(), "nothing folded yet");
    assertEquals(1, ManifestStore.floor(manifest));
  }

  @Test
  void foldMergesRunsOfSingleEntrySegmentsAndKeepsEveryEntryReadable() throws Exception {
    ManifestStore store = store();
    assertTrue(store.create());
    Map<Long, String> transactionIds = publishMany(store, 7);
    Manifest before = store.read();
    assertEquals(7, before.getLogSegmentsCount());

    Manifest folded = store.fold(new FoldPolicy(3, LONG, 1_000_000, 4)).manifest();

    assertEquals(before.getRevision() + 1, folded.getRevision());
    assertEquals(before.getHeadSeq(), folded.getHeadSeq());
    assertEquals(before.getRefRevision(), folded.getRefRevision());
    assertEquals(0, folded.getMinSeq());
    assertEquals(List.of(1L, 4L, 7L), firstSeqs(folded));
    assertEquals(List.of(3L, 6L, 7L), lastSeqs(folded));
    LogRef merged = folded.getLogSegments(0);
    assertEquals(transactionIds.get(3L), merged.getLastTransactionId());
    assertTrue(merged.getKey().contains("-merged-") || merged.getKey().matches(".*/[0-9a-f]{16}-[0-9a-f]{16}-.*"));

    List<LogEntry> all = store.readLogEntriesAfter(0, "", folded);
    assertEquals(7, all.size());
    for (int i = 0; i < 7; i++) {
      assertEquals(i + 1, all.get(i).getSeq());
      assertEquals(transactionIds.get((long) (i + 1)), all.get(i).getTransactionId());
    }
    for (LogRef single : before.getLogSegmentsList()) {
      assertTrue(Files.isRegularFile(repository().resolve(single.getKey())), "folded objects remain");
    }
    assertEquals(
        folded.getRevision(),
        store.fold(new FoldPolicy(3, LONG, 1_000_000, 4)).manifest().getRevision(),
        "a second fold has nothing to do");
  }

  @Test
  void cursorsAreValidatedAtHeadAtBoundariesAndInsideSegments() throws Exception {
    ManifestStore store = store();
    assertTrue(store.create());
    Map<Long, String> ids = publishMany(store, 7);
    Manifest folded = store.fold(new FoldPolicy(3, LONG, 1_000_000, 4)).manifest();

    assertEquals(List.of(3L, 4L, 5L, 6L, 7L), seqs(store.readLogEntriesAfter(2, ids.get(2L), folded)));
    assertEquals(List.of(4L, 5L, 6L, 7L), seqs(store.readLogEntriesAfter(3, ids.get(3L), folded)));
    assertTrue(store.readLogEntriesAfter(7, ids.get(7L), folded).isEmpty());

    assertThrows(IndexRebuildRequiredException.class, () -> store.readLogEntriesAfter(2, "bogus", folded));
    assertThrows(IndexRebuildRequiredException.class, () -> store.readLogEntriesAfter(3, "bogus", folded));
    assertThrows(IndexRebuildRequiredException.class, () -> store.readLogEntriesAfter(7, "bogus", folded));
    assertThrows(IndexRebuildRequiredException.class, () -> store.readLogEntriesAfter(8, ids.get(7L), folded));
  }

  @Test
  void retentionDropsOldSegmentsFromTheManifestButNeverTheNewestOrTheObjects() throws Exception {
    ManifestStore store = store();
    assertTrue(store.create());
    Map<Long, String> ids = new HashMap<>();
    for (int i = 1; i <= 6; i++) {
      store.publish(0, List.of(objectPack("p" + i)), List.of(), false);
      ids.put((long) i, store.read().getLogSegments(i - 1).getLastTransactionId());
      clock.advance(Duration.ofHours(1));
    }
    Manifest before = store.read();
    // Now is 6 hours after the first publish; entries 1..6 were created at +0h .. +5h.
    Manifest folded = store.fold(new FoldPolicy(2, Duration.ofHours(4), 2, 10)).manifest();

    // Merged into [1-2][3-4][5-6]; [1-2] ends at +1h (old enough at now-4h = +2h, 4 newer
    // entries retained) and drops; [3-4] ends at +3h, which is not old enough.
    assertEquals(3, folded.getMinSeq());
    assertEquals(3, ManifestStore.floor(folded));
    assertEquals(List.of(3L, 5L), firstSeqs(folded));
    assertEquals(before.getHeadSeq(), folded.getHeadSeq());
    for (LogRef single : before.getLogSegmentsList()) {
      assertTrue(Files.isRegularFile(repository().resolve(single.getKey())), "folding deletes nothing");
    }

    assertEquals(
        List.of(4L, 5L, 6L),
        seqs(store.readLogEntriesAfter(3, ids.get(3L), folded)),
        "a cursor at the floor still replays exactly");
    assertThrows(
        IndexRebuildRequiredException.class,
        () -> store.readLogEntriesAfter(2, ids.get(2L), folded),
        "a cursor whose entry was folded away cannot be validated, so it rebuilds");
    assertThrows(IndexRebuildRequiredException.class, () -> store.readLogEntriesAfter(1, ids.get(1L), folded));
    assertThrows(IndexRebuildRequiredException.class, () -> store.readLogEntriesAfter(0, "", folded));

    clock.advance(Duration.ofDays(1));
    Manifest aggressive = store.fold(new FoldPolicy(2, Duration.ZERO, 0, 10)).manifest();
    assertEquals(1, aggressive.getLogSegmentsCount(), "the newest segment is never dropped");
    assertEquals(5, aggressive.getMinSeq());
    assertEquals(ids.get(6L), ManifestStore.lastTransactionId(aggressive));
  }

  @Test
  void foldRetriesAfterAConcurrentPublishAndReusesItsMergedSegment() throws Exception {
    Path repository = repository();
    ManifestStore other = new ManifestStore(repository, "repo", clock);
    AtomicBoolean raced = new AtomicBoolean();
    ObjectStore racing =
        new RacingStore(
            new FileObjectStore(repository),
            () -> {
              if (raced.compareAndSet(false, true)) {
                try {
                  other.publish(0, List.of(objectPack("concurrent")), List.of(), false);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              }
            });
    ManifestStore store = new ManifestStore(racing, repository, "repo", clock, ignored -> {});
    assertTrue(store.create());
    publishMany(store, 4);

    Manifest folded = store.fold(new FoldPolicy(4, LONG, 1_000_000, 4)).manifest();

    assertTrue(raced.get(), "the concurrent publish happened during the fold");
    assertEquals(5, folded.getHeadSeq(), "the concurrent entry is in the folded manifest");
    assertEquals(List.of(1L, 5L), firstSeqs(folded));
    assertEquals(5, store.readLogEntriesAfter(0, "", folded).size());
    try (Stream<Path> logs = Files.list(repository.resolve("log"))) {
      long mergedObjects =
          logs.filter(p -> p.getFileName().toString().matches("[0-9a-f]{16}-[0-9a-f]{16}-.*")).count();
      assertEquals(1, mergedObjects, "the retry reused the merged segment instead of writing another");
    }
  }

  private ManifestStore store() {
    return new ManifestStore(repository(), "repo", clock);
  }

  private Path repository() {
    return directory.resolve("repo.git");
  }

  private Map<Long, String> publishMany(ManifestStore store, int count) throws IOException {
    Map<Long, String> ids = new HashMap<>();
    for (int i = 1; i <= count; i++) {
      Manifest manifest = store.publish(0, List.of(objectPack("p" + i)), List.of(), false);
      ids.put((long) i, ManifestStore.lastTransactionId(manifest));
    }
    return ids;
  }

  private static List<Long> firstSeqs(Manifest manifest) {
    return manifest.getLogSegmentsList().stream().map(LogRef::getFirstSeq).toList();
  }

  private static List<Long> lastSeqs(Manifest manifest) {
    return manifest.getLogSegmentsList().stream().map(LogRef::getLastSeq).toList();
  }

  private static List<Long> seqs(List<LogEntry> entries) {
    return entries.stream().map(LogEntry::getSeq).toList();
  }

  private static PackRef objectPack(String name) {
    return PackRef.newBuilder()
        .setName(name)
        .setSource("INSERT")
        .addFiles(PackFile.newBuilder().setExtension("pack"))
        .build();
  }

  /** Runs an action just before the first manifest CAS it sees, simulating another writer. */
  private static final class RacingStore implements ObjectStore {
    private final ObjectStore delegate;
    private final Runnable beforeFirstCas;

    RacingStore(ObjectStore delegate, Runnable beforeFirstCas) {
      this.delegate = delegate;
      this.beforeFirstCas = beforeFirstCas;
    }

    @Override
    public Optional<StoredObject> get(String key) throws IOException {
      return delegate.get(key);
    }

    @Override
    public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
      return delegate.putIfAbsent(key, bytes);
    }

    @Override
    public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
        throws IOException {
      if (key.endsWith(ManifestStore.MANIFEST_FILE)) {
        beforeFirstCas.run();
      }
      return delegate.compareAndSwap(key, expectedVersion, bytes);
    }

    @Override
    public void uploadIfAbsent(String key, Path source) throws IOException {
      delegate.uploadIfAbsent(key, source);
    }

    @Override
    public void download(String key, Path target) throws IOException {
      delegate.download(key, target);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
      return delegate.list(prefix);
    }

    @Override
    public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
      return delegate.listWithVersions(prefix);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
