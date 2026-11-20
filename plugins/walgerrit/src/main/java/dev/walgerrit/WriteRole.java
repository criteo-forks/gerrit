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

import com.google.gerrit.metrics.Counter2;
import com.google.gerrit.metrics.Counter3;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Binding;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import dev.walgerrit.proto.StorageProto.LogEntry;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Tells the storage layer which node Gerrit prefers to execute a project's writes. Counts the log
 * entries this node publishes and the ref transactions it reruns, by repository class and by
 * whether it was the preferred executor, to show the writes request forwarding did not place.
 */
@Singleton
final class WriteRole implements StorageLayout.PublicationObserver {
  /** Matches the name and type of Gerrit's {@code WriteForwarding#EXECUTOR_KEY} binding. */
  static final Key<Predicate<String>> WRITE_EXECUTOR =
      Key.get(new TypeLiteral<Predicate<String>>() {}, Names.named("writeExecutor"));

  private final WalGitRepositoryManager repositories;
  private final String allUsers;
  private final String allProjects;
  private final Counter3<String, String, String> publications;
  private final Counter2<String, String> conflicts;

  @Inject
  WriteRole(GitRepositoryManager repositories, Injector injector, MetricMaker metrics) {
    this(asWalGit(repositories), executor(injector), metrics);
  }

  WriteRole(
      WalGitRepositoryManager repositories, Predicate<String> executor, MetricMaker metrics) {
    this.repositories = repositories;
    allUsers = repositories.configuration().allUsers().get();
    allProjects = repositories.configuration().allProjects().get();
    Field<String> repository =
        Field.ofString("repository", (metadata, value) -> {})
            .description("all_users, all_projects, catalog or other")
            .build();
    Field<String> preferred =
        Field.ofString("executor", (metadata, value) -> {})
            .description(
                "preferred when this node was the preferred executor of the repository's writes,"
                    + " other when a peer was")
            .build();
    publications =
        metrics.newCounter(
            "walgerrit/publications",
            new Description("Log entries this node published").setCumulative(),
            repository,
            Field.ofString("kind", (metadata, value) -> {})
                .description("ref_update, pack, compact, event, index or fence")
                .build(),
            preferred);
    conflicts =
        metrics.newCounter(
            "walgerrit/ref_transaction_conflicts",
            new Description(
                    "Ref transactions of this node that lost to another publication and ran again")
                .setCumulative(),
            repository,
            preferred);
    repositories.writeExecutor(executor);
    repositories.storage().observePublications(this);
  }

  @Override
  public void published(RepositoryId id, LogEntry.Kind kind) {
    publications.increment(repositoryClass(id), kind.name().toLowerCase(Locale.ROOT), role(id));
  }

  @Override
  public void conflicted(RepositoryId id) {
    conflicts.increment(repositoryClass(id), role(id));
  }

  private String role(RepositoryId id) {
    return repositories.executes(id) ? "preferred" : "other";
  }

  private String repositoryClass(RepositoryId id) {
    if (id.isCatalog()) {
      return "catalog";
    }
    String name = repositories.knownName(id);
    return name.equals(allUsers) ? "all_users" : name.equals(allProjects) ? "all_projects" : "other";
  }

  /** Gerrit's answer, or a node that executes everything when this build of Gerrit has none. */
  private static Predicate<String> executor(Injector injector) {
    Binding<Predicate<String>> binding = injector.getExistingBinding(WRITE_EXECUTOR);
    return binding == null ? project -> true : binding.getProvider().get();
  }

  private static WalGitRepositoryManager asWalGit(GitRepositoryManager repositories) {
    if (repositories instanceof WalGitRepositoryManager walGit) {
      return walGit;
    }
    throw new IllegalStateException(
        "WalGitIndexModule requires WalGitRepositoryManager, found "
            + repositories.getClass().getName());
  }
}
