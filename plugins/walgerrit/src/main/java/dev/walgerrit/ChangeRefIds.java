// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;

/** Treats integer overflow from the Gerrit plugin API as an unrecognized raw Git ref. */
final class ChangeRefIds {
  private ChangeRefIds() {}

  @Nullable
  static Change.Id fromRef(String name) {
    try {
      return Change.Id.fromRef(name);
    } catch (NumberFormatException outOfRange) {
      return null;
    }
  }

  @Nullable
  static Change.Id fromAllUsersRef(String name) {
    try {
      return Change.Id.fromAllUsersRef(name);
    } catch (NumberFormatException outOfRange) {
      return null;
    }
  }
}
