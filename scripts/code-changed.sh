#!/usr/bin/env bash
# Says whether a change touches anything that CI builds, tests or publishes. It reads the paths
# of the changed files, one per line, and prints "true" or "false".
#
# Only what is listed here counts as documentation. Everything else is code, the workflow and
# these scripts included, and so is a change whose files are unknown: when in doubt, CI runs.
#
# usage: git diff --name-only main... | scripts/code-changed.sh
set -euo pipefail

documentation='^(docs/.*|[^/]+\.md|LICENSE|\.github/ISSUE_TEMPLATE/.*|\.github/pull_request_template\.md)$'
# Guides that the deployment tests read (their inputs in build.gradle.kts) are code too: a change to one of them
# alone has to run the tests that check it.
read_by_tests='^(docs/user/bluetooth-speakers\.md|docs/bluetooth-speakers\.md)$'

files="$(grep -v '^[[:space:]]*$' || true)"
if [[ -z "$files" ]] || grep -Evq "$documentation" <<< "$files" || grep -Eq "$read_by_tests" <<< "$files"; then
  echo true
else
  echo false
fi
