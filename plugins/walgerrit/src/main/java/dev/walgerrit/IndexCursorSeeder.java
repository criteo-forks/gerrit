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

import com.google.gerrit.server.git.GitRepositoryManager;
import dev.walgerrit.proto.StorageProto.IndexCursor;
import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Seeds node-local index cursors around a full offline reindex.
 *
 * <p>Capture heads before indexing, then persist those exact heads only after all indexes have been
 * rebuilt and flushed. Writes from other nodes during indexing remain available for replay. A
 * failed or interrupted reindex never acknowledges the captured heads; retry the full reindex
 * before starting this node. Like Gerrit's reindex command, this requires the local daemon stopped.
 */
public final class IndexCursorSeeder {
  private static final String USAGE =
      """
Use: reindex --walgerrit -d SITE

Standalone walgerrit-mark-indexed is unsafe with concurrent writers and is no longer supported.
Reindex captures replay cursors before indexing and saves them after successful index flushes.
""";

  private final WalGitRepositoryManager repositories;
  private final PrintStream out;

  IndexCursorSeeder(WalGitRepositoryManager repositories, PrintStream out) {
    this.repositories = repositories;
    this.out = out;
  }

  /** Refuse the unsafe standalone command, including when called from an older Gerrit WAR. */
  public static int run(GitRepositoryManager manager, String[] args) throws IOException {
    for (String arg : args) {
      if (arg.equals("--help") || arg.equals("-h")) {
        System.out.print(USAGE);
        return 0;
      }
      throw new IllegalArgumentException("Unknown argument: " + arg + "\n" + USAGE);
    }
    throw new IllegalArgumentException(USAGE);
  }

  /**
   * Called by Gerrit's full offline reindex; the callback must flush its indexes before success.
   */
  public static boolean reindex(GitRepositoryManager manager, Callable<Boolean> reindex)
      throws Exception {
    if (!(manager instanceof WalGitRepositoryManager walGit)) {
      throw new IllegalStateException(
          "gerrit.installDbModule must install dev.walgerrit.WalGitModule; found "
              + manager.getClass().getName());
    }
    return new IndexCursorSeeder(walGit, System.out).reindex(reindex);
  }

  boolean reindex(Callable<Boolean> reindex) throws Exception {
    Map<RepositoryId, IndexCursor> seeds = new LinkedHashMap<>();
    for (Map.Entry<RepositoryId, String> head :
        repositories.storage().listManifestVersions().entrySet()) {
      ManifestStore manifestStore = repositories.storage().manifestStore(head.getKey());
      ManifestCache.VersionedManifest versioned = manifestStore.currentOrRefresh(head.getValue());
      seeds.put(
          head.getKey(),
          IndexCursor.newBuilder()
              .setSequence(versioned.manifest().getHeadSeq())
              .setTransactionId(versioned.manifest().getHeadTransactionId())
              .setManifestVersion(versioned.version())
              .build());
    }
    if (!reindex.call()) {
      return false;
    }
    for (Map.Entry<RepositoryId, IndexCursor> seed : seeds.entrySet()) {
      IndexCursor cursor = seed.getValue();
      new IndexCursorStore(repositories.storage().manifestStore(seed.getKey()).indexCursorPath())
          .write(cursor.getSequence(), cursor.getTransactionId(), cursor.getManifestVersion());
    }
    out.printf(
        Locale.ROOT,
        "Marked %d repositories as indexed at their pre-reindex heads under %s%n",
        seeds.size(),
        repositories.configuration().indexCursorPath());
    return true;
  }
}
