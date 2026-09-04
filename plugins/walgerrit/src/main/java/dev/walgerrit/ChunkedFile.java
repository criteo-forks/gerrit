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

import com.google.protobuf.ByteString;
import dev.walgerrit.proto.StorageProto.PartialFile;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.UUID;

/**
 * An immutable store object materialised into the local cache in chunks, on demand.
 *
 * <p>The local file carries its final name from the start and is created sparse at the object's
 * full size; the sidecar {@code <name>.chunks} beside it names the chunks that are present and is
 * what marks the file incomplete. When the last chunk arrives the sidecar is removed, and the file
 * is then indistinguishable from one downloaded whole. A file without a sidecar is complete by
 * contract, so a sidecar is never removed before the data it describes is on disk: each chunk is
 * written and forced first, the sidecar is rewritten atomically after. A crash between the two
 * loses at most the chunk in flight, which is fetched again. A sidecar that does not describe the
 * file beside it (another size or chunk size, or missing data) resets the pair.
 *
 * <p>Reads of present chunks proceed concurrently and without locking; a missing chunk is fetched
 * under the file's lock, together with the missing chunks that follow it within the requested
 * range, so a sequential scan costs one request per run rather than per chunk.
 */
final class ChunkedFile {
  /** Reads {@code length} bytes of the store object starting at {@code offset}. */
  interface Fetcher {
    byte[] fetch(long offset, int length) throws IOException;
  }

  static final String SIDECAR_SUFFIX = ".chunks";

  /** Longest run of missing chunks fetched with one request. */
  private static final long MAX_FETCH_BYTES = 64L << 20;

  private final Path data;
  private final Path sidecar;
  private final long size;
  private final int chunkSize;
  private final int chunkCount;
  private final Fetcher fetcher;
  private final FileChannel channel;
  private final BitSet present;
  private final Object lock = new Object();
  private volatile boolean complete;

  private ChunkedFile(
      Path data, long size, int chunkSize, Fetcher fetcher, FileChannel channel, BitSet present) {
    this.data = data;
    this.sidecar = sidecarFor(data);
    this.size = size;
    this.chunkSize = chunkSize;
    this.chunkCount = chunkCount(size, chunkSize);
    this.fetcher = fetcher;
    this.channel = channel;
    this.present = present;
    this.complete = present.cardinality() == chunkCount;
  }

  static Path sidecarFor(Path data) {
    return data.resolveSibling(data.getFileName() + SIDECAR_SUFFIX);
  }

  static boolean isSidecar(String fileName) {
    return fileName.endsWith(SIDECAR_SUFFIX);
  }

  static String dataNameOf(String sidecarName) {
    return sidecarName.substring(0, sidecarName.length() - SIDECAR_SUFFIX.length());
  }

  static int chunkCount(long size, int chunkSize) {
    return (int) ((size + chunkSize - 1) / chunkSize);
  }

  /**
   * Opens or resumes the chunked materialisation of {@code data}, a store object of {@code size}
   * bytes. A complete file, one present without a sidecar, is returned as complete and never
   * written to again.
   */
  static ChunkedFile open(Path data, long size, int chunkSize, Fetcher fetcher) throws IOException {
    if (size <= 0 || chunkSize <= 0) {
      throw new IllegalArgumentException("size and chunk size must be positive");
    }
    Path sidecar = sidecarFor(data);
    int chunkCount = chunkCount(size, chunkSize);
    BitSet present = new BitSet(chunkCount);
    if (Files.isRegularFile(data) && !Files.exists(sidecar)) {
      present.set(0, chunkCount);
      FileChannel channel = FileChannel.open(data, StandardOpenOption.READ);
      return new ChunkedFile(data, size, chunkSize, fetcher, channel, present);
    }
    boolean resume = false;
    if (Files.isRegularFile(data) && Files.isRegularFile(sidecar)) {
      PartialFile recorded = PartialFile.parseFrom(Files.readAllBytes(sidecar));
      if (recorded.getSize() == size
          && recorded.getChunkSize() == chunkSize
          && Files.size(data) == size) {
        present = BitSet.valueOf(recorded.getPresent().toByteArray());
        present.clear(chunkCount, Math.max(chunkCount, present.length()));
        resume = true;
      }
    }
    if (!resume) {
      Files.deleteIfExists(sidecar);
      Files.deleteIfExists(data);
      Files.createDirectories(data.getParent());
      try (RandomAccessFile file = new RandomAccessFile(data.toFile(), "rw")) {
        file.setLength(size);
      }
      present = new BitSet(chunkCount);
      writeSidecar(sidecar, size, chunkSize, present);
    }
    FileChannel channel =
        FileChannel.open(data, StandardOpenOption.READ, StandardOpenOption.WRITE);
    return new ChunkedFile(data, size, chunkSize, fetcher, channel, present);
  }

