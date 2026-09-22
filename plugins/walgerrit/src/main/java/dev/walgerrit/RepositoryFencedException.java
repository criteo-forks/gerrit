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

/**
 * A publication was admitted under a write epoch the repository has since left, or the repository
 * is deleted. Nothing landed. Unlike a {@link ManifestConflictException} this is final: the writer
 * must resolve the name again and start over, since the repository may now be known by another
 * name, or by none.
 */
final class RepositoryFencedException extends IOException {
  private static final long serialVersionUID = 1L;

  RepositoryFencedException(String repository, long expectedWriteEpoch, Manifest current) {
    super(
        current.getDeleted()
            ? "Repository " + repository + " is deleted"
            : "Repository "
                + repository
                + " was fenced by namespace operation "
                + current.getFenceOperation()
                + ": write epoch "
                + current.getWriteEpoch()
                + ", publication admitted under "
                + expectedWriteEpoch);
  }
}
