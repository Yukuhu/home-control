# Architecture and maintainability roadmap

Date: 2026-09-27

Status: Design approved section by section by the user on 2026-09-27; written spec awaiting review.

## Purpose and agreed scope

The user asked for an architectural analysis of the whole project and a map of the improvements, refactors and
similar actions that would raise overall quality and maintainability.

**What the user said:**

- All four goals count equally: make **adding devices and sources** cheap, cause **fewer bugs and races**, make the
  code **easier to navigate** for people and agents, and give **faster, less flaky builds and CI**.
- Every kind of change is acceptable: new build and test tools, a Gradle multi-module split, `/data` format changes
  with automatic migration, and visible UI and HTTP changes.
- The program is sequenced **guardrails first**: fix known defects, add enforcement and test infrastructure, build
  shared building blocks, then do the structural splits, and split Gradle modules last.
- Four visible changes are approved: the login no longer disappears with the last secret, configuration keys are
  renamed with the old names still accepted, the unused `ContentController` JSON API is deleted, and virtual threads
  are enabled.
- After the Cyberpunk theme was merged during the analysis, the user asked whether theme switching and adding themes
  need a proper architecture base too. They do (theme 10 below, workstream 2F). The theme choice stays per browser,
  and how people pick among more than two themes is decided when a third theme is planned.

**Assumptions:**

- This document is a roadmap. Each workstream marked M or L gets its own spec, plan and pull requests; S items are
  handled as bounded changes with a short design in chat. Nothing here authorizes implementation by itself.
- The product, its protocols and its user-facing behaviour stay as they are unless this document names a change.
- The analysis reflects `main` at `89e8491` (CI green), plus the theme feature merged after it (`55aa212`,
  `343d27c`). Line numbers below are from those commits and will move.

## Baseline

| Measure | Value at `343d27c` |
| --- | --- |
| Main source | 423 Java files, 34,275 lines; largest class `DeviceManager` at 813 lines |
| Tests | 326 test files: 240 plain JUnit, 28 `@WebMvcTest`, 27 `@SpringBootTest`, 12 Playwright classes |
| Coverage | 87.6% (SonarCloud) |
| Spring context starts per test run | about 60 (estimate from annotations; measured in Phase 1) |
| CI on a push to `main` | about 16 minutes end to end; "Build and test" alone about 9 minutes (measured at `89e8491`) |
| Architecture rules enforced by the build | none |
| Places to edit when adding a UI theme | 9 (see theme 10) |

## What the analysis found

The code is in good shape locally. `core/` imports nothing but the JDK, the playback planner is pure, `Action` is a
sealed hierarchy dispatched with exhaustive switches, every device protocol has an in-process fake, the security
design is careful and coherent, `RailCache` serves stale data while refreshing, and `sports/ics` is a self-contained
library. The problems are structural: code copied between sibling modules has drifted apart, and nothing enforces
the architecture the [concept](2026-09-16-home-control-center-concept.md) describes in §7.

### Confirmed defects and risks

1. **Calendar DNS rebinding.** `CalendarFetcher.fetch` checks the address policy and then lets the JDK client resolve
   the host again when it connects (`sources/sports/calendar/CalendarFetcher.java:52-53`). A hostile calendar host or
   redirect target can rebind to a loopback or LAN address between the check and the connection.
   `WorkflowHttpClient` already pins the checked addresses (`WorkflowHttpClient.java:56-66`).
2. **Half-connected webOS session.** `WebOsSession.connect()` sets `connection` before publishing CONNECTED
   (`adapters/webos/WebOsSession.java:276, 288`). `update()` calls the listener synchronously, and the listener
   publishes Spring events synchronously. If any listener throws, the exception escapes both catch blocks: the
   connection stays set, the state subscription, inputs and MAC learning (`:289-291`) never run, and no reconnect is
   scheduled.
3. **Tests never bind the production configuration.** `src/test/resources/application.yaml` has the same name as
   the main file and hides it on the test classpath. `BluetoothModuleSwitchTest` works around this by parsing the
   main file by path.
4. **Security headers depend on a password.** `LoginGateFilter` returns before setting `X-Frame-Options`,
   `X-Content-Type-Options` and `Referrer-Policy` when no login is required (`security/LoginGateFilter.java:43-49`).
   There is no Content-Security-Policy anywhere.