  Path path() {
    return data;
  }

  long size() {
    return size;
  }

  boolean isComplete() {
    return complete;
  }

  /** Whether the data file still exists; a trimmed file must not be read through further. */
  boolean exists() {
    return Files.isRegularFile(data);
  }

  int presentChunks() {
    synchronized (lock) {
      return present.cardinality();
    }
  }

  /** Makes every byte of {@code [offset, offset + length)} present, fetching what is missing. */
  void ensure(long offset, long length) throws IOException {
    ensure(offset, length, 0);
  }

  /**
   * Makes {@code [offset, offset + length)} present. When that needs a fetch, the fetch also takes
   * the missing chunks that follow, up to {@code readAhead} bytes past the range, so that a
   * sequential reader costs one request per run of chunks rather than one per chunk.
   */
  void ensure(long offset, long length, long readAhead) throws IOException {
    if (complete || length <= 0) {
      return;
    }
    long end = Math.min(size, offset + length);
    int first = (int) (offset / chunkSize);
    int last = (int) ((end - 1) / chunkSize);
    int horizon = (int) ((Math.min(size, end + Math.max(0, readAhead)) - 1) / chunkSize);
    for (int chunk = first; chunk <= last; chunk++) {
      if (has(chunk)) {
        continue;
      }
      synchronized (lock) {
        if (present.get(chunk)) {
          continue;
        }
        int runEnd = chunk;
        while (runEnd + 1 <= horizon
            && !present.get(runEnd + 1)
            && (long) (runEnd + 2 - chunk) * chunkSize <= MAX_FETCH_BYTES) {
          runEnd++;
        }
        fetchRun(chunk, runEnd);
        chunk = runEnd;
      }
    }
  }

  /** Fetches every missing chunk; afterwards the file is complete and the sidecar is gone. */
  void fetchAll() throws IOException {
    ensure(0, size, 0);
  }

  /** Positional read of present bytes; callers ensure the range first. */
  int read(ByteBuffer destination, long position) throws IOException {
    return channel.read(destination, position);
  }

  private boolean has(int chunk) {
    synchronized (lock) {
      return present.get(chunk);
    }
  }

  private void fetchRun(int firstChunk, int lastChunk) throws IOException {
    long start = (long) firstChunk * chunkSize;
    long stop = Math.min(size, (long) (lastChunk + 1) * chunkSize);
    int length = (int) (stop - start);
    byte[] bytes = fetcher.fetch(start, length);
    if (bytes.length != length) {
      throw new IOException(
          "Range read of " + data.getFileName() + " returned " + bytes.length + " bytes, wanted "
              + length);
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long position = start;
    while (buffer.hasRemaining()) {
      position += channel.write(buffer, position);
    }
    channel.force(false);
    present.set(firstChunk, lastChunk + 1);
    if (present.cardinality() == chunkCount) {
      Files.deleteIfExists(sidecar);
      complete = true;
    } else {
      writeSidecar(sidecar, size, chunkSize, present);
    }
  }

  private static void writeSidecar(Path sidecar, long size, int chunkSize, BitSet present)
      throws IOException {
    byte[] bytes =
        PartialFile.newBuilder()
            .setSize(size)
            .setChunkSize(chunkSize)
            .setPresent(ByteString.copyFrom(present.toByteArray()))
            .build()
            .toByteArray();
    Path temporary = sidecar.resolveSibling(sidecar.getFileName() + "." + UUID.randomUUID() + ".tmp");
    try {
      Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(temporary, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("Cache filesystem does not support atomic replacement: " + sidecar, exception);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
