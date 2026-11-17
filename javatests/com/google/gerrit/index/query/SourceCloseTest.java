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

package com.google.gerrit.index.query;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.index.PaginationType;
import com.google.gerrit.index.QueryOptions;
import com.google.gerrit.server.cancellation.RequestCancelledException;
import com.google.gerrit.server.cancellation.RequestStateContext;
import com.google.gerrit.server.cancellation.RequestStateProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

/**
 * A source that wraps another one dispatches the other's query when it is read, before anybody asks
 * for results. Closing its result set must reach that query.
 */
public class SourceCloseTest extends PredicateTest {
  private static final IndexConfig PAGINATED =
      IndexConfig.builder().paginationType(PaginationType.OFFSET).build();
  private static final IndexConfig SEARCH_AFTER =
      IndexConfig.builder().paginationType(PaginationType.SEARCH_AFTER).build();
  private static final IndexConfig NOT_PAGINATED =
      IndexConfig.builder().paginationType(PaginationType.NONE).build();

  @Test
  public void closingResultsOfFilteredSourceClosesItsSource() {
    PagedSource source = new PagedSource(2, "a", "b", "c");

    new FilteredSource<>(source, 0, NOT_PAGINATED).read().close();

    assertThat(source.dispatched).hasSize(1);
    assertThat(source.dispatched.get(0).closed).isTrue();
  }

  @Test
  public void closingResultsOfPaginatingSourceClosesItsSource() {
    PagedSource source = new PagedSource(2, "a", "b", "c");

    new PaginatingSource<>(source, 0, PAGINATED).read().close();

    assertThat(source.dispatched).hasSize(1);
    assertThat(source.dispatched.get(0).closed).isTrue();
  }

  @Test
  public void closingResultsOfAndSourceClosesItsSource() {
    PagedSource source = new PagedSource(2, "a", "b", "c");

    new AndSource<>(ImmutableList.of(source), PAGINATED).read().close();

    assertThat(source.dispatched).hasSize(1);
    assertThat(source.dispatched.get(0).closed).isTrue();
  }

  @Test
  public void pagesThatPaginatingSourceReadsAreClosed() {
    PagedSource source = new PagedSource(2, "a", "b", "c");

    ResultSet<String> results = new PaginatingSource<>(source, 0, PAGINATED).read();
    assertThat(results.toList()).containsExactly("a", "b", "c").inOrder();

    // The first page is closed with the results, the pages after it once they are read.
    assertThat(source.dispatched).hasSize(2);
    assertThat(source.dispatched.get(0).closed).isFalse();
    assertThat(source.dispatched.get(1).closed).isTrue();
    results.close();
    assertThat(source.dispatched.get(0).closed).isTrue();
  }

  @Test
  public void pagesThatFilteredSourceReadsAreClosed() {
    PagedSource source = new PagedSource(2, "a", "b", "c");

    // Nothing of the first page matches, so the source is asked for more.
    FilteredSource<String> onlyC =
        new FilteredSource<>(source, 0, NOT_PAGINATED) {
          @Override
          protected boolean match(String object) {
            return object.equals("c");
          }

          @Override
          protected boolean isMatchable() {
            return true;
          }
        };
    assertThat(onlyC.read().toList()).containsExactly("c");

    assertThat(source.dispatched).hasSize(2);
    assertThat(source.dispatched.get(1).closed).isTrue();
  }

  @Test
  public void paginatingSourceAsksAPageWhereToGoOnBeforeItClosesThePage() {
    PagedSource source = new PagedSource(2, "a", "b", "c", "d", "e");
    source.limit = 10;

    // A page that is closed does not say where to go on: see RecordingResultSet.
    ResultSet<String> results = new PaginatingSource<>(source, 0, SEARCH_AFTER).read();
    assertThat(results.toList()).containsExactly("a", "b", "c", "d", "e").inOrder();

    assertThat(source.dispatched).hasSize(3);
    assertThat(source.dispatched.get(1).closed).isTrue();
    assertThat(source.dispatched.get(2).closed).isTrue();
  }

