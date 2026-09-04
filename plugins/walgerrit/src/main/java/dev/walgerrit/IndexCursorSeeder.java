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

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import dev.walgerrit.proto.StorageProto.Manifest;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Records on this node that its indexes reflect the current head of every repository.
 *
 * <p>The index-event tailer replays, from each repository's node-local cursor, the ref
 * transactions the node's indexes have not seen yet. A repository without a cursor counts as never
 * indexed, and when its log cannot be replayed from the very start, which is the case for every
 * imported repository that has been written to since (an import publishes no ref transaction, so
 * the chain does not reach back to the beginning), the daemon falls back to rebuilding every index
 * on startup, with one thread per index and the daemon's own configuration. Running this after an
 * offline {@code reindex} seeds every cursor at the head that reindex saw, so the daemon only
 * replays what is published afterwards. It writes node-local cursor files and nothing else.
 */
public final class IndexCursorSeeder {
  private static final String USAGE =
      """
      Usage: walgerrit-mark-indexed -d SITE

      Records on this node that its indexes reflect the current head of every repository. Run it
      after an offline reindex, before the daemon starts; anything published in between is replayed
      by the daemon when it starts.
      """;

  private final WalGitRepositoryManager repositories;
  private final PrintStream out;

  IndexCursorSeeder(WalGitRepositoryManager repositories, PrintStream out) {
    this.repositories = repositories;
    this.out = out;
  }

  /** Command-line entry point used by Gerrit's {@code walgerrit-mark-indexed} program. */
  public static int run(GitRepositoryManager manager, String[] args) throws IOException {
    for (String arg : args) {
      if (arg.equals("--help") || arg.equals("-h")) {
        System.out.print(USAGE);
        return 0;
      }
      throw new IllegalArgumentException("Unknown argument: " + arg + "\n" + USAGE);
    }
    if (!(manager instanceof WalGitRepositoryManager walGit)) {
      throw new IllegalStateException(
          "gerrit.installDbModule must install dev.walgerrit.WalGitModule; found "
              + manager.getClass().getName());
    }
    new IndexCursorSeeder(walGit, System.out).seedAll();
    return 0;
  }

  /** Seeds every repository's cursor at its current head; returns how many were written. */
  int seedAll() throws IOException {
    NavigableMap<Project.NameKey, String> heads = repositories.storage().listManifestVersions();
    for (Map.Entry<Project.NameKey, String> head : heads.entrySet()) {
      ManifestStore manifestStore = repositories.storage().manifestStore(head.getKey());
      Manifest manifest = manifestStore.currentOrRefresh(head.getValue()).manifest();
      new IndexCursorStore(manifestStore.indexCursorPath())
          .write(manifest.getHeadSeq(), manifest.getHeadTransactionId());
    }
    out.printf(
        Locale.ROOT,
        "Marked %d repositories as indexed at their current heads under %s%n",
        heads.size(),
        repositories.configuration().indexCursorPath());
    return heads.size();
  }
}