5. **Possible rail stall (unconfirmed).** The java.net.http clients read bodies through `ofInputStream`, and
   `HttpRequest.timeout` stops applying once headers arrive. A server that sends its body slowly could hold one of
   the four `RailCache` fetch permits (`content/RailCache.java:243`) indefinitely.

### Structural themes

| # | Theme | Evidence | Goals served |
| --- | --- | --- | --- |
| 1 | No enforced boundaries | Package cycles device↔adapters (`device/JsonFileDeviceRegistry.java:3-4` imports Android TV and Cast settings; eight adapter files import `DeviceManager`), web↔adapters (`web/SetupController.java:8-9` imports the Android TV `PairingService`), web↔sources/adapters/security (seven `*SetupAdvice` classes and `LoginModelAdvice` import `web.SetupController`). Jellyfin checks `hasAdapter("androidtv")` in five classes. No ArchUnit. The concept's package map is stale. No architecture document, `AGENTS.md` or `CLAUDE.md`; the working rules live only in the gitignored `.superpowers/sdd`. | navigate, extend |
| 2 | Application config lives in the Android TV adapter | `AndroidTvProperties` (`shield.*`) supplies the data directory, keystore and mDNS switch to `HomeControlConfiguration`, `SecurityConfiguration` and the sports, pinned and YouTube configurations. Android TV is the only adapter without `@ConditionalOnProperty` (`AndroidTvAdapter.java:25`). | extend, navigate |
| 3 | `DeviceManager` does too much | 813 lines, 27 public methods, nine responsibilities (registry reads, command fall-through, connection and state composition, enrolment, auto-merge, manual merge and split, adapter-settings rules, optional handle features, matching heuristics). One lock is held across DNS lookups (`attach` → `Hosts::same`), registry file I/O, every `adapter.connect` and event publication. `JsonFileDeviceRegistry.findAll` re-reads the file on every call. | bugs, navigate |
| 4 | Device and playback contracts leak | Optional features use six mechanisms: the `Capability` enum, `acceptedBy` overrides, the method-less `WakeOnLanAdapter` marker, `GroupListing`/`InputListing` mixins found with `isInstance`, a throwing default `DeviceHandle.query`, and the separate `PromptPairing` bean. Capabilities are coarse (`SelectInput` requires `REMOTE_KEYS`), so sessions throw "unsupported" to drive fall-through. `PlayableRef` (10 variants) → `Route` (11) → `Action` (13) repeat each other; `CastMessage` is defined three times; six source-specific variants live in core; adding `WorkflowCast` touched seven files outside its module. | extend |
| 5 | Adapter lifecycle copied seven times and drifted | Four backoff implementations; state publishing differs in deduplication, listener-exception handling and the closed guard; two idioms for closing a connection once; three thread kinds (platform non-daemon, platform daemon, virtual). webOS/Tizen and Sonos/UPnP are near-twins. webOS and Tizen have no `protocol/` package and pass Spring properties into wire classes. `InsecureTls` and `CastTls` hold identical trust managers. Three timeout exception clones. | bugs, extend |
| 6 | Six hand-written HTTP clients | `TmdbClient` and `TheSportsDbClient` are near line-for-line copies. Security details already disagree (cause handling, redirects, DNS pinning). Six exception classes each declare an overlapping `Kind` enum. Three address policies and six copies of URL parsing. Fourteen separate `JsonMapper` builders. | bugs, extend |
| 7 | Seven JSON stores | The atomic write is reimplemented five times without fsync, while a correct `OwnerOnlyFiles` (fsync, directory sync, mode 0600) exists but is package-private. `JsonFileSourceSettings` re-parses `sources.json` on every call, about ten times per rail refresh. webOS and Tizen credentials sit in plaintext `devices.json`; the Android TV keystore password defaults to `shield`. | bugs |
| 8 | The web edge | `HttpServletRequest` is passed into services and stores (`WorkflowStore`, the Jellyfin, TMDB and YouTube setup services, `SportsCalendars`, `SportsCompetitions`). The login check is written four times. Error bodies come in four shapes, with duplicated `@ExceptionHandler` sets. `ContentController` serves a JSON API no page calls. `setup.html` lists every source by hand and its nav already lacks `#workflows`. The page head and header are copied into four templates. Removing the last secret silently removes the login. | navigate, extend |
| 9 | Test and CI speed | 25 of 27 `@SpringBootTest` classes declare their own temp data directory; `LoginGatingTest` dirties the context after every method. HTTP fakes are copied seven times and `MutableClock` four times. Nine assertions put upper bounds on wall-clock time. Gradle compiles about seven times per push; `Dockerfile.dist` is not built on pull requests. | CI |
| 10 | No base for UI themes | Adding a theme touches nine places: the stylesheet, the `THEME_COLORS` map in `js/theme.js`, the two-state toggle hard-wired to `cyberpunk` in `fragments/theme-toggle.html`, the heads of five pages (dashboard, setup, workflow editor, login, `offline.html`), each CSS and font path in the `LoginGateFilter.OPEN_PATHS` security allowlist, the `sw.js` asset list and cache version, and two tests fixed to Cyberpunk. The default browser colour `#101917` is written in 11 places. `app.css` has about ten colour tokens but 76 colour literals outside them, so `themes/cyberpunk.css` restyles by overriding component selectors (394 lines mirroring the header, dashboard, tiles, fields, remote, play sheet, toast, setup and login rules). A class or markup change can break a theme silently, and nothing checks what a theme looks like. | extend, navigate |

