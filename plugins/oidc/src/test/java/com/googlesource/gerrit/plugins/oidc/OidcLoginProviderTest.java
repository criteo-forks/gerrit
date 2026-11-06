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

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.oidc.FakeProvider.ISSUER;
import static com.googlesource.gerrit.plugins.oidc.FakeProvider.OTHER_ISSUER;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.auth.ldap.LdapPasswordAuthenticator;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.AuthTokenVerifier;
import com.google.gerrit.server.account.HashedPassword;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.auth.AuthenticationUnavailableException;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.query.account.InternalAccountQuery;
import com.googlesource.gerrit.plugins.oidc.PasswordMemory.Answer;
import java.io.IOException;
import java.lang.Thread.State;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

public class OidcLoginProviderTest {
  private static final Account.Id ACCOUNT = Account.id(1000);

  private FakeProvider idp;
  private AccountCache accountCache;
  private InternalAccountQuery query;
  private AuthTokenVerifier authTokens;
  private AuthConfig authConfig;
  private OidcLoginProvider.Passwords passwords;
  private LdapPasswordAuthenticator directory;
  private Cache<String, HashedPassword> accepted;
  private Cache<String, HashedPassword> rejected;
  private Cache<String, HashedPassword> unavailable;
  private PasswordMemory memory;
  private final List<Thread> requestThreads = new CopyOnWriteArrayList<>();
  private final ExecutorService requests =
      Executors.newCachedThreadPool(
          work -> {
            Thread thread = new Thread(work);
            requestThreads.add(thread);
            return thread;
          });
  private final AtomicLong nanos = new AtomicLong();

  @Before
  public void setUp() throws Exception {
    idp = new FakeProvider();
    accountCache = mock(AccountCache.class);
    query = mock(InternalAccountQuery.class);
    when(query.query(ArgumentMatchers.<Predicate<AccountState>>any()))
        .thenReturn(ImmutableList.of());
    authTokens = mock(AuthTokenVerifier.class);
    authConfig = mock(AuthConfig.class);
    passwords = mock(OidcLoginProvider.Passwords.class);
    when(accountCache.getByUsername(anyString())).thenReturn(Optional.empty());
  }

  @After
  public void stopRequests() {
    requests.shutdownNow();
  }

  @Test
  public void basicAuthWithTokenOfThatUser() throws Exception {
    // First signed in through LDAP as "JDoe".
    account("jdoe", true, gerrit("JDoe"));

    OAuthUserInfo info = provider(true).login("jdoe", token(ISSUER, "jdoe", "gerrit"));

    assertThat(info.getExternalId()).isEqualTo("gerrit:JDoe");
    assertThat(info.getEmailAddress()).isNull();
    assertThat(info.getDisplayName()).isNull();
    verify(passwords, never()).check(anyString(), anyString());
  }

  @Test
  public void basicAuthWithTokenOfAnotherUserIsRejected() throws Exception {
    account("jdoe", true, gerrit("jdoe"));

    IOException e =
        assertThrows(
            IOException.class,
            () -> provider(true).login("jdoe", token(ISSUER, "admin", "gerrit")));
    assertThat(e).hasMessageThat().contains("Token of admin sent as jdoe");
  }

  @Test
  public void bearerTokenFromAnAcceptedIssuer() throws Exception {
    account("jdoe", true, gerrit("jdoe"));

    OAuthUserInfo info = provider(true).login(null, token(OTHER_ISSUER, "jdoe", "gerrit"));

    assertThat(info.getExternalId()).isEqualTo("gerrit:jdoe");
    assertThat(info.getUserName()).isEqualTo("jdoe");
  }

  @Test
  public void bearerTokenFindsAccountWhoseUsernameHasAnotherCase() throws Exception {
    AccountState a = state("JDoe", true, gerrit("JDoe"));
    when(query.query(ArgumentMatchers.<Predicate<AccountState>>any()))
        .thenReturn(ImmutableList.of(a));

    OAuthUserInfo info = provider(true).login(null, token(ISSUER, "jdoe", "gerrit"));

    assertThat(info.getExternalId()).isEqualTo("gerrit:JDoe");
    assertThat(info.getUserName()).isEqualTo("JDoe");
  }

