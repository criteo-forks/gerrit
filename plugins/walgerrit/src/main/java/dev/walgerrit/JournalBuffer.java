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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Events waiting to be published, grouped by the repository whose log they belong in. */
final class JournalBuffer {
  private final Map<Project.NameKey, List<String>> pending = new LinkedHashMap<>();

  /** Adds one event and returns how many now wait for that repository. */
  synchronized int add(Project.NameKey project, String eventJson) {
    List<String> batch = pending.computeIfAbsent(project, ignored -> new ArrayList<>());
    batch.add(eventJson);
    return batch.size();
  }

  /** Takes everything that waits, in arrival order per repository. */
  synchronized Map<Project.NameKey, List<String>> drain() {
    Map<Project.NameKey, List<String>> drained = new LinkedHashMap<>(pending);
    pending.clear();
    return drained;
  }

  synchronized boolean isEmpty() {
    return pending.isEmpty();
  }
}
