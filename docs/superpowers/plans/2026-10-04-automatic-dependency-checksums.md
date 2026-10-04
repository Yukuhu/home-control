# Automatic Dependency Checksums Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [x]`) syntax for tracking. Implementation is complete locally; review and live rollout are being tracked below.

**Goal:** CI verifies and commits missing dependency checksums to the existing Dependabot PR, then automatically
runs all normal checks on the committed update.

**Architecture:** Keep preparation read-only in `checksums`; add a separate `checksum-update` job in the same
`ci.yml` that validates metadata on a clean runner and commits it with a repository-scoped GitHub App token.
The resulting PR synchronization starts strict CI again; no additional PR or workflow is created.

**Tech Stack:** GitHub Actions, `gh`, git, Python 3 standard library, Gradle 9.8 / Java 25, existing JUnit and
Node test suites. Python is CI tooling, not an application dependency.

**Spec:** [Automatic dependency checksums design](../specs/2026-10-04-automatic-dependency-checksums-design.md).

## Global Constraints

- All automation remains in `.github/workflows/ci.yml` and writes only to the existing dependency PR branch.
- Normal Gradle builds retain strict dependency verification and use committed metadata.
- Automatic writes target same-repository Dependabot version-catalog PRs, including grouped Gradle updates.
- No build or dependency code runs with the App key or its installation token.
- Existing trusted hashes and verification settings cannot be replaced, broadened, or removed automatically.
- New hashes must match fresh canonical repository downloads; new protoc versions include all published platforms.
- `CI passed` requires `checksums.state=verified` whenever code changed.
- Use `gh` authentication and ordinary git pushes; stage only `gradle/verification-metadata.xml` in automation.
- Generated commit subject: `build(deps): update dependency verification metadata [dependabot skip]`.
- Implementation completion requires `scripts/gradle.sh build` to pass and live same-PR rerun behavior to be observed.

## Review Focus

1. A PR rebased during generation must not receive stale metadata (Task 2 git and API race tests).
2. Metadata inherited from a newer base must not replace or drop trusted head entries (Task 1 semantic tests).
3. A publisher commit must trigger CI without causing a commit loop or stopping Dependabot rebases
   (Task 2 identity tests, Task 3 gate tests, Task 4 live verification).
4. A protobuf update must work beyond the CI runner's architecture (Task 1 platform-discovery tests).
5. Candidate preparation must never make an untested revision green (Task 3 gate and summary tests).

## Task 1: Verify candidate metadata and construct the accepted update

**Files:**
- Create: `scripts/dependency-checksums/verify.py`
- Create: `scripts/dependency-checksums/test_verify.py`
- Create: `scripts/dependency-checksums/fixtures/` with small metadata and repository-response fixtures

**Interfaces:**
- CLI: `python3 verify.py --head HEAD_XML --base BASE_XML --candidate CANDIDATE_XML --output VERIFIED_XML --report REPORT_JSON`.
- `verify_metadata(head_xml: bytes, base_xml: bytes, candidate_xml: bytes, repository: Repository) -> VerifiedMetadata`.
- `Repository.fetch(group: str, module: str, version: str, artifact: str) -> Download`;
  `Repository.protoc_artifacts(version: str) -> list[str]`.
- `Download` contains `body: bytes` and `source_url: str`. `VerifiedMetadata` contains `xml: bytes`,
  `changed: bool`, and `additions: list[dict]` with group, module, version, artifact, sha256, and source URL.
- Production repository access is fixed to canonical hosts; tests inject a fake repository. Candidate input
  never overrides repository origins. CLI exits nonzero with the failing coordinate/reason on rejection.

- [x] **Write failing tests** for a #191-shaped five-artifact addition, unchanged metadata (zero downloads and
  no diff), metadata inherited from base, base/head conflicts, missing or altered trusted hashes, alternate
  hashes for an existing artifact, changed verification configuration, duplicate identities, malformed XML,
  unsafe artifact paths, and mismatched downloaded bytes. Reject DOCTYPE/entity declarations and oversized
  inputs; record only sanitized coordinates and URLs in diagnostics.
- [x] **Run the tests and confirm failure:**
  `python3 -m unittest discover -s scripts/dependency-checksums -p 'test_verify.py' -v`.
  The first run must fail because the verifier is absent, not because a live repository is unavailable.
- [x] **Implement semantic validation and deterministic additions.** Preserve existing hashes/settings from
  head and base, verify new coordinates, and render the accepted metadata with minimal XML churn. Retain the
  existing `Verified against Maven Central` / `Verified against Gradle Plugin Portal` origin conventions.
- [x] **Add download-policy and protoc tests:** Central success; Central 404 followed by Plugin Portal success;
  Central mismatch without fallback; bounded transient retries; disallowed redirect; Linux/macOS/Windows
  compiler discovery; rejected unexpected listing names; a published compiler that cannot be fetched.
- [x] **Implement canonical downloads and protoc completion.** Use HTTPS and allowlisted repository redirects,
  finite timeouts and retries. Compute every new compiler hash from downloaded bytes. Preserve the existing
  compiler entries and include every valid published executable for the new version.
