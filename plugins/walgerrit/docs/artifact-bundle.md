# WalGerrit deployment bundle

Deploy `gerrit.war` and `walgerrit.jar` from the same bundle. The WAR is the Gerrit 3.14.2 fork;
the JAR supplies its WalGerrit storage and index modules.

The build workflow tests the exact pair before uploading it. Its smoke test covers fresh init,
reindex, daemon readiness, shutdown, compaction, restart, index rebuilding and import.
`SOURCE_COMMIT` identifies the source revision, `GERRIT_VERSION` records `3.14.2`, and
`SHA256SUMS` covers the WAR and JAR.

## Verify and install

From the extracted bundle directory:

```sh
sha256sum --check SHA256SUMS
```

For an image with the WAR at `/home/gerrit/gerrit.war` and the site at
`/home/gerrit/gerrit_site`:

```dockerfile
COPY gerrit.war /home/gerrit/gerrit.war
COPY walgerrit.jar /home/gerrit/gerrit_site/lib/walgerrit.jar
```

If a volume mounts over the site, it hides files copied there during the image build. Stage the
JAR outside the site and copy it into the mounted site's `lib/` directory at startup.

## Configure before initialization

The site needs both modules and synchronous Lucene commits:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule

[index "accounts"]
  commitWithin = 0
[index "changes_open"]
  commitWithin = 0
[index "changes_closed"]
  commitWithin = 0
[index "groups"]
  commitWithin = 0
[index "projects"]
  commitWithin = 0
```

For S3, configure the shared store and node-local paths:

```ini
[walgerrit]
  backend = s3
  s3Bucket = gerrit-git
  s3Region = eu-west-3
  s3Prefix = production
  storagePath = data/walgerrit-cache
  indexCursorPath = data/walgerrit-index-events
```

Use the deployment's bucket, region and prefix. Credentials come from the AWS SDK's default
provider chain. A custom endpoint may also need `s3Endpoint` and `s3PathStyle = true`.

Rebuild older plugins against the target Gerrit version or omit them. Test the final image's
WAR, library and selected plugins together with `init`, `reindex` and daemon startup.

## Readiness requires the index marker and a listener

With the paths above and HTTP listening on port 8080, an exec probe can use:

```sh
test -f /home/gerrit/gerrit_site/data/walgerrit-index-events/READY &&
  curl -fsS http://127.0.0.1:8080/ >/dev/null
```

The marker means the last full index sweep succeeded. HTTP alone does not prove index health;
the marker alone can survive a hard kill. Adjust the URL for the actual listener.

## Pin the tested payload

Branch builds upload temporary GitHub Actions artifacts with 30-day retention. Tags matching
`walgerrit-3.14.2-*` also publish the tested payload as a GitHub prerelease. Pin the selected WAR
and JAR URLs and verify both checksums in the image build. A release label alone is not a
content-integrity check.

Use a fresh destination store or prefix for migration, and a separate local cursor directory on
each node. Follow the source revision's WalGerrit import guide before serving existing data;
installing the bundle does not migrate a previous site's repositories, indexes or plugins.
