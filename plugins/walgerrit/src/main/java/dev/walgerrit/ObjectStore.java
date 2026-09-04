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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Versioned immutable-object storage; conditional writes are the only mutation primitive. */
interface ObjectStore {
  record StoredObject(byte[] bytes, String version) {}

  /**
   * One listing entry: the key, the same opaque version a read of that key would return, and the
   * time the object was last written, as the store reports it.
   */
  record ObjectSummary(String key, String version, long lastModifiedEpochMillis) {}

  /** Outcome of a conditional read; exactly one state applies. */
  record ConditionalRead(State state, StoredObject object) {
    enum State {
      /** The stored version equals the caller's known version; no body was transferred. */
      UNCHANGED,
      /** The object exists with a different version; {@code object} carries it. */
      CHANGED,
      /** No object exists under the key. */
      ABSENT
    }

    static ConditionalRead unchanged() {
      return new ConditionalRead(State.UNCHANGED, null);
    }

    static ConditionalRead changed(StoredObject object) {
      return new ConditionalRead(State.CHANGED, object);
    }

    static ConditionalRead absent() {
      return new ConditionalRead(State.ABSENT, null);
    }
  }

  Optional<StoredObject> get(String key) throws IOException;

  /**
   * Reads {@code key} only if its current version differs from {@code knownVersion}.
   *
   * <p>Stores with a native conditional GET answer {@link ConditionalRead.State#UNCHANGED} without
   * transferring the body. A {@code null} known version always fetches the object.
   */
  default ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
    Optional<StoredObject> current = get(key);
    if (current.isEmpty()) {
      return ConditionalRead.absent();
    }
    if (knownVersion != null && knownVersion.equals(current.get().version())) {
      return ConditionalRead.unchanged();
    }
    return ConditionalRead.changed(current.get());
  }

  StoredObject putIfAbsent(String key, byte[] bytes) throws IOException;

  StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
      throws IOException;

  void uploadIfAbsent(String key, Path source) throws IOException;

  void download(String key, Path target) throws IOException;

  /**
   * Bytes {@code [offset, offset + length)} of {@code key}. Object stores answer this with one
   * range read; the fallback reads the whole object. Missing keys and ranges beyond the object
   * are errors.
   */
  default byte[] getRange(String key, long offset, int length) throws IOException {
    StoredObject object =
        get(key).orElseThrow(() -> new IOException("Object not found: " + key));
    if (offset < 0 || length < 0 || offset + length > object.bytes().length) {
      throw new IOException("Range beyond object: " + key);
    }
    return java.util.Arrays.copyOfRange(object.bytes(), (int) offset, (int) (offset + length));
  }

  /**
   * Removes {@code key}; a missing key is not an error. On a versioned bucket this leaves a delete
   * marker, so the data stays recoverable for the bucket's non-current-version retention.
   */
  void delete(String key) throws IOException;

  /** Every key below {@code prefix}; the versions are discarded. */
  default List<String> list(String prefix) throws IOException {
    return listWithVersions(prefix).stream().map(ObjectSummary::key).toList();
  }

  /**
   * Lists every key below {@code prefix} with its current version, so a caller can find the
   * objects that changed without reading any of them. Object stores return this from the listing
   * itself; on S3 the version is the ETag of each listed object.
   */
  List<ObjectSummary> listWithVersions(String prefix) throws IOException;
}
