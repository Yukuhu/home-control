# Dynamic workflows acceptance — 2026-09-22

The automated checks exercise the real application with a loopback JSON fixture and a recording device adapter. They do not prove that a physical receiver can fetch and decode a household's media URL. The separate hardware checklist below remains open.

## Automated acceptance

| Check | Command | Result |
| --- | --- | --- |
| Real MVC Save, login, tile, preview, Test, generated refresh, Play and no-Cast failures | `./gradlew --offline --console=plain test --tests '*WorkflowEndToEndTest'` | Passed: 3 tests, 0 failures/errors/skips |
| Full Java build and test suite | `./gradlew build` | Passed in 5m54s: 2,354 tests, 0 failures/errors, 1 skipped |
| Chromium and WebKit workflow, play sheet, login and responsive UI | `./gradlew e2eTest --tests '*WorkflowE2eTest' --tests '*PlaySheetE2eTest' --tests '*LoginGatingE2eTest' --tests '*InterfaceE2eTest'` | Passed in 31s: 42 cases, 0 failures/errors/skips (Workflow 4, Play sheet 8, Login 6, Interface 24) |
| Whitespace and patch integrity | `git diff --check` | Passed |

After independent review found that Spring 7 could bind normalized HTTP headers into omitted form fields, header binding was disabled at the workflow editor binder. The focused regression first failed because `Enabled: true` overrode an omitted unchecked `enabled` parameter, then passed after the fix. `./gradlew --offline --console=plain test --tests '*WorkflowSetupControllerTest' --tests '*WorkflowEndToEndTest'` passed 32 tests with no failures/errors/skips, and the two-engine `WorkflowE2eTest` run passed 4 cases with no failures/errors/skips. The full build above was not repeated for this bounded correction.

The browser command used Playwright 1.63.0's matching temporary Chromium revision 1243 and WebKit revision 2359 binaries. `PLAYWRIGHT_BROWSERS_PATH` pointed to `.superpowers/sdd/2026-09-22-dynamic-workflows/browsers`. For the temporary unpacked Ubuntu 24.04 WebKit runtime, `LOCAL_LIB` pointed to `.superpowers/sdd/2026-09-22-dynamic-workflows/ubuntu-runtime/root/usr/lib/x86_64-linux-gnu`, `PLAYWRIGHT_LOCAL_RUNTIME_LIB` and `LD_LIBRARY_PATH` were `$LOCAL_LIB:$LOCAL_LIB/blas:$LOCAL_LIB/lapack`, and `GST_PLUGIN_PATH_1_0` was `$LOCAL_LIB/gstreamer-1.0`. Browser execution needed the nonrestricted process sandbox for Chromium/WebKit IPC. These plan-local files are temporary and are not part of the product. A clean environment can reproducibly provision both matching browsers and system dependencies with `./gradlew installPlaywrightBrowsers`, then run the command above. Ordinary `build` stays independent of Playwright.

Synthetic visual artifacts from the workflow browser test:

- `/tmp/workflow-editor-390-chromium.png` and `/tmp/workflow-editor-1440-chromium.png`
- `/tmp/workflow-dashboard-390-chromium.png` and `/tmp/workflow-dashboard-1440-chromium.png`
- Equivalent `webkit` files after the two-engine run

The five identically titled “Flaky rail” sections in dashboard screenshots are intentional, distinct browser-test fixture rails with IDs `e2e/flaky` through `e2e/flaky-5`. The workflow browser scenario checks that all five `data-rail` values are unique; their shared title is not duplicate rendering of one rail.

## Final review fixes — amended-code evidence

The preceding full-build and 42-case browser results describe the pre-fix implementation
(`d686d29`); the binder correction was verified separately at `61d5d13`. The combined final
review fixes were then verified on the amended code with:

```bash
./gradlew --offline --console=plain test --tests '*Workflow*' \
  e2eTest -Pe2eBrowsers=chromium,webkit \
  --tests '*WorkflowE2eTest' --tests '*PlaySheetE2eTest' \
  --tests '*LoginGatingE2eTest' --tests '*InterfaceE2eTest'
```

Result: **189 workflow unit/MVC tests and 44 browser cases passed**, with zero failures,
errors or skips, in 49 seconds. Browser coverage comprises Workflow 6, Play sheet 8,
Login 6 and Interface 24 across Chromium and WebKit. This run used the same local
Playwright browser/runtime environment described above. The baseline full build was
not repeated: the fixes were confined to workflow parsing, editor/results and Setup,
and the amended suites exercised those changes and their surrounding UI integrations.

The initial 61-case focused Java/MVC run reproduced seven failures: numeric identity
precision, numeric mapping precision, exponent bounds, disappearing numeric ID selecting
its rounded neighbor at Play, the Sensitive default, missing Setup controls and safe Test
diagnostics. All 61 passed after the fixes. The new Chromium tests first failed on the
unchecked mapping default; an independent run with the original JavaScript then failed
on single mode offering two contexts. Both engines now pass the corrected behavior.

Verified outcomes:

- Exact decimal parsing distinguishes neighboring large integral IDs, rejects truly
  fractional IDs, preserves numeric `1`/`1.0` equivalence and text `"1"` distinction,
  and retains exact numeric URL substitutions. Missing precise IDs send no Cast action.
- Huge exponents are handled without expanding unbounded integers or plain decimal
  strings. Boundary, overflow, fractional and scientific-notation regressions pass.
