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
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Names;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Installs WAL-driven secondary-index convergence in Gerrit's system injector. */
public final class WalGitIndexModule extends LifecycleModule {
  /**
   * The binding Gerrit calls before it decides on the change index. The name and type mirror {@code
   * com.google.gerrit.server.index.change.ChangeIndexFreshness} in the WalGerrit fork of Gerrit,
   * which this library is built without.
   */
  static final Key<Function<Optional<String>, Optional<String>>> INDEX_FRESHNESS_BARRIER =
      Key.get(
          new TypeLiteral<Function<Optional<String>, Optional<String>>>() {},
          Names.named("changeIndexFreshnessBarrier"));

  /**
   * The binding Gerrit asks before it answers a request. The name and type mirror {@code
   * com.google.gerrit.server.ServingGate} in the WalGerrit fork of Gerrit.
   */
  static final Key<Supplier<Optional<String>>> SERVING_GATE =
      Key.get(new TypeLiteral<Supplier<Optional<String>>>() {}, Names.named("servingGate"));

  static final Key<LongSupplier> SEARCH_FRESHNESS =
      Key.get(LongSupplier.class, Names.named("changeIndexSearchGeneration"));

  @Override
  protected void configure() {
    // First: a Gerrit build that never calls the barrier or the gate is not worth a startup sweep.
    listener().to(IndexBarrier.class);
    listener().to(StartupGate.class);
    // Peers' wake-ups let the tailer replay a repository before its next sweep finds it.
    listener().to(GossipEndpoint.class);
    listener().to(IndexEventTailer.class);
    // Gerrit declares the binding with a default that waits for nothing; this replaces it.
    OptionalBinder.newOptionalBinder(binder(), INDEX_FRESHNESS_BARRIER)
        .setBinding()
        .to(IndexBarrier.class);
    OptionalBinder.newOptionalBinder(binder(), SEARCH_FRESHNESS)
        .setBinding()
        .to(IndexBarrier.class);
    OptionalBinder.newOptionalBinder(binder(), SERVING_GATE).setBinding().to(StartupGate.class);
    // What this node does travels in the WAL to the other nodes' tailers: the events it fires and
    // the documents it reindexes with no ref update behind them.
    DynamicSet.bind(binder(), EventListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), ChangeIndexedListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), AccountIndexedListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), GroupIndexedListener.class).to(WalJournal.class);
    DynamicSet.bind(binder(), ProjectIndexedListener.class).to(WalJournal.class);
    listener().to(WalJournal.class);
    // Last: a change-merged event that an earlier process of this node owes is fired once the
    // journal and, before this module, the plugins run.
    listener().to(OwedEventDelivery.class);
    // The journaled events as a log per repository, for plugins that forward them elsewhere.
    bind(EventLog.class).to(WalEventLog.class);
  }
}
