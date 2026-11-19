// Copyright (C) 2026 The Android Open Source Project
// SPDX-License-Identifier: Apache-2.0

package com.google.gerrit.server.index.change;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateListener;
import com.google.gerrit.server.update.UpdateException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Exact branch/Change-Id membership read from storage and checked when the same Git batch commits.
 */
@Singleton
public final class ChangeKeyLookup {
  private final ChangeNotes.Factory notesFactory;
  private final ChangeData.Factory changeDataFactory;
  private final ChangeUtil changeUtil;
  private final Backend backend;

  @Inject
  ChangeKeyLookup(
      ChangeNotes.Factory notesFactory,
      ChangeData.Factory changeDataFactory,
      ChangeUtil changeUtil,
      StorageBackend storage) {
    this(notesFactory, changeDataFactory, changeUtil, storage::open);
  }

  @VisibleForTesting
  public ChangeKeyLookup(
      ChangeNotes.Factory notesFactory,
      ChangeData.Factory changeDataFactory,
      ChangeUtil changeUtil,
      Backend backend) {
    this.notesFactory = notesFactory;
    this.changeDataFactory = changeDataFactory;
    this.changeUtil = changeUtil;
    this.backend = backend;
  }

  @FunctionalInterface
  public interface Backend {
    Optional<BiFunction<String, String, List<Integer>>> open(
        Repository repo, BiConsumer<BatchUpdateListener, BooleanSupplier> guard);
  }

  static final class StorageBackend {
    @Inject(optional = true)
    @Named("reviewChangeKeyLookup")
    @Nullable
    private BiFunction<
            Repository,
            BiConsumer<BatchUpdateListener, BooleanSupplier>,
            Optional<BiFunction<String, String, List<Integer>>>>
        opener;

    @Inject
    void verifyStorageModule(@GerritServerConfig Config config) {
      // Guice has injected the storage module's optional binding before calling this method.
      if (opener == null
          && Arrays.asList(config.getStringList("gerrit", null, "installDbModule"))
              .contains("dev.walgerrit.WalGitModule")) {
        throw new IllegalStateException(
            "Configured WalGerrit storage has no Change-Id publication guard. Deploy the matching"
                + " storage JAR.");
      }
    }

    Optional<BiFunction<String, String, List<Integer>>> open(
        Repository repo, BiConsumer<BatchUpdateListener, BooleanSupplier> guard) {
      if (opener == null) {
        return Optional.empty();
      }
      var lookup = opener.apply(repo, guard);
      if (lookup.isEmpty()) {
        throw new ChangeIndexNotFreshException(
            "Storage cannot guard this Change-Id lookup. Please retry.");
      }
      return lookup;
    }
  }

  public Optional<Snapshot> open(Repository repo) {
    Snapshot snapshot = new Snapshot(repo);
    try {
      return backend
          .open(repo, snapshot::register)
          .map(
              lookup -> {
                requireNonNull(snapshot.checks, "change-key lookup has no publication guard");
                snapshot.lookup = lookup;
                return snapshot;
              });
    } catch (UncheckedIOException e) {
      throw new ChangeIndexNotFreshException("Cannot read change keys. Please retry.", e);
    }
  }

  public final class Snapshot {
    private final Repository repo;
    private BiFunction<String, String, List<Integer>> lookup;
    private BatchUpdateListener checks;
    private BooleanSupplier conflicted;

    private Snapshot(Repository repo) {
      this.repo = repo;
    }

    private void register(BatchUpdateListener checks, BooleanSupplier conflicted) {
      this.checks = requireNonNull(checks);
      this.conflicted = requireNonNull(conflicted);
    }

    public List<Integer> members(String branch, String key) {
      try {
        return lookup.apply(branch, key);
      } catch (UncheckedIOException e) {
        throw new ChangeIndexNotFreshException("Cannot read change keys. Please retry.", e);
      }
    }

    /** Loads members from NoteDb and detects a move or deletion between the two reads. */
    public List<ChangeData> changes(BranchNameKey branch, Change.Key key, int limit)
        throws Conflict {
      List<ChangeData> matches = new ArrayList<>();
      for (int number : members(branch.branch(), key.get())) {
        ChangeNotes notes;
        try {
          notes = notesFactory.createChecked(repo, branch.project(), Change.id(number), null);
        } catch (NoSuchChangeException disappeared) {
          throw new Conflict(disappeared);
        }
        if (!notes.getChange().getDest().equals(branch)
            || !notes.getChange().getKey().equals(key)) {
          throw new Conflict(null);
        }
        matches.add(changeDataFactory.create(notes));
        if (matches.size() == limit) {
          break;
        }
      }
      return matches;
    }

    /** ChangeInserter takes the last configured Change-Id or Link footer of the final commit. */
    public void checkInsertedCommit(
        String branch, RevWalk walk, RevCommit commit, Optional<String> alreadyChecked)
        throws IOException, ResourceConflictException {
      walk.parseBody(commit);
      List<String> ids = changeUtil.getChangeIdsFromFooter(commit);
      if (!ids.isEmpty()) {
        String actual = ids.get(ids.size() - 1).trim();
        if (alreadyChecked.filter(actual::equals).isEmpty() && !members(branch, actual).isEmpty()) {
          throw new ResourceConflictException(
              "A change with Change-Id " + actual + " already exists for this branch.");
        }
      }
    }
  }

  /** Membership changed during selection or storage positively rejected the guarded publication. */
  public static final class Conflict extends ResourceConflictException {
    private static final long serialVersionUID = 1L;

    private Conflict(Exception cause) {
      super("Another write changed this Change-Id on the destination branch. Please retry.", cause);
    }
  }

  public static void execute(BatchUpdate batch, Optional<Snapshot> snapshot)
      throws UpdateException, RestApiException {
    if (snapshot.isEmpty()) {
      batch.execute();
      return;
    }
    Snapshot keys = snapshot.get();
    try {
      batch.execute(keys.checks);
    } catch (UpdateException | RestApiException | RuntimeException failure) {
      if (keys.conflicted.getAsBoolean()) {
        throw new Conflict(failure);
      }
      throw failure;
    }
  }
}
