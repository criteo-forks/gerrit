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
  kind = rename            # create, rename or delete
  expectedEpoch = 0
  targetEpoch = 1
  other = platform/old-name
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
 prepare (catalog)      fence (manifest)        finalize (catalog)
 source -> pending      write_epoch: n -> n+1   source -> retired
 destination -> pending deleted, for a delete   destination -> active at n+1
```

1. **Prepare** records the operation on the bindings it touches: the source becomes pending with
   the expected and target epochs, and a rename's destination becomes pending at the target epoch.
   A pending name is not served: opening it fails with what is happening to it. Handles opened
   before the prepare keep working until the fence.
2. **Fence** advances the write epoch in the repository's manifest, from the expected to the
   target epoch, and for a deletion marks the manifest deleted. A fence that finds its own
   operation recorded already does nothing.
3. **Finalize** retires the source, with the name it moved to when it was renamed, and activates
   the destination at the target epoch, in one commit. Writers admitted under the new name carry
   the epoch the manifest now has.

Each transition is idempotent, so an operation whose node died is finished from any node:

```bash
java -jar gerrit.war walgerrit-namespace -d "$site" rename OLD NEW
java -jar gerrit.war walgerrit-namespace -d "$site" delete NAME
java -jar gerrit.war walgerrit-namespace -d "$site" pending
java -jar gerrit.war walgerrit-namespace -d "$site" resume OPERATION
```

`pending` lists every operation no node finalized, with the names it touches. `resume` runs the
transitions the operation has not made. Resuming a finalized operation does nothing; an unknown
operation is refused.

Creation follows the same shape without a fence: the name is reserved pending with a fresh id,
the repository is initialized, and the name is activated. A creation an earlier attempt left
pending is finished by the next creation of that name, under the reserved id. Of several nodes
creating one name at once, the catalog admits one reservation; the others either join it or
receive `RepositoryExistsException`. An import reserves its name under its own kind, so an
unfinished import is finished only by rerunning the import, never by `resume` or a creation,
which would activate the name over an empty repository.

The configured `All-Projects` and `All-Users` names cannot be renamed or deleted.

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

Project-name references outside the repository are not moved: All-Users watch entries, plugin
configuration and access rules that name the project keep the old name. Gerrit's rename-project
and delete-project plugins are not used; their filesystem operations do not apply to this
backend, and `repositoryDeleted` is a no-op.
