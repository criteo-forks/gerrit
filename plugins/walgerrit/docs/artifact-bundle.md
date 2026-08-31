# WalGerrit deployment bundle

This bundle targets Gerrit 3.14.2 and contains two runtime artifacts:

- `gerrit.war`: the WalGerrit Gerrit fork;
- `walgerrit.jar`: the shaded storage and index module library.

For an image using `/home/gerrit/gerrit.war` and a Gerrit site at
`/home/gerrit/gerrit_site`, copy the files as follows:

```dockerfile
COPY gerrit.war /home/gerrit/gerrit.war
COPY walgerrit.jar /home/gerrit/gerrit_site/lib/walgerrit.jar
```

The site must configure both modules:

```ini
[gerrit]
  installDbModule = dev.walgerrit.WalGitModule
  installModule = dev.walgerrit.WalGitIndexModule
```

Configure the `[walgerrit]` S3 backend and zero `commitWithin` values documented in the main
WalGerrit README before initialization. Credentials come from the standard AWS SDK provider chain.

Use a new site volume, S3 prefix, and node-local index cursor for the first deployment. Gerrit
3.12 sites and plugins must be upgraded separately; do not place this bundle over an existing 3.12
site without a tested backup, migration, reindex, and rollback procedure.
