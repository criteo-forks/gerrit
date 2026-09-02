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

import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Decides, from a manifest alone, what a compaction pass should rewrite.
 *
 * <p>Object packs follow Git's geometric repacking rule: sorted by size, every pack should be at
 * least {@code geometricFactor} times as large as all smaller packs combined. The smallest packs
 * that break the progression, extended upward until the progression holds again, are rolled up
 * into one pack, but only once at least {@code minPacks} of them have accumulated so a repository
 * is not rewritten after every write. Packs above {@code maxPackSize} are never rewritten. The
 * reftable stack is compacted whole once it is at least {@code minReftables} tables deep; a partial
 * merge would have to respect the update-index order JGit derives from the pack source and is not
 * worth the risk for tables that are small compared to object data.
 *
 * <p>Everything here is a function of the manifest's pack list, so evaluating the policy costs no
 * round trip and can run on the write path after a publication.
 */
record CompactionPolicy(int minPacks, int geometricFactor, long maxPackSize, int minReftables) {
  private static final Set<String> OBJECT_SOURCES = Set.of("INSERT", "RECEIVE", "COMPACT");
  private static final String PACK_EXTENSION = "pack";
  private static final String REFTABLE_EXTENSION = "ref";

  /** What one pass rewrites: object packs by name, and whether the reftable stack is merged. */
  record Plan(List<String> packs, boolean reftables) {
    static final Plan NOTHING = new Plan(List.of(), false);

    boolean isEmpty() {
      return packs.isEmpty() && !reftables;
    }
  }

  static CompactionPolicy of(WalGitConfiguration configuration) {
    return new CompactionPolicy(
        configuration.compactMinPacks(),
        configuration.compactGeometricFactor(),
        configuration.compactMaxPackSize(),
        configuration.compactMinReftables());
  }

  Plan plan(Manifest manifest) {
    List<PackRef> candidates =
        manifest.getPacksList().stream()
            .filter(CompactionPolicy::isObjectPack)
            .filter(pack -> packSize(pack) <= maxPackSize)
            .sorted(Comparator.comparingLong(CompactionPolicy::packSize).thenComparing(PackRef::getName))
            .toList();
    int split = geometricSplit(candidates.stream().mapToLong(CompactionPolicy::packSize).toArray());
    List<String> packs =
        split >= minPacks ? candidates.subList(0, split).stream().map(PackRef::getName).toList() : List.of();
    long reftables = manifest.getPacksList().stream().filter(CompactionPolicy::hasReftable).count();
    return new Plan(packs, reftables >= minReftables);
  }

  /**
   * Git's {@code repack --geometric} split over ascending sizes: the number of leading packs to
   * roll up into one so that every remaining pack is at least {@code geometricFactor} times the
   * combined size of everything below it. Zero when the progression already holds.
   */
  int geometricSplit(long[] sizes) {
    int count = sizes.length;
    if (count < 2) {
      return 0;
    }
    int i;
    for (i = count - 1; i > 0; i--) {
      if (sizes[i] < geometricFactor * sizes[i - 1]) {
        break;
      }
    }
    if (i == 0) {
      return 0;
    }
    int split = i + 1;
    long total = 0;
    for (int j = 0; j < split; j++) {
      total += sizes[j];
    }
    for (i = split; i < count; i++) {
      if (sizes[i] < geometricFactor * total) {
        total += sizes[i];
        split++;
      } else {
        break;
      }
    }
    return split;
  }

  static boolean isObjectPack(PackRef pack) {
    return OBJECT_SOURCES.contains(pack.getSource()) && hasFile(pack, PACK_EXTENSION);
  }

  static boolean hasReftable(PackRef pack) {
    return hasFile(pack, REFTABLE_EXTENSION);
  }

  static long packSize(PackRef pack) {
    return pack.getFilesList().stream()
        .filter(file -> file.getExtension().equals(PACK_EXTENSION))
        .mapToLong(PackFile::getSize)
        .findFirst()
        .orElse(0);
  }

  private static boolean hasFile(PackRef pack, String extension) {
    return pack.getFilesList().stream().anyMatch(file -> file.getExtension().equals(extension));
  }
}
