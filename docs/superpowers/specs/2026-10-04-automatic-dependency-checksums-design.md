# Automatic dependency checksums in the existing PR

Date: 2026-10-04
Status: Approved for implementation; implemented locally, rollout pending

## Intent

The user wants dependency checksum updates completed automatically by the CI workflow in the dependency's
existing pull request. A normal update must not require downloading a patch, committing it manually, opening
another PR, or approving a workflow run. The scope is this repository's Dependabot Gradle updates, including
grouped updates. Application behavior and automatic merging are outside this change.

Success means PR #191 receives its missing verification metadata on its own branch, a new CI run verifies the
committed metadata, and subsequent dependency updates follow the same path without maintainer intervention.
Real checksum mismatches, unavailable artifacts, and build or test failures must still fail CI.

## Evidence and current behavior

- PR #191 changes `commons-lang3` from `3.20.0` to `3.21.0` in `gradle/libs.versions.toml`.
- Run `37159376770` rejects five missing artifact hashes, successfully generates a candidate patch, and then
  deliberately fails `checksums`. All dependent builds are skipped.
- `.github/workflows/ci.yml` already contains generation, artifact upload, and the dependency gate. The old
  standalone workflow was removed in commit `82335c63`; it did not commit metadata either.
- App setup was completed on 2026-10-04: Client ID and both private-key secrets are configured; App authentication
  and its Contents write / Pull requests read installation permissions were verified.

## Selected approach

Extend the existing workflow with a `checksum-update` job on a fresh runner. Keep candidate generation in the
read-only `checksums` job. The update job independently validates candidate data, then uses a short-lived
GitHub App installation token to commit only `gradle/verification-metadata.xml` to the original PR branch.
That push starts the normal `pull_request` CI workflow again.

The existing `push: main` and `pull_request` triggers remain. There is no extra workflow file, replacement PR,
`pull_request_target`, or `workflow_run` publisher. The existing run may be superseded by the new run; it must
never report `CI passed` for a revision whose strict verification failed.

Alternatives considered:

- Giving the generation job write access and committing whatever Gradle produces is smaller, but exposes
  write credentials to dependency code and accepts changes to previously trusted artifacts.
- Using only `GITHUB_TOKEN` avoids another credential, but GitHub documents approval requirements for PR runs
  caused by that token. A repository-scoped App token provides the unattended follow-up run required here.

## Workflow states

| `checksums` result | Meaning | Next action |
| --- | --- | --- |
| success, `state=verified` | Committed metadata passed strict verification | Run all normal builds and checks; skip `checksum-update` |
| success, `state=candidate` | A Dependabot update needs metadata and generated a complete candidate | Run `checksum-update`; skip builds against this old revision |
| failure | Resolution, generation, or another prerequisite failed | Fail CI with the underlying error |

`checksum-update` either commits metadata, detects that the PR has moved, or fails with a specific reason.
Only `state=verified` permits `CI passed` for a code-changing PR or main run. Documentation-only PRs retain
their existing behavior. `dependencies` also waits for verified metadata before submitting a dependency graph.
The existing PR summary reports an automatic update in progress, its commit, or the specific failure, instead
of instructing users to download an artifact. Artifacts remain useful diagnostic evidence.

## Candidate and verification contract

The generation job records the event's PR number, repository, head SHA, base SHA, tested merge SHA, and run
attempt with the generated XML. Artifact names include PR number, head SHA, and attempt. The update job
downloads only the named artifact from its own run. These fields are checked against event/API data; they
do not select the repository, branch, scripts, or credentials to use.

The update job executes helpers from the event's base SHA, with the PR checked out separately as data. It
does not run Gradle, build plugins, npm dependencies, local actions, or scripts from the PR. Before creating
the App token it must:

1. Confirm the live PR is open, authored by `dependabot[bot]`, targets this repository's `main`, and has its
   head branch in this repository. Match the expected head SHA and the tested merge's parents.
