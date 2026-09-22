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
import com.google.gerrit.server.git.RepositoryExistsException;
import dev.walgerrit.Catalog.Binding;
import dev.walgerrit.Catalog.Kind;
import dev.walgerrit.Catalog.Operation;
import dev.walgerrit.Catalog.PreconditionException;
import dev.walgerrit.Catalog.Snapshot;
import dev.walgerrit.Catalog.State;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, renames and deletes project names. One protocol, three durable transitions:
 *
 * <ol>
 *   <li><b>Prepare</b>, in the catalog: the bindings the operation touches become pending and
 *       record the operation, its expected and its target write epoch. A pending name is not
 *       served; handles opened before the prepare keep working until the fence. One rename or
 *       deletion is in flight at a time.
 *   <li><b>Fence</b>, in the repository's manifest: the write epoch advances from the expected to
 *       the target epoch, which refuses every writer admitted before, on every node, at the commit
 *       point. For a deletion the manifest also becomes terminally deleted. Whatever committed
 *       before the fence stays committed. Then, unless the operation is replicated from a primary,
 *       the typed references to the name outside the repository are rewritten; see {@link
 *       NameReferences}.
 *   <li><b>Finalize</b>, in the catalog: the source is retired, the destination activated under the
 *       target epoch, in one commit. Writers admitted under the new name carry the target epoch,
 *       which is what the manifest now has.
 * </ol>
 *
 * <p>Every transition is idempotent and recorded, so an operation whose node died is finished by
 * {@link #resume} from any node. Creation is the same protocol without a fence: the name is
 * reserved before the repository exists and activated once it is usable. See docs/namespace.md.
 */
public final class Namespace {
  private static final Logger logger = LoggerFactory.getLogger(Namespace.class);

  /** Makes a repository usable under its id: manifest present, HEAD initialized. */
  interface Initializer {
    void initialize(RepositoryId id, Project.NameKey name) throws IOException;
  }

  /** What outside the repository names a project; see {@link NameReferences}. */
  public record Report(NameReferences.Projects projects, NameReferences.Users users) {}

  private final Catalog catalog;
  private final StorageLayout storage;
  private final Initializer initializer;
  private final NameReferences references;
  private final Set<Project.NameKey> systemProjects;
  private final Clock clock;

  Namespace(
      Catalog catalog,
      StorageLayout storage,
      Initializer initializer,
      NameReferences references,
      Set<Project.NameKey> systemProjects,
      Clock clock) {
    this.catalog = catalog;
    this.storage = storage;
    this.initializer = initializer;
    this.references = references;
    this.systemProjects = systemProjects;
    this.clock = clock;
  }

  /**
   * Binds {@code name} to a new repository and makes it usable. A creation an earlier attempt left
   * pending is finished, under the id it reserved.
   *
   * @throws RepositoryExistsException when the name is bound, or was: names are never reused
   */
  RepositoryId create(Project.NameKey name) throws IOException {
    return create(name, Kind.CREATE, initializer);
  }

  /**
   * Same, with what makes the repository usable supplied by the caller: an import publishes the
   * imported packs and refs instead of an empty HEAD. An unfinished import is finished only by
   * another import, never by {@link #resume} or a plain creation, which would activate the name
   * over an empty repository.
   */
  RepositoryId create(Project.NameKey name, Kind kind, Initializer initializer) throws IOException {
    Catalog.requireValidName(name);
    String operationId = UUID.randomUUID().toString();
    RepositoryId fresh = RepositoryId.random();
    Snapshot prepared =
        catalog.commit(
            "Create " + name.get(),
            snapshot -> {
              Binding existing = snapshot.byName().get(name);
              if (existing == null) {
                return List.of(
                    new Binding(
                        name,
                        fresh,
                        State.PENDING,
                        0,
                        new Operation(operationId, kind, 0, 0, null, false),
                        null,
                        clock.millis()));
              }
              if (existing.pending() && existing.operation().kind() == kind) {
                return List.of();
              }
              throw new RepositoryExistsException(name, describe(existing));
            });
    Binding reserved = prepared.byName().get(name);
    complete(prepared, reserved.operation().id(), initializer);
    return reserved.id();
  }

  /**
   * Renames {@code from} to {@code to}; see the class comment for the protocol. Repeating a rename
   * that is in flight finishes it, and repeating one that completed does nothing, so a caller whose
   * reply was lost can call again.
   *
   * @param replicated the rename follows one a primary made: its name references arrive by
   *     replication, and the primary already refused parents and submodules
   */
  public void rename(Project.NameKey from, Project.NameKey to, boolean replicated)
      throws IOException {
    Catalog.requireValidName(to);
    requireNotSystem(from);
    if (from.equals(to)) {
      throw new PreconditionException("A project cannot be renamed to its own name");
    }
    Snapshot current = catalog.snapshot(true);
    if (!renames(current.byName().get(from), to)) {
      requireActive(current, from);
      if (!replicated) {
        preflight(from, "renamed");
      }
    }
    String operationId = UUID.randomUUID().toString();
    Snapshot prepared =
        catalog.commit(
            "Rename " + from.get() + " to " + to.get(),
            snapshot -> {
              if (renames(snapshot.byName().get(from), to)) {
                return List.of(); // This rename, prepared by another caller or finished.
              }
              requireNothingInFlight(snapshot);
              Binding active = requireActive(snapshot, from);
              Binding taken = snapshot.byName().get(to);
              if (taken != null) {
                throw new PreconditionException(to.get() + " " + describe(taken));
              }
              long now = clock.millis();
              long target = active.epoch() + 1;
              return List.of(
                  active.with(
                      State.PENDING,
                      active.epoch(),
                      new Operation(operationId, Kind.RENAME, active.epoch(), target, to, replicated),
                      to,
                      now),
                  new Binding(
                      to,
                      active.id(),
                      State.PENDING,
                      target,
                      new Operation(operationId, Kind.RENAME, active.epoch(), target, from, replicated),
                      null,
                      now));
            });
    finish(prepared, prepared.byName().get(from));
  }

  /**
   * Deletes {@code name}; the repository stays under its id, nothing is published to it again.
   *
   * @param replicated as for {@link #rename}
   */
  public void delete(Project.NameKey name, boolean replicated) throws IOException {
    requireNotSystem(name);
    Snapshot current = catalog.snapshot(true);
    if (!deletes(current.byName().get(name))) {
      requireActive(current, name);
      if (!replicated) {
        preflight(name, "deleted");
      }
    }
    String operationId = UUID.randomUUID().toString();
    Snapshot prepared =
        catalog.commit(
            "Delete " + name.get(),
            snapshot -> {
              if (deletes(snapshot.byName().get(name))) {
                return List.of(); // This deletion, prepared by another caller or finished.
              }
              requireNothingInFlight(snapshot);
              Binding active = requireActive(snapshot, name);
              return List.of(
                  active.with(
                      State.PENDING,
                      active.epoch(),
                      new Operation(
                          operationId,
                          Kind.DELETE,
                          active.epoch(),
                          active.epoch() + 1,
                          null,
                          replicated),
                      null,
                      clock.millis()));
            });
    finish(prepared, prepared.byName().get(name));
  }

  /**
   * The name the repository once called {@code name} goes by, through every rename since; empty
   * when {@code name} was not renamed away. For a caller that meets a name from before a rename.
   */
  public Optional<Project.NameKey> renamedTo(Project.NameKey name) throws IOException {
    Snapshot snapshot = catalog.snapshot(true);
    Project.NameKey current = name;
    for (Binding binding = snapshot.byName().get(current);
        binding != null && binding.retired() && binding.movedTo() != null;
        binding = snapshot.byName().get(current)) {
      current = binding.movedTo();
    }
    return current.equals(name) ? Optional.empty() : Optional.of(current);
  }

  /** Whether {@code name} was deleted; a name is never reused, so this stays true. */
  public boolean deleted(Project.NameKey name) throws IOException {
    Binding binding = catalog.snapshot(true).byName().get(name);
    return binding != null && binding.retired() && binding.movedTo() == null;
  }

  /** What names {@code name} outside its repository, rewritten or only reported. */
  public Report references(Project.NameKey name) throws IOException {
    return new Report(
        references.scanProjects(name, catalog.activeNames()), references.scanUsers(name));
  }

  /** The ids of the operations that have not finalized. */
  public Set<String> pending() throws IOException {
    return new TreeSet<>(catalog.pendingOperations().keySet());
  }

  /** Finishes a prepared operation from whatever transition it reached. */
  public void resume(String operationId) throws IOException {
    Snapshot snapshot = catalog.snapshot(true);
    for (Binding binding : pendingOf(snapshot, operationId)) {
      if (binding.operation().kind() == Kind.IMPORT) {
        throw new PreconditionException(
            "Rerun walgerrit-import to finish importing " + binding.name().get());
      }
    }
    complete(snapshot, operationId, initializer);
  }

  /** One line on what the catalog says about a name, for messages to users. */
  static String describe(Binding binding) {
    return switch (binding.state()) {
      case ACTIVE -> "exists";
      case PENDING ->
          switch (binding.operation().kind()) {
            case CREATE -> "is being created";
            case IMPORT -> "is being imported";
            case RENAME ->
                binding.pendingSource()
                    ? "is being renamed to " + binding.movedTo().get()
                    : "is being created by renaming " + binding.operation().other().get();
            case DELETE -> "is being deleted";
          };
      case RETIRED ->
          binding.movedTo() == null
              ? "was deleted; a name is never reused"
              : "was renamed to " + binding.movedTo().get() + "; a name is never reused";
    };
  }

  /**
   * Reads every configuration the operation would touch, before anything changes: a parent is
   * refused because its children would inherit from All-Projects while the name is pending; a
   * project that superprojects may subscribe to is refused because their {@code .gitmodules} would
   * keep the old name; a malformed file fails here rather than after the fence.
   */
  private void preflight(Project.NameKey name, String verb) throws IOException {
    NameReferences.Projects projects = references.scanProjects(name, catalog.activeNames());
    if (!projects.children().isEmpty()) {
      throw new PreconditionException(
          name.get()
              + " cannot be "
              + verb
              + ": it is the parent of "
              + projects.children().stream().map(Project.NameKey::get).toList()
              + "; reparent them first");
    }
    if (projects.allowsSuperprojects()) {
      throw new PreconditionException(
          name.get()
              + " cannot be "
              + verb
              + ": it, or an ancestor, allows superprojects to subscribe to it, and a"
              + " superproject's .gitmodules is never rewritten; withdraw the permission first");
    }
    NameReferences.Users users = references.scanUsers(name);
    logger.info(
        "WalGerrit {} to be {}: {} watching account(s), {} destination row(s), {} subscriber(s)",
        name.get(),
        verb,
        users.watchers(),
        users.destinationRows(),
        projects.subscribers().size());
  }

  /** Whether {@code source} is the name a rename to {@code to} moves, or moved, away from. */
  private static boolean renames(@Nullable Binding source, Project.NameKey to) {
    return source != null && to.equals(source.movedTo());
  }

  /** Whether {@code source} is being deleted, or was. */
  private static boolean deletes(@Nullable Binding source) {
    return source != null && source.operation() != null && source.operation().kind() == Kind.DELETE;
  }

  /** Runs the operation {@code source} is the source of to its end, unless it already finished. */
  private void finish(Snapshot snapshot, Binding source) throws IOException {
    if (!source.retired()) {
      complete(snapshot, source.operation().id(), initializer);
    }
  }

  private void requireNotSystem(Project.NameKey name) throws PreconditionException {
    if (systemProjects.contains(name)) {
      throw new PreconditionException(name.get() + " is a system project; Gerrit needs its name");
    }
  }

  /** Renames and deletions scan other projects' configuration, so only one runs at a time. */
  private static void requireNothingInFlight(Snapshot snapshot) throws PreconditionException {
    for (Binding binding : snapshot.byName().values()) {
      if (binding.pending()
          && (binding.operation().kind() == Kind.RENAME
              || binding.operation().kind() == Kind.DELETE)) {
        throw new PreconditionException(
            "Namespace operation "
                + binding.operation().id()
                + " is in flight ("
                + binding.name().get()
                + " "
                + describe(binding)
                + "); resume it first");
      }
    }
  }

  private static Binding requireActive(Snapshot snapshot, Project.NameKey name)
      throws PreconditionException {
    Binding binding = snapshot.byName().get(name);
    if (binding == null) {
      throw new PreconditionException("No project " + name.get());
    }
    if (!binding.active()) {
      throw new PreconditionException(name.get() + " " + describe(binding));
    }
    return binding;
  }

  private void complete(Snapshot snapshot, String operationId, Initializer initializer)
      throws IOException {
    List<Binding> pending = pendingOf(snapshot, operationId);
    if (pending.isEmpty()) {
      if (snapshot.byName().values().stream()
          .noneMatch(b -> b.operation() != null && b.operation().id().equals(operationId))) {
        throw new PreconditionException("No namespace operation " + operationId);
      }
      return; // Finalized already.
    }
    Operation operation = pending.get(0).operation();
    RepositoryId id = pending.get(0).id();
    switch (operation.kind()) {
      case CREATE, IMPORT -> initializer.initialize(id, pending.get(0).name());
      case RENAME, DELETE -> {
        storage
            .manifestStore(id)
            .fence(operation.expectedEpoch(), operationId, operation.kind() == Kind.DELETE);
        if (!operation.replicated()) {
          Binding source =
              pending.stream().filter(b -> b.pendingSource()).findFirst().orElseThrow();
          references.rewrite(
              source.name(), source.movedTo(), catalog.activeNames(), operationId);
        }
      }
    }
    catalog.commit(
        "Finalize " + operation.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + operationId,
        current -> {
          List<Binding> still = pendingOf(current, operationId);
          List<Binding> finalized = new ArrayList<>();
          long now = clock.millis();
          for (Binding binding : still) {
            Operation own = binding.operation();
            finalized.add(
                switch (operation.kind()) {
                  case CREATE, IMPORT -> binding.with(State.ACTIVE, 0, own, null, now);
                  case DELETE -> binding.with(State.RETIRED, own.targetEpoch(), own, null, now);
                  case RENAME ->
                      binding.pendingSource()
                          ? binding.with(State.RETIRED, own.targetEpoch(), own, binding.movedTo(), now)
                          : binding.with(State.ACTIVE, own.targetEpoch(), own, null, now);
                });
          }
          return finalized;
        });
    logger.info(
        "WalGerrit {} {} finalized ({})",
        operation.kind().name().toLowerCase(java.util.Locale.ROOT),
        pending.stream().map(b -> b.name().get()).toList(),
        operationId);
  }

  private static List<Binding> pendingOf(Snapshot snapshot, String operationId) {
    List<Binding> pending = new ArrayList<>();
    for (Binding binding : snapshot.byName().values()) {
      if (binding.pending()
          && binding.operation() != null
          && binding.operation().id().equals(operationId)) {
        pending.add(binding);
      }
    }
    return pending;
  }

  Map<String, List<Binding>> pendingOperations() throws IOException {
    return catalog.pendingOperations();
  }
}
