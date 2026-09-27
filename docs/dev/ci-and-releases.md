# CI and releases

What runs on every push and pull request, and how releases are made.

## CI quality gate

The `SonarCloud quality gate` job scans the results of the `Build and test` and
`Browser tests (Chromium)` jobs with SonarCloud. The browser tests run in one job for each
browser; the scan waits for Chromium alone, the one browser that records which JavaScript
ran. On pull requests, it waits for the quality gate, so a failed scan, failed gate, or
missing `SONAR_TOKEN` fails the job. Main-branch
scans update the analysis baseline without waiting for the gate. Keep `SONAR_TOKEN` in both
the GitHub Actions and Dependabot secret stores. GitHub does not provide that secret to fork
pull requests, so their build stays red until a separate scan path is configured.

`main` requires one check, `CI passed`. It passes only if every other job passed or was left
out on purpose, so a job is enforced by adding it to that job's `needs` in
`.github/workflows/ci.yml`, not in the repository's settings.

A pull request that changes only documentation builds and tests nothing, and `CI passed`
passes for it. Documentation is what `scripts/code-changed.sh` lists: `docs/`, the Markdown
files at the top of the repository, the licence, and the issue and pull request templates.
A push to `main` always runs every job.

After every pull request run, CI comments the results on the pull request: the test counts
of both suites, each failed test with its message, the quality gate with the conditions that
failed, and the image jobs. Each run replaces the comment of the previous one, and the
comment names the commit it belongs to. Pull requests from forks get no comment. The comment
is written by `scripts/pr-summary`; run its tests with `npm ci && npm test` in that
directory. CI runs them with coverage, which SonarCloud counts like the coverage of the
dashboard's JavaScript.

## Releases

Versions are derived from [conventional commit](https://www.conventionalcommits.org)
messages, and a push to `main` releases automatically:

| Commit type | Effect |
|---|---|
| `feat:` | Minor bump |
| `fix:`, `perf:` | Patch bump |
| `feat!:` or `BREAKING CHANGE:` | Minor bump, because this project is still pre-1.0 |
| `docs:`, `ci:`, `chore:`, `test:`, `refactor:` | No release |

A release builds the jar with that version, publishes the multi-arch image, then
creates the tag and the GitHub release from the generated changelog — in that
order, so a failed build never leaves a tag pointing at an image that was never
pushed.

The image that is published is the image that was tested. The `Build the jar` job
builds the one jar of a run. The `Smoke-test the image on amd64` and `… on arm64`
jobs each build the image from it, natively on a runner of that architecture, and
start it; for a release they then push that image without a tag. Once every other
check has passed, the `Release` job gives those two images the tags of the release,
as one multi-arch image. It builds nothing itself.

`Smoke-test the Bluetooth image on …` does the same for the `-bluetooth` variant,
which `Release the Bluetooth image` publishes after the release, without holding it
up. A variant that fails its smoke test is not published, and `latest-bluetooth`
stays at the release before.

Every published image carries an attestation, signed by GitHub, of the workflow run and the
commit it was built from, and a list of what it contains. To check an image before running it:

```bash
gh attestation verify oci://ghcr.io/yukuhu/home-control:latest --repo Yukuhu/home-control
```

Pull requests run the same jobs without pushing anything, and the `Build the jar`
job says in its summary which version merging would release. The same check runs
against a local build on any Linux Docker host, a Raspberry Pi included:

```bash
docker build -t home-control:smoke . && scripts/smoke-test-image.sh home-control:smoke
```
