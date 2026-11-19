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

import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.update.BatchUpdateListener;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.Repository;

abstract class AbstractWalGitModule extends LifecycleModule {
  @Override
  protected final void configure() {
    bind(GitRepositoryManager.class).to(WalGitRepositoryManager.class).in(Scopes.SINGLETON);
    listener().to(WalGitRepositoryManager.class);
    // Every Gerrit injector using this storage inherits the same lookup and publication guard.
    bind(ReviewChangeKeys.class);
    listener().to(ReviewChangeKeys.class);
    bind(new TypeLiteral<
            BiFunction<
                Repository,
                BiConsumer<BatchUpdateListener, BooleanSupplier>,
                Optional<BiFunction<String, String, List<Integer>>>>>() {})
        .annotatedWith(Names.named("reviewChangeKeyLookup"))
        .to(ReviewChangeKeys.class);
  }
}
