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
 * A publication asked to supersede a pack the manifest no longer lists, so its inputs were already
 * replaced by another compaction. Nothing was committed; the caller's uploaded output is unreferenced
 * and may be deleted.
 */
final class StaleCompactionInputException extends IOException {
  private static final long serialVersionUID = 1L;

  StaleCompactionInputException(String packName) {
    super("Cannot supersede a pack that is no longer live: " + packName);
  }
}
