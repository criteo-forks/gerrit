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

package com.googlesource.gerrit.plugins.oidc;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.cache.Cache;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gerrit.server.account.HashedPassword;
import com.google.gerrit.server.cache.CacheModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.security.SecureRandom;
import java.time.Duration;

/**
 * Caches accepted directory passwords for one minute per server, reducing checks for clients that
 * send a password on every request. Concurrent requests during renewal each check the directory.
 * Cache hits do not extend the lifetime.
 *
 * <p>Each user has one in-memory record: salted bcrypt over an HMAC with a random per-process key.
 * The secret key makes the HMAC output hard to guess, as the low cost of {@link HashedPassword}
 * assumes. Its fixed length also avoids bcrypt's 72-byte limit and collisions such as "ABC" and
 * "ABCABC".
 */
@Singleton
class AcceptedPasswords {
  static final String CACHE = "ldap_password_checks";

  static CacheModule module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        cache(CACHE, String.class, HashedPassword.class).expireAfterWrite(Duration.ofMinutes(1));
      }
    };
  }

  private final Cache<String, HashedPassword> records;
  private final HashFunction hmac;

  @Inject
  AcceptedPasswords(@Named(CACHE) Cache<String, HashedPassword> records) {
    this.records = records;
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    this.hmac = Hashing.hmacSha256(key);
  }

  boolean contains(String username, String password) {
    HashedPassword record = records.getIfPresent(username);
    return record != null && record.checkPassword(digest(password));
  }

  void add(String username, String password) {
    records.put(username, HashedPassword.fromPassword(digest(password)));
  }

  private String digest(String password) {
    return hmac.hashString(password, UTF_8).toString();
  }
}
