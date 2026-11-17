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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.extensions.events.ChangeMerged;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNoteFooters;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Rebuilds the change-merged event of a submit from the ref transaction the submit committed.
 *
 * <p>A submit commits, per repository, one transaction that moves the destination branch and the
 * meta ref of every change it merges there, and fires the events afterwards. The transaction
 * therefore says what the event says about the merge: NoteDb at the meta ref's new value has the
 * change merged, the patch set that was merged as its current one and the submitter's approval, and
 * the branch's new value is the revision the event names. A change that became merged in a
 * transaction that did not move its branch was closed by a push or found already merged; the
 * revision its event names is not in that transaction, and the event is not rebuilt.
 *
 * <p>What the event says about the change besides the merge, its topic or its hashtags, is what
 * Gerrit's listeners read when the event is fired, here as for any event.
 *
 * <p>Uses Gerrit's types only, so that Gerrit's own tests can hold it against real submits.
 */
@Singleton
class ChangeMergedReconstruction {
  /** One ref of a transaction; a ref that did not exist or was deleted has the zero id. */
  record RefChange(String name, ObjectId oldId, ObjectId newId) {}

  /**
   * A change that a transaction merged. {@code newRevision} is what the transaction made the
   * change's destination branch and {@code submitter} who submitted; a merge that lacks one of them
   * was not made by a submit.
   */
  record Merge(
      Change.Id change,
      int patchSet,
      ObjectId patchSetRevision,
      ObjectId metaRevision,
      @Nullable ObjectId newRevision,
      @Nullable Account.Id submitter,
      Instant mergedAt) {
    boolean bySubmit() {
      return newRevision != null && submitter != null;
    }
  }

  /** What {@link #fire} did. */
  enum Fired {
    FIRED,
    /**
     * The change does not exist any more, or is not what the merge left: its history was replaced
     * by one that does not hold the merge, or it was merged again as something else.
     */
    GONE,
    /** Not {@linkplain Merge#bySubmit() by a submit}: the event cannot be rebuilt. */
    NOT_BY_SUBMIT
  }

  private final GitRepositoryManager repositories;
  private final ChangeNotes.Factory notesFactory;
  private final OneOffRequestContext requestContext;
  // Only the daemon can fire events; init and reindex load this library too.
  private final Injector injector;

  @Inject
  ChangeMergedReconstruction(
      GitRepositoryManager repositories,
      ChangeNotes.Factory notesFactory,
      OneOffRequestContext requestContext,
      Injector injector) {
    this.repositories = repositories;
    this.notesFactory = notesFactory;
    this.requestContext = requestContext;
    this.injector = injector;
  }

  /**
   * The changes that became merged by {@code transaction}: the updates of one ref transaction of
   * {@code project}, no more and no fewer.
   *
   * @throws IOException when the repository cannot be read, or lacks what the transaction wrote
   */
  List<Merge> mergesIn(Project.NameKey project, List<RefChange> transaction) throws IOException {
    List<Merge> merges = new ArrayList<>();
    Repository repository = null;
    try {
      for (RefChange update : transaction) {
        Change.Id id = changeOfMetaRef(update);
        if (id == null) {
          continue;
        }
        if (repository == null) {
          repository = repositories.openRepository(project);
        }
        // Most updates of a change are comments and votes; only a few of their commits are read.
        if (setsStatusMerged(repository, update)) {
          merge(project, id, update, transaction).ifPresent(merges::add);
        }
      }
    } finally {
      if (repository != null) {
        repository.close();
      }
    }
    return merges;
  }

  @Nullable
  private static Change.Id changeOfMetaRef(RefChange update) {
    if (!update.name().startsWith(RefNames.REFS_CHANGES)
        || !update.name().endsWith(RefNames.META_SUFFIX)
        || isZero(update.newId())
        || update.newId().equals(update.oldId())) {
      return null;
    }
    return Change.Id.fromRef(update.name());
  }

