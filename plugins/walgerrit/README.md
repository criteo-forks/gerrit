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
- Gerrit 3.12.2

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

`storagePath` is relative to the Gerrit site unless it is absolute. Restart Gerrit. Repository data
is now stored below `data/walgerrit/repos/`; `gerrit.basePath` is not used by this backend.
`indexCursorPath` must be node-local even when the WAL storage is shared.

The zero `commitWithin` values are currently mandatory. WalGerrit advances a durable replay cursor
only after synchronous Lucene writes; allowing Lucene to defer its disk commit could otherwise lose
an acknowledged index event after a hard crash. The daemon refuses to start the tailer without
these settings. A later batched index-checkpoint implementation can remove this performance cost.

For S3-compatible storage, use a node-local cache as `storagePath` and configure the shared bucket:

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

This milestone is intended for a new Gerrit site. Importing existing repositories is deliberately
not implicit and remains a later milestone.

Build the fork WAR from the repository root, then run the fresh-site integration test:

```bash
npx @bazel/bazelisk build --config=java21 release
GERRIT_WAR="$PWD/bazel-bin/release.war" plugins/walgerrit/scripts/smoke-test.sh
```

The test builds the backend, initializes a fresh Gerrit site, verifies the `All-Projects` and
`All-Users` manifests, and reindexes entirely through WalGerrit. The same module is loaded by daemon
and batch programs; configuring a separate batch module would bind `GitRepositoryManager` twice.

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
changes Gerrit 3.12.2's init-only account, group, external-ID, authorized-key, and project-config
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
payloads, node-local replay cursors, and the Gerrit init/reindex path are implemented and tested. A
two-node MinIO test passes project creation, `refs/for/*` push, cross-node review/vote, submit,
search convergence, and restart without manual reindexing. The S3 object-store fault suite also
passes.

It is not production-ready yet: compactor lease/fencing and generation-aware pack reclamation are
still missing; index notification wakeups and scalable repository sweeping are not implemented;
cursor-gap recovery and legacy-WAL cursor seeding need an operator command; the cache has no size
bound; migration, deletion, metrics, integrity tooling, and broader Gerrit acceptance coverage
remain open.
