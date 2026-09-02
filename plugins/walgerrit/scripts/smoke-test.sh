#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${GERRIT_WAR:-}" ]]; then
  echo "Set GERRIT_WAR to the WalGerrit fork's Gerrit 3.14.2 WAR." >&2
  exit 2
fi

if [[ ! -f "$GERRIT_WAR" ]]; then
  echo "GERRIT_WAR does not exist: $GERRIT_WAR" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
walgerrit_jar="${WALGERRIT_JAR:-$repo_root/target/walgerrit-0.1.0-SNAPSHOT.jar}"
if [[ ! -f "$walgerrit_jar" ]]; then
  echo "WALGERRIT_JAR does not exist: $walgerrit_jar" >&2
  exit 2
fi

site=$(mktemp -d "${TMPDIR:-/tmp}/walgerrit-smoke.XXXXXX")
mkdir -p "$site/etc" "$site/home" "$site/lib"
daemon_pid=""

cleanup() {
  if [[ -n "$daemon_pid" ]] && kill -0 "$daemon_pid" 2>/dev/null; then
    kill "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" 2>/dev/null || true
  fi
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

cp "$walgerrit_jar" "$site/lib/walgerrit.jar"

git config --file "$site/etc/gerrit.config" \
  --add gerrit.installDbModule dev.walgerrit.WalGitModule
git config --file "$site/etc/gerrit.config" \
  --add gerrit.installModule dev.walgerrit.WalGitIndexModule
git config --file "$site/etc/gerrit.config" walgerrit.backend local
git config --file "$site/etc/gerrit.config" walgerrit.storagePath data/walgerrit
git config --file "$site/etc/gerrit.config" walgerrit.indexCursorPath data/walgerrit-index-events
for index_name in accounts changes_open changes_closed groups projects; do
  git config --file "$site/etc/gerrit.config" "index.${index_name}.commitWithin" 0
done

run_gerrit init --batch --no-auto-start -d "$site"
test -f "$site/data/walgerrit/manifests/All-Projects.git/manifest.pb"
test -f "$site/data/walgerrit/manifests/All-Users.git/manifest.pb"
test -d "$site/data/walgerrit/repos/All-Projects.git/wal"
test -d "$site/data/walgerrit/repos/All-Users.git/wal"

cp "$site/data/walgerrit/manifests/All-Projects.git/manifest.pb" \
  "$site/home/All-Projects.manifest.before-reinit"
cp "$site/data/walgerrit/manifests/All-Users.git/manifest.pb" \
  "$site/home/All-Users.manifest.before-reinit"

run_gerrit init --batch --no-auto-start -d "$site"
cmp "$site/home/All-Projects.manifest.before-reinit" \
  "$site/data/walgerrit/manifests/All-Projects.git/manifest.pb"
cmp "$site/home/All-Users.manifest.before-reinit" \
  "$site/data/walgerrit/manifests/All-Users.git/manifest.pb"

run_gerrit reindex -d "$site"

git config --file "$site/etc/gerrit.config" sshd.listenAddress off
http_port="${GERRIT_HTTP_PORT:-$((20000 + RANDOM % 20000))}"
listen_url="http://127.0.0.1:${http_port}/"
git config --file "$site/etc/gerrit.config" httpd.listenUrl "$listen_url"
readiness_marker="$site/data/walgerrit-index-events/READY"
daemon_log="$site/logs/walgerrit-readiness-smoke.log"
java -Duser.home="$site/home" -jar "$GERRIT_WAR" daemon -d "$site" --console-log \
  >"$daemon_log" 2>&1 &
daemon_pid=$!

for _ in $(seq 1 120); do
  if ! kill -0 "$daemon_pid" 2>/dev/null; then
    wait "$daemon_pid" || true
    daemon_pid=""
    cat "$daemon_log" >&2
    echo "Gerrit exited before publishing WalGerrit readiness." >&2
    exit 1
  fi
  if [[ -f "$readiness_marker" ]] && curl -fsS "$listen_url" >/dev/null; then
    break
  fi
  sleep 0.25
done

test -f "$readiness_marker"
curl -fsS "$listen_url" >/dev/null
kill "$daemon_pid"
wait "$daemon_pid" || true
daemon_pid=""
test ! -e "$readiness_marker"

echo "WalGerrit fork initialized, reindexed, caught up, and published readiness successfully."
