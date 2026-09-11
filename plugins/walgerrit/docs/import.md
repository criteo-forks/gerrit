# Importing repositories

`walgerrit-import` copies a tree of bare Gerrit repositories into the configured WalGerrit store.
It uploads pack files, writes the refs into one reftable, and publishes the data with one
manifest transaction per repository. It does not import Lucene indexes, reflogs or site settings.

```bash
java -jar gerrit.war walgerrit-import -d "$site" \
  --source /backup/git --stage /scratch --threads 4
```

Use the matching fork WAR and library. The destination site's `gerrit.config` must install
`dev.walgerrit.WalGitModule` and select the destination backend; the library belongs in `lib/`.

## Isolate the destination and freeze the source

Use a consistent backup or a quiescent source tree. Copying a live repository is not a snapshot
and may combine refs and objects from different moments.

Import into a fresh store or prefix with no daemon serving it. The importer creates an empty
manifest before uploading, so a repository name can be discoverable before its data is ready.
Once the data is published, any daemon using that prefix can see it. A prefix is isolated only
while no serving node points at it.

The importer reads the source without changing it. It writes shared store data, local staging
and temporary files, and may populate the local cache during verification. Do not run concurrent
imports of the same project or share a staging directory between importer processes.

## Survey the backup

From `plugins/walgerrit`, run:

```bash
scripts/survey-repositories.sh /backup/git /scratch/survey
```

The script leaves the backup unchanged and writes `repositories.tsv` and `packs.tsv` into the
report directory. It reports counts and sizes, the largest repositories and packs, loose objects,
refs and the `All-Projects` NoteDb schema version. Size scratch space for the repositories imported
concurrently, with extra room for repack output. A copy and its replacement packs can coexist.
S3 uploads use multipart transfer for files above 64 MiB.

## Prepare packs on a copy

With `--stage DIR`, the importer copies each repository into `DIR`, then runs `git repack -a -d`,
`git prune --expire=now` and `git fsck --connectivity-only` on that copy. It removes the copy after
success or failure. Use a dedicated scratch directory: the importer replaces
`DIR/<project>.git` before staging. Git must be on `PATH`.

Staging omits commit-graph and multi-pack-index files. It preserves a symbolic `HEAD` outside
`refs/heads/`, as used by Gerrit's system projects. Existing bitmap and reverse-index side files
are uploaded with their packs when present; staging does not guarantee that a bitmap is created.

Without `--stage`, the importer requires packed objects and rejects repositories containing loose
objects. Prepare and check a scratch copy yourself, including pruning loose unreachable objects
if needed. Staging is optional and is not the command's default.

### Missing objects require an explicit choice

A ref whose tip or peeled tag target is missing fails staging. `--prune-dangling-refs` deletes
such refs from the staged copy and prints their names. This changes the imported ref set; it does
not repair the missing history. Missing objects deeper in the reachable graph can still fail the
connectivity check.

Keep the list of removed refs for reconciliation with the source. A later rerun against an
already published repository verifies the original source directly, without staging or pruning;
it will fail if that source still contains the removed refs. The importer does not incrementally
update a published repository.

## Preserve the Gerrit server ID

Set the destination's `gerrit.serverId` to the source server's ID before serving imported NoteDb.
Gerrit uses that ID in account identities stored in NoteDb; a mismatch can make owners, reviewers
and comment authors appear as unknown accounts. Each node may have its own `gerrit.instanceId`.

Reflogs are not imported. The source copy may omit `refs/cache-automerge/*`, which Gerrit can
regenerate, but any such pruning must be deliberate and reflected in verification.

## Publication and verification

For each repository, the importer:

1. Creates or resumes its empty destination manifest.
2. Optionally stages and checks the source copy.
3. Uploads packs and supported side files under their original names.
4. Writes `HEAD` and all refs into a deterministically named reftable.
5. Publishes all additions in one manifest transaction.
6. Opens the result through WalGerrit and compares all ref names and targets.

Already uploaded files are checked by name and content. A nonempty destination is verified
instead of overwritten. Rerunning therefore resumes an interrupted import when the source ref
set is unchanged, subject to the pruning caveat above. It is not a synchronization tool.

| Option | Effect |
| --- | --- |
| `--threads N` | Import repositories concurrently; default `4`. |
| `--project NAME` | Limit import to a project; repeat for several projects. |
| `--verify-closure` | Also walk reachable commits, trees and object connectivity through WalGerrit. |
| `--prune-dangling-refs` | With staging, remove refs whose tip or peeled tag target is missing. |

Closure verification is more expensive than ref comparison. It is not a full byte-by-byte audit
of every stored pack or unreachable object. A run exits nonzero if any repository fails and
prints the failure cause. Verification happens after publication, so failure does not imply
that nothing was published.

## After the import

Keep **all writers to the destination stopped** through initialization or schema migration,
offline reindexing and cursor seeding. For each node's local indexes, finish reindexing and then
run:

```bash
java -jar gerrit.war reindex -d "$site"
java -jar gerrit.war walgerrit-mark-indexed -d "$site"
```

`walgerrit-mark-indexed` records the current repository heads. It does not inspect the indexes or
recover the heads observed by the preceding reindex. A write between those two operations could
be marked indexed without being indexed; stopping only this node is insufficient if other nodes
can still write to the destination.

An import entry contains files and refs but no logical ref-update payload for the index tailer.
Do not rely on replay to index the imported baseline. Seed cursors only after the offline reindex
succeeds. Subsequent publications are then replayed by the daemon.

For a large offline reindex, a larger heap, relaxed `commitWithin` and
`manifestRevalidateOnOpen = false` can reduce cost. Budget disk space for persistent caches and
pack reads. Restore all five `commitWithin = 0` settings and the intended manifest-freshness
settings before starting daemons. Require index readiness, then verify login, clone, search,
review, submit and restart before directing users to the new deployment.

## Capacity and rollback

Budget network transfer for the imported packs, additional reads for verification and reindexing,
and scratch space for concurrent copies plus repack output. With S3, each node's file cache grows
with its working set; a full reindex can read much of the repository data.

Keep the source backup and destination isolated during validation. Once the new deployment
accepts writes, pointing users back at the old site does not preserve those writes. A reverse
migration and reconciliation procedure remains deployment work; the importer supplies neither.
