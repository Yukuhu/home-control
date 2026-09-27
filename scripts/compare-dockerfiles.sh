#!/usr/bin/env bash
# Checks that Dockerfile and Dockerfile.dist describe the same runtime image. CI tests and
# publishes what Dockerfile.dist builds from a jar; `docker compose up --build` builds the jar
# itself with Dockerfile. Only if everything after the build is the same in both, apart from
# where the jar is copied from, does a test of the one say anything about the other.
#
# usage: scripts/compare-dockerfiles.sh [DOCKERFILE DOCKERFILE_DIST]
set -euo pipefail
cd "$(dirname "$0")/.."

# The last stage of a Dockerfile, without comments, blank lines and the line that copies the jar.
runtime() {
  local dockerfile="$1"
  awk '/^FROM /{ stage = "" } { stage = stage $0 "\n" } END { printf "%s", stage }' "$dockerfile" \
    | grep -Ev '^[[:space:]]*(#|$)|^COPY .*[[:space:]]app\.jar$'
}

source="${1:-Dockerfile}"
dist="${2:-Dockerfile.dist}"
if ! diff -u --label "$source" --label "$dist" <(runtime "$source") <(runtime "$dist"); then
  echo "The runtime stage of $source and $dist differ. Change both the same way." >&2
  exit 1
fi
for file in "$source" "$dist"; do
  [[ "$(grep -Ec '^COPY .*[[:space:]]app\.jar$' "$file")" == 1 ]] \
    || { echo "$file does not copy the jar to app.jar exactly once." >&2; exit 1; }
done
echo "$source and $dist describe the same runtime."
