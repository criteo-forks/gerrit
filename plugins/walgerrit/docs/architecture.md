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

Gerrit continues to maintain its existing local Lucene indexes. In a multi-instance deployment,
the existing index and cache event broker tells every instance to refresh its local derived state
after a shared Git transaction commits.

## Current milestone

The `local` backend is a real WAL-backed JGit DFS repository. JGit writes packs, indexes, and atomic
reftable transactions into staging files. WalGerrit moves those files into immutable `wal/` objects,
writes an immutable log entry, and atomically replaces `manifest.pb`. The manifest replacement is
the only publication point.

The filesystem implementation uses an OS file lock plus a JVM lock to serialize manifest CAS on one
shared local filesystem. Object-only pack additions can merge over a newer manifest because they do
not make refs visible. Ref updates require the reftable-stack revision they were prepared against.
Compaction verifies that every superseded input is still live while preserving concurrent additions.

## Target components

1. **Implemented:** pack-level JGit DFS storage backed by immutable WalGit files.
2. **Implemented:** atomic JGit reftables published through the manifest CAS.
3. **Implemented:** repository create, open, list, interrupted-create recovery, and stale-writer
   rejection.
4. **Next:** replace local durable files with an object-store interface and bounded local cache.
5. **Later:** a separately leased compactor using JGit/Git as the repacking engine, plus import,
   integrity checking, checkpoints, deletion, retention, and snapshots. Ordinary Gerrit nodes never
   run independent GC.
