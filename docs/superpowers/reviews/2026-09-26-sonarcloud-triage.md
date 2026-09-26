# SonarCloud triage — 2026-09-26

Branch: `fix/sonarcloud-after-remote`, created from PR #89 head
`3a3fa1f0f2e6a53840f629313ed6e5c6b0fdb3f3`.

## Analysis baseline

The initial [main-branch issue inventory](https://sonarcloud.io/project/issues?id=Yukuhu_home-control)
contained 1,195 open or confirmed findings: 31 blocker, 209 critical, 453 major,
501 minor, and 1 informational. Main's quality gate failed its new-code reliability
and security ratings. Coverage was 84.6%, duplication 0.3%, and hotspot review 100%.

[PR #89's analysis](https://sonarcloud.io/dashboard?id=Yukuhu_home-control&pullRequest=89)
became available during this work. It has no open code issues, but its quality gate
fails because new-code coverage is 0% against an 80% threshold. The failed CI step
is Sonar analysis; the Java build/tests and Chromium/WebKit browser job passed.
The build imports Java coverage through JaCoCo. Browser tests run in a separate CI
job, and there is no JavaScript coverage collection or LCOV import configured.
That gap needs a follow-up coverage change with meaningful frontend execution data.

## First batch

The following changes address 77 findings in the saved inventory. Counts are based
on matching the diff to existing issue keys; SonarCloud must reanalyze this branch
to confirm closure and check for new findings.

| Area | Change |
| --- | --- |
| Adapter and source constants | Rename 23 constants that differ from member names only by case; preserve adapter IDs and persisted settings keys. |
| Templates | Give reusable workflow rows distinct template IDs and remove a redundant ID from a Thymeleaf replacement placeholder. |
| Controllers and WebSocket | Separate operations from fixed return values; extract the WebSocket listener into a named class so its complexity is measured separately. |
| Jellyfin startup | Split startup progression into focused methods while preserving wake confirmation, reconnect retries, deadlines, cancellation, and single playback delivery. |
| Startup tests | Replace three sleeps with explicit synchronization, add an assertion to the delayed mpv connection test, and isolate the operation expected to throw. |
| Small cleanups | Reuse the transient key-password random generator, use record patterns and unnamed catches, share repeated message keys, clamp a wait duration, and use Bash's double-bracket condition. |

## Findings requiring separate work

- **TLS certificate validation (`java:S4830`, 12 findings):** `CastTls` and
  `InsecureTls` accept self-signed LAN appliance certificates. Replacing this with
  the default CA trust store would break those connections. Peer authentication
  or certificate pinning needs a protocol-specific design, pairing/re-pairing
  behavior, and device tests, including certificate rotation. The existing Cast
  trust decision is documented in the
  [Cast sender ADR](../specs/2026-09-16-cast-sender-adr.md). These findings remain open.
- **Tizen connection ownership (`java:S2095`):** the factory's connection object
  acquires no resource before the WebSocket open succeeds. Pairing uses
  try-with-resources; the session closes failed attempts and retained connections.
  The reported factory leak appears to be a false positive. No suppression or
  issue-status change was made.
- **Concurrency (`java:S3077`, 37 findings):** review each reference's ownership and
  mutation before changing `volatile` declarations. Immutable snapshots and
  mutable shared objects need different treatment.
- **Remaining complexity and test cleanup:** prioritize device-session and workflow
  control flow, then process the larger mechanical categories in reviewable batches.

## Validation

- 225 targeted Java tests across 29 classes passed, covering affected adapters,
  settings consumers, storage, sources, controllers, startup behavior, and network
  helpers.
- 9 Chromium browser tests passed across workflow editing, YouTube OAuth, and the
  mobile remote overlay.
- All 9 `TextWebSocketTest` tests passed again after the final listener extraction.
- `git diff --check` and `bash -n scripts/e2e.sh` passed.

This branch has not yet been analyzed by SonarCloud; no claim is made that its
quality gate passes or that the entire backlog is resolved.
