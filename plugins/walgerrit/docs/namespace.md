# Project names

A project name is a binding, not a storage path. The store keys every repository by an opaque
id, and a private catalog maps names to ids. Creating and deleting a project change the catalog;
the repository's files, log and manifest stay where they are.

## The catalog

The catalog is a WalGerrit repository with the fixed id `catalog`. It is not a Gerrit project and
is not listed. Its `refs/heads/main` tree holds one blob per name ever bound, at
`bindings/<sha1 of the name>`, in Git config syntax:

```ini
[binding]
  name = platform/service
  id = 3f9a...
  state = pending         # active, pending or retired
  epoch = 0               # the write epoch writers admitted under this name carry
  since = 1789000000000
[operation]                # the operation that is pending, or the last one that finished
  id = 3d2c...
  kind = delete            # create, import or delete
  expectedEpoch = 0
  targetEpoch = 1
  replicated = true        # follows a primary's deletion; references arrive by replication
```

Every commit on the catalog is one namespace transition, made with a compare-and-swap on the
branch tip and retried against the newer catalog when another node commits first. One node's
commits take turns, so only another node's commit can make one retry. A commit's
change function re-checks its preconditions against what it reads, so two nodes cannot bind one
name to two repositories or delete one name twice.

Opening a repository resolves its name through the catalog. With `manifestRevalidateOnOpen` on,
every open reads the catalog's manifest conditionally, the same one read that keeps the
repository fresh. Off, opens use the node's observed catalog, which the index tailer's sweeps and
peers' gossip hints keep current, as for any repository.

## Write epochs fence writers

Every binding carries a write epoch; the repository's manifest carries the epoch it currently
accepts. A handle opened under a name carries the binding's epoch into each publication, and the
manifest CAS refuses a publication whose epoch differs from the manifest's. To JGit the refusal
is a `LOCK_FAILURE`; the reason names the operation that fenced the repository.

A deletion advances the manifest's epoch once, with a `FENCE` log entry. From that commit point
on, every handle admitted under the name is refused on every node. Whatever committed before the
fence stays committed. The fence protects refs: object packs a fenced handle had not published
yet carry no refs and may still land, as unreferenced objects.

## Deleting a project

```text
 prepare (catalog)      fence (manifest)        references (All-Users, projects)   finalize (catalog)
 name -> pending        write_epoch: n -> n+1   watches, destinations and          name -> retired
                        deleted                 subscription permissions
```

1. **Prepare** records the operation on the binding: the name becomes pending with the expected
   and target epochs. A pending name is not served: opening it fails with what is happening to
   it. Handles opened before the prepare keep working until the fence. One deletion is in flight
   at a time; the catalog's CAS refuses a second until the first is finalized or resumed.
2. **Fence** advances the write epoch in the repository's manifest, from the expected to the
   target epoch, and marks the manifest deleted. A fence that finds its own operation recorded
   already does nothing.
3. **References**: the typed references to the name outside its repository are removed, see
   below, unless the deletion is replicated.
4. **Finalize** retires the name.

Each transition is idempotent, so an operation whose node died is finished from any node:

```bash
java -jar gerrit.war walgerrit-namespace -d "$site" delete NAME [--replicated]
java -jar gerrit.war walgerrit-namespace -d "$site" references NAME
java -jar gerrit.war walgerrit-namespace -d "$site" pending
java -jar gerrit.war walgerrit-namespace -d "$site" resume OPERATION
```

`pending` lists every operation no node finalized, with the names it touches. `resume` runs the
transitions the operation has not made. Resuming a finalized operation does nothing; an unknown
operation is refused. Repeating a deletion that is in flight finishes it, and repeating one that
completed does nothing, so a caller whose reply was lost can call again.

Creation follows the same shape without a fence: the name is reserved pending with a fresh id,
the repository is initialized, and the name is activated. A creation an earlier attempt left
pending is finished by the next creation of that name, under the reserved id. Of several nodes
creating one name at once, the catalog admits one reservation; the others either join it or
receive `RepositoryExistsException`. An import reserves its name under its own kind, so an
unfinished import is finished only by rerunning the import, never by `resume` or a creation,
which would activate the name over an empty repository.

