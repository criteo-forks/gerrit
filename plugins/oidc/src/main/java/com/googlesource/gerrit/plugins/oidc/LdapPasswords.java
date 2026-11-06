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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.auth.ldap.LdapPasswordAuthenticator;
import com.google.gerrit.server.auth.AuthenticationUnavailableException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.oidc.PasswordMemory.Answer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Reuses remembered answers and shares concurrent directory checks for the same credentials.
 * Different passwords are checked independently.
 *
 * <p>Waiters give up a fixed time after a check starts, limiting blocked HTTP threads.
 */
@Singleton
class LdapPasswords implements OidcLoginProvider.Passwords {
  private static final Duration MAX_WAIT = Duration.ofSeconds(2);

  private record Attempt(CompletableFuture<Answer> answer, long giveUpAtNanos) {}

  private final LdapPasswordAuthenticator ldap;
  private final PasswordMemory memory;
  private final Duration maxWait;
  private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

  @Inject
  LdapPasswords(LdapPasswordAuthenticator ldap, PasswordMemory memory) {
    this(ldap, memory, MAX_WAIT);
  }

  @VisibleForTesting
  LdapPasswords(LdapPasswordAuthenticator ldap, PasswordMemory memory, Duration maxWait) {
    this.ldap = ldap;
    this.memory = memory;
    this.maxWait = maxWait;
  }

  @Override
  public boolean check(String username, String password)
      throws AuthenticationUnavailableException {
    Answer answer = memory.recall(username, password).orElseGet(() -> attempt(username, password));
    if (answer == Answer.UNAVAILABLE) {
      throw new AuthenticationUnavailableException("Cannot query LDAP for account", null);
    }
    return answer == Answer.ACCEPTED;
  }

  private Answer attempt(String username, String password) {
    String key = memory.attemptKey(username, password);
    Attempt mine = new Attempt(new CompletableFuture<>(), System.nanoTime() + maxWait.toNanos());
    Attempt running = attempts.putIfAbsent(key, mine);
    if (running != null) {
      return await(running);
    }
    try {
      // An attempt may have finished between the first look and taking the key.
      Answer answer =
          memory.recall(username, password).orElseGet(() -> askDirectory(username, password));
      mine.answer().complete(answer);
      return answer;
    } finally {
      attempts.remove(key, mine);
      // Releases the waiters when the attempt threw.
      mine.answer().complete(Answer.UNAVAILABLE);
    }
  }

  private Answer await(Attempt attempt) {
    try {
      return attempt.answer().get(attempt.giveUpAtNanos() - System.nanoTime(), NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Answer.UNAVAILABLE;
    } catch (ExecutionException | TimeoutException e) {
      return Answer.UNAVAILABLE;
    }
  }

  private Answer askDirectory(String username, String password) {
    Answer answer;
    try {
      answer = ldap.authenticate(username, password) ? Answer.ACCEPTED : Answer.REJECTED;
    } catch (AuthenticationUnavailableException e) {
      answer = Answer.UNAVAILABLE;
    }
    memory.remember(username, password, answer);
    return answer;
  }
}
