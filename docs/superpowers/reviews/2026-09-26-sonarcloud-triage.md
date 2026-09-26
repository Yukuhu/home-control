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
The combined branch now adds execution-based browser coverage and imports it in CI.

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

## Combined cleanup batch

The combined diff addresses another 813 inventory keys, for **890 of 1,195**
findings (74.5%) across the combined branch. These are diff-matched candidates, not a
claim that SonarCloud has closed them. No findings were suppressed or marked
resolved through the API.

| Scope | Additional inventory keys | Changes |
| --- | ---: | --- |
| Production Java outside adapters | 234 | Unnamed catches, repeated literals, record patterns, and focused workflow validation/template/controller complexity reductions. |
| Device adapters | 195 | Protocol listener ownership, atomic Cast connection generations, constants, record patterns, and explicit no-op/cleanup behavior. |
| Java tests | 362 | Isolate throwing operations, strengthen AssertJ checks, remove unused bindings/imports, and clarify shadowed fixture fields. |
| Browser scripts and templates | 22 | Simplify conditionals, use sets for membership, remove nested templates, and use native output/list elements. |

Chromium browser sessions collect V8 execution ranges and the corresponding
script source across navigation. A tested V8-to-Istanbul converter merges sessions,
rejects coverage from a different source revision, and includes unvisited scripts
as uncovered. CI waits for Java and browser tests, downloads JaCoCo and LCOV into
the analysis job, then runs Sonar. WebKit still runs without Chromium-specific
instrumentation. Service-worker execution is not captured by the page target, so
`sw.js` correctly remains uncovered. Existing coverage thresholds are unchanged.

## Shield connectivity investigation

The investigation exposed three backend defects, also addressed on this branch:

- Pairing rejected the physical Shield's certificate because its X.509 self-signature
  is a one-byte placeholder. The self-signature check was introduced in the earlier
  TLS-validation change, commit `4a2ca31`. Bootstrap now checks the certificate and
  RSA key are present, leaving proof of key possession to TLS and device authentication
  to the on-screen pairing code. The fingerprint is still saved only after successful
  pairing; subsequent pinned connections still reject a different certificate.
- A successful TLS socket was clearing the authentication retry counter before
  the Remote v2 handshake completed. A subsequent reader-thread rejection then
  restarted the count at `1/5` forever. Sessions now wait for the configure/active
  exchange before reporting `CONNECTED` and resetting retry state. Attempt-specific
  callbacks prevent an old connection from updating a newer attempt.
- mDNS initialization stopped when the Docker hostname could not resolve.
  Discovery now falls back to an active multicast-capable IPv4 interface, preferring
  a private LAN address. An explicit `net.mdns.interface` setting retains priority.

Pairing failures now retain the actual TLS exception type/message in the page
error, and the setup controller logs the cause. Existing certificate pins, accepted
TLS versions, and stored identities are preserved. Only unpinned server bootstrap
stops requiring a valid X.509 self-signature, which does not authenticate device
identity. Legacy command connections without a saved fingerprint already lacked
server identity authentication; migrating those devices remains separate work.

The user's manual pairing attempt also failed on port 6467, while the Google TV
phone remote connected. Reverting the container to 0.7.0 with the same data also
failed. The 0.7.0-to-0.7.1 code diff contains only frontend/browser-test changes.
The subsequent TLS trace confirmed successful TLS 1.3 negotiation followed by a
peer `certificate_unknown` alert after the client certificate was sent. It arrived
on the remote reader thread, directly matching the retry-counter regression.

Running the application locally against the physical Shield reproduced the separate
pairing failure: `SignatureException: Bad signature length: got 1 but was expecting 256`.
After the fix, a fresh local identity completed the pairing handshake on port 6467
and reached the six-character code-entry form. A copied existing local pairing also
reached `CONNECTED` on the command channel. Both checks used temporary data directories;
the deployed container's credentials were not replaced. Completing a new pairing
still requires the TV's code, and recovery of the deployed container is not claimed.

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
- **Remaining complexity and test cleanup:** timing-sensitive sleeps, larger protocol
  state machines, and API parameter lists need individual review rather than mechanical
  rewrites. Existing exact-byte protocol fixtures and intentional no-op implementations
  are not changed merely to reduce the issue count.
- **Build metadata:** version-catalog migration and dependency verification/locking
  are deferred while prioritizing the reported connectivity failure. They need clean
  build and dependency-update workflow validation.
- **Context-dependent findings:** fragment-only HTML files do not need document titles;
  a focusable keyboard touchpad needs its tab stop. Arbitrary LAN device addresses are
  deliberate inputs to the fixed-port Android TV pairing protocol. These were reviewed
  without adding suppressions or globally restricting device addresses.

## Initial batch validation

- 225 targeted Java tests across 29 classes passed, covering affected adapters,
  settings consumers, storage, sources, controllers, startup behavior, and network
  helpers.
- 9 Chromium browser tests passed across workflow editing, YouTube OAuth, and the
  mobile remote overlay.
- All 9 `TextWebSocketTest` tests passed again after the final listener extraction.
- `git diff --check` and `bash -n scripts/e2e.sh` passed.

## Combined branch validation

- `./gradlew build e2eTest -Pe2eBrowsers=chromium -Pe2eCoverage=true --console=plain`
  passed, including the full Java suite and all 46 Chromium tests.
- After the final TLS bootstrap change, 43 focused Android TV tests passed across
  the session, TLS, pairing, and remote connection classes; `bootJar` also passed.
  The dummy-signature TLS regression test failed on the old verifier and passed
  after the fix. Existing pin-mismatch rejection remains covered.
- All three browser coverage converter tests passed. The complete Chromium run
  produced 89.81% JavaScript line coverage (741/825 lines) and 93.59% branch coverage.
- The physical-device checks above verified the failure and corrected bootstrap
  behavior beyond the fake-device regression tests.
- CI runs both Chromium and WebKit and imports Java test results, JaCoCo, and LCOV
  before the SonarCloud quality gate.

This branch has not yet been analyzed by SonarCloud; no claim is made that its
quality gate passes or that the entire backlog is resolved.
