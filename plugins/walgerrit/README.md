# WalGerrit

WalGerrit is a storage integration that makes Gerrit Code Review use a WalGit-style immutable-pack
and manifest transaction model without changing Gerrit's NoteDb, permissions, transport or search
layers.

The project currently contains local-filesystem and S3-compatible WAL backends. It loads through
Gerrit's supported library-module hooks and stores JGit DFS packs, indexes, and reftables as
immutable objects. A protobuf manifest CAS publishes each transaction. The same committed WAL also
drives every node's local Gerrit search indexes, so a separate Kafka index-event broker is not
required for the implemented workflow.

## Requirements

- Java 21
- Maven 3.9 or newer
- Gerrit 3.14.2

The Java runtime is pinned in `.tool-versions`.

## Build

```bash
cd plugins/walgerrit
./mvnw verify
```

The deployable library is written to `target/walgerrit-0.1.0-SNAPSHOT.jar`.

## Install

Copy the JAR into Gerrit's primary classpath:

```bash
cp target/walgerrit-0.1.0-SNAPSHOT.jar "$GERRIT_SITE/lib/walgerrit.jar"
```

Add the following to `etc/gerrit.config`:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule

[walgerrit]
  backend = local
  storagePath = data/walgerrit
  indexCursorPath = data/walgerrit-index-events
  # A node further behind than this many log entries rebuilds its indexes instead of replaying.
  indexReplayLimit = 10000

[index "accounts"]
  commitWithin = 0
[index "changes_open"]
  commitWithin = 0
[index "changes_closed"]
  commitWithin = 0
[index "groups"]
  commitWithin = 0
[index "projects"]
  commitWithin = 0
```

`storagePath` is relative to the Gerrit site unless it is absolute. Restart Gerrit. Manifests are
now stored below `data/walgerrit/manifests/` and immutable pack, reftable and log objects below
`data/walgerrit/repos/`; `gerrit.basePath` is not used by this backend. `indexCursorPath` must be
node-local even when the WAL storage is shared.

Manifest freshness follows the Continuity model: a repository handle revalidates the manifest with
one conditional read (`If-None-Match` on the manifest ETag) when Gerrit opens it and again when it
starts a ref transaction. Between those points, JGit's in-memory pack list and reftable stack serve
every lookup. `walgerrit.manifestRevalidateInterval` (default `1 sec`) bounds how long a long-lived
handle may serve reads without another conditional read; `0` disables the periodic check so only
opens, ref transactions and `scanForRepoChanges` revalidate. All handles on a node share the newest
manifest any of them observed, including the index-event tailer, so a handle adopts a newer
manifest as soon as its node has seen one. See [Consistency](docs/consistency.md#freshness).

Daemon startup synchronously catches every node-local index cursor up to a freshly read manifest
before Gerrit's SSH and HTTP listeners start. A successful full sweep publishes the gauge
`walgerrit/index_events/ready` and creates `<indexCursorPath>/READY`; a later failed sweep or orderly
shutdown revokes both. A Kubernetes readiness probe should require that marker and a successful
request to the local Gerrit listener, so a marker left by a hard kill cannot make an early-starting
container ready. See [WAL-driven index events](docs/index-events.md#startup-and-readiness).

The zero `commitWithin` values are currently mandatory. WalGerrit advances a durable replay cursor
only after synchronous Lucene writes; allowing Lucene to defer its disk commit could otherwise lose
an acknowledged index event after a hard crash. The daemon refuses to start the tailer without
these settings. A later batched index-checkpoint implementation can remove this performance cost.

Compaction runs on every node: after a write, the node rolls undersized packs up geometrically and
merges a deep reftable stack, publishing each result through the manifest CAS, and a sweep every
`walgerrit.reclaimInterval` (default `6h`) deletes files the manifest no longer references once they
are older than `walgerrit.reclaimGrace` (default `24h`). The defaults suit production; the keys
`compactMinPacks`, `compactGeometricFactor`, `compactMaxPackSize`, `compactMinReftables`,
`compactionLeaseDuration`, `reclaimEnabled`, `cacheSizeLimit` and JGit's `core.dfs.blockLimit` tune
it. See [Compaction and reclamation](docs/compaction.md).

For S3-compatible storage, use a node-local cache as `storagePath` and configure the shared bucket.
The client pools connections (`walgerrit.s3MaxConnections`, default 64), fails a connect after
`walgerrit.s3ConnectTimeout` (2 s) and a stalled transfer after `walgerrit.s3SocketTimeout` (30 s),
and retries throttling, server errors and connection failures with the SDK's standard backoff up to
`walgerrit.s3MaxAttempts` (4) times; a retried write that had already landed is recognised as such:

```ini
[walgerrit]
  backend = s3
  storagePath = data/walgerrit-cache
  indexCursorPath = data/walgerrit-index-events
  s3Bucket = gerrit-git
  s3Region = eu-west-3
  s3Prefix = production
  # Set these for MinIO or another non-AWS endpoint.
  s3Endpoint = http://127.0.0.1:9000
  s3PathStyle = true
