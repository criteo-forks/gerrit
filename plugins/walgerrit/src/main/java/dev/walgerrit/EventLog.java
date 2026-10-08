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

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The events every node journals, as an ordered log per repository, for a plugin that forwards
 * them elsewhere. Bound in the system injector by {@link WalGitIndexModule}.
 *
 * <p>Each repository's log holds every Gerrit event fired by any node, once, in the order that
 * node fired them, as the JSON {@code stream-events} prints. Log entries are never deleted, so a
 * reader that falls behind loses nothing. A reader keeps a {@link Cursor} per repository in the
 * shared store and moves it after it has dealt with the entries before it; a reader that runs on
 * several nodes elects one holder with a {@link Lease} of the same name, and the compare-and-swap
 * on every cursor write keeps a holder that lost the lease from moving a cursor back.
 *
 * <p>Repositories are named by id. The project name an id is bound to can change by a rename; the
 * name returned with each read is the one the catalog binds the id to at that time.
 *
 * <p>Reader names are lowercase letters, digits and dashes, starting with a letter or digit, at
 * most 63 characters; repository ids are what {@link #repositories()} returns.
 */
public interface EventLog {
  /** A place in one repository's log: after the entry with this sequence and transaction id. */
  record Position(long sequence, String transactionId) {
    /** Before the first entry. */
    public static final Position START = new Position(0, "");

    public Position {
      if (sequence < 0) {
        throw new IllegalArgumentException("negative sequence " + sequence);
      }
      Objects.requireNonNull(transactionId, "transactionId");
      if (sequence == 0 && !transactionId.isEmpty()) {
        throw new IllegalArgumentException("the start of the log has no transaction id");
      }
    }
  }

  /** A repository's head and the version of the manifest that names it. */
  record Head(Position position, String version) {}

  /**
   * One log entry that carries events: the events in firing order, where it sits, when and by
   * which node ({@code host:pid}) it was written.
   */
  record Entry(Position position, long createdAtEpochMillis, String writer, List<String> events) {}

  /**
   * What a read found after a position: the entries that carry events, oldest first, and the head
   * the read reached. {@code projectName} is empty for an id the catalog no longer binds.
   */
  record Entries(String repositoryId, Optional<String> projectName, List<Entry> entries, Head head) {}

  /**
   * A reader's stored cursor: the position it acknowledged, the manifest version when that
   * position was the head, or empty, and the store's version of the cursor itself, which the next
   * write must name.
   */
  record Cursor(Position position, String headVersion, String storeVersion) {}

  /** A lease this node holds; closing it releases the lease. */
  interface Lease extends AutoCloseable {
    /**
     * Extends the lease by {@code term} from now.
     *
     * @throws IOException when another node took the lease over, or the store is unreachable
     */
    void renew(Duration term) throws IOException;

    /** Marks the lease expired so another node can take it at once; best effort. */
    @Override
    void close();
  }

  /** Stops a listener; idempotent. */
  interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * The position given to a read is not in the repository's history: it is ahead of the head, or
   * names a transaction the log does not, which happens when a manifest is restored to an older
   * version. Entries after it can no longer be read; the reader decides where to resume.
   */
  final class HistoryChangedException extends IOException {
    private static final long serialVersionUID = 1L;

    public HistoryChangedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A cursor write lost to another: the cursor exists already, or changed since it was read. */
  final class CursorConflictException extends IOException {
    private static final long serialVersionUID = 1L;

    public CursorConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Every repository with the version of its manifest, from one listing of the store. A version
   * that equals one seen before means nothing was published to that repository since.
   */
  NavigableMap<String, String> repositories() throws IOException;

  /** The repository's head as the store has it now. */
  Head head(String repositoryId) throws IOException;

  /**
   * Every entry after {@code after} that carries events, up to the head as the store has it now.
   * Reads one object per entry behind the head, events or not.
   *
   * @throws HistoryChangedException when {@code after} is not in the repository's history
   */
  Entries readAfter(String repositoryId, Position after) throws IOException;

  /** The repositories {@code reader} has a cursor for, each with the cursor's store version. */
  NavigableMap<String, String> cursors(String reader) throws IOException;

  /** {@code reader}'s cursor for a repository, if it has one. */
  Optional<Cursor> cursor(String reader, String repositoryId) throws IOException;

  /**
   * Stores {@code reader}'s cursor for a repository: creates it when {@code expected} is empty,
   * replaces exactly {@code expected} otherwise.
   *
   * @param headVersion the manifest version when {@code position} is that manifest's head, or an
   *     empty string
   * @throws CursorConflictException when the cursor is not what {@code expected} says
   */
  Cursor saveCursor(
      String reader,
      String repositoryId,
      Position position,
      String headVersion,
      Optional<Cursor> expected)
      throws IOException;

  /** Takes {@code reader}'s lease for {@code term} unless another node holds an unexpired one. */
  Optional<Lease> acquireLease(String reader, Duration term) throws IOException;

  /** Who holds {@code reader}'s lease now ({@code host:pid}), if anyone does. */
  Optional<String> leaseOwner(String reader) throws IOException;

  /**
   * Calls {@code listener} with a repository id when this node publishes to that repository, or a
   * peer announces it did. Announcements are best effort; a reader still sweeps {@link
   * #repositories()}. The listener runs on the publishing or receiving thread and must not block.
   */
  Registration onChange(Consumer<String> listener);
}
