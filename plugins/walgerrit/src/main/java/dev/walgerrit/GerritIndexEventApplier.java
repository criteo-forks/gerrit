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
import com.google.gerrit.server.git.GitRepositoryManager;
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
import com.google.common.cache.Cache;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import dev.walgerrit.proto.StorageProto.IndexUpdate;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies one committed WAL ref transaction to Gerrit's node-local derived state. */
@Singleton
final class GerritIndexEventApplier implements IndexEventApplier {
  private static final Logger logger = LoggerFactory.getLogger(GerritIndexEventApplier.class);
  private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";

  private final WalGitRepositoryManager repositories;
  private final Project.NameKey allUsers;
  private final ChangeIndexer changeIndexer;
  private final AccountIndexer accountIndexer;
  private final GroupIndexer groupIndexer;
  private final ProjectCache projectCache;
  private final ProjectIndexer projectIndexer;
  private final ChangeNotes.Factory changeNotesFactory;
  private final Provider<InternalChangeQuery> changeQuery;
  private final OneOffRequestContext requestContext;
  private final ReplicatedCacheEvictions evictions;
  private final Cache<Change.Id, String> changeIdProjectCache;

  @Inject
  GerritIndexEventApplier(
      GitRepositoryManager repositories,
      AllUsersName allUsers,
      ChangeIndexer changeIndexer,
      AccountIndexer accountIndexer,
      GroupIndexer groupIndexer,
      ProjectCache projectCache,
      ProjectIndexer projectIndexer,
      ChangeNotes.Factory changeNotesFactory,
      Provider<InternalChangeQuery> changeQuery,
      OneOffRequestContext requestContext,
      ReplicatedCacheEvictions evictions,
      @Named("changeid_project") Cache<Change.Id, String> changeIdProjectCache) {
    this.repositories = (WalGitRepositoryManager) repositories;
    this.allUsers = allUsers;
    this.changeIndexer = changeIndexer;
    this.accountIndexer = accountIndexer;
    this.groupIndexer = groupIndexer;
    this.projectCache = projectCache;
    this.projectIndexer = projectIndexer;
    this.changeNotesFactory = changeNotesFactory;
    this.changeQuery = changeQuery;
    this.requestContext = requestContext;
    this.evictions = evictions;
    this.changeIdProjectCache = changeIdProjectCache;
  }

  @Override
  public void apply(Project.NameKey project, RefTransaction transaction) {
    try (ManualRequestContext ignored = requestContext.open();
        EventReplay.Scope replaying = EventReplay.enter()) {
      applyInContext(project, transaction);
    }
  }

  @Override
  public void reindex(Project.NameKey project, IndexUpdate update, Set<Integer> alreadyReindexed) {
    try (ManualRequestContext ignored = requestContext.open();
        EventReplay.Scope replaying = EventReplay.enter()) {
      for (int number : update.getChangesList()) {
        if (!alreadyReindexed.contains(number)) {
          changeIndexer.index(project, Change.id(number));
        }
      }
      for (int id : update.getAccountsList()) {
        accountIndexer.index(Account.id(id));
      }
      for (String uuid : update.getGroupsList()) {
        groupIndexer.index(AccountGroup.uuid(uuid));
      }
      for (String name : update.getProjectsList()) {
        projectIndexer.index(Project.nameKey(name));
      }
    }
  }

  /**
   * Caches first, so nothing below reads a stale project; then, per name, what the catalog says:
   * an active name gets its project document and every change of its repository indexed under it,
   * which also replaces the documents an old name left, since a change's document is keyed by its
   * number; a deleted name loses its change documents and its project document; a name in flight
   * is left to the finalize that follows.
   */
  @Override
  public void namespaceChanged(List<NamespaceChange> changes) {
    try (ManualRequestContext ignored = requestContext.open();
        EventReplay.Scope replaying = EventReplay.enter()) {
      for (NamespaceChange change : changes) {
        projectCache.evict(change.name());
      }
      projectCache.refreshProjectList();
      changeIdProjectCache.invalidateAll();
      for (NamespaceChange change : changes) {
        switch (change.state()) {
          case PENDING -> {}
          case ACTIVE -> {
            projectIndexer.index(change.name());
            try (Repository repository = repositories.openRepository(change.name())) {
              for (Change.Id id : changeIds(repository)) {
                changeIndexer.index(change.name(), id);
              }
            } catch (IOException failure) {
              throw new UncheckedIOException(failure);
            }
          }
          case RETIRED -> {
            changeIndexer.deleteAllForProject(change.name());
            projectIndexer.index(change.name());
          }
        }
        logger.info(
            "WalGerrit reconciled {} with the catalog: {}{}",
            change.name().get(),
            change.state().name().toLowerCase(java.util.Locale.ROOT),
            change.movedTo() == null ? "" : ", moved to " + change.movedTo().get());
      }
    }
  }

  private static List<Change.Id> changeIds(Repository repository) throws IOException {
    List<Change.Id> ids = new ArrayList<>();
    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES)) {
      if (ref.getName().endsWith(RefNames.META_SUFFIX)) {
        Change.Id id = Change.Id.fromRef(ref.getName());
        if (id != null) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  private void applyInContext(Project.NameKey project, RefTransaction transaction) {
    Set<Change.Id> projectChanges = new LinkedHashSet<>();
    Set<Change.Id> deletedProjectChanges = new LinkedHashSet<>();
    Map<Change.Id, Boolean> allUsersChanges = new LinkedHashMap<>();
    Set<Account.Id> accounts = new LinkedHashSet<>();
    Map<AccountGroup.UUID, RefUpdate> groups = new LinkedHashMap<>();
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
    for (Account.Id account : accounts) {
      accountIndexer.index(account);
      evictions.accountChanged(account);
    }
    for (Map.Entry<AccountGroup.UUID, RefUpdate> group : groups.entrySet()) {
      evictions.groupChanged(
          group.getKey(),
          objectIdOrNull(group.getValue().getOldObjectId()),
          objectIdOrNull(group.getValue().getNewObjectId()));
      groupIndexer.index(group.getKey());
    }

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
      Map<AccountGroup.UUID, RefUpdate> groups) {
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
      groups.put(group, update);
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

  private static ObjectId objectIdOrNull(String objectId) {
    return isZero(objectId) ? null : ObjectId.fromString(objectId);
  }

  private static boolean isZero(String objectId) {
    return objectId.isEmpty() || objectId.equals(ZERO_OBJECT_ID);
  }
}
