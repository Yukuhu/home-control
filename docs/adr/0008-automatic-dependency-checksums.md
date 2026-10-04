# 0008: Automatic dependency checksum updates in the existing PR

Date: 2026-10-04

Status: Accepted

## Context

Dependabot changes the Gradle version catalog but cannot update Gradle verification metadata. CI previously
prepared a patch and deliberately failed until a maintainer downloaded, verified, and committed it. The user
requires the update to complete automatically inside the same PR and CI workflow.

## Decision

Keep strict verification on all builds. A read-only CI job generates candidate metadata in an isolated cache;
when it produces a candidate, a second job on a clean runner validates it using scripts from the PR's base
commit. The publisher never runs Gradle or code from the dependency branch. It accepts only additive metadata
for same-repository Dependabot PRs changing the version catalog and verification metadata.

Preserve existing hashes and verification settings. Compare each new artifact's SHA-256 with a fresh download
from Maven Central, or from the Gradle Plugin Portal if Central returns 404. Only HTTPS repository origins and
approved redirects are allowed. Include POM-advertised Gradle module metadata even when generation omits it,
and include all published protoc platforms. Previously verified metadata inherited
from the base can be retained without another download.

After validation, mint a short-lived GitHub App token restricted to this repository. Commit only verification
metadata on the existing dependency branch. The commit is a direct child of the captured PR head. An explicit
expected-head lease makes publication atomic even if Dependabot concurrently rewinds or deletes its branch;
it never permits the publisher to overwrite a newer head or rewrite history.

The App's push triggers a new PR CI run. Only strict verification of committed metadata allows builds and the
required aggregate check to pass. Generated commits include `[dependabot skip]`, permitting Dependabot to
replace them when rebasing. Base/catalog trailers prevent repeated publication for unchanged inputs.

## Consequences

Normal dependency updates no longer require manual checksum commits or extra pull requests. The App private
key must be maintained in both Actions and Dependabot secret stores. A missing credential, modified trusted
hash, unavailable artifact, unsupported PR change, or failed build remains a visible CI failure.

The policy trusts new artifacts as served by the canonical repositories. Comparing downloads establishes
integrity relative to those repositories; it does not independently authenticate a publisher or detect a
compromised upstream release. Existing artifact hashes remain immutable. Expanding trust to other repositories,
metadata policies, or PR file types requires a deliberate code change.
