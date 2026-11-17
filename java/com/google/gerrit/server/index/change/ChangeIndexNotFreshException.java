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

package com.google.gerrit.server.index.change;

/**
 * Thrown when a request needs an up-to-date change index and this node could not provide one; see
 * {@link ChangeIndexFreshness}.
 *
 * <p>The step that asked wrote nothing: a push is rejected before it updates a ref, and a
 * submission before it merges a change. A push that submits has created its change or patch set by
 * the time its submission asks, and that remains. The same request can succeed when retried, on
 * this node or another, so REST calls answer {@code 503} and pushes are rejected with the message.
 */
public class ChangeIndexNotFreshException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ChangeIndexNotFreshException(String message) {
    super(message);
  }

  public ChangeIndexNotFreshException(String message, Throwable cause) {
    super(message, cause);
  }
}
