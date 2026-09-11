# Compaction and reclamation

Writes accumulate small packs and reftables. Compaction combines them into fewer files without
changing the stored Git objects or current refs. Reclamation later deletes unreferenced files.
These are separate operations: replacing a file in the manifest does not immediately delete it.

## What is rewritten

`CompactionPolicy` plans from the manifest inventory without storage I/O.

**Object packs use a geometric policy.** Candidates are sorted by size. The policy finds a break
in the progression between neighboring packs, then extends the selected prefix while its combined
size would crowd the next pack. It merges that prefix only when at least `compactMinPacks`
(default `8`) qualify. `compactGeometricFactor` defaults to `2`; input packs larger than
`compactMaxPackSize` (default `8g`) are excluded. That cap applies to inputs only; the merged
pack may be larger, and the result need not satisfy the factor at every step.

**Reftables merge from the top down.** Transaction tables and the small compacted tables directly
beneath them qualify once `compactMinReftables` (default `8`) accumulate. A compacted table larger
than `compactSmallReftableSize` (default `8m`) remains a base until the whole stack reaches
`compactMaxReftables` (default `32`), when all tables merge. Deletions must survive the merge so
older values cannot reappear.

JGit may also fold a small ref update into the top table during commit. WalGerrit allows that
only while the transaction is alone on its node; concurrent transactions extend the stack.

## Publication preserves concurrent work

`DfsPackCompactor` performs the rewrite. WalGerrit uploads its outputs and publishes an exact set
of additions and superseded inputs through manifest CAS. Every superseded input must still be
live. Concurrent additions remain in the resulting manifest; stale compaction outputs remain
unreferenced and can be reclaimed.

Object-pack and reftable compaction publish separately. Only the latter changes `ref_revision`.
A ref writer racing that change must revalidate and rebuild its table. On the same node,
reftable compaction uses the repository publisher queue. Writers do not wait for the repacking
work itself, but publication can queue or retry; bounded retries can still be exhausted.

## Who compacts, and when

After publication, a daemon evaluates the policy and queues due repositories on one maintenance
thread. For each repository it:

1. Acquires `leases/<project>.git/compaction`, or skips work if another node holds it.
2. Opens a handle and plans from its manifest view.
3. Compacts the selected object packs and reftables in separate publications.
4. Renews the lease after each rewrite and repeats, for at most four passes.
5. Evicts eligible superseded cache files and releases the lease.

With the default freshness settings, each new handle revalidates the manifest. Regardless of
freshness settings, publication checks reject superseded inputs that are no longer live.

A startup sweep and later sweeps queue repositories that became due without a local write,
including imports and batch-program writes. The default delay between sweeps is six hours
(`reclaimInterval`); work on the shared executor can delay them. Batch programs do not run this
background maintenance.

The compaction lease defaults to 30 minutes and is acquired or renewed by conditional write.
It avoids duplicate work. Correctness comes from the manifest CAS and input checks, so a lost
lease cannot authorize dropping another writer's data.

## Reclamation

Reclamation applies two intervals, each `reclaimGrace` (default `24 h`):

1. A `wal/` file must be old enough and absent from both the manifest and this node's unpublished
   pack view before the reclaimer starts tracking its absence.
2. A later sweep may delete it only after another full grace interval, while it remains absent
   with the same observed version and modification time.

The second interval protects readers of a recently retired manifest. A years-old pack may have
been compacted seconds ago; upload age alone says nothing about when readers stopped using it.

Observation records are node memory. A restart or a follower sweep drops them and postpones
deletion. Seeing a file live, young, missing or replaced resets its record. Log objects are never
reclaimed.

This relies on two operational bounds: upload-to-publication latency, including delayed requests
and deferred packs, and the lifetime of an old reader view must each fit within the grace period.
The implementation has no distributed writer or reader leases to enforce those bounds. Disable
reclamation when those assumptions cannot be met. Manifest revalidation alone does not bound the
lifetime of every in-flight reader.

Only the sweep-lease holder is selected to delete shared files. Its lease coordinates work but
does not provide hard exclusion; see [Sweep lease](events.md#the-sweep-lease-coordinates-housekeeping).
Bucket versioning and lifecycle retention, if configured, determine whether deleted object
versions remain recoverable. Keeping the log does not by itself keep historical Git data.

`reclaimEnabled = false` disables shared-file deletion. It also skips periodic cache eviction and
size trimming in the current implementation, though successful compactions still evict eligible
local files. `compactionEnabled = false` disables the maintenance executor, including its sweep.

## The node-local cache

With S3, superseded cached files can be evicted after compaction or during reclamation sweeps.
Files less than ten minutes old are protected from eviction. Pending files are included in the
live set for superseded-file eviction.

`cacheSizeLimit` defaults to `0` (unbounded). When enabled, a reclamation sweep trims the oldest
eligible cached files by modification time. Sweeps enforce it periodically, so young files,
staging data and writes between sweeps can exceed it. Sparse packs are counted by
logical file size. A later read fetches an evicted file again.

With the local backend, the cache is the store. Cache eviction and the size limit are disabled;
only reclamation may delete files.

JGit's process-wide block cache uses the larger of its default and one tenth of the JVM's
maximum heap, rounded down to a block boundary, unless `core.dfs.blockLimit` is configured. It
is separate from the disk cache.

## Configuration

All keys below belong to `[walgerrit]` except `core.dfs.blockLimit`.

| Key | Default | Meaning |
| --- | --- | --- |
| `compactionEnabled` | `true` | Run this node's background maintenance. |
| `compactMinPacks` | `8` | Minimum selected object packs to merge. |
| `compactGeometricFactor` | `2` | Factor used to select a geometric pack prefix. |
| `compactMaxPackSize` | `8g` | Maximum individual input pack size. |
| `compactMinReftables` | `8` | Minimum qualifying tables for a top-stack merge. |
| `compactSmallReftableSize` | `8m` | Largest compacted table included in a top-stack merge. |
| `compactMaxReftables` | `32` | Depth that triggers a whole-stack merge. |
| `compactionLeaseDuration` | `30 min` | Repository lease lifetime without renewal. |
| `sweepLeaseDuration` | `60 sec` | Shared reclamation lease lifetime without renewal. |
| `reclaimEnabled` | `true` | Run reclamation and periodic cache cleanup. |
| `reclaimGrace` | `24 h` | Minimum file age and subsequent absence-observation interval. |
| `reclaimInterval` | `6 h` | Scheduled delay between maintenance sweeps. |
| `cacheSizeLimit` | `0` | Periodic disk-cache size target; `0` disables trimming. |
| `core.dfs.blockLimit` | heap-based, with JGit default as a floor | Process-wide JGit block-cache size. |

## Deliberate limits

Compaction does not perform reachability-based garbage collection: it preserves objects in its
inputs, including unreachable ones. Reclamation removes unreferenced files, including failed
uploads, rather than individual unreachable objects from live packs. Multi-pack indexes and
bitmap generation are not part of the maintenance path; the compactor reuses existing deltas.
