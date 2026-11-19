// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.jgit.lib.ObjectId;

/**
 * A contiguous stretch of the WAL whose only index effects are replacements of change documents.
 */
record ChangeReplayBatch(int end, int transactions, Set<Integer> changes) {
  static final int MAX_ENTRIES = 16;
  private static final int MAX_UPDATES = 64;
  private static final String ZERO = ObjectId.zeroId().name();

  /** Returns null for a single ordinary update, a boundary, or a span with no documents. */
  static ChangeReplayBatch plan(List<LogEntry> entries, int start) {
    Set<Integer> changes = new LinkedHashSet<>();
    int transactions = 0;
    int updates = 0;
    int end = start;
    for (; end < entries.size() && end - start < MAX_ENTRIES; end++) {
      LogEntry entry = entries.get(end);
      if (entry.getEventJsonCount() != 0 || entry.hasIndexUpdate()) {
        break;
      }
      if (!entry.hasRefTransaction()
          && (entry.getKind() == LogEntry.Kind.PACK || entry.getKind() == LogEntry.Kind.COMPACT)) {
        continue;
      }
      if (entry.getKind() != LogEntry.Kind.REF_UPDATE || !entry.hasRefTransaction()) {
        break;
      }
      List<RefUpdate> refs = entry.getRefTransaction().getUpdatesList();
      updates += refs.size();
      if (updates > MAX_UPDATES || !collectChanges(refs, changes)) {
        break;
      }
      transactions++;
    }
    return changes.isEmpty() || (end - start == 1 && changes.size() == 1)
        ? null
        : new ChangeReplayBatch(end, transactions, changes);
  }

  private static boolean collectChanges(List<RefUpdate> refs, Set<Integer> changes) {
    Set<Integer> entryChanges = new LinkedHashSet<>();
    for (RefUpdate update : refs) {
      String ref = update.getName();
      String tip = update.getNewObjectId();
      if (!ref.startsWith(RefNames.REFS_CHANGES)
          || !update.getNewSymbolicTarget().isEmpty()
          || tip.isEmpty()
          || tip.equals(ZERO)) {
        return false;
      }
      Change.Id id = ChangeRefIds.fromRef(ref);
      if (id == null) {
        return false;
      }
      // Positive patch-set refs need no independent document. Meta and robot comment updates
      // read the current NoteDb state. Explicit deletions go through the ordered path.
      if (!tip.equals(update.getOldObjectId())
          && (ref.endsWith(RefNames.META_SUFFIX) || ref.endsWith(RefNames.ROBOT_COMMENTS_SUFFIX))) {
        entryChanges.add(id.get());
      }
    }
    changes.addAll(entryChanges);
    return true;
  }

  void apply(
      int workers,
      WalGitRepositoryManager repositories,
      RepositoryId id,
      Project.NameKey project,
      IndexEventApplier.ChangeDocuments documents)
      throws IOException {
    int count = Math.min(workers, changes.size());
    if (count == 1) {
      try (var applying = repositories.applyingReplay(id)) {
        documents.reindexChanges(project, changes);
      }
      return;
    }
    List<Set<Integer>> groups = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      groups.add(new LinkedHashSet<>());
    }
    int next = 0;
    for (int change : changes) {
      groups.get(next++ % count).add(change);
    }
    // One bounded set of workers per node. Closing joins even the siblings of a failed worker
    // before the tailer can release the replay lock or begin a retry or a rebuild.
    try (var pool =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("WalGerrit-Change-Replay-", 0).factory())) {
      List<Future<?>> pending = new ArrayList<>(count);
      for (Set<Integer> group : groups) {
        pending.add(
            pool.submit(
                () -> {
                  try (var handles = repositories.reuseReplayHandle(id);
                      var applying = repositories.applyingReplay(id)) {
                    documents.reindexChanges(project, group);
                  }
                }));
      }
      for (Future<?> job : pending) {
        job.get();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted indexing changes of " + project, interrupted);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Error error) {
        throw error;
      }
      throw new IOException("Could not index changes of " + project, failure.getCause());
    }
  }
}