  @Test
  public void bearerTokenOfServiceAccountCreatedThroughRest() throws Exception {
    account("svc-bot", true, ExternalId.create(key("username", "svc-bot"), ACCOUNT, null, null));

    OAuthUserInfo info = provider(true).login(null, token(ISSUER, "svc-bot", "gerrit"));

    assertThat(info.getExternalId()).isEqualTo("username:svc-bot");
  }

  @Test
  public void bearerTokenOfUnknownOrInactiveUserIsRejected() throws Exception {
    assertThrows(
        IOException.class, () -> provider(true).login(null, token(ISSUER, "nobody", "gerrit")));

    account("gone", false, gerrit("gone"));
    assertThrows(
        IOException.class, () -> provider(true).login(null, token(ISSUER, "gone", "gerrit")));
  }

  @Test
  public void tokenForAnotherAudienceIsRejected() throws Exception {
    account("jdoe", true, gerrit("jdoe"));

    IOException e =
        assertThrows(
            IOException.class, () -> provider(true).login(null, token(ISSUER, "jdoe", "other")));
    assertThat(e).hasMessageThat().contains("not meant for gerrit");
  }

  @Test
  public void bearerCredentialThatIsNotATokenIsRejected() throws Exception {
    assertThrows(IOException.class, () -> provider(true).login(null, "hunter2"));
    verify(passwords, never()).check(any(), any());
  }

  @Test
  public void gerritAuthTokenWhenAccepted() throws Exception {
    account("jdoe", true, gerrit("jdoe"));
    when(authTokens.checkToken(ACCOUNT, "gerrit-token")).thenReturn(true);

    assertThat(provider(true).login("jdoe", "gerrit-token").getExternalId())
        .isEqualTo("gerrit:jdoe");
    verify(passwords, never()).check(any(), any());
  }

  @Test
  public void gerritAuthTokenIgnoredWhenNotAccepted() throws Exception {
    account("jdoe", true, gerrit("jdoe"));
    when(authTokens.checkToken(ACCOUNT, "gerrit-token")).thenReturn(true);

    assertThrows(IOException.class, () -> provider(false).login("jdoe", "gerrit-token"));
    verify(authTokens, never()).checkToken(any(), any());
  }

