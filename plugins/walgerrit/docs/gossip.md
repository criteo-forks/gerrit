# Peer wake-ups over UDP

After a manifest CAS succeeds, the writing node sends a UDP hint to each configured peer. Each
receiver schedules index replay for that repository and marks its cached manifest for
revalidation. This lets nodes discover writes before their next sweep or periodic manifest read.

UDP delivery is best effort. Conditional reads still determine what is committed, and index
sweeps catch up after missed hints. The design follows the notification approach described in
Cursor's [Git at any scale](https://cursor.com/blog/git-at-any-scale).

## Datagram format

A datagram contains a four-byte magic (`WGG1`), a serialized `GossipHint` and, when a shared secret
is configured, a 32-byte HMAC-SHA256 over the preceding bytes.

| Field | Meaning |
| --- | --- |
| `repo` | Project name. |
| `manifest_version` | Opaque store version of the announced manifest; its ETag on S3. Compared for equality only. |
| `revision` | Manifest revision, used to ignore cache invalidations for versions already observed. |
| `head_seq`, `head_transaction_id` | Announced log head; currently unused by the receiver. |
| `writer` | Sender identity, `host:pid`; a node ignores hints from its own host. |
| `sent_at_epoch_millis` | Sender timestamp; currently unused by the receiver. |

The codec limits datagrams to 1,200 bytes. Size depends on the project name and version token.
This limit reduces fragmentation on typical networks; it cannot guarantee an unfragmented packet
on every path.

## Sending and receiving

Every successful manifest publication queues a hint, including pack, ref, compaction, event and
index-update publications. A sender thread encodes the hint and sends one datagram per resolved
peer address. Network I/O runs outside the publishing thread. A full queue drops the hint and
increments `dropped`.

A receiver:

1. Checks the magic, size, payload and project name. If a secret is configured, it also verifies
   the HMAC.
2. Ignores hints from its own writer host.
3. Records the announced version and revision in the manifest cache unless it already holds
   that manifest or a newer one. An outstanding hint forces the next open or lookup to revalidate,
   even with `manifestRevalidateOnOpen = false` or `manifestRevalidateInterval = 0`.
4. Queues the repository on the index tailer's thread unless that exact version was already
   replayed. The tailer conditionally reads the current manifest, then applies unseen log entries
   through the same catch-up path as a sweep.

Hints for a repository coalesce while its replay is queued. The replay reads the store when it
runs, so it includes publications announced while it was waiting. A hint received during replay
can queue another pass. Failed replay leaves retries and any index rebuild to the next sweep.

A manifest read updates the node-wide cache, which other Git handles can then adopt. Reading the
announced revision settles its hint. An unchanged store response clears only the hint observed
before that request; hints received while the request was in flight remain pending.

## Peer discovery

Peers come from both configured sources:

- `gossipPeer`: a fixed `host[:port]`, repeated for each peer. IPv6 with a port uses `[address]:port`.
- `gossipPeerDnsName`: a name whose address records supply peers on `gossipPort`, such as a
  Kubernetes headless service.

Before sending, the sender refreshes names whose `gossipPeerRefreshInterval` has elapsed. The [JVM's DNS cache](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetAddress.html) also affects when changed records become visible. Failed or empty resolutions retain
that source's previous addresses; other sources can still refresh. Addresses are deduplicated.

Every node serves every repository, so each sender contacts every peer directly. Peers do not
relay hints. A node may send to its own address and discard the hint by writer host. Nodes that
share a host name also ignore one another's hints, as they do for
[events](events.md#each-node-replays-foreign-events).

## Failure behavior

| Event | Effect |
| --- | --- |
| Datagram lost | Conditional reads and index sweeps still discover the write. |
| Datagram duplicated or reordered | It may cause another conditional read. Cache invalidation skips older revisions; replay skips an exact version already caught up. |
| Datagram forged | Unsigned hints can trigger reads and queued work, including failed lookups for nonexistent projects. A configured HMAC rejects hints without a valid signature; captured signed hints can still be replayed. |
| Hint for an unseen repository | The tailer attempts to fetch its manifest and replay its log. A missing repository fails that attempt. |
| Peer down or address stale | A send may fail or appear successful without delivery. Only local send errors increment `dropped`. |
| Port already bound | The daemon fails to start. |

Hints never supply Git data or authorize a publication. Restrict access to the gossip port to
cluster peers, especially when running unsigned. The receiver has no rate limit, and the tailer
queue has no limit on the number of distinct repository names.

## Configuration

Gossip runs in daemons when `gossipEnabled` is true and at least one peer source is configured.
Batch programs do not open the socket. All keys belong to `[walgerrit]`:

```ini
[walgerrit]
  gossipPeerDnsName = gerrit.gerrit-poc.svc.cluster.local
  gossipPort = 29419
  gossipPeerRefreshInterval = 30 sec
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `gossipEnabled` | `true` | Enable sending and receiving when a peer source is configured. |
| `gossipPeer` | none | Peer as `host[:port]`; repeat for multiple peers. |
| `gossipPeerDnsName` | none | DNS name whose addresses are peers on `gossipPort`. |
| `gossipPort` | `29419` | UDP listening port and default peer port. |
| `gossipListenAddress` | all interfaces | Local address to bind. |
| `gossipPeerRefreshInterval` | `30 sec` | Minimum interval between peer-name refreshes. |
| `gossipSecret` | none | Shared HMAC secret. Put it in `secure.config` on every node. |

Increasing `indexPollInterval` reduces idle listing traffic but delays recovery from missed hints,
failed replay and stale cursors. Sweep duration, backlog and failures add to the delay. The setting
does not change the cache-reuse window controlled by `manifestRevalidateInterval`.

## Metrics

Cumulative counters under `walgerrit/gossip/`:

| Metric | Meaning |
| --- | --- |
| `sent` | Successful local datagram sends; does not confirm delivery. |
| `dropped` | Hints lost to a full outbox, encoding size limit or local send error. |
| `received` | Datagrams received on the gossip port. |
| `accepted` | Valid foreign hints passed to the manifest cache and listeners. |
| `ignored` | Hints from this node's writer host. |
| `rejected` | Invalid datagrams, signatures or project names. |

If `received` grows while `accepted` stays at zero, compare `ignored` and `rejected`. Shared host
names raise `ignored`; signature mismatches and malformed packets raise `rejected`. Rising
`dropped` can indicate queue pressure, oversized hints or local send errors. An empty resolved
peer list produces no sends and does not increment `dropped`.

## Kubernetes

Configure a headless service and set `gossipPeerDnsName` to its DNS name:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: gerrit
spec:
  clusterIP: None
  selector:
    app: gerrit
  ports:
    - name: gossip
      port: 29419
      protocol: UDP
```

Allow UDP port 29419 between pods in the network policy. [Service DNS](https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/) normally returns ready
endpoints; use `publishNotReadyAddresses` if peers must include unready pods. Give each node a
distinct host name so it accepts the other nodes' hints.
