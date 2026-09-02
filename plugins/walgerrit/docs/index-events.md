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
   node-local cursor with the entry's sequence and transaction id.
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
  logSegmentEntries = 256
  logRetention = 30 days
  logRetainEntries = 10000
  indexRebuildOnStaleCursor = true
```

`indexCursorPath` must be on node-local durable storage beside that node's Lucene indexes. Sharing
it lets one node acknowledge work for another node and is invalid.

## Discovery and change detection

Each sweep is one paginated listing of the `manifests/` prefix. Because every manifest lives under
that prefix and nothing else does, the listing enumerates every repository together with the
current version of its manifest, which on S3 is the object's ETag and is returned by the listing
itself. The tailer remembers the version at which it last brought each repository's cursor to head
and replays a repository only when the listed version differs, taking the manifest from the node
cache when another handle already fetched that version and reading it conditionally otherwise. An
unchanged repository therefore costs nothing beyond its share of the listing: a sweep over 4300
repositories is five requests when nothing changed, plus one manifest read and its new log entries
per repository that did.

This is the only mechanism by which a node learns about other nodes' writes, so
`indexPollInterval` is the cross-node search convergence latency. S3 lists are strongly consistent,
so a manifest published anywhere appears in the next listing. A node that restarts forgets what it
had caught up to and reads every manifest once during its startup sweep, exactly as before.

Cursor's design adds gossip between replicas as a latency optimization on top of the same kind of
conditional check. WalGerrit does not need it while a sweep costs a handful of requests; if
sub-interval convergence is ever required, a peer wake-up can be added on the publication path
without changing this contract.

## Folding and the retention floor

After replaying a repository, the sweep folds its log: runs of `walgerrit.logSegmentEntries`
single-entry segments become one segment, and segments older than `walgerrit.logRetention` drop
below the manifest's floor once `walgerrit.logRetainEntries` newer entries remain. The defaults are
256 entries per segment, 30 days and 10,000 entries. Folding never deletes an object; see
[storage format](storage-format.md#folding).

A cursor is validated against the entry it names: at the head or a segment boundary from the
manifest alone, inside a segment while that segment is read for replay, so validation never costs
an extra read.

## Rebuilding instead of replaying

A cursor below the floor, ahead of the head, or naming a transaction the manifest no longer does
cannot be advanced by replay. A brand-new node with an empty volume is the common case: every
repository whose log has ever been folded is below its floor. Rather than replaying a long history
one entry at a time, the node rebuilds all four indexes from current repository state, the way the
offline `reindex` program does, emptying each index first so documents for deleted changes cannot
survive:

1. Record every repository's current head sequence and transaction id.
2. Mark each index not ready, empty it, refill it with Gerrit's site indexer, mark it ready.
3. Seed every cursor from the heads recorded in step one, then replay the tail published meanwhile.
4. Publish readiness.

At startup this runs before Gerrit opens its listeners, so the node serves nothing while an index
is empty; a background sweep that detects the condition revokes readiness for the duration. The
rebuild takes as long as an offline reindex of the site. With the default retention only a node that
was down or broken for a month, or one with a fresh volume, ever takes this path; a node down for an
hour replays its tail in seconds.

`walgerrit.indexRebuildOnStaleCursor = false` disables the automatic rebuild; the daemon then refuses
to become ready and names the repositories and the cursor directory in its error, and the remedy is
the offline `reindex` followed by removing the cursors. If a rebuild is interrupted, Gerrit refuses
to start until the offline `reindex` has run, because the index was marked not ready. To force a
rebuild deliberately, stop the node and remove its cursor directory: on a folded repository the
empty cursor is below the floor.

## Rollout and recovery boundary

Manifest format version 2 is not readable by earlier WalGerrit builds and does not read their data;
no deployment holds data in the earlier layout. A `REF_UPDATE` entry without its logical
transaction payload is a corruption and stops replay for that repository.
