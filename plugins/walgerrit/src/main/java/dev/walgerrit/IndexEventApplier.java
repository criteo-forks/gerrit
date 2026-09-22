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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.IndexUpdate;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import java.util.List;
import java.util.Set;

/** Consumer boundary for idempotently applying committed WAL entries to derived state. */
interface IndexEventApplier {
  /** What the catalog says now about a name a replayed catalog transaction touched. */
  record NamespaceChange(
      Project.NameKey name,
      RepositoryId id,
      Catalog.State state,
      @Nullable Project.NameKey movedTo) {}

  void apply(Project.NameKey project, RefTransaction transaction);

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
