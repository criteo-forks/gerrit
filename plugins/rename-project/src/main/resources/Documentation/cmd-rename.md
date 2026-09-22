@PLUGIN@
========

NAME
----
@PLUGIN@ - Rename a WalGerrit project

SYNOPSIS
--------
```
ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ <OLD> <NEW>
```

DESCRIPTION
-----------
Renames the project in WalGerrit's catalog: the new name serves the same repository, the old name
refuses every writer from the commit point on and is never reused. Every node follows the catalog.

ACCESS
------
Caller must be a member of a group that is granted the 'Rename Project' capability (provided by
this plugin) or the 'Administrate Server' capability.

EXAMPLES
--------
```
  $ ssh -p @SSH_PORT@ @SSH_HOST@ @PLUGIN@ platform/old platform/new
```
