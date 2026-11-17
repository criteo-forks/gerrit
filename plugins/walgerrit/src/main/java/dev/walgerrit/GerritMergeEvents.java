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

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.walgerrit.ChangeMergedReconstruction.Merge;
import dev.walgerrit.ChangeMergedReconstruction.RefChange;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.OwedMerge;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;

/**
 * {@link MergeEvents} of Gerrit changes: between the log's records, {@link
 * ChangeMergedReconstruction} and the journal.
 */
@Singleton
final class GerritMergeEvents implements MergeEvents {
  private static final String TYPE = "\"" + ChangeMergedEvent.TYPE + "\"";

  /** What a change-merged event says about the merge it announces. */
  private record Announced(int change, int patchSet, String patchSetRevision, String newRevision) {
    /**
     * Whether this is the event of {@code merge}: of that patch set's commit, on that branch tip.
     */
    boolean announces(OwedMerge merge) {
      return change == merge.getChange()
          && patchSet == merge.getPatchSet()
          && patchSetRevision.equals(merge.getPatchSetRevision())
          // The event of a merge that is not known to be a submit's names a revision this node
          // cannot know.
          && (!bySubmit(merge) || newRevision.equals(merge.getNewRevision()));
    }
  }

  private final ChangeMergedReconstruction reconstruction;
  private final WalJournal journal;

  @Inject
  GerritMergeEvents(ChangeMergedReconstruction reconstruction, WalJournal journal) {
    this.reconstruction = reconstruction;
    this.journal = journal;
  }

  @Override
  public List<OwedMerge> mergedBy(Project.NameKey project, LogEntry entry) throws IOException {
    RefTransaction logged = entry.getRefTransaction();
    int updates = logged.getUpdatesCount();
    boolean membersKnown =
        logged.getMembersCount() > 0
            && logged.getMembersList().stream().mapToInt(Integer::intValue).sum() == updates;
    List<OwedMerge> merged = new ArrayList<>();
    int from = 0;
    // An entry holds the transactions that were published together. Only what one of them did by
    // itself says something about a merge: a branch moved by another request is not its branch tip.
    for (int size : membersKnown ? logged.getMembersList() : List.of(updates)) {
      List<RefChange> transaction = new ArrayList<>();
      for (RefUpdate update : logged.getUpdatesList().subList(from, from + size)) {
        if (update.getNewSymbolicTarget().isEmpty()) {
          transaction.add(
              new RefChange(
                  update.getName(),
                  objectId(update.getOldObjectId()),
                  objectId(update.getNewObjectId())));
        }
      }
      from += size;
      for (Merge merge : reconstruction.mergesIn(project, transaction)) {
        OwedMerge.Builder owed =
            OwedMerge.newBuilder()
                .setChange(merge.change().get())
                .setPatchSet(merge.patchSet())
                .setPatchSetRevision(merge.patchSetRevision().name())
                .setMetaRevision(merge.metaRevision().name())
                .setMergedAtEpochMillis(merge.mergedAt().toEpochMilli())
                .setSequence(entry.getSeq())
                .setProcess(entry.getWriterProcess());
        if (membersKnown && merge.bySubmit()) {
          owed.setNewRevision(merge.newRevision().name()).setSubmitter(merge.submitter().get());
        }
        merged.add(owed.build());
      }
    }
    return merged;
  }

  @Override
  public List<OwedMerge> withoutAnnounced(List<OwedMerge> owed, LogEntry entry) {
    List<Announced> announced = new ArrayList<>();
    for (String json : entry.getEventJsonList()) {
      if (!json.contains(TYPE)) {
        continue;
      }
      try {
        JsonObject event = JsonParser.parseString(json).getAsJsonObject();
        if (!ChangeMergedEvent.TYPE.equals(string(event.get("type")))) {
          continue;
        }
        JsonObject patchSet = event.getAsJsonObject("patchSet");
        announced.add(
            new Announced(
                event.getAsJsonObject("change").get("number").getAsInt(),
                patchSet.get("number").getAsInt(),
                string(patchSet.get("revision")),
                string(event.get("newRev"))));
      } catch (JsonParseException | IllegalStateException | NullPointerException unreadable) {
        // Not an event this node can match to a merge; the merge stays owed.
      }
    }
    if (announced.isEmpty()) {
      return owed;
    }
    return owed.stream()
        .filter(merge -> announced.stream().noneMatch(event -> event.announces(merge)))
        .toList();
  }

  @Override
  public Delivery fire(Project.NameKey project, OwedMerge merge) {
    boolean bySubmit = bySubmit(merge);
    Merge rebuilt =
        new Merge(
            Change.id(merge.getChange()),
            merge.getPatchSet(),
            ObjectId.fromString(merge.getPatchSetRevision()),
            ObjectId.fromString(merge.getMetaRevision()),
            bySubmit ? ObjectId.fromString(merge.getNewRevision()) : null,
            bySubmit ? Account.id(merge.getSubmitter()) : null,
            Instant.ofEpochMilli(merge.getMergedAtEpochMillis()));
    try (WalJournal.Receipt receipt = journal.expect(event -> announces(event, merge))) {
      return switch (reconstruction.fire(project, rebuilt)) {
        case FIRED -> {
          if (!receipt.journaled()) {
            // Gerrit logs what goes wrong while an event is built and fired, and returns.
            throw new IllegalStateException(
                "No change-merged event of change "
                    + merge.getChange()
                    + " reached the journal when it was fired");
          }
          yield Delivery.FIRED;
        }
        case GONE -> Delivery.GONE;
        case NOT_BY_SUBMIT -> Delivery.LOST;
      };
    }
  }

  /**
   * Whether {@code event}, as it is being journaled, is the change-merged event of {@code merge}.
   */
  private static boolean announces(Event event, OwedMerge merge) {
    return event instanceof ChangeMergedEvent merged
        && merged.change != null
        && merged.patchSet != null
        && new Announced(
                merged.change.get().number,
                merged.patchSet.get().number,
                merged.patchSet.get().revision,
                merged.newRev == null ? "" : merged.newRev)
            .announces(merge);
  }

  private static boolean bySubmit(OwedMerge merge) {
    return !merge.getNewRevision().isEmpty();
  }

  private static String string(JsonElement element) {
    return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
  }

  private static ObjectId objectId(String id) {
    return id.isEmpty() ? ObjectId.zeroId() : ObjectId.fromString(id);
  }
}
