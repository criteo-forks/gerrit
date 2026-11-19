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

import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.IndexUpdate;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.util.List;
import java.util.Set;

/** Consumer boundary for idempotently applying committed WAL entries to derived state. */
interface IndexEventApplier {
  /** Replaces ordinary change documents from current NoteDb. Disjoint sets may run concurrently. */
  interface ChangeDocuments {
    void reindexChanges(Project.NameKey project, Set<Integer> changes);
  }

  /** What the catalog says now about a name a replayed catalog transaction touched. */
  record NamespaceChange(Project.NameKey name, RepositoryId id, Catalog.State state) {}

  void apply(Project.NameKey project, RefTransaction transaction);

  /**
   * What applying a transaction did about the changes it names: those of its own repository that
   * were indexed as the repository holds them or removed from the index, and those of other
   * repositories that this node's change index does not hold.
   */
  record Applied(Set<Integer> indexed, Set<Integer> unindexed) {}

  /**
   * Applies {@code transaction} as {@link #apply} does and reports what it did. A star or draft in
   * the users repository names a change of another repository; the logs of the two have no common
   * order, and the change may be deleted. Such a change is not a failure: the replay of its own
   * repository indexes it with its stars and drafts as they are then. An applier that cannot tell
   * reports none.
   */
  default Applied applyReporting(Project.NameKey project, RefTransaction transaction) {
    apply(project, transaction);
    return new Applied(Set.of(), Set.of());
  }

  /**
   * Indexes {@code change} as its repository holds it now, when the change index holds it. False
   * when it does not.
   */
  default boolean reindexIfIndexed(int change) {
    return false;
  }

  /**
   * Reconciles derived state with the catalog for the named projects: caches, the project index
   * and the change index converge to what the names are bound to now, whatever transitions led
   * there. Runs on every node, the one that made the change included.
   */
  default void namespaceChanged(List<NamespaceChange> changes) {}

  /**
   * Reindexes documents another node reindexed with no ref update behind them. Changes in {@code
   * alreadyReindexed} were reindexed from ref transactions of the same sweep and are skipped.
   */
  default void reindex(Project.NameKey project, IndexUpdate update, Set<Integer> alreadyReindexed) {}
}
