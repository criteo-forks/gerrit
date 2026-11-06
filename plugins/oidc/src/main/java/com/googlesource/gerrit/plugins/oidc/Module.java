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

import com.google.gerrit.auth.ldap.LdapPasswordModule;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;

/**
 * Authenticates Git over HTTP and REST API requests, as {@code auth.gitOAuthProvider = oidc:oidc}.
 */
public class Module extends AbstractModule {
  static final String EXPORT_NAME = "oidc";

  private final boolean ldapPasswords;

  @Inject
  Module(@PluginName String pluginName, @GerritServerConfig Config cfg) {
    ldapPasswords = OidcConfig.ldapPasswordsEnabled(pluginName, cfg);
  }

  @Override
  protected void configure() {
    bind(OAuthLoginProvider.class)
        .annotatedWith(Exports.named(EXPORT_NAME))
        .to(OidcLoginProvider.class);
    if (ldapPasswords) {
      install(new LdapPasswordModule());
      install(PasswordMemory.module());
      bind(OidcLoginProvider.Passwords.class).to(LdapPasswords.class);
    }
  }
}
