# WAL-driven search-index convergence

Each node derives its Lucene indexes from committed ref transactions. The logical ref payload
and its Git files share one manifest publication, so an acknowledged ref update cannot lose its
indexing intent. Search catches up asynchronously; it is not part of the Git transaction.

## Write and replay protocol

1. JGit prepares an atomic ref batch and its immutable files.
2. WalGerrit records the files and complete logical ref updates in a WAL entry.
3. Manifest CAS publishes the entry and refs together.
4. Each node reads unseen entries in repository sequence order and applies their index work.
5. After an entry's synchronous work succeeds, the node persists its cursor.

A crash between index application and cursor persistence causes replay. Index replacements and
deletions are idempotent, so delivery can be at least once. A failed index or cursor write leaves
the entry unacknowledged.

`EVENT` and `INDEX` entries share the log and cursor. `EVENT` entries carry best-effort Gerrit
notifications; both may carry the documents the writing node reindexed without a ref update, which
the tailer reindexes here, minus changes a ref transaction in the same sweep covers. The tailer
replays foreign-host entries and skips its own host's. Their failures and rebuild behavior differ
from index intents; see [Events](events.md).

## Ref-to-index mapping

| Ref update | Node-local action |
| --- | --- |
| `refs/changes/*/meta` and `refs/changes/*/robot-comments` | Replace or delete the change document. |
| All-Users `refs/draft-comments/*` and `refs/starred-changes/*` | Reindex the owning change. |
| All-Users `refs/users/*` | Replace or delete the account document. |
| All-Users `refs/groups/*` | Replace or delete the group document. |
| `refs/meta/config` | Evict project configuration, update the project document and reindex open changes. |
| `refs/heads/*` | Reindex open changes targeting the branch. |

Object-only and compaction entries advance the cursor without index work. The tailer does not
synthesize public ref events from the logical payload; those travel as separate `EVENT` entries.
An imported repository's initial files also lack a logical ref payload, so import requires an
[offline baseline reindex](import.md#after-the-import).

## Replay also evicts derived caches

Many Gerrit caches use a ref tip as part of their key. Group and project indexing also evicts
associated caches. `ReplicatedCacheEvictions` handles two additional cases:

- A user-ref update evicts the account's username from `sshkeys`.
- A group-ref update reads the old and new group revisions and evicts affected members,
  subgroups, names and legacy IDs from the membership caches. Both revisions matter for removals.

These evictions run with the ref transaction's replay and need no separate broker.

## Durability requirement

A replay cursor must never reach disk before the index writes it acknowledges. The current tailer
requires Lucene and synchronous commits for all affected indexes:

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

The daemon validates these settings at startup. Committing every write has a performance cost;
batched durable checkpoints remain future work.

## Ordering and failure behavior

Order is per repository. An All-Users draft or star update can arrive before its project's change
is indexed. A live update whose change cannot yet be resolved fails and retries on a later sweep;
a deletion can safely encounter an already deleted change.

A missing or malformed `REF_UPDATE` payload, missing log entry, sequence error, index failure or
cursor-write failure stops replay for that repository. Other repositories are still attempted.
A failed startup sweep prevents startup; a failed background sweep revokes readiness. A later
clean sweep restores it.

Cursors that are ahead of the head, on another history, or beyond the replay limit take the
rebuild path when enabled. They are not silently advanced past unknown index work.

## Startup and readiness

The system-injector lifecycle starts the tailer before Gerrit's serving listeners. Its first sweep
is synchronous. The sweep lists current manifest versions, reuses valid cursor/version matches,
and catches up changed repositories. It need not fetch every manifest body.

After a clean sweep, the daemon sets `walgerrit/index_events/ready = true` and creates
`<indexCursorPath>/READY`. Startup, a failed later sweep, and orderly shutdown clear readiness.
A hard kill can leave the file behind, so a probe must also check the local listener:

```sh
test -f /var/gerrit/data/walgerrit-index-events/READY &&
  curl -fsS http://127.0.0.1:8080/ >/dev/null
```

Adjust the paths and URL for the deployment. Readiness describes the most recently completed
sweep. It does not promise that every write committed afterward has been indexed. Revoking
readiness during a background rebuild does not close listeners; traffic routing must honor the
signal.

## Configuration

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule

[walgerrit]
  indexTailerEnabled = true
  indexPollInterval = 5 sec
  indexCursorPath = data/walgerrit-index-events
  indexReplayLimit = 10000
  indexRebuildOnStaleCursor = true
```

The shown values are defaults. Keep the cursor directory on durable node-local storage with the
indexes it acknowledges. Sharing it between nodes lets one node acknowledge another's work.
Restoring indexes and cursors independently can also skip required work; rebuild and reseed them
as a pair when their correspondence is uncertain.

## Discovery and change detection

One paginated listing of `manifests/` discovers repositories and their manifest versions. A
repository whose version matches the tailer's caught-up version needs no replay. On restart,
the version saved in its cursor provides the same shortcut.

For changed repositories, the tailer uses the node's cached manifest if it matches the listed
version, otherwise a conditional read. Replay reads the intervening log entries. A quiet S3
site therefore pays primarily for listing pages, rather than one read per repository. This
requires a store whose listing and conditional-write semantics satisfy the backend contract.

`indexPollInterval` is the scheduled delay after a sweep, not a maximum search-convergence time.
Sweep duration, log backlog, index work and retries add latency. Repository opens and ref
transactions also discover new manifests, but do not replace the tailer's index work.

## Replay

The manifest names the log head; each entry names its predecessor. The tailer walks backward to
the cursor, validates its transaction ID, then applies the entries forward. Catch-up reads one
log object per intervening entry. See [Storage format](storage-format.md#the-log-chain).

`indexReplayLimit` counts all publications, including object, compaction and notification entries.
If any repository exceeds the limit, the configured automatic rebuild covers all four logical
indexes, whichever repository triggered it.

## Rebuilding instead of replaying

A stale cursor triggers this sequence:

1. Capture every repository's current head sequence, transaction ID and manifest version.
2. Mark each index not ready, empty it, refill it with Gerrit's site indexer, then mark it ready.
3. Seed cursors at the captured heads.
4. Sweep again to replay writes published during the rebuild.
5. Publish readiness after the clean sweep.

At startup this finishes before listeners open. During a background rebuild readiness is revoked.
The work can be as expensive as a full offline reindex. A fresh node may need it, but the trigger
is the log-distance and history check, whether or not the volume is new.

With `indexRebuildOnStaleCursor = false`, stale cursors prevent readiness. For manual recovery,
stop all destination writers, complete an offline `reindex`, then run `walgerrit-mark-indexed`
for the affected node before restarting it. Removing cursors alone can immediately trigger the
same replay-limit failure. See [Import: after the import](import.md#after-the-import) for why
seeding requires a quiescent store.

An interrupted rebuild can leave an index marked not ready. Complete an offline reindex before
restarting in that case. Automatic rebuilds recover index state, not historical public events:
notifications before the captured heads are skipped.

## Format boundary

The backend accepts manifest format 3. Older layouts have no automatic migration. Missing log
objects or logical ref payloads are errors; they must not be treated as empty transactions.
