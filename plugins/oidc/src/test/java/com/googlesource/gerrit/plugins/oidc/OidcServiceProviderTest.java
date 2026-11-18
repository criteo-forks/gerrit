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

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.oidc.FakeProvider.ISSUER;
import static com.googlesource.gerrit.plugins.oidc.FakeProvider.OTHER_ISSUER;
import static com.googlesource.gerrit.plugins.oidc.OidcLoginProviderTest.gerrit;
import static com.googlesource.gerrit.plugins.oidc.OidcLoginProviderTest.state;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.query.account.InternalAccountQuery;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

public class OidcServiceProviderTest {
  private static final Instant NOW = Instant.now();

  private FakeProvider idp;
  private AccountCache accountCache;
  private OidcServiceProvider provider;

  @Before
  public void setUp() throws Exception {
    idp = new FakeProvider();
    accountCache = mock(AccountCache.class);
    when(accountCache.getByUsernameForAuthentication(anyString())).thenReturn(Optional.empty());
    InternalAccountQuery query = mock(InternalAccountQuery.class);
    when(query.query(ArgumentMatchers.<Predicate<AccountState>>any()))
        .thenReturn(ImmutableList.of());
    OidcConfig cfg = OidcLoginProviderTest.config();
    Providers providers = new Providers(idp);
    provider =
        new OidcServiceProvider(
            cfg,
            providers,
            idp,
            new TokenVerifier(providers, cfg),
            new Identities(accountCache, () -> query),
            () -> "https://gerrit.example.com/",
            () -> NOW);
  }

  @Test
  public void authorizationUrl() {
    assertThat(provider.getAuthorizationUrl())
        .isEqualTo(
            ISSUER
                + "/protocol/openid-connect/auth?response_type=code&client_id=gerrit"
                + "&redirect_uri=https%3A%2F%2Fgerrit.example.com%2Foauth"
                + "&scope=openid+profile+email");
    assertThat(provider.usesPkce()).isTrue();
  }

  @Test
  public void exchangesTheCodeWithItsVerifier() throws Exception {
    String idToken = idToken(ISSUER, "gerrit", "gerrit");
    idp.tokenResponse.addProperty("access_token", "access");
    idp.tokenResponse.addProperty("id_token", idToken);
    idp.tokenResponse.addProperty("refresh_token", "refresh");
    idp.tokenResponse.addProperty("expires_in", 300);

    OAuthToken token = provider.getAccessToken(new OAuthVerifier("the-code", "the-verifier"));

    assertThat(idp.lastForm)
        .containsExactly(
            "grant_type", "authorization_code",
            "code", "the-code",
            "redirect_uri", "https://gerrit.example.com/oauth",
            "code_verifier", "the-verifier");
    assertThat(idp.lastClientId).isEqualTo("gerrit");
    assertThat(idp.lastClientSecret).isEqualTo("client-secret");
    assertThat(token.getToken()).isEqualTo("access");
    assertThat(token.getRaw()).isEqualTo(idToken);
    assertThat(token.getExpiresAt()).isEqualTo(NOW.plusSeconds(300).toEpochMilli());
  }

  @Test
  public void newUserGetsAGerritIdentity() throws Exception {
    OAuthUserInfo info = provider.getUserInfo(token(idToken(ISSUER, "gerrit", "gerrit")));

    assertThat(info.getExternalId()).isEqualTo("gerrit:jdoe");
    assertThat(info.getUserName()).isEqualTo("jdoe");
    assertThat(info.getEmailAddress()).isEqualTo("jdoe@example.com");
    assertThat(info.getDisplayName()).isEqualTo("John Doe");
  }

  @Test
  public void existingUserKeepsTheIdentityLdapGaveIt() throws Exception {
    when(accountCache.getByUsernameForAuthentication("jdoe"))
        .thenReturn(Optional.of(state("jdoe", true, gerrit("JDoe"))));

    OAuthUserInfo info = provider.getUserInfo(token(idToken(ISSUER, "gerrit", "gerrit")));

    assertThat(info.getExternalId()).isEqualTo("gerrit:JDoe");
  }

  @Test
  public void idTokenMustComeFromTheLoginIssuer() throws Exception {
    IOException e =
        assertThrows(
            IOException.class,
            () -> provider.getUserInfo(token(idToken(OTHER_ISSUER, "gerrit", "gerrit"))));
    assertThat(e).hasMessageThat().contains("not accepted");
  }

  @Test
  public void idTokenMustBeForTheClient() throws Exception {
    assertThrows(
        IOException.class, () -> provider.getUserInfo(token(idToken(ISSUER, "other", "other"))));

    IOException e =
        assertThrows(
            IOException.class,
            () -> provider.getUserInfo(token(idToken(ISSUER, "gerrit", "other"))));
    assertThat(e).hasMessageThat().contains("issued to other");
  }

  private static OAuthToken token(String idToken) {
    return new OAuthToken("access", "", idToken);
  }

  private String idToken(String issuer, String aud, String azp) throws Exception {
    return FakeProvider.sign(
        idp.key(issuer),
        "{\"iss\": \""
            + issuer
            + "\", \"aud\": \""
            + aud
            + "\", \"azp\": \""
            + azp
            + "\", \"exp\": "
            + (NOW.getEpochSecond() + 300)
            + ", \"preferred_username\": \"jdoe\", \"email\": \"jdoe@example.com\","
            + " \"name\": \"John Doe\"}");
  }
}
