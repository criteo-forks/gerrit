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
 * Creates, renames and deletes project names. One protocol, three durable transitions:
 *
 * <ol>
 *   <li><b>Prepare</b>, in the catalog: the bindings the operation touches become pending and
 *       record the operation, its expected and its target write epoch. A pending name is not
 *       served; handles opened before the prepare keep working until the fence.
 *   <li><b>Fence</b>, in the repository's manifest: the write epoch advances from the expected to
 *       the target epoch, which refuses every writer admitted before, on every node, at the commit
 *       point. For a deletion the manifest also becomes terminally deleted. Whatever committed
 *       before the fence stays committed.
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

  private final Catalog catalog;
  private final StorageLayout storage;
  private final Initializer initializer;
  private final Set<Project.NameKey> systemProjects;
  private final Clock clock;

  Namespace(
      Catalog catalog,
      StorageLayout storage,
      Initializer initializer,
      Set<Project.NameKey> systemProjects,
      Clock clock) {
    this.catalog = catalog;
    this.storage = storage;
    this.initializer = initializer;
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
                        new Operation(operationId, kind, 0, 0, null),
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

  /** Renames {@code from} to {@code to}; see the class comment for the protocol. */
  public void rename(Project.NameKey from, Project.NameKey to) throws IOException {
    Catalog.requireValidName(to);
    requireNotSystem(from);
    if (from.equals(to)) {
      throw new PreconditionException("A project cannot be renamed to its own name");
    }
    String operationId = UUID.randomUUID().toString();
    Snapshot prepared =
        catalog.commit(
            "Rename " + from.get() + " to " + to.get(),
            snapshot -> {
              Binding source = requireActive(snapshot, from);
              Binding taken = snapshot.byName().get(to);
              if (taken != null) {
                throw new PreconditionException(to.get() + " " + describe(taken));
              }
              long now = clock.millis();
              long target = source.epoch() + 1;
              return List.of(
                  source.with(
                      State.PENDING,
                      source.epoch(),
                      new Operation(operationId, Kind.RENAME, source.epoch(), target, to),
                      to,
                      now),
                  new Binding(
                      to,
                      source.id(),
                      State.PENDING,
                      target,
                      new Operation(operationId, Kind.RENAME, source.epoch(), target, from),
                      null,
                      now));
            });
    complete(prepared, operationId, initializer);
  }

  /** Deletes {@code name}; the repository stays under its id, nothing is published to it again. */
  public void delete(Project.NameKey name) throws IOException {
    requireNotSystem(name);
    String operationId = UUID.randomUUID().toString();
    Snapshot prepared =
        catalog.commit(
            "Delete " + name.get(),
            snapshot -> {
              Binding source = requireActive(snapshot, name);
              return List.of(
                  source.with(
                      State.PENDING,
                      source.epoch(),
                      new Operation(
                          operationId, Kind.DELETE, source.epoch(), source.epoch() + 1, null),
                      null,
                      clock.millis()));
            });
    complete(prepared, operationId, initializer);
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

  private void requireNotSystem(Project.NameKey name) throws PreconditionException {
    if (systemProjects.contains(name)) {
      throw new PreconditionException(name.get() + " is a system project; Gerrit needs its name");
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
      case RENAME, DELETE ->
          storage
              .manifestStore(id)
              .fence(operation.expectedEpoch(), operationId, operation.kind() == Kind.DELETE);
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
