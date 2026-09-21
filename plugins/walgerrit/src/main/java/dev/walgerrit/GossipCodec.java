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

import com.google.protobuf.InvalidProtocolBufferException;
import dev.walgerrit.proto.StorageProto.GossipHint;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encodes a four-byte magic, a serialized {@link GossipHint} and an optional trailing HMAC-SHA256.
 * Decoding rejects malformed, oversized or incorrectly signed datagrams.
 */
final class GossipCodec {
  static final byte[] MAGIC = {'W', 'G', 'G', '1'};

  /** Datagram limit, chosen to reduce fragmentation on typical networks. */
  static final int MAX_DATAGRAM = 1200;

  private static final int MAC_LENGTH = 32;
  private static final String MAC_ALGORITHM = "HmacSHA256";

  private final byte[] secret;

  /**
   * @param secret the cluster's shared secret, or null or empty for unsigned hints
   */
  GossipCodec(byte[] secret) {
    this.secret = secret == null || secret.length == 0 ? null : secret.clone();
  }

  static GossipCodec unsigned() {
    return new GossipCodec(null);
  }

  boolean signs() {
    return secret != null;
  }

  /**
   * @throws IllegalArgumentException when the hint does not fit in one datagram
   */
  byte[] encode(GossipHint hint) {
    byte[] payload = hint.toByteArray();
    int trailer = secret == null ? 0 : MAC_LENGTH;
    int length = MAGIC.length + payload.length + trailer;
    if (length > MAX_DATAGRAM) {
      throw new IllegalArgumentException(
          "Gossip hint for "
              + hint.getRepo()
              + " is "
              + length
              + " bytes; the limit is "
              + MAX_DATAGRAM);
    }
    byte[] datagram = new byte[length];
    System.arraycopy(MAGIC, 0, datagram, 0, MAGIC.length);
    System.arraycopy(payload, 0, datagram, MAGIC.length, payload.length);
    if (secret != null) {
      byte[] mac = mac(datagram, length - trailer);
      System.arraycopy(mac, 0, datagram, length - trailer, MAC_LENGTH);
    }
    return datagram;
  }

  /** The hint in the first {@code length} bytes of {@code datagram}, or empty if it is not one. */
  Optional<GossipHint> decode(byte[] datagram, int length) {
    int trailer = secret == null ? 0 : MAC_LENGTH;
    if (length < MAGIC.length + trailer || length > MAX_DATAGRAM || length > datagram.length) {
      return Optional.empty();
    }
    for (int i = 0; i < MAGIC.length; i++) {
      if (datagram[i] != MAGIC[i]) {
        return Optional.empty();
      }
    }
    int payloadEnd = length - trailer;
    if (secret != null
        && !MessageDigest.isEqual(
            mac(datagram, payloadEnd), Arrays.copyOfRange(datagram, payloadEnd, length))) {
      return Optional.empty();
    }
    try {
      GossipHint hint =
          GossipHint.parser().parseFrom(datagram, MAGIC.length, payloadEnd - MAGIC.length);
      return hint.getRepo().isEmpty() ? Optional.empty() : Optional.of(hint);
    } catch (InvalidProtocolBufferException malformed) {
      return Optional.empty();
    }
  }

  private byte[] mac(byte[] data, int length) {
    try {
      Mac mac = Mac.getInstance(MAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
      mac.update(data, 0, length);
      return mac.doFinal();
    } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
      throw new IllegalStateException(MAC_ALGORITHM + " is unavailable", unavailable);
    }
  }
}
