#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${GERRIT_WAR:-}" ]]; then
  echo "Set GERRIT_WAR to the WalGerrit fork's Gerrit 3.12.2 WAR." >&2
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
  if [[ "${KEEP_SITE:-0}" != "1" && "$site" == "${TMPDIR:-/tmp}/walgerrit-smoke."* ]]; then
    rm -rf -- "$site"
  elif [[ "${KEEP_SITE:-0}" == "1" ]]; then
    echo "Preserved smoke-test site: $site" >&2
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
test -f "$site/data/walgerrit/repos/All-Projects.git/manifest.pb"
test -f "$site/data/walgerrit/repos/All-Users.git/manifest.pb"

cp "$site/data/walgerrit/repos/All-Projects.git/manifest.pb" \
  "$site/home/All-Projects.manifest.before-reinit"
cp "$site/data/walgerrit/repos/All-Users.git/manifest.pb" \
  "$site/home/All-Users.manifest.before-reinit"

run_gerrit init --batch --no-auto-start -d "$site"
cmp "$site/home/All-Projects.manifest.before-reinit" \
  "$site/data/walgerrit/repos/All-Projects.git/manifest.pb"
cmp "$site/home/All-Users.manifest.before-reinit" \
  "$site/data/walgerrit/repos/All-Users.git/manifest.pb"

run_gerrit reindex -d "$site"

echo "WalGerrit fork initialized and reindexed successfully."
