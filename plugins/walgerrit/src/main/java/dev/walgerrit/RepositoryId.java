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

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The key everything of one repository is stored under: manifest, packs, log, lease, cache and
 * cursor. Assigned once, at creation, and never derived from the project's name, so a rename or
 * deletion never moves data. The catalog repository has the fixed id {@link #CATALOG}, which no
 * random id can spell.
 */
record RepositoryId(String value) implements Comparable<RepositoryId> {
  private static final Pattern VALID = Pattern.compile("[0-9a-z]{1,64}");
  private static final SecureRandom RANDOM = new SecureRandom();

  static final RepositoryId CATALOG = new RepositoryId("catalog");

  RepositoryId {
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid repository id: " + value);
    }
  }

  /** 128 random bits as 32 hex digits. */
  static RepositoryId random() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return new RepositoryId(HexFormat.of().formatHex(bytes));
  }

  boolean isCatalog() {
    return equals(CATALOG);
  }

  @Override
  public int compareTo(RepositoryId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}
