# Dashboard Shell (Sub-project D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the content half of the dashboard on top of sub-projects A–C: a per-source rail cache refreshed in the background with `rail` SSE events, a phone-first rails layout that never shows a gap for a failed rail, a play sheet that previews the planned route for the selected device before one-tap play and names the failed and the next route on failure, a sources setup section persisted in `/data/sources.json`, a unified debounced search, PWA polish with the vNext v0.4 touchpad mode for the remote drawer, and a Playwright for Java browser-test harness that runs outside the normal `build`.

**Architecture:** A new `content` package (service layer, like `playback`) owns `RailCache` (entries per rail, virtual-thread fetches, one scheduler thread, `RailUpdatedEvent`/`RailsChangedEvent`), `RailPreferences` (which rails, in what order, how often), `SourcePreferencesService` (persisted preferences) and `SearchService` (parallel search with a deadline). `DeviceStateBroadcaster` becomes a generic named-event fan-out that also forwards `rail` and `rails` events. The web layer renders rails, search results and the setup section as Thymeleaf fragments that htmx swaps; small ES modules (`events.js`, `rails.js`, `toast.js`, `play-sheet.js`, `touchpad-gestures.js`, `touchpad.js`, `pwa.js`) add behaviour. The planner gains `routes(item, capabilities)` (every applicable route in preference order) so `PlaybackService` can preview a route with its alternatives and retry "the next one" by skipping route keys. Browser tests live in a separate Gradle source set `e2e` with its own `e2eTest` task, so `build` never resolves Playwright or needs browsers.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle 9.7.1 through `.superpowers/gradle.sh`, Thymeleaf, htmx 2, vanilla ES modules, SSE, virtual threads, Java2D (`java.desktop`, headless) for PNG icons, JUnit 5, AssertJ, Mockito, Awaitility. **One new dependency, test-only, in the `e2e` source set:** `com.microsoft.playwright:playwright:1.63.0` (verified 2026-09-16 on repo1.maven.org: `maven-metadata.xml` `<release>1.63.0</release>`, last updated 2026-09-14; transitive `driver` and `driver-bundle` 1.63.0, gson 2.14.0, Java-WebSocket, `slf4j-simple` — excluded — and `junit-jupiter-engine`, version-aligned by the Spring Boot BOM). Its driver bundles browsers Chromium 153.0.8010.12 (revision 1243) and WebKit 26.6 (revision 2359) and lists `ubuntu26.04-x64`/`-arm64` as supported hosts. Verified 2026-09-16 on this host: the Dockerfile in Task 7 builds from `gradle:jdk25` (Ubuntu 26.04.1), `cli.js install --with-deps chromium webkit` succeeds, and both browsers launch headless and render a page as uid 1000 with `HOME=/tmp` (the `.superpowers/gradle.sh` user). Also verified: a Java2D `BufferedImage` → `ImageIO` PNG renders under `eclipse-temurin:25-jre` (headless=true), which the runtime icon endpoint relies on. `actions/cache` latest major is `v6` (v6.1.0).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §5.3 (route shown before confirming; failure toasts name the failed and the next route; optimistic app links and "the app may not be installed"), §6.1 (device strip, rails in user order, compact error with retry, play sheet, search, setup: sources, rail toggles, refresh intervals, locale/providers), §6.3 (phone-first, PWA rules of vNext §5.6, dashboard at `/`, classic remote at `/remote/{id}`), §7 (rails cached per source with TTL, scheduler, "rail updated" SSE, virtual threads), §8 (`sources.json` with rail preferences), §9 (login gates everything), §12 (Playwright for play sheet, device switching, rail failure states, login gating). vNext spec `docs/superpowers/specs/2026-08-30-shield-remote-vnext-design.md` §5.2–§5.8 (modules, touchpad mode, gesture constants, key endpoint repeat/long press, connection gating, PWA levels, Playwright WebKit + Chromium). Roadmap `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md` section D (D1–D7, GitHub #42–#48, epic #8). Built on plans A `2026-09-16-multi-device-core.md`, B `2026-09-16-google-cast-adapter.md`, C `2026-09-16-jellyfin-source.md`.

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework, no bundler. (Playwright's bundled Node driver is test tooling only and never touches `src/main`.)
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a play request that cannot be routed now fails now with a reason. Nothing is queued. A retry is always a new, user-initiated request.
- Only adapters speak device protocols; only sources speak content APIs. `content`, `web`, `playback` and `core` must not import `adapters.*` or `sources.*` (except configuration wiring that C already has).
- Route by capability, not by brand.
- Honesty about walled gardens: the UI never implies a personalised feed from Netflix, Prime Video or DAZN.
- Secrets raise the bar: login is mandatory once any secret exists (C). Secrets never reach the browser: rails, search, SSE and play-sheet JSON carry only `ContentItemView` fields and route descriptions — never `PlayableRef`s, stream URLs, Cast message bodies or tokens.
- Persistent state stays in `/data` as JSON written atomically; `devices.json`, `keystore.p12`, `secrets.json` keep working.
- Every adapter and source is a Spring `@ConditionalOnProperty` module (D adds none; see Decisions).
- Every adapter has a fake server in tests; every source has recorded JSON fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `ci:`).

Epic constraints:

- Build and test only through the Docker wrapper: `.superpowers/gradle.sh build`; single tests with `.superpowers/gradle.sh test --tests '<pattern>'`; browser tests with `.superpowers/e2e.sh` (Task 7).
- Plans A, B and C are the contract. Where real code differs from their listings (a field name, a helper, a constructor parameter), adapt the edit to the real code, keep the behaviour this plan specifies, and say so in the task report. Names this plan relies on: `ContentSource`, `ContentSources`, `Rail`, `RailDescriptor`, `ContentSourceException`, `ContentItem` (with `progress`), `ContentItemView`, `ContentController` (`/sources`, `/sources/{s}/rails/{r}`, `/search`), `ContentPlayController` (`POST /devices/{id}/play` with `source`+`item`, `GET /devices/{id}/route`), `PlaybackService.plan/play`, `PlaybackPlanner`, `Route` (`OpenAppLink`, `Cast`, `CastMessage`, `JellyfinSession`, `Unroutable`), `Action.CastLoad`, `ActionFailedException`, `DeviceState.nowPlaying`, `JsonFileSourceSettings`, `LoginGateFilter`, `LoginService`, `DashboardController`, `DeviceController`, `DeviceStateBroadcaster`, `StateController`, `dashboard.html`, `static/js/state-view.js`, `remote-transport.js`, `app.js`.
- Page loads never block on an upstream content API: `GET /`, `GET /rails*`, `GET /events` read only the cache.
- `build` must not resolve Playwright or require installed browsers; browser tests run only through `e2eTest`.
- Beans for new services are declared in `@Configuration` classes (not `@Component`), like C's `SecurityConfiguration`; any `@ControllerAdvice` takes dependencies through `ObjectProvider`. `@WebMvcTest` slices that need a new service mock it with `@MockitoBean`.
- Jackson 3 API with defaults (`asString("")`, `asInt(0)`, …) on possibly missing nodes.
- Interactive targets ≥ 44×44 CSS px; icon-only controls have accessible names; respect `prefers-reduced-motion`.
- All wire formats in this plan (SSE `rail`/`rails` payloads, route-preview and play-attempt JSON, `sources.json` `preferences`, manifest, key endpoint parameters, gesture constants) are normative; tests pin them.
- The manual acceptance checklist is never marked passed by an agent.

## Decisions

- Decision: D adds no adapter or source module, so no new `@ConditionalOnProperty` module; the only switch is `home-control.content.rails.scheduler-enabled` (default `true`, `false` in `src/test/resources/application.yaml`) for the background ticker — the cache, preferences and search are core dashboard behaviour and disabling a source is already a user preference (D4) — cost if wrong: add a property later.
- Decision: the rail cache lives in a new `content` service package, not in `core/content` — it does I/O scheduling and publishes Spring events, while `core/content` is C's pure contract — cost if wrong: a package move.
- Decision: `ContentSource` gains `default Duration defaultRefreshInterval()` (15 min); Jellyfin overrides it with 5 min — per-source TTLs belong to the source (YouTube quota wants hourly, Continue watching wants minutes); users override per source in D4 — cost if wrong: one default method.
- Decision: rail refresh = a single platform "ticker" thread every 15 s decides what is due; each fetch runs on a virtual thread, at most 4 concurrent fetches (semaphore), at most one in flight per rail — upstream clients block, virtual threads make that cheap, and a per-rail guard stops a slow server from piling up requests — cost if wrong: tune two properties.
- Decision: a never-loaded rail starts loading when first asked for (page render, JSON read) as well as on the first tick, and the page renders a skeleton that the `rail` SSE event replaces — page loads never wait — cost if wrong: first paint after a restart shows skeletons for a moment.
- Decision: a failed refresh keeps the last good items; the snapshot status is `FAILED` with the error message; retry backoff after failure is `min(interval, 1 min × 2^(failures−1))`; a manual retry ignores backoff — stale content beats an error card, and the user still sees that refresh failed — cost if wrong: stale items look current apart from the small "couldn't refresh" note.
- Decision: the `rail` SSE event carries only a summary (`sourceId`, `railId`, `status`, `version`, `fetchedAt`, `error`, `refreshing`); the browser fetches the server-rendered fragment `GET /rails/{sourceId}/{railId}` when the version differs — keeps SSE small, keeps HTML rendering in Thymeleaf, and a reconnecting tab catches up because `/events` sends one summary per rail on subscribe — cost if wrong: one extra GET per changed rail.
- Decision: layout changes (rail added, removed or reordered) publish a separate `rails` event; the browser re-fetches `GET /rails` — reordering in setup shows up on open dashboards — cost if wrong: none.
- Decision: an empty but successful rail shows a one-line "Nothing here right now" card rather than disappearing — rails must not jump around as data changes; the "never a gap" rule is about layout stability — cost if wrong: users hide the rail in setup.
- Decision: the dashboard main area becomes rails (and search); the A/B remote UI moves unchanged into a drawer (`<aside id="remote-drawer">`) that opens from a chevron on the selected chip, from `/remote/{id}` (now redirecting to `/?device={id}&remote=open`) or from `?remote=open`; on ≥ 64 rem it is a right-hand column, on phones a bottom sheet — spec §6.1 — cost if wrong: CSS only.
- Decision: tapping a device chip remains a full navigation to `/?device={id}` (cheap now that rails come from the cache); the play sheet's device switcher changes only the sheet's target — keeps the server-rendered drawer correct without client-side templating — cost if wrong: switching in the sheet does not move the drawer; the user taps the chip.
- Decision: the play sheet uses two new JSON endpoints beside C's text endpoints instead of content negotiation on the same path: `GET /devices/{id}/route-preview?source=&item=` and `POST /devices/{id}/play-attempt` (`source`, `item`, repeated `skip`) — two handlers on one path distinguished only by `produces` are ambiguous for `Accept: */*` — cost if wrong: two extra paths.
- Decision: "the next route" = the planner's next applicable route in spec §5.3 order after resolving the item once; `PlaybackPlanner.routes(item, capabilities)` returns every route a strategy yields, `plan` stays "first or `Unroutable`"; a retry sends the failed route keys as `skip` and the server re-plans without them — nothing is remembered server-side, so commands stay ephemeral — cost if wrong: a resolver whose answer changed between attempts may offer a different next route (acceptable; the preview is re-read).
- Decision: route key (stable, browser-visible, secret-free) = `app-link`, `cast:<receiverAppId>`, `cast-message:<receiverAppId>`, `jellyfin-session`, from an exhaustive `switch` in `core/playback/RouteKeys` — later route variants (I's renderer) get a compile error until they add a key — cost if wrong: none.
- Decision: the "app may not be installed" hint is client-side: after an `app-link` route succeeds, `play-sheet.js` remembers the device's `currentApp` and `nowPlaying` from the last `state` event and shows a toast if neither changes within 5 s — the server cannot enumerate apps (spec §5.3); the wording is soft ("If nothing started on …") because an app already in the foreground does not change — cost if wrong: an occasional unnecessary hint.
- Decision: failure toasts carry an action button "Try <next route>" and stay 10 s; plain toasts stay 4 s — cost if wrong: timing constants.
- Decision: rail preferences live in the existing `sources.json` under a new optional top-level `preferences` object, owned by C's `JsonFileSourceSettings` (which is changed to preserve it); file `version` stays 1 because the addition is optional — one owner per file avoids two writers clobbering each other — cost if wrong: an older release rewriting the file drops preferences (downgrades only).
- Decision: disabling a source in setup hides its rails and removes it from search, but does not unregister its setup or block playing an item already on screen; the Spring module switch (`home-control.<source>.enabled`) remains the way to remove a source entirely — cost if wrong: none.
- Decision: default locale `de-DE` and watch region `DE` (spec §3 assumption 4), both overridable by `home-control.content.locale`/`region` and on the setup page; providers are chosen from a fixed list (`netflix`, `primevideo`, `dazn`, `disneyplus`, `appletvplus`, `paramountplus`, `wowtv`, `joyn`, `rtlplus`) whose keys match `AppLinks` service keys where they exist; D stores them, G and H consume them — cost if wrong: extend the list.
- Decision: rail order UI = "Move up / Move down / Show / Hide" buttons with plain form posts (no drag and drop) — works without JS and with keyboards, and needs no client framework — cost if wrong: more taps for long lists.
- Decision: all preference endpoints live under `/setup/sources/preferences/…` so C's `LoginGateFilter.ALWAYS_GUARDED` prefix `/setup/sources` applies the cross-origin check even before a login exists — cost if wrong: none.
- Decision: unified search keeps C's JSON `/search` shape but queries sources in parallel on virtual threads with an 8 s overall deadline (a late source becomes an `errors` entry "<Name> did not answer in time"), skips disabled sources, and adds the HTML fragment `GET /search/results?q=` driven by htmx `hx-trigger="input changed delay:350ms, search"` with `hx-sync="this:replace"` — htmx's delay trigger is the client-side debounce and `replace` drops stale answers, with no extra JS — cost if wrong: a debounce module later.
- Decision: search results render as one horizontal rail per source using the rail tile fragment, and tapping a result opens the same play sheet — spec §6.1 — cost if wrong: none.
- Decision: PNG icons (192, 512, maskable 512, Apple touch 180) are rendered at runtime by `web/IconController` with Java2D from one geometry and cached in memory, plus a hand-written `static/icons/icon.svg` — no binary files, no external tools, no generator step to drift; verified under the runtime JRE image — cost if wrong: switch to committing PNGs produced by the same renderer.
- Decision: manifest is served by `web/PwaController` as `application/manifest+json` (no reliance on MIME mappings); `/manifest.webmanifest`, `/icons/**` and `/offline.html` are added to the login gate's open paths because manifest and icon fetches are credential-less (C's decision explicitly deferred this to D6) — cost if wrong: none; they contain no data.
- Decision: a minimal service worker is registered only when `location.protocol === "https:"`; it pre-caches only `/offline.html`, `/app.css` and `/icons/icon.svg`, handles only `GET` navigations (network first, offline page on network failure) and never calls `respondWith` for anything else — so SSE, POSTs, JSON, fragments and the login redirect pass straight through; because nothing versioned is cached there is no stale UI, so it uses `skipWaiting` instead of vNext's explicit update button — cost if wrong: add versioned pre-caching and the update prompt later.
- Decision: touchpad mode implements vNext §5.3–§5.5 for the drawer — mode switch persisted in `localStorage` key `homecontrol.remote.mode.v1` (`buttons` default, `touchpad`), Pointer Events state machine with the vNext constants (12 px tap slop, 24 px swipe threshold, +1 step per 56 px, max 4 steps, 450 ms hold), adaptive landscape split, gesture cancel on state/stream loss; the secondary-control show/hide/reorder editor is deferred — the roadmap names the touchpad mode, the editor is separable — cost if wrong: a follow-up issue.
- Decision: key endpoint gains `repeat` (1–4, default 1) and `press` (`short` default, `start_long`, `end_long`); core gains `KeyPress` and `Action.PressKey(RemoteKey, KeyPress)` with the one-argument constructor kept; long press is accepted only for `DPAD_*`, `BACK`, `HOME`; `repeat` > 1 only with `short` — vNext §5.4 — cost if wrong: widen `RemoteKey.supportsLongPress()`.
- Decision: browser tests live in a Gradle source set `e2e` (`src/e2e/java`) whose classpath adds `test` output (fakes) and whose dependencies extend `testImplementation`; task `e2eTest` is not wired into `check`/`build`; `installPlaywrightBrowsers` runs Playwright's CLI (`install --with-deps chromium webkit`); `e2eTest` sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` so missing browsers fail fast instead of downloading 900 MB mid-test — cost if wrong: e2e code is compiled only by the CI e2e job and `.superpowers/e2e.sh`, so a refactor can break it until that job runs (it runs on every PR).
- Decision: locally, `.superpowers/e2e.sh` builds (once per Playwright version) `home-control-e2e:playwright-<version>` from `.superpowers/e2e.Dockerfile` (`FROM gradle:jdk25`, browsers in `/ms-playwright`, readable by any uid) and runs `.superpowers/gradle.sh e2eTest` in it via a new `HC_GRADLE_IMAGE` override — verified on this host, including non-root launch — cost if wrong: ~1 GB image.
- Decision: CI gets a separate `e2e` job on `ubuntu-latest` (setup-java 25, setup-gradle, `actions/cache@v6` for `~/.cache/ms-playwright`, `./gradlew installPlaywrightBrowsers` using passwordless sudo for apt, `./gradlew e2eTest`, reports and traces uploaded); `release` needs `[test, image, e2e]` — browser regressions in the play sheet or login gate must block a release, and the build job stays as fast as today — cost if wrong: a flaky browser test blocks a release until re-run.
- Decision: every browser test runs in Chromium and WebKit (`-Pe2eBrowsers=chromium` narrows locally) with a phone viewport (390×844, touch enabled); each test's Playwright trace is written to `build/e2e-artifacts/` — vNext §5.8, roadmap D7 — cost if wrong: doubled run time (~2–3 min).
- Decision: e2e tests boot the real application (`@SpringBootTest(RANDOM_PORT)`) with an in-process `FakeDeviceAdapter` (adapter id `e2e-fake`, capabilities and failures from the device's adapter settings) and a `FakeContentSource` (`e2e`, a healthy rail, a rail that fails until healed, searchable), plus C's real login — the four scenarios need deterministic devices and content, not protocols, which A–C already cover with fakes — cost if wrong: none.

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`, browser tests under `src/e2e/java/dev/andre/homecontrol/e2e/`. Paths are relative to those roots unless they start with `src/`, `docs/`, `.github/`, `.superpowers/` or are top-level files.

### Files to create

- `content/ContentConfiguration.java` — beans: properties, preferences, rail cache, search service, preference service.
- `content/ContentProperties.java` — `home-control.content.*` (rails, search, locale defaults).
- `content/RailStatus.java`, `content/RailSnapshot.java` — one cached rail.
- `content/RailUpdatedEvent.java`, `content/RailsChangedEvent.java` — Spring events for SSE.
- `content/RailPreferences.java` — which rails, order, interval, source enabled.
- `content/DefaultRailPreferences.java` — D1 behaviour before D4 (deleted in Task 4).
- `content/RailCache.java` — the cache and scheduler.
- `web/RailEventView.java` — SSE payload.
- `web/RailView.java`, `web/RailController.java` — rail fragments and retry.
- `src/main/resources/templates/fragments/rails.html` — rails, rail, tile fragments.
- `src/main/resources/static/js/events.js`, `rails.js`, `toast.js`, `play-sheet.js`, `touchpad-gestures.js`, `touchpad.js`, `pwa.js`.
- `core/playback/RouteKeys.java` — stable route keys.
- `playback/PlaybackPreview.java`, `playback/PlayAttempt.java` — preview and attempt results.
- `web/RouteView.java`, `web/RoutePreviewView.java`, `web/PlayResultView.java` — play-sheet JSON.
- `core/content/SourcePreferences.java`, `core/content/StreamingProviders.java` — persisted preferences and the provider list.
- `content/SourcePreferencesService.java`, `content/SourcePreferencesChangedEvent.java`, `content/StoredRailPreferences.java`.
- `web/SourcesSetupController.java`, `web/SourcesSetupAdvice.java`, `web/SourcesSetupView.java`, `src/main/resources/templates/fragments/sources-setup.html`.
- `content/SearchService.java`, `content/SearchOutcome.java`; `web/SearchController.java`, `src/main/resources/templates/fragments/search.html`.
- `core/KeyPress.java`.
- `web/PwaController.java`, `web/IconController.java`, `web/IconRenderer.java`; `src/main/resources/static/icons/icon.svg`, `src/main/resources/static/sw.js`, `src/main/resources/static/offline.html`.
- `.superpowers/e2e.Dockerfile`, `.superpowers/e2e.sh`.
- `src/e2e/java/dev/andre/homecontrol/e2e/` — `BrowserTest.java` (meta-annotation), `Browsers.java`, `E2eApplicationTest.java` (base), `FakeDeviceAdapter.java`, `FakeContentSource.java`, `E2eFakesConfiguration.java`, `PlaySheetE2eTest.java`, `DeviceSwitchingE2eTest.java`, `RailFailureE2eTest.java`, `LoginGatingE2eTest.java`, `TouchpadE2eTest.java`.
- `docs/superpowers/reviews/2026-09-16-dashboard-shell-acceptance.md` — manual checklist, all pending.
- Tests: `content/RailCacheTest.java`, `content/DefaultRailPreferencesTest.java`, `web/RailEventViewTest.java`, `web/RailControllerTest.java`, `core/playback/RouteKeysTest.java`, `web/ContentPlayPreviewTest.java`, `core/content/SourcePreferencesTest.java`, `content/SourcePreferencesServiceTest.java`, `content/StoredRailPreferencesTest.java`, `web/SourcesSetupControllerTest.java`, `content/SearchServiceTest.java`, `web/SearchControllerTest.java`, `web/PwaControllerTest.java`, `web/IconControllerTest.java`.

### Files to modify

- `core/content/ContentSource.java` — `defaultRefreshInterval()` (Task 1).
- `sources/jellyfin/JellyfinContentSource.java` — 5-minute interval (Task 1).
- `web/DeviceStateBroadcaster.java`, `web/StateController.java` — named events, rail summaries on subscribe (Task 1).
- `web/ContentController.java` — rails from the cache, refresh endpoint (Task 1); search through `SearchService` (Task 5).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.content.*` (Tasks 1, 4, 5).
- `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css`, `static/js/state-view.js`, `static/js/app.js` — rails, drawer, sheet, search, PWA, touchpad (Tasks 2, 3, 5, 6).
- `core/playback/PlaybackPlanner.java`, `playback/PlaybackService.java`, `web/ContentPlayController.java` — routes list, preview, attempt (Task 3).
- `storage/JsonFileSourceSettings.java` — `preferences` (Task 4); `templates/setup.html` — sources and install sections (Tasks 4, 6); `content/ContentConfiguration.java` — stored preferences (Task 4).
- `core/Action.java`, `core/RemoteKey.java`, `adapters/androidtv/AndroidTvSession.java`, `adapters/androidtv/protocol/RemoteConnection.java`, `web/DeviceController.java`, `static/js/remote-transport.js` — key press kinds and repeat (Task 6).
- `security/LoginGateFilter.java`, `templates/login.html` — open PWA paths, manifest link (Task 6).
- `README.md` — dashboard, install, browser tests (Tasks 6, 7).
- `build.gradle.kts`, `.superpowers/gradle.sh`, `.github/workflows/ci.yml` — e2e source set, tasks, image override, CI job (Task 7).
- Tests of the above: `web/DeviceStateBroadcasterTest.java`, `web/StateControllerTest.java`, `web/ContentControllerTest.java`, `web/DashboardPageTest.java`, `web/StaticAssetsTest.java`, `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`, `web/ContentPlayControllerTest.java`, `storage/JsonFileSourceSettingsTest.java`, `core/ActionTest.java`, `web/DeviceControllerTest.java`, `adapters/androidtv/protocol/RemoteConnectionTest.java`, `adapters/androidtv/protocol/FakeRemoteServer.java`, `web/LoginGatingTest.java`, `sources/jellyfin/JellyfinContentSourceTest.java`.

### Files to delete

- `content/DefaultRailPreferences.java` and `content/DefaultRailPreferencesTest.java` (Task 4, replaced by `StoredRailPreferences`).

---
### Task 1: D1 · Rail cache and scheduler

**Files:**
- Create: `content/ContentProperties.java`, `content/RailStatus.java`, `content/RailSnapshot.java`, `content/RailUpdatedEvent.java`, `content/RailsChangedEvent.java`, `content/RailPreferences.java`, `content/DefaultRailPreferences.java`, `content/RailCache.java`, `content/ContentConfiguration.java`, `web/RailEventView.java`
- Modify: `core/content/ContentSource.java`, `sources/jellyfin/JellyfinContentSource.java`, `web/DeviceStateBroadcaster.java`, `web/StateController.java`, `web/ContentController.java`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `content/RailCacheTest.java`, `content/DefaultRailPreferencesTest.java`, `web/RailEventViewTest.java`, `web/DeviceStateBroadcasterTest.java`, `web/StateControllerTest.java`, `web/ContentControllerTest.java`, `sources/jellyfin/JellyfinContentSourceTest.java`

**Interfaces:**
- Consumes: `ContentSources.all()/find(id)`, `ContentSource.available()/rails()/rail(id)/displayName()`, `Rail(descriptor, items, fetchedAt)`, `RailDescriptor(sourceId, id, title)`, `ContentSourceException` (C3); `ContentItemView.of` (C3); `DeviceStateBroadcaster.sendData/register/subscribe/unsubscribe` (A7).
- Produces:
  - `ContentSource.defaultRefreshInterval()` → `Duration` (default 15 min; Jellyfin 5 min).
  - `record ContentProperties(Rails rails)` bound to `home-control.content` with `record Rails(boolean schedulerEnabled, Duration tick, Duration retryAfterFailure, int maxConcurrentFetches, Map<String, Duration> refreshIntervals)`. (Task 4 adds `locale`, `region`; Task 5 adds `Search search`.)
  - `enum RailStatus { LOADING, READY, FAILED }`.
  - `record RailSnapshot(RailDescriptor descriptor, RailStatus status, List<ContentItem> items, Instant fetchedAt, String error, boolean refreshing, long version)` with `sourceId()`, `railId()`, `key()` (`sourceId + "/" + railId`), `hasItems()`.
  - `record RailUpdatedEvent(RailSnapshot snapshot)`; `record RailsChangedEvent(List<String> keys)`.
  - `interface RailPreferences { List<RailDescriptor> rails(List<ContentSource> sources); Duration refreshInterval(ContentSource source); default boolean sourceEnabled(String sourceId) { return true; } }`.
  - `class DefaultRailPreferences implements RailPreferences` (constructor `ContentProperties`).
  - `class RailCache implements SmartLifecycle` — `RailCache(ContentSources, RailPreferences, ApplicationEventPublisher, Clock, ContentProperties, ExecutorService fetches)`; `List<RailSnapshot> snapshots()` (reconciles, starts loads for never-loaded rails, never blocks); `List<RailSnapshot> peek()` (no reconcile, no loads); `Optional<RailSnapshot> snapshot(String sourceId, String railId)`; `Optional<RailSnapshot> refresh(String sourceId, String railId)`; `void tick()`; `void reconcile()`.
  - SSE event `rail`, JSON `{"sourceId":"jellyfin","railId":"resume","status":"READY","version":7,"fetchedAt":"2026-09-16T09:00:00Z","error":null,"refreshing":false}`; SSE event `rails`, JSON `{"rails":["jellyfin/resume","jellyfin/next-up"]}`. A new `/events` subscriber receives one `state` event per device (A7), then one `rail` event per cached rail.
  - `GET /sources/{sourceId}/rails/{railId}` now reads the cache: `READY` → 200; `LOADING` → 202; `FAILED` with items → 200 with `error`; `FAILED` without items → 502 `text/plain` error; unknown source → 404 `No content source <id>`; unknown rail → 404 `<Source name> has no rail '<railId>'`. JSON body `{"sourceId","id","title","status","fetchedAt","error","items":[ContentItemView…]}`.
  - `POST /sources/{sourceId}/rails/{railId}/refresh` → 202 with the same JSON (status as cached, `refreshing` implied by the next event) / 404.

**Cache rules (normative).**
- Rail set and order = `preferences.rails(sources.all())`. `reconcile()` adds an entry per new descriptor (status `LOADING`, version from a global counter, due now), drops entries whose key vanished, and publishes `RailsChangedEvent(keys)` when the ordered key list changed.
- A fetch starts only if no fetch for that rail is in flight. Starting marks the snapshot `refreshing=true` (new version, event published). The fetch runs on the `fetches` executor, holding one of `maxConcurrentFetches` semaphore permits.
- Success → `READY`, items and `fetchedAt` from the `Rail` (fall back to `clock.instant()` when null), `error=null`, failures reset, next due = now + `preferences.refreshInterval(source)`.
- Failure → `FAILED`, items and `fetchedAt` kept from the previous snapshot, `error` = `ContentSourceException.getMessage()`; `IllegalArgumentException` → `<Source name> no longer offers <rail title>`; source missing → `<sourceId> is switched off`; any other `RuntimeException` → `<Source name> could not load <rail title>` (logged at WARN with the exception, message not shown). `failures++`; next due = now + `min(interval, retryAfterFailure × 2^(failures−1))`.
- A result for an entry that was removed or replaced meanwhile is discarded.
- `tick()` = `reconcile()` then start every rail whose due time ≤ now. The scheduler thread calls `tick()` every `tick` (default 15 s, initial delay 0) when `schedulerEnabled`; exceptions in a tick are logged, never kill the schedule.
- `snapshots()` = `reconcile()`, start every `LOADING` rail without a fetch in flight, return the current snapshots in order. `refresh()` starts a fetch regardless of due time and backoff.
- Events are published outside any lock.

- [ ] **Step 1: Configuration**

Append to `src/main/resources/application.yaml` (merge into the existing `home-control:` block that C created):

```yaml
home-control:
  content:
    rails:
      # Background refresh; page loads never wait for it.
      scheduler-enabled: true
      tick: 15s
      retry-after-failure: 1m
      max-concurrent-fetches: 4
      # Per-source override of the source's default refresh interval, e.g. jellyfin: 10m
      refresh-intervals: {}
```

Append the same block to `src/test/resources/application.yaml` with `scheduler-enabled: false`.

- [ ] **Step 2: Write the failing tests**

`src/test/java/dev/andre/homecontrol/content/RailCacheTest.java`:

```java
package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RailCacheTest {

    /** Mutable clock for due-time tests. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-16T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    /** Runs tasks only when told to, so "in flight" is observable. */
    static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> queued = new ArrayList<>();
        @Override public void execute(Runnable command) { queued.add(command); }
        void runAll() { List<Runnable> now = new ArrayList<>(queued); queued.clear(); now.forEach(Runnable::run); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    static final class StubSource implements ContentSource {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;
        volatile boolean available = true;
        final Clock clock;
        StubSource(Clock clock) { this.clock = clock; }
        @Override public String id() { return "stub"; }
        @Override public String displayName() { return "Stub"; }
        @Override public boolean available() { return available; }
        @Override public List<RailDescriptor> rails() {
            return available ? List.of(new RailDescriptor("stub", "a", "Rail A"), new RailDescriptor("stub", "b", "Rail B")) : List.of();
        }
        @Override public Rail rail(String railId) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return new Rail(new RailDescriptor("stub", railId, "Rail " + railId),
                    List.of(new ContentItem("i-" + calls.get(), "stub", ContentKind.MOVIE, "Item", null, null, List.of())),
                    clock.instant());
        }
        @Override public Optional<ContentItem> item(String itemId) { return Optional.empty(); }
        @Override public Duration defaultRefreshInterval() { return Duration.ofMinutes(10); }
    }

    final TestClock clock = new TestClock();
    final StubSource source = new StubSource(clock);
    final ManualExecutor executor = new ManualExecutor();
    final List<Object> events = new CopyOnWriteArrayList<>();
    final ContentProperties properties = new ContentProperties(
            new ContentProperties.Rails(false, Duration.ofSeconds(15), Duration.ofMinutes(1), 4, Map.of()));
    final RailCache cache = new RailCache(new ContentSources(List.of(source)), new DefaultRailPreferences(properties),
            events::add, clock, properties, executor);

    @AfterEach
    void stop() {
        cache.stop();
    }

    private RailSnapshot a() {
        return cache.snapshot("stub", "a").orElseThrow();
    }

    @Test
    void aPageReadNeverWaitsAndStartsLoadingNeverLoadedRails() {
        List<RailSnapshot> first = cache.snapshots();

        assertThat(first).extracting(RailSnapshot::key).containsExactly("stub/a", "stub/b");
        assertThat(first).allSatisfy(s -> assertThat(s.status()).isEqualTo(RailStatus.LOADING));
        assertThat(source.calls).hasValue(0);
        assertThat(executor.queued).hasSize(2);

        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.READY);
        assertThat(a().items()).hasSize(1);
        assertThat(a().fetchedAt()).isEqualTo(clock.now);
        assertThat(events).filteredOn(RailsChangedEvent.class::isInstance).hasSize(1);
        assertThat(events).filteredOn(RailUpdatedEvent.class::isInstance)
                .extracting(e -> ((RailUpdatedEvent) e).snapshot().status())
                .contains(RailStatus.READY);
    }

    @Test
    void onlyOneFetchPerRailIsInFlight() {
        cache.snapshots();
        cache.snapshots();
        cache.refresh("stub", "a");
        cache.tick();

        assertThat(executor.queued).hasSize(2);
        assertThat(a().refreshing()).isTrue();
    }

    @Test
    void refreshesWhenTheSourceIntervalHasPassed() {
        cache.snapshots();
        executor.runAll();

        clock.advance(Duration.ofMinutes(9));
        cache.tick();
        assertThat(executor.queued).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
    }

    @Test
    void aFailureKeepsTheLastItemsAndIsRetriedWithBackoff() {
        cache.snapshots();
        executor.runAll();
        source.failure = new ContentSourceException("Could not reach Stub (connection refused)");
        clock.advance(Duration.ofMinutes(10));
        cache.tick();
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.FAILED);
        assertThat(a().error()).isEqualTo("Could not reach Stub (connection refused)");
        assertThat(a().items()).hasSize(1);
        assertThat(a().refreshing()).isFalse();

        clock.advance(Duration.ofSeconds(59));
        cache.tick();
        assertThat(executor.queued).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
        executor.runAll();

        clock.advance(Duration.ofMinutes(1));
        cache.tick();
        assertThat(executor.queued).as("second failure waits two minutes").isEmpty();
        clock.advance(Duration.ofMinutes(1));
        cache.tick();
        assertThat(executor.queued).hasSize(2);
    }

    @Test
    void aManualRetryIgnoresBackoffAndRecovers() {
        source.failure = new ContentSourceException("Stub is down");
        cache.snapshots();
        executor.runAll();
        assertThat(a().status()).isEqualTo(RailStatus.FAILED);
        assertThat(a().hasItems()).isFalse();

        source.failure = null;
        cache.refresh("stub", "a");
        executor.runAll();

        assertThat(a().status()).isEqualTo(RailStatus.READY);
        assertThat(a().error()).isNull();
    }

    @Test
    void unexpectedExceptionsBecomeAGenericMessage() {
        source.failure = new IllegalStateException("secret-token-123 leaked in a stack");
        cache.snapshots();
        executor.runAll();

        assertThat(a().error()).isEqualTo("Stub could not load Rail A").doesNotContain("secret");
    }

    @Test
    void railsThatDisappearAreDroppedAndLateResultsDiscarded() {
        cache.snapshots();
        source.available = false;
        cache.reconcile();
        executor.runAll();

        assertThat(cache.snapshots()).isEmpty();
        assertThat(events).filteredOn(RailsChangedEvent.class::isInstance)
                .extracting(e -> ((RailsChangedEvent) e).keys())
                .containsExactly(List.of("stub/a", "stub/b"), List.of());
    }

    @Test
    void versionsIncreaseWithEveryChange() {
        cache.snapshots();
        long loading = a().version();
        executor.runAll();
        assertThat(a().version()).isGreaterThan(loading);
    }

    @Test
    void peekNeitherReconcilesNorLoads() {
        assertThat(cache.peek()).isEmpty();
        assertThat(executor.queued).isEmpty();
    }
}
```

`src/test/java/dev/andre/homecontrol/content/DefaultRailPreferencesTest.java` — cases: `listsRailsOfAvailableSourcesInSourceOrder` (two stub sources, the second unavailable → only the first's rails); `usesThePropertyOverrideElseTheSourceDefault` (`refreshIntervals = {stub: 2m}` → 2 min for `stub`, source default for another); `everySourceIsEnabled`.

`src/test/java/dev/andre/homecontrol/web/RailEventViewTest.java` — case `summarisesWithoutItems`: serialise `RailEventView.of(snapshot)` with a Jackson 3 `JsonMapper` (with `JavaTimeModule` equivalent as Spring configures it — build the mapper via `JsonMapper.builder().build()` and assert on fields, not spacing) → keys exactly `sourceId, railId, status, version, fetchedAt, error, refreshing`; the JSON contains no item title.

`web/DeviceStateBroadcasterTest.java` — add `forwardsRailEventsUnderTheirOwnName`: subclass overriding `sendNamed(SseEmitter, String, Object)` to record `(name, data)`; `onRailUpdated(new RailUpdatedEvent(snapshot))` → awaited record name `rail` with a `RailEventView` whose `railId` matches; `onRailsChanged(new RailsChangedEvent(List.of("stub/a")))` → name `rails`, data `Map.of("rails", List.of("stub/a"))`. Existing tests stay green.

`web/StateControllerTest.java` — add `@MockitoBean RailCache rails`; existing test stubs `rails.peek()` → `List.of()`; new test `aNewSubscriberAlsoGetsOneRailSummaryPerCachedRail`: one device, one READY snapshot → body contains `event:state` before `event:rail` and `"railId":"a"`, and does not contain the item title.

`web/ContentControllerTest.java` — add `@MockitoBean RailCache rails`; replace `servesARailAsViewsWithoutPlayableReferences` so the rail comes from `rails.snapshot("jellyfin","resume")` (READY) and additionally assert `$.status` `READY`, `$.error` null; replace `anUpstreamFailureIs502WithTheReason` with `aFailedRailWithoutItemsIs502WithTheReason` (FAILED, no items → 502 text) and add `aFailedRailWithItemsIsServedWithItsError` (200, `$.error`), `aLoadingRailIs202`, `unknownRailIs404WithTheSourceName` (`sources.find("jellyfin")` present with display name `Jellyfin`, snapshot empty → 404 `Jellyfin has no rail 'nope'`), `refreshStartsAFetchAndAnswers202` (`verify(rails).refresh("jellyfin","resume")`). Verify no test calls `ContentSource.rail` any more (`verify(source, never()).rail(any())`).

`sources/jellyfin/JellyfinContentSourceTest.java` — add `refreshesEveryFiveMinutes`: `defaultRefreshInterval()` = 5 min.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinContentSourceTest'`
Expected: compilation failure — `content` package types do not exist.

- [ ] **Step 4: Implement the model, preferences and properties**

In `core/content/ContentSource.java` add:

```java
    /** How long a loaded rail stays fresh. Users override it per source (D4). */
    default Duration defaultRefreshInterval() {
        return Duration.ofMinutes(15);
    }
```

In `JellyfinContentSource` override it with `Duration.ofMinutes(5)` (Javadoc: "Continue watching changes while the household watches").

`content/ContentProperties.java`:

```java
package dev.andre.homecontrol.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("home-control.content")
public record ContentProperties(@DefaultValue Rails rails) {

    public record Rails(@DefaultValue("true") boolean schedulerEnabled,
                        @DefaultValue("15s") Duration tick,
                        @DefaultValue("1m") Duration retryAfterFailure,
                        @DefaultValue("4") int maxConcurrentFetches,
                        Map<String, Duration> refreshIntervals) {
        public Rails {
            refreshIntervals = refreshIntervals == null ? Map.of() : Map.copyOf(refreshIntervals);
            if (maxConcurrentFetches < 1) {
                throw new IllegalArgumentException("home-control.content.rails.max-concurrent-fetches must be at least 1");
            }
        }
    }
}
```

`content/RailStatus.java`: `public enum RailStatus { LOADING, READY, FAILED }` with a one-line Javadoc per constant.

`content/RailSnapshot.java`:

```java
package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Instant;
import java.util.List;

/** One cached rail as the dashboard shows it. {@code items} are the last good items, even when FAILED. */
public record RailSnapshot(RailDescriptor descriptor, RailStatus status, List<ContentItem> items,
                           Instant fetchedAt, String error, boolean refreshing, long version) {

    public RailSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    static RailSnapshot loading(RailDescriptor descriptor, long version) {
        return new RailSnapshot(descriptor, RailStatus.LOADING, List.of(), null, null, false, version);
    }

    public String sourceId() {
        return descriptor.sourceId();
    }

    public String railId() {
        return descriptor.id();
    }

    public String key() {
        return key(descriptor);
    }

    public static String key(RailDescriptor descriptor) {
        return descriptor.sourceId() + "/" + descriptor.id();
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    RailSnapshot refreshing(long newVersion) {
        return new RailSnapshot(descriptor, status, items, fetchedAt, error, true, newVersion);
    }

    RailSnapshot ready(List<ContentItem> newItems, Instant newFetchedAt, long newVersion) {
        return new RailSnapshot(descriptor, RailStatus.READY, newItems, newFetchedAt, null, false, newVersion);
    }

    RailSnapshot failed(String message, long newVersion) {
        return new RailSnapshot(descriptor, RailStatus.FAILED, items, fetchedAt, message, false, newVersion);
    }
}
```

`content/RailUpdatedEvent.java`: `public record RailUpdatedEvent(RailSnapshot snapshot) {}`. `content/RailsChangedEvent.java`: `public record RailsChangedEvent(List<String> keys) { public RailsChangedEvent { keys = List.copyOf(keys); } }`.

`content/RailPreferences.java` — the interface from **Interfaces** with Javadoc: "`rails` returns the rails to show and keep fresh, in display order; it must not do I/O."

`content/DefaultRailPreferences.java`:

```java
package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.RailDescriptor;

import java.time.Duration;
import java.util.List;

/** Every rail of every available source, in bean order. Replaced by stored preferences in D4. */
public class DefaultRailPreferences implements RailPreferences {

    private final ContentProperties properties;

    public DefaultRailPreferences(ContentProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RailDescriptor> rails(List<ContentSource> sources) {
        return sources.stream().filter(ContentSource::available).flatMap(s -> s.rails().stream()).toList();
    }

    @Override
    public Duration refreshInterval(ContentSource source) {
        return properties.rails().refreshIntervals().getOrDefault(source.id(), source.defaultRefreshInterval());
    }
}
```

- [ ] **Step 5: Implement the cache**

`content/RailCache.java`:

```java
package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-rail cache in front of every content source (spec §7): page loads read snapshots, a
 * scheduler refreshes them on virtual threads, and every change is published for SSE.
 */
public class RailCache implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RailCache.class);

    private final ContentSources sources;
    private final RailPreferences preferences;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ContentProperties.Rails properties;
    private final ExecutorService fetches;
    private final Semaphore permits;
    private final AtomicLong versions = new AtomicLong();

    /** Guarded by {@code this}; iteration order is display order. */
    private Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile ScheduledExecutorService ticker;
    private volatile boolean running;

    private static final class Entry {
        final RailDescriptor descriptor;
        RailSnapshot snapshot;
        Instant dueAt;
        int failures;
        boolean inFlight;

        Entry(RailDescriptor descriptor, RailSnapshot snapshot, Instant dueAt) {
            this.descriptor = descriptor;
            this.snapshot = snapshot;
            this.dueAt = dueAt;
        }
    }

    public RailCache(ContentSources sources, RailPreferences preferences, ApplicationEventPublisher events,
                     Clock clock, ContentProperties properties, ExecutorService fetches) {
        this.sources = sources;
        this.preferences = preferences;
        this.events = events;
        this.clock = clock;
        this.properties = properties.rails();
        this.fetches = fetches;
        this.permits = new Semaphore(this.properties.maxConcurrentFetches());
    }

    public List<RailSnapshot> snapshots() {
        reconcile();
        List<Entry> toStart = new ArrayList<>();
        List<RailSnapshot> result = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (entry.snapshot.status() == RailStatus.LOADING && !entry.inFlight) {
                    toStart.add(entry);
                }
            }
        }
        toStart.forEach(this::start);
        synchronized (this) {
            entries.values().forEach(entry -> result.add(entry.snapshot));
        }
        return result;
    }

    public synchronized List<RailSnapshot> peek() {
        return entries.values().stream().map(entry -> entry.snapshot).toList();
    }

    public Optional<RailSnapshot> snapshot(String sourceId, String railId) {
        reconcile();
        Entry entry;
        synchronized (this) {
            entry = entries.get(sourceId + "/" + railId);
        }
        if (entry == null) {
            return Optional.empty();
        }
        boolean load;
        synchronized (this) {
            load = entry.snapshot.status() == RailStatus.LOADING && !entry.inFlight;
        }
        if (load) {
            start(entry);
        }
        synchronized (this) {
            return Optional.of(entry.snapshot);
        }
    }

    public Optional<RailSnapshot> refresh(String sourceId, String railId) {
        reconcile();
        Entry entry;
        synchronized (this) {
            entry = entries.get(sourceId + "/" + railId);
        }
        if (entry == null) {
            return Optional.empty();
        }
        start(entry);
        synchronized (this) {
            return Optional.of(entry.snapshot);
        }
    }

    public void tick() {
        reconcile();
        Instant now = clock.instant();
        List<Entry> due = new ArrayList<>();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (!entry.inFlight && !entry.dueAt.isAfter(now)) {
                    due.add(entry);
                }
            }
        }
        due.forEach(this::start);
    }

    public void reconcile() {
        List<RailDescriptor> wanted = preferences.rails(sources.all());
        List<String> keys = null;
        synchronized (this) {
            Map<String, Entry> next = new LinkedHashMap<>();
            for (RailDescriptor descriptor : wanted) {
                String key = RailSnapshot.key(descriptor);
                Entry existing = entries.get(key);
                if (existing != null && existing.descriptor.equals(descriptor)) {
                    next.put(key, existing);
                } else if (!next.containsKey(key)) {
                    next.put(key, new Entry(descriptor,
                            RailSnapshot.loading(descriptor, versions.incrementAndGet()), clock.instant()));
                }
            }
            if (!List.copyOf(next.keySet()).equals(List.copyOf(entries.keySet()))) {
                keys = List.copyOf(next.keySet());
            }
            entries = next;
        }
        if (keys != null) {
            events.publishEvent(new RailsChangedEvent(keys));
        }
    }

    /** Re-evaluates due times after preferences changed (Task 4 calls this through an event). */
    public void reschedule() {
        Instant now = clock.instant();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                Optional<ContentSource> source = sources.find(entry.descriptor.sourceId());
                if (source.isPresent() && entry.snapshot.fetchedAt() != null && entry.failures == 0) {
                    Instant due = entry.snapshot.fetchedAt().plus(preferences.refreshInterval(source.get()));
                    entry.dueAt = due.isBefore(now) ? now : due;
                }
            }
        }
        reconcile();
    }

    private void start(Entry entry) {
        RailSnapshot marked;
        synchronized (this) {
            if (entry.inFlight || entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return;
            }
            entry.inFlight = true;
            entry.snapshot = entry.snapshot.refreshing(versions.incrementAndGet());
            marked = entry.snapshot;
        }
        events.publishEvent(new RailUpdatedEvent(marked));
        try {
            fetches.execute(() -> fetch(entry));
        } catch (RejectedExecutionException e) {
            synchronized (this) {
                entry.inFlight = false;
            }
        }
    }

    private void fetch(Entry entry) {
        RailDescriptor descriptor = entry.descriptor;
        Optional<ContentSource> source = sources.find(descriptor.sourceId());
        RailSnapshot published;
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                entry.inFlight = false;
            }
            return;
        }
        try {
            if (source.isEmpty()) {
                published = fail(entry, descriptor.sourceId() + " is switched off", Duration.ofMinutes(15));
            } else {
                ContentSource found = source.get();
                Duration interval = preferences.refreshInterval(found);
                try {
                    Rail rail = found.rail(descriptor.id());
                    published = succeed(entry, rail, interval);
                } catch (ContentSourceException e) {
                    published = fail(entry, e.getMessage(), interval);
                } catch (IllegalArgumentException e) {
                    published = fail(entry, found.displayName() + " no longer offers " + descriptor.title(), interval);
                } catch (RuntimeException e) {
                    log.warn("Rail {} failed to load", RailSnapshot.key(descriptor), e);
                    published = fail(entry, found.displayName() + " could not load " + descriptor.title(), interval);
                }
            }
        } finally {
            permits.release();
        }
        if (published != null) {
            events.publishEvent(new RailUpdatedEvent(published));
        }
    }

    private RailSnapshot succeed(Entry entry, Rail rail, Duration interval) {
        synchronized (this) {
            entry.inFlight = false;
            if (entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return null;
            }
            Instant fetchedAt = rail.fetchedAt() == null ? clock.instant() : rail.fetchedAt();
            entry.snapshot = entry.snapshot.ready(rail.items(), fetchedAt, versions.incrementAndGet());
            entry.failures = 0;
            entry.dueAt = clock.instant().plus(interval);
            return entry.snapshot;
        }
    }

    private RailSnapshot fail(Entry entry, String message, Duration interval) {
        synchronized (this) {
            entry.inFlight = false;
            if (entries.get(RailSnapshot.key(entry.descriptor)) != entry) {
                return null;
            }
            entry.failures++;
            long factor = 1L << Math.min(entry.failures - 1, 20);
            Duration backoff = properties.retryAfterFailure().multipliedBy(factor);
            entry.dueAt = clock.instant().plus(backoff.compareTo(interval) < 0 ? backoff : interval);
            entry.snapshot = entry.snapshot.failed(message, versions.incrementAndGet());
            return entry.snapshot;
        }
    }

    @Override
    public void start() {
        running = true;
        if (!properties.schedulerEnabled()) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rail-cache-ticker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("Rail refresh tick failed", e);
            }
        }, 0, properties.tick().toMillis(), TimeUnit.MILLISECONDS);
        ticker = scheduler;
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService scheduler = ticker;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        fetches.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

(The one-line `snapshot`/`refresh` lock juggling keeps `start` — which publishes an event — outside the monitor.)

`content/ContentConfiguration.java`:

```java
package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSources;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(ContentProperties.class)
public class ContentConfiguration {

    @Bean
    public RailPreferences railPreferences(ContentProperties properties) {
        return new DefaultRailPreferences(properties);
    }

    @Bean
    public RailCache railCache(ContentSources sources, RailPreferences preferences,
                               ApplicationEventPublisher events, ContentProperties properties) {
        return new RailCache(sources, preferences, events, Clock.systemUTC(), properties,
                Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

- [ ] **Step 6: SSE and the JSON rail endpoint**

`web/RailEventView.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailSnapshot;

import java.time.Instant;

/** The `rail` SSE payload: a summary only; the browser fetches the fragment when the version changed. */
public record RailEventView(String sourceId, String railId, String status, long version,
                           Instant fetchedAt, String error, boolean refreshing) {

    public static RailEventView of(RailSnapshot snapshot) {
        return new RailEventView(snapshot.sourceId(), snapshot.railId(), snapshot.status().name(),
                snapshot.version(), snapshot.fetchedAt(), snapshot.error(), snapshot.refreshing());
    }
}
```

`web/DeviceStateBroadcaster.java` — generalise the fan-out, keeping every existing guarantee:

```java
    @FunctionalInterface
    interface Send {
        void to(SseEmitter emitter) throws IOException;
    }

    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        enqueue(emitter -> sendData(emitter, event));
    }

    @EventListener
    public void onRailUpdated(RailUpdatedEvent event) {
        RailEventView view = RailEventView.of(event.snapshot());
        enqueue(emitter -> sendNamed(emitter, "rail", view));
    }

    @EventListener
    public void onRailsChanged(RailsChangedEvent event) {
        Map<String, Object> body = Map.of("rails", event.keys());
        enqueue(emitter -> sendNamed(emitter, "rails", body));
    }

    private void enqueue(Send send) {
        try {
            fanOut.execute(() -> broadcast(send));
        } catch (RejectedExecutionException e) {
            // The application is shutting down; there is nobody left to tell.
        }
    }

    private void broadcast(Send send) {
        for (SseEmitter emitter : emitters) {
            try {
                send.to(emitter);
            } catch (Throwable t) {
                log.debug("Dropping an SSE subscriber after a failed send", t);
                emitters.remove(emitter);
                completeQuietly(emitter, t);
            }
        }
    }

    /** Named non-device events; package-private so a test can observe them. */
    void sendNamed(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }
```

Keep the existing comments (move the long comment from the old `broadcast` loop body into the new one) and `sendData` unchanged. Rename the thread from `shield-sse-broadcast` to `home-control-sse-broadcast`.

`web/StateController.events()` — inject `RailCache` (constructor parameter), and after the device loop, inside the same `try`, add:

```java
            // Rail summaries let a reconnecting tab notice what changed while it was away.
            for (RailSnapshot rail : rails.peek()) {
                emitter.send(SseEmitter.event().name("rail").data(RailEventView.of(rail)));
            }
```

`web/ContentController.java` — inject `RailCache`; replace the rail handler:

```java
    public record RailContentView(String sourceId, String id, String title, String status, Instant fetchedAt,
                                  String error, List<ContentItemView> items) {
        static RailContentView of(RailSnapshot s) {
            return new RailContentView(s.sourceId(), s.railId(), s.descriptor().title(), s.status().name(),
                    s.fetchedAt(), s.error(), s.items().stream().map(ContentItemView::of).toList());
        }
    }

    @GetMapping("/sources/{sourceId}/rails/{railId}")
    public ResponseEntity<?> rail(@PathVariable String sourceId, @PathVariable String railId) {
        return rails.snapshot(sourceId, railId).<ResponseEntity<?>>map(snapshot -> switch (snapshot.status()) {
            case LOADING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(RailContentView.of(snapshot));
            case READY -> ResponseEntity.ok(RailContentView.of(snapshot));
            case FAILED -> snapshot.hasItems()
                    ? ResponseEntity.ok(RailContentView.of(snapshot))
                    : text(HttpStatus.BAD_GATEWAY, snapshot.error());
        }).orElseGet(() -> unknownRail(sourceId, railId));
    }

    @PostMapping("/sources/{sourceId}/rails/{railId}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String sourceId, @PathVariable String railId) {
        return rails.refresh(sourceId, railId)
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.status(HttpStatus.ACCEPTED).body(RailContentView.of(snapshot)))
                .orElseGet(() -> unknownRail(sourceId, railId));
    }

    private ResponseEntity<?> unknownRail(String sourceId, String railId) {
        return sources.find(sourceId)
                .<ResponseEntity<?>>map(source -> text(HttpStatus.NOT_FOUND, source.displayName() + " has no rail '" + railId + "'"))
                .orElseGet(() -> text(HttpStatus.NOT_FOUND, "No content source " + sourceId));
    }
```

(`text(...)` = the same plain-text helper C's controllers use; add it if `ContentController` has none. Remove the "Interim" Javadoc sentence and C's old `RailContentView` record.)

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinContentSourceTest'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. If a `@WebMvcTest` slice of another controller fails because `StateController`/`ContentController` now need `RailCache`, add `@MockitoBean RailCache` there.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/content src/main/java/dev/andre/homecontrol/core/content/ContentSource.java \
  src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java \
  src/main/java/dev/andre/homecontrol/web src/main/resources/application.yaml src/test/resources/application.yaml \
  src/test/java/dev/andre/homecontrol/content src/test/java/dev/andre/homecontrol/web \
  src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSourceTest.java
git commit -m "feat: cache rails per source and refresh them in the background"
```

---

### Task 2: D2 · Rails layout

**Files:**
- Create: `web/RailView.java`, `web/RailController.java`, `src/main/resources/templates/fragments/rails.html`, `src/main/resources/static/js/events.js`, `src/main/resources/static/js/rails.js`
- Modify: `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css`, `src/main/resources/static/js/state-view.js`, `src/main/resources/static/js/app.js`
- Test: `web/RailControllerTest.java`, `web/DashboardPageTest.java`, `web/StaticAssetsTest.java`

**Interfaces:**
- Consumes: `RailCache.snapshots()/snapshot()/refresh()`, `RailSnapshot`, `RailStatus` (Task 1); `ContentSources.find(id).displayName()`, `ContentItemView.of` (C3); A/B dashboard model (`devices`, `states`, `selected`, `selectedState`, `canOpenLinks`, `remoteKeys`, `castControls`).
- Produces:
  - `record RailView(String key, String domId, String sourceId, String railId, String title, String sourceName, String status, List<ContentItemView> items, String error, boolean refreshing, long version)` with `static RailView of(RailSnapshot, ContentSources)`; `domId` = `"rail-" + key` with every character outside `[A-Za-z0-9-]` replaced by `-`.
  - `GET /rails` → fragment `fragments/rails :: rails` (the whole `<section id="rails">`); `GET /rails/{sourceId}/{railId}` → fragment `fragments/rails :: rail` / 404 text; `POST /rails/{sourceId}/{railId}/refresh` → fragment `rail` (after starting a refresh) / 404 text.
  - Fragments: `rails(rails)`, `rail(rail)`, `tile(item)`. Tile markup contract used by Tasks 3, 5 and 7: `<button type="button" class="tile" data-source data-item data-title data-subtitle data-artwork data-kind>`.
  - Dashboard model adds `rails` (`List<RailView>`), `remoteOpen` (boolean from `?remote=open`), `hasSources` (`!contentSources.all().isEmpty()`).
  - `GET /remote/{id}` → redirect `/?device={id}&remote=open` (was `/?device={id}`).
  - ES modules: `events.js` `export function stream()` (one shared `EventSource("/events")`), `export function on(name, handler)`; `state-view.js` `subscribe(onState)` now uses `on("state", …)` and dispatches `document` event `homecontrol:state` `{deviceId, state}`, exports `stateOf(deviceId)`; `rails.js` `export function watchRails()`.

**Markup contract (normative).**
- Section per rail: `<section class="rail" id="{domId}" data-rail="{key}" data-version="{version}" data-status="{LOADING|READY|FAILED}" aria-busy="{status==LOADING}">` with `<header><h2>{title}</h2><span class="source">{sourceName}</span></header>`.
- `LOADING`: three `<div class="tile skeleton" aria-hidden="true">` placeholders.
- `READY` with items: `<div class="tiles" role="list">` of tiles (each tile wrapped in `<div role="listitem">`).
- `READY` without items: `<p class="rail-empty">Nothing here right now</p>`.
- `FAILED` without items: `<div class="rail-error" role="alert"><p>Couldn't load {title}: {error}</p><button type="button" class="retry" hx-post="/rails/{sourceId}/{railId}/refresh" hx-target="closest section" hx-swap="outerHTML">Retry</button></div>` — the section keeps the same minimum height as a tile row (`min-height: 7.5rem`), so it is never a gap.
- `FAILED` with items: the tiles, plus in the header `<span class="rail-stale" role="status">Couldn't refresh</span>` and the same Retry button.
- `refreshing=true` adds class `refreshing` to the section (a subtle spinner on the header; no layout change).
- No sources configured (`!hasSources` or `rails` empty): `<p class="hint rails-empty">No content yet. Connect a source in <a href="/setup#sources">Setup</a>.</p>` inside `#rails`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/andre/homecontrol/web/RailControllerTest.java` — `@WebMvcTest(RailController.class)`, `@MockitoBean RailCache rails`, `@MockitoBean ContentSources sources` (a mocked `ContentSource` with display name `Jellyfin`). Snapshots built with `new RailSnapshot(new RailDescriptor("jellyfin","resume","Continue watching"), status, items, fetchedAt, error, false, 7)`. Cases:
- `rendersAReadyRailAsTiles`: `GET /rails/jellyfin/resume` → 200 HTML containing `id="rail-jellyfin-resume"`, `data-version="7"`, `data-status="READY"`, `class="tile"`, `data-source="jellyfin"`, `data-item="item-1"`, `Continue watching`, the artwork URL, and a progress element `width:50%` for progress 0.5; the body does not contain `JellyfinItem`, `resumeTicks` or `playables`.
- `aFailedRailIsACompactErrorWithRetryNeverAGap`: FAILED, no items, error `Could not reach Jellyfin at http://nas:8096` → contains `class="rail-error"`, `role="alert"`, that message, `hx-post="/rails/jellyfin/resume/refresh"`, `Retry`; does not contain `class="tiles"`.
- `aFailedRailWithItemsKeepsThemAndOffersRetry`: contains `class="tiles"` and `Couldn&#39;t refresh` (Thymeleaf escapes the apostrophe; assert with `containsString("refresh")` plus `rail-stale`).
- `aLoadingRailShowsSkeletons`: 3 occurrences of `tile skeleton`, `aria-busy="true"`.
- `anEmptyRailSaysSo`: `Nothing here right now`.
- `retryStartsARefreshAndReturnsTheRail`: `POST /rails/jellyfin/resume/refresh` → 200, `verify(rails).refresh("jellyfin","resume")`.
- `unknownRailIs404`: snapshot empty → 404.
- `theWholeSectionKeepsCacheOrder`: `rails.snapshots()` → two snapshots `b` then `a` → `GET /rails` body has `rail-jellyfin-b` before `rail-jellyfin-a`.
- `domIdsAreSafe`: `RailView.of` for rail id `new/up` → `domId` `rail-jellyfin-new-up`.

`web/DashboardPageTest.java` — add `@MockitoBean RailCache rails` and `@MockitoBean ContentSources sources`; in existing cases stub `rails.snapshots()` → `List.of()`. Change `theClassicRemotePathRedirectsToTheDashboard` to expect `/?device=living&remote=open`. Add:
- `showsRailsFromTheCacheWithoutCallingSources`: one READY snapshot → body contains `id="rails"` and `id="rail-jellyfin-resume"`; `verify(source, never()).rail(any())`.
- `theRemoteIsADrawerClosedByDefault`: body contains `id="remote-drawer"` with attribute `hidden`, and still `/devices/living/key/DPAD_UP`; with `remote=open` the drawer has no `hidden`.
- `pointsToSetupWhenNoSourceIsConfigured`: `sources.all()` empty → contains `Connect a source`.

`web/StaticAssetsTest.java` — add `/js/events.js` contains `export function on`, `/js/rails.js` contains `export function watchRails`, `/js/state-view.js` contains `homecontrol:state`.

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `RailView`, `RailController` missing.

- [ ] **Step 3: Implement the view and controller**

`web/RailView.java` — the record from **Interfaces**; `of`:

```java
    public static RailView of(RailSnapshot snapshot, ContentSources sources) {
        String sourceName = sources.find(snapshot.sourceId()).map(ContentSource::displayName).orElse(snapshot.sourceId());
        return new RailView(snapshot.key(), "rail-" + snapshot.key().replaceAll("[^A-Za-z0-9-]", "-"),
                snapshot.sourceId(), snapshot.railId(), snapshot.descriptor().title(), sourceName,
                snapshot.status().name(), snapshot.items().stream().map(ContentItemView::of).toList(),
                snapshot.error(), snapshot.refreshing(), snapshot.version());
    }
```

`web/RailController.java` — `@Controller`, constructor `(RailCache rails, ContentSources sources)`:

```java
    @GetMapping("/rails")
    public String rails(Model model) {
        model.addAttribute("rails", rails.snapshots().stream().map(s -> RailView.of(s, sources)).toList());
        model.addAttribute("hasSources", !sources.all().isEmpty());
        return "fragments/rails :: rails";
    }

    @GetMapping("/rails/{sourceId}/{railId}")
    public String rail(@PathVariable String sourceId, @PathVariable String railId, Model model) {
        return render(rails.snapshot(sourceId, railId), model);
    }

    @PostMapping("/rails/{sourceId}/{railId}/refresh")
    public String refresh(@PathVariable String sourceId, @PathVariable String railId, Model model) {
        return render(rails.refresh(sourceId, railId), model);
    }

    private String render(Optional<RailSnapshot> snapshot, Model model) {
        RailSnapshot found = snapshot.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such rail"));
        model.addAttribute("rail", RailView.of(found, sources));
        return "fragments/rails :: rail";
    }
```

`DashboardController` — add constructor parameters `RailCache rails, ContentSources contentSources`, and in `dashboard(...)` add `@RequestParam(name = "remote", required = false) String remote` plus:

```java
        model.addAttribute("rails", rails.snapshots().stream().map(s -> RailView.of(s, contentSources)).toList());
        model.addAttribute("hasSources", !contentSources.all().isEmpty());
        model.addAttribute("remoteOpen", "open".equals(remote));
```

and `remote(...)` returns `"redirect:/?device=" + id + "&remote=open"` (URL-encode `id` with `UriUtils.encodeQueryParam(id, UTF_8)`).

- [ ] **Step 4: Write the fragments**

`src/main/resources/templates/fragments/rails.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="en">
<body>

<section id="rails" th:fragment="rails" aria-label="Content">
    <p class="hint rails-empty" th:if="${!hasSources or #lists.isEmpty(rails)}">
        No content yet. Connect a source in <a th:href="@{/setup(_fragment='sources')}" href="/setup#sources">Setup</a>.
    </p>
    <th:block th:each="rail : ${rails}">
        <section th:replace="~{fragments/rails :: rail}"></section>
    </th:block>
</section>

<section th:fragment="rail" class="rail"
         th:id="${rail.domId()}"
         th:classappend="${rail.refreshing()} ? 'refreshing'"
         th:attr="data-rail=${rail.key()},data-version=${rail.version()},data-status=${rail.status()},aria-busy=${rail.status() == 'LOADING'}"
         th:with="failed=${rail.status() == 'FAILED'}, hasItems=${!#lists.isEmpty(rail.items())}">
    <header>
        <h2 th:text="${rail.title()}">Continue watching</h2>
        <span class="source" th:text="${rail.sourceName()}">Jellyfin</span>
        <th:block th:if="${failed and hasItems}">
            <span class="rail-stale" role="status">Couldn't refresh</span>
            <button type="button" class="retry"
                    th:attr="hx-post=@{/rails/{s}/{r}/refresh(s=${rail.sourceId()},r=${rail.railId()})}"
                    hx-target="closest section" hx-swap="outerHTML">Retry</button>
        </th:block>
    </header>

    <div class="tiles" th:if="${rail.status() == 'LOADING'}" aria-hidden="true">
        <div class="tile skeleton"></div><div class="tile skeleton"></div><div class="tile skeleton"></div>
    </div>

    <div class="tiles" role="list" th:if="${rail.status() != 'LOADING' and hasItems}">
        <div role="listitem" th:each="item : ${rail.items()}">
            <button th:replace="~{fragments/rails :: tile(${item})}"></button>
        </div>
    </div>

    <p class="rail-empty" th:if="${rail.status() == 'READY' and !hasItems}">Nothing here right now</p>

    <div class="rail-error" role="alert" th:if="${failed and !hasItems}">
        <p th:text="|Couldn't load ${rail.title()}: ${rail.error()}|">Couldn't load</p>
        <button type="button" class="retry"
                th:attr="hx-post=@{/rails/{s}/{r}/refresh(s=${rail.sourceId()},r=${rail.railId()})}"
                hx-target="closest section" hx-swap="outerHTML">Retry</button>
    </div>
</section>

<button th:fragment="tile(item)" type="button" class="tile"
        th:attr="data-source=${item.sourceId()},data-item=${item.id()},data-title=${item.title()},data-subtitle=${item.subtitle()},data-artwork=${item.artwork()},data-kind=${item.kind()}">
    <span class="art">
        <img th:if="${item.artwork() != null}" th:src="${item.artwork()}" alt="" loading="lazy" decoding="async">
        <span th:if="${item.artwork() == null}" class="placeholder" aria-hidden="true"
              th:text="${#strings.isEmpty(item.title())} ? '?' : ${#strings.substring(item.title(), 0, 1)}">A</span>
        <span th:if="${item.progress() != null}" class="progress" aria-hidden="true"><span
              th:style="'width:' + ${#numbers.formatDecimal(item.progress() * 100, 1, 0)} + '%'"></span></span>
    </span>
    <span class="title" th:text="${item.title()}">Title</span>
    <span class="subtitle" th:if="${item.subtitle() != null}" th:text="${item.subtitle()}">S1:E1</span>
</button>

</body>
</html>
```

(`th:attr` drops attributes whose value is null.)

- [ ] **Step 5: Restructure the dashboard**

`dashboard.html` (starting from the A/B version in the repository):
1. In `<head>` keep everything; nothing new yet.
2. In each chip (`<a class="chip" …>`), for the selected device only, add after the chip — still inside `nav.devices` — a toggle: `<button type="button" class="drawer-toggle" th:if="${device.id() == selected.id()}" aria-controls="remote-drawer" th:attr="aria-expanded=${remoteOpen}" aria-label="Open the remote">⌄</button>`. Wrap chip + toggle in `<div class="chip-group">`.
3. Replace `<main hx-swap="none" th:with="id=${selected.id()}"> … </main>` by:

```html
<main id="content">
    <section id="rails" th:replace="~{fragments/rails :: rails}"></section>
</main>

<aside id="remote-drawer" class="drawer" hx-swap="none" th:with="id=${selected.id()}"
       th:attr="hidden=${!remoteOpen} ? 'hidden'" aria-label="Remote">
    <header class="drawer-header">
        <h2 th:text="${selected.name()}">Shield</h2>
        <button type="button" class="drawer-close" aria-controls="remote-drawer" aria-label="Close the remote">✕</button>
    </header>
    <!-- everything that was inside <main> before, unchanged (meta, key rows, cast section, open-link form) -->
</aside>
```

   Move the old `<main>` children verbatim; the old `<h1>` becomes the drawer header's `<h2>` (delete the old `<h1>`).
4. Keep `<div id="toast" hidden></div>` at the end of `<body>`.

Append to `app.css`:

```css
.strip { position: sticky; top: 0; z-index: 10; background: var(--bg); }
.chip-group { display: flex; align-items: stretch; gap: .25rem; }
.drawer-toggle, .drawer-close { min-width: 44px; min-height: 44px; }
main#content { max-width: none; padding: .5rem 0 5rem; }
#rails { display: grid; gap: 1.25rem; }
.rail { min-height: 7.5rem; }
.rail header { display: flex; align-items: baseline; gap: .5rem; padding: 0 1rem; }
.rail h2 { font-size: 1.05rem; margin: 0; }
.rail .source { font-size: .75rem; opacity: .65; }
.rail .retry { margin-left: auto; padding: .4rem .9rem; min-height: 44px; }
.rail.refreshing h2::after { content: " ⟳"; opacity: .6; }
.tiles { display: grid; grid-auto-flow: column; grid-auto-columns: clamp(7.5rem, 31vw, 10.5rem);
         gap: .6rem; overflow-x: auto; padding: .5rem 1rem; scroll-snap-type: x mandatory;
         scroll-padding-inline: 1rem; overscroll-behavior-x: contain; }
.tile { display: grid; gap: .25rem; padding: 0; border: 0; background: none; color: var(--fg);
        text-align: left; scroll-snap-align: start; min-height: 44px; }
.tile .art { position: relative; display: block; aspect-ratio: 2 / 3; border-radius: .6rem;
             overflow: hidden; background: var(--btn); }
.tile img { width: 100%; height: 100%; object-fit: cover; display: block; }
.tile .placeholder { display: grid; place-items: center; height: 100%; font-size: 2rem; opacity: .5; }
.tile .progress { position: absolute; left: 0; right: 0; bottom: 0; height: 4px; background: #0008; }
.tile .progress span { display: block; height: 100%; background: var(--accent); }
.tile .title { font-size: .85rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tile .subtitle { font-size: .75rem; opacity: .75; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tile.skeleton { aspect-ratio: 2 / 3; border-radius: .6rem; background: var(--btn); }
.rail-error, .rail-empty { margin: .5rem 1rem; padding: .75rem 1rem; border-radius: .6rem; min-height: 5.5rem;
                           display: flex; align-items: center; gap: .75rem; background: #2a1d1d; }
.rail-empty { background: var(--btn); opacity: .8; }
.rail-error p { margin: 0; flex: 1; font-size: .9rem; }
.rail-stale { font-size: .75rem; color: #f2b8b5; }
.drawer { position: fixed; inset: auto 0 0 0; max-height: 85vh; overflow-y: auto; z-index: 20;
          background: var(--bg); border-top: 1px solid #2a2d34; border-radius: 1rem 1rem 0 0; padding: 1rem; }
.drawer[hidden] { display: none; }
.drawer-header { display: flex; align-items: center; justify-content: space-between; }
@media (min-width: 64rem) {
    body:has(.drawer:not([hidden])) main#content { margin-right: 26rem; }
    .drawer { inset: 4.5rem 0 0 auto; width: 26rem; max-height: none; border-radius: 0; border-top: 0;
              border-left: 1px solid #2a2d34; }
    .tiles { grid-auto-columns: 10.5rem; }
}
@media (prefers-reduced-motion: no-preference) { .tiles { scroll-behavior: smooth; } }
```

- [ ] **Step 6: Write the browser modules**

`static/js/events.js`:

```js
// One EventSource per page. Modules register named handlers; EventSource reconnects on its own.
let source;
const handlers = new Map();

export function stream() {
    if (!source) {
        source = new EventSource("/events");
        source.addEventListener("open", () => document.dispatchEvent(
            new CustomEvent("homecontrol:stream", { detail: { connected: true } })));
        source.addEventListener("error", () => document.dispatchEvent(
            new CustomEvent("homecontrol:stream", { detail: { connected: false } })));
    }
    return source;
}

export function on(name, handler) {
    if (!handlers.has(name)) {
        handlers.set(name, []);
        stream().addEventListener(name, (event) => {
            const data = JSON.parse(event.data);
            for (const h of handlers.get(name)) h(data);
        });
    }
    handlers.get(name).push(handler);
}
```

`static/js/state-view.js` — keep `applyState`/`describePlaying`; replace `subscribe` with:

```js
import { on } from "./events.js";

const lastStates = new Map();

export function stateOf(deviceId) {
    return lastStates.get(deviceId);
}

export function subscribe(onState) {
    on("state", ({ deviceId, state }) => {
        lastStates.set(deviceId, state);
        onState(deviceId, state);
        document.dispatchEvent(new CustomEvent("homecontrol:state", { detail: { deviceId, state } }));
    });
}
```

and in `applyState` also update every `[data-status-for="<deviceId>"]` element (text = status, classes `ok`/`off`) — used by the play sheet in Task 3. Use `document.querySelectorAll(`[data-status-for="${CSS.escape(deviceId)}"]`)`.

`static/js/rails.js`:

```js
import { on } from "./events.js";

// Rails re-render on the server; SSE only says which rail changed.
function railElement(sourceId, railId) {
    return document.querySelector(`.rail[data-rail="${CSS.escape(`${sourceId}/${railId}`)}"]`);
}

export function watchRails() {
    on("rail", ({ sourceId, railId, version }) => {
        const el = railElement(sourceId, railId);
        if (!el || Number(el.dataset.version) >= version) return;
        const path = `/rails/${encodeURIComponent(sourceId)}/${encodeURIComponent(railId)}`;
        window.htmx.ajax("GET", path, { target: el, swap: "outerHTML" });
    });
    on("rails", ({ rails }) => {
        const shown = [...document.querySelectorAll("#rails > .rail")].map((el) => el.dataset.rail);
        if (shown.join("|") === rails.join("|")) return;
        window.htmx.ajax("GET", "/rails", { target: "#rails", swap: "outerHTML" });
    });
}
```

`static/js/app.js` — add `import { watchRails } from "./rails.js";` and call `watchRails();`; add drawer toggling:

```js
function setDrawer(open) {
    const drawer = document.getElementById("remote-drawer");
    if (!drawer) return;
    drawer.hidden = !open;
    document.querySelectorAll('[aria-controls="remote-drawer"].drawer-toggle')
        .forEach((b) => b.setAttribute("aria-expanded", String(open)));
}
document.addEventListener("click", (event) => {
    if (event.target.closest(".drawer-toggle")) setDrawer(document.getElementById("remote-drawer").hidden);
    if (event.target.closest(".drawer-close")) setDrawer(false);
});
```

Make the keyboard handler ignore key presses while the drawer is hidden on narrow screens? No — keep A's behaviour (keys always go to the selected device), but also ignore events whose target is inside `input, textarea, select, dialog`.

- [ ] **Step 7: Run the tests, then the build, then look at it**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

Optional visual check (no hardware needed): `.superpowers/gradle.sh bootRun --args='--shield.data-dir=build/devdata'`, open `http://localhost:8080/?device=…` in a phone-sized window; rails section renders, drawer opens from the chevron.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/web src/main/resources/templates src/main/resources/static \
  src/test/java/dev/andre/homecontrol/web
git commit -m "feat: rails layout with per-rail retry and a remote drawer"
```

---

### Task 3: D3 · Play sheet

**Files:**
- Create: `core/playback/RouteKeys.java`, `playback/PlaybackPreview.java`, `playback/PlayAttempt.java`, `web/RouteView.java`, `web/RoutePreviewView.java`, `web/PlayResultView.java`, `src/main/resources/static/js/toast.js`, `src/main/resources/static/js/play-sheet.js`
- Modify: `core/playback/PlaybackPlanner.java`, `playback/PlaybackService.java`, `web/ContentPlayController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css`, `src/main/resources/static/js/app.js`
- Test: `core/playback/RouteKeysTest.java`, `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`, `web/ContentPlayPreviewTest.java`, `web/DashboardPageTest.java`, `web/StaticAssetsTest.java`

**Interfaces:**
- Consumes: `PlaybackPlanner(List<RouteStrategy>)`, `RouteStrategy.route(item, caps)`, `Route` variants `OpenAppLink(URI, String service)`, `Cast(String receiverAppId, Map load)`, `CastMessage(String receiverAppId, String namespace, Map message, String receiverLabel)`, `JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client)`, `Unroutable(String reason)` (A8, B6, C5, C7); C7's `PlaybackService` (resolvers, executors, private `plan(ContentItem, Device)` and the execute `switch` in `play`); `ContentPlayController.find(source, item)`; `DeviceOfflineException`, `UnsupportedActionException`, `ActionFailedException`, `ContentSourceException`; tile markup and `stateOf` (Task 2).
- Produces:
  - `PlaybackPlanner.routes(ContentItem, Set<Capability>) → List<Route>` — every route a strategy yields, in strategy order, no duplicates (by `RouteKeys.key`), never an `Unroutable`; `plan` = first of `routes` or the existing explanation.
  - `final class RouteKeys { static String key(Route route); static boolean optimistic(Route route); }`.
  - `record PlaybackPreview(Device device, List<Route> routes, String reason)` — `routes` empty ⇔ `reason` non-null.
  - `sealed interface PlayAttempt` with `record Played(Device device, Route route, List<Route> remaining)`, `record Failed(Device device, Route route, List<Route> remaining, RuntimeException cause)`, `record Unroutable(Device device, String reason)`.
  - `PlaybackService.preview(ContentItem, String deviceId) → PlaybackPreview`; `PlaybackService.attempt(ContentItem, String deviceId, Set<String> skip) → PlayAttempt`.
  - `GET /devices/{id}/route-preview?source=&item=` → 200 JSON `RoutePreviewView` / 404 text (device, source, item) / 502 text (`ContentSourceException` from `item`).
  - `POST /devices/{id}/play-attempt` form `source`, `item`, `skip` (0–8 values, each ≤ 64 chars) → JSON `PlayResultView` with status 200 played / 409 offline / 422 unsupported or unroutable / 502 failed; 400 text for bad `skip`; 404 text as above.
  - `toast.js` `export function toast(message, { action, onAction, duration } = {})`; `play-sheet.js` `export function openPlaySheet(tileOrData)`, `export function initPlaySheet()`, `export const APP_LINK_HINT_MS = 5000`.

**JSON (normative).**

```json
// GET /devices/living/route-preview?source=jellyfin&item=3f2a…
{
  "deviceId": "living",
  "deviceName": "Living Room",
  "playable": true,
  "route": { "key": "app-link", "description": "Open in the YouTube app", "optimistic": true },
  "alternatives": [ { "key": "cast:CC1AD845", "description": "Cast with the Default Media Receiver", "optimistic": false } ],
  "reason": null
}
// unroutable
{ "deviceId": "speaker", "deviceName": "Speaker", "playable": false, "route": null, "alternatives": [],
  "reason": "this device cannot open app links; this device is not a Cast receiver" }
```

```json
// POST /devices/bedroom/play-attempt  source=e2e&item=clip-1&skip=
// 200
{ "played": true, "deviceId": "bedroom", "deviceName": "Bedroom",
  "route": { "key": "app-link", "description": "Open in the YouTube app", "optimistic": true },
  "next": null, "message": "Open in the YouTube app" }
// 502
{ "played": false, "deviceId": "bedroom", "deviceName": "Bedroom",
  "route": { "key": "app-link", "description": "Open in the YouTube app", "optimistic": true },
  "next": { "key": "cast:CC1AD845", "description": "Cast with the Default Media Receiver", "optimistic": false },
  "message": "Bedroom refused to open the link" }
// 422 unroutable (nothing left after skip)
{ "played": false, "deviceId": "bedroom", "deviceName": "Bedroom", "route": null, "next": null,
  "message": "Bedroom: no other way to play this" }
```

`next` is the first of `remaining` or null. Unroutable messages: without `skip` → `<device name>: <reason>`; with non-empty `skip` and no route left → `<device name>: no other way to play this`. Status for `Failed`: `DeviceOfflineException` 409, `UnsupportedActionException` 422, `ActionFailedException` 502; message = cause message.

**Route keys (normative):** `OpenAppLink` → `app-link`; `Cast` → `cast:<receiverAppId>`; `CastMessage` → `cast-message:<receiverAppId>`; `JellyfinSession` → `jellyfin-session`; `Unroutable` → `unroutable`. `optimistic` is true only for `OpenAppLink`.

- [ ] **Step 1: Write the failing core tests**

`src/test/java/dev/andre/homecontrol/core/playback/RouteKeysTest.java` — cases `keysAreStableAndSecretFree` (each variant above; `CastMessage` with a message map containing `"accessToken":"tok"` → key `cast-message:F007D354` without `tok`), `onlyAppLinksAreOptimistic`.

`core/playback/PlaybackPlannerTest.java` — add (C's strategy list, C's constants `LINK`, `STREAM`, `JELLYFIN_MESSAGE`, `OPEN_APP`):

```java
    @Test
    void listsEveryApplicableRouteInSpecOrder() {
        List<Route> routes = planner.routes(item(STREAM, JELLYFIN_MESSAGE, LINK, OPEN_APP),
                EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK, Capability.CAST_RECEIVER));

        assertThat(routes).extracting(RouteKeys::key)
                .containsExactly("jellyfin-session", "app-link", "cast-message:F007D354", "cast:CC1AD845");
        assertThat(planner.plan(item(STREAM, LINK), EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)))
                .isEqualTo(routes.get(1));
    }

    @Test
    void noApplicableRouteIsAnEmptyListNotUnroutable() {
        assertThat(planner.routes(item(LINK), EnumSet.of(Capability.MEDIA_RENDERER))).isEmpty();
        assertThat(planner.routes(item(), EnumSet.allOf(Capability.class))).isEmpty();
    }
```

(Adjust the constant names to C's actual test file; if `JELLYFIN_MESSAGE` uses another receiver id, adjust the expected key.)

`playback/PlaybackServiceTest.java` — add, with C's test setup (mocked `DeviceManager`, a stub resolver, mocked `RouteExecutor`):
- `previewResolvesOnceAndListsRoutesWithoutExecuting`: device caps `APP_LINK, CAST_RECEIVER`; item with `AppLink` + `StreamUrl` → `preview` routes keys `[app-link, cast:CC1AD845]`, reason null; `verify(devices, never()).execute(any(), any())`.
- `previewExplainsWhenNothingRoutes`: caps none → routes empty, reason contains `cannot open app links`.
- `attemptPlaysTheFirstRouteAndReportsTheRest`: → `Played` with route `app-link`, remaining `[cast:CC1AD845]`; `verify(devices).execute("shield", new Action.OpenAppLink(uri))`.
- `attemptReportsTheFailedRouteAndTheNextOne`: `willThrow(new ActionFailedException("Shield refused to open the link")).given(devices).execute(eq("shield"), any(Action.OpenAppLink.class))` → `Failed` with route `app-link`, remaining `[cast:CC1AD845]`, cause message; the Cast action was not executed.
- `attemptSkipsRoutesByKey`: `skip = {"app-link"}` → `Played` via `Route.Cast`; `verify(devices).execute(eq("shield"), any(Action.CastLoad.class))`.
- `attemptWithEverythingSkippedIsUnroutable`: skip both → `Unroutable` with reason `no other way to play this`.
- `offlineAndUnsupportedAreFailuresToo`: `DeviceOfflineException` → `Failed`; an unexpected `IllegalStateException` propagates.
- `sourceSideRoutesUseTheirExecutor`: a `JellyfinSession` route (stub resolver granting `JELLYFIN_CLIENT`) → executor called; its `ActionFailedException` → `Failed`.

- [ ] **Step 2: Write the failing web test**

`src/test/java/dev/andre/homecontrol/web/ContentPlayPreviewTest.java` — `@WebMvcTest(ContentPlayController.class)`, same mocks as C's `ContentPlayControllerTest` (`DeviceManager`, `ContentSources`, `PlaybackService`; a mocked `ContentSource jellyfin` whose `item("3f2a…")` returns an item). Device `living` named `Living Room`. Cases:
- `previewIsJsonWithTheRouteAndItsAlternatives`: `playback.preview(item, "living")` → `new PlaybackPreview(device, List.of(new Route.OpenAppLink(uri, "youtube"), new Route.Cast("CC1AD845", Map.of())), null)` → `$.deviceName` `Living Room`, `$.playable` true, `$.route.key` `app-link`, `$.route.description` `Open in the YouTube app`, `$.route.optimistic` true, `$.alternatives[0].key` `cast:CC1AD845`, `$.reason` null; `verify(playback, never()).attempt(any(), any(), any())`.
- `anUnroutablePreviewIsStill200`: routes empty, reason `x` → `$.playable` false, `$.route` null, `$.reason` `x`.
- `previewUnknownsAre404`: device `ghost`, source `nope`, item missing → 404 text as C's controller.
- `aSuccessfulAttemptIs200`: `playback.attempt(item, "living", Set.of())` → `Played` → `$.played` true, `$.next` null, `$.message` `Open in the YouTube app`.
- `aFailedAttemptNamesTheFailedRouteAndTheNextOne`: `Failed(device, appLink, List.of(cast), new ActionFailedException("Living Room refused"))` → 502, `$.route.key` `app-link`, `$.next.description` `Cast with the Default Media Receiver`, `$.message` `Living Room refused`.
- `failureStatusFollowsTheCause`: offline → 409, unsupported → 422.
- `skipIsPassedThroughAndValidated`: `skip=app-link&skip=cast:CC1AD845` → `verify(playback).attempt(item, "living", Set.of("app-link", "cast:CC1AD845"))`; nine `skip` values → 400; a 65-character value → 400.
- `unroutableAttemptIs422WithAMessage`: `PlayAttempt.Unroutable(device, "no other way to play this")` with a skip → 422, `$.message` `Living Room: no other way to play this`.
- `noSecretReachesTheBrowser`: a `Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk", Map.of("accessToken","tok-123"), "the Jellyfin receiver")` in preview → body does not contain `tok-123` nor `urn:x-cast`.
- `thePlainTextEndpointsAreUnchanged`: `GET /devices/living/route?source=…&item=…` still answers `text/plain` via `playback.plan` (smoke).

`web/DashboardPageTest.java` — add `rendersThePlaySheetWithADeviceSwitcher`: two devices → body contains `<dialog id="play-sheet"`, two `data-sheet-device=` buttons, `data-status-for="living"`, and `type="module"`.

`web/StaticAssetsTest.java` — `/js/play-sheet.js` contains `export function openPlaySheet` and `route-preview`; `/js/toast.js` contains `export function toast`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `routes`, `RouteKeys`, `preview`, `attempt` do not exist.

- [ ] **Step 4: Implement the planner and keys**

`core/playback/RouteKeys.java`:

```java
package dev.andre.homecontrol.core.playback;

/** Stable, browser-visible identifiers for routes. Never contains payloads: they may carry tokens. */
public final class RouteKeys {

    private RouteKeys() {
    }

    public static String key(Route route) {
        return switch (route) {
            case Route.OpenAppLink ignored -> "app-link";
            case Route.Cast cast -> "cast:" + cast.receiverAppId();
            case Route.CastMessage message -> "cast-message:" + message.receiverAppId();
            case Route.JellyfinSession ignored -> "jellyfin-session";
            case Route.Unroutable ignored -> "unroutable";
        };
    }

    /** True when success only means "the device accepted it" (spec §5.3: the app may not be installed). */
    public static boolean optimistic(Route route) {
        return route instanceof Route.OpenAppLink;
    }
}
```

In `PlaybackPlanner` add and use:

```java
    /** Every route the strategies offer, in preference order (spec §5.3). Pure. */
    public List<Route> routes(ContentItem item, Set<Capability> capabilities) {
        List<Route> routes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (RouteStrategy strategy : strategies) {
            strategy.route(item, capabilities)
                    .filter(route -> !(route instanceof Route.Unroutable))
                    .filter(route -> keys.add(RouteKeys.key(route)))
                    .ifPresent(routes::add);
        }
        return routes;
    }
```

and rewrite `plan` so that after the "nothing playable" check it returns `routes(item, capabilities).stream().findFirst().orElseGet(() -> new Route.Unroutable(<existing explanation>))`, keeping C's explanation code verbatim.

- [ ] **Step 5: Implement preview and attempt in the service**

`playback/PlaybackPreview.java` and `playback/PlayAttempt.java` — the records from **Interfaces** (`List.copyOf` in compact constructors).

In `PlaybackService` refactor C's private `plan(ContentItem, Device)` into a resolution step plus planning:

```java
    private record Resolved(ContentItem item, Set<Capability> capabilities, List<String> notes) {
    }

    private Resolved resolve(ContentItem item, Device device) {
        // body = C's loop over item.playables() and resolvers, unchanged, returning
        // new Resolved(item.withPlayables(playables), capabilities, notes)
    }

    private Route plan(ContentItem item, Device device) {
        Resolved resolved = resolve(item, device);
        // C's rules, unchanged: nothing playable + notes → Unroutable(notes); else planner.plan(...) with notes prefixed
    }

    private String explain(Resolved resolved) {
        Route route = planner.plan(resolved.item(), resolved.capabilities());
        String reason = route instanceof Route.Unroutable u ? u.reason() : "no route";
        return resolved.notes().isEmpty() ? reason : String.join("; ", resolved.notes()) + "; " + reason;
    }

    public PlaybackPreview preview(ContentItem item, String deviceId) {
        Device device = device(deviceId);
        Resolved resolved = resolve(item, device);
        List<Route> routes = resolved.item().playables().isEmpty() ? List.of()
                : planner.routes(resolved.item(), resolved.capabilities());
        if (routes.isEmpty()) {
            String reason = resolved.item().playables().isEmpty() && !resolved.notes().isEmpty()
                    ? String.join("; ", resolved.notes()) : explain(resolved);
            return new PlaybackPreview(device, List.of(), reason);
        }
        return new PlaybackPreview(device, routes, null);
    }

    public PlayAttempt attempt(ContentItem item, String deviceId, Set<String> skip) {
        PlaybackPreview preview = preview(item, deviceId);
        Device device = preview.device();
        List<Route> routes = preview.routes().stream().filter(r -> !skip.contains(RouteKeys.key(r))).toList();
        if (routes.isEmpty()) {
            return new PlayAttempt.Unroutable(device, skip.isEmpty() ? preview.reason() : "no other way to play this");
        }
        Route route = routes.getFirst();
        List<Route> remaining = routes.subList(1, routes.size());
        try {
            execute(route, device);
            return new PlayAttempt.Played(device, route, remaining);
        } catch (DeviceOfflineException | UnsupportedActionException | ActionFailedException e) {
            return new PlayAttempt.Failed(device, route, remaining, e);
        }
    }

    /** C's switch from play(), extracted; play() now calls it. */
    private void execute(Route route, Device device) {
        switch (route) {
            case Route.OpenAppLink open -> devices.execute(device.id(), open.action());
            case Route.Cast cast -> devices.execute(device.id(), cast.action());
            case Route.CastMessage message -> devices.execute(device.id(), message.action());
            case Route.JellyfinSession session -> executors.stream()
                    .filter(executor -> executor.executes(session))
                    .findFirst()
                    .orElseThrow(() -> new UnroutableException(device.name() + ": Jellyfin is switched off on this server"))
                    .execute(session, device);
            case Route.Unroutable unroutable -> throw new UnroutableException(device.name() + ": " + unroutable.reason());
        }
    }
```

(`preview.reason()` is non-null whenever routes were empty before skipping. If the "no route" wording produced by `explain` differs from C's `plan` for the same inputs, make `plan` call `explain` so both share one wording, and keep C's tests green.) `play()` keeps its public behaviour: `Route route = plan(item, device); execute(route, device); return route;`.

- [ ] **Step 6: Implement the JSON endpoints**

`web/RouteView.java`:

```java
public record RouteView(String key, String description, boolean optimistic) {
    public static RouteView of(Route route) {
        return route == null ? null : new RouteView(RouteKeys.key(route), route.describe(), RouteKeys.optimistic(route));
    }
}
```

`web/RoutePreviewView.java`: `record RoutePreviewView(String deviceId, String deviceName, boolean playable, RouteView route, List<RouteView> alternatives, String reason)` with `static of(PlaybackPreview)`.

`web/PlayResultView.java`: `record PlayResultView(boolean played, String deviceId, String deviceName, RouteView route, RouteView next, String message)`.

Add to `ContentPlayController`:

```java
    private static final int MAX_SKIP = 8;

    @GetMapping(path = "/devices/{id}/route-preview", params = {"source", "item"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        return ResponseEntity.ok(RoutePreviewView.of(playback.preview(content.get(), id)));
    }

    @PostMapping(path = "/devices/{id}/play-attempt", params = {"source", "item"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> attempt(@PathVariable String id, @RequestParam String source, @RequestParam String item,
                                     @RequestParam(name = "skip", required = false) List<String> skip) {
        List<String> skips = skip == null ? List.of() : skip.stream().filter(s -> !s.isBlank()).toList();
        if (skips.size() > MAX_SKIP || skips.stream().anyMatch(s -> s.length() > 64)) {
            return text(HttpStatus.BAD_REQUEST, "Too many or too long route keys to skip");
        }
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        return switch (playback.attempt(content.get(), id, Set.copyOf(skips))) {
            case PlayAttempt.Played played -> ResponseEntity.ok(new PlayResultView(true, id, played.device().name(),
                    RouteView.of(played.route()), RouteView.of(first(played.remaining())), played.route().describe()));
            case PlayAttempt.Failed failed -> ResponseEntity.status(statusOf(failed.cause())).body(new PlayResultView(false, id,
                    failed.device().name(), RouteView.of(failed.route()), RouteView.of(first(failed.remaining())),
                    failed.cause().getMessage()));
            case PlayAttempt.Unroutable unroutable -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
                    new PlayResultView(false, id, unroutable.device().name(), null, null,
                            unroutable.device().name() + ": " + unroutable.reason()));
        };
    }

    private static Route first(List<Route> routes) {
        return routes.isEmpty() ? null : routes.getFirst();
    }

    private static HttpStatus statusOf(RuntimeException cause) {
        return switch (cause) {
            case DeviceOfflineException ignored -> HttpStatus.CONFLICT;
            case UnsupportedActionException ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
```

(`find`, `notFound`, `text` and the exception handlers are C's; `ContentSourceException` from `item` already maps to 502.)

- [ ] **Step 7: The sheet markup**

In `dashboard.html`, before `<div id="toast" hidden></div>`, add:

```html
<dialog id="play-sheet" class="sheet" aria-labelledby="sheet-title">
    <form method="dialog" class="sheet-close-form">
        <button class="sheet-close" aria-label="Close">✕</button>
    </form>
    <div class="sheet-item">
        <img id="sheet-art" alt="" hidden>
        <div>
            <h2 id="sheet-title">Title</h2>
            <p id="sheet-subtitle" class="hint"></p>
        </div>
    </div>
    <div class="sheet-devices" role="radiogroup" aria-label="Play on">
        <button type="button" role="radio" class="sheet-device" th:each="device : ${devices}"
                th:attr="data-sheet-device=${device.id()},aria-checked=${device.id() == selected.id()}">
            <span th:text="${device.name()}">Shield</span>
            <span class="badge" th:attr="data-status-for=${device.id()}"
                  th:classappend="${states[device.id()].connected()} ? 'ok' : 'off'"
                  th:text="${states[device.id()].status()}">CONNECTED</span>
        </button>
    </div>
    <p id="sheet-route" class="sheet-route" aria-live="polite">Working out how to play this…</p>
    <button type="button" id="sheet-play" class="primary" disabled>Play</button>
</dialog>
<div id="toast" hidden role="status" aria-live="polite"><span class="toast-text"></span><button type="button" class="toast-action" hidden></button></div>
```

(Replace A's bare `<div id="toast" hidden></div>` with the richer one; A's `toast()` in `app.js` moves to `toast.js`.)

Append to `app.css`:

```css
.sheet { width: min(100%, 32rem); margin: auto auto 0; border: 0; border-radius: 1rem 1rem 0 0;
         background: var(--bg); color: var(--fg); padding: 1rem 1rem 1.5rem; }
.sheet::backdrop { background: #000a; }
.sheet-close-form { display: flex; justify-content: flex-end; margin: 0; }
.sheet-close { min-width: 44px; min-height: 44px; }
.sheet-item { display: flex; gap: .9rem; align-items: center; }
.sheet-item img { width: 5.5rem; aspect-ratio: 2 / 3; object-fit: cover; border-radius: .5rem; }
.sheet-item h2 { margin: 0; font-size: 1.15rem; }
.sheet-devices { display: flex; gap: .5rem; overflow-x: auto; margin: 1rem 0 .5rem; }
.sheet-device { display: flex; gap: .4rem; align-items: center; white-space: nowrap; min-height: 44px; }
.sheet-device[aria-checked="true"] { border-color: var(--accent); }
.sheet-route { min-height: 2.8em; }
.sheet-route.unroutable { color: #f2b8b5; }
#sheet-play { width: 100%; background: var(--accent); font-weight: 600; min-height: 48px; }
@media (min-width: 40rem) { .sheet { margin: auto; border-radius: 1rem; } }
#toast { display: flex; gap: .75rem; align-items: center; max-width: calc(100% - 2rem); z-index: 30; }
#toast[hidden] { display: none; }
#toast.ok { background: #1f3a24; }
.toast-action { padding: .5rem .8rem; min-height: 44px; }
```

- [ ] **Step 8: The modules**

`static/js/toast.js`:

```js
// One toast at a time. Failure toasts may carry an action ("Try Cast with …").
let timer;

export function toast(message, { action, onAction, duration, ok } = {}) {
    const el = document.getElementById("toast");
    if (!el) return;
    el.querySelector(".toast-text").textContent = message;
    const button = el.querySelector(".toast-action");
    button.hidden = !action;
    button.textContent = action || "";
    button.onclick = action ? () => { el.hidden = true; onAction(); } : null;
    el.classList.toggle("ok", Boolean(ok));
    el.hidden = false;
    clearTimeout(timer);
    timer = setTimeout(() => (el.hidden = true), duration ?? (action ? 10000 : 4000));
}
```

`static/js/play-sheet.js`:

```js
import { stateOf } from "./state-view.js";
import { toast } from "./toast.js";

export const APP_LINK_HINT_MS = 5000;

const sheet = () => document.getElementById("play-sheet");
let current = null;   // { source, item, title }
let target = null;    // device id
let previewSeq = 0;

function form(fields) {
    const body = new URLSearchParams();
    for (const [key, value] of Object.entries(fields)) {
        for (const v of [].concat(value)) body.append(key, v);
    }
    return body;
}

async function readJsonOrText(response) {
    const type = response.headers.get("Content-Type") || "";
    return type.includes("application/json") ? response.json() : { message: await response.text() };
}

function selectDevice(deviceId) {
    target = deviceId;
    for (const button of sheet().querySelectorAll("[data-sheet-device]")) {
        button.setAttribute("aria-checked", String(button.dataset.sheetDevice === deviceId));
    }
    preview();
}

async function preview() {
    const seq = ++previewSeq;
    const routeEl = document.getElementById("sheet-route");
    const play = document.getElementById("sheet-play");
    routeEl.textContent = "Working out how to play this…";
    routeEl.classList.remove("unroutable");
    play.disabled = true;
    const query = new URLSearchParams({ source: current.source, item: current.item });
    let data;
    try {
        const response = await fetch(`/devices/${encodeURIComponent(target)}/route-preview?${query}`,
            { headers: { Accept: "application/json" } });
        data = await readJsonOrText(response);
        if (!response.ok) throw new Error(data.message || "Cannot plan this right now");
    } catch (error) {
        if (seq !== previewSeq) return;
        routeEl.textContent = error.message;
        routeEl.classList.add("unroutable");
        return;
    }
    if (seq !== previewSeq) return;   // the user switched device meanwhile
    if (!data.playable) {
        routeEl.textContent = `Cannot play on ${data.deviceName}: ${data.reason}`;
        routeEl.classList.add("unroutable");
        play.textContent = "Play";
        return;
    }
    routeEl.textContent = `Play on ${data.deviceName} · ${data.route.description}`;
    play.textContent = `Play on ${data.deviceName}`;
    play.disabled = false;
}

function watchAppLink(deviceId, deviceName) {
    const before = stateOf(deviceId);
    const snapshot = (s) => JSON.stringify([s?.currentApp ?? null, s?.nowPlaying?.title ?? null]);
    const initial = snapshot(before);
    let changed = false;
    const listener = (event) => {
        if (event.detail.deviceId === deviceId && snapshot(event.detail.state) !== initial) changed = true;
    };
    document.addEventListener("homecontrol:state", listener);
    setTimeout(() => {
        document.removeEventListener("homecontrol:state", listener);
        if (!changed) {
            toast(`If nothing started on ${deviceName}, the app for this link may not be installed.`);
        }
    }, APP_LINK_HINT_MS);
}

async function attempt(deviceId, skip) {
    const play = document.getElementById("sheet-play");
    play.disabled = true;
    const request = { ...current };
    let response;
    let data;
    try {
        response = await fetch(`/devices/${encodeURIComponent(deviceId)}/play-attempt`, {
            method: "POST",
            headers: { Accept: "application/json" },
            body: form({ source: request.source, item: request.item, skip }),
        });
        data = await readJsonOrText(response);
    } catch {
        toast("Cannot reach the server");
        play.disabled = false;
        return;
    }
    if (response.ok && data.played) {
        sheet().close();
        toast(`${data.message} on ${data.deviceName}`, { ok: true });
        if (data.route.optimistic) watchAppLink(deviceId, data.deviceName);
        return;
    }
    play.disabled = false;
    if (!data.route) {
        toast(data.message || "Cannot play this");
        return;
    }
    const failed = `${data.route.description} failed: ${data.message}`;
    if (data.next) {
        current = request;
        toast(`${failed}. Next: ${data.next.description}`, {
            action: `Try ${data.next.description}`,
            onAction: () => { current = request; attempt(deviceId, [...skip, data.route.key]); },
        });
    } else {
        toast(`${failed}. There is no other way to play this on ${data.deviceName}.`);
    }
}

export function openPlaySheet(tile) {
    const d = tile.dataset;
    current = { source: d.source, item: d.item, title: d.title };
    document.getElementById("sheet-title").textContent = d.title || "";
    document.getElementById("sheet-subtitle").textContent = d.subtitle || "";
    const art = document.getElementById("sheet-art");
    art.hidden = !d.artwork;
    if (d.artwork) art.src = d.artwork; else art.removeAttribute("src");
    sheet().showModal();
    selectDevice(target && sheet().querySelector(`[data-sheet-device="${CSS.escape(target)}"]`)
        ? target : document.body.dataset.device);
}

export function initPlaySheet() {
    if (!sheet()) return;
    document.addEventListener("click", (event) => {
        const tile = event.target.closest("button.tile");
        if (tile && tile.dataset.item) openPlaySheet(tile);
        const device = event.target.closest("[data-sheet-device]");
        if (device) selectDevice(device.dataset.sheetDevice);
    });
    document.getElementById("sheet-play").addEventListener("click", () => attempt(target, []));
    sheet().addEventListener("close", () => { previewSeq++; });
}
```

Note: the sheet remembers the last device chosen in it for the lifetime of the page (`target`), and falls back to the page's selected device.

`static/js/app.js` — remove A's local `toast` function, `import { toast } from "./toast.js";` (the htmx error handlers keep using it), `import { initPlaySheet } from "./play-sheet.js";` and call `initPlaySheet();`.

- [ ] **Step 9: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. (Browser behaviour of the sheet — device switching, failure toast, app-link hint — is proven in Task 7.)

- [ ] **Step 10: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/playback src/main/java/dev/andre/homecontrol/playback \
  src/main/java/dev/andre/homecontrol/web src/main/resources/templates/dashboard.html src/main/resources/static \
  src/test/java/dev/andre/homecontrol/core/playback src/test/java/dev/andre/homecontrol/playback src/test/java/dev/andre/homecontrol/web
git commit -m "feat: play sheet with route preview, device switcher and next-route retry"
```

---

### Task 4: D4 · Sources setup

**Files:**
- Create: `core/content/SourcePreferences.java`, `core/content/StreamingProviders.java`, `content/SourcePreferencesService.java`, `content/SourcePreferencesChangedEvent.java`, `content/StoredRailPreferences.java`, `web/SourcesSetupView.java`, `web/SourcesSetupController.java`, `web/SourcesSetupAdvice.java`, `src/main/resources/templates/fragments/sources-setup.html`
- Modify: `storage/JsonFileSourceSettings.java`, `content/ContentProperties.java`, `content/ContentConfiguration.java`, `content/RailCache.java`, `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Delete: `content/DefaultRailPreferences.java`, `content/DefaultRailPreferencesTest.java`
- Test: `core/content/SourcePreferencesTest.java`, `storage/JsonFileSourceSettingsTest.java`, `content/SourcePreferencesServiceTest.java`, `content/StoredRailPreferencesTest.java`, `content/RailCacheTest.java`, `web/SourcesSetupControllerTest.java`

**Interfaces:**
- Consumes: `JsonFileSourceSettings(Path)` with `get/put/remove` (C2) and its atomic write; `RailCache.reconcile()/reschedule()`, `RailPreferences`, `ContentProperties` (Task 1); `ContentSources` (C3); `SetupController` (A/B/C).
- Produces:
  - `record SourcePreferences(List<String> railOrder, Set<String> hiddenRails, Set<String> disabledSources, Map<String, Integer> refreshMinutes, String locale, String region, List<String> providers)` with `static SourcePreferences defaults(String locale, String region)` and withers `withRailOrder`, `withRailVisible(String key, boolean)`, `withSourceEnabled(String id, boolean)`, `withRefreshMinutes(String id, Integer minutesOrNull)`, `withLocale(String locale, String region, List<String> providers)`. The compact constructor validates (see rules) and throws `IllegalArgumentException` with a user-facing message.
  - `final class StreamingProviders { static final Map<String, String> KNOWN; }` (key → display name, insertion order).
  - `JsonFileSourceSettings.preferences() → Optional<SourcePreferences>`; `JsonFileSourceSettings.putPreferences(SourcePreferences)`; `put`/`remove` preserve `preferences`.
  - `ContentProperties` gains `@DefaultValue("de-DE") String locale, @DefaultValue("DE") String region`.
  - `class SourcePreferencesService { SourcePreferencesService(JsonFileSourceSettings, ContentProperties, ApplicationEventPublisher); SourcePreferences current(); SourcePreferences update(UnaryOperator<SourcePreferences>); }` — `update` is synchronized, validates by construction, writes, publishes `SourcePreferencesChangedEvent`.
  - `class StoredRailPreferences implements RailPreferences` (constructor `SourcePreferencesService, ContentProperties`) plus `List<RailDescriptor> allRailsInOrder(List<ContentSource> sources)` (hidden included, for the setup page).
  - `RailCache.onPreferencesChanged(SourcePreferencesChangedEvent)` (`@EventListener`) → `reschedule()`.
  - Endpoints (form posts, all `302 /setup#sources` with flash attribute `sourcesMessage` or `sourcesError`): `POST /setup/sources/preferences/{sourceId}/enabled` (`enabled=true|false`), `POST /setup/sources/preferences/{sourceId}/interval` (`minutes`, blank = source default), `POST /setup/sources/preferences/rails/move` (`rail=<sourceId>/<railId>`, `direction=up|down`), `POST /setup/sources/preferences/rails/visibility` (`rail`, `visible=true|false`), `POST /setup/sources/preferences/locale` (`locale`, `region`, repeated `providers`).
  - Setup model attribute `sourcesSetup` (`SourcesSetupView`) added by `SourcesSetupAdvice` (`@ControllerAdvice(assignableTypes = SetupController.class)`).

**`sources.json` (normative).** C's shape plus an optional `preferences` object; `version` stays 1:

```json
{
  "version" : 1,
  "sources" : { "jellyfin" : { "serverUrl" : "http://nas:8096" } },
  "preferences" : {
    "railOrder" : [ "jellyfin/next-up", "jellyfin/resume", "jellyfin/latest" ],
    "hiddenRails" : [ "jellyfin/latest" ],
    "disabledSources" : [ ],
    "refreshMinutes" : { "jellyfin" : 10 },
    "locale" : "de-DE",
    "region" : "DE",
    "providers" : [ "netflix", "primevideo" ]
  }
}
```

**Validation rules (normative).** Rail keys match `^[a-z0-9][a-z0-9._-]{0,63}/[a-z0-9][a-z0-9._-]{0,63}$`; source ids `^[a-z0-9][a-z0-9._-]{0,63}$`; `railOrder` has no duplicates (duplicates after the first are dropped, not rejected) and at most 200 entries; `refreshMinutes` values 1–1440 (`Refresh every 1 to 1440 minutes`); `locale` must satisfy `Locale.forLanguageTag(tag).toLanguageTag().equals(tag)` and not be `und` (`Use a language tag such as de-DE`); `region` `^[A-Z]{2}$` (`Use a two-letter country code such as DE`); `providers` ⊆ `StreamingProviders.KNOWN` keys (`Unknown streaming service <key>`), duplicates dropped. Stored unknown rail keys (rails that no longer exist) are kept so a temporarily disconnected source keeps its place. A malformed `preferences` object → `StorageException("Could not read source preferences in <file>; fix or delete the \"preferences\" object", e)`.

**Ordering rule (normative).** `allRailsInOrder` = rails of sources that are `available()` and not disabled, gathered in source bean order; those whose key appears in `railOrder` come first in `railOrder` order, the rest follow in gathered order. `rails()` = `allRailsInOrder` minus `hiddenRails`. `refreshInterval(source)` = `refreshMinutes[source]` minutes, else `home-control.content.rails.refresh-intervals[source]`, else `source.defaultRefreshInterval()`. `sourceEnabled(id)` = `!disabledSources.contains(id)`. Moving a rail writes the full current `allRailsInOrder` key list with that rail swapped with its neighbour (moving the first up or last down is a no-op, no error).

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/andre/homecontrol/core/content/SourcePreferencesTest.java` — cases: `defaultsAreEmptyWithTheGivenLocale`; `rejectsBadLocaleRegionProvidersAndIntervals` (each message above; `withRefreshMinutes("jellyfin", 0)` and `1441` rejected, `null` removes the entry); `dropsDuplicatesInOrderAndProviders`; `rejectsMalformedRailKeys` (`"jellyfin"`, `"../x"`, `"a/b/c"`); `withersReturnCopies` (collections are unmodifiable).

`storage/JsonFileSourceSettingsTest.java` — add: `preferencesRoundTripBesideSourceSettings` (put a source map, put preferences, reopen a new instance → both present; parsed JSON has `preferences.railOrder` as an array); `puttingSourceSettingsKeepsPreferences` and `removingASourceKeepsPreferences`; `aFileWithoutPreferencesHasNone` (C's fixture shape → `preferences()` empty); `malformedPreferencesAreANamedStorageException` (`"preferences": {"refreshMinutes": {"jellyfin": "often"}}` → message contains `"preferences"`).

`src/test/java/dev/andre/homecontrol/content/StoredRailPreferencesTest.java` — stub sources `jellyfin` (rails `resume`, `next-up`, `latest`) and `tube` (rail `subs`, `defaultRefreshInterval` 60 min); a real `SourcePreferencesService` over `@TempDir`. Cases: `withoutPreferencesRailsFollowSourceOrder`; `storedOrderWinsAndNewRailsAppend` (order `[tube/subs, jellyfin/latest]` → `tube/subs, jellyfin/latest, jellyfin/resume, jellyfin/next-up`); `hiddenRailsAreNotShownButStayInTheSetupList`; `disabledSourcesDisappearAndAreNotSearchable` (`sourceEnabled("tube")` false); `unavailableSourcesKeepTheirStoredPlace` (make `tube` unavailable then available again → same position); `intervalPrecedence` (stored 10 → 10 min; none + property 2 min → 2; none → source default).

`src/test/java/dev/andre/homecontrol/content/SourcePreferencesServiceTest.java` — `currentUsesPropertyDefaultsWhenNothingIsStored` (`de-DE`/`DE` from `ContentProperties`); `updateWritesAndPublishes` (events list gets one `SourcePreferencesChangedEvent`; file contains the change); `anInvalidUpdateWritesNothing` (`IllegalArgumentException`, file unchanged, no event).

`content/RailCacheTest.java` — add `preferenceChangesReorderAndReschedule`: use a `RailPreferences` lambda-backed stub whose order and interval can be changed; after load, change interval from 10 to 2 minutes and order, call `onPreferencesChanged(new SourcePreferencesChangedEvent())` → a `RailsChangedEvent` with the new order; advancing 2 minutes and `tick()` starts fetches.

`src/test/java/dev/andre/homecontrol/web/SourcesSetupControllerTest.java` — `@WebMvcTest({SourcesSetupController.class, SetupController.class, SourcesSetupAdvice.class})` with `@MockitoBean` for `SetupController`'s dependencies (as in `SetupControllerTest`), `SourcePreferencesService prefs`, `ContentSources sources`, `StoredRailPreferences rails`. Cases:
- `theSetupPageListsSourcesRailsAndLocale`: `GET /setup` → contains `id="sources"`, the source name `Jellyfin`, rail titles in `allRailsInOrder` order, `Move up`, `Hide`/`Show`, a `minutes` input with placeholder `5` (source default), `name="locale"` with value `de-DE`, a checkbox `value="netflix"`.
- `togglingASourceUpdatesPreferences`: `POST /setup/sources/preferences/jellyfin/enabled` `enabled=false` → 302 `/setup#sources`; captured updater applied to defaults yields `disabledSources=[jellyfin]`; flash `sourcesMessage` `Jellyfin is hidden from the dashboard and search`.
- `anUnknownSourceIsRejected`: `sources.find("nope")` empty → flash `sourcesError` `No content source nope`, no update.
- `setsAndClearsTheInterval`: `minutes=10` → `refreshMinutes={jellyfin=10}`; `minutes=` → removed; `minutes=abc` → `sourcesError` `Refresh every 1 to 1440 minutes`.
- `movesARail`: current `allRailsInOrder` `[resume, next-up, latest]`, `rail=jellyfin/next-up&direction=up` → stored order `[jellyfin/next-up, jellyfin/resume, jellyfin/latest]`; `direction=sideways` → `sourcesError`.
- `hidesAndShowsARail`.
- `savesLocaleRegionAndProviders`: `locale=en-GB&region=GB&providers=netflix&providers=dazn` → stored; `locale=english` → `sourcesError` `Use a language tag such as de-DE`.
- `theseFormsAreUnderTheGuardedPrefix`: every mapping path starts with `/setup/sources/` (assert with `RequestMappingHandlerMapping` handler methods of `SourcesSetupController`).

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.content.*' --tests 'dev.andre.homecontrol.storage.*' --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.SourcesSetupControllerTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the preferences model and storage**

`core/content/StreamingProviders.java`:

```java
package dev.andre.homecontrol.core.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Streaming services a household can say it subscribes to. Keys match AppLinks service keys where one exists. */
public final class StreamingProviders {

    public static final Map<String, String> KNOWN;

    static {
        Map<String, String> known = new LinkedHashMap<>();
        known.put("netflix", "Netflix");
        known.put("primevideo", "Prime Video");
        known.put("dazn", "DAZN");
        known.put("disneyplus", "Disney+");
        known.put("appletvplus", "Apple TV+");
        known.put("paramountplus", "Paramount+");
        known.put("wowtv", "WOW");
        known.put("joyn", "Joyn");
        known.put("rtlplus", "RTL+");
        KNOWN = Collections.unmodifiableMap(known);
    }

    private StreamingProviders() {
    }
}
```

`core/content/SourcePreferences.java` — implement the record, compact-constructor validation and withers exactly per the rules (use `LinkedHashSet` to de-duplicate while keeping order; store `List.copyOf`/`Set.copyOf` — for sets that must keep order use `Collections.unmodifiableSet(new LinkedHashSet<>(…))`). Null collections become empty.

`storage/JsonFileSourceSettings.java` — keep C's public API and write pattern. Refactor internal state to the whole root document: read → `ObjectNode root` (absent file → `{"version":1,"sources":{}}`); `get/put/remove` work on `root.path("sources")`; new methods:

```java
    public synchronized Optional<SourcePreferences> preferences() {
        JsonNode node = read().path("preferences");
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SourcePreferences(
                    strings(node.path("railOrder")),
                    new LinkedHashSet<>(strings(node.path("hiddenRails"))),
                    new LinkedHashSet<>(strings(node.path("disabledSources"))),
                    minutes(node.path("refreshMinutes")),
                    node.path("locale").asString(null),
                    node.path("region").asString(null),
                    strings(node.path("providers"))));
        } catch (RuntimeException e) {
            throw new StorageException("Could not read source preferences in " + file
                    + "; fix or delete the \"preferences\" object", e);
        }
    }

    public synchronized void putPreferences(SourcePreferences preferences) {
        ObjectNode root = read();
        ObjectNode node = root.putObject("preferences");
        preferences.railOrder().forEach(node.putArray("railOrder")::add);
        preferences.hiddenRails().forEach(node.putArray("hiddenRails")::add);
        preferences.disabledSources().forEach(node.putArray("disabledSources")::add);
        ObjectNode minutes = node.putObject("refreshMinutes");
        preferences.refreshMinutes().forEach(minutes::put);
        node.put("locale", preferences.locale());
        node.put("region", preferences.region());
        preferences.providers().forEach(node.putArray("providers")::add);
        write(root);
    }
```

`strings(node)`: non-array → throw `IllegalArgumentException`; each element must be textual (`isString()` in Jackson 3; `isTextual()` if that is the available name). `minutes(node)`: object whose values are integral numbers (`isIntegralNumber()`), else `IllegalArgumentException`. A missing array or object is empty. `locale`/`region` missing → the record falls back? No: missing locale/region in a stored object is filled by the service from properties before constructing — implement by reading them as nullable and letting `SourcePreferencesService.current()` replace null with property defaults (so the record constructor accepts null for both and `withLocale` requires non-null). Document this in the record Javadoc.

- [ ] **Step 4: Implement the service, stored preferences and cache hook**

`content/SourcePreferencesChangedEvent.java`: `public record SourcePreferencesChangedEvent() {}`.

`content/SourcePreferencesService.java`:

```java
public class SourcePreferencesService {

    private final JsonFileSourceSettings settings;
    private final ContentProperties properties;
    private final ApplicationEventPublisher events;

    public SourcePreferencesService(JsonFileSourceSettings settings, ContentProperties properties,
                                    ApplicationEventPublisher events) {
        this.settings = settings;
        this.properties = properties;
        this.events = events;
    }

    public synchronized SourcePreferences current() {
        SourcePreferences stored = settings.preferences()
                .orElseGet(() -> SourcePreferences.defaults(properties.locale(), properties.region()));
        return stored.locale() == null || stored.region() == null
                ? stored.withLocale(stored.locale() == null ? properties.locale() : stored.locale(),
                        stored.region() == null ? properties.region() : stored.region(), stored.providers())
                : stored;
    }

    public synchronized SourcePreferences update(UnaryOperator<SourcePreferences> change) {
        SourcePreferences next = change.apply(current());
        settings.putPreferences(next);
        events.publishEvent(new SourcePreferencesChangedEvent());
        return next;
    }
}
```

`content/StoredRailPreferences.java` — implement the ordering rule; `rails(sources)` and `allRailsInOrder(sources)` read `service.current()` once per call.

`RailCache` — add:

```java
    @EventListener
    public void onPreferencesChanged(SourcePreferencesChangedEvent event) {
        reschedule();
    }
```

`ContentProperties` — add the two components (`@DefaultValue("de-DE") String locale`, `@DefaultValue("DE") String region`) after `rails`; update the `ContentProperties` constructions in `RailCacheTest` and elsewhere.

`ContentConfiguration` — replace the `railPreferences` bean:

```java
    @Bean
    public SourcePreferencesService sourcePreferencesService(JsonFileSourceSettings settings, ContentProperties properties,
                                                             ApplicationEventPublisher events) {
        return new SourcePreferencesService(settings, properties, events);
    }

    @Bean
    public StoredRailPreferences railPreferences(SourcePreferencesService preferences, ContentProperties properties) {
        return new StoredRailPreferences(preferences, properties);
    }
```

Delete `DefaultRailPreferences` and its test (`git rm`); `RailCacheTest` keeps using a small stub `RailPreferences` (write one inline where it used `DefaultRailPreferences`).

Configuration — add under `home-control.content` in both yaml files:

```yaml
    # Defaults until changed on the setup page (TMDB watch region and deep-link domains use them).
    locale: ${HOME_CONTROL_LOCALE:de-DE}
    region: ${HOME_CONTROL_REGION:DE}
```

(test yaml: literal `de-DE` and `DE`).

- [ ] **Step 5: Implement the setup section**

`web/SourcesSetupView.java`:

```java
public record SourcesSetupView(List<SourceRow> sources, List<RailRow> rails, String locale, String region,
                               List<ProviderRow> providers) {
    public record SourceRow(String id, String name, boolean available, boolean enabled, boolean searchable,
                            Integer refreshMinutes, long defaultMinutes) { }
    public record RailRow(String key, String title, String sourceName, boolean visible, boolean first, boolean last) { }
    public record ProviderRow(String key, String name, boolean selected) { }
}
```

`defaultMinutes` = the interval without a stored override (property override or source default), in minutes.

`web/SourcesSetupAdvice.java` — `@ControllerAdvice(assignableTypes = SetupController.class)`; constructor takes `ObjectProvider<SourcePreferencesService>`, `ObjectProvider<StoredRailPreferences>`, `ObjectProvider<ContentSources>`; `@ModelAttribute("sourcesSetup")` builds the view (returns null when any provider is unavailable, and the fragment is then skipped). Rails list = `allRailsInOrder` over **all** sources that are available (disabled sources' rails are not listed; the source row says "Hidden").

`web/SourcesSetupController.java` — `@Controller`, constructor `(SourcePreferencesService prefs, StoredRailPreferences rails, ContentSources sources)`. Each handler: validate input, call `prefs.update(...)`, set flash, `return "redirect:/setup#sources"`; catch `IllegalArgumentException` → flash `sourcesError` with its message. Messages: enabled → `<Name> is shown on the dashboard` / `<Name> is hidden from the dashboard and search`; interval → `<Name> refreshes every <n> minutes` / `<Name> uses its default refresh interval`; move → `Rail order saved`; visibility → `<Rail title> is shown` / `<Rail title> is hidden`; locale → `Language and services saved`. Parse `minutes` with `Integer.parseInt` inside try → `NumberFormatException` → `Refresh every 1 to 1440 minutes`. `direction` other than `up`/`down` → `Choose up or down`. A rail key not in `allRailsInOrder` → `No rail <key>`.

`templates/fragments/sources-setup.html` — `th:fragment="sources(view)"`, a `<section id="sources" class="setup">`:
- `<h2>Content sources</h2>`; flash messages `sourcesMessage` (`class="hint"`) and `sourcesError` (`class="error"`).
- Per source row: name, availability (`Connected`/`Not connected`), a form posting `enabled` with a button `Hide from dashboard` or `Show on dashboard`; an interval form: `<label>Refresh every <input name="minutes" type="number" min="1" max="1440" inputmode="numeric" th:value="${row.refreshMinutes()}" th:placeholder="${row.defaultMinutes()}"> minutes</label><button>Save</button>`.
- `<h3>Rails</h3>` ordered list; each `<li>`: title, source name, `Move up` (disabled when `first`), `Move down` (disabled when `last`), `Hide`/`Show` — each its own small `<form method="post">` with hidden `rail` and `direction`/`visible`.
- `<h3>Language and services</h3>`: form with `locale` text input (pattern hint `de-DE`), `region` input (`maxlength=2`, uppercase hint), provider checkboxes from `view.providers()`, `Save`. A hint: "Streaming services decide which launchers and trending titles appear; this app never reads your personal watch history from them."
- No `th:fragment` content when `view` is null.

In `setup.html` add `<section th:if="${sourcesSetup != null}" th:replace="~{fragments/sources-setup :: sources(${sourcesSetup})}"></section>` after C's Jellyfin section.

- [ ] **Step 6: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.content.*' --tests 'dev.andre.homecontrol.storage.*' --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/dev/andre/homecontrol src/main/resources src/test/java/dev/andre/homecontrol src/test/resources
git commit -m "feat: source and rail preferences on the setup page"
```

---

### Task 5: D5 · Unified search

**Files:**
- Create: `content/SearchOutcome.java`, `content/SearchService.java`, `web/SearchController.java`, `src/main/resources/templates/fragments/search.html`
- Modify: `content/ContentProperties.java`, `content/ContentConfiguration.java`, `web/ContentController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `content/SearchServiceTest.java`, `web/SearchControllerTest.java`, `web/ContentControllerTest.java`, `web/DashboardPageTest.java`

**Interfaces:**
- Consumes: `ContentSources.searchable()`, `ContentSource.search(query, limit)`, `ContentSourceException` (C3/C8); C8's `/search` records `SearchResult`, `SearchError`, `SearchResponse`; `RailPreferences.sourceEnabled` (Tasks 1, 4); tile fragment and play sheet (Tasks 2, 3).
- Produces:
  - `ContentProperties.Search(@DefaultValue("8s") Duration timeout)` as component `search`.
  - `record SearchOutcome(String query, List<Hits> hits, List<Failure> failures)` with `record Hits(ContentSource source, List<ContentItem> items)` and `record Failure(ContentSource source, String message)`.
  - `class SearchService { SearchService(ContentSources, RailPreferences, ContentProperties, ExecutorService); SearchOutcome search(String query, int limit); }` — callers validate the query.
  - `GET /search?q=&limit=` (C8 JSON shape unchanged) now delegates to `SearchService`; disabled sources are skipped; a late source appears in `errors` as `<Name> did not answer in time`.
  - `GET /search/results?q=` → fragment `fragments/search :: results` (always 200): blank → empty body; 1 character → `<p class="hint">Type at least 2 characters</p>`; > 100 → `<p class="hint">Search for 2 to 100 characters</p>`; otherwise one `<section class="rail search-hits" data-source>` per source with hits (tiles), a compact `<p class="rail-error">` per failure, and `<p class="hint">Nothing found for “q”</p>` when there are neither hits nor failures. Limit 20.
  - Dashboard search form (see Step 5).

**Search rules (normative).** Targets = `sources.searchable()` filtered by `preferences.sourceEnabled(id)`, in `ContentSources` order. Each target's `search` is submitted to the executor (virtual threads in production). One deadline for all: `start + timeout`. Results are collected in target order; each `Future.get` waits at most the remaining time; `TimeoutException` → cancel(true) and failure `<Name> did not answer in time`; `ExecutionException` whose cause is `ContentSourceException` → failure with its message; any other cause → failure `<Name> could not search` (logged at WARN); `InterruptedException` → restore the flag, cancel the rest, failures for the rest `<Name> did not answer in time`. A source with zero items is a `Hits` entry with an empty list (the fragment skips it; the JSON keeps it, as C8 did).

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/andre/homecontrol/content/SearchServiceTest.java` — real `Executors.newVirtualThreadPerTaskExecutor()` (closed in `@AfterEach`), stub sources, `ContentProperties` with a 300 ms search timeout. Cases:
- `queriesSourcesInParallelAndKeepsSourceOrder`: two sources each sleeping 200 ms → total < 350 ms (measure with `System.nanoTime`), hits in `ContentSources` order.
- `aSlowSourceBecomesAFailureAndOthersStillAnswer`: one sleeps 2 s → failure `Slow did not answer in time`, the other's hits present, returns within 600 ms.
- `aContentSourceExceptionKeepsItsMessage`, `anUnexpectedExceptionIsGeneric` (message does not contain the exception text).
- `disabledSourcesAreNotQueried`: `sourceEnabled("tube")` false → never called.
- `passesQueryAndLimitThrough`.

`src/test/java/dev/andre/homecontrol/web/SearchControllerTest.java` — `@WebMvcTest(SearchController.class)`, `@MockitoBean SearchService search`. Cases: `blankQueryRendersNothing` (empty body, `search` never called); `oneCharacterAsksForMore`; `tooLongIsExplained`; `rendersOneRailPerSourceWithTiles` (hits for `Jellyfin` with one item → `class="rail search-hits"`, `data-source="jellyfin"`, `class="tile"`, `data-item`; no `playables`); `rendersFailuresCompactly` (`class="rail-error"` with the message); `saysWhenNothingWasFound` (contains `Nothing found for` and the escaped query — use a query with `<b>` and assert `&lt;b&gt;`); `trimsTheQuery` (`q="  bunny "` → `verify(search).search("bunny", 20)`).

`web/ContentControllerTest.java` — add `@MockitoBean SearchService searchService`; rewrite C8's search cases to stub `searchService.search(...)` with a `SearchOutcome` and assert the same JSON; keep the 400 and limit-clamp cases (`verify(searchService).search("bunny", 50)`).

`web/DashboardPageTest.java` — add `hasADebouncedSearchBox`: body contains `role="search"`, `hx-get="/search/results"`, `delay:350ms`, `hx-sync="this:replace"`, `id="search-results"`.

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.content.SearchServiceTest' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure.

- [ ] **Step 3: Implement the service**

`content/SearchService.java`:

```java
package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One query, every enabled searchable source, in parallel, with one deadline (spec §6.1 search). */
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final ContentSources sources;
    private final RailPreferences preferences;
    private final ContentProperties properties;
    private final ExecutorService executor;

    public SearchService(ContentSources sources, RailPreferences preferences, ContentProperties properties,
                         ExecutorService executor) {
        this.sources = sources;
        this.preferences = preferences;
        this.properties = properties;
        this.executor = executor;
    }

    public SearchOutcome search(String query, int limit) {
        Map<ContentSource, Future<List<ContentItem>>> pending = new LinkedHashMap<>();
        for (ContentSource source : sources.searchable()) {
            if (preferences.sourceEnabled(source.id())) {
                pending.put(source, executor.submit(() -> source.search(query, limit)));
            }
        }
        long deadline = System.nanoTime() + properties.search().timeout().toNanos();
        List<SearchOutcome.Hits> hits = new ArrayList<>();
        List<SearchOutcome.Failure> failures = new ArrayList<>();
        boolean interrupted = false;
        for (Map.Entry<ContentSource, Future<List<ContentItem>>> entry : pending.entrySet()) {
            ContentSource source = entry.getKey();
            Future<List<ContentItem>> future = entry.getValue();
            if (interrupted) {
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + " did not answer in time"));
                continue;
            }
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                hits.add(new SearchOutcome.Hits(source, future.get(remaining, TimeUnit.NANOSECONDS)));
            } catch (TimeoutException e) {
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + " did not answer in time"));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ContentSourceException cause) {
                    failures.add(new SearchOutcome.Failure(source, cause.getMessage()));
                } else {
                    log.warn("{} search failed", source.id(), e.getCause());
                    failures.add(new SearchOutcome.Failure(source, source.displayName() + " could not search"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                future.cancel(true);
                failures.add(new SearchOutcome.Failure(source, source.displayName() + " did not answer in time"));
            }
        }
        return new SearchOutcome(query, hits, failures);
    }
}
```

`content/SearchOutcome.java` — the records from **Interfaces** (`List.copyOf`).

`ContentProperties` — add component `@DefaultValue Search search` with `public record Search(@DefaultValue("8s") Duration timeout) {}`; yaml under `home-control.content`: `search: { timeout: 8s }` (main) and `timeout: 2s` (test).

`ContentConfiguration` — `@Bean(destroyMethod = "close") ExecutorService searchExecutor()` returning `Executors.newVirtualThreadPerTaskExecutor()` is **not** a good idea as a bean of type `ExecutorService` (other auto-configuration may pick it up); instead create it inside the `SearchService` bean method and register `@Bean SearchService searchService(ContentSources, RailPreferences, ContentProperties)` with a `SearchService.close()` method (`executor.close()`) and `destroyMethod = "close"`. Add that `close()` to `SearchService`.

- [ ] **Step 4: Web**

`web/ContentController.search` — keep validation and clamping, replace the loop by:

```java
        SearchOutcome outcome = searchService.search(query, clamped);
        List<SearchResult> results = outcome.hits().stream()
                .map(h -> new SearchResult(h.source().id(), h.source().displayName(),
                        h.items().stream().map(ContentItemView::of).toList()))
                .toList();
        List<SearchError> errors = outcome.failures().stream()
                .map(f -> new SearchError(f.source().id(), f.message()))
                .toList();
        return ResponseEntity.ok(new SearchResponse(query, results, errors));
```

`web/SearchController.java` — `@Controller`, `@GetMapping("/search/results") String results(@RequestParam(required = false) String q, Model model)`: strip; model `query`; `state` ∈ `empty|short|long|done`; for `done`, `model.addAttribute("hits", …)` as `List<SearchHitsView(String sourceId, String sourceName, List<ContentItemView> items)>` (only non-empty) and `failures` as `List<SearchFailureView(String sourceId, String sourceName, String message)>`; return `"fragments/search :: results"`.

`templates/fragments/search.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="en">
<body>
<th:block th:fragment="results">
    <p class="hint" th:if="${state == 'short'}">Type at least 2 characters</p>
    <p class="hint" th:if="${state == 'long'}">Search for 2 to 100 characters</p>
    <th:block th:if="${state == 'done'}">
        <section class="rail search-hits" th:each="hit : ${hits}" th:attr="data-source=${hit.sourceId()}">
            <header><h2 th:text="${hit.sourceName()}">Jellyfin</h2></header>
            <div class="tiles" role="list">
                <div role="listitem" th:each="item : ${hit.items()}">
                    <button th:replace="~{fragments/rails :: tile(${item})}"></button>
                </div>
            </div>
        </section>
        <p class="rail-error" role="alert" th:each="failure : ${failures}"
           th:text="${failure.message()}">Jellyfin did not answer in time</p>
        <p class="hint" th:if="${#lists.isEmpty(hits) and #lists.isEmpty(failures)}"
           th:text="|Nothing found for “${query}”|">Nothing found</p>
    </th:block>
</th:block>
</body>
</html>
```

- [ ] **Step 5: The search box**

In `dashboard.html`, at the top of `<main id="content">`:

```html
    <form role="search" class="search" onsubmit="return false">
        <label for="search-q" class="visually-hidden">Search every source</label>
        <input id="search-q" name="q" type="search" placeholder="Search" autocomplete="off" enterkeyhint="search"
               hx-get="/search/results" hx-trigger="input changed delay:350ms, search"
               hx-target="#search-results" hx-swap="innerHTML" hx-sync="this:replace">
    </form>
    <div id="search-results" aria-live="polite"></div>
```

Append to `app.css`:

```css
.search { padding: .5rem 1rem 0; }
.search input { width: 100%; padding: .75rem 1rem; border-radius: 999px; border: 1px solid #313640;
                background: #1b1e24; color: var(--fg); font-size: 1rem; min-height: 44px; }
#search-results:not(:empty) { display: grid; gap: 1rem; margin: .75rem 0 1.25rem; }
main#content:has(#search-results:not(:empty)) #rails { display: none; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
```

(`htmx` swaps an empty body for a blank query, which brings the rails back.) Tiles in results open the play sheet through Task 3's delegated click handler — no new JS.

- [ ] **Step 6: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/content src/main/java/dev/andre/homecontrol/web src/main/resources \
  src/test/java/dev/andre/homecontrol/content src/test/java/dev/andre/homecontrol/web src/test/resources
git commit -m "feat: unified search across every enabled source"
```

---

### Task 6: D6 · PWA and touch polish

**Files:**
- Create: `core/KeyPress.java`, `web/PwaController.java`, `web/IconRenderer.java`, `web/IconController.java`, `src/main/resources/static/icons/icon.svg`, `src/main/resources/static/sw.js`, `src/main/resources/static/offline.html`, `src/main/resources/static/js/touchpad-gestures.js`, `src/main/resources/static/js/touchpad.js`, `src/main/resources/static/js/pwa.js`
- Modify: `core/Action.java`, `core/RemoteKey.java`, `adapters/androidtv/AndroidTvSession.java`, `adapters/androidtv/protocol/RemoteConnection.java`, `web/DeviceController.java`, `security/LoginGateFilter.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/templates/setup.html`, `src/main/resources/templates/login.html`, `src/main/resources/static/app.css`, `src/main/resources/static/js/remote-transport.js`, `src/main/resources/static/js/app.js`, `README.md`
- Test: `core/ActionTest.java`, `adapters/androidtv/protocol/FakeRemoteServer.java`, `adapters/androidtv/protocol/RemoteConnectionTest.java`, `web/DeviceControllerTest.java`, `web/PwaControllerTest.java`, `web/IconControllerTest.java`, `web/LoginGatingTest.java`, `web/StaticAssetsTest.java`, `web/DashboardPageTest.java`

**Interfaces:**
- Consumes: `Action.PressKey`, `RemoteKey`, `AndroidTvSession.execute/sendKey`, `RemoteConnection.sendKey` (A); `DeviceController.key` (A9); `LoginGateFilter.OPEN_PATHS` (C1); `events.js`, `state-view.js` events `homecontrol:state`/`homecontrol:stream` (Task 2); `toast.js` (Task 3).
- Produces:
  - `enum KeyPress { SHORT, START_LONG, END_LONG }`; `record Action.PressKey(RemoteKey key, KeyPress press)` with constructor `PressKey(RemoteKey key)` = `SHORT`; null `press` → `SHORT`.
  - `RemoteKey.supportsLongPress()` — true for `DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME`.
  - `RemoteConnection.sendKey(RemoteKey key, KeyPress press)` mapping `SHORT→RemoteDirection.SHORT`, `START_LONG→START_LONG`, `END_LONG→END_LONG`; the one-argument overload stays (`SHORT`).
  - `POST /devices/{id}/key/{key}?repeat=1..4&press=short|start_long|end_long` → 204 after `repeat` sequential `execute` calls / 400 `repeat must be 1 to 4` / 400 `Unknown press <value>` / 400 `A long press cannot repeat` / 400 `<KEY> has no long press`; existing statuses unchanged.
  - `GET /manifest.webmanifest` → `application/manifest+json` (JSON below), `Cache-Control: public, max-age=3600`.
  - `GET /icons/{name}.png` for `icon-192`, `icon-512`, `maskable-512`, `apple-touch-icon` (180) → `image/png`, `Cache-Control: public, max-age=86400`; other names 404. `GET /icons/icon.svg` static.
  - `LoginGateFilter` open when a login is required: `/login`, `/app.css`, `/manifest.webmanifest`, `/offline.html`, and every path starting with `/icons/`.
  - `remote-transport.js` `sendKey(deviceId, key, { repeat = 1, press = "short" } = {})`.
  - `touchpad-gestures.js` constants `TAP_SLOP_PX = 12`, `SWIPE_THRESHOLD_PX = 24`, `STEP_PX = 56`, `MAX_STEPS = 4`, `HOLD_MS = 450`; `export function classify(dx, dy)` → `{ kind: "tap" } | { kind: "swipe", key, repeat } | { kind: "none" }`; `export function stepsFor(distance)`.
  - `touchpad.js` `export function initTouchpad(root = document)`; mode key `homecontrol.remote.mode.v1`.
  - `pwa.js` `export function initPwa()`.

**Manifest (normative).**

```json
{
  "id": "/",
  "name": "Home Control",
  "short_name": "Home",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#14161a",
  "theme_color": "#14161a",
  "icons": [
    { "src": "/icons/icon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any" },
    { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any" },
    { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any" },
    { "src": "/icons/maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable" }
  ]
}
```

**Icon geometry (normative, in units of the icon size `s`).** Background `#14161a`. `any` icons: rounded square covering the canvas, corner radius `0.22 s`, transparent outside. `maskable` and `apple-touch-icon`: full-bleed square. Foreground (scaled into the central `0.8 s` safe zone for maskable, full canvas otherwise): a screen outline — rounded rectangle from `(0.18, 0.24)` to `(0.82, 0.68)`, radius `0.05`, stroke `#e8e8ea` width `0.045`; a stand bar from `(0.40, 0.76)` to `(0.60, 0.80)` filled `#e8e8ea`; a play triangle filled `#3d7dff` with vertices `(0.44, 0.35)`, `(0.62, 0.46)`, `(0.44, 0.57)`. Anti-aliasing on. `icon.svg` draws the same shapes in a `viewBox="0 0 100 100"`.

**Gesture mapping (normative).** `distance = max(|dx|, |dy|)` of the dominant axis. `|dx| ≤ 12 && |dy| ≤ 12` → tap. Dominant distance `< 24` → none. Otherwise key = `DPAD_RIGHT`/`DPAD_LEFT` when `|dx| ≥ |dy|` (sign of `dx`), else `DPAD_DOWN`/`DPAD_UP` (sign of `dy`), `repeat = stepsFor(distance) = min(4, 1 + floor((distance − 24) / 56))`. Hold: pointer stays within the tap slop for 450 ms → `DPAD_CENTER` `start_long`; release or cancel after that → `end_long` (always, exactly once); no tap is sent after a hold. Pointer cancel, `homecontrol:stream {connected:false}`, a `homecontrol:state` for the drawer's device with status ≠ `CONNECTED`, or a mode switch clears the gesture without sending a swipe (but still sends `end_long` if a hold started).

- [ ] **Step 1: Write the failing Java tests**

`core/ActionTest.java` — add `aKeyPressIsShortByDefault` (`new Action.PressKey(RemoteKey.HOME).press()` = `SHORT`; `new Action.PressKey(RemoteKey.HOME, null).press()` = `SHORT`) and `longPressSupportIsLimitedToNavigationKeys` (`DPAD_CENTER`, `BACK` true; `VOLUME_UP`, `POWER` false).

`adapters/androidtv/protocol/FakeRemoteServer.java` — record the `RemoteDirection` of each `RemoteKeyInject` beside the key code (add an accessor such as `receivedKeyPresses()` returning `List<Map.Entry<Integer, RemoteDirection>>` or a small record; keep the existing key-code accessor working). `RemoteConnectionTest` — add `sendsLongPressDirections`: `sendKey(DPAD_CENTER, START_LONG)` then `END_LONG` → the fake saw `(23, START_LONG)`, `(23, END_LONG)`.

`web/DeviceControllerTest.java` — add:
- `repeatsAShortPress`: `?repeat=3` → 204, `verify(devices, times(3)).execute("shield", new Action.PressKey(RemoteKey.DPAD_RIGHT, KeyPress.SHORT))`.
- `sendsLongPressEdges`: `POST /devices/shield/key/DPAD_CENTER?press=start_long` → `execute(… START_LONG)`; `end_long` likewise.
- `rejectsInvalidRepeatAndPress`: `repeat=0`, `repeat=5` → 400 `repeat must be 1 to 4`; `press=double` → 400 `Unknown press double`; `press=start_long&repeat=2` → 400 `A long press cannot repeat`; `VOLUME_UP?press=start_long` → 400 `VOLUME_UP has no long press`; nothing executed.
- `stopsAtTheFirstFailure`: first `execute` throws `DeviceOfflineException` → 409 and only one call.

`src/test/java/dev/andre/homecontrol/web/PwaControllerTest.java` — `@WebMvcTest(PwaController.class)`: `servesTheManifest` (content type compatible with `application/manifest+json`; `$.name` `Home Control`, `$.display` `standalone`, `$.start_url` `/`, `$.icons[3].purpose` `maskable`, `$.icons[1].sizes` `192x192`); `theServiceWorkerOnlyHandlesNavigations` (static `GET /sw.js` via a `@SpringBootTest`-free check: read `src/main/resources/static/sw.js` from the classpath and assert it contains `request.mode !== "navigate"` and does not contain `/events`, `POST` handling or `cache.put`).

`src/test/java/dev/andre/homecontrol/web/IconControllerTest.java` — `@WebMvcTest(IconController.class)` plus a plain unit part over `IconRenderer`:
- `servesEveryIconSizeAsPng`: for each name → 200, `image/png`, bytes start with the PNG signature, `ImageIO.read` dimensions 192/512/512/180.
- `anyIconsHaveTransparentCornersAndMaskableOnesAreFullBleed`: pixel `(1,1)` alpha 0 for `icon-512`; opaque `#14161a` for `maskable-512` and `apple-touch-icon`.
- `thePlayTriangleIsAccentColoured`: pixel at `(0.50 s, 0.46 s)` of `icon-512` ≈ `#3d7dff` (each channel within 8).
- `maskableContentStaysInTheSafeZone`: every non-background pixel of `maskable-512` lies within a circle of radius `0.4 s` around the centre.
- `unknownIconIs404`; `rendersOncePerName` (two requests → `IconRenderer.render` invoked once; spy or counter).

`web/LoginGatingTest.java` (C1) — add `manifestAndIconsStayOpenWhenALoginIsRequired`: with a login stored, unauthenticated `GET /manifest.webmanifest`, `GET /icons/icon-192.png`, `GET /icons/icon.svg`, `GET /offline.html` → 200; `GET /sw.js` and `GET /js/touchpad.js` → 401 (still gated); `GET /icons/../setup` → not 200.

`web/StaticAssetsTest.java` — `/js/touchpad-gestures.js` contains `export const TAP_SLOP_PX = 12`, `SWIPE_THRESHOLD_PX = 24`, `STEP_PX = 56`, `MAX_STEPS = 4`, `HOLD_MS = 450`; `/js/touchpad.js` contains `homecontrol.remote.mode.v1`; `/js/pwa.js` contains `beforeinstallprompt` and `location.protocol === "https:"`; `/offline.html` 200.

`web/DashboardPageTest.java` — add `hasPwaMetadataAndTheTouchpad`: body contains `rel="manifest"`, `href="/manifest.webmanifest"`, `viewport-fit=cover`, `name="theme-color"`, `rel="apple-touch-icon"`, `id="touchpad"`, `data-mode-switch`, and no `maximum-scale`.

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.adapters.androidtv.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure.

- [ ] **Step 3: Key press kinds end to end**

`core/KeyPress.java`: `public enum KeyPress { SHORT, START_LONG, END_LONG }` (Javadoc: "How a key is pressed. Long presses come as a start and an end edge.").

`core/Action.java` — replace `PressKey`:

```java
    record PressKey(RemoteKey key, KeyPress press) implements Action {
        public PressKey {
            press = press == null ? KeyPress.SHORT : press;
        }

        public PressKey(RemoteKey key) {
            this(key, KeyPress.SHORT);
        }

        @Override
        public Capability requires() {
            return Capability.REMOTE_KEYS;
        }
    }
```

`RemoteKey.supportsLongPress()` per **Interfaces** (switch on `this`).

`RemoteConnection` — add `sendKey(RemoteKey key, KeyPress press)` building the same `RemoteKeyInject` with `RemoteDirection` mapped by an exhaustive switch; the old `sendKey(key)` delegates with `SHORT`. `AndroidTvSession` — `case Action.PressKey press -> sendKey(press.key(), press.press());` with a matching `sendKey(RemoteKey, KeyPress)` (same offline handling as today). Other adapters that switch over `Action` compile unchanged (they match the record pattern by type).

`web/DeviceController.key`:

```java
    @PostMapping("/devices/{id}/key/{key}")
    public ResponseEntity<String> key(@PathVariable String id, @PathVariable String key,
                                      @RequestParam(defaultValue = "1") int repeat,
                                      @RequestParam(defaultValue = "short") String press) {
        RemoteKey remoteKey;
        try {
            remoteKey = RemoteKey.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return text(HttpStatus.BAD_REQUEST, "Unknown key " + key);
        }
        KeyPress keyPress;
        try {
            keyPress = KeyPress.valueOf(press.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return text(HttpStatus.BAD_REQUEST, "Unknown press " + press);
        }
        if (repeat < 1 || repeat > 4) {
            return text(HttpStatus.BAD_REQUEST, "repeat must be 1 to 4");
        }
        if (keyPress != KeyPress.SHORT && repeat != 1) {
            return text(HttpStatus.BAD_REQUEST, "A long press cannot repeat");
        }
        if (keyPress != KeyPress.SHORT && !remoteKey.supportsLongPress()) {
            return text(HttpStatus.BAD_REQUEST, remoteKey + " has no long press");
        }
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Action action = new Action.PressKey(remoteKey, keyPress);
        for (int i = 0; i < repeat; i++) {
            devices.execute(id, action);   // a failure stops here and maps to its status; nothing is retried
        }
        return ResponseEntity.noContent().build();
    }
```

`static/js/remote-transport.js`:

```js
export function sendKey(deviceId, key, { repeat = 1, press = "short" } = {}) {
    const query = new URLSearchParams();
    if (repeat !== 1) query.set("repeat", String(repeat));
    if (press !== "short") query.set("press", press);
    const suffix = query.size ? `?${query}` : "";
    return post(`/devices/${encodeURIComponent(deviceId)}/key/${key}${suffix}`);
}
```

- [ ] **Step 4: Manifest, icons, service worker, offline page**

`web/PwaController.java` — `@RestController`; `@GetMapping(path = "/manifest.webmanifest", produces = "application/manifest+json")` returns `ResponseEntity<Map<String, Object>>` built with `LinkedHashMap`s matching the normative JSON, with `CacheControl.maxAge(Duration.ofHours(1)).cachePublic()`.

`web/IconRenderer.java`:

```java
package dev.andre.homecontrol.web;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Draws the app icon with Java2D so no binary artwork lives in the repository. */
public final class IconRenderer {

    static final Color BACKGROUND = new Color(0x14161a);
    static final Color FOREGROUND = new Color(0xe8e8ea);
    static final Color ACCENT = new Color(0x3d7dff);

    public enum Shape { ROUNDED, FULL_BLEED, MASKABLE }

    private IconRenderer() {
    }

    public static byte[] png(int size, Shape shape) {
        BufferedImage image = render(size, shape);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BufferedImage render(int size, Shape shape) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(BACKGROUND);
            if (shape == Shape.ROUNDED) {
                g.fill(new RoundRectangle2D.Double(0, 0, size, size, 0.44 * size, 0.44 * size));
            } else {
                g.fill(new Rectangle2D.Double(0, 0, size, size));
            }
            double scale = shape == Shape.MASKABLE ? 0.8 : 1.0;
            double offset = (1 - scale) / 2 * size;
            double u = scale * size;
            g.translate(offset, offset);
            g.setColor(FOREGROUND);
            g.setStroke(new BasicStroke((float) (0.045 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Double(0.18 * u, 0.24 * u, 0.64 * u, 0.44 * u, 0.10 * u, 0.10 * u));
            g.fill(new Rectangle2D.Double(0.40 * u, 0.76 * u, 0.20 * u, 0.04 * u));
            g.setColor(ACCENT);
            Path2D play = new Path2D.Double();
            play.moveTo(0.44 * u, 0.35 * u);
            play.lineTo(0.62 * u, 0.46 * u);
            play.lineTo(0.44 * u, 0.57 * u);
            play.closePath();
            g.fill(play);
        } finally {
            g.dispose();
        }
        return image;
    }
}
```

(`RoundRectangle2D` takes arc *diameters*, hence `0.44 s` for a `0.22 s` radius and `0.10 u` for `0.05`. The maskable foreground's farthest corner, `(0.82, 0.68)` scaled by 0.8 around the centre, stays inside radius 0.4 s — the safe-zone test proves it.)

`web/IconController.java` — `@Controller`; `Map<String, byte[]> cache = new ConcurrentHashMap<>()`; names → `(size, shape)`: `icon-192` (192, ROUNDED), `icon-512` (512, ROUNDED), `maskable-512` (512, MASKABLE), `apple-touch-icon` (180, FULL_BLEED); `@GetMapping("/icons/{name}.png")` → `computeIfAbsent`, `ResponseEntity.ok().contentType(IMAGE_PNG).cacheControl(maxAge 1 day, public).body(bytes)`; unknown → 404. For the "renders once" test, give the controller a package-private constructor taking a `BiFunction<Integer, IconRenderer.Shape, byte[]>` renderer (default `IconRenderer::png`).

`static/icons/icon.svg`:

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <rect width="100" height="100" rx="22" fill="#14161a"/>
  <rect x="18" y="24" width="64" height="44" rx="5" fill="none" stroke="#e8e8ea" stroke-width="4.5" stroke-linejoin="round"/>
  <rect x="40" y="76" width="20" height="4" fill="#e8e8ea"/>
  <path d="M44 35 L62 46 L44 57 Z" fill="#3d7dff"/>
</svg>
```

`static/sw.js`:

```js
// Minimal service worker (registered on HTTPS only): an offline explanation page for navigations.
// It never answers SSE, POSTs, JSON, fragments or assets, so live state and the login flow are untouched.
const CACHE = "home-control-offline-v1";
const OFFLINE = "/offline.html";

self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll([OFFLINE, "/app.css", "/icons/icon.svg"])));
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(caches.keys()
        .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
        .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
    const request = event.request;
    if (request.mode !== "navigate" || request.method !== "GET") return;
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE)));
});
```

`static/offline.html` — standalone page (no scripts): title "Home Control is offline", link to `/app.css`, text "Cannot reach Home Control. Check that this phone is on the home network and the server is running, then try again." and `<a href="/">Try again</a>`.

- [ ] **Step 5: Open paths in the login gate**

`security/LoginGateFilter`:

```java
    static final Set<String> OPEN_PATHS = Set.of("/login", "/app.css", "/manifest.webmanifest", "/offline.html");
    static final List<String> OPEN_PREFIXES = List.of("/icons/");

    private static boolean open(String path) {
        return OPEN_PATHS.contains(path) || OPEN_PREFIXES.stream().anyMatch(path::startsWith);
    }