- Untouched new mappings start Sensitive and mask Test output. Explicitly unchecked
  mappings and saved false values remain false across browser Save and MVC binding.
- Test distinguishes safe HTTP status, busy, timeout, missing mapping and duplicate-entry
  diagnostics; unexpected exceptions, causes, parser excerpts and sensitive values stay hidden.
- Setup displays both modes, Edit and a Test POST containing the saved revision and a
  fresh-fetch/no-playback explanation. Setup GET does not fetch the single-workflow feed;
  pressing Test fetches once and sends no device action.
- Single mode offers only Whole response, including dynamically added mappings. Mode
  changes reset entry scopes explicitly; switching back permits deliberate entry selection.
  Existing server-side scope validation remains in place.

Updated synthetic editor and Dashboard screenshots use the filenames listed above.
New Setup screenshots are `/tmp/workflow-setup-final-{390,1440}-{chromium,webkit}.png`.
The mobile editor and desktop Setup were visually inspected; browser layout assertions
also passed at the tested widths. Physical Cast acceptance below remains unchecked.

## Physical Cast acceptance — unchecked

- [ ] On a real household installation, save one single-tile workflow against a reachable JSON feed and inspect its Dashboard tile.
- [ ] Save one generated workflow, refresh its catalog, and inspect the desired tile.
- [ ] Open each tile's play sheet and confirm the selected Cast device and route preview without starting playback.
- [ ] Press Play for each tile on a real Cast device; confirm the receiver starts the expected direct stream.
- [ ] Rotate an upstream token, refresh/reopen the generated tile, press Play, and confirm the same media entry uses the new token.
- [ ] Confirm the expected on-screen video and audio output and record receiver/model, feed/media setup, and any codec or network limitation.

No physical receiver or live media service was used by the automated checks.

## Decisions and costs carried forward

These decisions and costs are carried from the root execution ledger so they remain reviewable after the SDD scratch directory is removed:

- **Ruling:** Use an isolated feature worktree from the accepted plan HEAD. Cost: a removable local branch/worktree if that choice was wrong.
- **Ruling:** Treat plan code fragments as illustrative where syntax would duplicate a class, while keeping specified behavior and interfaces. Cost: reviewers must check actual behavior and meaningful tests rather than literal fragments.
- **Ruling:** Permit only legitimate Spring/Thymeleaf checkbox markers for explicitly allowed editor fields. Cost: a narrow exception to unknown-field rejection.
- **Ruling:** Use targeted task tests after the baseline, then one full build at final integration. Cost: unrelated coupling may be detected later; focused integration suites reduce that risk.
- **Ruling:** Public artwork validation is a pure URL admissibility check: HTTPS, no credentials/query/fragment, and no clearly local host or non-public literal IP; qualified DNS names remain admissible without resolution. Cost: a qualified hostname that resolves privately may pass this check, since browser DNS cannot be pinned by this server.
- **Ruling:** Count corrupt workflow keys toward capacity, reject stored key/payload ID mismatches, and remove corrupt entries only through their exact recorded keys. Cost: damaged records may require cleanup before another save.
- **Ruling:** Store edit/delete takes the workflow stripe before the global write lock; create takes the global lock and dispatch takes the stripe. Cost: revisit lock ordering if callbacks later mutate store state.
- **Ruling:** Sanitize receiver `ActionFailedException` text during workflow dispatch while retaining offline/unsupported types. Cost: receiver diagnostics are less detailed, preventing credential echo.
- **Ruling:** `WorkflowRunner.resolve` validates final media addresses once; Test fetches once for masked samples and uses a bounded media check. Cost: update that contract if later callers need unresolved URLs.
- **Ruling:** Keep the existing same-origin `Referrer-Policy` and add `no-store` to editor/results. Cost: same-origin page-path referrers remain possible; workflow secrets are absent from those paths.
- **Ruling:** Encode malformed corrupt-workflow IDs as canonical `invalid-` plus base64url ASCII only for remove-invalid routes, checking exact problem-map membership. Cost: one bounded recovery token codec.
- **Ruling:** Allow explicit authenticated Test of disabled saved workflows, with revision checks before and after. Cost: pressing Test can deliberately fetch even while the workflow is disabled.
- **Ruling:** Add optional subtitle/artwork pointer toggles and exact binder marker allowances so omitted pointers and empty-root pointers both round-trip. Cost: two additional controls.
- **Ruling:** Describe Save as never starting playback while explaining that generated catalog refresh can fetch after a content change. Cost: users must distinguish persistence from background refresh; the copy no longer promises zero requests for generated saves.

- **Ruling:** Parse JSON decimals exactly; explicitly retain Jackson's 1,000-character numeric-token limit and cap expanded whole-number entry IDs at 1,000 decimal digits before integer conversion. Preserve existing typed integer hashes, including numeric `1`/`1.0` equivalence. Numeric mappings can use scientific notation without losing precision. Cost: extreme-exponent integral IDs are rejected, and downstream media endpoints must accept the exact numeric representation used in substitutions.
- **Ruling:** Switching generated workflows to one tile resets ENTRY mappings to ROOT and shows a pointer-review explanation. Switching back requires deliberately selecting Current entry again. Cost: previous per-entry scope choices are not automatically restored; users must review their pointers.
- **Ruling:** Reuse only the locally authored safe `WorkflowException` message in Test results; retain generic fallback for unexpected exceptions. Cost: new exception producers must maintain that safe-context contract and never include upstream text, URLs or causes.