- [x] **Run the full verifier suite until green.** Tests must be offline and deterministic; the report must identify
  all new verified entries, and repeating verification must produce byte-identical XML.
- [x] **Commit:** stage only these scripts/fixtures; use `ci: verify dependency checksum candidates automatically`.

## Task 2: Publish validated metadata to the captured PR head

**Files:**
- Create: `scripts/dependency-checksums/publish.py`
- Create: `scripts/dependency-checksums/test_publish.py`

**Interfaces:**
- `publish.py prepare --repository REPO --pr NUMBER --head HEAD_SHA --base BASE_SHA --tested MERGE_SHA --run-attempt ATTEMPT --artifact DIR --checkout DIR --output DIR`.
- `publish.py commit --repository REPO --pr NUMBER --head HEAD_SHA --checkout DIR --prepared DIR`.
- `prepare` uses read-only `gh` access, validates live PR/run identity, calls Task 1's verifier, and writes
  the verified XML plus `prepared.json` containing the expected head/base, catalog SHA-256, metadata SHA-256,
  and verification report. `MERGE_SHA` comes from `github.sha` for the triggering PR run.
- `commit` receives `GH_TOKEN` only for the App installation token. It rechecks PR/head and prepared-file digest,
  writes the single allowed file, authenticates via `gh auth setup-git`, commits, and pushes normally.
- Both commands output JSON with `state` (`ready`, `unchanged`, `committed`, or `superseded`) and optional
  `commit_sha`; rejection exits nonzero. Repository and PR identity come from workflow arguments, not the artifact.

- [x] **Write failing tests** using temporary bare git remotes and controlled `gh` subprocess responses:
  successful publication has the captured head as sole parent and changes exactly the metadata path; an
  already-applied update produces no commit; a moved/closed PR produces no push; forks, wrong author, changed
  workflow/build scripts, mismatched manifest, symlinks, and malformed artifact data are rejected.
- [x] **Run:** `python3 -m unittest discover -s scripts/dependency-checksums -p 'test_publish.py' -v` and confirm red.
- [x] **Implement `prepare`.** Fetch trusted scripts from the event base checkout, read only the expected candidate
  files, compare the merge parents and current PR identity, apply the path eligibility rule from the spec, then
  invoke the verifier. Use fixed filenames and bounded inputs; extract no arbitrary paths from the artifact.
- [x] **Implement `commit`.** Resolve `gh` from PATH, use argument arrays rather than shell interpolation, disable
  git hooks for automation, and stage only the metadata path. Use the App bot identity and the exact Conventional
  Commit subject from Global Constraints, with `Checksum-Base` and `Checksum-Catalog` trailers from `prepared.json`.
  Use an explicit expected-head lease only after proving the new commit has that head as its sole parent;
  this is an atomic append, never a history rewrite. Do not rebase or upload commit objects through APIs.
- [x] **Add and pass race/loop tests.** Advance the bare remote between validation and push and assert rejection
  without overwriting it. Check PR author rather than triggering actor; reject a second generated update after
  an immediately preceding App metadata commit still fails strict verification with unchanged base/catalog inputs.
  A new base or catalog must remain eligible. Assert `[dependabot skip]` is present and `[skip ci]` is absent.
  Validate no-op reruns and a Dependabot rebase replacing the generated commit.
- [x] **Run all Python tests**, then commit only Task 2 files with
  `ci: publish verified checksums to the existing dependency branch`.

## Task 3: Wire preparation, publication, strict gates, and PR reporting into CI

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `src/test/java/dev/andre/homecontrol/deployment/DependencyVerificationWorkflowTest.java`
- Modify: `build.gradle.kts` deployment test inputs where new files are read by those tests
- Modify: `scripts/pr-summary/summary.mjs`
- Modify: `scripts/pr-summary/summary.test.mjs`

**Interfaces:**
- `checksums.outputs.state`: `verified` or `candidate`; absent on job failure.
- `checksums.outputs.artifact`: exact current-run candidate artifact name including PR, head, and attempt.
- `checksum-update.outputs.state`: `unchanged`, `committed`, or `superseded` on success.
- `checksum-update.outputs.commit_sha`: the published commit when `state=committed`.
- The PR summary consumes these through its existing `NEEDS_JSON` input.

- [x] **Write failing workflow contract tests** for the three states, same-workflow publication, fresh-runner
  separation, no App credentials in Gradle jobs, trusted-base helper checkout, same-run artifact selection,
  publisher eligibility by PR author/repository, and strict prerequisites on every build and dependency submission.
  Add summary tests for an update being prepared, a committed update, a superseded run, credential/verification
  failure, and a later verified run. Include a matrix proving candidate state can never produce `CI passed`.
- [x] **Run focused tests:**
  `scripts/gradle.sh test --tests 'dev.andre.homecontrol.deployment.DependencyVerificationWorkflowTest'` and
  `npm test --prefix scripts/pr-summary` after `npm ci --prefix scripts/pr-summary`. Confirm the new tests fail
  on the old behavior.