```

and use `open(path)` where `OPEN_PATHS.contains(path)` was. (`path` is the container-normalised path, so `/icons/../setup` arrives as `/setup`.) Update the class Javadoc and C's Decision comment reference ("D6 adds the manifest and icons").

- [ ] **Step 6: Page metadata, safe areas, touchpad markup**

`dashboard.html`, `setup.html` and `login.html` `<head>` — replace the viewport meta and add:

```html
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="theme-color" content="#14161a">
    <meta name="mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
    <meta name="apple-mobile-web-app-title" content="Home">
    <link rel="manifest" href="/manifest.webmanifest">
    <link rel="icon" href="/icons/icon.svg" type="image/svg+xml">
    <link rel="apple-touch-icon" href="/icons/apple-touch-icon.png">
```

In the drawer (inside B's `th:block th:if="${remoteKeys}"`), before the D-pad section:

```html
    <div class="mode-switch" role="tablist" aria-label="Remote layout" data-mode-switch>
        <button type="button" role="tab" data-mode="buttons" aria-selected="true">Buttons</button>
        <button type="button" role="tab" data-mode="touchpad" aria-selected="false">Touchpad</button>
    </div>
    <div id="touchpad" class="touchpad" tabindex="0" role="application" th:attr="data-device=${id}"
         aria-label="Touchpad: tap for OK, swipe to move, hold for a long press"
         aria-describedby="touchpad-help">
        <span id="touchpad-help" class="hint">Tap = OK · Swipe = move (longer swipe, more steps) · Hold = long press</span>
    </div>
