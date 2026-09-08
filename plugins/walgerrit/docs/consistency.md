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

Within a node, every handle shares one publisher per repository. A ref transaction is admitted
once no transaction in flight on the node touches a ref name that could interfere with its own (the
same name, or one nested under the other); it then validates and writes its reftable under the node
lock, releases it, and hands the publication to the publisher. Members admitted together validated
against states this node itself produced, so their reftables land in one log entry with one CAS,
together with every object pack JGit committed ahead of a transaction: a received pack or an
inserter flush is uploaded at once but published by the next ref transaction on the node, or when
the handle that committed it closes, so a push is one CAS rather than three. A pack stays listed on
its node until the publication carrying it is known to have landed, and a publication whose
response was lost is settled from the log chain before anything it carried is retried. A transaction
the chain shows has published its packs, whatever compaction did to them since. A manifest that
came back with more ref changes than the publication made ends the validation epoch of every
transaction still queued. Readers and the reclaimer sample pending packs before the manifest and
resolve their publication identities against that manifest, as described below. Across nodes the
manifest compare-and-swap is the only fence. A group that loses it to another node's ref change
fails as a whole, and every member is re-run from scratch against the reloaded manifest,
expected-value checks included, up to five times; independent updates to different refs therefore
all land, as they do on Gerrit's file-based backends. A real ref conflict is reported to Gerrit as
a lock failure; it must not be silently overwritten. The reftable a lost attempt already uploaded
stays in the store unreferenced, like any other immutable file a failed publication leaves behind.

The manifest carries a separate ref revision. Appending unreachable object packs is safe before ref
publication and therefore does not invalidate a ref transaction. Only a change to the live
reftable stack advances the ref revision.

## Ambiguous outcomes and recovery

A conditional-write error does not by itself tell the caller whether a publication committed.
This includes a precondition failure: an SDK retry can receive it after an earlier request succeeded.
Every CAS error, including an unchecked SDK exception, is verified using the attempted sequence and
transaction ID. A failed manifest or log read preserves that identity for recovery.

Recovery has three outcomes:

| Evidence from the manifest's log chain | Action |
| --- | --- |
| The attempted transaction occupies its sequence | Forget its pending packs; never add them again, even if compaction removed them from the manifest. |
| Another transaction occupies that sequence | The old CAS can no longer land. Its pending packs may be carried by a new publication. |
| The head has not reached that sequence, or verification fails | Keep the attempt unresolved. An older manifest is not proof that a delayed request failed. |

When the head is still behind the attempt, recovery appends an empty `PACK` entry using the normal
conditional manifest write. This fences the version the old request could still replace. The empty
entry changes the log head and manifest revision, but no refs, ref revision, or packs. If the old
request wins the race, the fence follows that history on retry. Either result lets recovery settle
the original identity from the chain. If the fence or its verification fails, recovery retains the
original record. The fence never carries pending packs and is never mistaken for their publication.
No fence or additional store request is added to healthy publication.

Settlement finishes before pending packs are moved into a new group. Each dequeued request gets a
terminal result even when recovery or preparation fails before group acceptance. A waiter interrupted
before it is taken is removed; one interrupted after it is taken waits for the group's result and
then retains its interrupt flag. Ref transactions whose folded table was replaced by compaction
revalidate and rebuild, just like transactions whose ref revision changed.

Read snapshots also need publication identity. A pack's shared pending record receives the attempted
sequence and transaction ID **before** the CAS is sent. A reader samples those records first, then
the manifest, then the attempt identities. Records already sampled survive removal from the node's
inventory. A transaction found in that manifest's chain is excluded from the unpublished view:
its pack is either still in the manifest or has been superseded. This also covers a committed write
whose response is still in flight, and a write that commits and is compacted between the two
snapshot reads. Readers never fence or mutate the inventory. A chain walk is necessary only when
an attempted publication trails the sampled head; packs carried by one attempt share the lookup.

The manifest CAS is the commit point. Maintenance notification or local JGit cache failures after
it must not report a durable ref update as failed. Failed local cache updates invalidate the cache
so a later read rebuilds it from the manifest.

All pending records and uncertainty are node memory only. A restart forgets both, so it cannot
re-add a forgotten pack. Its files are already represented by committed history or become orphans
that reclamation can remove. Recovery assumes linearizable conditional writes, immutable log entries
retained for the recovery interval, and a manifest history that does not roll back or reuse versions.
Reclamation waits a separate grace interval after first observing an eligible file as absent;
file age alone cannot protect a reader of the previous manifest. Upload-to-publication latency and
old reader lifetimes must each fit the configured grace period; see [compaction.md](compaction.md#reclamation).
This protocol does not add distributed publication or reader leases.

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
re-runs against the compacted manifest. On the same node a reftable compaction goes through the
repository's publisher like a ref transaction, so it queues behind a publication already in flight.
A ref transaction still uploading its folded table may be overtaken by compaction; if so, it rebuilds
against the new stack. Superseded files stay in the store for the reclamation grace period, which
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
