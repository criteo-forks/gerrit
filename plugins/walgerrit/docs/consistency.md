# Consistency contract

A repository's manifest CAS is its commit point. Immutable files must exist before that CAS;
local caches and search indexes may catch up afterward. There is no transaction spanning several
repositories.

The local backend uses locks, fsync and atomic filesystem replacement. The S3 backend uses
immutable puts and conditional manifest writes. Both require storage semantics that preserve
atomic publication and readable immutable dependencies.

## Publication order

1. Persist every immutable object pack, index and reftable needed by the transaction.
2. Persist its immutable log entry.
3. Compare-and-swap the manifest.
4. Report success after the commit is known to have landed.

Acknowledgement depends on the store's visibility guarantees, not on polling every Gerrit node.
Search indexes need not have converged before a Git write succeeds. Objects may precede their
refs; refs must never precede their objects.

## Ref transactions

An atomic Gerrit `BatchRefUpdate` validates expected old values and publishes all accepted ref
changes together. `ref_revision` identifies the reftable stack used for validation. Object-only
publications leave it unchanged; any live reftable-stack change advances it.

All handles for a repository on one node share a `GroupPublisher`. Admission prevents concurrent
local transactions from touching the same ref or conflicting parent/child ref names. JGit
validation and table construction use the node lock; publication can batch independent prepared
transactions into one log entry and one manifest CAS.

Object inserters and received-pack parsers upload files before ref publication. Their packs remain
pending on the node until a ref transaction carries them or the originating handle closes and
flushes them. Local readers can see these pending objects. Other nodes see them only after
manifest publication.

Across nodes, manifest CAS is the publication fence. If another node changes the ref revision,
a losing ref batch refreshes, validates again and rebuilds its table, for at most five total
attempts. A changed expected value, exhausted retries or publication failure can surface as a
JGit lock failure. Retrying never silently overwrites a conflicting ref.

A returned manifest can include later ref changes beyond the publishing group's own work. That
ends the validation epoch of queued transactions. A transaction whose folded table was replaced
by compaction must also rebuild. Uploaded tables from losing attempts remain unreferenced.

## Ambiguous outcomes and recovery

A write error does not prove that a CAS failed. Even a precondition error may be an SDK retry of
a request that already succeeded. WalGerrit verifies every CAS error against the attempted
sequence and transaction ID, including unchecked SDK failures.

| Evidence in committed history | Meaning |
| --- | --- |
| The attempt occupies its sequence. | It committed. Never publish its pending packs again, even if compaction has since removed them. |
| A different transaction occupies that sequence. | The old CAS can no longer land. Uncommitted pending packs may enter a new publication. |
| The head has not reached that sequence, or verification fails. | The outcome remains unresolved. Preserve the attempt's identity. |

Recovery resolves uncertainty before moving pending packs into another publication. If the head
is still behind the attempt, it appends an empty `PACK` entry using the normal conditional write.
This fences the version the delayed request could still replace. If the old request wins first,
the fence follows that history. Either outcome lets recovery determine which transaction occupies
the original sequence.

The empty entry changes the log head and manifest revision but no files, refs or ref revision.
It carries no pending packs. If fencing or verification fails, the original attempt stays
unresolved. Healthy publication needs no such fence.

Every dequeued request receives a terminal result, including when recovery fails before the group
is accepted. A waiter interrupted before dequeue is removed. One interrupted afterward waits for
the group's result and preserves its interrupt flag.

### Readers must not resurrect retired packs

A pending pack record receives the attempted sequence and transaction ID before the CAS is sent.
Readers sample pending records first, then the manifest, then the attempt identities. The sampled
records remain valid even if publication removes them from the node's pending inventory.

If the sampled manifest's chain contains the attempt, its packs are excluded from the unpublished
view. They are either still live in the manifest or have been superseded. This covers a committed
write whose response is in flight, including one already followed by compaction. Readers resolve
identity without fencing or changing pending state; packs from one attempt share the history check.

