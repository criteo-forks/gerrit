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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-wide record of the newest manifest observed per repository.
 *
 * <p>Every {@link ManifestStore} on a node shares one cache, so a conditional read by any handle,
 * including the index-event tailer, lets every other open handle adopt the newer manifest without
 * another round trip. Entries only move forward in revision.
 */
final class ManifestCache {
  record VersionedManifest(Manifest manifest, String version) {}

  private final ConcurrentHashMap<String, VersionedManifest> latest = new ConcurrentHashMap<>();

  VersionedManifest get(String key) {
    return latest.get(key);
  }

  /** Records {@code candidate} unless a newer revision is already known; returns the newest. */
  VersionedManifest offer(String key, VersionedManifest candidate) {
    return latest.merge(
        key,
        candidate,
        (known, fresh) ->
            fresh.manifest().getRevision() >= known.manifest().getRevision() ? fresh : known);
  }

  void evict(String key) {
    latest.remove(key);
  }
}
