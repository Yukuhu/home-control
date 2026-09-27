# Pull request CI summary comment

Date: 2026-09-27

Status: Accepted by the user on 2026-09-27.

## Purpose and agreed scope

CI runs six jobs on every pull request: the unit and integration suite, the
browser suite, the SonarCloud quality gate and three image builds. None of them
reports back to the pull request. Finding out what broke means opening the
failed job and reading its log.

After each pull request run, CI posts one comment that aggregates the results:
test counts per suite, the failed tests with their messages, the SonarCloud
quality gate with the conditions that failed, and pass or fail for each image
job. The reader should learn what broke without opening a job log.

The user confirmed these decisions:

- The comment works for pull requests from branches of this repository,
  including Dependabot's. Pull requests from forks get no comment. They cannot
  pass CI today either, because `SONAR_TOKEN` is unavailable to them.
- The SonarCloud section shows the gate status, the failed conditions with
  actual value and threshold, and a link. It is one line when the gate passes.
- A rerun or new push deletes the previous comment and posts a fresh one. The
  previous comment stays until the new one is ready, so the pull request always
  has exactly one. The comment names its commit and run so that a result from
  an earlier commit is recognisable.
- A run cancelled because a newer push superseded it posts nothing.

Outside this version: comments on fork pull requests, a placeholder while CI
runs, a full SonarCloud metrics table, coverage figures beyond failed gate
conditions, check-run annotations on changed lines, and any reporting on pushes
to `main`.

## Approach

A Node module in `scripts/pr-summary/` renders the comment as Markdown. A new
final job in `.github/workflows/ci.yml` runs it, then replaces the comment with
the `gh` CLI that runners ship with.

Two alternatives were rejected. A sticky-comment action would save about 15
lines of shell, but it hands `pull-requests: write` to a third-party action for
something `gh` does in two calls. Off-the-shelf test reporters and SonarCloud's
own decoration produce several comments in different formats, do not cover the
image jobs, and update in place rather than replacing.

## Changes to existing jobs

`test` uploads `build/test-results/test/TEST-*.xml` as artifact `junit-test`
with `if: always()`. The existing `java-analysis` artifact stays as it is; it
also carries the JaCoCo report, requires its files to exist, and is only
uploaded on success.

`e2e` uploads `build/test-results/e2eTest/TEST-*.xml` as artifact `junit-e2e`
with `if: always()`.

Both new uploads use `if-no-files-found: ignore`, because a compile error
produces no XML and must not add a second failure.

`sonar` keeps `needs: [test, e2e]` and therefore stays skipped when a suite
fails. Analysing a broken build would report misleading coverage.

`e2e` also runs the summary script's tests with coverage and uploads the LCOV
file as artifact `pr-summary-coverage`. `sonar` downloads it and lists it in
`sonar.javascript.lcov.reportPaths`. The script is new code that SonarCloud
analyses, so without its coverage the pull request that introduces it fails
the gate's condition on coverage of new code.

## The `pr-summary` job

```yaml
pr-summary:
  name: Summarise the run on the pull request
  needs: [test, e2e, image, image-arm64, image-arm64-bluetooth, sonar]
  if: >-
    !cancelled()
    && github.event_name == 'pull_request'
    && github.event.pull_request.head.repo.full_name == github.repository
  runs-on: ubuntu-latest
  permissions:
    contents: read
    actions: read
    pull-requests: write
```

`actions: read` lets the job read this run's start time and the links to its
jobs.

`!cancelled()` makes the job run when earlier jobs failed or were skipped, but
not when the run was cancelled. No other job's permissions change.

`release` does not list `pr-summary` in its `needs`, so the summary can never
gate a release.

Steps:

1. Check out the repository.
2. Set up Node 24 with the npm cache keyed on
   `scripts/pr-summary/package-lock.json`.
3. Download `junit-test` and `junit-e2e` into separate directories, each with
   `continue-on-error: true`, since either may be absent.
4. Run `npm ci --ignore-scripts` and `npm test` in `scripts/pr-summary`.
5. Run the script. It writes `summary.md`.
6. Delete the previous comment and post `summary.md`.

