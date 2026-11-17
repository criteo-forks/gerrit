// Copyright (C) 2022 The Android Open Source Project
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
import com.google.common.collect.Lists;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeDataSource;
import com.google.gerrit.server.query.change.OrSource;
import java.util.Collection;
import java.util.Iterator;
import org.junit.Test;

public class OrSourceTest extends PredicateTest {
  @Test
  public void ensureAllChildrenAreDataSources() {
    TestMatchablePredicate<ChangeData> p1 = new TestMatchablePredicate<>("predicate1", "foo", 10);
    TestMatchablePredicate<ChangeData> p2 = new TestMatchablePredicate<>("predicate2", "foo", 1);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new OrSource(Lists.newArrayList(p1, p2)));
    assertThat(thrown).hasMessageThat().contains("No ChangeDataSource");
  }

  @Test
  public void closingResultsClosesTheReadsOfAllChildren() {
    ReadSource first = new ReadSource(false);
    ReadSource second = new ReadSource(false);

    new OrSource(ImmutableList.of(first, second)).read().close();

    assertThat(first.results.closed).isTrue();
    assertThat(second.results.closed).isTrue();
  }

  @Test
  public void readsThatWereStartedAreClosedWhenALaterChildFailsToStartIts() {
    ReadSource first = new ReadSource(false);
    ReadSource second = new ReadSource(false);
    ReadSource failing = new ReadSource(true);
    ReadSource notReached = new ReadSource(false);

    OrSource source = new OrSource(ImmutableList.of(first, second, failing, notReached));
    assertThrows(IllegalStateException.class, source::read);

    assertThat(first.results.closed).isTrue();
    assertThat(second.results.closed).isTrue();
    assertThat(notReached.results).isNull();
  }

  /** A source whose read either fails or hands out an empty result set that records its close. */
  private static class ReadSource extends Predicate<ChangeData> implements ChangeDataSource {
    private final boolean fails;
    ClosableResults results;

    ReadSource(boolean fails) {
      this.fails = fails;
    }

    @Override
    public ResultSet<ChangeData> read() {
      if (fails) {
        throw new IllegalStateException("index is not there");
      }
      results = new ClosableResults();
      return results;
    }

    @Override
    public ResultSet<FieldBundle> readRaw() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasChange() {
      return false;
    }

    @Override
    public int getCardinality() {
      return 1;
    }

    @Override
    public Predicate<ChangeData> copy(Collection<? extends Predicate<ChangeData>> children) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object other) {
      return this == other;
    }
  }

  private static class ClosableResults implements ResultSet<ChangeData> {
    boolean closed;

    @Override
    public Iterator<ChangeData> iterator() {
      return toList().iterator();
    }

    @Override
    public ImmutableList<ChangeData> toList() {
      return ImmutableList.of();
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public Object searchAfter() {
      return null;
    }
  }
}
