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
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Crash-safe node-local acknowledgement of WAL entries applied to local secondary indexes. */
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

  void write(long sequence, String logKey) throws IOException {
    Files.createDirectories(path.getParent());
    byte[] bytes =
        IndexCursor.newBuilder().setSequence(sequence).setLogKey(logKey).build().toByteArray();
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
