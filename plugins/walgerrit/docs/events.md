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
entry. Serialization runs in the listener; storage publication runs in the background. An event
that cannot be serialized is logged and dropped. A batch whose publication fails is retried with
the next flush, its events ahead of those fired since; a publication whose response was lost is
first settled from the log, so a batch that landed is not written twice. While a repository's log
refuses writes it keeps at most 10,000 events, discarding the oldest beyond that with an error. A
process crash still loses the events buffered since the last flush, at most 200 ms of them, and
the events that a request had not fired yet: Gerrit fires events after it committed. For the
change-merged event of a submit the node makes up for that after its restart, as the next section
says. For every other event it does not.

## Recovering a submit's change-merged event after restart

A submit commits each repository's part of a topic one after the other and fires the events when
all are committed. A process that ends in between has merged changes nobody was told about: no
listener on that node, no other node, no reader of the event log.

The ref transaction in the log is what remains, and it says what the event says about the merge.
The tailer therefore keeps, in each repository's cursor file, the merges that ref transactions of
this node made and whose change-merged event it has not seen in the log:

- When replay passes a ref transaction written on this host, it reads the NoteDb commits the
  transaction added to each change. A change that the transaction turned to merged is recorded
  with its patch set and that patch set's commit, the change's meta revision, the submitter and
  time of the submit approval, and the revision the same transaction gave the destination branch,
  which is the `newRev` of the event. The record is written in the same replacement of the cursor
  file that moves the cursor past the transaction.
- When replay passes a journal entry with a change-merged event of that change, patch set, commit
  and revision, the record is removed.
- A record whose transaction was written by a process that has ended is due once replay has
  reached the head. A process is known by a random id it draws at start and puts on every entry it
  writes (`writer_process`); host name and process id repeat across a container's restarts.
- Due events are fired by one thread of their own, with no lock of the tailer held and not as a
  replay: `ChangeMerged.fire` with the change as NoteDb had it after the merge, in a request
  context of the submitter. Extension listeners and stream-event listeners, plugins included, hear
  the event as they hear any event of this node, and what they write and fire in turn is journaled.
  The journal writes the event to the log, the other nodes replay it from there, and the entry
  removes the record.

What this gives, and what it does not:

- **At least once, not exactly once.** A process that ends after firing and before its journal
  wrote the entry leaves the record due again: the node's own listeners hear the event twice. An
  entry that was written removes the record whichever process replays it, so the log gets the
  event once, apart from the journal's own retry of a publication whose outcome it could not
  read. Consumers already have to tolerate duplicates.
- **The same node has to run again, with its volume.** The records are in the cursor directory. A
  node that does not come back, or comes back with an empty volume, owes nothing it knows of.
- **Only merges in entries the node reads.** Entries that an index rebuild or an offline reindex
  moves the cursor over are read afterwards for merges and their events only, up to the replay
  limit; a range that cannot be read any more (the history diverged, or it is longer than
  `indexReplayLimit`) is given up with an error. At most 10,000 records are kept per repository;
  beyond that the oldest are dropped with an error.
- **Only submits, and only since entries say whose update was whose.** The revision an event names
  has to be the branch update of the same ref transaction. A change closed by a push, or found
  already merged, is merged in a transaction that does not move its branch; its record is kept and
  settled like any other, but when it falls due the event is reported as lost, at error level, and
  not sent with a revision this node would have to guess. A node publishes transactions that wait
  together as one entry and since this version records which update was whose; in an older entry
  a branch update next to a merge proves nothing, and its merges are treated the same way.
- **The merge is described as it was, the rest of the change as it is.** Change, patch set,
  commit, submitter and revision are those of the merge, and extension listeners get the time of
  the submit. Gerrit's stream-event listener reads the change's other attributes, such as topic
  and hashtags, when the event is fired, as it does for every event, and the stream event's
  `eventCreatedOn` is the time it is fired again, not the time of the merge. No event is fired for a change that has been deleted, is not merged any
  more, was merged again as something else, or whose NoteDb history no longer holds the merge.
- **Only this event.** The ref-updated events of the submit, its e-mail and the other work Gerrit
  does after a submit committed are not made up for.
- **A process that keeps running is left alone.** Its merges are recorded too, but it fires its own
  events; a merge whose event it never fires, because the submit failed in a later repository,
  falls due when that process has ended. A node whose journal is disabled records nothing.
- **Firing waits for the server.** The delivery starts after the journal and the plugins. When the
  server stops, the delivery is stopped before the journal: an event in flight gets ten seconds
  for its listeners, and what was not begun is fired by the next process. Listeners that take
  longer can outlast the journal; the tailer logs that.

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

## The journal also records reindexed documents

The mergeable endpoint, `gerrit index changes`, `POST /changes/{id}/index` and
`autoReindexIfStale` can reindex documents without a ref update. `WalJournal` listens for indexed
changes, accounts, groups and projects and adds their IDs to the batch (`index_update`).
A batch with no events becomes an `INDEX` entry. Changes are journaled in their repository,
accounts and groups in `All-Users`, projects in `All-Projects`.

The tailer reindexes those documents on foreign nodes, skipping a change that a ref transaction in
the same sweep reindexes anyway: a local write journals both, and followers index the change once.
A batch whose publication fails keeps its index update for the next batch, unlike its events.
Replay and index rebuilds run under `EventReplay`, so a reindex is never journaled back. A rebuild
marks the whole process, because its indexers run on a pool, but it fires no events: events this
node fires during a rebuild are still journaled. Only a replaying thread skips the events it
delivers.

## Consumers must tolerate loss and duplication

A committed notification reaches foreign nodes through a sweep or a
[peer wake-up](gossip.md), after preceding ref entries in the same repository have been indexed. There is no order across repositories and no
fixed latency bound: journal scheduling, sweep duration, backlog and failures all add delay.

Plugins receive replayed events through the normal dispatcher. A plugin that forwards them to an
external service may therefore send one copy per node. Such a plugin should read the
[event log](event-log.md) instead: one copy of each event, in order per repository, with a shared
cursor and a lease. The sweep lease alone is not an exactly-once delivery mechanism.

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
