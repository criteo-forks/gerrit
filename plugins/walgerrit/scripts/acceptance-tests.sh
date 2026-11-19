#!/usr/bin/env bash
# Runs Gerrit's acceptance suite with the in-memory test server bound to the WalGerrit repository
# backend instead of the in-memory repository manager (see WalGerritTestSupport).
#
# Usage: plugins/walgerrit/scripts/acceptance-tests.sh [bazel test targets...]
#   Defaults to every acceptance test. Bazel reports results for the current invocation.
# Environment:
#   BAZEL                    bazel or bazelisk launcher (default: bazelisk via npx)
#   WALGERRIT_ACCEPTANCE_TMP tmpfs-backed scratch directory for the jar and repositories
#                            (default: /dev/shm/walgerrit-acceptance when present, else $TMPDIR)
#   BAZEL_TEST_FLAGS         extra flags, e.g. "--local_test_jobs=4"
#
# The groups run with `--define=acceptance_heap=large` (512 MB instead of 256 MB): a test JVM keeps
# every server it started reachable, and WalGerrit's per-server footprint is larger than the
# in-memory manager's, so the biggest groups need the headroom.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
plugin_root="$repo_root/plugins/walgerrit"
bazel=${BAZEL:-"npx --yes @bazel/bazelisk"}
scratch=${WALGERRIT_ACCEPTANCE_TMP:-}
if [[ -z "$scratch" ]]; then
  if [[ -d /dev/shm && -w /dev/shm ]]; then
    scratch=/dev/shm/walgerrit-acceptance
  else
    scratch="${TMPDIR:-/tmp}/walgerrit-acceptance"
  fi
fi
mkdir -p "$scratch/store"

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then
  targets=(
    "//javatests/com/google/gerrit/acceptance/..."
    "//javatests/com/google/gerrit/acceptance:WalGerritChangeKeyRaceIT"
  )
fi

echo "== building the WalGerrit library"
(cd "$plugin_root" && ./mvnw --batch-mode --no-transfer-progress -DskipTests package)
cp "$plugin_root"/target/walgerrit-*-SNAPSHOT.jar "$scratch/walgerrit.jar"

echo "== running ${targets[*]} on the WalGerrit backend (store: $scratch/store)"
# The runtime jar is supplied outside the Bazel dependency graph.
# shellcheck disable=SC2086
exec $bazel test --config=java21 --keep_going --nocache_test_results --test_output=errors \
  --test_summary=detailed \
  --define=acceptance_heap=large \
  --test_env=GERRIT_WALGERRIT_JAR="$scratch/walgerrit.jar" \
  --test_env=GERRIT_WALGERRIT_STORAGE="$scratch/store" \
  ${BAZEL_TEST_FLAGS:-} "${targets[@]}"
