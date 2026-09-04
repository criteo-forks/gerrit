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
import com.google.gerrit.server.git.GitRepositoryManager;
import dev.walgerrit.proto.StorageProto.Manifest;
import dev.walgerrit.proto.StorageProto.PackRef;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory;
import org.eclipse.jgit.internal.storage.file.Pack;
import org.eclipse.jgit.internal.storage.file.PackFile;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.internal.storage.reftable.ReftableWriter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Imports a tree of bare repositories, as {@code gerrit.basePath} lays them out, into WalGerrit.
 *
 * <p>Nothing in the source is rewritten. Each repository's existing pack files are uploaded as they
 * are, so it must hold no loose objects: either the operator repacks a scratch copy of the backup
 * beforehand ({@code git repack -a -d}, then {@code git fsck --connectivity-only}), or the importer
 * is given {@code --stage DIR} and does exactly that itself, one repository at a time, on a copy it
 * makes under that directory and removes afterwards. Staging needs scratch space for the largest
 * repository times the number of parallel imports rather than for the whole site, and lets the
 * source be a read-only mount. Without staging, a repository that still has loose objects is
 * refused with that instruction. Staging leaves git's derived indexes, the commit-graph and the
 * multi-pack-index, behind, since a backup of a serving repository routinely holds stale ones, and
 * refuses a repository whose refs point at objects the backup lacks unless {@code
 * --prune-dangling-refs} says to drop those refs from the copy. Its refs, including
 * {@code HEAD} and every {@code refs/changes}, {@code refs/meta}, {@code refs/users} and {@code
 * refs/groups} ref Gerrit needs, become one reftable, and one manifest publication makes the whole
 * repository visible at once. Reflogs are not carried over.
 *
 * <p>Every step is idempotent, so the importer is resumable by rerunning it: an uploaded file is
 * recognised by its name and content, a published repository is recognised by its manifest and only
 * verified, and a repository whose manifest exists but is empty, because a run died between
 * creating it and publishing, is published again. The verification compares every source ref with
 * what a WalGerrit handle serves; {@code --verify-closure} additionally walks every object the refs
 * reach through WalGerrit, which reads every pack back.
 */
public final class RepositoryImporter {
  private static final String USAGE =
      """
      Usage: walgerrit-import -d SITE --source DIR [--stage DIR] [--prune-dangling-refs]
                              [--project NAME]... [--threads N] [--verify-closure]

        --source DIR       directory of bare repositories laid out like gerrit.basePath
        --stage DIR        copy each repository here, repack, prune and fsck it with git, import
                           the copy and delete it; needs room for the largest repository times
                           --threads, and git on the PATH (default: import the source as it is)
        --prune-dangling-refs
                           with --stage, delete refs that point at objects the source lacks from
                           the copy, and list them, instead of failing the repository
        --project NAME     import only this project (repeatable; default: every repository)
        --threads N        repositories imported in parallel (default 4)
        --verify-closure   after publishing, walk every reachable object through WalGerrit
        --help             this text

      Without --stage, repositories must be repacked first (git repack -a -d) so that they hold no
      loose objects. Set gerrit.serverId to the source server's id before importing NoteDb data.
      """;

  /** What happened to one repository. */
  public enum Outcome {
    IMPORTED,
    ALREADY_IMPORTED,
    FAILED
  }

  /** The importer's summary. */
  public record Report(int imported, int alreadyImported, int failed, List<String> failures) {
    public boolean ok() {
      return failed == 0;
    }
  }

  private final WalGitRepositoryManager repositories;
  private final PrintStream out;
  private final boolean verifyClosure;
  private final Path stage;
  private final boolean pruneDanglingRefs;

  RepositoryImporter(WalGitRepositoryManager repositories, PrintStream out, boolean verifyClosure) {
    this(repositories, out, verifyClosure, null, false);
  }

  RepositoryImporter(
      WalGitRepositoryManager repositories, PrintStream out, boolean verifyClosure, Path stage) {
    this(repositories, out, verifyClosure, stage, false);
  }

  RepositoryImporter(
      WalGitRepositoryManager repositories,
      PrintStream out,
      boolean verifyClosure,
      Path stage,
      boolean pruneDanglingRefs) {
    this.repositories = repositories;
    this.out = out;
    this.verifyClosure = verifyClosure;
    this.stage = stage;
    this.pruneDanglingRefs = pruneDanglingRefs;
  }