```

Wrap the D-pad section and the Back/Home/Menu/Power row, media row and volume row in `<div class="remote-controls" data-remote th:attr="data-device=${id}">`; add class `essential` to the Back/Home/Power row and to the volume row (the fixed core stays visible in both modes). Add `data-mode="buttons"` on the `<aside id="remote-drawer">` (JS changes it).

Append to `app.css`:

```css
:root { --safe-top: env(safe-area-inset-top, 0px); --safe-right: env(safe-area-inset-right, 0px);
        --safe-bottom: env(safe-area-inset-bottom, 0px); --safe-left: env(safe-area-inset-left, 0px); }
body { padding-left: var(--safe-left); padding-right: var(--safe-right); }
.strip { padding-top: calc(.75rem + var(--safe-top)); }
.drawer { padding-bottom: calc(1rem + var(--safe-bottom)); }
.sheet { padding-bottom: calc(1.5rem + var(--safe-bottom)); }
#toast { bottom: calc(1rem + var(--safe-bottom)); }
main#content { padding-bottom: calc(5rem + var(--safe-bottom)); }
button, a { touch-action: manipulation; }
.mode-switch { display: flex; gap: .5rem; margin: .5rem 0 1rem; }
.mode-switch [aria-selected="true"] { border-color: var(--accent); }
.touchpad { display: none; position: relative; height: min(55vh, 22rem); border-radius: 1rem;
            background: #1b1e24; border: 1px solid #313640; touch-action: none; user-select: none;
            -webkit-user-select: none; -webkit-touch-callout: none; margin-bottom: 1rem; }
