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

package com.google.gerrit.httpd;

import static com.google.gerrit.server.ioutil.BasicSerialization.readFixInt64;
import static com.google.gerrit.server.ioutil.BasicSerialization.readString;
import static com.google.gerrit.server.ioutil.BasicSerialization.readVarInt32;
import static com.google.gerrit.server.ioutil.BasicSerialization.writeFixInt64;
import static com.google.gerrit.server.ioutil.BasicSerialization.writeString;
import static com.google.gerrit.server.ioutil.BasicSerialization.writeVarInt32;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.httpd.WebSessionManager.Val;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a web session into a self-contained cookie value and back.
 *
 * <p>The value is the serialized session followed by an HMAC-SHA256 over it, base64url encoded.
 * Nothing is stored on the server: any node holding the key accepts the cookie, and a node that
 * restarts loses no session. The session record is small (account id, expiry, refresh time,
 * remember-me flag, the external id used to sign in, session id and XSRF token), about 250 bytes
 * encoded.
 */
final class WebSessionTokenCodec {
  private static final int VERSION = 1;
  private static final String MAC_ALGORITHM = "HmacSHA256";
  private static final int MAC_LENGTH = 32;

  private final byte[] key;
  private final ExternalIdKeyFactory externalIdKeyFactory;

  WebSessionTokenCodec(byte[] key, ExternalIdKeyFactory externalIdKeyFactory) {
    if (key == null || key.length < 16) {
      throw new IllegalArgumentException("web session signing key must be at least 16 bytes");
    }
    this.key = key.clone();
    this.externalIdKeyFactory = externalIdKeyFactory;
  }

  String encode(Val val) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
    try {
      writeVarInt32(buf, VERSION);
      writeVarInt32(buf, val.getAccountId().get());
      writeFixInt64(buf, val.getRefreshCookieAt());
      writeVarInt32(buf, val.isPersistentCookie() ? 1 : 0);
      writeFixInt64(buf, val.getExpiresAt());
      writeString(buf, val.getExternalId() == null ? null : val.getExternalId().toString());
      writeString(buf, val.getSessionId());
      writeString(buf, val.getAuth());
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
    byte[] payload = buf.toByteArray();
    byte[] mac = mac(payload);
    byte[] token = Arrays.copyOf(payload, payload.length + mac.length);
    System.arraycopy(mac, 0, token, payload.length, mac.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  /** Returns the session the token carries, or null when it was not issued with this key. */
  @Nullable
  Val decode(String token) {
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException notBase64) {
      return null;
    }
    if (bytes.length <= MAC_LENGTH) {
      return null;
    }
    byte[] payload = Arrays.copyOf(bytes, bytes.length - MAC_LENGTH);
    byte[] mac = Arrays.copyOfRange(bytes, bytes.length - MAC_LENGTH, bytes.length);
    if (!MessageDigest.isEqual(mac(payload), mac)) {
      return null;
    }
    try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
      if (readVarInt32(in) != VERSION) {
        return null;
      }
      Account.Id accountId = Account.id(readVarInt32(in));
      long refreshCookieAt = readFixInt64(in);
      boolean persistentCookie = readVarInt32(in) != 0;
      long expiresAt = readFixInt64(in);
      String externalId = readString(in);
      String sessionId = readString(in);
      String auth = readString(in);
      return new Val(
          accountId,
          refreshCookieAt,
          persistentCookie,
          externalId == null ? null : externalIdKeyFactory.parse(externalId),
          expiresAt,
          sessionId,
          auth);
    } catch (IOException | RuntimeException malformed) {
      return null;
    }
  }

  private byte[] mac(byte[] payload) {
    try {
      Mac mac = Mac.getInstance(MAC_ALGORITHM);
      mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(MAC_ALGORITHM + " unavailable", e);
    }
  }
}
