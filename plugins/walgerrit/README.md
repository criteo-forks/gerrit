# WalGerrit

WalGerrit is a storage integration that makes Gerrit Code Review use a WalGit-style immutable-pack
and manifest transaction model without changing Gerrit's NoteDb, permissions, transport or search
layers.

The project currently contains the **Milestone 1 local WAL backend**. It loads through Gerrit's
supported library-module hook and stores JGit DFS packs, indexes, and reftables as immutable files.
A protobuf manifest is atomically replaced to publish each transaction; the local filesystem is the
source of truth for this milestone.

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

## Install the local WAL backend

Copy the JAR into Gerrit's primary classpath:

```bash
cp target/walgerrit-0.1.0-SNAPSHOT.jar "$GERRIT_SITE/lib/walgerrit.jar"
```

Add the following to `etc/gerrit.config`:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule

[walgerrit]
  backend = local
  storagePath = data/walgerrit
```

`storagePath` is relative to the Gerrit site unless it is absolute. Restart Gerrit. Repository data
is now stored below `data/walgerrit/repos/`; `gerrit.basePath` is not used by this backend.

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
[JGit/CAS audit](docs/jgit-cas-deep-dive.md).

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

Experimental. The local filesystem publication and concurrency contract is implemented and tested.
The Gerrit init fork, fresh-site init/reindex, and JGit compactor-to-manifest publication path are
also implemented and tested. S3-compatible storage, cache materialization, compactor lease
orchestration, checkpoints, import, deletion, and production fault-injection are not implemented
yet.
