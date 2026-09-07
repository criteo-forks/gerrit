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

import com.google.gerrit.server.ModuleImpl;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.name.Names;

/**
 * Replaces Gerrit's {@code web-session-signing-key} module so the key behind {@code
 * auth.statelessSessions} comes from the object store instead of each node's configuration.
 *
 * <p>Install with {@code gerrit.installModule = dev.walgerrit.WalGitWebSessionModule}; Gerrit
 * swaps the module with the same {@link ModuleImpl} name. The binding names mirror {@code
 * com.google.gerrit.httpd.WebSessionSigningKeyModule} in the WalGerrit fork of Gerrit.
 */
@ModuleImpl(name = WalGitWebSessionModule.NAME)
public final class WalGitWebSessionModule extends AbstractModule {
  static final String NAME = "web-session-signing-key";
  static final String KEY = "webSessionSigningKey";

  @Override
  protected void configure() {
    bind(byte[].class)
        .annotatedWith(Names.named(KEY))
        .toProvider(ObjectStoreWebSessionSigningKey.class)
        .in(Scopes.SINGLETON);
  }
}
