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
import java.util.stream.Stream;
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
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Imports a tree of bare repositories, as {@code gerrit.basePath} lays them out, into WalGerrit.
 *
 * <p>Nothing is repacked or rewritten here. Each repository's existing pack files are uploaded as
 * they are, so the operator repacks once beforehand ({@code git repack -a -d}, then {@code git fsck
 * --connectivity-only}) on a scratch copy of the backup, never on a serving repository, and a
 * repository that still has loose objects is refused with that instruction. Its refs, including
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
      Usage: walgerrit-import -d SITE --source DIR [--project NAME]... [--threads N] [--verify-closure]

        --source DIR       directory of bare repositories laid out like gerrit.basePath
        --project NAME     import only this project (repeatable; default: every repository)
        --threads N        repositories imported in parallel (default 4)
        --verify-closure   after publishing, walk every reachable object through WalGerrit
        --help             this text

      Repositories must be repacked first (git repack -a -d) so that they hold no loose objects.
      Set gerrit.serverId to the source server's id before importing NoteDb data.
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

  RepositoryImporter(WalGitRepositoryManager repositories, PrintStream out, boolean verifyClosure) {
    this.repositories = repositories;
    this.out = out;
    this.verifyClosure = verifyClosure;
  }

  /** Command-line entry point used by Gerrit's {@code walgerrit-import} program. */
  public static int run(GitRepositoryManager manager, String[] args) throws Exception {
    Path source = null;
    Set<String> only = new HashSet<>();
    int threads = 4;
    boolean verifyClosure = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--source" -> source = Path.of(requireValue(args, ++i));
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
        new RepositoryImporter(walGit, System.out, verifyClosure).importAll(source, only, threads);
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
    try (FileRepository source =
        (FileRepository)
            new FileRepositoryBuilder().setGitDir(bareDirectory.toFile()).setMustExist(true).build()) {
      Map<String, Ref> refs = sourceRefs(source);
      ManifestStore store = repositories.storage().manifestStore(project);
      boolean created = store.create();
      if (!created) {
        Manifest existing = store.refresh();
        if (existing.getPacksCount() > 0 || existing.getRevision() > 0) {
          verify(project, refs);
          out.printf(Locale.ROOT, "skipped %s: already imported and verified%n", projectName);
          return Outcome.ALREADY_IMPORTED;
        }
        // Created by an earlier run that died before publishing; publish now.
      }
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

  private static Map<String, Ref> sourceRefs(Repository source) throws IOException {
    Map<String, Ref> refs = new TreeMap<>();
    for (Ref ref : source.getRefDatabase().getRefsByPrefix(RefDatabase.ALL)) {
      refs.put(ref.getName(), ref);
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
