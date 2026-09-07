# Events in the WAL

Gerrit fires an event for every ref update, patch set, comment, vote and merge. Stock Gerrit
delivers it to the listeners of the node that did the work: the SSH `stream-events` subscribers
connected there (Jenkins), and the plugins loaded there (webhooks, Kafka publishers, review
assignment). A client connected to another node hears nothing. The multi-site plugin fans events
out over a Kafka topic and marks the forwarded copies so nodes do not re-publish them.

WalGerrit uses the log it already has. The WAL entry for a ref transaction is the record of the
change; the events that describe it travel in the same log, one entry behind it.

## Write side

`WalEventJournal` is an unrestricted `EventListener` on every node. It serializes each event with
Gerrit's own event Gson, buffers it under the repository it concerns (an event without a project
goes to `All-Projects`), and every 200 ms publishes one `EVENT` log entry per repository holding
the batch (`LogEntry.event_json`). That is one object write and one manifest CAS per batch, on a
background thread; the request that fired the event is never delayed. A batch of 100 flushes at
once. If publication fails the batch is logged and dropped: events are notifications, and losing
one is better than blocking the log.

The entry lands after the ref update that caused it, because Gerrit fires events only once the ref
transaction, and therefore its WAL entry, is durable.

## Read side

The index-event tailer reads `EVENT` entries with everything else. An entry whose writer host is
this node is skipped: its events were fired here. Any other entry is handed to
`GerritEventReplayer`, which deserializes each event and posts it to the node's `EventDispatcher`
inside a one-off request context, so the usual per-listener visibility checks apply and the node's
stream-events subscribers and plugins see the event exactly as the origin's did. During delivery
the thread is marked (`EventReplay`), and the journal ignores what it sees on such a thread, so a
replayed event is never journaled again.

The writer host is the pod or host name, which a StatefulSet keeps across restarts; a node that
restarts therefore does not replay the events it fired before the restart.

## Guarantees

- Every node delivers every event once in normal operation. Delivery is at least once: an entry a
  node replayed but had not acknowledged when it crashed is replayed again after it restarts.
- Ordering is per repository, and an event is delivered after the index update it belongs with,
  because the tailer replays a repository's entries in log order in the same sweep. Events of
  different repositories carry no relative order, as before.
- Latency is the tailer's poll interval plus the journal's flush interval, a few seconds at most.
- Plugins cannot tell a replayed event from a local one. A plugin that publishes events outside
  Gerrit therefore publishes on every node. Give such a plugin one node with the leader lease
  (`WalGitRepositoryManager.isLeader()`), or run it on one node only.

## Configuration

```ini
[walgerrit]
  eventJournalEnabled = true    # default; false journals nothing and replays nothing sent by others
```

## Leader lease

Some work should run on one node: deleting unreferenced files from the shared store, or forwarding
events to an external system. `ClusterLeader` elects that node with a `StoreLease` on
`leases/cluster/leader`: the holder renews every third of `walgerrit.leaderLeaseDuration` (60 s by
default), another node takes over once the lease has lapsed, and a node that fails to renew stops
leading at once. A node may believe it leads for up to one tick after losing the lease, so
leader-only work must be safe to run twice; store reclamation is, because it only deletes files no
manifest references and older than the grace period. Every node still trims its own cache and
evicts its own copies of unreferenced files.
