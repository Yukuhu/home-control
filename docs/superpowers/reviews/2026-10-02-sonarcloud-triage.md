# SonarCloud issue triage for October 2 2026

The [main quality gate](https://sonarcloud.io/dashboard?id=Yukuhu_home-control&branch=main) passes.
All **7 OPEN/CONFIRMED findings** have a recommendation: **2 fix, 3 accepted, 2 false positive**.
There is no urgent security or gate remediation in this snapshot. The two fixes clarify tests.

The [issue ledger](2026-10-02-sonarcloud-triage-decisions.tsv) records every issue key once, with
the source location, priority, recommendation, evidence and direct SonarCloud link.
The ledger preserves the initial recommendations. The two FIX findings are addressed in the
implementation follow-up below; SonarCloud statuses have not been changed.

## Scope and baseline

- Project: `Yukuhu_home-control`; branch: `main`; reported analysis: **2026-10-02 21:04:08 UTC**.
- Inspected clean local `main` at `683989c785cb2ba27bd588f829b18f52095ed217`.
  The connector does not expose the analyzed commit SHA; source inspection does not establish that SHA.
- Queried all OPEN/CONFIRMED findings with a 500-item page; all seven are OPEN.
  A separate new-code query returns the same seven keys.
- Sonar's legacy measures report **0 bugs, 0 vulnerabilities, 7 code smells**.
  Quality-impact queries additionally label the login autofocus and touchpad findings as RELIABILITY.
  The SECURITY-impact query returns zero; all security hotspots, including reviewed ones, total zero.
- Read all six distinct rule definitions and checked each flagged source site and prior triage decisions.

| Gate condition | Actual | Required | Result |
| --- | ---: | ---: | --- |
| New reliability rating | A (1) | A (1) | Pass |
| New security rating | A (1) | A (1) | Pass |
| New maintainability rating | A (1) | A (1) | Pass |
| New coverage | 91.3% | At least 80% | Pass |
| New duplicated lines | 0.1% | At most 3% | Pass |
| New hotspot review | 100% | 100% | Pass |

Overall coverage is 91.2%; overall duplicated lines are 0.3%.
The gate response is authoritative for its configured conditions; the two quality-impact reliability
findings do not mean this gate is failing.

## Recommendations

P2 means small, worthwhile cleanup. P3 means an individual disposition for a deliberate choice or
an analyzer limitation. None of these findings warrants P1 urgency.

| Rule and current location | Count | Priority | Recommendation |
| --- | ---: | --- | --- |
| `java:S5778`, `JellyfinSetupServiceTest.java:141,177` | 2 | P2 | FIX |
| `java:S110`, `SsapPairingException.java:4` | 1 | P3 | ACCEPTED |
| `Web:S9379`, `login.html:17` | 1 | P3 | ACCEPTED |
| `Web:S6819`, `dashboard.html:189` | 1 | P3 | ACCEPTED |
| `Web:S6845`, `dashboard.html:72` | 1 | P3 | FALSE_POSITIVE |
| `docker:S6595`, `scripts/e2e.Dockerfile:22` | 1 | P3 | FALSE_POSITIVE |

### Clarify the two exception tests

In `aSecretPersistenceFailureRevokesTheNewPasswordToken` and
`aFailedTokenRevocationPreservesTheSecretPersistenceError`, the assertion lambda calls both
`passwordRequest(...)` and `setup.connect(...)`. Move the request construction into a local variable
before the assertion so only `setup.connect(connectRequest, request)` runs inside the lambda.

The helper currently constructs a valid request, so this is test clarity rather than a demonstrated
incorrect assertion. Preserve the storage-error checks, token-redaction checks, logout-request counts
and no-persisted-connection assertions. Verify with
`scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinSetupServiceTest'`,
then `scripts/gradle.sh build`.

### Accept the exception hierarchy

The six ancestors are `SsapException → DeviceRefusedException → IOException → Exception → Throwable → Object`.
The count is accurate. Four ancestors belong to the JDK; the application layers distinguish
registration failure, SSAP refusal and a device refusal that leaves the connection healthy.

`WebOsPairing` uses the typed reason to distinguish a declined prompt from timeout/key rejection.
`WebOsSession.connect` catches pairing failure before ordinary IOException, marks the device unpaired
and stops reconnecting. Protocol tests assert each pairing reason. Flattening the hierarchy solely for
the depth threshold would remove useful type relationships.

Recommend an individual ACCEPTED disposition. If documenting the exception in source instead, use
`@SuppressWarnings("java:S110")` only on `SsapPairingException`, with a one-line reason.
Do not change the rule threshold or exclude the protocol package.

### Retain the existing accessibility decisions

The single labeled password input still uses autofocus. Preserve the ACCEPTED recommendation from
[September 29](2026-09-29-sonarcloud-triage.md): it speeds password entry, with a real accessibility
tradeoff. It can skip preceding context for screen readers and open the mobile keyboard automatically.
[MDN documents those effects](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/autofocus).
Acceptance records that choice; it does not establish that the page is fully accessible.

The play-on picker uses native buttons with `role="radio"` inside a labeled radiogroup.
`selectDevice` updates checked state and roving tabindex; `bindChoiceKeys` implements wrapping arrow
navigation and focus, while native buttons provide Space activation.
`InterfaceE2eTest.arrowKeysChooseThePlaybackDeviceAndPreviewItsRouteWithoutSendingATvKey` contains
regression assertions for selection and focus. These behaviors correspond to the
[WAI-ARIA radio-group pattern](https://www.w3.org/WAI/ARIA/apg/patterns/radio/).
Preserve the existing ACCEPTED custom-widget decision. Native radio inputs remain an optional redesign;
the rule's preference is valid, so this is not a false positive.

### Dismiss the two analyzer limitations

The touchpad is interactive: `touchpad.js` handles arrow keys, Enter and Space as device commands.
Its `role="application"`, accessible label and description accompany `tabindex="0"`.
Removing its tab stop would remove keyboard access. Preserve the FALSE_POSITIVE recommendation for
the claim that this is a noninteractive element. This review does not constitute a screen-reader audit.

The Dockerfile runs `apt-get update`, Playwright's `install --with-deps`, and apt-list cleanup inside
one RUN instruction. [Playwright documents](https://playwright.dev/java/docs/browsers#install-system-dependencies)
that this option installs OS dependencies along with browsers. The analyzer does not recognize the
package installation inside that CLI call. Preserve the FALSE_POSITIVE recommendation; no additional
apt layer or duplicate package list is needed.

## Previous decisions and initial verification

The four HTML/Docker issue keys also appear in the September 29 ledger and September 30 review.
Their recommendations remain unchanged. The exception-depth finding and two test lambdas are newer
than those reviews.

The ledger was checked against the complete live issue list and new-code list for exact membership,
unique keys, matching source locations and totals. Recommendations sum to seven.
Only this report and its ledger were added. No comments, assignments or status transitions were
submitted; the available SonarCloud connector exposes read-only issue tools.

The container helper could not run: Podman failed to set a sticky bit under the read-only
`/run/user/1000/libpod`. The documented Java 25 fallback, `./gradlew build --console=plain`, ran
but failed `spotlessMiscCheck`. Its broad file glob includes pre-existing nested `.worktrees/`
checkouts and reports formatting violations in their vendored htmx copies. It also identified an
extra final blank line in this report, which was removed. Those other checkouts were not changed.
The full build is therefore not verified green. A second `spotlessMiscCheck` attempt still included
the nested worktrees and failed on their existing files; it no longer reported this review. The report
and ledger passed direct trailing-whitespace and final-newline checks. Browser tests were not run.

## Implementation follow-up

Branch `fix/sonar-jellyfin-test-lambdas` addresses both FIX keys, `AaD0YZw3uXooXlIH9xCC` and
`AaD0YZw3uXooXlIH9xCD`. Each test constructs its valid password request before the assertion lambda,
so the lambda invokes only `setup.connect`. All existing storage-error, redaction, logout and
persisted-state assertions are preserved. No production code changed.

The existing JellyfinSetupServiceTest passed before the cleanup (14 tests). After the cleanup,
`./gradlew build --console=plain` passed with installed Java 25, as permitted by the testing guide.
Formatting checks passed and independent review found no issues. The isolated worktree does not
contain the nested checkouts that blocked the initial review's formatting check. Browser tests were
not run for this test-only change. SonarCloud must analyze the PR to confirm these two findings close.
