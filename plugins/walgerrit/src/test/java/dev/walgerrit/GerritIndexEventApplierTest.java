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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.index.group.GroupIndexer;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * Deterministic interleavings of the production replay applier with index-backed membership caches.
 */
class GerritIndexEventApplierTest {
  static final Account.Id MEMBER = Account.id(42);
  static final AccountGroup.UUID GROUP =
      AccountGroup.uuid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  static final ObjectId BEFORE = ObjectId.fromString("1111111111111111111111111111111111111111");
  static final ObjectId AFTER = ObjectId.fromString("2222222222222222222222222222222222222222");
  final Map<Account.Id, Boolean> membershipCache = new HashMap<>();
  final AtomicBoolean indexContainsMember = new AtomicBoolean(true);
  final GroupCache groups = proxy(GroupCache.class, (method, args) -> null);
  final GroupIncludeCache includes =
      proxy(
          GroupIncludeCache.class,
          (method, args) -> {
            if (method.equals("evictGroupsWithMember")) {
              membershipCache.remove(args[0]);
            }
            return null;
          });

  boolean member() {
    return membershipCache.computeIfAbsent(MEMBER, ignored -> indexContainsMember.get());
  }

  InternalGroup snapshot(boolean contains) {
    return InternalGroup.builder()
        .setId(AccountGroup.id(7))
        .setNameKey(AccountGroup.nameKey("privileged"))
        .setGroupUUID(GROUP)
        .setOwnerGroupUUID(GROUP)
        .setVisibleToAll(false)
        .setCreatedOn(Instant.EPOCH)
        .setMembers(contains ? ImmutableSet.of(MEMBER) : ImmutableSet.of())
        .setSubgroups(ImmutableSet.of())
        .setRefState(contains ? BEFORE : AFTER)
        .build();
  }

  ReplicatedCacheEvictions evictions(boolean failOldRead) {
    return new ReplicatedCacheEvictions(
        id -> Optional.empty(),
        null,
        groups,
        includes,
        (g, commit) -> {
          if (failOldRead && commit.equals(BEFORE)) {
            throw new IOException("transient old-snapshot read failure");
          }
          return Optional.of(snapshot(commit.equals(BEFORE)));
        });
  }

  void apply(ReplicatedCacheEvictions evictions, boolean concurrentReadBeforeIndexWrite)
      throws Exception {
    GroupIndexer indexer =
        new GroupIndexer() {
          @Override
          public void index(AccountGroup.UUID id) {
            // This read represents a request between eviction and replacement of the group index.
            if (concurrentReadBeforeIndexWrite) {
              member();
            }
            indexContainsMember.set(false);
          }

          @Override
          public boolean reindexIfStale(AccountGroup.UUID id) {
            return false;
          }
        };
    GerritIndexEventApplier applier =
        new GerritIndexEventApplier(
            null,
            new AllUsersName("All-Users"),
            null,
            null,
            indexer,
            null,
            null,
            null,
            null,
            null,
            evictions,
            null);
    RefTransaction txn =
        RefTransaction.newBuilder()
            .addUpdates(
                RefUpdate.newBuilder()
                    .setName(RefNames.refsGroups(GROUP))
                    .setOldObjectId(BEFORE.name())
                    .setNewObjectId(AFTER.name()))
            .build();
    var method =
        GerritIndexEventApplier.class.getDeclaredMethod(
            "applyInContext", Project.NameKey.class, RefTransaction.class);
    method.setAccessible(true);
    try {
      method.invoke(applier, Project.nameKey("All-Users"), txn);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception failure) {
        throw failure;
      }
      throw e;
    }
  }

  @Test
  void removalMustRemainEffectiveAfterConcurrentCacheRead() throws Exception {
    assertTrue(member());
    apply(evictions(false), true);
    assertFalse(
        member(),
        "Removed member was cached again from the old group index before reindex completed");
  }

  @Test
  void oldSnapshotReadFailureMustNotSilentlyAcknowledgeRevocation() throws Exception {
    assertTrue(member());
    assertThrows(UncheckedIOException.class, () -> apply(evictions(true), false));
    // The same WAL entry must be retried: its old snapshot still identifies the removed member
    // even though the group index was already updated by the failed attempt.
    apply(evictions(false), false);
    assertFalse(member(), "Retry must remove the previously cached membership");
  }

  interface Call {
    Object run(String method, Object[] args);
  }

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, Call call) {
    return (T)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (p, m, args) -> call.run(m.getName(), args == null ? new Object[0] : args));
  }
}
