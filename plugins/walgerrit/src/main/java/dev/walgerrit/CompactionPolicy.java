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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Decides what a compaction pass rewrites, from the manifest alone.
 *
 * <p>Object packs follow Git's geometric repack: the smallest packs are rolled into one whenever
 * the ascending size progression no longer grows by {@code geometricFactor} at every step, provided
 * at least {@code minPacks} of them are involved; packs above {@code maxPackSize} are never
 * rewritten.
 *
 * <p>Reftables are merged from the top of the stack down. Every table a ref transaction wrote
 * ({@code INSERT} or {@code RECEIVE}) is included, together with the {@code COMPACT} tables directly
 * beneath them that are no larger than {@code smallReftableSize}; a large compacted table stays as a
 * base. That respects the order JGit derives for the stack, in which compacted tables always sort
 * below transaction tables: a merge that left a transaction table underneath its own output would
 * let stale values shadow newer ones. The merge runs once {@code minReftables} tables qualify, and
 * once the whole stack is {@code maxReftables} deep the bases are merged too.
 */
record CompactionPolicy(
    int minPacks,
    int geometricFactor,
    long maxPackSize,
    int minReftables,
    long smallReftableSize,
    int maxReftables) {
  private static final Set<String> OBJECT_SOURCES = Set.of("INSERT", "RECEIVE", "COMPACT");
  private static final Set<String> TRANSACTION_SOURCES = Set.of("INSERT", "RECEIVE");
  private static final String PACK_EXTENSION = "pack";
  private static final String REFTABLE_EXTENSION = "ref";

  CompactionPolicy(int minPacks, int geometricFactor, long maxPackSize, int minReftables) {
    this(
        minPacks,
        geometricFactor,
        maxPackSize,
        minReftables,
        WalGitConfiguration.DEFAULT_COMPACT_SMALL_REFTABLE_SIZE,
        WalGitConfiguration.DEFAULT_COMPACT_MAX_REFTABLES);
  }

  /** What one pass rewrites: object packs by name, and reftables by name. */
  record Plan(List<String> packs, List<String> reftables) {
    static final Plan NOTHING = new Plan(List.of(), List.of());

    boolean isEmpty() {
      return packs.isEmpty() && reftables.isEmpty();
    }
  }

  static CompactionPolicy of(WalGitConfiguration configuration) {
    return new CompactionPolicy(
        configuration.compactMinPacks(),
        configuration.compactGeometricFactor(),
        configuration.compactMaxPackSize(),
        configuration.compactMinReftables(),
        configuration.compactSmallReftableSize(),
        configuration.compactMaxReftables());
  }

  Plan plan(Manifest manifest) {
    List<PackRef> candidates =
        manifest.getPacksList().stream()
            .filter(CompactionPolicy::isObjectPack)
            .filter(pack -> packSize(pack) <= maxPackSize)
            .sorted(
                Comparator.comparingLong(CompactionPolicy::packSize).thenComparing(PackRef::getName))
            .toList();
    int split =
        geometricSplit(candidates.stream().mapToLong(CompactionPolicy::packSize).toArray());
    List<String> packs =
        split >= minPacks
            ? candidates.subList(0, split).stream().map(PackRef::getName).toList()
            : List.of();
    return new Plan(packs, reftablesToMerge(manifest));
  }

  /** The reftables one pass merges, in stack order; empty when the stack is shallow enough. */
  List<String> reftablesToMerge(Manifest manifest) {
    List<PackRef> stack = reftableStack(manifest);
    if (stack.size() >= maxReftables) {
      return stack.stream().map(PackRef::getName).toList();
    }
    int from = stack.size();
    while (from > 0) {
      PackRef table = stack.get(from - 1);
      if (!TRANSACTION_SOURCES.contains(table.getSource())
          && reftableSize(table) > smallReftableSize) {
        break;
      }
      from--;
    }
    List<PackRef> top = stack.subList(from, stack.size());
    return top.size() >= minReftables ? top.stream().map(PackRef::getName).toList() : List.of();
  }

  /**
   * The stack as JGit orders it: compacted tables first, then transaction tables, each by update
   * index.
   */
  static List<PackRef> reftableStack(Manifest manifest) {
    List<PackRef> tables = new ArrayList<>();
    for (PackRef pack : manifest.getPacksList()) {
      if (hasReftable(pack)) {
        tables.add(pack);
      }
    }
    tables.sort(
        Comparator.comparingInt((PackRef pack) -> TRANSACTION_SOURCES.contains(pack.getSource()) ? 1 : 0)
            .thenComparingLong(PackRef::getMaxUpdateIndex)
            .thenComparingLong(PackRef::getMinUpdateIndex)
            .thenComparingLong(PackRef::getLastModifiedEpochMillis));
    return tables;
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
    return fileSize(pack, PACK_EXTENSION);
  }

  static long reftableSize(PackRef pack) {
    return fileSize(pack, REFTABLE_EXTENSION);
  }

  private static long fileSize(PackRef pack, String extension) {
    return pack.getFilesList().stream()
        .filter(file -> file.getExtension().equals(extension))
        .mapToLong(PackFile::getSize)
        .findFirst()
        .orElse(0);
  }

  private static boolean hasFile(PackRef pack, String extension) {
    return pack.getFilesList().stream().anyMatch(file -> file.getExtension().equals(extension));
  }
}
