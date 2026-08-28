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

/** Installs WAL-driven secondary-index convergence in Gerrit's system injector. */
public final class WalGitIndexModule extends LifecycleModule {
  @Override
  protected void configure() {
    listener().to(IndexEventTailer.class);
  }
}
