#!/usr/bin/env bash
# Checks commit subjects, one per line on standard input, against Conventional Commits: releases and the changelog
# are made from them (docs/dev/ci-and-releases.md). Prints every subject that does not follow them, and fails if
# there is one. CI runs it on every commit of a pull request:
#
#   git log --no-merges --format=%s BASE..HEAD | scripts/check-commits.sh
set -euo pipefail

# A type, an optional (scope), an optional ! for a breaking change, then ": " and a description.
pattern='^(feat|fix|refactor|test|docs|build|ci|chore|revert)(\([^()]+\))?!?: [^[:space:]]'
bad=0
while IFS= read -r subject || [[ -n "$subject" ]]; do
  if [[ ! "$subject" =~ $pattern ]]; then
    echo "Not a Conventional Commit: $subject"
    bad=$((bad + 1))
  fi
done
if ((bad > 0)); then
  echo "$bad commit message(s) do not start with a type (feat, fix, refactor, test, docs, build, ci, chore or" \
    "revert), an optional (scope) and \": \"; see AGENTS.md. Reword them, for example with git rebase -i."
  exit 1
fi
echo "Every commit message follows Conventional Commits."
