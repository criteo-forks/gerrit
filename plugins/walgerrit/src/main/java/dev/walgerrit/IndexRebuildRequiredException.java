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

/**
 * A node's index cursor for a repository can no longer be advanced by replaying the WAL: it is
 * below the manifest's retention floor, ahead of the manifest's head, or names a transaction the
 * manifest does not, which happens when a manifest is restored to an older version and diverges.
 * The remedy is to rebuild the node's indexes from current repository state and reseed cursors.
 */
final class IndexRebuildRequiredException extends IOException {
  private static final long serialVersionUID = 1L;

  IndexRebuildRequiredException(String repositoryName, String reason) {
    super(
        "Index replay for "
            + repositoryName
            + " cannot continue: "
            + reason
            + "; this node's indexes must be rebuilt from current repository state");
  }
}
