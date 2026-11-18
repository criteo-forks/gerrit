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

package com.google.gerrit.server.index.change;

import com.google.gerrit.entities.Project;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Requires this node's change index to be up to date before a decision that is wrong on a stale
 * one: whether a pushed Change-Id names an existing change, and which changes a topic holds.
 *
 * <p>On a single server the request that writes a change indexes it before it answers, so the index
 * holds every change a client was told about. Where several nodes share the repositories and each
 * keeps its own index, a node's index trails the other nodes' writes. Such a backend binds a
 * barrier that catches the index up, and the callers here fail with {@link
 * ChangeIndexNotFreshException} when it cannot, instead of deciding on the index as it is.
 *
 * <p>The barrier is a JDK-typed binding so a library built against the plugin API can provide it:
 *
 * <pre>{@code
 * OptionalBinder.newOptionalBinder(binder(), ChangeIndexFreshness.BARRIER_KEY)
 *     .setBinding()
 *     .to(MyBarrier.class);
 * }</pre>
 *
 * Its argument is a project name, or empty for every project. It returns empty once the index holds
 * every change update that was acknowledged, on any node, before the call, and otherwise the reason
 * it could not establish that. It must return within a bounded time. Updates acknowledged after the
 * call began are outside the contract. {@link ChangeIndexFreshnessModule} supplies the default,
 * which takes the index to be fresh.
 */
@Singleton
public class ChangeIndexFreshness {
  /** Guice name of the barrier binding. */
  public static final String BARRIER = "changeIndexFreshnessBarrier";

  public static final Key<Function<Optional<String>, Optional<String>>> BARRIER_KEY =
      Key.get(
          new TypeLiteral<Function<Optional<String>, Optional<String>>>() {}, Names.named(BARRIER));

  private static final String SEARCH = "changeIndexSearchGeneration";
  public static final Key<LongSupplier> SEARCH_KEY =
      Key.get(LongSupplier.class, Names.named(SEARCH));

  /** Declares the barrier binding with a default that takes the index to be fresh. */
  public static class ChangeIndexFreshnessModule extends AbstractModule {
    @Override
    protected void configure() {
      OptionalBinder.newOptionalBinder(binder(), SEARCH_KEY).setDefault().toInstance(() -> 0L);
      OptionalBinder.newOptionalBinder(binder(), BARRIER_KEY)
          .setDefault()
          .toInstance(scope -> Optional.empty());
    }
  }

  private final Function<Optional<String>, Optional<String>> barrier;

  private final LongSupplier searchGeneration;

  @Inject
  ChangeIndexFreshness(
      @Named(BARRIER) Function<Optional<String>, Optional<String>> barrier,
      @Named(SEARCH) LongSupplier searchGeneration) {
    this.barrier = barrier;
    this.searchGeneration = searchGeneration;
  }

  /**
   * Returns once the index holds every update to {@code project}'s changes acknowledged before the
   * call.
   *
   * @throws ChangeIndexNotFreshException if that could not be established
   */
  public void requireFresh(Project.NameKey project) {
    require(Optional.of(project.get()), "project " + project.get());
  }

  /**
   * Returns once the index holds every change update, in any project, acknowledged before the call.
   *
   * @throws ChangeIndexNotFreshException if that could not be established
   */
  public void requireAllFresh() {
    require(Optional.empty(), "all projects");
  }

  /** Refuses a user search when this node cannot confirm its index stayed within its age limit. */
  public long requireSearchFresh() {
    long generation = searchGeneration.getAsLong();
    if (generation < 0) {
      throw searchUnavailable();
    }
    return generation;
  }

  /** A query crossing an index rebuild must discard results from its old generation. */
  public void checkSearchResult(long generation) {
    if (generation != searchGeneration.getAsLong()) {
      throw searchUnavailable();
    }
  }

  private static ChangeIndexNotFreshException searchUnavailable() {
    return new ChangeIndexNotFreshException(
        "This server cannot confirm its change index is up to date. Please retry.");
  }

  private void require(Optional<String> scope, String description) {
    Optional<String> notFresh;
    try {
      notFresh = barrier.apply(scope);
    } catch (RuntimeException failure) {
      throw new ChangeIndexNotFreshException(
          String.format(
              "This server could not bring its change index up to date for %s. Please retry.",
              description),
          failure);
    }
    if (notFresh.isPresent()) {
      throw new ChangeIndexNotFreshException(
          String.format(
              "This server could not bring its change index up to date for %s: %s. Please retry.",
              description, notFresh.get()));
    }
  }
}