2. Limit automatic writes to version-catalog dependency PRs: changed paths may be
   `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. This covers the project's current
   Gradle declarations, including plugins. Other PRs keep normal strict checks and get no automatic write.
   Any future expansion to executable build files must be deliberate and tested.
3. Parse the metadata as data. Preserve verification configuration and every existing hash. Reject removals,
   replacements, alternate hashes for an already trusted artifact, duplicate/conflicting identities, trust
   exclusions, and malformed XML. Compare against both the PR head and the tested base; preserve legitimate
   metadata inherited from the base without discarding changes on either side.
4. Independently download each genuinely new artifact from Maven Central, falling back to the Gradle Plugin
   Portal only when absent from Central. Construct paths from validated coordinates and artifact names;
   never accept a download URL supplied by the candidate. Require HTTPS, bounded timeouts/retries, and
   approved repository redirect destinations. A Central checksum mismatch must not trigger a fallback.
5. Calculate SHA-256 locally and require an exact match with the candidate. Record verified source URLs in
   the report. Inherited entries already identical to the tested base need no new download.
6. For a new `protoc` version, discover its published platform executables at Maven Central and include all
   of their hashes, including Linux, macOS, and Windows. A Linux CI runner must not produce Linux-only metadata.
7. Include `.module` companions advertised by verified POM files. The real #191 candidate omitted the
   advertised JUnit BOM module; a strict-resolution replay proved it was still required.
8. Produce a deterministic metadata file containing the accepted additions with minimal formatting changes.
   Artifact files, patches, and candidate URLs are never executed.

This replaces the manual fresh-download comparison with an explicit repository-origin trust policy. It checks
integrity against canonical repository bytes; it does not establish publisher identity or detect a compromised
upstream release. Keep this distinction in an ADR. Do not claim independent signature authentication.

## Commit and rerun

Install a dedicated GitHub App only on `Yukuhu/home-control`, with Contents write and Pull requests read.
Store `CHECKSUM_APP_CLIENT_ID` as a repository variable and `CHECKSUM_APP_PRIVATE_KEY` under the same name
in both Actions and Dependabot secrets. Dependabot-triggered runs use the latter; App-triggered runs use the
former. Pin `actions/create-github-app-token` to a reviewed full commit SHA and request only this repository
and these permissions. Mint the token after validation, only in `checksum-update`.

Use the installed `gh` CLI (`command -v gh`, then `gh auth setup-git` with the App token) and ordinary git
commit/push. Recheck the PR's live head immediately before publishing. The commit must have the captured head
as its sole parent and change only the metadata file. Push the proven direct-child commit to the existing branch with an explicit expected-head lease. This guards
against a concurrent rewind or deletion as well as a forward update; it cannot rewrite history or create a branch. A concurrent update makes the write stale and must not be overwritten or rebased silently.

Commit subject: `build(deps): update dependency verification metadata [dependabot skip]`.
The Dependabot marker lets Dependabot replace this generated commit during future rebases. It is not a
`[skip ci]` marker. After such a rebase, CI generates metadata again if necessary.

Select eligibility by PR author, not `github.actor`, so an App-authored metadata commit still belongs to a
Dependabot PR. Normally the next run verifies successfully and makes no further commit. Record `Checksum-Base`
and `Checksum-Catalog` commit trailers containing the tested base SHA and catalog SHA-256. If the latest commit
was made by this App with the metadata-update subject, those inputs are unchanged, and verification still needs
additions, fail with a diagnostic instead of making an unbounded sequence of update commits. A newer base or
catalog is a new input and remains eligible for an update. Missing credentials also fail visibly.

## Validation and rollout

Use offline Python standard-library tests for semantic metadata validation and git publication with temporary
repositories. Extend the existing Java workflow contract tests and JavaScript summary tests. Run the required
`scripts/gradle.sh build`, the Python tests, and the summary suite before rollout.

Record the changed trust policy in the next available ADR. Update developer documentation and Dependabot
comments to describe automatic commits. Configure the App before enabling the publisher. After the workflow
and its trusted helpers are on `main`, update the existing PR #191 branch with the new base using `gh`; merely
rerunning its historical run would still execute the old workflow. Observe the bot commit and the complete new
CI run on that same PR. Do not merge #191 as part of testing the automation.

## References

- [Failing checksum job for #191](https://github.com/Yukuhu/home-control/actions/runs/37159376770/job/111309441986)
- [GitHub token event and approval behavior](https://docs.github.com/en/actions/concepts/security/github_token)
- [Dependabot secret stores and token permissions](https://docs.github.com/en/code-security/reference/supply-chain-security/troubleshoot-dependabot/dependabot-on-actions)
- [GitHub App token action and git authentication](https://github.com/actions/create-github-app-token)
- [Allowing Dependabot to rebase over generated commits](https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/manage-your-dependency-security/manage-dependabot-prs)
- [Gradle dependency verification and bootstrapping](https://docs.gradle.org/current/userguide/dependency_verification.html)
