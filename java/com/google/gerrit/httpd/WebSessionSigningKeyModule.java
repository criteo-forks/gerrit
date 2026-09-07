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

import com.google.common.base.Strings;
import com.google.gerrit.server.ModuleImpl;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import java.util.Base64;
import org.eclipse.jgit.lib.Config;

/**
 * Provides the key that signs stateless web-session cookies ({@code auth.statelessSessions}).
 *
 * <p>The key is bound as {@code @Named(KEY) byte[]}. This module reads it from {@code
 * auth.sessionSigningKey}; a library module annotated {@code @ModuleImpl(name = NAME)} replaces
 * the whole module and may source the key elsewhere, for example from shared storage every node
 * can reach.
 */
@ModuleImpl(name = WebSessionSigningKeyModule.NAME)
public class WebSessionSigningKeyModule extends AbstractModule {
  public static final String NAME = "web-session-signing-key";

  /** Guice name of the {@code byte[]} binding that carries the signing key. */
  public static final String KEY = "webSessionSigningKey";

  @Override
  protected void configure() {
    bind(byte[].class)
        .annotatedWith(Names.named(KEY))
        .toProvider(ConfigSigningKey.class)
        .in(Scopes.SINGLETON);
  }

  static class ConfigSigningKey implements Provider<byte[]> {
    private final Config cfg;

    @Inject
    ConfigSigningKey(@GerritServerConfig Config cfg) {
      this.cfg = cfg;
    }

    @Override
    public byte[] get() {
      String encoded = cfg.getString("auth", null, "sessionSigningKey");
      if (Strings.isNullOrEmpty(encoded)) {
        throw new ProvisionException(
            "auth.statelessSessions is enabled but auth.sessionSigningKey is not set; put the"
                + " base64 of at least 16 random bytes in secure.config on every node, or install a"
                + " library module that provides the key");
      }
      byte[] key;
      try {
        key = Base64.getDecoder().decode(encoded.trim());
      } catch (IllegalArgumentException notBase64) {
        throw new ProvisionException("auth.sessionSigningKey is not valid base64", notBase64);
      }
      if (key.length < 16) {
        throw new ProvisionException("auth.sessionSigningKey must decode to at least 16 bytes");
      }
      return key;
    }
  }
}