```

Credentials come from the standard AWS SDK provider chain (environment, workload identity, shared
AWS profile, container credentials, or instance role); they are not stored in `gerrit.config`.

Existing repositories are brought in with the `walgerrit-import` program, which uploads a tree of
bare repositories as they are, one manifest per repository, and verifies every ref afterwards:

```bash
java -jar gerrit.war walgerrit-import -d "$site" --source /backup/git --threads 8
```

See [Importing repositories](docs/import.md) for the preparation it expects, repacking and
`git fsck` on a scratch copy and the source server's `gerrit.serverId`, and for how a run resumes.

Build the fork WAR from the repository root, then run the fresh-site integration test:

```bash
npx @bazel/bazelisk build --config=java21 release
GERRIT_WAR="$PWD/bazel-bin/release.war" plugins/walgerrit/scripts/smoke-test.sh
```

The test builds the backend, initializes a fresh Gerrit site, verifies the `All-Projects` and
`All-Users` manifests, and reindexes entirely through WalGerrit. The same module is loaded by daemon
and batch programs; configuring a separate batch module would bind `GitRepositoryManager` twice.

Gerrit's own acceptance suite can run on the WalGerrit backend. The in-memory test server binds
`GitRepositoryManager` to WalGerrit's local backend whenever `GERRIT_WALGERRIT_JAR` names the built
library, loading it at runtime so the test framework needs no build dependency on it:

```bash
plugins/walgerrit/scripts/acceptance-tests.sh                       # everything
plugins/walgerrit/scripts/acceptance-tests.sh //javatests/com/google/gerrit/acceptance/api/change:api_change
```

`GERRIT_WALGERRIT_STORAGE` may point the repositories at a tmpfs, and
`GERRIT_WALGERRIT_COMPACTION=aggressive` makes every test server compact after two writes so the
whole suite races real compactions. Tests annotated `@UseLocalDisk`
keep running on the filesystem backend they ask for. The script passes
`--define=acceptance_heap=large`, which lifts the groups' 256 MB test heap to 512 MB: a test JVM
keeps every server it started reachable, and WalGerrit's per-server footprint is larger than the
in-memory manager's, so the biggest groups (`rest_account`) run out of heap otherwise.

Ten cases in four groups fail by design on any backend other than the in-memory manager, because
they assert on instrumentation that only `InMemoryRepositoryManager` provides: the four
`GitRepositoryReferenceCountingManagerIT` cases in `acceptance_framework_tests` (open-handle
reference counting) and the six `RefUpdateContext` cases in `git:DirectPushRefUpdateContextIT`,
`git:HttpSubmitOnPushIT` and `git:SshSubmitOnPushIT` (the `RefUpdateContextCollector`). Everything
else in the suite passes on WalGerrit.

## Storage model

For every repository, WalGerrit writes:

- `manifest.pb`: the CAS-replaced linearization point;
- `log/*.pb`: immutable transaction entries;
- `wal/*`: immutable JGit pack, index, and reftable files;
- `staging/*`: unpublished files that are safe to discard after failure.

Publication order is immutable files, immutable log entry, then manifest replacement. A stale ref
writer receives a JGit lock failure and must refresh before retrying. See the
[storage format](docs/storage-format.md), [consistency contract](docs/consistency.md), and
[JGit/CAS audit](docs/jgit-cas-deep-dive.md). See [WAL-driven index events](docs/index-events.md)
for the local-Lucene convergence and recovery contract.

## Fork boundary

The storage engine does not require a JGit fork. Gerrit's runtime Git paths and schema creation use
`GitRepositoryManager`, so the engine remains a separate module inside this Gerrit fork. The fork
changes Gerrit 3.14.2's init-only account, group, external-ID, authorized-key, and project-config
helpers to use a switching init repository manager. Before the system injector exists it preserves
Gerrit's local fallback; afterwards it delegates to the configured WalGerrit manager. No shadow
local `All-Users` repository is created.

The design follows Cursor's
[Git at any scale](https://cursor.com/blog/git-at-any-scale) and uses
[tobi/walgit](https://github.com/tobi/walgit) as its concrete WAL/object-store reference.

See [the architecture](docs/architecture.md), [CAS audit](docs/jgit-cas-deep-dive.md), and
[roadmap](docs/roadmap.md).

## Status

Experimental. Local and S3-compatible manifest CAS, cache materialization, durable ref-event
payloads, synchronous startup catch-up, node-local replay cursors and readiness, and the Gerrit
init/reindex path are implemented and tested. A two-node MinIO test passes project creation,
`refs/for/*` push, cross-node review/vote, submit, search convergence, and restart without manual
reindexing. The S3 object-store fault suite also passes.

A node whose index cursors cannot be replayed, including a new node with an empty volume on a busy
site, rebuilds its indexes from current repository state before it becomes ready. See [WAL-driven index events](docs/index-events.md#rebuilding-instead-of-replaying).

Compaction, reclamation and cache bounds are implemented and exercised by the unit suite, the smoke
test and an acceptance-suite run with aggressive thresholds. Still open before production: import
and migration tooling, durable repository deletion, replay-lag metrics, integrity checking, and a
pooled HTTP client for S3.
