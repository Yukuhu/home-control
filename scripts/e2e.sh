#!/usr/bin/env bash
# Tracked copy of .superpowers/e2e.sh: .superpowers/ is gitignored (agent scratch space), but a
# human without a local JDK needs this to survive in version control. Keep it in sync with
# .superpowers/e2e.sh. CI does NOT use this script — its `e2e` job has a real JDK via
# actions/setup-java and just runs `./gradlew installPlaywrightBrowsers`/`./gradlew e2eTest`
# directly; this is for local runs only.
#
# Runs the Playwright browser tests inside gradle:jdk25 + browsers (no local JDK or browsers needed).
# Usage: scripts/e2e.sh                      # Chromium, Firefox and WebKit
#        scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*PlaySheet*'
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's/^playwright = "\(.*\)"$/\1/p' "$ROOT/gradle/libs.versions.toml")"
if [[ -z "$VERSION" ]]; then
  echo "playwright version not found in gradle/libs.versions.toml" >&2
  exit 1
fi
IMAGE="home-control-e2e:playwright-$VERSION"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  docker build -t "$IMAGE" --build-arg "PLAYWRIGHT_VERSION=$VERSION" \
    -f "$ROOT/scripts/e2e.Dockerfile" "$ROOT/scripts"
fi
HC_GRADLE_IMAGE="$IMAGE" exec "$ROOT/scripts/gradle.sh" e2eTest "$@"
