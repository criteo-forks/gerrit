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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where Gerrit keeps a project's name outside the project's own repository, and what a rename or a
 * deletion does about it.
 *
 * <p>Typed references are rewritten: the {@code [project "name"]} sections of every account's
 * {@code watch.config} and the project column of its {@code destinations/*} files in All-Users, and
 * the {@code [allowSuperproject "name"]} sections of the projects that let the name subscribe to
 * them. Account refs are changed a few hundred per ref transaction, each project in one, all with
 * expected old tips, so every node's tailer evicts and reindexes the accounts and projects as for
 * any write. Text that may mention a name (watch filters, named queries, plugin sections,
 * dashboards, {@code .gitmodules}) is never rewritten; the literal occurrences in All-Projects'
 * {@code project.config} and in accounts' watch filters and named queries are reported.
 *
 * <p>Children are not rewritten: a parent is refused, because its children would inherit from
 * All-Projects while the name is pending.
 */
final class NameReferences {
  private static final Logger logger = LoggerFactory.getLogger(NameReferences.class);
  private static final Pattern ACCOUNT_REF = Pattern.compile("^refs/users/\\d\\d/\\d+$");
  private static final String WATCH_CONFIG = "watch.config";
  private static final String QUERIES = "queries";
  private static final String DESTINATIONS = "destinations/";
  private static final String PROJECT_CONFIG = "project.config";
  private static final String META_CONFIG = "refs/meta/config";
  private static final int BATCH = 500;
  private static final int MAX_ATTEMPTS = 8;

  interface Opener {
    Repository open(Project.NameKey name) throws IOException;
  }

  /**
   * What other projects' configuration says about a name. {@code allowsSuperprojects} is the
   * subscription-permission guard: the project, or an ancestor it inherits the permission from, lets
   * superprojects subscribe to it, so some {@code .gitmodules} may name it. It is not proof that one
   * does.
   */
  record Projects(
      NavigableSet<Project.NameKey> children,
      NavigableSet<Project.NameKey> subscribers,
      boolean allowsSuperprojects,
      List<String> textual) {}

  /** What All-Users says about a name. */
  record Users(int watchers, int destinationRows, List<String> textual) {}

  private final Opener opener;
  private final Project.NameKey allProjects;
  private final Project.NameKey allUsers;
  private final Clock clock;

  NameReferences(Opener opener, Project.NameKey allProjects, Project.NameKey allUsers, Clock clock) {
    this.opener = opener;
    this.allProjects = allProjects;
    this.allUsers = allUsers;
    this.clock = clock;
  }

  /**
   * Reads every active project's {@code project.config} once: the children of {@code name}, the
   * projects that allow {@code name} as a superproject, whether {@code name} or an ancestor allows
   * superprojects, and the lines of All-Projects' configuration that mention the name without being
   * one of those fields. Every file is parsed as Git configuration, so a malformed one fails here,
   * before anything is changed.
   */
  Projects scanProjects(Project.NameKey name, Collection<Project.NameKey> activeNames)
      throws IOException {
    NavigableSet<Project.NameKey> children = new TreeSet<>();
    NavigableSet<Project.NameKey> subscribers = new TreeSet<>();
    Map<Project.NameKey, String> parents = new LinkedHashMap<>();
    NavigableSet<Project.NameKey> allowing = new TreeSet<>();
    List<String> textual = new ArrayList<>();
    for (Project.NameKey project : activeNames) {
      try (Repository repository = opener.open(project)) {
        byte[] blob = blob(repository, META_CONFIG, PROJECT_CONFIG);
        if (blob == null) {
          continue;
        }
        Config config = parse(blob, project.get() + " " + META_CONFIG);
        parents.put(project, config.getString("access", null, "inheritFrom"));
        if (!config.getSubsections("allowSuperproject").isEmpty()) {
          allowing.add(project);
        }
        if (project.equals(name)) {
          continue;
        }
        if (name.get().equals(parents.get(project))) {
          children.add(project);
        }
        if (config.getSubsections("allowSuperproject").contains(name.get())) {
          subscribers.add(project);
        }
        if (project.equals(allProjects)) {
          for (String line : new String(blob, StandardCharsets.UTF_8).split("\n")) {
            if (line.contains(name.get())
                && !line.contains("inheritFrom")
                && !line.contains("allowSuperproject")) {
              textual.add(project.get() + " project.config: " + line.strip());
            }
          }
        }
      }
    }
    return new Projects(children, subscribers, inherits(name, allowing, parents), textual);
  }

  /**
   * Whether {@code name} or an ancestor is in {@code allowing}, walking parents as Gerrit's
   * project hierarchy does: a parent that does not exist, or was already visited, is All-Projects.
   */
  private boolean inherits(
      Project.NameKey name, Set<Project.NameKey> allowing, Map<Project.NameKey, String> parents) {
    Set<Project.NameKey> visited = new HashSet<>();
    for (Project.NameKey ancestor = name; visited.add(ancestor); ) {
      if (allowing.contains(ancestor)) {
        return true;
      }
      String parent = parents.get(ancestor);
      Project.NameKey next = parent == null ? allProjects : Project.nameKey(parent);
      ancestor = parents.containsKey(next) && !visited.contains(next) ? next : allProjects;
    }
    return false;
  }

  /** Reads every account's files in All-Users once. */
  Users scanUsers(Project.NameKey name) throws IOException {
    int watchers = 0;
    int destinationRows = 0;
    List<String> textual = new ArrayList<>();
    Repository users = openAllUsers();
    if (users == null) {
      return new Users(0, 0, textual);
    }
    try (Repository repository = users;
        ObjectReader reader = repository.newObjectReader();
        RevWalk walk = new RevWalk(reader)) {
      for (Ref ref : accountRefs(repository)) {
        RevCommit tip = walk.parseCommit(ref.getObjectId());
        byte[] watches = blob(reader, tip, WATCH_CONFIG);
        if (watches != null) {
          Config config = parse(watches, ref.getName());
          if (config.getSubsections("project").contains(name.get())) {
            watchers++;
          }
          for (String project : config.getSubsections("project")) {
            for (String notify : config.getStringList("project", project, "notify")) {
              if (notify.contains(name.get())) {
                textual.add(ref.getName() + " watch.config: [project \"" + project + "\"] notify = " + notify);
              }
            }
          }
        }
        for (byte[] destination : blobsUnder(reader, tip, DESTINATIONS).values()) {
          for (String line : new String(destination, StandardCharsets.UTF_8).split("\n")) {
            if (projectColumn(line).equals(name.get())) {
              destinationRows++;
            }
          }
        }
        byte[] queries = blob(reader, tip, QUERIES);
        if (queries != null) {
          for (String line : new String(queries, StandardCharsets.UTF_8).split("\n")) {
            if (line.contains(name.get())) {
              textual.add(ref.getName() + " queries: " + line.strip());
            }
          }
        }
      }
    }
    return new Users(watchers, destinationRows, textual);
  }

  /**
   * Rewrites every typed reference to {@code from} into {@code to}, or removes it when {@code to}
   * is null. Idempotent: a second run finds nothing to change.
   */
  void rewrite(
      Project.NameKey from,
      @Nullable Project.NameKey to,
      Collection<Project.NameKey> activeNames,
      String operationId)
      throws IOException {
    String message =
        (to == null ? "Delete " + from.get() : "Rename " + from.get() + " to " + to.get())
            + "\n\nwalgerrit-namespace operation "
            + operationId
            + "\n";
    int accounts = rewriteAccounts(from, to, message);
    int projects = 0;
    for (Project.NameKey project : activeNames) {
      try (Repository repository = opener.open(project)) {
        if (rewriteProjectConfig(repository, project, from, to, message)) {
          projects++;
        }
      }
    }
    logger.info(
        "WalGerrit rewrote references to {} in {} account(s) and {} project(s) ({})",
        from.get(),
        accounts,
        projects,
        operationId);
  }

  private int rewriteAccounts(Project.NameKey from, @Nullable Project.NameKey to, String message)
      throws IOException {
    int rewritten = 0;
    Repository users = openAllUsers();
    if (users == null) {
      return 0;
    }
    try (Repository repository = users) {
      List<Ref> remaining = accountRefs(repository);
      for (int attempt = 1; !remaining.isEmpty(); attempt++) {
        List<ReceiveCommand> commands = new ArrayList<>();
        try (ObjectReader reader = repository.newObjectReader();
            ObjectInserter inserter = repository.newObjectInserter();
            RevWalk walk = new RevWalk(reader)) {
          for (Ref ref : remaining) {
            RevCommit tip = walk.parseCommit(ref.getObjectId());
            Map<String, byte[]> changes = new LinkedHashMap<>();
            byte[] watches = blob(reader, tip, WATCH_CONFIG);
            if (watches != null) {
              byte[] changed = rewriteWatches(parse(watches, ref.getName()), from, to);
              if (changed != null) {
                changes.put(WATCH_CONFIG, changed);
              }
            }
            for (Map.Entry<String, byte[]> destination :
                blobsUnder(reader, tip, DESTINATIONS).entrySet()) {
              byte[] changed = rewriteDestinations(destination.getValue(), from, to);
              if (changed != null) {
                changes.put(destination.getKey(), changed);
              }
            }
            if (!changes.isEmpty()) {
              ObjectId commit = commit(reader, inserter, tip, changes, message);
              commands.add(new ReceiveCommand(tip, commit, ref.getName()));
            }
          }
          inserter.flush();
        }
        if (commands.isEmpty()) {
          break;
        }
        List<Ref> retry = new ArrayList<>();
        for (int start = 0; start < commands.size(); start += BATCH) {
          List<ReceiveCommand> chunk = commands.subList(start, Math.min(start + BATCH, commands.size()));
          BatchRefUpdate batch = repository.getRefDatabase().newBatchUpdate();
          batch.setAtomic(true);
          batch.setRefLogMessage(message.lines().findFirst().orElse(""), false);
          batch.addCommand(chunk);
          try (RevWalk walk = new RevWalk(repository)) {
            batch.execute(walk, NullProgressMonitor.INSTANCE);
          }
          for (ReceiveCommand command : chunk) {
            switch (command.getResult()) {
              case OK -> rewritten++;
              case LOCK_FAILURE, REJECTED_OTHER_REASON -> {
                // The ref moved under the transaction; one deleted meanwhile has nothing left.
                Ref current = repository.exactRef(command.getRefName());
                if (current != null) {
                  retry.add(current);
                }
              }
              default ->
                  throw new IOException(
                      "Rewriting " + command.getRefName() + " in " + allUsers.get() + " ended in "
                          + command.getResult() + " " + command.getMessage());
            }
          }
        }
        if (retry.isEmpty()) {
          break;
        }
        if (attempt >= MAX_ATTEMPTS) {
          throw new IOException(
              "Rewriting " + retry.size() + " account ref(s) in " + allUsers.get() + " lost " + attempt
                  + " races in a row");
        }
        remaining = retry;
      }
    }
    return rewritten;
  }

  private boolean rewriteProjectConfig(
      Repository repository, Project.NameKey project, Project.NameKey from, @Nullable Project.NameKey to, String message)
      throws IOException {
    for (int attempt = 1; ; attempt++) {
      Ref ref = repository.exactRef(META_CONFIG);
      if (ref == null) {
        return false;
      }
      ObjectId commitId;
      try (ObjectReader reader = repository.newObjectReader();
          ObjectInserter inserter = repository.newObjectInserter();
          RevWalk walk = new RevWalk(reader)) {
        RevCommit tip = walk.parseCommit(ref.getObjectId());
        byte[] blob = blob(reader, tip, PROJECT_CONFIG);
        if (blob == null) {
          return false;
        }
        Config config = parse(blob, project.get() + " " + META_CONFIG);
        if (!config.getSubsections("allowSuperproject").contains(from.get())) {
          return false;
        }
        if (to != null) {
          for (String key : config.getNames("allowSuperproject", from.get())) {
            config.setStringList(
                "allowSuperproject",
                to.get(),
                key,
                List.of(config.getStringList("allowSuperproject", from.get(), key)));
          }
        }
        config.unsetSection("allowSuperproject", from.get());
        commitId =
            commit(
                reader,
                inserter,
                tip,
                Map.of(PROJECT_CONFIG, config.toText().getBytes(StandardCharsets.UTF_8)),
                message);
        inserter.flush();
      }
      RefUpdate update = repository.updateRef(META_CONFIG);
      update.setExpectedOldObjectId(ref.getObjectId());
      update.setNewObjectId(commitId);
      update.setRefLogMessage(message.lines().findFirst().orElse(""), false);
      RefUpdate.Result result = update.update();
      switch (result) {
        case FAST_FORWARD, NEW -> {
          return true;
        }
        case LOCK_FAILURE, REJECTED -> {
          if (attempt >= MAX_ATTEMPTS) {
            throw new IOException(
                "Rewriting " + META_CONFIG + " of " + project.get() + " lost " + attempt + " races in a row");
          }
        }
        default ->
            throw new IOException("Rewriting " + META_CONFIG + " of " + project.get() + " ended in " + result);
      }
    }
  }

  /** The watch file with {@code from} moved to {@code to} (notify values unioned) or removed; null when untouched. */
  @Nullable
  static byte[] rewriteWatches(Config config, Project.NameKey from, @Nullable Project.NameKey to) {
    if (!config.getSubsections("project").contains(from.get())) {
      return null;
    }
    if (to != null) {
      List<String> merged = new ArrayList<>(List.of(config.getStringList("project", to.get(), "notify")));
      for (String notify : config.getStringList("project", from.get(), "notify")) {
        if (!merged.contains(notify)) {
          merged.add(notify);
        }
      }
      config.setStringList("project", to.get(), "notify", merged);
    }
    config.unsetSection("project", from.get());
    return config.toText().getBytes(StandardCharsets.UTF_8);
  }

  /** A destination file with rows of {@code from} moved to {@code to} or dropped; null when untouched. */
  @Nullable
  static byte[] rewriteDestinations(byte[] bytes, Project.NameKey from, @Nullable Project.NameKey to) {
    List<String> lines = new ArrayList<>();
    boolean changed = false;
    // Splitting keeps a final line break as an empty last line, so joining restores it.
    for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
      if (projectColumn(line).equals(from.get())) {
        changed = true;
        if (to == null) {
          continue;
        }
        line = line.substring(0, line.indexOf('\t') + 1) + to.get();
      }
      lines.add(line);
    }
    return changed ? String.join("\n", lines).getBytes(StandardCharsets.UTF_8) : null;
  }

  /** The project column of a destination row: text after the tab of a line that is not a comment. */
  private static String projectColumn(String line) {
    int tab = line.indexOf('\t');
    if (line.isBlank() || line.startsWith("#") || tab < 0) {
      return "";
    }
    return line.substring(tab + 1).strip();
  }

  /** All-Users, or null before Gerrit initialized the site: then no account references anything. */
  @Nullable
  private Repository openAllUsers() throws IOException {
    try {
      return opener.open(allUsers);
    } catch (RepositoryNotFoundException absent) {
      return null;
    }
  }

  private static List<Ref> accountRefs(Repository repository) throws IOException {
    List<Ref> refs = new ArrayList<>();
    for (Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/users/")) {
      if (ACCOUNT_REF.matcher(ref.getName()).matches()) {
        refs.add(ref);
      }
    }
    return refs;
  }

  private ObjectId commit(
      ObjectReader reader, ObjectInserter inserter, RevCommit parent, Map<String, byte[]> files, String message)
      throws IOException {
    DirCache index = DirCache.newInCore();
    DirCacheBuilder builder = index.builder();
    builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, parent.getTree());
    builder.finish();
    DirCacheEditor editor = index.editor();
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, file.getValue());
      editor.add(
          new DirCacheEditor.PathEdit(file.getKey()) {
            @Override
            public void apply(DirCacheEntry entry) {
              entry.setFileMode(FileMode.REGULAR_FILE);
              entry.setObjectId(blob);
            }
          });
    }
    editor.finish();
    PersonIdent author =
        new PersonIdent("WalGerrit", "walgerrit@" + ManifestStore.writerHost(), clock.instant(), ZoneOffset.UTC);
    CommitBuilder commit = new CommitBuilder();
    commit.setTreeId(index.writeTree(inserter));
    commit.setParentId(parent);
    commit.setAuthor(author);
    commit.setCommitter(author);
    commit.setMessage(message);
    return inserter.insert(commit);
  }

  @Nullable
  private static byte[] blob(Repository repository, String refName, String path) throws IOException {
    Ref ref = repository.exactRef(refName);
    if (ref == null) {
      return null;
    }
    try (ObjectReader reader = repository.newObjectReader();
        RevWalk walk = new RevWalk(reader)) {
      return blob(reader, walk.parseCommit(ref.getObjectId()), path);
    }
  }

  @Nullable
  private static byte[] blob(ObjectReader reader, RevCommit commit, String path) throws IOException {
    try (TreeWalk walk = TreeWalk.forPath(reader, path, commit.getTree())) {
      if (walk == null || walk.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) {
        return null;
      }
      return reader.open(walk.getObjectId(0)).getCachedBytes();
    }
  }

  private static Map<String, byte[]> blobsUnder(ObjectReader reader, RevCommit commit, String directory)
      throws IOException {
    Map<String, byte[]> blobs = new LinkedHashMap<>();
    try (TreeWalk walk = new TreeWalk(reader)) {
      walk.addTree(commit.getTree());
      walk.setRecursive(true);
      while (walk.next()) {
        String path = walk.getPathString();
        if (path.startsWith(directory) && walk.getFileMode(0).getObjectType() == Constants.OBJ_BLOB) {
          blobs.put(path, reader.open(walk.getObjectId(0)).getCachedBytes());
        }
      }
    }
    return blobs;
  }

  private static Config parse(byte[] blob, String where) throws IOException {
    Config config = new Config();
    try {
      config.fromText(new String(blob, StandardCharsets.UTF_8));
    } catch (ConfigInvalidException invalid) {
      throw new IOException("Unreadable configuration in " + where, invalid);
    }
    return config;
  }
}
