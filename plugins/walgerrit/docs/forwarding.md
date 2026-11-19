# Write forwarding

Every node accepts writes and forwards a project's writes to the preferred node when it has a
working link, executing them locally otherwise. Forwarding reduces index barrier waits and manifest
conflicts; a write is correct on whichever node executes it.

## Choosing the node

Each node ranks the candidates for a project by rendezvous hashing: the SHA-256 of the project
name and a node name, highest value first. The candidates are the node itself and the peers it has
a working link to. Nodes that see the same peers agree without talking to each other, and a node
that leaves moves only the projects it held.

A node's name is its `HOSTNAME`, the pod name in a StatefulSet. Names must differ between nodes and
stay the same across restarts.

A node always executes what a peer handed it. A write therefore crosses at most one link, however
the nodes' views of each other differ.

## Peers and links

The peers are the hosts of the gossip peer sources, `gossipPeer` and `gossipPeerDnsName`. Once a
second, a node asks each host for `/peerz` on its own HTTP port. A serving node answers with its
name and its SSH port; a node that is still catching up answers `503` like it does to everything
else. The hosts that answer are the candidates, except the one that gives the node's own name.

| Transport | Link | A peer is a candidate while |
| --- | --- | --- |
| HTTP | A new connection for each request, to the peer's HTTP port. | It answered the last hello and no request to it has failed since. |
| SSH | One session for each peer, opened after its first hello and reopened when closed. | It is an HTTP candidate and the session is open. |

The SSH session logs in as `Gerrit Code Review`, Gerrit's peer daemon user, with the node's own
host keys. The peer accepts a key that is one of its own host keys or is listed in its
`etc/peer_keys`, and the node accepts the peer's host key under the same rule. See
[Deployment](#deployment).

## What is handed over

| Request | Project taken from |
| --- | --- |
| SSH `git-receive-pack` | The command's project argument. |
| HTTP push: `GET .../info/refs?service=git-receive-pack` and `POST .../git-receive-pack` | The path. |
| REST requests other than `GET` and `HEAD` under `/projects/NAME` | The path. |
| REST requests other than `GET` and `HEAD` under `/changes/PROJECT~NUMBER` and `/changes/PROJECT~BRANCH~CHANGE-ID` | The change identifier. |

Everything else is executed where it arrives: fetches, queries and other reads, REST writes that
name a change by its number or Change-Id alone, `POST /changes/` whose project is in the body, and
SSH commands other than a push, such as `gerrit review`.

## Identity and permissions

Over HTTP the request reaches the peer unchanged, before the first node has authenticated it. The
peer authenticates the credentials and checks permissions as it would for a request of its own.
The first node adds `X-Gerrit-Forwarded-By` with its name, and `X-Forwarded-For` when the request
has none. The marker grants nothing: a request that carries it is executed where it arrives.

Over SSH the first node authenticates the client and runs
`suexec --as ACCOUNT --from CLIENT -- git-receive-pack ...` on the peer. The peer runs the push as
that account and checks its permissions itself. A push from a client that is itself impersonating
another account is executed where it arrives.

## Failures

A write is executed locally after all only when the peer cannot have acted on it. Once the peer may
have acted, the failure goes to the client and nothing is retried.

| Transport | What happened | Result |
| --- | --- | --- |
| HTTP | No connection to the peer could be made. | Executed locally. The peer stops being a candidate until its next hello. |
| HTTP | The peer gave no answer, or `503`, to a push's ref advertisement. | Executed locally. |
| HTTP | The connection failed after any other request was sent. | `502` with a message that the write may or may not have been applied. |
| HTTP | The connection failed while the answer was being relayed. | The client's connection is aborted. |
| SSH | The channel could not be opened, or closed before the peer sent anything. | Executed locally; nothing of the client's had been sent. |
| SSH | The link broke after the peer's first byte, before any client data was read. | The push fails: nothing was written, retry. |
| SSH | The link broke after client data was read. | The push fails: it may or may not have been applied, fetch before pushing again. |

A peer's own refusal, such as a missing permission, is relayed to the client as the peer gave it.

## Threads

A handed-over request holds a thread on the first node until the peer has answered, and needs a
thread on the peer. So that two nodes handing writes to each other always keep threads to execute
them, at most a third of `httpd.maxThreads` relay HTTP requests at a time, and at most half of an
SSH command pool, interactive or batch, relays pushes. Further writes are executed where they
arrive.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `walgerrit.forwardWrites` | `true` | Hand writes to the preferred peer once a peer source is configured. Off, the node executes every write it receives; it still executes what peers hand it. |

Forwarding uses the peer sources of [gossip](gossip.md) and needs no settings of its own. A node
without a peer source has no peers. `gossipEnabled` does not affect it.

## Deployment

- Nodes must reach each other's HTTP and SSH ports directly, by the addresses the peer source
  resolves to.
- The HTTP listener must be plain HTTP: `http`, `proxy-http` or `proxy-https` in
  `httpd.listenUrl`. Every node must use the same port and context path. With an `https` listener
  the nodes find no peers.
- For SSH pushes, every node must trust its peers' host keys. Either all nodes share one set of
  host keys, or each node's `etc/peer_keys` lists the public host keys of the others, one base64
  key per line as in the second field of a `.pub` file, for every key type the daemons hold.
  Without either, the SSH sessions fail to log in, SSH pushes are executed where they arrive, and
  HTTP forwarding is unaffected.
- `/peerz` is answered to anyone who can reach the HTTP listener. It discloses the node's name and
  SSH port.

## Observing

`write_forwarding/requests` counts writes by `transport`, `ssh` or `http`, and `outcome`:

| Outcome | Meaning |
| --- | --- |
| `forwarded` | The peer answered, whether it accepted or refused the write. |
| `fallback` | Executed locally because the peer could not be reached. |
| `busy` | Executed locally because the thread limit was reached. |
| `failed` | The link broke after the peer may have acted. |

Writes that the node ranks first for itself, or that name no project, are not counted. The log
reports each change of the peer set, each SSH link that opens or fails, and each fallback and
failure. On the first node, `httpd_log` records a relayed request without a user and `sshd_log`
records the push as usual; the peer logs the request with the user, and the SSH command as
`suexec`.

## Limits

- A stopping node is dropped by its peers when its HTTP listener closes. Writes it is executing for
  a peer at that moment fail on the peer's client.
- A node that returns takes its projects back as soon as it answers a hello, while its indexes may
  still trail the node that held them; the index barrier covers the gap.
- All pushes to one peer share one SSH session. A session whose peer stops answering is closed by
  its heartbeat after about half a minute; until then each push to that peer waits up to five
  seconds for a channel before it is executed locally.
- Hellos are sent one peer after another, each with a one-second timeout.
