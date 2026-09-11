# Events and index updates in the WAL

WalGerrit forwards Gerrit notifications between nodes through the WAL. These notifications are
best effort. The logical ref updates used to maintain search indexes have a stronger guarantee:
they commit with the ref transaction itself. See [Index events](index-events.md).

## Notifications follow the write

`WalJournal` listens for local Gerrit events and serializes them with Gerrit's event Gson.
It buffers events by project; events without a project go to `All-Projects`. A background worker
flushes every 200 ms. Reaching 100 buffered events for one project schedules an earlier flush.
Each batch becomes an `EVENT` entry containing `event_json`.

A notification about a completed ref update enters the log after that update. Other publications
may intervene: the notification is neither part of the ref transaction nor necessarily the next
entry. Serialization runs in the listener; storage publication runs in the background. A
serialization or publication failure is logged and the affected notification or batch is dropped.
A process crash can also lose buffered events.

## Each node replays foreign events

The index tailer reads `EVENT` entries in repository sequence order. It skips entries written on
the same host, whose events were already dispatched locally. For foreign entries,
`GerritEventReplayer` deserializes each event and calls Gerrit's `EventDispatcher` in a request
context. The dispatcher applies its normal listener visibility checks.

`EventReplay` marks the replay thread so the journal does not record the event again. Writer
identity includes the host name; stable, distinct host names preserve origin filtering across
restarts. Nodes sharing a host name will treat one another's events as local.

Replay failures are logged and skipped, allowing the cursor to advance. A crash after delivery
but before cursor persistence can cause duplicate delivery. A full index rebuild seeds cursors
at captured heads and skips older notifications; it does not reconstruct them from Git.

## Reindexed documents ride in the same journal

A node also reindexes documents with no ref update behind them: the mergeable endpoint,
`gerrit index changes`, `POST /changes/{id}/index`, `autoReindexIfStale`. `WalJournal` listens for
indexed changes, accounts, groups and projects and adds their ids to the batch (`index_update`).
A batch with no events becomes an `INDEX` entry. Changes are journaled in their repository,
accounts and groups in `All-Users`, projects in `All-Projects`.

The tailer reindexes those documents on foreign nodes, skipping a change that a ref transaction in
the same sweep reindexes anyway: a local write journals both, and followers index the change once.
A batch whose publication fails keeps its index update for the next batch, unlike its events.
Replay and index rebuilds run under `EventReplay`, so a reindex is never journaled back.

## Consumers must tolerate loss and duplication

A committed notification normally reaches foreign nodes on a later sweep, after preceding ref
entries in the same repository have been indexed. There is no order across repositories and no
fixed latency bound: journal scheduling, sweep duration, backlog and failures all add delay.

Plugins receive replayed events through the normal dispatcher. A plugin that forwards them to an
external service may therefore send one copy per node. Run such a publisher on a designated node
or implement deduplication. The sweep lease alone is not an exactly-once delivery mechanism.

## Configuration

`WalGitIndexModule` installs the journal and tailer. The journal is enabled by default:

```ini
[walgerrit]
  eventJournalEnabled = true
```

Setting this to `false` stops this node from journaling local events and reindexed documents;
the tailer still replays foreign entries. Disabling `indexTailerEnabled` stops both index
catch-up and event replay, so it also removes cross-node search convergence on that node.

## The sweep lease coordinates housekeeping

`SweepLease` uses `leases/cluster/sweep` to choose which node deletes unreferenced shared files.
Its default term is 60 seconds (`sweepLeaseDuration`); renewal is scheduled every third of the
term, with a one-second minimum delay.

Renewal shares the maintenance executor with compaction and reclamation. Long work can delay a
renewal, and the holder flag changes only when lease code runs. The lease reduces duplicate
work; it is neither a hard exclusion nor a bounded failover guarantee. Manifest CAS
and the [reclamation rules](compaction.md#reclamation) remain responsible for data safety.
