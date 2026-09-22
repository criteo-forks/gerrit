// Copyright (C) 2026 Criteo
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

package com.criteo.gerrit.renameproject;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import java.io.IOException;
import org.kohsuke.args4j.Argument;

@RequiresCapability(RenameProjectCapability.RENAME_PROJECT)
final class RenameCommand extends SshCommand {
  @Argument(index = 0, required = true, metaVar = "OLD", usage = "project to rename")
  private String oldName;

  @Argument(index = 1, required = true, metaVar = "NEW", usage = "new name for the project")
  private String newName;

  private final WalGerritNamespace namespace;

  @Inject
  RenameCommand(WalGerritNamespace namespace) {
    this.namespace = namespace;
  }

  @Override
  protected void run() throws UnloggedFailure {
    try {
      namespace.rename(Project.nameKey(oldName), Project.nameKey(newName));
    } catch (IOException failure) {
      throw die(failure.getMessage());
    }
    stdout.println("Renamed " + oldName + " to " + newName);
  }
}
