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
package com.google.gerrit.server;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Frames stdout, stderr and the final exit status of one duplex receive-pack. */
public final class PeerGitStream {
  public static final int MAX_FRAME = 16 * 1024;
  private final DataOutputStream response;

  public PeerGitStream(OutputStream response) {
    this.response = new DataOutputStream(response);
  }

  public OutputStream stream(boolean stderr) {
    return new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        write(new byte[] {(byte) value});
      }

      @Override
      public void write(byte[] bytes, int offset, int size) throws IOException {
        synchronized (response) {
          while (size > 0) {
            int n = Math.min(MAX_FRAME, size);
            response.writeInt(stderr ? -n : n);
            response.write(bytes, offset, n);
            offset += n;
            size -= n;
          }
        }
      }

      @Override
      public void flush() throws IOException {
        synchronized (response) {
          response.flush();
        }
      }
    };
  }

  public void finish(int status) throws IOException {
    synchronized (response) {
      response.writeInt(0);
      response.writeByte(status);
      response.flush();
    }
  }
}
