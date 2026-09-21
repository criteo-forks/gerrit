# Peer wake-ups over UDP

WalGerrit nodes share nothing but the store. Each learns of the others' writes by asking it: a
conditional manifest read when a repository is opened or a handle's revalidation interval elapses,
and one listing of the `manifests/` prefix per index sweep. Both bound staleness by a configured
interval rather than by the write itself.

Gossip adds a channel that is faster and deliberately unreliable, in the way Cursor's
[Git at any scale](https://cursor.com/blog/git-at-any-scale) describes for its replicas: after a
manifest CAS lands, the writing node sends one small UDP datagram to every peer, carrying what a
receiver needs to catch up straight from the store. The receiver treats it as a hint. Correctness
still comes from conditional reads and the sweep; gossip only moves them earlier.

## The datagram

A datagram is a four-byte magic (`WGG1`), a serialized `GossipHint` and, when the cluster shares a
secret, a 32-byte HMAC-SHA256 over the bytes before it.

| Field | Meaning |
| --- | --- |
| `repo` | The project name. |
| `manifest_version` | The store's version token for the manifest the sender wrote; its ETag on S3. Compared for equality, like a listed version. |
| `revision`, `head_seq`, `head_transaction_id` | The manifest's revision and log head, so a receiver can tell a hint older than what it holds without I/O. |
| `writer` | The sender's writer identity, `host:pid`; a node drops its own hints. |
| `sent_at_epoch_millis` | Sender clock, for diagnostics only. |

A hint is well under 200 bytes; the codec refuses anything above 1200 bytes so no datagram is ever
fragmented.

## What the sender does

Every publication that lands, whether it carries packs, a ref transaction, a compaction, an event
batch or an index update, is a manifest replacement, and every one is announced. The publication
listener that also feeds the compactor queues a hint; a sender thread encodes it and sends one
datagram per peer address. The publishing thread never waits on the network and never sees a send
fail. A full queue drops the hint, counted as `dropped`, and the receivers' sweeps cover the gap.

## What a receiver does

For each datagram on its gossip port a node:

1. Rejects what is not a hint for this cluster: wrong magic, a bad or missing HMAC when a secret
   is configured, a malformed payload, or a project name the layout would not map to a key.
2. Ignores hints whose writer host is its own; they are its own publications coming back.
3. Records the announced version and revision in its manifest cache. Until the node reads a
   manifest at that revision, or the store itself reports the node's view current, the
   repository does not count as recently validated. The next open makes a conditional read even
   with `manifestRevalidateOnOpen = false`, and an open handle revalidates on its next lookup
   whatever its interval, including an interval of `0`. A hint for a manifest the node already
   holds, or an older one, records nothing.
4. Wakes the index-event tailer for the repository. On the tailer thread, the same catch-up a
   sweep performs runs for that one repository: the cached manifest if it already matches the
   announced version, otherwise one conditional read, then the intervening log entries are applied
   to the local indexes, foreign events are dispatched and derived caches evicted. Wake-ups for a
   repository coalesce while one is queued; a hint that arrives during the replay queues another,
   since the replay may have read an older manifest. A wake-up that cannot be replayed leaves the
   decision to rebuild to the sweep.

The conditional read the tailer makes lands in the node-wide manifest cache, so Git handles on
that node adopt the newer manifest without a read of their own.

## Membership

Peers come from two sources, combined:

- `gossipPeer`, a fixed `host[:port]`, one key per peer.
- `gossipPeerDnsName`, a name whose every address record is a peer on `gossipPort`. A Kubernetes
  headless service resolves to every pod of a StatefulSet, so a cluster needs no per-pod list.

Names are resolved again after `gossipPeerRefreshInterval`, so a replaced pod is reached without a
restart; a resolution that yields nothing keeps the previous answer. A node may find its own
address among the peers; it sends to itself and ignores the result by writer host.

Every WalGerrit node serves every repository, so a hint goes to every peer directly. There is no
placement by repository, no relaying between peers and no membership protocol: the peer list is
configuration, and a peer that is down simply misses hints until it is back, when its own sweep
catches it up.

## Guarantees and failure modes

Gossip never changes what a node may serve, only when it asks the store.

| Event | Effect |
| --- | --- |
| Datagram lost | The receiver converges at its next sweep or conditional read, as without gossip. |
| Datagram duplicated or reordered | At most one conditional read that returns nothing new; an older hint is dropped against the cache. |
| Datagram forged | Without a secret, one conditional read per forged hint; with a secret, dropped. Nothing a hint says is ever served to a client. |
| Hint for a repository this node has not seen | The first read of it fetches the manifest as it always would. |
| Peer down or address stale | Sends fail or go nowhere; counted as `dropped`; the peer's sweep covers it. |
| Port already bound | The daemon refuses to start, as with any misconfiguration. |

Nodes that share a host name, and so a writer host, ignore one another's hints, as they already
treat one another's events as local. See [Events](events.md#each-node-replays-foreign-events).

## Configuration

Gossip is active when it is enabled and at least one peer source is configured. All keys belong
to `[walgerrit]`; the shown values are defaults.

```ini
[walgerrit]
  gossipEnabled = true
  gossipPeerDnsName = gerrit.gerrit-poc.svc.cluster.local
  gossipPort = 29419
  gossipListenAddress = 0.0.0.0
  gossipPeerRefreshInterval = 30 sec
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `gossipEnabled` | `true` | Send and receive wake-ups once a peer source is configured. |
| `gossipPeer` | none | One peer as `host[:port]`; repeat the key for each peer. |
| `gossipPeerDnsName` | none | A name whose every address record is a peer on `gossipPort`. |
| `gossipPort` | `29419` | UDP port to listen on, and to send to for peers that name none. |
| `gossipListenAddress` | all interfaces | Local address to bind. |
| `gossipPeerRefreshInterval` | `30 sec` | How often peer names are resolved again. |
| `gossipSecret` | none | Shared secret; datagrams without its HMAC are dropped. Put it in `secure.config`, which Gerrit reads into the same configuration. |

With gossip in place, `indexPollInterval` bounds only what a lost datagram missed. Raising it
from the default `5 sec` to `30 sec` or more cuts the idle listing traffic in proportion without
delaying convergence in the common case. The sweep also refreshes the node's validation time for
unchanged repositories, so a longer interval makes `manifestRevalidateOnOpen = false` reuse
views for longer.

Batch programs never open the socket, whatever the configuration.

## Metrics

Cumulative counters under `walgerrit/gossip/`:

| Metric | Meaning |
| --- | --- |
| `sent` | Datagrams sent, one per peer per publication. |
| `dropped` | Hints not sent: the outbox was full, a send failed or the hint was too large. |
| `received` | Datagrams received on the gossip port. |
| `accepted` | Hints from other nodes this node acted on. |
| `ignored` | Hints about this node's own publications. |
| `rejected` | Datagrams that were not hints for this cluster. |

A steady `received` with `accepted` at zero on every node means the nodes share a host name or the
secret differs. `dropped` growing on one node points at its peer list.

## Kubernetes

Give the StatefulSet a headless service and name it in `gossipPeerDnsName`; declare the port so a
network policy can allow it between pods:

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

Each pod's `HOSTNAME` is its pod name, which is what makes writer hosts distinct.
