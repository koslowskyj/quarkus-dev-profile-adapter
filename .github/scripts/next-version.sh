#!/usr/bin/env bash
# Works out the next release from the Quarkus line and the conventional commits since the last tag on it.
# major.minor always equal quarkus.version's, so a commit can only raise the fix number:
#   - no tag on this Quarkus line yet           -> <line>.0
#   - feat, fix, perf, revert or a breaking one -> last fix + 1
#   - anything else (docs, ci, chore, test, …)  -> no release: a Central version can never be withdrawn
# Writes skip=true, or next=<version> and snapshot=<next development version>, to $GITHUB_OUTPUT.
set -euo pipefail

out=${GITHUB_OUTPUT:-/dev/stdout}
skip() { echo "$1"; echo "skip=true" >> "$out"; exit 0; }
# Maven's error goes to the log, not into the variable the caller assigns.
evaluate() {
  local value
  value=$(mvn -B -q help:evaluate -Dexpression="$1" -DforceStdout) || { echo "$value" >&2; exit 1; }
  echo "$value"
}

line=$(evaluate quarkus.version); line=${line%.*}
current=$(evaluate project.version)
last_tag=$(git describe --tags --abbrev=0 --match "v$line.*" 2>/dev/null || true)

if [ -z "$last_tag" ]; then
  next="$line.0"
else
  [ -n "$(git rev-list "$last_tag"..HEAD)" ] || skip "Nothing since $last_tag."
  log=$(git log --format='%s%n%b' "$last_tag"..HEAD)
  grep -qE '^(feat|fix|perf|revert)(\(.+\))?!?:|^[a-z]+(\(.+\))?!:|BREAKING CHANGE' <<< "$log" \
    || skip "No feat, fix, perf, revert or breaking change since $last_tag: nothing to release."
  last=${last_tag#v}
  next="$line.$((${last##*.} + 1))"
fi
# A version already raised by hand in the POM is never lowered.
next=$(printf '%s\n' "$next" "${current%-SNAPSHOT}" | sort -V | tail -n 1)

# A rewritten history can leave a tag behind on a commit main no longer holds: stop rather than retag.
if git rev-parse -q --verify "refs/tags/v$next" > /dev/null; then
  if [ "$(git rev-parse "v$next^{commit}")" = "$(git rev-parse HEAD)" ]; then
    skip "v$next already tags HEAD."
  fi
  echo "::error::Tag v$next exists but is not in main's history; move or delete it, then re-run."
  exit 1
fi

echo "Releasing $next (last tag: ${last_tag:-none}, POM: $current)"
echo "next=$next" >> "$out"
echo "snapshot=$line.$((${next##*.} + 1))-SNAPSHOT" >> "$out"
