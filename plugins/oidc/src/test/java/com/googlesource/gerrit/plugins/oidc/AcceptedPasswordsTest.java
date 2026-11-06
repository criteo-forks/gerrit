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

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.testing.InMemoryModule;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class AcceptedPasswordsTest {
  @Test
  public void pluginRemembersAcceptedPasswords() throws Exception {
    Injector injector = injector(new Config());

    injector.getInstance(AcceptedPasswords.class).add("jdoe", "s3cret");

    AcceptedPasswords accepted = injector.getInstance(AcceptedPasswords.class);
    assertThat(accepted.contains("jdoe", "s3cret")).isTrue();
    assertThat(accepted.contains("jdoe", "wrong")).isFalse();
    assertThat(injector.getInstance(OidcLoginProvider.Passwords.class))
        .isInstanceOf(OidcLoginProvider.LdapPasswords.class);
  }

  @Test
  public void memoryLimitOfZeroTurnsItOff() throws Exception {
    Config cfg = new Config();
    cfg.setLong("cache", "oidc." + AcceptedPasswords.CACHE, "memoryLimit", 0);
    // Evicts before add returns, instead of moments later in the background.
    cfg.setInt("cache", null, "threads", 0);
    AcceptedPasswords accepted = injector(cfg).getInstance(AcceptedPasswords.class);

    accepted.add("jdoe", "s3cret");

    assertThat(accepted.contains("jdoe", "s3cret")).isFalse();
  }

  @Test
  public void maxAgeOfTheDocumentedCacheNameApplies() throws Exception {
    Config cfg = new Config();
    cfg.setString("cache", "oidc." + AcceptedPasswords.CACHE, "maxAge", "1 s");
    AcceptedPasswords accepted = injector(cfg).getInstance(AcceptedPasswords.class);

    accepted.add("jdoe", "s3cret");
    Thread.sleep(1100);

    assertThat(accepted.contains("jdoe", "s3cret")).isFalse();
  }

  private static Injector injector(Config cfg) {
    cfg.setString("auth", null, "type", "OAUTH");
    cfg.setString("ldap", null, "server", "ldap://directory.example.com");
    return Guice.createInjector(
        new InMemoryModule(cfg),
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(String.class).annotatedWith(PluginName.class).toInstance("oidc");
            install(new Module("oidc", cfg));
          }
        });
  }
}
