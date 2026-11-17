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

package com.google.gerrit.lucene;

import com.google.gerrit.server.cancellation.RequestStateContext;
import java.util.concurrent.CancellationException;
import org.apache.lucene.search.IndexSearcher;

/**
 * Stops an index read that is of no use any more.
 *
 * <p>A read is done for a request, by a thread of the index unless the index runs its queries
 * directly, while the thread of the request waits for it. It is of no use once the request is
 * cancelled or its result set is closed. Only the thread of the request knows whether the request
 * is cancelled: it {@link #abandon() abandons} the read, and the read {@link #check() looks} at
 * that before each piece of its work.
 */
final class ReadCancellation {
  private volatile boolean abandoned;

  void abandon() {
    abandoned = true;
  }

  /**
   * Ends the read by an exception if it was abandoned, or if the reading thread is the thread of
   * the request and that request is cancelled.
   */
  void check() {
    if (abandoned) {
      throw new CancellationException("index read abandoned");
    }
    RequestStateContext.abortIfCancelled();
  }

  /**
   * Returns a searcher of the index of the given one whose searches {@link #check() look} at this
   * while they collect hits, so that a search that is under way ends as well.
   *
   * <p>The given searcher is shared by all reads of its index and is left as it is: what ends a
   * search is set on a searcher that only this read uses.
   */
  IndexSearcher searcherFor(IndexSearcher shared) {
    IndexSearcher searcher = new IndexSearcher(shared.getIndexReader());
    searcher.setSimilarity(shared.getSimilarity());
    searcher.setQueryCache(shared.getQueryCache());
    searcher.setQueryCachingPolicy(shared.getQueryCachingPolicy());
    searcher.setTimeout(
        () -> {
          check();
          // Lucene returns the hits it has so far as if they were all when told to stop.
          return false;
        });
    return searcher;
  }
}
