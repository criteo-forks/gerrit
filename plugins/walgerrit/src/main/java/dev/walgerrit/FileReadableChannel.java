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
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;

/** A seekable read channel over one immutable WAL file. */
final class FileReadableChannel implements ReadableChannel {
  private final FileChannel channel;

  FileReadableChannel(Path path) throws IOException {
    channel = FileChannel.open(path, StandardOpenOption.READ);
  }

  @Override
  public int read(ByteBuffer buffer) throws IOException {
    return channel.read(buffer);
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  @Override
  public boolean isOpen() {
    return channel.isOpen();
  }

  @Override
  public long position() throws IOException {
    return channel.position();
  }

  @Override
  public void position(long position) throws IOException {
    channel.position(position);
  }

  @Override
  public long size() throws IOException {
    return channel.size();
  }

  @Override
  public int blockSize() {
    return 0;
  }

  @Override
  public void setReadAheadBytes(int bytes) {
    // The local filesystem and operating system page cache provide read-ahead.
  }
}
