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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.walgerrit.proto.StorageProto.GossipHint;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GossipCodecTest {
  private static final GossipHint HINT =
      GossipHint.newBuilder()
          .setRepo("platform/gossip")
          .setManifestVersion("\"3f2a-etag\"")
          .setRevision(7)
          .setHeadSeq(12)
          .setHeadTransactionId("tx-12")
          .setWriter("node-a:4242")
          .setSentAtEpochMillis(1_700_000_000_000L)
          .build();
  private static final byte[] SECRET = "cluster-secret".getBytes(StandardCharsets.UTF_8);

  @Test
  void unsignedHintsRoundTrip() {
    GossipCodec codec = GossipCodec.unsigned();
    byte[] datagram = codec.encode(HINT);

    assertArrayEquals(GossipCodec.MAGIC, Arrays.copyOf(datagram, GossipCodec.MAGIC.length));
    assertEquals(HINT, codec.decode(datagram, datagram.length).orElseThrow());
    assertTrue(datagram.length < 200, "a hint is a small fraction of one datagram");
  }

  @Test
  void signedHintsRoundTripAndRejectTampering() {
    GossipCodec codec = new GossipCodec(SECRET);
    byte[] datagram = codec.encode(HINT);
    assertEquals(HINT, codec.decode(datagram, datagram.length).orElseThrow());

    byte[] payloadTampered = datagram.clone();
    payloadTampered[GossipCodec.MAGIC.length + 2] ^= 0x01;
    assertTrue(codec.decode(payloadTampered, payloadTampered.length).isEmpty());

    byte[] macTampered = datagram.clone();
    macTampered[macTampered.length - 1] ^= 0x01;
    assertTrue(codec.decode(macTampered, macTampered.length).isEmpty());

    assertTrue(codec.decode(datagram, datagram.length - 1).isEmpty(), "truncated");
    assertTrue(
        new GossipCodec("other-secret".getBytes(StandardCharsets.UTF_8))
            .decode(datagram, datagram.length)
            .isEmpty(),
        "another cluster's secret");
  }

  @Test
  void aSignedClusterIgnoresUnsignedDatagrams() {
    byte[] unsigned = GossipCodec.unsigned().encode(HINT);
    assertTrue(new GossipCodec(SECRET).decode(unsigned, unsigned.length).isEmpty());
  }

  @Test
  void datagramsThatAreNotHintsDecodeToNothing() {
    GossipCodec codec = GossipCodec.unsigned();
    byte[] garbage = "hello, is this gerrit?".getBytes(StandardCharsets.UTF_8);
    assertTrue(codec.decode(garbage, garbage.length).isEmpty());

    byte[] wrongMagic = codec.encode(HINT);
    wrongMagic[3] = '0';
    assertTrue(codec.decode(wrongMagic, wrongMagic.length).isEmpty());

    byte[] noRepo = codec.encode(HINT.toBuilder().clearRepo().build());
    assertTrue(codec.decode(noRepo, noRepo.length).isEmpty(), "a hint names a repository");

    assertTrue(codec.decode(new byte[0], 0).isEmpty());
    assertTrue(codec.decode(GossipCodec.MAGIC, GossipCodec.MAGIC.length + 1).isEmpty());
  }

  @Test
  void hintsThatDoNotFitOneDatagramAreRefused() {
    GossipHint huge = HINT.toBuilder().setRepo("x".repeat(GossipCodec.MAX_DATAGRAM)).build();
    assertThrows(IllegalArgumentException.class, () -> GossipCodec.unsigned().encode(huge));
  }
}