  @Test
  public void filteredSourceStopsFilteringWhenItsRequestIsCancelled() {
    PagedSource source = new PagedSource(4, "a", "b", "c", "d");
    List<String> filtered = new ArrayList<>();

    assertCancelledAfter(2, filtered, new RecordingFilter(source, NOT_PAGINATED, filtered));

    assertThat(filtered).containsExactly("a", "b").inOrder();
  }

  @Test
  public void paginatingSourceStopsFilteringWhenItsRequestIsCancelled() {
    PagedSource source = new PagedSource(2, "a", "b", "c", "d");
    source.limit = 10;
    List<String> filtered = new ArrayList<>();

    // The third result is on the second page.
    assertCancelledAfter(
        3,
        filtered,
        new PaginatingSource<>(source, 0, PAGINATED) {
          @Override
          protected boolean match(String object) {
            filtered.add(object);
            return true;
          }
        });

    assertThat(filtered).containsExactly("a", "b", "c").inOrder();
    assertThat(source.dispatched).hasSize(2);
    assertThat(source.dispatched.get(1).closed).isTrue();
  }

  private static void assertCancelledAfter(
      int results, List<String> filtered, DataSource<String> source) {
    RequestStateProvider cancelledAfterSomeResults =
        onCancelled -> {
          if (filtered.size() == results) {
            onCancelled.onCancel(RequestStateProvider.Reason.CLIENT_CLOSED_REQUEST, null);
          }
        };
    try (RequestStateContext context =
        RequestStateContext.open().addRequestStateProvider(cancelledAfterSomeResults)) {
      ResultSet<String> read = source.read();
      assertThrows(RequestCancelledException.class, read::toList);
    }
  }

  private static class RecordingFilter extends FilteredSource<String> {
    private final List<String> filtered;

    RecordingFilter(DataSource<String> source, IndexConfig indexConfig, List<String> filtered) {
      super(source, 0, indexConfig);
      this.filtered = filtered;
    }

    @Override
    protected boolean match(String object) {
      filtered.add(object);
      return true;
    }
  }

  /** A source of a list of values that hands them out page by page and records what it did. */
  private static class PagedSource extends TestDataSourcePredicate implements Paginated<String> {
    final List<RecordingResultSet> dispatched = new ArrayList<>();
    private final ImmutableList<String> values;
    private final int pageSize;
    // How many results the query wants; a page of them unless set.
    int limit;

    PagedSource(int pageSize, String... values) {
      super("source", "values", 1, values.length);
      this.values = ImmutableList.copyOf(values);
      this.pageSize = pageSize;
      this.limit = pageSize;
    }

    @Override
    public ResultSet<String> read() {
      return restart(0, pageSize);
    }

    @Override
    public QueryOptions getOptions() {
      return QueryOptions.create(PAGINATED, 0, pageSize, limit, ImmutableSet.of());
    }

    @Override
    public ResultSet<String> restart(int start) {
      return restart(start, pageSize);
    }

    @Override
    public ResultSet<String> restart(int start, int pageSize) {
      int end = Math.min(start + pageSize, values.size());
      RecordingResultSet page =
          new RecordingResultSet(values.subList(Math.min(start, values.size()), end), end);
      dispatched.add(page);
      return page;
    }

    @Override
    public ResultSet<String> restart(Object searchAfter, int pageSize) {
      return restart((int) searchAfter, pageSize);
    }
  }

  /** Results that record their close and, like a cursor, forget where they were when closed. */
  private static class RecordingResultSet implements ResultSet<String> {
    private final ImmutableList<String> results;
    private final int end;
    boolean closed;

    RecordingResultSet(List<String> results, int end) {
      this.results = ImmutableList.copyOf(results);
      this.end = end;
    }

    @Override
    public Iterator<String> iterator() {
      return results.iterator();
    }

    @Override
    public ImmutableList<String> toList() {
      return results;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public Object searchAfter() {
      if (closed) {
        throw new IllegalStateException("closed");
      }
      return end;
    }
  }
}
