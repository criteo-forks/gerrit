# Web sessions without a session store

Stock Gerrit keeps each login in the `web_sessions` cache, an H2 file under the node's `cache/`
directory. Behind a load balancer a second node has never heard of the session, so the user is
anonymous there; a restart wipes the file; the multi-site plugin syncs the cache over Kafka.

WalGerrit's fork of Gerrit adds `auth.statelessSessions`. The `GerritAccount` cookie then carries
the session record itself (account id, expiry, refresh time, remember-me flag, the external id used
to sign in, session id and XSRF token, about 250 bytes) signed with HMAC-SHA256. Any node holding
the key accepts the cookie without a lookup, nothing is stored, and a restarted or rescheduled node
keeps every user signed in. Every node sees every session, so the load balancer needs no
stickiness for the web UI.

## Configuration

```ini
[gerrit]
  installModule = dev.walgerrit.WalGitWebSessionModule
[auth]
  statelessSessions = true
```

`WalGitWebSessionModule` replaces Gerrit's `web-session-signing-key` module. The key comes from
`auth.sessionSigningKey` in `secure.config` when set (base64 of at least 16 bytes, identical on
every node). Otherwise the first node to need it generates 32 random bytes and publishes them with a
put-if-absent at `<s3Prefix>/cluster/web-session-signing-key`; a node that loses the race reads the
winner's key. The bucket already holds every repository, so this adds no trust it did not have.

To sign everyone out at once, delete that object (or change `auth.sessionSigningKey`) and restart
the nodes; each node caches the key for its lifetime.

## What changes for users

- Signing in on one node signs in everywhere; a node failure does not log anyone out.
- Signing out clears the cookie in that browser. The server cannot revoke a stateless session, so a
  copied cookie stays valid until the session expires (`cache.web_sessions.maxAge`, 12 hours by
  default). Remember-me cookies are refreshed the same way as before: when a session is more than
  half old, the response carries a new cookie with a new expiry.
- The `web_sessions` cache still exists but is never written.

Server-side revocation, if it is ever needed, is one object in the cluster prefix listing revoked
session ids, read by every node on the tailer's poll interval. It is not implemented.
