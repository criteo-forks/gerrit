Configuration
=============

```
[plugin "@PLUGIN@"]
  replicated = true
```

`replicated`
:	The renames this Gerrit receives follow renames a primary made. Name references (account
	watches, destinations, subscription permissions) arrive by replication, so none are rewritten
	here, and the primary's own checks on parents and submodules are not repeated. Default `false`.

On the primary, the Gerrit rename-project plugin replicates renames to this Gerrit with:

```
[plugin "rename-project"]
  url = ssh://<account>@<host>:29418
```

The account needs the `Rename Project` capability here, and the primary's Gerrit user must trust
this host's SSH key.
