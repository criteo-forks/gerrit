# Consistency contract

Both storage backends provide the same publication contract. The local backend uses atomic
filesystem moves and a locked manifest compare-and-swap. The S3 backend uses immutable puts and a
conditional manifest request.

## Publication order

1. Upload every immutable pack and index required by the transaction.
2. Persist the transaction-log entry.
3. Atomically compare-and-swap the repository manifest.
4. Acknowledge the Gerrit operation only after the winning manifest is readable by every serving
   instance.

Objects may exist before their refs. Refs must never point to unavailable objects.

## Ref transactions

Gerrit's `BatchRefUpdate` may update several refs atomically. The backend must validate every
expected old object ID and publish either all requested ref changes or none of them.

On a compare-and-swap conflict, the backend reloads the manifest and revalidates the requested old
values. A real ref conflict is reported to Gerrit as a lock failure; it must not be silently
overwritten.

The manifest carries a separate ref revision. Appending unreachable object packs is safe before ref
publication and therefore does not invalidate a ref transaction. Only a change to the live
reftable stack advances the ref revision.

## Derived state

Lucene indexes and caches are not part of the Git transaction. They are updated after publication
and remain rebuildable from Git/NoteDb. The complete logical ref transaction is stored in the
immutable WAL entry before the manifest CAS. A node processes entries in repository sequence order
and atomically advances its node-local cursor only after the synchronous index applications return.
Delivery is at least once because Gerrit index replacements and deletions are idempotent.

The cursor is safe across hard crashes only when every affected Lucene index commits each write to
stable storage. WalGerrit therefore requires `commitWithin = 0` for accounts, both change
sub-indexes, groups, and projects. A missing payload, sequence gap, cursor/history mismatch, or index
failure leaves the cursor unacknowledged and stops progress for that repository instead of silently
skipping data.

Before Gerrit's serving listeners start, a daemon must complete a clean sweep of every repository.
Only then does it publish its node-local readiness marker and gauge. A failed background sweep
revokes readiness while still attempting every repository, and a later clean sweep restores it.
This is a consumer-health signal for the most recently completed sweep, not a cross-repository
snapshot or a barrier against writes committed just afterward.

Repository WAL streams have no global order. When an All-Users draft/star event depends on a change
whose project stream has not yet been indexed, the event is retried rather than acknowledged.

See [WAL-driven index events](index-events.md) for mappings and operational limitations.
