# Roadmap

WalGerrit implements the storage and convergence paths below. It remains experimental: an
implemented feature and a passing test are evidence for a specific contract, not a production
qualification for every deployment.

## Implemented

| Area | Capabilities | Details |
| --- | --- | --- |
| Gerrit integration | Library modules; daemon and batch repository access; init helpers; acceptance-test adapter. | [Architecture](architecture.md) |
| Storage | Local and S3-compatible backends; immutable files; format-3 manifest CAS; bounded retries and ambiguous-outcome recovery. | [Consistency](consistency.md) |
| Reads | Shared manifest cache; conditional revalidation; ranged S3 pack reads; configurable cache trimming. | [README](../README.md) |
| Indexes | Durable ref payloads; node-local replay cursors; startup catch-up; readiness; automatic rebuild for stale cursors. | [Index events](index-events.md) |
| Maintenance | Geometric object compaction; tiered reftable merging; leases; grace-based reclamation; heap-sized JGit block cache. | [Compaction](compaction.md) |
| Import | Bare-repository import; optional staging, repack and connectivity checks; ref verification; offline cursor seeding. | [Import](import.md) |
| Notifications | Best-effort cross-node forwarding through separate WAL entries. | [Events](events.md) |
| Sessions | Optional signed cookies and a shared signing key. | [Web sessions](web-sessions.md) |
| Packaging | Matched WAR/JAR bundles, checksums, smoke testing and tagged prereleases. | [Deployment bundle](artifact-bundle.md) |

The [JGit audit](jgit-cas-deep-dive.md#verification) maps the correctness properties to test
sources and runnable checks. Use results from the exact revision and environment being deployed.

## Remaining work

- Batched Lucene checkpoints to replace the cost of committing every replayed index write.
- Replay lag/error metrics and operational alerts beyond the readiness gauge and logs.
- Durable repository deletion, snapshots and ongoing integrity checking.
- A tested end-to-end cutover and rollback procedure, including writes accepted after cutover.
- Explicit bounds or distributed protection for writers and readers that outlive reclamation's
  grace period.
- An atomic ref-rename implementation before exposing that JGit API as a general guarantee.

## Optional extensions

Peer wake-ups could reduce index latency below the polling delay. Multi-pack indexes could reduce
pack-index scans, but require a storage-format extension for coverage relationships. Native GCS
conditional requests and per-session revocation are not implemented.
