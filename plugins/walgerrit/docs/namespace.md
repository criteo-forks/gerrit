# Project names

A project name is a binding, not a storage path. The store keys every repository by an opaque
id, and a private catalog maps names to ids. Renaming and deleting a project change the catalog;
the repository's files, log and manifest stay where they are.

## The catalog

The catalog is a WalGerrit repository with the fixed id `catalog`. It is not a Gerrit project and
is not listed. Its `refs/heads/main` tree holds one blob per name ever bound, at
`bindings/<sha1 of the name>`, in Git config syntax:

```ini
[binding]
  name = platform/service
  id = 3f9a...
  state = active          # active, pending or retired
  epoch = 1               # the write epoch writers admitted under this name carry
  since = 1789000000000
[operation]                # present while an operation is pending, and on retired bindings
  id = 3d2c...
  kind = rename            # create, import, rename or delete
  expectedEpoch = 0
  targetEpoch = 1
  other = platform/old-name
  replicated = true        # follows a primary's operation; references arrive by replication
```

Every commit on the catalog is one namespace transition, made with a compare-and-swap on the
branch tip and retried against the newer catalog when another node commits first. A commit's
change function re-checks its preconditions against what it reads, so two nodes cannot bind one
name to two repositories or rename one name twice.

Opening a repository resolves its name through the catalog. With `manifestRevalidateOnOpen` on,
every open reads the catalog's manifest conditionally, the same one read that keeps the
repository fresh. Off, opens use the node's observed catalog, which the index tailer's sweeps and
peers' gossip hints keep current, as for any repository.

## Write epochs fence writers

Every binding carries a write epoch; the repository's manifest carries the epoch it currently
accepts. A handle opened under a name carries the binding's epoch into each publication, and the
manifest CAS refuses a publication whose epoch differs from the manifest's. To JGit the refusal
is a `LOCK_FAILURE`; the reason names the operation that fenced the repository.

A rename or deletion advances the manifest's epoch once, with a `FENCE` log entry. From that
commit point on, every handle admitted under the old name is refused on every node. Whatever
committed before the fence stays committed. The fence protects refs: object packs a fenced handle
had not published yet carry no refs and may still land, as unreferenced objects.

## One protocol for rename and delete

```text
 prepare (catalog)      fence (manifest)        references (All-Users, projects)   finalize (catalog)
 source -> pending      write_epoch: n -> n+1   watches, destinations and          source -> retired
 destination -> pending deleted, for a delete   subscription permissions           destination -> active at n+1
```

1. **Prepare** records the operation on the bindings it touches: the source becomes pending with
   the expected and target epochs, and a rename's destination becomes pending at the target epoch.
   A pending name is not served: opening it fails with what is happening to it. Handles opened
   before the prepare keep working until the fence. One rename or deletion is in flight at a time;
   the catalog's CAS refuses a second until the first is finalized or resumed.
2. **Fence** advances the write epoch in the repository's manifest, from the expected to the
   target epoch, and for a deletion marks the manifest deleted. A fence that finds its own
   operation recorded already does nothing.
3. **References**: the typed references to the name outside its repository are rewritten, see
   below, unless the operation is replicated.
4. **Finalize** retires the source, with the name it moved to when it was renamed, and activates
   the destination at the target epoch, in one commit. Writers admitted under the new name carry
   the epoch the manifest now has.

Each transition is idempotent, so an operation whose node died is finished from any node:

```bash
java -jar gerrit.war walgerrit-namespace -d "$site" rename OLD NEW [--replicated]
java -jar gerrit.war walgerrit-namespace -d "$site" delete NAME [--replicated]
java -jar gerrit.war walgerrit-namespace -d "$site" references NAME
java -jar gerrit.war walgerrit-namespace -d "$site" pending
java -jar gerrit.war walgerrit-namespace -d "$site" resume OPERATION
```

`pending` lists every operation no node finalized, with the names it touches. `resume` runs the
transitions the operation has not made. Resuming a finalized operation does nothing; an unknown
operation is refused. Repeating a rename that is in flight finishes it, and repeating one that
completed does nothing, so a caller whose reply was lost can call again.

