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

import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.time.Duration;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRefDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.lib.RefDatabase;

/** A normal JGit DFS repository whose durable files are published through a WalGit manifest. */
final class LocalWalGitRepository extends DfsRepository {
  private final LocalWalGitObjectDatabase objectDatabase;
  private final DfsRefDatabase refDatabase;
  private volatile String gitwebDescription;

  LocalWalGitRepository(Project.NameKey name, ManifestStore manifestStore) throws IOException {
    this(name, manifestStore, WalGitConfiguration.DEFAULT_MANIFEST_REVALIDATE_INTERVAL);
  }

  LocalWalGitRepository(
      Project.NameKey name, ManifestStore manifestStore, Duration revalidateInterval)
      throws IOException {
    super(
        new Builder()
            .setRepositoryDescription(new DfsRepositoryDescription(name.get())));
    objectDatabase = new LocalWalGitObjectDatabase(this, manifestStore, revalidateInterval);
    refDatabase = new LocalWalGitRefDatabase(this, objectDatabase);
  }

  @Override
  public DfsObjDatabase getObjectDatabase() {
    return objectDatabase;
  }

  @Override
  public RefDatabase getRefDatabase() {
    return refDatabase;
  }

  /** Callers asking for a rescan get one conditional manifest read, then JGit's cache reset. */
  @Override
  public void scanForRepoChanges() throws IOException {
    objectDatabase.revalidateNow();
    super.scanForRepoChanges();
  }

  @Override
  public String getGitwebDescription() {
    return gitwebDescription;
  }

  @Override
  public void setGitwebDescription(String description) {
    // Gerrit's authoritative project description lives in refs/meta/config. A DFS repository has
    // no description file, but Gerrit still invokes this optional JGit compatibility API while
    // creating and updating a project. Match Gerrit's InMemoryRepository behavior so that the
    // compatibility write cannot prevent the Git-backed project update.
    gitwebDescription = description;
  }

  ManifestStore manifestStore() {
    return objectDatabase.manifestStore();
  }

  private static final class Builder
      extends DfsRepositoryBuilder<Builder, LocalWalGitRepository> {
    @Override
    public LocalWalGitRepository build() {
      throw new UnsupportedOperationException("Use LocalWalGitRepository's project-aware constructor");
    }
  }
}
