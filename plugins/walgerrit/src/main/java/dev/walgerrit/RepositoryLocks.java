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
 * Node-wide write locks, one per repository.
 *
 * <p>JGit's reftable batch update serializes writers through a lock on the {@code
 * DfsReftableDatabase} instance, which assumes one instance per repository per process. WalGerrit
 * opens a fresh handle per {@code openRepository} call, so the handles of one node share these
 * locks instead: within a node, ref transactions on a repository run one at a time and each one
 * starts from the manifest the previous one published. Writers on other nodes are still fenced by
 * the manifest compare-and-swap, and a losing transaction retries against the newer manifest.
 */
final class RepositoryLocks {
  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  ReentrantLock forRepository(String key) {
    return locks.computeIfAbsent(key, ignored -> new ReentrantLock());
  }
}
