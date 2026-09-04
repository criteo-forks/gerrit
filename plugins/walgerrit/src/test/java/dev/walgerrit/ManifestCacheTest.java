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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.ManifestCache.VersionedManifest;
import dev.walgerrit.proto.StorageProto.Manifest;
import org.junit.jupiter.api.Test;

class ManifestCacheTest {
  @Test
  void validationIsRecordedPerKeyAndForgottenWithTheManifest() {
    ManifestCache cache = new ManifestCache();
    cache.markValidated("repo", 1_000);
    assertFalse(cache.validatedWithin("repo", 60_000, 1_000), "nothing cached, nothing validated");

    cache.offer("repo", new VersionedManifest(Manifest.newBuilder().setRevision(1).build(), "v1"));
    assertFalse(cache.validatedWithin("repo", 60_000, 1_000), "cached but never compared");
    cache.markValidated("repo", 1_000);
    assertTrue(cache.validatedWithin("repo", 60_000, 30_000));
    assertFalse(cache.validatedWithin("repo", 60_000, 61_000), "too old");
    assertFalse(cache.validatedWithin("repo", 0, 1_000), "a zero age never counts");

    cache.evict("repo");
    cache.offer("repo", new VersionedManifest(Manifest.newBuilder().setRevision(2).build(), "v2"));
    assertFalse(cache.validatedWithin("repo", 60_000, 1_000), "eviction forgets the validation");
  }
}
