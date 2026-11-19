# Write forwarding

Every ready node accepts writes. For each project, rendezvous hashing ranks the node and the
peers it can reach. Nodes that see the same candidates choose the same executor. The shared
store's conditional writes, index barrier and change keys handle overlapping writers, including
periods of partial connectivity. Placement has no lease or election.

## Peer membership

The plugin supplies its StatefulSet pod name through `HOSTNAME` and supplies addresses from
`gossipPeer` and `gossipPeerDnsName`. Every node needs a distinct stable name. The daemon
probes the addresses over private HTTPS. A ready member answers `GET /peerz` with its name.
The configured address and peer port determine the destination of subsequent connections.
Hellos carry no dialing instructions. The serving gate also covers the private listener.

All members use the same dedicated `forwardingSecret`. Gerrit uses its 32 bytes as an Ed25519
key and wraps the public key in a self-signed certificate in memory. Both sides authenticate to
each other on the private TLS 1.3 listener using that cluster identity. Pods keep their own
public-facing SSH host keys. Membership grants the ability to assert any active Gerrit account
for an eligible project write. Use a different secret for each cluster and for gossip.

## Request handling

The entry node authenticates the user. An identified user's eligible request takes at most one
hop. The destination checks its own node name, the account's active state and the permissions
for the operation. Only its private connector accepts a peer identity. The public connector
rejects peer headers and `/peerz`.

| Entry request | How it executes |
| --- | --- |
| SSH `git-receive-pack PROJECT` | A single private HTTPS POST carries the bidirectional Git stream. The destination runs JGit receive-pack, advertises its refs and capabilities, then processes the client's commands and pack. The response carries stdout, stderr and a final exit status. Git push options, side-band and report-status use their native encoding. |
| Smart HTTP push | The receive-pack advertisement and POST remain normal Git HTTP requests. Each request is authenticated at entry. |
| REST POST, PUT and DELETE under `/projects/NAME` or `/changes/PROJECT~…` | Eligible JSON or explicitly empty requests go to the preferred project node. Supported conditional, Git, deadline, trace and representation headers travel with the request. The peer establishes a request-scoped identity. |

The relay constructs the peer request from an allowlist. Client credentials, cookies, CSRF
tokens and proxy address headers stay at entry. SSH uses the address of its client connection.
HTTP uses the original TCP address on a proxy listener, which generally identifies the ingress.
The peer preserves that address for the request and Gerrit's remote-peer provider. A peer
connection has no authenticated client source port.

Reads, other SSH commands, SSH command-line reviewer/CC options, run-as requests and anonymous
requests execute at entry. So do REST requests with a query, form or unrecognized media type,
browser requests with an Origin header, and requests with site-specific headers outside the
allowlist. Cookie-authenticated REST writes must pass Gerrit's normal CSRF check at entry.
A site with a custom `httpd.filterClass` executes every write locally. Such a filter can
consume a token, establish a principal or alter a request in a way the other pod cannot reproduce.

## Configuration and deployment

| Setting | Default | Effect |
| --- | --- | --- |
| `walgerrit.forwardWrites` | `true` in the daemon | `false` disables handoff, probing and the private connector. |
| `walgerrit.forwardingSecret` | unset | Base64 encoding of 32 cryptographically random bytes in `secure.config`. An absent or malformed key leaves writes local. |
| `walgerrit.forwardingPort` | `9443` | Private TCP port. Configure the same port and HTTP context path on all nodes. |
| `sshd.enablePeerDaemon` | `false` with a forwarding key, otherwise `true` | Set to `false` in this deployment to disable SSH daemon-account login with host keys and `etc/peer_keys`. Normal user SSH keys still work. |

Use the existing secure-config delivery mechanism. Permit peer TCP only between this deployment's
pods. Restrict the public HTTP port to the trusted ingress and restrict the SSH port to the site's
approved sources. The ingress must replace client-supplied forwarding headers. A membership key
change takes effect when a pod restarts. During a staggered change the two key groups execute writes
independently. A cluster with no reachable members continues to serve local writes.
Peer discovery uses the configured gossip address sources even when UDP gossip is disabled.

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
| Gerrit refuses the write | Relay the refusal. |

After an uncertain result, fetch the target ref or read the change and its messages before
resubmitting. A deliberate second REST request can create a second effect. A terminating
pod can lose its final reply.

`write_forwarding/requests` has `http` and `ssh` transport labels. Outcomes are
`forwarded` (including peer refusals), `fallback`, `busy` (entry),
`inbound_busy` (destination) and `failed`. Peer-set changes and ambiguous HTTP failures
appear in the daemon log. Supply a request ID or Gerrit trace ID for correlation across
the hop.
