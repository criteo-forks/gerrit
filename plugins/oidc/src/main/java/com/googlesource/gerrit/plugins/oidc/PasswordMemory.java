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
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Remembers the directory's latest answers to password checks, in memory and per server. Each user
 * has one accepted password, and a few rejected or unavailable ones since clients of one account
 * can send different stale passwords. Using a record does not extend its life.
 *
 * <p>A record is salted bcrypt over an HMAC with a random per-process key. The secret key makes the
 * HMAC output hard to guess, as the low cost of {@link HashedPassword} assumes. Its fixed length
 * also avoids bcrypt's 72-byte limit and collisions such as "ABC" and "ABCABC".
 */
@Singleton
class PasswordMemory {
  enum Answer {
    ACCEPTED,
    REJECTED,
    UNAVAILABLE
  }

  static final String ACCEPTED_CACHE = "ldap_password_checks";
  static final String REJECTED_CACHE = "ldap_password_rejections";
  static final String UNAVAILABLE_CACHE = "ldap_password_unavailable";
  static final Duration ACCEPTED_LIFETIME = Duration.ofMinutes(1);
  static final Duration REJECTED_LIFETIME = Duration.ofMinutes(1);
  static final Duration UNAVAILABLE_LIFETIME = Duration.ofSeconds(5);

  private static final int FAILED_RECORDS_PER_USER = 4;

  static CacheModule module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        cache(ACCEPTED_CACHE, String.class, HashedPassword.class)
            .expireAfterWrite(ACCEPTED_LIFETIME);
        cache(REJECTED_CACHE, String.class, HashedPassword.class)
            .expireAfterWrite(REJECTED_LIFETIME);
        cache(UNAVAILABLE_CACHE, String.class, HashedPassword.class)
            .expireAfterWrite(UNAVAILABLE_LIFETIME);
      }
    };
  }

  private final Cache<String, HashedPassword> accepted;
  private final Cache<String, HashedPassword> rejected;
  private final Cache<String, HashedPassword> unavailable;
  private final HashFunction hmac;

  @Inject
  PasswordMemory(
      @Named(ACCEPTED_CACHE) Cache<String, HashedPassword> accepted,
      @Named(REJECTED_CACHE) Cache<String, HashedPassword> rejected,
      @Named(UNAVAILABLE_CACHE) Cache<String, HashedPassword> unavailable) {
    this.accepted = accepted;
    this.rejected = rejected;
    this.unavailable = unavailable;
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    this.hmac = Hashing.hmacSha256(key);
  }

  Optional<Answer> recall(String username, String password) {
    String digest = digest(password);
    if (matches(accepted.getIfPresent(username), digest)) {
      return Optional.of(Answer.ACCEPTED);
    }
    if (holds(rejected, username, digest)) {
      return Optional.of(Answer.REJECTED);
    }
    if (holds(unavailable, username, digest)) {
      return Optional.of(Answer.UNAVAILABLE);
    }
    return Optional.empty();
  }

  void remember(String username, String password, Answer answer) {
    HashedPassword record = HashedPassword.fromPassword(digest(password));
    switch (answer) {
      case ACCEPTED -> accepted.put(username, record);
      case REJECTED -> addFailure(rejected, username, record);
      case UNAVAILABLE -> addFailure(unavailable, username, record);
    }
  }

  /** Identifies a username and password without holding the password. */
  String attemptKey(String username, String password) {
    return username + ":" + digest(password);
  }

  private static boolean matches(HashedPassword record, String digest) {
    return record != null && record.checkPassword(digest);
  }

  private static boolean holds(
      Cache<String, HashedPassword> cache, String username, String digest) {
    for (int i = 0; i < FAILED_RECORDS_PER_USER; i++) {
      if (matches(cache.getIfPresent(slot(username, i)), digest)) {
        return true;
      }
    }
    return false;
  }

  private static void addFailure(
      Cache<String, HashedPassword> cache, String username, HashedPassword record) {
    for (int i = 0; i < FAILED_RECORDS_PER_USER; i++) {
      if (cache.asMap().putIfAbsent(slot(username, i), record) == null) {
        return;
      }
    }
    cache.put(slot(username, ThreadLocalRandom.current().nextInt(FAILED_RECORDS_PER_USER)), record);
  }

  private static String slot(String username, int index) {
    return username + ":" + index;
  }

  private String digest(String password) {
    return hmac.hashString(password, UTF_8).toString();
  }
}
