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
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

/**
 * Runs a hook right before the first manifest compare-and-swap that matches a predicate over the
 * current and proposed manifests: the window in which another node's write makes that CAS fail.
 */
final class HookedObjectStore implements ObjectStore {
  final AtomicInteger matchedCasAttempts = new AtomicInteger();
  /** The manifest the first matching CAS proposed, whether or not it landed. */
  volatile Manifest firstMatchingProposal;
  /** Every uploaded key with the name of the thread that uploaded it, in order. */
  final List<String> uploads = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
  private final ObjectStore delegate;
  private final BiPredicate<Manifest, Manifest> matches;
  private final Runnable beforeFirstMatchingCas;

  HookedObjectStore(
      ObjectStore delegate,
      BiPredicate<Manifest, Manifest> matches,
      Runnable beforeFirstMatchingCas) {
    this.delegate = delegate;
    this.matches = matches;
    this.beforeFirstMatchingCas = beforeFirstMatchingCas;
  }

  /** A CAS that publishes a compaction: it adds a pack whose source is COMPACT. */
  static boolean publishesCompaction(Manifest current, Manifest proposed) {
    return publishesCompaction(current, proposed, null);
  }

  /** A CAS that publishes a compacted object pack. */
  static boolean publishesPackCompaction(Manifest current, Manifest proposed) {
    return publishesCompaction(current, proposed, "pack");
  }

  /** A CAS that publishes a compacted reftable stack. */
  static boolean publishesReftableCompaction(Manifest current, Manifest proposed) {
    return publishesCompaction(current, proposed, "ref");
  }

  private static boolean publishesCompaction(Manifest current, Manifest proposed, String extension) {
    return proposed.getPacksList().stream()
        .anyMatch(
            pack ->
                pack.getSource().equals("COMPACT")
                    && (extension == null
                        || pack.getFilesList().stream()
                            .anyMatch(file -> file.getExtension().equals(extension)))
                    && current.getPacksList().stream()
                        .noneMatch(existing -> existing.getName().equals(pack.getName())));
  }

  /** A CAS that publishes a ref transaction rather than objects or a compaction. */
  static boolean publishesRefChange(Manifest current, Manifest proposed) {
    return proposed.getRefRevision() > current.getRefRevision()
        && !publishesCompaction(current, proposed);
  }

  @Override
  public Optional<StoredObject> get(String key) throws IOException {
    return delegate.get(key);
  }

  @Override
  public StoredObject putIfAbsent(String key, byte[] bytes) throws IOException {
    return delegate.putIfAbsent(key, bytes);
  }

  @Override
  public StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
      throws IOException {
    if (key.endsWith(ManifestStore.MANIFEST_FILE)) {
      Manifest current = Manifest.parseFrom(delegate.get(key).orElseThrow().bytes());
      Manifest proposed = Manifest.parseFrom(bytes);
      if (matches.test(current, proposed) && matchedCasAttempts.getAndIncrement() == 0) {
        firstMatchingProposal = proposed;
        beforeFirstMatchingCas.run();
      }
    }
    return delegate.compareAndSwap(key, expectedVersion, bytes);
  }

  @Override
  public void uploadIfAbsent(String key, Path source) throws IOException {
    uploads.add(Thread.currentThread().getName() + " " + key);
    delegate.uploadIfAbsent(key, source);
  }

  @Override
  public void download(String key, Path target) throws IOException {
    delegate.download(key, target);
  }

  @Override
  public void delete(String key) throws IOException {
    delegate.delete(key);
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    return delegate.list(prefix);
  }

  @Override
  public List<ObjectSummary> listWithVersions(String prefix) throws IOException {
    return delegate.listWithVersions(prefix);
  }
}
