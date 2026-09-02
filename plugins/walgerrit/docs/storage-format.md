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
ref revision, live DFS file-set inventory, and immutable log references. `log/*.pb` records the
additions and superseded files for one publication. A `REF_UPDATE` entry also contains the complete
logical ref transaction (ref name, old/new object IDs, and new symbolic target). The schemas are in
`src/main/proto/walgerrit.proto`.

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
or an orphan log entry, but cannot expose partial refs. Orphan reclamation belongs to a later
maintenance milestone.

JGit represents a batch ref update as one reftable file, so all refs in that batch share one manifest
publication. Reftable compaction may supersede an earlier reftable in the same transaction.

## Deliberate limitations

- SHA-1 repositories only, matching current Gerrit project storage.
- No checkpoints or log-segment compaction yet.
- No durable repository deletion or import workflow yet.
- S3-compatible storage is implemented; GCS-native conditional requests are not.
- The node-local immutable-file cache is not bounded yet.
- Immutable file names are random DFS pack identifiers; Git pack checksums are recorded in the
  manifest. The object-store milestone can use the checksum as the remote content key.
