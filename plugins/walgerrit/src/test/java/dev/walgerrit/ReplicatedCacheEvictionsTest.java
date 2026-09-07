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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.ssh.SshKeyCache;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class ReplicatedCacheEvictionsTest {
  private final List<String> calls = new ArrayList<>();
  private final SshKeyCache sshKeys = recording(SshKeyCache.class);
  private final GroupCache groups = recording(GroupCache.class);
  private final GroupIncludeCache groupIncludes = recording(GroupIncludeCache.class);

  private static final ObjectId BEFORE = ObjectId.fromString("1111111111111111111111111111111111111111");
  private static final ObjectId AFTER = ObjectId.fromString("2222222222222222222222222222222222222222");

  @Test
  void accountChangeEvictsTheUsersSshKeysByUsername() {
    ReplicatedCacheEvictions evictions =
        new ReplicatedCacheEvictions(
            id -> id.get() == 42 ? Optional.of("jdoe") : Optional.empty(),
            sshKeys,
            groups,
            groupIncludes,
            (group, commit) -> Optional.empty());

    evictions.accountChanged(Account.id(42));
    evictions.accountChanged(Account.id(43)); // no username: nothing cached under a name

    assertEquals(List.of("SshKeyCache.evict[jdoe]"), calls);
  }

  @Test
  void groupChangeEvictsOldAndNewMembersSubgroupsNamesAndIds() {
    AccountGroup.UUID uuid = AccountGroup.uuid("group-uuid");
    InternalGroup before =
        group(uuid, 7, "old-name", ImmutableSet.of(Account.id(1), Account.id(2)), ImmutableSet.of(AccountGroup.uuid("sub-a")));
    InternalGroup after =
        group(uuid, 7, "new-name", ImmutableSet.of(Account.id(2), Account.id(3)), ImmutableSet.of(AccountGroup.uuid("sub-b")));
    ReplicatedCacheEvictions evictions =
        new ReplicatedCacheEvictions(
            id -> Optional.empty(),
            sshKeys,
            groups,
            groupIncludes,
            (g, commit) -> Optional.of(commit.equals(BEFORE) ? before : after));

    evictions.groupChanged(uuid, BEFORE, AFTER);

    assertTrue(calls.contains("GroupCache.evict[group-uuid]"), calls.toString());
    assertTrue(calls.contains("GroupIncludeCache.evictParentGroupsOf[group-uuid]"), calls.toString());
    assertTrue(calls.contains("GroupCache.evict[old-name]"), calls.toString());
    assertTrue(calls.contains("GroupCache.evict[new-name]"), calls.toString());
    assertTrue(calls.contains("GroupCache.evict[7]"), calls.toString());
    for (int member : new int[] {1, 2, 3}) {
      assertTrue(calls.contains("GroupIncludeCache.evictGroupsWithMember[" + member + "]"), calls.toString());
    }
    assertTrue(calls.contains("GroupIncludeCache.evictParentGroupsOf[sub-a]"), calls.toString());
    assertTrue(calls.contains("GroupIncludeCache.evictParentGroupsOf[sub-b]"), calls.toString());
    // Member 2 is in both revisions and is evicted once.
    assertEquals(1, calls.stream().filter("GroupIncludeCache.evictGroupsWithMember[2]"::equals).count());
  }

  @Test
  void deletedGroupUsesOnlyTheOldRevision() {
    AccountGroup.UUID uuid = AccountGroup.uuid("gone");
    InternalGroup before = group(uuid, 9, "gone-name", ImmutableSet.of(Account.id(5)), ImmutableSet.of());
    List<ObjectId> asked = new ArrayList<>();
    ReplicatedCacheEvictions evictions =
        new ReplicatedCacheEvictions(
            id -> Optional.empty(),
            sshKeys,
            groups,
            groupIncludes,
            (g, commit) -> {
              asked.add(commit);
              return Optional.of(before);
            });

    evictions.groupChanged(uuid, BEFORE, ObjectId.zeroId());
    evictions.groupChanged(uuid, null, AFTER);

    assertEquals(List.of(BEFORE, AFTER), asked);
    assertTrue(calls.contains("GroupIncludeCache.evictGroupsWithMember[5]"), calls.toString());
  }

  @Test
  void unreadableSnapshotStillEvictsWhatIsKnown() {
    AccountGroup.UUID uuid = AccountGroup.uuid("broken");
    ReplicatedCacheEvictions evictions =
        new ReplicatedCacheEvictions(
            id -> Optional.empty(),
            sshKeys,
            groups,
            groupIncludes,
            (g, commit) -> {
              throw new IOException("corrupt");
            });

    evictions.groupChanged(uuid, BEFORE, AFTER);

    assertEquals(
        List.of("GroupCache.evict[broken]", "GroupIncludeCache.evictParentGroupsOf[broken]"), calls);
  }

  private static InternalGroup group(
      AccountGroup.UUID uuid,
      int id,
      String name,
      ImmutableSet<Account.Id> members,
      ImmutableSet<AccountGroup.UUID> subgroups) {
    return InternalGroup.builder()
        .setId(AccountGroup.id(id))
        .setNameKey(AccountGroup.nameKey(name))
        .setGroupUUID(uuid)
        .setOwnerGroupUUID(uuid)
        .setVisibleToAll(false)
        .setCreatedOn(Instant.EPOCH)
        .setMembers(members)
        .setSubgroups(subgroups)
        .setRefState(ObjectId.zeroId())
        .build();
  }

  /** A fake that records every call as {@code Type.method[args]} and returns empty values. */
  @SuppressWarnings("unchecked")
  private <T> T recording(Class<T> type) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return method.getName().equals("toString") ? type.getSimpleName() : 0;
              }
              calls.add(
                  type.getSimpleName()
                      + "."
                      + method.getName()
                      + Arrays.toString(args == null ? new Object[0] : args));
              Class<?> returns = method.getReturnType();
              if (returns == Optional.class) {
                return Optional.empty();
              }
              if (Collection.class.isAssignableFrom(returns) || returns == Iterable.class) {
                return List.of();
              }
              if (returns == Map.class) {
                return Map.of();
              }
              if (returns == boolean.class) {
                return false;
              }
              return null;
            });
  }
}
