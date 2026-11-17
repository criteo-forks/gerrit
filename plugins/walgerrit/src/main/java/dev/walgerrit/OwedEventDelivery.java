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

/**
 * Lets the tailer fire the change-merged events an earlier process of this node owes. Bound after
 * the tailer and the journal, so that it starts when both run and ends before either stops: the
 * tailer's start-up sweep finds what is owed, and an event fired while the journal does not run
 * would not reach the log.
 */
@Singleton
final class OwedEventDelivery implements LifecycleListener {
  private final IndexEventTailer tailer;

  @Inject
  OwedEventDelivery(IndexEventTailer tailer) {
    this.tailer = tailer;
  }

  @Override
  public void start() {
    tailer.startOwedEventDelivery();
  }

  @Override
  public void stop() {
    // Listeners stop in reverse: this ends the delivery while the journal still runs, so that what
    // the listeners of an event fire in turn is journaled.
    tailer.stopOwedEventDelivery();
  }
}
