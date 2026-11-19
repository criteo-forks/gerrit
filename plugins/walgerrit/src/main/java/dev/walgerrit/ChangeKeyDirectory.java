// Copyright 2026 The WalGerrit Authors
// SPDX-License-Identifier: Apache-2.0

package dev.walgerrit;

import static com.google.gerrit.server.notedb.ChangeNoteFooters.FOOTER_BRANCH;
import static com.google.gerrit.server.notedb.ChangeNoteFooters.FOOTER_CHANGE_ID;

import com.google.common.hash.Hashing;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.RefNames;
import dev.walgerrit.proto.StorageProto.ChangeKeyCheck;
import dev.walgerrit.proto.StorageProto.ChangeKeyMutation;
import dev.walgerrit.proto.StorageProto.ChangeKeyPage;
import dev.walgerrit.proto.StorageProto.ChangeKeys;
import dev.walgerrit.proto.StorageProto.ChangeMembers;
import dev.walgerrit.proto.StorageProto.LogEntry;
import dev.walgerrit.proto.StorageProto.LogPosition;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.RefTransaction;
import dev.walgerrit.proto.StorageProto.RefUpdate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

/** Branch/Change-Id membership at one published ref revision, including pre-existing duplicates. */
final class ChangeKeyDirectory {
  /** This ref transaction definitively did not publish. Re-select the changes on a new snapshot. */
  static final class DecisionChanged extends IOException {
    private static final long serialVersionUID = 1L;

    DecisionChanged(String reason) {
      super(reason);
    }
  }

  /** A raw or nonstandard meta update can be published, but invalidates directory coverage. */
  static final class Unclassified extends IOException {
    private static final long serialVersionUID = 1L;

    Unclassified(String reason) {
      super(reason);
    }
  }

  private final ManifestStore store;
  private final Manifest base;
  private final Map<Integer, ChangeKeyPage.Builder> pages = new HashMap<>();
  private final Set<Integer> dirty = new HashSet<>();

  ChangeKeyDirectory(ManifestStore store, Manifest base) {
    this.store = store;
    this.base = base;
  }

  static boolean complete(Manifest manifest) {
    return manifest.hasChangeKeys()
        && manifest.getChangeKeys().getRefRevision() == manifest.getRefRevision();
  }

  static String key(String branch, String changeId) {
    return RefNames.fullName(branch) + '\0' + changeId;
  }

  static Change.Id metaId(String name) {
    if (!name.startsWith(RefNames.REFS_CHANGES) || !name.endsWith(RefNames.META_SUFFIX)) {
      return null;
    }
    Change.Id id = ChangeRefIds.fromRef(name);
    return id != null && RefNames.changeMetaRef(id).equals(name) ? id : null;
  }

  private static int shard(String key) {
    return Hashing.murmur3_32_fixed().hashString(key, StandardCharsets.UTF_8).asInt() & 255;
  }

  private ChangeKeyPage.Builder page(int shard) throws IOException {
    ChangeKeyPage.Builder page = pages.get(shard);
    if (page == null) {
      LogPosition position = base.getChangeKeys().getPagesMap().get(shard);
      page =
          position == null
              ? ChangeKeyPage.newBuilder()
              : store.keyPage(position, shard).toBuilder();
      pages.put(shard, page);
    }
    return page;
  }

  ChangeMembers members(String key) throws IOException {
    return page(shard(key)).getKeysOrDefault(key, ChangeMembers.getDefaultInstance());
  }

  private void change(String key, int id, boolean add) throws IOException {
    if (key.isEmpty()) {
      return;
    }
    int shard = shard(key);
    ChangeKeyPage.Builder page = page(shard);
    TreeSet<Integer> ids = new TreeSet<>(members(key).getNumbersList());
    if (!(add ? ids.add(id) : ids.remove(id))) {
      throw new IOException("Change-key directory disagrees with the meta ref of " + id);
    }
    if (ids.isEmpty()) {
      page.removeKeys(key);
    } else {
      page.putKeys(key, ChangeMembers.newBuilder().addAllNumbers(ids).build());
    }
    dirty.add(shard);
  }

