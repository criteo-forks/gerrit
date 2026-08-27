#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${GERRIT_WAR:-}" ]]; then
  echo "Set GERRIT_WAR to a stock Gerrit 3.12.2 WAR." >&2
  exit 2
fi

if [[ ! -f "$GERRIT_WAR" ]]; then
  echo "GERRIT_WAR does not exist: $GERRIT_WAR" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
site=$(mktemp -d "${TMPDIR:-/tmp}/walgerrit-smoke.XXXXXX")
mkdir -p "$site/etc" "$site/home" "$site/lib"

cleanup() {
  if [[ "$site" == "${TMPDIR:-/tmp}/walgerrit-smoke."* ]]; then
    rm -rf -- "$site"
  fi
}
trap cleanup EXIT

cd "$repo_root"
./mvnw --batch-mode --no-transfer-progress verify

run_gerrit() {
  java -Duser.home="$site/home" -jar "$GERRIT_WAR" "$@"
}

cp target/walgerrit-0.1.0-SNAPSHOT.jar "$site/lib/walgerrit.jar"

git config --file "$site/etc/gerrit.config" \
  --add gerrit.installDbModule dev.walgerrit.WalGitModule
git config --file "$site/etc/gerrit.config" walgerrit.backend local
git config --file "$site/etc/gerrit.config" walgerrit.storagePath data/walgerrit

run_gerrit init --batch --no-auto-start -d "$site"
run_gerrit reindex -d "$site"

test -f "$site/data/walgerrit/repos/All-Projects.git/manifest.pb"
test -f "$site/data/walgerrit/repos/All-Users.git/manifest.pb"

echo "WalGerrit loaded successfully in stock Gerrit 3.12.2."
