# Theme packages implementation review

Scope: the accepted [design](../specs/2026-10-02-theme-packages-design.md) and
[implementation plan](../plans/2026-10-02-theme-packages.md), based on `2b7662c`.

## Outcome

Default and Cyberpunk use the same package/compiler contract as imported themes. Both IDs remain protected on
every mutation path. Appearance supports review, install/update, export and removal; selection remains local to
each browser. Recovery always uses Default, including its login page. The author guide documents the supported
token and CSS vocabulary.

The independent review has no remaining actionable findings. Final verification results are recorded below.

## Findings and resolutions

- Decorative pseudo-elements could intercept clicks if their author omitted `pointer-events`. The app's protected
  accessibility layer now disables pointer events on `::before` and `::after`; a browser regression verifies the
  actual hit target and successful link activation through an otherwise full-screen overlay.
- A second `font-family` descriptor could bypass the font namespace check. Duplicate font-face descriptors are
  rejected, with a regression using a final unnamespaced family.
- Colour functions were checked by name without validating their arguments. Typed colour tokens now accept
  validated numeric RGB/HSL forms; malformed values such as `rgb(banana)` fail. Typed length arithmetic is also
  checked. Decorative CSS retains its separately documented function vocabulary.
- Evicting one Cyberpunk font invalidated the entire offline shell. Runtime availability now requires the base
  shell and Default independently, and advertises other themes only when their complete assets survive. Browser
  regressions cover both built-in and custom font eviction.
- Firefox could refresh its catalog while the initial theme stylesheet was loading, restarting that load and
  losing startup fallback context. An unchanged pending revision is now preserved; the stalled-load regression
  explicitly refreshes the catalog during startup.

Integration checks also led to a lexical CSS depth guard that ignores comments and strings, strict duplicate and
trailing JSON rejection, bounded abandoned import reviews, and stable source digests separated from compiled
public revisions. An upgrade recompiles verified source without invalidating its installation.

## Verification

- The unchanged baseline build passed using the installed Podman runtime.
- Initial tests failed for missing package classes, resources and routes before implementation.
- Focused package and web tests passed before the final review additions. Adversarial regressions demonstrated
  the original deep-nesting, JSON and namespace failures before their fixes.
- The first full application run completed 3,206 tests with one failure in a new layout test: a fresh installation
  correctly redirected Home to Setup. The test now registers a device before checking the dashboard's head.
- After integrating the `protocols` module from `main`, `scripts/gradle.sh build e2eClasses` passed under Podman.
  Application: 2,911 tests, zero failures/errors and one skipped local-network mDNS test; core: 223 tests;
  protocols: 314 tests. Both library modules had zero failures/errors/skips.
- Final `scripts/e2e.sh --tests '*ThemeE2eTest' --tests '*OfflineUiE2eTest' --tests '*LoginGatingE2eTest'
  --tests '*InterfaceE2eTest'`: 105 tests passed across Chromium, Firefox and WebKit, with zero failures or skips.
- Executable JAR smoke test: started with temporary data and all device/content modules disabled, served both
  protected catalog entries, their compiled CSS/fonts and Appearance successfully, then shut down.
- `git diff --check`, both JavaScript syntax checks and shell syntax checks passed.

Screenshots and Playwright traces are local review artifacts under `build/e2e-artifacts/`. The review covers
Default and Cyberpunk on login, offline, workflow, dashboard, remote and setup surfaces, plus an imported
Cyberpunk derivative on Appearance. Package validation does not certify the visual quality of arbitrary themes.

## Maintenance notes

The ph-css dependency's 26 new artifact checksums were independently checked against Maven Central, preserving
the existing verification metadata. No architecture rules were weakened. Local build helpers support Docker and
Podman; `HC_CONTAINER_RUNTIME=podman` explicitly selects Podman.

PNG/JPEG content is decoded with ImageIO. Static WebP uses RIFF/frame-header and dimension checks; WOFF2 uses
signature and bounded header validation. Both formats are decoded by the browser when used. Animated WebP is
outside the version 1 contract.

## Pull request analysis follow-up

CodeQL's cleanup-path findings led to explicit normalized containment checks, ID/revision validation at the
storage boundary, and rejection of symlink ancestors during reclamation. A regression reproduced removal
following a substituted package-directory symlink before the fix and now verifies the unrelated files survive.

Sonar's reliability findings led to content-based asset value equality and an identity-based private pending
review class. Long ZIP paths now fail before regex evaluation; the regression reproduced a stack overflow in
the earlier validator. Parser helpers retain the original validation vocabulary and depth bounds, with tests for
manifest/ZIP metadata, image/font headers, selectors, namespaces and each token type. No architecture rules were
weakened. The documented 10 MiB upload contract has one method-level `java:S5693` exception, with explicit disk,
request and retained-review bounds verified by web tests.

Browser review also covers authenticated reverse-proxy cookies, removal of cached themes, failed downloads of
updated revisions, and message-origin checks. Coverage now collects the service worker's separate V8 isolate;
the report rejects a run that captures pages but no worker execution. The converter's five tests pass and retain
unexecuted worker lines as uncovered. An existing cache test used an asynchronous Playwright polling predicate
that could return before publication; it now waits for the evaluated boolean result.

After the backend corrections, `scripts/gradle.sh build` passed under Podman: 3,161 application tests (one existing
local-network skip), 223 core tests and 314 protocol tests, with zero failures or errors.

The selected theme, offline, login and interface browser suites passed all 123 distinct cases across Chromium,
Firefox and WebKit with coverage enabled. A slow-font test needed one Firefox retry because it assumed both the
page and worker would request the same font weight; its waiter now observes the worker request directly. That
corrected test passed in all three browsers with no retries or skips. The exact-source-checked LCOV report records
300/300 service-worker lines and 133/175 branches, replacing the earlier missing-worker coverage.
