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

package com.google.gerrit.pgm.init;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitFlags;
import com.google.gerrit.pgm.init.api.Section;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.securestore.testing.InMemorySecureStore;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InitIndexTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private SitePaths site;
  private InitFlags flags;
  private InitIndex initIndex;

  @Before
  public void setUp() throws Exception {
    site = new SitePaths(temporaryFolder.newFolder().toPath());
    Files.createDirectories(site.etc_dir);
    Files.createDirectories(site.resolve("git"));
    InMemorySecureStore secureStore = new InMemorySecureStore();
    flags = new InitFlags(site, secureStore, ImmutableList.of(), false);
    flags.cfg.setString("gerrit", null, "basePath", "git");
    flags.autoStart = true;
    ConsoleUI ui = ConsoleUI.getInstance(true);
    Section.Factory sections =
        (name, subsection) -> new Section(flags, site, secureStore, ui, name, subsection);
    initIndex = new InitIndex(ui, sections, site, flags);
  }

  @Test
  public void marksIndexesReadyForEmptyLocalSite() throws Exception {
    initIndex.run();

    assertThat(Files.exists(site.index_dir.resolve("gerrit_index.config"))).isTrue();
    assertThat(flags.autoStart).isTrue();
  }

  @Test
  public void customDatabaseModuleDoesNotMarkEmptyLocalIndexesReady() throws Exception {
    flags.cfg.setString("gerrit", null, "installDbModule", "example.CustomRepositoryModule");

    initIndex.run();

    assertThat(Files.exists(site.index_dir.resolve("gerrit_index.config"))).isFalse();
    assertThat(flags.autoStart).isFalse();
  }

  @Test
  public void customDatabaseModulePreservesExistingIndexReadiness() throws Exception {
    initIndex.run();
    byte[] readiness = Files.readAllBytes(site.index_dir.resolve("gerrit_index.config"));
    flags.cfg.setString("gerrit", null, "installDbModule", "example.CustomRepositoryModule");

    initIndex.run();

    assertThat(Files.readAllBytes(site.index_dir.resolve("gerrit_index.config")))
        .isEqualTo(readiness);
    assertThat(flags.autoStart).isFalse();
  }
}
