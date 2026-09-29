# SonarCloud triage — 2026-09-29

The [main-branch quality gate](https://sonarcloud.io/dashboard?id=Yukuhu_home-control) fails on reliability and security.
All **47 open/confirmed issues** have a recommendation: **23 fix, 8 false positive, 8 accepted, 8 defer**.
The ledger records the initial review decisions. All 23 FIX recommendations are implemented in the
[follow-up below](#implementation-follow-up); SonarCloud transitions have not been applied.

The [issue ledger](2026-09-29-sonarcloud-triage-decisions.tsv) lists every key exactly once with its current
location, rule, priority, new-code membership, recommended disposition, reason and direct SonarCloud link.

## Scope and baseline

- Project: `Yukuhu_home-control`; branch: `main`; latest reported analysis: **2026-09-29 21:16:48 UTC**.
- Queried all OPEN/CONFIRMED issues, paginated up to 500; the result is 47 issues, all OPEN. Queried security
  impacts, reliability impacts, the new-code period, the quality gate, project measures and all 20 rule definitions.
- **46** issues are in the new-code period. `DataDirectory`'s record suggestion is the only older issue.
- Legacy issue types: **3 bugs, 2 vulnerabilities, 42 code smells**. Quality-impact filters also label three
  code smells as reliability findings, so the reliability query contains **6**, not just the 3 legacy bugs.
- Security hotspots: **0**, including reviewed hotspots.
- Source inspected at `d27bf6f08216cc1d3704eb661725296bdfd7c05c` on `docs/workflow-chains-2-plan`.
  Its only difference from local `origin/main` at `d48144e4d4df4a1e54cb4065a7cc1d229f370e87` is a planning
  document. The SonarCloud branch tool does not expose the analyzed commit SHA; matching timestamps alone do not prove it.

| Gate condition | Actual | Required | Result |
| --- | ---: | ---: | --- |
| New reliability rating | C (3) | A (1) | Fails |
| New security rating | B (2) | A (1) | Fails |
| New maintainability rating | A (1) | A (1) | Passes |
| New coverage | 89.8% | At least 80% | Passes |
| New duplicated lines | 0.2% | At most 3% | Passes |
| New hotspot review | 100% | 100% | Passes |

Overall coverage is 89.7%; overall duplicated lines are 0.4%. Improving coverage or duplication is not the current gate remedy.

## Recommended order

P1 means resolve the failing gate first; it does not imply that an exploit or user-visible outage has been reproduced.
P2 means straightforward cleanup or a decision worth recording. P3 means optional design/style/performance work.

| Recommendation | Issues | Action |
| --- | ---: | --- |
| FIX, P1 | 3 | Make play-sheet promise handling explicit and contain unexpected rejection paths. |
| FIX, P2 | 20 | Remove 15 unused imports; remove one unused local reported twice; extract one JSON field constant; clarify two exception-test lambdas. |
| FALSE_POSITIVE | 8 | Preserve the tested behavior and dismiss with the code-specific reasons below. |
| ACCEPTED | 8 | Keep documented exceptions and the bounded sequential coverage processing. |
| DEFER, P3 | 8 | Four stream-style suggestions, one loop-style suggestion, one record conversion and two independent-read concurrency suggestions. |
| **Total** | **47** | Every issue has exactly one recommendation. |

### 1. Resolve the gate findings

The three `javascript:S9383` issues are in `play-sheet.js:57,85,183`. The calls return promises that the caller
does not observe. Both `preview()` and `attempt()` already catch fetch/JSON failures and show errors, so these warnings
do not demonstrate broken network-error handling. The DOM updates before and after those try blocks can still reject.

Use `await` where the surrounding async handler can observe the result, and explicitly launch background work with
a rejection boundary where the caller is a synchronous event callback. `void` alone can declare intent and satisfy
this rule, but does not catch a rejection. Preserve the sequence guard for stale preview responses, the retry toast
and user-triggered retries. Audit the unflagged sheet-play click callback too; the same return-value pattern appears there.

The other five gate-related issues need dispositions, rather than blindly following the suggested edits:

| Rule and location | Recommendation | Evidence |
| --- | --- | --- |
| `kotlin:S6474`, project | ACCEPTED | Dependency verification is genuinely absent. Preserve the September 27 decision while its dependency-update workflow is unresolved. This is a real supply-chain control gap, not a false positive. |
| `docker:S6471`, `scripts/e2e.Dockerfile:4` | ACCEPTED | The image is local test tooling; `scripts/e2e.sh` uses `scripts/gradle.sh`, which supplies the invoking uid/gid with `docker run -u`. Root is used to install OS packages during the build. Direct runs without `-u` would still use root. |
| `Web:S9379`, `login.html:29` | ACCEPTED | Preserve the documented choice to autofocus the labeled password control on this single-field page. The accessibility tradeoff remains real. |
| `Web:S6845`, `dashboard.html:91` | FALSE_POSITIVE | The touchpad handles Arrow keys, Enter and Space in `touchpad.js`. Removing its tab stop would remove keyboard access. |
| `javascript:S6859`, `setup.html:331` | FALSE_POSITIVE | `/js/pwa.js` is an origin-root browser URL, not a machine filesystem import. The rule's rationale is Node filesystem portability. |

Subject to the next scan introducing no other findings, fixing the three promises and applying these five
dispositions addresses every currently open security/reliability impact. Requery the gate afterward; this review
does not claim that it passes.

Exact version pins and HTTPS do not verify the bytes of an artifact. Any future dependency-verification work should
review the generated checksums, cover build plugins and other configurations, and define how dependency updates
change the trusted metadata. Automatically accepting whatever a new download contains defeats the control.
[Gradle explains the verification mechanism](https://docs.gradle.org/current/userguide/dependency_verification.html).
[Dependabot's support request #1996](https://github.com/dependabot/dependabot-core/issues/1996) is still open,
checked through the GitHub API during this review.

Autofocus is an accepted product tradeoff: it can skip preceding context for a screen reader and open a mobile
keyboard immediately. [MDN describes those concerns](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/autofocus).
The root-relative import is supported by
[browser module resolution](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Statements/import#module_specifier_resolution).

### 2. Take the small cleanup batch

- **15 `java:S1128` findings:** every flagged file imports `java.time.Duration` without referring to the type.
- **`S1854` and `S1481` in `PairingServiceTest:47`:** one unused `AndroidTvProperties` local. One edit resolves
  both findings; check its import afterward.
- **`S1192` in `JsonFileDeviceRegistry:45`:** extract the three `"adapters"` literals to one private constant.
  Keep the persisted field name unchanged. Its legacy CRITICAL severity represents maintainability, not a secret leak.
- **Two `S5778` findings:** hoist the known-valid Duration construction out of the exception-test lambdas in
  `CastPropertiesTest:40` and `RecordingStateListenerTest:55`. The lambdas then name only the intended failure.

These are 20 issue keys but 19 distinct cleanup sites because the unused local has two findings.

### 3. Record the remaining false positives

- **Three `java:S3415` findings:** the AssertJ argument order is already correct.
  `JellyfinRouteExecutor.RETRY`, `PAUSE_STEP` and `GoogleOAuthClient.MINIMUM_POLL_INTERVAL` are the production
  values under test; independent `Duration.ofSeconds/ofMillis` calls supply the expectations. Reversing these
  assertions would make the diagnostic order worse. Use the narrowest Java declaration with a one-line reason if
  declaring the exception in code.
- **Two `css:S7924` findings:** recalculate the current selectors rather than copy the older report's icon reason.
  `app.css:68` is the drawer toggle: accent text over the alpha-composited background has contrast 9.54:1 normally
  and at least 7.17:1 at the selected-card gradient endpoints. `app.css:103` is the 4rem tile placeholder:
  conservatively composing the radial tint over the art-gradient endpoints gives at least 5.17:1.
  Both exceed normal-text AA. These calculations cover the flagged default-theme rules; they are not a browser
  audit of arbitrary artwork or every theme.
- **`docker:S6595`:** `apt-get update` and `playwright install --with-deps` are already in one RUN layer, followed
  by apt-list cleanup. Sonar cannot see the package installation inside Playwright's installer.

Together with the touchpad and browser import above, these are 8 false positives.

### 4. Keep the other deliberate exceptions

- **`java:S2143` in `ClientCertificate`:** the implementation uses Instant/Duration and converts to Date only for
  BouncyCastle's certificate builder. The source already documents it. Sonar gives no line for this finding; prefer
  its individual disposition over a broad class suppression.
- **`Web:S6819`, the play-on radio buttons:** retain the custom widget decision. Checked state, roving tabindex
  and arrow-key selection are implemented; native buttons provide Space activation. This matches the interaction
  expected by the [WAI-ARIA radio-group pattern](https://www.w3.org/WAI/ARIA/apg/patterns/radio/).
  Native inputs remain a possible future redesign.
- **Three `javascript:S9382` findings in `browser-coverage/report.mjs:34,40,45`:** the converter processes captures
  sequentially and merges into one mutable map. This bounds simultaneous conversion/JSON memory. Unbounded
  Promise.all over every raw capture would change that resource bound; concurrency is optional if a measured
  bottleneck justifies a bounded design.

Together with the dependency, e2e-image and login exceptions, these are 8 accepted recommendations.

### 5. Defer optional changes

- **Four `java:S9391` stream suggestions:** current loops are small and legible. Jackson JsonNode loops need
  iterable bridging, the provider filter must retain configured order, and YouTube's options list is appended to
  later, so replacing it with the unmodifiable result of `Stream.toList()` would break the method.
- **`java:S135` in `LegacyPropertyNames`:** its two continues express distinct skip reasons. Any rewrite must retain
  property-source precedence, legacy `shield.*` aliases and warning behavior.
- **`java:S6206` in `DataDirectory`:** record conversion changes generated equality/hashCode/toString and turns a
  storage service into a value object. Review that contract before treating this as a mechanical cleanup.
- **Two independent-read `javascript:S9382` suggestions:** `summary.mjs:100` and `browser-coverage/report.mjs:17`
  can use bounded concurrency if worth doing. Preserve sorted failure diagnostics, per-file unreadable handling
  and Map iteration order. No tooling performance bottleneck was measured here.

Keep these 8 findings open as low-priority work; they are not automatically false positives.

## Relationship to the previous triage

Ten currently open keys already appear in the
[September 27 ledger](2026-09-27-sonarcloud-backlog-decisions.tsv): five accepted exceptions and five false positives.
This review retains those dispositions, adds precise reasons for the current flagged CSS selectors, and does not
rerun the old backlog cleanup. The other 37 findings include newer rules and findings introduced by subsequent changes.

## Initial triage verification and limits

- All 47 live keys are represented once in the ledger; classifications sum to 47.
- All 46 new-code keys and all 8 gate-relevant keys have explicit recommendations.
- Reviewed source, rule descriptions, existing decisions and relevant primary documentation. Recomputed flagged
  contrast using sRGB relative luminance after alpha composition.
- At the initial triage stage, no application or test source was changed. No SonarCloud transitions or comments
  were submitted.
- The initial `scripts/gradle.sh build` attempt failed because this environment has no `docker` executable
  (exit 127). Build and browser verification subsequently used the installed Java 25, as recorded below.

## Implementation follow-up

Implemented all **23 FIX issue keys** on `fix/sonarcloud-followup`, based on
`d48144e4d4df4a1e54cb4065a7cc1d229f370e87` (`main`). The separate planning commit from the original inspection
branch is not included. The ledger remains the historical recommendation snapshot with its original locations.

- Added rejection handlers to all three reported play-sheet promise calls and the initial playback click
  callback. An unexpected preview failure explains the route failure and disables playback; an unexpected
  playback failure closes the sheet, shows a toast and releases the play button. Pin submission releases its
  button while the background preview is still pending. Existing fetch/JSON handling, stale-preview guards,
  route retries and ephemeral commands are preserved.
- Removed the 15 reported unused production imports and the unused Android TV pairing fixture reported by
  two rules, along with its now-unused import.
- Extracted the private `ADAPTERS` JSON field constant without changing the stored format.
- Moved valid Duration construction outside the two exception-test lambdas so each lambda invokes only the
  operation expected to fail.
- Added four browser regression tests covering malformed preview responses, malformed playback responses
  with a subsequent successful attempt, malformed retry responses and post-pin preview failures. The pin
  test also verifies button recovery while the preview is held pending.

Review found one new pin-button lifetime issue during implementation; removing the unnecessary await fixed
it, and the strengthened pin regression reproduced it before the correction. The final review found no
remaining defects and confirmed that all 23 FIX keys map to the changes.

### Final verification

- `./gradlew build e2eTest -Pe2eBrowsers=chromium --console=plain` passed. The Java report contains **3,048**
  tests across 355 suites, with no failures/errors and one opt-in mDNS test skipped. Chromium passed all
  **66** browser tests without retries.
- `./gradlew e2eTest -Pe2eBrowsers=firefox --console=plain` passed: **64** browser tests passed, with the two
  Chromium-only pinch-zoom tests skipped. No failures/errors or retries.
- The four new browser regressions failed on the original implementation in Chromium and Firefox
  (**8** failing runs), then passed after the fix. The targeted Java batch passed all **70** tests and the
  targeted play-sheet/pin batch passed all **20** Chromium/Firefox runs. The later pin-button review assertion
  also failed before its correction and passes in both final full browser suites.
- The combined Chromium/Firefox suite exposed an existing isolation problem in `YouTubeOAuthE2eTest`:
  the first browser creates a login password in the class's shared data directory, so the next browser's fresh
  session reaches the login page instead of setup. The isolated Firefox OAuth check passed. Final full suites
  run in separate invocations, matching CI's per-browser jobs; no OAuth implementation or test was changed.
- WebKit could not launch locally because the host lacks `libicu74` and `libjpeg-turbo8`. Four original
  play-sheet WebKit cases failed for that host-library reason before any implementation change. CI supplies
  the browser dependencies and runs WebKit separately.
- Used the installed Java 25 and the Gradle wrapper directly, which `docs/dev/testing.md` permits when a
  local JDK is available. Docker remains unavailable, so the container wrappers could not run here.
- `git diff --check` passed; the frozen architecture store is unchanged. The ledger still contains all 47
  keys exactly once, and every one of the 23 FIX keys maps to a changed source file.

No SonarCloud issue statuses were changed. The five gate-related ACCEPTED/FALSE_POSITIVE recommendations
still require their individual SonarCloud dispositions. A subsequent scan must confirm which findings close
and report the resulting gate; this change does not claim the gate passes.
