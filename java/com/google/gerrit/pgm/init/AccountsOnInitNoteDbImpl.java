// Copyright (C) 2023 The Android Open Source Project
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.pgm.init.api.AllUsersNameOnInitProvider;
import com.google.gerrit.pgm.init.api.GitRepositoryManagerOnInit;
import com.google.gerrit.pgm.init.api.InitFlags;
import com.google.gerrit.server.GerritPersonIdentProvider;
import com.google.gerrit.server.account.AccountDelta;
import com.google.gerrit.server.account.AccountProperties;
import com.google.gerrit.server.account.storage.notedb.AccountsNoteDbRepoReader;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

public class AccountsOnInitNoteDbImpl implements AccountsOnInit {
  private final InitFlags flags;
  private final String allUsers;
  private final GitRepositoryManagerOnInit repositoryManager;

  @Inject
  AccountsOnInitNoteDbImpl(
      InitFlags flags,
      AllUsersNameOnInitProvider allUsers,
      GitRepositoryManagerOnInit repositoryManager) {
    this.flags = flags;
    this.allUsers = allUsers.get();
    this.repositoryManager = repositoryManager;
  }

  @Override
  public Account insert(Account.Builder account) throws IOException {
    try (Repository repo = repositoryManager.openRepository(Project.nameKey(allUsers));
        ObjectInserter oi = repo.newObjectInserter()) {
      PersonIdent ident =
          new PersonIdent(new GerritPersonIdentProvider(flags.cfg).get(), account.registeredOn());

      Config accountConfig = new Config();
      AccountProperties.writeToAccountConfig(
          AccountDelta.builder()
              .setActive(!account.inactive())
              .setFullName(account.fullName())
              .setPreferredEmail(account.preferredEmail())
              .setStatus(account.status())
              .build(),
          accountConfig);

      DirCache newTree = DirCache.newInCore();
      DirCacheEditor editor = newTree.editor();
      final ObjectId blobId = oi.insert(Constants.OBJ_BLOB, accountConfig.toText().getBytes(UTF_8));
      editor.add(
          new DirCacheEditor.PathEdit(AccountProperties.ACCOUNT_CONFIG) {
            @Override
            public void apply(DirCacheEntry ent) {
              ent.setFileMode(FileMode.REGULAR_FILE);
              ent.setObjectId(blobId);
            }
          });
      editor.finish();

      ObjectId treeId = newTree.writeTree(oi);

      CommitBuilder cb = new CommitBuilder();
      cb.setTreeId(treeId);
      cb.setCommitter(ident);
      cb.setAuthor(ident);
      cb.setMessage("Create Account");
      ObjectId id = oi.insert(cb);
      oi.flush();

      String refName = RefNames.refsUsers(account.id());
      RefUpdate ru = repo.updateRef(refName);
      ru.setExpectedOldObjectId(ObjectId.zeroId());
      ru.setNewObjectId(id);
      ru.setRefLogIdent(ident);
      ru.setRefLogMessage("Create Account", false);
      RefUpdate.Result result = ru.update();
      if (result != RefUpdate.Result.NEW) {
        throw new IOException(String.format("Failed to update ref %s: %s", refName, result.name()));
      }
      account.setMetaId(id.name());
      account.setUniqueTag(id.name());
    }
    return account.build();
  }

  @Override
  public boolean hasAnyAccount() throws IOException {
    try (Repository repo = repositoryManager.openRepository(Project.nameKey(allUsers))) {
      return AccountsNoteDbRepoReader.hasAnyAccount(repo);
    } catch (RepositoryNotFoundException e) {
      return false;
    }
  }
}
