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
   The same file holds the merges of this node whose change-merged event is not in the log yet,
   so passing a ref transaction and recording what it merged are one write; see
   [Events](events.md#recovering-a-submits-change-merged-event-after-restart).

A crash between index application and cursor persistence causes replay. Index replacements and
deletions are idempotent, so delivery can be at least once. A failed index or cursor write leaves
the entry unacknowledged.

`EVENT` and `INDEX` entries share the log and cursor. `EVENT` entries carry best-effort Gerrit
notifications. Both may identify documents reindexed without a ref update. The tailer reindexes
those documents on other nodes, skipping changes already covered by ref transactions in the same
replay. It skips its own host's events and index updates. See [Events](events.md) for their failure
and rebuild behavior.

## Ref-to-index mapping

| Ref update | Node-local action |
| --- | --- |
| `refs/changes/*/meta` and `refs/changes/*/robot-comments` | Replace or delete the change document. |
| All-Users `refs/draft-comments/*` and `refs/starred-changes/*` | Reindex the owning change. |
| All-Users `refs/users/*` | Replace or delete the account document. |
| All-Users `refs/groups/*` | Replace or delete the group document. |
| `refs/meta/config` | Evict project configuration, update the project document and reindex open changes. |
| `refs/heads/*` | Reindex open changes targeting the branch when Gerrit indexes mergeability or the site enables `walgerrit.reindexOnBranchUpdate`. |

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
is indexed, whichever node wrote it: a node replays its own All-Users entries too, and has a change
another node created only once it has replayed that creation. The change can also have been
deleted since. Neither is a failure. The entry is passed and its account and group updates are
applied; the change's document is left to the replay of the entry that creates the change, which
indexes it with the stars and drafts All-Users holds at that time.

The cursor that passes such an entry lists the change, in the same file replacement, until the
node has indexed it. A change leaves the list when:

- a replay of its own repository indexes it, or removes it because it was deleted;
- the lookup at the end of a sweep finds it in the index, and indexes it again. This is a change
  whose document a request of this node was writing when the star or draft was replayed;
- the indexes are rebuilt;
- it has been listed for an hour of one process's run and is still not in the index. Nothing on
  the node says which repository held a change its index does not hold, so the change is taken
  to be deleted, and the log says so.

A process that starts reads the lists from its cursors and looks again.

A missing or malformed `REF_UPDATE` payload, missing log entry, sequence error, index failure or
cursor-write failure stops replay for that repository. Other repositories are still attempted.

A sweep replays a repository whose replay failed once more after the others. Before it does, it
lists the store again and replays what is behind that listing, since what a failed entry needs
may have been published after the sweep's first listing. The second replay stops at the head the
node knew when the first one failed: later entries may wait for publications later still, and
are left to the next wake-up or sweep. The extra listing is made only in a sweep where a replay
failed. A replay that fails again fails the sweep. A wake-up that fails is left to the next sweep.

## The catalog is replayed first

Each sweep replays the [catalog](namespace.md) before any repository. A catalog commit names the
bindings it changed; the tailer evicts those projects' caches, refreshes the project list, and
for each activated name reindexes the project and every change in the repository under that
name. For a retired name it deletes the change documents by project and drops the project
document. A repository whose name is pending is left where it is, and replayed once the name is
active.
Replayed entries are always indexed under the name the catalog binds the repository to.
A failed sweep revokes readiness, or withholds it during the startup catch-up. The next clean
sweep publishes it.

Cursors that are ahead of the head, on another history, or beyond the replay limit take the
rebuild path when enabled. They are not silently advanced past unknown index work.

## Startup and readiness

The tailer's first sweep is the startup catch-up: it replays what was published while the node
was away. It runs on the tailer's thread, so Gerrit's listeners open at once, whatever the
backlog. Until that sweep completes cleanly the node's indexes are behind, and the node answers
nothing but a liveness request:

- HTTP requests get `503` with `Retry-After: 30`, except `GET` and `HEAD` of
  `/livez`. The legacy version probes also work during an image-first upgrade;
- SSH commands are refused with the same message;
- the index barrier rejects what reaches it from inside the process, such as a plugin's
  submission;
- change-merged events an earlier process owes are not fired.

This needs the fork's `ServingGate` hook; a node whose Gerrit build lacks it refuses to start.

A sweep that fails during the catch-up is logged and tried again after `indexPollInterval`; the
daemon does not exit. Cursors are saved entry by entry, so a process that ends during the
catch-up loses no progress: the next one continues from them.

The sweep lists current manifest versions, reuses valid cursor/version matches, and catches up
changed repositories. It need not fetch every manifest body.

After a clean sweep, the daemon sets `walgerrit/index_events/ready = true` and creates
`<indexCursorPath>/READY`. The first one also opens the gate and logs `index-event tailer is
ready after its startup catch-up`. Startup, a failed later sweep, and orderly shutdown clear
readiness. A hard kill can leave the file behind, so a readiness probe must also check the local
listener:

```sh
test -f /var/gerrit/data/walgerrit-index-events/READY &&
  curl -fsS http://127.0.0.1:8080/ >/dev/null
```

For startup and liveness, use `GET /livez` or `HEAD /livez`. The HTTP filter returns an
empty 200 before it consults authentication, permissions or storage. Keep the readiness probe
on the index marker and the deployment's deep health check.

## Change search during replay delays

Each daemon records when it started the last complete manifest inventory whose change updates
it has indexed. A clean inventory renews this timestamp even on an idle repository. If the
inventory or replay stalls, the age keeps increasing. A rebuild clears it before touching the
index. After a rebuild, the next complete inventory establishes coverage again.

REST change searches and lookups that need the change index return 503 with `Retry-After: 5`
once the age reaches `walgerrit.indexMaxSearchAge`. An SSH `gerrit query` exits with a retry
message. Gerrit checks the age in memory on each request, including requests sent straight to a
pod. A project and numeric change number, such as `GET /changes/my-project~123/detail`, can
still read NoteDb directly. Git ref reads and background index replay also remain available when
their own dependencies are healthy.

A change number alone, such as `GET /changes/123/detail`, is a point lookup: a number names one
change. The pod takes a candidate project from its number cache or from its index document for that
number, however old its coverage, and answers only after storage confirms it: the pod reads the
catalog and the project's manifest from the store, whatever `manifestRevalidateOnOpen` says and
also while it replays the log, and loads the change at the meta revision that manifest holds. A
project that was deleted, or whose manifest a namespace operation fenced, confirms nothing. A store
that cannot be read, or an active project whose manifest is missing, fails the request. The index
names no candidate while it is being rebuilt or before the first inventory after a rebuild
completes. A read that names the project fills the number cache. A number for which the pod has no
confirmed candidate gets the 503 while the coverage age is over the limit, as for a change only
another pod has seen, and is not found otherwise, even when the index holds a document for it.
During a submit, a merge validator that looks the validated change up by its number finds it in the
project the submit read it from, confirmed the same way. Visibility checks apply as for every
lookup. On a server with `gerrit.importedServerId`, where numbers can repeat, a number alone
depends on index coverage and the number cache is not used.

The default age is 75 seconds. Compare
`walgerrit/index_events/search_coverage_age_seconds` against that limit on each pod during normal
peak traffic. It reads `-1` before coverage is established. The strict barrier for write
decisions still catches up to publications acknowledged before the call.

## Configuration

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule

[walgerrit]
  indexTailerEnabled = true
  indexPollInterval = 5 sec
  indexMaxSearchAge = 75 sec
  indexCursorPath = data/walgerrit-index-events
  indexReplayLimit = 10000
  indexRebuildOnStaleCursor = true
  indexBarrierTimeout = 5 sec
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

A peer's UDP hint queues replay for one repository on the tailer thread. When it runs, the
replay conditionally reads the current manifest and applies unseen log entries. Hints received
while replay is queued share that pass; hints received during replay can queue another. Sweeps
retry failed work and discover writes whose hints were lost. See [Peer wake-ups](gossip.md).

## Change lookups wait for other nodes' writes

WalGerrit reads exact branch/Change-Id membership from storage. Some write decisions still
need Lucene, including finding changes to close after a direct push and finding all members of
a topic before submission. The `ChangeIndexFreshness` barrier brings the node's index up to
date before those lookups. A timeout rejects the request.

### Authoritative branch and Change-Id lookups

The WalGerrit storage module installs the branch/Change-Id directory lookup for review
pushes, REST change creation, moves and cherry-picks. It requires a WAR with the
`ChangeKeyLookup` hook and a WalGerrit repository handle for each guarded operation. A server
configured with WalGerrit storage refuses to start if its storage JAR lacks the guard.
The operation reads one manifest snapshot and loads any selected change from NoteDb. The
conditional publication that makes the Git refs visible validates the memberships the operation
read, including an empty destination. Only one of two concurrent creations on the same branch
and Change-Id can publish. Receive repeats selection once after a proven conflict. REST returns
HTTP 409 for a confirmed membership conflict.

On first use, requests to one pod join a background seed for the project. Each pod has one
seed worker and queues up to 16 more projects. Once a seed succeeds the request rereads the
manifest and checks coverage before using the directory. A request that cannot obtain coverage
within the seed wait receives a retriable error before publishing refs. The wait uses
`indexBarrierTimeout`. A zero timeout allows an already covered lookup and gives a cold
request no waiting time. An unreadable legacy meta ref can keep one project unavailable for
these writes until its metadata is repaired. For a cutover from writers that lack the guard,
drain their in-flight writes before admitting writes through the matching WAR and storage JAR.
Raw imports, older binaries and extensions that write refs outside these paths can invalidate
coverage or introduce duplicates.
The directory preserves historical duplicates and closed changes. See the
[storage format](storage-format.md#branch-and-change-id-directory).

Direct pushes retain the project barrier. Auto-close can query open changes, and creation of a
branch or tag can use a visibility-filtered change-index query during permission checks. A review
without a Change-Id still uses a by-commit index lookup. REST operations also keep the barrier
for auxiliary lookups such as resolving an indexed base change. Whole-topic submission keeps
its global barrier. Search visibility on each pod follows its own replay.

The `walgerrit/change_keys/decisions` counter reports `covered`, `seed_wait`,
`seed_unavailable`, `unsupported_repository` and `publication_conflict`. A cold lookup can record
both a wait and a covered snapshot. A receive can open two snapshots if it repeats selection.

### One repository

When a write needs an indexed project lookup, its pod brings the index up to date for that project.

1. It reads the repository's manifest conditionally to learn the log head.
2. If its cursor is at the head, or this node published every entry between the cursor and the
   head, the request continues. The request that publishes an entry indexes its own work, so a
   node's own entries are not waited for; the tailer replays them later as usual.
3. Otherwise the request waits for its captured head. Each durable cursor advance from a sweep,
   a hint or another request can satisfy the waiter. Later writes can still be replaying when the
   request continues. The catalog uses the same per-target wake-up and requires actual replay.

The index holds a repository under the name the catalog gave it when the node last replayed the
catalog. While the catalog the node has read is ahead of the one it has replayed, for instance
right after any node created or deleted a project, the catalog is replayed first. A name with no
repository has nothing to wait for; Gerrit answers that request as it would anyway.

### Every repository

A topic's members can be in any repository, and the members the index holds do not lead to the
one it lacks. So a submission that reaches a topic, with `change.submitWholeTopic` on, first
brings the index up to date for every repository:

1. One paginated listing of `manifests/` names every repository with its manifest version. Every
   publication acknowledged before the listing began is in it.
2. The catalog is replayed first when it is behind the listing, then every repository whose
   listed version the node has not caught up to, unless this node published every entry it is
   missing.
3. A repository whose replay fails is tried once more after the others: an All-Users entry can
   name a change whose own repository comes later in the pass.

The submission asks once, at its first topic lookup; a submission that reaches no topic does not
ask, and neither do the read paths that compute the same set, such as `submitted_together` and
the submit button. Submissions share passes: one that arrives while a pass runs waits for the
next, since the running pass may have listed the store before it arrived, and however many wait,
one pass follows another. On a quiet site a pass costs the listing; otherwise it costs the listing
plus the replay the next sweep would have done.

### When the index cannot be brought up to date

The request is rejected: a push fails with a message for the client, and a REST call answers
`503`. Both say to retry. A push is rejected before it updates any ref, and a rejected submission
merges nothing. A push with `%submit` is two steps, though: the push has created its change or
patch set by the time the submission asks, and that stays when the submission is rejected. This
happens when:

- `indexBarrierTimeout` runs out;
- reading the store or replaying fails;
- the node is still catching up after its start;
- the node cannot replay now: it is stopping, a cursor needs a rebuild, a project's creation or
  deletion is in flight and its unreplayed entries touch changes, the catalog kept moving, or
  more requests wait than the barrier takes;
- the node's indexes are in doubt: it is rebuilding them, a rebuild began while the request
  waited, or its last rebuild failed. A rebuild empties the indexes before it refills them, and a
  failed one leaves the cursors saying what the indexes held before. The node therefore rejects
  until a rebuild has completed, which the next sweep attempts.

A node that stays unable to replay rejects writes that need these index lookups. A failed
sweep also revokes readiness. See [Startup and readiness](#startup-and-readiness).

The store is read on up to 16 threads and the log replayed on up to 4 threads of their own,
never on the request thread, and at most 256 reads and 256 replays wait for one. Requests for
one repository share a replay, and a replay or pass a request stopped waiting for still
completes. One replay runs at a time on a node, whoever started it, because entries of different
repositories can index the same change. The time a push waits counts toward its deadline, when
the client or the server set one.

No replay runs while the node rebuilds its indexes; a replay that started earlier finishes first.
A sweep, wake-up or rebuild waits up to one minute for a replay running on another thread. If the
wait times out, it fails and the next sweep retries. A replay never waits for the barrier: a
request made on a thread that is replaying is rejected rather than deadlocked.

### Index barrier guarantees

A successful barrier covers updates that peers acknowledged before the request asked.
Additional writes can overlap a subsequent Lucene lookup.

- A change that joins a topic, or is uploaded with it, after a submission's listing began may or
  may not be part of that submission: nothing guarantees either. A single Gerrit server makes
  the same choice at the moment it looks the topic up.
- A rebuild can overlap a lookup that passed the barrier, causing it to miss changes while
  indexes are emptied and refilled. The node revokes readiness before a rebuild; admitted requests
  can continue, but requests still waiting at the barrier are rejected.
- Nodes that share a host name take one another's entries for their own and do not wait for
  them. Every node needs its own.
- The barrier covers the decisions above. Queries, dashboards and other reads see the index as it
  is, and a REST call on a change this node's index does not hold yet can answer `404` until the
  tailer catches up.

```ini
[walgerrit]
  indexBarrierTimeout = 5 sec
```

With `indexBarrierTimeout = 0` or the tailer disabled, indexed lookups use the local index
immediately. Exact branch/Change-Id lookups still require certified storage coverage. The
`walgerrit/index_barrier/latency` timer records the wait by `scope` (`project` or `all`) and
`outcome`.

| Outcome | Request | Meaning |
| --- | --- | --- |
| `not_behind` | continues | The index already held every entry other nodes wrote. |
| `replayed` | continues | The index was behind and caught up while the request waited. |
| `disabled` | continues | The node does not tail the log. |
| `timed_out` | rejected | `indexBarrierTimeout` ran out first. |
| `failed` | rejected | Reading the store or replaying failed. |
| `unavailable` | rejected | The node cannot replay now, for one of the reasons above. |

## Replay

The manifest names the log head; each entry names its predecessor. The tailer walks backward to
the cursor, validates its transaction ID, then applies the entries forward. Catch-up reads one
log object per intervening entry. See [Storage format](storage-format.md#the-log-chain).

Indexing a change opens its repository and All-Users. Repeated NoteDb opens on a replay
thread borrow the same repository handle, retaining its DFS pack inventory for that scope.
The repository manifest is revalidated when `manifestRevalidateInterval` expires. The catalog
and any other repository are validated as on an ordinary open.

For ordinary positive change-ref updates, the tailer reads up to 16 already available WAL
entries and 64 ref updates at a time. It replaces each distinct change document from current
NoteDb state. Up to `indexReplayWorkers` workers share that work (default 4, range 1–16).
Setting it to 1 keeps coalescing and runs the writes serially. The tailer never waits to fill
a batch. Branch or config changes, deletions, All-Users, catalog transactions, events and
explicit index updates follow their ordered paths. Each original transaction retains its merge
accounting. The durable cursor advances only after the entire prefix succeeds. On failure the
next pass repeats the prefix, including any idempotent document writes that already succeeded.
A waiting project barrier gets entry checkpoints after any batch already in flight finishes.
A head checkpoint includes the manifest version.

Stock Gerrit defaults to omitting mergeability from the index. With that setting a branch-tip
move alone does no change-document work. Both Gerrit configurations that index mergeability
still reindex open reviews on the destination branch. Enable `walgerrit.reindexOnBranchUpdate`
if a site extension indexes fields that depend on the destination tip. Project-config, account,
group and explicit change updates keep their own invalidations. The counter
`walgerrit/index_events/change_documents` counts document replacements or removals by reason
(`metadata`, `metadata_batch`, `branch_tip` and `project_config`). Retries count the work
again.

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

At startup this is part of the catch-up, and the node answers no request until the sweep after
the rebuild is clean. During a background rebuild readiness is revoked.
The work can be as expensive as a full offline reindex. A fresh node may need it, but the trigger
is the log-distance and history check, whether or not the volume is new.

With `indexRebuildOnStaleCursor = false`, stale cursors prevent readiness. For manual recovery,
stop the affected node and run `reindex --walgerrit` before restarting it. This captures heads
before rebuilding and saves them only after successful index flushes, so other nodes can keep
writing during the rebuild. Removing cursors alone can immediately trigger the same replay-limit
failure. See [Import: after the import](import.md#after-the-import). Snapshot reads needed for
group membership cache invalidation must also succeed before a replay cursor advances; failures
are retried instead of leaving removed members cached indefinitely.

An interrupted rebuild can leave an index marked not ready. Complete an offline reindex before
restarting in that case. Automatic rebuilds recover index state, not historical public events:
notifications before the captured heads are skipped.

## Format boundary

The backend accepts manifest format 4. Older layouts have no automatic migration. Missing log
objects or logical ref payloads are errors; they must not be treated as empty transactions.
