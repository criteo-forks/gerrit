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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import dev.walgerrit.proto.StorageProto.IndexUpdate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What this node did and has not journaled yet, grouped by the repository whose log it belongs in. */
final class JournalBuffer {
  /** One repository's pending batch: the events fired and the documents reindexed. */
  record Batch(List<String> events, @Nullable IndexUpdate index) {}

  private static final class Pending {
    final List<String> events = new ArrayList<>();
    final Set<Integer> changes = new LinkedHashSet<>();
    final Set<Integer> accounts = new LinkedHashSet<>();
    final Set<String> groups = new LinkedHashSet<>();
    final Set<String> projects = new LinkedHashSet<>();

    @Nullable
    IndexUpdate index() {
      if (changes.isEmpty() && accounts.isEmpty() && groups.isEmpty() && projects.isEmpty()) {
        return null;
      }
      return IndexUpdate.newBuilder()
          .addAllChanges(changes)
          .addAllAccounts(accounts)
          .addAllGroups(groups)
          .addAllProjects(projects)
          .build();
    }
  }

  private final Map<Project.NameKey, Pending> pending = new LinkedHashMap<>();

  /** Adds one event and returns how many now wait for that repository. */
  synchronized int add(Project.NameKey project, String eventJson) {
    List<String> events = of(project).events;
    events.add(eventJson);
    return events.size();
  }

  synchronized void addChange(Project.NameKey project, int number) {
    of(project).changes.add(number);
  }

  synchronized void addAccount(Project.NameKey allUsers, int id) {
    of(allUsers).accounts.add(id);
  }

  synchronized void addGroup(Project.NameKey allUsers, String uuid) {
    of(allUsers).groups.add(uuid);
  }

  synchronized void addProject(Project.NameKey allProjects, String name) {
    of(allProjects).projects.add(name);
  }

  /** Puts an index update back after a failed publication; it rides with the next batch. */
  synchronized void requeue(Project.NameKey project, IndexUpdate index) {
    Pending p = of(project);
    p.changes.addAll(index.getChangesList());
    p.accounts.addAll(index.getAccountsList());
    p.groups.addAll(index.getGroupsList());
    p.projects.addAll(index.getProjectsList());
  }

  /** Takes everything that waits, in arrival order per repository. */
  synchronized Map<Project.NameKey, Batch> drain() {
    Map<Project.NameKey, Batch> drained = new LinkedHashMap<>();
    pending.forEach((project, p) -> drained.put(project, new Batch(List.copyOf(p.events), p.index())));
    pending.clear();
    return drained;
  }

  synchronized boolean isEmpty() {
    return pending.isEmpty();
  }

  private Pending of(Project.NameKey project) {
    return pending.computeIfAbsent(project, ignored -> new Pending());
  }
}
