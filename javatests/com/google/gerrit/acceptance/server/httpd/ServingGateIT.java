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

package com.google.gerrit.acceptance.server.httpd;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.server.ServingGate;
import com.google.gerrit.server.index.change.ChangeIndexFreshness;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.multibindings.OptionalBinder;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.http.message.BasicHeader;
import org.junit.After;
import org.junit.Test;

/** A server whose backend keeps its serving gate closed, and after the gate opens. */
@UseSsh
public class ServingGateIT extends AbstractDaemonTest {
  private static final String CATCHING_UP = "This server is catching up. Please retry.";

  private static volatile LongSupplier searchGeneration = () -> 0L;
  private static volatile Optional<String> closed = Optional.empty();

  @Override
  public Module createModule() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        OptionalBinder.newOptionalBinder(binder(), ChangeIndexFreshness.SEARCH_KEY)
            .setBinding()
            .toInstance(() -> searchGeneration.getAsLong());
        OptionalBinder.newOptionalBinder(binder(), ServingGate.GATE_KEY)
            .setBinding()
            .toInstance(() -> closed);
      }
    };
  }

  @After
  public void openGate() {
    searchGeneration = () -> 0L;
    closed = Optional.empty();
  }

  @Test
  public void aClosedGateAnswersOnlyTheLivenessRequest() throws Exception {
    String change = createChange().getChangeId();
    closed = Optional.of(CATCHING_UP);

    anonymousRestSession.get(ServingGate.LIVENESS_PATH).assertOK();
    adminRestSession.get(ServingGate.LIVENESS_PATH).assertOK();
    anonymousRestSession
        .getWithHeaders("/livez", new BasicHeader("Authorization", "Basic malformed"))
        .assertOK();
    anonymousRestSession.head("/livez").assertOK();
    anonymousRestSession.get("/config/server/version").assertOK();
    anonymousRestSession.get("/livez/other").assertStatus(503);
    anonymousRestSession.post("/livez").assertStatus(503);

    // Index, repository, health-check and page requests.
    for (String path :
        new String[] {
          "/changes/?q=status:open",
          "/changes/" + change,
          "/" + project.get() + "/info/refs?service=git-upload-pack",
          "/config/server/healthcheck~status",
          "/config/server/info",
          "/"
        }) {
      RestResponse refused = anonymousRestSession.get(path);
      refused.assertStatus(503);
      assertThat(refused.getHeader("Retry-After")).isEqualTo("30");
      assertThat(refused.getEntityContent()).contains(CATCHING_UP);
    }
    adminRestSession.get("/changes/" + change).assertStatus(503);
    adminRestSession.post("/changes/" + change + "/abandon").assertStatus(503);
    assertThat(gApi.changes().id(change).get().status.name()).isEqualTo("NEW");

    adminSshSession.exec("gerrit version");
    adminSshSession.assertFailure(CATCHING_UP);
    adminSshSession.exec("gerrit query status:open");
    adminSshSession.assertFailure(CATCHING_UP);
    adminSshSession.exec("git-upload-pack " + project.get());
    adminSshSession.assertFailure(CATCHING_UP);
  }

  @Test
  public void staleChangeSearchRefusesRestAndSshAndResumesOnTheSameConnection() throws Exception {
    PushOneCommit.Result change = createChange();
    String number = change.getChange().getId().toString();
    String search = "/changes/?q=change:" + number;
    adminSshSession.exec("gerrit query change:" + number);
    adminSshSession.assertSuccess();
    searchGeneration = () -> -1L;

    RestResponse refused = anonymousRestSession.get(search);
    refused.assertStatus(503);
    assertThat(refused.getHeader("Retry-After")).isEqualTo("5");
    adminRestSession.get(search).assertStatus(503);
    adminRestSession.get("/changes/" + change.getChangeId()).assertStatus(503);
    assertThat(adminSshSession.exec("gerrit query --format=JSON change:" + number)).isEmpty();
    adminSshSession.assertFailure("Please retry");

    adminRestSession.get("/changes/" + project.get() + "~" + number + "/detail").assertOK();
    anonymousRestSession.get("/" + project.get() + "/info/refs?service=git-upload-pack").assertOK();
    anonymousRestSession.get("/livez").assertOK();

    searchGeneration = () -> 0L;
    adminRestSession.get(search).assertOK();
    assertThat(adminSshSession.exec("gerrit query --format=JSON change:" + number))
        .contains(change.getChangeId());
    adminSshSession.assertSuccess();
  }

  @Test
  public void aSearchCrossingARebuildReturnsNoResults() throws Exception {
    createChange();
    searchGeneration = new AtomicLong()::getAndIncrement;
    adminRestSession.get("/changes/?q=status:open").assertStatus(503);
    assertThat(adminSshSession.exec("gerrit query --format=JSON status:open")).isEmpty();
    adminSshSession.assertFailure("Please retry");
  }

  @Test
  public void anOpenedGateAnswersEverything() throws Exception {
    String change = createChange().getChangeId();
    closed = Optional.of(CATCHING_UP);
    adminRestSession.get("/changes/" + change).assertStatus(503);

    closed = Optional.empty();

    adminRestSession.get("/changes/" + change).assertOK();
    anonymousRestSession.get(ServingGate.LIVENESS_PATH).assertOK();
    anonymousRestSession.get("/" + project.get() + "/info/refs?service=git-upload-pack").assertOK();
    adminSshSession.exec("gerrit version");
    adminSshSession.assertSuccess();
    adminRestSession.post("/changes/" + change + "/abandon").assertOK();
  }
}
