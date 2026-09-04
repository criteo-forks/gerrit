# Consistency contract

Both storage backends provide the same publication contract. The local backend uses atomic
filesystem moves and a locked manifest compare-and-swap. The S3 backend uses immutable puts and a
conditional manifest request.

## Publication order

1. Upload every immutable pack and index required by the transaction.
2. Persist the transaction-log entry.
3. Atomically compare-and-swap the repository manifest.
4. Acknowledge the Gerrit operation only after the winning manifest is readable by every serving
   instance.

Objects may exist before their refs. Refs must never point to unavailable objects.

## Ref transactions

Gerrit's `BatchRefUpdate` may update several refs atomically. The backend must validate every
expected old object ID and publish either all requested ref changes or none of them.

Within a node, ref transactions on one repository run one at a time: every handle on the node
shares a per-repository lock, so a transaction always starts from the manifest the previous local
one published, as JGit's reftable batch update assumes when it serializes writers on a single
repository instance. Across nodes the manifest compare-and-swap is the only fence. A transaction
that loses it to another node's ref change is re-run from scratch against the reloaded manifest,
expected-value checks included, up to five times; independent updates to different refs therefore
all land, as they do on Gerrit's file-based backends. A real ref conflict is reported to Gerrit as
a lock failure; it must not be silently overwritten. The reftable a lost attempt already uploaded
stays in the store unreferenced, like any other immutable file a failed publication leaves behind.

The manifest carries a separate ref revision. Appending unreachable object packs is safe before ref
publication and therefore does not invalidate a ref transaction. Only a change to the live
reftable stack advances the ref revision.

## Freshness

Local disk and JGit memory state are caches; the manifest in the object store is the authority. A
handle establishes freshness with one conditional read of the manifest, using the newest version
this node has observed as the `If-None-Match` token, at these points:

1. when `GitRepositoryManager` opens or creates the repository (with
   `walgerrit.manifestRevalidateOnOpen = false`, an open reuses the node's view when it was
   validated less than `manifestRevalidateInterval` ago; see the README for when that is safe);
2. when a ref transaction begins, before JGit validates expected old values;
3. when a caller asks for `scanForRepoChanges`;
4. at most once per `walgerrit.manifestRevalidateInterval` within a long-lived handle (`0`
   disables this periodic check).

Between those points every object and ref lookup is served from JGit's in-memory pack list and
reftable stack, which mirror the newest manifest the node has observed. A manifest observed by any
handle on the node, including the index-event tailer's sweep, is adopted by every other handle on
its next lookup without a further read. A handle's own publications update the node's view
directly from the CAS response.

This gives the same guarantee Cursor describes: a write acknowledged anywhere is visible to every
request that starts afterwards on any node, and a ref transaction never validates against a view
older than its own start. Within one request, reads are a consistent snapshot rather than a live
feed of other nodes' writes.

## Local disk is a cache

Every immutable file a node reads lives in the store; the local copy is a cache the node may lose at
any time. Whole files are materialised atomically (temporary file, fsync, rename). A pack fetched
in chunks is a sparse file at its final name plus a `<name>.chunks` sidecar; the data of a chunk is
written and forced before the sidecar records it, and the sidecar is removed only after the last
chunk, so a file without a sidecar is complete and a crash can lose no more than the chunk in
flight. Eviction and trimming treat the pair as one file and never remove the sidecar first.

## Compaction

Compaction changes how a repository is stored, never what it contains. A compacted pack holds the
same objects as the packs it supersedes and a compacted reftable the same refs as the stack it
replaces, and both enter the live set through the same manifest CAS as a write, with the added
check that every superseded file is still live. A reader therefore sees either the old files or the
new ones, both complete, and a writer racing a compaction on another node either lands first, in
which case the compaction's manifest update merges the writer's additions, or lands second and
re-runs against the compacted manifest. On the same node a reftable compaction publishes under the
repository's write lock, so it waits for the transaction in flight rather than failing it. Superseded files stay in the store for the reclamation grace period, which
bounds how long a reader may keep using a manifest it read earlier. See
[compaction.md](compaction.md).

## Derived state

Lucene indexes and caches are not part of the Git transaction. They are updated after publication
and remain rebuildable from Git/NoteDb. The complete logical ref transaction is stored in the
immutable WAL entry before the manifest CAS. A node processes entries in repository sequence order
and atomically advances its node-local cursor only after the synchronous index applications return.
Delivery is at least once because Gerrit index replacements and deletions are idempotent.

The cursor is safe across hard crashes only when every affected Lucene index commits each write to
stable storage. WalGerrit therefore requires `commitWithin = 0` for accounts, both change
sub-indexes, groups, and projects. A missing payload, sequence gap or index failure leaves the
cursor unacknowledged and stops progress for that repository instead of silently skipping data. A
cursor that can no longer be replayed at all, because it names a transaction the chain does not
or is further behind than the replay limit, makes the node rebuild its indexes from repository
state and reseed every cursor before it serves again.

Before Gerrit's serving listeners start, a daemon must complete a clean sweep of every repository.
Only then does it publish its node-local readiness marker and gauge. A failed background sweep
revokes readiness while still attempting every repository, and a later clean sweep restores it.
This is a consumer-health signal for the most recently completed sweep, not a cross-repository
snapshot or a barrier against writes committed just afterward.

Repository WAL streams have no global order. When an All-Users draft/star event depends on a change
whose project stream has not yet been indexed, the event is retried rather than acknowledged.

See [WAL-driven index events](index-events.md) for mappings and operational limitations.
