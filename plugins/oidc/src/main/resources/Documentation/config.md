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

	Each server remembers the directory's last answers, per user, in these
	caches:

	* `oidc.ldap_password_checks`: the accepted password, for one minute. A
	  changed or disabled password may work for that long.
	* `oidc.ldap_password_rejections`: up to four rejected passwords, for one
	  minute each. A password that becomes valid may fail for that long. Any
	  other password goes to the directory at once, and counts towards lockout.
	* `oidc.ldap_password_unavailable`: up to four passwords being checked when
	  the directory failed, for five seconds each.

	Only a keyed, salted bcrypt hash is kept in memory. Set `maxAge` in
	`[cache "<name>"]` to change a lifetime, or `memoryLimit = 0` to disable a
	cache.

	Only one check of a given username and password runs at a time. Other
	requests for that pair wait for its answer until two seconds after it began,
	then fail as if the directory were unavailable.

	These checks never use `ldap.useConnectionPooling`, because a pooled
	connection can skip the bind that proves a password. `ldap.readTimeout` and
	`ldap.connectTimeout` default to 10 seconds here.
