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

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.server.cancellation.RequestCancelledException;
import com.google.gerrit.server.cancellation.RequestStateContext;
import com.google.gerrit.server.cancellation.RequestStateProvider;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReadCancellationTest {
  private static final int SEGMENTS = 4;
  private static final int DOCUMENTS_PER_SEGMENT = 1000;
  private static final int DOCUMENTS = SEGMENTS * DOCUMENTS_PER_SEGMENT;

  private Directory directory;
  private DirectoryReader reader;
  private IndexSearcher shared;

  @Before
  public void createIndex() throws Exception {
    directory = new ByteBuffersDirectory();
    try (IndexWriter writer =
        new IndexWriter(
            directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
      for (int segment = 0; segment < SEGMENTS; segment++) {
        for (int i = 0; i < DOCUMENTS_PER_SEGMENT; i++) {
          Document document = new Document();
          document.add(new StringField("id", segment + "-" + i, Field.Store.NO));
          writer.addDocument(document);
        }
        writer.commit();
      }
    }
    reader = DirectoryReader.open(directory);
    assertThat(reader.leaves()).hasSize(SEGMENTS);
    shared = new IndexSearcher(reader);
  }

  @After
  public void closeIndex() throws Exception {
    reader.close();
    directory.close();
  }

  @Test
  public void searchOfReadThatGoesOnFindsEverything() throws Exception {
    Hits hits = new Hits();

    new ReadCancellation().searcherFor(shared).search(new MatchAllDocsQuery(), hits);

    assertThat(hits.collected.get()).isEqualTo(DOCUMENTS);
  }

  @Test
  public void searchUnderWayEndsWhenItsReadIsAbandoned() throws Exception {
    ReadCancellation cancellation = new ReadCancellation();
    Hits hits = new Hits();
    hits.at(DOCUMENTS_PER_SEGMENT / 2, cancellation::abandon);

    assertThrows(
        CancellationException.class,
        () -> cancellation.searcherFor(shared).search(new MatchAllDocsQuery(), hits));

    // The search looks every so many documents, more often at the start of a segment.
    assertThat(hits.collected.get()).isAtLeast(DOCUMENTS_PER_SEGMENT / 2);
    assertThat(hits.collected.get()).isLessThan(DOCUMENTS_PER_SEGMENT);
  }

  @Test
  public void searchUnderWayOnThreadOfRequestEndsWhenTheRequestIsCancelled() throws Exception {
    Hits hits = new Hits();
    RequestStateProvider cancelledHalfwayThroughFirstSegment =
        onCancelled -> {
          if (hits.collected.get() >= DOCUMENTS_PER_SEGMENT / 2) {
            onCancelled.onCancel(
                RequestStateProvider.Reason.CLIENT_PROVIDED_DEADLINE_EXCEEDED, null);
          }
        };

    try (RequestStateContext context =
        RequestStateContext.open().addRequestStateProvider(cancelledHalfwayThroughFirstSegment)) {
      assertThrows(
          RequestCancelledException.class,
          () -> new ReadCancellation().searcherFor(shared).search(new MatchAllDocsQuery(), hits));
    }

    assertThat(hits.collected.get()).isLessThan(DOCUMENTS_PER_SEGMENT);
  }

  @Test
  public void otherReadsOfTheSameIndexGoOn() throws Exception {
    ReadCancellation abandoned = new ReadCancellation();
    IndexSearcher searcherOfAbandoned = abandoned.searcherFor(shared);
    abandoned.abandon();
    assertThrows(
        CancellationException.class,
        () -> searcherOfAbandoned.search(new MatchAllDocsQuery(), new Hits()));

    // What ends the abandoned read's search is not set on the searcher that the reads share.
    assertThat(shared.getTimeout()).isNull();
    Hits ofSharedSearcher = new Hits();
    shared.search(new MatchAllDocsQuery(), ofSharedSearcher);
    assertThat(ofSharedSearcher.collected.get()).isEqualTo(DOCUMENTS);

    Hits ofAnotherRead = new Hits();
    new ReadCancellation().searcherFor(shared).search(new MatchAllDocsQuery(), ofAnotherRead);
    assertThat(ofAnotherRead.collected.get()).isEqualTo(DOCUMENTS);
  }

  /** Counts the documents that a search finds, and can do something at one of them. */
  private static class Hits implements CollectorManager<SimpleCollector, Void> {
    final AtomicInteger collected = new AtomicInteger();
    private int at = -1;
    private Runnable action;

    void at(int count, Runnable action) {
      this.at = count;
      this.action = action;
    }

    @Override
    public SimpleCollector newCollector() {
      return new SimpleCollector() {
        @Override
        public void collect(int doc) throws IOException {
          if (collected.incrementAndGet() == at) {
            action.run();
          }
        }

        @Override
        public ScoreMode scoreMode() {
          return ScoreMode.COMPLETE_NO_SCORES;
        }
      };
    }

    @Override
    public Void reduce(Collection<SimpleCollector> collectors) {
      return null;
    }
  }
}
