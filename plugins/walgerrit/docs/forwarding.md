# Write forwarding

Every ready node accepts writes. Rendezvous hashing ranks the node and the peers it can reach,
and the node hands an eligible write to the highest-ranked one. Nodes that see the same
candidates choose the same executor. The choice is a preference: the shared store's conditional
writes, index barrier and change keys handle overlapping writers, including periods of partial
connectivity. Placement has no lease or election.

## Placement

`walgerrit.placement` selects what the nodes are ranked by.

| Value | Executor | Eligible writes |
| --- | --- | --- |
| `project` (default) | One per project. | Pushes, and REST writes whose address names the project: `/projects/NAME` and `/changes/PROJECT~…`. |
| `cluster` | One for every write. | Pushes, and REST writes to core views under `/changes/`, `/projects/`, `/accounts/` and `/groups/`, however they identify their target. |

Set the same value on every node. A destination applies its own placement to what it is handed
and refuses what that placement keeps local; the entry node then executes the request itself.
Nodes with different values therefore stay correct and forward less.

With `cluster`, the node that currently ranks first also runs compaction, and the gauge
`write_forwarding/cluster_executor` is true on it. The log line for a peer-set change names
the executor.

## Peer membership

The plugin supplies its StatefulSet pod name through `HOSTNAME` and supplies addresses from
`gossipPeer` and `gossipPeerDnsName`. Every node needs a distinct stable name. The daemon
probes the addresses over private HTTPS every second. A ready member answers `GET /peerz`
with its name and the version of the hand-over rules it applies; a node joins only members that
state its own version. The configured address and peer port determine the destination of
subsequent connections. Hellos carry no dialing instructions. The serving gate also covers the
private listener.

A member answers a hello only while it can execute writes:

- **Store health.** A daemon reads the catalog manifest every `storeProbeInterval` and watches
  pack, index and reftable uploads, log-entry puts, and manifest creation and compare-and-swap.
  Lost races do not count as failures. When no read succeeds for `storeProbeTimeout`, or three
  publication writes fail in a row, the node answers `503` to hellos and forwards eligible writes
  to the best reachable peer even if the node ranks first. With no peer it executes them locally.
  The write-failure condition clears when a publication lands or `storeProbeTimeout` passes
  without another failure.
  `walgerrit/store/reachable` reports the state. The node writes nothing to the store to test it.
- **Stopping.** With `drainOnStop` set, a stopping daemon stops answering hellos, hands new
  eligible writes to its peers and keeps serving for that long before the HTTP listener closes.
  Peers drop it at their next probe. Give the pod a termination grace period that covers it.

A node in either state refuses forwarded writes as `unavailable`. The peer executes them locally
and excludes the node until it answers a hello. When no node reaches the store, a write crosses
at most once and is tried where it arrived.

A peer normally joins after `forwardingJoinProbes` consecutive hellos and stays while it answers.
With a value above one, a node that starts or recovers its store immediately admits answering
peers and leaves eligible writes to them while they count its hellos, so both sides change
executor at about the same time. Nodes starting together hand writes to each other during this
interval. A flapping peer cannot join a settled node without the required streak.

All members use the same dedicated `forwardingSecret`. Gerrit uses its 32 bytes as an Ed25519
key and wraps the public key in a self-signed certificate in memory. Both sides authenticate to
each other on the private TLS 1.3 listener using that cluster identity. Pods keep their own
public-facing SSH host keys. Membership grants the ability to assert any active Gerrit account
for an eligible write. Use a different secret for each cluster and for gossip.

## Request handling

The entry node authenticates the user and applies Gerrit's CSRF check. An identified user's
eligible request takes at most one hop. The destination checks its own node name, the request's
eligibility under its own placement, the account's active state and the permissions for the
operation. Only its private connector accepts a peer identity. The public connector rejects
peer headers and `/peerz`.

| Entry request | How it executes |
| --- | --- |
| SSH `git-receive-pack PROJECT` | A single private HTTPS POST carries the bidirectional Git stream. The destination runs JGit receive-pack, advertises its refs and capabilities, then processes the client's commands and pack. The response carries stdout, stderr and a final exit status. Git push options, side-band and report-status use their native encoding. |
| Smart HTTP push | The receive-pack advertisement and POST remain normal Git HTTP requests. Each request is authenticated at entry. |
| REST POST, PUT and DELETE | Requests of the placement's eligible classes go to the preferred node, with their query string and with any body other than a form. A request without a body carries an explicit zero length. The peer establishes a request-scoped identity. |

