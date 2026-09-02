# Compaction and reclamation

Every NoteDb write leaves one object pack, one pack index and, unless JGit folds it into the top of
the stack at commit time, one reftable. Without compaction a repository accumulates thousands of
tiny files: every object lookup consults every pack index, the reftable stack deepens, the manifest
grows, and the block cache fills with indexes. Compaction rewrites those files into fewer, larger
ones and reclamation deletes what nothing references any more. Neither changes a single object or
ref: compaction is representation only, and everything it publishes goes through the same manifest
compare-and-swap as a write.

This follows Cursor's design for Continuity: compaction is a WAL event that replicas follow by
downloading the compacted result rather than repacking themselves, the policy is geometric, and an
idle local copy is disposable. The two deliberate differences are that Gerrit nodes are symmetric,
so a lease stands in for Cursor's per-repository primary, and that superseded files are deleted
after a grace period rather than kept forever; the log of every push and every compaction is kept.

## What is rewritten

The policy is a function of the manifest's pack list alone, so it costs no round trip and runs on
the write path after a publication.

**Object packs** follow Git's geometric repacking rule (`git repack --geometric`). Sorted by size,
every pack should be at least `walgerrit.compactGeometricFactor` (default 2) times as large as all
smaller packs combined. The smallest packs that break the progression, extended upward until it
holds again, roll up into one `COMPACT` pack, but only once `walgerrit.compactMinPacks` (default 8)
of them have accumulated, so a repository is not rewritten after every write. Packs above
`walgerrit.compactMaxPackSize` (default 8g) are never rewritten. In steady state a repository holds
a few large packs in geometric progression and a short tail of recent small ones, and the amount of
data rewritten per byte written is logarithmic in the repository's size.

**Reftables** are compacted whole: once the stack is `walgerrit.compactMinReftables` (default 8)
tables deep it is merged into one `COMPACT` table, deletions included. JGit already folds a small
new table into the top of the stack at commit time, so the stack deepens only by about one table per
12 KB of ref and reflog data. A partial merge would have to respect the order JGit derives from the
pack source and the update index, and is not worth the risk for tables that are small next to object
data.

JGit's `DfsPackCompactor` is the repacking engine for both. Object compaction and reftable compaction
are separate manifest transactions, because a reftable change advances the ref revision and a ref
transaction in flight on another node must notice it. On the compacting node itself a reftable
compaction is published under the repository's write lock, the one ref transactions hold, so a local
transaction in flight finishes first and the next one starts from the compacted manifest; local
writers never lose a CAS to local compaction. Object compaction needs no such care: it leaves the
ref revision alone, and a writer whose CAS it pre-empts merely retries the CAS on the merged
manifest without rewriting anything.

## Who compacts, and when

**The node that writes compacts.** After every publication the writer evaluates the policy on the
manifest it just produced. If anything is due, the repository is queued on the node's single
compaction thread, one compaction per repository at a time. The compactor then:

1. takes the repository's lease (below), or skips the repository if another node holds it;
2. opens a fresh handle, which reads the newest manifest, and plans against it;
3. rolls the planned packs up, then the reftable stack if due, publishing each result as one
   add-and-supersede transaction; the CAS refuses the result if any input is no longer live;
4. repeats from step 2, up to four passes, until the policy is satisfied;
5. evicts superseded files from its own cache and releases the lease.

