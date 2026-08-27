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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Versioned immutable-object storage; conditional writes are the only mutation primitive. */
interface ObjectStore {
  record StoredObject(byte[] bytes, String version) {}

  Optional<StoredObject> get(String key) throws IOException;

  StoredObject putIfAbsent(String key, byte[] bytes) throws IOException;

  StoredObject compareAndSwap(String key, String expectedVersion, byte[] bytes)
      throws IOException;

  void uploadIfAbsent(String key, Path source) throws IOException;

  void download(String key, Path target) throws IOException;

  List<String> list(String prefix) throws IOException;
}
