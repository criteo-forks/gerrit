# Importing repositories

`walgerrit-import` moves an existing Gerrit site's repositories into WalGerrit without rewriting
them. It reads a tree of bare repositories laid out like `gerrit.basePath`, uploads each
repository's pack files as they are, writes its refs into one reftable, and publishes the whole
repository with one manifest transaction. The result is exactly what a compaction would have
produced: a few large packs and one table, so the compactor has nothing to do afterwards.

```bash
java -jar gerrit.war walgerrit-import -d "$site" --source /backup/git --stage /scratch \
    [--prune-dangling-refs] [--threads N] [--project NAME]... [--verify-closure]
```

The site is the WalGerrit site that will serve the data: its `gerrit.config` names the bucket or
local store, installs `dev.walgerrit.WalGitModule`, and carries the WalGerrit library in `lib/`.
Nothing is written anywhere but the configured store, and the daemon need not be stopped; the
imported repositories are invisible until a site is pointed at their prefix.

## Survey first

`scripts/survey-repositories.sh BASE_PATH` reads the tree without writing to it and prints the
numbers the import depends on: repository count and total size, the largest repositories and the
largest single pack, which repositories still hold loose objects, ref counts, and the NoteDb schema
version in All-Projects. The largest repository sizes the staging space; the largest pack says
whether the 5 GiB single-upload limit, above which parts are used, is exercised on day one.

## Prepare the source

The source is never written to. Repositories must hold no loose objects when they are uploaded, and
there are two ways to get there.

**Staged, the default for a real site.** With `--stage DIR` the importer copies each repository
into that directory, runs `git repack -a -d`, `git prune --expire=now` and `git fsck
--connectivity-only` on the copy, imports the copy and deletes it. The source can be a read-only
mount of the backup, and the scratch space needed is the largest repository times `--threads`, not
the whole site. `git` must be on the PATH. Bitmaps and reverse indexes written by the repack are
imported alongside the packs and speed up clones.

Staging expects what a backup of a serving Gerrit looks like. git's derived indexes, the
commit-graph and the multi-pack-index, are not copied: the importer does not ship them, and they
are routinely stale in a backup because JGit prunes commits and deletes packs without updating them,
which `git fsck` would report as corruption. A `HEAD` that names `refs/meta/config`, as Gerrit sets
up `All-Projects` and `All-Users`, is kept although `git fsck` objects to it. A ref that points at
an object the backup lacks, which a backup taken while the server writes can hold (`refs/multi-site/
version` and freshly written change refs are the usual cases), fails the repository with the list
of such refs; with `--prune-dangling-refs` they are deleted from the copy instead, and listed in the
output as `pruned N dangling refs from PROJECT: ...`, so the operator can fetch them from the
primary afterwards. The source is never changed either way.

**Pre-repacked.** Without `--stage`, run `git repack -a -d` and `git fsck --connectivity-only` in
every repository of a scratch copy yourself, then import the copy. The importer refuses a
repository that still has loose objects and says so.

Either way:

3. **Set the server id.** NoteDb stores every identity as `accountId@serverId`, and Gerrit resolves
   it to an account only when the id matches its own `gerrit.serverId`. Before starting a daemon on
   imported data, set the WalGerrit site's `gerrit.serverId` to the source server's; a fresh
   `instanceId` per node is fine. Skipping this turns every owner, reviewer and comment author into
   an unknown account.

Reflogs are not imported; reftables carry the refs themselves. `refs/cache-automerge/*` may be
deleted from the copy first, they are regenerated on demand.

## What one run does

For every repository, in `--threads` repositories at a time (default 4):

- with `--stage`, the repository is copied, repacked, pruned and checked first, and the copy is
  removed afterwards even if the import fails;
- every pack, index, bitmap and reverse index is uploaded under its original name, so a rerun
  recognises an already uploaded file by name and content and skips it; files above 64 MiB use a
  multipart upload;
- `HEAD` and every ref, including `refs/changes/*`, `refs/meta/*`, `refs/users/*` and
  `refs/groups/*`, become one reftable named after a digest of the refs, so a rerun reuses it;
- one manifest publication adds all of them, and only then does the repository exist;
- a handle is opened through WalGerrit and every source ref is compared with what it serves;
  `--verify-closure` additionally walks every object the refs reach, which reads every pack back
  through WalGerrit and is the expensive option.

Every step is idempotent, so a run that died is resumed by running it again: repositories with a
published manifest are only verified, a repository whose manifest exists but is empty is published,
and the rest proceed. The exit status is non-zero if any repository failed, and each failure is
printed with its cause. `--project` limits a run to the named projects, which is how a failed
repository is retried after its source was fixed.

## After the import

Run an offline `reindex` against the site before any daemon starts, then

```bash
java -jar gerrit.war walgerrit-mark-indexed -d "$site"
```

which records on that node that its indexes reflect the current head of every repository. Without
it the daemon treats a repository without a cursor as never indexed; the import publishes no ref
transaction, so as soon as anything has been written to an imported repository (the schema
migration `init` runs on All-Projects and All-Users is enough) its log cannot be replayed from the
start, and the daemon rebuilds every index on startup with one thread per index and its own
configuration. For a large site, run the reindex with a generous heap, a relaxed `commitWithin`,
`walgerrit.manifestRevalidateOnOpen = false` and Gerrit's persistent caches on scratch space
(`cache.directory`); computing every change's diffs fills them by tens of gigabytes, and they are
never pruned outside the daemon. Set `commitWithin = 0` back before starting daemons, which is
when the index-event tailer checks it. Then point the deployment at the prefix, log in, clone,
search, review, submit and restart.

## Sizing

The import moves every byte of the source over the network once; plan for that bandwidth. Staging
adds one local copy and one repack per repository, in `--threads` repositories at a time. A node
that later serves the data materializes packs into its local cache on first use, so with an object
store backend the runtime volume should hold the working set, and with a full reindex that is
everything.