## Principles

- **Behaviour stays the same by default.** A visible change is made only where this document names it.
- **Composition over inheritance.** Shared adapter and source behaviour is extracted into small collaborators, not
  base classes.
- **Every step leaves `./gradlew build` green** and is small enough to review in one pull request.
- **Enforcement before refactoring.** Rules are added with today's violations frozen; refactors remove violations
  from the frozen store rather than adding exceptions.
- **Data migrations are automatic and tested.** Every `/data` format change bumps a schema version, migrates on read,
  and ships with a test that loads the previous format.

## Phase 0: Defect fixes

Five independent S-sized pull requests, each starting from a failing test.

| Item | Change |
| --- | --- |
| 0.1 | `CalendarFetcher` connects to the addresses it checked. The DNS-pinning code is extracted from `WorkflowHttpClient` into a small reusable helper that 2C builds on. |
| 0.2 | `WebOsSession` catches and logs listener exceptions at publish time and can no longer be left half-connected. The same guard is applied wherever a session publishes before finishing its connect sequence. |
| 0.3 | Security headers are sent on every response, set in `CrossOriginFilter`, together with `frame-ancestors 'none'`. The full CSP waits for 2E, which removes the inline scripts. |
| 0.4 | A test reproduces the slow-body stall against a fake server. If it does, a minimal fix applies one deadline to the whole exchange, including the body. If it does not, the item is closed with the test kept. |
| 0.5 | The test configuration becomes `application-test.yaml` holding only overrides, activated for all tests; a test binds the main `application.yaml`; the path-parsing workaround in `BluetoothModuleSwitchTest` goes. |

## Phase 1: Guardrails

### 1.1 Architecture rules

One ArchUnit test class runs in `./gradlew build`. Today's violations go into a committed `FreezingArchRule` store:
new violations fail the build, and fixed ones drop out of the store. The rules:

1. `core` depends only on the JDK.
2. `*.protocol` packages depend on neither Spring nor any application package other than `adapters.net` and other
   `protocol` packages.
3. The top-level packages have no cycles.
4. `sources` do not import `adapters`.
5. `adapters` import neither `sources` nor `web`.
6. `web` does not import adapter internals.
7. No source imports another source, and no adapter imports another adapter, except the shared `adapters.net` and
   `adapters.support` packages and `adapters.sonos` using `adapters.upnp.protocol` (Sonos speaks UPnP).
8. `jakarta.servlet` appears only in `web`, `security`, and the controllers and controller advice of module packages.
9. Outbound network libraries (`java.net.http`, httpclient5, jmdns, dbus) appear only in `adapters`, `sources` and
   `discovery`.

The `hasAdapter("androidtv")` string checks are invisible to ArchUnit; 3B removes them.

### 1.2 Documentation

