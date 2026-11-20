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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.server.WriteEligibility.Decision;
import com.google.gerrit.server.WriteEligibility.Placement;
import org.junit.Test;

public class WriteEligibilityTest {
  private static final String JSON = "application/json";

  @Test
  public void readsAreNeitherForwardedNorCounted() {
    for (Placement placement : Placement.values()) {
      for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
        Decision read = rest(placement, method, "/a/changes/team%2Frepo~42/detail", null, null);
        assertThat(read.write()).isFalse();
        assertThat(read.eligible()).isFalse();
      }
      Decision fetch =
          WriteEligibility.http(
              placement, "GET", "/team/repo/info/refs", "service=git-upload-pack", null, -1, false);
      assertThat(fetch.write()).isFalse();
      Decision upload =
          WriteEligibility.http(
              placement, "POST", "/team/repo/git-upload-pack", null, "x", 4, false);
      assertThat(upload.write()).isFalse();
    }
  }

  @Test
  public void aProjectPlacementRanksByTheProjectTheAddressNames() {
    assertKey(Placement.PROJECT, "POST", "/a/changes/team%2Frepo~42/revisions/1/review", "team/repo");
    assertKey(Placement.PROJECT, "DELETE", "/a/changes/team%2Frepo~42/topic", "team/repo");
    assertKey(Placement.PROJECT, "PUT", "/projects/team%2Frepo/branches/stable", "team/repo");
    assertKey(Placement.PROJECT, "POST", "/a/team/repo.git/git-receive-pack", "team/repo");
    assertLocal(Placement.PROJECT, "POST", "/a/changes/42/revisions/1/review", "no_project");
    assertLocal(Placement.PROJECT, "POST", "/a/changes/I0123456789/submit", "no_project");
    assertLocal(Placement.PROJECT, "POST", "/a/changes/", "no_project");
    assertLocal(Placement.PROJECT, "PUT", "/a/accounts/self/name", "no_project");
    assertLocal(Placement.PROJECT, "PUT", "/a/groups/reviewers/members/7", "no_project");
  }

  @Test
  public void aClusterPlacementRanksEveryWriteAlike() {
    String key = key(Placement.CLUSTER, "POST", "/a/changes/42/revisions/1/review");
    assertThat(key).isNotNull();
    for (String[] write :
        new String[][] {
          {"POST", "/a/changes/"},
          {"POST", "/changes/I0123456789/submit"},
          {"DELETE", "/a/changes/team%2Frepo~42/topic"},
          {"PUT", "/a/accounts/self/name"},
          {"PUT", "/accounts/self/starred.changes/42"},
          {"PUT", "/a/groups/reviewers/members/7"},
          {"PUT", "/a/projects/team%2Frepo/config"},
          {"POST", "/a/team/repo/git-receive-pack"},
        }) {
      assertWithMessage("%s %s", write[0], write[1])
          .that(key(Placement.CLUSTER, write[0], write[1]))
          .isEqualTo(key);
    }
    assertThat(WriteEligibility.sshReceivePack(Placement.CLUSTER, "team/repo").key()).isEqualTo(key);
    assertThat(WriteEligibility.sshReceivePack(Placement.PROJECT, "team/repo").key())
        .isEqualTo("team/repo");
  }

  @Test
  public void aClusterPlacementKeepsWhatAPluginServesWhereItArrives() {
    for (String[] write :
        new String[][] {
          {"POST", "/a/changes/team%2Frepo~42/owners~check"},
          {"POST", "/a/changes/42/revisions/current/checks~rerun"},
          {"PUT", "/a/projects/team%2Frepo/replication~start"},
          {"POST", "/a/changes/42/owners%7Echeck"},
          {"POST", "/a/changes/42/owner%73~check/"},
          {"DELETE", "/a/accounts/self/quota~limits"},
          // A plugin's collection, its members and their views.
          {"POST", "/a/projects/team%2Frepo/checks~checkers/"},
          {"PUT", "/a/projects/team%2Frepo/checks~checkers/build"},
          {"POST", "/a/projects/team%2Frepo/checks~checkers/build/run"},
          {"DELETE", "/a/changes/42/revisions/1/checks~checks/lint"},
          {"POST", "/a/groups/reviewers/members/7/audit~note"},
        }) {
      assertLocal(Placement.CLUSTER, write[0], write[1], "plugin");
    }
    // The id of a resource can hold a tilde, and a core view answers to its qualified name.
    for (String[] write :
        new String[][] {
          {"PUT", "/accounts/self/starred.changes/team%2Frepo~42"},
          {"POST", "/a/changes/team%2Frepo~master~I0123456789/gerrit~submit"},
          {"PUT", "/a/projects/team%2Frepo/branches/feature~1"},
          {"DELETE", "/a/changes/42/revisions/1/drafts/a~b"},
        }) {
      assertKey(Placement.CLUSTER, write[0], write[1], WriteEligibility.CLUSTER_KEY);
    }
    assertKey(Placement.PROJECT, "POST", "/a/changes/team%2Frepo~42/owners~check", "team/repo");
    assertKey(
        Placement.PROJECT, "PUT", "/a/projects/team%2Frepo/checks~checkers/build", "team/repo");
  }

  @Test
  public void nodeLocalAndSessionBoundEndpointsStayWhereTheyArrive() {
    for (Placement placement : Placement.values()) {
      assertLocal(placement, "POST", "/a/config/server/caches/projects/flush", "config");
      assertLocal(placement, "DELETE", "/a/config/server/tasks/1a2b", "config");
      assertLocal(placement, "PUT", "/config/server/email.confirm", "config");
      assertLocal(placement, "POST", "/a/plugins/replication/gerrit~reload", "plugins");
      assertLocal(placement, "PUT", "/a/plugins/x.jar", "plugins");
      String files = "/a/changes/team%2Frepo~42/revisions/1/files/";
      assertLocal(placement, "PUT", files + "src%2FMain.java/reviewed", "reviewed");
      assertLocal(placement, "DELETE", files + "src%2FMain.java/reviewed", "reviewed");
      assertLocal(placement, "PUT", files + "src%2FMain.java/reviewed/", "reviewed");
      assertLocal(placement, "PUT", files + "src%2FMain.java/gerrit~reviewed", "reviewed");
      assertLocal(placement, "PUT", files + "src%2FMain.java/review%65d", "reviewed");
      assertLocal(placement, "PUT", files + "a/gerrit%7Ereviewed", "reviewed");
      assertLocal(placement, "POST", "/a/accounts/self/external.ids:delete", "external_ids");
      assertLocal(placement, "POST", "/accounts/1000001/external.ids%3Adelete", "external_ids");
      assertLocal(placement, "POST", "/a/changes/team%2Frepo~42/index", "node_local");
      assertLocal(placement, "POST", "/a/projects/team%2Frepo/index.changes", "node_local");
      assertLocal(placement, "POST", "/a/projects/team%2Frepo/gc", "node_local");
      assertLocal(placement, "POST", "/a/accounts/self/index", "node_local");
      assertLocal(placement, "POST", "/login/", "endpoint");
      assertLocal(placement, "POST", "/a/access/", "endpoint");
      assertLocal(placement, "POST", "/", "endpoint");
    }
  }

  @Test
  public void aQueryThatCarriesATokenOrOverridesTheRequestStaysHere() {
    String review = "/a/changes/team%2Frepo~42/revisions/1/review";
    for (Placement placement : Placement.values()) {
      for (String query :
          new String[] {
            "access_token=secret",
            "notify=NONE&access_token=secret",
            "acce%73s_token=secret",
            "$m=PUT",
            "%24m=PUT",
            "$ct=application%2Fjson",
            "%24ct=application/json",
            "access_token",
          }) {
        assertWithMessage(query)
            .that(rest(placement, "POST", review, query, JSON).reason())
            .isEqualTo("token_query");
      }
      assertThat(rest(placement, "POST", review, "%ZZ=1", JSON).reason()).isEqualTo("query");
      assertThat(rest(placement, "DELETE", review, "notify=NONE&o=x", null).eligible()).isTrue();
    }
  }

  @Test
  public void formsStayHereAndEveryOtherBodyTravels() {
    String topic = "/a/changes/team%2Frepo~42/topic";
    for (Placement placement : Placement.values()) {
      for (String form :
          new String[] {
            "application/x-www-form-urlencoded",
            "Application/X-WWW-Form-Urlencoded; charset=UTF-8",
            "multipart/form-data; boundary=x",
          }) {
        assertThat(rest(placement, "POST", topic, null, form).reason()).isEqualTo("form");
      }
      for (String type :
          new String[] {JSON, "application/json; charset=UTF-8", "text/plain", "application/zip"}) {
        assertThat(rest(placement, "PUT", topic, null, type).eligible()).isTrue();
      }
      assertThat(
              WriteEligibility.http(placement, "DELETE", topic, null, null, -1, false).eligible())
          .isTrue();
      assertThat(WriteEligibility.http(placement, "POST", topic, null, null, 0, false).eligible())
          .isTrue();
    }
  }

  @Test
  public void anAmbiguousAddressStaysHere() {
    for (Placement placement : Placement.values()) {
      for (String path :
          new String[] {
            "//team/repo/git-receive-pack",
            "/a/projects/team%2Frepo/a%7Cb/../config",
            "/a/projects/team%2Frepo/./config",
            "/a/projects/team%252Frepo/config",
            "/a/projects/team;x/config",
            "/a/projects/team%5Crepo/config",
            "/a/projects/%ZZ/config",
            "/a/projects/team%00/config",
          }) {
        assertWithMessage(path)
            .that(rest(placement, "POST", path, null, JSON).reason())
            .isEqualTo("path");
      }
    }
  }

  @Test
  public void aPushNeedsItsOwnBodyTypeAndNoQuery() {
    for (Placement placement : Placement.values()) {
      String push = "/a/team/repo/git-receive-pack";
      String type = "application/x-git-receive-pack-request";
      assertThat(WriteEligibility.http(placement, "POST", push, null, type, -1, true).eligible())
          .isTrue();
      assertThat(WriteEligibility.http(placement, "POST", push, null, JSON, 2, false).reason())
          .isEqualTo("content_type");
      assertThat(WriteEligibility.http(placement, "POST", push, "x=y", type, 2, false).reason())
          .isEqualTo("query");
      String refs = "/team/repo/info/refs";
      String service = "service=git-receive-pack";
      assertThat(WriteEligibility.http(placement, "GET", refs, service, null, -1, false).eligible())
          .isTrue();
      assertThat(
              WriteEligibility.http(placement, "GET", refs, "a=b&" + service, null, -1, false)
                  .reason())
          .isEqualTo("query");
      assertThat(WriteEligibility.http(placement, "GET", refs, service, null, 4, false).reason())
          .isEqualTo("body");
      assertThat(
              WriteEligibility.http(placement, "POST", "/git-receive-pack", null, type, 4, false)
                  .reason())
          .isEqualTo("no_project");
    }
  }

  @Test
  public void placementIsClusterUnlessConfiguredOtherwise() {
    assertThat(Placement.parse(null)).isEqualTo(Placement.CLUSTER);
    assertThat(Placement.parse(" ")).isEqualTo(Placement.CLUSTER);
    assertThat(Placement.parse("project")).isEqualTo(Placement.PROJECT);
    assertThat(Placement.parse(" ProJect ")).isEqualTo(Placement.PROJECT);
    assertThat(Placement.parse(" Cluster ")).isEqualTo(Placement.CLUSTER);
    assertThat(assertThrows(IllegalArgumentException.class, () -> Placement.parse("pod")))
        .hasMessageThat()
        .contains("walgerrit.placement must be project or cluster");
  }

  private static Decision rest(
      Placement placement,
      String method,
      String path,
      @Nullable String query,
      @Nullable String type) {
    return WriteEligibility.http(placement, method, path, query, type, type == null ? -1 : 2, false);
  }

  @Nullable
  private static String key(Placement placement, String method, String path) {
    String type =
        path.endsWith("/git-receive-pack") ? "application/x-git-receive-pack-request" : JSON;
    return rest(placement, method, path, null, type).key();
  }

  private static void assertKey(Placement placement, String method, String path, String key) {
    assertWithMessage("%s %s", method, path).that(key(placement, method, path)).isEqualTo(key);
  }

  private static void assertLocal(Placement placement, String method, String path, String reason) {
    Decision decision = rest(placement, method, path, null, JSON);
    assertWithMessage("%s %s under %s", method, path, placement)
        .that(decision.reason())
        .isEqualTo(reason);
    assertThat(decision.eligible()).isFalse();
    assertThat(decision.write()).isTrue();
  }
}
