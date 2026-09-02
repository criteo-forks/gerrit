# Roadmap

## Milestone 0: extension boundary

- [x] Load a library module in stock Gerrit 3.14.2.
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

- [x] Replace local object operations with conditional S3 requests.
- [x] Materialize immutable packs into a node-local cache on demand.
- [x] Exercise concurrent writers, CAS conflicts, and ambiguous-success fault points against MinIO.
- [x] Revalidate manifests with conditional reads once per repository handle and ref transaction
  instead of on every JGit lookup; pin the round-trip budget in tests.
- [ ] Add a size bound and eviction policy to the local pack cache.

## Milestone 3: Gerrit workflows

- [x] Initialize and reindex `All-Projects` and `All-Users` through WalGerrit.
- [x] Push `refs/for/*`, comment, vote, and submit.
- [x] Run Gerrit's acceptance suite on the backend (`scripts/acceptance-tests.sh`); every case
      passes except ten that assert on in-memory-manager-only instrumentation (see README).
- [x] Run two Gerrit instances against one S3-compatible backend with independent local Lucene
  indexes, including restart.
- [x] Persist exact ref transactions in the WAL and replay them at least once into accounts,
  changes, groups, and projects indexes from node-local cursors.
- [x] Block daemon startup on a full initial catch-up and publish node-local replay readiness.
- [x] Discover repositories and changed manifests with one listing of the flat `manifests/`
  prefix per sweep; read only manifests whose version changed.
- [ ] Optional peer wake-ups if cross-node index convergence must be faster than the sweep
  interval.
- [x] Fold the log into sealed segments with a retention floor so the manifest stays bounded, and
  rebuild a node's indexes from repository state when its cursors cannot be replayed.

## Milestone 4: migration and operations

- Import all repositories with full object-closure verification.
- Add snapshots, integrity checks, and orphan cleanup.
- [x] Publish `DfsPackCompactor` output as one exact add-and-supersede manifest transaction while
  retaining old files.
- Add the per-repository compaction lease, scheduling, and reader-generation-aware physical
  reclamation.
- Add index replay lag/error metrics and operational alerts.
- Document cutover and rollback.