A compaction that loses its inputs to another node's compaction deletes its own output, which
nothing references, and re-plans on the fresh manifest. Concurrent writes are merged into the
compaction's manifest update, so a push or a ref update never waits for a compaction and never
fails because of one: a ref transaction whose CAS is lost to a reftable compaction re-runs itself
against the new stack, as [consistency.md](consistency.md#ref-transactions) describes.

**A sweep at start and every `walgerrit.reclaimInterval` (default 6h)** lists every repository,
reads each manifest once, and queues the ones that fell due without this node writing to them:
repositories imported or written by a batch program, or written while compaction was off. Batch
programs such as `init` and `reindex` never compact.

**The lease** is a small object at `leases/<project>.git/compaction` holding an owner and an
expiry (`walgerrit.compactionLeaseDuration`, default 30 minutes, renewed between passes). It is
acquired by creating the object or by replacing an expired one through a CAS on its version, and
released by writing an expiry of zero. The lease only prevents two nodes repacking the same
repository at once; correctness comes from the manifest CAS and its live-supersedes check. A lease
lost or expired mid-compaction wastes an upload, never data.

## Reclamation

A file beneath `wal/` that the current manifest does not list and whose store timestamp is older
than `walgerrit.reclaimGrace` (default 24h) is deleted by the sweep. That one rule covers packs and
reftables superseded by compaction, reftables of ref transactions that lost their CAS, and outputs
of compactions that lost theirs. The grace period is what makes it safe: it exceeds by orders of
magnitude the seconds between a file's upload and the publication that references it, and any reader
still holding an older manifest, since handles revalidate every `walgerrit.manifestRevalidateInterval`.
On a versioned bucket a deleted file remains recoverable as a non-current version for the bucket's
lifecycle window, which is the real knob for how far back object data can be rewound. Log objects
are never deleted; they remain the complete history of every ref change and every compaction.

Reclamation is on by default and `walgerrit.reclaimEnabled = false` turns it off; compaction then
keeps publishing and files accumulate until it is turned back on.

## The node-local cache

With an object store backend the files beneath `storagePath` are a cache. After a compaction the
compacting node evicts superseded files from its own cache, and every sweep evicts files the manifest
no longer lists. Files written in the last ten minutes are never evicted, since they may be uploads
whose publication has not landed. `walgerrit.cacheSizeLimit` (default `0`, unbounded) sets a size
above which the sweep deletes the oldest cached files first; a handle that needs an evicted file
fetches it again from the store. With the local backend the cache directory *is* the store, so
nothing is ever evicted there and the size limit is ignored; reclamation's grace rule is the only
deletion.

JGit's process-wide block cache, which holds pack blocks and pack indexes for every open repository,
is sized to a tenth of the heap at startup unless `core.dfs.blockLimit` is set in `gerrit.config`.
JGit's own default of 32 MB suits a laptop, not a server holding thousands of repositories.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `walgerrit.compactionEnabled` | `true` | Run the compactor and the sweep on this node. |
| `walgerrit.compactMinPacks` | `8` | Smallest run of undersized packs worth rolling up. |
| `walgerrit.compactGeometricFactor` | `2` | Each pack should be this many times all smaller packs combined. |
| `walgerrit.compactMaxPackSize` | `8g` | Packs above this size are never rewritten. |
| `walgerrit.compactMinReftables` | `8` | Stack depth at which the reftable stack is merged into one table. |
| `walgerrit.compactionLeaseDuration` | `30 min` | Lease lifetime without renewal. |
| `walgerrit.reclaimEnabled` | `true` | Delete unreferenced store files past the grace period. |
| `walgerrit.reclaimGrace` | `24 h` | Minimum age of an unreferenced file before it is deleted. |
| `walgerrit.reclaimInterval` | `6 h` | Period of the sweep that reclaims and queues overdue repositories. |
| `walgerrit.cacheSizeLimit` | `0` | Node-local cache size above which the oldest cached files are dropped. |
| `core.dfs.blockLimit` | a tenth of the heap | JGit block cache size in bytes. |

## What is deliberately not done

- No reachability-based garbage collection: objects are only ever re-packed, never dropped.
  Gerrit repositories keep almost everything reachable through change refs, and skipping GC keeps
  "the store never loses anything" trivially true.
- No multi-pack index yet. JGit's DFS layer supports one; written alongside a compacted pack it
  would cut per-lookup index scans further between compactions.
- No bitmaps or delta re-compression tuning; JGit's compactor reuses existing deltas.
