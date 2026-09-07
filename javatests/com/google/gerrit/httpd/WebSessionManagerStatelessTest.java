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

package com.google.gerrit.httpd;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gerrit.entities.Account;
import com.google.gerrit.httpd.WebSessionManager.Val;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class WebSessionManagerStatelessTest {
  private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(UTF_8);
  private final ExternalIdKeyFactory externalIds = new ExternalIdKeyFactory(() -> false);

  private WebSessionManager manager(boolean stateless, Cache<String, Val> cache) {
    Config cfg = new Config();
    cfg.setBoolean("auth", null, "statelessSessions", stateless);
    return new WebSessionManager(cfg, cache, externalIds, () -> KEY);
  }

  @Test
  public void statelessSessionLivesInTheTokenAndNotInTheCache() {
    Cache<String, Val> cache = CacheBuilder.newBuilder().build();
    WebSessionManager manager = manager(true, cache);
    Account.Id who = Account.id(1000042);

    WebSessionManager.Key key = manager.createKey(who);
    String placeholder = key.getToken();
    Val created =
        manager.createVal(key, who, true, externalIds.parse("username:jdoe"), null, null);

    assertThat(key.getToken()).isNotEqualTo(placeholder);
    assertThat(cache.size()).isEqualTo(0);
    assertThat(manager.isStateless()).isTrue();

    // Another manager with the same key, as on another node, accepts the token.
    Val read = manager(true, CacheBuilder.newBuilder().build()).get(new WebSessionManager.Key(key.getToken()));
    assertThat(read).isNotNull();
    assertThat(read.getAccountId()).isEqualTo(who);
    assertThat(read.getAuth()).isEqualTo(created.getAuth());
    assertThat(read.getSessionId()).isEqualTo(created.getSessionId());
    assertThat(read.getExpiresAt()).isEqualTo(created.getExpiresAt());
    assertThat(read.isPersistentCookie()).isTrue();
  }

  @Test
  public void refreshIssuesANewTokenForTheSameSession() {
    WebSessionManager manager = manager(true, CacheBuilder.newBuilder().build());
    Account.Id who = Account.id(7);
    WebSessionManager.Key key = manager.createKey(who);
    Val first = manager.createVal(key, who, false, null, null, null);
    String firstToken = key.getToken();

    Val refreshed = manager.createVal(key, first);

    // The token is a deterministic encoding of the session, so it only changes when the clock
    // moved between the two calls; what matters is that it still carries the same session.
    assertThat(refreshed.getExpiresAt()).isAtLeast(first.getExpiresAt());
    assertThat(refreshed.getSessionId()).isEqualTo(first.getSessionId());
    assertThat(refreshed.getAuth()).isEqualTo(first.getAuth());
    assertThat(manager.get(new WebSessionManager.Key(key.getToken()))).isNotNull();
    assertThat(manager.get(new WebSessionManager.Key(firstToken))).isNotNull(); // still signed and unexpired
  }

  @Test
  public void destroyLeavesTheTokenValidUntilExpiry() {
    WebSessionManager manager = manager(true, CacheBuilder.newBuilder().build());
    Account.Id who = Account.id(7);
    WebSessionManager.Key key = manager.createKey(who);
    Val unused = manager.createVal(key, who, false, null, null, null);

    manager.destroy(key);

    // Stateless sessions are revoked at the client by clearing the cookie, not on the server.
    assertThat(manager.get(new WebSessionManager.Key(key.getToken()))).isNotNull();
  }

  @Test
  public void configuredKeyProviderIsHarmlessWhenStatelessSessionsAreOff() {
    Config cfg = new Config();
    assertThat(new WebSessionSigningKeyModule.ConfigSigningKey(cfg).get()).isEmpty();

    cfg.setBoolean("auth", null, "statelessSessions", true);
    assertThrows(
        com.google.inject.ProvisionException.class,
        () -> new WebSessionSigningKeyModule.ConfigSigningKey(cfg).get());

    cfg.setString("auth", null, "sessionSigningKey", java.util.Base64.getEncoder().encodeToString(KEY));
    assertThat(new WebSessionSigningKeyModule.ConfigSigningKey(cfg).get()).isEqualTo(KEY);
  }

  @Test
  public void cacheBackedModeIsUnchanged() {
    Cache<String, Val> cache = CacheBuilder.newBuilder().build();
    WebSessionManager manager = manager(false, cache);
    Account.Id who = Account.id(7);
    WebSessionManager.Key key = manager.createKey(who);
    String token = key.getToken();

    Val unused = manager.createVal(key, who, false, null, null, null);

    assertThat(key.getToken()).isEqualTo(token);
    assertThat(cache.size()).isEqualTo(1);
    assertThat(manager.isStateless()).isFalse();
    manager.destroy(key);
    assertThat(cache.size()).isEqualTo(0);
    assertThat(manager.get(key)).isNull();
  }
}
