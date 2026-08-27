# JGit and manifest-CAS correctness audit

## Conclusion

JGit has the right core semantics for WalGerrit. Do not fork JGit for the storage engine.

`DfsObjDatabase` gives one publication hook for immutable pack/index/reftable files, and
`DfsReftableDatabase` turns a Gerrit `BatchRefUpdate` into one immutable reftable. The backend can
therefore make one manifest compare-and-swap the linearization point for every Git ref transaction.

Two boundaries remain:

1. WalGerrit must supply stronger distributed publication and freshness behavior than JGit's
   process-local caches and locks provide. This belongs in the DFS backend and its
   `DfsReftableBatchRefUpdate` subclass.
2. Gerrit 3.12.2 initialization contained direct `FileRepository` access that bypassed
   `GitRepositoryManager`. This repository carries the small Gerrit fork patch that switches init
   helpers to the configured manager. It is not a reason to fork JGit.

The audit baseline is Gerrit `v3.12.2` and its pinned JGit revision
[`010858e24f1860fc70ecce534a274def79826abb`](https://eclipse.googlesource.com/jgit/jgit/+/010858e24f1860fc70ecce534a274def79826abb/).
The storage rules come from Cursor's
[`Git at scale`](https://cursor.com/blog/git-at-any-scale) and the executable design in
[`tobi/walgit`](https://github.com/tobi/walgit), inspected at
[`6d8fa54ba0f83072a1a50317bb6c8c1afa5a3cd1`](https://github.com/tobi/walgit/commit/6d8fa54ba0f83072a1a50317bb6c8c1afa5a3cd1).

## Reference invariants

The backend must preserve these properties:

1. Pack, index, reftable, and log objects are immutable after publication.
2. All immutable dependencies land before the manifest changes.
3. The manifest CAS is the only commit point. Files that are not named by a committed manifest are
   harmless orphans.
4. Objects may become available before their refs. A ref may never become visible before all of its
   objects are available.
5. Each read request revalidates the manifest. Local disk and JGit memory state are caches, not
   authority.
6. A ref transaction validates all expected old values and publishes all accepted commands in one
   CAS, or publishes none of them.
7. Once the manifest CAS lands, the operation is committed even if the response is lost or the
   writer's local-cache update fails.
8. Compaction changes representation, not Git state. It must never discard a concurrently published
   pack or ref update.

## The two CAS tokens

There are two related but different tokens:

- The **physical manifest version** is the object store's opaque ETag/generation. Every remote
  manifest write uses it in a conditional request. It prevents lost updates to any manifest field.
- `ref_revision` is the **semantic reftable-stack generation** stored inside the manifest. It changes
  whenever the live reftable stack changes, including representation-only reftable compaction.

The local backend obtains the physical exclusion with `manifest.lock` plus an OS file lock and then
atomically replaces `manifest.pb`. An S3/GCS backend must instead use the opaque object version and a
CAS retry loop; comparing the protobuf `revision` field alone is not sufficient.

Separating `ref_revision` from the overall manifest `revision` matters because Gerrit deliberately
flushes objects before executing its `BatchRefUpdate`. Those object-pack publications are safe to
merge into the manifest and must not invalidate an otherwise current ref transaction. A changed
reftable stack, on the other hand, invalidates the transaction's JGit update index and expected-ref
snapshot.

The remote CAS loop is therefore:

1. Read manifest `M` with opaque version `V`.
2. Validate the operation's semantic preconditions against `M`.
3. Build `M'` by merging the operation with `M`.
4. Conditionally write `M'` using `V`.
5. On a precondition failure, read the winner. If `ref_revision` is unchanged, merge concurrent
   object-only work and retry. If it changed, fail with a JGit lock conflict unless the entire ref
   transaction is revalidated and its reftable is rewritten with a new update index.

Writer affinity and batching reduce contention, but correctness never depends on a primary writer.

## JGit path-by-path audit

| Operation | Pinned JGit path | Required WalGerrit behavior | Verdict |
|---|---|---|---|
| Programmatic object insertion | `DfsInserter.flush` writes pack and index, then calls `commitPack` | Publish files, log, then manifest; an orphan object pack is safe | Fits cleanly |
| Received Git pack | `DfsPackParser.parse` writes pack/index and calls `commitPack` | Same as insertion; thin-pack resolution finishes before publication | Fits cleanly |
| Atomic batch refs | `ReftableBatchRefUpdate.execute` validates objects, fast-forwards, expected old IDs, and namespace conflicts; `DfsReftableBatchRefUpdate` writes one `.ref` and calls `commitPack` | Capture a fresh ref generation before validation; one manifest CAS publishes the whole `.ref` | Fits cleanly |
| Single ref update/delete/link | `DfsRefUpdate` delegates to `DfsReftableDatabase.compareAndPut/Remove`, which creates a batch update | Use the same distributed ref transaction path | Fits cleanly |
| Ref conflict reporting | `ReftableBatchRefUpdate` maps an `IOException` to `LOCK_FAILURE` and aborts the remaining commands; Gerrit's `RefUpdateUtil` converts a fully aborted atomic batch to `LockFailureException` | Surface manifest/ref-generation conflicts as lock failures | Fits, though the original exception is lossy |
| Reftable ordering | `ReftableDatabase.nextUpdateIndex` returns current max + 1; `DfsPackDescription.reftableComparator` orders the stack by source and update index | Persist min/max update indices and prevent two publications based on the same ref generation | Fits if the manifest CAS is enforced |
| Reftable compaction during a ref update | `DfsReftableBatchRefUpdate` may fold the top table and pass it as `replaces` | Addition and superseded table are one manifest transaction | Fits cleanly |
| Leased pack compaction | `DfsPackCompactor` supplies new descriptions and the source descriptions it replaces | WalGerrit owns the lease, stale-input check, manifest CAS, propagation, and retention | Correct production maintenance model |
| Full DFS garbage collection | `DfsGarbageCollector` snapshots refs and packs, then commits several outputs and removals together | It is not the production maintenance path | Gerrit GC is disabled and the backend rejects its `GC`, `GC_REST`, and `UNREACHABLE_GARBAGE` publications |
| MIDX | Optional `DfsMidxWriter`; covered-pack relationships live in `DfsPackDescription` | Persist the MIDX base and covered-pack graph before enabling it | Keep disabled until the manifest schema supports it |
| Ref rename | `DfsRefRename` creates the destination and deletes the source as two updates; JGit contains a TODO to batch them | Provide an atomic WalGerrit override before exposing rename to plugins | Gerrit core does not currently call it, but it is not acceptable as a general API |
| Cache refresh | `DfsRepository.scanForRepoChanges` clears ref and object caches | A newly opened repository starts from a fresh manifest; mutations force refresh before expected-ref validation | Fits with backend policy; long-lived readers need an explicit request-boundary revalidation contract |

The relevant JGit sources are
[`DfsObjDatabase`](https://eclipse.googlesource.com/jgit/jgit/+/010858e24f1860fc70ecce534a274def79826abb/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/dfs/DfsObjDatabase.java),
[`DfsReftableDatabase`](https://eclipse.googlesource.com/jgit/jgit/+/010858e24f1860fc70ecce534a274def79826abb/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/dfs/DfsReftableDatabase.java),
[`DfsReftableBatchRefUpdate`](https://eclipse.googlesource.com/jgit/jgit/+/010858e24f1860fc70ecce534a274def79826abb/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/dfs/DfsReftableBatchRefUpdate.java), and
[`ReftableBatchRefUpdate`](https://eclipse.googlesource.com/jgit/jgit/+/010858e24f1860fc70ecce534a274def79826abb/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/reftable/ReftableBatchRefUpdate.java).

## Logical ref transaction

For a Gerrit batch, the target algorithm is:

1. Read the current manifest and remember `ref_revision`.
2. Clear JGit's object and ref caches, so expected-value checks use that generation or a newer one.
3. Let JGit validate object existence, fast-forward policy, every old object ID/symbolic target, and
   ref namespace conflicts.
4. Let JGit write one reftable containing every accepted command and its reflog records. Its update
   index is the prior stack maximum plus one.
5. Publish the immutable reftable.
6. Under the physical manifest CAS, require the remembered `ref_revision`, append a log entry, add
   the new table, and remove any table folded by commit-time compaction.
7. Only after the manifest is committed, refresh/add to the local JGit cache and report `OK`.

If another ref writer wins after step 1, the CAS fails. The current implementation reports
`LOCK_FAILURE`. A future transparent retry may refresh and re-run all of steps 3-6; it must not just
reuse the old reftable, because its update index and namespace checks are based on stale state.

## Object publication

JGit object inserters and received-pack parsers publish their pack before the later ref update. This
is compatible with the reference design: unreachable objects are harmless, while publishing a ref
without its objects is corruption.

An object-only publication does not carry a ref semantic precondition. Under the physical CAS it
merges with the latest live pack set. If Gerrit dies before the ref transaction, maintenance later
reclaims the unreachable pack.

For each JGit `DfsPackDescription`, the manifest must preserve at least:

- stable pack identity and source;
- every file extension and exact size/block size;
- object/delta counts;
- reftable min/max update indices;
- creation/last-modified ordering metadata;
- pack checksum.

Readers enumerate only the manifest inventory, never the object-store prefix. Immutable files can
be materialized into a bounded local cache and served through `ReadableChannel`.

## Ambiguous outcomes and acknowledgements

A timeout or connection loss after a conditional manifest write is not evidence that the write
failed. Each attempted publication has a unique immutable log key. After a non-precondition error,
the writer re-reads the manifest:

- if the manifest names that log key, the transaction committed and must be reported as success;
- if it does not, the log/file objects remain harmless orphans and the operation failed;
- if the re-read also fails, the result is unknown and the writer must not destructively clean up
  anything that could be committed.

Likewise, once the CAS lands, a failure to update this process's JGit pack/ref cache cannot change
the Git result. The operation is acknowledged and the local cache is invalidated so the next read
repairs it. Reporting an error after a landed CAS would tell the caller a durable Git update failed.

The local implementation verifies exact manifest bytes after an error following atomic rename. The
future object-store implementation must verify the unique log key, matching WalGit's `cas_landed`
behavior.

## Normative compaction model

Ordinary Gerrit nodes never run JGit GC independently. `canPerformGC()` remains `false`, and the
publication hook rejects all `DfsGarbageCollector` pack sources as defense in depth.

A separate compactor does the following:

1. Acquire a per-repository compaction lease. The lease prevents duplicate expensive work; it is not
   the correctness mechanism.
2. Read a manifest snapshot and materialize the input packs into its local cache.
3. Run JGit's `DfsPackCompactor` (or Git repack where measured better) exactly once as the repacking
   engine. JGit supplies the new `DfsPackDescription` values and the descriptions they replace.
4. Upload every immutable output and its side files.
5. Ask WalGerrit to publish one precise add-and-supersede transaction. At the physical manifest CAS,
   every item in `supersedes` must still be live. If any input is stale, leave the output orphaned
   and restart from a fresh snapshot.
6. Merge concurrent push packs not named by `supersedes`, publish one COMPACT log entry, and CAS the
   manifest.
7. Let every Gerrit node consume the result by manifest/WAL revalidation.
8. Remove superseded files from the live inventory only. Physical reclamation waits until no
   retained manifest/checkpoint and no reader holding an older generation can use them.

This is exactly the division of responsibility: JGit/Git supplies the repacking engine; WalGerrit
supplies distributed publication, leases, stale-input handling, propagation, and retention.

Reftable maintenance is either a separate transaction or an explicitly included input/output set.
It must not be accidentally coupled to object repacking, because a reftable-stack change has its own
generation and ref-reader implications.

## Gerrit semantics above JGit

Gerrit NoteDb requires `RefDatabase.performsAtomicTransactions()`. Its update manager flushes newly
created objects first, builds one `BatchRefUpdate`, calls `setAtomic(true)`, and only starts secondary
index work after the ref batch succeeds. This matches the publication ordering above.

The Lucene/Elasticsearch secondary indexes are derived state, not part of the Git CAS. Gerrit writes
pending index intents around NoteDb mutation and can rebuild indexes from Git. Events and other side
effects must likewise happen after publication and be replayable from the WAL.

Gerrit's atomicity is per repository. Operations spanning multiple projects are not made globally
atomic by a per-repository manifest; this is existing Gerrit behavior, not a JGit storage regression.

## Gerrit fork seam

The stock 3.12.2 server loads `installDbModule` and creates the complete `All-Projects` and
`All-Users` NoteDb schema through WalGerrit. Unmodified init then fails because later init-only code
opens repositories below `$site/git` directly.

Direct filesystem assumptions exist in:

- `AccountsOnInitNoteDbImpl`;
- `GroupsOnInit`;
- `ExternalIdsOnInit`;
- `VersionedMetaDataOnInit`, used by project config and initial authorized keys;
- `GitRepositoryManagerOnInit`.

The fork now makes `GitRepositoryManagerOnInit` a switching manager. It preserves the filesystem
fallback while init gathers configuration, then `SitePathInitializer.postRun` installs the system
injector's configured `GitRepositoryManager` before any post-init step. All affected helpers use
that switching manager. A missing pre-schema fallback repository remains an ordinary
`RepositoryNotFoundException`, preserving upstream's empty-state behavior.

The fresh-site smoke test completes init and reindex with no repository under `gerrit.basePath` and
with both system projects present in the WalGerrit manifest store.

This is a narrow Gerrit integration seam. Runtime NoteDb, fetch, push, and schema creation already
operate through the repository manager and JGit APIs.

## Test gates before an object-store backend is production-ready

1. Two concurrent ref batches from the same generation: exactly one CAS winner.
2. Concurrent object append and ref update: both survive, with one ref transaction.
3. Two disjoint ref batches: conflict is safe; a retry revalidates and succeeds.
4. Expected-old mismatch, including symbolic refs: no manifest change.
5. Namespace race (`refs/heads/a` versus `refs/heads/a/b`): no invalid ref set.
6. Process death after each immutable-file move, log write, and manifest CAS.
7. CAS success with lost response: caller receives success after outcome verification.
8. CAS success followed by local-cache failure: caller receives success and the next read repairs.
9. Reftable commit-time compaction racing a ref update.
10. Leased object compaction racing object insertion and ref publication.
11. A cold second instance observes the new refs and can read all pointed-to objects immediately
    after the first instance acknowledges.
12. Full Gerrit init, reindex, daemon start, project creation, push, review mutation, submit, and
    restart against the backend.

The local milestone covers gates 1-3, the object-ID part of gate 4, gate 5, gate 7, the core
add-and-supersede/retention behavior in gate 10, basic batch atomicity, and reopen/recovery. Gate 12
currently covers fresh init and reindex. Symbolic expected-old races, crash injection beyond lost
post-rename acknowledgement, lease orchestration, two-node visibility, daemon/push/review/submit,
and restart remain release blockers rather than optional polish.
