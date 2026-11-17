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
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.OwedMerge;
import java.io.IOException;
import java.util.List;

/**
 * What the tailer needs to know about merges to make up for a change-merged event that was lost
 * with the process that merged the change: which changes a ref transaction merged, which events an
 * entry of the log carries, and how to fire an event again.
 */
interface MergeEvents {
  /** What became of an event that was due. */
  enum Delivery {
    /** Fired on this node; the journal carries it to the log like any event of this node. */
    FIRED,
    /** The change is gone; there is nobody to tell about what. */
    GONE,
    /** The merge was not made by a submit; its event cannot be rebuilt and stays lost. */
    LOST
  }

  /** Tracks nothing: for the nodes and tests that have no events. */
  MergeEvents NONE =
      new MergeEvents() {
        @Override
        public List<OwedMerge> mergedBy(Project.NameKey project, LogEntry entry) {
          return List.of();
        }

        @Override
        public List<OwedMerge> withoutAnnounced(List<OwedMerge> owed, LogEntry entry) {
          return owed;
        }

        @Override
        public Delivery fire(Project.NameKey project, OwedMerge merge) {
          return Delivery.GONE;
        }
      };

  /**
   * The changes that the ref transaction of {@code entry} merged, each with what its change-merged
   * event says and the entry's sequence and writing process.
   *
   * @throws IOException when the repository cannot be read; the entry is then not passed
   */
  List<OwedMerge> mergedBy(Project.NameKey project, LogEntry entry) throws IOException;

  /**
   * {@code owed} without the merges whose change-merged event is among the events of {@code entry}.
   */
  List<OwedMerge> withoutAnnounced(List<OwedMerge> owed, LogEntry entry);

  /**
   * Fires the change-merged event of {@code merge} to this node's listeners, as the process that
   * merged the change would have. Called with no lock of the tailer held: a listener may write, and
   * wait for this node's indexes.
   *
   * @throws RuntimeException when the event could not be fired now; it is tried again later
   */
  Delivery fire(Project.NameKey project, OwedMerge merge);
}
