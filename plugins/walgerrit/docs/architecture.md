# Architecture

WalGerrit replaces Gerrit's `GitRepositoryManager` binding while preserving the rest of Gerrit.

```text
Git clients
    |
Gerrit SSH/HTTP, permissions, submit rules and NoteDb
    |
GitRepositoryManager
    |
WalGerrit repository implementation
    |
WalGit immutable packs, transaction log and manifest
```

Gerrit continues to maintain its existing local Lucene indexes. Each manifest-committed ref
transaction contains its exact logical ref updates. Every Gerrit node tails that durable WAL from a
node-local cursor and synchronously applies the corresponding Gerrit index updates. Startup blocks
on one clean full catch-up before the serving listeners start; subsequent full-sweep health is
published through a metric and node-local readiness marker. No separate event broker is required
for search convergence.

## Current implementation

The `local` and `s3` backends are real WAL-backed JGit DFS repositories. JGit writes packs, indexes,
and atomic reftable transactions into node-local staging files. WalGerrit publishes those files as
immutable `wal/` objects, writes an immutable log entry, and compare-and-swaps `manifest.pb`. The
manifest CAS is the only publication point.

The filesystem implementation uses an OS file lock plus a JVM lock to serialize manifest CAS on one
shared local filesystem. S3 uses conditional object requests. Object-only pack additions can merge
over a newer manifest because they do not make refs visible. Ref updates require the reftable-stack
revision they were prepared against. Compaction verifies that every superseded input is still live
while preserving concurrent additions.

## Target components

1. **Implemented:** pack-level JGit DFS storage backed by immutable WalGit files.
2. **Implemented:** atomic JGit reftables published through the manifest CAS.
3. **Implemented:** repository create, open, list, interrupted-create recovery, and stale-writer
   rejection.
4. **Implemented:** JGit `DfsPackCompactor` output is published as one exact add-and-supersede
   manifest transaction; superseded files remain physically retained. Ordinary Gerrit GC is
   disabled at the manager and rejected at the DFS publication hook.
5. **Implemented:** S3-compatible conditional publication, node-local on-demand cache
   materialization, and fault tests for CAS conflicts and ambiguous success.
6. **Implemented:** WAL-native, at-least-once convergence of each node's accounts, changes, groups,
   and projects Lucene indexes from exact ref transactions, including synchronous initial catch-up
   and a readiness signal that is revoked after an incomplete sweep.
7. **Next:** add the separate per-repository compaction lease, cursor recovery tooling, scalable
   notification-driven index wakeups, and bounded cache eviction.
8. **Later:** reader-generation-aware reclamation, import, integrity checking, checkpoints,
   deletion, and snapshots. Ordinary Gerrit nodes never run independent GC.