.touchpad .hint { position: absolute; bottom: .75rem; left: 0; right: 0; text-align: center; pointer-events: none; }
.touchpad.active { border-color: var(--accent); }
.touchpad[aria-disabled="true"] { opacity: .45; }
.drawer[data-mode="touchpad"] .touchpad { display: block; }
.drawer[data-mode="touchpad"] .remote-controls > :not(.essential) { display: none; }
.remote-controls[aria-disabled="true"] button { opacity: .55; }
@media (min-width: 48rem) and (orientation: landscape) {
    .drawer[data-mode="touchpad"] .remote-body { display: grid; grid-template-columns: 3fr 2fr; gap: 1rem; align-items: start; }
    .drawer[data-mode="touchpad"] .touchpad { height: 60vh; }
}
@media (forced-colors: active) { .touchpad { border: 2px solid CanvasText; } }
```

(Wrap touchpad + `.remote-controls` in `<div class="remote-body">` for the landscape split.)

- [ ] **Step 7: Touchpad and PWA modules**

`static/js/touchpad-gestures.js`:

```js
// Pure gesture mapping (vNext §5.4). Changing a constant requires changing its boundary tests.
export const TAP_SLOP_PX = 12;
export const SWIPE_THRESHOLD_PX = 24;
export const STEP_PX = 56;
export const MAX_STEPS = 4;
export const HOLD_MS = 450;

