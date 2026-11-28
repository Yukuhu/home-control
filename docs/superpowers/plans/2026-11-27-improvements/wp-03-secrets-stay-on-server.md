# WP-03: Secrets stay on the server — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit
> that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** A TV, speaker or Cast receiver that echoes a Jellyfin stream address in its refusal no longer puts the token
in a toast, a 502 body or a log line: a device's words are cleaned and capped where they enter the process. Setup never
shows Google's own error text, a failed page request gets a themed error page instead of the Whitelabel page, and one
test with canary secrets checks "secrets never reach the browser" across every page, play and event.

**Findings:**

- [SEC-1](../../reviews/2026-11-27-improvement-catalogue.md#sec-1): all of it. One redactor in `core`
  (`RedactedUris.inText`, moved from Bluetooth's `StreamRedaction`) and one cleaner (`ForeignText.clean`), applied where
  device refusals are made, so `DeviceCalls.run`, `RendererCommands.run` and log lines get clean text.
  `TextWebSocket.withoutQuery` delegates to `RedactedUris`.
- [ARCH-10](../../reviews/2026-11-27-improvement-catalogue.md#arch-10) (merged into SEC-1): control characters go,
  whitespace collapses, the length is capped, and UPnP, Cast and webOS each get a test with a fault that echoes a URL.
- [DEV-6](../../reviews/2026-11-27-improvement-catalogue.md#dev-6) (merged into SEC-1): `WebOsSession` names an opened
  link without its query, as Android TV does, and `FakeUpnpRenderer` and `FakeCastReceiver` gain echoing faults.
- [TEST-V1](../../reviews/2026-11-27-improvement-catalogue.md#test-v1): all of it. `SecretsStayOnTheServerEndToEndTest`
  seeds canary secrets, plays against echoing fakes, walks every GET mapping and the SSE snapshot, and fails when a new
  GET mapping is not walked.
- [SEC-2](../../reviews/2026-11-27-improvement-catalogue.md#sec-2): all of it, with the optional hardening. The device
  flow shows Home Control's words and Google's error code only, and links only to a page on Google.
- [SRC-15](../../reviews/2026-11-27-improvement-catalogue.md#src-15) (merged into SEC-2): known codes get the module's
  own wording; Google's description goes to the DEBUG log, redacted.
- [WEB-5](../../reviews/2026-11-27-improvement-catalogue.md#web-5): parts 1, 3, 4 and 5 in full, part 2 for
  `ResponseStatusException`. A catch-all handler for "any other exception" is left out (decision 3).
- [SEC-11](../../reviews/2026-11-27-improvement-catalogue.md#sec-11): options (a) and (b). The connected summary already
  warns in API-key mode (`jellyfin-setup.html:18-20`) but names only Cast and VLC; the choice itself and the docs say
  nothing about stream URLs or revocation. Proxying media is left out.

**Approach:** Device text enters the process in two exception types: `DeviceRefusedException` in `adapters.net` (the
base of `CastRefusedException`, `SsapException` and `DialException`) and `SoapFault` in `upnp.protocol`. Their
constructors pass the device's words through a new `core.ForeignText.clean`: bound the input, turn control and format
characters (line breaks, bidi overrides, zero-width marks) into spaces, redact with `RedactedUris.inText`, collapse
whitespace and cut at 300 characters. The funnels the findings name, `DeviceCalls.run` and `RendererCommands.run`, then
receive clean text, and so do the pairing pages, every log line that prints these exceptions and any future protocol.
`RedactedUris.inText` takes over `StreamRedaction`'s rules (URL query and user info, loose keys) and adds `ws`, `wss`
and `vlc` URLs, JSON keys such as `"accessToken"`, `Token="…"` and `Bearer`. Two web-edge nets catch text from sources
and anything new: `ErrorAdvice.text` and the `PlayResultView` message pass through the idempotent `inText`.

The error boundary: `templates/error.html`, built on the layout fragments, themes Spring Boot's own error page.
`ErrorAdvice` answers a `ResponseStatusException` with its reason as plain text and rethrows it for a page navigation,
so a form post gets the themed page. A new `static/js/failure-text.js` decides what a toast may show: a `text/plain`
body as it is, otherwise a sentence of its own. `GoogleOAuthClient` accepts an error code only when it looks like one,
words it itself, and accepts `verification_url` only on Google. Jellyfin gets two hints and a docs section. The canary
test lands last and pins all of it.

**Depends on / touches:** Depends on nothing. Touches WP-11 (WEB-2 and WEB-7 change `remote-transport.js`, `app.js` and
`toast.js`; whichever lands second rebases), WP-15 (WEB-13 adds unit tests for pure modules such as `failure-text.js`),
WP-16 (OPS-3 builds its INFO lines and kept causes on `ForeignText` and `RedactedUris.inText`), WP-20 (OPS-4's redacted
support info uses `inText`), WP-25 (SEC-3 changes the ws fallback next to `TextWebSocket.withoutQuery`) and WP-32
(DEV-14's wire traces use the same redactor). No ADR changes: nothing proxies media, so ADR 0005 stands, and ADR 0002's
secret store is unchanged.

**Effort:** M overall. PR 1: S. PR 2: M. PR 3: S. PR 4: S. PR 5: M.

## Before you start

- Re-check the findings against current main. At 6a253e4, whose code is identical to 3932f9b (`git diff --stat 3932f9b
  HEAD -- src protocols core` is empty), every finding holds:
  - `DeviceCalls.run` appends `e.getMessage()` to "refused to …", and `RendererCommands.run` wraps `fault.getMessage()`
    in "refused to … (…)". Neither cleans anything.
  - `SoapClient.fault` takes `errorDescription` or `faultstring` as given and `SoapFault.describe` only strips it;
    `CastIncoming.describeFailure`, `CastApps.reason` and `SsapConnection.payloadOf` (`errorText`) are verbatim too.
  - `WebOsSession.execute` describes `Action.OpenAppLink` as `"open " + uri` (`AndroidTvSession` already redacts).
    `StreamRedaction` lives in `adapters.bluetooth.player`, `TextWebSocket.withoutQuery` duplicates
    `RedactedUris.withoutQuery`, and `JellyfinStreams.streamUrl` still appends `ApiKey=`.
  - `ErrorAdvice.text` answers `e.getMessage()`, nothing handles `ResponseStatusException`, there is no
    `templates/error.html`, and `ContentPlayController.attempt` puts `cause.getMessage()` into `PlayResultView`.
    `app.js`, `remote-transport.js`, `setup.js` and `play-sheet.js` show a failed response's body whatever its type.
  - `GoogleOAuthClient.poll`'s default branch returns `Failed(error, error_description)`, which
    `YouTubeAuthorizationService.pollOnce` shows, and `requestDeviceCode` passes `verification_url` to `URI.create`.

  Repeat with `git log 3932f9b..origin/main -- core/src/main/java/dev/andre/homecontrol/core
  protocols/src/main/java/dev/andre/homecontrol/adapters src/main/java/dev/andre/homecontrol/web
  src/main/java/dev/andre/homecontrol/sources/youtube src/main/resources` and by reading the methods above.
- Owner decisions. Ask these up front. None changes a product rule, a name that never changes, or an ADR:
  1. **Length cap.** SEC-1 and WEB-5 say about 300 characters, ARCH-10 about 160. Recommended: 300, as
     `ForeignText.MAX_LENGTH`. "Living room TV refused to play the stream (UPnP error 714: …)" with a real description
     fits, and the toast wraps.
  2. **Where device text is cleaned.** Recommended: in the constructors of `DeviceRefusedException` and `SoapFault`,
     plus the web-edge nets. The funnels the findings name then receive clean text, and log lines, pairing pages and
     future protocols are covered too. The cost: the cap also applies to refusals Home Control words itself, all short.
  3. **No catch-all exception handler.** Recommended: leave out WEB-5's "one sentence for any other exception": an
     `@ExceptionHandler(Exception.class)` also catches Spring MVC's own 4xx exceptions and the SSE stream's client
     disconnects. Boot's error JSON carries no exception message by default (a test pins it), Tomcat logs the stack
     trace, and `failure-text.js` words such a failure itself.
  4. **Logs in the canary test.** Recommended: also assert that the captured output (INFO, as tests run) holds no
     canary. Logs are not the browser, but bug reports paste them, and the check costs nothing.
  5. **API-key mode stays.** Recommended: warn and document; do not remove or migrate it ("existing installs upgrade in
     place"). Proxying media to hide the token is in the analysis's "What not to do".
  6. **Verification page.** Recommended: accept `verification_url` only as `https` on `www.google.com` or `google.com`,
     without user info or port; otherwise show `DEFAULT_VERIFICATION_URL` and log a WARN without the URL.
  7. **Hardware.** Whether a real renderer echoes the URL is unverified. Recommended: not a merge gate.

## Global constraints

- **Secrets never reach the browser** (AGENTS.md product rules). No step reads a secret for display. The canary test is
  the check; a leak it finds is fixed at its source, never by weakening the test.
- **Commands are ephemeral.** Nothing retries or queues: `failure-text.js` only words a failure. **A LAN appliance:** no
  new outbound call or dependency. **Upgrade in place:** no `/data` format changes, so nothing migrates, and API-key
  mode and every setting keep working.
- **CSP.** `error.html` has no inline script, style or `on…=` attribute (`InlineCodeTest`); the error page keeps the
  `Content-Security-Policy` header, and the browser tests record no violation on it.
- **Architecture** (every package rule is strict). `core` stays JDK-only (`java.util.regex`). `protocols` uses `core`
  through its `api(project(":core"))`, Bluetooth imports `core`, and `web` uses `core` only
  (`webDoesNotDependOnAdapters`). `ArchitectureTest` does not change.
- **Modules.** The error page needs only `ThemeViewAdvice` and `ThemeCatalog`, which are always on, so it renders with
  every module off (`ModulesOffSmokeTest`).
- **Tests** sit in the package and module of the code they test. The fakes' echo options live in
  `protocols/src/testFixtures` and speak the real protocol: a UPnP fault envelope, a Cast `LOAD_FAILED`. Full-app tests
  extend `FullAppTest` and add nothing to its context (`SharedContextRulesTest`).
- **Sonar and Error Prone.** Device text is attacker-controlled: every pattern is a literal alternation with possessive
  quantifiers, and `ForeignText` bounds its input before matching (java:S5852, java:S5843). A deliberate exception uses
  the narrowest `@SuppressWarnings` with a one-line reason.
- `scripts/gradle.sh build` is green at the end of every task. Tasks 3, 4 and 6 change what a browser shows: run
  `scripts/e2e.sh` before their pull requests (`-Pe2eBrowsers=chromium --tests '*ErrorBoundary*'` while iterating).
- Commits follow Conventional Commits (`scripts/check-commits.sh`), with scopes as in the log (`core`, `adapters`,
  `web`, `youtube`, `jellyfin`). Stage only the task's files with `git add <paths>`, never `git add -A`. Each commit
  ends with the session's `Co-Authored-By` and `Claude-Session` lines.

## Review focus

1. **Every device refusal is cleaned where it is made.** Pinned by `DeviceRefusedExceptionTest`, the echo tests in
   `SoapClientTest`, `CastAppsTest` and `SsapConnectionTest`, and at the funnels by
   `DeviceCallsTest.aRefusalShowsNoQueryAndNoLineBreak` and
   `RendererCommandsTest.aRefusalThatEchoesTheStreamShowsNoKey`.
2. **The rules.** A query, user info, a key in a query, in JSON or after `Bearer` never survive; a device's ordinary
   words do; the cut never splits a character; a huge input is bounded. Pinned by `RedactedUrisTest` and
   `ForeignTextTest`; read the patterns for nested quantifiers.
3. **The error boundary.** A page request gets the themed page with its status and the CSP header; htmx and fetch get a
   plain-text reason or a sentence of the client's own. Pinned by `ErrorPageEndToEndTest`,
   `ErrorAdviceTest.aResponseStatusIsItsReasonAsPlainText` and `ErrorBoundaryE2eTest`.
4. **Google's words never reach Setup, and the code link goes to Google.** Pinned by
   `GoogleOAuthClientTest.aDescriptionIsNeverEchoed`, `anErrorThatIsNoCodeIsNotShown` and
   `aVerificationPageOffGoogleIsReplacedByGooglesOwn`.
5. **The canary walk cannot silently shrink.** `SecretsStayOnTheServerEndToEndTest.everyGetMappingIsWalkedOrExcused`
   fails for a GET mapping neither walked nor excused; check that each excuse is a real reason.
6. **The Jellyfin texts say what really happens.** Pinned by `JellyfinSetupControllerTest`'s two new tests; read
   `docs/user/sources.md` against `JellyfinSetupService.connect` and `disconnect`.

## File structure

- **Create:** `core/src/main/java/dev/andre/homecontrol/core/ForeignText.java`,
  `src/main/resources/templates/error.html`, `src/main/resources/static/js/failure-text.js`.
- **Modify** (main code under `core/src/main/java/`, `protocols/src/main/java/` or `src/main/java/`, package
  `dev.andre.homecontrol`): `core.RedactedUris`; `adapters.net.DeviceRefusedException`, `adapters.net.TextWebSocket`,
  `adapters.upnp.protocol.SoapFault`; `adapters.bluetooth` (`BluetoothHostChecks`, `BluetoothSpeakerSession`,
  `player.MpvPlayer`, `player.ProcessMpvLauncher`, `player.MpvException`; delete `player.StreamRedaction`);
  `adapters.webos.WebOsSession`; `web.ErrorAdvice`, `web.ContentPlayController`; `sources.youtube.GoogleOAuthClient`,
  `sources.youtube.YouTubeAuthorizationService`. Fakes in `protocols/src/testFixtures/java/`:
  `adapters.upnp.protocol.FakeUpnpRenderer`, `adapters.cast.protocol.FakeCastReceiver`. Resources: `static/js/app.js`,
  `remote-transport.js`, `setup.js`, `play-sheet.js`, `templates/fragments/jellyfin-setup.html`. Docs:
  `docs/user/security.md`, `docs/user/sources.md`, `docs/dev/architecture.md`, `docs/dev/testing.md`.
- **Test**, each next to its class: create `core.ForeignTextTest` (core), `adapters.net.DeviceRefusedExceptionTest`
  (protocols), `web.ErrorPageEndToEndTest` and `web.SecretsStayOnTheServerEndToEndTest` (app), and
  `src/e2e/java/dev/andre/homecontrol/e2e/ErrorBoundaryE2eTest.java`. Modify `RedactedUrisTest`, `TextWebSocketTest`,
  `SoapClientTest`, `CastAppsTest`, `SsapConnectionTest`, `DeviceCallsTest`, `RendererCommandsTest`, `WebOsSessionTest`,
  `ErrorAdviceTest`, `ContentPlayPreviewTest`, `LayoutFragmentsTest`, `StaticAssetsTest`, `GoogleOAuthClientTest`,
  `YouTubeAuthorizationServiceTest` and `JellyfinSetupControllerTest`. Delete
  `src/test/java/dev/andre/homecontrol/adapters/bluetooth/player/StreamRedactionTest.java` (its cases move). Must stay
  green unchanged: `ArchitectureTest`, `InlineCodeTest`, `SharedContextRulesTest`, `ModulesOffSmokeTest`,
  `JellyfinEndToEndTest`, `SpeakerJellyfinEndToEndTest`, `DeviceControlsE2eTest`, `ContentSecurityPolicyE2eTest`.

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `fix(adapters): clean and cap device text before it reaches a message or a log` | 1, 2 | S |
| 2 | `fix(web): themed error pages, and toasts that show only plain-text reasons` | 3, 4 | M |
| 3 | `fix(youtube): show Google's error code on Setup, never its description` | 5 | S |
| 4 | `fix(jellyfin): say that stream addresses carry the token to every player` | 6 | S |
| 5 | `test(web): check app-wide that no secret reaches the browser` | 7 | M |

PR 1 lands first; PR 2 uses its redactor. PRs 3 and 4 are independent of both. PR 5 lands last, so its walk covers the
error page and the device-flow wording. Each PR leaves main releasable after every commit.

## Tasks

### Task 1: One redactor and one cleaner in `core`

**Files:** Create `ForeignText.java`. Modify `RedactedUris.java`, `TextWebSocket.java` and the Bluetooth classes above;
delete `StreamRedaction.java`. Test `RedactedUrisTest`, `ForeignTextTest`, `TextWebSocketTest`; delete
`StreamRedactionTest`.

**Interfaces:**

- `public static String RedactedUris.inText(String text)` (new; `null` stays `null`). In order: the query of every
  `http`, `https`, `ws`, `wss`, `rtsp`, `rtmp` or `vlc` URL becomes `?…`; user info becomes `//…@`; the value of
  `name=value` for `api_key`, `apikey`, `access_token`, `refresh_token`, `id_token`, `token`, `client_secret`, `secret`,
  `password`, `passwd`, `device_code`, `code`, `x-emby-token`, `sig` and `signature` (any case, `_` optional, value
  optionally in double quotes) becomes `…`; the same names as JSON keys (`"accessToken":"…"`); and the word after
  `Bearer` or `Basic`. Idempotent.
- New `public final class ForeignText` ("text Home Control did not write"): `public static final int MAX_LENGTH = 300`
  and `public static String clean(String text)` (`null` stays `null`). It keeps the first 4096 characters, turns every
  ISO control and Unicode format character into a space, applies `RedactedUris.inText`, collapses whitespace, strips,
  and cuts to 299 code points plus `…` when longer than 300.
- `TextWebSocket.withoutQuery(URI)` returns `RedactedUris.withoutQuery(uri)`, so a query now shows as `?…`.

- [ ] **Step 1: Write the failing test.**
  - `RedactedUrisTest` gains `StreamRedactionTest`'s four cases with the same expectations, as
    `inTextHidesTheQueryOfEveryUrl`, `inTextHidesLooseKeys`, `inTextHidesUserInfo` and `inTextLeavesOtherTextAlone`,
    plus:
    - `inTextCoversWebSocketAndVlcLinks`: `wss://tv/api?token=1` gives `wss://tv/api?…`;
      `vlc://http://nas:8096/Videos/a/stream?ApiKey=0123` gives `vlc://http://nas:8096/Videos/a/stream?…`.
    - `inTextHidesKeysInJsonHeadersAndAuthorizations`: `{"accessToken":"abc"}`, `MediaBrowser Token="abc"`,
      `Authorization: Bearer abc.def`, `client_secret=abc&x=1` and `device_code=abc` keep their names and lose `abc`.
    - `inTextKeepsADevicesOwnWords`: `UPnP error 714: Illegal MIME-type` and `LAUNCH_ERROR: NOT_FOUND` are unchanged.
    - `inTextIsIdempotent`: for every input above, `inText(inText(x))` equals `inText(x)`.
  - `ForeignTextTest`: `controlAndFormatCharactersBecomeOneSpace` (`"busy\r\n\tnow\u202E\u200Bok"` gives `busy now ok`),
    `aLongTextIsCutAtTheCap` (400 `x` give 299 `x` and `…`), `theCutNeverSplitsACharacter` (299 `x` and two emoji give
    299 `x` and `…`), `aUrlIsRedactedBeforeTheCut` (280 `x`, a space and `http://nas:8096/a?ApiKey=0123456789abcdef`
    hold no `0123`), `aHugeAnswerIsBoundedBeforeMatching` (a million `a-` under `@Timeout(2)` give 300 code points),
    `aDevicesOwnWordsAreKept` and `nullStaysNull`.
  - `TextWebSocketTest.theQueryIsLeftOutOfAnAddressWithoutAPort` expects
    `wss://tv.local/api/v2/channels/samsung.remote.control?…` and `ws://10.0.0.5:3000?…`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh :core:test` does not compile (`cannot find symbol` for
  `inText` and `ForeignText`); `scripts/gradle.sh :protocols:test --tests
  'dev.andre.homecontrol.adapters.net.TextWebSocketTest'` fails on the missing `?…`.
- [ ] **Step 3: Implement.** Move `StreamRedaction`'s three patterns into `RedactedUris` and add the new rules, each a
  literal alternation with possessive quantifiers, e.g.
  `Pattern.compile("\\b((?:https?|wss?|rtsp|rtmp|vlc)://[^\\s?#'\"<>]*+)\\?[^\\s'\"<>]*+", CASE_INSENSITIVE)`; no
  `[\w-]*` before a keyword. `ForeignText.clean` walks code points once for the control and format pass, then applies
  `inText`, `replaceAll("\\s++", " ").strip()` and the cut with `offsetByCodePoints`. In Bluetooth, replace
  `StreamRedaction.redact` with `RedactedUris.inText`; the two `ActionFailedException` messages in
  `BluetoothSpeakerSession` that quote mpv use `ForeignText.clean`, because they reach a toast.
- [ ] **Step 4: Run the tests.** `scripts/gradle.sh :core:test`, `scripts/gradle.sh :protocols:test --tests
  'dev.andre.homecontrol.adapters.net.*'`, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.bluetooth.*'`, then `scripts/gradle.sh build`. All green.
- [ ] **Step 5: Update docs.** `docs/dev/architecture.md`, the `core` row: "`RedactedUris` and `ForeignText`, which
  every message built from a URL or from a device's own words passes through".
- [ ] **Step 6: Commit.** `refactor(core): share one text redactor and a cleaner for device text`.

### Task 2: Device refusals are cleaned where they are made; the web edge redacts what it shows

**Files:** Modify `DeviceRefusedException.java`, `SoapFault.java`, `FakeUpnpRenderer.java`, `FakeCastReceiver.java`,
`WebOsSession.java`, `ErrorAdvice.java`, `ContentPlayController.java`, `docs/user/security.md`,
`docs/dev/architecture.md`. Create `DeviceRefusedExceptionTest`. Test `SoapClientTest`, `CastAppsTest`,
`SsapConnectionTest`, `DeviceCallsTest`, `RendererCommandsTest`, `WebOsSessionTest`, `ErrorAdviceTest`,
`ContentPlayPreviewTest`.

**Interfaces:**

- `DeviceRefusedException(String message)` calls `super(ForeignText.clean(message))`; its javadoc says so.
- `SoapFault.describe` cleans before the `KNOWN` lookup: `String given = description == null ? "" :
  ForeignText.clean(description);`.
- `FakeUpnpRenderer.fail(action, code, description, times)`: the description may name request arguments in braces, e.g.
  `{CurrentURI}`, filled from the call when the fault is answered. Existing callers name none.
- `FakeCastReceiver.failNextLoad(String reason)` (new): the next `LOAD` is answered `LOAD_FAILED` with `reason`, in
  which `{contentId}` becomes the LOAD's `media.contentId`. `failNextLoad()` passes `null` (no reason, as today); the
  `boolean failNextLoad` field becomes a nullable `String nextLoadFailure`.

- [ ] **Step 1: Write the failing test.**
  - `DeviceRefusedExceptionTest`: `theDevicesWordsAreCleaned` (a line break and `?token=abc` give a space and `?…`) and
    `aLongReasonIsCut` (500 characters give 300).
  - `SoapClientTest.aFaultThatEchoesTheStreamUrlKeepsNoQueryOrLineBreak`: `SoapClient.fault` on an envelope whose
    `errorDescription` is `Illegal MIME-type for&#10;http://nas:8096/Videos/x/stream.mkv?static=true&amp;ApiKey=0123`
    gives the message `UPnP error 714: Illegal MIME-type for http://nas:8096/Videos/x/stream.mkv?…`, and that
    `description()` without the prefix.
  - `CastAppsTest.aFailedLoadThatEchoesTheContentIdKeepsNoQuery`: `receiver.failNextLoad("Cannot load {contentId}")` and
    a load whose `contentId` is `http://nas:8096/Audio/a/stream.flac?ApiKey=0123` throw `CastRefusedException` with
    `LOAD_FAILED: Cannot load http://nas:8096/Audio/a/stream.flac?…`.
  - `SsapConnectionTest.anErrorTextThatEchoesALinkKeepsNoQueryOrControlCharacter`: `SsapConnection.payloadOf(
    SsapUris.OPEN, message)` for `{"type":"error","error":"500 Application error","payload":{"errorText":"cannot open
    https://x.example/y?token=abc\n\u202Espoof"}}` throws with `ssap://system.launcher/open failed: 500 Application
    error (cannot open https://x.example/y?… spoof)`.
  - `DeviceCallsTest.aRefusalShowsNoQueryAndNoLineBreak`: `DeviceRefusedException("cannot open
    https://x/y?token=abc\nnow")` becomes `Kitchen TV refused to open the link: cannot open https://x/y?… now`.
  - `RendererCommandsTest.aRefusalThatEchoesTheStreamShowsNoKey`: `fake.fail("SetAVTransportURI", 714, "Illegal
    MIME-type for {CurrentURI}", 1)` and `playUri` with
    `http://127.0.0.1:8096/Audio/a/stream.flac?static=true&ApiKey=0123` throw `RendererFaultException` with code 714 and
    `Kitchen Speaker refused to play the stream (UPnP error 714: Illegal MIME-type for
    http://127.0.0.1:8096/Audio/a/stream.flac?…)`.
  - `WebOsSessionTest.aLinkThatTimesOutIsNamedWithoutItsQuery`: with `tv.ignoreRequests("ssap://system.launcher/open")`,
    opening `https://example.org/page?token=s3cret` throws `ActionFailedException` ending `when asked to open
    https://example.org/page?…`, without `s3cret`.
  - `ErrorAdviceTest`: `THROWN` gains `"echo"`, an `ActionFailedException` quoting
    `http://nas:8096/Audio/a?ApiKey=0123`, mapped to 502 with `http://nas:8096/Audio/a?…`.
    `ContentPlayPreviewTest.aFailedAttemptsMessageShowsNoQuery`: a `PlayAttempt.Failed` whose cause carries
    `?ApiKey=0123` answers a JSON `message` ending `?…`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh :protocols:test --tests 'dev.andre.homecontrol.adapters.*'`
  fails the four protocol tests on the raw query and line break. `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.support.*' --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest' --tests
  'dev.andre.homecontrol.web.ErrorAdviceTest' --tests 'dev.andre.homecontrol.web.ContentPlayPreviewTest'` fails the same
  way, `RendererCommandsTest` showing the `ApiKey`.
- [ ] **Step 3: Implement.** The two constructors as in Interfaces. `WebOsSession.execute`'s `OpenAppLink` branch says
  `"open " + RedactedUris.withoutQuery(uri)`. `ErrorAdvice.text` answers `RedactedUris.inText(e.getMessage())`, and the
  class javadoc says device and source words are redacted on the way out. `ContentPlayController.attempt`'s `Failed`
  branch passes `RedactedUris.inText(cause.getMessage())`. `FakeUpnpRenderer.soap` fills `{name}` from the call's
  arguments before `new Fault(…)`; `FakeCastReceiver.media` answers `error("LOAD_FAILED", requestId, reason)` with the
  filled reason. `DeviceCalls` and `RendererCommands` do not change: they now receive clean text, as their tests pin.
- [ ] **Step 4: Run the tests.** The Step 2 commands, `scripts/gradle.sh :protocols:test`, `scripts/gradle.sh test
  --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.web.*'`, then `scripts/gradle.sh build`. If
  an existing assertion expected a raw query, a line break or an over-long reason, change only that expectation and say
  so in the commit body.
- [ ] **Step 5: Update docs.** `docs/user/security.md`, end of "Secrets": "Error messages that quote a device, a
  receiver or Google are cleaned first: web addresses lose their query and user name, keys and tokens are hidden, line
  breaks and invisible characters go, and a device's own words are cut at 300 characters." `docs/dev/architecture.md`,
  the `adapters` row: a device's refusal arrives cleaned by `DeviceRefusedException` and `SoapFault`.
- [ ] **Step 6: Commit.** `fix(adapters): clean and cap the words a device refuses with`. The body names the scenario: a
  renderer that echoed a Jellyfin stream URL put the token in the toast.

### Task 3: A themed error page, and a `ResponseStatusException`'s reason as plain text

**Files:** Create `src/main/resources/templates/error.html`, `ErrorPageEndToEndTest`. Modify `ErrorAdvice.java`,
`docs/dev/architecture.md`. Test `ErrorAdviceTest`, `LayoutFragmentsTest`.

**Interfaces:**

- `ErrorAdvice.status(ResponseStatusException e, HttpServletRequest request)` (new `@ExceptionHandler`): for a page
  navigation it rethrows `e`; `ExceptionHandlerExceptionResolver` then carries on with the original exception,
  `ResponseStatusExceptionResolver` calls `sendError`, and Spring Boot renders `error.html`. Otherwise it answers
  `e.getStatusCode()` with `text/plain` `RedactedUris.inText(e.getReason())`, or the reason phrase when there is none.
- `static boolean ErrorAdvice.isPageNavigation(HttpServletRequest)` (new): no `HX-Request: true`, and `Accept` lists
  `text/html`, as `LoginGateFilter` decides.

- [ ] **Step 1: Write the failing test.**
  - `ErrorAdviceTest`: `THROWN` gains `"gone"`, `new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rail")`.
    `aResponseStatusIsItsReasonAsPlainText` (with `HX-Request`: 404, `text/plain`, `No such rail`) and
    `aPageNavigationToAResponseStatusGoesToTheErrorPage` (`Accept: text/html`: 404, an empty body, and
    `getResponse().getErrorMessage()` `No such rail`, since MockMvc does not forward to `/error`).
  - `ErrorPageEndToEndTest extends FullAppTest`, with `HttpClient` on the real port (only a real server dispatches to
    `/error`):
    - `anUnknownPageIsTheThemedErrorPage`: `GET /no-such-page` with `Accept: text/html` answers 404, `text/html`, a
      `Content-Security-Policy` header, the default theme's stylesheet link, `href="/"` with `Back to Home`,
      `href="/setup"`, and no `Whitelabel Error Page`.
    - `aFormPostedToASwitchedOffModuleIsTheErrorPage`: `POST /setup/bluetooth/scan` with `Accept: text/html` (Bluetooth
      is off by default) answers 404 with that page.
    - `aRailThatIsGoneAnswersItsReasonAsPlainText`: `GET /rails/nope/nope` with `HX-Request: true` answers 404,
      `text/plain`, `No such rail`.
    - `springsOwnErrorBodyCarriesNoExceptionText`: `POST /devices/ghost/key/HOME?repeat=x` with `HX-Request: true`
      answers 400 without `NumberFormatException` or `For input string` in the body (decision 3).
  - `LayoutFragmentsTest.everyPageUsesTheSharedHeadAndNamesItself` lists `error.html` too.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ErrorAdviceTest'
  --tests 'dev.andre.homecontrol.web.ErrorPageEndToEndTest' --tests 'dev.andre.homecontrol.web.LayoutFragmentsTest'`:
  the 404 page is the Whitelabel page, the rail answers JSON, and `error.html` does not exist.
- [ ] **Step 3: Implement.** `error.html` follows `offline.html`: `pageHead(false)`, `body class="auth-page"`, an
  `auth-card` with the brand, an eyebrow with `${status}`, and a headline and sentence by `th:switch="${status}"` (404
  and 405: "This page is not here." / "It may have moved, or the part of Home Control it belongs to is switched off.";
  403: "Not allowed from here." / "Open Home Control at its own address and try again."; otherwise "Something went
  wrong." / "Home Control could not finish this. Its log has the details."). Then `<a class="button primary"
  href="/">Back to Home</a>` and `<a class="button" href="/setup">Setup</a>`. It shows no exception message and no path.
  `ThemeViewAdvice`'s attributes reach `BasicErrorController`, a `@Controller`; if the test finds the stylesheet link
  missing, add them in a small `ErrorViewResolver` in `web`. If the CSP header is missing on the error dispatch, also
  register `CrossOriginFilter` for `DispatcherType.ERROR`.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.*'
  --tests 'dev.andre.homecontrol.testsupport.*'`, then `scripts/gradle.sh build`. The log holds no `Failure in
  @ExceptionHandler` WARN for the rethrow.
- [ ] **Step 5: Update docs.** `docs/dev/architecture.md`, the `web` row: "A failed page request gets
  `templates/error.html` through Spring Boot's error page; `ErrorAdvice` answers core exceptions and a
  `ResponseStatusException`'s reason as plain text."
- [ ] **Step 6: Commit.** `fix(web): show a themed error page instead of the Whitelabel page`.

### Task 4: The pages show a failure's body only when it is plain text

**Files:** Create `src/main/resources/static/js/failure-text.js`, `ErrorBoundaryE2eTest`. Modify `app.js`,
`remote-transport.js`, `setup.js`, `play-sheet.js`, `docs/dev/architecture.md`. Test `StaticAssetsTest`.

**Interfaces:** `failureText(status, contentType, body, fallback)` and the exported sentences `NOT_HERE` ("That is no
longer here. Reload the page."), `SERVER_FAILED` ("Something went wrong on the server. Its log has the details.") and
`NOT_UNDERSTOOD` ("The server could not use this request. Reload the page.").

```js
// What a failed request may show: the server's plain-text reason, else a sentence of our own. An error page or a
// JSON error body is never shown as it is.
export function failureText(status, contentType, body, fallback) {
    if (/^text\/plain\b/i.test(contentType ?? "")) return body || fallback;
    if (status === 404 || status === 405) return NOT_HERE;
    if (status >= 500) return SERVER_FAILED;
    return status >= 400 ? NOT_UNDERSTOOD : fallback;
}
```

- [ ] **Step 1: Write the failing test.**
  - `StaticAssetsTest.servesTheEsModulesTheDashboardLoads` also expects `/js/failure-text.js` to contain `export
    function failureText`.
  - `ErrorBoundaryE2eTest extends E2eApplicationTest`:
    - `anUnknownPageShowsTheThemedErrorPageWithAWayBack`: `/no-such-page` shows the heading "This page is not here.",
      and "Back to Home" leaves it. The session records no CSP violation.
    - `aFailureWithoutAPlainTextReasonToastsASentenceOfOurOwn`: on `/`, `page.evaluate("async () => (await
      import('/js/remote-transport.js')).sendKey('ghost', 'HOME', { repeat: 'x' }).then(() => 'sent', (e) =>
      e.message)")` returns `The server could not use this request. Reload the page.` (Spring answers the type mismatch
      with its JSON error body).
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.StaticAssetsTest'`
  gets a 404, and `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*ErrorBoundary*'` fails the second test with Spring's
  JSON as the message.
- [ ] **Step 3: Implement.** `remote-transport.js`'s `post` throws `new Error(failureText(response.status,
  response.headers.get("Content-Type"), await response.text(), "The device is not connected"))`. `app.js`'s
  `htmx:responseError` passes `xhr.status`, `xhr.getResponseHeader("Content-Type")` and `xhr.responseText` with the same
  fallback; `setup.js` does so with "The test could not run". `play-sheet.js`'s `readJsonOrText` answers `{ message:
  failureText(response.status, type, await response.text(), "Cannot play this") }` for a body that is not JSON.
  Plain-text reasons, which every refusal is, show exactly as before.
- [ ] **Step 4: Run the tests.** The Step 2 commands, then `scripts/e2e.sh` on all three engines
  (`DeviceControlsE2eTest` and `PlaySheetFailureE2eTest` pin that plain-text reasons still show), then
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/dev/architecture.md`, the paragraph on `static/js`: "`failure-text.js` decides what
  a failed request may show: a plain-text body as it is, otherwise a sentence of its own."
- [ ] **Step 6: Commit.** `fix(web): toast a failure's body only when it is plain text`.

### Task 5: The device flow shows Google's code, never its words, and links only to Google

**Files:** Modify `GoogleOAuthClient.java`, `YouTubeAuthorizationService.java`, `docs/user/sources.md`. Test
`GoogleOAuthClientTest`, `YouTubeAuthorizationServiceTest`.

**Interfaces:**

- `record TokenPoll.Failed(String error, String message)`: the second component, renamed from `description`, is Home
  Control's own words.
- `errorCode(Response)` returns the code only when it matches `[a-z][a-z0-9_]{0,63}`, else `""`, which `poll` already
  answers with `failure(response)`: "Google answered HTTP <status>".
- `private static String refusal(String error)` (new): `invalid_grant` gives "Google no longer accepts this sign-in
  code. Start again."; `unauthorized_client` gives "Google refused the authorization (unauthorized_client). Check that
  the OAuth client is of type “TVs and Limited Input devices”, then start again."; any other code "Google refused the
  authorization (<code>)". `no_refresh_token` keeps its sentence and adds "Start again and accept the consent request."
- `static URI verificationPage(String given)` (new): `DEFAULT_VERIFICATION_URL` unless `given` parses with scheme
  `https`, host `www.google.com` or `google.com`, no user info and no port.

- [ ] **Step 1: Write the failing test.**
  - `GoogleOAuthClientTest`:
    - `anUnknownErrorIsFailed` becomes `anInvalidGrantIsFailedInHomeControlsWords`: `error()` is `invalid_grant`,
      `message()` its sentence, and `Malformed auth code.` appears nowhere.
    - `aDescriptionIsNeverEchoed`: 400 `{"error":"unauthorized_client","error_description":"client_secret=canary-77 was
      rejected"}` gives a message with `(unauthorized_client)` and without `canary-77`.
    - `anErrorThatIsNoCodeIsNotShown`: 400 `{"error":"<b>ApiKey=abc</b>"}` throws `YouTubeException` `Google answered
      HTTP 400`.
    - `aVerificationPageOffGoogleIsReplacedByGooglesOwn`, parameterized over `https://evil.example/device`,
      `http://www.google.com/device`, `https://www.google.com.evil.example/device`,
      `https://user@www.google.com/device`, `https://www.google.com:8443/device` and `not a url`: `verificationUrl()` is
      `DEFAULT_VERIFICATION_URL`. `aVerificationPageOnGoogleIsKept`: `https://google.com/device` stays.
  - `YouTubeAuthorizationServiceTest.aFailedResultEndsThePending` expects the `invalid_grant` sentence;
    `aFailedResultWithoutADescriptionNamesOnlyTheError` keeps `Google refused the authorization (invalid_scope)`.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.sources.youtube.GoogleOAuthClientTest' --tests
  'dev.andre.homecontrol.sources.youtube.YouTubeAuthorizationServiceTest'` does not compile on `message()`; with the
  rename alone it fails on the echoed descriptions and the kept foreign page.
- [ ] **Step 3: Implement.** `poll`'s default branch logs `log.debug("Google refused the device authorization ({}): {}",
  error, RedactedUris.inText(description))` (a new SLF4J logger) and returns `new TokenPoll.Failed(error,
  refusal(error))`. `requestDeviceCode` uses `verificationPage(json.path("verification_url").asString(""))`, which
  catches `IllegalArgumentException` and logs a WARN without the URL when it falls back.
  `YouTubeAuthorizationService.pollOnce` finishes with `finish(State.FAILED, message)`.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.web.YouTube*'`, then `scripts/gradle.sh
  build`.
- [ ] **Step 5: Update docs.** `docs/user/sources.md`, YouTube's **Troubleshooting**: with a device code, Setup names
  Google's error code, e.g. `Google refused the authorization (unauthorized_client)`, never Google's explanation, which
  can quote the request; `unauthorized_client` means the client is not of type TVs and Limited Input devices. The code
  is always entered on Google's own device page.
- [ ] **Step 6: Commit.** `fix(youtube): show Google's error code on Setup, never its description`.

### Task 6: Jellyfin setup says where the token goes and how to revoke it

**Files:** Modify `src/main/resources/templates/fragments/jellyfin-setup.html`, `docs/user/sources.md`,
`docs/user/security.md`. Test `JellyfinSetupControllerTest`.

- [ ] **Step 1: Write the failing test.** In `JellyfinSetupControllerTest` (a `WebSliceTest`):
  `theSignInChoiceSaysWhereTheTokenGoes` (`GET /setup` contains `TVs, speakers and VLC receive the Jellyfin token inside
  every stream address`) and `anApiKeyConnectionSaysEveryPlayerGetsAnAdministratorKey` (with `jellyfinSetup.settings()`
  stubbed to an API-key `JellyfinSettings`, built as `JellyfinSetupSectionTest.connected` is, the page contains
  `administrator key to every TV, speaker` and `revoke it in Jellyfin's dashboard`).
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.sources.jellyfin.JellyfinSetupControllerTest'`: neither text is on the page.
- [ ] **Step 3: Implement.** Under the "Sign in with" select, a `<p class="hint">`: "TVs, speakers and VLC receive the
  Jellyfin token inside every stream address. A user login gives them that user's access, and Disconnect or Reconnect
  revokes it. An API key is an administrator key that does not expire." The connected summary's API-key hint becomes
  "API-key mode hands an administrator key to every TV, speaker, Cast receiver and VLC that plays from Jellyfin. Prefer
  a user login; when you stop using the key, revoke it in Jellyfin's dashboard." No logic changes.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.*'
  --tests 'dev.andre.homecontrol.sources.jellyfin.*'`, `scripts/e2e.sh -Pe2eBrowsers=chromium --tests '*SetupFlows*'`,
  then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/user/sources.md`, "Connecting Jellyfin": replace the **Sign in with** bullet. In
  both modes, DLNA TVs and speakers, the Cast Default Media Receiver and VLC get the token inside each stream address,
  and the Jellyfin Cast receiver in its play request; a cheap device may log it. A user login limits that to the user's
  own access, and Home Control revokes the old token on Disconnect and Reconnect. An API key is an administrator key
  that never expires: revoke it under Jellyfin's **Dashboard → API Keys** when you stop using it or switch to a user
  login. This replaces "a token scoped to that session". `docs/user/security.md`, "Secrets": the Jellyfin token is the
  one credential that leaves the server, because the players need it, with a link to that section.
- [ ] **Step 6: Commit.** `fix(jellyfin): say that stream addresses carry the token to every player`.

### Task 7: Canary secrets across the whole app

**Files:** Create `src/test/java/dev/andre/homecontrol/web/SecretsStayOnTheServerEndToEndTest.java`. Modify
`docs/dev/testing.md`. Main code changes only if the walk finds a leak (Step 3).

- [ ] **Step 1: Write the failing test.** `SecretsStayOnTheServerEndToEndTest extends FullAppTest`, with
  `@ExtendWith(OutputCaptureExtension.class)` (not a Spring annotation, so `SharedContextRulesTest` stays green). It
  keeps every response (status line, headers, body) and every SSE line, as `JellyfinEndToEndTest.send` keeps bodies.
  - `noCanarySecretReachesTheBrowser(CapturedOutput output)`, one journey (`@SuppressWarnings("java:S5961")`, as
    `JellyfinEndToEndTest` has):
    1. **Seed every kind of secret** through the flow the named test uses: login and Jellyfin passwords (canaries such
       as `canary-login-7d41c2`) through `POST /setup/sources/jellyfin` on `FakeJellyfinServer.withConnectableServer()`,
       whose token is `FakeJellyfinServer.ACCESS_TOKEN` (`SpeakerJellyfinEndToEndTest`); `FakeTmdbServer.READ_TOKEN`
       through `POST /setup/sources/tmdb` (`StreamingLaunchersEndToEndTest`); a YouTube client secret canary and the
       `oauth-token-granted.json` tokens through the device flow on `SharedFakes.google()` (`YouTubeEndToEndTest`); a
       workflow whose call sends `Authorization: canary-workflow-5b2e` (`WorkflowSetupControllerTest`'s form fields);
       and an LG and a Samsung TV adopted with `keyRef` settings, their keys stored with `SecretStore.putDeviceSecret`
       as `device.webos.<ref>.client-key` and `device.tizen.<ref>.token`, the names their pairing writes.
    2. **Echo request content in faults:** a `FakeUpnpRenderer` with `fail("SetAVTransportURI", 714, "Illegal MIME-type
       for {CurrentURI}", 2)`; a `FakeCastReceiver` that speaks the Jellyfin receiver's namespace, with
       `failNextLoad("Cannot load {contentId}")` and `answerCustom("urn:x-cast:com.connectsdk", …)` set to an `error`
       whose `message` quotes `{"accessToken":"<ACCESS_TOKEN>"}`.
    3. **Play:** `POST /devices/<speaker>/play-attempt` and `/play` for a Jellyfin track (502, a body with `?…`); on the
       Cast device, play-attempts with a growing `skip` until the routes tried cover the Jellyfin receiver and
       `cast:CC1AD845`. Assert that each fake received the stream, so the echo happened.
    4. **Walk every GET mapping** of the `requestMappingHandlerMapping` bean. A `WALK` map gives each pattern its URLs:
       every device id for `{id}`, every rail of `RailCache.snapshots()`, the item and track for `source` and `item`
       parameters, the default theme and its stylesheet for the theme patterns, the saved workflow for
       `/setup/workflows/{id}`, `?q=bunny` for search, and so on. Pages are requested with `Accept: text/html`.
    5. **The error page and the event stream:** `GET /no-such-page` as a page, then `/events` with the browser's
       cookies, read with `EventStreamReader` until the `rails` event, then `shutdownNow()`.
    6. **Assert** that no canary, matched with non-alphanumeric boundaries so a short value cannot match inside a
       number, appears in any recorded line or in `output.getAll()` (decision 4). A failure names the canary and the
       request.
  - `everyGetMappingIsWalkedOrExcused`: every GET pattern is a key of `WALK` or of `NOT_WALKED`, a map from pattern to
    reason (`/events`: read as a stream; `/error`: reached through the 404 page). It lists the patterns that are
    neither.
- [ ] **Step 2: Run it to see it fail.** `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.web.SecretsStayOnTheServerEndToEndTest'`. With PRs 1 to 4 merged it passes, so prove it bites:
  locally replace `ForeignText.clean(message)` with `message` in `DeviceRefusedException` and `SoapFault.describe`, and
  expect failures naming `FakeJellyfinServer.ACCESS_TOKEN` in the play-attempt bodies; drop one `WALK` entry and expect
  `everyGetMappingIsWalkedOrExcused` to name it. Restore both before committing.
- [ ] **Step 3: Implement.** No main code when it is green. If the walk finds a real leak, fix it at its source in this
  pull request when that is S, with its own `fix:` commit and test; otherwise stop, record it in the index row and ask
  the owner. Never weaken a canary or excuse a leak.
- [ ] **Step 4: Run the tests.** The Step 2 command, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.testsupport.*'` (`SharedContextRulesTest`, `FullAppResetTest`), then `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** `docs/dev/testing.md`, "Full-application tests": `SecretsStayOnTheServerEndToEndTest`
  walks every GET mapping and the event stream with canary secrets; a new GET mapping needs its URLs in `WALK` or a
  reason in `NOT_WALKED`, and a new kind of secret needs a canary there.
- [ ] **Step 6: Commit.** `test(web): check app-wide that no secret reaches the browser`.

## Done when

- Every finding listed above is resolved, or left out below with its reason: SEC-1 with ARCH-10 and DEV-6, TEST-V1,
  SEC-2 with SRC-15, WEB-5 (parts 1, 3, 4 and 5, part 2 for `ResponseStatusException`), SEC-11 (a) and (b).
- A refusal from any device protocol reaches `DeviceCalls`, `RendererCommands`, the logs and the browser without URL
  queries, user info, keys, control or format characters, and at most 300 characters long. `StreamRedaction` and the
  duplicate `TextWebSocket.withoutQuery` logic are gone.
- A failed page request shows `error.html` with a way back and the CSP header; htmx and fetch failures show a plain-text
  reason or a `failure-text.js` sentence, in all three engines. Setup never shows Google's `error_description`, and its
  device-code link is always on Google. The Jellyfin page and docs say where the token goes and how it is revoked.
- `SecretsStayOnTheServerEndToEndTest` is green, walks every GET mapping, and fails when a canary is let through.
- `scripts/gradle.sh build` is green after each commit, `scripts/e2e.sh` for PRs 2 and 4, and `scripts/check-commits.sh`
  accepts the commits. This package's row in [the index](README.md) is updated.

## Out of scope

- **Proxying Jellyfin media, or per-play capability URLs** (SEC-11's option b): the analysis's "What not to do". L to
  XL, an amendment to ADR 0005, and media bytes through a small board. Only on a user's request, with its own ADR.
- **The Jellyfin Cast receiver's `accessToken`.** Jellyfin's receiver needs it by design (SEC-11 trade-offs).
- **A catch-all exception handler** (WEB-5 part 2's second half): decision 3.
- **Scrubbing known secret values from every response.** It would hold every decrypted secret at the web edge; shape
  rules where device text enters, plus the canary test, cover the echo shapes the findings describe.
- **INFO lines for failed commands and plays, kept causes, documented log levels.** WP-16 (OPS-3), built on
  `ForeignText` and `RedactedUris.inText`. A WebSocket close reason logged by `WebOsSession.lost` goes there too.
- **A redacted "Copy support info"** (WP-20, OPS-4); **toasts in live regions and remote key repeat** (WP-11, WEB-7 and
  WEB-2); **unit tests for `failure-text.js`** (WP-15, WEB-13); **recorded device transcripts** that would settle
  whether real devices echo URLs (WP-32, DEV-14).
- **BlueZ's D-Bus messages in Bluetooth errors.** Host text without URLs, unchanged; Bluetooth is off in the canary
  test's context.
