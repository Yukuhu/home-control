# Streaming Launchers (Sub-project G) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Netflix, Prime Video and DAZN usable from the dashboard as honest *launchers*: a TMDB content source (search, trending, watch providers by region, public artwork), user-pinned shortcuts that turn a pasted service URL into a playable item, canonical per-service link builders that every device platform translates (Android TV app links, webOS `contentId`, Tizen app launch only), and a "Trending on your services" rail whose items open the service's app or — once the user pastes a title link — the title itself.

**Architecture:** Two new source modules on sub-project C's `ContentSource` contract: `sources/tmdb` (typed `java.net.http` client with bearer or API-key auth, a secret-store credential, an image-base cache, a watch-provider cache, a provider matcher) and `sources/pinned` (`/data/pinned.json` written atomically, a "Pinned" rail, setup-page management, and an "upgrade" endpoint used by the play sheet). A pure core class `core/playback/ServiceLinks` owns service URL grammar: display names, canonical title links (`https://www.netflix.com/title/{id}`, `https://app.primevideo.com/detail?gti=…`), and "app home" links that open a service without a title. Items carry **one platform-neutral `PlayableRef.AppLink`**; the planner stays brand-free (`APP_LINK` capability) and each adapter translates the canonical link for its platform exactly as sub-project F already does (webOS `contentId` for Netflix titles, Tizen app launch only). A core `PinnedLinks` SPI lets a source replace its app-home link with the title link the user pinned; a core `PinOffers` rule tells the play sheet when to offer "paste a link to open this title directly". A core `ContentChangedEvent` lets a source ask D's `RailCache` to refresh its rails immediately.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle 9.7.1, Thymeleaf, htmx 2, vanilla ES modules, JUnit 5, AssertJ, Mockito, Awaitility, Playwright for Java (test-only, from D7). **No new dependencies:** HTTP uses `java.net.http.HttpClient` (as C's Jellyfin client), JSON uses Jackson 3 already on the classpath, the fake TMDB server uses the JDK's `com.sun.net.httpserver.HttpServer` (as C's `FakeJellyfinServer`).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §2 (honesty about walled gardens, route by capability, secrets raise the bar), §4.2 (Netflix and Prime Video rows: TMDB metadata, ids only from pasted URLs; per-platform launch), §5.2 (`AppLink`; `WebOsLaunch`/`TizenLaunch` — see Decisions), §5.3 (optimistic app links, route shown before play), §6.1 (*Pinned* and *Trending on your services* rails, "Opens in the Netflix app", search across TMDB, locale/providers setup), §7 (`sources/tmdb`, `sources/pinned`), §8 (`pinned.json`, `sources.json`, `secrets.json`), §9 (login once a secret exists), §11 (TMDB has no Netflix/Prime ids → app launch + pin to upgrade; deep links differ by firmware), §12 (fixtures, planner tests, manual checklist). Roadmap: `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md` section G. Issues: epic #11, tasks #60–#64. Contracts this plan builds on: plans A `2026-09-16-multi-device-core.md`, B `2026-09-16-google-cast-adapter.md`, C `2026-09-16-jellyfin-source.md`, D `2026-09-16-dashboard-shell.md`, F `2026-09-16-smart-tv-adapters.md` (execution order A, B, C, D, F, E, then G). Sub-project E (YouTube) is not relied on.

**External references (read 2026-09-16):**
- TMDB API v3 reference (`developer.themoviedb.org/reference`): every v3 endpoint accepts either `Authorization: Bearer <API Read Access Token>` or the `api_key=<v3 key>` query parameter; `GET /3/authentication` validates the credential (`{"success":true,"status_code":1,"status_message":"Success."}`, invalid → HTTP 401 `{"status_code":7,…,"success":false}`); `GET /3/configuration` → `images.secure_base_url` (`https://image.tmdb.org/t/p/`) and `images.poster_sizes`; `GET /3/search/multi?query=&language=&include_adult=false&page=` and `GET /3/trending/all/week?language=&page=` → `{"page","results":[…],"total_pages","total_results"}` with `media_type` `movie|tv|person`, movies carrying `title`/`original_title`/`release_date`, series `name`/`original_name`/`first_air_date`, both `id`, `poster_path`, `adult`; `GET /3/movie/{id}/watch/providers` and `GET /3/tv/{id}/watch/providers` → `{"id","results":{"<ISO 3166-1>":{"link","flatrate":[…],"free":[…],"ads":[…],"rent":[…],"buy":[…]}}}`, each provider `{"logo_path","provider_id","provider_name","display_priority"}`; details `GET /3/movie/{id}` and `GET /3/tv/{id}` accept `append_to_response=watch/providers`, which adds a `"watch/providers"` object of the same shape. Watch-provider data comes from JustWatch and must be attributed; apps must show "This product uses the TMDB API but is not endorsed or certified by TMDB."
- Provider ids: Netflix `8`, Amazon Prime Video `9` and `119` (both appear depending on region) are confirmed by TMDB forum answers and the watch-provider reference example; `1796` ("Netflix basic with Ads") and `2100` ("Amazon Prime Video with Ads") come from the TMDB provider list and are not independently confirmed — the matcher therefore also matches by provider name (see Decisions) and the ids are a property.

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a play request that cannot be routed now fails now with a reason. Nothing is queued.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `content`, `playback`, `device` and `web` must not import `sources.*` or `adapters.*`; only `sources.tmdb` speaks HTTP to TMDB; `sources.*` must not import `adapters.*`.
- Route by capability, not by brand. G adds no capability and no route variant; the planner's strategy list is unchanged.
- Honesty about walled gardens: Netflix, Prime Video and DAZN are launchers with third-party metadata; the UI never implies a personalised feed from them. An app-home launch is always described as opening the app, not the title.
- Secrets raise the bar: a single-password login is mandatory once any secret is stored. The TMDB credential is encrypted at rest (C's `SecretStore`) and never reaches the browser, a log line, an exception message or a `toString()`.
- Persistent state stays in `/data` as JSON files written atomically; `devices.json`, `keystore.p12`, `secrets.json`, `sources.json` keep working.
- Every adapter and source is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; every source has recorded JSON fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), each ending with the two trailer lines required by `.superpowers/sdd/implementer-common.md`.

Build and tooling (this repository):

- There is no local JDK. Build with `.superpowers/gradle.sh build`; focused tests with `.superpowers/gradle.sh test --tests '<pattern>'`; browser tests with `.superpowers/e2e.sh` (D7). Failing test detail: grep `<failure` in `build/test-results/test/*.xml`.
- Spring Boot 4.1.1: MockMvc test auto-configuration is `org.springframework.boot.webmvc.test.autoconfigure`; `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Jackson 3 (`tools.jackson.databind.*`): `JsonMapper.builder().build()`, `readTree`, `writeValueAsString`; on possibly missing nodes use `path(..)` and the defaulted accessors `asString("")`, `asInt(0)`, `asLong(0)`, `asBoolean(false)`; `isString()` for textual nodes (`isTextual()` if that is the available name). Exceptions are unchecked `tools.jackson.core.JacksonException`.

Epic constraints:

- Plans A–D and F are the contract. Where real code differs from their listings (a field name, a helper, a constructor parameter), adapt the edit to the real code, keep the behaviour this plan specifies, and say so in the task report. Names this plan relies on: `ContentSource` (with D's `defaultRefreshInterval()`), `ContentSources`, `Rail`, `RailDescriptor`, `ContentSourceException`, `ContentItem` (8 components with `progress`, 7-argument constructor kept), `ContentKind`, `PlayableRef.AppLink(URI uri, String service)`, `AppLinks.fromUrl/serviceOf`, `Route.OpenAppLink(URI uri, String service)`, `RouteKeys`, `PlaybackService.preview/attempt`, `ContentPlayController`, `RoutePreviewView`, `SecretStore.secret`, `LoginService.storeSecrets/removeSecrets/loginRequired`, `PasswordRejectedException`, `LoginRequiredException`, `JsonFileSourceSettings.get/put/remove`, `SourcePreferences` (`locale`, `region`, `providers`), `SourcePreferencesService.current()`, `SourcePreferencesChangedEvent`, `StreamingProviders.KNOWN`, `RailCache.peek/reconcile/refresh`, `SetupController`, `setup.html`, `dashboard.html` (`#play-sheet`), `static/js/play-sheet.js`, `toast.js`, F's `adapters/links/ContentLinks`, `adapters/webos/WebOsLaunches`, `adapters/tizen/TizenLaunches`, A's `FakeRemoteServer.nextAppLink()`, D7's `FakeContentSource`, `E2eFakesConfiguration`, `E2eApplicationTest`.
- The TMDB HTTP client never follows redirects (a redirect would replay the `Authorization` header), has connect and request timeouts, caps response bodies at 2 MiB, and never puts a URL with its query string into a log line or exception message (the API-key form carries the key in the query).
- Browser-bound content never carries a `PlayableRef` (C): rails, search and item JSON use `ContentItemView`; the browser plays by `source` + `item`; the server re-reads the item with `ContentSource.item`.
- Pinned URLs are stored and handed to devices; the server never fetches them.
- Beans for new services are declared in `@Configuration` classes (not `@Component`); controllers and `@ControllerAdvice` classes of a module carry the module's `@ConditionalOnProperty`; advice takes dependencies through `ObjectProvider`.
- Every setup endpoint lives under `/setup/sources/…` so C's `LoginGateFilter.ALWAYS_GUARDED` cross-origin check applies before a login exists.
- All wire formats in this plan (TMDB queries and headers, `pinned.json`, the `tmdb` entry in `sources.json`, canonical service links, the route-preview `pin` object, the pin-upgrade JSON) are normative; tests pin them.
- The manual acceptance checklist is never marked passed by an agent.

---

## Decisions

- Decision: accept both TMDB credential kinds in one field and detect the kind by shape — a 32-character lower-case hex string is a v3 API key (sent as `api_key` query parameter), a three-part base64url JWT is an API Read Access Token (sent as `Authorization: Bearer`); the setup page recommends the read access token — TMDB shows both on the same account page and users paste whichever they see; the bearer form keeps the credential out of URLs, the key form works for older accounts — cost if wrong: a future credential format needs one more pattern.
- Decision: the credential is stored only in `secrets.json` under `tmdb.credential` through `LoginService.storeSecrets`, so connecting TMDB is a "first secret" that sets the login password exactly like Jellyfin; `sources.json` holds only `{"credentialKind","connectedAt"}` — spec §9 and C's design; an API key is as sensitive as a token (quota abuse, account ban) — cost if wrong: none found.
- Decision: TMDB artwork is served as direct public URLs on `image.tmdb.org` (from `/configuration` `secure_base_url` + a poster size), not proxied — TMDB images need no credential, are HTTPS (no mixed content on either an HTTP or an HTTPS dashboard), are CDN-cached, and a phone that can use Netflix can reach them; a proxy would add bandwidth, a cache and an SSRF-shaped endpoint for no security gain. The only exposure is the viewer's IP and the dashboard's LAN URL as `Referer` to TMDB's CDN, documented in the README; `home-control.tmdb.image-base-url` overrides the base for users who run their own mirror — cost if wrong: add a C-style image proxy later (artwork URIs are built in one class).
- Decision: poster size `w342` when `/configuration` lists it, else the largest `w…` size ≤ 500, else `original`; `/configuration` is cached 24 h; when it fails the fallback `https://image.tmdb.org/t/p/w342` is used and retried after 10 minutes; a `secure_base_url` that is not `https://` is ignored — TMDB asks clients to read the configuration but the value has been stable for years — cost if wrong: broken thumbnails until the next retry.
- Decision: `home-control.tmdb.api-base-url` (default `https://api.themoviedb.org/3`) is a property so tests and self-hosted mirrors can point elsewhere; `src/test/resources/application.yaml` points it at the unroutable `http://127.0.0.1:9/3` so no test can reach the internet by accident — cost if wrong: none.
- Decision: `home-control.tmdb.enabled` and `home-control.pinned.enabled` default to `true` — TMDB does nothing until a credential is saved; pinned does no I/O except its own file; release 1.1's headline — cost if wrong: set the variable to false.
- Decision: the TMDB language is D4's `locale` (e.g. `de-DE`) and the watch region is D4's `region` (e.g. `DE`); providers are D4's `providers` keys in the user's order; TMDB reads them through a `Supplier<SourcePreferences>` wired in `TmdbConfiguration` — one place for locale/providers (D decided G consumes them) — cost if wrong: none.
- Decision: a provider key matches a TMDB watch provider when its `provider_id` is in `home-control.tmdb.provider-ids.<key>` (defaults: `netflix: [8, 1796]`, `primevideo: [9, 119, 2100]`) **or** its normalised `provider_name` (lower case, `+` → `plus`, non-alphanumerics removed) matches the key's name rule (`netflix*`, `amazonprimevideo*`, `dazn*`, `disneyplus*`, `appletvplus*`, `paramountplus*`, `wow` or `wowtv*`, `joyn*`, `rtlplus*`) and does not contain `channel` — only Netflix and Prime ids could be verified without an API key, provider ids differ by region (Prime is 9 or 119), and "Paramount+ Amazon Channel" is a Prime add-on — cost if wrong: a provider with an unusual name needs its id added to the property.
- Decision: "on your services" means the provider appears in the region's `flatrate`, `free` or `ads` list; `rent` and `buy` never count — the rail promises what the household can watch with its subscriptions — cost if wrong: none.
- Decision: the trending rail = `/trending/all/week` pages until `trending-candidates` (40) movie/series results are seen or `total_pages` is reached, each candidate's watch providers looked up (24 h in-memory LRU cache, 2 000 entries, sequential calls on the rail cache's virtual thread), filtered to configured providers, first `rail-size` (20) kept in TMDB's trending order; the source's default refresh interval is 6 h — ~42 calls on a cold refresh, 2 when warm, far below TMDB's ~50 requests/s limit; trending changes daily — cost if wrong: tune two properties.
- Decision: with no providers configured the trending rail fails with `Choose your streaming services in Setup to see what is trending on them` (D shows it as the rail's compact error with retry) instead of showing unfiltered trending — an unfiltered list would suggest the household can watch it — cost if wrong: one message.
- Decision: TMDB search results carry no playable references; `ContentSource.item(id)` (called at play time by C's controller) loads details with `append_to_response=watch/providers` and builds them — search stays one call per query, and C already re-reads items before playing — cost if wrong: none; tiles never show refs.
- Decision: no `PlayableRef.WebOsLaunch` / `TizenLaunch` and no brand-named capability. A TMDB or pinned item carries exactly one platform-neutral `AppLink` whose URI is the canonical service link; Android TV opens it as an app link, F's webOS handle turns a Netflix title link into the ConnectSDK `contentId` launch and a Prime link into an `amazon` app launch, and F's Tizen handle opens the Netflix or Prime Video app without the title. Spec §5.2's platform refs would need capabilities such as `WEBOS_LAUNCH` (routing by brand) and would make sources build device-protocol payloads (only adapters speak device protocols); F already made the same call and "G3 per-platform builders" become source-side canonical builders plus adapter-side translation tests — cost if wrong: a platform whose app needs a different id than the canonical URL carries would need a new `PlayableRef` variant and strategy.
- Decision: canonical links: Netflix `https://www.netflix.com/title/{digits}` (from `/title/{id}` or `/watch/{id}`, optional locale prefix, any query dropped); Prime Video `https://app.primevideo.com/detail?gti={amzn1.dv.gti.<uuid>}` whenever a GTI appears (query or path, lower-cased), else `https://www.primevideo.com/detail/{ID}` for a primevideo.com detail id, else `https://<amazon host>/gp/video/detail/{ASIN}` for an Amazon video detail link; anything else (YouTube, DAZN, web, non-matching Netflix/Prime URLs) is kept exactly as pasted — spec §4.2 names these two Android TV forms; `/watch/` is turned into `/title/` so a pinned series opens its title page instead of starting an arbitrary episode; unknown shapes are kept because the service app may still claim them — cost if wrong: one regex.
- Decision: "Open Netflix" without a title is an `AppLink` to the service's *app home* link — Netflix `https://www.netflix.com/browse`, Prime Video `https://app.primevideo.com/`, DAZN `https://www.dazn.com/` — and `Route.OpenAppLink.describe()` says `Open the Netflix app (not this title)` for app-home links; other providers (Disney+, WOW, Joyn, …) get no playable and the play sheet offers only pinning — spec §4.2 lists launch paths only for these three services, and which URL each Android TV app claims is unverified (checklist items); every platform already maps any netflix.com / primevideo.com URL to the app — cost if wrong: one constant per service.
- Decision: pinned shortcuts never fetch the pasted page (no `og:title` / `og:image`): the user types an optional title, and artwork exists only when a pin upgrades a TMDB item (copied from that item) — fetching user-supplied URLs server-side is an SSRF vector on a LAN appliance, Netflix and Amazon pages are geo- and bot-gated and render client-side, and the title the user types is what they want to see — cost if wrong: pins made in Setup show a placeholder tile.
- Decision: pinned management (add, rename, move up/down, remove) is a Setup section with plain form posts, like D4's rail order — works without JS and on keyboards, consistent with D — cost if wrong: a dashboard-side editor later.
- Decision: "pin a URL to upgrade" is offered in the play sheet for any item whose playables are empty or only app-home `AppLink`s (and whose source is not `pinned` or `manual`), through a `pin` object added to D3's route-preview JSON; posting a link creates a pin with `upgradeOf = "<sourceId>/<itemId>"` copying title, artwork and kind from the item, and replaces an earlier pin for the same item — sources look up `PinnedLinks.linkFor(sourceId, itemId)` and put the pinned link in place of the app-home link, so the rail, preview and play use it; the rule is generic so sub-project H's DAZN events can reuse it — cost if wrong: an item type that should not be upgradable needs a check in `PinOffers`.
- Decision: pins live in `/data/pinned.json` (`{"version":1,"pins":[…]}`, array order = rail order), written atomically with a temp file and `ATOMIC_MOVE`, at most 200 pins, ids `p-` + 12 random hex characters; the stored `service` is informational and recomputed on read; entries that are invalid after a hand edit are skipped with a WARN; an unparsable file or unknown version is a named `StorageException` at first use — spec §8; mirrors the registry's rules — cost if wrong: none found.
- Decision: the Pinned source is `available()` only while at least one pin exists, so a fresh install shows no empty "Pinned" rail; adding the first pin publishes `ContentChangedEvent("pinned")`, and D's `RailCache` gains a listener that reconciles and refreshes that source's rails at once — the rail appears without waiting for the 15 s ticker — cost if wrong: a D change of one listener.
- Decision: pinned items are searchable locally (case-insensitive title contains) so unified search finds them — no I/O, costs nothing — cost if wrong: none.
- Decision: TMDB's "external ids" endpoint is not used — it returns IMDb/Wikidata/social ids, never Netflix or Prime ids (spec §11) — cost if wrong: none.
- Decision: attribution — the TMDB setup section shows "This product uses the TMDB API but is not endorsed or certified by TMDB." and "Streaming availability data by JustWatch."; the README repeats both; the source's display name is `TMDB` so every rail and search section is labelled with where the metadata comes from — TMDB terms — cost if wrong: wording.
- Decision: G5's automated acceptance is a Spring end-to-end test over real sockets (fake TMDB server, A's `FakeRemoteServer` as a Shield, real login) that asserts the exact app-link strings the Shield receives, plus one Playwright test for the play sheet's pin prompt; the manual checklist `docs/superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md` has every item "Pending — requires real hardware" — agents cannot touch a Shield — cost if wrong: none.

---

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`, browser tests under `src/e2e/java/dev/andre/homecontrol/e2e/`. Paths are relative to those roots unless they start with `src/`, `docs/` or are top-level files.

### Files to create

- `sources/tmdb/TmdbProperties.java` — `home-control.tmdb.*`.
- `sources/tmdb/TmdbConfiguration.java` — module beans.
- `sources/tmdb/TmdbCredential.java` — bearer token or API key, redacted `toString`.
- `sources/tmdb/TmdbException.java` — `ContentSourceException` with a kind.
- `sources/tmdb/TmdbClient.java` — HTTP, auth, status mapping, body cap.
- `sources/tmdb/TmdbSettings.java` — the `tmdb` entry in `sources.json`.
- `sources/tmdb/TmdbSetupService.java`, `TmdbSetupController.java`, `TmdbSetupAdvice.java` — connect, test, disconnect.
- `sources/tmdb/TmdbImages.java` — image base from `/configuration`, poster URIs.
- `sources/tmdb/TmdbMediaRef.java` — `movie-603` / `tv-66732` item ids.
- `sources/tmdb/WatchProvider.java`, `sources/tmdb/TmdbWatchProviders.java` — provider parsing and cache.
- `sources/tmdb/TmdbItemMapper.java` — TMDB JSON → `ContentItem`.
- `sources/tmdb/TmdbContentSource.java` — search, item, trending rail.
- `sources/tmdb/ProviderMatcher.java` — D4 provider keys ↔ TMDB providers (Task 4).
- `sources/tmdb/TmdbPreferencesListener.java` — preferences change → refresh (Task 4).
- `src/main/resources/templates/fragments/tmdb-setup.html`.
- `core/content/ContentChangedEvent.java` — a source's content changed (Task 2).
- `core/playback/ServiceLinks.java` — service names (Task 2), canonical and app-home links (Task 3).
- `core/content/PinnedLinks.java`, `core/content/PinOffers.java` — upgrade SPI and offer rule (Task 4).
- `sources/pinned/PinnedProperties.java`, `PinnedConfiguration.java`, `Pin.java`, `JsonFilePinStore.java`, `PinnedShortcuts.java`, `PinnedContentSource.java`, `PinnedSetupController.java`, `PinnedSetupAdvice.java`.
- `sources/pinned/PinUpgradeController.java` — JSON endpoint for the play sheet (Task 4).
- `src/main/resources/templates/fragments/pinned-setup.html`.
- `web/PinOfferView.java` — the route-preview `pin` object (Task 4).
- `docs/superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md`.
- Tests: `sources/tmdb/FakeTmdbServer.java`, `TmdbCredentialTest.java`, `TmdbClientTest.java`, `TmdbSetupServiceTest.java`, `TmdbSetupControllerTest.java`, `TmdbImagesTest.java`, `TmdbMediaRefTest.java`, `TmdbWatchProvidersTest.java`, `TmdbItemMapperTest.java`, `TmdbContentSourceTest.java`, `TmdbModuleSwitchTest.java`, `ProviderMatcherTest.java`, `TmdbTrendingRailTest.java`, `TmdbFixtureContractTest.java`; `sources/pinned/JsonFilePinStoreTest.java`, `PinnedShortcutsTest.java`, `PinnedContentSourceTest.java`, `PinnedSetupControllerTest.java`, `PinnedModuleSwitchTest.java`, `PinUpgradeControllerTest.java`; `core/playback/ServiceLinksTest.java`; `core/content/PinOffersTest.java`; `web/StreamingLaunchersEndToEndTest.java`; e2e `PinUpgradeE2eTest.java`.
- Fixtures `src/test/resources/fixtures/tmdb/`: `authentication.json`, `authentication-invalid.json`, `configuration.json`, `search-multi.json`, `trending-all-week.json`, `providers-tv-66732.json`, `providers-tv-76479.json`, `providers-movie-603.json`, `providers-movie-550.json`, `providers-tv-94997.json`, `details-tv-66732.json`, `details-movie-603.json`, `not-found.json`; `src/test/resources/fixtures/pinned/pinned-v1.json`.

### Files to modify

- `core/playback/AppLinks.java` — public `parseHttpUrl` (Task 2); canonical links in `fromUrl` (Task 3).
- `core/playback/Route.java` — names from `ServiceLinks` (Task 2); app-home wording (Task 3).
- `adapters/links/ContentLinks.java` — `netflixTitleId` delegates to `ServiceLinks` (Task 3).
- `content/RailCache.java` — `onContentChanged` listener (Task 2).
- `web/ContentPlayController.java`, `web/RoutePreviewView.java` — `pin` offer (Task 4).
- `src/main/resources/templates/setup.html` — TMDB (Task 1) and Pinned (Task 2) sections.
- `src/main/resources/templates/dashboard.html`, `src/main/resources/static/js/play-sheet.js`, `src/main/resources/static/app.css` — pin prompt (Task 4).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.tmdb.*` (Task 1), `home-control.pinned.*` (Task 2).
- `README.md` — streaming launchers, attribution, privacy note (Task 5).
- `src/e2e/java/dev/andre/homecontrol/e2e/FakeContentSource.java`, `E2eFakesConfiguration.java` — a launcher item (Task 5).
- Tests: `core/playback/AppLinksTest.java`, `core/playback/RouteTest.java` (create if absent), `adapters/links/ContentLinksTest.java`, `adapters/webos/WebOsLaunchesTest.java`, `adapters/tizen/TizenLaunchesTest.java`, the Android TV app-link test from A5, `content/RailCacheTest.java`, `web/ContentPlayPreviewTest.java`, `web/StaticAssetsTest.java`, `web/DashboardPageTest.java`.

### Files to delete

- None.

---

### Task 1: G1 · TMDB client

**Files:**
- Create: `sources/tmdb/TmdbProperties.java`, `TmdbConfiguration.java`, `TmdbCredential.java`, `TmdbException.java`, `TmdbClient.java`, `TmdbSettings.java`, `TmdbSetupService.java`, `TmdbSetupController.java`, `TmdbSetupAdvice.java`, `TmdbImages.java`, `TmdbMediaRef.java`, `WatchProvider.java`, `TmdbWatchProviders.java`, `TmdbItemMapper.java`, `TmdbContentSource.java`; `src/main/resources/templates/fragments/tmdb-setup.html`
- Modify: `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `sources/tmdb/FakeTmdbServer.java`, `TmdbCredentialTest.java`, `TmdbClientTest.java`, `TmdbSetupServiceTest.java`, `TmdbSetupControllerTest.java`, `TmdbImagesTest.java`, `TmdbMediaRefTest.java`, `TmdbWatchProvidersTest.java`, `TmdbItemMapperTest.java`, `TmdbContentSourceTest.java`, `TmdbModuleSwitchTest.java`; fixtures `authentication.json`, `authentication-invalid.json`, `configuration.json`, `search-multi.json`, `trending-all-week.json`, `providers-tv-66732.json`, `providers-tv-76479.json`, `providers-movie-603.json`, `providers-movie-550.json`, `providers-tv-94997.json`, `details-tv-66732.json`, `details-movie-603.json`, `not-found.json`

**Interfaces:**
- Consumes: `ContentSource` (with `defaultRefreshInterval()`), `ContentSourceException`, `Rail`, `RailDescriptor` (C3/D1); `ContentItem`, `ContentKind`, `PlayableRef` (A/C); `SecretStore.secret(String)`, `LoginService.storeSecrets(Map,String,String,HttpServletRequest)`, `LoginService.removeSecrets(Collection)`, `LoginService.loginRequired()`, `PasswordRejectedException`, `LoginRequiredException` (C1); `JsonFileSourceSettings.get/put/remove` (C2); `SourcePreferences.locale()/region()/providers()`, `SourcePreferencesService.current()` (D4); `SetupController`, `setup.html` (A–D).
- Produces:
  - `record TmdbProperties(boolean enabled, URI apiBaseUrl, URI imageBaseUrl, int connectTimeoutSeconds, int requestTimeoutSeconds, int railSize, int trendingCandidates, Duration providerCacheTtl, Duration configurationCacheTtl, Map<String, List<Integer>> providerIds)` bound to `home-control.tmdb` (`imageBaseUrl` nullable; `providerIds` null/empty → defaults `netflix: [8, 1796]`, `primevideo: [9, 119, 2100]`).
  - `record TmdbCredential(Kind kind, String value)` with `enum Kind { BEARER, API_KEY }`, `static TmdbCredential parse(String raw)` (throws `IllegalArgumentException` with a user-facing message), redacted `toString()`.
  - `class TmdbException extends ContentSourceException { enum Kind { INVALID_INPUT, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER_ERROR, BAD_RESPONSE } Kind kind(); }` with constructors `(Kind, String)` and `(Kind, String, Throwable)`.
  - `class TmdbClient { TmdbClient(TmdbProperties); TmdbClient(TmdbProperties, HttpClient); JsonNode get(TmdbCredential, String path, Map<String,String> query); }` — `path` starts with `/` and is relative to `apiBaseUrl`.
  - `record TmdbSettings(TmdbCredential.Kind credentialKind, Instant connectedAt)` with `SOURCE_ID = "tmdb"`, `CREDENTIAL_SECRET = "tmdb.credential"`, `Map<String,String> toMap()`, `static Optional<TmdbSettings> from(Map<String,String>)`.
  - `class TmdbSetupService { TmdbSetupService(TmdbClient, JsonFileSourceSettings, SecretStore, LoginService, Clock); Optional<TmdbSettings> settings(); Optional<TmdbCredential> credential(); TmdbSettings connect(ConnectRequest, HttpServletRequest); String check(); void disconnect(); }` with `record ConnectRequest(String credential, String loginPassword, String loginPasswordConfirmation)` (redacted `toString`).
  - Endpoints: `POST /setup/sources/tmdb` (`credential`, `loginPassword`, `loginPasswordConfirmation`), `POST /setup/sources/tmdb/test`, `POST /setup/sources/tmdb/disconnect` → 302 `/setup#tmdb` with flash `tmdbMessage` / `tmdbError`.
  - Model attribute `tmdb` = `TmdbSetupAdvice.View(boolean configured, String credentialKind, boolean needsLoginPassword)`.
  - `class TmdbImages { TmdbImages(TmdbClient, TmdbProperties, Clock); URI poster(TmdbCredential, String posterPath); }` — null for a missing or malformed path.
  - `record TmdbMediaRef(Type type, long id)` with `enum Type { MOVIE, TV }`, `String itemId()` (`movie-603`), `String path()` (`/movie/603`), `static Optional<TmdbMediaRef> parse(String itemId)`, `static Optional<TmdbMediaRef> of(JsonNode result, String mediaTypeHint)`.
  - `record WatchProvider(int id, String name, Category category, int displayPriority)` with `enum Category { FLATRATE, FREE, ADS, RENT, BUY; boolean subscription(); String jsonKey(); }` and `static List<WatchProvider> parse(JsonNode providers, String region)` (`providers` = the object that holds `results`).
  - `class TmdbWatchProviders { TmdbWatchProviders(TmdbClient, TmdbProperties, Clock); List<WatchProvider> providers(TmdbCredential, TmdbMediaRef, String region); void remember(TmdbMediaRef, JsonNode providers); }`.
  - `final class TmdbItemMapper { static Optional<ContentItem> toItem(JsonNode result, String mediaTypeHint, Function<String, URI> posters, String subtitlePrefix, List<PlayableRef> playables); }`.
  - `class TmdbContentSource implements ContentSource` — id `tmdb`, display name `TMDB`, `available()` ⇔ settings and credential present, no rails in this task, `searchable()` true, `search(query, limit)`, `item(itemId)`, `defaultRefreshInterval()` 6 h. Constructor `(TmdbSetupService, TmdbClient, TmdbImages, TmdbWatchProviders, TmdbProperties, Supplier<SourcePreferences>)` (Task 4 appends parameters).
  - Property `home-control.tmdb.enabled` (default `true`).

**TMDB wire format (normative).**
- Base: `apiBaseUrl` with trailing slashes removed, then `path`, then the query. Query parameters are encoded with `URLEncoder.encode(value, UTF_8)` in insertion order; for `API_KEY` credentials `api_key=<key>` is appended last. Every request: `GET`, header `Accept: application/json`; for `BEARER` credentials header `Authorization: Bearer <token>`. Request timeout `requestTimeoutSeconds`; client connect timeout `connectTimeoutSeconds`; `HttpClient.Redirect.NEVER`.
- Status mapping: 2xx → JSON object (anything else, including an empty body or a JSON array, → `BAD_RESPONSE` `TMDB answered with something unexpected`); 401 and 403 → `UNAUTHORIZED` `TMDB rejected the API key or read access token`; 404 → `NOT_FOUND` `TMDB does not know this title`; 429 → `RATE_LIMITED` `TMDB is limiting requests; try again in a moment`; 5xx → `SERVER_ERROR` `TMDB had a server error (HTTP <status>)`; other 3xx/4xx → `BAD_RESPONSE` `TMDB answered HTTP <status>`; I/O → `UNREACHABLE` `Could not reach TMDB at <apiBaseUrl host>`; more than 2 MiB → `BAD_RESPONSE` `TMDB answered with more data than expected`; unparsable → `BAD_RESPONSE` `TMDB answered with something that is not JSON`. No message contains a path with a query, a token or a key.
- Validate: `GET /authentication` → `success: true` required, else `UNAUTHORIZED`.
- Configuration: `GET /configuration`.
- Search: `GET /search/multi` with `query=<q>`, `language=<locale>`, `include_adult=false`, `page=1`.
- Trending (Task 4): `GET /trending/all/week` with `language=<locale>`, `page=<n>`.
- Watch providers: `GET /movie/<id>/watch/providers` or `GET /tv/<id>/watch/providers` (no query).
- Details: `GET /movie/<id>` or `GET /tv/<id>` with `language=<locale>`, `append_to_response=watch/providers`.

**Item mapping (normative).** Media type = the result's `media_type`, else the hint; only `movie` and `tv` map (others, e.g. `person`, are skipped). `id` must be an integral number > 0. `adult: true` → skipped. Movie title `title`, fallback `original_title`; series title `name`, fallback `original_name`; blank title → skipped. Year = the first four characters of `release_date` (movie) or `first_air_date` (series) when the value matches `^\d{4}-`. Subtitle: with a non-null `subtitlePrefix` → prefix plus ` · <year>` when a year exists; otherwise `Movie` or `Series`, plus ` · <year>`. Item id `movie-<id>` / `tv-<id>`; source id `tmdb`; kind `MOVIE` for movies, `VIDEO` for series; artwork `posters.apply(poster_path)` (poster_path `null` or missing → `posters` is not called, artwork null); progress null; playables as given.

- [ ] **Step 1: Configuration, fixtures and the fake server**

`src/main/resources/application.yaml` — under the existing `home-control:` key add:

```yaml
  tmdb:
    enabled: true
    api-base-url: https://api.themoviedb.org/3
    # Leave empty to use TMDB's /configuration; set to serve posters from a mirror.
    image-base-url:
    connect-timeout-seconds: 5
    request-timeout-seconds: 10
    rail-size: 20
    trending-candidates: 40
    provider-cache-ttl: 24h
    configuration-cache-ttl: 24h
    provider-ids:
      netflix: [8, 1796]
      primevideo: [9, 119, 2100]
```

`src/test/resources/application.yaml` — the same block with `api-base-url: http://127.0.0.1:9/3` and `connect-timeout-seconds: 1`, `request-timeout-seconds: 2`.

`sources/tmdb/TmdbProperties.java`:

```java
package dev.andre.homecontrol.sources.tmdb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("home-control.tmdb")
public record TmdbProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("https://api.themoviedb.org/3") URI apiBaseUrl,
                             URI imageBaseUrl,
                             @DefaultValue("5") int connectTimeoutSeconds,
                             @DefaultValue("10") int requestTimeoutSeconds,
                             @DefaultValue("20") int railSize,
                             @DefaultValue("40") int trendingCandidates,
                             @DefaultValue("24h") Duration providerCacheTtl,
                             @DefaultValue("24h") Duration configurationCacheTtl,
                             Map<String, List<Integer>> providerIds) {

    public static final Map<String, List<Integer>> DEFAULT_PROVIDER_IDS =
            Map.of("netflix", List.of(8, 1796), "primevideo", List.of(9, 119, 2100));

    public TmdbProperties {
        providerIds = providerIds == null || providerIds.isEmpty() ? DEFAULT_PROVIDER_IDS : Map.copyOf(providerIds);
        if (imageBaseUrl != null && imageBaseUrl.toString().isBlank()) {
            imageBaseUrl = null;
        }
    }
}
```

(If Spring binds an empty `image-base-url:` to a failure instead of null, delete the key from both YAML files and keep the comment in the README.)

Fixtures under `src/test/resources/fixtures/tmdb/` (plausible copies of TMDB's documented shapes; trimmed to the fields used plus a few that are not):

`authentication.json`:
```json
{ "success": true, "status_code": 1, "status_message": "Success." }
```

`authentication-invalid.json`:
```json
{ "status_code": 7, "status_message": "Invalid API key: You must be granted a valid key.", "success": false }
```

`not-found.json`:
```json
{ "success": false, "status_code": 34, "status_message": "The resource you requested could not be found." }
```

`configuration.json`:
```json
{
  "change_keys": ["adult", "air_date", "also_known_as", "images", "release_dates", "videos"],
  "images": {
    "base_url": "http://image.tmdb.org/t/p/",
    "secure_base_url": "https://image.tmdb.org/t/p/",
    "backdrop_sizes": ["w300", "w780", "w1280", "original"],
    "logo_sizes": ["w45", "w92", "w154", "w185", "w300", "w500", "original"],
    "poster_sizes": ["w92", "w154", "w185", "w342", "w500", "w780", "original"],
    "profile_sizes": ["w45", "w185", "h632", "original"],
    "still_sizes": ["w92", "w185", "w300", "original"]
  }
}
```

`search-multi.json`:
```json
{
  "page": 1,
  "results": [
    { "adult": false, "backdrop_path": "/ncEsesgOJDNrTUED89hYbA117wo.jpg", "id": 603, "title": "Matrix",
      "original_title": "The Matrix", "overview": "Der Hacker Neo …", "poster_path": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
      "media_type": "movie", "original_language": "en", "genre_ids": [28, 878], "popularity": 98.1,
      "release_date": "1999-03-30", "video": false, "vote_average": 8.2, "vote_count": 26000 },
    { "adult": false, "gender": 2, "id": 6384, "name": "Keanu Reeves", "original_name": "Keanu Reeves",
      "media_type": "person", "popularity": 60.3, "known_for_department": "Acting",
      "profile_path": "/4D0PpNI0kmP58hgrwGC3wCjxhnm.jpg", "known_for": [] },
    { "adult": false, "id": 604, "title": "Matrix Reloaded", "original_title": "The Matrix Reloaded",
      "poster_path": "/9TGHDvWrqKBzwDxDodHYXEmOE6J.jpg", "media_type": "movie", "release_date": "2003-05-15" },
    { "adult": false, "id": 55931, "title": "", "original_title": "The Animatrix", "poster_path": null,
      "media_type": "movie", "release_date": "" },
    { "adult": true, "id": 999001, "title": "Adult title", "media_type": "movie", "release_date": "2001-01-01" }
  ],
  "total_pages": 1,
  "total_results": 5
}
```

`trending-all-week.json`:
```json
{
  "page": 1,
  "results": [
    { "adult": false, "id": 66732, "name": "Stranger Things", "original_name": "Stranger Things", "media_type": "tv",
      "poster_path": "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg", "first_air_date": "2016-07-15", "origin_country": ["US"] },
    { "adult": false, "id": 603, "title": "Matrix", "original_title": "The Matrix", "media_type": "movie",
      "poster_path": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg", "release_date": "1999-03-30" },
    { "adult": false, "id": 500, "name": "Tom Cruise", "media_type": "person", "profile_path": "/eOh4ubpOm2Igdg0QH2ghj0mFtC.jpg" },
    { "adult": false, "id": 76479, "name": "The Boys", "original_name": "The Boys", "media_type": "tv",
      "poster_path": "/2zmTngn1tYC1AvfnrFLhxeD82hz.jpg", "first_air_date": "2019-07-25" },
    { "adult": false, "id": 550, "title": "Fight Club", "original_title": "Fight Club", "media_type": "movie",
      "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", "release_date": "1999-10-15" },
    { "adult": false, "id": 94997, "name": "House of the Dragon", "original_name": "House of the Dragon", "media_type": "tv",
      "poster_path": "/z2yahl2uefxDCl0nogcRBstwruJ.jpg", "first_air_date": "2022-08-21" }
  ],
  "total_pages": 1,
  "total_results": 6
}
```

`providers-tv-66732.json`:
```json
{
  "id": 66732,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/tv/66732-stranger-things/watch?locale=DE",
      "flatrate": [
        { "logo_path": "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg", "provider_id": 8, "provider_name": "Netflix", "display_priority": 0 },
        { "logo_path": "/kICQccvOh8AIBMHGkBXJ047xeHN.jpg", "provider_id": 1796, "provider_name": "Netflix basic with Ads", "display_priority": 95 }
      ]
    },
    "US": {
      "link": "https://www.themoviedb.org/tv/66732-stranger-things/watch?locale=US",
      "flatrate": [ { "logo_path": "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg", "provider_id": 8, "provider_name": "Netflix", "display_priority": 0 } ]
    }
  }
}
```

`providers-tv-76479.json`:
```json
{
  "id": 76479,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/tv/76479-the-boys/watch?locale=DE",
      "flatrate": [ { "logo_path": "/pvske1MyAoymrs5bguRfVqYiM9a.jpg", "provider_id": 119, "provider_name": "Amazon Prime Video", "display_priority": 2 } ],
      "ads": [ { "logo_path": "/8aBqoNeGGr0oSA85iopgNZUOTOc.jpg", "provider_id": 2100, "provider_name": "Amazon Prime Video with Ads", "display_priority": 180 } ]
    }
  }
}
```

`providers-movie-603.json`:
```json
{
  "id": 603,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/movie/603-the-matrix/watch?locale=DE",
      "flatrate": [ { "logo_path": "/1UP7ysjs5zvtSXBf8TXDmR9TUCu.jpg", "provider_id": 30, "provider_name": "WOW", "display_priority": 12 } ],
      "rent": [ { "logo_path": "/9ghgSC0MA082EL6HLCW3GalykFD.jpg", "provider_id": 2, "provider_name": "Apple TV", "display_priority": 4 } ],
      "buy": [ { "logo_path": "/seGSXajazLMCKGB5hnRCidtjay1.jpg", "provider_id": 10, "provider_name": "Amazon Video", "display_priority": 20 } ]
    }
  }
}
```

`providers-movie-550.json`:
```json
{
  "id": 550,
  "results": {
    "DE": {
      "link": "https://www.themoviedb.org/movie/550-fight-club/watch?locale=DE",
      "rent": [ { "logo_path": "/seGSXajazLMCKGB5hnRCidtjay1.jpg", "provider_id": 10, "provider_name": "Amazon Video", "display_priority": 20 } ],
      "buy": [ { "logo_path": "/9ghgSC0MA082EL6HLCW3GalykFD.jpg", "provider_id": 2, "provider_name": "Apple TV", "display_priority": 4 } ]
    }
  }
}
```

`providers-tv-94997.json`:
```json
{
  "id": 94997,
  "results": {
    "US": {
      "link": "https://www.themoviedb.org/tv/94997-house-of-the-dragon/watch?locale=US",
      "flatrate": [ { "logo_path": "/fksCUZ9QDWZMUwL2LgMtLckROUN.jpg", "provider_id": 1899, "provider_name": "Max", "display_priority": 3 } ]
    }
  }
}
```

`details-tv-66732.json`:
```json
{
  "adult": false, "id": 66732, "name": "Stranger Things", "original_name": "Stranger Things",
  "first_air_date": "2016-07-15", "number_of_seasons": 5, "poster_path": "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
  "watch/providers": {
    "results": {
      "DE": {
        "link": "https://www.themoviedb.org/tv/66732-stranger-things/watch?locale=DE",
        "flatrate": [ { "logo_path": "/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg", "provider_id": 8, "provider_name": "Netflix", "display_priority": 0 } ]
      }
    }
  }
}
```

`details-movie-603.json`:
```json
{
  "adult": false, "id": 603, "title": "Matrix", "original_title": "The Matrix", "release_date": "1999-03-30",
  "runtime": 136, "poster_path": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
  "watch/providers": {
    "results": {
      "DE": {
        "link": "https://www.themoviedb.org/movie/603-the-matrix/watch?locale=DE",
        "flatrate": [ { "logo_path": "/1UP7ysjs5zvtSXBf8TXDmR9TUCu.jpg", "provider_id": 30, "provider_name": "WOW", "display_priority": 12 } ]
      }
    }
  }
}
```

`src/test/java/dev/andre/homecontrol/sources/tmdb/FakeTmdbServer.java` — same design as C's `FakeJellyfinServer` (JDK `HttpServer` on `127.0.0.1:0`, virtual-thread executor, canned responses keyed by `METHOD path`, every request recorded with method, path, query map, lower-cased headers): constants `READ_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJob21lLWNvbnRyb2wtdGVzdCJ9.c2lnbmF0dXJlLW9mLXRoZS10ZXN0LXRva2Vu"` and `API_KEY = "0123456789abcdef0123456789abcdef"`; `URI url()` → `http://127.0.0.1:<port>`; `URI apiBase()` → `url() + "/3"`; `respond(method, path, status, fixture)` (fixture from `/fixtures/tmdb/`), `respondJson(method, path, status, json)`, `respondBytes(method, path, status, contentType, body)`, `delay(Duration)` (sleeps before answering, for timeout tests), `redirect(path, location)` (302); `List<Recorded> requests(method, path)`, `Recorded last(method, path)` (throws `AssertionError` listing received requests when none), `int count(method, path)`; `withStandardResponses()` registers `GET /3/authentication`, `/3/configuration`, `/3/search/multi`, `/3/trending/all/week`, `/3/movie/603`, `/3/tv/66732` (details fixtures), and `/3/{movie|tv}/{id}/watch/providers` for the five provider fixtures; unknown routes answer 404 with `not-found.json`. `close()` stops the server with delay 0.

- [ ] **Step 2: Write the failing tests**

`TmdbCredentialTest.java`:
- `aThirtyTwoCharacterHexStringIsAnApiKey`: `parse(" 0123456789abcdef0123456789abcdef ")` → `API_KEY`, value without spaces.
- `aJwtIsABearerToken`: `parse(FakeTmdbServer.READ_TOKEN)` → `BEARER`; `parse("Bearer " + READ_TOKEN)` → `BEARER` with the token only.
- `anythingElseIsRejectedWithAHint` (parameterized: `""`, `"   "`, `null`, `"0123456789ABCDEF0123456789ABCDEF"`, `"abc"`, `"a.b.c"`, 2 100 × `a` joined into three dotted parts) → `IllegalArgumentException` with message `Paste the API Read Access Token or the API key from your TMDB account settings`.
- `toStringNeverShowsTheValue`: `toString()` contains `BEARER` and not the token.

`TmdbClientTest.java` (real `FakeTmdbServer`, `TmdbProperties` pointing `apiBaseUrl` at `fake.apiBase()`, timeouts 1 s / 1 s):
- `bearerTokensGoInTheAuthorizationHeader`: `get(bearer, "/authentication", Map.of())` → `success` true; recorded header `authorization` = `Bearer <READ_TOKEN>`; query has no `api_key`.
- `apiKeysGoInTheQueryLast`: `get(apiKey, "/search/multi", linked map query=matrix, language=de-DE)` → recorded raw query equals `query=matrix&language=de-DE&api_key=0123456789abcdef0123456789abcdef`; no `authorization` header.
- `queryValuesAreEncoded`: query `Tom & Jerry/ü` → recorded decoded `query` value equals it; header `accept` is `application/json`.
- `statusesMapToKinds` (parameterized 401→UNAUTHORIZED, 403→UNAUTHORIZED, 404→NOT_FOUND, 429→RATE_LIMITED, 500→SERVER_ERROR, 503→SERVER_ERROR, 418→BAD_RESPONSE) with the messages from the wire format.
- `redirectsAreNotFollowed`: `/configuration` answers 302 to `/elsewhere` → `BAD_RESPONSE` `TMDB answered HTTP 302`; `fake.count("GET", "/elsewhere")` is 0.
- `nonJsonAndArraysAreBadResponses`: body `<html>` → `TMDB answered with something that is not JSON`; body `[]` → `TMDB answered with something unexpected`.
- `oversizedBodiesAreRejected`: 2 MiB + 10 bytes of spaces inside a JSON string → `TMDB answered with more data than expected`.
- `unreachableAndSlowServersAreUnreachable`: base `http://127.0.0.1:9/3` → `UNREACHABLE` with message `Could not reach TMDB at 127.0.0.1`; `fake.delay(3s)` → `UNREACHABLE` within 3 s.
- `noMessageLeaksTheKey`: for the API-key credential and each failing case above, `exception.getMessage()` and `String.valueOf(exception.getCause())` do not contain `0123456789abcdef0123456789abcdef` or `api_key`.
- `aTmdbExceptionIsAContentSourceException`.

`TmdbMediaRefTest.java`: `parsesItemIds` (`movie-603` → MOVIE 603, path `/movie/603`; `tv-66732` → TV); `rejectsOtherIds` (`person-1`, `movie-`, `movie-0`, `movie-12345678901`, `movie-6a`, `../movie-1`, null → empty); `readsSearchResults` (`{"media_type":"tv","id":1}` → TV 1; `{"id":2}` with hint `movie` → MOVIE 2; `{"media_type":"person","id":3}` → empty; `{"media_type":"movie","id":"7"}` → empty).

`WatchProvider`/`TmdbWatchProvidersTest.java`:
- `parsesEveryCategoryForTheRegion`: `providers-movie-603.json` region `DE` → `[WOW FLATRATE 30, Apple TV RENT 2, Amazon Video BUY 10]` in category order FLATRATE, FREE, ADS, RENT, BUY and then by `display_priority`.
- `aMissingRegionIsEmpty`: `providers-tv-94997.json` region `DE` → empty; region `us` (lower case) → uses `US` (region upper-cased).
- `skipsEntriesWithoutIdOrName`: inline JSON with `provider_id: 0` and `provider_name: ""` → skipped.
- `onlySubscriptionCategoriesCount`: `FLATRATE`, `FREE`, `ADS` → `subscription()` true; `RENT`, `BUY` false.
- `looksUpOnceAndCaches`: fake → `providers(bearer, tv-66732, "DE")` twice → one request to `/3/tv/66732/watch/providers`; after a mutable `Clock` advances past `providerCacheTtl` → two.
- `rememberedDetailsAvoidALookup`: `remember(tv-66732, details["watch/providers"])` then `providers(...)` → no request.
- `failuresAreNotCached`: first call 500 → `TmdbException`; second call (fixture restored) succeeds.

`TmdbImagesTest.java`:
- `buildsPosterUrisFromTheConfiguration`: `poster(bearer, "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg")` → `https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg`; a second poster → still one `/3/configuration` request.
- `picksTheBestSizeWhenW342IsMissing`: configuration with `poster_sizes ["w92","w500","w780","original"]` → `w500`; `["original"]` → `original`.
- `ignoresAnInsecureBase`: `secure_base_url` `http://evil.example/` → fallback base `https://image.tmdb.org/t/p/`.
- `fallsBackWhenConfigurationFailsAndRetriesLater`: 500 → fallback URI; clock + 5 min → no new request; clock + 11 min → one new request.
- `anOverrideSkipsTheConfiguration`: `imageBaseUrl` `https://img.example/t/p/` → `https://img.example/t/p/w342/x.jpg` and no `/3/configuration` request.
- `rejectsMalformedPaths` (parameterized: null, `""`, `"x.jpg"`, `"//evil.example/x.jpg"`, `"/../x.jpg"`, `"/a/b.jpg"`, `"/x.jpg?y"`, `"/x.gif"`) → null.

`TmdbItemMapperTest.java` (poster function `p -> URI.create("https://image.tmdb.org/t/p/w342" + p)`):
- `mapsAMovie`: first `search-multi.json` result → id `movie-603`, source `tmdb`, kind `MOVIE`, title `Matrix`, subtitle `Movie · 1999`, artwork `…/w342/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg`, progress null, no playables.
- `mapsASeries`: first `trending-all-week.json` result → `tv-66732`, kind `VIDEO`, subtitle `Series · 2016`.
- `fallsBackToTheOriginalTitleAndOmitsAMissingYear`: result 4 → title `The Animatrix`, subtitle `Movie`, artwork null (poster function never called — use a function that throws for null).
- `skipsPeopleAndAdultTitles`: results 2 and 5 → empty.
- `usesThePrefixAndThePlayables`: prefix `On Netflix`, playables `[AppLink(https://www.netflix.com/browse, netflix)]` → subtitle `On Netflix · 2016`, playables as given.

`TmdbSetupServiceTest.java` (real `SecretStore`, `LoginService`, `JsonFileSourceSettings` in `@TempDir` as C's `JellyfinSetupServiceTest`; fake server; fixed `Clock` at `2026-09-16T10:00:00Z`; `MockHttpServletRequest`):
- `connectsWithABearerTokenAndSetsTheLoginPassword`: `connect(new ConnectRequest(READ_TOKEN, "household pw 1", "household pw 1"), req)` → settings `BEARER`, `connectedAt` 10:00; `secretStore.secret("tmdb.credential")` = token; `sources.json` `tmdb` map = `{"credentialKind":"BEARER","connectedAt":"2026-09-16T10:00:00Z"}`; `login.loginRequired()` true; `/3/authentication` requested once with the bearer header.
- `connectsWithAnApiKey`: → `API_KEY`, secret = key.
- `aRejectedCredentialStoresNothing`: `/3/authentication` 401 → `TmdbException` UNAUTHORIZED; no secret, no settings, `loginRequired()` false.
- `aMalformedCredentialIsInvalidInput`: `"nope"` → `TmdbException` kind `INVALID_INPUT` with the parse message; no request sent.
- `theFirstSecretNeedsAGoodPassword`: password `short` → `PasswordRejectedException`; nothing stored.
- `laterConnectsNeedALoggedInBrowser`: after a Jellyfin-style first secret, `connect(..., new MockHttpServletRequest())` → `LoginRequiredException`.
- `checkSaysWhatWorks`: → `TMDB accepted the read access token` (or `… the API key`); not connected → `TmdbException` INVALID_INPUT `TMDB is not connected`.
- `disconnectRemovesSecretAndSettings`: → `credential()` empty, `settings()` empty; when it was the only secret, `loginRequired()` false (C's rule).
- `credentialNeedsBothSettingsAndSecret`: settings present but secret removed by hand → `credential()` empty.

`TmdbSetupControllerTest.java` — `@WebMvcTest({TmdbSetupController.class, SetupController.class, TmdbSetupAdvice.class})`, mocks for `SetupController`'s dependencies as in C's `JellyfinSetupControllerTest`, `@MockitoBean TmdbSetupService setup`, `@MockitoBean LoginService login`:
- `connectRedirectsWithAMessage`: POST `/setup/sources/tmdb` `credential=x&loginPassword=p&loginPasswordConfirmation=p` → 302 `/setup#tmdb`, flash `tmdbMessage` `TMDB connected`; captor: `ConnectRequest` values passed through.
- `failuresBecomeFlashErrors`: `TmdbException(UNAUTHORIZED, …)` → flash `tmdbError` with its message; `PasswordRejectedException("The two passwords do not match")` → that message; `LoginRequiredException` → `Log in again to change TMDB`.
- `testAndDisconnect`: `/test` → flash `tmdbMessage` = `setup.check()`; `/disconnect` → `TMDB disconnected`.
- `theSetupPageShowsTheSection`: GET `/setup` with `setup.settings()` empty and `login.loginRequired()` false → body contains `id="tmdb"`, `name="credential"`, `type="password"`, `loginPassword`, `This product uses the TMDB API but is not endorsed or certified by TMDB.`; configured → contains `Connected (read access token)` and `Disconnect`, no `loginPassword`; the body never contains a credential value.

`TmdbContentSourceTest.java` (fake server with standard responses; a real `TmdbSetupService` connected in `@BeforeEach`, or a mocked one returning `Optional.of(bearer)` and settings; preferences supplier returning `SourcePreferences.defaults("de-DE","DE")`):
- `isAvailableOnlyWhenConnected`: mocked setup without credential → `available()` false, `rails()` empty; with → true.
- `searchesMultiInTheUsersLanguage`: `search("matrix", 10)` → titles `[Matrix, Matrix Reloaded, The Animatrix]`; recorded query `query=matrix`, `language=de-DE`, `include_adult=false`, `page=1`; artwork of the first starts with `https://image.tmdb.org/t/p/w342/`.
- `searchRespectsTheLimit`: `search("matrix", 2)` → 2 items.
- `searchFailuresAreContentSourceExceptions`: 429 → `ContentSourceException` with `TMDB is limiting requests; try again in a moment`.
- `readsAnItemWithItsProviders`: `item("tv-66732")` → title `Stranger Things`, subtitle `Series · 2016`; recorded query `language=de-DE`, `append_to_response=watch/providers`; afterwards `watchProviders.providers(bearer, tv-66732, "DE")` sends no request (remembered).
- `unknownOrMalformedItemsAreEmpty`: `item("movie-1")` (404) → empty; `item("person-1")` → empty without a request.
- `itemFailuresOtherThanNotFoundPropagate`: 500 → `ContentSourceException`.
- `hasNoRailsYetAndRefreshesEverySixHours`: `rails()` empty; `rail("trending")` → `IllegalArgumentException`; `defaultRefreshInterval()` 6 h.

`TmdbModuleSwitchTest.java` — `@SpringBootTest` with `home-control.tmdb.enabled=false` → no `TmdbContentSource`, `TmdbSetupController`, `TmdbSetupAdvice` bean; `ContentSources.find("tmdb")` empty; MockMvc `GET /setup` → 200 without `id="tmdb"`. A second nested class (or a separate test) with the default → the beans exist.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.*'`
Expected: compilation failure — the `sources.tmdb` classes do not exist.

- [ ] **Step 4: Implement credential, exception and client**

`sources/tmdb/TmdbCredential.java`:

```java
package dev.andre.homecontrol.sources.tmdb;

import java.util.regex.Pattern;

/** A TMDB v4 API Read Access Token (sent as a bearer header) or a v3 API key (sent as {@code api_key}). */
public record TmdbCredential(Kind kind, String value) {

    public enum Kind { BEARER, API_KEY }

    private static final Pattern API_KEY = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern JWT = Pattern.compile("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$");
    private static final int MAX_LENGTH = 2048;

    public static TmdbCredential parse(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).strip();
        }
        if (API_KEY.matcher(value).matches()) {
            return new TmdbCredential(Kind.API_KEY, value);
        }
        if (value.length() <= MAX_LENGTH && JWT.matcher(value).matches()) {
            return new TmdbCredential(Kind.BEARER, value);
        }
        throw new IllegalArgumentException("Paste the API Read Access Token or the API key from your TMDB account settings");
    }

    public String describe() {
        return kind == Kind.BEARER ? "read access token" : "API key";
    }

    @Override
    public String toString() {
        return "TmdbCredential[" + kind + ", redacted]";
    }
}
```

`sources/tmdb/TmdbException.java` — `public class TmdbException extends ContentSourceException` with `enum Kind { INVALID_INPUT, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER_ERROR, BAD_RESPONSE }`, a `kind` field, constructors `(Kind, String)` and `(Kind, String, Throwable)`, accessor `kind()`.

`sources/tmdb/TmdbClient.java`:

```java
package dev.andre.homecontrol.sources.tmdb;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The only class that speaks HTTP to TMDB. Never follows redirects (a redirect would replay the
 * bearer header elsewhere), caps bodies, and never puts a URL with its query into a message:
 * the API-key form carries the key in the query.
 */
public class TmdbClient {

    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TmdbProperties properties;
    private final HttpClient http;

    public TmdbClient(TmdbProperties properties) {
        this(properties, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build());
    }

    public TmdbClient(TmdbProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    public JsonNode get(TmdbCredential credential, String path, Map<String, String> query) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(credential, path, query))
                .GET()
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "application/json");
        if (credential.kind() == TmdbCredential.Kind.BEARER) {
            request.header("Authorization", "Bearer " + credential.value());
        }
        HttpResponse<InputStream> response;
        byte[] body;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_BODY_BYTES + 1);
            }
        } catch (IOException e) {
            throw new TmdbException(TmdbException.Kind.UNREACHABLE, unreachable(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmdbException(TmdbException.Kind.UNREACHABLE, unreachable(), e);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new TmdbException(TmdbException.Kind.UNAUTHORIZED, "TMDB rejected the API key or read access token");
        }
        if (status == 404) {
            throw new TmdbException(TmdbException.Kind.NOT_FOUND, "TMDB does not know this title");
        }
        if (status == 429) {
            throw new TmdbException(TmdbException.Kind.RATE_LIMITED, "TMDB is limiting requests; try again in a moment");
        }
        if (status >= 500) {
            throw new TmdbException(TmdbException.Kind.SERVER_ERROR, "TMDB had a server error (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered HTTP " + status);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered with more data than expected");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered with something that is not JSON");
        }
        if (node == null || !node.isObject()) {
            throw new TmdbException(TmdbException.Kind.BAD_RESPONSE, "TMDB answered with something unexpected");
        }
        return node;
    }

    private URI uri(TmdbCredential credential, String path, Map<String, String> query) {
        String base = properties.apiBaseUrl().toString().replaceAll("/+$", "");
        StringJoiner parameters = new StringJoiner("&");
        query.forEach((name, value) -> parameters.add(encode(name) + "=" + encode(value)));
        if (credential.kind() == TmdbCredential.Kind.API_KEY) {
            parameters.add("api_key=" + encode(credential.value()));
        }
        return URI.create(base + path + (parameters.length() == 0 ? "" : "?" + parameters));
    }

    private String unreachable() {
        return "Could not reach TMDB at " + properties.apiBaseUrl().getHost();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

Note: the JDK wraps some `IOException`s in messages containing the URI; the tests' leak check covers `getMessage()` of our exception and `String.valueOf(getCause())`. If the cause's text contains the query, wrap with a cause-less exception instead (`new TmdbException(kind, message)`) and log nothing. Callers pass `Map`s in a defined order: use `LinkedHashMap` or `Map.of` only for a single entry.

- [ ] **Step 5: Implement media refs, providers, images and the mapper**

`sources/tmdb/TmdbMediaRef.java`: pattern `^(movie|tv)-([1-9][0-9]{0,9})$`; `of(JsonNode result, String hint)`: type = `result.path("media_type").asString(hint == null ? "" : hint)`, must be `movie` or `tv`; `id` node must satisfy `isIntegralNumber()` and `asLong(0) > 0`.

`sources/tmdb/WatchProvider.java`:

```java
package dev.andre.homecontrol.sources.tmdb;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** One entry of TMDB's JustWatch-backed watch-provider lists for a region. */
public record WatchProvider(int id, String name, Category category, int displayPriority) {

    public enum Category {
        FLATRATE("flatrate"), FREE("free"), ADS("ads"), RENT("rent"), BUY("buy");

        private final String jsonKey;

        Category(String jsonKey) {
            this.jsonKey = jsonKey;
        }

        public String jsonKey() {
            return jsonKey;
        }

        /** Watchable with a subscription or for free — what "on your services" means. */
        public boolean subscription() {
            return this == FLATRATE || this == FREE || this == ADS;
        }
    }

    /** {@code providers} is the object holding {@code results} (the endpoint body or {@code "watch/providers"}). */
    public static List<WatchProvider> parse(JsonNode providers, String region) {
        JsonNode forRegion = providers.path("results").path(region.toUpperCase(Locale.ROOT));
        List<WatchProvider> found = new ArrayList<>();
        for (Category category : Category.values()) {
            List<WatchProvider> inCategory = new ArrayList<>();
            for (JsonNode entry : forRegion.path(category.jsonKey()).values()) {
                int id = entry.path("provider_id").asInt(0);
                String name = entry.path("provider_name").asString("").strip();
                if (id > 0 && !name.isEmpty()) {
                    inCategory.add(new WatchProvider(id, name, category, entry.path("display_priority").asInt(Integer.MAX_VALUE)));
                }
            }
            inCategory.sort(Comparator.comparingInt(WatchProvider::displayPriority));
            found.addAll(inCategory);
        }
        return List.copyOf(found);
    }
}
```

(`values()` on a missing node yields nothing in Jackson 3; if the real API differs, guard with `isArray()`.)

`sources/tmdb/TmdbWatchProviders.java` — `synchronized` access to a `LinkedHashMap<TmdbMediaRef, Entry(JsonNode providers, Instant fetchedAt)>` in access order with `removeEldestEntry` beyond 2 000; `providers(credential, ref, region)`: fresh entry (`fetchedAt + providerCacheTtl` after `clock.instant()`) → parse; else `client.get(credential, ref.path() + "/watch/providers", Map.of())` **outside** the lock, store, parse. `remember(ref, node)` stores when `node` is an object.

`sources/tmdb/TmdbImages.java` — constants `DEFAULT_BASE = "https://image.tmdb.org/t/p/"`, `PREFERRED_SIZE = "w342"`, `RETRY_AFTER_FAILURE = Duration.ofMinutes(10)`; `POSTER_PATH = Pattern.compile("^/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp|svg)$")`. `poster(credential, path)`: null when `path` does not match; base = `imageBaseUrl` override (ensure trailing `/`) + `w342`, else cached `(base, size, validUntil)`; refresh when expired: `GET /configuration` → `images.secure_base_url` (must start with `https://`, else `DEFAULT_BASE`; ensure trailing `/`), size per Decisions (from `images.poster_sizes`), valid for `configurationCacheTtl`; `TmdbException` → `(DEFAULT_BASE, w342)` valid for 10 min, logged once at WARN with the message only. Result `URI.create(base + size + path)`.

`sources/tmdb/TmdbItemMapper.java` — implement the item mapping rules above.

- [ ] **Step 6: Implement settings, setup service, controller, advice and the section**

`sources/tmdb/TmdbSettings.java` — map keys `credentialKind` (`BEARER`/`API_KEY`) and `connectedAt` (ISO instant); `from` returns empty when the kind is missing or unknown; a missing/unparsable `connectedAt` becomes `Instant.EPOCH`.

`sources/tmdb/TmdbSetupService.java`:
- `credential()` = `settings().flatMap(s -> secrets.secret(CREDENTIAL_SECRET)).flatMap(v -> { try { return Optional.of(TmdbCredential.parse(v)); } catch (IllegalArgumentException e) { return Optional.empty(); } })`.
- `connect(request, http)`: parse (`IllegalArgumentException` → `TmdbException(INVALID_INPUT, message)`); `validate(credential)` = `client.get(credential, "/authentication", Map.of())` and `success` must be true (else `UNAUTHORIZED` with the client's message); `login.storeSecrets(Map.of(CREDENTIAL_SECRET, credential.value()), request.loginPassword(), request.loginPasswordConfirmation(), http)`; `sources.put(SOURCE_ID, new TmdbSettings(credential.kind(), clock.instant()).toMap())`; return settings. Nothing is stored when validation fails.
- `check()`: not connected → `TmdbException(INVALID_INPUT, "TMDB is not connected")`; validate → `TMDB accepted the ` + `credential.describe()`.
- `disconnect()`: `login.removeSecrets(List.of(CREDENTIAL_SECRET))`, `sources.remove(SOURCE_ID)`.
- `ConnectRequest.toString()` → `ConnectRequest[redacted]`.

`sources/tmdb/TmdbSetupController.java` — `@Controller`, `@ConditionalOnProperty(name = "home-control.tmdb.enabled", havingValue = "true", matchIfMissing = true)`; the three POST handlers from Interfaces; messages `TMDB connected`, check text, `TMDB disconnected`; catches `ContentSourceException` (incl. `TmdbException`), `PasswordRejectedException` → its message; `LoginRequiredException` → `Log in again to change TMDB`. Always `redirect:/setup#tmdb`.

`sources/tmdb/TmdbSetupAdvice.java` — `@ControllerAdvice(assignableTypes = SetupController.class)` with the module condition; `ObjectProvider<TmdbSetupService>`, `ObjectProvider<LoginService>`; `@ModelAttribute("tmdb")` → `View(configured, kindLabel ("read access token"/"API key"/null), needsLoginPassword = login unavailable || !loginRequired())`.

`src/main/resources/templates/fragments/tmdb-setup.html` — `<section id="tmdb" th:fragment="section">` with heading `TMDB (movies and series)`; flash `tmdbError` (`<p class="error">`) / `tmdbMessage` (`<p class="hint">`); text `TMDB supplies titles, artwork, trending lists and where a title streams. Netflix, Prime Video and DAZN have no public APIs, so Home Control opens their apps — it cannot see what you watch there.`; configured → `Connected (<kind>)` plus forms to `/setup/sources/tmdb/test` (`Test connection`) and `/setup/sources/tmdb/disconnect` (`Disconnect`); always a form to `/setup/sources/tmdb` with `credential` (`type="password"`, `autocomplete="off"`, required, label `API Read Access Token (recommended) or API key`, never pre-filled), a hint linking `https://www.themoviedb.org/settings/api` (`rel="noreferrer"`), and — only when `${tmdb.needsLoginPassword()}` — `loginPassword` / `loginPasswordConfirmation` exactly as C's Jellyfin fragment under the same explanatory sentence; footer `<p class="hint">This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability data by JustWatch.</p>`.

In `setup.html`, after the Jellyfin block:

```html
    <th:block th:if="${tmdb != null}">
        <section th:replace="~{fragments/tmdb-setup :: section}"></section>
    </th:block>
```

- [ ] **Step 7: Implement the content source and the module configuration**

`sources/tmdb/TmdbContentSource.java`:
- `ID = "tmdb"`; `displayName()` `TMDB`; `available()` = `setup.credential().isPresent()`; `rails()` → `List.of()`; `rail(id)` → `IllegalArgumentException("TMDB has no rail '" + id + "'")`; `defaultRefreshInterval()` → `Duration.ofHours(6)`; `searchable()` → true.
- `search(query, limit)`: credential (absent → `ContentSourceException("TMDB is not connected")`); query map `LinkedHashMap` `query`, `language` (= `preferences.get().locale()`), `include_adult=false`, `page=1`; map `results` with hint `null`, posters `path -> images.poster(credential, path)`, prefix null, playables empty; stop at `limit`.
- `item(itemId)`: `TmdbMediaRef.parse` empty → `Optional.empty()`; `client.get(credential, ref.path(), language + append_to_response=watch/providers)`; `NOT_FOUND` → empty; `providers.remember(ref, body.path("watch/providers"))`; map with hint `ref.type() == MOVIE ? "movie" : "tv"`, prefix null, playables empty (Task 4 changes both).

`sources/tmdb/TmdbConfiguration.java` — `@Configuration`, the module condition, `@EnableConfigurationProperties(TmdbProperties.class)`; beans: `TmdbClient(TmdbProperties)`, `TmdbSetupService(TmdbClient, JsonFileSourceSettings, SecretStore, LoginService)` with `Clock.systemUTC()`, `TmdbImages`, `TmdbWatchProviders` (system clock), `TmdbContentSource(setup, client, images, providers, properties, preferencesService::current)` taking `SourcePreferencesService preferencesService` as a bean-method parameter.

- [ ] **Step 8: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. If an existing `@WebMvcTest` of `SetupController` now fails because the page references `tmdb`, the `th:if="${tmdb != null}"` guard is missing.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/tmdb src/test/java/dev/andre/homecontrol/sources/tmdb \
  src/test/resources/fixtures/tmdb src/main/resources/templates/fragments/tmdb-setup.html \
  src/main/resources/templates/setup.html src/main/resources/application.yaml src/test/resources/application.yaml
git commit -m "feat: TMDB source with search, watch providers and setup"
```

(End the message with the two trailer lines from `.superpowers/sdd/implementer-common.md`.)

---
### Task 2: G2 · Pinned shortcuts

**Files:**
- Create: `core/content/ContentChangedEvent.java`, `core/playback/ServiceLinks.java` (names only in this task), `sources/pinned/PinnedProperties.java`, `PinnedConfiguration.java`, `Pin.java`, `JsonFilePinStore.java`, `PinnedShortcuts.java`, `PinnedContentSource.java`, `PinnedSetupController.java`, `PinnedSetupAdvice.java`; `src/main/resources/templates/fragments/pinned-setup.html`
- Modify: `core/playback/AppLinks.java`, `core/playback/Route.java`, `content/RailCache.java`, `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `core/playback/AppLinksTest.java`, `core/playback/ServiceLinksTest.java`, `content/RailCacheTest.java`, `sources/pinned/JsonFilePinStoreTest.java`, `PinnedShortcutsTest.java`, `PinnedContentSourceTest.java`, `PinnedSetupControllerTest.java`, `PinnedModuleSwitchTest.java`; fixture `src/test/resources/fixtures/pinned/pinned-v1.json`

**Interfaces:**
- Consumes: `AppLinks.serviceOf(String host, String path)` (A, public since F), `PlayableRef.AppLink`, `ContentItem`, `ContentKind` (A/C); `ContentSource`, `Rail`, `RailDescriptor`, `ContentSourceException` (C3), `defaultRefreshInterval()` (D1); `RailCache.reconcile()/peek()/refresh(sourceId, railId)`, `RailSnapshot.sourceId()/railId()` (D1); `StorageException(String, Throwable)` (existing); `AndroidTvProperties.dataDir()` (A); `SetupController` (A–D).
- Produces:
  - `record ContentChangedEvent(String sourceId)` in `core/content` — "this source's rails changed; refresh them now".
  - `RailCache.onContentChanged(ContentChangedEvent)` (`@EventListener`): `reconcile()`, then `refresh(sourceId, railId)` for every snapshot in `peek()` whose `sourceId` matches.
  - `final class ServiceLinks { static final String YOUTUBE = "youtube", NETFLIX = "netflix", PRIME_VIDEO = "primevideo", DAZN = "dazn", WEB = "web"; static Optional<String> displayName(String service); static String label(String service, URI uri); }` — `label` = display name, else the URI host.
  - `AppLinks.parseHttpUrl(String url) → URI` (public; the existing validation and messages) — `fromUrl` uses it.
  - `Route.OpenAppLink.describe()` takes names from `ServiceLinks.displayName` (text unchanged).
  - `record PinnedProperties(boolean enabled, int maxPins)` bound to `home-control.pinned` (defaults `true`, `200`).
  - `record Pin(String id, URI url, String service, String title, String subtitle, URI artwork, ContentKind kind, String upgradeOf, Instant createdAt)`.
  - `class JsonFilePinStore { JsonFilePinStore(Path file); List<Pin> load(); void save(List<Pin> pins); }`.
  - `class PinnedShortcuts { PinnedShortcuts(JsonFilePinStore, PinnedProperties, ApplicationEventPublisher, Clock, SecureRandom); List<Pin> all(); Optional<Pin> find(String id); Pin add(String url, String title); void rename(String id, String title); void move(String id, boolean up); void remove(String id); }` — mutations are `synchronized`, validate, save, then publish `ContentChangedEvent("pinned")`; user errors are `IllegalArgumentException` with user-facing messages.
  - `class PinnedContentSource implements ContentSource` — id `pinned`, name `Pinned`, `available()` ⇔ at least one pin, rail `pinned` "Pinned", `item(id)`, searchable (local), `defaultRefreshInterval()` 24 h; `static ContentItem toItem(Pin)`.
  - Endpoints (form posts, all `302 /setup#pinned` with flash `pinnedMessage` or `pinnedError`): `POST /setup/sources/pinned` (`url`, `title`), `POST /setup/sources/pinned/{id}/title` (`title`), `POST /setup/sources/pinned/{id}/move` (`direction=up|down`), `POST /setup/sources/pinned/{id}/remove`.
  - Model attribute `pinned` = `PinnedSetupAdvice.View(List<PinView> pins, int maxPins)` with `record PinView(String id, String title, String subtitle, String url, boolean first, boolean last)`.
  - Property `home-control.pinned.enabled` (default `true`).

**`pinned.json` (normative).** Pretty-printed, written atomically (temp file in the same directory, `Files.move` with `ATOMIC_MOVE` and `REPLACE_EXISTING`):

```json
{
  "version" : 1,
  "pins" : [ {
    "id" : "p-3f9a1c2b7d4e",
    "url" : "https://www.netflix.com/title/80057281",
    "service" : "netflix",
    "title" : "Stranger Things",
    "subtitle" : "Netflix",
    "artwork" : "https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg",
    "kind" : "VIDEO",
    "upgradeOf" : "tmdb/tv-66732",
    "createdAt" : "2026-09-16T10:00:00Z"
  } ]
}
```

Read rules: a missing file → no pins; unparsable JSON, a non-object root, or `version` ≠ 1 → `StorageException("Could not read pinned shortcuts in <file>; fix or delete it", cause)` (for a newer version: `…was written by a newer Home Control; …`). Each entry needs `id` matching `^p-[0-9a-f]{12}$`, a `url` accepted by `AppLinks.parseHttpUrl`, a non-blank `title` ≤ 120 characters, a `kind` naming a `ContentKind` (missing → `VIDEO`); otherwise it is skipped and logged at WARN with its index (never the URL). `service` is recomputed with `AppLinks.serviceOf` (the stored value is informational). `artwork` is kept only when it is an absolute `https://` URI or a path starting with a single `/`; `subtitle` and `upgradeOf` are optional strings (`upgradeOf` must match `^[a-z0-9][a-z0-9._-]{0,63}/[A-Za-z0-9._:-]{1,128}$`, else dropped); `createdAt` missing or unparsable → `Instant.EPOCH`. Duplicate ids after the first are skipped. Null values are written as JSON `null`.

**Pin rules (normative).**
- `add(url, title)`: `url` stripped; blank → `Enter a link to pin`; longer than 2 048 characters → `That link is too long to pin`; `AppLinks.parseHttpUrl` failures keep A's messages (e.g. `Only http and https links can be opened on a device`). Already pinned (same `URI.toString()` after the Task 3 canonicalisation, identity in this task) → `That link is already pinned`. At `maxPins` → `You can pin up to <n> links`. `title` stripped; longer than 120 → `Keep the title under 120 characters`; blank → default `<Service name> link` (`Netflix link`, `Prime Video link`, `YouTube link`, `DAZN link`), or the host for `web`. `subtitle` = `ServiceLinks.label(service, url)`. `kind` `VIDEO`. `artwork` null. `upgradeOf` null. `createdAt` = clock. Id = `p-` + 12 lower-case hex characters from `SecureRandom` (retry on the unlikely collision). Appended at the end.
- `rename`: unknown id → `No pinned link <id>`; title rules as above but blank → `Enter a title`.
- `move(id, up)`: unknown id → `No pinned link <id>`; swaps with the neighbour; at the edge nothing is written and no event is published.
- `remove`: unknown id → `No pinned link <id>`.
- Every write publishes `ContentChangedEvent("pinned")` after saving, outside the lock.

`PinnedContentSource.toItem(pin)` = `ContentItem(pin.id(), "pinned", pin.kind(), pin.title(), pin.subtitle(), pin.artwork(), List.of(new PlayableRef.AppLink(pin.url(), AppLinks.serviceOf(host, path))), null)` (Task 3 replaces the playable with `ServiceLinks.appLink(pin.url())`).

- [ ] **Step 1: Write the failing tests**

`core/playback/ServiceLinksTest.java`:
- `namesKnownServices` (parameterized): `youtube→YouTube`, `netflix→Netflix`, `primevideo→Prime Video`, `dazn→DAZN`, `jellyfin→Jellyfin`; `web` and `unknown` → empty.
- `labelFallsBackToTheHost`: `label("web", https://example.org/a)` → `example.org`; `label("netflix", …)` → `Netflix`.

`core/playback/AppLinksTest.java` — add `parseHttpUrlAcceptsHttpAndHttps` (`HTTPS://Example.org/a` → URI with that string) and `parseHttpUrlRejectsTheSameInputsAsFromUrl` (reuse the `@ValueSource` list, expect `IllegalArgumentException`).

`content/RailCacheTest.java` — add `aContentChangeReconcilesAndRefreshesOnlyThatSource`: two stub sources `a` (rail `r1`) and `b` (rail `r2`), both loaded READY with fetch counters at 1; `cache.onContentChanged(new ContentChangedEvent("a"))` → awaited: `a` fetched twice, `b` still once. And `aSourceThatBecameAvailableGetsItsRail`: stub `c` unavailable (no rails) → `snapshots()` has no `c/…`; make it available; `onContentChanged(new ContentChangedEvent("c"))` → a `RailsChangedEvent` containing `c/pinned` is published and `c` is fetched once.

`src/test/resources/fixtures/pinned/pinned-v1.json` — three entries: a valid Netflix pin (the example above), a valid DAZN pin `{"id":"p-00000000000a","url":"https://www.dazn.com/de-DE/home","title":"DAZN","kind":"VIDEO","createdAt":"2026-09-15T08:00:00Z"}` (no subtitle/artwork/upgradeOf, no `service`), and an invalid one `{"id":"p-00000000000b","url":"javascript:alert(1)","title":"x"}`; plus a fourth `{"id":"p-00000000000c","url":"https://example.org/","title":"Evil art","artwork":"//evil.example/x.jpg","upgradeOf":"../x"}`.

`sources/pinned/JsonFilePinStoreTest.java` (`@TempDir`):
- `aMissingFileHasNoPins`.
- `readsTheDocumentedShape`: copy the fixture → 3 pins: the Netflix pin with every field; the DAZN pin with service `dazn` (recomputed), subtitle/artwork/upgradeOf null; `p-00000000000c` with artwork null and upgradeOf null; the `javascript:` entry skipped.
- `roundTripsAndWritesPrettyJson`: `save(load())` then compare `load()` equal; file text contains `"version" : 1` and `"pins" : [`.
- `writesAtomicallyAndLeavesNoTempFiles`: after `save`, the directory contains exactly `pinned.json`.
- `malformedFilesAreNamedErrors` (parameterized contents: `not json`, `[]`, `{"version":2,"pins":[]}`) → `StorageException` whose message contains the file path and `fix or delete it` (and `newer Home Control` for version 2).
- `duplicateIdsKeepTheFirst`.

`sources/pinned/PinnedShortcutsTest.java` (real store in `@TempDir`, `PinnedProperties(true, 3)`, fixed clock, `SecureRandom` seeded via a stub that returns fixed bytes where determinism matters, captured `ApplicationEventPublisher` mock):
- `addsALinkWithDetectedServiceAndDefaultTitle`: `add("  https://www.netflix.com/title/80057281 ", "")` → id matches `^p-[0-9a-f]{12}$`, service `netflix`, title `Netflix link`, subtitle `Netflix`, kind `VIDEO`, `createdAt` = clock; persisted; one `ContentChangedEvent("pinned")`.
- `usesTheGivenTitle`: title ` Stranger Things ` → `Stranger Things`.
- `webLinksAreTitledByHost`: `https://example.org/x` → title `example.org`, subtitle `example.org`, service `web`.
- `rejectsBadInput` (parameterized url/title → message): `""`→`Enter a link to pin`; `ftp://x/y`→`Only http and https links can be opened on a device`; a 2 049-character URL → `That link is too long to pin`; title of 121 characters → `Keep the title under 120 characters`; nothing is written and no event is published.
- `refusesDuplicatesAndTooManyPins`: same URL twice → `That link is already pinned`; a fourth pin with `maxPins` 3 → `You can pin up to 3 links`.
- `renamesMovesAndRemoves`: three pins A, B, C → `move(C, up)` → A, C, B; `move(A, up)` → unchanged and no event; `rename(B, "Bee")`; `remove(A)` → C, Bee; each effective change publishes one event; unknown ids → `No pinned link p-000000000000`; `rename(id, " ")` → `Enter a title`.
- `survivesARestart`: a new `PinnedShortcuts` over the same file sees the same order.

`sources/pinned/PinnedContentSourceTest.java`:
- `isUnavailableWithoutPins`: empty → `available()` false, `rails()` empty.
- `offersOnePinnedRailInPinOrder`: two pins → `rails()` = `[RailDescriptor("pinned","pinned","Pinned")]`; `rail("pinned").items()` titles in order; each item source `pinned`, one `AppLink` with the pin URL and service; `rail("x")` → `IllegalArgumentException`.
- `readsAnItemById`: `item(id)` present; `item("p-ffffffffffff")` empty.
- `searchesTitlesLocally`: pins `Stranger Things`, `The Boys` → `search("str", 10)` → `[Stranger Things]`; `searchable()` true; limit respected.
- `refreshesDaily`: `defaultRefreshInterval()` = 24 h.

`sources/pinned/PinnedSetupControllerTest.java` — `@WebMvcTest({PinnedSetupController.class, SetupController.class, PinnedSetupAdvice.class})` with `SetupController`'s mocks, `@MockitoBean PinnedShortcuts pins`, and a nested `@TestConfiguration` providing `new PinnedProperties(true, 200)`:
- `addingRedirectsWithAMessage`: POST `/setup/sources/pinned` `url=…&title=…` → `verify(pins).add(url, title)`; 302 `/setup#pinned`; flash `pinnedMessage` `Pinned <title of returned pin>`.
- `errorsBecomeFlashErrors`: `add` throws `IllegalArgumentException("That link is already pinned")` → flash `pinnedError` with the message.
- `renameMoveRemove`: `/p-…/title` → `Renamed to <title>`; `/p-…/move` `direction=down` → `verify(pins).move(id, false)`, message `Order saved`; `direction=sideways` → `pinnedError` `Choose up or down` and `move` never called; `/p-…/remove` → `Removed <title>` (title looked up before removal; unknown id → `No pinned link <id>`).
- `theSetupPageListsPins`: `pins.all()` → two pins → body contains `id="pinned"`, both titles, the URLs as text (escaped), `action="/setup/sources/pinned/p-…/move"`, a disabled "Move up" for the first and a disabled "Move down" for the last, `name="url"` with `type="url"`; with no pins → `No pinned links yet.`.

`sources/pinned/PinnedModuleSwitchTest.java` — `home-control.pinned.enabled=false` → no `PinnedShortcuts`, `PinnedContentSource`, `PinnedSetupController` beans; `/setup` renders without `id="pinned"`; `pinned.json` is never created in the data dir.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.pinned.*' --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.content.RailCacheTest'`
Expected: compilation failure — `ServiceLinks`, `ContentChangedEvent`, `parseHttpUrl`, `sources.pinned` do not exist.

- [ ] **Step 3: Core changes**

`core/content/ContentChangedEvent.java`:

```java
package dev.andre.homecontrol.core.content;

/** Published by a source whose rails changed because of a user action; the rail cache refreshes them now. */
public record ContentChangedEvent(String sourceId) {
}
```

`core/playback/ServiceLinks.java` (this task's part; Task 3 extends the same class):

```java
package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/** Service URL grammar shared by sources, the planner's descriptions and adapters. Pure; no I/O. */
public final class ServiceLinks {

    public static final String YOUTUBE = "youtube";
    public static final String NETFLIX = "netflix";
    public static final String PRIME_VIDEO = "primevideo";
    public static final String DAZN = "dazn";
    public static final String WEB = "web";

    private static final Map<String, String> NAMES = Map.of(
            YOUTUBE, "YouTube", NETFLIX, "Netflix", PRIME_VIDEO, "Prime Video", DAZN, "DAZN", "jellyfin", "Jellyfin");

    private ServiceLinks() {
    }

    public static Optional<String> displayName(String service) {
        return Optional.ofNullable(service == null ? null : NAMES.get(service));
    }

    /** The service's name, or the link's host for a plain web link. */
    public static String label(String service, URI uri) {
        return displayName(service).orElse(uri.getHost());
    }
}
```

`core/playback/AppLinks.java` — rename the private `parse` to `public static URI parseHttpUrl(String url)` (same body and messages; Javadoc "Validates a user-supplied link: http or https with a host."); `fromUrl` calls it.

`core/playback/Route.java` — delete `SERVICE_NAMES`; `describe()` becomes `ServiceLinks.displayName(service).map(name -> "Open in the " + name + " app").orElseGet(() -> "Open " + uri.getHost() + " on the device")`.

`content/RailCache.java` — add:

```java
    /** A source said its rails changed (e.g. a new pin): pick up new rails and refetch that source now. */
    @EventListener
    public void onContentChanged(ContentChangedEvent event) {
        reconcile();
        for (RailSnapshot snapshot : peek()) {
            if (snapshot.sourceId().equals(event.sourceId())) {
                refresh(snapshot.sourceId(), snapshot.railId());
            }
        }
    }
```

(If D's `reconcile()` already runs inside `peek()` in the real code, keep the explicit call anyway; it is idempotent.)

- [ ] **Step 4: The pinned module**

`src/main/resources/application.yaml` and `src/test/resources/application.yaml` — under `home-control:` add `pinned: { enabled: true, max-pins: 200 }` in block style.

`sources/pinned/PinnedProperties.java` — `@ConfigurationProperties("home-control.pinned") record PinnedProperties(@DefaultValue("true") boolean enabled, @DefaultValue("200") int maxPins)`.

`sources/pinned/Pin.java` — the record; compact constructor requires non-null `id`, `url`, `title`, `kind`, `createdAt`.

`sources/pinned/JsonFilePinStore.java` — implement the read and write rules above with a `JsonMapper` (`SerializationFeature.INDENT_OUTPUT` or its Jackson 3 builder equivalent, as the device registry does), writing an `ObjectNode` root field by field in the documented order. The write mirrors `JsonFileDeviceRegistry.writeAll` (create parent directories, `Files.createTempFile(parent, "pinned", ".json")`, write, move with `ATOMIC_MOVE` + `REPLACE_EXISTING`, delete the temp file on failure, `StorageException("Could not write pinned shortcuts to <file>", e)`).

`sources/pinned/PinnedShortcuts.java` — implement the pin rules; loads lazily on first use and keeps the list in memory (the file has one writer).

`sources/pinned/PinnedContentSource.java` — implement per Interfaces; `rail("pinned")` = `new Rail(new RailDescriptor("pinned","pinned","Pinned"), pins.all().stream().map(PinnedContentSource::toItem).toList(), Instant.now())`.

`sources/pinned/PinnedSetupController.java` — `@Controller` with the module condition; handlers per Interfaces; messages `Pinned <title>`, `Renamed to <title>`, `Order saved`, `Removed <title>`; `IllegalArgumentException` → `pinnedError`; `StorageException` → `pinnedError` `Could not save pinned links: <message>` (logged at WARN).

`sources/pinned/PinnedSetupAdvice.java` — `@ControllerAdvice(assignableTypes = SetupController.class)`, module condition, `ObjectProvider<PinnedShortcuts>` and `ObjectProvider<PinnedProperties>`; `@ModelAttribute("pinned")` → view or null when unavailable.

`sources/pinned/PinnedConfiguration.java` — `@Configuration`, module condition, `@EnableConfigurationProperties(PinnedProperties.class)`; beans `JsonFilePinStore(androidTvProperties.dataDir().resolve("pinned.json"))`, `PinnedShortcuts(store, properties, events, Clock.systemUTC(), new SecureRandom())`, `PinnedContentSource(PinnedShortcuts)`.

`src/main/resources/templates/fragments/pinned-setup.html` — `<section id="pinned" th:fragment="section">`, heading `Pinned links`; flash messages; text `Pin a Netflix, Prime Video, YouTube or DAZN link (or any web link) to show it in the Pinned rail. Home Control opens the link in the app on the TV; it never loads the page itself.`; add form (`url` `type="url"` required `maxlength="2048"` `inputmode="url"` placeholder `https://www.netflix.com/title/80057281`; `title` optional `maxlength="120"`; button `Pin`); `No pinned links yet.` when empty; otherwise a `<ol class="pinned-list">` with, per pin: title, subtitle, the URL in a `<code>` (text only, never a link), a rename form (`title` + `Rename`), `Move up` / `Move down` forms (`direction`, buttons `disabled` at the edges, `aria-label` `Move <title> up`), and a `Remove` form. Buttons are ≥ 44 px high (existing setup styles).

In `setup.html`, after the TMDB block:

```html
    <th:block th:if="${pinned != null}">
        <section th:replace="~{fragments/pinned-setup :: section}"></section>
    </th:block>
```

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.pinned.*' --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.content.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL (existing `Route` description tests still pass: the texts did not change).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/content/ContentChangedEvent.java \
  src/main/java/dev/andre/homecontrol/core/playback/ServiceLinks.java src/main/java/dev/andre/homecontrol/core/playback/AppLinks.java \
  src/main/java/dev/andre/homecontrol/core/playback/Route.java src/main/java/dev/andre/homecontrol/content/RailCache.java \
  src/main/java/dev/andre/homecontrol/sources/pinned src/main/resources/templates/fragments/pinned-setup.html \
  src/main/resources/templates/setup.html src/main/resources/application.yaml src/test/resources/application.yaml \
  src/test/java/dev/andre/homecontrol/core/playback src/test/java/dev/andre/homecontrol/content/RailCacheTest.java \
  src/test/java/dev/andre/homecontrol/sources/pinned src/test/resources/fixtures/pinned
git commit -m "feat: pinned shortcuts with a Pinned rail and setup management"
```

---

### Task 3: G3 · Netflix and Prime app-link builders

**Files:**
- Modify: `core/playback/ServiceLinks.java`, `core/playback/AppLinks.java`, `core/playback/Route.java`, `adapters/links/ContentLinks.java`, `sources/pinned/PinnedContentSource.java`, `sources/pinned/PinnedShortcuts.java`
- Test: `core/playback/ServiceLinksTest.java`, `core/playback/AppLinksTest.java`, `core/playback/RouteTest.java` (create if absent; otherwise add to the test class that pins `describe()` texts), `core/playback/PlaybackPlannerTest.java`, `adapters/links/ContentLinksTest.java`, `adapters/webos/WebOsLaunchesTest.java`, `adapters/tizen/TizenLaunchesTest.java`, the Android TV app-link test from A5 (find it with `grep -rl nextAppLink src/test/java`), `sources/pinned/PinnedContentSourceTest.java`, `sources/pinned/PinnedShortcutsTest.java`

**Interfaces:**
- Consumes: `ServiceLinks` names (Task 2); `AppLinks.serviceOf/parseHttpUrl` (A/Task 2); F's `ContentLinks.netflixTitleId(URI)`, `WebOsLaunches.forUri(URI)` → `WebOsLaunch(ssapUri, payload)`, `SsapMessages.JSON`, `TizenLaunches.forUri(URI, Optional<List<TizenApp>>)` → `TizenLaunch.App/Dial/Unsupported`; A's `FakeRemoteServer.nextAppLink()`; `PlaybackPlanner`, `AppLinkStrategy` (A).
- Produces (all in `ServiceLinks`, pure):
  - `static Optional<String> netflixTitleId(URI uri)` — digits (1–12) from `/title/{id}` or `/watch/{id}` with an optional `/xx` or `/xx-yy` locale prefix, on `netflix.com` or a subdomain.
  - `static Optional<String> primeVideoGti(URI uri)` — `amzn1.dv.gti.<uuid>` (lower-cased) from the `gti` query parameter or anywhere in the path, on `primevideo.com` or a subdomain.
  - `static URI netflixTitle(String titleId)` → `https://www.netflix.com/title/{id}`; `IllegalArgumentException` unless `^[0-9]{1,12}$`.
  - `static URI primeVideoDetail(String gti)` → `https://app.primevideo.com/detail?gti={gti lower-cased}`; `IllegalArgumentException` unless a full GTI.
  - `static Optional<URI> appHome(String service)` — Netflix `https://www.netflix.com/browse`, Prime Video `https://app.primevideo.com/`, DAZN `https://www.dazn.com/`; others empty.
  - `static boolean isAppHome(URI uri)`.
  - `static URI canonical(URI uri)` — per the canonical-link rules below.
  - `static PlayableRef.AppLink appLink(URI uri)` — `canonical(uri)` with `AppLinks.serviceOf` of the canonical URI.
  - `AppLinks.fromUrl` builds its playable with `ServiceLinks.appLink` (item id stays `link:` + the pasted URI; title stays the pasted host).
  - `Route.OpenAppLink.describe()` for an app-home URI: `Open the <Name> app (not this title)`.
  - `ContentLinks.netflixTitleId(URI)` delegates to `ServiceLinks.netflixTitleId` (one grammar).
  - Pins: `PinnedContentSource.toItem` uses `ServiceLinks.appLink(pin.url())`; `PinnedShortcuts.add` stores the canonical URL and detects duplicates on it.

**Canonical-link rules (normative).** Host comparison is case-insensitive; "under D" means equal to D or ending with `.D`.
1. Service `netflix` (A's `serviceOf`) and `netflixTitleId` present → `https://www.netflix.com/title/<id>`.
2. Service `primevideo` and `primeVideoGti` present → `https://app.primevideo.com/detail?gti=<gti>`.
3. Host under `primevideo.com` and the path contains `/detail/<ID>` where `<ID>` matches `[0-9A-Za-z]{10,40}` followed by `/` or the end → `https://www.primevideo.com/detail/<ID>`.
4. Service `primevideo`, host not under `primevideo.com` (so an Amazon host), path matches `^/gp/video/detail/([0-9A-Z]{10})(/.*)?$` → `https://<host lower-cased>/gp/video/detail/<ASIN>`.
5. Anything else → the URI unchanged (same object or an equal URI; no fragment or query stripping).

GTI pattern: `amzn1\.dv\.gti\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`, case-insensitive. Netflix path pattern: `^(?:/[a-z]{2}(?:-[a-z]{2})?)?/(?:title|watch)/([0-9]{1,12})(?:/.*)?$`, case-insensitive.

**What each platform does with these links (verified by tests in this task, implemented by A and F):**

| Link | Android TV (Remote v2) | LG webOS (F) | Samsung Tizen (F) |
|---|---|---|---|
| `https://www.netflix.com/title/80057281` | app link, URI as is | `system.launcher/launch` `netflix` with ConnectSDK `contentId` for 80057281 | Netflix app, no title |
| `https://www.netflix.com/browse` (app home) | app link | `netflix` app | Netflix app |
| `https://app.primevideo.com/detail?gti=…` | app link | `amazon` app, no title | Prime Video app |
| `https://www.amazon.de/gp/video/detail/B0B8TJ4WQS` | app link | `amazon` app | Prime Video app |
| `https://app.primevideo.com/` (app home) | app link | `amazon` app | Prime Video app |
| `https://www.dazn.com/…` | app link | TV browser (F's default) | refused: web links (F) |

- [ ] **Step 1: Write the failing tests**

`core/playback/ServiceLinksTest.java` — add:
- `canonicalLinks` (`@CsvSource`, pasted → canonical):
  - `https://www.netflix.com/de/title/80057281?s=a&trkid=13747225` → `https://www.netflix.com/title/80057281`
  - `https://www.netflix.com/de-en/title/80057281` → `https://www.netflix.com/title/80057281`
  - `https://www.netflix.com/watch/80057281?trackId=1` → `https://www.netflix.com/title/80057281`
  - `https://netflix.com/title/80057281` → `https://www.netflix.com/title/80057281`
  - `https://www.primevideo.com/region/eu/detail/amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6/ref=atv_dp_share_cu_r` → `https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6`
  - `https://app.primevideo.com/detail?gti=amzn1.dv.gti.8EB3C4A1-1B2C-4D5E-9F60-718293A4B5C6&ref_=x` → `https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6`
  - `https://www.primevideo.com/-/de/detail/0HAQAA7JM43QWX0H6GUD3IOF70/ref=atv_sr` → `https://www.primevideo.com/detail/0HAQAA7JM43QWX0H6GUD3IOF70`
  - `https://www.amazon.de/gp/video/detail/B0B8TJ4WQS/ref=atv_dp?language=de` → `https://www.amazon.de/gp/video/detail/B0B8TJ4WQS`
- `keepsEverythingElseAsPasted` (`@ValueSource`): `https://www.netflix.com/browse/genre/83`, `https://app.primevideo.com/detail?gti=amzn1.dv.gti.1`, `https://www.amazon.de/gp/video/detail/B08XYZ`, `https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=30`, `https://youtu.be/aqz-KE-bpKQ`, `https://www.dazn.com/de-DE/fixture/ContentId:abc`, `https://example.org/a?b=c#d`, `https://www.notnetflix.com/title/1` → `canonical(uri)` equals `uri`.
- `buildsTitleLinksFromIds`: `netflixTitle("80057281")` → `https://www.netflix.com/title/80057281`; `primeVideoDetail("AMZN1.DV.GTI.8EB3C4A1-1B2C-4D5E-9F60-718293A4B5C6")` → lower-cased app link; `netflixTitle("80057281&x=1")`, `netflixTitle("")`, `primeVideoDetail("amzn1.dv.gti.1")` → `IllegalArgumentException`.
- `extractsIds`: `netflixTitleId` on the Netflix cases above → `80057281`; on `https://example.org/title/80057281` → empty; `primeVideoGti` on `https://www.amazon.de/gp/video/detail/B0B8TJ4WQS` → empty.
- `appHomes`: `appHome("netflix")` → `https://www.netflix.com/browse`; `primevideo` → `https://app.primevideo.com/`; `dazn` → `https://www.dazn.com/`; `youtube`, `disneyplus`, `web` → empty; `isAppHome` true exactly for those three URIs (and false for `https://www.netflix.com/title/80057281` and `https://www.netflix.com/browse?x=1`).
- `appLinkCarriesTheServiceOfTheCanonicalLink`: `appLink(https://www.netflix.com/de/title/80057281?s=a)` → `AppLink(https://www.netflix.com/title/80057281, "netflix")`; `appLink(https://www.amazon.de/gp/video/detail/B0B8TJ4WQS/ref=x)` → service `primevideo`.

`core/playback/AppLinksTest.java` — the existing parameterized test keeps passing (its URLs are all canonical or unchanged); add `pastedLinksAreCanonicalised`: `fromUrl("https://www.netflix.com/de/title/80057281?s=a")` → playable `AppLink(https://www.netflix.com/title/80057281, netflix)`, title `www.netflix.com`, id `link:https://www.netflix.com/de/title/80057281?s=a`.

`core/playback/RouteTest.java`:
- `titleLinksOpenInTheApp`: `new Route.OpenAppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix").describe()` → `Open in the Netflix app`.
- `appHomeLinksSayTheyDoNotOpenTheTitle` (parameterized): Netflix home → `Open the Netflix app (not this title)`; Prime home → `Open the Prime Video app (not this title)`; DAZN home → `Open the DAZN app (not this title)`.
- `webLinksNameTheHost`: `https://example.org/a`, `web` → `Open example.org on the device`.

`core/playback/PlaybackPlannerTest.java` — add `serviceLinksRouteByCapabilityNotBrand`: an item with `ServiceLinks.appLink(netflix title)` → with `EnumSet.of(APP_LINK)` → `Route.OpenAppLink` with the canonical URI; with `EnumSet.of(CAST_RECEIVER, MEDIA_RENDERER, REMOTE_KEYS)` → `Unroutable` containing `cannot open app links`; the same for the Prime app home.

`adapters/links/ContentLinksTest.java` — unchanged cases must pass after delegation; add `agreesWithServiceLinks`: for each Netflix case, `ContentLinks.netflixTitleId(u)` equals `ServiceLinks.netflixTitleId(u)`.

`adapters/webos/WebOsLaunchesTest.java` — add `serviceLinkBuildersLaunchPerPlatform` (compare `ssapUri` and serialized payload exactly):
- `forUri(ServiceLinks.netflixTitle("80057281"))` → `ssap://system.launcher/launch`, `{"id":"netflix","contentId":"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4","params":{"contentId":"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4"}}`.
- `forUri(ServiceLinks.canonical(URI.create("https://www.netflix.com/de/watch/80057281?trackId=1")))` → the same payload.
- `forUri(ServiceLinks.appHome("netflix").orElseThrow())` → `{"id":"netflix"}`.
- `forUri(ServiceLinks.primeVideoDetail("amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6"))`, `forUri(URI.create("https://www.amazon.de/gp/video/detail/B0B8TJ4WQS"))`, `forUri(ServiceLinks.appHome("primevideo").orElseThrow())` → `{"id":"amazon"}`.

`adapters/tizen/TizenLaunchesTest.java` — add `serviceLinkBuildersOpenAppsOnly` with `Optional.empty()` installed list:
- Netflix title link and Netflix app home → `App("3201907018807", "Netflix", "DEEP_LINK")`; `toString()` of the result does not contain `80057281`.
- Prime detail link, Amazon ASIN link and Prime app home → `App("3201910019365", "Prime Video", "DEEP_LINK")`.
- DAZN app home → `Unsupported` containing `cannot open web links`.

Android TV (in the class from A5 that asserts `server.nextAppLink()`): add `sendsCanonicalServiceLinksVerbatim`: executing `new Action.OpenAppLink(ServiceLinks.primeVideoDetail("amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6"))` → `nextAppLink()` equals `https://app.primevideo.com/detail?gti=amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6`; `netflixTitle("80057281")` → `https://www.netflix.com/title/80057281`.

`sources/pinned/PinnedContentSourceTest.java` — add `pinsCarryCanonicalLinks`: a pin whose stored URL is `https://www.netflix.com/de/title/80057281?s=a` (written straight into the store) → item playable `AppLink(https://www.netflix.com/title/80057281, netflix)`.

`sources/pinned/PinnedShortcutsTest.java` — add `storesCanonicalLinksAndSpotsDuplicatesAcrossForms`: `add("https://www.netflix.com/de/title/80057281?s=a", "")` → stored URL `https://www.netflix.com/title/80057281`; `add("https://www.netflix.com/watch/80057281", "")` → `That link is already pinned`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.adapters.links.*' --tests 'dev.andre.homecontrol.adapters.webos.WebOsLaunchesTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenLaunchesTest' --tests 'dev.andre.homecontrol.sources.pinned.*'`
Expected: compilation failure — `canonical`, `appHome`, `netflixTitle`, … do not exist.

- [ ] **Step 3: Implement the builders**

Add to `core/playback/ServiceLinks.java`:

```java
    private static final Pattern NETFLIX_PATH = Pattern.compile(
            "^(?:/[a-z]{2}(?:-[a-z]{2})?)?/(?:title|watch)/([0-9]{1,12})(?:/.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NETFLIX_ID = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern GTI = Pattern.compile(
            "amzn1\\.dv\\.gti\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIME_DETAIL = Pattern.compile("/detail/([0-9A-Za-z]{10,40})(?:/|$)");
    private static final Pattern AMAZON_DETAIL = Pattern.compile("^/gp/video/detail/([0-9A-Z]{10})(?:/.*)?$");

    /** Opens the service's app without a title. Which URL each Android TV app claims is on the acceptance checklist. */
    private static final Map<String, URI> APP_HOMES = Map.of(
            NETFLIX, URI.create("https://www.netflix.com/browse"),
            PRIME_VIDEO, URI.create("https://app.primevideo.com/"),
            DAZN, URI.create("https://www.dazn.com/"));

    public static Optional<URI> appHome(String service) {
        return Optional.ofNullable(service == null ? null : APP_HOMES.get(service));
    }

    public static boolean isAppHome(URI uri) {
        return uri != null && APP_HOMES.containsValue(uri);
    }

    public static URI netflixTitle(String titleId) {
        if (titleId == null || !NETFLIX_ID.matcher(titleId).matches()) {
            throw new IllegalArgumentException("Not a Netflix title id: " + titleId);
        }
        return URI.create("https://www.netflix.com/title/" + titleId);
    }

    public static URI primeVideoDetail(String gti) {
        if (gti == null || !GTI.matcher(gti).matches()) {
            throw new IllegalArgumentException("Not a Prime Video GTI");
        }
        return URI.create("https://app.primevideo.com/detail?gti=" + gti.toLowerCase(Locale.ROOT));
    }

    public static Optional<String> netflixTitleId(URI uri) {
        if (!isOrUnder(host(uri), "netflix.com")) {
            return Optional.empty();
        }
        Matcher matcher = NETFLIX_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static Optional<String> primeVideoGti(URI uri) {
        if (!isOrUnder(host(uri), "primevideo.com")) {
            return Optional.empty();
        }
        String query = uri.getRawQuery() == null ? "" : URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        for (String candidate : List.of(query, uri.getPath() == null ? "" : uri.getPath())) {
            Matcher matcher = GTI.matcher(candidate);
            if (matcher.find()) {
                return Optional.of(matcher.group().toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    public static URI canonical(URI uri) {
        String host = host(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String service = AppLinks.serviceOf(host, path);
        if (service.equals(NETFLIX)) {
            return netflixTitleId(uri).map(ServiceLinks::netflixTitle).orElse(uri);
        }
        if (!service.equals(PRIME_VIDEO)) {
            return uri;
        }
        Optional<String> gti = primeVideoGti(uri);
        if (gti.isPresent()) {
            return primeVideoDetail(gti.get());
        }
        if (isOrUnder(host, "primevideo.com")) {
            Matcher detail = PRIME_DETAIL.matcher(path);
            return detail.find() ? URI.create("https://www.primevideo.com/detail/" + detail.group(1)) : uri;
        }
        Matcher asin = AMAZON_DETAIL.matcher(path);
        return asin.matches() ? URI.create("https://" + host + "/gp/video/detail/" + asin.group(1)) : uri;
    }

    public static PlayableRef.AppLink appLink(URI uri) {
        URI link = canonical(uri);
        return new PlayableRef.AppLink(link, AppLinks.serviceOf(host(link), link.getPath()));
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isOrUnder(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
```

(Imports: `java.net.URLDecoder`, `java.nio.charset.StandardCharsets`, `java.util.List`, `java.util.Locale`, `java.util.regex.Matcher`, `java.util.regex.Pattern`. The GTI query search over the whole decoded query matches `gti=…` and a GTI inside another parameter alike, which is intended.)

`core/playback/AppLinks.java` — `fromUrl` builds `List.of(ServiceLinks.appLink(uri))`.

`core/playback/Route.java` — `describe()`:

```java
        @Override
        public String describe() {
            Optional<String> name = ServiceLinks.displayName(service);
            if (ServiceLinks.isAppHome(uri)) {
                return "Open the " + name.orElse(uri.getHost()) + " app (not this title)";
            }
            return name.map(n -> "Open in the " + n + " app").orElseGet(() -> "Open " + uri.getHost() + " on the device");
        }
```

`adapters/links/ContentLinks.java` — `netflixTitleId(URI uri)` returns `ServiceLinks.netflixTitleId(uri)`; delete the now-unused `NETFLIX_PATH`. (If F's grammar in the real code accepts something `ServiceLinks` does not — e.g. a longer id — widen `ServiceLinks` and add that case to both tests.)

Pinned: `PinnedContentSource.toItem` → `ServiceLinks.appLink(pin.url())`; `PinnedShortcuts.add` computes `URI link = ServiceLinks.canonical(AppLinks.parseHttpUrl(url))`, stores `link`, compares duplicates on `link`, and derives `service` from `link`.

- [ ] **Step 4: Run the tests, then the build**

Run: the Step 2 command, then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. Every existing F test (`WebOsSessionTest`, `TizenSessionTest`, end-to-end tests) stays green: F's handles see the same service keys.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/playback src/main/java/dev/andre/homecontrol/adapters/links/ContentLinks.java \
  src/main/java/dev/andre/homecontrol/sources/pinned src/test/java/dev/andre/homecontrol/core/playback \
  src/test/java/dev/andre/homecontrol/adapters/links src/test/java/dev/andre/homecontrol/adapters/webos/WebOsLaunchesTest.java \
  src/test/java/dev/andre/homecontrol/adapters/tizen/TizenLaunchesTest.java src/test/java/dev/andre/homecontrol/sources/pinned \
  <the Android TV app-link test file>
git commit -m "feat: canonical Netflix and Prime Video links with app-home launches"
```

---
### Task 4: G4 · Provider-aware trending rail

**Files:**
- Create: `core/content/PinnedLinks.java`, `core/content/PinOffers.java`, `sources/tmdb/ProviderMatcher.java`, `sources/tmdb/TmdbPreferencesListener.java`, `sources/pinned/PinUpgradeController.java`, `web/PinOfferView.java`
- Modify: `sources/tmdb/TmdbContentSource.java`, `sources/tmdb/TmdbConfiguration.java`, `sources/pinned/PinnedShortcuts.java`, `sources/pinned/PinnedConfiguration.java`, `web/ContentPlayController.java`, `web/RoutePreviewView.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/js/play-sheet.js`, `src/main/resources/static/app.css`
- Test: `core/content/PinOffersTest.java`, `sources/tmdb/ProviderMatcherTest.java`, `sources/tmdb/TmdbTrendingRailTest.java`, `sources/tmdb/TmdbContentSourceTest.java`, `sources/pinned/PinnedShortcutsTest.java`, `sources/pinned/PinUpgradeControllerTest.java`, `web/ContentPlayPreviewTest.java`, `web/StaticAssetsTest.java`, `web/DashboardPageTest.java`

**Interfaces:**
- Consumes: Task 1 (`TmdbContentSource`, `TmdbClient`, `TmdbImages`, `TmdbWatchProviders`, `WatchProvider`, `TmdbItemMapper`, `TmdbMediaRef`, `FakeTmdbServer`, fixtures); Task 2 (`PinnedShortcuts`, `Pin`, `ContentChangedEvent`); Task 3 (`ServiceLinks.appHome/isAppHome/appLink/displayName/canonical`); C (`ContentSources.find`, `ContentSource.item`); D3 (`ContentPlayController.preview`, `RoutePreviewView.of(PlaybackPreview)`, `play-sheet.js` `preview()`/`openPlaySheet`, `toast`); D4 (`SourcePreferences.providers()/region()/locale()`, `StreamingProviders.KNOWN`, `SourcePreferencesChangedEvent`).
- Produces:
  - `interface PinnedLinks { Optional<PlayableRef.AppLink> linkFor(String sourceId, String itemId); }` in `core/content`; implemented by `PinnedShortcuts`.
  - `final class PinOffers { static Optional<Offer> offer(ContentItem item); record Offer(String upgradeOf, String service) {} }` in `core/content` — rule below.
  - `record PinOfferView(String upgradeOf, String service, String serviceName)` in `web`; `RoutePreviewView` gains a last component `PinOfferView pin` and `static RoutePreviewView of(PlaybackPreview preview, PinOfferView pin)` (the one-argument `of` stays, passing null).
  - `ContentPlayController` takes an extra `ObjectProvider<PinnedLinks>`; `preview` adds the offer when a `PinnedLinks` bean exists.
  - `final class ProviderMatcher { ProviderMatcher(Map<String, List<Integer>> providerIds); Optional<String> keyOf(WatchProvider provider); List<String> matches(List<WatchProvider> providers, List<String> configuredKeys); }`.
  - `TmdbContentSource` constructor gains `ProviderMatcher matcher, ObjectProvider<PinnedLinks> pinnedLinks` (last); rail `trending` "Trending on your services"; items carry playables per the playable rule.
  - `class TmdbPreferencesListener { @EventListener void onPreferencesChanged(SourcePreferencesChangedEvent) }` → publishes `ContentChangedEvent("tmdb")`.
  - `PinnedShortcuts.addUpgrade(String url, String upgradeOf) → Pin` and `PinnedShortcuts implements PinnedLinks`; constructor gains `ObjectProvider<ContentSources>` (last).
  - `POST /setup/sources/pinned/upgrade` (`url`, `upgradeOf`) → `200 application/json {"id","title","message"}` / `400 application/json {"message"}`.
  - Play-sheet markup `<form id="sheet-pin">` and its behaviour.

**Provider name rules (normative).** `normalise(name)` = lower case (ROOT), `+` → `plus`, then remove every character outside `[a-z0-9]`. A provider whose normalised name contains `channel` never matches by name. Key → name rule: `netflix` starts with `netflix`; `primevideo` starts with `amazonprimevideo`; `dazn` starts with `dazn`; `disneyplus` starts with `disneyplus`; `appletvplus` starts with `appletvplus`; `paramountplus` starts with `paramountplus`; `wowtv` equals `wow` or starts with `wowtv`; `joyn` starts with `joyn`; `rtlplus` starts with `rtlplus`. `keyOf(provider)` = the first key (in `StreamingProviders.KNOWN` order) whose configured ids contain `provider.id()`, else the first key whose name rule matches, else empty. `matches(providers, configured)` = the configured keys, in configured order, for which some provider with `category().subscription()` has that key.

**Trending rail (normative).** `rails()` = `[RailDescriptor("tmdb", "trending", "Trending on your services")]` while `available()`. `rail("trending")`:
1. Credential absent → `ContentSourceException("TMDB is not connected")`. `preferences.providers()` empty → `ContentSourceException("Choose your streaming services in Setup to see what is trending on them")`.
2. For page = 1, 2, …: `GET /trending/all/week` (`language`, `page`); collect results that map to a `TmdbMediaRef` and are not `adult`, de-duplicated by item id, until `trendingCandidates` candidates are collected or `page ≥ total_pages` or a page has no results.
3. For each candidate in order: `providers(credential, ref, region)`; a `TmdbException` for one candidate skips it (count it); `keys = matcher.matches(providers, preferences.providers())`; empty → skip. Map with prefix `On ` + the keys' display names (`StreamingProviders.KNOWN`) joined with `, ` and the playables below. Stop at `railSize` items.
4. If every candidate's lookup failed and at least one was attempted, throw the first `TmdbException`.
5. `Rail(descriptor, items, clock.instant())`.

**Playable rule (normative), used by the trending rail and by `item(itemId)`.** `pinned = pinnedLinks.getIfAvailable()?.linkFor("tmdb", itemId)`; present → `[pinned link]`. Otherwise the first key in `keys` with `ServiceLinks.appHome(key)` present → `[AppLink(appHome, key)]`. Otherwise `[]`. `item(itemId)` computes `keys` from the appended `watch/providers` for the preference region and configured providers, and — when `keys` is empty — uses subtitle prefix null (plain `Movie · 1999`); when non-empty the prefix is `On <names>`. Search results stay without playables and prefix.

**Pin offer rule (normative).** `PinOffers.offer(item)`: empty when `item.sourceId()` is `pinned` or `manual`; empty when any playable is not a `PlayableRef.AppLink`, or is an `AppLink` whose URI is not an app home (`ServiceLinks.isAppHome`); otherwise `Offer(item.sourceId() + "/" + item.id(), service of the first app-home link or null)`.

**Route-preview JSON addition (normative).** D3's object gains `pin` as its last field: `"pin": {"upgradeOf": "tmdb/tv-66732", "service": "netflix", "serviceName": "Netflix"}`; `service` and `serviceName` are null when the item has no app-home link; `"pin": null` when there is no offer or the pinned module is off. The offer is computed from the item as the source returned it (before resolvers), whatever the device.

**Pin upgrade (normative).** `addUpgrade(url, upgradeOf)`: `upgradeOf` must match `^[a-z0-9][a-z0-9._-]{0,63}/[A-Za-z0-9._:-]{1,128}$` → else `That item cannot be pinned`; source = `ContentSources.find(sourceId)` (absent → `That item is no longer available`); `item = source.item(itemId)` (empty → `That item is no longer available`; `ContentSourceException` → its message); `PinOffers.offer(item)` empty → `This item already opens directly`; URL rules as `add` (canonical link, length, scheme); an existing pin with the same `upgradeOf` is replaced in place (same id and position, new URL, `createdAt` unchanged); otherwise the URL must not already be pinned (`That link is already pinned`) and the pin is appended with title `item.title()`, subtitle `ServiceLinks.label(service, link)`, artwork `item.artwork()` when absolute `https://` or a single-slash path, kind `item.kind()`, `upgradeOf`. Publishes `ContentChangedEvent("pinned")` and `ContentChangedEvent(sourceId)`. `linkFor(sourceId, itemId)` = the pin with `upgradeOf` equal to `sourceId/itemId` mapped with `ServiceLinks.appLink(pin.url())`.

`POST /setup/sources/pinned/upgrade` → 200 `{"id":"p-3f9a1c2b7d4e","title":"Stranger Things","message":"Pinned Stranger Things. It now opens directly."}`; `IllegalArgumentException` → 400 `{"message":"<message>"}`; `StorageException` → 500 `{"message":"Could not save the pinned link"}`.

- [ ] **Step 1: Write the failing tests**

`core/content/PinOffersTest.java` (items built with the 8-argument `ContentItem`):
- `appHomeOnlyItemsOfferThePinWithTheirService`: source `tmdb`, id `tv-66732`, playables `[AppLink(netflix home, netflix)]` → `Offer("tmdb/tv-66732", "netflix")`.
- `itemsWithoutPlayablesOfferAPinWithoutService`: `[]` → `Offer("tmdb/movie-603", null)`.
- `titleLinksAndOtherRefsOfferNothing`: `[AppLink(netflix title)]`, `[JellyfinItem("s","i",0)]`, `[AppLink(netflix home), StreamUrl(…)]` → empty.
- `pinnedAndManualItemsOfferNothing`: source `pinned` / `manual` with an app-home link → empty.

`sources/tmdb/ProviderMatcherTest.java` (ids = `TmdbProperties.DEFAULT_PROVIDER_IDS`):
- `matchesByIdFirst`: `WatchProvider(8, "Anything", FLATRATE, 0)` → `netflix`; `(119, "x", …)` → `primevideo`; `(2100, …)` → `primevideo`.
- `matchesByNormalisedName` (parameterized name → key): `Netflix basic with Ads`→netflix, `Amazon Prime Video`→primevideo, `DAZN`→dazn, `Disney Plus`→disneyplus, `Apple TV Plus`→appletvplus, `Apple TV+`→appletvplus, `Paramount Plus`→paramountplus, `Paramount+`→paramountplus, `WOW`→wowtv, `Joyn Plus`→joyn, `RTL+`→rtlplus.
- `neverMatchesChannelsOrLookAlikes` (parameterized, each id 99999 → empty): `Paramount+ Amazon Channel`, `MGM Plus Amazon Channel`, `Amazon Video`, `Apple TV`, `Max`, `Sky Go`, `Wowow` (normalised `wowow`: neither `wow` nor `wowtv…`).
- `onlySubscriptionsCountAndConfiguredOrderWins`: providers from `providers-tv-76479.json` DE → `matches(…, ["netflix","primevideo"])` → `[primevideo]`; `providers-movie-603.json` → `matches(…, ["primevideo","wowtv"])` → `[wowtv]` (Amazon Video is BUY and not Prime anyway); `providers-movie-550.json` → `matches(…, ["primevideo"])` → `[]`; a list with Netflix FLATRATE and Prime ADS → `matches(…, ["primevideo","netflix"])` → `[primevideo, netflix]`.
- `configuredIdsReplaceTheDefaults`: `new ProviderMatcher(Map.of("joyn", List.of(304)))` → `(304, "Joyn", …)` → `joyn`; `(8, "Some name", …)` → empty.

`sources/tmdb/TmdbTrendingRailTest.java` (fake server standard responses; connected setup; mutable preferences supplier; `ObjectProvider<PinnedLinks>` backed by a map; `ProviderMatcher` with defaults):
- `listsOnlyTitlesOnTheHouseholdsServices`: providers `[netflix, primevideo]`, region `DE` → items `[tv-66732 Stranger Things "On Netflix · 2016", tv-76479 The Boys "On Prime Video · 2019"]`; Stranger Things playables `[AppLink(https://www.netflix.com/browse, netflix)]`; The Boys `[AppLink(https://app.primevideo.com/, primevideo)]`; artwork from the configuration base; trending requested with `language=de-DE`, `page=1`; no request for person 500.
- `providersWithoutLauncherHaveNoPlayables`: providers `[wowtv]` → `[movie-603 Matrix "On WOW · 1999"]` with no playables.
- `regionComesFromPreferences`: region `US`, providers `[netflix]` → `[Stranger Things]` only (The Boys has no US entry).
- `aPinnedLinkReplacesTheAppHome`: `linkFor("tmdb","tv-66732")` → `AppLink(https://www.netflix.com/title/80057281, netflix)` → Stranger Things playables are exactly that link.
- `respectsRailSizeAndCandidates`: `railSize` 1 → one item; `trendingCandidates` 2 → only the first two movie/series results (66732, 603) are looked up — `fake.count` for `/3/tv/76479/watch/providers` is 0.
- `pagesUntilTotalPages`: fixture `total_pages` 1 → exactly one trending request even with 40 candidates wanted.
- `withoutProvidersTheRailExplainsWhatToDo`: providers `[]` → `ContentSourceException` with the exact message; no request sent.
- `oneFailingLookupSkipsThatTitle`: `/3/tv/66732/watch/providers` 500 → rail still has The Boys.
- `allLookupsFailingFailsTheRail`: all provider routes 500 → `TmdbException` `TMDB had a server error (HTTP 500)`.
- `theRailTitleNeverClaimsAPersonalFeed`: `rails().getFirst().title()` equals `Trending on your services`; no item subtitle contains `Continue`, `For you`, `Recommended` or `watching`.
- `itemReadsCarryTheSamePlayables`: `item("tv-66732")` with providers `[netflix]` → subtitle `On Netflix · 2016`, playables `[netflix home]`; with providers `[primevideo]` → subtitle `Series · 2016`, playables `[]`; with the pin present → `[title link]`.

`sources/tmdb/TmdbContentSourceTest.java` — update `hasNoRailsYet…` to `offersTheTrendingRailWhenAvailable` (`rails()` = one descriptor; unavailable → empty; `rail("nope")` → `IllegalArgumentException`).

`sources/pinned/PinnedShortcutsTest.java` — add (stub `ContentSources` with a stub `tmdb` source whose `item("tv-66732")` returns `Stranger Things`, artwork `https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg`, kind `VIDEO`, playables `[netflix home]`; `item("movie-9")` returns an item with a title link):
- `upgradesAnItemWithItsMetadata`: `addUpgrade("https://www.netflix.com/de/title/80057281?s=a", "tmdb/tv-66732")` → title `Stranger Things`, subtitle `Netflix`, artwork copied, kind `VIDEO`, `upgradeOf` `tmdb/tv-66732`, URL canonical; events `ContentChangedEvent("pinned")` and `ContentChangedEvent("tmdb")`; `linkFor("tmdb","tv-66732")` → `AppLink(https://www.netflix.com/title/80057281, netflix)`; `linkFor("tmdb","tv-1")` empty.
- `upgradingAgainReplacesThePin`: second `addUpgrade` with a Prime link → same id and position, new URL, `all().size()` unchanged.
- `refusesWhatCannotBeUpgraded` (message): `tmdb/../x` → `That item cannot be pinned`; `nope/tv-1` (no source) and `tmdb/tv-404` (item empty) → `That item is no longer available`; `tmdb/movie-9` → `This item already opens directly`; a `javascript:` URL → A's scheme message; nothing written, no event.
- `artworkFromTheItemIsKeptOnlyWhenSafe`: item artwork `http://example.org/x.jpg` → pin artwork null.

`sources/pinned/PinUpgradeControllerTest.java` — `@WebMvcTest(PinUpgradeController.class)`, `@MockitoBean PinnedShortcuts pins`:
- `pinsAndAnswersJson`: POST `/setup/sources/pinned/upgrade` `url=…&upgradeOf=tmdb/tv-66732` → `verify(pins).addUpgrade(url, "tmdb/tv-66732")`; 200 JSON `$.id`, `$.title` `Stranger Things`, `$.message` `Pinned Stranger Things. It now opens directly.`
- `userErrorsAre400WithAMessage`: `IllegalArgumentException("This item already opens directly")` → 400 `$.message` equal.
- `missingParametersAre400`: no `upgradeOf` → 400.
- `storageFailuresAre500WithoutDetails`: `StorageException("disk /data/pinned.json full", …)` → 500 `$.message` `Could not save the pinned link`; body does not contain `/data`.

`web/ContentPlayPreviewTest.java` — add `@MockitoBean PinnedLinks pinnedLinks` in a nested class or a second test class `ContentPlayPinOfferTest` (so the existing class keeps running without the bean):
- `offersAPinForAnAppHomeItem`: source `tmdb` item `tv-66732` with `[netflix home]`, preview routes `[OpenAppLink(netflix home, netflix)]` → `$.route.description` `Open the Netflix app (not this title)`, `$.pin.upgradeOf` `tmdb/tv-66732`, `$.pin.service` `netflix`, `$.pin.serviceName` `Netflix`.
- `offersAPinWithoutServiceForAnUnplayableItem`: item without playables, preview routes empty with reason → `$.playable` false, `$.pin.service` null, `$.pin.serviceName` null.
- `noPinForTitleLinks`: item with a Netflix title link → `$.pin` null.
- In the existing class (no `PinnedLinks` bean): `noPinWithoutThePinnedModule`: app-home item → `$.pin` null; and the JSON still contains the key `pin` (`jsonPath("$.pin").value(nullValue())` with `exists()` semantics via `$.pin` present as null).

`web/DashboardPageTest.java` — `thePlaySheetHasAPinForm`: body contains `<form id="sheet-pin"`, `hidden`, `id="sheet-pin-url"`, `type="url"`, `Pin link`.

`web/StaticAssetsTest.java` — `/js/play-sheet.js` contains `sheet-pin` and `/setup/sources/pinned/upgrade`, and does not contain `not this title` (route descriptions come from the server).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.content.*' --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `PinnedLinks`, `PinOffers`, `ProviderMatcher`, `addUpgrade`, `PinOfferView` do not exist.

- [ ] **Step 3: Core SPI and offer rule**

`core/content/PinnedLinks.java`:

```java
package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.PlayableRef;

import java.util.Optional;

/**
 * A title link the user pasted for an item a source can only launch at the app level (spec §11:
 * "let the user pin a pasted URL to upgrade the item"). Sources put it in place of the app-home link.
 */
public interface PinnedLinks {
    Optional<PlayableRef.AppLink> linkFor(String sourceId, String itemId);
}
```

`core/content/PinOffers.java`:

```java
package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;

import java.util.Optional;
import java.util.Set;

/** When the play sheet offers "paste a link to open this title directly". */
public final class PinOffers {

    public record Offer(String upgradeOf, String service) {
    }

    private static final Set<String> NOT_UPGRADABLE = Set.of("pinned", "manual");

    private PinOffers() {
    }

    public static Optional<Offer> offer(ContentItem item) {
        if (NOT_UPGRADABLE.contains(item.sourceId())) {
            return Optional.empty();
        }
        String service = null;
        for (PlayableRef ref : item.playables()) {
            if (!(ref instanceof PlayableRef.AppLink link) || !ServiceLinks.isAppHome(link.uri())) {
                return Optional.empty();
            }
            if (service == null) {
                service = link.service();
            }
        }
        return Optional.of(new Offer(item.sourceId() + "/" + item.id(), service));
    }
}
```

- [ ] **Step 4: Provider matcher, trending rail and listener**

`sources/tmdb/ProviderMatcher.java` — implement the name rules with a `LinkedHashMap<String, Predicate<String>>` in `StreamingProviders.KNOWN` order (keys not in `KNOWN` are ignored); ids map copied to `Map<String, Set<Integer>>`.

`sources/tmdb/TmdbContentSource.java` — add `rails()`, `rail("trending")` and the playable rule exactly as specified; `Clock` for `fetchedAt` (constructor keeps `Clock.systemUTC()` internally or takes one — tests may pass a fixed clock through a package-private constructor). Display names for the prefix come from `StreamingProviders.KNOWN.get(key)`.

`sources/tmdb/TmdbPreferencesListener.java` — constructor `(ApplicationEventPublisher)`; `@EventListener onPreferencesChanged(SourcePreferencesChangedEvent)` → `publishEvent(new ContentChangedEvent("tmdb"))`. Bean in `TmdbConfiguration`, plus `ProviderMatcher(properties.providerIds())` and the extra `TmdbContentSource` constructor arguments (`ObjectProvider<PinnedLinks>` as a bean-method parameter).

- [ ] **Step 5: Pin upgrade**

`sources/pinned/PinnedShortcuts.java` — `implements PinnedLinks`; `addUpgrade` per the rule (the `source.item(...)` call runs **outside** the lock: resolve the item first, then lock, re-check, save); `linkFor` reads the in-memory list. `PinnedConfiguration` passes `ObjectProvider<ContentSources>`; declare the bean's type so it is injectable both as `PinnedShortcuts` and as `PinnedLinks` (a single `@Bean PinnedShortcuts` already is).

`sources/pinned/PinUpgradeController.java`:

```java
package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The play sheet's "paste a link to open this title directly". */
@RestController
@ConditionalOnProperty(name = "home-control.pinned.enabled", havingValue = "true", matchIfMissing = true)
public class PinUpgradeController {

    private static final Logger log = LoggerFactory.getLogger(PinUpgradeController.class);

    private final PinnedShortcuts pins;

    public PinUpgradeController(PinnedShortcuts pins) {
        this.pins = pins;
    }

    @PostMapping(path = "/setup/sources/pinned/upgrade", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> upgrade(@RequestParam String url, @RequestParam String upgradeOf) {
        try {
            Pin pin = pins.addUpgrade(url, upgradeOf);
            return ResponseEntity.ok(Map.of("id", pin.id(), "title", pin.title(),
                    "message", "Pinned " + pin.title() + ". It now opens directly."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (StorageException e) {
            log.warn("Could not save a pinned link", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not save the pinned link"));
        }
    }
}
```

(A missing parameter is Spring's 400; the test only checks the status.) A `ContentSourceException` thrown by `source.item` inside `addUpgrade` is converted to `IllegalArgumentException(e.getMessage())` there, so the sheet shows TMDB's user-facing message.

- [ ] **Step 6: Route preview and play sheet**

`web/PinOfferView.java`: `public record PinOfferView(String upgradeOf, String service, String serviceName) { static PinOfferView of(PinOffers.Offer offer) { … serviceName = offer.service() == null ? null : ServiceLinks.displayName(offer.service()).orElse(null) … } }`.

`web/RoutePreviewView.java` — add the component and the two-argument factory. `ContentPlayController.preview` — after finding the item: `PinOfferView pin = pinnedLinks.getIfAvailable() == null ? null : PinOffers.offer(content.get()).map(PinOfferView::of).orElse(null);` then `RoutePreviewView.of(playback.preview(content.get(), id), pin)`. Jackson writes the null `pin` field (default inclusion); if the project's mapper excludes nulls, annotate the component with `@JsonInclude(JsonInclude.Include.ALWAYS)`.

`dashboard.html` — inside `#play-sheet`, after `#sheet-play`:

```html
    <form id="sheet-pin" class="sheet-pin" hidden>
        <p id="sheet-pin-text" class="hint"></p>
        <label for="sheet-pin-url" class="visually-hidden">Link to this title</label>
        <input id="sheet-pin-url" name="url" type="url" inputmode="url" autocomplete="off" required maxlength="2048"
               placeholder="https://www.netflix.com/title/…">
        <button type="submit" id="sheet-pin-submit">Pin link</button>
        <p id="sheet-pin-error" class="error" role="alert" hidden></p>
    </form>
```

(If `.visually-hidden` does not exist in `app.css`, add the standard clip rule.) `app.css`: `.sheet-pin { display: grid; gap: .5rem; margin-top: 1rem; border-top: 1px solid var(--border, #333); padding-top: 1rem; }`, `#sheet-pin-url { min-height: 44px; }`, `#sheet-pin-submit { min-height: 44px; }`.

`static/js/play-sheet.js` — add (and call `showPin(data.pin)` in `preview()` right after the `seq` check for both the playable and the unplayable branch; call `showPin(null)` at the start of `preview()`):

```js
let pinOffer = null;

function showPin(offer) {
    const form = document.getElementById("sheet-pin");
    if (!form) return;
    pinOffer = offer;
    document.getElementById("sheet-pin-error").hidden = true;
    if (!offer) { form.hidden = true; return; }
    document.getElementById("sheet-pin-text").textContent = offer.serviceName
        ? `This opens the ${offer.serviceName} app, not the title. Paste the ${offer.serviceName} link for this title to open it directly.`
        : "Home Control cannot open this title on your services. Paste a link to it (Netflix, Prime Video, YouTube, DAZN or any web link) to pin it.";
    form.hidden = false;
}

async function submitPin(event) {
    event.preventDefault();
    if (!pinOffer) return;
    const input = document.getElementById("sheet-pin-url");
    const errorEl = document.getElementById("sheet-pin-error");
    const button = document.getElementById("sheet-pin-submit");
    button.disabled = true;
    try {
        const response = await fetch("/setup/sources/pinned/upgrade", {
            method: "POST",
            headers: { Accept: "application/json" },
            body: form({ url: input.value, upgradeOf: pinOffer.upgradeOf }),
        });
        const data = await readJsonOrText(response);
        if (!response.ok) {
            errorEl.textContent = data.message || "Could not pin this link";
            errorEl.hidden = false;
            return;
        }
        input.value = "";
        toast(data.message, { ok: true });
        preview();
    } catch {
        errorEl.textContent = "Cannot reach the server";
        errorEl.hidden = false;
    } finally {
        button.disabled = false;
    }
}
```

In `initPlaySheet()`: `document.getElementById("sheet-pin")?.addEventListener("submit", submitPin);`. The fetch is same-origin, so C's cross-origin guard passes and the login cookie is sent.

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/content src/main/java/dev/andre/homecontrol/sources \
  src/main/java/dev/andre/homecontrol/web/PinOfferView.java src/main/java/dev/andre/homecontrol/web/RoutePreviewView.java \
  src/main/java/dev/andre/homecontrol/web/ContentPlayController.java src/main/resources/templates/dashboard.html \
  src/main/resources/static/js/play-sheet.js src/main/resources/static/app.css \
  src/test/java/dev/andre/homecontrol/core/content src/test/java/dev/andre/homecontrol/sources src/test/java/dev/andre/homecontrol/web
git commit -m "feat: trending on your services rail with app launch and pin-to-upgrade"
```

---

### Task 5: G5 · Tests and acceptance

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbFixtureContractTest.java`, `src/test/java/dev/andre/homecontrol/web/StreamingLaunchersEndToEndTest.java`, `src/e2e/java/dev/andre/homecontrol/e2e/PinUpgradeE2eTest.java`, `docs/superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md`
- Modify: `src/e2e/java/dev/andre/homecontrol/e2e/FakeContentSource.java`, `src/e2e/java/dev/andre/homecontrol/e2e/E2eFakesConfiguration.java`, `README.md`
- Uses unchanged: `FakeTmdbServer` and every TMDB fixture (Task 1), A's `FakeRemoteServer`, `CertificateStore`, `AndroidTvSettings.device`, `DeviceManager.adopt/state`, C's login flow, D's endpoints (`/setup/sources/preferences/locale`, `/sources/{s}/rails/{r}` and `/refresh`, `/devices/{id}/route-preview`, `/devices/{id}/play-attempt`, `/search`), D7's browser harness.

**Interfaces:**
- Consumes: everything above; no production code changes except README.
- Produces: contract tests over every TMDB fixture; an end-to-end proof over real sockets that the Shield receives the exact Netflix and Prime Video links; a browser test for the pin prompt; the manual checklist; README documentation.

- [ ] **Step 1: Fixture contract test**

`TmdbFixtureContractTest.java`:
- `everyFixtureIsAJsonObject`: every `*.json` under `/fixtures/tmdb` parses to an object; at least 13 files.
- `listFixturesHaveTheDocumentedPagingShape` (`search-multi.json`, `trending-all-week.json`): integral `page`, `total_pages`, `total_results`; `results` array; every result has `id` and `media_type` ∈ {movie, tv, person}; movies have `title` or `original_title`, series `name` or `original_name`.
- `listFixturesProduceWellFormedItems`: every result mapped with `TmdbItemMapper` → id matches `^(movie|tv)-[1-9][0-9]*$`, source `tmdb`, non-blank title, subtitle starting with `Movie` or `Series`, artwork null or starting with `https://image.tmdb.org/t/p/w342/`, no playables.
- `providerFixturesHaveTheDocumentedShape`: every `providers-*.json` and the `watch/providers` object of both `details-*.json` → `results` object whose keys match `^[A-Z]{2}$`; every region has a `link` starting with `https://www.themoviedb.org/`; every provider entry has integral `provider_id` > 0, non-blank `provider_name`, `logo_path` starting with `/`, integral `display_priority`.
- `configurationFixtureHasSecureImages`: `images.secure_base_url` starts with `https://`, `poster_sizes` contains `w342`.
- `errorFixturesHaveStatusCodes`: `authentication-invalid.json` and `not-found.json` have integral `status_code`, `success` false.
- `noFixtureContainsACredential`: no fixture text contains `FakeTmdbServer.READ_TOKEN` or `API_KEY`.

- [ ] **Step 2: End-to-end test**

`src/test/java/dev/andre/homecontrol/web/StreamingLaunchersEndToEndTest.java` — structure copied from C's `JellyfinEndToEndTest` (same `send/get/page/post` helpers, a cookie-keeping `browser` client and a `stranger` client, every browser body recorded):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamingLaunchersEndToEndTest {

    static final String LOGIN = "household password";
    static final String GTI = "amzn1.dv.gti.8eb3c4a1-1b2c-4d5e-9f60-718293a4b5c6";
    static final FakeTmdbServer TMDB;
    static Path dataDir;

    static {
        try {
            TMDB = new FakeTmdbServer().withStandardResponses();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("streaming-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.tmdb.api-base-url", () -> TMDB.apiBase().toString());
    }

    @AfterAll
    static void stop() {
        TMDB.close();
    }
    // helpers as in JellyfinEndToEndTest; Accept: application/json on JSON calls
}
```

Test `launchTrendingTitlesAndUpgradeThemWithPinnedLinks` — steps and assertions, in order:
1. `certificates.loadOrCreate("shield-e2e")`; `devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()))` with `FakeRemoteServer shieldRemote` in try-with-resources; `await().until(() -> devices.state("shield-e2e").connected())`.
2. Before any secret: `GET /setup` from `stranger` → 200 (device-only deployments unchanged).
3. `POST /setup/sources/tmdb` `credential=FakeTmdbServer.READ_TOKEN`, `loginPassword=LOGIN`, `loginPasswordConfirmation=LOGIN` from `browser` → 302; `TMDB.last("GET", "/3/authentication").header("authorization")` = `Bearer ` + token; `GET /setup` from `browser` contains `Connected (read access token)` and `This product uses the TMDB API`; from `stranger` `GET /sources` → 401.
4. `POST /setup/sources/preferences/locale` `locale=de-DE`, `region=DE`, `providers=netflix`, `providers=primevideo` → 302.
5. `POST /sources/tmdb/rails/trending/refresh`; `await().atMost(10 s)` until `GET /sources/tmdb/rails/trending` is 200 with `"status":"READY"`; body contains `Stranger Things`, `On Netflix · 2016`, `The Boys`, `On Prime Video · 2019`, `https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg`; does not contain `Matrix`, `Fight Club`, `House of the Dragon`, `playables`, `AppLink`.
6. `GET /devices/shield-e2e/route-preview?source=tmdb&item=tv-66732` → `$.route.description` `Open the Netflix app (not this title)`, `$.pin.upgradeOf` `tmdb/tv-66732`, `$.pin.serviceName` `Netflix` (parse with `JsonMapper`).
7. `POST /devices/shield-e2e/play-attempt` `source=tmdb`, `item=tv-66732` → 200, `played` true; `shieldRemote.nextAppLink()` = `https://www.netflix.com/browse`.
8. `POST /setup/sources/pinned/upgrade` `url=https://www.netflix.com/de/title/80057281?s=a&trkid=13747225`, `upgradeOf=tmdb/tv-66732` → 200, `$.title` `Stranger Things`.
9. Preview again → `$.route.description` `Open in the Netflix app`, `$.pin` null; play-attempt → `nextAppLink()` = `https://www.netflix.com/title/80057281`.
10. `POST /setup/sources/pinned` `url=https://www.primevideo.com/region/eu/detail/` + GTI + `/ref=atv_dp_share_cu_r`, `title=The Boys` → 302. Await `GET /sources/pinned/rails/pinned` READY containing `Stranger Things` and `The Boys` (the `ContentChangedEvent` refreshed it without a manual refresh); read the Prime pin's id from the JSON items (title `The Boys`).
11. `POST /devices/shield-e2e/play-attempt` `source=pinned`, `item=<id>` → 200; `nextAppLink()` = `https://app.primevideo.com/detail?gti=` + GTI.
12. Ad-hoc open-link form (A): `POST /devices/shield-e2e/play` `uri=https://www.amazon.de/gp/video/detail/B0B8TJ4WQS/ref=atv_dp` → 200 `Open in the Prime Video app`; `nextAppLink()` = `https://www.amazon.de/gp/video/detail/B0B8TJ4WQS`.
13. `GET /search?q=matrix` → contains `Matrix` from source `tmdb` (and TMDB's `/3/search/multi` was called with `language=de-DE`).
14. `pinned.json` in `dataDir` parses; it has two pins; the Stranger Things pin has `upgradeOf` `tmdb/tv-66732` and URL `https://www.netflix.com/title/80057281`; `secrets.json` does not contain the token in plain text.
15. No recorded browser body contains `FakeTmdbServer.READ_TOKEN`, `api_key` or `Bearer`.
16. `POST /setup/sources/tmdb/disconnect` → 302; `GET /sources` (browser) lists `tmdb` with `"available":false`; the pinned rail still answers.

A second test method `reconnectingWithAnApiKeyUsesTheQueryParameter` (class annotated `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)`; the first test `@Order(1)`, this one `@Order(2)`, sharing the logged-in `browser` as a static field): after step 16, `POST /setup/sources/tmdb` `credential=FakeTmdbServer.API_KEY` → 302; the latest `/3/authentication` request has `api_key` in its query and no `authorization` header; `GET /sources` lists `tmdb` as available again.

- [ ] **Step 3: Browser test for the pin prompt**

`FakeContentSource.java` (D7) — add a constructor parameter `ObjectProvider<PinnedLinks> pinnedLinks` (update `E2eFakesConfiguration` to pass it) and an item `launcher-1` "Launcher Film", subtitle `On Netflix`, kind `MOVIE`, playables computed on each `item`/`search` call: `pinnedLinks.getIfAvailable()?.linkFor("e2e","launcher-1")` present → `[that link]`, else `[AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix")]`. It is in the item map and in search results but in no rail (D7's rail expectations stay unchanged).

`PinUpgradeE2eTest.java` — `@BrowserTest` on D7's base class, phone viewport; with the fake device `Living Room` that has `APP_LINK`:
- `pinningALinkUpgradesTheSheet`: open `/`; type `Launcher` into the search box; click the `Launcher Film` tile; `#sheet-route` has text `Play on Living Room · Open the Netflix app (not this title)`; `#sheet-pin` is visible and `#sheet-pin-text` contains `Paste the Netflix link for this title`; fill `#sheet-pin-url` with `https://www.netflix.com/de/title/80057281?s=a`; click `Pin link`; a toast contains `Pinned Launcher Film. It now opens directly.`; `#sheet-route` becomes `Play on Living Room · Open in the Netflix app`; `#sheet-pin` is hidden; closing the sheet shows a rail titled `Pinned` containing `Launcher Film`.
- `aBadLinkShowsAnInlineError`: same start; bypass the browser's URL validation with `page.evaluate("document.getElementById('sheet-pin-url').type='text'")`, fill `ftp://example.org/x`, submit → `#sheet-pin-error` visible with `Only http and https links can be opened on a device`; the sheet stays open.

Clean up created pins between tests by deleting `pinned.json` in the test data dir and publishing nothing (each test re-opens the page), or use a fresh data dir per class as D7's base does.

- [ ] **Step 4: Manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md`:

```markdown
# Streaming launchers — acceptance record (2026-09-16)

Plan G "Streaming launchers", Task 5. No agent running this plan has access to a real NVIDIA Shield, LG or
Samsung TV, a TMDB account or streaming subscriptions, so every manual check below is recorded as pending for
a human with hardware. None is claimed as passed. Automated coverage: `StreamingLaunchersEndToEndTest`
(fake TMDB, fake Shield, exact app-link strings), `PinUpgradeE2eTest` (play sheet), unit and fixture tests.

## TMDB

1. Connect a real TMDB API Read Access Token: setup says "Connected (read access token)"; login is now required.
   **Pending — requires real hardware.**
2. Disconnect and connect a v3 API key instead: "Test connection" succeeds.
   **Pending — requires real hardware.**
3. With locale de-DE, region DE and providers Netflix + Prime Video, "Trending on your services" lists only
   titles TMDB shows as streaming on those services in Germany; posters load on a phone over the plain-HTTP
   dashboard.
   **Pending — requires real hardware.**
4. Provider ids: in the real `/watch/providers` data for DE, Netflix with Ads and Prime Video with Ads titles are
   matched (ids 1796 / 2100 or by name); no rent/buy-only title appears.
   **Pending — requires real hardware.**
5. Search "matrix" shows TMDB results next to other sources.
   **Pending — requires real hardware.**

## Shield (Android TV app links)

6. Netflix title link `https://www.netflix.com/title/<id>` (pinned from a real Netflix URL) opens that title's page
   in the Netflix app.
   **Pending — requires real hardware.**
7. "Open Netflix" (`https://www.netflix.com/browse`) from a trending item opens the Netflix app home.
   **Pending — requires real hardware.**
8. Prime Video `https://app.primevideo.com/detail?gti=…` (pinned from a primevideo.com share link) opens the title
   in the Prime Video app.
   **Pending — requires real hardware.**
9. Prime Video `https://www.amazon.de/gp/video/detail/<ASIN>` opens the title in the Prime Video app.
   **Pending — requires real hardware.**
10. Prime Video `https://www.primevideo.com/detail/<ID>` (no GTI) — record whether the app opens the title, the
    app home, or nothing.
    **Pending — requires real hardware.**
11. "Open Prime Video" (`https://app.primevideo.com/`) opens the Prime Video app.
    **Pending — requires real hardware.**
12. A pinned DAZN link opens the DAZN app.
    **Pending — requires real hardware.**
13. After a successful Netflix launch the "the app may not be installed" hint does not appear; after pinning and
    launching a link for an app that is not installed (e.g. a Disney+ web link) the hint appears.
    **Pending — requires real hardware.**

## LG webOS and Samsung Tizen (F adapters)

14. webOS: a pinned Netflix title opens the title (ConnectSDK `contentId`); record the webOS version.
    **Pending — requires real hardware.**
15. webOS: a Prime Video link opens the Prime Video app (no title — documented).
    **Pending — requires real hardware.**
16. Tizen: a Netflix title link opens the Netflix app without the title; the play sheet's wording is acceptable.
    **Pending — requires real hardware.**
17. Tizen: a DAZN link is refused with "Samsung TVs cannot open web links …".
    **Pending — requires real hardware.**

## Honesty and security

18. No screen suggests a personalised Netflix, Prime Video or DAZN feed; the trending rail is labelled TMDB.
    **Pending — requires real hardware.**
19. View the page source and network tab of the dashboard, setup page and play sheet: the TMDB credential never
    appears.
    **Pending — requires real hardware.**
```

- [ ] **Step 5: README**

Add a section `## Netflix, Prime Video and DAZN` after the Jellyfin section:
- What it is: launchers with TMDB metadata; no access to what you watch.
- Setup: create a free TMDB account → Settings → API → copy the *API Read Access Token* (or the API key) → Setup → TMDB; this stores a secret, so a login password is set (link to the login section).
- Choose language, region and your services under Setup → Content (D4).
- "Trending on your services": TMDB's weekly trending titles available with a subscription on your services in your region; tapping opens the service's app; paste the title's link in the play sheet to open it directly next time.
- Pinned links: Setup → Pinned links; which URLs work (Netflix `…/title/<id>`, Prime Video share links and `amazon.<tld>/gp/video/detail/<ASIN>`, YouTube, DAZN, any web link); Home Control never fetches the page.
- What each TV does with the links (the table from Task 3).
- Privacy: posters load directly from `image.tmdb.org` in the browser, so TMDB's CDN sees the viewing device's IP; set `HOME_CONTROL_TMDB_IMAGE_BASE_URL` to use a mirror.
- Attribution: "This product uses the TMDB API but is not endorsed or certified by TMDB." and "Streaming availability data by JustWatch."
- Configuration rows: `HOME_CONTROL_TMDB_ENABLED` (true), `HOME_CONTROL_TMDB_API_BASE_URL`, `HOME_CONTROL_TMDB_IMAGE_BASE_URL`, `HOME_CONTROL_TMDB_PROVIDER_IDS_NETFLIX` (`8,1796`), `HOME_CONTROL_TMDB_PROVIDER_IDS_PRIMEVIDEO` (`9,119,2100`), `HOME_CONTROL_PINNED_ENABLED` (true), `HOME_CONTROL_PINNED_MAX_PINS` (200); `/data/pinned.json` in the data files list.

- [ ] **Step 6: Run everything**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.tmdb.TmdbFixtureContractTest' --tests 'dev.andre.homecontrol.web.StreamingLaunchersEndToEndTest'`, then `.superpowers/gradle.sh build`, then `.superpowers/e2e.sh --tests 'dev.andre.homecontrol.e2e.PinUpgradeE2eTest'` (or the whole `e2eTest` task if the script takes no filter).
Expected: PASS; BUILD SUCCESSFUL; the browser test passes in Chromium and WebKit.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbFixtureContractTest.java \
  src/test/java/dev/andre/homecontrol/web/StreamingLaunchersEndToEndTest.java \
  src/e2e/java/dev/andre/homecontrol/e2e/PinUpgradeE2eTest.java src/e2e/java/dev/andre/homecontrol/e2e/FakeContentSource.java \
  src/e2e/java/dev/andre/homecontrol/e2e/E2eFakesConfiguration.java \
  docs/superpowers/reviews/2026-09-16-streaming-launchers-acceptance.md README.md
git commit -m "test: streaming launcher fixtures, end-to-end launch checks and acceptance checklist"
```

---

## Final Automated Verification

- [ ] `.superpowers/gradle.sh build` — BUILD SUCCESSFUL.
- [ ] `.superpowers/e2e.sh` — all browser tests pass (D7's plus `PinUpgradeE2eTest`).
- [ ] `grep -rn "import dev.andre.homecontrol.sources\|import dev.andre.homecontrol.adapters" src/main/java/dev/andre/homecontrol/{core,content,playback,device,web}` — only configuration wiring that existed before G (no new hits).
- [ ] `grep -rn "import dev.andre.homecontrol.adapters" src/main/java/dev/andre/homecontrol/sources` — no hits.
- [ ] `grep -rln "READ_TOKEN\|0123456789abcdef0123456789abcdef" src/main` — no hits.
- [ ] The acceptance checklist has every item "Pending — requires real hardware".

## Out of scope for this plan

- Fetching page metadata (`og:title`, `og:image`) for pins (see Decisions).
- An artwork proxy or server-side artwork cache for TMDB images.
- Netflix or Prime Video "continue watching", watchlists or any personalised data (no public APIs).
- webOS Prime Video title deep links and Tizen title deep links (no known working payloads; F's tables are the place to add them).
- App-home launches for Disney+, WOW, Joyn, RTL+, Paramount+ and Apple TV+ (unverified URLs; pinning covers them).
- DAZN schedules and per-event links (sub-project H, which reuses `PinnedLinks` and `PinOffers`).
- Discover-style browsing (`/discover/movie` with `with_watch_providers`) and per-provider rails.