- `docs/dev/architecture.md`: the real package map, the rules above, a dependency diagram, and the progress measures
  from [Progress measures](#progress-measures).
- A tracked `AGENTS.md` holding the working rules now kept in `.superpowers/sdd` (build through `scripts/gradle.sh`,
  Jackson 3 package names, the architecture rules), and a `CLAUDE.md` that points to it.
- The README cut to about 150 lines (what it is, running it, where to read more). User guides move to
  `docs/user/{devices,sources,configuration,security}.md` and contributor material to
  `docs/dev/{architecture,testing,ci-and-releases}.md`.
- A `docs/adr/` index; the Cast sender ADR moves there.
- Merged implementation plans move to `docs/superpowers/archive/`.

### 1.3 Test infrastructure

- A shared `testsupport` package: `FakeHttpServer` (routes, canned responses, recording, delay, redirect),
  `MutableClock`, `EventStreamReader`, `TestTls.serverContext(cn)` and `RecordingStateListener`. The seven HTTP fakes
  and four clocks move onto it.
- Spring tests share one data directory per JVM and reset state explicitly instead of declaring per-class
  `@DynamicPropertySource` and per-method `@DirtiesContext`. Target: fewer than 10 context starts per run.
- Module-switch tests become `ApplicationContextRunner` checks plus one context with every module off.
- A global JUnit timeout (`junit.jupiter.execution.timeout.default`) and `timeout-minutes` on every CI job.
- The nine wall-clock upper-bound assertions are replaced with an injected `Clock`, injected timeouts or ordering
  checks.

**Phase 1 is done when** the ArchUnit test runs in `./gradlew build` with a recorded frozen-violation count,
`docs/dev/architecture.md` and `AGENTS.md` are merged, and the measured context starts are below the target.

## Phase 2: Shared building blocks

2A comes first; 2B, 2C, 2D and 2E are independent of each other after it. 2F follows 2E, whose layout fragments it
builds on.

### 2A: Configuration root and module switches (S–M)

- A `HomeControlProperties` record binds `home-control.data-dir` and `home-control.discovery.*`. An
  `EnvironmentPostProcessor` maps the old `shield.*` keys and environment variables to the new names and logs a
  deprecation warning. `compose*.yaml` and the CasaOS manifest move to the new names.
- `DataDirectory.resolve(name)` becomes the only place that knows file names under `/data`.
- An `AndroidTvConfiguration` makes Android TV a switchable module like every other adapter. `AndroidTvProperties`
  keeps only Android TV settings.
- One meta-annotation, `@ConditionalOnModule("x")`, replaces the 32 repeated `@ConditionalOnProperty` strings.
- `@Value` reads move into the properties records (`deep-link-test.timeout`, the Jellyfin startup timeout). Durations
  use `Duration` everywhere; the old `*-seconds` keys stay accepted through the same post-processor. Redundant
  `@EnableConfigurationProperties` declarations go.
- `rootProject.name` becomes `home-control`.

### 2B: Storage (M, includes data migrations)

- `storage.AtomicFiles`, promoted from `OwnerOnlyFiles`: temp file, fsync, rename, directory sync, mode 0600.
- `VersionedJsonFile<T>`: record binding, a schema version with a migration hook, a cached snapshot and a single
  writer. The plain JSON stores (`JsonFileSourceSettings`, `JsonFileDeviceRegistry`, `JsonFilePinStore`,
  `JsonFileSportsStore`, `QuotaLedger`) move onto it. `SecretStore` keeps its encrypted format, and `SecretKeySource`
  and `CertificateStore` keep their binary files; all three write through `AtomicFiles`.
- `sources.json` gets typed per-source sections, so Jellyfin stops flattening nested data into `link.<device>` and
  `player.<device>` keys. The device registry is cached in memory and written through.
- Adapter-specific settings validation moves from `JsonFileDeviceRegistry` into the adapters, removing the
  device→adapters imports.
- webOS client keys and Tizen tokens move to `secrets.json`. The Android TV keystore password is generated on first
  start instead of defaulting to `shield`; existing keystores are re-protected during migration.
- **Visible change (approved):** the login no longer disappears when the last secret is removed. The Account section
  gets explicit "set password" and "remove password" actions.

### 2C: Outbound HTTP for sources (M)

- One `GuardedHttpClient`, built on the 0.1 pinning helper and Apache HttpClient 5, the only HTTP stack in the
  project that supports pinned DNS resolution. It provides: redirects off by default or allowed only after a policy
  check; a body cap that checks Content-Length first; one deadline for the whole exchange; error messages that name
  only the host and never attach a cause that may carry the URI; and a status-to-kind mapping.
- `OutboundAddressPolicy` and `HttpUrls.parse` replace the three address policies and six URL parsers.
- `ContentSourceException.Kind` replaces the six overlapping enums. One shared `JsonMapper` bean replaces the
  separate builders.
- Jellyfin, TMDB, YouTube, TheSportsDB, calendars and workflows move onto it. Device adapters keep their own
  transports, because LAN device protocols need a different trust model.

### 2D: Adapter lifecycle support (M)

- A new `adapters.support` package with four collaborators used by composition:
  - `StatePublisher`: one deduplication policy, a closed guard, and listener exceptions caught and logged.
  - `Backoff`: `next()` and `reset()`.
  - `SessionLoop`: a single-thread scheduler on virtual threads that tracks its pending task and closes cleanly.
  - `ConnectionSlot<C extends AutoCloseable>`: set, take-if and take-all; each connection is closed exactly once.
- `ReconnectingPoller` moves into `adapters.support` without its dependency on UPnP's `SoapFault`.
- All seven sessions adopt them. Push-style sessions keep their own `connect()` returning connected, retry or stop,
  so policies such as Android TV's unpaired latch and webOS's key-rejected stop stay in the session.
- The near-twin code is extracted: `WakeOnLanPower`, `LearnedMac`, `SessionRegistry<S>` for the adapters, and a
  `RendererStatePoller` shared by Sonos and UPnP.
- `DeviceCalls.run(name, what, call)` and one `DeviceTimeoutException` replace the three timeout clones and the
  per-session translation ladders. `CastTls` delegates to `InsecureTls`; `TlsSockets` keeps its separate
  client-certificate trust model.
- The small races go: the non-atomic play/pause toggle in webOS and Tizen, Bluetooth's `RejectedExecutionException`
  when racing `close()`, and UPnP setting its endpoints again after `close()`.
- **Visible change (approved):** `spring.threads.virtual.enabled=true`, so request threads are virtual too.

### 2E: The web edge (M)

- One `@RestControllerAdvice` maps core exceptions to a status and a plain-text body. An unknown device id is always
  404, including in merge, split and the deep-link test.
- A `LoginContext` argument resolver gives controllers the login state and a way to start a session; services and
  stores take a value instead of `HttpServletRequest`. The login check lives in one place.
- `SetupSection` beans (id, title, fragment, order) that `setup.html` iterates over, replacing the hand-written list
  and the seven `*SetupAdvice` → `web.SetupController` imports.
- Layout fragments for the page head, the app header and the first-password fields, used by every page including
  `login.html` and the workflow editor. With the inline module script, the `onsubmit` attribute and the `hx-on`
  attributes gone, a full CSP with `script-src 'self'` is sent. The synchronous `js/theme.js` in the head is an
  external script and stays compatible with it.
- **Visible change (approved):** the unused `ContentController` JSON API (`/sources`, `/sources/{s}/rails/{r}`, JSON
  `/search`) is deleted. The documented `GET /devices/<id>/route` stays.
- The event stream sends a heartbeat comment every 25 seconds and completes its emitters on shutdown.
  `DeviceStateBroadcaster` and `StateController` are renamed for what they carry now.

### 2F: Theme architecture (M, after 2E)

What the merged Cyberpunk theme already does well stays: the saved theme is applied from `localStorage` before the
first paint, other tabs follow a switch, private mode falls back to the default, and forced-colours and
reduced-motion are respected. The choice stays per browser.

- **A token contract.** Every colour, radius, font and shadow in `app.css` becomes a semantic token, including
  `--theme-color` for the browser UI; the 76 colour literals outside the token block go. `app.css` and every theme
  use cascade layers (`@layer base, components, theme`), so a theme wins by layer rather than by out-specifying
  component selectors. A guard test fails when `app.css` gains a colour literal outside its token block.
- **A theme is tokens first.** A theme file redefines tokens under `:root[data-theme="<id>"]`. Decorations that
  tokens cannot express, such as Cyberpunk's chamfers, scan lines and glitch text, go in a marked section that
  targets documented hook classes. A colour-only theme is then a token block of about 30 lines, and `cyberpunk.css`
  shrinks to its tokens plus its decorations.
- **One theme catalog on the server.** A `ThemeCatalog` lists each theme's id, label, stylesheet, font files and
  browser colour; the default theme's colour is defined there once instead of in 11 places (only the static
  `icons/icon.svg` keeps its own copy). Everything else is generated from it:
  - the stylesheet links and the default `theme-color` meta in the 2E head fragment;
  - a JSON data block that `js/theme.js` reads instead of its own `THEME_COLORS` map (a non-executable data block, so
    the CSP allows it);
  - the header toggle, which stays two-state while there are two themes;
  - the login gate's open paths, still an exact-match set, extended with the catalog's asset paths at startup;
  - the PWA manifest colour and `IconRenderer`'s background;
  - the service worker's offline asset list, with its cache version derived from the assets so nobody bumps it by
    hand. `offline.html` stops being a hand-written copy of the page head.
