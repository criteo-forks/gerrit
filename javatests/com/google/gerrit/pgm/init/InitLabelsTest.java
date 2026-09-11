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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.pgm.init.api.AllProjectsConfig;
import com.google.gerrit.pgm.init.api.AllProjectsNameOnInitProvider;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class InitLabelsTest {
  @Mock ConsoleUI ui;
  @Mock AllProjectsConfig allProjectsConfig;
  @Mock AllProjectsNameOnInitProvider allProjectsName;

  private InitLabels initLabels;

  @Before
  public void setUp() {
    initLabels = new InitLabels(ui, allProjectsConfig, allProjectsName);
  }

  @Test
  public void doesNotReadLocalConfigBeforeConfiguredManagerIsAvailable() throws Exception {
    initLabels.run();

    verifyNoInteractions(allProjectsConfig, ui);
  }

  @Test
  public void preservesExistingVerifiedLabel() throws Exception {
    Config config = new Config();
    config.setString("label", "Verified", "value", "+2 Custom verification");
    when(allProjectsConfig.load()).thenReturn(allProjectsConfig);
    when(allProjectsConfig.getConfig()).thenReturn(config);

    initLabels.postRun();

    verifyNoInteractions(ui, allProjectsName);
  }

  @Test
  public void offersMissingLabelAfterConfiguredManagerIsAvailable() throws Exception {
    when(allProjectsConfig.load()).thenReturn(allProjectsConfig);
    when(allProjectsConfig.getConfig()).thenReturn(new Config());

    initLabels.postRun();

    verify(ui).yesno(false, "Install Verified label");
  }
}
