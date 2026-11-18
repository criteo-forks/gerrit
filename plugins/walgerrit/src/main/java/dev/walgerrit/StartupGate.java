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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Keeps requests out of a node whose indexes are still catching up after its start.
 *
 * <p>The tailer replays what was published while the node was away on its own thread, so the
 * daemon's listeners open and a liveness probe is answered however long the replay takes. Until the
 * first clean sweep the indexes are behind, and Gerrit asks this gate before it answers anything
 * else: through its {@code ServingGate} hook, which {@link WalGitIndexModule} binds it to. The
 * result is empty when requests may be answered, and otherwise the reason they may not.
 */
@Singleton
final class StartupGate implements Supplier<Optional<String>>, LifecycleListener {
  /** The Gerrit class that asks the gate; absent from a build without the hook. */
  private static final String HOOK = "com.google.gerrit.server.ServingGate";

  static final String CATCHING_UP =
      "This server is starting: it is indexing what other servers wrote while it was away."
          + " Please retry.";

  private final IndexEventTailer tailer;

  @Inject
  StartupGate(IndexEventTailer tailer) {
    this.tailer = tailer;
  }

  /**
   * Without the hook nothing asks the gate, and a node would answer from indexes that are hours
   * behind while it catches up. Refuse to start instead.
   */
  @Override
  public void start() {
    if (!tailer.tailing()) {
      return;
    }
    try {
      Class.forName(HOOK, false, StartupGate.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError missing) {
      throw new IllegalStateException(
          "This Gerrit build has no "
              + HOOK
              + ", so nothing would keep requests out of this node while its indexes catch up."
              + " Deploy the WalGerrit library with the fork WAR it was built for.",
          missing);
    }
  }

  @Override
  public void stop() {}

  @Override
  public Optional<String> get() {
    return tailer.catchingUp() ? Optional.of(CATCHING_UP) : Optional.empty();
  }
}
