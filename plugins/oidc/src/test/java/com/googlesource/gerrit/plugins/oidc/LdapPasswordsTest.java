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
import static org.junit.Assert.assertThrows;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.auth.AuthenticationUnavailableException;
import com.google.gerrit.testing.InMemoryModule;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.googlesource.gerrit.plugins.oidc.FakeDirectory.UserBinds;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The plugin's real wiring and JNDI, against a directory listening on a socket. */
public class LdapPasswordsTest {
  private FakeDirectory directory;
  private final ExecutorService requests = Executors.newCachedThreadPool();

  @Before
  public void startDirectory() throws Exception {
    directory = new FakeDirectory();
    directory.addUser("jdoe", "s3cret");
  }

  @After
  public void stop() throws Exception {
    requests.shutdownNow();
    directory.close();
  }

  @Test
  public void pluginChecksPasswordsAgainstTheDirectory() throws Exception {
    assertThat(injector(new Config()).getInstance(OidcLoginProvider.Passwords.class))
        .isInstanceOf(LdapPasswords.class);
  }

  @Test
  public void rightPasswordBindsOnce() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(new Config());

    assertThat(passwords.check("jdoe", "s3cret")).isTrue();
    assertThat(passwords.check("jdoe", "s3cret")).isTrue();

    assertThat(directory.userBindCount()).isEqualTo(1);
  }

  @Test
  public void wrongPasswordBindsOnce() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(new Config());

    assertThat(passwords.check("jdoe", "wrong")).isFalse();
    assertThat(passwords.check("jdoe", "wrong")).isFalse();

    assertThat(directory.userBindCount()).isEqualTo(1);
    assertThat(passwords.check("jdoe", "s3cret")).isTrue();
  }

  @Test
  public void unknownUserIsRejectedWithoutABind() throws Exception {
    assertThat(passwords(new Config()).check("nobody", "s3cret")).isFalse();

    assertThat(directory.userBindCount()).isEqualTo(0);
  }

  @Test
  public void concurrentRequestsBindOnce() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(new Config());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> checks = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      checks.add(
          requests.submit(
              () -> {
                start.await();
                return passwords.check("jdoe", "s3cret");
              }));
    }

    start.countDown();

    for (Future<Boolean> check : checks) {
      assertThat(check.get(30, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(directory.userBindCount()).isEqualTo(1);
  }

  @Test
  public void connectionDroppedDuringTheBindIsNotAWrongPassword() throws Exception {
    assertFailureIsNotRememberedAsAWrongPassword(UserBinds.DROP_CONNECTION);
  }

  @Test
  public void busyDirectoryIsNotAWrongPassword() throws Exception {
    assertFailureIsNotRememberedAsAWrongPassword(UserBinds.BUSY);
  }

  @Test
  public void unreachableDirectoryIsUnavailable() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(new Config());
    directory.close();

    assertThrows(AuthenticationUnavailableException.class, () -> passwords.check("jdoe", "s3cret"));
  }

  @Test
  public void differentPasswordsRejectedAtOnceAreEachRemembered() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(new Config());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> checks = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String password = "stale" + i;
      checks.add(
          requests.submit(
              () -> {
                start.await();
                return passwords.check("jdoe", password);
              }));
    }

    start.countDown();

    for (Future<Boolean> check : checks) {
      assertThat(check.get(30, TimeUnit.SECONDS)).isFalse();
    }
    assertThat(directory.userBindCount()).isEqualTo(4);
    for (int i = 0; i < 4; i++) {
      assertThat(passwords.check("jdoe", "stale" + i)).isFalse();
    }
    assertThat(directory.userBindCount()).isEqualTo(4);
  }

  @Test
  public void hungDirectoryTimesOutWithoutConfiguredTimeouts() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(new Config());
    directory.setUserBinds(UserBinds.NEVER_ANSWER);

    Future<Boolean> check = requests.submit(() -> passwords.check("jdoe", "s3cret"));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> check.get(30, TimeUnit.SECONDS));
    assertThat(e).hasCauseThat().isInstanceOf(AuthenticationUnavailableException.class);
  }

  @Test
  public void connectionPoolingCannotKeepAChangedPasswordWorking() throws Exception {
    Config cfg = oneSecond(PasswordMemory.ACCEPTED_CACHE);
    cfg.setBoolean("ldap", null, "useConnectionPooling", true);
    cfg.setBoolean("ldap", null, "supportAnonymous", false);
    OidcLoginProvider.Passwords passwords = passwords(cfg);

    assertThat(passwords.check("jdoe", "s3cret")).isTrue();
    directory.addUser("jdoe", "changed");
    Thread.sleep(1100);

    assertThat(passwords.check("jdoe", "s3cret")).isFalse();
    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  @Test
  public void memoryLimitOfZeroTurnsOffAcceptedAnswers() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(withoutMemory(PasswordMemory.ACCEPTED_CACHE));

    passwords.check("jdoe", "s3cret");
    passwords.check("jdoe", "s3cret");

    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  @Test
  public void memoryLimitOfZeroTurnsOffRejectedAnswers() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(withoutMemory(PasswordMemory.REJECTED_CACHE));

    passwords.check("jdoe", "wrong");
    passwords.check("jdoe", "wrong");

    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  @Test
  public void memoryLimitOfZeroTurnsOffUnavailableAnswers() throws Exception {
    OidcLoginProvider.Passwords passwords =
        passwords(withoutMemory(PasswordMemory.UNAVAILABLE_CACHE));
    directory.setUserBinds(UserBinds.BUSY);

    assertThrows(AuthenticationUnavailableException.class, () -> passwords.check("jdoe", "s3cret"));
    assertThrows(AuthenticationUnavailableException.class, () -> passwords.check("jdoe", "s3cret"));

    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  @Test
  public void maxAgeAppliesToAcceptedAnswers() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(oneSecond(PasswordMemory.ACCEPTED_CACHE));

    passwords.check("jdoe", "s3cret");
    Thread.sleep(1100);
    passwords.check("jdoe", "s3cret");

    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  @Test
  public void maxAgeAppliesToRejectedAnswers() throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(oneSecond(PasswordMemory.REJECTED_CACHE));

    passwords.check("jdoe", "wrong");
    Thread.sleep(1100);
    passwords.check("jdoe", "wrong");

    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  private void assertFailureIsNotRememberedAsAWrongPassword(UserBinds failure) throws Exception {
    OidcLoginProvider.Passwords passwords = passwords(oneSecond(PasswordMemory.UNAVAILABLE_CACHE));
    directory.setUserBinds(failure);

    assertThrows(AuthenticationUnavailableException.class, () -> passwords.check("jdoe", "s3cret"));
    assertThrows(AuthenticationUnavailableException.class, () -> passwords.check("jdoe", "s3cret"));
    assertThat(directory.userBindCount()).isEqualTo(1);

    directory.setUserBinds(UserBinds.ANSWER);
    Thread.sleep(1100);

    assertThat(passwords.check("jdoe", "s3cret")).isTrue();
    assertThat(directory.userBindCount()).isEqualTo(2);
  }

  private static Config withoutMemory(String cache) {
    Config cfg = new Config();
    cfg.setLong("cache", "oidc." + cache, "memoryLimit", 0);
    // Evicts before remember returns, instead of moments later in the background.
    cfg.setInt("cache", null, "threads", 0);
    return cfg;
  }

  private static Config oneSecond(String cache) {
    Config cfg = new Config();
    cfg.setString("cache", "oidc." + cache, "maxAge", "1 s");
    return cfg;
  }

  private OidcLoginProvider.Passwords passwords(Config cfg) {
    return injector(cfg).getInstance(OidcLoginProvider.Passwords.class);
  }

  private Injector injector(Config cfg) {
    cfg.setString("auth", null, "type", "OAUTH");
    cfg.setString("ldap", null, "server", directory.url());
    cfg.setString("ldap", null, "accountBase", FakeDirectory.ACCOUNT_BASE);
    cfg.setString("ldap", null, "username", "cn=svc-gerrit,dc=example");
    cfg.setString("ldap", null, "password", "service-password");
    return Guice.createInjector(
        new InMemoryModule(cfg),
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(String.class).annotatedWith(PluginName.class).toInstance("oidc");
            install(new Module("oidc", cfg));
          }
        });
  }
}
