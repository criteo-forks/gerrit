# WAL-driven search-index convergence

WalGerrit uses the committed Git WAL as the durable source of secondary-index work. This follows
the event bridge described by [WalGit](https://github.com/tobi/walgit/blob/main/docs/EVENTS.md): the
transaction is recorded with the write, followers use durable per-repository cursors, delivery is
at least once, and a periodic sweep is the correctness backstop. It also follows Cursor's
[Git at any scale](https://cursor.com/blog/git-at-any-scale) split: the object store is truth while
notifications or gossip are only latency optimizations.

## Write and replay protocol

1. Gerrit gives JGit a `BatchRefUpdate` containing the complete logical ref transaction.
2. JGit emits the immutable pack/index/reftable files.
3. WalGerrit writes a WAL entry containing those files and every logical ref update.
4. The manifest CAS publishes the files, refs, and index event together.
5. Before Gerrit's SSH or HTTP listeners start, each daemon reads a fresh manifest for every
   repository and synchronously replays unseen entries in sequence order.
6. After all synchronous index work for an entry succeeds, the daemon atomically writes its
   node-local cursor with the sequence and immutable log key.
7. Only after a complete clean sweep does the daemon publish readiness and start periodic sweeps.

If index work or the cursor write fails, the entry is retried. A crash after indexing but before the
cursor write also replays the entry, which is safe because the operations are idempotent. A
successful manifest CAS cannot acknowledge a Git write without also making its event durable.

## Ref-to-index mapping

| Durable ref update | Node-local action |
| --- | --- |
| `refs/changes/*/meta` and `refs/changes/*/robot-comments` | Replace or delete the change document |
| All-Users `refs/draft-comments/*` and `refs/starred-changes/*` | Replace the owning change document |
| All-Users `refs/users/*` | Replace or delete the account document |
| All-Users `refs/groups/*` | Replace or delete the group document |
| `refs/meta/config` | Evict project configuration, replace/delete the project document, and reindex its open changes |
| `refs/heads/*` | Reindex open changes targeting the branch for branch-derived fields |

Object-only pack entries and compaction entries advance the cursor without index work. The tailer
does not synthesize Gerrit's public `GitReferenceUpdated` stream events, avoiding a second external
event stream for the same committed write.

## Durability requirement

Gerrit's normal Lucene default may make a write visible before committing it to stable storage. If
WalGerrit fsynced its cursor during that window, a hard crash could preserve the cursor but lose the
index write. Until WalGerrit has a batched Lucene commit/checkpoint API, every affected index must
commit each write:

```ini
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

The daemon validates this configuration before starting the tailer. This is correct but potentially
expensive; replacing per-write commits with explicit batched index checkpoints is a production
performance milestone.

## Ordering and failure behavior

Ordering is exact within one repository and undefined across repositories. An All-Users star or
draft update can therefore arrive locally before the owning project's change event. A live update
whose change cannot yet be resolved fails without advancing the All-Users cursor and is retried on
the next sweep. Deletion of an All-Users ref may safely observe an already-deleted change.

The tailer also fails closed for:

- a `REF_UPDATE` entry without its logical transaction payload;
- a missing or out-of-order WAL sequence;
- a cursor ahead of the manifest;
- a cursor whose saved log key no longer matches manifest history; or
- any synchronous Gerrit index failure.

One repository's failure is logged and does not stop the same sweep from converging other
repositories. It does, however, fail the initial startup catch-up or revoke readiness during a
background sweep. A later complete clean sweep restores readiness.

## Startup and readiness

The index lifecycle listener belongs to Gerrit's system injector, which starts before the SSH and
HTTP injectors. Its first sweep is deliberately synchronous. If repository listing, WAL replay,
index application, cursor persistence, or readiness publication fails, Gerrit startup fails rather
than briefly serving with a stale local index.

A ready node exposes the Gerrit callback gauge `walgerrit/index_events/ready = true` and atomically
creates `<indexCursorPath>/READY`. Both remain false/absent until a full sweep succeeds and are
revoked on a failed later sweep or orderly shutdown. The file is node-local, just like the cursors.

For a Kubernetes `exec` readiness probe, combine the marker with a request to the node itself so a
marker left by a hard-killed prior process cannot make the new container ready before Java starts:

```sh
test -f /var/gerrit/data/walgerrit-index-events/READY &&
  curl -fsS http://127.0.0.1:8080/ >/dev/null
```

Adjust the site path and listener URL for the image. The signal means that the last full sweep was
clean; it is not a global linearizable barrier against a ref transaction committed immediately
after that sweep.

## Configuration

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule

[walgerrit]
  indexTailerEnabled = true
  indexPollInterval = 5 sec
  indexCursorPath = data/walgerrit-index-events
```

`indexCursorPath` must be on node-local durable storage beside that node's Lucene indexes. Sharing
it lets one node acknowledge work for another node and is invalid.

The current sweep lists all repositories and reads their manifests. S3/GCS finalize notifications
can later wake the same replay loop for low latency, while a slower full sweep remains the backstop.

## Rollout and recovery boundary

The current implementation is for fresh WalGerrit WAL history. Older `REF_UPDATE` entries do not
contain the logical transaction payload. Encountering one deliberately stops that repository and
asks for a full reindex plus cursor seed. That seed/recovery command is not implemented yet, so do
not enable the tailer over legacy WalGerrit data.

Likewise, retained WAL history is currently complete because checkpoints and WAL pruning are not
implemented. Before pruning exists, it must coordinate a full index checkpoint and seed so a new or
long-offline node never starts behind the oldest retained event.
