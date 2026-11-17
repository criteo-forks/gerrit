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

package com.google.gerrit.acceptance.server.index;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.server.git.QueueProvider.QueueType.INTERACTIVE;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.server.cancellation.RequestCancelledException;
import com.google.gerrit.server.cancellation.RequestStateContext;
import com.google.gerrit.server.cancellation.RequestStateProvider;
import com.google.gerrit.server.index.IndexExecutor;
import com.google.gerrit.server.restapi.change.QueryChanges;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * A change query of the Lucene index is read by a thread of the index while the thread of the
 * request waits for it.
 */
public class ChangeIndexReadCancellationIT extends AbstractDaemonTest {
  private static final int CHANGES = 5;

  // The tests run the index with one thread.
  @Inject
  @IndexExecutor(INTERACTIVE)
  private ListeningExecutorService indexExecutor;

  @Inject private Provider<QueryChanges> queryChangesProvider;

  @Test
  @GerritConfig(name = "index.type", value = "lucene")
  public void readStopsWhenItsRequestIsCancelled() throws Exception {
    for (int i = 0; i < CHANGES; i++) {
      createChange();
    }

    // A read looks whether to go on before each piece of its work. On its own thread that asks
    // the providers registered there, which tells this test how far a read is and lets it hold
    // the read.
    ReadObserver read = new ReadObserver();
    RequestStateContext contextOfIndexThread =
        indexExecutor.submit(() -> RequestStateContext.open().addRequestStateProvider(read)).get();
    try {
      // What a read does undisturbed: it looks before it searches, while it searches, and before
      // each change that it loads.
      assertThat(gApi.changes().query("status:open").get()).hasSize(CHANGES);
      waitForIndexThread();
      int looksOfWholeRead = read.looks.get();
      assertThat(looksOfWholeRead).isAtLeast(1 + 1 + CHANGES);

      // The same read, held two changes before its end. The request is cancelled meanwhile.
      read.looks.set(0);
      read.holdAt = looksOfWholeRead - 2;
      RequestStateProvider cancelledOnceTheReadIsHeld =
          onCancelled -> {
            if (read.held.getCount() == 0) {
              onCancelled.onCancel(RequestStateProvider.Reason.CLIENT_CLOSED_REQUEST, null);
            }
          };
      try (RequestStateContext contextOfRequest =
          RequestStateContext.open().addRequestStateProvider(cancelledOnceTheReadIsHeld)) {
        // The request does not wait for the read that is held.
        assertThrows(
            RequestCancelledException.class, () -> gApi.changes().query("status:open").get());
      }
      // The request ended because it was cancelled, not because the read went on by itself.
      assertThat(read.holdExpired).isFalse();

      // Let go, the read does not load the changes that were left.
      read.released.countDown();
      waitForIndexThread();
      assertThat(read.holdExpired).isFalse();
      assertThat(read.looks.get()).isEqualTo(looksOfWholeRead - 2);
    } finally {
      read.released.countDown();
      indexExecutor.submit(contextOfIndexThread::close).get();
    }
  }

  @Test
  @GerritConfig(name = "index.type", value = "lucene")
  public void otherReadsOfCancelledRequestAreNotDone() throws Exception {
    createChange();

    ReadObserver read = new ReadObserver();
    RequestStateContext contextOfIndexThread =
        indexExecutor.submit(() -> RequestStateContext.open().addRequestStateProvider(read)).get();
    try {
      // Two queries in one request are both given to the index before the results of the first
      // are fetched. The first read is held as soon as it begins, the second waits for the thread.
      read.holdAt = 1;
      QueryChanges twoQueries = queryChangesProvider.get();
      twoQueries.addQuery("status:open");
      twoQueries.addQuery("status:merged");
      RequestStateProvider cancelledOnceTheReadIsHeld =
          onCancelled -> {
            if (read.held.getCount() == 0) {
              onCancelled.onCancel(RequestStateProvider.Reason.CLIENT_CLOSED_REQUEST, null);
            }
          };
      try (RequestStateContext contextOfRequest =
          RequestStateContext.open().addRequestStateProvider(cancelledOnceTheReadIsHeld)) {
        assertThrows(
            RequestCancelledException.class, () -> twoQueries.apply(TopLevelResource.INSTANCE));
      }
      assertThat(read.holdExpired).isFalse();

      // Let go, the first read ends and the second is not begun.
      read.released.countDown();
      waitForIndexThread();
      assertThat(read.holdExpired).isFalse();
      assertThat(read.looks.get()).isEqualTo(1);
    } finally {
      read.released.countDown();
      indexExecutor.submit(contextOfIndexThread::close).get();
    }
  }

  private void waitForIndexThread() throws Exception {
    indexExecutor.submit(() -> {}).get();
  }

  /** Counts how often the Lucene index looks whether to go on, and can hold it at one look. */
  private static class ReadObserver implements RequestStateProvider {
    final AtomicInteger looks = new AtomicInteger();
    final CountDownLatch held = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    volatile int holdAt;
    volatile boolean holdExpired;

    @Override
    public void checkIfCancelled(OnCancelled onCancelled) {
      // The queue that runs the tasks of the index thread looks as well, around each task.
      if (!calledByLuceneIndex()) {
        return;
      }
      if (looks.incrementAndGet() == holdAt) {
        held.countDown();
        if (!Uninterruptibles.awaitUninterruptibly(released, 30, SECONDS)) {
          holdExpired = true;
        }
      }
    }

    private static boolean calledByLuceneIndex() {
      return StackWalker.getInstance()
          .walk(
              frames ->
                  frames.anyMatch(
                      frame -> frame.getClassName().startsWith("com.google.gerrit.lucene.")));
    }
  }
}