export function stepsFor(distance) {
    if (distance < SWIPE_THRESHOLD_PX) return 0;
    return Math.min(MAX_STEPS, 1 + Math.floor((distance - SWIPE_THRESHOLD_PX) / STEP_PX));
}

export function withinSlop(dx, dy) {
    return Math.abs(dx) <= TAP_SLOP_PX && Math.abs(dy) <= TAP_SLOP_PX;
}

export function classify(dx, dy) {
    if (withinSlop(dx, dy)) return { kind: "tap" };
    const horizontal = Math.abs(dx) >= Math.abs(dy);
    const distance = horizontal ? Math.abs(dx) : Math.abs(dy);
    const repeat = stepsFor(distance);
    if (repeat === 0) return { kind: "none" };
    const key = horizontal ? (dx > 0 ? "DPAD_RIGHT" : "DPAD_LEFT") : (dy > 0 ? "DPAD_DOWN" : "DPAD_UP");
    return { kind: "swipe", key, repeat };
}
```

`static/js/touchpad.js`:

```js
import { classify, withinSlop, HOLD_MS } from "./touchpad-gestures.js";
import { sendKey } from "./remote-transport.js";
import { toast } from "./toast.js";

const MODE_KEY = "homecontrol.remote.mode.v1";
const MODES = ["buttons", "touchpad"];

