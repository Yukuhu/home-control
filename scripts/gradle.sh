#!/usr/bin/env bash
# Tracked copy of .superpowers/gradle.sh (.superpowers/ is gitignored agent scratch space). Runs
# Gradle inside a container image (default gradle:jdk25) for anyone without a local JDK 25.
# Usage: scripts/gradle.sh test   |   scripts/gradle.sh test --tests 'dev.andre.*Foo*'
#        HC_GRADLE_IMAGE=home-control-e2e:playwright-1.63.0 scripts/gradle.sh e2eTest
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/container-runtime.sh"
IMAGE="${HC_GRADLE_IMAGE:-gradle:jdk25}"
mkdir -p "$HOME/.cache/hc-gradle"
exec "$HC_CONTAINER_ENGINE" run --rm --network host "${HC_CONTAINER_RUN_OPTIONS[@]}" -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/gradle-home -e HOME=/tmp \
  -v "$HOME/.cache/hc-gradle:/gradle-home" -v "$ROOT:$ROOT" -w "$ROOT" \
  "$IMAGE" ./gradlew --no-daemon -q "$@"
