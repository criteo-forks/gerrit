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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Node-wide write coordination, one {@link GroupPublisher} per repository.
 *
 * <p>JGit's reftable batch update serializes writers through a lock on the {@code
 * DfsReftableDatabase} instance, which assumes one instance per repository per process. WalGerrit
 * opens a fresh handle per {@code openRepository} call, so the handles of one node share a
 * publisher instead: its lock serializes the validation and reftable write of ref transactions on
 * the repository, and its queue lands their publications in groups. Writers on other nodes are
 * still fenced by the manifest compare-and-swap, and a losing transaction retries against the newer
 * manifest.
 */
final class RepositoryLocks {
  private final ConcurrentHashMap<String, GroupPublisher> publishers = new ConcurrentHashMap<>();

  GroupPublisher publisherFor(String key) {
    return publishers.computeIfAbsent(key, ignored -> new GroupPublisher());
  }

  ReentrantLock forRepository(String key) {
    return publisherFor(key).nodeLock();
  }
}