function readMode() {
    try {
        const value = localStorage.getItem(MODE_KEY);
        return MODES.includes(value) ? value : "buttons";
    } catch {
        return "buttons";
    }
}

function writeMode(mode) {
    try { localStorage.setItem(MODE_KEY, mode); } catch { /* private mode: keep it for this page only */ }
}

export function initTouchpad(root = document) {
    const drawer = root.getElementById("remote-drawer");
    const pad = root.getElementById("touchpad");
    if (!drawer || !pad) return;
    const deviceId = pad.dataset.device;
    let gesture = null;          // { id, x, y, holdTimer, holding }
    let available = true;

    const fail = (error) => toast(error.message);

    function setMode(mode) {
        cancel();
        drawer.dataset.mode = mode;
        for (const tab of drawer.querySelectorAll("[data-mode-switch] [data-mode]")) {
            tab.setAttribute("aria-selected", String(tab.dataset.mode === mode));
        }
        writeMode(mode);
    }

    function cancel() {
        if (!gesture) return;
        clearTimeout(gesture.holdTimer);
        if (gesture.holding) sendKey(deviceId, "DPAD_CENTER", { press: "end_long" }).catch(fail);
        try { pad.releasePointerCapture(gesture.id); } catch { /* already released */ }
        gesture = null;
        pad.classList.remove("active");
    }

    function setAvailable(value) {
        available = value;
        pad.setAttribute("aria-disabled", String(!value));
        drawer.querySelector(".remote-controls")?.setAttribute("aria-disabled", String(!value));
        if (!value) cancel();
    }

    pad.addEventListener("pointerdown", (event) => {
        if (gesture || event.button > 0) return;
        if (!available) {
            toast("The device is not connected");
            return;
        }
        event.preventDefault();
        pad.setPointerCapture(event.pointerId);
        pad.classList.add("active");
        gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, holding: false };
        gesture.holdTimer = setTimeout(() => {
            if (!gesture) return;
            gesture.holding = true;
            sendKey(deviceId, "DPAD_CENTER", { press: "start_long" }).catch(fail);
        }, HOLD_MS);
    });

    pad.addEventListener("pointermove", (event) => {
        if (!gesture || event.pointerId !== gesture.id || gesture.holding) return;
        if (!withinSlop(event.clientX - gesture.x, event.clientY - gesture.y)) clearTimeout(gesture.holdTimer);
    });

    pad.addEventListener("pointerup", (event) => {
        if (!gesture || event.pointerId !== gesture.id) return;
        const { x, y, holding } = gesture;
        clearTimeout(gesture.holdTimer);
        gesture = null;
        pad.classList.remove("active");
        if (holding) {
            sendKey(deviceId, "DPAD_CENTER", { press: "end_long" }).catch(fail);
            return;
        }
        const result = classify(event.clientX - x, event.clientY - y);
        if (result.kind === "tap") sendKey(deviceId, "DPAD_CENTER").catch(fail);
        if (result.kind === "swipe") sendKey(deviceId, result.key, { repeat: result.repeat }).catch(fail);
    });

    pad.addEventListener("pointercancel", cancel);
    pad.addEventListener("lostpointercapture", (event) => { if (gesture && event.pointerId === gesture.id) cancel(); });

    // Keyboard path without gestures (vNext §5.7).
    pad.addEventListener("keydown", (event) => {
        const keys = { ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT", ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", " ": "DPAD_CENTER" };
        if (!keys[event.key]) return;
        event.preventDefault();
        event.stopPropagation();
        sendKey(deviceId, keys[event.key]).catch(fail);
    });

    drawer.querySelector("[data-mode-switch]")?.addEventListener("click", (event) => {
        const tab = event.target.closest("[data-mode]");
        if (tab) setMode(tab.dataset.mode);
    });

    document.addEventListener("homecontrol:state", (event) => {
        if (event.detail.deviceId === deviceId) setAvailable(event.detail.state.status === "CONNECTED");
    });
    document.addEventListener("homecontrol:stream", (event) => {
        if (!event.detail.connected) setAvailable(false);
    });

    setMode(readMode());
}
```

(The keyboard handler stops propagation so A's global arrow-key handler does not send a second key.)

`static/js/pwa.js`:

```js
// Install guidance and, on trustworthy HTTPS only, the offline-page service worker (vNext §5.6).
export function initPwa() {
    if ("serviceWorker" in navigator && location.protocol === "https:") {
        navigator.serviceWorker.register("/sw.js").catch(() => { /* optional feature */ });
    }
    const section = document.getElementById("install");
    if (!section) return;
    const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
    const show = (id) => { const el = document.getElementById(id); if (el) el.hidden = false; };
    if (standalone) {
        show("install-done");
        return;
    }
    let deferred = null;
    window.addEventListener("beforeinstallprompt", (event) => {
        event.preventDefault();
        deferred = event;
        show("install-button");
    });
    document.getElementById("install-button")?.addEventListener("click", async () => {
        if (!deferred) return;
        deferred.prompt();
        await deferred.userChoice;
        deferred = null;
        document.getElementById("install-button").hidden = true;
    });
    if ("standalone" in navigator) show("install-ios");      // Safari on iPhone and iPad
    else show("install-other");
    if (location.protocol !== "https:") show("install-http-note");
}
```

`setup.html` — add near the end:

```html
<section id="install" class="setup">
    <h2>Install on this phone or tablet</h2>
    <p id="install-done" class="hint" hidden>Home Control is already running as an installed app.</p>
    <button type="button" id="install-button" hidden>Install Home Control</button>
    <p id="install-ios" class="hint" hidden>In Safari, tap the Share button, then <strong>Add to Home Screen</strong>.</p>
    <p id="install-other" class="hint" hidden>Open the browser menu and choose <strong>Install app</strong> or <strong>Add to Home screen</strong>.</p>
    <p id="install-http-note" class="hint" hidden>Over plain HTTP the home-screen icon opens the dashboard like a bookmark; offline support needs HTTPS through your reverse proxy.</p>
