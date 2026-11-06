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

package com.google.gerrit.auth.ldap;

import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import java.time.Duration;
import org.eclipse.jgit.lib.Config;

/**
 * Provides {@link LdapPasswordAuthenticator} to a site whose {@code auth.type} is not one of the
 * LDAP types, typically from the module of an authentication plugin. Do not install it next to
 * {@link LdapModule}, which already binds the LDAP helper and its cache.
 */
public class LdapPasswordModule extends CacheModule {
  @Override
  protected void configure() {
    cache(LdapModule.PARENT_GROUPS_CACHE, String.class, new TypeLiteral<ImmutableSet<String>>() {})
        .expireAfterWrite(Duration.ofHours(1));
    bind(LdapPasswordAuthenticator.class);
  }

  @Provides
  @Singleton
  Helper provideHelper(
      @GerritServerConfig Config config,
      @Named(LdapModule.PARENT_GROUPS_CACHE) Cache<String, ImmutableSet<String>> parentGroups,
      MetricMaker metricMaker) {
    return new Helper(passwordCheckConfig(config), parentGroups, metricMaker);
  }

  private static Config passwordCheckConfig(Config config) {
    Config checks = new Config(config);
    // A pooled connection can skip the bind that proves a password, and its pool key holds it.
    checks.setBoolean("ldap", null, "useConnectionPooling", false);
    // Helper applies no timeout unless one is set, and a hung check must end.
    for (String timeout : ImmutableList.of("readTimeout", "connectTimeout")) {
      if (checks.getString("ldap", null, timeout) == null) {
        checks.setString("ldap", null, timeout, "10 s");
      }
    }
    return checks;
  }
}
