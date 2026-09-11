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

import com.google.gerrit.extensions.events.AccountIndexedListener;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.extensions.events.GroupIndexedListener;
import com.google.gerrit.extensions.events.ProjectIndexedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.events.EventListener;

/** Installs WAL-driven secondary-index convergence in Gerrit's system injector. */
public final class WalGitIndexModule extends LifecycleModule {
  @Override
  protected void configure() {
    listener().to(IndexEventTailer.class);
    // What this node does travels in the WAL to the other nodes' tailers: the events it fires and
    // the documents it reindexes with no ref update behind them.
    DynamicSet.bind(binder(), EventListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), ChangeIndexedListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), AccountIndexedListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), GroupIndexedListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), ProjectIndexedListener.class).to(WalJournal.class);
    listener().to(WalJournal.class);
  }
}
