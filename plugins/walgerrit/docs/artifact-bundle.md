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

The `gerritpoc` image mounts the Gerrit site from a PVC, so a library copied there during the
image build is hidden at runtime. Put `gerrit.war` at `/home/gerrit/gerrit.war`, stage
`walgerrit.jar` at `/opt/gerrit/lib/walgerrit.jar`, and let the existing entrypoint copy staged
libraries into the mounted site. If `GERRIT_LIBS` is made explicit, it must include `walgerrit`.
The included `criteo-images.Dockerfile.fragment` shows the checksum-pinned internal Nexus form.

The site must configure both modules:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule
```

Configure the `[walgerrit]` S3 backend and zero `commitWithin` values documented in the main
WalGerrit README before initialization. Credentials come from the standard AWS SDK provider chain.

The current PoC healthcheck, metrics and download-command plugins were built for Gerrit 3.12.2.
Replace them with Gerrit 3.14-compatible builds, or omit them for the first image. The image build's
throwaway `init`/`reindex` smoke test must use the final WAR, library and selected plugins. Prefer
an exec readiness probe for
`/home/gerrit/gerrit_site/data/walgerrit-index-events/READY`; an HTTP version response only proves
that Gerrit's web server is running, not that the local Lucene indexes have caught up.

Use a new site volume, S3 prefix, and node-local index cursor for the first deployment. Gerrit
3.12 sites and plugins must be upgraded separately; do not place this bundle over an existing 3.12
site without a tested backup, migration, reindex, and rollback procedure.

GitHub Actions artifacts are a temporary handoff, not a durable `criteo-images` dependency. Publish
the verified payload to an approved internal Nexus Maven or Raw repository under an immutable
version/commit path, then pin its URLs and both checksums in `containers/criteo-images`.
