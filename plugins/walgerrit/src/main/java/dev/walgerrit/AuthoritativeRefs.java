// Copyright (C) 2026 The Android Open Source Project
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

import com.google.gerrit.entities.Project;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.BiFunction;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Gerrit's hook for a ref as the store holds it now, by project name and ref name; see {@link
 * WalGitRepositoryManager#storedRef}. A store that cannot be read fails the call.
 */
@Singleton
final class AuthoritativeRefs implements BiFunction<String, String, Optional<String>> {
  private final WalGitRepositoryManager repositories;

  @Inject
  AuthoritativeRefs(WalGitRepositoryManager repositories) {
    this.repositories = repositories;
  }

  @Override
  public Optional<String> apply(String project, String ref) {
    try {
      return repositories.storedRef(Project.nameKey(project), ref).map(ObjectId::name);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