  @Test
  public void ldapPassword() throws Exception {
    account("jdoe", true, gerrit("jdoe"));
    when(passwords.check("jdoe", "s3cret")).thenReturn(true);

    assertThat(provider(true).login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
  }

  @Test
  public void wrongPasswordIsRejected() throws Exception {
    account("jdoe", true, gerrit("jdoe"));

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "wrong"));
  }

  @Test
  public void unreachableDirectoryIsAnError() throws Exception {
    account("jdoe", true, gerrit("jdoe"));
    doThrow(new AuthenticationUnavailableException("down", new IOException()))
        .when(passwords)
        .check("jdoe", "s3cret");

    IOException e = assertThrows(IOException.class, () -> provider(true).login("jdoe", "s3cret"));
    assertThat(e).hasCauseThat().isInstanceOf(AuthenticationUnavailableException.class);
  }

  @Test
  public void acceptedPasswordIsNotCheckedAgainWithinAMinute() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true);

    for (int i = 0; i < 5; i++) {
      assertThat(provider(true).login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
    }

    verify(directory, times(1)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void usingARememberedPasswordDoesNotExtendItsLife() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true, false);

    provider(true).login("jdoe", "s3cret");
    advance(Duration.ofSeconds(40));
    provider(true).login("jdoe", "s3cret");
    advance(Duration.ofSeconds(40));

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "s3cret"));
    verify(directory, times(2)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void wrongPasswordIsNotCheckedAgainWithinAMinute() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true);

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "wrong"));
    provider(true).login("jdoe", "s3cret");
    assertThrows(IOException.class, () -> provider(true).login("jdoe", "wrong"));
    assertThrows(IOException.class, () -> provider(true).login("jdoe", "wrong"));
    provider(true).login("jdoe", "s3cret");

    verify(directory, times(1)).authenticate("jdoe", "wrong");
    verify(directory, times(1)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void passwordThatBecomesValidWorksOnceItsRejectionExpires() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "new")).thenReturn(false, true);

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "new"));
    advance(Duration.ofSeconds(59));
    assertThrows(IOException.class, () -> provider(true).login("jdoe", "new"));
    advance(Duration.ofSeconds(2));

    assertThat(provider(true).login("jdoe", "new").getExternalId()).isEqualTo("gerrit:jdoe");
  }

  @Test
  public void eachDifferentWrongPasswordReachesTheDirectory() throws Exception {
    useTheDirectory();

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "guess1"));
    assertThrows(IOException.class, () -> provider(true).login("jdoe", "guess2"));

    verify(directory).authenticate("jdoe", "guess1");
    verify(directory).authenticate("jdoe", "guess2");
  }

  @Test
  public void clientsWithDifferentStalePasswordsAreEachRemembered() throws Exception {
    useTheDirectory();

    for (int i = 0; i < 5; i++) {
      assertThrows(IOException.class, () -> provider(true).login("jdoe", "stale1"));
      assertThrows(IOException.class, () -> provider(true).login("jdoe", "stale2"));
    }

    verify(directory, times(1)).authenticate("jdoe", "stale1");
    verify(directory, times(1)).authenticate("jdoe", "stale2");
  }

  @Test
  public void clientsWithDifferentPasswordsDuringAnOutageAreEachRemembered() throws Exception {
    useTheDirectory();
    when(directory.authenticate(anyString(), anyString()))
        .thenThrow(new AuthenticationUnavailableException("down", new IOException()));

    for (int i = 0; i < 5; i++) {
      assertThrows(IOException.class, () -> provider(true).login("jdoe", "one"));
      assertThrows(IOException.class, () -> provider(true).login("jdoe", "two"));
    }

    verify(directory, times(1)).authenticate("jdoe", "one");
    verify(directory, times(1)).authenticate("jdoe", "two");
  }

  @Test
  public void moreWrongPasswordsThanTheMemoryHoldsAreStillRejected() throws Exception {
    useTheDirectory();

    for (int i = 0; i < 10; i++) {
      String password = "guess" + i;
      assertThrows(IOException.class, () -> provider(true).login("jdoe", password));
    }

    verify(directory, times(10)).authenticate(eq("jdoe"), anyString());
  }

  @Test
  public void anotherUserIsNotRejectedByARememberedRejection() throws Exception {
    useTheDirectory();
    account("other", true, gerrit("other"));
    when(directory.authenticate("other", "same")).thenReturn(true);

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "same"));

    assertThat(provider(true).login("other", "same").getExternalId()).isEqualTo("gerrit:other");
  }

  @Test
  public void directoryIsNotAskedAgainForFiveSecondsAfterFailing() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "s3cret"))
        .thenThrow(new AuthenticationUnavailableException("down", new IOException()))
        .thenReturn(true);

    for (int i = 0; i < 3; i++) {
      IOException e = assertThrows(IOException.class, () -> provider(true).login("jdoe", "s3cret"));
      assertThat(e).hasCauseThat().isInstanceOf(AuthenticationUnavailableException.class);
    }
    verify(directory, times(1)).authenticate("jdoe", "s3cret");
    advance(Duration.ofSeconds(6));

    assertThat(provider(true).login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
    verify(directory, times(2)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void directoryFailureForOnePasswordDoesNotStopAnother() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "wrong"))
        .thenThrow(new AuthenticationUnavailableException("down", new IOException()));
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true);

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "wrong"));

    assertThat(provider(true).login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
  }

  @Test
  public void waitingRequestsShareAnAcceptance() throws Exception {
    useTheDirectory();
    Gate gate = holdDirectory("jdoe", "s3cret", () -> true);
    OidcLoginProvider provider = provider(true);

    List<Future<OAuthUserInfo>> logins = loginsDuring(gate, provider, "jdoe", "s3cret");

    for (Future<OAuthUserInfo> login : logins) {
      assertThat(login.get(10, TimeUnit.SECONDS).getExternalId()).isEqualTo("gerrit:jdoe");
    }
    verify(directory, times(1)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void waitingRequestsShareARejection() throws Exception {
    useTheDirectory();
    Gate gate = holdDirectory("jdoe", "wrong", () -> false);
    OidcLoginProvider provider = provider(true);

    List<Future<OAuthUserInfo>> logins = loginsDuring(gate, provider, "jdoe", "wrong");

    for (Future<OAuthUserInfo> login : logins) {
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> login.get(10, TimeUnit.SECONDS));
      assertThat(e).hasCauseThat().hasMessageThat().contains("Invalid credentials");
    }
    verify(directory, times(1)).authenticate("jdoe", "wrong");
  }

  @Test
  public void waitingRequestsShareAFailure() throws Exception {
    useTheDirectory();
    Gate gate =
        holdDirectory(
            "jdoe",
            "s3cret",
            () -> {
              throw new AuthenticationUnavailableException("down", new IOException());
            });
    OidcLoginProvider provider = provider(true);

    List<Future<OAuthUserInfo>> logins = loginsDuring(gate, provider, "jdoe", "s3cret");

    for (Future<OAuthUserInfo> login : logins) {
      ExecutionException e =
          assertThrows(ExecutionException.class, () -> login.get(10, TimeUnit.SECONDS));
      assertThat(e)
          .hasCauseThat()
          .hasCauseThat()
          .isInstanceOf(AuthenticationUnavailableException.class);
    }
    verify(directory, times(1)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void wrongPasswordInProgressDoesNotHoldUpTheRightOne() throws Exception {
    useTheDirectory();
    Gate gate = holdDirectory("jdoe", "wrong", () -> false);
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true);
    OidcLoginProvider provider = provider(true);

    Future<OAuthUserInfo> wrong = requests.submit(() -> provider.login("jdoe", "wrong"));
    gate.asked.await();

    assertThat(provider.login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
    assertThat(wrong.isDone()).isFalse();
    gate.answer.countDown();
    assertThrows(ExecutionException.class, () -> wrong.get(10, TimeUnit.SECONDS));
  }

  @Test
  public void waitIsCappedWhenTheDirectoryHangs() throws Exception {
    useTheDirectory();
    passwords = new LdapPasswords(directory, memory, Duration.ofMillis(50));
    Gate gate = holdDirectory("jdoe", "s3cret", () -> true);
    OidcLoginProvider provider = provider(true);

    Future<OAuthUserInfo> first = requests.submit(() -> provider.login("jdoe", "s3cret"));
    gate.asked.await();

    IOException e = assertThrows(IOException.class, () -> provider.login("jdoe", "s3cret"));
    assertThat(e).hasCauseThat().isInstanceOf(AuthenticationUnavailableException.class);

    gate.answer.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS).getExternalId()).isEqualTo("gerrit:jdoe");
    assertThat(provider.login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
    verify(directory, times(1)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void requestsArrivingAfterTheWaitEndsFailAtOnce() throws Exception {
    useTheDirectory();
    passwords = new LdapPasswords(directory, memory, Duration.ofSeconds(1));
    Gate gate = holdDirectory("jdoe", "s3cret", () -> true);
    OidcLoginProvider provider = provider(true);

    Future<OAuthUserInfo> first = requests.submit(() -> provider.login("jdoe", "s3cret"));
    gate.asked.await();
    Thread.sleep(1100);

    long before = System.nanoTime();
    IOException e = assertThrows(IOException.class, () -> provider.login("jdoe", "s3cret"));
    assertThat(e).hasCauseThat().isInstanceOf(AuthenticationUnavailableException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - before).toMillis()).isLessThan(900L);

    gate.answer.countDown();
    first.get(10, TimeUnit.SECONDS);
  }

  @Test
  public void attemptThatThrowsReleasesWaitersAndIsNotRemembered() throws Exception {
    useTheDirectory();
    passwords = new LdapPasswords(directory, memory, Duration.ofMinutes(5));
    Gate gate = new Gate();
    AtomicInteger calls = new AtomicInteger();
    when(directory.authenticate("jdoe", "s3cret"))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() > 1) {
                return true;
              }
              gate.asked.countDown();
              assertThat(gate.answer.await(20, TimeUnit.SECONDS)).isTrue();
              throw new IllegalStateException("bug");
            });
    OidcLoginProvider provider = provider(true);

    Future<OAuthUserInfo> first = requests.submit(() -> provider.login("jdoe", "s3cret"));
    gate.asked.await();
    Future<OAuthUserInfo> waiting = requests.submit(() -> provider.login("jdoe", "s3cret"));
    waitForParkedRequests(2);
    gate.answer.countDown();

    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
    ExecutionException released =
        assertThrows(ExecutionException.class, () -> waiting.get(10, TimeUnit.SECONDS));
    assertThat(released)
        .hasCauseThat()
        .hasCauseThat()
        .isInstanceOf(AuthenticationUnavailableException.class);
    assertThat(provider.login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");
    verify(directory, times(2)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void answerThatArrivesJustAfterTheFirstLookIsUsed() throws Exception {
    useTheDirectory();
    memory.remember("jdoe", "s3cret", Answer.ACCEPTED);
    PasswordMemory lateAnswer = spy(memory);
    doReturn(Optional.empty()).doCallRealMethod().when(lateAnswer).recall("jdoe", "s3cret");
    passwords = new LdapPasswords(directory, lateAnswer);

    assertThat(provider(true).login("jdoe", "s3cret").getExternalId()).isEqualTo("gerrit:jdoe");

    verify(directory, never()).authenticate("jdoe", "s3cret");
  }

  @Test
  public void manyRequestsAtOnceAskTheDirectoryOnce() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true);
    OidcLoginProvider provider = provider(true);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> workers = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      workers.add(
          requests.submit(
              () -> {
                start.await();
                for (int j = 0; j < 20; j++) {
                  provider.login("jdoe", "s3cret");
                }
                return null;
              }));
    }

    start.countDown();

    for (Future<?> worker : workers) {
      worker.get(60, TimeUnit.SECONDS);
    }
    verify(directory, times(1)).authenticate("jdoe", "s3cret");
  }

  @Test
  public void newPasswordReplacesTheOldOne() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "old")).thenReturn(true, false);
    when(directory.authenticate("jdoe", "new")).thenReturn(true);

    provider(true).login("jdoe", "old");
    provider(true).login("jdoe", "new");

    assertThrows(IOException.class, () -> provider(true).login("jdoe", "old"));
  }

  @Test
  public void anotherUserCannotUseARememberedPassword() throws Exception {
    useTheDirectory();
    account("other", true, gerrit("other"));
    when(directory.authenticate("jdoe", "s3cret")).thenReturn(true);

    provider(true).login("jdoe", "s3cret");

    assertThrows(IOException.class, () -> provider(true).login("other", "s3cret"));
    verify(directory).authenticate("other", "s3cret");
  }

  @Test
  public void rememberedPasswordWorksThroughADirectoryOutageUntilItExpires() throws Exception {
    useTheDirectory();
    when(directory.authenticate("jdoe", "s3cret"))
        .thenReturn(true)
        .thenThrow(new AuthenticationUnavailableException("down", new IOException()));

    provider(true).login("jdoe", "s3cret");
    advance(Duration.ofSeconds(59));
    provider(true).login("jdoe", "s3cret");
    advance(Duration.ofSeconds(2));

    IOException e = assertThrows(IOException.class, () -> provider(true).login("jdoe", "s3cret"));
    assertThat(e).hasCauseThat().isInstanceOf(AuthenticationUnavailableException.class);
  }

  @Test
  public void passwordsBcryptWouldConfuseAreToldApart() throws Exception {
    useTheDirectory();
    String longPassword = "x".repeat(100);
    when(directory.authenticate("jdoe", longPassword + "a")).thenReturn(true);
    when(directory.authenticate("jdoe", "ABC")).thenReturn(true);

    provider(true).login("jdoe", longPassword + "a");
    assertThrows(IOException.class, () -> provider(true).login("jdoe", longPassword + "b"));
    provider(true).login("jdoe", "ABC");
    assertThrows(IOException.class, () -> provider(true).login("jdoe", "ABCABC"));
  }

  @Test
  public void recordsAreSaltedAndUselessToAnotherProcess() throws Exception {
    useTheDirectory();

    memory.remember("jdoe", "s3cret", Answer.ACCEPTED);
    String first = accepted.getIfPresent("jdoe").encode();
    memory.remember("jdoe", "s3cret", Answer.ACCEPTED);
    String second = accepted.getIfPresent("jdoe").encode();

    assertThat(first).startsWith("bcrypt0:");
    assertThat(first).isNotEqualTo(second);
    assertThat(memory.recall("jdoe", "s3cret")).isEqualTo(Optional.of(Answer.ACCEPTED));
    assertThat(new PasswordMemory(accepted, rejected, unavailable).recall("jdoe", "s3cret"))
        .isEqualTo(Optional.empty());
  }

  private void useTheDirectory() {
    account("jdoe", true, gerrit("jdoe"));
    directory = mock(LdapPasswordAuthenticator.class);
    accepted = cache(PasswordMemory.ACCEPTED_LIFETIME);
    rejected = cache(PasswordMemory.REJECTED_LIFETIME);
    unavailable = cache(PasswordMemory.UNAVAILABLE_LIFETIME);
    memory = new PasswordMemory(accepted, rejected, unavailable);
    // Long enough that only tests about the wait ever reach it.
    passwords = new LdapPasswords(directory, memory, Duration.ofMinutes(1));
  }

  private Cache<String, HashedPassword> cache(Duration lifetime) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(lifetime)
        .ticker(
            new Ticker() {
              @Override
              public long read() {
                return nanos.get();
              }
            })
        .build();
  }

  private static class Gate {
    final CountDownLatch asked = new CountDownLatch(1);
    final CountDownLatch answer = new CountDownLatch(1);
  }

  private interface DirectoryAnswer {
    boolean get() throws Exception;
  }

  private Gate holdDirectory(String username, String password, DirectoryAnswer directoryAnswer)
      throws Exception {
    Gate gate = new Gate();
    when(directory.authenticate(username, password))
        .thenAnswer(
            invocation -> {
              gate.asked.countDown();
              assertThat(gate.answer.await(20, TimeUnit.SECONDS)).isTrue();
              return directoryAnswer.get();
            });
    return gate;
  }

  /** Waits for the check holding the directory and the requests waiting for it to be asleep. */
  private void waitForParkedRequests(int count) throws Exception {
    long giveUp = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (requestThreads.stream().filter(t -> t.getState() == State.TIMED_WAITING).count()
        < count) {
      assertThat(System.nanoTime() - giveUp).isLessThan(0L);
      Thread.sleep(5);
    }
  }

  private List<Future<OAuthUserInfo>> loginsDuring(
      Gate gate, OidcLoginProvider provider, String username, String password) throws Exception {
    List<Future<OAuthUserInfo>> logins = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      logins.add(requests.submit(() -> provider.login(username, password)));
    }
    gate.asked.await();
    waitForParkedRequests(logins.size());
    gate.answer.countDown();
    return logins;
  }

  private void advance(Duration duration) {
    nanos.addAndGet(duration.toNanos());
  }

  private OidcLoginProvider provider(boolean acceptsAuthTokens) {
    doReturn(acceptsAuthTokens).when(authConfig).acceptsAuthTokens();
    OidcConfig cfg = config();
    TokenVerifier verifier = new TokenVerifier(new Providers(idp), cfg);
    return new OidcLoginProvider(
        cfg,
        verifier,
        new Identities(accountCache, () -> query),
        authTokens,
        authConfig,
        passwords);
  }

  static OidcConfig config() {
    Config c = new Config();
    c.setString("plugin", "oidc", "issuer", ISSUER);
    c.setString("plugin", "oidc", "acceptedIssuer", OTHER_ISSUER);
    c.setString("plugin", "oidc", "clientId", "gerrit");
    c.setString("plugin", "oidc", "clientSecret", "client-secret");
    return new OidcConfig("oidc", c);
  }

  private String token(String issuer, String username, String aud) throws Exception {
    return FakeProvider.sign(
        idp.key(issuer),
        "{\"iss\": \""
            + issuer
            + "\", \"aud\": \""
            + aud
            + "\", \"exp\": "
            + (Instant.now().getEpochSecond() + 300)
            + ", \"preferred_username\": \""
            + username
            + "\"}");
  }

  private void account(String username, boolean active, ExternalId... ids) {
    when(accountCache.getByUsername(username))
        .thenReturn(Optional.of(state(username, active, ids)));
  }

  static AccountState state(String username, boolean active, ExternalId... ids) {
    ImmutableList<ExternalId> all =
        ImmutableList.<ExternalId>builder()
            .add(ids)
            .add(ExternalId.create(key("username", username), ACCOUNT, null, null))
            .build()
            .stream()
            .distinct()
            .collect(ImmutableList.toImmutableList());
    return AccountState.forAccount(
        Account.builder(ACCOUNT, Instant.EPOCH).setInactive(!active).build(), all);
  }

  static ExternalId gerrit(String id) {
    return ExternalId.create(key("gerrit", id), ACCOUNT, id.toLowerCase() + "@example.com", null);
  }

  static ExternalId.Key key(String scheme, String id) {
    return ExternalId.Key.create(scheme, id, false);
  }
}
