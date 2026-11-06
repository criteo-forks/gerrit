Configuration
=============

The plugin is configured in `gerrit.config`; the client secret belongs in
`secure.config`.

```
[auth]
  type = OAUTH
  gitBasicAuthPolicy = OAUTH
  gitOAuthProvider = @PLUGIN@:oidc
  oauthAcceptsAuthTokens = true
  statelessSessions = true

[plugin "@PLUGIN@"]
  issuer = https://idp.example.com/realms/example
  clientId = gerrit
```

```
[plugin "@PLUGIN@"]
  clientSecret = ...
```

Register `<canonicalWebUrl>oauth` as the client's redirect URI.

With a session signing key, `auth.sessionSigningKey` as
`auth.statelessSessions` requires, a login started on one node can finish on
another: the state, PKCE verifier and return address travel in a signed cookie
instead of the servlet session.

plugin.@PLUGIN@.issuer
:	The issuer of the web login, exactly as tokens name it in `iss`. Its
	`/.well-known/openid-configuration` gives the endpoints and keys. Required.

plugin.@PLUGIN@.acceptedIssuer
:	Another issuer whose tokens Git and REST requests may carry, such as the
	same provider under a second host name. May be repeated. `issuer` is
	always accepted.

plugin.@PLUGIN@.clientId
:	The client the web login authenticates as. Required.

plugin.@PLUGIN@.clientSecret
:	The client's secret, sent with `client_secret_basic`. Required for the
	web login.

plugin.@PLUGIN@.audience
:	The `aud` a token must include to authenticate Git and REST requests.
	Defaults to `clientId`.

plugin.@PLUGIN@.scope
:	The scopes the web login asks for. Defaults to `openid profile email`.

plugin.@PLUGIN@.usernameClaim
:	The claim holding the Gerrit username. Defaults to `preferred_username`.

plugin.@PLUGIN@.clockSkew
:	How far `exp` and `nbf` may be off. Defaults to `60 s`.

plugin.@PLUGIN@.ldapPasswords
:	Whether Git and REST requests may authenticate with the user's LDAP
	password, checked against the `ldap` section. Only applies when
	`auth.type` is `OAUTH` and `ldap.server` is set. Defaults to `true`.

	Accepted passwords are cached for one minute per server, so a changed or
	disabled password may work for that long. Other passwords are checked
	against the directory. Only a keyed, salted bcrypt hash is kept in memory.
	Set `maxAge` in `[cache "oidc.ldap_password_checks"]` to change the
	lifetime, or `memoryLimit = 0` to disable the cache.
