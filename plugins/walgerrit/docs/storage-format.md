# Storage format

The shared format mirrors the Continuity/WalGit layout while using JGit's native DFS files.

```text
<object-store-prefix>/manifests/<project>.git/
  manifest.pb                  # the CAS-replaced linearization point

<object-store-prefix>/repos/<project>.git/
  log/<sequence>-<transaction>.pb
  wal/<pack-id>.pack
  wal/<pack-id>.idx
  wal/<pack-id>.ref

<storagePath>/repos/<project>.git/
  staging/
  wal/                         # materialized immutable-file cache

<indexCursorPath>/repos/<project>.git.cursor
<indexCursorPath>/READY
```

Manifests live under their own prefix, apart from the pack, index, reftable and log objects. One
paginated listing of `manifests/` therefore enumerates every repository together with the current
version of its manifest, the ETag on S3, at a cost proportional to the number of repositories. That
listing is how repositories are discovered and how the index-event sweep finds the manifests that
changed without reading any of them.

The local backend maps the shared object-store prefix and cache onto the same filesystem tree and
keeps its lock files under `.object-locks/`. The S3 backend keeps the shared objects in the bucket
and the staging/cache tree on each node.

`manifest.pb` contains the repository identity, object format, head sequence and the head entry's
transaction id, overall revision, ref revision, and the live DFS file-set inventory. Its size
depends on the number of live files, never on the repository's age. The schemas are in
`src/main/proto/walgerrit.proto`; the manifest format version is 3.

## The log chain

Every `log/<seq>-<transaction_id>.pb` object is one `LogEntry`: the additions and superseded files
of one publication, a `transaction_id` unique to that publication attempt, and the transaction id of
the entry before it. A `REF_UPDATE` entry also contains the complete logical ref transaction (ref
name, old/new object IDs, and new symbolic target). Because each entry names its predecessor, the
history of a repository is the chain reachable from the manifest's head: every key on it is known
without a listing, and an entry written by a publication that lost its CAS is simply never
referenced. Nothing is ever deleted from `log/`, so every transition ever published remains
available for audit and for recovery from an earlier manifest version.

A follower's cursor names the last entry it applied by sequence and transaction id. To replay, it
walks the chain back from the head to its sequence, one object per entry behind, and the id the walk
arrives at must be the one the cursor recorded. A cursor ahead of the head, one naming a transaction
the chain does not, which happens after a manifest is restored to an older version and diverges, or
one more than `walgerrit.indexReplayLimit` entries behind cannot or should not be replayed, and the
follower rebuilds its derived state instead.

`indexCursorPath` is node-local and is not part of the shared object store. Its protobuf cursor
identifies both the last applied sequence and the immutable log key at that sequence, which detects
history replacement rather than trusting a sequence number alone. `READY` exists only while the
daemon's most recently completed full index-event sweep was clean.

## Publication

1. JGit writes a pack/index or reftable into `staging/`.
2. WalGerrit publishes every completed file as an immutable `wal/` object and materializes it in
   the node-local cache.
3. WalGerrit writes and fsyncs a uniquely named immutable log entry.
4. A ref transaction verifies the expected ref revision. Concurrent object-pack appends do not
   invalidate that token; a concurrent reftable publication does. Maintenance verifies every file
   it supersedes is still live.
5. WalGerrit atomically replaces and fsyncs `manifest.pb` locally, or conditionally replaces it in
   S3.

Only step 5 makes a transaction visible. A process death before it can leave immutable orphan files
or an orphan log entry, but cannot expose partial refs. Orphan files are reclaimed by the rule in
[compaction.md](compaction.md#reclamation) once they are older than the grace period; log entries
are never deleted.

`leases/<project>.git/compaction` holds the repository's compaction lease, a small protobuf with an
owner and an expiry, kept apart from the manifests prefix so a listing of manifests stays a listing
of repositories.

JGit represents a batch ref update as one reftable file, so all refs in that batch share one manifest
publication. Reftable compaction may supersede an earlier reftable in the same transaction.

## Deliberate limitations

- SHA-1 repositories only, matching current Gerrit project storage.
- No durable repository deletion or import workflow yet.
- S3-compatible storage is implemented; GCS-native conditional requests are not.
- With the local backend the node-local cache is the store, so it cannot be bounded; the object
  store backends bound it with `walgerrit.cacheSizeLimit`.
- Immutable file names are random DFS pack identifiers; Git pack checksums are recorded in the
  manifest. The object-store milestone can use the checksum as the remote content key.
