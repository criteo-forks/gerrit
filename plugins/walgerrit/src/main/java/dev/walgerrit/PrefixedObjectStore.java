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

/** Repository-scoped view over a shared object store. */
final class PrefixedObjectStore implements ObjectStore {
  private final ObjectStore delegate;
  private final String prefix;

  PrefixedObjectStore(ObjectStore delegate, String prefix) {
    this.delegate = delegate;
    this.prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
  }

  @Override
  public Optional<StoredObject> get(String key) throws IOException {
    return delegate.get(full(key));
  }

  @Override
  public ConditionalRead getIfChanged(String key, String knownVersion) throws IOException {
    return delegate.getIfChanged(full(key), knownVersion);
  }

  @Override
  public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
    return delegate.putIfAbsent(full(key), bytes);
  }

  @Override
  public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
      throws IOException {
    return delegate.compareAndSwap(full(key), expectedVersion, bytes);
  }

  @Override
  public void uploadIfAbsent(String key, Path source) throws IOException {
    delegate.uploadIfAbsent(full(key), source);
  }

  @Override
  public void download(String key, Path target) throws IOException {
    delegate.download(full(key), target);
  }

  @Override
  public void delete(String key) throws IOException {
    delegate.delete(full(key));
  }

  @Override
  public List<ObjectSummary> listWithVersions(String keyPrefix) throws IOException {
    return delegate.listWithVersions(full(keyPrefix)).stream()
        .map(
            summary ->
                new ObjectSummary(
                    summary.key().substring(prefix.length()),
                    summary.version(),
                    summary.lastModifiedEpochMillis()))
        .toList();
  }

  private String full(String key) {
    return prefix + key;
  }
}
