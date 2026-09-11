# WalGerrit

WalGerrit stores Gerrit's Git data in immutable files and publishes each ref transaction by
atomically replacing a manifest. Nodes share the store; each node keeps its own Lucene indexes
and follows the transaction log to update them. No separate index-event broker is required.

This is an experimental backend for the Gerrit 3.14.2 fork on `walgerrit-3.14`. It supports a
local filesystem and S3-compatible storage. It uses JGit's DFS and reftable APIs without a JGit
fork. See the [architecture](docs/architecture.md) for the integration boundary and the
[roadmap](docs/roadmap.md) for remaining work.

## Build

Use Java 21. The runtime is pinned in [.tool-versions](.tool-versions); the Maven wrapper supplies
Maven 3.9.11.

```bash
cd plugins/walgerrit
./mvnw verify
```

The library is `target/walgerrit-0.1.0-SNAPSHOT.jar`. Deploy it with the matching fork WAR; the
[deployment bundle](docs/artifact-bundle.md) contains both.

## Install

Copy the library onto Gerrit's primary classpath before initializing the site:

```bash
mkdir -p "$GERRIT_SITE/lib"
cp target/walgerrit-0.1.0-SNAPSHOT.jar "$GERRIT_SITE/lib/walgerrit.jar"
```

Configure `etc/gerrit.config`:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule

[walgerrit]
  backend = local
  storagePath = data/walgerrit
  indexCursorPath = data/walgerrit-index-events

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

Paths are relative to the site unless absolute. The local backend stores manifests under
`data/walgerrit/manifests/` and immutable files under `data/walgerrit/repos/`; it does not use
`gerrit.basePath`. The cursor directory must remain node-local, beside that node's Lucene indexes.

The index tailer requires Lucene and all five `commitWithin = 0` settings. It saves a replay
cursor only after synchronous index writes. Deferred Lucene commits could preserve the cursor
while losing the indexed data after a crash, so the daemon rejects that configuration.

For existing data, follow [Importing repositories](docs/import.md) before starting a daemon.
Changing the backend does not migrate repositories from `gerrit.basePath`.

## Share storage through S3

Keep the modules and index settings above. Replace the `[walgerrit]` section with:

```ini
[walgerrit]
  backend = s3
  storagePath = data/walgerrit-cache
  indexCursorPath = data/walgerrit-index-events
  s3Bucket = gerrit-git
  s3Region = eu-west-3
  s3Prefix = production
```

For MinIO or another custom endpoint, also set `s3Endpoint` and, if required, `s3PathStyle = true`.
Credentials come from the AWS SDK's default provider chain. Keep `storagePath` node-local: it is
a disposable file cache in S3 mode.

| S3 setting | Default | Meaning |
| --- | --- | --- |
| `s3MaxConnections` | `64` | HTTP connection-pool size. |
| `s3ConnectTimeout` | `2 sec` | Connection timeout. |
| `s3SocketTimeout` | `30 sec` | Timeout for a stalled connection and for acquiring a pooled connection. |
| `s3MaxAttempts` | `4` | Maximum attempts per SDK call, including the first. |