The configured `All-Projects` and `All-Users` names cannot be deleted.

## References outside the repository

Gerrit keeps a project's name in places a deletion must clear. The typed ones are removed, between
the fence and the finalize:

- every account's `watch.config` in All-Users: the `[project "name"]` section;
- every account's `destinations/*` files in All-Users: rows whose project column is the name;
- every project's `[allowSuperproject "name"]` sections: the permission for the name to subscribe
  to the project as a superproject.

Account refs change a few hundred per ref transaction, each project in one, all with expected old
tips, so every node's tailer evicts and reindexes the accounts and projects as for any write. Watch
and project configurations are parsed as Git configuration before the prepare, so a malformed one
fails the operation before anything changes; a destination row is a ref, a tab and a project, and a
line without a tab is left as it is. The removal is a scan, not a lock: a watch or a subscription
permission added while the deletion runs stays, and one changed under a transaction makes the
transaction retry, eight times before the deletion fails after the fence and waits for `resume`.
Run deletions while such writes are quiet, and check with `references` afterwards. Two projects
are refused rather than deleted:

- a parent: its children would inherit access rules from All-Projects instead, since Gerrit
  falls back to All-Projects for a parent it cannot resolve; reparent them first;
- a project that superprojects may subscribe to, because it or an ancestor allows superprojects:
  their `.gitmodules` would keep naming it, and user content is never rewritten; withdraw the
  permission first.

Text that may mention a name is never changed: watch filters, named queries, dashboards, submit
requirements, contributor agreement patterns, plugin sections such as `[lfs "name"]` in
All-Projects, `replication.config`. `walgerrit-namespace references NAME` reports what a deletion
would remove or refuse, and the literal occurrences it found in All-Projects' `project.config`
and in accounts' watch filters and named queries. A pattern can match a name without containing
it, so that list is partial.

With `--replicated` nothing is removed and the parent and subscription checks are skipped: the
deletion follows one a primary made, and the primary's All-Users and project configurations
arrive by replication.

## Names are never reused

A retired binding stays in the catalog. Opening a retired name fails with what happened to it:

```text
platform/gone was deleted; a name is never reused
```

Creating or importing onto a retired name is refused the same way, so a push to a stale remote
fails rather than creating an empty repository under the deleted name. The retired binding is the
record of which repository the name had, and every node reconciles its indexes with what the
catalog says about a name now, which is only right while a name has had one repository.

A deleted repository keeps its id, files, log and manifest. The manifest is marked deleted, which
refuses every further publication and makes compaction skip it. Nothing reclaims its files, and
nothing lists it: the data is reachable only by id, for audits or restoration by hand.

## Operations of another version

A binding stores its operation's kind as text, and a store can hold kinds this version does not
run: a version of WalGerrit that renames projects records `rename`. Such a binding is read as its
state says, and is never changed or dropped because of its kind:

- an active binding is served, at the epoch it records;
- a retired binding is a retired name: opening it fails with `is retired; a name is never
  reused`, and nothing is created over it;
- a pending binding is an operation in flight that this version cannot finish. Its names are not
  served, `pending` lists them, `resume` refuses the operation, and no deletion starts while it
  is in flight. Finish it with `walgerrit-namespace resume` of a version that runs that kind; the
  names are then served as it left them.

## Indexes follow the catalog

The index tailer follows the catalog like any repository. Each catalog commit names the bindings
it changed; on every node the tailer evicts those projects' caches and the change-number-to-project
cache, refreshes the project list, and reindexes an activated name's project and change documents
under that name. A retired name's change documents are deleted by project and its project
document dropped. A repository whose name is pending waits: its log is replayed under the name
once it is active, and passed over once it is retired.

Gerrit's delete-project plugin is not used on the storage side; its filesystem operations do not
apply to this backend, and `repositoryDeleted` is a no-op.
