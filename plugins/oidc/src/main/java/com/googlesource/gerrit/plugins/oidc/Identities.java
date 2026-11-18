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

package com.googlesource.gerrit.plugins.oidc;

import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_GERRIT;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_USERNAME;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.query.account.AccountPredicates;
import com.google.gerrit.server.query.account.InternalAccountQuery;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.Optional;

/**
 * Maps a username from the provider to the Gerrit account and identity it authenticates.
 *
 * <p>Accounts created by LDAP or HTTP logins carry a {@code gerrit:<username>} identity, and the
 * provider's users are the directory's: authenticating through that identity reuses the account
 * instead of creating a second one. A new user gets the same identity, so the site can move between
 * LDAP and OIDC logins without touching accounts.
 */
@Singleton
class Identities {
  /** The account a username names, and the external ID a login authenticates. */
  record Identity(Optional<AccountState> account, String externalId, String username) {}

  private final AccountCache accountCache;
  private final Provider<InternalAccountQuery> queryProvider;

  @Inject
  Identities(AccountCache accountCache, Provider<InternalAccountQuery> queryProvider) {
    this.accountCache = accountCache;
    this.queryProvider = queryProvider;
  }

  Identity of(String username) {
    Optional<AccountState> account = account(username);
    if (account.isEmpty()) {
      return new Identity(account, SCHEME_GERRIT + ":" + username, username);
    }
    AccountState a = account.get();
    String id =
        localIdentity(a, username).map(e -> e.key().get()).orElse(SCHEME_GERRIT + ":" + username);
    return new Identity(account, id, a.userName().orElse(username));
  }

  /**
   * The account with this username. A username keeps the case of the account's first login, which
   * need not be the provider's: when no account has it exactly, the only account that has it in
   * another case.
   */
  private Optional<AccountState> account(String username) {
    Optional<AccountState> exact = accountCache.getByUsernameForAuthentication(username);
    if (exact.isPresent()) {
      return exact;
    }
    ImmutableList<AccountState> others =
        queryProvider.get().query(AccountPredicates.username(username)).stream()
            .filter(a -> a.userName().map(username::equalsIgnoreCase).orElse(false))
            .collect(ImmutableList.toImmutableList());
    return others.size() == 1 ? Optional.of(others.get(0)) : Optional.empty();
  }

  /**
   * The account's {@code gerrit:} identity, preferring the one named like {@code username}, else
   * its {@code username:} identity.
   */
  private static Optional<ExternalId> localIdentity(AccountState a, String username) {
    Optional<ExternalId> named =
        a.externalIds().stream()
            .filter(e -> e.key().isScheme(SCHEME_GERRIT) && e.key().id().equalsIgnoreCase(username))
            .findAny();
    if (named.isPresent()) {
      return named;
    }
    Optional<ExternalId> gerrit =
        a.externalIds().stream().filter(e -> e.key().isScheme(SCHEME_GERRIT)).findAny();
    if (gerrit.isPresent()) {
      return gerrit;
    }
    return a.externalIds().stream().filter(e -> e.key().isScheme(SCHEME_USERNAME)).findAny();
  }
}
