// Copyright (C) 2016 The Android Open Source Project
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

import com.google.gerrit.pgm.init.api.AllUsersNameOnInitProvider;
import com.google.gerrit.pgm.init.api.GitRepositoryManagerOnInit;
import com.google.gerrit.pgm.init.api.InitFlags;
import com.google.gerrit.server.GerritPersonIdentProvider;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdFactoryNoteDbImpl;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdNotes;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.meta.MetaDataUpdate;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.Collection;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

public class ExternalIdsOnInit {
  private final InitFlags flags;
  private final AllUsersName allUsers;
  private final ExternalIdFactoryNoteDbImpl externalIdFactory;
  private final AuthConfig authConfig;
  private final GitRepositoryManagerOnInit repositoryManager;

  @Inject
  public ExternalIdsOnInit(
      InitFlags flags,
      AllUsersNameOnInitProvider allUsers,
      ExternalIdFactoryNoteDbImpl externalIdFactory,
      AuthConfig authConfig,
      GitRepositoryManagerOnInit repositoryManager) {
    this.flags = flags;
    this.allUsers = new AllUsersName(allUsers.get());
    this.externalIdFactory = externalIdFactory;
    this.authConfig = authConfig;
    this.repositoryManager = repositoryManager;
  }

  public synchronized void insert(String commitMessage, Collection<ExternalId> extIds)
      throws IOException, ConfigInvalidException {
    try (Repository allUsersRepo = repositoryManager.openRepository(allUsers)) {
      ExternalIdNotes extIdNotes =
          ExternalIdNotes.load(
              allUsers,
              allUsersRepo,
              externalIdFactory,
              authConfig.isUserNameCaseInsensitiveMigrationMode());
      extIdNotes.insert(extIds);
      try (MetaDataUpdate metaDataUpdate =
          new MetaDataUpdate(GitReferenceUpdated.DISABLED, allUsers, allUsersRepo)) {
        PersonIdent serverIdent = new GerritPersonIdentProvider(flags.cfg).get();
        metaDataUpdate.getCommitBuilder().setAuthor(serverIdent);
        metaDataUpdate.getCommitBuilder().setCommitter(serverIdent);
        metaDataUpdate.getCommitBuilder().setMessage(commitMessage);
        extIdNotes.commit(metaDataUpdate);
      }
    } catch (RepositoryNotFoundException e) {
      // Preserve init's behavior when All-Users has not been created yet.
    }
  }
}
