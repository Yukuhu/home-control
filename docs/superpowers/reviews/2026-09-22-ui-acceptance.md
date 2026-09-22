# Home Control interface acceptance

Scope: develop a polished interface for the existing application, with equal attention to phones and desktops (the user's selected direction). Preserve real device controls, source connections, playback planning, authentication, and live updates.

## Delivered interface

| Requirement | Evidence |
| --- | --- |
| Consistent dashboard, setup, login, and offline presentation | Shared `app.css` design tokens, matching templates, SVG media controls, matching manifest and app-icon colors; rendered browser screenshots |
| Usable phone and desktop layouts | `InterfaceE2eTest` checks page/control bounds at 320, 390, and 1440 pixels in Chromium and WebKit; the active device's Remote action stays in view |
| Discoverable device selection and remote controls | Device cards, selected-device styling, explicit Remote action, desktop side panel and phone bottom panel; existing device-switching and touchpad tests |
| Organized setup and first-run guidance | Section navigation, source links, responsive forms, visible field labels, expandable help, first-run walkthrough; browser label, anchor, and first-run tests |
| Accessible keyboard interaction | Enter activates focused controls without sending a TV command; opening/closing the remote transfers/restores focus; Escape closes it; arrow keys select remote modes and playback devices; visible focus and reduced-motion/forced-colors styles |
| Content browsing, playback, and meaningful states | Content rails, source badges, progress text, artwork proportions, search feedback, actionable empty state, play sheet, retry states; existing playback/search/rail/pin browser tests |
| Offline page remains styled | `OfflineUiE2eTest` stops a real local origin and checks service-worker delivery, CSS and icon availability, while live pages, streams, scripts, and writes remain unavailable |
| Existing integration behavior preserved | Existing browser suite exercises device switching, playback alternatives, touch gestures, login gating, source failures, pin upgrades, and YouTube OAuth; existing server UI tests rerun |

## Verification

- UI/controller regression run: **163 tests passed**. Includes dashboard, login gating, setup, YouTube integration, PWA resources, and app icons.
- Complete browser suite: **74 tests passed**, zero failures or skips, across Chromium and WebKit. `bootJar` also passed.
- JavaScript syntax checks (`node --check`) and `git diff --check`: passed.
- Screenshots generated under `build/e2e-artifacts/ui-*.png` cover dashboard, remote, setup, empty library, first-run setup, login, and offline screens. `InterfaceE2eTest` also emits setup viewport images for readable visual review.

The full server suite was exercised: **2,166 tests, 1 skipped**. Its first run reported nine failures. Six were assertions tied to replaced UI wording/markup; these were corrected without weakening the device, login, or source behavior assertions and passed in the 163-test rerun. The remaining three also fail in an untouched archive of baseline commit `70bbaf0`:

- `BluetoothClassLoadingTest.theProbeSeesTheModuleWhenEnabled`
- `ProcessMpvLauncherTest.runCapturesOutput`
- `BluetoothSpeakerEndToEndTest.pairsPlaysControlsAndForgetsASpeaker`

The host's GraalVM 25.0.1 emits cgroup warnings into child-JVM output. The fake mpv version test consequently receives warnings before its expected version string; the Bluetooth setup check uses that same output. The class-loading probe also returns no matching class-log lines in this environment. No Bluetooth runtime code was changed for this UI task.

Two browser-harness issues were addressed: WebKit cannot fulfill an intercepted OAuth response with status 302 (also reproduced on the untouched baseline), so the test now inspects the real redirect and simulates the fake consent hop with HTML while preserving the session cookie. WebKit's emulated offline mode bypasses the worker; the offline test instead disconnects a real local test origin in both engines.

## Reproduction

Use the project's existing Java 25 / Playwright 1.63 tooling:

```sh
./gradlew test --tests '*DashboardPageTest' --tests '*LoginGatingTest' \
  --tests '*YouTubeEndToEndTest' --tests '*PwaControllerTest' \
  --tests '*IconControllerTest' --tests '*Setup*'
./gradlew e2eTest bootJar
```

For this workstation, matching browsers were installed in `/tmp/shield-ui-browsers`; WebKit's headless libraries were extracted under `/tmp/shield-webkit-deps` without changing system packages. The local browser runs used `PLAYWRIGHT_BROWSERS_PATH=/tmp/shield-ui-browsers` and `PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1` after verifying the headless dependencies and actual rendering. No production dependency was added.

Physical TV playback and installed-app behavior on actual iOS/Android hardware were not newly tested; their existing hardware acceptance checklists remain unchanged. Browser automation uses the real application with fake devices and sources.