  /** Whether a NoteDb commit that {@code update} added sets the change's status to merged. */
  private static boolean setsStatusMerged(Repository repository, RefChange update)
      throws IOException {
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(update.newId()));
      if (!isZero(update.oldId())) {
        walk.markUninteresting(walk.parseCommit(update.oldId()));
      }
      for (RevCommit commit : walk) {
        for (String status : commit.getFooterLines(ChangeNoteFooters.FOOTER_STATUS)) {
          if (Change.Status.MERGED.name().equalsIgnoreCase(status.trim())) {
            return true;
          }
        }
      }
      return false;
    } catch (MissingObjectException missing) {
      if (repository.exactRef(update.name()) == null) {
        // The change was deleted since, and its history with it.
        return false;
      }
      // NoteDb only appends: what a change's meta ref once was is in its history.
      throw missing;
    }
  }

  private Optional<Merge> merge(
      Project.NameKey project, Change.Id id, RefChange update, List<RefChange> transaction) {
    ChangeNotes after = notesFactory.create(project, id, update.newId());
    if (!after.getChange().isMerged()) {
      return Optional.empty();
    }
    if (!isZero(update.oldId())
        && notesFactory.create(project, id, update.oldId()).getChange().isMerged()) {
      // Merged before already; this transaction changed something else.
      return Optional.empty();
    }
    // A submit makes the patch set it merges the current one and approves it as the submitter.
    PatchSet.Id patchSet = after.getChange().currentPatchSetId();
    PatchSet merged = after.getPatchSets().get(patchSet);
    if (merged == null) {
      // Gerrit could not fire an event for such a change either.
      return Optional.empty();
    }
    PatchSetApproval submit =
        after.getApprovals().all().get(patchSet).stream()
            .filter(PatchSetApproval::isLegacySubmit)
            .findFirst()
            .orElse(null);
    String branch = after.getChange().getDest().branch();
    ObjectId tip =
        transaction.stream()
            .filter(ref -> ref.name().equals(branch) && !isZero(ref.newId()))
            .map(RefChange::newId)
            .findFirst()
            .orElse(null);
    return Optional.of(
        new Merge(
            id,
            patchSet.get(),
            merged.commitId(),
            update.newId(),
            tip,
            submit == null ? null : submit.accountId(),
            submit == null ? after.getChange().getLastUpdatedOn() : submit.granted()));
  }

  /**
   * Fires the change-merged event of {@code merge} as the submit that made it does: to the
   * listeners of the extension API, one of which turns it into the stream event. Gerrit logs what a
   * listener throws and goes on, so {@link Fired#FIRED} says the event was handed to them, not that
   * each of them took it.
   */
  Fired fire(Project.NameKey project, Merge merge) {
    if (!merge.bySubmit()) {
      return Fired.NOT_BY_SUBMIT;
    }
    try (ManualRequestContext asSubmitter = requestContext.openAs(merge.submitter())) {
      // Listeners look the change up as it is now: it has to be the change this merge left, with
      // whatever was added since, and not one whose history was put back to before the merge.
      ChangeNotes now;
      try {
        now = notesFactory.createChecked(project, merge.change());
      } catch (NoSuchChangeException deleted) {
        return Fired.GONE;
      }
      if (!now.getChange().isMerged() || !inHistory(project, merge.metaRevision(), now)) {
        return Fired.GONE;
      }
      ChangeNotes merged = notesFactory.create(project, merge.change(), merge.metaRevision());
      PatchSet.Id patchSetId = PatchSet.id(merge.change(), merge.patchSet());
      PatchSet patchSet = merged.getPatchSets().get(patchSetId);
      if (patchSet == null) {
        throw new IllegalStateException(
            "Change " + merge.change() + " has no patch set " + merge.patchSet());
      }
      // Still that merge: the same commit merged by the same submission. Listeners would read
      // another one from the change as it is.
      PatchSet current = now.getCurrentPatchSet();
      if (!patchSetId.equals(now.getChange().currentPatchSetId())
          || current == null
          || !current.commitId().equals(merge.patchSetRevision())
          || !Objects.equals(
              now.getChange().getSubmissionId(), merged.getChange().getSubmissionId())) {
        return Fired.GONE;
      }
      injector
          .getInstance(ChangeMerged.class)
          .fire(
              injector.getInstance(ChangeData.Factory.class).create(merged),
              patchSet,
              injector.getInstance(AccountCache.class).get(merge.submitter()).orElse(null),
              merge.newRevision().name(),
              merge.mergedAt());
      return Fired.FIRED;
    }
  }

  /**
   * Whether the NoteDb history of the change {@code now} holds the commit {@code revision}. The
   * history is walked to its beginning, so "no" is what the repository says and not what a commit
   * that cannot be read leaves open.
   */
  private boolean inHistory(Project.NameKey project, ObjectId revision, ChangeNotes now) {
    try (Repository repository = repositories.openRepository(project);
        RevWalk walk = new RevWalk(repository)) {
      walk.setRetainBody(false);
      walk.markStart(walk.parseCommit(now.getMetaId()));
      for (RevCommit commit : walk) {
        if (commit.equals(revision)) {
          return true;
        }
      }
      return false;
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private static boolean isZero(ObjectId id) {
    return ObjectId.zeroId().equals(id);
  }
}
