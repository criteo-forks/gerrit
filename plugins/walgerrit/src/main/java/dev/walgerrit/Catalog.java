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
import com.google.gerrit.entities.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The names: which project name is bound to which repository id, and how each binding got there.
 *
 * <p>The catalog is a WalGerrit repository like any other, under the fixed id {@link
 * RepositoryId#CATALOG}, and never a Gerrit project: the repository manager does not list it and no
 * Gerrit permission applies to it. One ref, {@link #REF}, holds one blob per name that was ever
 * bound, at {@code bindings/<sha1 of the name>} so that a name and a name it is a prefix of never
 * collide. Every namespace operation is one commit on that ref, hence one manifest CAS on the
 * catalog: atomic across every binding it touches, linearizable, replicated and replayed by the same
 * machinery as every other write, with the history as commits.
 *
 * <p>A binding is {@code active}, {@code pending} while an operation is in flight, or {@code
 * retired} for good: a name is never rebound, so "never existed", "moved", "deleted" and "in flight"
 * are four distinct authoritative answers. A pending binding records its operation, so any node can
 * finish an operation whose node died.
 *
 * <p>Reads are served from the catalog repository's handle and revalidate the way every handle
 * does: within the revalidation interval, or at once when a peer's hint announced a newer catalog.
 * Writes always start from an authoritative read.
 */
final class Catalog {
  private static final Logger logger = LoggerFactory.getLogger(Catalog.class);
  static final String REF = Constants.R_HEADS + "main";
  static final String BINDINGS = "bindings";
  private static final int MAX_COMMIT_ATTEMPTS = 16;
  private static final Project.NameKey NAME = Project.nameKey(RepositoryId.CATALOG.value());

  enum State {
    ACTIVE,
    PENDING,
    RETIRED
  }

  enum Kind {
    CREATE,
    IMPORT,
    RENAME,
    DELETE
  }

  /**
   * A namespace operation in flight, recorded on every binding it touches. {@code replicated}: the
   * operation follows one a primary made, whose name references arrive by replication, so none are
   * rewritten here.
   */
  record Operation(
      String id,
      Kind kind,
      long expectedEpoch,
      long targetEpoch,
      @Nullable Project.NameKey other,
      boolean replicated) {}

  /**
   * One name's binding. {@code epoch} is the repository write epoch writers admitted under this
   * name carry. A retired binding keeps its last epoch and, after a rename, the destination.
   */
  record Binding(
      Project.NameKey name,
      RepositoryId id,
      State state,
      long epoch,
      @Nullable Operation operation,
      @Nullable Project.NameKey movedTo,
      long sinceMillis) {

    boolean active() {
      return state == State.ACTIVE;
    }

    boolean pending() {
      return state == State.PENDING;
    }

    boolean retired() {
      return state == State.RETIRED;
    }

    /** Whether this is the name a pending rename moves the repository away from. */
    boolean pendingSource() {
      return pending()
          && operation != null
          && (operation.kind() == Kind.DELETE
              || (operation.kind() == Kind.RENAME && epoch == operation.expectedEpoch()));
    }

    Binding with(State newState, long newEpoch, @Nullable Operation op, @Nullable Project.NameKey to, long now) {
      return new Binding(name, id, newState, newEpoch, op, to, now);
    }
  }

  /** The catalog as of one commit. */
  record Snapshot(
      ObjectId tip,
      Map<Project.NameKey, Binding> byName,
      Map<RepositoryId, Project.NameKey> activeNames) {
    static final Snapshot EMPTY = new Snapshot(ObjectId.zeroId(), Map.of(), Map.of());

    Optional<Binding> binding(Project.NameKey name) {
      return Optional.ofNullable(byName.get(name));
    }
  }

  /** What one commit does to the bindings, given the authoritative catalog. */
  interface Change {
    Collection<Binding> apply(Snapshot current) throws IOException;
  }

  /** Thrown when a namespace operation's precondition does not hold. */
  static class PreconditionException extends IOException {
    private static final long serialVersionUID = 1L;

    PreconditionException(String message) {
      super(message);
    }
  }

  private final StorageLayout storage;
  private final Duration revalidateInterval;
  private final Clock clock;
  private final Object handleLock = new Object();
  private LocalWalGitRepository repository;
  private volatile Snapshot snapshot = Snapshot.EMPTY;

  Catalog(StorageLayout storage, Duration revalidateInterval) {
    this(storage, revalidateInterval, Clock.systemUTC());
  }

  Catalog(StorageLayout storage, Duration revalidateInterval, Clock clock) {
    this.storage = storage;
    this.revalidateInterval = revalidateInterval;
    this.clock = clock;
  }

  /** A project name WalGerrit accepts; the same rule for every backend. */
  static void requireValidName(Project.NameKey name) throws IOException {
    String projectName = name.get();
    if (projectName.isBlank() || projectName.indexOf('\\') >= 0) {
      throw new IOException("Invalid project name: " + projectName);
    }
    for (String component : projectName.split("/", -1)) {
      if (component.isBlank() || component.equals(".") || component.equals("..")) {
        throw new IOException("Invalid project name: " + projectName);
      }
    }
  }

  /**
   * The binding of {@code name}. With {@code authoritative}, the catalog is re-read from the store
   * first, so the answer is the catalog's current state; otherwise the handle's usual freshness
   * applies.
   */
  Optional<Binding> resolve(Project.NameKey name, boolean authoritative) throws IOException {
    return snapshot(authoritative).binding(name);
  }

  /** The names that currently have an active binding, in order. */
  NavigableSet<Project.NameKey> activeNames() throws IOException {
    NavigableSet<Project.NameKey> names = new TreeSet<>();
    for (Binding binding : snapshot(false).byName().values()) {
      if (binding.active()) {
        names.add(binding.name());
      }
    }
    return names;
  }

  /**
   * What the catalog says about {@code id}: its active binding when it has one, else a pending
   * one, else a retired one, else nothing (an orphan, or the catalog itself).
   */
  Optional<Binding> bindingOf(RepositoryId id, boolean authoritative) throws IOException {
    Snapshot current = snapshot(authoritative);
    Project.NameKey active = current.activeNames().get(id);
    if (active != null) {
      return current.binding(active);
    }
    Binding best = null;
    for (Binding binding : current.byName().values()) {
      if (!binding.id().equals(id)) {
        continue;
      }
      if (binding.pending()) {
        return Optional.of(binding);
      }
      best = binding;
    }
    return Optional.ofNullable(best);
  }

  /** Every binding that carries a pending operation, by operation id. */
  Map<String, List<Binding>> pendingOperations() throws IOException {
    Map<String, List<Binding>> pending = new TreeMap<>();
    for (Binding binding : snapshot(true).byName().values()) {
      if (binding.pending() && binding.operation() != null) {
        pending.computeIfAbsent(binding.operation().id(), ignored -> new ArrayList<>()).add(binding);
      }
    }
    return pending;
  }

  /** The current catalog; see {@link #resolve} for what {@code authoritative} means. */
  Snapshot snapshot(boolean authoritative) throws IOException {
    LocalWalGitRepository handle = handle();
    if (authoritative) {
      handle.scanForRepoChanges();
    }
    Ref ref = handle.exactRef(REF);
    ObjectId tip = ref == null ? ObjectId.zeroId() : ref.getObjectId();
    Snapshot known = snapshot;
    if (known.tip().equals(tip)) {
      return known;
    }
    Snapshot loaded = load(handle, tip);
    snapshot = loaded;
    return loaded;
  }

  /**
   * Applies {@code change} to the current bindings in one commit. The function sees the
   * authoritative bindings, checks its preconditions, throwing {@link PreconditionException} when
   * they do not hold, and returns the bindings to write. A commit that loses the catalog's CAS to
   * another node re-runs the function against the newer catalog.
   */
  Snapshot commit(String message, Change change) throws IOException {
    for (int attempt = 1; attempt <= MAX_COMMIT_ATTEMPTS; attempt++) {
      Snapshot current = snapshot(true);
      Collection<Binding> changed = change.apply(current);
      if (changed.isEmpty()) {
        return current;
      }
      LocalWalGitRepository handle = handle();
      ObjectId commitId;
      try (ObjectInserter inserter = handle.newObjectInserter();
          ObjectReader reader = handle.newObjectReader()) {
        DirCache index = DirCache.newInCore();
        if (!current.tip().equals(ObjectId.zeroId())) {
          DirCacheBuilder builder = index.builder();
          try (RevWalk walk = new RevWalk(reader)) {
            builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, walk.parseTree(current.tip()));
          }
          builder.finish();
        }
        DirCacheEditor editor = index.editor();
        for (Binding binding : changed) {
          ObjectId blob = inserter.insert(Constants.OBJ_BLOB, serialize(binding));
          editor.add(
              new DirCacheEditor.PathEdit(leaf(binding.name())) {
                @Override
                public void apply(DirCacheEntry entry) {
                  entry.setFileMode(FileMode.REGULAR_FILE);
                  entry.setObjectId(blob);
                }
              });
        }
        editor.finish();
        ObjectId tree = index.writeTree(inserter);
        PersonIdent author =
            new PersonIdent("WalGerrit", "walgerrit@" + ManifestStore.writerHost(), clock.instant(), java.time.ZoneOffset.UTC);
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        if (!current.tip().equals(ObjectId.zeroId())) {
          commit.setParentId(current.tip());
        }
        commit.setAuthor(author);
        commit.setCommitter(author);
        commit.setMessage(message + "\n");
        commitId = inserter.insert(commit);
        inserter.flush();
      }
      RefUpdate update = handle.updateRef(REF);
      update.setExpectedOldObjectId(current.tip());
      update.setNewObjectId(commitId);
      update.setRefLogMessage(message, false);
      RefUpdate.Result result = update.update();
      switch (result) {
        case NEW, FAST_FORWARD -> {
          Snapshot after = load(handle, commitId);
          snapshot = after;
          return after;
        }
        case LOCK_FAILURE, REJECTED -> {
          logger.info(
              "WalGerrit catalog changed while committing '{}'; retrying ({}/{})",
              message,
              attempt,
              MAX_COMMIT_ATTEMPTS);
        }
        default -> throw new IOException("Catalog commit '" + message + "' ended in " + result);
      }
    }
    throw new IOException(
        "Catalog commit '" + message + "' lost " + MAX_COMMIT_ATTEMPTS + " races in a row");
  }

  /**
   * The names whose bindings differ between two catalog commits, for the tailer: what a replayed
   * catalog transaction touched. Bindings are never removed, so every difference is a blob in
   * {@code newTip}.
   */
  Set<Project.NameKey> changedNames(ObjectId oldTip, ObjectId newTip) throws IOException {
    LocalWalGitRepository handle = handle();
    Set<Project.NameKey> names = new LinkedHashSet<>();
    try (ObjectReader reader = handle.newObjectReader();
        RevWalk walk = new RevWalk(reader);
        TreeWalk trees = new TreeWalk(reader)) {
      trees.setRecursive(true);
      if (!oldTip.equals(ObjectId.zeroId())) {
        trees.addTree(walk.parseTree(oldTip));
      } else {
        trees.addTree(new org.eclipse.jgit.treewalk.EmptyTreeIterator());
      }
      trees.addTree(walk.parseTree(newTip));
      while (trees.next()) {
        if (!trees.idEqual(0, 1) && trees.getPathString().startsWith(BINDINGS + "/")) {
          names.add(nameOf(trees.getPathString()));
        }
      }
    }
    return names;
  }

  /** The catalog repository's id-keyed store, for callers that need to read it as a repository. */
  RepositoryId id() {
    return RepositoryId.CATALOG;
  }

  private Snapshot load(LocalWalGitRepository handle, ObjectId tip) throws IOException {
    if (tip.equals(ObjectId.zeroId())) {
      return Snapshot.EMPTY;
    }
    Map<Project.NameKey, Binding> byName = new TreeMap<>();
    Map<RepositoryId, Project.NameKey> activeNames = new TreeMap<>();
    try (ObjectReader reader = handle.newObjectReader();
        RevWalk walk = new RevWalk(reader);
        TreeWalk trees = new TreeWalk(reader)) {
      RevCommit commit = walk.parseCommit(tip);
      trees.setRecursive(true);
      trees.addTree(commit.getTree());
      while (trees.next()) {
        if (!trees.getPathString().startsWith(BINDINGS + "/")) {
          continue;
        }
        Binding binding = parse(reader.open(trees.getObjectId(0)));
        byName.put(binding.name(), binding);
        if (binding.active()) {
          activeNames.put(binding.id(), binding.name());
        }
      }
    }
    return new Snapshot(
        tip, Collections.unmodifiableMap(byName), Collections.unmodifiableMap(activeNames));
  }

  private LocalWalGitRepository handle() throws IOException {
    synchronized (handleLock) {
      if (repository != null) {
        return repository;
      }
      ManifestStore store = storage.manifestStore(RepositoryId.CATALOG);
      if (!store.exists()) {
        store.create();
      }
      LocalWalGitRepository opened =
          new LocalWalGitRepository(NAME, store, revalidateInterval, ManifestStore.UNFENCED);
      if (!opened.exists()) {
        // Recover a process death between manifest creation and the initial HEAD transaction, or
        // this is a brand-new deployment. Of two nodes doing this at once, the CAS refuses one.
        try {
          opened.create(true);
        } catch (IOException lostRace) {
          opened.scanForRepoChanges();
          if (!opened.exists()) {
            opened.close();
            throw lostRace;
          }
        }
      }
      repository = opened;
      return opened;
    }
  }

  /** Where {@code name}'s binding lives in the catalog tree: the name's bytes, hex encoded. */
  static String leaf(Project.NameKey name) {
    return BINDINGS + "/" + HexFormat.of().formatHex(name.get().getBytes(StandardCharsets.UTF_8));
  }

  static Project.NameKey nameOf(String leaf) {
    return Project.nameKey(
        new String(
            HexFormat.of().parseHex(leaf.substring(BINDINGS.length() + 1)), StandardCharsets.UTF_8));
  }

  static byte[] serialize(Binding binding) {
    Config config = new Config();
    config.setString("binding", null, "name", binding.name().get());
    config.setString("binding", null, "id", binding.id().value());
    config.setString("binding", null, "state", binding.state().name().toLowerCase(java.util.Locale.ROOT));
    config.setLong("binding", null, "epoch", binding.epoch());
    config.setLong("binding", null, "since", binding.sinceMillis());
    if (binding.movedTo() != null) {
      config.setString("binding", null, "movedTo", binding.movedTo().get());
    }
    Operation op = binding.operation();
    if (op != null) {
      config.setString("operation", null, "id", op.id());
      config.setString("operation", null, "kind", op.kind().name().toLowerCase(java.util.Locale.ROOT));
      config.setLong("operation", null, "expectedEpoch", op.expectedEpoch());
      config.setLong("operation", null, "targetEpoch", op.targetEpoch());
      if (op.other() != null) {
        config.setString("operation", null, "other", op.other().get());
      }
      if (op.replicated()) {
        config.setBoolean("operation", null, "replicated", true);
      }
    }
    return config.toText().getBytes(StandardCharsets.UTF_8);
  }

  static Binding parse(ObjectLoader loader) throws IOException {
    Config config = new Config();
    try {
      config.fromText(new String(loader.getCachedBytes(), StandardCharsets.UTF_8));
    } catch (ConfigInvalidException invalid) {
      throw new IOException("Unreadable catalog binding", invalid);
    }
    Operation op = null;
    String opId = config.getString("operation", null, "id");
    if (opId != null) {
      String other = config.getString("operation", null, "other");
      op =
          new Operation(
              opId,
              Kind.valueOf(config.getString("operation", null, "kind").toUpperCase(java.util.Locale.ROOT)),
              config.getLong("operation", null, "expectedEpoch", 0),
              config.getLong("operation", null, "targetEpoch", 0),
              other == null ? null : Project.nameKey(other),
              config.getBoolean("operation", null, "replicated", false));
    }
    String movedTo = config.getString("binding", null, "movedTo");
    return new Binding(
        Project.nameKey(config.getString("binding", null, "name")),
        new RepositoryId(config.getString("binding", null, "id")),
        State.valueOf(config.getString("binding", null, "state").toUpperCase(java.util.Locale.ROOT)),
        config.getLong("binding", null, "epoch", 0),
        op,
        movedTo == null ? null : Project.nameKey(movedTo),
        config.getLong("binding", null, "since", 0));
  }
}
