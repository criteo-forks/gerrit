#!/usr/bin/env bash
# Surveys a tree of bare repositories laid out like gerrit.basePath, read-only, and prints what an
# import needs to know: how many repositories and how large, the largest repository and the largest
# single pack, which repositories still hold loose objects, ref counts, and the NoteDb schema version.
#
# Usage: survey-repositories.sh BASE_PATH [REPORT_DIR]
#   Writes REPORT_DIR/repositories.tsv (name, bytes, packs, loose objects, refs) and prints a summary.
#   Runs under nice; touches nothing under BASE_PATH.

set -euo pipefail

base=${1:?BASE_PATH is required}
report_dir=${2:-.}
mkdir -p "$report_dir"
tsv="$report_dir/repositories.tsv"
packs="$report_dir/packs.tsv"

echo "== enumerating repositories under $base"
mapfile -t repos < <(find "$base" -type d -name '*.git' -prune | sort)
echo "${#repos[@]} repositories"

printf 'name\tbytes\tpacks\tloose_objects\trefs\n' >"$tsv"
: >"$packs"
i=0
for repo in "${repos[@]}"; do
  i=$((i + 1))
  name=${repo#"$base"/}; name=${name%.git}
  bytes=$(nice du -sb "$repo" | cut -f1)
  pack_count=$(find "$repo/objects/pack" -maxdepth 1 -name '*.pack' 2>/dev/null | wc -l)
  loose=$(find "$repo/objects" -mindepth 2 -maxdepth 2 -type f -path '*/objects/??/*' 2>/dev/null | wc -l)
  refs=$(nice git -C "$repo" for-each-ref --format='%(refname)' 2>/dev/null | wc -l)
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$bytes" "$pack_count" "$loose" "$refs" >>"$tsv"
  find "$repo/objects/pack" -maxdepth 1 -name '*.pack' -printf '%s\t%p\n' 2>/dev/null >>"$packs" || true
  if (( i % 200 == 0 )); then echo "  $i / ${#repos[@]}"; fi
done

echo
echo "== totals"
awk -F'\t' 'NR>1 {b+=$2; p+=$3; l+=$4; r+=$5; if ($4>0) with_loose++} END {
  printf "bytes: %d (%.1f GiB)\npacks: %d\nloose objects: %d in %d repositories\nrefs: %d\n", b, b/1073741824, p, l, with_loose, r}' "$tsv"
echo
echo "== 20 largest repositories (GiB)"
awk -F'\t' 'NR>1 {printf "%8.2f  %s\n", $2/1073741824, $1}' "$tsv" | sort -rn | head -20
echo
echo "== 10 largest packs (GiB); a single S3 PUT stops at 5 GiB, above that multipart is used"
sort -rn "$packs" | head -10 | awk -F'\t' '{printf "%8.2f  %s\n", $1/1073741824, $2}'
echo
echo "== 10 repositories with the most refs"
awk -F'\t' 'NR>1 {printf "%9d  %s\n", $5, $1}' "$tsv" | sort -rn | head -10
echo
echo "== repositories with loose objects (need repack or --stage): $(awk -F'\t' 'NR>1 && $4>0' "$tsv" | wc -l)"
echo
if [[ -d "$base/All-Projects.git" ]]; then
  echo "== All-Projects"
  echo "NoteDb schema version: $(git -C "$base/All-Projects.git" show refs/meta/version:version 2>/dev/null || echo '(no refs/meta/version)')"
  echo "refs: $(git -C "$base/All-Projects.git" for-each-ref | wc -l)"
fi
if [[ -d "$base/All-Users.git" ]]; then
  echo "== All-Users"
  echo "accounts (refs/users/*): $(git -C "$base/All-Users.git" for-each-ref 'refs/users/' | wc -l)"
  echo "groups (refs/groups/*): $(git -C "$base/All-Users.git" for-each-ref 'refs/groups/' | wc -l)"
  echo "size: $(du -sh "$base/All-Users.git" | cut -f1)"
fi
echo
echo "Report: $tsv and $packs"