- **Tests over the catalog.** One parameterized test checks every theme: its assets are served without a login, it
  declares every required token, and it has a browser colour. `ThemeE2eTest` runs once per catalog theme. The e2e
  job saves a screenshot per theme and page as a CI artifact for review; it does not fail the build.

**2F is done when** adding a theme means one stylesheet, its fonts and one catalog entry, and the existing themes look
the same as before.

**Phase 2 is done when** each workstream's violations have left the frozen store and every data-format change has a
migration test.

## Phase 3: Structural splits

### 3A: Decompose `DeviceManager` (L, after 2B)

- A façade first: the public API stays and delegates to five collaborators.
  - `DeviceConnections` owns the handle and state maps and `Generation`, and is the only holder of their lock.
  - `CommandRouter` owns execute, query and the fall-through.
  - `DeviceEnrollment` owns adopt, attach, add, forget, auto-merge, manual merge and split. It relies on a pure
    `DeviceMatching` class that absorbs `DeviceMerge`, `bestMatch`, `absorbable` and `uniqueId` and replaces the
    three slightly different matching rules with one.
  - `AdapterSettingsStore` owns learned settings and the Wake-on-LAN MAC rules.
  - `DeviceQueries` gives read-only access.
- Callers then move to the narrow interfaces. Pairing modules depend on a core `DeviceEnrollment` interface instead of
  the concrete `DeviceManager`, breaking the adapters→device cycle.
