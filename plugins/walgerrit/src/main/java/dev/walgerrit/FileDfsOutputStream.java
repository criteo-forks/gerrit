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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;

/** A random-readable DFS write stream backed by a staging file. */
final class FileDfsOutputStream extends DfsOutputStream {
  private final FileChannel channel;

  FileDfsOutputStream(Path path) throws IOException {
    channel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);
  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    ByteBuffer source = ByteBuffer.wrap(buffer, offset, length);
    while (source.hasRemaining()) {
      channel.write(source);
    }
  }

  @Override
  public int read(long position, ByteBuffer buffer) throws IOException {
    return channel.read(buffer, position);
  }

  @Override
  public void flush() throws IOException {
    channel.force(false);
  }

  @Override
  public void close() throws IOException {
    if (channel.isOpen()) {
      channel.force(true);
      channel.close();
    }
  }
}
