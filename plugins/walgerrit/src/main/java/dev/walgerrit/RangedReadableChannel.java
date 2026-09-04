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
import java.nio.channels.ClosedChannelException;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;

/**
 * JGit's view of a pack that is fetched from the store in chunks as it is read.
 *
 * <p>Each read makes the requested range present in the shared {@link ChunkedFile} and then reads
 * it from the local file, so the block cache only ever sees local bytes. JGit's read-ahead hint
 * widens what a read makes present, which turns a sequential copy, as a clone performs, into one
 * range request per run of chunks. The underlying file channel is shared by every open handle of
 * the pack and stays open; closing this channel only closes this view.
 */
final class RangedReadableChannel implements ReadableChannel {
  /** Upper bound for the read-ahead JGit may ask for. */
  private static final int MAX_READ_AHEAD = 32 << 20;

  private final ChunkedFile file;
  private long position;
  private int readAhead;
  private boolean open = true;

  RangedReadableChannel(ChunkedFile file) {
    this.file = file;
  }

  @Override
  public int read(ByteBuffer destination) throws IOException {
    if (!open) {
      throw new ClosedChannelException();
    }
    long size = file.size();
    if (position >= size) {
      return -1;
    }
    long wanted = Math.min(destination.remaining(), size - position);
    file.ensure(position, wanted, readAhead);
    int read = file.read(destination, position);
    if (read > 0) {
      position += read;
    }
    return read;
  }

  @Override
  public long position() {
    return position;
  }

  @Override
  public void position(long newPosition) {
    position = newPosition;
  }

  @Override
  public long size() {
    return file.size();
  }

  @Override
  public int blockSize() {
    return 0;
  }

  @Override
  public void setReadAheadBytes(int bytes) {
    readAhead = Math.max(0, Math.min(bytes, MAX_READ_AHEAD));
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
  }
}
