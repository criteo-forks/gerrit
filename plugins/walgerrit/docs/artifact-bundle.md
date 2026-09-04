# WalGerrit deployment bundle

This bundle targets Gerrit 3.14.2 and contains two inseparable runtime artifacts:

- `gerrit.war`: the WalGerrit Gerrit fork;
- `walgerrit.jar`: the shaded storage and index module library.

`gerrit.war` is the WalGerrit fork, not an upstream release WAR. CI runs that exact uploaded WAR
with the exact uploaded `walgerrit.jar`: it initializes a fresh site, reindexes it, starts the
daemon, waits for WalGerrit's catch-up readiness marker, and verifies shutdown removes readiness.
`SOURCE_COMMIT`, `GERRIT_VERSION`, and `SHA256SUMS` bind the payload to its source and contents.

For an image using `/home/gerrit/gerrit.war` and a Gerrit site at
`/home/gerrit/gerrit_site`, copy the files as follows:

```dockerfile
COPY gerrit.war /home/gerrit/gerrit.war
COPY walgerrit.jar /home/gerrit/gerrit_site/lib/walgerrit.jar
```

An image that mounts the Gerrit site from a volume hides a library copied into the site at build
time; stage `walgerrit.jar` outside the site and copy it into `lib/` at start-up instead. Pin the
URLs and SHA-256 sums of both files in the image build rather than downloading a CI artifact, which
is authenticated and expires.

The site must configure both modules:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule
```

Configure the `[walgerrit]` S3 backend and zero `commitWithin` values documented in the main
WalGerrit README before initialization. Credentials come from the standard AWS SDK provider chain.

Plugins built for an earlier Gerrit must be rebuilt for 3.14 or omitted. The image build's
throwaway `init`/`reindex` smoke test must use the final WAR, library and selected plugins. Prefer
an exec readiness probe for
`/home/gerrit/gerrit_site/data/walgerrit-index-events/READY`; an HTTP version response only proves
that Gerrit's web server is running, not that the local Lucene indexes have caught up.

Use a new site volume, S3 prefix, and node-local index cursor for the first deployment. Sites and
plugins from an earlier Gerrit must be upgraded separately; do not place this bundle over an existing
site without a tested backup, migration, reindex, and rollback procedure.

GitHub Actions artifacts are a temporary handoff. Publish the verified payload to an artifact
repository under an immutable version or commit path and pin its URLs and both checksums in the
image build. Site-specific packaging belongs with the operator, not in this repository.
