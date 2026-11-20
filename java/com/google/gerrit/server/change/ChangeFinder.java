// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.server.change;

import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.git.ObjectIds;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.config.GerritImportedServerIdsProvider;
import com.google.gerrit.server.index.change.ChangeIndexFreshness;
import com.google.gerrit.server.index.change.ChangeIndexNotFreshException;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;

@Singleton
public class ChangeFinder {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String CACHE_NAME = "changeid_project";

  public static Module module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        cache(CACHE_NAME, Change.Id.class, String.class).maximumWeight(1024);
        // The server injector can see the storage hook installed in the database injector.
        bind(StoredRefs.class);
      }
    };
  }

  public enum ChangeIdType {
    ALL,
    TRIPLET,
    NUMERIC_ID,
    I_HASH,
    PROJECT_NUMERIC_ID,
    COMMIT_HASH
  }

  /** A change whose project a caller on this thread knows; see {@link #knownProject}. */
  private record Known(Project.NameKey project, Change.Id id) {}

  private static final ThreadLocal<Known> KNOWN = new ThreadLocal<>();

  /** Ends a {@link #knownProject} scope. */
  public interface Hint extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Until closed, this thread's lookups of {@code id} by number try {@code project} first. A
   * confirmed hint needs no index or number cache. The hint grants no access; closing restores the
   * previous one.
   */
  public static Hint knownProject(Project.NameKey project, Change.Id id) {
    Known before = KNOWN.get();
    KNOWN.set(new Known(project, id));
    return () -> {
      if (before == null) {
        KNOWN.remove();
      } else {
        KNOWN.set(before);
      }
    };
  }

  /**
   * A storage backend's read of a ref as storage holds it now, by project and ref name; empty when
   * the project is inactive or lacks the ref. Bound where nodes share storage; absent where a local
   * read is the answer.
   */
  static class StoredRefs {
    @Inject(optional = true)
    @Named("authoritativeRef")
    @Nullable
    private BiFunction<String, String, Optional<String>> reader;
  }

  private final ChangeIndexFreshness freshness;
  private final StoredRefs storedRefs;
  private final IndexConfig indexConfig;
  private final boolean hasImportedChanges;
  private final Cache<Change.Id, String> changeIdProjectCache;
  private final Provider<InternalChangeQuery> queryProvider;
  private final ChangeNotes.Factory changeNotesFactory;
  private final Counter1<ChangeIdType> changeIdCounter;

  @Inject
  ChangeFinder(
      ChangeIndexFreshness freshness,
      StoredRefs storedRefs,
      IndexConfig indexConfig,
      GerritImportedServerIdsProvider gerritImportedServerIdsProvider,
      @Named(CACHE_NAME) Cache<Change.Id, String> changeIdProjectCache,
      Provider<InternalChangeQuery> queryProvider,
      ChangeNotes.Factory changeNotesFactory,
      MetricMaker metricMaker) {
    this.freshness = freshness;
    this.storedRefs = storedRefs;
    this.indexConfig = indexConfig;
    this.hasImportedChanges = gerritImportedServerIdsProvider.hasImportedServerIds();
    this.changeIdProjectCache = changeIdProjectCache;
    this.queryProvider = queryProvider;
    this.changeNotesFactory = changeNotesFactory;
    this.changeIdCounter =
        metricMaker.newCounter(
            "http/server/rest_api/change_id_type",
            new Description("Total number of API calls per identifier type.")
                .setRate()
                .setUnit("requests"),
            Field.ofEnum(ChangeIdType.class, "change_id_type", Metadata.Builder::changeIdType)
                .description("The type of the change identifier.")
                .build());
  }

  public Optional<ChangeNotes> findOne(String id) {
    // Limit the maximum number of results to just 2 items for saving CPU cycles
    // in reading change-notes.
    List<ChangeNotes> ctls = find(id, 2);
    if (ctls.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(ctls.get(0));
  }

  /**
   * Find changes matching the given identifier.
   *
   * @param id change identifier.
   * @return possibly-empty list of notes for all matching changes; may or may not be visible.
   */
  public List<ChangeNotes> find(String id) {
    return find(id, 0);
  }

  /**
   * Find at most N changes matching the given identifier.
   *
   * @param id change identifier.
   * @param queryLimit maximum number of changes to be returned
   * @return possibly-empty list of notes for all matching changes; may or may not be visible.
   */
  public List<ChangeNotes> find(String id, int queryLimit) {
    if (id.isEmpty()) {
      return Collections.emptyList();
    }

    int z = id.lastIndexOf('~');
    int y = id.lastIndexOf('~', z - 1);
    if (y < 0 && z > 0) {
      // Try project~numericChangeId
      Integer n = Ints.tryParse(id.substring(z + 1));
      if (n != null) {
        changeIdCounter.increment(ChangeIdType.PROJECT_NUMERIC_ID);
        return fromProjectNumber(id.substring(0, z), n.intValue());
      }
    }

    if (y < 0 && z < 0) {
      // Try numeric changeId
      Integer n = Ints.tryParse(id);
      if (n != null) {
        changeIdCounter.increment(ChangeIdType.NUMERIC_ID);
        return find(Change.id(n));
      }
    }

    // Use the index to search for changes, but don't return any stored fields,
    // to force rereading in case the index is stale.
    long generation = freshness.requireSearchFresh();
    InternalChangeQuery query = queryProvider.get().noFields();
    if (queryLimit > 0) {
      query.setLimit(queryLimit);
    }

    // Try commit hash
    if (id.matches("^([0-9a-fA-F]{" + ObjectIds.ABBREV_STR_LEN + "," + ObjectIds.STR_LEN + "})$")) {
      changeIdCounter.increment(ChangeIdType.COMMIT_HASH);
      return asChangeNotes(query.byCommit(id), generation);
    }

    if (y > 0 && z > 0) {
      // Try change triplet (project~branch~Ihash...)
      Optional<ChangeTriplet> triplet = ChangeTriplet.parse(id, y, z);
      if (triplet.isPresent()) {
        ChangeTriplet t = triplet.get();
        changeIdCounter.increment(ChangeIdType.TRIPLET);
        return asChangeNotes(query.byBranchKey(t.branch(), t.id()), generation);
      }
    }

    // Try isolated Ihash... format ("Change-Id: Ihash").
    List<ChangeNotes> notes = asChangeNotes(query.byKeyPrefix(id), generation);
    if (!notes.isEmpty()) {
      changeIdCounter.increment(ChangeIdType.I_HASH);
    }
    return notes;
  }

  private List<ChangeNotes> fromProjectNumber(String project, int changeNumber) {
    return read(Project.NameKey.parse(project), Change.id(changeNumber), null);
  }

  /** Reads a change from a candidate project as storage holds it now; empty if absent there. */
  private List<ChangeNotes> confirmed(Project.NameKey project, Change.Id id) {
    if (storedRefs.reader == null) {
      return read(project, id, null);
    }
    Optional<String> meta;
    try {
      meta = storedRefs.reader.apply(project.get(), RefNames.changeMetaRef(id));
    } catch (UncheckedIOException failure) {
      throw new StorageException(
          String.format("Cannot read change %s of %s from storage", id, project),
          failure.getCause());
    }
    if (meta.isEmpty()) {
      return ImmutableList.of();
    }
    return read(project, id, ObjectId.fromString(meta.get()));
  }

  private List<ChangeNotes> read(Project.NameKey project, Change.Id cId, @Nullable ObjectId meta) {
    try {
      ChangeNotes notes = changeNotesFactory.createChecked(project, cId, meta);
      if (!hasImportedChanges && changeIdProjectCache.getIfPresent(cId) == null) {
        // Without imported changes a number names one change, just read from this project.
        changeIdProjectCache.put(cId, Url.encode(notes.getProjectName().get()));
      }
      return ImmutableList.of(notes);
    } catch (NoSuchChangeException e) {
      return Collections.emptyList();
    } catch (StorageException e) {
      // Distinguish between a RepositoryNotFoundException (project argument invalid) and
      // other StorageExceptions (failure in the persistence layer).
      if (Throwables.getRootCause(e) instanceof RepositoryNotFoundException) {
        return Collections.emptyList();
      }
      throw e;
    }
  }

  public Optional<ChangeNotes> findOne(Change.Id id) {
    List<ChangeNotes> notes = find(id);
    if (notes.size() != 1) {
      return Optional.empty();
    }
    return Optional.of(notes.get(0));
  }

  /**
   * @deprecated this method is not reliable in Gerrit instances with imported changes, since
   *     multiple changes can have the same change number and make the `changeIdProjectCache` cache
   *     pointless.
   */
  @Deprecated(since = "3.10", forRemoval = true)
  public List<ChangeNotes> find(Change.Id id) {
    if (!hasImportedChanges) {
      return findUnique(id);
    }
    // Imported changes may share a number, so the cache cannot resolve it.
    // Use the index to search for changes, but don't return any stored fields,
    // to force rereading in case the index is stale.
    long generation = freshness.requireSearchFresh();
    return asChangeNotes(queryProvider.get().noFields().byChangeNumber(id), generation);
  }

  /**
   * Without imported changes, a number names at most one change. A hinted, cached or indexed
   * project must be confirmed in storage. Stale indexes may supply candidates outside a rebuild;
   * without a confirmed candidate, a fresh index is required.
   */
  private List<ChangeNotes> findUnique(Change.Id id) {
    Known known = KNOWN.get();
    if (known != null && known.id().equals(id)) {
      List<ChangeNotes> hinted = confirmed(known.project(), id);
      if (!hinted.isEmpty()) {
        return hinted;
      }
    }
    String cached = changeIdProjectCache.getIfPresent(id);
    if (cached != null) {
      List<ChangeNotes> notes = confirmed(Project.NameKey.parse(cached), id);
      if (!notes.isEmpty()) {
        return notes;
      }
      changeIdProjectCache.invalidate(id);
    }

    long candidates = freshness.requireCandidates();
    long generation = 0;
    ChangeIndexNotFreshException stale = null;
    try {
      generation = freshness.requireSearchFresh();
    } catch (ChangeIndexNotFreshException notFresh) {
      stale = notFresh;
    }
    // No stored fields, to force rereading in case the index is stale.
    List<ChangeData> r = queryProvider.get().noFields().byLegacyChangeId(id);
    freshness.checkCandidates(candidates);
    Set<Project.NameKey> projects = new LinkedHashSet<>();
    for (ChangeData cd : r) {
      projects.add(cd.project());
    }
    ImmutableList.Builder<ChangeNotes> notes = ImmutableList.builder();
    for (Project.NameKey project : projects) {
      notes.addAll(confirmed(project, id));
    }
    ImmutableList<ChangeNotes> found = notes.build();
    if (!found.isEmpty()) {
      return found;
    }
    if (stale != null) {
      throw stale;
    }
    freshness.checkSearchResult(generation);
    return ImmutableList.of();
  }

  private List<ChangeNotes> asChangeNotes(List<ChangeData> cds, long generation) {
    freshness.checkSearchResult(generation);
    List<ChangeNotes> notes = new ArrayList<>(cds.size());
    if (!indexConfig.separateChangeSubIndexes()) {
      for (ChangeData cd : cds) {
        try {
          notes.add(cd.notes());
        } catch (NoSuchChangeException e) {
          logger.atWarning().log("Change %s seen in index, but missing in NoteDb", e.getMessage());
        }
      }
      return notes;
    }

    // If an index implementation uses separate non-atomic subindexes, it's possible to temporarily
    // observe a change as present in both subindexes, if this search is concurrent with a write.
    // Dedup to avoid confusing the caller. We can choose an arbitrary ChangeData instance because
    // the index results have no stored fields, so the data is already reloaded. (It's also possible
    // that a change might appear in zero subindexes, but there's nothing we can do here to help
    // this case.)
    Set<Change.Id> seen = Sets.newHashSetWithExpectedSize(cds.size());
    for (ChangeData cd : cds) {
      if (seen.add(cd.virtualId())) {
        try {
          notes.add(cd.notes());
        } catch (NoSuchChangeException e) {
          logger.atWarning().log("Change %s seen in index, but missing in NoteDb", e.getMessage());
        }
      }
    }
    return notes;
  }
}
