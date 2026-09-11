# JGit and manifest-CAS correctness audit

JGit supplies the immutable-file and atomic-ref primitives WalGerrit needs. WalGerrit supplies
distributed publication, freshness and recovery around them. The storage engine does not require
a JGit fork.

This audit targets Gerrit 3.14.2 and its pinned JGit revision,
[`38da6c1f8f8bc2ec7a19f4115fcea7c94a5875fa`](https://eclipse.googlesource.com/jgit/jgit/+/38da6c1f8f8bc2ec7a19f4115fcea7c94a5875fa/).
The [consistency contract](consistency.md) defines the protocol; this document connects that
contract to the APIs and test sources.

## One publication hook covers the Git files

`DfsObjDatabase.commitPack(newPacks, replacements)` gives the backend both additions and
superseded inputs. `DfsReftableBatchRefUpdate` writes an atomic ref batch into an immutable
reftable. WalGerrit can therefore upload the dependencies and make one manifest CAS the commit
point for the entire ref batch.

JGit's process-local locks do not provide distributed exclusion. WalGerrit must check the
manifest's store version, validate the ref generation and resolve uncertain writes itself.

## Two tokens protect different state

The **store version** is an opaque token, an ETag on S3. Conditional replacement against it
prevents lost manifest updates. The local backend provides equivalent exclusion through JVM and
OS file locks beneath `.object-locks/`, followed by atomic replacement.

The manifest's **`ref_revision`** identifies the live reftable stack. It advances on ref updates
and reftable compaction, but not on object-only additions. This lets an object flush coexist with
a prepared ref transaction without invalidating its ref checks.

A publication merges its changes into the current inventory and conditionally replaces the
manifest. A store-version conflict with an unchanged ref revision can retry the merge. A changed
ref revision requires fresh JGit validation and a new table. Comparing the protobuf `revision`
field without a conditional store write would not prevent lost updates.

## JGit path-by-path audit

| Operation | JGit path | WalGerrit responsibility |
| --- | --- | --- |
| Object insertion | `DfsInserter.flush` calls `commitPack`. | Upload files; retain pending packs until a ref publication or handle close. |
| Received pack | `DfsPackParser.parse` completes the pack and calls `commitPack`. | Apply the same pending-pack protocol after thin-pack resolution. |
| Atomic ref batch | `ReftableBatchRefUpdate.execute` validates commands; `DfsReftableBatchRefUpdate` writes the table. | Revalidate first, then publish all accepted commands with their logical WAL payload. |
| Single update, delete or symbolic link | `DfsRefUpdate` delegates to reftable compare-and-put/remove operations. | Use the same ref-generation checks. |
| Ref conflict | JGit maps I/O failure to `LOCK_FAILURE`; Gerrit interprets an aborted atomic batch. | Retry storage conflicts with full validation; preserve real expected-value conflicts. |
| Reftable ordering | Update indices and `DfsPackDescription.reftableComparator` order the stack. | Persist source, ordering metadata and update indices; reject stale ref generations. |
| Commit-time table folding | `DfsReftableBatchRefUpdate` can replace the top table. | Publish the new table and removal together; rebuild if that input was superseded. |
| Pack and table compaction | `DfsPackCompactor` supplies outputs and source descriptions. | Lease the work, check live inputs, CAS the result and retain old files for the grace checks. |
| Full DFS GC | `DfsGarbageCollector` commits reachability-based outputs and removals. | Disable Gerrit GC and reject `GC`, `GC_REST` and `UNREACHABLE_GARBAGE` sources. |
| Multi-pack index | MIDX descriptions include covered-pack relationships. | Keep disabled until the manifest can represent those relationships. |
| Ref rename | `DfsRefRename` creates the destination, then deletes the source separately. | Do not claim atomic rename; no WalGerrit override supplies it. |
| Cache refresh | DFS readers consult the pack list; refs have a separate stack cache. | Adopt the node's newest manifest and revalidate at defined boundaries, without promising a fixed request snapshot. |

JGit's compactor pre-commit hook can add descriptions to the same `commitPack` call. It does not
remove the backend's responsibility to validate the complete replacement set. WalGerrit's
compactor uses `DfsPackCompactor`; it does not invoke command-line Git for runtime maintenance.

Relevant pinned sources:
[DfsObjDatabase](https://eclipse.googlesource.com/jgit/jgit/+/38da6c1f8f8bc2ec7a19f4115fcea7c94a5875fa/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/dfs/DfsObjDatabase.java),
[DfsReftableBatchRefUpdate](https://eclipse.googlesource.com/jgit/jgit/+/38da6c1f8f8bc2ec7a19f4115fcea7c94a5875fa/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/dfs/DfsReftableBatchRefUpdate.java),
[ReftableBatchRefUpdate](https://eclipse.googlesource.com/jgit/jgit/+/38da6c1f8f8bc2ec7a19f4115fcea7c94a5875fa/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/reftable/ReftableBatchRefUpdate.java),
[DfsPackCompactor](https://eclipse.googlesource.com/jgit/jgit/+/38da6c1f8f8bc2ec7a19f4115fcea7c94a5875fa/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/dfs/DfsPackCompactor.java).

## Ref retries rebuild the transaction

`LocalWalGitRefDatabase` revalidates at the start of each attempt. `GroupPublisher` admits
nonconflicting local ref names, and the node lock protects validation and table construction.
Independent prepared batches can share a publication, together with pending object packs.

When another node changes the ref generation, the losing batch reruns expected-value,
namespace and object checks against fresh state. It has at most five total attempts. Reusing the
old table would reuse its stale update index and validation assumptions. A local folded table
replaced by compaction requires the same rebuild.

The manifest CAS is authoritative. Once success is established, a cache-update or maintenance
notification failure cannot turn the committed ref update into a failure. Invalidate the cache
and rebuild it on a later read.

## Outcome recovery follows history

Every CAS attempt has a sequence and transaction ID. After any CAS error, including a
precondition error from an SDK retry, `ManifestStore` checks whether that identity is on the
committed chain. Checking only the current head is insufficient: later writes or compactions
may already have advanced it.

An attempt present at its sequence committed. A different transaction at that sequence proves
the old CAS cannot land. A head below the attempted sequence proves neither outcome. Recovery
can append an empty `PACK` entry to fence that older version, then resolve the original attempt.
Failed verification preserves uncertainty.

Pending-pack readers also consult publication identity. A pack already committed and then
compacted must not reappear as an unpublished addition. See the
[full recovery protocol](consistency.md#ambiguous-outcomes-and-recovery) and its retention
assumptions.

## Compaction preserves the input objects

WalGerrit's `Compactor`, `CompactionPolicy`, `StoreLease` and `Reclaimer` divide the work:

1. Acquire the repository lease and select live input files.
2. Let JGit rewrite them and upload every output.
3. Publish additions and superseded names together, checking that each input is still live.
4. Preserve concurrent additions and let other nodes adopt the result through manifest reads.
5. Reclaim retired files only after the file-age and observed-absence intervals.

Object and reftable compaction are separate publications. There is no reachability-based object
pruning, retained-snapshot registry or distributed reader lease. Reclamation depends on bounded
writer and reader lifetimes; see [Compaction](compaction.md#reclamation).

## Gerrit remains responsible for NoteDb semantics

Gerrit flushes new objects before executing its atomic ref batch and performs secondary-index
work afterward. WalGerrit's pending-pack and manifest protocol preserves that ordering. Atomicity
is per repository; an operation spanning projects does not become globally atomic.

The index tailer supports local Lucene with synchronous commits. Public event notifications use
separate best-effort entries and cannot be treated as the durable index payload.

For initialization, `GitRepositoryManagerOnInit` retains the local fallback until the system
injector is ready. `SitePathInitializer.postRun` then installs the configured manager. Init-only
account, group, external-ID and versioned-metadata helpers use that switching manager, avoiding a
shadow local `All-Users`. The fork also contains import/seeding commands, test integration and
optional stateless sessions; the initialization seam is not its only change.

## Verification

The repository contains tests for these properties. This inventory describes coverage, not the
result of a particular execution.

| Property | Test sources in `src/test/java/dev/walgerrit/` |
| --- | --- |
| Atomic refs, expected values and concurrent writers | `LocalWalGitTransactionTest`, `ConcurrentWritersTest`, `GroupCommitTest` |
| Lost responses, delayed CAS, failed verification and recovery fencing | `PublicationFaultTest`, `PublicationRecoveryTest`, `RecoveryFaultTest` |
| Queued/interrupted publishers and pending-pack snapshots | `GroupPublisherFailureTest` |
| Manifest freshness and request counts | `ManifestFreshnessTest`, `ManifestCacheTest`, `ManifestReadCountTest` |
| S3 conditional writes, listings, range reads and multipart upload | `S3ObjectStoreContractTest` |
| Compaction, lease contention and grace intervals | `CompactorTest`, `StoreLeaseTest`, `SweepLeaseTest`, `ReclaimerRetentionTest` |
| Index replay, cursor seeding and readiness | `IndexEventTailerTest`, `IndexCursorSeederTest` |
| Import and source preservation | `RepositoryImporterTest` |

Run `./mvnw verify` in `plugins/walgerrit`. S3 contract tests require
`WALGERRIT_S3_TEST_ENDPOINT` and credentials; they are skipped without an endpoint. The
[build workflow](../../../.github/workflows/walgerrit-build.yml) supplies MinIO before verification.

The [smoke script](../scripts/smoke-test.sh) runs the actual WAR/library pair through init,
reindex, daemon startup, readiness, restart, maintenance, index rebuilding and import. The
[acceptance script](../scripts/acceptance-tests.sh) runs Gerrit's tests against the local backend.
See the [README](../README.md#verify-the-integration) for commands and instrumentation caveats.

Production qualification also needs results from the target storage service and deployment:
concurrent nodes, process failure at publication boundaries, cold-node reads after acknowledged
writes, full review/submit workflows and migration recovery. Source-level tests and historical
success reports do not replace those results.