</section>
<script type="module">import { initPwa } from "/js/pwa.js"; initPwa();</script>
```

`static/js/app.js` — `import { initTouchpad } from "./touchpad.js"; import { initPwa } from "./pwa.js";` and call both after the existing setup. Keyboard handler: ignore events whose target is inside `#touchpad`.

- [ ] **Step 8: README**

Add a section `## On your phone` to `README.md`: the dashboard at `/` shows rails from connected sources, tap an item to see how it will play and on which device; the remote opens from the chevron next to the selected device; the touchpad mode (tap = OK, swipe = move, longer swipe = more steps up to four, hold = long press) and that the choice is remembered per browser; installing to the home screen (Setup → Install), with the HTTPS note; keyboard shortcuts unchanged.

- [ ] **Step 9: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.adapters.androidtv.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. (Gesture → POST behaviour and mode persistence are proven in Task 7's `TouchpadE2eTest`.)

- [ ] **Step 10: Commit**

```bash
git add src/main/java src/main/resources src/test/java README.md
git commit -m "feat: installable dashboard with safe areas and a touchpad remote"
```

---

### Task 7: D7 · Playwright harness

**Files:**
- Create: `.superpowers/e2e.Dockerfile`, `.superpowers/e2e.sh`, `src/e2e/java/dev/andre/homecontrol/e2e/BrowserTest.java`, `Browsers.java`, `BrowserSession.java`, `FakeDeviceAdapter.java`, `FakeContentSource.java`, `E2eFakesConfiguration.java`, `E2eApplicationTest.java`, `PlaySheetE2eTest.java`, `DeviceSwitchingE2eTest.java`, `RailFailureE2eTest.java`, `LoginGatingE2eTest.java`, `TouchpadE2eTest.java`; `docs/superpowers/reviews/2026-09-16-dashboard-shell-acceptance.md`
- Modify: `build.gradle.kts`, `.superpowers/gradle.sh`, `.github/workflows/ci.yml`, `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6; `DeviceManager.adopt/forget` (A6), `DeviceAdapter`/`DeviceHandle` contracts as they exist after B (implement every abstract method the real interface has; B adds `kind()`, `settingsFor(DiscoveredDevice)`, `credentialsBoundToDeviceId()` — give them trivial implementations if they are abstract), `Action.CastLoad` (B5), `ActionFailedException` (B4), `LoginService.storeSecrets(Map, String, String, HttpServletRequest)` and `removeSecrets(Collection)` (C1), `ContentSource` (C3), `RailCache` (Task 1).
- Produces:
  - Gradle source set `e2e` (`src/e2e/java`), configurations `e2eImplementation`/`e2eRuntimeOnly` extending the test ones, task `e2eTest` (not part of `check`/`build`), task `installPlaywrightBrowsers`; Gradle property `-Pe2eBrowsers=chromium,webkit` (default both).
  - `.superpowers/gradle.sh` honours `HC_GRADLE_IMAGE` (default `gradle:jdk25`).
  - `.superpowers/e2e.sh [gradle args…]` — builds `home-control-e2e:playwright-<version>` if missing, runs `e2eTest` in it.
  - CI job `e2e` (Chromium + WebKit), required by `release`.
  - `@BrowserTest` = parameterized over browser names; `Browsers.open(String browser, String baseUrl, String traceName) → BrowserSession` (`page()`, `close()` saves the trace).

- [ ] **Step 1: Gradle wiring**

In `build.gradle.kts`, after `dependencies { … }` add:

```kotlin
// Browser tests (Playwright for Java) live in their own source set so `build` never resolves
// Playwright (~200 MB driver bundle) and never needs installed browsers. Run: ./gradlew e2eTest
val playwrightVersion = "1.63.0"

sourceSets {
    create("e2e") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations["e2eImplementation"].extendsFrom(configurations["testImplementation"])
configurations["e2eRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "e2eImplementation"("com.microsoft.playwright:playwright:$playwrightVersion") {
        // Spring Boot's Logback is the SLF4J provider; two providers only produce warnings.
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
}

val e2eTest by tasks.registering(Test::class) {
    description = "Runs the Playwright browser tests (needs installed browsers, see installPlaywrightBrowsers)."
    group = "verification"
    testClassesDirs = sourceSets["e2e"].output.classesDirs
    classpath = sourceSets["e2e"].runtimeClasspath
    shouldRunAfter(tasks.test)
    // Fail fast with a clear error instead of downloading browsers in the middle of a test run.
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    systemProperty("e2e.browsers", (findProperty("e2eBrowsers") as String?) ?: "chromium,webkit")
    systemProperty("e2e.artifacts", layout.buildDirectory.dir("e2e-artifacts").get().asFile.absolutePath)
    maxParallelForks = 1
}

val installPlaywrightBrowsers by tasks.registering(JavaExec::class) {
    description = "Installs Playwright's Chromium and WebKit plus their OS packages (needs root or passwordless sudo)."
    group = "verification"
    classpath = configurations["e2eRuntimeClasspath"]
    mainClass = "com.microsoft.playwright.CLI"
    args("install", "--with-deps", "chromium", "webkit")
}
```

The existing `tasks.withType<Test> { useJUnitPlatform() … }` block also configures `e2eTest`. Do **not** add `e2eTest` to `check`.

`.superpowers/gradle.sh` — replace the image literal: add `IMAGE="${HC_GRADLE_IMAGE:-gradle:jdk25}"` after `ROOT=…` and use `"$IMAGE"` in place of `gradle:jdk25` in the `docker run` line; update the usage comment (`HC_GRADLE_IMAGE=… .superpowers/gradle.sh e2eTest`).

- [ ] **Step 2: The local browser image and script**

`.superpowers/e2e.Dockerfile` (verified 2026-09-16 with version 1.63.0; both browsers launch as uid 1000):

```dockerfile
# Gradle + JDK 25 plus Playwright's Chromium and WebKit with their OS packages, for
# `.superpowers/e2e.sh`. Browsers live in /ms-playwright so any uid can use them.
FROM gradle:jdk25
ARG PLAYWRIGHT_VERSION
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN set -eux; \
    test -n "$PLAYWRIGHT_VERSION"; \
    case "$(dpkg --print-architecture)" in \
      amd64) node_dir=linux ;; \
      arm64) node_dir=linux-arm64 ;; \
      *) echo "unsupported architecture" >&2; exit 1 ;; \
    esac; \
    repo=https://repo1.maven.org/maven2/com/microsoft/playwright; \
    work=$(mktemp -d); cd "$work"; \
    curl -fsSLo driver.jar "$repo/driver/$PLAYWRIGHT_VERSION/driver-$PLAYWRIGHT_VERSION.jar"; \
    curl -fsSLo bundle.jar "$repo/driver-bundle/$PLAYWRIGHT_VERSION/driver-bundle-$PLAYWRIGHT_VERSION.jar"; \
    unzip -q driver.jar 'driver/package/*'; \
    unzip -q bundle.jar "driver/$node_dir/node"; \
    chmod +x "driver/$node_dir/node"; \
    apt-get update; \
    (cd driver/package && "../$node_dir/node" cli.js install --with-deps chromium webkit); \
    chmod -R a+rX /ms-playwright; \
    cd /; rm -rf "$work" /var/lib/apt/lists/*
```

`.superpowers/e2e.sh` (mode 755):

```bash
#!/usr/bin/env bash
# Runs the Playwright browser tests inside gradle:jdk25 + browsers (no local JDK or browsers needed).
# Usage: .superpowers/e2e.sh                      # Chromium and WebKit
#        .superpowers/e2e.sh -Pe2eBrowsers=chromium --tests '*PlaySheet*'
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's/^val playwrightVersion = "\(.*\)"$/\1/p' "$ROOT/build.gradle.kts")"
if [ -z "$VERSION" ]; then
  echo "playwrightVersion not found in build.gradle.kts" >&2
  exit 1
fi
IMAGE="home-control-e2e:playwright-$VERSION"
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  docker build -t "$IMAGE" --build-arg "PLAYWRIGHT_VERSION=$VERSION" \
    -f "$ROOT/.superpowers/e2e.Dockerfile" "$ROOT/.superpowers"
fi
HC_GRADLE_IMAGE="$IMAGE" exec "$ROOT/.superpowers/gradle.sh" e2eTest "$@"
```

- [ ] **Step 3: The harness**

`src/e2e/java/dev/andre/homecontrol/e2e/BrowserTest.java`:

```java
package dev.andre.homecontrol.e2e;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs the test once per browser named in -De2e.browsers (default chromium,webkit). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest(name = "{0}")
@MethodSource("dev.andre.homecontrol.e2e.Browsers#names")
public @interface BrowserTest {
}
```

`Browsers.java`:

```java
package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** One Playwright and one browser per kind for the whole JVM; a fresh context per test. */
public final class Browsers {

