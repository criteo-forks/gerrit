Renames a WalGerrit project. The plugin carries the name and the SSH command of the Gerrit
rename-project plugin so that a Gerrit running that plugin replicates its renames here through its
`url` setting, and so that access rules granting its `Rename Project` capability apply here too.

The rename itself is WalGerrit's: the store binds the name to the repository's id in its catalog,
fences the writers admitted under the old name, and activates the new name on every node. No files
move, and a name is never reused. See WalGerrit's `docs/namespace.md`.

A replica sets `plugin.rename-project.replicated` (see [config](config.md)): the renames it
receives follow renames the primary made, whose account watches and subscription permissions arrive
by replication, so WalGerrit rewrites none here. Without it, a rename rewrites those references and
refuses a parent or a project that superprojects may subscribe to, as the rename-project plugin
does.

A rename received twice, as after a lost reply, finishes the rename in flight or does nothing when it
already completed. There is no REST endpoint and no revert: renaming back is refused because names
are never reused.
