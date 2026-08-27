# Local storage format

The local format mirrors the Continuity/WalGit layout while using JGit's native DFS files.

```text
<storagePath>/repos/<project>.git/
  manifest.pb
  manifest.lock
  log/<sequence>-<transaction>.pb
  wal/<pack-id>.pack
  wal/<pack-id>.idx
  wal/<pack-id>.ref
  staging/
```

`manifest.pb` contains the repository identity, object format, head sequence, overall revision,
ref revision, live DFS file-set inventory, and immutable log references. `log/*.pb` records the
additions and superseded files for one publication. The schemas are in
`src/main/proto/walgerrit.proto`.

## Publication

1. JGit writes a pack/index or reftable into `staging/`.
2. WalGerrit fsyncs and atomically moves every completed file into `wal/`.
3. WalGerrit writes and fsyncs a uniquely named immutable log entry.
4. A ref transaction verifies the expected ref revision. Concurrent object-pack appends do not
   invalidate that token; a concurrent reftable publication does. Maintenance verifies every file
   it supersedes is still live.
5. WalGerrit atomically replaces and fsyncs `manifest.pb`.

Only step 5 makes a transaction visible. A process death before it can leave immutable orphan files
or an orphan log entry, but cannot expose partial refs. Orphan reclamation belongs to a later
maintenance milestone.

JGit represents a batch ref update as one reftable file, so all refs in that batch share one manifest
publication. Reftable compaction may supersede an earlier reftable in the same transaction.

## Deliberate limitations

- SHA-1 repositories only, matching current Gerrit project storage.
- No checkpoints or log-segment compaction yet.
- No durable repository deletion or import workflow yet.
- No S3/GCS conditional requests or local cache tier yet.
- Immutable file names are random DFS pack identifiers; Git pack checksums are recorded in the
  manifest. The object-store milestone can use the checksum as the remote content key.
