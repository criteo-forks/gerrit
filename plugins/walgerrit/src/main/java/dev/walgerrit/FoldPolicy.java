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

import java.time.Duration;

/**
 * How a repository's log is folded: runs of at least {@code segmentEntries} consecutive
 * single-entry segments are merged into one segment, at most {@code maxMergesPerPass} merges per
 * fold, and the oldest segments drop below the manifest's floor once they are older than
 * {@code retainFor} and at least {@code retainEntries} newer entries remain referenced. Both
 * retention conditions must hold before a segment is dropped. Folding never deletes an object.
 */
record FoldPolicy(int segmentEntries, Duration retainFor, long retainEntries, int maxMergesPerPass) {
  static final int DEFAULT_MERGES_PER_PASS = 4;

  static FoldPolicy of(WalGitConfiguration configuration) {
    return new FoldPolicy(
        configuration.logSegmentEntries(),
        configuration.logRetention(),
        configuration.logRetainEntries(),
        DEFAULT_MERGES_PER_PASS);
  }
}
