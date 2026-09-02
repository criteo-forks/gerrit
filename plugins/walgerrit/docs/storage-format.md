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

`manifest.pb` contains the repository identity, object format, head sequence, overall revision,
ref revision, live DFS file-set inventory, the retention floor `min_seq`, and references to the log
segments covering `[min_seq, head_seq]`. Every `log/*.pb` object is a `LogSegment`: one entry when
published, several consecutive entries after folding. An entry records the additions and superseded
files for one publication and carries a `transaction_id` that is unique per publication attempt; a
`REF_UPDATE` entry also contains the complete logical ref transaction (ref name, old/new object
IDs, and new symbolic target). The schemas are in `src/main/proto/walgerrit.proto`; the manifest
format version is 2.

## Folding

The manifest would otherwise grow by one segment reference per publication forever, and it is
uploaded in full on every CAS. Folding keeps it bounded regardless of the repository's age, as a
representation-only manifest change that alters no sequence number, ref revision or pack:

- Runs of `walgerrit.logSegmentEntries` consecutive single-entry segments are merged into one
  segment object whose reference records the last entry's transaction id and creation time.
- The oldest segments drop below the floor once they are older than `walgerrit.logRetention` and at
  least `walgerrit.logRetainEntries` newer entries remain referenced; both must hold, and the newest
  segment is never dropped. `min_seq` then names the oldest entry still referenced.
- Nothing is deleted. Folded objects stay in the store, so every transition ever published remains
  available for audit and for recovery from an earlier manifest version. Only pack reclamation, a
  separate maintenance step, ever removes objects.

Folding runs on any node after its index sweep replays a repository; concurrent publications make
its CAS retry with the merged segment reused.

A follower's cursor names the last entry it applied by sequence and transaction id. Replay is
exact while the entry the cursor names is still referenced by the manifest, because only then can
its identity be checked. A cursor below the floor, ahead of the head, or naming a transaction the
manifest does not, which happens after a manifest is restored to an older version and diverges,
cannot be replayed, and the follower rebuilds its derived state instead. An empty cursor is
replayable only while nothing has been folded.

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