- No blocking work under the lock: DNS resolution happens before it, `adapter.connect` runs outside it with a
  generation check, and events are published after it is released.
- Discovery starts on `ApplicationReadyEvent`, so `DiscoveryCatchUp` is deleted. The registry-merge rules
  (`discovered`, `settingsFor`, `hostOf`, `carries`, `credentialsBoundToDeviceId`) move from `DeviceAdapter` into a
  separate `AdapterDiscovery` interface.

### 3B: The device feature contract (M–L)

- Finer capabilities (`INPUTS`, `GROUPING`, `WAKE_ON_LAN`, and whatever Jellyfin actually needs instead of
  `hasAdapter("androidtv")`, to be named in the 3B spec), so `Action.requires()` is exact and `CommandRouter` uses it.
- One typed lookup, `<T> Optional<T> feature(Class<T>)`, replaces the `WakeOnLanAdapter` marker, the `isInstance`
  checks and the throwing default `query`.
- One pairing interface covers Android TV, webOS and Tizen; `web` stops importing `androidtv`.
- Sessions keep their exhaustive `switch` over `Action`: it is the compiler's reminder when an action is added.
  Bluetooth's `default ->` arm goes.

### 3C: Playback routes (M)

- `Route` splits into `DeviceRoute(Action)` and `DelegatedRoute`, which a `RouteExecutor` handles by key. `key()`
  and `optimistic()` become methods on `Route`. The planner returns `Plan(routes, reason)` so `explain()` stops
  planning twice. The preference order lives in one place, and a generic route strategy replaces the seven
  near-identical ones.
- Source-specific `PlayableRef` and `Route` variants move into their source modules through an open extension point,
  so a new source does not edit core. `CastMessage` is defined once.
- An ADR records where device-controlling code in sources belongs. Jellyfin sessions and the YouTube Lounge are
  content-service APIs and stay in sources; workflow Cast playback goes through the Cast adapter as an `Action`.

### 3D: Sports (S–M, after 2C)

- A `SportsFeed` interface and a shared `FeedResult` replace the duplicated calendar and TheSportsDB result types and
  backoff constants, and remove the package cycles between them.
