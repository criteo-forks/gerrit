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

import dev.walgerrit.proto.StorageProto.IndexCursor;
import dev.walgerrit.proto.StorageProto.LogPosition;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Crash-safe node-local acknowledgement of WAL entries applied to local secondary indexes. The
 * cursor names the last applied entry by sequence and transaction id, so a manifest that was
 * restored to an older version and diverged is detected rather than silently followed.
 *
 * <p>The same file holds the merges whose change-merged event this node still owes the log, so that
 * passing a transaction and recording what it merged are one replacement of the file.
 */
final class IndexCursorStore {
  private final Path path;

  IndexCursorStore(Path path) {
    this.path = path;
  }

  IndexCursor read() throws IOException {
    if (!Files.exists(path)) {
      return IndexCursor.getDefaultInstance();
    }
    return IndexCursor.parseFrom(Files.readAllBytes(path));
  }

  /** Whether this node has a cursor for the repository at all. */
  boolean exists() {
    return Files.exists(path);
  }

  /**
   * {@code cursor} at another entry: {@code manifestVersion} names the manifest whose head {@code
   * sequence} is, or is empty while replay is still short of the head. What the cursor holds
   * besides its place is kept.
   */
  static IndexCursor at(
      IndexCursor cursor, long sequence, String transactionId, String manifestVersion) {
    return cursor.toBuilder()
        .setSequence(sequence)
        .setTransactionId(transactionId == null ? "" : transactionId)
        .setManifestVersion(manifestVersion == null ? "" : manifestVersion)
        .build();
  }

  /**
   * The cursor that replaces {@code this} one when it is moved to an entry without the entries in
   * between being read, as an index rebuild and an offline reindex do. The merges it holds stay,
   * and it remembers up to where merges were read, so that the next replay reads the rest. The
   * changes it lists as unindexed go: both index every change the repositories hold. A node that
   * had no cursor has read nothing and owes nothing.
   */
  IndexCursor movedTo(long sequence, String transactionId, String manifestVersion)
      throws IOException {
    if (!exists()) {
      return at(IndexCursor.getDefaultInstance(), sequence, transactionId, manifestVersion);
    }
    IndexCursor old = read();
    IndexCursor.Builder moved =
        at(old, sequence, transactionId, manifestVersion).toBuilder().clearUnindexed();
    if (!old.hasMergesReadTo() && old.getSequence() != sequence) {
      moved.setMergesReadTo(
          LogPosition.newBuilder()
              .setSequence(old.getSequence())
              .setTransactionId(old.getTransactionId()));
    }
    return moved.build();
  }

  /** Replaces the cursor and everything it holds in one step. */
  void write(IndexCursor cursor) throws IOException {
    Files.createDirectories(path.getParent());
    byte[] bytes = cursor.toByteArray();
    Path temporary =
        path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
    try {
      try (FileChannel channel =
          FileChannel.open(
              temporary,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE)) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      try {
        Files.move(
            temporary,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException(
            "Cursor filesystem does not support atomic replacement: " + path, exception);
      }
      forceDirectory(path.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException ignored) {
      // Some filesystems do not expose directory fsync through FileChannel.
    }
  }
}
