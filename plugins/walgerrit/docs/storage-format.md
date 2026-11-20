# Storage format

The manifest names the live Git files. The log records how that inventory changed. Files outside
the committed inventory do not publish refs merely by existing in the store.

```text
<store-prefix>/
  manifests/catalog/manifest.pb           # the name catalog, a repository with a fixed id
  manifests/<repository-id>/manifest.pb
  repos/<repository-id>/
    log/<sequence>-<transaction-id>.pb
    log/auth-refs/<page-id>                 # All-Users account ref tips
    wal/<pack-id>.pack
    wal/<pack-id>.idx
    wal/<pack-id>.ref
  leases/<repository-id>/compaction
  leases/cluster/sweep
  cluster/web-session-signing-key         # optional stateless sessions

<storagePath>/repos/<repository-id>/
  staging/
  wal/                                    # local immutable-file cache

<indexCursorPath>/repos/<repository-id>.cursor
<indexCursorPath>/READY
```

A repository id is an opaque lowercase hexadecimal string chosen at creation. Project names are
bindings in the [catalog](namespace.md), so deleting a project moves no files.

Pack families may also contain bitmap and reverse-index files. Large cached S3 packs can have a
`.chunks` sidecar that records downloaded ranges. Staging files and cursors are node-local.

The local backend maps the store and file cache onto the same filesystem tree, with conditional
write locks under `.object-locks/`. The S3 backend keeps the shared objects beneath `s3Prefix` in
the bucket and the cache beneath each node's `storagePath`.

## The manifest records live data

Format version 4 records the repository id, SHA-1 object format, head sequence and transaction
ID, overall revision, ref revision, write epoch, the namespace operation that last fenced the
repository, whether it is deleted, writer, timestamp and live pack families. Each family records
its source, files and sizes, object/delta counts, reftable update indices and pack checksum.
The schema is [walgerrit.proto](../src/main/proto/walgerrit.proto).

`revision` advances on every publication. `ref_revision` advances only when the live reftable
stack changes, including reftable compaction. `write_epoch` advances only when a deletion
fences the repository; a publication whose writer was admitted under another epoch is refused. The object store's version token is separate from all three: it is the value used for
conditional replacement.

Manifests have a dedicated prefix. A paginated listing discovers repository ids and manifest
versions without enumerating packs or logs. On S3, the listed version is the ETag. An unchanged
version lets the index tailer skip an already indexed repository.

## All-Users authentication refs

`auth_refs`, when present, holds the tips of `refs/meta/external-ids` and `refs/users/default`.
It points to immutable pages for canonical numeric account refs, each covering 256 consecutive
account IDs. Direct tips include the JGit update index and any peeled tag target. Symbolic refs
fall back to JGit.

An All-Users ref transaction seeds missing coverage from a merged reftable snapshot. The manifest
CAS checks the snapshot's ref revision and publishes the accepted group's updates and changed page
pointers together. Draft and star updates reuse all pages; compaction carries the directory to the
new ref revision. Pages are retained with the log, including those from losing CAS attempts.

## Branch and Change-Id directory

The `change_keys` manifest field records a covered `ref_revision` and up to 256
pointers to immutable pages in the committed WAL. Each page maps a full destination branch and
exact Change-Id to sorted numeric change IDs. A missing field requires a seed before a writer can
use the directory. An absent key is authoritative only when the directory covers the current
ref revision. A missing referenced page fails the lookup.

On a classified meta-ref update, the WAL entry carries its membership change and the full
contents of each modified page. The manifest CAS publishes the page pointers together with the
new ref inventory. Compaction preserves memberships. A replay of an unchanged identity, such
as a comment or vote, leaves the pages in place. WalGerrit retains a bounded per-node cache of
immutable pages. Logical checks and changes are part of the same-site group publisher's
admission, so two batches touching one key cannot enter the same group independently.

Seeding reads a snapshot of all meta refs, reconciles intervening WAL entries and publishes
the directory conditional on that ref revision. A raw reftable import, unclassified meta write or
legacy ref revision without corresponding directory coverage requires a new scan. One node
builds at most one project directory at a time. The initial WAL record contains every populated
page, and loading a cold page reads its containing log entry.

## The log chain

Each entry names its sequence, unique transaction ID and predecessor's transaction ID. Starting
from the manifest head, a reader derives each predecessor key without listing the log prefix.
Only entries reachable through that chain belong to committed history. Losing CAS attempts can
leave unreferenced log objects.

| Kind | Contents |
| --- | --- |
| `PACK` | File additions without a logical ref transaction; also used for import and recovery fences. |
| `REF_UPDATE` | File changes and the complete logical ref transaction. |
| `COMPACT` | Replacement files and the names they supersede. |
| `EVENT` | Serialized Gerrit notifications, optionally with document IDs to reindex. |
| `INDEX` | Document IDs to reindex without a ref update or public event. |
| `FENCE` | No files or refs; the namespace operation that advanced the write epoch. |

A logical ref update records the ref name, old and new object IDs, and a new symbolic target when
applicable. Several independent local batches can share one entry; their logical updates are
concatenated in publication order.

Log objects are not reclaimed. This preserves the recorded transitions, but does not retain all
historical pack contents: superseded files can be deleted after the reclamation grace checks.
Log retention alone therefore does not provide point-in-time data recovery.

## A cursor names its history as well as its position

Each node stores the last applied sequence and transaction ID beside its own Lucene indexes.
At a repository head, it also records the manifest version. A later listing of the same version
confirms that no replay is needed, including after a restart.

Replay walks backward from the head, validates the cursor's transaction ID, then applies entries
forward. A cursor ahead of the head, on a different history, or more than `indexReplayLimit`
entries behind requires a rebuild. Missing or malformed log entries stop replay. See
[Index events](index-events.md).

`READY` reports the outcome of the daemon's last completed full sweep. An orderly shutdown
removes it; a hard kill can leave it behind. A probe must also check the local listener.

## Publication

1. Write pack/index or reftable files into local staging.
2. Persist every completed file as an immutable `wal/` object.
3. Write a uniquely named immutable log entry.
4. Conditionally replace the manifest, checking ref revision and superseded inputs as required.

The local backend uses fsync and atomic replacement; S3 uses conditional object requests. Step 4
is the commit point. Object packs may wait in the node's pending inventory until a ref transaction
or handle close publishes them. Ref transactions publish their reftables and pending object packs
together.

Failure before the CAS can leave files or log entries behind without exposing partial ref
updates. The [reclaimer](compaction.md#reclamation) removes eligible unreferenced `wal/` files;
log objects remain. A lost CAS response requires [outcome recovery](consistency.md#ambiguous-outcomes-and-recovery).

## Format boundaries

Only SHA-1 repositories and manifest format 4 are supported. There is no automatic conversion
from earlier manifest versions or native GCS backend. Import from bare repositories is available
through [walgerrit-import](import.md). Deleting a project retires its name and refuses further
writes; its files are kept and never reclaimed.

New DFS file names use random pack identifiers; imported packs retain their original names.
Pack checksums are metadata, not a universal object-store naming scheme. Multi-pack indexes are
disabled because the manifest does not represent their coverage relationships.
