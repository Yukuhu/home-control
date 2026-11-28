# WP-02: Release what ships — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** A Gradle or base-image update cuts a patch release on its own, a maintainer can start a release by hand,
and a breaking change below 1.0 releases the next minor instead of failing CI. Dependabot sends about one grouped pull
request per ecosystem a week (Actions once a month), `perf:` works as documented, and a version override or a checksum
entry that has outlived its reason shows up by itself.

**Findings:**

- [BUILD-1](../../reviews/2026-11-27-improvement-catalogue.md#build-1): all of it. Gradle and Docker bumps are
  committed as `fix(deps)`, a manual run can force a patch, `check-commits.sh` refuses `fix(ci)`-style scopes, and the
  README and the release table are corrected. The "more precise" option (comparing `BOOT-INF/classpath.idx` and the
  FROM digests) is left out; see owner decision 1.
- [BUILD-2](../../reviews/2026-11-27-improvement-catalogue.md#build-2): all of it. A proposed 1.0.0 below 1.0
  becomes the next minor, and a test pins it.
- [BUILD-3](../../reviews/2026-11-27-improvement-catalogue.md#build-3): all of it. Groups across directories and
  ecosystems, a cooldown, Actions monthly, and squash-merged Dependabot PRs (a repository setting, owner decision 6).
- [DOCS-11](../../reviews/2026-11-27-improvement-catalogue.md#docs-11): partly fixed by 432fbd2, which made
  AGENTS.md say Spring Boot 4.1.2, correct today. Still to do: `perf:`, the `build:` and `revert:` rows, a Spring
  Boot version that cannot go stale, the PR template's command, the `.superpowers` wording in the scripts and in
  `testing.md`, the CI job name in `e2e.sh`, and the stale largest-class measure. The "test count" it mentions is no
  longer in `docs/dev/` or the README.
- [BUILD-12](../../reviews/2026-11-27-improvement-catalogue.md#build-12): all of it. `verify.py` inserts entries in
  sorted order, a maintainer-run `prune.py` is added, and the first sort-and-prune pull request is made.
- [ARCH-14](../../reviews/2026-11-27-improvement-catalogue.md#arch-14): partly fixed. 4d898c9 raised Tomcat above
  the BOM again, and 432fbd2 rewrote the comments, corrected AGENTS.md and kept Jackson 3.2.x deliberately. Still to
  do: a test that fails when a raise is no longer above the version Spring Boot's BOM manages, and comments that stop
  quoting the managed version.

**Approach:**
- **Releases.** The jar job's inline `plan` step becomes `scripts/release-plan.sh`, plain bash that reads the
  changelog action's outputs, the last tag and two flags from its environment.
  - `ReleaseWorkflowTest` runs it as `CommitMessagesTest` runs `check-commits.sh`.
  - Below 1.0 it turns a proposed major into the next minor, and a manual run with nothing releasable gets a patch.
  - It refuses a version that is not above the last tag.
  - `ci.yml` gains `workflow_dispatch`, and the `release` job accepts it on `main` with its `needs` unchanged.
- **Dependency updates.** `.github/dependabot.yml` names a prefix for every ecosystem, so Dependabot never copies
  recent commits: `fix` for Gradle and Docker, `build` for Actions and npm.
  - Groups and cooldowns hold this to about one releasable commit a week.
  - `DependabotConfigTest` pins the file.
  - `check-commits.sh` closes the opposite leak, a `feat` or `fix` scoped to something not in the image.
- **Overrides.** `VersionOverridesTest` reads Spring Boot's own BOM through a new non-transitive `springBootBom`
  configuration. Its POM is already trusted.
- **Checksums.** `verify.py` inserts in Gradle's string order. `prune.py` reuses `verify.parse`, deletes whole
  components a fresh generation did not resolve, and never adds or changes a hash.

**Depends on / touches:**
- **Depends on:** nothing.
- **WP-08** works in the same files. BUILD-4 changes the `dependencies` job, and BUILD-6 changes `sonar` in the
  release's `needs`. BUILD-V1 writes re-run docs, BUILD-11 changes the metadata schema, and BUILD-15 edits the headers
  of `gradle.sh` and `e2e.Dockerfile`. If BUILD-7's `deploymentTest` task lands first, this package's tests and the
  BOM property move onto it.
- **WP-18** (DOCS-10) edits the README tag table.
- **WP-19** (DOCS-12, DOCS-5) edits the PR template and adds a CONTRIBUTING type list, which must include `perf:`.
- **[ADR 0008](../../../adr/0008-automatic-dependency-checksums.md)** is amended in Task 8.

**Effort:** M overall: six S pull requests, PR 1 about half a day, plus a week to see Dependabot's first grouped run.

## Before you start

**Re-check each finding on current `origin/main`.** Run `git fetch` first: this checkout once lagged behind. If a part
is fixed already, tick its steps and name the commit.

- **BUILD-1** is open while:
  - `ci.yml` has only `push` and `pull_request` triggers (:3-6), `skip-on-empty: true` (:298) and a push-only
    `ON_MAIN` (:309);
  - `dependabot.yml` has no `commit-message`;
  - `check-commits.sh:11` has no `perf`;
  - `README.md:52-53` says "newest code".
- **BUILD-2** is open while the `plan` step refuses `v0.*` → non-`0.*` (:319-324) and no test names
  `Refusing to promote`.
- **BUILD-3** is open while Gradle has only four groups (:13-25), Actions only `codeql` (:34-38), and Docker none
  (:43-46). Baseline: 43 `build(deps)` commits since 2026-11-01.
- **DOCS-11** is open while:
  - `ci-and-releases.md:83` lists `perf:`, and :85 has no `build:` or `revert:`;
  - the PR template says `./gradlew build` (:21);
  - the scripts and `testing.md:254-256` name `.superpowers/`;
  - `architecture.md:127` says 453 lines, while `Enrollment.java` has 480.
- **BUILD-12** is open while:
  - the metadata has 574 components and 17 sort breaks, the first at :2473;
  - `render_additions` (`verify.py:196-215`) appends before `</components>`;
  - four `tomcat-embed-core` versions are trusted.
- **ARCH-14** is open while:
  - `libs.versions.toml:3-11` quotes the managed versions;
  - the raises are at `build.gradle.kts:6-12` and `:169-177`, and at `protocols/build.gradle.kts:16-20`;
  - the 4.1.2 BOM sets `tomcat.version` to 11.0.27 (:215) and `jackson-bom.version` to 3.1.7 (:83);
  - no test reads either version.

**Owner decisions.** Ask these up front. None changes a product rule or a name that never changes. Decisions 4 and 5
change `AGENTS.md`'s commit rules, and decision 10 amends ADR 0008.

1. **How does a dependency update become releasable?** Recommended: through Dependabot's prefix, `fix(deps)` for
   Gradle and Docker. That is S and configuration only. Tooling bumps then also release an unchanged image, weekly at
   most once grouped. The digest comparison (M) can follow if those releases become a nuisance.
2. **What does a manual run take?** Recommended: one boolean, `force-patch`, default on. Off makes it a rehearsal.
   There is no "minor" choice, because a minor needs a `feat:` commit for the notes to say why.
3. **How is 1.0 reached?** The finding suggests a repository variable. Recommended: no variable. Going to 1.0 is a
   reviewed one-line change to `release-plan.sh` with an ADR, so the policy stays in the history.
4. **Is `perf:` in or out?** Recommended: in. The preset already releases it as a patch, and the docs promise it.
5. **Which scopes are refused on `feat`, `fix` and `perf`?** Recommended: `ci`, `test`, `tests`, `build`, `docs` and
   `deps-dev`. That is the finding's four plus two that leak the same way.
6. **Squash-merge Dependabot PRs?** Recommended: yes. Allow squash merging with the PR title as the message, and use
   it for Dependabot only. This is a repository setting.
7. **Cadence and cooldown?** Recommended: Gradle, Docker and npm weekly, Actions monthly. Set `default-days: 5`, and
   `semver-major-days: 14` where versions are semver. Put Spring Boot in the runtime group; `@dependabot ignore`
   unblocks a stuck group. Security updates are never delayed.
8. **May a Spring Boot PR fail?** A Spring Boot PR that makes a raise stale fails `VersionOverridesTest` until the
   raise is removed on that PR. Recommended: yes, that is the point.
9. **What happens to the progress measures?** Recommended: keep them in `architecture.md`, rename "Now" to "Last
   measured", and correct the largest class. Moving them to the roadmap spec is the alternative.
10. **Amend ADR 0008?** Recommended: yes. A maintainer-run prune may withdraw trust in whole components, but it never
    adds or edits a hash, and the automatic flow stays additive.

## Global constraints

- **A LAN appliance.** No service, app or bot joins the release path. These stay as they are:
  - the image `ghcr.io/yukuhu/home-control` and its tags (`X.Y.Z`, `X.Y`, `latest`, `sha-…`, `-bluetooth`);
  - the names that never change: `dev.andre.shield-remote`, `dev.andre.shield` and `shield.*`.
- **Releases stay gated.** The `release` job's `needs` do not change. Only smoke-tested digests are published. A
  manual run anywhere but `main` publishes nothing.
- **ADR 0008 holds:**
  - strict verification and the checksum gate cover every Gradle job (`DependencyVerificationWorkflowTest`);
  - `trusted_metadata` and `publish.py`'s `ALLOWED` stay as they are;
  - `prune.py` runs only by hand, in a reviewed PR.
- **Untrusted text reaches scripts through `env:` only.** Commit subjects, the changelog and the dispatch input never
  appear as `${{ }}` inside `run:` (`ci.yml:362-366`), and no new action is added.
- **Tests sit where AGENTS.md says.**
  - Deployment tests live in `dev.andre.homecontrol.deployment` of the root module. Every file they read is in
    `deploymentFiles` (`build.gradle.kts:213-227`).
  - No test reads a `docs/` guide, so a docs-only PR still builds nothing (`ChangedFilesTest`).
  - Python tests keep at least 80% coverage.
- **The configuration cache is on** (`gradle.properties`), so new build logic must be cache-compatible.
- **Every task** ends with `scripts/gradle.sh build` green; only Task 9 needs `scripts/e2e.sh`. Commits are `ci:`,
  `build:` or `docs:`, and stage only the task's files. Markdown wraps at 120.

## Review focus

1. **The version is right, and nothing else releases.** `ReleaseWorkflowTest`'s
   `aBreakingChangeBelow1_0ReleasesTheNextMinor`, `aManualRunReleasesAPatchWhenNothingIsReleasable`,
   `aPullRequestOnlySaysWhatMergingWouldRelease` and `aVersionThatIsNotAboveTheLastReleaseFailsTheRun`.
2. **A manual run waits for every gate a push does, and its input goes through `env:`.**
   `aManualReleaseWaitsForTheSameGatesAsAPush`, `onlyAPushOrAManualRunOnMainMayRelease`, and the unchanged
   `DependencyVerificationWorkflowTest`.
3. **Every ecosystem names its prefix, and only what ships is a `fix`.** `DependabotConfigTest`'s
   `whatShipsInTheImageIsCommittedAsAFix`, `whatNeverShipsIsCommittedAsABuildChange` and
   `gradleUpdatesArriveInLockstepThenToolingThenRuntimeGroups`.
4. **`perf:` and `fix(deps)` pass; `fix(ci)` fails.** `CommitMessagesTest.conventionalSubjectsPass` and
   `aReleasableTypeScopedToWhatNeverShipsFailsByName`.
5. **No checksum tool edits a kept hash.** `test_insertion_keeps_every_existing_byte`, the unchanged
   `test_rejects_removal_changed_hash_and_conflicting_base`, `test_refuses_to_add_or_change_trust` and
   `test_deletes_only_whole_components_nothing_resolves`.
6. **A stale raise fails the build.** `VersionOverridesTest.everyRaiseIsStillAboveTheVersionSpringBootManages` and
   `everyRaiseTheCatalogDescribesIsChecked`.

## File structure

- **Create:**
  - `scripts/release-plan.sh` (mode 100755)
  - `scripts/dependency-checksums/prune.py` and `test_prune.py`
  - `ReleaseWorkflowTest`, `DependabotConfigTest` and `VersionOverridesTest` in
    `src/test/java/dev/andre/homecontrol/deployment/`
- **Modify:**
  - CI: `.github/workflows/ci.yml` (`on:`, the jar job's `plan` and notes steps, the `if` of `release`) and
    `.github/dependabot.yml`
  - Scripts: `scripts/check-commits.sh`, `scripts/dependency-checksums/verify.py`, and the header comments of
    `scripts/gradle.sh`, `scripts/e2e.sh` and `scripts/e2e.Dockerfile`
  - Build: `build.gradle.kts`, the comments in `gradle/libs.versions.toml`, and `gradle/verification-metadata.xml`
    (Task 9)
  - Docs: `AGENTS.md`, `README.md`, `.github/pull_request_template.md`, `docs/dev/ci-and-releases.md`,
    `docs/dev/testing.md`, `docs/dev/architecture.md` and `docs/adr/0008-automatic-dependency-checksums.md`
- **Test:** the new files above, plus `CommitMessagesTest.java` and `scripts/dependency-checksums/test_verify.py`.

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `ci: release a breaking change below 1.0 as a minor, and a patch by hand` | 1, 2, 3 | M |
| 2 | `ci: group Dependabot updates and commit those that ship as fix(deps)` | 4 | S |
| 3 | `build: fail the build when a raised version no longer raises anything` | 5 | S |
| 4 | `docs: fix small contradictions in the contributor guides` | 6 | S |
| 5 | `build: keep dependency verification metadata in Gradle's order, and prune it` | 7, 8 | S |
| 6 | `build: sort and prune dependency verification metadata` | 9 | S |

The PRs are independent, with three ordering rules:
- PR 5 comes before PR 6.
- PR 2 follows PR 1 in the same week, because both edit the release table.
- PR 6 merges while no Dependabot Gradle PR is open, or Dependabot rebases the open ones afterwards.

## Tasks

### Task 1: The commit check accepts `perf:` and refuses releases of what never ships

**Files:** Modify `scripts/check-commits.sh`, `AGENTS.md` and `docs/dev/ci-and-releases.md`. Test
`src/test/java/dev/andre/homecontrol/deployment/CommitMessagesTest.java`.

**Interfaces:** A new output line, `Releases an unchanged app: <subject> (write <type>: instead)`. Input and exit
codes stay as they are.

- [ ] **Step 1: Write the failing test.** Add three subjects to `conventionalSubjectsPass`:
  - `perf: cache the TMDB configuration`
  - `fix(deps): bump org.jmdns:jmdns from 3.6.5 to 3.6.6`
  - `build(deps-dev): bump c8 from 10.1.3 to 10.1.4 in /scripts/pr-summary`

  Then add a test:

  ```java
  @Test
  void aReleasableTypeScopedToWhatNeverShipsFailsByName() throws Exception {
      Result result = check("""
              fix(ci): retry a download that Maven Central throttled
              feat(test): a fake Sonos speaker
              fix(deps-dev): bump c8 from 10.1.3 to 10.1.4
              fix(deps): bump org.jmdns:jmdns from 3.6.5 to 3.6.6
              ci: retry a download that Maven Central throttled
              """);

      assertThat(result.exitCode()).isEqualTo(1);
      assertThat(result.output())
              .contains("Releases an unchanged app: fix(ci): retry a download that Maven Central throttled"
                      + " (write ci: instead)")
              .contains("Releases an unchanged app: feat(test): a fake Sonos speaker (write test: instead)")
              .contains("Releases an unchanged app: fix(deps-dev): bump c8 from 10.1.3 to 10.1.4"
                      + " (write build: instead)")
              .doesNotContain("jmdns", "app: ci: retry");
  }
  ```

  Add `perf(build): …` (expecting `build:`) and `fix(docs): …` (expecting `docs:`) to the input, each with its
  `contains`.
- [ ] **Step 2: Run it to see it fail.** Run
  `scripts/gradle.sh test --tests dev.andre.homecontrol.deployment.CommitMessagesTest`. `perf:` is reported as
  unconventional, and the new test exits 0.
- [ ] **Step 3: Implement.** Add `perf` to `pattern` after `fix`, then:

  ```bash
  # A feature, fix or performance change releases the app (docs/dev/ci-and-releases.md#releases). Scoped to a part
  # that is not in the image, it would release an unchanged one, so such a change takes that part's own type.
  unshipped='^(feat|fix|perf)\((ci|test|tests|build|docs|deps-dev)\)!?: '
  ```

  In an `elif [[ "$subject" =~ $unshipped ]]` branch, a `case` maps `BASH_REMATCH[2]` to a type: `tests` → `test`,
  `deps-dev` → `build`, otherwise the scope itself. The branch prints the new line and counts it in `bad`. The final
  message names `perf`, and the scoped features and fixes it refuses.
- [ ] **Step 4: Run the tests.** Run the test from Step 2 again, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.**
  - `AGENTS.md`: list `perf:`, and the rule that `feat`, `fix` and `perf` never take `ci`, `test`, `build`, `docs` or
    `deps-dev` as scope.
  - The release table in `ci-and-releases.md`: `` `fix:`, `perf:`, `revert:` (or `Revert "…"`) `` is a patch bump,
    and `` `build:`, `ci:`, `chore:`, `docs:`, `refactor:`, `test:` `` release nothing.
  - The "Check commit messages" row: name the refused scopes.
- [ ] **Step 6: Commit.** `ci: accept perf commits and refuse fixes scoped to what never ships`

### Task 2: A breaking change below 1.0 releases the next minor

**Files:**
- Create `scripts/release-plan.sh` (`git update-index --chmod=+x`).
- Modify `.github/workflows/ci.yml`: the jar job's comment at :278-282 and its `plan` step at :303-338.
- Modify `build.gradle.kts`: add the script to `deploymentFiles`.
- Test: create `src/test/java/dev/andre/homecontrol/deployment/ReleaseWorkflowTest.java`.

**Interfaces:** `scripts/release-plan.sh` is new.
- **Reads:** `SKIPPED`, `NEXT`, `PREVIOUS`, `ON_MAIN` and `FORCE_PATCH`; unset means `false`.
- **Writes:**
  - `release` and `previous` to `$GITHUB_OUTPUT`, plus `version`, `majorminor` and `tag` when it releases;
  - one line to `$GITHUB_STEP_SUMMARY`.
- **On bad input:** exits 1 with `::error::`.

- [ ] **Step 1: Write the failing test.** A helper, `plan(String... env)`:
  - runs `bash scripts/release-plan.sh`;
  - removes the five inputs from the inherited environment;
  - points `GITHUB_OUTPUT` and `GITHUB_STEP_SUMMARY` at `@TempDir` files;
  - returns `record Plan(int exitCode, Map<String, String> outputs, String summary, String log)`.

  By default PREVIOUS=v0.21.24, ON_MAIN=true and SKIPPED=false. The tests:
  - `aBreakingChangeBelow1_0ReleasesTheNextMinor`: NEXT=1.0.0 gives `version=0.22.0`, `majorminor=0.22`,
    `tag=v0.22.0` and `previous=v0.21.24`, and the summary `Releasing v0.22.0 (was v0.21.24).`
  - `featuresAndFixesReleaseWhatTheChangelogActionProposes`: NEXT=0.22.0 and NEXT=0.21.25 release as given.
  - `nothingReleasableReleasesNothing`: SKIPPED=true gives `release=false`, no `version`, and the summary
    `No releasable commits since the last tag: nothing will be published.`
  - `aManualRunReleasesAPatchWhenNothingIsReleasable`: SKIPPED=true with FORCE_PATCH=true gives 0.21.25.
  - `aManualRunStillReleasesWhatTheCommitsAskFor`: NEXT=0.22.0 with FORCE_PATCH=true gives 0.22.0.
  - `aPullRequestOnlySaysWhatMergingWouldRelease`: ON_MAIN=false with NEXT=1.0.0 gives `release=false` and the summary
    `Merging this would release v0.22.0 (now v0.21.24).`
  - `aVersionThatIsNotAboveTheLastReleaseFailsTheRun`: NEXT=0.21.24, NEXT=1.0 and PREVIOUS=main each exit 1, with
    `::error::`.
  - `theFirstReleaseStartsFromNothing`: with PREVIOUS=v0.0.0, both NEXT=0.1.0 and NEXT=1.0.0 give 0.1.0.
  - `ciPlansEveryReleaseWithTheScript`: step `id: plan` runs the script, with `SKIPPED` and `NEXT` from
    `steps.version.outputs`.
- [ ] **Step 2: Run it to see it fail.** Run
  `scripts/gradle.sh test --tests dev.andre.homecontrol.deployment.ReleaseWorkflowTest`. The script cases exit 127,
  and the YAML case finds the inline script.
- [ ] **Step 3: Implement.** The script:
  - starts with a header that names its inputs and links `docs/dev/ci-and-releases.md#releases`, then
    `set -euo pipefail`;
  - parses `${PREVIOUS#v}` with `semver='^([0-9]+)\.([0-9]+)\.([0-9]+)$'` into `major`, `minor` and `patch`;
  - writes `previous`, then runs:

  ```bash
  if [[ "$SKIPPED" != false ]]; then
    if [[ "${FORCE_PATCH:-false}" != true ]]; then
      echo "No releasable commits since the last tag: nothing will be published." >> "$GITHUB_STEP_SUMMARY"
      echo "release=false" >> "$GITHUB_OUTPUT"
      exit 0
    fi
    NEXT="$major.$minor.$((patch + 1))"
  fi
  [[ "$NEXT" =~ $semver ]] || fail "The changelog action proposed '$NEXT', which is not a version."
  # Below 1.0 a breaking change bumps the minor; the action proposes 1.0.0 for it.
  if ((major == 0 && BASH_REMATCH[1] > 0)); then
    NEXT="0.$((minor + 1)).0"
  fi
  ```

  - **Order check:** `is_above "$NEXT" "$major.$minor.$patch"` compares the fields numerically; if it is false, the
    script calls `fail "Refusing to release $NEXT after $PREVIOUS."`.
  - **Outputs:** `TAG="v$NEXT"` and `majorminor=${NEXT%.*}`. The `ON_MAIN` branch and the summary lines stay as
    they are.
  - **`ci.yml`:** rename the step to "Decide whether to release, and as which version". Keep the `git remote set-url`
    line and run `PREVIOUS=$(git describe --tags --abbrev=0 2>/dev/null || echo v0.0.0) scripts/release-plan.sh`.
    Drop the `TAG` env, and point the comment at :278-282 to the script.
- [ ] **Step 4: Run the tests.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.deployment.*'`, then
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** The "Build the jar" row of `ci-and-releases.md` names the script. Below the release
  table, add: "Mark a breaking change with `!` or a `BREAKING CHANGE:` footer. Below 1.0 it releases the next minor,
  and the notes list it under BREAKING CHANGES."
- [ ] **Step 6: Commit.** `ci: a breaking change below 1.0 releases the next minor`

### Task 3: A maintainer can release by hand

**Files:** Modify `.github/workflows/ci.yml`: `on:`, the `env` of `plan`, "Write the release notes", and the `if` of
the `release` job. Modify `docs/dev/ci-and-releases.md`. Test: new methods in `ReleaseWorkflowTest`.

**Interfaces:** A new `workflow_dispatch` input, `force-patch` (boolean, default `true`). The notes step reads
`steps.plan.outputs.previous`.

- [ ] **Step 1: Write the failing test.**
  - `aMaintainerCanStartARunThatReleasesAPatch`: `workflow_dispatch.inputs.force-patch` is
    `{type: boolean, default: true}`. SnakeYAML reads YAML 1.1, where the key `on:` loads as `Boolean.TRUE`, so the
    test calls `workflow.get(true)`.
  - `onlyAPushOrAManualRunOnMainMayRelease`:
    - `plan` sets `ON_MAIN` to this expression. It is wrapped here, and the test compares it as one line:

      ```yaml
      ${{ github.ref == 'refs/heads/main'
          && (github.event_name == 'push' || github.event_name == 'workflow_dispatch') }}
      ```

    - `plan` sets `FORCE_PATCH` to `${{ github.event_name == 'workflow_dispatch' && inputs.force-patch }}`.
    - The `if` of `release` names `workflow_dispatch`, `refs/heads/main` and `needs.jar.outputs.release`.
  - `aManualReleaseWaitsForTheSameGatesAsAPush`: the `needs` of `release` contain `jar`, `test`, `image`, `smoke` and
    the three `e2e-*` jobs. `sonar` is left to BUILD-6.
  - `maintenanceReleaseNotesListTheCommitsSinceTheLastRelease`:
    1. In the `@TempDir`, run `git init -q -b main`. Make empty commits with `-c user.name=test`,
       `-c user.email=test@example.invalid` and `-c commit.gpgsign=false`: `fix: the last release` (tagged `v0.21.24`),
       then `build(deps): bump gradle/actions/setup-gradle from 6.0.0 to 6.1.0`, then
       `ci: retry a throttled download`.
    2. Run the step's `run` with `bash -e -c`, with `CHANGELOG=""`, `PREVIOUS=v0.21.24` and `RUNNER_TEMP` set.
    3. `notes.md` starts `Maintenance release` and lists both later subjects as `- …`, but not the first one.

    Git is in `gradle:jdk25` and on the runners.
  - `releaseNotesAreTheChangelogWhenThereIsOne`: a non-empty `CHANGELOG` is written as it is, with a newline.
- [ ] **Step 2: Run it to see it fail.** `ReleaseWorkflowTest` fails on the missing trigger and conditions, and on the
  empty notes.
- [ ] **Step 3: Implement.** Under `on:`:

  ```yaml
    # A release by hand, from the Actions page: the run builds, tests and gates as a push to main does
    # (docs/dev/ci-and-releases.md#releasing-by-hand).
    workflow_dispatch:
      inputs:
        force-patch:
          description: Release a patch even if no commit since the last release is a feature or a fix
          type: boolean
          default: true
  ```

  - **`plan`:** gets the `ON_MAIN` and `FORCE_PATCH` from Step 1.
  - **`release`:** the first line of its `if` becomes
    `(github.event_name == 'push' || github.event_name == 'workflow_dispatch')`.
  - **Notes step:** adds `PREVIOUS: ${{ steps.plan.outputs.previous }}` and runs:

  ```bash
  if [[ -n "${CHANGELOG//[[:space:]]/}" ]]; then
    printf '%s\n' "$CHANGELOG" > "$RUNNER_TEMP/notes.md"
    exit 0
  fi
  # A release started by hand with no feature or fix since the last one: say what it carries.
  range=HEAD
  if git rev-parse -q --verify "refs/tags/$PREVIOUS" > /dev/null; then range="$PREVIOUS..HEAD"; fi
  {
    echo "Maintenance release: the image is built again from main, with these changes since $PREVIOUS."
    echo
    git log --no-merges --format='- %s' "$range"
  } > "$RUNNER_TEMP/notes.md"
  ```

  A manual run skips `dependencies` and `codeql`. That is fine: they do not gate the release, the push already ran
  them, and WP-08 rewrites `dependencies`.
- [ ] **Step 4: Run the tests.** Run the deployment tests as in Task 2, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** In `ci-and-releases.md`, line 7 and the "Release" row mention manual runs. Add a
  section, `### Releasing by hand`:
  - Start one from Actions → CI → Run workflow on `main`.
  - With force-patch on, it releases at least a patch, with notes listing the commits. With it off, it is a
    rehearsal.
  - Every job runs except the dependency graph and CodeQL.
  - A push queued behind a waiting manual run replaces it. Start the run while `main` is idle, and again if it was
    replaced.
- [ ] **Step 6: Commit.** `ci: release a patch by hand from the Actions page`

After PR 1 merges, start one run on `main` with force-patch off. It must end green and publish nothing.

### Task 4: Dependabot groups its updates, waits a cooldown, and commits what ships as `fix(deps)`

**Files:** Modify `.github/dependabot.yml`, `README.md:52-53`, `docs/dev/ci-and-releases.md` and
`docs/dev/testing.md`. In `build.gradle.kts`, add `.github/dependabot.yml` and `gradle/libs.versions.toml` to
`deploymentFiles`. Test: create `src/test/java/dev/andre/homecontrol/deployment/DependabotConfigTest.java`.

**Interfaces:** No code interface changes; only the commit subjects do. Gradle and Docker updates become
`fix(deps): bump …`, and Actions and npm updates become `build(deps)` or `build(deps-dev)`.

- [ ] **Step 1: Write the failing test.** The test loads the file with SnakeYAML, which keeps the groups in order,
  and finds each entry by `package-ecosystem`. It holds these tests:
  - `whatShipsInTheImageIsCommittedAsAFix`: `gradle` and `docker` set `commit-message: {prefix: fix, include: scope}`.
  - `whatNeverShipsIsCommittedAsABuildChange`: `github-actions` and `npm` have prefix `build` with `include: scope`,
    and `npm` adds `prefix-development: build`.
  - `everyEcosystemNamesItsCommitPrefix`: every entry has a prefix. Without one, Dependabot copies recent commits and
    would start writing `fix(deps)` for Actions.
  - `everyEcosystemWaitsACooldown`: `default-days` is 5 everywhere, and `semver-major-days` is 14 for `gradle`, `npm`
    and `github-actions`.
  - `gradleUpdatesArriveInLockstepThenToolingThenRuntimeGroups`: the groups appear in this order.

    | Group | Update types | Patterns |
    | --- | --- | --- |
    | `protobuf`, `bouncycastle`, `dbus-java`, `tomcat` | `[major]` | their own |
    | `build-tooling` | `[minor, patch]` | the tooling list in Step 3 |
    | `runtime` | `[minor, patch]` | `["*"]`, excluding `gradle-wrapper` |

  - `everyBuildPluginIsInTheToolingGroup`: every catalog `[plugins]` id other than `org.springframework.boot` is a
    `build-tooling` pattern. Dependabot names plugins by their id.
  - `actionUpdatesArriveAsOneMonthlyPullRequest`: both directories, monthly, with one group matching `["*"]`.
  - `baseImagesArriveTogether`: `docker` has the single group `base-images`, matching `["*"]`.
- [ ] **Step 2: Run it to see it fail.** Run
  `scripts/gradle.sh test --tests dev.andre.homecontrol.deployment.DependabotConfigTest`. It fails on the missing
  `commit-message`.
- [ ] **Step 3: Implement.** Keep the existing comments. An update joins the first group it matches, so the order
  matters.
  - **gradle:** `commit-message: {prefix: fix, include: scope}`. Its comment says that what Gradle resolves is in the
    image or builds it, and that without a prefix Dependabot copies recent commits.
    - Add `cooldown: {default-days: 5, semver-major-days: 14}`. Its comment says this gives a bad release time to be
      withdrawn, and that security updates are not delayed.
    - The four lockstep groups get `update-types: [major]`.
    - `build-tooling` covers `com.diffplug.spotless`, `com.google.errorprone:*`, `net.ltgt.errorprone`,
      `org.sonarqube`, `org.jacoco:*`, `org.gradle.test-retry`, `com.microsoft.playwright:*`, `com.tngtech.archunit:*`
      and the `com.google.protobuf` plugin.
    - `runtime` excludes `gradle-wrapper`, whose files `publish.py`'s `ALLOWED` refuses.
  - **github-actions:** monthly, with `{prefix: build, include: scope}`, the same cooldown, and one group, `actions`,
    matching `["*"]`. The comment keeps the CodeQL reason.
  - **docker:** `{prefix: fix, include: scope}`, `{default-days: 5}`, and the group `base-images`, matching `["*"]`.
  - **npm:** `{prefix: build, prefix-development: build, include: scope}`, with Gradle's cooldown. `istanbul` stays
    as it is.
- [ ] **Step 4: Run the tests.** Run the test from Step 2 again, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.**
  - **Release table:** add a row for `` `fix(deps):` ``, a patch bump that Dependabot writes for Gradle and Docker.
    The no-release row adds that Actions and npm updates arrive as `build(deps):`.
  - **`README.md:52-53`:** "Every releasable commit on `main` is released immediately: features, fixes, and updates of
    the libraries and base images in the image. `latest` is the newest release; commits that change only CI, tests or
    documentation wait for the next one."
  - **`testing.md#dependabot-updates`:** what arrives when. Dependabot PRs are squash-merged, so a bump and its
    metadata land as one `fix(deps)` commit.
  - Ask the owner for the squash setting (decision 6).
- [ ] **Step 6: Commit.** `ci: group Dependabot updates and commit those that ship as fix(deps)`

After merging, Insights → Dependency graph → Dependabot must show no error. If an ecosystem rejects a key, drop it
there and adjust the test. Then watch the next weekly run.

### Task 5: A raise Spring Boot has caught up with fails the build

**Files:** Modify `build.gradle.kts` (the `springBootBom` configuration, and a property and an input on the test
task) and the comments at `gradle/libs.versions.toml:3-11`. Test: create
`src/test/java/dev/andre/homecontrol/deployment/VersionOverridesTest.java`.

**Interfaces:** New: the `springBootBom` configuration, and the system property `home-control.test.spring-boot-bom`,
which holds the path of the resolved `spring-boot-dependencies-<version>.pom`.

- [ ] **Step 1: Write the failing test.** Each raise is a `record Raise(String version, String bomProperty, String
  remove)`, where `remove` names the places to delete the raise from:
  - `tomcat` and `tomcat.version`: the catalog's version and its tomcat-embed libraries, and the constraints in
    `build.gradle.kts`.
  - `jackson` and `jackson-bom.version`: the catalog's version and jackson-bom, and `platform(libs.jackson.bom)`,
    twice in `build.gradle.kts` and once in `protocols/build.gradle.kts`.

  The tests:
  - `everyRaiseIsStillAboveTheVersionSpringBootManages`:
    - It reads the BOM path from the property. If the property is missing, it fails with "set by build.gradle.kts:
      run this test through Gradle".
    - It finds `<tomcat.version>` and `<jackson-bom.version>` in the BOM, and the catalog versions with
      `(?m)^<key> = "([^"]+)"$`.
    - It asserts `isAbove(raised, managed)`, with a message like "gradle/libs.versions.toml raises tomcat to 11.0.29,
      but spring-boot-dependencies-4.1.3.pom manages 11.0.30 already: the raise does nothing. Remove …".
  - `everyRaiseTheCatalogDescribesIsChecked`: the `[versions]` keys whose comment says "Spring Boot manages" are
    exactly the keys in the list.
  - `versionsCompareNumerically`:
    - `11.0.30` is above `11.0.29`, and `11.0.10` is above `11.0.9`;
    - `3.1.7` is not above `3.1.7`, and `3.2.4` is not above `3.10.0`.
- [ ] **Step 2: Run it to see it fail.** Run
  `scripts/gradle.sh test --tests dev.andre.homecontrol.deployment.VersionOverridesTest`. It fails on the missing
  property. After Step 3, a scratch edit `tomcat = "11.0.27"` shows the message; revert it.
- [ ] **Step 3: Implement.** In `build.gradle.kts`, next to the test task:

  ```kotlin
  // Spring Boot's BOM as a file, for VersionOverridesTest: a version gradle/libs.versions.toml raises above Spring
  // Boot's must stay above it, or the raise does nothing. The POM is the one the platform already resolves.
  val springBootBom = configurations.create("springBootBom") {
      isCanBeConsumed = false
      isTransitive = false
  }
  dependencies {
      springBootBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}@pom")
  }
  ```

  - **Test task:** add `systemProperty("home-control.test.spring-boot-bom", springBootBom.singleFile.absolutePath)`
    and `inputs.files(springBootBom).withPropertyName("springBootBom")`. It resolves like
    `home-control.test.runtime-classpath`, which the configuration cache already stores. If the cache objects, use a
    `CommandLineArgumentProvider` class with `@InputFiles`.
  - **Checksums:** `verifyDependencyChecksums` picks up the configuration when the task is realised. Strict `build`
    stays green with no new metadata entry.
  - **Catalog comments:** drop the "(… in Spring Boot 4.1.2)" asides. End both comments with "VersionOverridesTest
    fails once Spring Boot manages this version or later: then remove it."
- [ ] **Step 4: Run the tests.** Run the test from Step 2 again, then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** In `testing.md`, under Dependency verification: a Spring Boot update that catches up
  with a raise fails `VersionOverridesTest`. Remove the raise on that pull request.
- [ ] **Step 6: Commit.** `build: fail the build when a raised version no longer raises anything`

### Task 6: The contributor guides stop contradicting themselves

**Files:** Modify `AGENTS.md:15`, `.github/pull_request_template.md:21`, `scripts/gradle.sh:2-3`, `scripts/e2e.sh:2-6`,
`scripts/e2e.Dockerfile:1`, `docs/dev/testing.md:254-256` and `docs/dev/architecture.md:120-136`.

**Interfaces:** none.

- [ ] **Step 1: Write the failing check.** No test reads these files, since a docs-only PR builds nothing. Instead,
  `grep -rn "\.superpowers" scripts docs/dev AGENTS.md .github` and `grep -n "4\.1\.[0-9]" AGENTS.md` must both
  print nothing.
- [ ] **Step 2: Run it to see it fail.** The greps list `gradle.sh:2`, `e2e.sh:2,4`, `e2e.Dockerfile:1`,
  `testing.md:256` and `AGENTS.md:15`.
- [ ] **Step 3: Implement.**
  - **`AGENTS.md`:** "Spring Boot 4.1 (exact versions in `gradle/libs.versions.toml`)".
  - **PR template:** "`scripts/gradle.sh build` passes (`./gradlew build` with a local JDK 25)".
  - **`gradle.sh`:** "Runs Gradle inside a container image (default gradle:jdk25) for anyone without a local JDK
    25."
  - **`e2e.sh`:** for local runs only. CI's e2e-chromium, e2e-firefox and e2e-webkit jobs use
    `.github/actions/browser-tests`, which has its own JDK.
  - **`e2e.Dockerfile`, `testing.md`:** drop the copy wording.
  - **`architecture.md`:**
    - The intro says each measure is as last measured.
    - "Now" becomes "Last measured".
    - The largest-class row is measured again with `find src/main/java core/src/main/java protocols/src/main/java
      -name '*.java' | xargs wc -l | sort -n | tail -2`, and names the commit: 480 lines, `Enrollment`, 3932f9b.
  - **Kept:** `.superpowers/` stays in `.gitignore`, `.dockerignore` and Spotless's `targetExclude`, since local copies
    may remain.
- [ ] **Step 4: Run the tests.** Run the greps again, then `scripts/gradle.sh build`. The PR also builds in CI,
  because `code-changed.sh` counts scripts as code.
- [ ] **Step 5: Update docs.** This task is the docs change.
- [ ] **Step 6: Commit.** `docs: fix small contradictions in the contributor guides`

### Task 7: New checksum entries land in Gradle's order

**Files:** Modify `scripts/dependency-checksums/verify.py`: `render_additions` and `append_component_artifacts`. Test
`scripts/dependency-checksums/test_verify.py`.

**Interfaces:** A new helper, `insertion_point(text, spans, key, closing_index) -> int`. Only the position of new
blocks changes.

- [ ] **Step 1: Write the failing test.** Add a helper, `gradle_metadata(entries)`, that renders Gradle's layout, one
  element per line; `metadata()` stays. The tests:
  - `test_new_components_are_inserted_in_gradles_order`:
    - The head holds `org.example:a:1.0` and `c:1.0`.
    - The candidate adds `b:1.0`, `d:1.0`, `a:0.9`, and `org.ow2.asm:asm:9.10.1` next to `9.7.1`.
    - The output order is a 0.9, a 1.0, b, c, d, then asm 9.10.1 before 9.7.1. Gradle sorts versions as strings, as
      `verification-metadata.xml:1821/1829` shows.
  - `test_new_artifacts_are_inserted_in_name_order`: a jar added next to a pom comes first.
  - `test_insertion_keeps_every_existing_byte`: removing the inserted blocks gives back the head.
- [ ] **Step 2: Run it to see it fail.** Run
  `python3 -m unittest discover -s scripts/dependency-checksums -p 'test_verify.py' -v`. The order tests fail.
- [ ] **Step 3: Implement.**
  - **Components:** after each insertion, `render_additions` finds the component spans again, with the regex and
    `ET.fromstring` of `append_component_artifacts`. It puts each new block at the start of the line of the first
    component whose `(group, name, version)` sorts after it. If no component does, it falls back to
    `insert_before_closing`.
  - **Artifacts:** `append_component_artifacts` does the same with `<artifact name=…>`.
  - **Final check:** the check in `verify_metadata` stays as it is.
- [ ] **Step 4: Run the tests.** From the repository root, run the three commands of the `checksums` job's step "Test
  checksum verification and publication" (`ci.yml:80-87`), with `build/checksum-tests` as the venv. Add
  `-m coverage report --rcfile=scripts/dependency-checksums/.coveragerc` to see coverage. Then run
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** In `testing.md#dependabot-updates`, say that new entries go in Gradle's order, so a
  regeneration does not reorder them.
- [ ] **Step 6: Commit.** `build: insert new dependency checksums in Gradle's order`

### Task 8: A maintainer can sort and prune the metadata

**Files:** Create `scripts/dependency-checksums/prune.py` and `test_prune.py`. Modify `docs/dev/testing.md` and
`docs/adr/0008-automatic-dependency-checksums.md`.

**Interfaces:** New.
- **Usage:** `prune.py --committed PATH --output PATH [--resolved PATH --catalog PATH]`. Without `--resolved`, it
  only sorts.
- **Imports:** `parse` and `VerificationError` from `verify`, through `publish.py`'s `sys.path` line.
- **Functions:** `component_blocks(text)`, `sort_components(xml) -> bytes`,
  `prune(committed, resolved, playwright) -> tuple[bytes, list]` and `main()`.

- [ ] **Step 1: Write the failing test.** The fixtures use Gradle's layout.
  - `test_sorting_keeps_every_entry_and_its_bytes`: an unsorted file comes out sorted, every block byte-identical,
    with the same hashes.
  - `test_deletes_only_whole_components_nothing_resolves`: from A 1.0, A 2.0 and B 1.0, with A 2.0 and B 1.0
    resolved, A 1.0 goes and the report names it.
  - `test_keeps_every_protoc_platform_of_a_resolved_version`: when one of protoc 4.36.2's three `.exe` artifacts is
    resolved, all three stay, since they form one component.
  - `test_keeps_playwright_at_the_catalog_version`: with neither driver resolved and the catalog at 1.65.0, 1.65.0
    stays and 1.64.0 goes. BUILD-15 checks the e2e image's driver jars against these entries.
  - `test_refuses_to_add_or_change_trust`: a resolved artifact with another hash, or missing from the committed file,
    raises `VerificationError`, and nothing is written.
  - `test_refuses_unsafe_xml`: a DOCTYPE is refused, through `verify.parse`.
  - `test_cli_writes_the_output_and_lists_what_it_removed`: `main()` runs with a patched `sys.argv`.
- [ ] **Step 2: Run it to see it fail.** Run Task 7's Step 2 command with `-p 'test_prune.py'`. It fails with
  `ModuleNotFoundError: No module named 'prune'`.
- [ ] **Step 3: Implement.**
  - **Output:** the committed text up to the `<components>` line, then the kept blocks, unchanged and sorted by
    `(group, name, version)` as strings, then the text from the `</components>` line.
  - **Kept:** the blocks that were resolved, plus `com.microsoft.playwright` at the catalog's version.
  - **Before writing:** `parse(output)` must equal the committed artifacts of the kept components. If it does not,
    raise `VerificationError('Pruned metadata changed a kept entry')`.
  - **Report:** print `Removed <n> components:` and the list.
- [ ] **Step 4: Run the tests.** Run Task 7's Step 4 commands; coverage must stay at least 80%. Then run
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.**
  - In `testing.md`, add `#### Pruning what no build resolves` with Task 9's procedure.
    - The generation step runs unverified, like the documented regeneration, so `prune.py` refuses any differing hash.
    - Merge a prune while no Dependabot Gradle PR is open.
  - In ADR 0008, add an "(Amended …)" paragraph under Decision, as in owner decision 10.
- [ ] **Step 6: Commit.** `build: a maintainer script that sorts and prunes dependency checksums`

### Task 9: Sort, then prune, the committed metadata

**Files:** Modify `gradle/verification-metadata.xml`.

**Interfaces:** none.

- [ ] **Step 1: Write the failing check.** Sort into a scratch file, then compare it with the committed one:

  ```bash
  python3 -I scripts/dependency-checksums/prune.py --committed gradle/verification-metadata.xml \
    --output build/sorted.xml
  cmp gradle/verification-metadata.xml build/sorted.xml
  ```

- [ ] **Step 2: Run it to see it fail.** `cmp` reports a difference: the 17 sort breaks.
- [ ] **Step 3: Implement.** This takes two commits.
  1. **Sort.** Copy `build/sorted.xml` over the file. `git diff --color-moved=dimmed-zebra` must show only moves. Run
     `scripts/gradle.sh build`, then commit.
  2. **Prune.** Generate a fresh, untrusted file, then prune:

  ```bash
  rm -rf build/prune && mkdir -p build/prune
  cp gradle/verification-metadata.xml build/prune/committed.xml && rm gradle/verification-metadata.xml
  scripts/gradle.sh -g build/prune/gradle-home --project-cache-dir build/prune/project-cache \
    --write-verification-metadata sha256 verifyDependencyChecksums e2eClasses
  mv gradle/verification-metadata.xml build/prune/resolved.xml
  python3 -I scripts/dependency-checksums/prune.py --committed build/prune/committed.xml \
    --resolved build/prune/resolved.xml --catalog gradle/libs.versions.toml --output gradle/verification-metadata.xml
  ```

  That resolves CI's candidate set (`ci.yml:131`) plus the e2e classes. `git diff --numstat` must show no added
  lines; tomcat-embed-core 11.0.24, .26 and .27 go, for example.
- [ ] **Step 4: Run the tests.** Run `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` against the
  pruned file. The PR's `Verify dependency checksums` job is the final proof.
- [ ] **Step 5: Update docs.** None.
- [ ] **Step 6: Commit.** First `build: sort dependency verification metadata in Gradle's order`, then
  `build: stop trusting dependency versions no build resolves`.

## Done when

- [ ] **Every finding is resolved, or a part is left out with its reason.**
  - BUILD-1: Tasks 1-4; the digest comparison is left out (decision 1).
  - BUILD-2: Task 2.
  - BUILD-3: Task 4, and the squash setting.
  - DOCS-11: Tasks 1, 4 and 6.
  - BUILD-12: Tasks 7-9.
  - ARCH-14: Task 5.
- [ ] **CI is green.** After each PR, `scripts/gradle.sh build` and CI's Python checksum tests (coverage at least
  80%) pass on `main`.
- [ ] **A manual run works.** One started on `main` with force-patch off ends green and publishes nothing.
- [ ] **Dependabot behaves.** GitHub shows no error for `dependabot.yml`. After PR 2, the first weekly run opens at
  most one `fix(deps)` PR each for runtime, tooling and base images, plus any majors and the wrapper. The first
  monthly run opens one Actions PR.
- [ ] **Squash merging is allowed** in the repository settings.
- [ ] **No `.superpowers` reference is left:** `grep -rn "\.superpowers" scripts docs/dev AGENTS.md .github` prints
  nothing.
- [ ] **The metadata is sorted and pruned.** After PR 6, `prune.py` without `--resolved` reproduces the committed
  file exactly, the file has fewer than 574 components, and the prune commit only deletes.
- [ ] **The index is current:** this package's row in [the index](README.md) is updated.

## Out of scope

- **The classpath and digest comparison** (BUILD-1's M option) waits until tooling-only releases are a nuisance.
- **A release-PR model such as release-please.** BUILD-1 calls it a judgement call; releases stay one per commit.
- **A changelog section for dependencies.** `fix(deps)` already lists under Bug Fixes with its scope, and a preset
  file could break the rule that a `feat` bumps the minor.
- **The commons-lang3 raise** is a build-classpath raise the BOM does not manage. Its comment keeps its own removal
  condition.
- **Pruning in CI.** ADR 0008 keeps the automatic flow additive.
- **Other packages own the rest:** WP-08 has BUILD-4, BUILD-5, BUILD-6, BUILD-7, BUILD-11, BUILD-15 and BUILD-V1.
  BUILD-10 is in WP-30, DOCS-10 in WP-18, and DOCS-12 and DOCS-5 in WP-19.
- **Removing the checksum gate from the Gradle jobs** is on the analysis's "what not to do" list.
