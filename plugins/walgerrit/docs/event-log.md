# Reading the event log

Every node journals the Gerrit events it fires into the log of the event's repository (see
[Events in the WAL](events.md)). A plugin that forwards events to an external system, such as a
Kafka topic, can read that log instead of listening to the event dispatcher. The dispatcher hands
it every event once per node, and anything held in memory is lost with the node. The log holds
each event once, in firing order per repository, on the shared store, and is never deleted.

`WalGitIndexModule` binds `dev.walgerrit.EventLog` in the system injector, so a plugin can inject
it. The interface uses only JDK types. Compile against `walgerrit.jar` and leave it out of the
plugin jar, since the server's class path provides it.

## What a reader does

A reader has a name, for example `kafka-gerrit-events`. The name keys its lease and its cursors.

1. Take the reader's lease with `acquireLease` and renew it well within its term. Only the
   holder reads and moves cursors; the other nodes wait to take the lease over.
2. List repositories with `repositories()`. Each comes with the version of its manifest, which
   changes with every publication. A repository whose version equals the one stored in the
   reader's cursor has nothing new.
3. For a repository with something new, `readAfter` the cursor's position. It returns the entries
   that carry events, oldest first, and the head the read reached.
4. Deliver the events. Then `saveCursor` at the last entry delivered, or at the head with its
   version once everything up to it is delivered.
5. React to `onChange` so a new publication is read without waiting for the next listing.
   Announcements from other nodes travel by [gossip](gossip.md) and may be lost; the listing
   is the backstop.

## Guarantees and limits

A cursor write names the version of the cursor it replaces. A holder that lost its lease without
noticing cannot move a cursor back after another node moved it: its write fails with
`CursorConflictException`, and it should stop.

Delivery is at least once. A reader that crashes after delivering and before saving its cursor
delivers those entries again from the next holder.

A read walks the log back from the head, one object per entry, so the cost grows with how far
the cursor is behind. A position that is not in the repository's history, after a manifest was
restored to an older version, fails with `HistoryChangedException`; the reader chooses where to
resume.

The log holds what the journal wrote. A node that crashes loses the events it buffered since its
last flush, at most 200 ms of them; failed publications are retried.

Cursors live at `cluster/event-log/<reader>/cursors/<repository id>` and the lease at
`leases/cluster/event-log/<reader>`. Deleting a reader's cursors resets it; what the reader does
when it finds none is the reader's choice.
