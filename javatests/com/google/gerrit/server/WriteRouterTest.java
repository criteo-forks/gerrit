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
package com.google.gerrit.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.Range;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class WriteRouterTest {
  private static final List<String> NODES = List.of("gerrit-0", "gerrit-1", "gerrit-2", "gerrit-3");

  @Test
  public void theOrderOfTheCandidatesDoesNotMatter() {
    List<String> reversed = new ArrayList<>(NODES);
    Collections.reverse(reversed);
    for (int i = 0; i < 500; i++) {
      String project = "team-" + i % 7 + "/repo-" + i;
      assertThat(preferred(project, reversed)).isEqualTo(preferred(project, NODES));
    }
  }

  @Test
  public void aNodeThatLeavesMovesOnlyItsProjects() {
    List<String> rest = NODES.subList(0, 3);
    int held = 0;
    for (int i = 0; i < 2000; i++) {
      String project = "team-" + i % 37 + "/repo-" + i;
      String before = preferred(project, NODES);
      if (before.equals("gerrit-3")) {
        held++;
      } else {
        assertWithMessage(project).that(preferred(project, rest)).isEqualTo(before);
      }
    }
    assertWithMessage("share of one node in four").that(held).isIn(Range.closed(400, 600));
  }

  @Test
  public void outranksIsAStrictOrder() {
    for (String project : List.of("a", "platform/monorepo", "All-Projects")) {
      for (String one : NODES) {
        assertThat(WriteRouter.outranks(project, one, one)).isFalse();
        for (String other : NODES) {
          if (!one.equals(other)) {
            assertThat(WriteRouter.outranks(project, one, other))
                .isNotEqualTo(WriteRouter.outranks(project, other, one));
          }
        }
      }
    }
  }

  private static String preferred(String project, List<String> nodes) {
    String best = nodes.get(0);
    for (String node : nodes) {
      if (WriteRouter.outranks(project, node, best)) {
        best = node;
      }
    }
    return best;
  }
}
