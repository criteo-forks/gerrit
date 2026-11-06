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

import com.google.common.base.Strings;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.server.account.AuthTokenVerifier;
import com.google.gerrit.server.auth.AuthenticationUnavailableException;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gson.JsonObject;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oidc.Jwt.InvalidTokenException;
import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates Git over HTTP and REST API requests. The secret, sent as a Bearer token or as the
 * password of Basic authentication, is tried as:
 *
 * <ol>
 *   <li>a token from an accepted issuer, meant for {@code plugin.oidc.audience};
 *   <li>a Gerrit authentication token, when {@code auth.oauthAcceptsAuthTokens} is set;
 *   <li>the user's LDAP password, when {@code ldap.server} is set.
 * </ol>
 *
 * <p>The user info it returns carries no email or name, so authenticating never changes the
 * account; and only an existing account can authenticate.
 */
@Singleton
class OidcLoginProvider implements OAuthLoginProvider {
  /** Checks a directory password; nothing when the site has none. */
  @ImplementedBy(NoPasswords.class)
  interface Passwords {
    boolean check(String username, String password) throws AuthenticationUnavailableException;
  }

  static class NoPasswords implements Passwords {
    @Override
    public boolean check(String username, String password) {
      return false;
    }
  }

  private final OidcConfig cfg;
  private final TokenVerifier verifier;
  private final Identities identities;
  private final AuthTokenVerifier authTokens;
  private final boolean acceptsAuthTokens;
  private final Passwords passwords;

  @Inject
  OidcLoginProvider(
      OidcConfig cfg,
      TokenVerifier verifier,
      Identities identities,
      AuthTokenVerifier authTokens,
      AuthConfig authConfig,
      Passwords passwords) {
    this.cfg = cfg;
    this.verifier = verifier;
    this.identities = identities;
    this.authTokens = authTokens;
    this.acceptsAuthTokens = authConfig.acceptsAuthTokens();
    this.passwords = passwords;
  }

  @Override
  public OAuthUserInfo login(@Nullable String username, String secret) throws IOException {
    if (Strings.isNullOrEmpty(secret)) {
      throw new IOException("No credentials");
    }
    if (Jwt.looksLikeJwt(secret)) {
      return tokenLogin(username, secret);
    }
    if (username == null) {
      // A Bearer credential that is not a token: there is no one to check a password for.
      throw new IOException("Bearer credential is not a token");
    }
    Identities.Identity id = identities.of(username);
    Optional<Account.Id> account = id.account().map(a -> a.account().id());
    if (account.isEmpty()) {
      throw new IOException("No account for " + username);
    }
    if (acceptsAuthTokens && authTokens.checkToken(account.get(), secret)) {
      return userInfo(id);
    }
    try {
      if (passwords.check(username, secret)) {
        return userInfo(id);
      }
    } catch (AuthenticationUnavailableException e) {
      throw new IOException("Cannot check the password of " + username, e);
    }
    throw new IOException("Invalid credentials for " + username);
  }

  private OAuthUserInfo tokenLogin(@Nullable String username, String token) throws IOException {
    JsonObject claims;
    try {
      claims = verifier.verify(token, cfg.acceptedIssuers(), cfg.audience());
    } catch (InvalidTokenException e) {
      throw new IOException("Rejected token: " + e.getMessage());
    }
    String subject = Jwt.string(claims, cfg.usernameClaim());
    if (Strings.isNullOrEmpty(subject)) {
      throw new IOException("Token has no " + cfg.usernameClaim());
    }
    if (username != null && !subject.equalsIgnoreCase(username)) {
      throw new IOException(String.format("Token of %s sent as %s", subject, username));
    }
    Identities.Identity id = identities.of(subject);
    if (id.account().filter(a -> a.account().isActive()).isEmpty()) {
      // Accounts are created by the web login, which has the user's name and email.
      throw new IOException("No active account for " + subject);
    }
    return userInfo(id);
  }

  private static OAuthUserInfo userInfo(Identities.Identity id) {
    return new OAuthUserInfo(id.externalId(), id.username(), null, null, null);
  }
}