The relay forwards conditional, Git, content, language, deadline, trace and request-ID headers,
`Origin` and `X-Gerrit-UpdatedRef-Enabled`. Other headers, including credentials, cookies, CSRF
tokens, proxy address headers, browser fetch metadata and site-specific headers, stay at entry
without blocking forwarding. `Origin` selects CORS response headers; the peer's `Set-Cookie`
headers are dropped. SSH uses the client connection's address; HTTP uses the original TCP
address, which on a proxy listener is usually the ingress. The peer preserves the address for
the request and Gerrit's remote-peer provider; no authenticated client source port is available.

These execute where they arrive, under either placement. The reason is the label of
`write_forwarding/ineligible`.

| Request | Reason |
| --- | --- |
| Reads and SSH commands other than `git-receive-pack` | not counted |
| REST writes under `/config/` and `/plugins/`, which administer one node | `config`, `plugins` |
| REST writes under any other top-level collection | `endpoint` |
| Reviewed flags, which each node stores for itself | `reviewed` |
| External-ID deletion, which must see the caller's own login | `external_ids` |
| Reindexing and garbage collection of one change, project or account | `node_local` |
| A query with `access_token`, `$m` or `$ct`, or one that cannot be decoded | `token_query`, `query` |
| A form body | `form` |
| A path that is not a plain sequence of segments | `path` |
| A push with an unexpected content type, query or advertisement body | `content_type`, `query`, `body` |
| With `project` placement, a write whose address names no project | `no_project` |
| A request with `X-Gerrit-RunAs`, or from an impersonated user | `run_as`, `impersonated` |
| An anonymous request | `anonymous` |
| A cookie-authenticated REST write without the session's CSRF token | `csrf` |
| A request whose client address is unknown or whose path would be read as a host | `unforwardable` |
| An SSH push with command-line reviewer or CC options | `options` |
| With `cluster` placement, a view or collection addressed with a plugin's name, as `PLUGIN~NAME` | `plugin` |

With `project` placement, plugin views under projects or project-qualified changes are forwarded
like core views. With `cluster`, qualified plugin views and collections and the resources below
those collections stay at entry because they may depend on the entry node's session, headers or
state. No plugins are exempt. Unqualified plugin views cannot be distinguished from core views
and are forwarded. Node-local plugin views need a route under `/config/` or `/plugins/`.

A site with a custom `httpd.filterClass` executes every write locally. Such a filter can
consume a token, establish a principal or alter a request in a way the other pod cannot reproduce.

## Background writers

Writes that no request carries are placed by the code that makes them. Core binds
`Predicate<String>` named `writeExecutor`; it takes a project name and says whether this node
is the preferred executor of that project's writes at this moment. Without forwarding it is
always true.

```java
@Inject @Named("writeExecutor") Predicate<String> executes;
```

WalGerrit's compaction uses it under `cluster` placement: only the executor compacts after a
publication and in its sweeps. Under `project` placement every node compacts. Gerrit's own
scheduled jobs and third-party plugins are not gated.

Every node delivers local and replayed events to its listeners, so event-driven plugins can
write on every node regardless of placement. An event-driven or scheduled plugin can check
`writeExecutor` for the target project before writing to act only where it is preferred.
Otherwise, run it on one node or expect writes from every node.

## Configuration and deployment