Creation follows the same shape without a fence: the name is reserved pending with a fresh id,
the repository is initialized, and the name is activated. A creation an earlier attempt left
pending is finished by the next creation of that name, under the reserved id. Of several nodes
creating one name at once, the catalog admits one reservation; the others either join it or
receive `RepositoryExistsException`. An import reserves its name under its own kind, so an
unfinished import is finished only by rerunning the import, never by `resume` or a creation,
which would activate the name over an empty repository.

The configured `All-Projects` and `All-Users` names cannot be renamed or deleted.

## References outside the repository

Gerrit keeps a project's name in places a rename must follow. The typed ones are rewritten, between
the fence and the finalize, so they name the new project the moment it is served:

- every account's `watch.config` in All-Users: the `[project "name"]` section moves to the new
  name, its notify values joined with any the new name already had; a deletion removes it;
- every account's `destinations/*` files in All-Users: rows whose project column is the name;
- every project's `[allowSuperproject "name"]` sections: the permission for the name to subscribe
  to the project as a superproject.

Account refs change a few hundred per ref transaction, each project in one, all with expected old
tips, so every node's tailer evicts and reindexes the accounts and projects as for any write. Watch
and project configurations are parsed as Git configuration before the prepare, so a malformed one
fails the operation before anything changes; a destination row is a ref, a tab and a project, and a
line without a tab is left as it is. The rewrite is a scan, not a lock: a watch or a subscription
permission added while the operation runs keeps the old name, and one changed under a transaction
makes the transaction retry, eight times before the operation fails after the fence and waits for
`resume`. Run renames and deletions while such writes are quiet, and check with `references`
afterwards. Two projects are refused rather than rewritten:

- a parent: its children would inherit access rules from All-Projects while the name is pending,
  since Gerrit falls back to All-Projects for a parent it cannot resolve; reparent them first;
- a project that superprojects may subscribe to, because it or an ancestor allows superprojects:
  their `.gitmodules` would keep the old name, and user content is never rewritten; withdraw the
  permission first.

Text that may mention a name is never rewritten: watch filters, named queries, dashboards, submit
requirements, contributor agreement patterns, plugin sections such as `[lfs "name"]` in
All-Projects, `replication.config`. `walgerrit-namespace references NAME` reports what a rename
would rewrite or refuse, and the literal occurrences it found in All-Projects' `project.config`
and in accounts' watch filters and named queries. A pattern can match a name without containing
it, so that list is partial.

With `--replicated` nothing is rewritten and the parent and subscription checks are skipped: the
operation follows one a primary made, and the primary's All-Users and project configurations
arrive by replication. The `rename-project` plugin in this fork accepts the Gerrit rename-project
plugin's replication and runs the rename this way when `plugin.rename-project.replicated` is
`true`; without it the plugin renames as on a primary, references rewritten and checks made.

## Names are never reused

A retired binding stays in the catalog. Opening a retired name fails with what happened to it:

```text
platform/old was renamed to platform/new; a name is never reused
platform/gone was deleted; a name is never reused
```

Creating, importing or renaming onto a retired name is refused the same way. A push to a stale
remote therefore fails with the new name in the message rather than creating an empty
repository under the old one.

A deleted repository keeps its id, files, log and manifest. The manifest is marked deleted, which
refuses every further publication and makes compaction skip it. Nothing reclaims its files, and
nothing lists it: the data is reachable only by id, for audits or restoration by hand.

## Indexes follow the catalog

The index tailer follows the catalog like any repository. Each catalog commit names the bindings
it changed; on every node the tailer evicts those projects' caches and the change-number-to-project
cache, refreshes the project list, and reindexes an activated name's project and change documents
under that name. A retired name's change documents are deleted by project and its project
document dropped. A repository whose only names are pending waits: its log is replayed once a
name is active, under that name.

Gerrit's rename-project and delete-project plugins are not used on the storage side; their
filesystem operations do not apply to this backend, and `repositoryDeleted` is a no-op. The
`rename-project` plugin in this fork carries the former's name and SSH command so that a primary
running it replicates its renames here.
