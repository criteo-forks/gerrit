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

package com.google.gerrit.server;

import com.google.common.flogger.FluentLogger;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Says whether this server may answer requests.
 *
 * <p>A backend whose nodes share repositories and keep their own indexes binds a gate that stays
 * closed while a starting node indexes what the others wrote. The listeners open meanwhile, so
 * that a supervisor sees the process alive: HTTP answers {@code 503} to everything but {@link
 * #LIVENESS_PATH}, and SSH refuses every command.
 *
 * <p>The gate is a JDK-typed binding so a library built against the plugin API can provide it:
 *
 * <pre>{@code
 * OptionalBinder.newOptionalBinder(binder(), ServingGate.GATE_KEY)
 *     .setBinding()
 *     .to(MyGate.class);
 * }</pre>
 *
 * It returns empty when requests may be answered, and otherwise the reason they may not, which is
 * sent to the client. It is asked on every request, so it must be cheap. {@link ServingGateModule}
 * supplies the default, which is always open.
 */
@Singleton
public class ServingGate {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String GATE = "servingGate";

  public static final Key<Supplier<Optional<String>>> GATE_KEY =
      Key.get(new TypeLiteral<Supplier<Optional<String>>>() {}, Names.named(GATE));

  /**
   * The request a closed gate still lets through, to tell a supervisor that the process is alive.
   * Asked without credentials it reads no repository and no index.
   */
  public static final String LIVENESS_PATH = "/config/server/version";

  public static class ServingGateModule extends AbstractModule {
    @Override
    protected void configure() {
      OptionalBinder.newOptionalBinder(binder(), GATE_KEY)
          .setDefault()
          .toInstance(Optional::empty);
    }
  }

  private final Supplier<Optional<String>> gate;

  @Inject
  ServingGate(@Named(GATE) Supplier<Optional<String>> gate) {
    this.gate = gate;
  }

  /** Empty when requests may be answered; otherwise the reason they may not. */
  public Optional<String> closed() {
    try {
      return gate.get();
    } catch (RuntimeException failure) {
      logger.atWarning().withCause(failure).log("The serving gate failed; taking it to be closed");
      return Optional.of("This server cannot tell whether it is ready. Please retry.");
    }
  }
}