Inputs reach the script as environment variables, never interpolated into
shell text:

| Variable | Source |
|---|---|
| `NEEDS_JSON` | `${{ toJSON(needs) }}` |
| `HEAD_SHA` | `${{ github.event.pull_request.head.sha }}` |
| `PR_NUMBER` | `${{ github.event.pull_request.number }}` |
| `RUN_URL` | Server URL, repository and run id |
| `RUN_NUMBER` | `${{ github.run_number }}` |
| `RUN_STARTED_AT` | The run's `run_started_at`, read with `gh api` in a preceding step; the duration is the time from then until the script runs |
| `JOBS_JSON` | This run's jobs, read with `gh api` in the same step, for the per-job log links |
| `SONAR_TOKEN` | `${{ secrets.SONAR_TOKEN }}` |
| `SONAR_PROJECT_KEY` | `Yukuhu_home-control` |

### Replacing the comment

The comment starts with the hidden marker `<!-- home-control-ci-summary -->`.

The job lists the pull request's comments with pagination, selects those whose
body starts with the marker and whose author is `github-actions[bot]`, deletes
each of them, and then posts the new comment. Checking the author means nobody
can have their own comment deleted by pasting the marker into it. Deleting all
matches, not only the first, also cleans up after a run that posted and then
failed to delete.

Deletion comes before posting. If posting then fails, the pull request has no
summary until the next run, which is preferable to two.

### Failure behaviour

The job never turns a green run red for a reporting problem. The steps that
generate and post the comment use `continue-on-error: true`, and a failure
emits a `::warning::` annotation. The `npm test` step is the exception: a
broken summary script should fail the pull request that broke it.

## The comment

### Layout

```markdown
<!-- home-control-ci-summary -->
## ❌ CI failed
`1277414` · [run #413](…) · 9m 40s

| Check | Result |
|---|---|
| Unit and integration tests | ❌ 2 failed, 840 passed, 3 skipped |
| Browser tests (Chromium, WebKit) | ✅ 96 passed |
| SonarCloud quality gate | ⏭️ not run, because tests failed |
| Image (amd64) | ✅ |
| Image smoke test (arm64) | ❌ [job log](…) |
| Bluetooth image smoke test (arm64) | ✅ |

### Failed tests

#### Unit and integration tests

**AndroidTvSessionTest** › `reportsInferredPlaybackAfterLaunch`
…message in a code block…
<details><summary>Stack trace</summary> … </details>
```

The heading is `✅ CI passed` when every job in `needs` succeeded, otherwise
`❌ CI failed`. A green run consists of the heading, the commit line and the
table only.

When the gate fails, a `### Quality gate` section lists each failed condition
as metric name, actual value and threshold, followed by a link to the pull
request's analysis on SonarCloud.

### Rules

| Case | Behaviour |
|---|---|
| More than 10 failed tests in a suite | Failures are grouped under a sub-heading per suite. The first 10 of each suite are listed, followed by "and N more" linking to the run |
| Long failure message | Cut to 300 characters with an ellipsis |
| Long stack trace | Cut to 30 lines |
| Suite job failed and produced no XML | Row reads "❌ failed before tests ran" and links to the job log |
| Suite job succeeded and produced no XML | Row reads "✅" without counts |
| Suite job failed although every test passed | Row shows the counts, "but the job failed" and a link to the job log |
| A result file cannot be read, for instance because it is truncated | The other files still count; the row adds "1 result file unreadable" |
| Failure without a message | The first line of its trace is used; without a trace, "No failure message" |
| Non-test job failed | Row links to that job's log |
| Job skipped | Row reads "⏭️ not run" |
| Job cancelled or timed out while the run continued | Row reads "❌ cancelled" and links to the job log |
| `sonar` skipped after tests failed | Row reads "⏭️ not run, because tests failed" |
| `sonar` skipped after a suite job failed without a failed test | Row reads "⏭️ not run, because an earlier job failed" |
| Gate details cannot be fetched or are stale | Row shows the job's own result with "details unavailable" and a link to SonarCloud |
| Rendered comment exceeds 60 000 characters | Stack traces are dropped, then the failure list is shortened until it fits |

