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
package com.google.gerrit.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ranks the nodes of a cluster for a project by rendezvous hashing.
 *
 * <p>Every node scores each candidate by a hash of the project and the candidate's name, and the
 * highest score wins. Nodes that see the same candidates therefore agree with no coordination, and
 * a candidate that leaves moves only the projects it held.
 */
public final class WriteRouter {
  /** Whether {@code candidate} is preferred over {@code incumbent} for {@code project}. */
  public static boolean outranks(String project, String candidate, String incumbent) {
    int order = Long.compareUnsigned(score(project, candidate), score(project, incumbent));
    return order > 0 || (order == 0 && candidate.compareTo(incumbent) < 0);
  }

  static long score(String project, String node) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    digest.update(project.getBytes(UTF_8));
    digest.update((byte) 0);
    digest.update(node.getBytes(UTF_8));
    byte[] hash = digest.digest();
    long score = 0;
    for (int i = 0; i < Long.BYTES; i++) {
      score = (score << 8) | (hash[i] & 0xff);
    }
    return score;
  }

  private WriteRouter() {}
}
