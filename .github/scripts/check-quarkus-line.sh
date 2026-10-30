#!/usr/bin/env bash
# The adapter is versioned per Quarkus line: the project version and the extension descriptor's
# requires-quarkus-core must follow quarkus.version. Run after `mvn verify` (it reads the built descriptor).
set -euo pipefail

# Maven's error goes to the log, not into the variable the caller assigns.
evaluate() {
  local value
  value=$(mvn -B -q help:evaluate -Dexpression="$1" -DforceStdout) || { echo "$value" >&2; exit 1; }
  echo "$value"
}

quarkus=$(evaluate quarkus.version)
version=$(evaluate project.version)
line=${quarkus%.*}
range="[$line,${line%.*}.$((${line#*.} + 1)))"

if [ "${version%.*}" != "$line" ]; then
  echo "::error::Project version $version is not on the Quarkus $line line: set it to $line.0-SNAPSHOT (mvn versions:set)."
  exit 1
fi
if ! grep -qF "requires-quarkus-core: \"$range\"" dev-profile-adapter/target/classes/META-INF/quarkus-extension.yaml; then
  echo "::error::Set <requiresQuarkusCore> in dev-profile-adapter/pom.xml to $range."
  exit 1
fi
echo "On the Quarkus $line line: version $version, requires-quarkus-core $range."
