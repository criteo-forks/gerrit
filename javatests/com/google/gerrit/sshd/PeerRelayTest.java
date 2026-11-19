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
package com.google.gerrit.sshd;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.server.PeerGitStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import org.junit.Test;

public class PeerRelayTest {
  @Test
  public void stdoutStderrAndExitSurviveFraming() throws Exception {
    var wire = new ByteArrayOutputStream();
    var stream = new PeerGitStream(wire);
    stream.stream(false).write(new byte[PeerGitStream.MAX_FRAME + 1]);
    stream.stream(true).write(42);
    stream.finish(128);
    var in = new DataInputStream(new ByteArrayInputStream(wire.toByteArray()));
    assertThat(in.readInt()).isEqualTo(PeerGitStream.MAX_FRAME);
    in.skipNBytes(PeerGitStream.MAX_FRAME);
    assertThat(in.readInt()).isEqualTo(1);
    assertThat(in.readUnsignedByte()).isEqualTo(0);
    assertThat(in.readInt()).isEqualTo(-1);
    assertThat(in.readUnsignedByte()).isEqualTo(42);
    assertThat(in.readInt()).isEqualTo(0);
    assertThat(in.readUnsignedByte()).isEqualTo(128);
    assertThat(in.read()).isEqualTo(-1);
  }
}
