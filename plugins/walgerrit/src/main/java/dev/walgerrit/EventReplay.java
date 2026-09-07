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


/** Marks the thread that is delivering events replayed from another node's WAL entry. */
final class EventReplay {
  private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);

  private EventReplay() {}

  /** True while this thread dispatches events another node fired; the journal must not re-log them. */
  static boolean isReplaying() {
    return REPLAYING.get();
  }

  static Scope enter() {
    REPLAYING.set(true);
    return () -> REPLAYING.set(false);
  }

  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