These keys belong to `[walgerrit]`. Retried conditional writes may have succeeded already;
WalGerrit resolves their outcome from committed history. See the
[consistency contract](docs/consistency.md#ambiguous-outcomes-and-recovery).

## Git reads and search have different freshness rules

By default, opening a repository and starting a ref transaction each revalidate its manifest.
Long-lived handles also check periodically. All handles adopt newer manifests observed on the
same node, so a handle is not a fixed snapshot for the duration of a request.

Search catches up through the WAL. Startup completes a full index sweep before opening Gerrit's
listeners. A clean sweep creates `<indexCursorPath>/READY` and sets
`walgerrit/index_events/ready`; a failed sweep or orderly shutdown revokes both. A readiness probe
must require both the marker and a successful request to the local listener. See
[index startup and readiness](docs/index-events.md#startup-and-readiness).

| Setting | Default | Meaning |
| --- | --- | --- |
| `manifestRevalidateOnOpen` | `true` | Revalidate on every repository open. |
| `manifestRevalidateInterval` | `1 sec` | Interval checked by active handles; `0` disables periodic checks. |
| `indexPollInterval` | `5 sec` | Delay between index sweeps. |
| `indexReplayLimit` | `10000` | Maximum log entries to replay per repository before rebuilding indexes. |

Setting `manifestRevalidateOnOpen = false` allows an open to reuse a recently validated node
view. This reduces reads during offline reindexing but gives up freshness at every open. Ref
transactions still revalidate. See [Freshness](docs/consistency.md#freshness).

## Cold reads fetch only the needed pack chunks

The S3 backend fetches indexes, bitmaps and reftables whole. Packs larger than
`packFetchChunkSize` (default `8m`) are fetched in chunks as JGit reads them. Smaller packs are
fetched whole. A sparse pack's `.chunks` sidecar records which chunks are present; without that
sidecar, the cached file is complete.

Set `rangedPackReads = false` to fetch all packs whole. The local backend always reads complete
files because its cache is the store.

## Maintenance preserves Git state

Daemon nodes compact small packs and merge reftables after publication. A per-repository lease
avoids duplicate repacking; the manifest CAS rejects stale inputs. A periodic sweep also queues
repositories that need maintenance.

Reclamation requires two grace checks: a file must be old enough and then remain unreferenced
for another grace period. Only the sweep-lease holder deletes shared files. Cache trimming is
node-local. Read [Compaction and reclamation](docs/compaction.md) before changing retention or
cache limits.

## Verify the integration

From the repository root, initialize the pinned submodules and build the WAR:

```bash
git submodule update --init --recursive
npx --yes @bazel/bazelisk build --config=java21 release
GERRIT_WAR="$PWD/bazel-bin/release.war" plugins/walgerrit/scripts/smoke-test.sh
```

The smoke script exercises initialization, reindexing, daemon readiness, compaction, restart,
index rebuilding and import. The same database module serves daemon and batch programs; adding a
separate batch database module would bind `GitRepositoryManager` twice.

Run Gerrit's acceptance tests on the local WalGerrit backend with:

```bash
plugins/walgerrit/scripts/acceptance-tests.sh
plugins/walgerrit/scripts/acceptance-tests.sh //javatests/com/google/gerrit/acceptance/api/change:api_change
```

The script builds the library, passes it as `GERRIT_WALGERRIT_JAR`, and uses a 512 MiB test heap.
`WALGERRIT_ACCEPTANCE_TMP` selects its scratch directory. To exercise aggressive compaction:

```bash
BAZEL_TEST_FLAGS='--test_env=GERRIT_WALGERRIT_COMPACTION=aggressive' \
  plugins/walgerrit/scripts/acceptance-tests.sh
```

`@UseLocalDisk` tests retain their requested filesystem backend. Tests that assert on
`InMemoryRepositoryManager` instrumentation need separate interpretation: these include
`GitRepositoryReferenceCountingManagerIT` and the ref-context tests in
`DirectPushRefUpdateContextIT`, `HttpSubmitOnPushIT` and `SshSubmitOnPushIT`. Consult the actual test
results for the revision being deployed.

## Read further

| Topic | Guide |
| --- | --- |
| Components and fork boundary | [Architecture](docs/architecture.md) |
| Files, manifests and log entries | [Storage format](docs/storage-format.md) |
| Publication, recovery and freshness | [Consistency](docs/consistency.md) |
| JGit integration and test coverage | [JGit/CAS audit](docs/jgit-cas-deep-dive.md) |
| Local search and recovery | [Index events](docs/index-events.md) |
| Cross-node notifications | [Events in the WAL](docs/events.md) |
| Shared login cookies | [Web sessions](docs/web-sessions.md) |
