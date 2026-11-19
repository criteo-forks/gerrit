// Copyright (C) 2026 The Android Open Source Project
// SPDX-License-Identifier: Apache-2.0

package com.google.gerrit.server.index.change;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.index.change.ChangeIndexFreshness.ChangeIndexFreshnessModule;
import com.google.gerrit.server.update.BatchUpdateListener;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.junit.Test;

public class ChangeKeyLookupTest {
  @Test
  public void plainGerritCanUseItsIndex() {
    var server = configInjector(false).createChildInjector(new ChangeIndexFreshnessModule());
    assertThat(
            server
                .getInstance(ChangeKeyLookup.StorageBackend.class)
                .open(mock(Repository.class), (g, c) -> {}))
        .isEmpty();
  }

  @Test
  public void configuredWalStorageWithoutAPublicationHookFailsAtStartup() {
    var database = configInjector(true).createChildInjector();
    var failure =
        assertThrows(
            CreationException.class,
            () -> database.createChildInjector(new ChangeIndexFreshnessModule()));
    assertThat(failure)
        .hasMessageThat()
        .contains("WalGerrit storage has no Change-Id publication guard");
  }

  @Test
  public void aRegisteredStoreCannotDeclineTheLookup() {
    var database = configInjector(true).createChildInjector(storageReturning(Optional.empty()));
    var server = database.createChildInjector(new ChangeIndexFreshnessModule());
    var lookup = server.getInstance(ChangeKeyLookup.StorageBackend.class);
    assertThrows(
        ChangeIndexNotFreshException.class,
        () -> lookup.open(mock(Repository.class), (guard, conflict) -> {}));
  }

  @Test
  public void serverDiscoversTheHookInTheDatabaseInjector() {
    var database =
        configInjector(true)
            .createChildInjector(storageReturning(Optional.of((branch, key) -> List.of(17))));
    var server = database.createChildInjector(new ChangeIndexFreshnessModule());
    var guard = new AtomicReference<BatchUpdateListener>();
    var lookup =
        server
            .getInstance(ChangeKeyLookup.StorageBackend.class)
            .open(mock(Repository.class), (listener, conflict) -> guard.set(listener))
            .orElseThrow();
    assertThat(guard.get()).isSameInstanceAs(BatchUpdateListener.NONE);
    assertThat(lookup.apply("refs/heads/main", "I1")).containsExactly(17);
  }

  private static Injector configInjector(boolean walStorage) {
    Config config = new Config();
    if (walStorage) {
      config.setString("gerrit", null, "installDbModule", "dev.walgerrit.WalGitModule");
    }
    return Guice.createInjector(
        binder ->
            binder.bind(Config.class).annotatedWith(GerritServerConfig.class).toInstance(config));
  }

  private static Module storageReturning(
      Optional<BiFunction<String, String, List<Integer>>> lookup) {
    return binder ->
        binder
            .bind(
                new TypeLiteral<
                    BiFunction<
                        Repository,
                        BiConsumer<BatchUpdateListener, BooleanSupplier>,
                        Optional<BiFunction<String, String, List<Integer>>>>>() {})
            .annotatedWith(Names.named("reviewChangeKeyLookup"))
            .toInstance(
                (repo, guard) -> {
                  if (lookup.isPresent()) {
                    guard.accept(BatchUpdateListener.NONE, () -> false);
                  }
                  return lookup;
                });
  }
}