Pending records and unresolved outcomes exist only in node memory. A restart forgets them and
cannot re-add them. Their files are either covered by committed history or left for reclamation.

### A local failure cannot undo a commit

Once a CAS is known to have landed, maintenance-notification or JGit-cache failure must not turn
it into a reported Git failure. Cache invalidation lets a later read reconstruct the local view.
If the outcome cannot yet be verified, it remains unknown; the client must not infer failure from
a timeout alone.

Recovery assumes linearizable conditional writes, immutable log entries retained throughout
recovery, and manifest history that does not roll back or reuse versions. Restoring an older
manifest while writers are active violates those assumptions. Index cursor mismatch detection is
not a live Git rollback protocol.

## Freshness

The store's manifest is authoritative. A handle revalidates it:

1. On repository open, by default.
2. At the start of each ref-transaction attempt, before expected-value validation.
3. On `scanForRepoChanges`.
4. During active reads when `manifestRevalidateInterval` has elapsed, `1 sec` by default.

A read triggers the periodic check; there is no background timer. Setting the interval to `0`
disables it; opens, ref transactions and explicit scans still revalidate. Conditional reads use
the node's latest known version, with `If-None-Match` on S3.

A manifest observed by any handle or the index tailer becomes available to other handles on that
node. Their next lookup can adopt it without another manifest request. An open handle is
therefore not a fixed request-wide snapshot; later lookups may observe a newer committed state.
JGit's individual read operations still use their own in-memory structures.

With `manifestRevalidateOnOpen = true` and the required store semantics, an open sees writes
acknowledged before its revalidation. Setting it to `false` permits reuse of a node view validated
less than `manifestRevalidateInterval` ago, giving up that per-open guarantee. This can reduce
I/O for an offline reindex against a quiescent store. A nonpositive interval never qualifies a
cached view as recently validated for this optimization.

A tailer listing can confirm an unchanged manifest version and refresh the node's validation
time. Polling delay, sweep duration and failures add to the staleness a node can observe; the
configured intervals are not end-to-end bounds.

## Local disk is a cache

With S3, whole immutable files are materialized by temporary write, fsync and rename. A chunked
pack uses a sparse file plus a `.chunks` sidecar. Chunk data is forced before the sidecar records
it; the sidecar disappears only when every chunk is present. Eviction removes the data file
before its sidecar so a partial file cannot be mistaken for a complete one.

With the local backend, these same file paths are authoritative store data and cannot be evicted
as a cache. See [Storage format](storage-format.md).

## Compaction

Compaction preserves Git objects and current refs while replacing their files. Publication
requires all superseded inputs to remain live and preserves concurrent additions. Reftable
compaction advances `ref_revision`; affected ref writers revalidate rather than reuse a stale
table. Superseded files remain available until reclamation's grace checks allow deletion.

Reader lifetimes and upload-to-publication latency must each fit within the configured grace
period. The backend does not enforce those bounds with distributed reader or publication leases.
See [Compaction and reclamation](compaction.md#reclamation).

## Derived state

The ref transaction's logical payload is durable in the same publication as its reftable. Each
node applies it to Lucene in repository sequence order and saves a cursor after synchronous
index writes. Replaying an entry after a crash is safe because index replacement and deletion
are idempotent. This requires Lucene and `commitWithin = 0` for accounts, both change sub-indexes,
groups and projects.

A malformed payload, log gap or index failure stops progress for that repository. A stale or
divergent cursor can trigger a full index rebuild. Startup completes a clean sweep before
listeners open; a failed background sweep revokes readiness. Readiness reports the last sweep,
not a cross-repository snapshot or a barrier against later writes.

Repository streams have no global order. An All-Users draft or star update that depends on a
change not yet discoverable in its project is retried. See [Index events](index-events.md).
Separate public `EVENT` notifications are [best effort](events.md), with possible loss and
duplication; they do not inherit the ref payload's durability guarantee.
