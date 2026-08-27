# Consistency contract

The object-store backend is not complete until it provides all of the following properties.

The Milestone 1 local backend implements this ordering with atomic filesystem moves and a locked
manifest compare-and-swap. Milestone 2 must preserve the same contract through conditional object
store operations.

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
and remain rebuildable from Git/NoteDb. Event delivery may be at least once because Gerrit index
updates are idempotent.
