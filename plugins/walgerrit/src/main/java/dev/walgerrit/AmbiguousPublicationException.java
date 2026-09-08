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

/**
 * A manifest compare-and-swap whose outcome is unknown: the response was lost and the log chain
 * could not be read, or did not show the transaction yet. It names the transaction so a later
 * publication can settle what happened from the log before it retries anything the attempt carried.
 */
final class AmbiguousPublicationException extends IOException {
  private static final long serialVersionUID = 1L;
  private final long sequence;
  private final String transactionId;

  AmbiguousPublicationException(long sequence, String transactionId, IOException cause) {
    super("Manifest CAS outcome unknown for transaction " + transactionId + " at sequence " + sequence, cause);
    this.sequence = sequence;
    this.transactionId = transactionId;
  }

  long sequence() {
    return sequence;
  }

  String transactionId() {
    return transactionId;
  }
}