  /** Command-line entry point used by Gerrit's {@code walgerrit-import} program. */
  public static int run(GitRepositoryManager manager, String[] args) throws Exception {
    Path source = null;
    Path stage = null;
    Set<String> only = new HashSet<>();
    int threads = 4;
    boolean verifyClosure = false;
    boolean pruneDanglingRefs = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--source" -> source = Path.of(requireValue(args, ++i));
        case "--stage" -> stage = Path.of(requireValue(args, ++i));
        case "--prune-dangling-refs" -> pruneDanglingRefs = true;
        case "--project" -> only.add(requireValue(args, ++i));
        case "--threads" -> threads = Integer.parseInt(requireValue(args, ++i));
        case "--verify-closure" -> verifyClosure = true;
        case "--help", "-h" -> {
          System.out.print(USAGE);
          return 0;
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i] + "\n" + USAGE);
      }
    }
    if (source == null) {
      throw new IllegalArgumentException("--source is required\n" + USAGE);
    }
    if (!(manager instanceof WalGitRepositoryManager walGit)) {
      throw new IllegalStateException(
          "gerrit.installDbModule must install dev.walgerrit.WalGitModule; found "
              + manager.getClass().getName());
    }
    Report report =
        new RepositoryImporter(walGit, System.out, verifyClosure, stage, pruneDanglingRefs)
            .importAll(source, only, threads);
    System.out.printf(
        Locale.ROOT,
        "Imported %d repositories, %d were already imported, %d failed%n",
        report.imported(),
        report.alreadyImported(),
        report.failed());
    return report.ok() ? 0 : 1;
  }

  private static String requireValue(String[] args, int index) {
    if (index >= args.length) {
      throw new IllegalArgumentException(args[index - 1] + " needs a value\n" + USAGE);
    }
    return args[index];
  }

  /** Imports every bare repository below {@code source}, or only those named in {@code only}. */
  public Report importAll(Path source, Set<String> only, int threads) throws IOException {
    Map<String, Path> discovered = discover(source);
    if (!only.isEmpty()) {
      discovered.keySet().retainAll(only);
      for (String name : only) {
        if (!discovered.containsKey(name)) {
          throw new IOException("No bare repository for project " + name + " below " + source);
        }
      }
    }
    out.printf(Locale.ROOT, "Importing %d repositories from %s%n", discovered.size(), source);
    AtomicInteger imported = new AtomicInteger();
    AtomicInteger already = new AtomicInteger();
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (Map.Entry<String, Path> entry : discovered.entrySet()) {
        futures.add(
            pool.submit(
                () -> {
                  String name = entry.getKey();
                  try {
                    switch (importOne(name, entry.getValue())) {
                      case IMPORTED -> imported.incrementAndGet();
                      case ALREADY_IMPORTED -> already.incrementAndGet();
                      case FAILED -> failures.add(name);
                    }
                  } catch (IOException | RuntimeException exception) {
                    failures.add(name);
                    out.printf(Locale.ROOT, "FAILED  %s: %s%n", name, exception);
                  }
                }));
      }
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
          throw new IOException(failure.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }
    return new Report(imported.get(), already.get(), failures.size(), List.copyOf(failures));
  }

  /** Bare repositories below {@code root}, keyed by project name, the way Gerrit lays them out. */
  static Map<String, Path> discover(Path root) throws IOException {
    Map<String, Path> found = new TreeMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : (Iterable<Path>) paths::iterator) {
        if (Files.isDirectory(path)
            && path.getFileName() != null
            && path.getFileName().toString().endsWith(Constants.DOT_GIT_EXT)
            && Files.isDirectory(path.resolve(Constants.OBJECTS))) {
          String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
          found.put(relative.substring(0, relative.length() - Constants.DOT_GIT_EXT.length()), path);
        }
      }
    }
    return found;
  }

  /** Imports one repository; see the class comment for what is checked and what is skipped. */
  public Outcome importOne(String projectName, Path bareDirectory) throws IOException {
    Project.NameKey project = Project.nameKey(projectName);
    ManifestStore store = repositories.storage().manifestStore(project);
    boolean created = store.create();
    if (!created) {
      Manifest existing = store.refresh();
      if (existing.getPacksCount() > 0 || existing.getRevision() > 0) {
        try (Repository original = open(bareDirectory)) {
          verify(project, sourceRefs(original));
        }
        out.printf(Locale.ROOT, "skipped %s: already imported and verified%n", projectName);
        return Outcome.ALREADY_IMPORTED;
      }
      // Created by an earlier run that died before publishing; publish now.
    }
    if (stage == null) {
      return publishFrom(project, bareDirectory, store);
    }
    Path copy = stage.resolve(projectName + Constants.DOT_GIT_EXT);
    try {
      stageCopy(projectName, bareDirectory, copy);
      return publishFrom(project, copy, store);
    } finally {
      deleteRecursively(copy);
    }
  }

  private Outcome publishFrom(Project.NameKey project, Path bareDirectory, ManifestStore store)
      throws IOException {
    String projectName = project.get();
    try (FileRepository source = open(bareDirectory)) {
      Map<String, Ref> refs = sourceRefs(source);
      refuseLooseObjects(source, projectName);

      List<PackRef> additions = new ArrayList<>();
      long bytes = 0;
      for (Pack pack : source.getObjectDatabase().getPacks()) {
        PackFile packFile = pack.getPackFile();
        PackRef.Builder ref =
            PackRef.newBuilder()
                .setName(stripExtension(packFile.getName()))
                .setSource("COMPACT")
                .setLastModifiedEpochMillis(packFile.lastModified())
                .setObjectCount(pack.getIndex().getObjectCount())
                .setPackChecksum(LocalWalGitObjectDatabase.readPackChecksum(packFile.toPath()));
        for (PackExt extension :
            List.of(PackExt.PACK, PackExt.INDEX, PackExt.BITMAP_INDEX, PackExt.REVERSE_INDEX)) {
          File file = packFile.create(extension);
          if (!file.isFile()) {
            continue;
          }
          String fileName = ref.getName() + "." + extension.getExtension();
          store.publishExternalFile(fileName, file.toPath());
          bytes += file.length();
          ref.addFiles(
              dev.walgerrit.proto.StorageProto.PackFile.newBuilder()
                  .setExtension(extension.getExtension())
                  .setSize(file.length()));
        }
        additions.add(ref.build());
      }

      Path reftable = Files.createTempFile("walgerrit-import-", ".ref");
      try {
        long refCount = writeReftable(refs, reftable);
        String reftableName = "pack-import-" + refsDigest(refs);
        store.publishExternalFile(reftableName + ".ref", reftable);
        additions.add(
            PackRef.newBuilder()
                .setName(reftableName)
                .setSource("COMPACT")
                .setLastModifiedEpochMillis(System.currentTimeMillis())
                .setMinUpdateIndex(1)
                .setMaxUpdateIndex(1)
                .addFiles(
                    dev.walgerrit.proto.StorageProto.PackFile.newBuilder()
                        .setExtension(PackExt.REFTABLE.getExtension())
                        .setSize(Files.size(reftable)))
                .build());
        store.publish(0, additions, List.of(), false, null);
        verify(project, refs);
        out.printf(
            Locale.ROOT,
            "imported %s: %d packs, %d bytes, %d refs%n",
            projectName,
            additions.size() - 1,
            bytes,
            refCount);
        return Outcome.IMPORTED;
      } finally {
        Files.deleteIfExists(reftable);
      }
    }
  }

  /** Every ref, peeled: the reftable writer records a tag's target alongside the tag itself. */
  private static FileRepository open(Path bareDirectory) throws IOException {
    return (FileRepository)
        new FileRepositoryBuilder().setGitDir(bareDirectory.toFile()).setMustExist(true).build();
  }

  /** The exit status {@code git fsck} uses when its only complaint is where {@code HEAD} points. */
  private static final int FSCK_HEAD_LINK_ERROR = 8;

  /**
   * Copies the parts of a bare repository that carry state, then repacks, prunes and checks the
   * copy with git so that it holds exactly the reachable objects, in packs, verified.
   *
   * <p>git's derived indexes, the commit-graph and the multi-pack-index, are not copied: the
   * importer does not ship them, and in a backup of a serving repository they are routinely stale,
   * because JGit deletes packs and prunes commits without updating them, which {@code git fsck}
   * would then report as corruption. Refs that point at objects the source lacks, which a backup
   * taken while the server writes can hold, fail the repository unless {@code
   * --prune-dangling-refs} was given, in which case they are deleted from the copy and listed.
   */
  private void stageCopy(String projectName, Path bareDirectory, Path copy) throws IOException {
    deleteRecursively(copy);
    Files.createDirectories(copy);
    for (String entry : List.of(Constants.HEAD, Constants.CONFIG, Constants.PACKED_REFS)) {
      Path file = bareDirectory.resolve(entry);
      if (Files.isRegularFile(file)) {
        Files.copy(file, copy.resolve(entry));
        copy.resolve(entry).toFile().setWritable(true, true);
      }
    }
    copyTree(
        bareDirectory.resolve(Constants.OBJECTS),
        copy.resolve(Constants.OBJECTS),
        RepositoryImporter::isDerivedIndex);
    String refs = Constants.R_REFS.replace("/", "");
    copyTree(bareDirectory.resolve(refs), copy.resolve(refs), relative -> false);

    List<String> dangling = danglingRefs(copy);
    if (!dangling.isEmpty()) {
      if (!pruneDanglingRefs) {
        throw new IOException(
            projectName
                + " has "
                + dangling.size()
                + " refs pointing at objects the source lacks; rerun with --prune-dangling-refs to"
                + " drop them from the copy: "
                + String.join(" ", dangling));
      }
      StringBuilder deletions = new StringBuilder();
      for (String ref : dangling) {
        deletions.append("delete ").append(ref).append('\n');
      }
      git(copy, deletions.toString(), "update-ref", "--stdin");
      out.printf(
          Locale.ROOT,
          "pruned %d dangling refs from %s: %s%n",
          dangling.size(),
          projectName,
          String.join(" ", dangling));
    }

    git(copy, null, "repack", "-a", "-d", "-q");
    git(copy, null, "prune", "--expire=now");
    GitResult fsck = runGit(copy, null, "fsck", "--connectivity-only", "--no-progress", "--no-dangling");
    // git fsck insists that a symbolic HEAD name a branch and reports nothing else with this
    // status; Gerrit deliberately points All-Projects' and All-Users' HEAD at refs/meta/config.
    if (fsck.status() != 0
        && !(fsck.status() == FSCK_HEAD_LINK_ERROR && headOutsideBranches(copy))) {
      throw fsck.failure(copy);
    }
  }

  /** git's commit-graph and multi-pack-index files, given a path relative to {@code objects/}. */
  static boolean isDerivedIndex(Path relativeToObjects) {
    String path = relativeToObjects.toString().replace(File.separatorChar, '/');
    return path.startsWith("info/commit-graph") || path.startsWith("pack/multi-pack-index");
  }

  /** Refs of the copy whose object, or whose annotated tag's target, the copy does not hold. */
  private static List<String> danglingRefs(Path copy) throws IOException {
    List<String> dangling = new ArrayList<>();
    try (FileRepository repository = open(copy);
        RevWalk walk = new RevWalk(repository)) {
      for (Ref ref : repository.getRefDatabase().getRefsByPrefix(RefDatabase.ALL)) {
        if (ref.isSymbolic() || ref.getObjectId() == null || Constants.HEAD.equals(ref.getName())) {
          continue;
        }
        try {
          walk.peel(walk.parseAny(ref.getObjectId()));
        } catch (MissingObjectException missing) {
          dangling.add(ref.getName());
        }
      }
    }
    return dangling;
  }

  /** Whether the copy's HEAD is a symbolic ref to something other than a branch. */
  private static boolean headOutsideBranches(Path copy) throws IOException {
    Path head = copy.resolve(Constants.HEAD);
    if (!Files.isRegularFile(head)) {
      return false;
    }
    String content = Files.readString(head, StandardCharsets.UTF_8).strip();
    return content.startsWith("ref: ") && !content.startsWith("ref: " + Constants.R_HEADS);
  }

  private static void copyTree(Path from, Path to, Predicate<Path> skip) throws IOException {
    if (!Files.isDirectory(from)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path path : (Iterable<Path>) paths::iterator) {
        Path relative = from.relativize(path);
        if (skip.test(relative)) {
          continue;
        }
        Path target = to.resolve(relative.toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else if (Files.isRegularFile(path)) {
          Files.copy(path, target);
          target.toFile().setWritable(true, true);
        }
      }
    }
  }

  private record GitResult(List<String> arguments, int status, String output) {
    IOException failure(Path repository) {
      return new IOException(
          "git " + String.join(" ", arguments) + " failed in " + repository + " (" + status + "):\n"
              + output.strip());
    }
  }

  private static void git(Path repository, String input, String... arguments) throws IOException {
    GitResult result = runGit(repository, input, arguments);
    if (result.status() != 0) {
      throw result.failure(repository);
    }
  }

  /** Runs git in {@code repository}, feeding it {@code input} if there is any, and collects its output. */
  private static GitResult runGit(Path repository, String input, String... arguments)
      throws IOException {
    List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
    command.addAll(List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    if (input == null) {
      builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    }
    builder.environment().put("LC_ALL", "C");
    Process process = builder.start();
    if (input != null) {
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write(input.getBytes(StandardCharsets.UTF_8));
      }
    }
    String output;
    try (var stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    int status;
    try {
      status = process.waitFor();
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while running git " + arguments[0], interrupted);
    }
    return new GitResult(List.of(arguments), status, output);
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static Map<String, Ref> sourceRefs(Repository source) throws IOException {
    Map<String, Ref> refs = new TreeMap<>();
    RefDatabase database = source.getRefDatabase();
    for (Ref ref : database.getRefsByPrefix(RefDatabase.ALL)) {
      refs.put(ref.getName(), ref.isSymbolic() || ref.isPeeled() ? ref : database.peel(ref));
    }
    Ref head = source.exactRef(Constants.HEAD);
    if (head != null) {
      refs.put(Constants.HEAD, head);
    }
    return refs;
  }

  private static void refuseLooseObjects(FileRepository source, String projectName)
      throws IOException {
    ObjectDirectory objects = source.getObjectDatabase();
    File[] fanout = objects.getDirectory().listFiles();
    if (fanout == null) {
      return;
    }
    for (File directory : fanout) {
      String name = directory.getName();
      if (name.length() == 2 && directory.isDirectory()) {
        String[] loose = directory.list();
        if (loose != null && loose.length > 0) {
          throw new IOException(
              projectName
                  + " has loose objects; run 'git repack -a -d' on the scratch copy before importing");
        }
      }
    }
  }

  private static long writeReftable(Map<String, Ref> refs, Path target) throws IOException {
    try (OutputStream out = Files.newOutputStream(target)) {
      ReftableWriter writer =
          new ReftableWriter(new ReftableConfig(), out).setMinUpdateIndex(1).setMaxUpdateIndex(1);
      writer.begin();
      writer.sortAndWriteRefs(new ArrayList<>(refs.values()));
      writer.finish();
    }
    return refs.size();
  }

  /** Stable name for the imported reftable, so a rerun reuses the file it already uploaded. */
  private static String refsDigest(Map<String, Ref> refs) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
    for (Ref ref : refs.values()) {
      digest.update(ref.getName().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(target(ref).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String target(Ref ref) {
    if (ref.isSymbolic()) {
      return "ref: " + ref.getTarget().getName();
    }
    ObjectId id = ref.getObjectId();
    return id == null ? "" : id.name();
  }

  /** Every source ref must be served identically through WalGerrit; optionally every object too. */
  private void verify(Project.NameKey project, Map<String, Ref> expected) throws IOException {
    try (Repository imported = repositories.openRepository(project)) {
      Map<String, Ref> actual = sourceRefs(imported);
      for (Map.Entry<String, Ref> entry : expected.entrySet()) {
        Ref ref = actual.get(entry.getKey());
        if (ref == null) {
          throw new IOException(project.get() + ": ref " + entry.getKey() + " is missing after import");
        }
        if (!target(ref).equals(target(entry.getValue()))) {
          throw new IOException(
              project.get()
                  + ": ref "
                  + entry.getKey()
                  + " is "
                  + target(ref)
                  + " after import, expected "
                  + target(entry.getValue()));
        }
      }
      if (actual.size() != expected.size()) {
        throw new IOException(
            project.get()
                + ": "
                + actual.size()
                + " refs after import, expected "
                + expected.size());
      }
      if (verifyClosure) {
        walkEverything(imported, expected.values());
      }
    }
  }

  private static void walkEverything(Repository repository, Collection<Ref> refs) throws IOException {
    try (ObjectWalk walk = new ObjectWalk(repository)) {
      for (Ref ref : refs) {
        if (ref.getObjectId() != null) {
          walk.markStart(walk.parseAny(ref.getObjectId()));
        }
      }
      while (walk.next() != null) {
        // every commit is parsed by next()
      }
      RevObject object;
      while ((object = walk.nextObject()) != null) {
        walk.checkConnectivity();
      }
    }
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? fileName : fileName.substring(0, dot);
  }
}
