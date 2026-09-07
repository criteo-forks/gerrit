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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.gerrit.entities.Account;
import com.google.gerrit.httpd.WebSessionManager.Val;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

public class WebSessionTokenCodecTest {
  private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(UTF_8);
  private final ExternalIdKeyFactory externalIds = new ExternalIdKeyFactory(() -> false);
  private final WebSessionTokenCodec codec = new WebSessionTokenCodec(KEY, externalIds);

  @Test
  public void roundTripKeepsEveryField() {
    Val val =
        new Val(
            Account.id(1000042),
            1_700_000_000_000L,
            true,
            externalIds.parse("username:jdoe"),
            1_700_003_600_000L,
            "session-1",
            "xsrf-token-1");

    Val decoded = codec.decode(codec.encode(val));

    assertThat(decoded).isNotNull();
    assertThat(decoded.getAccountId()).isEqualTo(Account.id(1000042));
    assertThat(decoded.getRefreshCookieAt()).isEqualTo(1_700_000_000_000L);
    assertThat(decoded.isPersistentCookie()).isTrue();
    assertThat(decoded.getExternalId()).isEqualTo(externalIds.parse("username:jdoe"));
    assertThat(decoded.getExpiresAt()).isEqualTo(1_700_003_600_000L);
    assertThat(decoded.getSessionId()).isEqualTo("session-1");
    assertThat(decoded.getAuth()).isEqualTo("xsrf-token-1");
  }

  @Test
  public void roundTripKeepsNulls() {
    Val val = new Val(Account.id(7), 1L, false, null, 2L, null, null);

    Val decoded = codec.decode(codec.encode(val));

    assertThat(decoded).isNotNull();
    assertThat(decoded.getExternalId()).isNull();
    assertThat(decoded.getSessionId()).isNull();
    assertThat(decoded.getAuth()).isNull();
    assertThat(decoded.isPersistentCookie()).isFalse();
  }

  @Test
  public void tokenIsCookieSafeAndSmall() {
    Val val =
        new Val(
            Account.id(1000042),
            1_700_000_000_000L,
            true,
            externalIds.parse("username:someone.with.a.long.name"),
            1_700_003_600_000L,
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");

    String token = codec.encode(val);

    assertThat(token).matches("[A-Za-z0-9_-]+");
    assertThat(token.length()).isLessThan(400);
  }

  @Test
  public void tamperedTokenIsRejected() {
    String token = codec.encode(new Val(Account.id(7), 1L, false, null, 2L, "s", "a"));
    byte[] bytes = Base64.getUrlDecoder().decode(token);
    bytes[2] ^= 0x01; // flips the account id
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    assertThat(codec.decode(tampered)).isNull();
  }

  @Test
  public void tokenSignedWithAnotherKeyIsRejected() {
    byte[] otherKey = Arrays.copyOf(KEY, KEY.length);
    otherKey[0] ^= 0x01;
    String token =
        new WebSessionTokenCodec(otherKey, externalIds)
            .encode(new Val(Account.id(7), 1L, false, null, 2L, "s", "a"));

    assertThat(codec.decode(token)).isNull();
  }

  @Test
  public void garbageIsRejected() {
    assertThat(codec.decode("")).isNull();
    assertThat(codec.decode("not base64!")).isNull();
    assertThat(codec.decode("AAAA")).isNull();
    assertThat(codec.decode(Base64.getUrlEncoder().encodeToString(new byte[40]))).isNull();
  }

  @Test
  public void shortKeyIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new WebSessionTokenCodec(new byte[8], externalIds));
  }
}
