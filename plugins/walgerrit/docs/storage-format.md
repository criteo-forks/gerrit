# Storage format

The manifest names the live Git files. The log records how that inventory changed. Files outside
the committed inventory do not publish refs merely by existing in the store.

```text
<store-prefix>/
  manifests/<project>.git/manifest.pb
  repos/<project>.git/
    log/<sequence>-<transaction-id>.pb
    wal/<pack-id>.pack
    wal/<pack-id>.idx
    wal/<pack-id>.ref
  leases/<project>.git/compaction
  leases/cluster/sweep
  cluster/web-session-signing-key       # optional stateless sessions

<storagePath>/repos/<project>.git/
  staging/
  wal/                                # local immutable-file cache

<indexCursorPath>/repos/<project>.git.cursor
<indexCursorPath>/READY
```

Pack families may also contain bitmap and reverse-index files. Large cached S3 packs can have a
`.chunks` sidecar that records downloaded ranges. Staging files and cursors are node-local.

The local backend maps the store and file cache onto the same filesystem tree, with conditional
write locks under `.object-locks/`. The S3 backend keeps the shared objects beneath `s3Prefix` in
the bucket and the cache beneath each node's `storagePath`.

## The manifest grows with live data, not history

Format version 3 records the repository name, SHA-1 object format, head sequence and transaction
ID, overall revision, ref revision, writer, timestamp and live pack families. Each family records
its source, files and sizes, object/delta counts, reftable update indices and pack checksum.
The schema is [walgerrit.proto](../src/main/proto/walgerrit.proto).

`revision` advances on every publication. `ref_revision` advances only when the live reftable
stack changes, including reftable compaction. The object store's version token is separate from
both: it is the value used for conditional replacement.

Manifests have a dedicated prefix. A paginated listing discovers repository names and manifest
versions without enumerating packs or logs. On S3, the listed version is the ETag. An unchanged
version lets the index tailer skip an already indexed repository.

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
| `EVENT` | Serialized Gerrit notifications. |

A logical ref update records the ref name, old and new object IDs, and a new symbolic target when
applicable. Several independent local batches can share one entry; their logical updates are
concatenated in publication order.

Log objects are not reclaimed. This preserves the recorded transitions, but does not retain all
historical pack contents: superseded files can be deleted after the reclamation grace checks.
Log retention alone therefore does not provide point-in-time data recovery.

## A cursor identifies history, not just a position

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

Only SHA-1 repositories and manifest format 3 are supported. There is no automatic conversion
from earlier manifest versions, durable repository deletion, or native GCS backend. Import from
bare repositories is available through [walgerrit-import](import.md).

New DFS file names use random pack identifiers; imported packs retain their original names.
Pack checksums are metadata, not a universal object-store naming scheme. Multi-pack indexes are
disabled because the manifest does not represent their coverage relationships.