    private static Playwright playwright;
    private static final Map<String, Browser> browsers = new HashMap<>();

    private Browsers() {
    }

    public static Stream<String> names() {
        return Arrays.stream(System.getProperty("e2e.browsers", "chromium,webkit").split(","))
                .map(String::strip).filter(name -> !name.isEmpty());
    }

    private static synchronized Browser browser(String name) {
        if (playwright == null) {
            playwright = Playwright.create();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> playwright.close()));
        }
        return browsers.computeIfAbsent(name, n -> {
            BrowserType type = switch (n) {
                case "chromium" -> playwright.chromium();
                case "webkit" -> playwright.webkit();
                case "firefox" -> playwright.firefox();
                default -> throw new IllegalArgumentException("Unknown browser " + n);
            };
            return type.launch(new BrowserType.LaunchOptions().setHeadless(true));
        });
    }

    public static BrowserSession open(String browser, String baseUrl, String traceName) {
        BrowserContext context = browser(browser).newContext(new Browser.NewContextOptions()
                .setBaseURL(baseUrl)
                .setViewportSize(390, 844)
                .setHasTouch(true)
                .setLocale("en-US"));
        context.setDefaultTimeout(10_000);
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        Path trace = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"),
                traceName.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + browser + ".zip");
        return new BrowserSession(context, context.newPage(), trace);
    }
}
```

`BrowserSession.java`: `public record BrowserSession(BrowserContext context, Page page, Path trace) implements AutoCloseable` whose `close()` calls `context.tracing().stop(new Tracing.StopOptions().setPath(trace))` then `context.close()`.

`FakeDeviceAdapter.java` — `DeviceAdapter` with id `e2e-fake`. Per device, adapter settings: `caps` (comma-separated `Capability` names; blank entries ignored, so `""` means no capabilities) and optional `fail` (comma-separated simple class names of `Action` records, e.g. `OpenAppLink`). `connect` returns a handle whose state is `CONNECTED`, power on, `currentApp` `com.example.launcher`, published once through `onChange`; the handle's `execute` records `Recorded(String deviceId, Action action)` in a `CopyOnWriteArrayList` and throws `new ActionFailedException(device.name() + " refused to " + <action simple name>)` when that action is listed in `fail`. Public methods: `List<Recorded> recorded()`, `List<Action> recorded(String deviceId)`, `void clear()`, `void push(String deviceId, DeviceState state)` (calls the stored `onChange`), `discovered()` → empty.

`FakeContentSource.java` — `ContentSource` id `e2e`, name `E2E`, available, searchable. Rails: `picks` "Picks" and `flaky` "Flaky rail". Items (artwork null, progress 0.4 for the first):
- `clip-1` "Big Buck Bunny", subtitle `2008`, kind `MOVIE`, playables `AppLink(https://www.youtube.com/watch?v=aqz-KE-bpKQ, "youtube")`, `StreamUrl(http://127.0.0.1:9/bunny.mp4, "video/mp4")`.
- `clip-2` "Sintel", kind `MOVIE`, playables `AppLink(https://www.youtube.com/watch?v=eRsGyueVLvQ, "youtube")`.
`rail("picks")` → both; `rail("flaky")` → throws `new ContentSourceException("E2E source is down")` while `broken` (an `AtomicBoolean`, initially true), else `[clip-2]`; `item(id)` from the map; `search(q, limit)` → items whose title contains `q` case-insensitively. Methods `breakFlaky()`, `heal()`.

`E2eFakesConfiguration.java` — `@TestConfiguration` with `@Bean FakeDeviceAdapter` and `@Bean FakeContentSource`.

`E2eApplicationTest.java`:

```java
package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

/** The real application on a random port with fake devices and a fake content source. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(E2eFakesConfiguration.class)
public abstract class E2eApplicationTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dir = Files.createTempDirectory("home-control-e2e").toString();
        registry.add("shield.data-dir", () -> dir);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DeviceManager devices;

    @Autowired
    protected FakeDeviceAdapter fakeDevices;

    @Autowired
    protected FakeContentSource fakeContent;

    @Autowired
    protected RailCache rails;

    protected String traceName;

    @BeforeEach
    void devicesAndContent(TestInfo info) {
        traceName = info.getTestClass().map(Class::getSimpleName).orElse("e2e") + "-"
                + info.getTestMethod().map(m -> m.getName()).orElse("test");
        adopt("living", "Living Room", "APP_LINK,REMOTE_KEYS", "");
        adopt("bedroom", "Bedroom", "APP_LINK,CAST_RECEIVER,REMOTE_KEYS", "OpenAppLink");
        adopt("speaker", "Speaker", "", "");
        fakeContent.breakFlaky();
        fakeDevices.clear();
    }

    @AfterEach
    void cleanUp() {
        for (String id : new String[] {"living", "bedroom", "speaker"}) {
            devices.forget(id);
        }
        fakeDevices.clear();
    }

    protected void adopt(String id, String name, String caps, String fail) {
        devices.adopt(new Device(id, name, DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("e2e-fake", Map.of("caps", caps, "fail", fail)), Instant.now()));
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected BrowserSession open(String browser) {
        return Browsers.open(browser, baseUrl(), traceName);
    }
}
```

(`DeviceManager.devices()` sorts by name, so the strip order is Bedroom, Living Room, Speaker; tests select devices explicitly with `?device=`. If `rails` keeps entries between tests, call `rails.reconcile()` — no reset is needed because the fake source's rails are stable and `breakFlaky()` plus a refresh re-establishes the failed state; tests that need a fresh failure call `rails.refresh("e2e","flaky")` and await `FAILED`.)

- [ ] **Step 4: Write the browser tests**

`PlaySheetE2eTest.java`:

```java
package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.andre.homecontrol.core.Action;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

class PlaySheetE2eTest extends E2eApplicationTest {

    @BrowserTest
    void showsThePlannedRouteBeforePlayingAndPlaysWithOneTap(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();

            assertThat(page.locator("#sheet-title")).hasText("Big Buck Bunny");
            assertThat(page.locator("#sheet-route")).hasText("Play on Living Room · Open in the YouTube app");
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).isEmpty();

            page.locator("#sheet-play").click();

            await().until(() -> fakeDevices.recorded("living").stream().anyMatch(Action.OpenAppLink.class::isInstance));
            assertThat(page.locator("#toast")).containsText("Open in the YouTube app on Living Room");
            assertThat(page.locator("#play-sheet")).not().hasAttribute("open", "");
        }
    }

    @BrowserTest
    void hintsThatTheAppMayNotBeInstalledWhenNothingChanges(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.clock().install();
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();
            page.locator("#sheet-play").click();
            assertThat(page.locator("#toast")).containsText("on Living Room");

            page.clock().runFor(5_100);

            assertThat(page.locator("#toast .toast-text"))
                    .hasText("If nothing started on Living Room, the app for this link may not be installed.");
        }
    }

    @BrowserTest
    void aFailureNamesTheFailedRouteAndOffersTheNextOne(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=bedroom");
            page.locator("button.tile[data-item='clip-1']").click();
            assertThat(page.locator("#sheet-route")).hasText("Play on Bedroom · Open in the YouTube app");

            page.locator("#sheet-play").click();

            assertThat(page.locator("#toast .toast-text")).hasText(Pattern.compile(
                    "^Open in the YouTube app failed: Bedroom refused to OpenAppLink\\. Next: Cast with the Default Media Receiver$"));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Try Cast with the Default Media Receiver")).click();

            await().until(() -> fakeDevices.recorded("bedroom").stream().anyMatch(Action.CastLoad.class::isInstance));
            assertThat(page.locator("#toast")).containsText("Cast with the Default Media Receiver on Bedroom");
        }
    }

    @BrowserTest
    void anUnroutableDeviceExplainsWhyAndCannotPlay(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=speaker");
            page.locator("button.tile[data-item='clip-2']").click();

            assertThat(page.locator("#sheet-route")).containsText("Cannot play on Speaker");
            assertThat(page.locator("#sheet-play")).isDisabled();
        }
    }
}
```

(If the real `ActionFailedException` message format in the fake differs, keep the regex in sync with `FakeDeviceAdapter`.)

`DeviceSwitchingE2eTest.java` — cases (each `@BrowserTest`, `try (BrowserSession …)`):
- `tappingAChipTargetsThatDeviceForKeys`: navigate `/?device=living`; click the chip link for `Bedroom` (`a.chip[data-device='bedroom']`); `assertThat(page).hasURL(Pattern.compile(".*device=bedroom.*"))`; open the drawer (`.drawer-toggle`); click the `Home` button inside `#remote-drawer`; await `fakeDevices.recorded("bedroom")` contains `new Action.PressKey(RemoteKey.HOME)`; `recorded("living")` has no `PressKey`.
- `theSheetSwitcherReplansForTheChosenDevice`: `/?device=living`, open `clip-1`; route text `… Open in the YouTube app`; click `[data-sheet-device='speaker']` → `aria-checked="true"`, route contains `Cannot play on Speaker`, Play disabled; click `[data-sheet-device='bedroom']` → `Play on Bedroom · Open in the YouTube app`, Play enabled; nothing recorded on any device.
- `liveStateReachesTheStripAndTheSheet`: `/?device=living`; `fakeDevices.push("living", state with status DISCONNECTED)` → `#status-living` has text `DISCONNECTED`; open a tile → `[data-status-for='living']` has text `DISCONNECTED`; push `CONNECTED` → both show `CONNECTED`.

`RailFailureE2eTest.java` — cases:
- `aFailedRailShowsACompactErrorWithRetryNotAGap`: `rails.refresh("e2e","flaky")` and await status `FAILED` via `rails.snapshot`; navigate `/`; the section `.rail[data-rail='e2e/flaky']` is visible with `data-status="FAILED"`, contains `Couldn't load Flaky rail: E2E source is down` and a `Retry` button; its bounding box height ≥ 80 px (`section.boundingBox().height`); the `picks` rail shows 2 tiles.
- `retryRecoversTheRail`: as above, then `fakeContent.heal()`, click `Retry` → the section eventually has `data-status="READY"` and one tile `clip-2` (the POST returns the refreshing fragment; the SSE `rail` event triggers the final fragment fetch).
- `aRailThatBreaksWhileTheTabIsOpenUpdatesWithoutReload`: navigate with the flaky rail healed and READY; `fakeContent.breakFlaky()`; `rails.refresh("e2e","flaky")` from the test → the open page shows `rail-stale` "Couldn't refresh" while keeping the `clip-2` tile (no navigation: assert `page.url()` unchanged).
- `searchResultsOpenThePlaySheet`: type `bunny` into `#search-q` → `#search-results .tile[data-item='clip-1']` visible and `#rails` hidden; click it → `#sheet-title` `Big Buck Bunny`; clear the box → `#rails` visible again.

`LoginGatingE2eTest.java` — autowire `LoginService`. `@BeforeEach` (after the base one): `login.storeSecrets(Map.of("e2e.token", "not-a-real-token"), "correct horse", "correct horse", new MockHttpServletRequest())`; `@AfterEach`: `login.removeSecrets(List.of("e2e.token"))`. Cases:
- `theDashboardRedirectsToLoginAndOpensAfterThePassword`: navigate `/?device=living` → URL matches `/login`; fill `input[name=password]` with `wrong` and submit → an error is visible and URL still `/login`; fill `correct horse` and submit → URL matches `device=living`; `.rail[data-rail='e2e/picks']` visible; after `fakeDevices.push("living", DISCONNECTED state)` the badge updates (proves `/events` is authorised); opening the sheet shows the route (proves `/devices/{id}/route-preview` is authorised).
- `pwaFilesStayReachableWithoutASession`: with `session.context().request()` (no cookies) `GET /manifest.webmanifest` → 200 and JSON `name` `Home Control`; `GET /icons/icon-192.png` → 200 `image/png`; `GET /events` → 401; `GET /rails` → 401; `GET /devices/living/route-preview?source=e2e&item=clip-1` → 401.
- `theTokenNeverReachesThePage`: after logging in, `page.content()` and the body of `GET /rails` (via `page.request()`, which shares cookies) do not contain `not-a-real-token`.

`TouchpadE2eTest.java` — cases:
- `tapAndSwipesSendTheDocumentedKeys`: `/?device=living&remote=open`; click tab `Touchpad`; get `#touchpad` bounding box centre `(cx, cy)`; `page.mouse().move(cx, cy); down(); up();` → recorded `PressKey(DPAD_CENTER, SHORT)`; `move(cx, cy); down(); move(cx + 120, cy, steps 6); up();` → two `PressKey(DPAD_RIGHT, SHORT)` recorded from one request (assert count 2 after awaiting); a 20 px move → nothing more recorded within 500 ms.
- `holdSendsStartAndEndOfALongPress`: `page.clock().install()` before navigation; `down()`, `page.clock().runFor(500)`, `up()` → recorded `START_LONG` then `END_LONG` for `DPAD_CENTER`, and no `SHORT`.
- `theModeIsRememberedAcrossReloads`: select `Touchpad`, reload → `#remote-drawer[data-mode='touchpad']`; set `localStorage['homecontrol.remote.mode.v1'] = 'bogus'` and reload → `buttons`.
- `gesturesAreRefusedWhileTheDeviceIsDisconnected`: push `DISCONNECTED` → `#touchpad[aria-disabled='true']`; a tap records nothing and a toast `The device is not connected` appears.

(Use `org.assertj.core.api.Assertions` fully qualified or statically imported under another name where Playwright's `assertThat` is imported.)

- [ ] **Step 5: Run the browser tests locally**

Run: `chmod +x .superpowers/e2e.sh && .superpowers/e2e.sh`
Expected: the image builds once (several minutes, ~1 GB), then `e2eTest` passes for Chromium and WebKit. On failure open the trace: `build/e2e-artifacts/<Class>-<method>-<browser>.zip` at `https://trace.playwright.dev` (the upload stays in the browser) and the report at `build/reports/tests/e2eTest/index.html`.

Then confirm the normal build is unaffected: `.superpowers/gradle.sh build` — BUILD SUCCESSFUL, and `.superpowers/gradle.sh dependencies --configuration testRuntimeClasspath | grep -c playwright` prints `0`.

- [ ] **Step 6: CI**

In `.github/workflows/ci.yml` add a job after `test`:

```yaml
  e2e:
    name: Browser tests (Chromium, WebKit)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'

      - uses: gradle/actions/setup-gradle@v6

      # Browser binaries are version-pinned by build.gradle.kts; OS packages are re-installed every
      # run by --with-deps (fast when already present) because the runner image is fresh.
      - name: Cache Playwright browsers
        uses: actions/cache@v6
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('build.gradle.kts') }}

      - name: Install Chromium and WebKit
        run: ./gradlew installPlaywrightBrowsers

      - name: Run the browser tests
        run: ./gradlew e2eTest

      - name: Upload the browser test report and traces
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: e2e-report
          path: |
            build/reports/tests/e2eTest
            build/e2e-artifacts
          if-no-files-found: ignore
```

and change the release job to `needs: [test, image, e2e]`.

- [ ] **Step 7: Acceptance checklist and README**

`docs/superpowers/reviews/2026-09-16-dashboard-shell-acceptance.md`:

```markdown
# Dashboard shell (sub-project D) — manual acceptance

Automated coverage: `RailCacheTest`, `RailControllerTest`, `ContentPlayPreviewTest`, `SourcesSetupControllerTest`,
`SearchServiceTest`, `IconControllerTest`, and the Playwright suite (`./gradlew e2eTest`: play sheet, device
switching, rail failure, login gating, touchpad) in Chromium and WebKit. Playwright WebKit is not iOS Safari,
so the items below need real phones, tablets and devices. Agents never mark these as passed.

| # | Check | Result |
|---|---|---|
| 1 | With Jellyfin connected, the dashboard shows Continue watching / Next up / Latest within seconds of opening, and opening it again is instant | Pending — requires real hardware |
| 2 | Stop the Jellyfin server: rails keep their items with "Couldn't refresh"; after a restart of the app with Jellyfin still down, each rail shows the compact error with Retry and no empty gap; start Jellyfin and press Retry | Pending — requires real hardware |
| 3 | Resume an episode on the TV, wait 5 minutes with the dashboard open: Continue watching updates without reloading | Pending — requires real hardware |
| 4 | Tap an item: the sheet names the route for the Shield (e.g. "Play in the open Jellyfin app (Android TV)"); switch to a Chromecast: the route changes to a Cast route; Play starts it on the chosen device | Pending — requires real hardware |
| 5 | Make a route fail (e.g. power off the Cast side): the toast names the failed route and the next one, and "Try …" plays through it | Pending — requires real hardware |
| 6 | Open a YouTube item on a device without the YouTube app: after about 5 s the "may not be installed" hint appears; with the app installed it does not | Pending — requires real hardware |
| 7 | Setup: hide a rail, move another to the top, set Jellyfin to refresh every 10 minutes; an open dashboard on a second phone reorders without reload; `/data/sources.json` holds a `preferences` object and survives a container restart | Pending — requires real hardware |
| 8 | Search "a" shows the hint, a title finds movies and episodes, typing fast sends few requests (browser devtools), tapping a result opens the sheet | Pending — requires real hardware |
| 9 | iPhone (Safari): Add to Home Screen, launch: standalone, icon correct, content clear of the notch and home indicator; login works in the standalone app | Pending — requires real hardware |
| 10 | Android (Chrome): install from Setup or the menu; the maskable icon is not clipped; behind an HTTPS reverse proxy, airplane mode shows the offline page and live updates resume after reconnecting | Pending — requires real hardware |
| 11 | iPad landscape: touchpad on the left, essential buttons on the right; phone portrait: touchpad mode; tap, swipes of increasing length (1–4 steps) and hold behave as documented on the Shield | Pending — requires real hardware |
| 12 | Software keyboard on iPhone and Android does not cover the search box or the setup inputs in an unusable way | Pending — requires real hardware |

## Findings

(none recorded yet)
```

`README.md` — add `## Browser tests` under the development section: `./gradlew build` never needs browsers; `./gradlew installPlaywrightBrowsers` (root or sudo; Ubuntu 22.04–26.04) then `./gradlew e2eTest` (`-Pe2eBrowsers=chromium` to narrow); without a local JDK use `.superpowers/e2e.sh`; traces in `build/e2e-artifacts`.

- [ ] **Step 8: Build and commit**

Run: `.superpowers/gradle.sh build` then `.superpowers/e2e.sh`
Expected: BUILD SUCCESSFUL; all browser tests pass in both browsers.

```bash
git add build.gradle.kts .superpowers/gradle.sh .superpowers/e2e.sh .superpowers/e2e.Dockerfile \
  .github/workflows/ci.yml src/e2e README.md docs/superpowers/reviews/2026-09-16-dashboard-shell-acceptance.md
git commit -m "test: Playwright browser tests for the dashboard in Chromium and WebKit"
```

---

## Final Automated Verification

```bash
.superpowers/gradle.sh clean build
.superpowers/e2e.sh
docker compose up --build
```

Expected: build green without Playwright on the classpath; the browser suite green in Chromium and WebKit; the container starts against an existing `./data`, `GET /` renders the device strip, rails (or the "Connect a source" hint), the search box and a closed remote drawer; `GET /manifest.webmanifest` and `GET /icons/icon-512.png` answer 200.

## Out of scope for this plan

- The vNext secondary-control editor (show, hide, reorder Menu/Play/Info/… and reset); only the fixed core and mode switch ship here.
- Versioned asset pre-caching and an explicit "Update available" service-worker flow; the service worker only provides the offline page.
- Server-side artwork cache under `/data/cache` (spec §8); artwork relies on browser caching of C's immutable image URLs.
- Consumers of locale, region and providers (TMDB trending, deep-link domains): sub-projects G and H.
- Drag-and-drop rail ordering; per-user preferences.
- YouTube quota accounting in search (E4); per-source search limits beyond the shared deadline.
