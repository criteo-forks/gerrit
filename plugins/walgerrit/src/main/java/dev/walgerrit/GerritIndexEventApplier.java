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

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.index.group.GroupIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Applies one committed WAL ref transaction to Gerrit's node-local derived state. */
@Singleton
final class GerritIndexEventApplier implements IndexEventApplier {
  private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";

  private final Project.NameKey allUsers;
  private final ChangeIndexer changeIndexer;
  private final AccountIndexer accountIndexer;
  private final GroupIndexer groupIndexer;
  private final ProjectCache projectCache;
  private final ProjectIndexer projectIndexer;
  private final ChangeNotes.Factory changeNotesFactory;
  private final Provider<InternalChangeQuery> changeQuery;
  private final OneOffRequestContext requestContext;

  @Inject
  GerritIndexEventApplier(
      AllUsersName allUsers,
      ChangeIndexer changeIndexer,
      AccountIndexer accountIndexer,
      GroupIndexer groupIndexer,
      ProjectCache projectCache,
      ProjectIndexer projectIndexer,
      ChangeNotes.Factory changeNotesFactory,
      Provider<InternalChangeQuery> changeQuery,
      OneOffRequestContext requestContext) {
    this.allUsers = allUsers;
    this.changeIndexer = changeIndexer;
    this.accountIndexer = accountIndexer;
    this.groupIndexer = groupIndexer;
    this.projectCache = projectCache;
    this.projectIndexer = projectIndexer;
    this.changeNotesFactory = changeNotesFactory;
    this.changeQuery = changeQuery;
    this.requestContext = requestContext;
  }

  @Override
  public void apply(Project.NameKey project, RefTransaction transaction) {
    try (ManualRequestContext ignored = requestContext.open()) {
      applyInContext(project, transaction);
    }
  }

  private void applyInContext(Project.NameKey project, RefTransaction transaction) {
    Set<Change.Id> projectChanges = new LinkedHashSet<>();
    Set<Change.Id> deletedProjectChanges = new LinkedHashSet<>();
    Map<Change.Id, Boolean> allUsersChanges = new LinkedHashMap<>();
    Set<Account.Id> accounts = new LinkedHashSet<>();
    Set<AccountGroup.UUID> groups = new LinkedHashSet<>();
    Set<BranchNameKey> branches = new LinkedHashSet<>();
    boolean projectConfigChanged = false;

    for (RefUpdate update : transaction.getUpdatesList()) {
      if (isNoOp(update) || !update.getNewSymbolicTarget().isEmpty()) {
        continue;
      }
      String ref = update.getName();
      if (RefNames.REFS_CONFIG.equals(ref)) {
        projectConfigChanged = true;
      }
      if (ref.startsWith(RefNames.REFS_HEADS)) {
        branches.add(BranchNameKey.create(project, ref));
      }

      if (project.equals(allUsers)) {
        collectAllUsersUpdate(update, allUsersChanges, accounts, groups);
      } else if (isChangeMetadataRef(ref)) {
        Change.Id id = Change.Id.fromRef(ref);
        if (id != null) {
          projectChanges.add(id);
          if (ref.endsWith(RefNames.META_SUFFIX)) {
            if (isZero(update.getNewObjectId())) {
              deletedProjectChanges.add(id);
            } else {
              deletedProjectChanges.remove(id);
            }
          }
        }
      }
    }

    if (projectConfigChanged) {
      projectCache.refreshProjectList();
      projectCache.evict(project);
      projectIndexer.index(project);
    }

    for (Change.Id change : projectChanges) {
      if (deletedProjectChanges.contains(change)) {
        changeIndexer.delete(change);
      } else {
        changeIndexer.index(project, change);
      }
    }
    for (Map.Entry<Change.Id, Boolean> change : allUsersChanges.entrySet()) {
      try {
        changeIndexer.index(changeNotesFactory.createCheckedUsingIndexLookup(change.getKey()));
      } catch (NoSuchChangeException missing) {
        if (!change.getValue()) {
          // All-Users and project WAL streams have no global ordering. A newly created change may
          // not have reached this node's change index yet, so leave the cursor unacknowledged and
          // retry this transaction on the next sweep.
          throw missing;
        }
        // A draft/star deletion may race with deletion of the owning change in another repository.
        // There is no document left to update in that case.
      }
    }
    accounts.forEach(accountIndexer::index);
    groups.forEach(groupIndexer::index);

    Set<Change.Id> alreadyIndexed = projectChanges;
    for (BranchNameKey branch : branches) {
      for (ChangeData change : changeQuery.get().byBranchNew(branch)) {
        if (!alreadyIndexed.contains(change.getId())) {
          changeIndexer.index(change.project(), change.getId());
        }
      }
    }
    if (projectConfigChanged) {
      for (ChangeData change : changeQuery.get().byProjectOpen(project)) {
        if (!alreadyIndexed.contains(change.getId())) {
          changeIndexer.index(change.project(), change.getId());
        }
      }
    }
  }

  private static void collectAllUsersUpdate(
      RefUpdate update,
      Map<Change.Id, Boolean> changes,
      Set<Account.Id> accounts,
      Set<AccountGroup.UUID> groups) {
    String ref = update.getName();
    Change.Id changeId = Change.Id.fromAllUsersRef(ref);
    if (changeId != null) {
      changes.merge(changeId, isZero(update.getNewObjectId()), Boolean::logicalAnd);
    }
    if (RefNames.isRefsUsers(ref) && !RefNames.isRefsEdit(ref)) {
      Account.Id accountId = Account.Id.fromRef(ref);
      if (accountId != null) {
        accounts.add(accountId);
      }
    }
    AccountGroup.UUID group = AccountGroup.UUID.fromRef(ref);
    if (group != null) {
      groups.add(group);
    }
  }

  private static boolean isChangeMetadataRef(String ref) {
    return ref.startsWith(RefNames.REFS_CHANGES)
        && (ref.endsWith(RefNames.META_SUFFIX)
            || ref.endsWith(RefNames.ROBOT_COMMENTS_SUFFIX));
  }

  private static boolean isNoOp(RefUpdate update) {
    return update.getOldObjectId().equals(update.getNewObjectId())
        && update.getNewSymbolicTarget().isEmpty();
  }

  private static boolean isZero(String objectId) {
    return objectId.isEmpty() || objectId.equals(ZERO_OBJECT_ID);
  }
}