  /** Rebuild entirely from the ref snapshot. The installer must still win the ref-revision CAS. */
  static ChangeKeyDirectory scan(Repository repo, ManifestStore store) throws IOException {
    return scan(repo, store, repo.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES));
  }

  static ChangeKeyDirectory scan(Repository repo, ManifestStore store, List<Ref> refs)
      throws IOException {
    ChangeKeyDirectory directory = new ChangeKeyDirectory(store, Manifest.getDefaultInstance());
    try (RevWalk walk = new RevWalk(repo)) {
      for (Ref ref : refs) {
        Change.Id id = metaId(ref.getName());
        if (id != null) {
          if (ref.isSymbolic()) {
            throw new Unclassified("Symbolic change meta ref " + ref.getName());
          }
          directory.change(identity(walk, ref.getObjectId()), id.get(), true);
        }
      }
    }
    return directory;
  }

  /** Catch an online seed up with writes acknowledged while it parsed the captured meta tips. */
  boolean advanceSeed(RevWalk walk, List<LogEntry> entries) throws IOException {
    for (LogEntry entry : entries) {
      if (entry.getKind() == LogEntry.Kind.UNRECOGNIZED
          || entry.getKind() == LogEntry.Kind.KIND_UNSPECIFIED
          || (entry.getKind() == LogEntry.Kind.REF_UPDATE && !entry.hasRefTransaction())
          || (entry.getKind() == LogEntry.Kind.PACK
              && entry.getAdditionsList().stream().anyMatch(ManifestStore::hasReftable))) {
        return false; // The history cannot certify identities. Start from a fresh ref snapshot.
      }
      for (RefUpdate update : entry.getRefTransaction().getUpdatesList()) {
        Change.Id id = metaId(update.getName());
        if (id == null) {
          continue;
        }
        if (!ObjectId.isId(update.getOldObjectId())
            || !ObjectId.isId(update.getNewObjectId())
            || !update.getNewSymbolicTarget().isEmpty()) {
          return false;
        }
        ChangeKeyMutation delta =
            update.hasChangeKey()
                ? update.getChangeKey()
                : classify(
                    walk,
                    new ReceiveCommand(
                        ObjectId.fromString(update.getOldObjectId()),
                        ObjectId.fromString(update.getNewObjectId()),
                        update.getName()));
        change(delta.getBefore(), id.get(), false);
        change(delta.getAfter(), id.get(), true);
      }
    }
    return true;
  }

  /** Immutable NoteDb commits carry the key and the latest Branch footer, including moves. */
  private static String identity(RevWalk walk, ObjectId tip) throws IOException {
    if (ObjectId.zeroId().equals(tip)) {
      return "";
    }
    String branch = null;
    String changeId = null;
    RevCommit commit = metaCommit(walk, tip);
    for (; ; ) {
      walk.parseBody(commit);
      if (commit.getParentCount() > 1) {
        throw new Unclassified("Nonlinear change history at " + commit.name());
      }
      if (branch == null) {
        branch = footer(commit, FOOTER_BRANCH);
      }
      if (changeId == null) {
        changeId = footer(commit, FOOTER_CHANGE_ID);
      }
      if (branch != null && changeId != null) {
        return key(branch, changeId);
      }
      if (commit.getParentCount() != 1) {
        throw new Unclassified("Cannot determine change identity at " + tip.name());
      }
      commit = walk.parseCommit(commit.getParent(0));
    }
  }

  private static RevCommit metaCommit(RevWalk walk, ObjectId id) throws IOException {
    try {
      RevCommit commit = walk.parseCommit(id);
      if (!commit.equals(id)) {
        throw new Unclassified("Change meta points to a tag at " + id.name());
      }
      return commit;
    } catch (IncorrectObjectTypeException unsupported) {
      throw new Unclassified("Change meta does not point to a commit at " + id.name());
    }
  }

  private static String footer(RevCommit commit, FooterKey name) throws IOException {
    List<String> values = commit.getFooterLines(name);
    if (values.size() > 1 || (!values.isEmpty() && values.getFirst().isEmpty())) {
      throw new Unclassified("Invalid " + name + " footer on " + commit.name());
    }
    return values.isEmpty() ? null : values.getFirst();
  }

  static ChangeKeyMutation classify(RevWalk walk, ReceiveCommand cmd) throws IOException {
    if (cmd.getOldSymref() != null || cmd.getNewSymref() != null) {
      throw new Unclassified("Symbolic change meta ref " + cmd.getRefName());
    }
    if (!ObjectId.zeroId().equals(cmd.getNewId())) {
      RevCommit next = metaCommit(walk, cmd.getNewId());
      walk.parseBody(next);
      if (next.getParentCount() == 1
          && next.getParent(0).equals(cmd.getOldId())
          && footer(next, FOOTER_BRANCH) == null
          && footer(next, FOOTER_CHANGE_ID) == null) {
        return ChangeKeyMutation.getDefaultInstance(); // Ordinary vote, comment or patch set.
      }
    }
    String before = identity(walk, cmd.getOldId());
    String after = identity(walk, cmd.getNewId());
    return before.equals(after)
        ? ChangeKeyMutation.getDefaultInstance()
        : ChangeKeyMutation.newBuilder().setBefore(before).setAfter(after).build();
  }

  void apply(RefTransaction tx) throws IOException {
    for (ChangeKeyCheck check : tx.getChangeKeyChecksList()) {
      if (!members(check.getKey()).equals(check.getExpected())) {
        throw new DecisionChanged("Change-Id membership changed while preparing the push");
      }
    }
    for (RefUpdate update : tx.getUpdatesList()) {
      if (update.hasChangeKey()) {
        int id = metaId(update.getName()).get();
        change(update.getChangeKey().getBefore(), id, false);
        change(update.getChangeKey().getAfter(), id, true);
      }
    }
  }

  void publish(LogEntry.Builder entry, Manifest.Builder next) {
    ChangeKeys.Builder keys =
        base.getChangeKeys().toBuilder().setRefRevision(next.getRefRevision());
    LogPosition position =
        LogPosition.newBuilder()
            .setSequence(entry.getSeq())
            .setTransactionId(entry.getTransactionId())
            .build();
    for (int shard : dirty) {
      ChangeKeyPage page = pages.get(shard).build();
      if (page.getKeysCount() == 0) {
        keys.removePages(shard);
      } else {
        entry.putChangeKeyPages(shard, page);
        keys.putPages(shard, position);
      }
    }
    next.setChangeKeys(keys);
  }
}
