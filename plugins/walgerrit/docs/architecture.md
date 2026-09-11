# Architecture

WalGerrit separates authoritative Git state from rebuildable node state. The shared store holds
immutable Git files, a transaction log and one mutable manifest per repository. Each Gerrit node
keeps local Lucene indexes and, with S3, a disposable cache of immutable files.

```text
Git clients
    |
Gerrit SSH/HTTP, permissions, submit rules and NoteDb
    |
GitRepositoryManager
    |
WalGitRepositoryManager + JGit DFS/reftables
    |
Immutable files -> immutable log entry -> manifest CAS
                          |
                  Each node's WAL tailer
                          |
                  Local Lucene and caches
```

## The manifest publishes the transaction

JGit writes packs, indexes and reftables into local staging files. WalGerrit uploads them, writes
an immutable log entry, then replaces the manifest with a compare-and-swap (CAS). That CAS makes
the transaction visible. A ref cannot become visible before its objects are available.

The local backend serializes conditional writes with JVM and OS file locks, then uses atomic
filesystem replacement. The S3 backend uses opaque ETags and conditional requests. Object-only
additions can merge over concurrent publications; ref transactions must also validate the
reftable-stack revision. Compaction must find all its superseded inputs still live.

See [Consistency](consistency.md) for retries, ambiguous outcomes and freshness, and
[Storage format](storage-format.md) for the persisted data.

## Every node derives its own search indexes

A ref transaction's WAL entry includes its logical ref updates. Each node applies them to its
local indexes and saves a durable cursor after the index writes finish. A listing of the
`manifests/` prefix discovers repositories and changed manifest versions.

Startup catches up before Gerrit opens its listeners. Later sweeps maintain readiness; a cursor
that cannot be replayed can trigger a full rebuild. Search convergence is asynchronous and has
no global order across repositories. See [Index events](index-events.md).

Public Gerrit notifications use separate, best-effort `EVENT` entries. Their delivery guarantee
is weaker than the durable ref payload used for indexing. See [Events](events.md).

## Maintenance uses the same publication rules

Writing nodes queue geometric pack compaction and reftable merging. Per-repository leases reduce
duplicate work; manifest CAS and live-input checks preserve concurrent writes. The sweep-lease
holder reclaims unreferenced shared files after the retention checks. Each node manages its own
S3 cache. Ordinary Gerrit GC is disabled. See [Compaction](compaction.md).

## The fork connects Gerrit to the backend

The storage module replaces `GitRepositoryManager`. Gerrit's runtime NoteDb and Git transport
paths continue to use JGit APIs. Init-only helpers use a switching repository manager: a local
fallback before the system injector exists, then the configured backend for post-init work.

The fork also supplies the import and cursor-seeding commands, an acceptance-test adapter, and
optional stateless web sessions. Storage integration does not require a JGit fork. The
[JGit audit](jgit-cas-deep-dive.md) describes that boundary; the [roadmap](roadmap.md) separates
implemented features from remaining work.
