# Roadmap

WalGerrit remains experimental. The features below are implemented; deployment qualification
still requires tests against the target storage service and configuration.

## Implemented

| Area | Capabilities | Details |
| --- | --- | --- |
| Gerrit integration | Library modules; daemon and batch repository access; init helpers; acceptance-test adapter. | [Architecture](architecture.md) |
| Storage | Local and S3-compatible backends; immutable files; format-4 manifest CAS; bounded retries and ambiguous-outcome recovery. | [Consistency](consistency.md) |
| Names | Id-keyed repositories; catalog of name bindings; rename and delete with write-epoch fencing; account watches, destinations and subscription permissions rewritten; resumable operations; index reconciliation on every node; replication from the rename-project plugin. | [Project names](namespace.md) |
| Reads | Shared manifest cache; conditional revalidation; ranged S3 pack reads; configurable cache trimming. | [README](../README.md) |
| Indexes | Durable ref payloads; node-local replay cursors; startup catch-up; readiness; automatic rebuild for stale cursors. | [Index events](index-events.md) |
| Maintenance | Geometric object compaction; tiered reftable merging; leases; grace-based reclamation; heap-sized JGit block cache. | [Compaction](compaction.md) |
| Import | Bare-repository import; optional staging, repack and connectivity checks; ref verification; offline cursor seeding. | [Import](import.md) |
| Notifications | Best-effort cross-node event forwarding; reindexed documents journaled so every node's index follows. | [Events](events.md) |
| Peer wake-ups | One UDP hint per publication to every peer; receivers revalidate and replay ahead of the sweep; optional HMAC. | [Gossip](gossip.md) |
| Sessions | Optional signed cookies and a shared signing key. | [Web sessions](web-sessions.md) |
| Packaging | Matched WAR/JAR bundles, checksums, smoke testing and tagged prereleases. | [Deployment bundle](artifact-bundle.md) |

The [JGit audit](jgit-cas-deep-dive.md#verification) maps the correctness properties to test
sources and runnable checks. Use results from the exact revision and environment being deployed.

## Remaining work

- Batched Lucene checkpoints to replace the cost of committing every replayed index write.
- Replay lag/error metrics and operational alerts beyond the readiness gauge and logs.
- Reclaiming a deleted repository's files, snapshots and ongoing integrity checking.
- Renaming a parent, or a project that superprojects subscribe to; both are refused. Textual
  references are not rewritten; those in All-Projects' configuration and in accounts' watch
  filters and named queries are reported.
- A tested end-to-end cutover and rollback procedure, including writes accepted after cutover.
- Explicit bounds or distributed protection for writers and readers that outlive reclamation's
  grace period.
- An atomic ref-rename implementation before exposing that JGit API as a general guarantee.

## Optional extensions

Multi-pack indexes could reduce pack-index scans, but require a storage-format extension for
coverage relationships. Native GCS conditional requests and per-session revocation are not
implemented.