Errors and failures in the JUnit XML both count as failed.

### Untrusted text

Test names, failure messages and stack traces come from test output. They are
rendered inside fenced code blocks or inline code. The script picks a fence
longer than any backtick run in the content, so content cannot close the block
early. Text placed outside code, such as class names, has Markdown and HTML
special characters and `@` escaped, so it cannot produce markup or mentions.

## SonarCloud gate details

The script queries SonarCloud only when the `sonar` job result is `success` or
`failure`.

It requests `api/qualitygates/project_status` for the project key and pull
request number, with `SONAR_TOKEN` as a bearer token and a 10 second timeout.
The response provides the status and the conditions with `metricKey`,
`actualValue`, `errorThreshold` and `comparator`. Known metric keys map to
readable names, such as `new_coverage` to "Coverage on new code"; unknown keys
are shown as they are.

To avoid presenting an earlier analysis as this run's result, the script also
requests `api/project_pull_requests/list` and uses the gate details only when
the commit recorded for this pull request matches this run. SonarCloud records
the pull request's head commit, not the merge commit that Actions checks out;
this was confirmed against the analysis of pull request 105. The script
therefore compares against `github.event.pull_request.head.sha`. If the commits
differ, or SonarCloud has no analysis for the pull request, the details count
as unavailable.

Both endpoints answer without authentication while the project is public. The
token is sent when present, so the script keeps working if that changes.

Any HTTP error, timeout or unexpected response shape also counts as
unavailable. The `sonar` job remains the actual gate; the comment only
explains it.

## The script

`scripts/pr-summary/` follows `scripts/browser-coverage/`: a private ES module
package requiring Node 22 or later, tested with `node --test`.

```text
scripts/pr-summary/
  package.json
  package-lock.json
  summary.mjs
  summary.test.mjs
  fixtures/
```

| Unit | Responsibility | Depends on |
|---|---|---|
| `parseJUnit(xml)` | One XML document to counts and a list of failures | An XML parser |
| `collectSuite(directory)` | Reads every `TEST-*.xml` in a directory and sums the results; reports whether any file existed | Filesystem, `parseJUnit` |
| `fetchGate(options, fetch)` | The two SonarCloud requests and the staleness check; returns status and failed conditions, or unavailable | An injected `fetch` |
| `render(model)` | Model to Markdown, including truncation and escaping | Nothing |
| `buildModel(inputs)` | Environment, suites and gate to the model | Nothing |
| `main` | Reads the environment, calls the units, writes `summary.md` | All of the above |

`render` is pure. Everything the comment shows is in the model passed to it.

`fast-xml-parser`, pinned to an exact version like every other dependency in
this project, parses the XML. It is a development dependency of this module
only and does not reach the application.

## Testing

| Unit | Cases |
|---|---|
| `parseJUnit` | All passed; failures; errors; skipped; empty suite; message containing backticks, HTML and `@`; malformed XML |
| `collectSuite` | Several files summed; empty directory; missing directory |
| `fetchGate` | Gate passed; gate failed with conditions; commit mismatch; HTTP error; timeout; unexpected shape |
| `render` | Green run; failed tests; failed gate; failed image job; suite without XML; skipped `sonar`; gate unavailable; more than 10 failures; oversized comment; escaping of hostile text; marker on the first line |

Fixtures are real `TEST-*.xml` files taken from this project's two suites, plus
hand-edited variants for the failure cases.

Replacing the comment cannot be tested locally. It is verified on the pull
request that introduces this change:

1. The first run posts one comment.
2. A rerun leaves exactly one comment, with a new comment id.
3. A commit with a deliberately failing test produces a red comment that names
   the test, and shows SonarCloud as not run. The commit is then removed.
4. A push that supersedes a running run yields one comment, from the newer run.

## Documentation

The README's "CI quality gate" section gains a short paragraph stating that CI
comments its results on the pull request, replaces the comment on each run, and
does not comment on fork pull requests. The same section currently says the
`Build and test` job scans with SonarCloud; the scan runs in the separate
`SonarCloud quality gate` job, and the paragraph is corrected while it is being
edited.