| Setting | Default | Effect |
| --- | --- | --- |
| `walgerrit.forwardWrites` | `true` in the daemon | `false` disables handoff, probing and the private connector. |
| `walgerrit.forwardingSecret` | unset | Base64 encoding of 32 cryptographically random bytes in `secure.config`. An absent or malformed key leaves writes local. |
| `walgerrit.forwardingPort` | `9443` | Private TCP port. Configure the same port and HTTP context path on all nodes. |
| `walgerrit.placement` | `project` | `project` or `cluster`; see [Placement](#placement). Any other value stops the daemon at start. |
| `walgerrit.forwardingJoinProbes` | `1` | Consecutive hellos, one second apart, a peer must answer before this node hands it writes; see [Peer membership](#peer-membership). 1 to 3600. |
| `walgerrit.drainOnStop` | `0` | How long a stopping daemon hands its writes to peers before its HTTP listener closes. Zero stops at once. |
| `walgerrit.storeProbeInterval` | `2 sec` | Delay between store health reads. |
| `walgerrit.storeProbeTimeout` | `10 sec` | Time without a successful store read after which the node stops being a candidate, and how long failed publication writes keep it out. Zero disables both checks. |
| `sshd.enablePeerDaemon` | `false` with a forwarding key, otherwise `true` | Set to `false` in this deployment to disable SSH daemon-account login with host keys and `etc/peer_keys`. Normal user SSH keys still work. |

Use the existing secure-config delivery mechanism. Permit peer TCP only between this deployment's
pods. Restrict the public HTTP port to the trusted ingress and restrict the SSH port to the site's
approved sources. The ingress must replace client-supplied forwarding headers. A membership key
change takes effect when a pod restarts. During a staggered change the two key groups execute writes
independently. A cluster with no reachable members continues to serve local writes.
Peer discovery uses the configured gossip address sources even when UDP gossip is disabled.

For one executor, set on every pod:

```
[walgerrit]
  placement = cluster
  forwardingJoinProbes = 5
  drainOnStop = 5 sec
```

## Admission and outcomes

Outgoing HTTP handoffs use at most one third of `httpd.maxThreads`. Outgoing SSH handoffs use
at most half of the corresponding command executor. An entry at its outbound limit runs the
write locally. The destination admits at most one third of `httpd.maxThreads` peer writes
across all senders and both protocols. Admission precedes the account lookup and body read.
Excess requests receive `503` and `Retry-After`. The private listener has a 30-second idle
timeout. These limits cover relay work. Ordinary public requests, handshakes and the storage
and authentication systems still need capacity.

| Failure | Client outcome |
| --- | --- |
| Connection or TLS authentication fails before a write can have reached the peer | Execute locally. A failed peer leaves the candidate set until its next successful hello. |
| A smart HTTP advertisement loses its reply or receives a 503 | Advertise locally. |
| A submitted HTTP mutation loses its reply | Return 502 with an uncertain-outcome message. A failure partway through a response terminates that response. |
| A native SSH push loses the peer before any output or receives a 503 before its advertisement | Execute locally. The relay has not read any client data. |
| A native SSH push loses the peer after output begins | Report a failure. If the relay has consumed client input, the write may have landed. |
| The peer refuses before execution: `ineligible` for its rules, `destination` for a wrong node or `unavailable` when it cannot execute | Execute locally if the body is unread. The peer sends `X-Gerrit-Peer-Refused` with the reason; `Expect: 100-continue` can keep the body unread at entry. |
| The peer refuses such a hand-over after the body was sent | Return 503 with `Retry-After`. Nothing was written. |
| The peer refuses the caller: `account` for an inactive or unknown account, `identity` for an unusable identity, or `credentials` when the hop carried client credentials | Return 403; an SSH push fails. Neither node executes the request. |
| The peer refuses the hand-over for a reason the entry node does not know | Return 502; an SSH push fails. Nothing is executed on either node. |
| Gerrit refuses the write | Relay the refusal. |

After an uncertain result, fetch the target ref or read the change and its messages before
resubmitting. A deliberate second REST request can create a second effect. A terminating
pod can lose its final reply.

## Metrics

| Metric | Labels | Meaning |
| --- | --- | --- |
| `write_forwarding/requests` | `transport`: `http`, `ssh`; `outcome` | Writes by what the entry node did with them. |
| `write_forwarding/ineligible` | `transport`, `reason` | Writes kept at entry, by the reason in [Request handling](#request-handling). |
| `write_forwarding/peers` | | Peers this node may hand writes to. |
| `write_forwarding/cluster_executor` | | Whether this node is the executor under `cluster` placement. |
| `walgerrit/publications` | `repository`: `all_users`, `all_projects`, `catalog`, `other`; `kind`; `executor`: `preferred`, `other` | Manifest publications by this node, and whether it was the preferred executor of the repository when it published. |
| `walgerrit/ref_transaction_conflicts` | `repository`, `executor` | Ref transaction attempts that lost a conditional write to another publisher. |
| `walgerrit/store/reachable` | | Whether the store health read is succeeding and publication writes are not failing. |

| Outcome | Meaning |
| --- | --- |
| `forwarded` | The peer answered, including with Gerrit's own refusal. |
| `local` | Eligible, and this node is the preferred executor. |
| `ineligible` | Executed here because the request may not be handed over. |
| `fallback` | Executed here because the peer could not be reached. |
| `refused` | Executed here because the peer refused the hand-over for a reason about the request or itself. |
| `rejected` | The peer refused after the body was sent; the client was told to retry. |
| `denied` | The peer refused the hand-over for a reason about the caller; the client was refused and nothing was executed. |
| `busy` | Executed here because the entry was at its outbound limit. |
| `inbound_busy` | The destination's admission limit was reached. |
| `failed` | The link broke after the peer may have acted, or the peer refused for a reason this node does not know. |

`executor="other"` publications are the writes that placement did not reach: ineligible
requests, background writers, and writes made while the nodes disagreed about the executor.

Peer-set changes, health changes, refused hand-overs and ambiguous HTTP failures appear in the
daemon log. Supply a request ID or Gerrit trace ID for correlation across the hop.
