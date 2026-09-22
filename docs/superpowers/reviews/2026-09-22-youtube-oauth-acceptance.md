# YouTube browser OAuth verification

Implemented browser OAuth for the existing single household account, retaining device-code
authorization for LAN deployments. The setup form displays the callback URI and instructions
for creating the matching Google OAuth client. No new product dependencies were added.

## Verified behavior

| Requirement | Evidence |
|---|---|
| Setup opens Google authorization and returns to the connected channel | `YouTubeBrowserOAuthTest`; Chromium `YouTubeOAuthE2eTest` |
| Cross-site return retains the household login | Chromium test uses localhost for Home Control and 127.0.0.1 for simulated consent |
| Offline, read-only authorization with PKCE | HTTP test checks authorization parameters and hashes the actual exchanged verifier |
| State belongs to the initiating browser, expires, and is single-use | HTTP test plus `YouTubeBrowserAuthorizationTest` |
| Denied, malformed, failed and cancelled consent cannot replace the saved token | Browser authorization tests |
| Refresh tokens encrypted at rest; access tokens refreshed automatically | HTTP test checks stored ciphertext and the refresh request |
| Disconnect cannot race with a new authorization | Latch-based browser authorization regression test |
| Same account retains playlist choices; a different account clears them | HTTP integration regression test |
| Previous account's dashboard snapshots and in-flight results are discarded | HTTP integration test and `RailCacheTest` |
| HTTPS proxy callback reflects forwarded origin | `YouTubeBrowserAuthorizationTest` with `ForwardedHeaderFilter` |
| Existing device-code flow remains functional | Existing YouTube service tests and `YouTubeEndToEndTest` |

## Commands and results

- `./gradlew test --offline`: 2,166 tests; 2,162 passed, 3 failed, 1 skipped.
  All 228 YouTube and rail-cache tests passed.
- `PLAYWRIGHT_BROWSERS_PATH=/tmp/shield-oauth-browsers ./gradlew e2eTest --tests '*YouTubeOAuthE2eTest' -Pe2eBrowsers=chromium --offline`:
  passed. The matching Playwright Chromium binary was downloaded into `/tmp` for this run.
- `git diff --check`: passed.
- `./gradlew bootJar --offline`: passed; runnable application JAR built.
- Read-only code review: no remaining blocking findings after the reconnect fixes.

The three full-suite failures also reproduced against the unchanged starting commit `eff29ee`
in `/tmp/shield-oauth-baseline`:

- `BluetoothClassLoadingTest.theProbeSeesTheModuleWhenEnabled`
- `ProcessMpvLauncherTest.runCapturesOutput`
- `BluetoothSpeakerEndToEndTest.pairsPlaysControlsAndForgetsASpeaker`

The mpv output assertion receives local JVM cgroup warnings before its expected version line.
The other two failures involve Bluetooth class loading and the mpv version display. They were
not changed as part of YouTube OAuth.

## Limits

Google's token service and consent page are simulated in these tests. Live consent requires
the owner's Google Cloud OAuth client and Google account; it was not performed. WebKit was
not verified because this machine lacks the matching Playwright WebKit binary. Deployment
instructions and the exact callback requirements are in README's YouTube section.

If the channel lookup fails after successful authorization, the token is connected but the
old account's rail choices stay cleared, avoiding display of another account's library.
