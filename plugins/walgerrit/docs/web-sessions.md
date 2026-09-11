# Web sessions without a session store

Gerrit's usual web sessions live in a node-local cache. A second node cannot resolve a session
unless the deployment shares that state or routes the browser back to the original node.
Persistent cache storage can survive a restart; losing the node's cache loses its sessions.

The fork's optional `auth.statelessSessions` stores the session in the `GerritAccount` cookie,
signed with HMAC-SHA256. The cookie carries the account ID, expiry, refresh time, remember-me
flag, external ID, session ID and XSRF token. Any node with the same key can validate it without a
session-cache lookup. Web UI sessions then need no load-balancer stickiness.

## Configuration

Add this module alongside the storage and index modules:

```ini
[gerrit]
  installModule = dev.walgerrit.WalGitWebSessionModule
[auth]
  statelessSessions = true
```

`WalGitWebSessionModule` replaces Gerrit's signing-key provider. It uses
`auth.sessionSigningKey` from `secure.config` when configured: base64 encoding of at least 16
bytes, identical on every node. Otherwise, the first node generates 32 random bytes and creates
`cluster/web-session-signing-key` in the configured store. A node that loses the create race
reads the winner's key. On S3, the object is beneath `s3Prefix`.

Anyone who can read this key can mint session cookies. Protect it as an authentication secret;
repository read access alone must not be treated as equivalent authority. The signature prevents
modification but does not encrypt the cookie's contents.

## Refresh extends the session

`cache.web_sessions.maxAge` sets the lifetime of each issued token, 12 hours by default. On an
eligible request, Gerrit refreshes the token after one hour or half its lifetime, whichever is
sooner, and issues a new expiry. This applies to both browser-session and remember-me cookies;
remember-me controls browser persistence. The session path does not write the `web_sessions`
cache in stateless mode.

## Signing out does not revoke a copied token

Logout clears the browser's cookie. It cannot invalidate another copy on the server. An
unexpired copy remains usable and can itself be refreshed, so `maxAge` is not an absolute limit
on a continuously used copied session. Per-session server-side revocation is not implemented.

To invalidate all existing tokens, rotate the configured key and restart every node. If using
the generated store key, stop all nodes, delete that object, then restart them so they load one
new shared key. Nodes cache their key for their lifetime; leaving an old node serving leaves old
tokens valid there.
