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

import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The key that signs stateless web-session cookies, shared by every node through the object store.
 *
 * <p>Gerrit's {@code auth.statelessSessions} keeps no session on the server: the cookie carries the
 * signed session record, so a node only needs the key to accept a login made on another node. An
 * explicit {@code auth.sessionSigningKey} wins. Otherwise the first node to start generates 32
 * random bytes and publishes them with a put-if-absent at {@code cluster/web-session-signing-key};
 * a node that loses that race reads the winner's key. Whoever can read the bucket can therefore
 * mint sessions, which is the same trust the repositories themselves already place in it.
 */
@Singleton
final class ObjectStoreWebSessionSigningKey implements Provider<byte[]> {
  private static final Logger logger =
      LoggerFactory.getLogger(ObjectStoreWebSessionSigningKey.class);
  static final String OBJECT = "web-session-signing-key";
  private static final int KEY_BYTES = 32;

  private final Config serverConfig;
  private final ObjectStore clusterStore;
  private volatile byte[] key;

  @Inject
  ObjectStoreWebSessionSigningKey(
      @GerritServerConfig Config serverConfig, GitRepositoryManager repositories) {
    this(serverConfig, asWalGit(repositories).storage().clusterStore());
  }

  ObjectStoreWebSessionSigningKey(Config serverConfig, ObjectStore clusterStore) {
    this.serverConfig = serverConfig;
    this.clusterStore = clusterStore;
  }

  @Override
  public byte[] get() {
    byte[] current = key;
    if (current == null) {
      synchronized (this) {
        if (key == null) {
          key = load();
        }
        current = key;
      }
    }
    return current.clone();
  }

  private byte[] load() {
    String configured = serverConfig.getString("auth", null, "sessionSigningKey");
    if (configured != null && !configured.isBlank()) {
      try {
        return Base64.getDecoder().decode(configured.trim());
      } catch (IllegalArgumentException notBase64) {
        throw new ProvisionException("auth.sessionSigningKey is not valid base64", notBase64);
      }
    }
    try {
      return loadOrCreate();
    } catch (IOException e) {
      throw new ProvisionException(
          "Cannot read or create the web-session signing key in the object store", e);
    }
  }

  byte[] loadOrCreate() throws IOException {
    Optional<ObjectStore.StoredObject> existing = clusterStore.get(OBJECT);
    if (existing.isPresent()) {
      return existing.get().bytes();
    }
    byte[] fresh = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(fresh);
    try {
      clusterStore.putIfAbsent(OBJECT, fresh);
      logger.info("WalGerrit generated the shared web-session signing key");
      return fresh;
    } catch (ObjectAlreadyExistsException raced) {
      return clusterStore
          .get(OBJECT)
          .orElseThrow(() -> new IOException("web-session signing key vanished after a race"))
          .bytes();
    }
  }

  private static WalGitRepositoryManager asWalGit(GitRepositoryManager repositories) {
    if (repositories instanceof WalGitRepositoryManager walGit) {
      return walGit;
    }
    throw new IllegalStateException(
        "WalGitWebSessionModule requires WalGitRepositoryManager, found "
            + repositories.getClass().getName());
  }
}
