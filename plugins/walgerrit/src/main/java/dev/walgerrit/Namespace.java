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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and deletes project names. A deletion is three durable transitions:
 *
 * <ol>
 *   <li><b>Prepare</b>, in the catalog: the binding becomes pending and records the operation, its
 *       expected and its target write epoch. A pending name is not served; handles opened before
 *       the prepare keep working until the fence. One deletion is in flight at a time.
 *   <li><b>Fence</b>, in the repository's manifest: the write epoch advances from the expected to
 *       the target epoch and the manifest becomes terminally deleted, which refuses every writer
 *       admitted before, on every node, at the commit point. Whatever committed before the fence
 *       stays committed. Then, unless the deletion is replicated from a primary, the typed
 *       references to the name outside the repository are removed; see {@link NameReferences}.
 *   <li><b>Finalize</b>, in the catalog: the binding is retired.
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
                        new Operation(operationId, kind, 0, 0, false),
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
   * Deletes {@code name}; the repository stays under its id, nothing is published to it again. See
   * the class comment for the protocol. Repeating a deletion that is in flight finishes it, and
   * repeating one that completed does nothing, so a caller whose reply was lost can call again.
   *
   * @param replicated the deletion follows one a primary made: its name references arrive by
   *     replication, and the primary already refused parents and submodules
   */
  public void delete(Project.NameKey name, boolean replicated) throws IOException {
    requireNotSystem(name);
    Snapshot current = catalog.snapshot(true);
    if (!deletes(current.byName().get(name))) {
      requireActive(current, name);
      if (!replicated) {
        preflight(name);
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
                          operationId, Kind.DELETE, active.epoch(), active.epoch() + 1, replicated),
                      clock.millis()));
            });
    finish(prepared, prepared.byName().get(name));
  }

  /** Whether {@code name} was deleted; a name is never reused, so this stays true. */
  public boolean deleted(Project.NameKey name) throws IOException {
    Binding binding = catalog.snapshot(true).byName().get(name);
    return binding != null && binding.retired() && deletes(binding);
  }

  /** What names {@code name} outside its repository, removed by a deletion or only reported. */
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
            case DELETE -> "is being deleted";
            case UNSUPPORTED -> "is held by " + unsupported(binding.operation());
          };
      case RETIRED ->
          deletes(binding)
              ? "was deleted; a name is never reused"
              : "is retired; a name is never reused";
    };
  }

  private static String unsupported(Operation operation) {
    return "an operation of kind '"
        + operation.kindName()
        + "', which this version of WalGerrit does not run";
  }

  /**
   * Reads every configuration the deletion would touch, before anything changes: a parent is
   * refused because its children would inherit from All-Projects instead; a project that
   * superprojects may subscribe to is refused because their {@code .gitmodules} would keep naming
   * it; a malformed file fails here rather than after the fence.
   */
  private void preflight(Project.NameKey name) throws IOException {
    NameReferences.Projects projects = references.scanProjects(name, catalog.activeNames());
    if (!projects.children().isEmpty()) {
      throw new PreconditionException(
          name.get()
              + " cannot be deleted: it is the parent of "
              + projects.children().stream().map(Project.NameKey::get).toList()
              + "; reparent them first");
    }
    if (projects.allowsSuperprojects()) {
      throw new PreconditionException(
          name.get()
              + " cannot be deleted: it, or an ancestor, allows superprojects to subscribe to it,"
              + " and a superproject's .gitmodules is never rewritten; withdraw the permission"
              + " first");
    }
    NameReferences.Users users = references.scanUsers(name);
    logger.info(
        "WalGerrit {} to be deleted: {} watching account(s), {} destination row(s), {}"
            + " subscriber(s)",
        name.get(),
        users.watchers(),
        users.destinationRows(),
        projects.subscribers().size());
  }

  /** Whether {@code binding} is being deleted, or was. */
  private static boolean deletes(@Nullable Binding binding) {
    return binding != null
        && binding.operation() != null
        && binding.operation().kind() == Kind.DELETE;
  }

  private void finish(Snapshot snapshot, Binding binding) throws IOException {
    if (!binding.retired()) {
      complete(snapshot, binding.operation().id(), initializer);
    }
  }

  private void requireNotSystem(Project.NameKey name) throws PreconditionException {
    if (systemProjects.contains(name)) {
      throw new PreconditionException(name.get() + " is a system project; Gerrit needs its name");
    }
  }

  /**
   * Deletions scan other projects' configuration, so only one runs at a time; an operation of a
   * kind this version does not run may do the same.
   */
  private static void requireNothingInFlight(Snapshot snapshot) throws PreconditionException {
    for (Binding binding : snapshot.byName().values()) {
      if (!binding.pending()) {
        continue;
      }
      Kind kind = binding.operation().kind();
      if (kind == Kind.DELETE || kind == Kind.UNSUPPORTED) {
        throw new PreconditionException(
            "Namespace operation "
                + binding.operation().id()
                + " is in flight ("
                + binding.name().get()
                + " "
                + describe(binding)
                + (kind == Kind.DELETE ? "); resume it first" : "); it has to finish first"));
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
    Binding binding = pending.get(0);
    Operation operation = binding.operation();
    boolean deletion = operation.kind() == Kind.DELETE;
    switch (operation.kind()) {
      case CREATE, IMPORT -> initializer.initialize(binding.id(), binding.name());
      case DELETE -> {
        storage.manifestStore(binding.id()).fence(operation.expectedEpoch(), operationId, true);
        if (!operation.replicated()) {
          references.remove(binding.name(), catalog.activeNames(), operationId);
        }
      }
      case UNSUPPORTED ->
          throw new PreconditionException(
              "Namespace operation "
                  + operationId
                  + " is "
                  + unsupported(operation)
                  + "; finish it with a version that does");
    }
    catalog.commit(
        "Finalize " + operation.kindName() + " " + operationId,
        current -> {
          long now = clock.millis();
          List<Binding> finalized = new ArrayList<>();
          for (Binding still : pendingOf(current, operationId)) {
            finalized.add(
                deletion
                    ? still.with(State.RETIRED, operation.targetEpoch(), still.operation(), now)
                    : still.with(State.ACTIVE, 0, still.operation(), now));
          }
          return finalized;
        });
    logger.info(
        "WalGerrit {} of {} finalized ({})",
        operation.kindName(),
        binding.name().get(),
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