- `ics/` becomes a Spring-free library package.
- `CalendarSchedule.events()` stops fetching calendars while holding its lock.

### 3E: Adapter layering (M, after 2D)

- webOS and Tizen get `protocol/` packages whose classes take `Duration`s instead of Spring properties records.
- Cast's launch and namespace choreography moves out of `CastSession` into `cast/protocol/ReceiverApps`; UPnP's
  description and SCPD resolution moves into `upnp/protocol/RendererResolver`.
- Protocol errors are translated into core exceptions only in the sessions.

## Phase 4: Gradle modules (M)

Starts only when the frozen ArchUnit store is empty. The first split has three modules: `core` (JDK only),
`protocols` (the Spring-free wire libraries and `ics`) and `app` (everything with Spring). Whether to split `app`
further is decided from measured build times at that point. ArchUnit stays to enforce the rules inside `app`.

## Parallel track: CI, Docker and tooling

Independent S items that can land at any time.

- **Docker:** one Dockerfile. A `FROM scratch AS jar` stage lets CI pass the prebuilt jar as a named build context,
  so image builds skip Gradle; local builds use `./gradlew` with a cache mount. The container runs as a non-root user,
  has a `HEALTHCHECK`, and uses base images pinned by digest (Dependabot keeps them current).
- **CI:** Gradle build cache and configuration cache in `gradle.properties`; path filters so documentation-only pushes
  skip the build; `timeout-minutes` on every job; the unused jar artifact removed or actually consumed; a commit-lint
  check, because releases are computed from commit messages. The stale "CI quality gate" README section is fixed.
- **Tooling:** `.editorconfig`, Error Prone, Spotless with `ratchetFrom("origin/main")`, the jacoco version moved into
  the version catalog, `scripts/` made the only copy of the helper scripts, and the absolute paths removed from
  `.codex/config.toml`.
- **Frontend hygiene:** the remote drawer moved out of `app.js`; the vendored htmx version and source recorded. (The
  `app.css` token and layout work belongs to 2F.)

## How the program runs

```text
Phase 0 ──▶ Phase 1 ──▶ 2A ──┬──▶ 2B ──▶ 3A ──▶ 3B ──▶ 3C ──┐
                             ├──▶ 2C ──▶ 3D ─────────────────┤
                             ├──▶ 2D ──▶ 3E ─────────────────┼──▶ Phase 4
                             └──▶ 2E ──▶ 2F ─────────────────┘
Parallel track (CI, Docker, tooling): any time
```

- Every pull request starts from `main` and keeps `./gradlew build` green.
- M and L workstreams get their own spec in `docs/superpowers/specs/` and plan in `docs/superpowers/plans/`. S items
  get a short design in chat before implementation.
- Commits follow the existing conventional-commit style. Visible changes are called out in the commit body so the
  release notes carry them.

### Progress measures

Recorded in `docs/dev/architecture.md` at the end of each workstream.

| Measure | Baseline | Target |
| --- | --- | --- |
| Frozen ArchUnit violations | recorded in Phase 1 | 0 before Phase 4 |
| Largest class | 813 lines (`DeviceManager`) | under 300 lines for any class in `device/` |
| Spring context starts per test run | about 60 (estimate) | under 10 |
| CI time for a push to `main` | about 16 minutes | recorded after the parallel track; no target yet |
| Copies of the atomic write, backoff, HTTP error mapping | 5, 4, 6 | 1 each |
| Places to edit when adding a UI theme | 9 | a stylesheet, its fonts and one catalog entry |
| Colour literals in `app.css` outside its token block | 76 | 0 |

## Out of scope

- New features, new devices or new sources.
- A UI redesign; frontend work is limited to the structural items named above.
- Moving to Spring Security. The hand-rolled filter chain was reviewed and is coherent; switching would add
  token-based CSRF plumbing to every htmx call for little gain.
- Rewriting working protocol code beyond moving it into `protocol/` packages.

## Decisions deferred to workstream specs

- The capability that replaces `hasAdapter("androidtv")` in Jellyfin (3B).
- The exact extension point for source-specific playable refs and routes (3C).
- Whether `app` is split further than three modules (Phase 4).
- How people choose among more than two themes (a header menu, a Setup section, or both), decided when a third theme
  is planned. 2F keeps the two-state toggle and makes that later change local to the toggle fragment.
