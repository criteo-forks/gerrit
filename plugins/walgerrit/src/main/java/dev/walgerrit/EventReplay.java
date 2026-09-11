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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marks work this node does on another node's behalf, delivering its events or repeating its
 * index writes, which {@link WalJournal} must not record again.
 */
final class EventReplay {
  private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
  private static final AtomicInteger EVERYWHERE = new AtomicInteger();

  private EventReplay() {}

  /** True while this thread, or the whole process, repeats another node's work. */
  static boolean isReplaying() {
    return REPLAYING.get() || EVERYWHERE.get() > 0;
  }

  /** Marks the calling thread until the scope closes. */
  static Scope enter() {
    REPLAYING.set(true);
    return () -> REPLAYING.set(false);
  }

  /** Marks every thread until the scope closes, for work that fans out over a pool. */
  static Scope enterEverywhere() {
    EVERYWHERE.incrementAndGet();
    return EVERYWHERE::decrementAndGet;
  }

  interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
