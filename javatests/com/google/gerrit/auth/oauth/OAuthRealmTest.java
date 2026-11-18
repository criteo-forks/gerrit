// Copyright (C) 2020 The Android Open Source Project
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

package com.google.gerrit.auth.oauth;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_EXTERNAL;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_HTTP;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_HTTPS;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_MAILTO;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_USERNAME;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_XRI;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.server.account.AccountException;
import com.google.gerrit.server.account.AuthRequest;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.auth.AuthenticationUnavailableException;
import com.google.gerrit.testing.InMemoryModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.io.IOException;
import java.util.Arrays;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class OAuthRealmTest {
  @Mock private DynamicMap<OAuthLoginProvider> providers;
  @Inject private AuthRequest.Factory authRequests;
  @Inject private OAuthRealm oauthRealm;
  @Inject private ExternalIdFactory externalIdFactory;

  @Before
  public void setUpInjector() throws Exception {
    Injector injector = Guice.createInjector(new InMemoryModule());
    injector.injectMembers(this);
  }

  private ExternalId id(String scheme, String id) {
    return externalIdFactory.create(scheme, id, Account.id(1000));
  }

  private boolean accountBelongsToRealm(ExternalId... ids) {
    return oauthRealm.accountBelongsToRealm(Arrays.asList(ids));
  }

  private boolean accountBelongsToRealm(String scheme, String id) {
    return accountBelongsToRealm(id(scheme, id));
  }

  @Test
  public void backendOutageSurvivesTheProviderBoundary() throws Exception {
    AuthenticationUnavailableException unavailable =
        new AuthenticationUnavailableException(
            "LDAP unavailable", new IOException("connection refused"));
    when(providers.get("oidc", "login"))
        .thenReturn(
            (name, password) -> {
              throw new IOException("Cannot verify password", unavailable);
            });
    AuthRequest request = authRequests.createForUser("alice");
    request.setAuthPlugin("oidc");
    request.setAuthProvider("login");
    request.setPassword("secret");
    OAuthRealm realm = new OAuthRealm(providers, new Config());
    assertThat(
            assertThrows(
                AuthenticationUnavailableException.class, () -> realm.authenticate(request)))
        .isSameInstanceAs(unavailable);

    when(providers.get("oidc", "login"))
        .thenReturn(
            (name, password) -> {
              throw new IOException("Rejected credential");
            });
    assertThat(assertThrows(AccountException.class, () -> realm.authenticate(request)).getClass())
        .isEqualTo(AccountException.class);
  }

  @Test
  public void accountBelongsToRealm() throws Exception {
    assertThat(accountBelongsToRealm(SCHEME_EXTERNAL, "test")).isTrue();
    assertThat(accountBelongsToRealm(id(SCHEME_USERNAME, "test"), id(SCHEME_EXTERNAL, "test")))
        .isTrue();
    assertThat(accountBelongsToRealm(id(SCHEME_EXTERNAL, "test"), id(SCHEME_USERNAME, "test")))
        .isTrue();

    assertThat(accountBelongsToRealm(SCHEME_USERNAME, "test")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_MAILTO, "foo@bar.com")).isFalse();

    assertThat(accountBelongsToRealm(SCHEME_USERNAME, "external")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_USERNAME, "xxexternalxx")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_MAILTO, "external.foo@bar.com")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_MAILTO, "bar.external@bar.com")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_HTTP, "example.org/test")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_HTTPS, "example.org/test")).isFalse();
    assertThat(accountBelongsToRealm(SCHEME_XRI, "example.org/test")).isFalse();
  }
}
