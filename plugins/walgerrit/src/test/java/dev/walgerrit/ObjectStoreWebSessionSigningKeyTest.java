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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Base64;
import org.eclipse.jgit.lib.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStoreWebSessionSigningKeyTest {
  @TempDir Path root;

  @Test
  void firstNodeGeneratesTheKeyAndEveryOtherNodeReadsIt() throws Exception {
    StorageLayout layout = new StorageLayout(root);
    ObjectStoreWebSessionSigningKey first =
        new ObjectStoreWebSessionSigningKey(new Config(), layout.clusterStore());
    ObjectStoreWebSessionSigningKey second =
        new ObjectStoreWebSessionSigningKey(new Config(), layout.clusterStore());

    byte[] key = first.get();

    assertEquals(32, key.length);
    assertArrayEquals(key, second.get());
    assertArrayEquals(key, first.get());
    assertTrue(
        layout.clusterStore().get(ObjectStoreWebSessionSigningKey.OBJECT).isPresent(),
        "the key is published in the cluster prefix");
    assertFalse(
        new FileObjectStore(root).get(ObjectStoreWebSessionSigningKey.OBJECT).isPresent(),
        "the key is not written at the store root");
  }

  @Test
  void configuredKeyWinsOverTheStore() throws Exception {
    StorageLayout layout = new StorageLayout(root);
    Config config = new Config();
    byte[] configured = "0123456789abcdef0123456789abcdef".getBytes();
    config.setString(
        "auth", null, "sessionSigningKey", Base64.getEncoder().encodeToString(configured));

    byte[] key = new ObjectStoreWebSessionSigningKey(config, layout.clusterStore()).get();

    assertArrayEquals(configured, key);
    assertFalse(layout.clusterStore().get(ObjectStoreWebSessionSigningKey.OBJECT).isPresent());
  }

  @Test
  void callersCannotMutateTheCachedKey() throws Exception {
    ObjectStoreWebSessionSigningKey provider =
        new ObjectStoreWebSessionSigningKey(new Config(), new StorageLayout(root).clusterStore());
    byte[] key = provider.get();
    key[0] ^= 0x7f;
    assertFalse(java.util.Arrays.equals(key, provider.get()));
  }
}
