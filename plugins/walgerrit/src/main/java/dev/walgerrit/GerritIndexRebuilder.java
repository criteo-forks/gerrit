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

import com.google.common.io.ByteStreams;
import com.google.gerrit.index.Index;
import com.google.gerrit.index.IndexDefinition;
import com.google.gerrit.index.SiteIndexer;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds Gerrit's indexes the way the offline {@code reindex} program does: mark the search
 * index not ready, empty it, refill it with the index's site indexer, mark it ready. Emptying
 * first is what makes documents for deleted changes disappear, which incremental replay from
 * NoteDb could not achieve.
 */
@Singleton
final class GerritIndexRebuilder implements IndexRebuilder {
  private static final Logger logger = LoggerFactory.getLogger(GerritIndexRebuilder.class);

  private final Collection<IndexDefinition<?, ?, ?>> definitions;

  @Inject
  GerritIndexRebuilder(Collection<IndexDefinition<?, ?, ?>> definitions) {
    this.definitions = definitions;
  }

  @Override
  public void rebuildAll() throws IOException {
    for (IndexDefinition<?, ?, ?> definition : definitions) {
      rebuild(definition);
    }
  }

  private <K, V, I extends Index<K, V>> void rebuild(IndexDefinition<K, V, I> definition)
      throws IOException {
    I index = definition.getIndexCollection().getSearchIndex();
    if (index == null) {
      throw new IOException("No active search index configured for " + definition.getName());
    }
    logger.info(
        "WalGerrit is rebuilding the {} index (version {}) from current repository state",
        definition.getName(),
        index.getSchema().getVersion());
    index.markReady(false);
    index.deleteAll();
    SiteIndexer<K, V, I> indexer = definition.getSiteIndexer(false);
    indexer.setProgressOut(ByteStreams.nullOutputStream());
    indexer.setVerboseOut(ByteStreams.nullOutputStream());
    SiteIndexer.Result result = indexer.indexAll(index);
    if (!result.success()) {
      throw new IOException(
          "Rebuilding the "
              + definition.getName()
              + " index failed for "
              + result.failedCount()
              + " documents; run the offline reindex before starting this node again");
    }
    index.markReady(true);
    logger.info(
        "WalGerrit rebuilt the {} index: {} documents in {} s",
        definition.getName(),
        result.doneCount(),
        result.elapsed(TimeUnit.SECONDS));
  }
}
