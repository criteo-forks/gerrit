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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.group.db.GroupConfig;
import com.google.gerrit.server.ssh.SshKeyCache;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evicts the Gerrit caches that a ref update replayed from another node makes stale.
 *
 * <p>Most of Gerrit's caches need nothing here: accounts, external ids, change notes, project
 * configs and the like are keyed by the tip of the ref they derive from, or are evicted by the
 * indexers the tailer already drives. Two are keyed by something else and are evicted on the
 * writing node only:
 *
 * <ul>
 *   <li>{@code sshkeys}, keyed by username, derived from the user's {@code refs/users/} ref;
 *   <li>the group membership caches ({@code groups_bymember}, {@code groups_bysubgroup},
 *       {@code groups_byname}, {@code groups}), keyed by member, subgroup, name and legacy id and
 *       derived from {@code refs/groups/} refs.
 * </ul>
 *
 * <p>For a group the members and subgroups of both the old and the new revision are evicted, so a
 * removal is forgotten as well as an addition. This mirrors what the multi-site plugin's cache
 * eviction topic achieves, using the WAL entry itself as the trigger.
 */
final class ReplicatedCacheEvictions {
  private static final Logger logger = LoggerFactory.getLogger(ReplicatedCacheEvictions.class);

  /** Loads a group as it was at one commit of its {@code refs/groups/} ref. */
  interface GroupSnapshots {
    Optional<InternalGroup> load(AccountGroup.UUID group, ObjectId commit) throws IOException;
  }

  private final Function<Account.Id, Optional<String>> usernames;
  @Nullable private SshKeyCache sshKeys;
  private final GroupCache groups;
  private final GroupIncludeCache groupIncludes;
  private final GroupSnapshots snapshots;

  @Inject
  ReplicatedCacheEvictions(
      AllUsersName allUsers,
      GitRepositoryManager repositories,
      AccountCache accounts,
      GroupCache groups,
      GroupIncludeCache groupIncludes) {
    this(
        id -> accounts.get(id).flatMap(AccountState::userName),
        null,
        groups,
        groupIncludes,
        (group, commit) -> {
          try (Repository repository = repositories.openRepository(allUsers)) {
            return GroupConfig.loadForGroupSnapshot(allUsers, repository, group, commit)
                .getLoadedGroup();
          } catch (ConfigInvalidException invalid) {
            throw new IOException(invalid);
          }
        });
  }

  ReplicatedCacheEvictions(
      Function<Account.Id, Optional<String>> usernames,
      @Nullable SshKeyCache sshKeys,
      GroupCache groups,
      GroupIncludeCache groupIncludes,
      GroupSnapshots snapshots) {
    this.usernames = usernames;
    this.sshKeys = sshKeys;
    this.groups = groups;
    this.groupIncludes = groupIncludes;
    this.snapshots = snapshots;
  }

  /**
   * The daemon binds the SSH key cache; init and reindex, which load this library too, do not.
   * Optional so the applier can be created there.
   */
  @Inject(optional = true)
  void setSshKeyCache(SshKeyCache sshKeys) {
    this.sshKeys = sshKeys;
  }

  /** The user's {@code refs/users/} ref changed: SSH keys live there, cached by username. */
  void accountChanged(Account.Id account) {
    if (sshKeys == null) {
      return;
    }
    usernames.apply(account).ifPresent(sshKeys::evict);
  }

  /**
   * The group's {@code refs/groups/} ref moved from {@code before} to {@code after}; either may be
   * null or zero when the group was created or deleted.
   */
  void groupChanged(AccountGroup.UUID group, ObjectId before, ObjectId after) {
    groups.evict(group);
    groupIncludes.evictParentGroupsOf(group);
    Set<Account.Id> members = new LinkedHashSet<>();
    Set<AccountGroup.UUID> subgroups = new LinkedHashSet<>();
    for (ObjectId commit : new ObjectId[] {before, after}) {
      if (commit == null || ObjectId.zeroId().equals(commit)) {
        continue;
      }
      try {
        snapshots
            .load(group, commit)
            .ifPresent(
                snapshot -> {
                  groups.evict(snapshot.getId());
                  groups.evict(snapshot.getNameKey());
                  members.addAll(snapshot.getMembers());
                  subgroups.addAll(snapshot.getSubgroups());
                });
      } catch (IOException | RuntimeException unreadable) {
        logger.warn(
            "WalGerrit cannot read group {} at {} to evict its membership caches",
            group.get(),
            commit.name(),
            unreadable);
      }
    }
    members.forEach(groupIncludes::evictGroupsWithMember);
    subgroups.forEach(groupIncludes::evictParentGroupsOf);
  }
}
