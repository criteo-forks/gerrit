# Roadmap

## Milestone 0: extension boundary

- [x] Load a library module in stock Gerrit 3.12.2.
- [x] Route daemon and batch repository access through `WalGitRepositoryManager`.
- [x] Delegate to local repositories as a behavioral control.
- [x] Run unit tests and prove stock Gerrit schema creation uses the module.
- [x] Patch Gerrit's init-only direct `FileRepository` helpers to switch to the configured
  `GitRepositoryManager` after the system injector is available.

## Milestone 1: local WalGit format

- [x] Store immutable packs, indexes, and reftables in a local object-store directory.
- [x] Store a protobuf manifest and immutable transaction log as local files.
- [x] Implement atomic manifest compare-and-swap and stale-writer rejection.
- [x] Pass repository create/open/list and JGit object/ref/batch transaction tests.

## Milestone 2: S3-compatible storage

- Replace local object operations with conditional S3 requests.
- Add a bounded local pack cache.
- Exercise concurrent writers and injected crash points.

## Milestone 3: Gerrit workflows

- [x] Initialize and reindex `All-Projects` and `All-Users` through WalGerrit.
- Push `refs/for/*`, comment, vote, edit and submit.
- Run two Gerrit instances against one backend with local Lucene indexes.
- Consume existing Kafka index/cache events.

## Milestone 4: migration and operations

- Import all repositories with full object-closure verification.
- Add snapshots, integrity checks, and orphan cleanup.
- [x] Publish `DfsPackCompactor` output as one exact add-and-supersede manifest transaction while
  retaining old files.
- Add the per-repository compaction lease, scheduling, and reader-generation-aware physical
  reclamation.
- Document cutover and rollback.