- [x] **Update `checksums`.** Run the Python helper tests in this required job. Retain strict verification first;
  on an eligible Dependabot verification failure, generate metadata with no App secrets or shared dependency
  cache, upload complete candidate XML/identity, and return `state=candidate`. Remove the deliberate
  "maintainer must commit" failure step. All other failures remain failures. Return `state=verified` only after
  strict verification succeeds against committed metadata.
- [x] **Add `checksum-update`.** Run only after a complete candidate; retain read-only default permissions and
  grant Pull requests read where API checks require it. Checkout trusted base helpers and PR data separately,
  use `prepare`, mint a pinned GitHub App token only for `ready`, then use `commit`. Keep artifact/report uploads
  and error summaries free of credentials. Missing App configuration must give a clear setup error.
- [x] **Update downstream conditions and the required gate.** All build jobs plus `dependencies` require
  `state=verified`. Include `checksum-update` in the aggregate gate and summary dependencies. Preserve failure
  propagation, existing main/release behavior, and the documentation-only fast path. Leave the existing
  PR concurrency cancellation in place so the new synchronization supersedes the candidate run.
- [x] **Update PR summary behavior** and add a live-head check before posting/replacing the existing summary,
  so an obsolete candidate run cannot overwrite the current run's report. This extends the current CI summary
  mechanism; no separate bot conversation or additional PR is introduced.
- [x] **Run the focused Java and summary suites plus all Python tests until green.** Commit only Task 3 paths
  with `ci: complete dependency checksum updates within pull requests`.

## Task 4: Document the trust policy, configure the App, and validate #191

**Files:**
- Modify: `.github/dependabot.yml` explanatory comments
- Modify: `docs/dev/testing.md`
- Modify: `docs/dev/ci-and-releases.md`
- Create: `docs/adr/0008-automatic-dependency-checksums.md` (use the next free number at implementation time)
- Modify: `docs/adr/README.md`

**Interfaces:**
- Repository variable `CHECKSUM_APP_CLIENT_ID`.
- `CHECKSUM_APP_PRIVATE_KEY` in both Actions and Dependabot secret stores, with identical values.
- GitHub App installation limited to this repository; Contents write and Pull requests read; no main-branch bypass.

- [x] **Update documentation and ADR.** Replace normal manual-patch instructions with the automatic same-PR flow,
  its state transitions, error handling, and credential setup. Record the repository-origin trust policy and
  its integrity/provenance distinction. Keep manual commands only as troubleshooting, not a routine requirement.
- [x] **Run required final local validation:** `scripts/gradle.sh build`,
  `python3 -m unittest discover -s scripts/dependency-checksums -p 'test_*.py' -v`,
  `npm test --prefix scripts/pr-summary`, and `git diff --check`.
  The Gradle build must be green without any verification-generation flag. Browser functionality is unchanged;
  the subsequent live CI run must still execute its normal browser jobs.
- [x] **Commit documentation** using `docs: explain automatic dependency checksum updates`, staging exact paths.
- [x] **Provision the App during authorized implementation rollout.** Register/install the App and configure
  the named variable and secret stores using the user's GitHub workflow. Keep private-key contents out of
  chat, logs, and the repository. Select and verify the full token-action commit SHA before enabling the job.
  If GitHub requires an owner interaction, prepare the exact setup and report that requirement; never silently
  substitute a broad personal token or a manual-per-PR process.
- [ ] **Deploy the workflow and base helpers together.** Once available on `main`, update the existing #191 branch
  with the new base using `gh pr update-branch 191`, after checking its current head. Do not create a replacement
  dependency PR, post a command comment, or rerun only the old workflow revision.
- [ ] **Observe the live result on #191.** Confirm exactly one generated metadata commit, the same branch/PR,
  no additional write credential in preparation logs, and an automatically triggered PR CI run on the new head.
  Confirm strict verification, build/tests, browser checks, and `CI passed` complete successfully. A failure
  elsewhere is investigated and reported, not hidden by the checksum update.
- [ ] **Verify idempotence on the current head.** Rerun its checksum job and confirm it reports verified and creates
  no commit. Confirm the generated subject passes `scripts/check-commits.sh`. Leave merging the dependency PR
  to the user's normal review workflow.

## Completion evidence

Record the local test results, updated PR head SHA, generated commit SHA, and follow-up CI run URL. The feature
is not complete merely because a candidate artifact was generated or a commit was pushed: the same PR must
receive an unattended strict CI run, and a repeat check must make no further commit.

## Implementation evidence (2026-10-04)

- Full `scripts/gradle.sh build`: passed; 3,921 tests, zero failures/errors after the Sonar follow-up.
- Python tooling: 37 tests passed, including real temporary git remotes, CLI entry points, and GitHub adapter errors; 91% branch-inclusive coverage after the Sonar follow-up.
- PR summary: 63 tests passed.
- Real #191 candidate: verified five new hashes after completing its omitted JUnit BOM `.module` file.
- App variable/secrets and installation permissions were configured and authenticated before implementation.
- Final independent review: no blocking findings; live rollout remains outstanding.
- Real #191 strict-resolution replay: passed with the independently verified five-artifact update.
