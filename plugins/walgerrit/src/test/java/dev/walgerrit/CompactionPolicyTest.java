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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.CompactionPolicy.Plan;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackFile;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactionPolicyTest {
  private final CompactionPolicy policy = new CompactionPolicy(8, 2, 1L << 30, 8);

  @Test
  void geometricSplitMatchesGitsRepackRule() {
    assertEquals(0, policy.geometricSplit(new long[] {}));
    assertEquals(0, policy.geometricSplit(new long[] {100}));
    // Already a geometric progression with factor two: nothing to roll up.
    assertEquals(0, policy.geometricSplit(new long[] {100, 250, 600, 1500}));
    // Equal sizes always violate the progression: everything rolls up.
    assertEquals(4, policy.geometricSplit(new long[] {1, 1, 1, 1}));
    // The two smallest violate; their sum (2) keeps 4 and 8 in progression, so only they roll up.
    assertEquals(2, policy.geometricSplit(new long[] {1, 1, 4, 8}));
    // Eight tiny packs sum to 80; the 80-byte pack above them is below 2 x 80 and joins the roll-up.
    assertEquals(9, policy.geometricSplit(new long[] {10, 10, 10, 10, 10, 10, 10, 10, 80, 400}));
  }

  @Test
  void nothingIsRolledUpBelowTheMinimumRun() {
    Manifest manifest = manifest(objectPacks("INSERT", 7, 100));
    assertTrue(policy.plan(manifest).isEmpty());
  }

  @Test
  void undersizedPacksRollUpOnceEnoughAccumulate() {
    Manifest manifest = manifest(objectPacks("INSERT", 8, 100));
    Plan plan = policy.plan(manifest);
    assertEquals(8, plan.packs().size());
    assertFalse(plan.reftables());
  }

  @Test
  void aCompactedPackJoinsTheRollUpWhenTheNewPackWouldRivalIt() {
    List<PackRef> packs = objectPacks("INSERT", 8, 10);
    packs.add(objectPack("compacted", "COMPACT", 80));
    packs.add(objectPack("big", "COMPACT", 400));
    Plan plan = policy.plan(manifest(packs));
    assertEquals(9, plan.packs().size());
    assertTrue(plan.packs().contains("compacted"));
    assertFalse(plan.packs().contains("big"));
  }

  @Test
  void packsAboveTheCeilingAndGarbageCollectorSourcesAreNeverRewritten() {
    CompactionPolicy capped = new CompactionPolicy(2, 2, 1000, 8);
    List<PackRef> packs = objectPacks("INSERT", 3, 100);
    packs.add(objectPack("huge", "INSERT", 5000));
    packs.add(objectPack("gc", "GC", 100));
    Plan plan = capped.plan(manifest(packs));
    assertEquals(3, plan.packs().size());
    assertFalse(plan.packs().contains("huge"));
    assertFalse(plan.packs().contains("gc"));
  }

  @Test
  void reftableStackCompactsAtTheConfiguredDepth() {
    List<PackRef> shallow = reftables(7);
    assertFalse(policy.plan(manifest(shallow)).reftables());
    List<PackRef> deep = reftables(8);
    Plan plan = policy.plan(manifest(deep));
    assertTrue(plan.reftables());
    assertTrue(plan.packs().isEmpty(), "reftables are not object packs");
  }

  private static Manifest manifest(List<PackRef> packs) {
    return Manifest.newBuilder().addAllPacks(packs).build();
  }

  private static List<PackRef> objectPacks(String source, int count, long size) {
    List<PackRef> packs = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      packs.add(objectPack(source.toLowerCase() + "-" + i, source, size));
    }
    return packs;
  }

  private static PackRef objectPack(String name, String source, long size) {
    return PackRef.newBuilder()
        .setName(name)
        .setSource(source)
        .addFiles(PackFile.newBuilder().setExtension("pack").setSize(size))
        .addFiles(PackFile.newBuilder().setExtension("idx").setSize(size / 4 + 1))
        .build();
  }

  private static List<PackRef> reftables(int count) {
    List<PackRef> packs = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      packs.add(
          PackRef.newBuilder()
              .setName("ref-" + i)
              .setSource("INSERT")
              .addFiles(PackFile.newBuilder().setExtension("ref").setSize(200))
              .build());
    }
    return packs;
  }
}
