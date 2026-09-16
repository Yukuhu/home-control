# Sports and DAZN (Sub-project H) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show live and upcoming sport on the dashboard and launch it honestly: a sports content source that reads user-supplied ICS calendars and TheSportsDB fixtures for user-selected competitions, a per-competition "where do you watch it" mapping that the UI always labels as the user's own setting, and a "Live now / Today" rail whose events open the DAZN app (or another service's app) through the existing app-link route — or, once the user pastes a per-event link, that event.

**Architecture:** One new source module `sources/sports` on sub-project C's `ContentSource` contract, with two feed kinds behind one schedule: `sources/sports/ics` (a small RFC 5545 subset parser: line unfolding, content lines with quoted parameters, TEXT unescaping, DATE / DATE-TIME with `TZID`, `Z` and floating forms, `DURATION`, `EXDATE`, `RECURRENCE-ID`, and a bounded `RRULE` subset), `sources/sports/calendar` (an SSRF-aware fetcher for user calendar links, per-calendar cache) and `sources/sports/thesportsdb` (typed `java.net.http` client for the v1 JSON API, daily per-competition-per-day cache, its own `@ConditionalOnProperty` switch). User content lives in `/data/sports.json` (atomic writes); calendar links and a personal TheSportsDB key are secrets in C's `SecretStore`. Every event becomes a `ContentItem` of kind `LIVE_EVENT` carrying the new optional `startsAt`/`endsAt` components from spec §5.2. Playables come from G's service grammar: a competition mapped to DAZN (or Netflix / Prime Video) gets one `AppLink` to `ServiceLinks.appHome(provider)`; G's `PinnedLinks` replaces it with a per-event link the user pasted, and G's `PinOffers` makes the play sheet offer that paste automatically. The planner is unchanged: it routes by `APP_LINK` capability and never looks at `kind` or time.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle 9.7.1, Thymeleaf, htmx 2, vanilla ES modules, JUnit 5, AssertJ, Mockito, Awaitility. **No new dependencies:** ICS parsing is an in-house subset parser (see Decisions; `org.mnode.ical4j:ical4j` was rejected), HTTP uses `java.net.http.HttpClient` (as C's Jellyfin and G's TMDB clients), JSON uses Jackson 3 already on the classpath, and the fake calendar and TheSportsDB servers use the JDK's `com.sun.net.httpserver.HttpServer` (as C's `FakeJellyfinServer` and G's `FakeTmdbServer`).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §2 (honesty about walled gardens, route by capability, secrets raise the bar), §3 assumption 4 (German market, configurable), §4.2 DAZN row (ship ICS + TheSportsDB; the "on DAZN" flag is user-configured per competition; Android TV launches the DAZN app via a `https://www.dazn.com/…` app link; a per-event deep link exists only if the user pastes it), §5.2 (`ContentItem` `startsAt?`, `endsAt?`, kind `LIVE_EVENT`), §5.3 (optimistic app links, route shown before play), §6.1 (*Live now / Today* rail, setup: sources, locale/providers), §7 (`sources/sports`; TheSportsDB through a small typed client), §8 (`sports.json`, `secrets.json`), §9 (login once a secret exists), §11 (DAZN schedule inaccuracy → flag is a user mapping labelled as such), §12 (fixtures, planner tests, manual checklist). Roadmap `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md` section H (H1–H5). Issues: epic #12, tasks #65–#69. Contracts this plan builds on: plans A `2026-09-16-multi-device-core.md`, B `2026-09-16-google-cast-adapter.md`, C `2026-09-16-jellyfin-source.md`, D `2026-09-16-dashboard-shell.md`, F `2026-09-16-smart-tv-adapters.md`, G `2026-09-16-streaming-launchers.md` (execution order A, B, C, D, F, E, G, then H). Sub-project E (YouTube) is not relied on.

**External references (verified 2026-09-16 against the live API and documentation):**
- TheSportsDB v1 JSON API, `https://www.thesportsdb.com/documentation`: base `https://www.thesportsdb.com/api/v1/json/{key}/…`; the free key is `123`; free users are limited to **30 requests per minute**, breaching it answers HTTP **429** ("wait another minute"). Free-tier result caps per call: `eventsday.php` 3, `eventsnextleague.php` 1, `eventspastleague.php` 1, `eventsseason.php` 15, `search_all_leagues.php` 10. An invalid key answered HTTP 400 with `{"Message":"Invalid Premium API key: Signup here: https:\/\/www.thesportsdb.com\/pricing"}`.
- Probed with key `123`: `lookupleague.php?id=4331` → `{"leagues":[{"idLeague":"4331","strSport":"Soccer","strLeague":"German Bundesliga","strCountry":"Germany","strBadge":"https://r2.thesportsdb.com/images/media/league/badge/teqh1b1679952008.png","strTvRights":"…Germany - DAZN and Sky",…}]}`; unknown id → `{"leagues":null}`. `search_all_leagues.php?c=Germany&s=Soccer` → `{"countries":[…league objects…]}` (the array is named `countries`). `eventsday.php?d=2026-09-19&l=4331` → `{"events":[…]}` filtered to that league (a league **name** in `l` returned nothing); no events → `{"events":null}`. Event objects carry `idEvent`, `idLeague`, `strEvent`, `strHomeTeam`, `strAwayTeam`, `strSport`, `strLeague`, `strTimestamp` (`2026-09-18T18:30:00`, **UTC without offset**: the same event has `strTime` `18:30:00` and `strTimeLocal` `20:30:00` for a Munich kick-off in CEST), `dateEvent`, `dateEventLocal`, `strTime`, `strTimeLocal`, `strStatus` (`NS`, `FT`, …), `strPostponed` (`no`), `strThumb`, `strPoster`, `strVenue`, `strCountry`.
- TheSportsDB images: `https://r2.thesportsdb.com/images/media/event/thumb/<name>.jpg` answered 709 KB; the same URL with `/small` appended answered 200 `image/jpeg` 50 KB (`/medium` 152 KB).
- RFC 5545 §3.1 (content lines, folding: CRLF followed by one space or tab), §3.2 (parameters, `DQUOTE` values), §3.3.4/§3.3.5 (DATE, DATE-TIME forms #1 floating, #2 UTC, #3 `TZID`), §3.3.6 (DURATION), §3.3.10 (RECUR; `COUNT` and `UNTIL` must not both occur; the DTSTART counts as the first instance), §3.3.11 (TEXT escaping `\\ \; \, \n \N`), §3.8.4.4 (RECURRENCE-ID), §3.8.5.1 (EXDATE, applied after the rule is generated), §3.6.1 (a VEVENT with a DATE `DTSTART` and no `DTEND` lasts one day).

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a play request that cannot be routed now fails now with a reason. Nothing is queued — in particular nothing waits for an event to start.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `content`, `playback`, `device` and `web` must not import `sources.*` or `adapters.*`; only `sources.sports.calendar` fetches calendars and only `sources.sports.thesportsdb` speaks HTTP to TheSportsDB; `sources.*` must not import `adapters.*`.
- Route by capability, not by brand. H adds no capability, no `PlayableRef` variant, no route variant and no strategy.
- Honesty about walled gardens: DAZN is a launcher with third-party metadata. The UI never implies a personalised DAZN feed, never implies that a competition is on DAZN unless the user said so, and labels every provider flag as the user's setting. An app-home launch is always described as opening the app, not the event.
- Secrets raise the bar: a single-password login is mandatory once any secret is stored. Calendar links and a personal TheSportsDB key are encrypted at rest (C's `SecretStore`) and never reach the browser, a log line, an exception message or a `toString()`.
- Persistent state stays in `/data` as JSON files written atomically; `devices.json`, `keystore.p12`, `secrets.json`, `sources.json`, `pinned.json` keep working.
- Every adapter and source is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; every source has recorded fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), each ending with the two trailer lines required by `.superpowers/sdd/implementer-common.md`.

Build and tooling (this repository):

- There is no local JDK. Build with `.superpowers/gradle.sh build`; focused tests with `.superpowers/gradle.sh test --tests '<pattern>'`; browser tests with `.superpowers/e2e.sh` (D7). Failing test detail: grep `<failure` in `build/test-results/test/*.xml`.
- Spring Boot 4.1.1: MockMvc test auto-configuration is `org.springframework.boot.webmvc.test.autoconfigure`; `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Jackson 3 (`tools.jackson.databind.*`): `JsonMapper.builder().build()`, `readTree`, `writeValueAsString`; on possibly missing nodes use `path(..)` and the defaulted accessors `asString("")`, `asInt(0)`, `asLong(0)`, `asBoolean(false)`; `isString()` for textual nodes (`isTextual()` if that is the available name); `isNull()`/`isMissingNode()` for absent values. Exceptions are unchecked `tools.jackson.core.JacksonException`.

Epic constraints:

- Plans A–D, F and G are the contract. Where real code differs from their listings (a field name, a helper, a constructor parameter), adapt the edit to the real code, keep the behaviour this plan specifies, and say so in the task report. Names this plan relies on: `ContentSource` (`id`, `displayName`, `available`, `rails`, `rail`, `item`, `searchable`, `search`, `defaultRefreshInterval`), `ContentSources`, `Rail`, `RailDescriptor`, `ContentSourceException`, `ContentItem` (8 components with `progress`, 7-argument constructor kept, `withPlayables`), `ContentKind.LIVE_EVENT`, `ContentItemView`, `PlayableRef.AppLink(URI uri, String service)`, `Route.OpenAppLink`, `PlaybackPlanner.plan/routes`, `AppLinkStrategy`, `ServiceLinks` (`DAZN`, `displayName`, `appHome`, `isAppHome`, `appLink`), `ContentChangedEvent`, `PinnedLinks.linkFor`, `PinOffers.offer`, `PinnedShortcuts.addUpgrade`, `PinUpgradeController` (`POST /setup/sources/pinned/upgrade`), `RoutePreviewView` `pin`, `static/js/play-sheet.js` `showPin`, `RailCache` (`onContentChanged`, `refresh`), `SourcePreferences` (`locale`, `region`, `providers`), `SourcePreferencesService.current()`, `StreamingProviders.KNOWN`, `SecretStore.secret/names`, `LoginService.loginRequired/checkNewPassword/storeSecrets/removeSecrets`, `PasswordRejectedException`, `LoginRequiredException`, `StorageException`, `AndroidTvProperties.dataDir()`, `SetupController`, `setup.html`, `fragments/rails.html :: tile`, A's `FakeRemoteServer.nextAppLink()`, `CertificateStore`, `AndroidTvSettings.device`, `DeviceManager.adopt/state`, C's `JellyfinEndToEndTest` helpers, G's `StreamingLaunchersEndToEndTest` structure.
- The calendar fetcher never follows a redirect without re-validating the target, has connect and request timeouts, caps bodies at 5 MiB, and never puts a calendar URL's path or query into a log line, exception message, flash attribute or page (private calendar links carry tokens in the path).
- The TheSportsDB client never follows redirects, has connect and request timeouts, caps bodies at 2 MiB, and never puts a request URL into a log line or exception message (the key is a path segment).
- Browser-bound content never carries a `PlayableRef` (C): rails and item JSON use `ContentItemView`; the browser plays by `source` + `item`; the server re-reads the item with `ContentSource.item`.
- Beans for new services are declared in `@Configuration` classes (not `@Component`); controllers and `@ControllerAdvice` classes of a module carry the module's `@ConditionalOnProperty`; advice takes dependencies through `ObjectProvider`.
- Every setup endpoint lives under `/setup/sources/sports/…` so C's `LoginGateFilter.ALWAYS_GUARDED` cross-origin check (and A's `CrossSitePostGuard`) apply before a login exists.
- All wire formats in this plan (ICS subset rules, TheSportsDB queries and mapping, `sports.json`, item ids, the subtitle grammar, the rail order, the `ContentItemView` additions) are normative; tests pin them.
- The manual acceptance checklist is never marked passed by an agent.

---

## Decisions

- Decision: add the spec's optional `startsAt` and `endsAt` (`Instant`, nullable) to `ContentItem` as components 9 and 10, keep the 7- and 8-argument constructors, carry them through `withPlayables`, and expose them in `ContentItemView` as ISO-8601 strings plus tile `data-starts-at`/`data-ends-at` attributes — spec §5.2 names exactly these fields, the play sheet and later clients need machine-readable times, and a sports-only side channel would duplicate the item model — cost if wrong: a code path that deconstructs `ContentItem` with a record pattern fails to compile (none exists in plans A–G).
- Decision: no ical4j; an in-house RFC 5545 subset parser (~500 lines with tests) — `org.mnode.ical4j:ical4j` 4.x pulls commons-lang3, commons-collections4, commons-validator, commons-codec, threeten-extra and slf4j plus its own tz cache and network zone updates, while H needs only VEVENT start/end/summary/uid/status and simple recurrence; a small parser is fully testable against fixtures — cost if wrong: an exotic calendar feature (RDATE, BYMONTH rules) shows only its first occurrence; switch to ical4j behind `IcsParser`'s signature.
- Decision: recurrence subset = `FREQ=DAILY|WEEKLY` with `INTERVAL`, `COUNT` **or** `UNTIL`, plain `BYDAY` (two-letter days, weekly only) and an ignored `WKST`; plus `EXDATE` and `RECURRENCE-ID` overrides; any other rule (MONTHLY, YEARLY, BYSETPOS, numbered BYDAY, BYMONTH, COUNT with UNTIL, …) yields only the DTSTART occurrence and is counted and shown in setup as "N repeating events use rules Home Control shows only once" — sports calendars list fixtures as single events; weekly series (e.g. a darts league night) are the realistic recurring case; silently dropping recurring events would hide content — cost if wrong: a monthly series shows once until the subset grows.
- Decision: weeks for `BYDAY` start on Monday regardless of `WKST` — `WKST` only changes results when `INTERVAL > 1` and `BYDAY` spans a week boundary — cost if wrong: a bi-weekly Sunday+Monday rule with `WKST=SU` is off by one week.
- Decision: `TZID` resolution = IANA id as written; else a trailing IANA id inside a path-like id (`/mozilla.org/20050126_1/Europe/Paris`); else a fixed map of the common Windows zone names Outlook and Exchange write (`W. Europe Standard Time` → `Europe/Berlin`, …); else the calendar's `X-WR-TIMEZONE`, else the user's sports time zone, counted as "unknown time zone" in setup; `VTIMEZONE` components are ignored — every real producer pairs `VTIMEZONE` with a recognisable id; parsing `VTIMEZONE` rules is the heaviest part of RFC 5545 — cost if wrong: an event with an invented `TZID` is off by the zone difference and setup says so.
- Decision: an event without `DTEND` and `DURATION` lasts one day for a DATE start (RFC) and `home-control.sports.default-event-duration` (120 min) for a DATE-TIME start (RFC says zero) — a zero-length match would never be "live" — cost if wrong: a property.
- Decision: calendar links are user-supplied URLs fetched by the server. Allowed: `http`, `https`, and `webcal`/`webcals` rewritten to `https`; any public **or private LAN** address (10/8, 172.16/12, 192.168/16, fc00::/7, CGNAT) because self-hosted Nextcloud/Radicale calendars on the LAN are a primary use case and anyone who can reach the dashboard can already reach those hosts. Refused: user-info in the URL, and — after DNS resolution of **every** address and again on **every redirect hop** — loopback (the appliance runs with host networking, so `localhost` reaches CasaOS and other host services), any-local (`0.0.0.0`, `::`), link-local (`169.254/16` incl. cloud metadata, `fe80::/10`) and multicast. `home-control.sports.calendar.allow-loopback` (default `false`) exists for tests and for users who host the calendar on the same box. Residual risk documented in the README: DNS rebinding between the check and the connect (the JDK client re-resolves) and the server acting as a LAN fetcher for whoever can reach the setup page (which requires the login once a calendar exists) — cost if wrong: a calendar served from the Docker host itself needs the property.
- Decision: the calendar URL is stored only as a secret (`sports.calendar.<id>` through `LoginService.storeSecrets`), so adding the first calendar sets the login password; `sports.json` keeps only id, label, host and provider — Google's "secret address in iCal format", Nextcloud share links and fixtur.es personal feeds carry credentials in the path, and H cannot tell a public link from a private one — cost if wrong: a household with only public calendars sets a login password it did not strictly need.
- Decision: the calendar is fetched and parsed once when it is added, so a wrong link fails in the setup form with a clear message ("That link did not return a calendar (.ics)") instead of as a rail error later; the fetch reports only the host in every message — cost if wrong: adding a calendar takes up to the request timeout.
- Decision: calendars are refreshed every `home-control.sports.calendar.refresh` (6 h); a failed refresh keeps the last good copy and is retried after 10 min; events are expanded from the cached parse on every rail load into the window [now − 1 day, now + 8 days] — expansion is cheap and keeps "today" current without refetching — cost if wrong: a changed kick-off time shows up to 6 h late (manual retry on the rail refetches only when stale; see next decision).
- Decision: TheSportsDB uses `eventsday.php?d=<UTC date>&l=<league id>` for each UTC date overlapping [start of today − 6 h, start of tomorrow) in the user's zone (two dates for European and American zones, at most three) — the endpoint answers exactly "this competition on this day", the league filter was verified to work with ids, and `eventsnextleague.php` returns a single event on the free key — cost if wrong: an event that started more than 6 h before local midnight and is still running is missing (none realistic).
- Decision: "fixtures cached daily" = each (league, UTC date) answer is cached for `home-control.sports.thesportsdb.fixtures-ttl` (24 h, in memory, failures retried after 10 min, stale answers kept on failure); the **rail** itself is refreshed by D's `RailCache` every 5 minutes (`SportsContentSource.defaultRefreshInterval()`), which only re-reads the caches and recomputes live/upcoming — D's cache is per source and one TTL cannot be both "daily" for the upstream and "minutes" for the live boundary; a daily rail refresh would show a match as upcoming for its whole duration — cost if wrong: a postponed kick-off shows up to 24 h late; the user can lower the TTL.
- Decision: TheSportsDB key: the documented free key `123` by default (not a secret, no login needed to add competitions); a personal (Patreon) key is a secret `sports.thesportsdb.key` verified with one `lookupleague.php` call before storing. Setup states the free key's limit plainly: "The free key shows at most 3 matches per competition per day." — honest about incomplete data, zero setup for trying it — cost if wrong: users with busy competitions see a partial Today list until they add a key.
- Decision: at most 10 calendars and 10 competitions (properties), fetched sequentially; a `RATE_LIMITED` answer stops further TheSportsDB fetches for that load and keeps stale data — 10 competitions × 2 dates = 20 calls, under the free 30/min — cost if wrong: raise the property with a personal key.
- Decision: a TheSportsDB event is skipped when `strPostponed` is `yes` or its status means cancelled/postponed/abandoned/awarded; `FT`, `AET`, `PEN`, `Match Finished`, `AOT` mark it finished; in-play statuses mark it live; otherwise live/ended follows the clock with a per-sport duration (`home-control.sports.thesportsdb.sport-durations`, soccer 120 min, default 120 min) because the daily cache rarely sees in-play statuses — cost if wrong: a long match drops off the "Live" list early; tune the property.
- Decision: `strTvRights` from TheSportsDB is **not** used to prefill the provider mapping — it is community-edited free text, and spec §11 requires the flag to be the user's own mapping — cost if wrong: one more click per competition.
- Decision: the provider mapping is stored on each calendar and competition entry (`provider`: a `StreamingProviders.KNOWN` key or null); new entries start unmapped; setup shows the column as "Where you watch it (your setting)" with the sentence "Home Control does not know broadcast rights; this is only what you told it."; every item subtitle that mentions a provider ends with `(your setting)` — spec §4.2, §11 — cost if wrong: wording.
- Decision: playables per event: a pinned per-event link (`PinnedLinks.linkFor("sports", itemId)`) first; else `AppLink(ServiceLinks.appHome(provider), provider)` when the mapped provider has an app home (DAZN, Netflix, Prime Video); else none. With no playables the planner's existing reason ("This item has nothing playable") applies and G's play sheet still offers pinning a link — G's `PinOffers` rule is generic and treats both cases; no per-competition link is added (a pasted competition page would suppress the per-event pin offer) — cost if wrong: a follow-up adds per-competition links as a second mapping field.
- Decision: a per-event pin created from the play sheet also appears in G's "Pinned" rail and stays there after the event (G's pin semantics); H does not expire pins — reusing G's store keeps one pin model; the user removes old pins in Setup → Pinned links — cost if wrong: Pinned rail clutter for heavy users; a later `expiresAt` on pins.
- Decision: item ids — ICS: `ics:<calendar id>:<first 16 hex of SHA-256(uid "|" occurrence start epoch seconds)>` (without `UID`: `no-uid|<summary>` in place of the uid); TheSportsDB: `tsdb:<idEvent>` — stable across refreshes, unique per occurrence, and inside G's `upgradeOf` item-id alphabet `[A-Za-z0-9._:-]{1,128}` so per-event pins work — cost if wrong: a calendar that rewrites UIDs on every export loses its per-event pins.
- Decision: the rail is one rail `live-today` titled `Live now / Today` (roadmap wording): events in phase LIVE (started, not ended, not finished) first, then all-day events covering today, then events starting later today, each group by start time, then title, then id; at most `rail-size` (30) items; no cross-feed de-duplication (ICS and TheSportsDB spell teams differently) — cost if wrong: the same match twice when a user configures both feeds for one competition; setup text advises choosing one.
- Decision: the sports time zone is a sports setting (`sports.json` `timeZone`, set in the sports setup section), else `home-control.sports.time-zone`, else the JVM default (`TZ` in the container); setup warns when the effective zone is UTC and nothing was chosen — D4 stores locale, region and providers but no zone, and a region can span zones (US, AU); adding a component to D's `SourcePreferences` would ripple through D and G — cost if wrong: a later move of the field into D4's form.
- Decision: subtitle times use D4's `locale` (`DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)` etc.) and the sports zone; the page language stays English — consistent with G using D4's locale for TMDB language — cost if wrong: none.
- Decision: TheSportsDB artwork is used directly (public HTTPS on `r2.thesportsdb.com`), with `/small` appended for `.jpg`/`.jpeg`/`.png` paths on hosts under `thesportsdb.com` (verified: 50 KB instead of 700 KB); fallback order event `strThumb`, event `strPoster`, competition badge; ICS events have no artwork — same privacy trade-off G documented for TMDB images — cost if wrong: an artwork proxy later.
- Decision: module switches `home-control.sports.enabled` (default `true`) and `home-control.sports.thesportsdb.enabled` (default `true`) — the source does nothing until a calendar or competition is added, and a user who wants no third-party API calls can turn TheSportsDB off alone — cost if wrong: set a variable.
- Decision: the sports source is not searchable — the only data is today's window, which is on screen — cost if wrong: a local title search like G's pinned source later.
- Decision: H5's automated acceptance is a Spring end-to-end test over real sockets (fake calendar server, fake TheSportsDB server, A's `FakeRemoteServer` as the Shield, real login, G's real pin upgrade) that asserts the exact app-link strings the Shield receives; its calendar and fixture content is generated relative to the real clock so "live" is deterministic; no new Playwright test — G's `PinUpgradeE2eTest` already covers the play-sheet pin prompt and H changes only its wording, which `StaticAssetsTest` pins — cost if wrong: an event-specific browser regression is caught by the manual checklist instead.

---

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`. Paths are relative to those roots unless they start with `src/`, `docs/` or are top-level files.

### Files to create

- `sources/sports/SportsProperties.java` — `home-control.sports.*` (Task 1; `theSportsDb` used from Task 2).
- `sources/sports/SportsConfiguration.java` — module beans.
- `sources/sports/SportsEvent.java`, `sources/sports/EventPhase.java` — one event and its phase relative to now.
- `sources/sports/SportsSettings.java` — the `sports.json` model (calendars, competitions, key kind, time zone).
- `sources/sports/JsonFileSportsStore.java`, `sources/sports/SportsSettingsService.java` — atomic file, single writer, `ContentChangedEvent`.
- `sources/sports/SportsTimeZones.java` — effective zone.
- `sources/sports/SportsSchedule.java` — all feeds combined.
- `sources/sports/SportsItems.java` — `SportsEvent` → `ContentItem` (subtitle grammar; playables from Task 4).
- `sources/sports/SportsContentSource.java` — the source.
- `sources/sports/SportsSetupController.java`, `sources/sports/SportsSetupAdvice.java`; `src/main/resources/templates/fragments/sports-setup.html`.
- `sources/sports/ics/IcsParser.java`, `IcsCalendar.java`, `IcsEvent.java`, `IcsTime.java`, `IcsFormatException.java`, `IcsZones.java`, `IcsRecurrence.java`, `IcsOccurrences.java`, `IcsOccurrence.java`.
- `sources/sports/calendar/CalendarUrlPolicy.java`, `CalendarFetcher.java`, `CalendarFetchException.java`, `CalendarSchedule.java`, `FeedStatus.java`, `SportsCalendars.java`.
- `sources/sports/thesportsdb/TheSportsDbConfiguration.java`, `TheSportsDbClient.java`, `TheSportsDbException.java`, `TheSportsDbKeys.java`, `League.java`, `TheSportsDbEventMapper.java`, `TheSportsDbSchedule.java`, `SportsCompetitions.java`, `TheSportsDbSetupController.java` (Task 2).
- `sources/sports/SportsProviders.java` — mapping validation and labels (Task 3).
- `sources/sports/LiveTodayRail.java` — selection and order (Task 4).
- `docs/superpowers/reviews/2026-09-16-sports-dazn-acceptance.md` (Task 5).
- Tests: `core/playback/ContentItemTest.java` (create if absent), `web/ContentItemViewTest.java`; `sources/sports/ics/IcsParserTest.java`, `IcsZonesTest.java`, `IcsRecurrenceTest.java`, `IcsOccurrencesTest.java`; `sources/sports/calendar/FakeCalendarServer.java`, `CalendarUrlPolicyTest.java`, `CalendarFetcherTest.java`, `CalendarScheduleTest.java`, `SportsCalendarsTest.java`; `sources/sports/JsonFileSportsStoreTest.java`, `SportsSettingsServiceTest.java`, `SportsTimeZonesTest.java`, `EventPhaseTest.java`, `SportsItemsTest.java`, `SportsContentSourceTest.java`, `SportsSetupControllerTest.java`, `SportsModuleSwitchTest.java`; Task 2: `sources/sports/thesportsdb/FakeTheSportsDbServer.java`, `TheSportsDbClientTest.java`, `LeagueTest.java`, `TheSportsDbEventMapperTest.java`, `TheSportsDbScheduleTest.java`, `SportsCompetitionsTest.java`, `TheSportsDbSetupControllerTest.java`; Task 3: `sources/sports/SportsProvidersTest.java`; Task 4: `sources/sports/LiveTodayRailTest.java`, `sources/sports/SportsRailTest.java`, `sources/sports/SportsPinUpgradeTest.java`, `core/playback/LiveEventRoutingTest.java`; Task 5: `sources/sports/ics/IcsFixtureContractTest.java`, `sources/sports/thesportsdb/TheSportsDbFixtureContractTest.java`, `web/SportsEndToEndTest.java`.
- Fixtures `src/test/resources/fixtures/ics/`: `bundesliga.ics`, `recurring.ics`, `outlook.ics`, `broken.ics`, `not-a-calendar.html`; `src/test/resources/fixtures/thesportsdb/`: `lookupleague-4331.json`, `lookupleague-unknown.json`, `lookupleague-4328.json`, `search_all_leagues-germany-soccer.json`, `eventsday-2026-09-18-4331.json`, `eventsday-2026-09-19-4331.json`, `eventsday-2026-09-19-4328.json`, `eventsday-empty.json`, `invalid-key.json`; `src/test/resources/fixtures/sports/sports-v1.json`.

### Files to modify

- `core/playback/ContentItem.java` — `startsAt`, `endsAt` (Task 1).
- `web/ContentItemView.java` — `startsAt`, `endsAt` (Task 1).
- `src/main/resources/templates/fragments/rails.html` — tile `data-starts-at`, `data-ends-at` (Task 1).
- `src/main/resources/templates/setup.html` — sports section (Task 1).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.sports.*` (Tasks 1, 2).
- `src/main/resources/static/js/play-sheet.js` — event wording for the pin prompt (Task 4).
- `README.md` — sports and DAZN section (Task 5).
- Tests: `core/playback/PlaybackPlannerTest.java` untouched; `web/StaticAssetsTest.java` (Task 4); `web/DashboardPageTest.java` or D2's rails fragment test (Task 1).

### Files to delete

- None.

---
### Task 1: H1 · ICS calendar source

**Files:**
- Create: `sources/sports/SportsProperties.java`, `SportsConfiguration.java`, `SportsEvent.java`, `EventPhase.java`, `SportsSettings.java`, `JsonFileSportsStore.java`, `SportsSettingsService.java`, `SportsTimeZones.java`, `SportsSchedule.java`, `SportsItems.java`, `SportsContentSource.java`, `SportsSetupController.java`, `SportsSetupAdvice.java`; `sources/sports/ics/IcsParser.java`, `IcsCalendar.java`, `IcsEvent.java`, `IcsTime.java`, `IcsFormatException.java`, `IcsZones.java`, `IcsRecurrence.java`, `IcsOccurrences.java`, `IcsOccurrence.java`; `sources/sports/calendar/CalendarUrlPolicy.java`, `CalendarFetcher.java`, `CalendarFetchException.java`, `CalendarSchedule.java`, `FeedStatus.java`, `SportsCalendars.java`; `src/main/resources/templates/fragments/sports-setup.html`
- Modify: `core/playback/ContentItem.java`, `web/ContentItemView.java`, `src/main/resources/templates/fragments/rails.html`, `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `core/playback/ContentItemTest.java`, `web/ContentItemViewTest.java`, `sources/sports/ics/IcsParserTest.java`, `IcsZonesTest.java`, `IcsRecurrenceTest.java`, `IcsOccurrencesTest.java`, `sources/sports/calendar/FakeCalendarServer.java`, `CalendarUrlPolicyTest.java`, `CalendarFetcherTest.java`, `CalendarScheduleTest.java`, `SportsCalendarsTest.java`, `sources/sports/JsonFileSportsStoreTest.java`, `SportsSettingsServiceTest.java`, `SportsTimeZonesTest.java`, `EventPhaseTest.java`, `SportsItemsTest.java`, `SportsContentSourceTest.java`, `SportsSetupControllerTest.java`, `SportsModuleSwitchTest.java`; the D2 test that renders `fragments/rails :: tile` (find it with `grep -rl "class=\"tile\"\|data-kind" src/test/java`); fixtures `src/test/resources/fixtures/ics/bundesliga.ics`, `recurring.ics`, `outlook.ics`, `broken.ics`, `not-a-calendar.html`, `src/test/resources/fixtures/sports/sports-v1.json`

**Interfaces:**
- Consumes: `ContentSource`, `Rail`, `RailDescriptor`, `ContentSourceException` (C3), `defaultRefreshInterval()` (D1); `ContentItem`, `ContentKind.LIVE_EVENT` (A/C); `ContentItemView` (C3); `ContentChangedEvent` (G2); `SecretStore.secret(String)`, `LoginService.loginRequired()/checkNewPassword(String,String)/storeSecrets(Map,String,String,HttpServletRequest)/removeSecrets(Collection)`, `PasswordRejectedException`, `LoginRequiredException` (C1); `SourcePreferences.locale()`, `SourcePreferencesService.current()`, `StreamingProviders.KNOWN` (D4); `StorageException` (existing); `AndroidTvProperties.dataDir()` (A); `SetupController`, `setup.html`, `fragments/rails.html` (A–D).
- Produces:
  - `ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle, URI artwork, List<PlayableRef> playables, Double progress, Instant startsAt, Instant endsAt)`; the 7- and 8-argument constructors stay (times null); `withPlayables` keeps the times.
  - `ContentItemView` gains `String startsAt, String endsAt` (last two components, `Instant.toString()` or null).
  - Tile attributes `data-starts-at`, `data-ends-at`.
  - `sealed interface IcsTime` with `record Date(LocalDate date)`, `record Local(LocalDateTime dateTime, String tzid)` (`tzid` null = floating), `record Utc(Instant instant)`.
  - `record IcsEvent(String uid, String summary, IcsTime start, IcsTime end, Duration duration, String rrule, List<IcsTime> exdates, IcsTime recurrenceId, String status)`; `record IcsCalendar(String name, String timeZone, List<IcsEvent> events, int skippedEvents)`; `class IcsFormatException extends RuntimeException` (user-facing message).
  - `final class IcsParser { static IcsCalendar parse(String text); static IcsTime parseTime(String value, String tzid); static Duration parseDuration(String value); static String unescape(String text); static List<String> unfold(String text); }`.
  - `final class IcsZones { static Optional<ZoneId> resolve(String tzid); }`.
  - `record IcsRecurrence(Frequency frequency, int interval, Integer count, IcsTime until, List<DayOfWeek> byDay)` with `enum Frequency { DAILY, WEEKLY }` and `static Optional<IcsRecurrence> parse(String rule)`.
  - `record IcsOccurrence(String uid, String summary, Instant startsAt, Instant endsAt, LocalDate allDayDate)`; `final class IcsOccurrences { static Result expand(IcsCalendar, ZoneId fallback, Instant windowStart, Instant windowEnd, Duration defaultDuration); record Result(List<IcsOccurrence> occurrences, int unsupportedRules, int unknownZones) }`.
  - `class CalendarUrlPolicy { CalendarUrlPolicy(boolean allowLoopback); CalendarUrlPolicy(boolean allowLoopback, HostResolver resolver); URI parse(String raw); void checkAddress(URI uri); interface HostResolver { InetAddress[] resolve(String host) throws UnknownHostException; } }`.
  - `class CalendarFetchException extends ContentSourceException { enum Kind { BLOCKED, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, BAD_RESPONSE, TOO_LARGE, NOT_A_CALENDAR } Kind kind(); }`.
  - `class CalendarFetcher { CalendarFetcher(SportsProperties.Calendar, CalendarUrlPolicy); CalendarFetcher(SportsProperties.Calendar, CalendarUrlPolicy, HttpClient); String fetch(URI url); }`.
  - `record SportsEvent(String itemId, String competitionKey, String title, Instant startsAt, Instant endsAt, LocalDate allDayDate, URI artwork, Status status)` with `enum Status { SCHEDULED, LIVE, FINISHED }` and `boolean allDay()`.
  - `enum EventPhase { LIVE, ALL_DAY_TODAY, UPCOMING_TODAY, LATER, ENDED; static EventPhase of(SportsEvent, Instant now, ZoneId zone) }`.
  - `record SportsSettings(String timeZone, List<CalendarEntry> calendars, KeyKind keyKind, List<CompetitionEntry> competitions)` with `enum KeyKind { FREE, PERSONAL }`, `record CalendarEntry(String id, String label, String host, String provider, Instant addedAt)`, `record CompetitionEntry(String leagueId, String name, String sport, String country, URI badge, String provider, Instant addedAt)`, `static SportsSettings empty()`, withers `withTimeZone`, `withCalendars`, `withKeyKind`, `withCompetitions`, `static String calendarKey(String id)` (`calendar:<id>`), `static String competitionKey(String leagueId)` (`thesportsdb:<id>`), `Optional<String> labelFor(String competitionKey)`, `Optional<String> providerFor(String competitionKey)`, `Optional<CalendarEntry> calendar(String id)`.
  - `class JsonFileSportsStore { JsonFileSportsStore(Path file); SportsSettings load(); void save(SportsSettings); }`.
  - `class SportsSettingsService { SportsSettingsService(JsonFileSportsStore, ApplicationEventPublisher); SportsSettings current(); SportsSettings update(UnaryOperator<SportsSettings>); }` — `update` saves under a lock, then publishes `ContentChangedEvent("sports")`.
  - `class SportsTimeZones { SportsTimeZones(SportsSettingsService, SportsProperties); ZoneId effective(); boolean chosen(); static Optional<ZoneId> parse(String id); }`.
  - `record FeedStatus(Instant fetchedAt, int events, String error, int unsupportedRules, int unknownZones, int skippedEvents)`.
  - `class CalendarSchedule { CalendarSchedule(SportsSettingsService, CalendarFetcher, SecretStore, SportsProperties, SportsTimeZones, Clock); Result events(); Optional<SportsEvent> find(String itemId); Optional<FeedStatus> status(String calendarId); void prime(String calendarId, IcsCalendar calendar); void forget(String calendarId); boolean hasCalendars(); static SportsEvent toEvent(String calendarId, IcsOccurrence occurrence); record Result(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) }`.
  - `class SportsCalendars { SportsCalendars(SportsSettingsService, CalendarUrlPolicy, CalendarFetcher, CalendarSchedule, SecretStore, LoginService, SportsProperties, Clock, SecureRandom); CalendarEntry add(AddCalendar request, HttpServletRequest http); CalendarEntry remove(String id); record AddCalendar(String url, String label, String loginPassword, String loginPasswordConfirmation) }` (redacted `toString`); `static String secretName(String calendarId)` = `sports.calendar.<id>`.
  - `class SportsSchedule { SportsSchedule(CalendarSchedule); boolean hasFeeds(); List<SportsEvent> events(); Optional<SportsEvent> find(String itemId); }` (Task 2 appends a parameter).
  - `final class SportsItems { static ContentItem toItem(SportsEvent event, SportsSettings settings, ZoneId zone, Locale locale, Instant now); static String subtitle(SportsEvent, SportsSettings, ZoneId, Locale, Instant now); }` (Tasks 3 and 4 extend).
  - `class SportsContentSource implements ContentSource` — id `sports`, display name `Sports`, `available()` ⇔ `schedule.hasFeeds()`, no rails in this task, `item(itemId)`; constructor `(SportsSettingsService, SportsSchedule, SportsTimeZones, Supplier<SourcePreferences>, Clock)` (Task 4 appends parameters).
  - Endpoints (form posts, `302 /setup#sports`, flash `sportsMessage` / `sportsError`): `POST /setup/sources/sports/calendars` (`url`, `label`, `loginPassword`, `loginPasswordConfirmation`), `POST /setup/sources/sports/calendars/{id}/remove`, `POST /setup/sources/sports/time-zone` (`timeZone`).
  - Model attribute `sports` = `SportsSetupAdvice.View(String timeZone, String storedTimeZone, boolean timeZoneLooksUnset, List<CalendarView> calendars, int maxCalendars, boolean needsLoginPassword)` with `record CalendarView(String id, String label, String host, String status)` (Tasks 2–3 append components).
  - Property `home-control.sports.enabled` (default `true`).

**`ContentItem` additions (normative).** `startsAt` and `endsAt` are nullable. `endsAt` non-null requires `startsAt` non-null (`IllegalArgumentException("endsAt needs startsAt")`) and `!endsAt.isBefore(startsAt)` (`IllegalArgumentException("endsAt must not be before startsAt")`). `ContentItemView.startsAt/endsAt` = `Instant.toString()` (e.g. `2026-09-19T13:30:00Z`) or null; JSON field order: C's seven fields, then `startsAt`, `endsAt`.

**ICS subset (normative).**
1. Input: a leading U+FEFF is removed. Lines split on CRLF, LF or CR. A line starting with a space or horizontal tab continues the previous line with that first character removed (RFC 5545 §3.1). Blank lines are ignored.
2. Content line: the first `:` outside double quotes ends the name-and-parameters part; `;` outside double quotes separates parameters; names and parameter names are upper-cased; a parameter value wrapped in double quotes loses them; the first occurrence of a parameter wins; a line without `:` is ignored.
3. Structure: text before the first `BEGIN:VCALENDAR` is ignored; a `BEGIN` of anything else before it → `IcsFormatException("That link did not return a calendar (.ics)")`; no `BEGIN:VCALENDAR` at all → the same. Properties count only directly inside `VCALENDAR` (`X-WR-CALNAME` → name, TEXT-unescaped and stripped; `X-WR-TIMEZONE` → time zone) or directly inside a `VEVENT` that is directly inside `VCALENDAR` (properties of `VALARM`, `VTIMEZONE`, `STANDARD`, `DAYLIGHT`, `VTODO` are ignored). A `VEVENT` still open at the end of input counts as skipped. More than 5 000 events → `IcsFormatException("The calendar has more than 5000 events")`.
4. Event properties (first occurrence wins, except `EXDATE` which accumulates, and `STATUS` where the last wins): `UID` (TEXT-unescaped, stripped, blank → null), `SUMMARY` (TEXT-unescaped, stripped), `DTSTART` (required; missing → the event is skipped), `DTEND`, `DURATION`, `RRULE` (raw, stripped), `EXDATE` (comma-separated values, each with the line's `TZID`), `RECURRENCE-ID`, `STATUS` (upper-cased). A DATE/DATE-TIME value that cannot be read skips the whole event.
5. TEXT unescape: `\\` → `\`, `\;` → `;`, `\,` → `,`, `\n` and `\N` → newline, `\x` for any other `x` → `x`; a trailing lone `\` stays.
6. Values: `^\d{8}$` → `Date` (strict ISO basic date); `^(\d{8})T(\d{6})(Z?)$` (case-insensitive) → with `Z` `Utc`, else `Local(dateTime, tzid)` where `tzid` is the property's `TZID` parameter or null; anything else, or an invalid calendar date/time (e.g. `20260231`, hour 24) → `IcsFormatException("Unreadable date or time")`.
7. `DURATION`: `^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$` (case-insensitive); a negative, zero, unparsable or overflowing value → null (ignored).
8. `TZID` → zone: blank → none; surrounding double quotes stripped; the Windows map below; else, for the `/`-separated segments, the first suffix `segments[i..]` (i = 0, 1, …) that `ZoneId.of` accepts; else none (counted as unknown and read in the calendar zone). Windows map: `W. Europe Standard Time` → `Europe/Berlin`, `Central Europe Standard Time` → `Europe/Budapest`, `Central European Standard Time` → `Europe/Warsaw`, `Romance Standard Time` → `Europe/Paris`, `GMT Standard Time` → `Europe/London`, `Greenwich Standard Time` → `Atlantic/Reykjavik`, `E. Europe Standard Time` → `Europe/Chisinau`, `FLE Standard Time` → `Europe/Kyiv`, `Eastern Standard Time` → `America/New_York`, `Central Standard Time` → `America/Chicago`, `Mountain Standard Time` → `America/Denver`, `Pacific Standard Time` → `America/Los_Angeles`, `UTC` and `Coordinated Universal Time` → `UTC`.
9. Calendar zone = `IcsZones.resolve(X-WR-TIMEZONE)` else the fallback (the sports time zone). `Date` values and floating `Local` values are read in the calendar zone; `Local` with a resolvable `TZID` in that zone; `Utc` in UTC. Gaps and overlaps follow `LocalDateTime.atZone` (gap → shifted forward, overlap → earlier offset), matching RFC 5545 §3.3.5.
10. Occurrence length: all-day (`Date` start) → whole days = days between the start date and a `Date` `DTEND`, else `DURATION.toDays()`, else 1; at least 1; ends at the start of the day after the last day in the calendar zone. Timed → `DTEND` instant − start instant, else `DURATION`, else the default duration; a zero or negative length becomes the default duration.
11. `STATUS:CANCELLED` events produce nothing.
12. Recurrence (`IcsRecurrence.parse`): `;`-separated `KEY=VALUE`, keys case-insensitive. Supported keys only: `FREQ` (`DAILY`|`WEEKLY`), `INTERVAL` (1–1000), `COUNT` (1–10 000), `UNTIL` (a value per rule 6 with no `TZID`), `BYDAY` (comma-separated `MO`…`SU` without numbers; only with `WEEKLY`), `WKST` (a valid day; ignored). Anything else — another `FREQ`, another key, a numbered `BYDAY`, `COUNT` together with `UNTIL`, a missing `FREQ`, a malformed part — → empty (unsupported: only the DTSTART occurrence, counted in `unsupportedRules`). `byDay` is returned sorted Monday → Sunday, without duplicates.
13. Expansion (`IcsOccurrences.expand`): occurrences are generated in the event's local time (so a weekly 20:15 New York game stays at 20:15 across DST). The DTSTART is always the first instance and counts toward `COUNT`. `DAILY`/`WEEKLY` without `BYDAY`: the n-th instance is DTSTART + n × `INTERVAL` days (weekly: × 7). `WEEKLY` with `BYDAY`: weeks start on the Monday on or before DTSTART; week w = 0, `INTERVAL`, 2 × `INTERVAL`, …; within a week the listed days in order at DTSTART's time; candidates not after DTSTART are skipped. Generation stops at the first candidate that would exceed `COUNT`, lies after `UNTIL` (`Date` until: candidate date after it; `Utc`: instant after it; `Local`: local date-time after it), or starts at or after the window end; hard stop after 50 000 candidates. `EXDATE` removes an instance after counting (RFC 5545 §3.8.5.1): a `Date` exdate removes instances on that local date, other exdates remove the instance with that start instant. For an event whose `UID` has override events (`RECURRENCE-ID` present), the master's instance whose start instant equals an override's `RECURRENCE-ID` instant is removed; override events never recur and are emitted as single events with their own start/end. An instance is emitted when its end is after the window start and its start is before the window end. The result is sorted by start, then summary; a null summary becomes `""`.

**Calendar URL policy (normative).**
- `parse(raw)`: null/blank → `IllegalArgumentException("Enter a calendar link")`; stripped length > 2 048 → `That calendar link is too long`; not a `java.net.URI` → `That is not a valid link`; scheme `webcal`/`webcals` (case-insensitive) → the same URI with scheme `https`; scheme other than `http`/`https` → `Use an http, https or webcal link`; raw user info present → `Links with a user name or password are not supported; use the calendar's secret link instead`; no host → `That is not a valid link`.
- `checkAddress(uri)`: host with `[`/`]` removed; `resolver.resolve(host)` (default `InetAddress::getAllByName`); `UnknownHostException` → `CalendarFetchException(UNREACHABLE, "Could not find <host>")`; any resolved address that `isAnyLocalAddress()`, `isLinkLocalAddress()`, `isMulticastAddress()`, or `isLoopbackAddress()` while loopback is not allowed → `CalendarFetchException(BLOCKED, "Home Control does not load calendars from <host>: that address belongs to this machine or its network link")`. Private LAN addresses are allowed.

**Calendar fetch (normative).** Client: `HttpClient.Redirect.NEVER`, connect timeout `connectTimeoutSeconds`. Each hop: `checkAddress`; `GET` with headers `Accept: text/calendar, text/plain;q=0.9, */*;q=0.5` and `User-Agent: HomeControl`, request timeout `requestTimeoutSeconds`, body as `InputStream` (always closed). Status 301/302/303/307/308 with `Location` → `parse(current.resolve(location))` (any failure → `BAD_RESPONSE "<host> redirected to a link Home Control does not follow"`), at most `maxRedirects` (3) hops (`BAD_RESPONSE "The calendar link redirected too many times"`); a redirect without `Location` → `BAD_RESPONSE "<host> answered HTTP <status>"`. 401/403 → `UNAUTHORIZED "<host> refused access to the calendar"`; 404/410 → `NOT_FOUND "<host> has no calendar at that link"`; other non-200 → `BAD_RESPONSE "<host> answered HTTP <status>"`. Body: read at most `maxBytes + 1` bytes; more than `maxBytes` → `TOO_LARGE "The calendar is larger than <maxBytes / 1 048 576> MB"`; decoded with the `Content-Type` `charset` when `Charset.isSupported`, else UTF-8. `IOException` (including timeouts) → `UNREACHABLE "Could not reach <host>"`; `InterruptedException` → re-interrupt, `UNREACHABLE`. `<host>` is always the host of the hop being fetched; no message, log line or exception contains a path or query.

**`sports.json` (normative).** Pretty-printed, written atomically (temp file in the same directory, `Files.move` with `ATOMIC_MOVE` and `REPLACE_EXISTING`), fields in this order:

```json
{
  "version" : 1,
  "timeZone" : "Europe/Berlin",
  "calendars" : [ {
    "id" : "c-3f9a1c2b7d4e",
    "label" : "Bundesliga 2026/27",
    "host" : "calendar.example.org",
    "provider" : "dazn",
    "addedAt" : "2026-09-16T10:00:00Z"
  } ],
  "theSportsDb" : {
    "key" : "free",
    "competitions" : [ {
      "leagueId" : "4331",
      "name" : "German Bundesliga",
      "sport" : "Soccer",
      "country" : "Germany",
      "badge" : "https://r2.thesportsdb.com/images/media/league/badge/teqh1b1679952008.png",
      "provider" : "dazn",
      "addedAt" : "2026-09-16T10:05:00Z"
    } ]
  }
}
```

Read rules: missing file → `SportsSettings.empty()` (`timeZone` null, no calendars, `FREE`, no competitions); unparsable JSON, a non-object root or `version` ≠ 1 → `StorageException("Could not read sports settings in <file>; fix or delete it", cause)` (a larger version: `Sports settings in <file> were written by a newer Home Control; fix or delete it`). `timeZone`: kept only when `SportsTimeZones.parse` accepts it (else null, WARN). Calendars: `id` `^c-[0-9a-f]{12}$`, `label` non-blank ≤ 80, `host` non-blank ≤ 253; otherwise skipped with a WARN naming the index; duplicate ids after the first skipped. Competitions: `leagueId` `^[0-9]{1,9}$` (else skipped), `name` non-blank ≤ 120 (else `Competition <leagueId>`), `sport`/`country` stripped, blank → null, longer than 60 → null, `badge` kept only as an absolute `https://` URI; duplicate `leagueId`s skipped. `provider` on both: a `StreamingProviders.KNOWN` key, else null. `addedAt` unparsable or missing → `Instant.EPOCH`. `theSportsDb.key`: `personal` → `PERSONAL`, anything else or missing → `FREE`. Nulls are written as JSON `null`; an empty competitions list is written as `[ ]`. The calendar URL is never written to this file.

**Sports item rules (normative, extended by Tasks 3 and 4).**
- `EventPhase.of(event, now, zone)`: `tomorrow` = start of the day after `LocalDate.ofInstant(now, zone)` in `zone`. `FINISHED` status or `endsAt ≤ now` → `ENDED`. All-day: `startsAt ≤ now` or `startsAt < tomorrow` → `ALL_DAY_TODAY`, else `LATER`. Timed: status `LIVE` or `startsAt ≤ now` → `LIVE`; `startsAt < tomorrow` → `UPCOMING_TODAY`; else `LATER`.
- Subtitle = `<when> · <competition label>` where `<when>` is: `LIVE` → `Live`; `ALL_DAY_TODAY` → `Today`; `UPCOMING_TODAY` → `DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)` in `zone` and `locale` (de-DE: `18:30`); `ENDED` → `Ended`; `LATER` → all-day: `ofLocalizedDate(FormatStyle.MEDIUM)` of `allDayDate`, timed: `ofLocalizedDateTime(FormatStyle.SHORT)` in `zone`. Competition label = `settings.labelFor(event.competitionKey())` (calendar label or competition name), else `Sports`.
- `toItem` = `new ContentItem(event.itemId(), "sports", ContentKind.LIVE_EVENT, event.title(), subtitle, event.artwork(), List.of(), null, event.startsAt(), event.endsAt())`.
- Locale = `Locale.forLanguageTag(preferences.locale())`.

**ICS event mapping (normative).** `SportsEvent(itemId, "calendar:<calendarId>", title, startsAt, endsAt, allDayDate, null, SCHEDULED)` with `title` = summary stripped, blank → `Event`, longer than 200 characters → first 199 characters + `…`; `itemId` = `ics:<calendarId>:` + the first 16 lower-case hex characters of SHA-256 (UTF-8) of `<uid>|<startsAt epoch seconds>`, or `no-uid|<summary>|<startsAt epoch seconds>` when `uid` is null. Example: calendar `c-3f9a1c2b7d4e`, UID `bl-2026-04-02@fixtures.example`, start `2026-09-19T13:30:00Z` (epoch 1789824600) → `ics:c-3f9a1c2b7d4e:069e696917c4a665`.

**Calendar schedule (normative).** `events()` (synchronized): for each configured calendar in settings order: refetch when there is no cached parse, or `fetchedAt + calendar.refresh ≤ now`, and — after a failure — only when `lastAttempt + 10 min ≤ now`. The URL comes from `SecretStore.secret(SportsCalendars.secretName(id))`; missing → error `The link for <label> is missing; remove the calendar and add it again`. Fetch → `IcsParser.parse`; `IcsFormatException` → `CalendarFetchException(NOT_A_CALENDAR, message)`; a `ContentSourceException` is recorded as the calendar's error (the last good parse is kept, WARN log with calendar id and kind only). Every cached parse is expanded with window [now − 1 day, now + 8 days], fallback zone `zones.effective()`, default duration `default-event-duration`. A calendar "succeeded" when it has a cached parse. Errors are prefixed with the label: `<label>: <message>`. `find(itemId)` reads the last computed events (a `ConcurrentHashMap` replaced after each `events()`); when `events()` has never run it calls it once. `status(id)` = `FeedStatus(fetchedAt or null, events of that calendar in the window, error or null, unsupportedRules, unknownZones, skippedEvents)`. `prime` stores a parse fetched by `SportsCalendars.add` with `fetchedAt = now`; `forget` drops the calendar's cache and events. Cached calendars no longer in settings are dropped on the next `events()`.

**Adding a calendar (normative), `SportsCalendars.add`, in this order:** `policy.parse(url)`; label stripped, longer than 80 → `Keep the name under 80 characters`; `calendars.size() ≥ maxCalendars` → `You can add up to <n> calendars`; an existing calendar whose stored secret equals `uri.toString()` → `That calendar is already added`; when `!login.loginRequired()` → `login.checkNewPassword(loginPassword, confirmation)` (before any network call); `fetcher.fetch(uri)` then `IcsParser.parse` (`IcsFormatException` → `IllegalArgumentException(message)`; `CalendarFetchException` propagates); id = `c-` + 12 lower-case hex from `SecureRandom` (retry on collision); `login.storeSecrets(Map.of(secretName(id), uri.toString()), loginPassword, confirmation, http)`; label default: the calendar's `X-WR-CALNAME` (cut to 80) else `uri.getHost()`; `settings.update(add CalendarEntry(id, label, host, null, clock.instant()))` — if that throws, `login.removeSecrets(List.of(secretName(id)))` and rethrow; `schedule.prime(id, parsed)`. `remove(id)`: unknown → `No calendar <id>`; `settings.update(remove)`, `login.removeSecrets(List.of(secretName(id)))`, `schedule.forget(id)`; returns the removed entry.

- [ ] **Step 1: Configuration and fixtures**

`src/main/resources/application.yaml` — under the existing `home-control:` key add:

```yaml
  sports:
    enabled: true
    # Blank: the time zone chosen in Setup, else the container's TZ.
    time-zone: ""
    rail-size: 30
    max-calendars: 10
    max-competitions: 10
    default-event-duration: 120m
    calendar:
      refresh: 6h
      connect-timeout-seconds: 5
      request-timeout-seconds: 15
      max-bytes: 5242880
      max-redirects: 3
      # Loopback addresses reach this box's own services; allow only if the calendar is served here.
      allow-loopback: false
```

`src/test/resources/application.yaml` — the same block with `connect-timeout-seconds: 1` and `request-timeout-seconds: 2`.

`sources/sports/SportsProperties.java`:

```java
package dev.andre.homecontrol.sources.sports;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("home-control.sports")
public record SportsProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("") String timeZone,
                               @DefaultValue("30") int railSize,
                               @DefaultValue("10") int maxCalendars,
                               @DefaultValue("10") int maxCompetitions,
                               @DefaultValue("120m") Duration defaultEventDuration,
                               @DefaultValue Calendar calendar) {

    public record Calendar(@DefaultValue("6h") Duration refresh,
                           @DefaultValue("5") int connectTimeoutSeconds,
                           @DefaultValue("15") int requestTimeoutSeconds,
                           @DefaultValue("5242880") int maxBytes,
                           @DefaultValue("3") int maxRedirects,
                           @DefaultValue("false") boolean allowLoopback) {
    }
}
```

(Task 2 appends a `TheSportsDb theSportsDb` component.)

Fixtures under `src/test/resources/fixtures/ics/` (UTF-8, LF line endings; tests derive CRLF/BOM variants). Reference clock for tests: **2026-09-19T14:00:00Z** (Saturday, 16:00 in Berlin).

`bundesliga.ics` (the two spaces before `im Stream` are intentional: one is the fold marker, one is content):

```text
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//fixtures.example//Bundesliga//EN
CALSCALE:GREGORIAN
METHOD:PUBLISH
X-WR-CALNAME:Bundesliga 2026/27
X-WR-TIMEZONE:Europe/Berlin
BEGIN:VTIMEZONE
TZID:Europe/Berlin
BEGIN:DAYLIGHT
TZOFFSETFROM:+0100
TZOFFSETTO:+0200
TZNAME:CEST
DTSTART:19700329T020000
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
END:DAYLIGHT
BEGIN:STANDARD
TZOFFSETFROM:+0200
TZOFFSETTO:+0100
TZNAME:CET
DTSTART:19701025T030000
RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
END:STANDARD
END:VTIMEZONE
BEGIN:VEVENT
UID:bl-2026-04-01@fixtures.example
DTSTAMP:20260901T080000Z
DTSTART;TZID=Europe/Berlin:20260918T203000
DTEND;TZID=Europe/Berlin:20260918T222500
SUMMARY:FC Bayern München – 1. FC Union Berlin
LOCATION:Allianz Arena\, München
END:VEVENT
BEGIN:VEVENT
UID:bl-2026-04-02@fixtures.example
DTSTAMP:20260901T080000Z
DTSTART:20260919T133000Z
DTEND:20260919T152500Z
SUMMARY:SV Werder Bremen – FC Augsburg
END:VEVENT
BEGIN:VEVENT
UID:bl-2026-04-03@fixtures.example
DTSTAMP:20260901T080000Z
DTSTART;TZID="Europe/Berlin":20260919T183000
DURATION:PT1H55M
SUMMARY:Borussia Dortmund – RB Leipzig
DESCRIPTION:Topspiel am Samstagabend.\nLive laut Kalender.
END:VEVENT
BEGIN:VEVENT
UID:bl-2026-04-04@fixtures.example
DTSTAMP:20260901T080000Z
DTSTART;TZID=Europe/Berlin:20260920T173000
SUMMARY:VfB Stuttgart – SC Freiburg\, Topspiel des 4. Spieltags\; live
  im Stream
BEGIN:VALARM
ACTION:DISPLAY
SUMMARY:Reminder
TRIGGER:-PT30M
END:VALARM
END:VEVENT
BEGIN:VEVENT
UID:bl-2026-04-05@fixtures.example
DTSTAMP:20260901T080000Z
DTSTART;TZID=Europe/Berlin:20260919T200000
DTEND;TZID=Europe/Berlin:20260919T215500
STATUS:CANCELLED
SUMMARY:Hertha BSC – FC Schalke 04
END:VEVENT
END:VCALENDAR
```

`recurring.ics`:

```text
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//fixtures.example//Recurring//EN
X-WR-CALNAME:Weekly sport
X-WR-TIMEZONE:Europe/Berlin
BEGIN:VEVENT
UID:mnf@fixtures.example
DTSTART;TZID=America/New_York:20260907T201500
DTEND;TZID=America/New_York:20260907T233000
RRULE:FREQ=WEEKLY;COUNT=4
EXDATE;TZID=America/New_York:20260921T201500
SUMMARY:Monday Night Football
END:VEVENT
BEGIN:VEVENT
UID:darts@fixtures.example
DTSTART;TZID=Europe/London:20260917T190000
DTEND;TZID=Europe/London:20260917T230000
RRULE:FREQ=WEEKLY;BYDAY=TH,SA;UNTIL=20261003T235959Z
SUMMARY:Darts Premier League
END:VEVENT
BEGIN:VEVENT
UID:darts@fixtures.example
RECURRENCE-ID;TZID=Europe/London:20260919T190000
DTSTART;TZID=Europe/London:20260919T200000
DTEND;TZID=Europe/London:20260919T233000
SUMMARY:Darts Premier League (moved)
END:VEVENT
BEGIN:VEVENT
UID:vuelta@fixtures.example
DTSTART;VALUE=DATE:20260917
DTEND;VALUE=DATE:20260918
RRULE:FREQ=DAILY;UNTIL=20260920
SUMMARY:Vuelta stage
END:VEVENT
BEGIN:VEVENT
UID:club@fixtures.example
DTSTART:20260905T170000Z
DTEND:20260905T180000Z
RRULE:FREQ=MONTHLY;BYDAY=1SA
SUMMARY:Club meeting
END:VEVENT
BEGIN:VEVENT
UID:local@fixtures.example
DTSTART:20260919T150000
DTEND:20260919T170000
SUMMARY:Local kickoff
END:VEVENT
END:VCALENDAR
```

`outlook.ics` (no `X-WR-TIMEZONE`; the colon inside the first summary is content):

```text
BEGIN:VCALENDAR
PRODID:-//Microsoft Corporation//Outlook 16.0 MIMEDIR//EN
VERSION:2.0
BEGIN:VTIMEZONE
TZID:W. Europe Standard Time
BEGIN:STANDARD
DTSTART:16011028T030000
TZOFFSETFROM:+0200
TZOFFSETTO:+0100
END:STANDARD
END:VTIMEZONE
BEGIN:VEVENT
UID:040000008200E00074C5B7101A82E00800000000
DTSTART;TZID="W. Europe Standard Time":20260919T180000
DTEND;TZID="W. Europe Standard Time":20260919T200000
SUMMARY;LANGUAGE=de-DE:Handball: Finale
END:VEVENT
BEGIN:VEVENT
UID:f1-quali@fixtures.example
DTSTART;TZID=/mozilla.org/20050126_1/Europe/Paris:20260919T160000
DTEND;TZID=/mozilla.org/20050126_1/Europe/Paris:20260919T170000
SUMMARY:Formula 1 Qualifying
END:VEVENT
BEGIN:VEVENT
UID:mars@fixtures.example
DTSTART;TZID=Mars/Olympus_Mons:20260919T120000
DTEND;TZID=Mars/Olympus_Mons:20260919T130000
SUMMARY:Unknown zone match
END:VEVENT
END:VCALENDAR
```

`broken.ics` (deliberately truncated — no final `END:VEVENT`/`END:VCALENDAR`):

```text
BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:no-start@fixtures.example
SUMMARY:No start
END:VEVENT
BEGIN:VEVENT
UID:bad-date@fixtures.example
DTSTART:20260231T100000Z
SUMMARY:February 31st
END:VEVENT
BEGIN:VEVENT
SUMMARY:No uid but fine
DTSTART:20260919T100000Z
DTEND:20260919T110000Z
END:VEVENT
BEGIN:VEVENT
UID:truncated@fixtures.example
DTSTART:20260919T120000Z
SUMMARY:Never ends
```

`not-a-calendar.html`:

```html
<!doctype html>
<html><head><title>Sign in</title></head><body><p>Please sign in: your session expired.</p></body></html>
```

`src/test/resources/fixtures/sports/sports-v1.json` — the normative example above plus: a second calendar `{"id":"c-00000000000a","label":"Weekly sport","host":"nas.local","provider":"notaservice","addedAt":"yesterday"}` (provider dropped to null, addedAt EPOCH), an invalid calendar `{"id":"x","label":"Bad","host":"h"}`, a duplicate of `c-3f9a1c2b7d4e` with label `Duplicate`, and a second competition `{"leagueId":"4328","name":" ","sport":"Soccer","country":"England","badge":"http://insecure.example/badge.png","provider":null}`.

`src/test/java/dev/andre/homecontrol/sources/sports/calendar/FakeCalendarServer.java` — same design as G's `FakeTmdbServer` (JDK `HttpServer` on `127.0.0.1:0`, virtual-thread executor, canned responses keyed by path, every request recorded with method, path, query and lower-cased headers): `URI url(String path)` → `http://127.0.0.1:<port><path>`; `respond(path, status, contentType, String body)`; `respondFixture(path, fixtureName)` (from `/fixtures/ics/`, `text/calendar; charset=utf-8`); `respondBytes(path, status, contentType, byte[] body)`; `redirect(path, status, location)`; `delay(Duration)`; `List<Recorded> requests(path)`; `int count(path)`; unknown paths answer 404 `text/plain` `not found`; `close()` stops with delay 0.

- [ ] **Step 2: Write the failing tests**

`core/playback/ContentItemTest.java`:
- `sevenAndEightArgumentConstructorsHaveNoTimes`: both → `startsAt()` and `endsAt()` null; progress kept.
- `timesAreValidated`: `endsAt` without `startsAt` → `endsAt needs startsAt`; `endsAt` before `startsAt` → `endsAt must not be before startsAt`; equal instants accepted.
- `withPlayablesKeepsTheTimes`.

`web/ContentItemViewTest.java`:
- `carriesTimesAsIsoStrings`: item with `2026-09-19T13:30:00Z`/`2026-09-19T15:25:00Z` → view `startsAt` `2026-09-19T13:30:00Z`; serialised with the application's `JsonMapper` → the JSON object's field names in order end with `progress`, `startsAt`, `endsAt`; an item without times → both null.

Tile test (D2's rails fragment test) — add `liveEventTilesCarryTheirTimes`: a rail with a `LIVE_EVENT` item with times → the tile has `data-starts-at="2026-09-19T13:30:00Z"` and `data-ends-at="2026-09-19T15:25:00Z"`; a `MOVIE` tile has neither attribute.

`sources/sports/ics/IcsParserTest.java`:
- `unfoldsContinuationLines`: `unfold("A:1\n  two\n\tthree\nB:2")` → `["A:1 twothree", "B:2"]`; CRLF and lone CR behave the same.
- `unescapesText` (parameterized): `a\\,b` → `a,b`; `a\;b` → `a;b`; `a\\\\b` → `a\b`; `a\\nb` and `a\\Nb` → `a` newline `b`; `a\\xb` → `axb`; trailing `a\\` → `a\`.
- `parsesTimes`: `20260919` → `Date(2026-09-19)`; `20260919T133000Z` → `Utc(2026-09-19T13:30:00Z)`; `20260919t133000z` → the same; `20260919T183000` with tzid `Europe/Berlin` → `Local(2026-09-19T18:30, "Europe/Berlin")`; `20260231`, `20260919T240000`, `2026-09-19`, `` → `IcsFormatException` with message `Unreadable date or time`.
- `parsesDurations` (parameterized): `PT1H55M` → 115 min; `P1D` → 24 h; `P1W` → 7 days; `P1DT2H` → 26 h; `-PT1H`, `P`, `PT`, `PT0S`, `nonsense`, `PT99999999999999999999H` → null.
- `readsTheBundesligaFixture`: name `Bundesliga 2026/27`, time zone `Europe/Berlin`, 5 events in file order, skipped 0; event 2 `Utc` start/end; event 3 start `Local(2026-09-19T18:30, "Europe/Berlin")` (quotes stripped), duration 115 min, end null; event 4 summary exactly `VfB Stuttgart – SC Freiburg, Topspiel des 4. Spieltags; live im Stream` (the `VALARM` summary `Reminder` did not win), end and duration null; event 5 status `CANCELLED`; no event carries `VTIMEZONE`'s `DTSTART`/`RRULE`.
- `toleratesWindowsLineEndingsAndABom`: the Outlook fixture with every `\n` replaced by `\r\n` and U+FEFF prepended → 3 events; first summary `Handball: Finale` (a colon in the value and a `LANGUAGE` parameter), its start `Local(2026-09-19T18:00, "W. Europe Standard Time")`.
- `skipsBrokenEvents`: `broken.ics` → one event (`No uid but fine`, uid null); `skippedEvents` 3.
- `refusesWhatIsNotACalendar`: `not-a-calendar.html` text, `""`, and `BEGIN:VEVENT\nEND:VEVENT` → `IcsFormatException` with `That link did not return a calendar (.ics)`.
- `readsRecurrenceProperties`: `recurring.ics` → Monday Night Football `rrule` `FREQ=WEEKLY;COUNT=4`, one exdate `Local(2026-09-21T20:15, "America/New_York")`; the moved darts event has `recurrenceId` `Local(2026-09-19T19:00, "Europe/London")`; the Vuelta start is a `Date`.
- `capsTheNumberOfEvents`: a generated calendar with 5 001 minimal events → `IcsFormatException` `The calendar has more than 5000 events`.
- `quotedParametersMayContainSeparators`: `DTSTART;TZID="Odd;Zone:Name":20260919T100000` → tzid `Odd;Zone:Name`.

`sources/sports/ics/IcsZonesTest.java`:
- `resolvesIanaIds`: `Europe/Berlin`, `"Europe/Berlin"` (with quotes), `UTC`.
- `resolvesPathLikeIds`: `/mozilla.org/20050126_1/Europe/Paris` → `Europe/Paris`; `/citadel.org/20190914_1/Europe/London` → `Europe/London`.
- `resolvesWindowsNames` (parameterized over the whole map).
- `unknownOrBlankIsEmpty`: `Mars/Olympus_Mons`, ``, null.

`sources/sports/ics/IcsRecurrenceTest.java`:
- `parsesTheSupportedSubset`: `FREQ=WEEKLY;INTERVAL=2;BYDAY=SA,TH,SA;WKST=SU;COUNT=5` → WEEKLY, 2, 5, until null, `[THURSDAY, SATURDAY]`; `freq=daily;until=20260920` → DAILY, 1, null, `Date(2026-09-20)`.
- `rejectsEverythingElse` (parameterized → empty): `FREQ=MONTHLY;BYDAY=1SA`, `FREQ=YEARLY`, `FREQ=WEEKLY;BYDAY=1MO`, `FREQ=DAILY;BYDAY=MO`, `FREQ=WEEKLY;COUNT=2;UNTIL=20261231`, `INTERVAL=2`, `FREQ=WEEKLY;BYMONTH=9`, `FREQ=WEEKLY;INTERVAL=0`, `FREQ=WEEKLY;COUNT=abc`, `FREQ=WEEKLY;UNTIL=soon`, `FREQ=WEEKLY;;X`, ``, null.

`sources/sports/ics/IcsOccurrencesTest.java` (fallback zone `Europe/Berlin`, default duration 120 min):
- `expandsTheBundesligaFixture`: window [2026-09-18T14:00Z, 2026-09-27T14:00Z] → 4 occurrences in start order: `2026-09-18T18:30Z`–`20:25Z` (TZID Berlin, CEST), `2026-09-19T13:30Z`–`15:25Z`, `2026-09-19T16:30Z`–`18:25Z` (DURATION), `2026-09-20T15:30Z`–`17:30Z` (default duration); the cancelled event is absent; `unsupportedRules` 0, `unknownZones` 0; `allDayDate` null everywhere.
- `windowBoundsAreHalfOpen`: window [2026-09-19T15:25Z, 2026-09-19T16:30Z] → empty (one event ends exactly at the start, the next starts exactly at the end).
- `expandsTheRecurringFixture`: window [2026-09-01T00:00Z, 2026-10-10T00:00Z] → 15 occurrences:
  - Monday Night Football at `2026-09-08T00:15Z`, `2026-09-15T00:15Z`, `2026-09-29T00:15Z` (COUNT 4 includes the excluded 21 September), each ending 3 h 15 min later;
  - Darts Premier League at `2026-09-17T18:00Z`, `2026-09-24T18:00Z`, `2026-09-26T18:00Z`, `2026-10-01T18:00Z`, `2026-10-03T18:00Z` (Saturday 19 September removed by the override), each 4 h; and `Darts Premier League (moved)` at `2026-09-19T19:00Z`–`22:30Z`;
  - Vuelta stage all-day on 17, 18, 19, 20 September: starts `2026-09-16T22:00Z` … `2026-09-19T22:00Z`, each ending 24 h later, `allDayDate` set;
  - Club meeting once at `2026-09-05T17:00Z` (`unsupportedRules` 1);
  - Local kickoff `2026-09-19T13:00Z`–`15:00Z` (floating, read in `X-WR-TIMEZONE`).
- `windowLimitsRecurrences`: same fixture, window [2026-09-19T00:00Z, 2026-09-20T00:00Z] → exactly Vuelta 19 September (starts `2026-09-18T22:00Z`), Local kickoff (`13:00Z`), moved darts (`19:00Z`), Vuelta 20 September (starts `2026-09-19T22:00Z`, before the window end); the 18 September stage ends exactly at `2026-09-18T22:00Z` and is excluded.
- `wallClockSurvivesDst`: a calendar `DTSTART;TZID=Europe/Berlin:20261017T153000`, `DTEND …T173000`, `RRULE:FREQ=WEEKLY;COUNT=3` → `2026-10-17T13:30Z`, `2026-10-24T13:30Z`, `2026-10-31T14:30Z` (CET after 25 October).
- `resolvesOutlookZones`: `outlook.ics` with fallback `Europe/Berlin` → `Handball: Finale` `2026-09-19T16:00Z`, `Formula 1 Qualifying` `2026-09-19T14:00Z`, `Unknown zone match` `2026-09-19T10:00Z`; `unknownZones` 1.
- `intervalAndUntil` (window [2026-08-01T00:00Z, 2026-10-01T00:00Z]): `DTSTART:20260901T100000Z`, `DTEND:20260901T110000Z`, `RRULE:FREQ=DAILY;INTERVAL=3;UNTIL=20260910T100000Z` → 1, 4, 7, 10 September at 10:00Z (UNTIL inclusive).
- `dateExdateOnAllDaySeries`: `DTSTART;VALUE=DATE:20260917`, `RRULE:FREQ=DAILY;COUNT=3`, `EXDATE;VALUE=DATE:20260918` → 17 and 19 September.
- `runawayRulesStop`: `DTSTART:18000101T100000Z`, `DTEND:18000101T110000Z`, `RRULE:FREQ=DAILY` with a 2026 window → returns without error and without occurrences (50 000 daily steps end in 1936).
- `zeroLengthUsesTheDefault`: `DTEND` equal to `DTSTART` → 120 min.

`sources/sports/calendar/CalendarUrlPolicyTest.java`:
- `acceptsHttpHttpsAndWebcal`: `https://calendar.example.org/a.ics` unchanged; `webcal://fixtur.es/de/team.ics?x=1` → `https://fixtur.es/de/team.ics?x=1`; `WEBCALS://Example.org/x` → `https://Example.org/x`; `http://192.168.1.20:5232/user/sport/` unchanged.
- `rejectsBadLinks` (parameterized → message): blank → `Enter a calendar link`; 2 049 characters → `That calendar link is too long`; `ftp://example.org/a.ics` → `Use an http, https or webcal link`; `https://user:pw@example.org/a.ics` → `Links with a user name or password are not supported; use the calendar's secret link instead`; `https:///a.ics` and `not a url` → `That is not a valid link`.
- `blocksMachineAndLinkAddresses` (resolver stub returning the given literal; each → `CalendarFetchException` kind `BLOCKED`, message contains the host and `belongs to this machine`): `127.0.0.1`, `::1`, `0.0.0.0`, `169.254.169.254`, `fe80::1`, `224.0.0.251`, `::ffff:127.0.0.1`.
- `allowsLanAndPublicAddresses`: `192.168.1.20`, `10.0.0.5`, `172.16.3.4`, `fd12:3456::1`, `100.64.1.1`, `93.184.216.34` → no exception.
- `blocksWhenAnyResolvedAddressIsBlocked`: resolver returns `[93.184.216.34, 127.0.0.1]` for `rebind.example` → `BLOCKED`.
- `loopbackCanBeAllowed`: `new CalendarUrlPolicy(true, stub)` → `127.0.0.1` passes; `169.254.169.254` still blocked.
- `unknownHostsAreUnreachable`: resolver throws `UnknownHostException` → kind `UNREACHABLE`, message `Could not find nowhere.invalid`.

`sources/sports/calendar/CalendarFetcherTest.java` (FakeCalendarServer; policy with `allowLoopback` true; properties timeouts 1 s / 2 s, `maxBytes` 2 097 152, `maxRedirects` 3):
- `fetchesACalendar`: `/private/token-abc123/basic.ics` → text starts with `BEGIN:VCALENDAR`; the request had `accept` containing `text/calendar` and `user-agent` `HomeControl`.
- `decodesTheDeclaredCharset`: body `SUMMARY:Köln` encoded ISO-8859-1 with `text/calendar; charset=ISO-8859-1` → contains `Köln`; unknown charset `x-nope` → decoded as UTF-8 without error.
- `followsValidatedRedirects`: `/old` 301 → `/new` (relative `Location`), `/new` 302 → absolute `/cal.ics` → fetched; 4 chained redirects → `The calendar link redirected too many times`.
- `refusesRedirectsToBlockedOrForeignSchemes`: fetcher whose policy allows loopback and uses a stub resolver (`127.0.0.1` → `127.0.0.1`, `metadata.test` → `169.254.169.254`); `/hop` redirects to `http://metadata.test/latest` → `CalendarFetchException` `BLOCKED` naming `metadata.test` (the resolver was asked, no connection attempted); `/ftp` redirects to `ftp://x/y` → `BAD_RESPONSE` `127.0.0.1 redirected to a link Home Control does not follow`.
- `mapsStatuses` (parameterized status → kind, message): 401 → `UNAUTHORIZED`, `127.0.0.1 refused access to the calendar`; 403 same; 404 and 410 → `NOT_FOUND`, `127.0.0.1 has no calendar at that link`; 500 → `BAD_RESPONSE`, `127.0.0.1 answered HTTP 500`.
- `capsTheBody`: a 2 097 153-byte body → `TOO_LARGE` `The calendar is larger than 2 MB`; a 2 097 152-byte body that starts with `BEGIN:VCALENDAR` is returned.
- `timesOut`: `delay(3 s)` → `UNREACHABLE` `Could not reach 127.0.0.1`.
- `neverLeaksThePath`: for every failure above the exception message and `toString()` do not contain `token-abc123`, `/private`, `/old`, or `?`.

`sources/sports/JsonFileSportsStoreTest.java` (`@TempDir`):
- `aMissingFileIsEmpty`: `load()` equals `SportsSettings.empty()`.
- `readsTheDocumentedShape`: copy `sports-v1.json` → time zone `Europe/Berlin`; calendars `[c-3f9a1c2b7d4e (label Bundesliga 2026/27, host calendar.example.org, provider dazn, addedAt 2026-09-16T10:00:00Z), c-00000000000a (provider null, addedAt EPOCH)]` (invalid and duplicate skipped); key `FREE`; competitions `[4331 with every field, 4328 name "Competition 4328", badge null, provider null]`.
- `roundTripsAndWritesPrettyJson`: `save(load())` → `load()` equal; text contains `"version" : 1`, `"theSportsDb" : {`, `"key" : "free"`; never contains `http://` or `https://calendar`.
- `writesAtomicallyAndLeavesNoTempFiles`: the directory contains exactly `sports.json`.
- `malformedFilesAreNamedErrors` (parameterized: `not json`, `[]`, `{"version":2}`) → `StorageException` whose message contains the path and `fix or delete it` (and `newer Home Control` for version 2).
- `invalidTimeZonesAreDropped`: `"timeZone":"Mars/Base"` → null.

`sources/sports/SportsSettingsServiceTest.java`:
- `loadsLazilyOnceAndCaches`: two `current()` calls read the file once (spy store).
- `updateSavesAndPublishesAfterwards`: `update(s -> s.withTimeZone("Europe/London"))` → file changed; exactly one `ContentChangedEvent("sports")`; the publisher is called after `save` returned (Mockito `inOrder`).
- `aFailingSaveKeepsTheOldStateAndPublishesNothing`: store `save` throws `StorageException` → exception propagates; `current()` unchanged; no event.

`sources/sports/SportsTimeZonesTest.java`:
- `storedWinsOverConfiguredOverSystem`: stored `Europe/London` + property `America/New_York` → London; none stored → New York, `chosen()` true; neither → `ZoneId.systemDefault()`, `chosen()` false.
- `parseAcceptsRegionIdsAndUtcOnly` (parameterized): `Europe/Berlin`, `America/Argentina/Buenos_Aires`, `UTC` → present; `GMT+2`, `EST`, `CET`, `Mars/Base`, `` , null → empty.

`sources/sports/EventPhaseTest.java` (zone `Europe/Berlin`, now `2026-09-19T14:00:00Z`):
- `timedEvents` (parameterized start, end, status → phase): `13:30Z–15:25Z SCHEDULED` → LIVE; `14:00Z–16:00Z` → LIVE (starts now); `16:30Z–18:25Z` → UPCOMING_TODAY; `2026-09-19T21:59Z–23:59Z` → UPCOMING_TODAY (23:59 Berlin); `2026-09-19T22:00Z–…` → LATER (midnight Berlin); `11:30Z–13:30Z` → ENDED; `12:00Z–13:59:59Z` → ENDED; `13:59:59Z–14:00Z` → ENDED (`endsAt ≤ now`); `15:00Z–17:00Z LIVE` → LIVE (status wins); `13:30Z–15:25Z FINISHED` → ENDED.
- `allDayEvents`: day 19 (`2026-09-18T22:00Z–2026-09-19T22:00Z`) → ALL_DAY_TODAY; day 20 → LATER; day 18 → ENDED; a multi-day 18–21 → ALL_DAY_TODAY.

`sources/sports/SportsItemsTest.java` (settings with calendar `c-3f9a1c2b7d4e` label `Bundesliga 2026/27`; zone Berlin; locale `de-DE`; now `2026-09-19T14:00:00Z`):
- `liveEventsSayLive`: Werder Bremen event → subtitle `Live · Bundesliga 2026/27`; item source `sports`, kind `LIVE_EVENT`, times copied, artwork null, playables empty, progress null.
- `upcomingEventsShowTheLocalTime`: Dortmund at `16:30Z` → `18:30 · Bundesliga 2026/27`; with zone `Europe/London` → `17:30 · …`; with locale `en-US` the time equals `DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US).withZone(zone).format(start)` (do not hard-code; the JDK uses a narrow no-break space).
- `laterAndEndedEvents`: Stuttgart 20 September → `DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.GERMANY).withZone(Berlin).format(start) + " · Bundesliga 2026/27"`; Bayern 18 September → `Ended · Bundesliga 2026/27`.
- `allDayEvents`: Vuelta 19 September → `Today · …`; 20 September → `ofLocalizedDate(MEDIUM)` of `2026-09-20` + ` · …`.
- `unknownCompetitionLabel`: competition key `calendar:c-ffffffffffff` → `… · Sports`.

`sources/sports/calendar/CalendarScheduleTest.java` (FakeCalendarServer serving `bundesliga.ics` at `/private/token-abc123/bl.ics` and `recurring.ics` at `/weekly.ics`; `SecretStore` mock returning those URLs for `sports.calendar.c-3f9a1c2b7d4e` and `sports.calendar.c-00000000000a`; settings service over `@TempDir` with those two calendars; mutable clock starting `2026-09-19T14:00:00Z`; zones fixed `Europe/Berlin`; refresh 6 h):
- `loadsEveryCalendarOnce`: `events()` → events from both (Werder Bremen `ics:c-3f9a1c2b7d4e:069e696917c4a665` present, competition key `calendar:c-3f9a1c2b7d4e`); `succeeded` 2, `errors` empty; a second `events()` makes no new request.
- `refetchesWhenStale`: advance 6 h → one more request per calendar.
- `aFailureKeepsTheLastGoodCopyAndRetriesAfterTenMinutes`: after a good load, advance 6 h and make `/weekly.ics` answer 500 → events still include Monday Night Football; `errors` = `[Weekly sport: 127.0.0.1 answered HTTP 500]`; advance 5 min → no new request; advance another 5 min → one new request.
- `aCalendarThatNeverLoadedIsAnError`: `/weekly.ics` 404 from the start → `succeeded` 1, `errors` = `[Weekly sport: 127.0.0.1 has no calendar at that link]`.
- `aMissingSecretIsExplained`: secret absent → error `Bundesliga 2026/27: The link for Bundesliga 2026/27 is missing; remove the calendar and add it again`; no request.
- `htmlIsNotACalendar`: `not-a-calendar.html` → error `…: That link did not return a calendar (.ics)`.
- `theWindowMovesWithTheClock`: at `2026-09-19T14:00Z` Bayern (18 September 18:30Z) is included; advance 2 days (a stale refetch happens, same content) → it is not.
- `findsItemsAndStatuses`: `find("ics:c-3f9a1c2b7d4e:069e696917c4a665")` present without calling `events()` first (it loads once); `find("ics:nope")` empty; `status("c-00000000000a")` → `fetchedAt` = clock, `events` > 0, `unsupportedRules` 1, error null.
- `primeAndForget`: `prime("c-3f9a1c2b7d4e", IcsParser.parse(fixture))` → the next `events()` makes no request for it; `forget` → its events disappear from `find`.
- `removedCalendarsAreDropped`: remove one calendar from settings → `events()` has only the other's events.

`sources/sports/calendar/SportsCalendarsTest.java` (real `CalendarFetcher` and `CalendarSchedule` against FakeCalendarServer; `LoginService` and `SecretStore` mocks; seeded `SecureRandom` stub):
- `addsACalendarAsASecret`: `loginRequired()` false; `add(new AddCalendar(server.url("/private/token-abc123/bl.ics").toString(), "", "household password", "household password"), http)` → `checkNewPassword` called before the fetch (`inOrder` with the fake's request count); `storeSecrets(Map.of("sports.calendar.<id>", url), "household password", "household password", http)`; entry id matches `^c-[0-9a-f]{12}$`, label `Bundesliga 2026/27` (from `X-WR-CALNAME`), host `127.0.0.1`, provider null; `sports.json` does not contain `token-abc123`; the schedule then serves its events without a second request; a `ContentChangedEvent("sports")` was published.
- `webcalLinksAreFetchedOverHttps`: parse-only check that `webcal://127.0.0.1:<port>/x.ics` becomes `https://…` (assert on `CalendarUrlPolicy.parse`; no fetch — the fake is plain HTTP).
- `labelsFallBackToTheHost`: serve `recurring.ics` with its `X-WR-CALNAME` line removed → label `127.0.0.1`; a given label ` My games ` → `My games`.
- `rejectsBadInput` (message; nothing stored, `storeSecrets` never called): URL `ftp://x/y`; label of 81 characters → `Keep the name under 80 characters`; an HTML link → `That link did not return a calendar (.ics)`; a 404 link → `127.0.0.1 has no calendar at that link` (`CalendarFetchException`); `checkNewPassword` throwing `PasswordRejectedException` → propagated and the fake received no request.
- `refusesDuplicatesAndTooManyCalendars`: an existing calendar whose secret equals the URL → `That calendar is already added`; `maxCalendars` 1 with one calendar → `You can add up to 1 calendars`.
- `laterCalendarsNeedNoNewPassword`: `loginRequired()` true → `checkNewPassword` never called; `storeSecrets(…, null, null, http)` passes the given (null) passwords through.
- `aFailedSaveRemovesTheSecret`: settings save throws → `removeSecrets(List.of("sports.calendar.<id>"))` and the exception propagates.
- `removesCalendarAndSecret`: `remove(id)` → entry gone, `removeSecrets(List.of("sports.calendar.<id>"))`, schedule forgot it; unknown id → `No calendar c-000000000000`.
- `requestToStringIsRedacted`: `AddCalendar.toString()` contains neither the URL nor the passwords.

`sources/sports/SportsContentSourceTest.java`:
- `isUnavailableWithoutFeeds`: no calendars → `available()` false, `rails()` empty.
- `availableWithACalendarButNoRailsYet`: one calendar → `available()` true, `rails()` empty (Task 4 adds the rail), `rail("live-today")` → `IllegalArgumentException`; id `sports`, name `Sports`; `searchable()` false.
- `readsItems`: schedule stub finds the Werder Bremen event → `item(id)` subtitle `Live · Bundesliga 2026/27` using the preferences' locale `de-DE`; unknown → empty.

`sources/sports/SportsSetupControllerTest.java` — `@WebMvcTest({SportsSetupController.class, SetupController.class, SportsSetupAdvice.class})` with `SetupController`'s mocks, `@MockitoBean SportsCalendars calendars`, `SportsSettingsService settings`, `SportsTimeZones zones`, `CalendarSchedule schedule`, `LoginService login`, and a nested `@TestConfiguration` providing `new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120), <calendar defaults>)`:
- `addingACalendarRedirectsWithAMessage`: POST `/setup/sources/sports/calendars` `url=https://calendar.example.org/private/token-abc123/bl.ics&label=&loginPassword=pw1234567890&loginPasswordConfirmation=pw1234567890` → `verify(calendars).add(new AddCalendar(url, "", "pw1234567890", "pw1234567890"), any())`; 302 `/setup#sports`; flash `sportsMessage` `Added Bundesliga 2026/27`.
- `errorsBecomeFlashErrorsWithoutTheLink` (parameterized exception → message): `IllegalArgumentException("Use an http, https or webcal link")`, `CalendarFetchException(NOT_FOUND, "calendar.example.org has no calendar at that link")`, `PasswordRejectedException("The two passwords do not match")`, `LoginRequiredException`, `StorageException("disk full", null)` → `Could not save sports settings`; no flash attribute value contains `token-abc123`; flash `sportsForm` is `Map.of("label", "")` only.
- `removeAndTimeZone`: `/calendars/c-3f9a1c2b7d4e/remove` → `Removed Bundesliga 2026/27`; `/time-zone` `timeZone=Europe/London` → `settings.update(…)` storing `Europe/London`, message `Times are shown in Europe/London`; `timeZone=` (blank) → stored null, message `Times are shown in <zones.effective()> (default)`; `timeZone=GMT+2` → `sportsError` `Use a time zone such as Europe/Berlin`, no update.
- `theSetupPageListsCalendars`: settings with two calendars, `schedule.status` → one loaded (`FeedStatus(2026-09-19T14:02Z, 23, null, 1, 0, 0)`) and one failed (`FeedStatus(null, 0, "nas.local answered HTTP 500", 0, 0, 0)`); zones effective `Europe/Berlin` chosen → body contains `id="sports"`, both labels and hosts, `23 events · updated 16:02`, `1 repeating event uses rules Home Control shows only once`, `Could not refresh: nas.local answered HTTP 500`, `action="/setup/sources/sports/calendars/c-3f9a1c2b7d4e/remove"`, `name="url"`, `value="Europe/Berlin"`; never `token-abc123` or `https://calendar`; with `loginRequired()` false → `name="loginPassword"`; true → no `loginPassword` field.
- `utcWarning`: zones effective `UTC`, not chosen → body contains `The server's clock is set to UTC. Choose your time zone so kick-off times are right.`

`sources/sports/SportsModuleSwitchTest.java` — `@SpringBootTest` with `home-control.sports.enabled=false` → no `SportsContentSource`, `SportsCalendars`, `SportsSetupController` beans; `GET /setup` renders without `id="sports"`; `sports.json` is never created in the data dir. A second nested class with the module on and no calendars → `ContentSources.find("sports")` present and `available()` false; `sports.json` still not created (nothing is written until a change).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*' --tests 'dev.andre.homecontrol.core.playback.ContentItemTest' --tests 'dev.andre.homecontrol.web.ContentItemViewTest'`
Expected: compilation failure — `sources.sports` does not exist; `ContentItem` has no `startsAt`.

- [ ] **Step 4: Core additions**

`core/playback/ContentItem.java` — replace the record header and constructors (keep the class Javadoc; add "`startsAt`/`endsAt` are set for scheduled items such as live events"):

```java
public record ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                          URI artwork, List<PlayableRef> playables, Double progress,
                          Instant startsAt, Instant endsAt) {

    public ContentItem {
        playables = playables == null ? List.of() : List.copyOf(playables);
        if (progress != null && (progress.isNaN() || progress < 0.0 || progress > 1.0)) {
            throw new IllegalArgumentException("progress must be between 0 and 1");
        }
        if (endsAt != null && startsAt == null) {
            throw new IllegalArgumentException("endsAt needs startsAt");
        }
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("endsAt must not be before startsAt");
        }
    }

    public ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                       URI artwork, List<PlayableRef> playables, Double progress) {
        this(id, sourceId, kind, title, subtitle, artwork, playables, progress, null, null);
    }

    public ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                       URI artwork, List<PlayableRef> playables) {
        this(id, sourceId, kind, title, subtitle, artwork, playables, null, null, null);
    }

    public ContentItem withPlayables(List<PlayableRef> replacement) {
        return new ContentItem(id, sourceId, kind, title, subtitle, artwork, replacement, progress, startsAt, endsAt);
    }
}
```

`web/ContentItemView.java` — add components `String startsAt, String endsAt` at the end; `of` passes `item.startsAt() == null ? null : item.startsAt().toString()` and the same for `endsAt`. Any other place that calls the canonical `ContentItemView` constructor directly (e.g. D7's fakes) passes `null, null`.

`fragments/rails.html` — in the `tile` fragment's `th:attr` append `,data-starts-at=${item.startsAt()},data-ends-at=${item.endsAt()}` (the view's strings; `th:attr` drops nulls).

- [ ] **Step 5: The ICS parser**

`sources/sports/ics/IcsFormatException.java`: `public class IcsFormatException extends RuntimeException { public IcsFormatException(String message) { super(message); } }` — Javadoc "Message is user-facing and never contains calendar content beyond counts."

`sources/sports/ics/IcsTime.java`:

```java
package dev.andre.homecontrol.sources.sports.ics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** An iCalendar DATE or DATE-TIME as written (RFC 5545 §3.3.4, §3.3.5); zones are applied at expansion. */
public sealed interface IcsTime {

    record Date(LocalDate date) implements IcsTime {
    }

    /** Wall-clock time; {@code tzid} null means floating (read in the calendar's zone). */
    record Local(LocalDateTime dateTime, String tzid) implements IcsTime {
    }

    record Utc(Instant instant) implements IcsTime {
    }
}
```

`sources/sports/ics/IcsEvent.java` — the record; compact constructor `exdates = exdates == null ? List.of() : List.copyOf(exdates)`.
`sources/sports/ics/IcsCalendar.java` — the record; compact constructor copies `events`.
`sources/sports/ics/IcsOccurrence.java` — the record.

`sources/sports/ics/IcsParser.java`:

```java
package dev.andre.homecontrol.sources.sports.ics;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small RFC 5545 reader: VEVENT start, end, duration, summary, uid, status and the
 * recurrence properties Home Control expands. Everything else is ignored. Pure; no I/O.
 */
public final class IcsParser {

    static final int MAX_EVENTS = 5_000;
    static final String NOT_A_CALENDAR = "That link did not return a calendar (.ics)";

    private static final Pattern DATE = Pattern.compile("^\\d{8}$");
    private static final Pattern DATE_TIME = Pattern.compile("^(\\d{8})T(\\d{6})(Z?)$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final Pattern DURATION = Pattern.compile(
            "^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$");

    record ContentLine(String name, Map<String, String> params, String value) {
    }

    private IcsParser() {
    }

    public static IcsCalendar parse(String text) {
        if (text == null) {
            throw new IcsFormatException(NOT_A_CALENDAR);
        }
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        Deque<String> stack = new ArrayDeque<>();
        List<ContentLine> current = null;
        List<IcsEvent> events = new ArrayList<>();
        String name = null;
        String zone = null;
        int skipped = 0;
        boolean sawCalendar = false;
        for (String raw : unfold(body)) {
            if (raw.isBlank()) {
                continue;
            }
            ContentLine line = contentLine(raw);
            if (line == null) {
                continue;
            }
            switch (line.name()) {
                case "BEGIN" -> {
                    String component = line.value().strip().toUpperCase(Locale.ROOT);
                    if (stack.isEmpty()) {
                        if (!component.equals("VCALENDAR")) {
                            throw new IcsFormatException(NOT_A_CALENDAR);
                        }
                        sawCalendar = true;
                    }
                    stack.push(component);
                    if (component.equals("VEVENT") && stack.size() == 2) {
                        current = new ArrayList<>();
                    }
                }
                case "END" -> {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    String component = stack.pop();
                    if (component.equals("VEVENT") && stack.size() == 1 && current != null) {
                        try {
                            events.add(event(current));
                        } catch (IcsFormatException e) {
                            skipped++;
                        }
                        current = null;
                        if (events.size() > MAX_EVENTS) {
                            throw new IcsFormatException("The calendar has more than " + MAX_EVENTS + " events");
                        }
                    }
                }
                default -> {
                    if (stack.size() == 1 && "VCALENDAR".equals(stack.peek())) {
                        if (line.name().equals("X-WR-CALNAME")) {
                            name = unescape(line.value()).strip();
                        } else if (line.name().equals("X-WR-TIMEZONE")) {
                            zone = line.value().strip();
                        }
                    } else if (current != null && stack.size() == 2 && "VEVENT".equals(stack.peek())) {
                        current.add(line);
                    }
                }
            }
        }
        if (!sawCalendar) {
            throw new IcsFormatException(NOT_A_CALENDAR);
        }
        if (current != null) {
            skipped++;
        }
        return new IcsCalendar(blankToNull(name), blankToNull(zone), events, skipped);
    }

    public static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = null;
        for (String raw : text.split("\r\n|\n|\r", -1)) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                if (current != null) {
                    current.append(raw, 1, raw.length());
                }
                continue;
            }
            if (current != null) {
                lines.add(current.toString());
            }
            current = new StringBuilder(raw);
        }
        if (current != null) {
            lines.add(current.toString());
        }
        return lines;
    }

    static ContentLine contentLine(String raw) {
        boolean quoted = false;
        int colon = -1;
        List<Integer> semicolons = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == ';') {
                semicolons.add(i);
            } else if (!quoted && c == ':') {
                colon = i;
                break;
            }
        }
        if (colon <= 0) {
            return null;
        }
        int nameEnd = semicolons.isEmpty() ? colon : semicolons.getFirst();
        String name = raw.substring(0, nameEnd).strip().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int s = 0; s < semicolons.size(); s++) {
            int from = semicolons.get(s) + 1;
            int to = s + 1 < semicolons.size() ? semicolons.get(s + 1) : colon;
            String param = raw.substring(from, to);
            int eq = param.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = param.substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            params.putIfAbsent(param.substring(0, eq).strip().toUpperCase(Locale.ROOT), value);
        }
        return new ContentLine(name, Map.copyOf(params), raw.substring(colon + 1));
    }

    private static IcsEvent event(List<ContentLine> lines) {
        String uid = null;
        String summary = null;
        String rrule = null;
        String status = null;
        IcsTime start = null;
        IcsTime end = null;
        IcsTime recurrenceId = null;
        Duration duration = null;
        List<IcsTime> exdates = new ArrayList<>();
        for (ContentLine line : lines) {
            String tzid = line.params().get("TZID");
            switch (line.name()) {
                case "UID" -> uid = uid != null ? uid : blankToNull(unescape(line.value()).strip());
                case "SUMMARY" -> summary = summary != null ? summary : unescape(line.value()).strip();
                case "DTSTART" -> start = start != null ? start : parseTime(line.value(), tzid);
                case "DTEND" -> end = end != null ? end : parseTime(line.value(), tzid);
                case "DURATION" -> duration = duration != null ? duration : parseDuration(line.value());
                case "RRULE" -> rrule = rrule != null ? rrule : line.value().strip();
                case "RECURRENCE-ID" -> recurrenceId = recurrenceId != null ? recurrenceId : parseTime(line.value(), tzid);
                case "STATUS" -> status = line.value().strip().toUpperCase(Locale.ROOT);
                case "EXDATE" -> {
                    for (String value : line.value().split(",")) {
                        if (!value.isBlank()) {
                            exdates.add(parseTime(value, tzid));
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (start == null) {
            throw new IcsFormatException("An event has no start");
        }
        return new IcsEvent(uid, summary, start, end, duration, rrule, exdates, recurrenceId, status);
    }

    public static IcsTime parseTime(String value, String tzid) {
        String v = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        try {
            if (DATE.matcher(v).matches()) {
                return new IcsTime.Date(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE));
            }
            Matcher m = DATE_TIME.matcher(v);
            if (m.matches()) {
                LocalDateTime dateTime = LocalDateTime.of(
                        LocalDate.parse(m.group(1), DateTimeFormatter.BASIC_ISO_DATE), LocalTime.parse(m.group(2), TIME));
                return m.group(3).isEmpty()
                        ? new IcsTime.Local(dateTime, blankToNull(tzid))
                        : new IcsTime.Utc(dateTime.toInstant(ZoneOffset.UTC));
            }
        } catch (DateTimeException e) {
            throw new IcsFormatException("Unreadable date or time");
        }
        throw new IcsFormatException("Unreadable date or time");
    }

    public static Duration parseDuration(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = DURATION.matcher(value.strip().toUpperCase(Locale.ROOT));
        if (!m.matches() || "-".equals(m.group(1))) {
            return null;
        }
        try {
            Duration duration = Duration.ofDays(7 * number(m.group(2)) + number(m.group(3)))
                    .plusHours(number(m.group(4))).plusMinutes(number(m.group(5))).plusSeconds(number(m.group(6)));
            return duration.isZero() || duration.isNegative() ? null : duration;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    public static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == 'n' || next == 'N' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static long number(String digits) {
        return digits == null ? 0 : Long.parseLong(digits);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
```

`sources/sports/ics/IcsZones.java`:

```java
package dev.andre.homecontrol.sources.sports.ics;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

/** Maps the TZID spellings real calendar producers use to Java zones. VTIMEZONE bodies are not read. */
public final class IcsZones {

    private static final Map<String, String> WINDOWS = Map.ofEntries(
            entry("W. Europe Standard Time", "Europe/Berlin"),
            entry("Central Europe Standard Time", "Europe/Budapest"),
            entry("Central European Standard Time", "Europe/Warsaw"),
            entry("Romance Standard Time", "Europe/Paris"),
            entry("GMT Standard Time", "Europe/London"),
            entry("Greenwich Standard Time", "Atlantic/Reykjavik"),
            entry("E. Europe Standard Time", "Europe/Chisinau"),
            entry("FLE Standard Time", "Europe/Kyiv"),
            entry("Eastern Standard Time", "America/New_York"),
            entry("Central Standard Time", "America/Chicago"),
            entry("Mountain Standard Time", "America/Denver"),
            entry("Pacific Standard Time", "America/Los_Angeles"),
            entry("UTC", "UTC"),
            entry("Coordinated Universal Time", "UTC"));

    private IcsZones() {
    }

    public static Optional<ZoneId> resolve(String tzid) {
        if (tzid == null) {
            return Optional.empty();
        }
        String id = tzid.strip();
        if (id.length() >= 2 && id.startsWith("\"") && id.endsWith("\"")) {
            id = id.substring(1, id.length() - 1).strip();
        }
        if (id.isEmpty()) {
            return Optional.empty();
        }
        String windows = WINDOWS.get(id);
        if (windows != null) {
            return Optional.of(ZoneId.of(windows));
        }
        String[] segments = id.split("/");
        for (int i = 0; i < segments.length; i++) {
            String candidate = String.join("/", Arrays.copyOfRange(segments, i, segments.length));
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                return Optional.of(ZoneId.of(candidate));
            } catch (DateTimeException e) {
                // try a shorter suffix
            }
        }
        return Optional.empty();
    }
}
```

(If the JDK's tzdb lacks `Europe/Kyiv`, use `Europe/Kiev`; the test iterates the map and only asserts presence.)

`sources/sports/ics/IcsRecurrence.java`:

```java
package dev.andre.homecontrol.sources.sports.ics;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The RRULE subset Home Control expands (see the plan's ICS subset rule 12). */
public record IcsRecurrence(Frequency frequency, int interval, Integer count, IcsTime until, List<DayOfWeek> byDay) {

    public enum Frequency { DAILY, WEEKLY }

    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY, "WE", DayOfWeek.WEDNESDAY, "TH", DayOfWeek.THURSDAY,
            "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY, "SU", DayOfWeek.SUNDAY);

    public IcsRecurrence {
        byDay = List.copyOf(byDay);
    }

    public static Optional<IcsRecurrence> parse(String rule) {
        if (rule == null || rule.isBlank()) {
            return Optional.empty();
        }
        Frequency frequency = null;
        int interval = 1;
        Integer count = null;
        IcsTime until = null;
        Set<DayOfWeek> byDay = EnumSet.noneOf(DayOfWeek.class);
        for (String part : rule.strip().split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return Optional.empty();
            }
            String key = part.substring(0, eq).strip().toUpperCase(Locale.ROOT);
            String value = part.substring(eq + 1).strip().toUpperCase(Locale.ROOT);
            try {
                switch (key) {
                    case "FREQ" -> {
                        if (!value.equals("DAILY") && !value.equals("WEEKLY")) {
                            return Optional.empty();
                        }
                        frequency = Frequency.valueOf(value);
                    }
                    case "INTERVAL" -> {
                        interval = Integer.parseInt(value);
                        if (interval < 1 || interval > 1000) {
                            return Optional.empty();
                        }
                    }
                    case "COUNT" -> {
                        count = Integer.parseInt(value);
                        if (count < 1 || count > 10_000) {
                            return Optional.empty();
                        }
                    }
                    case "UNTIL" -> until = IcsParser.parseTime(value, null);
                    case "BYDAY" -> {
                        for (String token : value.split(",")) {
                            DayOfWeek day = DAYS.get(token.strip());
                            if (day == null) {
                                return Optional.empty();
                            }
                            byDay.add(day);
                        }
                    }
                    case "WKST" -> {
                        if (!DAYS.containsKey(value)) {
                            return Optional.empty();
                        }
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            } catch (NumberFormatException | IcsFormatException e) {
                return Optional.empty();
            }
        }
        if (frequency == null || (count != null && until != null)
                || (!byDay.isEmpty() && frequency != Frequency.WEEKLY)) {
            return Optional.empty();
        }
        return Optional.of(new IcsRecurrence(frequency, interval, count, until, List.copyOf(byDay)));
    }
}
```

(An empty part such as `;;` has no `=` and makes the rule unsupported, matching the test.)

`sources/sports/ics/IcsOccurrences.java`:

```java
package dev.andre.homecontrol.sources.sports.ics;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Expands parsed events into concrete occurrences inside a window (plan ICS subset rules 9–13). Pure. */
public final class IcsOccurrences {

    static final int MAX_STEPS = 50_000;

    public record Result(List<IcsOccurrence> occurrences, int unsupportedRules, int unknownZones) {
        public Result {
            occurrences = List.copyOf(occurrences);
        }
    }

    private IcsOccurrences() {
    }

    public static Result expand(IcsCalendar calendar, ZoneId fallback, Instant windowStart, Instant windowEnd,
                                Duration defaultDuration) {
        Zones zones = new Zones(IcsZones.resolve(calendar.timeZone()).orElse(fallback));
        Map<String, Set<Instant>> overridden = new HashMap<>();
        for (IcsEvent event : calendar.events()) {
            if (event.recurrenceId() != null && event.uid() != null) {
                overridden.computeIfAbsent(event.uid(), uid -> new HashSet<>()).add(zones.instant(event.recurrenceId()));
            }
        }
        List<IcsOccurrence> out = new ArrayList<>();
        int unsupported = 0;
        for (IcsEvent event : calendar.events()) {
            if ("CANCELLED".equals(event.status())) {
                continue;
            }
            boolean master = event.recurrenceId() == null;
            IcsRecurrence rule = null;
            if (master && event.rrule() != null) {
                Optional<IcsRecurrence> parsed = IcsRecurrence.parse(event.rrule());
                if (parsed.isEmpty()) {
                    unsupported++;
                }
                rule = parsed.orElse(null);
            }
            Set<Instant> skip = master && event.uid() != null ? overridden.getOrDefault(event.uid(), Set.of()) : Set.of();
            new Expansion(event, rule, zones, skip, windowStart, windowEnd, defaultDuration, out).run();
        }
        out.sort(Comparator.comparing(IcsOccurrence::startsAt).thenComparing(IcsOccurrence::summary));
        return new Result(out, unsupported, zones.unknown.size());
    }

    private static final class Zones {

        private final ZoneId calendarZone;
        private final Set<String> unknown = new HashSet<>();

        Zones(ZoneId calendarZone) {
            this.calendarZone = calendarZone;
        }

        ZoneId zoneOf(IcsTime time) {
            return switch (time) {
                case IcsTime.Utc ignored -> ZoneOffset.UTC;
                case IcsTime.Date ignored -> calendarZone;
                case IcsTime.Local local -> {
                    if (local.tzid() == null) {
                        yield calendarZone;
                    }
                    Optional<ZoneId> zone = IcsZones.resolve(local.tzid());
                    if (zone.isEmpty()) {
                        unknown.add(local.tzid());
                    }
                    yield zone.orElse(calendarZone);
                }
            };
        }

        static LocalDateTime local(IcsTime time) {
            return switch (time) {
                case IcsTime.Utc utc -> LocalDateTime.ofInstant(utc.instant(), ZoneOffset.UTC);
                case IcsTime.Date date -> date.date().atStartOfDay();
                case IcsTime.Local local -> local.dateTime();
            };
        }

        Instant instant(IcsTime time) {
            return local(time).atZone(zoneOf(time)).toInstant();
        }
    }

    private static final class Expansion {

        private final IcsEvent event;
        private final IcsRecurrence rule;
        private final Set<Instant> overridden;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final List<IcsOccurrence> out;
        private final ZoneId zone;
        private final LocalDateTime first;
        private final boolean allDay;
        private final long days;
        private final Duration length;
        private final Set<Instant> exInstants = new HashSet<>();
        private final Set<LocalDate> exDates = new HashSet<>();
        private int produced;

        Expansion(IcsEvent event, IcsRecurrence rule, Zones zones, Set<Instant> overridden, Instant windowStart,
                  Instant windowEnd, Duration defaultDuration, List<IcsOccurrence> out) {
            this.event = event;
            this.rule = rule;
            this.overridden = overridden;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.out = out;
            this.zone = zones.zoneOf(event.start());
            this.first = Zones.local(event.start());
            this.allDay = event.start() instanceof IcsTime.Date;
            if (allDay) {
                long d = 1;
                if (event.end() instanceof IcsTime.Date end) {
                    d = ChronoUnit.DAYS.between(first.toLocalDate(), end.date());
                } else if (event.duration() != null) {
                    d = event.duration().toDays();
                }
                this.days = Math.max(1, d);
                this.length = null;
            } else {
                Instant start = first.atZone(zone).toInstant();
                Duration l = event.end() != null ? Duration.between(start, zones.instant(event.end()))
                        : event.duration() != null ? event.duration() : defaultDuration;
                this.length = l.isZero() || l.isNegative() ? defaultDuration : l;
                this.days = 0;
            }
            for (IcsTime exdate : event.exdates()) {
                if (exdate instanceof IcsTime.Date date) {
                    exDates.add(date.date());
                } else {
                    exInstants.add(zones.instant(exdate));
                }
            }
        }

        void run() {
            produced = 1;
            emit(first);
            if (rule == null) {
                return;
            }
            if (rule.frequency() == IcsRecurrence.Frequency.WEEKLY && !rule.byDay().isEmpty()) {
                byWeekDays();
            } else {
                byFixedSteps();
            }
        }

        private void byWeekDays() {
            LocalDate weekStart = first.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int steps = 0;
            for (long week = 0; ; week += rule.interval()) {
                for (DayOfWeek day : rule.byDay()) {
                    if (++steps > MAX_STEPS) {
                        return;
                    }
                    LocalDateTime candidate = weekStart.plusWeeks(week).plusDays(day.getValue() - 1L)
                            .atTime(first.toLocalTime());
                    if (!candidate.isAfter(first)) {
                        continue;
                    }
                    if (!accept(candidate)) {
                        return;
                    }
                }
            }
        }

        private void byFixedSteps() {
            long stepDays = rule.frequency() == IcsRecurrence.Frequency.DAILY ? rule.interval() : 7L * rule.interval();
            for (long n = 1; n <= MAX_STEPS; n++) {
                if (!accept(first.plusDays(stepDays * n))) {
                    return;
                }
            }
        }

        private boolean accept(LocalDateTime candidate) {
            if (rule.count() != null && produced >= rule.count()) {
                return false;
            }
            Instant start = candidate.atZone(zone).toInstant();
            if (rule.until() != null && pastUntil(candidate, start)) {
                return false;
            }
            if (!start.isBefore(windowEnd)) {
                return false;
            }
            produced++;
            emit(candidate);
            return true;
        }

        private boolean pastUntil(LocalDateTime candidate, Instant start) {
            return switch (rule.until()) {
                case IcsTime.Date date -> candidate.toLocalDate().isAfter(date.date());
                case IcsTime.Utc utc -> start.isAfter(utc.instant());
                case IcsTime.Local local -> candidate.isAfter(local.dateTime());
            };
        }

        private void emit(LocalDateTime occurrence) {
            Instant start = occurrence.atZone(zone).toInstant();
            if (exInstants.contains(start) || exDates.contains(occurrence.toLocalDate()) || overridden.contains(start)) {
                return;
            }
            Instant end = allDay ? occurrence.plusDays(days).atZone(zone).toInstant() : start.plus(length);
            if (!end.isAfter(windowStart) || !start.isBefore(windowEnd)) {
                return;
            }
            out.add(new IcsOccurrence(event.uid(), event.summary() == null ? "" : event.summary(), start, end,
                    allDay ? occurrence.toLocalDate() : null));
        }
    }
}
```

- [ ] **Step 6: Calendar fetching**

`sources/sports/calendar/CalendarFetchException.java` — `extends ContentSourceException`, `enum Kind { BLOCKED, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, BAD_RESPONSE, TOO_LARGE, NOT_A_CALENDAR }`, constructors `(Kind, String)` and `(Kind, String, Throwable)`, `kind()`.

`sources/sports/calendar/CalendarUrlPolicy.java`:

```java
package dev.andre.homecontrol.sources.sports.calendar;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Which calendar links the server may fetch. LAN hosts are allowed (self-hosted calendars); this machine
 * (host networking), link-local (cloud metadata) and multicast addresses are not. Checked on every hop.
 */
public class CalendarUrlPolicy {

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static final int MAX_LENGTH = 2048;

    private final boolean allowLoopback;
    private final HostResolver resolver;

    public CalendarUrlPolicy(boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    public CalendarUrlPolicy(boolean allowLoopback, HostResolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
    }

    public URI parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Enter a calendar link");
        }
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("That calendar link is too long");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That is not a valid link", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("webcal") || scheme.equals("webcals")) {
            try {
                uri = new URI("https" + url.substring(uri.getScheme().length()));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("That is not a valid link", e);
            }
        } else if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Use an http, https or webcal link");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Links with a user name or password are not supported; use the calendar's secret link instead");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("That is not a valid link");
        }
        return uri;
    }

    public void checkAddress(URI uri) {
        String host = uri.getHost();
        String lookup = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(lookup);
        } catch (UnknownHostException e) {
            throw new CalendarFetchException(CalendarFetchException.Kind.UNREACHABLE, "Could not find " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || (address.isLoopbackAddress() && !allowLoopback)) {
                throw new CalendarFetchException(CalendarFetchException.Kind.BLOCKED, "Home Control does not load calendars from "
                        + host + ": that address belongs to this machine or its network link");
            }
        }
    }
}
```

(Note for the `blocksMachineAndLinkAddresses` test: `InetAddress.getByName("::ffff:127.0.0.1")` already yields an `Inet4Address`, so the loopback check covers IPv4-mapped forms.)

`sources/sports/calendar/CalendarFetcher.java` — implement the fetch rules above. Skeleton of the loop:

```java
    public String fetch(URI url) {
        URI current = url;
        for (int hop = 0; ; hop++) {
            policy.checkAddress(current);
            String host = current.getHost();
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                    .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                    .header("User-Agent", "HomeControl")
                    .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host, e);
            }
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (REDIRECTS.contains(status)) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) {
                        throw new CalendarFetchException(Kind.BAD_RESPONSE, host + " answered HTTP " + status);
                    }
                    if (hop >= properties.maxRedirects()) {
                        throw new CalendarFetchException(Kind.BAD_RESPONSE, "The calendar link redirected too many times");
                    }
                    try {
                        current = policy.parse(current.resolve(location).toString());
                    } catch (IllegalArgumentException e) {
                        throw new CalendarFetchException(Kind.BAD_RESPONSE,
                                host + " redirected to a link Home Control does not follow");
                    }
                    continue;
                }
                // 401/403, 404/410, other non-200 per the rules; then readNBytes(maxBytes + 1), size check, charset
            } catch (IOException e) {
                throw new CalendarFetchException(Kind.UNREACHABLE, "Could not reach " + host, e);
            }
        }
    }
```

with `REDIRECTS = Set.of(301, 302, 303, 307, 308)`. The exception cause must never be an exception whose message contains the URL: wrap `IOException`s **without** passing them as cause if their message contains the URI (the JDK's `HttpTimeoutException` message is `request timed out`, `ConnectException` has no URI — pass the cause; a `URISyntaxException`/`IllegalArgumentException` from `resolve` is not passed as cause). Log nothing here; callers log kind and calendar id only.

- [ ] **Step 7: Settings, zones, schedule and items**

`sources/sports/SportsEvent.java` — the record; compact constructor requires non-null `itemId`, `competitionKey`, `title`, `startsAt`, `endsAt`, `status`, and `!endsAt.isBefore(startsAt)`; `allDay()` = `allDayDate != null`.

`sources/sports/EventPhase.java`:

```java
package dev.andre.homecontrol.sources.sports;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Where an event stands relative to now, in the household's time zone. */
public enum EventPhase {
    LIVE, ALL_DAY_TODAY, UPCOMING_TODAY, LATER, ENDED;

    public static EventPhase of(SportsEvent event, Instant now, ZoneId zone) {
        if (event.status() == SportsEvent.Status.FINISHED || !event.endsAt().isAfter(now)) {
            return ENDED;
        }
        Instant tomorrow = LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant();
        if (event.allDay()) {
            return !event.startsAt().isAfter(now) || event.startsAt().isBefore(tomorrow) ? ALL_DAY_TODAY : LATER;
        }
        if (event.status() == SportsEvent.Status.LIVE || !event.startsAt().isAfter(now)) {
            return LIVE;
        }
        return event.startsAt().isBefore(tomorrow) ? UPCOMING_TODAY : LATER;
    }
}
```

`sources/sports/SportsSettings.java` — per Interfaces; compact constructor copies lists (nulls → empty) and defaults `keyKind` to `FREE`; `labelFor(key)`: `calendar:<id>` → that calendar's label, `thesportsdb:<leagueId>` → that competition's name; `providerFor(key)` likewise (empty when null).

`sources/sports/JsonFileSportsStore.java` — implement the read and write rules with a `JsonMapper` (indented output as the device registry does), writing an `ObjectNode` field by field in the documented order; the write mirrors `JsonFileDeviceRegistry.writeAll` (`Files.createTempFile(parent, "sports", ".json")`, write, move with `ATOMIC_MOVE` + `REPLACE_EXISTING`, delete the temp file on failure, `StorageException("Could not write sports settings to <file>", e)`).

`sources/sports/SportsSettingsService.java` — `private final Object lock`; `current()` loads lazily under the lock and caches; `update(op)`: under the lock `next = op.apply(current())`, `store.save(next)`, replace the cache; after the lock `events.publishEvent(new ContentChangedEvent("sports"))`; returns `next`.

`sources/sports/SportsTimeZones.java` — per Interfaces; `parse(id)`: null/blank → empty; `ZoneId.of(id.strip())` (`DateTimeException` → empty); present only when the resulting `getId()` contains `/` or equals `UTC`.

`sources/sports/calendar/FeedStatus.java` — the record.

`sources/sports/calendar/CalendarSchedule.java` — implement the calendar schedule rules. Per calendar keep `record Cached(IcsCalendar calendar, Instant fetchedAt, Instant lastAttempt, String error)` in a `ConcurrentHashMap<String, Cached>`; `events()` is `synchronized`, builds a new `Map<String, SportsEvent>` for `find` and a per-calendar `IcsOccurrences.Result` for `status`, and maps occurrences with the ICS event mapping (SHA-256 via `MessageDigest.getInstance("SHA-256")`, `HexFormat.of()`). Log failures at WARN as `Calendar {} could not be refreshed ({})` with the calendar id and the exception kind only.

`sources/sports/calendar/SportsCalendars.java` — implement the adding rules; `add` and `remove` are `synchronized` against double-submits; `AddCalendar.toString()` = `AddCalendar[label=<label>]`.

`sources/sports/SportsSchedule.java`:

```java
package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every configured feed as one list of events. Only fails when no feed has anything to show. */
public class SportsSchedule {

    private final CalendarSchedule calendars;

    public SportsSchedule(CalendarSchedule calendars) {
        this.calendars = calendars;
    }

    public boolean hasFeeds() {
        return calendars.hasCalendars();
    }

    public List<SportsEvent> events() {
        CalendarSchedule.Result result = calendars.events();
        if (result.feeds() > 0 && result.succeeded() == 0 && !result.errors().isEmpty()) {
            throw new ContentSourceException(result.errors().getFirst());
        }
        return new ArrayList<>(result.events());
    }

    public Optional<SportsEvent> find(String itemId) {
        return itemId.startsWith("ics:") ? calendars.find(itemId) : Optional.empty();
    }
}
```

(add `boolean hasCalendars()` to `CalendarSchedule`: settings has at least one calendar — no I/O.)

`sources/sports/SportsItems.java` — implement the sports item rules with `DateTimeFormatter`s built per call (`.withLocale(locale).withZone(zone)`).

`sources/sports/SportsContentSource.java` — per Interfaces: `rails()` → `List.of()`; `rail(id)` → `IllegalArgumentException("Sports has no rail '" + id + "'")`; `item(id)` → `schedule.find(id).map(e -> SportsItems.toItem(e, settings.current(), zones.effective(), locale(), clock.instant()))`.

- [ ] **Step 8: Setup and wiring**

`sources/sports/SportsSetupController.java` — `@Controller`, `@ConditionalOnProperty(name = "home-control.sports.enabled", havingValue = "true", matchIfMissing = true)`; handlers per Interfaces. Messages: `Added <label>`, `Removed <label>`, `Times are shown in <zone>` / `Times are shown in <zone> (default)`. `IllegalArgumentException`, `ContentSourceException`, `PasswordRejectedException`, `LoginRequiredException` → `sportsError` with their message (`LoginRequiredException` → `Log in again to change sources`); `IllegalStateException` (a concurrent first secret) → its message; `StorageException` → `Could not save sports settings` (WARN log with the exception). After an add error, flash `sportsForm` = `Map.of("label", label == null ? "" : label)` — never the URL. Time zone: `SportsTimeZones.parse` empty for a non-blank value → `Use a time zone such as Europe/Berlin`.

`sources/sports/SportsSetupAdvice.java` — `@ControllerAdvice(assignableTypes = SetupController.class)`, module condition, `ObjectProvider`s for `SportsSettingsService`, `SportsTimeZones`, `CalendarSchedule`, `LoginService`, `SportsProperties`; `@ModelAttribute("sports")` → `View` or null when unavailable. `timeZoneLooksUnset` = `!zones.chosen()` and the effective zone's `normalized()` is `ZoneOffset.UTC` (covers `UTC`, `Etc/UTC`, `GMT`). Status text per calendar: no status or `fetchedAt` null and no error → `Not loaded yet`; error → `Could not refresh: <error without the "<label>: " prefix>`; else `<n> events · updated <HH:mm in the effective zone>`; then, when `unsupportedRules` > 0, `; <k> repeating event uses rules Home Control shows only once` (plural `events use` for k ≠ 1); when `unknownZones` > 0, `; <k> unknown time zone(s)` as `1 unknown time zone` / `2 unknown time zones`; when `skippedEvents` > 0, `; <k> unreadable event(s)` likewise.

`src/main/resources/templates/fragments/sports-setup.html` — `<section id="sports" th:fragment="section">`, heading `Sports`; flash `sportsMessage` (`class="ok"`) and `sportsError` (`class="error" role="alert"`); the paragraph `Show live and upcoming sport from calendar links (.ics). Home Control does not know which service broadcasts an event.`; a time-zone form (`POST /setup/sources/sports/time-zone`, `<input name="timeZone" list="sports-zones" placeholder="Europe/Berlin" th:value="${sports.storedTimeZone()}">` with a `<datalist id="sports-zones">` of `Europe/Berlin`, `Europe/Vienna`, `Europe/Zurich`, `Europe/London`, `America/New_York`, `UTC`; text `Times are shown in <timeZone>`; the UTC warning when `timeZoneLooksUnset`); heading `Calendars`; `No calendars yet.` when empty, else `<ul class="sports-calendars">` with label, host (`<code>`), status and a `Remove` form (`aria-label` `Remove <label>`); an add form (`POST /setup/sources/sports/calendars`) with `url` (`type="url"`, required, `maxlength="2048"`, `inputmode="url"`, `autocomplete="off"`, placeholder `https://example.org/fixtures.ics`, never pre-filled), `label` (optional, `maxlength="80"`, value from `sportsForm.label`), the login-password fields only when `needsLoginPassword` under the text `A calendar link can contain a private token, so Home Control stores it as a secret and will require this login password from now on.` (`type="password"`, `minlength="10"`, `autocomplete="new-password"`), button `Add calendar`, and the hint `Links starting with webcal:// work too. Home Control only reads the calendar; it never changes it.` Buttons ≥ 44 px high (existing setup styles).

In `setup.html`, after the Pinned block:

```html
    <th:block th:if="${sports != null}">
        <section th:replace="~{fragments/sports-setup :: section}"></section>
    </th:block>
```

`sources/sports/SportsConfiguration.java` — `@Configuration`, the module condition, `@EnableConfigurationProperties(SportsProperties.class)`; beans: `JsonFileSportsStore(androidTvProperties.dataDir().resolve("sports.json"))`; `SportsSettingsService(store, events)`; `SportsTimeZones(settings, properties)`; `CalendarUrlPolicy(properties.calendar().allowLoopback())`; `CalendarFetcher(properties.calendar(), policy)`; `CalendarSchedule(settings, fetcher, secretStore, properties, zones, Clock.systemUTC())`; `SportsCalendars(settings, policy, fetcher, schedule, secretStore, loginService, properties, Clock.systemUTC(), new SecureRandom())`; `SportsSchedule(schedule)`; `SportsContentSource(settings, sportsSchedule, zones, sourcePreferencesService::current, Clock.systemUTC())`. Declare the `ContentSource` bean with its concrete type so C's `ContentSources` picks it up like Jellyfin's.

- [ ] **Step 9: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*' --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL (every existing `ContentItem`/`ContentItemView` user still compiles through the kept constructors).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/playback/ContentItem.java \
  src/main/java/dev/andre/homecontrol/web/ContentItemView.java \
  src/main/java/dev/andre/homecontrol/sources/sports \
  src/main/resources/templates/fragments/rails.html src/main/resources/templates/fragments/sports-setup.html \
  src/main/resources/templates/setup.html src/main/resources/application.yaml src/test/resources/application.yaml \
  src/test/java/dev/andre/homecontrol/core/playback/ContentItemTest.java \
  src/test/java/dev/andre/homecontrol/web src/test/java/dev/andre/homecontrol/sources/sports \
  src/test/resources/fixtures/ics src/test/resources/fixtures/sports
git commit -m "feat: sports source reads ICS calendar links into live events"
```

---

### Task 2: H2 · TheSportsDB fixtures

**Files:**
- Create: `sources/sports/thesportsdb/TheSportsDbConfiguration.java`, `TheSportsDbClient.java`, `TheSportsDbException.java`, `TheSportsDbKeys.java`, `League.java`, `TheSportsDbEventMapper.java`, `TheSportsDbSchedule.java`, `SportsCompetitions.java`, `TheSportsDbSetupController.java`
- Modify: `sources/sports/SportsProperties.java`, `sources/sports/SportsSchedule.java`, `sources/sports/SportsConfiguration.java`, `sources/sports/SportsSetupAdvice.java`, `src/main/resources/templates/fragments/sports-setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `sources/sports/thesportsdb/FakeTheSportsDbServer.java`, `TheSportsDbClientTest.java`, `LeagueTest.java`, `TheSportsDbEventMapperTest.java`, `TheSportsDbScheduleTest.java`, `SportsCompetitionsTest.java`, `TheSportsDbSetupControllerTest.java`; `sources/sports/SportsContentSourceTest.java`, `sources/sports/SportsModuleSwitchTest.java`, `sources/sports/SportsSetupControllerTest.java`; fixtures `src/test/resources/fixtures/thesportsdb/lookupleague-4331.json`, `lookupleague-4328.json`, `lookupleague-unknown.json`, `search_all_leagues-germany-soccer.json`, `eventsday-2026-09-18-4331.json`, `eventsday-2026-09-19-4331.json`, `eventsday-2026-09-19-4328.json`, `eventsday-empty.json`, `invalid-key.json`

**Interfaces:**
- Consumes: Task 1 (`SportsProperties`, `SportsSettings`, `SportsSettingsService`, `SportsTimeZones`, `SportsEvent`, `SportsSchedule`, `FeedStatus`, `SportsSetupAdvice`, `sports-setup.html`); `ContentSourceException` (C3); `SecretStore.secret`, `LoginService.loginRequired/checkNewPassword/storeSecrets/removeSecrets`, `PasswordRejectedException`, `LoginRequiredException` (C1); `SetupController` (A–D).
- Produces:
  - `SportsProperties` gains a last component `@DefaultValue TheSportsDb theSportsDb` with `record TheSportsDb(boolean enabled, URI apiBaseUrl, String freeKey, Duration fixturesTtl, int connectTimeoutSeconds, int requestTimeoutSeconds, Map<String, Duration> sportDurations)`; `Duration durationFor(String sport, Duration fallback)`.
  - `class TheSportsDbException extends ContentSourceException { enum Kind { UNREACHABLE, UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER_ERROR, BAD_RESPONSE } Kind kind(); }` with constructors `(Kind, String)`, `(Kind, String, Throwable)`.
  - `class TheSportsDbClient { TheSportsDbClient(SportsProperties.TheSportsDb); TheSportsDbClient(SportsProperties.TheSportsDb, HttpClient); JsonNode get(String key, String endpoint, Map<String,String> query); Optional<League> lookupLeague(String key, String leagueId); List<League> searchLeagues(String key, String country, String sport); List<JsonNode> eventsDay(String key, LocalDate utcDate, String leagueId); }`.
  - `record League(String id, String name, String sport, String country, URI badge)` with `static Optional<League> of(JsonNode node)`.
  - `class TheSportsDbKeys { TheSportsDbKeys(SportsSettingsService, SecretStore, SportsProperties); String current(); static final String SECRET = "sports.thesportsdb.key"; }`.
  - `final class TheSportsDbEventMapper { static Optional<SportsEvent> toEvent(JsonNode event, String leagueId, URI badge, ZoneId zone, Function<String, Duration> durations); }`.
  - `class TheSportsDbSchedule { TheSportsDbSchedule(TheSportsDbClient, TheSportsDbKeys, SportsSettingsService, SportsProperties, SportsTimeZones, Clock); boolean hasCompetitions(); Result events(); Optional<SportsEvent> find(String itemId); Optional<FeedStatus> status(String leagueId); void forget(String leagueId); void clear(); static Set<LocalDate> utcDates(Instant now, ZoneId zone); record Result(List<SportsEvent> events, List<String> errors, int feeds, int succeeded) }`.
  - `class SportsCompetitions { SportsCompetitions(SportsSettingsService, TheSportsDbClient, TheSportsDbKeys, TheSportsDbSchedule, LoginService, SportsProperties, Clock); CompetitionEntry add(String leagueId); CompetitionEntry remove(String leagueId); List<League> search(String country, String sport); void usePersonalKey(PersonalKey request, HttpServletRequest http); void useFreeKey(); record PersonalKey(String key, String loginPassword, String loginPasswordConfirmation) }` (redacted `toString`).
  - `SportsSchedule(CalendarSchedule calendars, TheSportsDbSchedule competitions)` — `competitions` nullable (module off).
  - Endpoints (form posts, `302 /setup#sports`, flash `sportsMessage` / `sportsError`): `POST /setup/sources/sports/competitions` (`leagueId`), `POST /setup/sources/sports/competitions/{leagueId}/remove`, `POST /setup/sources/sports/thesportsdb/search` (`country`, `sport`; flash `sportsSearch`), `POST /setup/sources/sports/thesportsdb/key` (`key`, `loginPassword`, `loginPasswordConfirmation`), `POST /setup/sources/sports/thesportsdb/free-key`.
  - `SportsSetupAdvice.View` gains `boolean theSportsDbEnabled, String keyKind, List<CompetitionView> competitions, int maxCompetitions` with `record CompetitionView(String leagueId, String name, String sport, String country, String status)`; flash `sportsSearch` = `TheSportsDbSetupController.SearchView(String country, String sport, List<LeagueView> leagues)` with `record LeagueView(String id, String name, String sport, String country, boolean added)`.
  - Property `home-control.sports.thesportsdb.enabled` (default `true`).

**TheSportsDB wire format (normative).**
- URL: `apiBaseUrl` with trailing `/` removed, then `/<key>/<endpoint>`, then `?` and the query parameters in insertion order, each value `URLEncoder.encode(value, UTF_8)`. Every request: `GET`, headers `Accept: application/json` and `User-Agent: HomeControl`; request timeout `requestTimeoutSeconds`; client connect timeout `connectTimeoutSeconds`; `HttpClient.Redirect.NEVER`. A key not matching `^[A-Za-z0-9]{1,64}$` → `UNAUTHORIZED "That does not look like a TheSportsDB API key"` without a request.
- Status mapping: 200 → JSON object (a non-object, e.g. an empty body or an array, → `BAD_RESPONSE "TheSportsDB answered with something unexpected"`; unparsable → `BAD_RESPONSE "TheSportsDB answered with something that is not JSON"`); 401/403, and 400/404 whose body contains `api key` (case-insensitive) → `UNAUTHORIZED "TheSportsDB rejected the API key"`; 429 → `RATE_LIMITED "TheSportsDB is limiting requests; try again in a minute"`; 5xx → `SERVER_ERROR "TheSportsDB had a server error (HTTP <status>)"`; other → `BAD_RESPONSE "TheSportsDB answered HTTP <status>"`; I/O or timeout → `UNREACHABLE "Could not reach TheSportsDB"`; body over 2 MiB → `BAD_RESPONSE "TheSportsDB answered with more data than expected"`. No message, log line or exception cause message contains the URL or the key.
- `lookupLeague(key, id)`: `lookupleague.php` `id=<id>` → first element of `leagues` mapped with `League.of`; `leagues` null, missing, empty or unmappable → empty.
- `searchLeagues(key, country, sport)`: `search_all_leagues.php` `c=<country>` then `s=<sport>` (omitted when blank) → the `countries` array (null → empty) mapped with `League.of`, at most 50.
- `eventsDay(key, date, leagueId)`: `eventsday.php` `d=<yyyy-MM-dd>` then `l=<leagueId>` → the `events` array elements (null → empty).
- `League.of(node)`: `idLeague` `^[0-9]{1,9}$` required; `strLeague` stripped non-blank required (≤ 120); `strSport`, `strCountry` stripped, blank → null; `strBadge` kept only as an absolute `https://` URI.

**Event mapping (normative), `TheSportsDbEventMapper.toEvent`.**
1. `idEvent` (string or number) must match `^[0-9]{1,12}$` → item id `tsdb:<idEvent>`; `idLeague` must equal `leagueId`. Otherwise empty.
2. `strPostponed` equal to `yes` (ignoring case) → empty. Status = `strStatus` stripped, lower case (ROOT). In {`canc`, `cancelled`, `pst`, `postponed`, `abd`, `abandoned`, `awd`, `awarded`, `susp`, `suspended`} → empty. In {`ft`, `aet`, `pen`, `aot`, `match finished`, `finished`, `after extra time`, `after penalties`, `after over time`} → `FINISHED`. In {`1h`, `ht`, `2h`, `et`, `bt`, `p`, `live`, `in progress`, `q1`, `q2`, `q3`, `q4`, `ot`, `break time`} → `LIVE`. Otherwise `SCHEDULED`.
3. Title: `strEvent` stripped; blank → `<strHomeTeam> vs <strAwayTeam>` when both are non-blank; else empty. Longer than 200 → first 199 characters + `…`.
4. Start: `strTimestamp` non-blank → `OffsetDateTime.parse` when it ends with `Z` or a `±hh:mm` offset, else `LocalDateTime.parse(…).toInstant(UTC)` (TheSportsDB timestamps are UTC); unparsable → step 5. Step 5: `dateEvent` (`yyyy-MM-dd`) and `strTime` whose first 5 or 8 characters match `HH:mm` / `HH:mm:ss` and are not `00:00` / `00:00:00` → that local date-time at UTC. Otherwise all-day: date = `dateEventLocal` if parseable else `dateEvent` if parseable else empty; `startsAt` = date at start of day in `zone`, `endsAt` = the next day's start in `zone`, `allDayDate` = date.
5. Timed end = start + `durations.apply(strSport)`.
6. Artwork: the first of `strThumb`, `strPoster` that is an absolute `https://` URI; when its host is `thesportsdb.com` or ends with `.thesportsdb.com` and its path (lower case) ends with `.jpg`, `.jpeg` or `.png`, append `/small`. Else the competition `badge` as is (may be null).
7. Competition key `thesportsdb:<leagueId>`.

`SportsProperties.TheSportsDb.durationFor(sport, fallback)` = `sportDurations[normalise(sport)]` else `fallback`, where `normalise` = lower case (ROOT) with everything outside `[a-z0-9]` removed; property keys are normalised the same way on binding. Defaults (used when the property map is null or empty): `soccer` 120 min, `basketball` 150 min, `americanfootball` 210 min, `icehockey` 165 min, `handball` 105 min, `rugby` 120 min, `tennis` 180 min, `motorsport` 150 min, `darts` 240 min.

**Daily fixture cache (normative), `TheSportsDbSchedule`.**
- `utcDates(now, zone)`: `from` = start of `LocalDate.ofInstant(now, zone)` in `zone` minus 6 h; `to` = start of the next day in `zone`; every UTC `LocalDate` from `LocalDate.ofInstant(from, UTC)` through `LocalDate.ofInstant(to.minusNanos(1), UTC)`, ascending. Europe/Berlin at `2026-09-19T14:00Z` → {2026-09-18, 2026-09-19}; America/Los_Angeles at the same instant → {2026-09-19, 2026-09-20}.
- `events()` (synchronized): drop cache entries whose date is more than one day before the first requested date. `key = keys.current()`; a `TheSportsDbException` from it becomes every competition's error and nothing is fetched. For each competition (settings order) and each date: the cache entry `(leagueId, date)` is fresh when `fetchedAt + fixturesTtl > now`; a stale or missing entry is fetched unless the last failure for that pair was less than 10 min ago or a `RATE_LIMITED` answer already happened in this call. A successful fetch stores `Entry(events, now)` — the answer mapped with `TheSportsDbEventMapper` (badge from the competition entry, zone `zones.effective()`, durations from the properties, falling back to `default-event-duration`), even when empty — and clears the competition's error; a failure records `lastFailure` and the competition's error (its message; for a rate-limit skip without any cached entry: `TheSportsDB is limiting requests; try again in a minute`) and keeps a stale entry. A competition "succeeded" when any of its dates has an entry. Result events = all entries' events for the requested dates; errors = `<competition name>: <message>` in settings order; `feeds` = competitions.
- `find(itemId)` searches every cached entry (no I/O; when `events()` has never run, it runs once). `status(leagueId)` = `FeedStatus(latest fetchedAt among its entries or null, events of its requested-date entries, error or null, 0, 0, 0)`. `forget(leagueId)` drops its entries, failures and error; `clear()` drops everything. Entries of competitions no longer in settings are ignored and dropped on the next call.
- `SportsSchedule.events()` combines calendars and competitions: `feeds` and `succeeded` are summed, errors concatenated (calendars first); throws `ContentSourceException(first error)` only when `feeds > 0`, `succeeded == 0` and errors exist. `find` dispatches `ics:` to calendars and `tsdb:` to competitions (empty when the module is off). `hasFeeds()` = calendars or competitions present.

**Competitions and keys (normative), `SportsCompetitions`.**
- `add(leagueId)`: stripped, `^[0-9]{1,9}$` else `Enter the competition's TheSportsDB id (digits only)`; already added → `That competition is already added`; at `maxCompetitions` → `You can add up to <n> competitions`; `client.lookupLeague(keys.current(), id)` empty → `TheSportsDB has no competition <id>`; stores `CompetitionEntry(id, league.name(), league.sport(), league.country(), league.badge(), null, clock.instant())` appended. `TheSportsDbException` propagates.
- `remove(leagueId)`: unknown → `No competition <id>`; removed from settings; `schedule.forget(id)`.
- `search(country, sport)`: country stripped, blank → `Enter a country such as Germany`, longer than 60 → `Keep the country under 60 characters`; sport stripped, longer than 60 → `Keep the sport under 60 characters`; → `client.searchLeagues`.
- `usePersonalKey(request, http)`: key stripped, not `^[A-Za-z0-9]{1,64}$` → `That does not look like a TheSportsDB API key`; when `!login.loginRequired()` → `checkNewPassword` (before any network call); `client.lookupLeague(key, "4328")` — `UNAUTHORIZED` → `IllegalArgumentException("TheSportsDB rejected that key")`, other `TheSportsDbException`s propagate; `login.storeSecrets(Map.of(TheSportsDbKeys.SECRET, key), …)`; settings `keyKind` → `PERSONAL`; `schedule.clear()`. `PersonalKey.toString()` = `PersonalKey[redacted]`.
- `useFreeKey()`: when the stored kind is `PERSONAL` → `login.removeSecrets(List.of(TheSportsDbKeys.SECRET))`; kind → `FREE`; `schedule.clear()`.
- `TheSportsDbKeys.current()`: `FREE` → `freeKey`; `PERSONAL` → the secret, missing → `TheSportsDbException(UNAUTHORIZED, "Your TheSportsDB key is missing; enter it again or use the free key")`.

- [ ] **Step 1: Configuration, fixtures and the fake server**

`application.yaml` — inside `home-control.sports` add:

```yaml
    thesportsdb:
      enabled: true
      api-base-url: https://www.thesportsdb.com/api/v1/json
      # TheSportsDB's documented free key; a personal key is entered in Setup and stored as a secret.
      free-key: "123"
      fixtures-ttl: 24h
      connect-timeout-seconds: 5
      request-timeout-seconds: 15
      sport-durations:
        soccer: 120m
        basketball: 150m
        americanfootball: 210m
        icehockey: 165m
        handball: 105m
        rugby: 120m
        tennis: 180m
        motorsport: 150m
        darts: 240m
```

`src/test/resources/application.yaml` — the same with `api-base-url: http://127.0.0.1:9/api/v1/json`, `connect-timeout-seconds: 1`, `request-timeout-seconds: 2`.

`SportsProperties.java` — append `@DefaultValue TheSportsDb theSportsDb` and:

```java
    public record TheSportsDb(@DefaultValue("true") boolean enabled,
                              @DefaultValue("https://www.thesportsdb.com/api/v1/json") URI apiBaseUrl,
                              @DefaultValue("123") String freeKey,
                              @DefaultValue("24h") Duration fixturesTtl,
                              @DefaultValue("5") int connectTimeoutSeconds,
                              @DefaultValue("15") int requestTimeoutSeconds,
                              Map<String, Duration> sportDurations) {

        public static final Map<String, Duration> DEFAULT_DURATIONS = Map.of(
                "soccer", Duration.ofMinutes(120), "basketball", Duration.ofMinutes(150),
                "americanfootball", Duration.ofMinutes(210), "icehockey", Duration.ofMinutes(165),
                "handball", Duration.ofMinutes(105), "rugby", Duration.ofMinutes(120),
                "tennis", Duration.ofMinutes(180), "motorsport", Duration.ofMinutes(150),
                "darts", Duration.ofMinutes(240));

        public TheSportsDb {
            Map<String, Duration> source = sportDurations == null || sportDurations.isEmpty() ? DEFAULT_DURATIONS : sportDurations;
            Map<String, Duration> normalised = new HashMap<>();
            source.forEach((sport, duration) -> normalised.put(normalise(sport), duration));
            sportDurations = Map.copyOf(normalised);
        }

        public Duration durationFor(String sport, Duration fallback) {
            return sport == null ? fallback : sportDurations.getOrDefault(normalise(sport), fallback);
        }

        static String normalise(String sport) {
            return sport.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }
    }
```

Update every Task 1 test that constructs `SportsProperties` directly to pass `new SportsProperties.TheSportsDb(true, URI.create("http://127.0.0.1:9/api/v1/json"), "123", Duration.ofHours(24), 1, 2, null)`.

Fixtures under `src/test/resources/fixtures/thesportsdb/` (trimmed copies of the live shapes probed on 2026-09-16; all strings as the API writes them, `null`s kept):

`lookupleague-4331.json`:
```json
{"leagues":[{"idLeague":"4331","idAPIfootball":"8437","strSport":"Soccer","strLeague":"German Bundesliga","strLeagueAlternate":"Bundesliga, Fußball-Bundesliga","intDivision":"0","idCup":"0","strCurrentSeason":"2026-2027","intFormedYear":"1963","strGender":"Male","strCountry":"Germany","strWebsite":"www.bundesliga.com\/en","strDescriptionEN":"The Fußball-Bundesliga is a professional association football league in Germany.","strTvRights":"UK - Sky Sports\r\nGermany - DAZN and Sky","strBadge":"https:\/\/r2.thesportsdb.com\/images\/media\/league\/badge\/teqh1b1679952008.png","strLogo":"https:\/\/r2.thesportsdb.com\/images\/media\/league\/logo\/620ayu1534764709.png","strComplete":"yes","strLocked":"unlocked"}]}
```

`lookupleague-4328.json`:
```json
{"leagues":[{"idLeague":"4328","strSport":"Soccer","strLeague":"English Premier League","strLeagueAlternate":"Premier League, EPL","strCurrentSeason":"2026-2027","strCountry":"England","strTvRights":"","strBadge":"https:\/\/r2.thesportsdb.com\/images\/media\/league\/badge\/gasy9d1737743125.png","strLocked":"unlocked"}]}
```

`lookupleague-unknown.json`:
```json
{"leagues":null}
```

`search_all_leagues-germany-soccer.json`:
```json
{"countries":[
 {"idLeague":"4485","strSport":"Soccer","strLeague":"DFB-Pokal","strLeagueAlternate":"DFB Cup","strCountry":"Germany","strBadge":"https:\/\/r2.thesportsdb.com\/images\/media\/league\/badge\/tlczpm1780941454.png"},
 {"idLeague":"4399","strSport":"Soccer","strLeague":"German 2. Bundesliga","strLeagueAlternate":"","strCountry":"Germany","strBadge":"https:\/\/r2.thesportsdb.com\/images\/media\/league\/badge\/hl40981534764789.png"},
 {"idLeague":"4331","strSport":"Soccer","strLeague":"German Bundesliga","strLeagueAlternate":"Bundesliga, Fußball-Bundesliga","strCountry":"Germany","strBadge":"https:\/\/r2.thesportsdb.com\/images\/media\/league\/badge\/teqh1b1679952008.png"},
 {"idLeague":"x12","strSport":"Soccer","strLeague":"Broken id","strCountry":"Germany","strBadge":null},
 {"idLeague":"5891","strSport":"Soccer","strLeague":"German Oberliga Baden-Württemberg","strCountry":"Germany","strBadge":"http:\/\/insecure.example\/badge.png"}
]}
```

`eventsday-2026-09-19-4331.json`:
```json
{"events":[
 {"idEvent":"2508361","idAPIfootball":"1575172","strTimestamp":"2026-09-19T13:30:00","strEvent":"Werder Bremen vs Augsburg","strEventAlternate":"Augsburg @ Werder Bremen","strSport":"Soccer","idLeague":"4331","strLeague":"German Bundesliga","strSeason":"2026-2027","strHomeTeam":"Werder Bremen","strAwayTeam":"Augsburg","intHomeScore":null,"intAwayScore":null,"intRound":"4","dateEvent":"2026-09-19","dateEventLocal":"2026-09-19","strTime":"13:30:00","strTimeLocal":"15:30:00","strVenue":"wohninvest WESERSTADION","strCountry":"Germany","strPoster":"https:\/\/r2.thesportsdb.com\/images\/media\/event\/poster\/z98ir21688635931.jpg","strThumb":"https:\/\/r2.thesportsdb.com\/images\/media\/event\/thumb\/ppxv5f1688630656.jpg","strVideo":"","strStatus":"NS","strPostponed":"no","strLocked":"unlocked"},
 {"idEvent":"2508362","strTimestamp":"2026-09-19T13:30:00","strEvent":"Wolfsburg vs Mainz","strSport":"Soccer","idLeague":"4331","strLeague":"German Bundesliga","strHomeTeam":"Wolfsburg","strAwayTeam":"Mainz","dateEvent":"2026-09-19","dateEventLocal":"2026-09-19","strTime":"13:30:00","strTimeLocal":"15:30:00","strThumb":null,"strPoster":"","strStatus":"2H","strPostponed":"no"},
 {"idEvent":"2508365","strTimestamp":"2026-09-19T16:30:00","strEvent":"Borussia Dortmund vs RB Leipzig","strSport":"Soccer","idLeague":"4331","strLeague":"German Bundesliga","strHomeTeam":"Borussia Dortmund","strAwayTeam":"RB Leipzig","dateEvent":"2026-09-19","dateEventLocal":"2026-09-19","strTime":"16:30:00","strTimeLocal":"18:30:00","strThumb":"","strPoster":"https:\/\/r2.thesportsdb.com\/images\/media\/event\/poster\/qwe7rt1754041701.jpg","strStatus":"NS","strPostponed":"no"},
 {"idEvent":"2508366","strTimestamp":"","strEvent":"","strSport":"Soccer","idLeague":"4331","strLeague":"German Bundesliga","strHomeTeam":"Heidenheim","strAwayTeam":"Hamburger SV","dateEvent":"2026-09-19","dateEventLocal":"2026-09-19","strTime":"18:30:00","strTimeLocal":"20:30:00","strThumb":null,"strPoster":null,"strStatus":"NS","strPostponed":"no"},
 {"idEvent":"2508367","strTimestamp":"2026-09-19T13:30:00","strEvent":"Hoffenheim vs Bochum","strSport":"Soccer","idLeague":"4331","dateEvent":"2026-09-19","strTime":"13:30:00","strStatus":"NS","strPostponed":"yes"},
 {"idEvent":"2508368","strTimestamp":"2026-09-19T16:30:00","strEvent":"St. Pauli vs Köln","strSport":"Soccer","idLeague":"4331","dateEvent":"2026-09-19","strTime":"16:30:00","strStatus":"CANC","strPostponed":"no"}
]}
```

`eventsday-2026-09-18-4331.json`:
```json
{"events":[
 {"idEvent":"2508360","strTimestamp":"2026-09-18T18:30:00","strEvent":"Bayern Munich vs Union Berlin","strSport":"Soccer","idLeague":"4331","strLeague":"German Bundesliga","strHomeTeam":"Bayern Munich","strAwayTeam":"Union Berlin","intHomeScore":"3","intAwayScore":"0","dateEvent":"2026-09-18","dateEventLocal":"2026-09-18","strTime":"18:30:00","strTimeLocal":"20:30:00","strThumb":"https:\/\/r2.thesportsdb.com\/images\/media\/event\/thumb\/mjnxjr1754041746.jpg","strStatus":"FT","strPostponed":"no"},
 {"idEvent":"abc","strTimestamp":"2026-09-18T20:00:00","strEvent":"Bad id","idLeague":"4331","strStatus":"NS"},
 {"idEvent":"2601999","strTimestamp":"2026-09-18T19:00:00","strEvent":"Wrong league","idLeague":"4328","strStatus":"NS"}
]}
```

`eventsday-2026-09-19-4328.json`:
```json
{"events":[
 {"idEvent":"2601001","strTimestamp":"2026-09-19T11:30:00+00:00","strEvent":"Arsenal vs Chelsea","strSport":"Soccer","idLeague":"4328","strLeague":"English Premier League","strHomeTeam":"Arsenal","strAwayTeam":"Chelsea","dateEvent":"2026-09-19","strTime":"11:30:00","strThumb":"https:\/\/r2.thesportsdb.com\/images\/media\/event\/thumb\/arsche1754000001.jpg","strStatus":"FT","strPostponed":"no"},
 {"idEvent":"2601002","strTimestamp":"2026-09-19T14:00:00+00:00","strEvent":"Liverpool vs Everton","strSport":"Soccer","idLeague":"4328","strLeague":"English Premier League","strHomeTeam":"Liverpool","strAwayTeam":"Everton","dateEvent":"2026-09-19","strTime":"14:00:00","strThumb":"https:\/\/r2.thesportsdb.com\/images\/media\/event\/thumb\/liveve1754000002.jpg","strStatus":"NS","strPostponed":"no"},
 {"idEvent":"2601003","strTimestamp":null,"strEvent":"Brighton vs Fulham","strSport":"Soccer","idLeague":"4328","strLeague":"English Premier League","strHomeTeam":"Brighton","strAwayTeam":"Fulham","dateEvent":"2026-09-19","dateEventLocal":"2026-09-19","strTime":"00:00:00","strThumb":null,"strStatus":"","strPostponed":"no"}
]}
```

`eventsday-empty.json`:
```json
{"events":null}
```

`invalid-key.json`:
```json
{"Message":"Invalid Premium API key: Signup here: https:\/\/www.thesportsdb.com\/pricing"}
```

`sources/sports/thesportsdb/FakeTheSportsDbServer.java` — same design as `FakeTmdbServer`: constants `FREE_KEY = "123"`, `PERSONAL_KEY = "9876543210"`; `URI apiBase()` → `http://127.0.0.1:<port>/api/v1/json`; requests are recorded with `key` (the path segment after `/api/v1/json/`), `endpoint`, the query map and lower-cased headers; routes keyed by `endpoint` plus the query map: `respond(endpoint, Map<String,String> query, status, fixture)`, `respondJson(endpoint, query, status, json)`, `delay(Duration)`; `withStandardResponses()` registers `lookupleague.php {id=4331}` / `{id=4328}` → their fixtures, `search_all_leagues.php {c=Germany, s=Soccer}` → the search fixture, `eventsday.php {d=2026-09-18, l=4331}`, `{d=2026-09-19, l=4331}`, `{d=2026-09-19, l=4328}` → their fixtures; unregistered `lookupleague.php` → `lookupleague-unknown.json`, unregistered `eventsday.php` → `eventsday-empty.json`, anything else 404 `{}`; a key other than `FREE_KEY` or `PERSONAL_KEY` → 400 with `invalid-key.json` before routing; `List<Recorded> requests(endpoint)`, `int count(endpoint)`, `Recorded last(endpoint)`; `close()`.

- [ ] **Step 2: Write the failing tests**

`sources/sports/thesportsdb/TheSportsDbClientTest.java` (fake with standard responses; properties base `fake.apiBase()`, timeouts 1 s / 2 s):
- `buildsTheDocumentedRequests`: `lookupLeague("123", "4331")` → the fake saw key `123`, endpoint `lookupleague.php`, query `{id=4331}`, header `accept` `application/json`; `searchLeagues("123", "Germany", "Soccer")` → query in order `c`, `s`; `searchLeagues("123", "Côte d'Ivoire", "")` → raw query `c=C%C3%B4te+d%27Ivoire` and no `s`; `eventsDay("123", 2026-09-19, "4331")` → query `d=2026-09-19&l=4331`.
- `mapsLeagues`: `lookupLeague("123", "4331")` → `League("4331", "German Bundesliga", "Soccer", "Germany", https://r2…/teqh1b1679952008.png)`; `lookupLeague("123", "999")` → empty.
- `searchSkipsUnmappableLeagues`: search fixture → ids `[4485, 4399, 4331, 5891]` (the `x12` entry skipped); 5891's badge null (http).
- `eventsDayReturnsElementsOrNothing`: 19 September 4331 → 6 nodes; 20 September → empty list.
- `mapsStatuses` (parameterized status/body → kind, message): 400 `invalid-key.json` → `UNAUTHORIZED` `TheSportsDB rejected the API key`; 401 `{}` → same; 404 `{"Message":"Invalid API key"}` → same; 404 `{}` → `BAD_RESPONSE` `TheSportsDB answered HTTP 404`; 429 → `RATE_LIMITED` `TheSportsDB is limiting requests; try again in a minute`; 502 → `SERVER_ERROR` `TheSportsDB had a server error (HTTP 502)`; 200 `[]` → `BAD_RESPONSE` `TheSportsDB answered with something unexpected`; 200 `<html>` → `…something that is not JSON`.
- `wrongKeysNeverLeaveTheServer`: `lookupLeague("12 3/..", "4331")` → `UNAUTHORIZED` `That does not look like a TheSportsDB API key`; `fake.count` 0.
- `timeoutsAndSizeLimits`: `delay(3 s)` → `UNREACHABLE` `Could not reach TheSportsDB`; a 2 MiB + 1 byte JSON body → `BAD_RESPONSE` `TheSportsDB answered with more data than expected`.
- `neverLeaksTheKey`: with key `PERSONAL_KEY` and every failure above, neither `getMessage()`, `toString()` nor any cause's message contains `9876543210` or `/api/v1/json`.
- `doesNotFollowRedirects`: a 302 to another path → `BAD_RESPONSE` `TheSportsDB answered HTTP 302`; the target was never requested.

`sources/sports/thesportsdb/LeagueTest.java` — `requiresIdAndName` (id `4a`, blank name → empty), `keepsOnlyHttpsBadges`, `stripsBlankSportAndCountry`, `cutsLongNames` (a 121-character `strLeague` → a 120-character name).

`sources/sports/thesportsdb/TheSportsDbEventMapperTest.java` (zone `Europe/Berlin`; durations from the default properties; fallback 120 min; badge `https://r2.thesportsdb.com/images/media/league/badge/teqh1b1679952008.png`):
- `mapsTheNineteenthOfSeptember`: `eventsday-2026-09-19-4331.json` mapped → 4 events:
  - `tsdb:2508361` `Werder Bremen vs Augsburg`, `2026-09-19T13:30Z`–`15:30Z`, SCHEDULED, artwork `https://r2.thesportsdb.com/images/media/event/thumb/ppxv5f1688630656.jpg/small`, competition key `thesportsdb:4331`, not all-day;
  - `tsdb:2508362` `Wolfsburg vs Mainz`, LIVE, artwork = the badge (thumb null, poster blank);
  - `tsdb:2508365` artwork `…/poster/qwe7rt1754041701.jpg/small` (thumb blank);
  - `tsdb:2508366` title `Heidenheim vs Hamburger SV` (blank `strEvent`), start `2026-09-19T18:30Z` (blank timestamp → date + time), artwork the badge;
  - postponed `2508367` and cancelled `2508368` absent.
- `mapsTheEighteenth`: `eventsday-2026-09-18-4331.json` with league `4331` → only `tsdb:2508360`, FINISHED (`abc` and the wrong league skipped).
- `offsetTimestampsAndDateOnlyEvents`: `eventsday-2026-09-19-4328.json` with league `4328` → `tsdb:2601001` FINISHED at `11:30Z`; `tsdb:2601002` SCHEDULED at `14:00Z`; `tsdb:2601003` all-day on 2026-09-19 in Berlin: `2026-09-18T22:00Z`–`2026-09-19T22:00Z` (`strTime` `00:00:00` means unknown).
- `statusSets` (parameterized inline events): `Match Finished` → FINISHED; `AET` → FINISHED; `HT` → LIVE; `Q3` → LIVE; `Susp` → empty; `Awarded` → empty; `strPostponed` `YES` → empty; `` → SCHEDULED.
- `durationsFollowTheSport`: `strSport` `Ice Hockey` → 165 min; `American Football` → 210 min; `Curling` → 120 min (fallback).
- `numericIdsAndTitleFallbacks`: `"idEvent": 2508361` (number) → `tsdb:2508361`; blank `strEvent` and blank away team → empty; a 250-character `strEvent` → 200 characters ending with `…`.
- `artworkRules`: `http://r2.thesportsdb.com/x.jpg` → not used (badge instead); `https://cdn.example/x.jpg` → used without `/small`; `https://www.thesportsdb.com/images/x.PNG` → `…/x.PNG/small`; `https://r2.thesportsdb.com/images/x.gif` → without `/small`.
- `unparsableTimesFallBack`: `strTimestamp` `soon`, `dateEvent` `2026-09-19`, `strTime` `15:00` → `2026-09-19T15:00Z`; `dateEvent` `nope` and no `dateEventLocal` → empty.

`sources/sports/thesportsdb/TheSportsDbScheduleTest.java` (fake with standard responses; real client; settings service over `@TempDir` with competitions `4331` "German Bundesliga" and `4328` "English Premier League"; `TheSportsDbKeys` with FREE; zones fixed `Europe/Berlin`; mutable clock at `2026-09-19T14:00:00Z`; TTL 24 h):
- `utcDatesCoverTheLocalDay`: Berlin → `[2026-09-18, 2026-09-19]`; Los Angeles → `[2026-09-19, 2026-09-20]`; `Pacific/Kiritimati` (UTC+14) at `2026-09-19T14:00Z` → `[2026-09-19, 2026-09-20]` (local day 20 September starts `2026-09-19T10:00Z`, minus 6 h = `04:00Z`).
- `fetchesEachCompetitionAndDateOnce`: `events()` → 4 `eventsday.php` requests (4331×18, 4331×19, 4328×18, 4328×19), all with key `123`; events contain `tsdb:2508360`, `tsdb:2508361`, `tsdb:2601002`; `succeeded` 2; errors empty; a second `events()` → still 4 requests.
- `isCachedForADay`: advance 7 h (`2026-09-19T21:00Z`, still 19 September in Berlin) → no new request; set the clock to `2026-09-20T13:59Z` → exactly 2 new requests (4331 and 4328 for the newly needed date 2026-09-20; the 19 September answers are 23 h 59 min old and still fresh; the 18th is no longer requested); set it to `2026-09-20T14:01Z` → exactly 2 more (the 19 September answers are now stale).
- `keepsStaleFixturesOnFailureAndRetriesAfterTenMinutes`: after a good load advance 25 h (`2026-09-20T15:00Z`, dates 19 and 20); `eventsday.php {d=2026-09-20,l=4331}` answers 500 → 4331 still counts as succeeded through its refreshed 19 September answer; errors `[German Bundesliga: TheSportsDB had a server error (HTTP 500)]`; advance 5 min → no new request for that pair; advance 5 more → one.
- `rateLimitingStopsTheRound`: the first request answers 429 → only one request in that call; errors name both competitions — `German Bundesliga: TheSportsDB is limiting requests; try again in a minute` and `English Premier League: TheSportsDB is limiting requests; try again in a minute`; `succeeded` 0.
- `aMissingPersonalKeyIsAnErrorWithoutRequests`: key kind PERSONAL, secret absent → both competitions have `Your TheSportsDB key is missing; enter it again or use the free key`; `fake.count("eventsday.php")` 0.
- `findsCachedEventsAndReportsStatus`: `find("tsdb:2508365")` present (loads once); `find("tsdb:1")` empty; `status("4331")` → `fetchedAt` = clock, events 5 (1 on 18th + 4 on 19th), error null.
- `forgetAndClear`: `forget("4331")` → its events gone, next `events()` refetches only 4331; `clear()` → everything refetched.
- `removedCompetitionsAreIgnored`: remove 4328 from settings → events have no `tsdb:26…` ids and no new 4328 request.

`sources/sports/thesportsdb/SportsCompetitionsTest.java` (fake + real client; `LoginService` mock; settings over `@TempDir`; schedule spy):
- `addsACompetitionFromLookup`: `add(" 4331 ")` → entry `(4331, German Bundesliga, Soccer, Germany, badge, provider null, clock)`; persisted; `ContentChangedEvent("sports")` published.
- `rejectsBadCompetitions` (message): `43a1` and `` → `Enter the competition's TheSportsDB id (digits only)`; second `4331` → `That competition is already added`; `maxCompetitions` 1 with one → `You can add up to 1 competitions`; `999` → `TheSportsDB has no competition 999`.
- `removes`: `remove("4331")` → gone, `schedule.forget("4331")`; unknown → `No competition 4331`.
- `searches`: `search(" Germany ", "Soccer")` → 4 leagues; blank country → `Enter a country such as Germany`; 61-character sport → `Keep the sport under 60 characters`.
- `storesAPersonalKeyAfterVerifyingIt`: `loginRequired()` false; `usePersonalKey(new PersonalKey(" 9876543210 ", "household password", "household password"), http)` → `checkNewPassword` before the fake's `lookupleague.php` request with key `9876543210` and `id=4328`; `storeSecrets(Map.of("sports.thesportsdb.key", "9876543210"), …)`; kind `PERSONAL`; `schedule.clear()`.
- `rejectsWrongKeys`: `bad key!` → `That does not look like a TheSportsDB API key` (no request); `1111` (fake answers 400 invalid key) → `TheSportsDB rejected that key`, nothing stored.
- `backToTheFreeKey`: kind PERSONAL → `useFreeKey()` → `removeSecrets(List.of("sports.thesportsdb.key"))`, kind FREE, `schedule.clear()`; with kind FREE → `removeSecrets` never called.
- `personalKeyToStringIsRedacted`.

`sources/sports/thesportsdb/TheSportsDbSetupControllerTest.java` — `@WebMvcTest({TheSportsDbSetupController.class, SportsSetupController.class, SetupController.class, SportsSetupAdvice.class})` with the Task 1 mocks plus `@MockitoBean SportsCompetitions competitions`, `TheSportsDbSchedule tsdbSchedule`:
- `addAndRemove`: POST `/setup/sources/sports/competitions` `leagueId=4331` → `Added German Bundesliga`; `/competitions/4331/remove` → `Removed German Bundesliga`; `IllegalArgumentException` and `TheSportsDbException` → `sportsError` with the message.
- `searchFlashesResults`: `/thesportsdb/search` `country=Germany&sport=Soccer` → flash `sportsSearch` with 4 `LeagueView`s; `4331` has `added` true when settings contain it.
- `keys`: `/thesportsdb/key` `key=9876543210&loginPassword=…` → `verify(competitions).usePersonalKey(new PersonalKey("9876543210", …), any())`, message `Using your TheSportsDB key`; no flash attribute contains `9876543210`; `/thesportsdb/free-key` → `Using the free TheSportsDB key`.
- `theSetupPageShowsTheSportsDb`: kind FREE, competitions 4331 (status loaded, 5 events) and 4328 (status error `TheSportsDB is limiting requests; try again in a minute`), flash `sportsSearch` with results → body contains `TheSportsDB`, `The free key shows at most 3 matches per competition per day.`, `German Bundesliga`, `5 events · updated 16:00`, `Could not refresh: TheSportsDB is limiting requests; try again in a minute`, `action="/setup/sources/sports/competitions/4331/remove"`, a search result row for `German 2. Bundesliga` with an `Add` button posting `leagueId=4399` and `German Bundesliga` marked `Added` (no button), `name="key"` with `type="password"`, `Data from TheSportsDB`; kind PERSONAL → `Using your personal key.` and a `Use the free key` button; never `9876543210`.

`sources/sports/SportsContentSourceTest.java` — add `availableWithOnlyACompetition`: no calendars, one competition, module on → `available()` true; the same with a `SportsSchedule(calendars, null)` → false. `readsTheSportsDbItems`: `item("tsdb:2508361")` → subtitle `Live · German Bundesliga`, artwork the `/small` thumb.

`sources/sports/SportsModuleSwitchTest.java` — add a nested class with `home-control.sports.thesportsdb.enabled=false` → `SportsContentSource` present, no `TheSportsDbClient`, `SportsCompetitions` or `TheSportsDbSetupController` beans; `/setup` contains `id="sports"` but not `TheSportsDB`; POST `/setup/sources/sports/competitions` → 404.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*'`
Expected: compilation failure — `sources.sports.thesportsdb` does not exist; `SportsProperties` has no `theSportsDb`.

- [ ] **Step 4: Client, mapper, keys and schedule**

`TheSportsDbException.java` — per Interfaces.

`TheSportsDbClient.java` — implement the wire format like G's `TmdbClient` (read the body as an `InputStream`, `readNBytes(2 MiB + 1)`, then `JsonMapper.readTree`); status mapping in one `switch`; the `api key` body check reads at most the capped body as UTF-8.

`League.java`, `TheSportsDbEventMapper.java` — implement the rules above. Status sets as `Set.of(…)` constants. Timestamp parsing:

```java
    private static Optional<Instant> timestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        try {
            if (value.endsWith("Z") || value.endsWith("z") || OFFSET.matcher(value).find()) {
                return Optional.of(OffsetDateTime.parse(value).toInstant());
            }
            return Optional.of(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
```

with `OFFSET = Pattern.compile("[+-]\\d{2}:\\d{2}$")`.

`TheSportsDbKeys.java`, `TheSportsDbSchedule.java` — implement the cache rules. Keep `record Entry(List<SportsEvent> events, Instant fetchedAt)` in a `ConcurrentHashMap<String, Entry>` keyed `<leagueId>|<date>`, a `Map<String, Instant> lastFailure` with the same keys and a `Map<String, String> errors` by league id. Log failures at WARN as `TheSportsDB fixtures for competition {} on {} failed ({})` with league id, date and kind.

`SportsSchedule.java` — add the nullable `TheSportsDbSchedule` parameter and the combination rules.

- [ ] **Step 5: Competitions, setup and wiring**

`SportsCompetitions.java` — implement the rules; `add`, `remove`, `usePersonalKey`, `useFreeKey` are `synchronized`.

`TheSportsDbSetupController.java` — `@Controller` with `@ConditionalOnProperty(name = {"home-control.sports.enabled", "home-control.sports.thesportsdb.enabled"}, havingValue = "true", matchIfMissing = true)`; handlers per Interfaces; messages `Added <name>`, `Removed <name>`, `Using your TheSportsDB key`, `Using the free TheSportsDB key`; the search stores `SearchView` in flash `sportsSearch` (with `added` from the current settings); errors as in `SportsSetupController` (`StorageException` → `Could not save sports settings`).

`SportsSetupAdvice.java` — add `ObjectProvider<TheSportsDbSchedule>`; `theSportsDbEnabled` = a schedule bean exists; `keyKind` `free`/`personal`; `CompetitionView` statuses with the same wording as calendars (events and `updated HH:mm`, or `Could not refresh: <message without the name prefix>`, or `Not loaded yet`).

`sports-setup.html` — update the intro to `Show live and upcoming sport from calendar links (.ics) and from TheSportsDB. Home Control does not know which service broadcasts an event.`; after the calendars add, when `sports.theSportsDbEnabled()`: heading `TheSportsDB`; text `Fixtures for the competitions you choose come from TheSportsDB, a community database. Kick-off times can be missing or late.`; key status (`Using the free key. The free key shows at most 3 matches per competition per day.` or `Using your personal key.` plus a `Use the free key` form); a personal-key form (`key` `type="password"` `autocomplete="off"` never pre-filled, the login-password fields when `needsLoginPassword`, button `Use my key`); a search form (`country` required `maxlength="60"` placeholder `Germany`, `sport` with a datalist `Soccer`, `Basketball`, `Ice Hockey`, `Handball`, `American Football`, `Tennis`, `Motorsport`, `Darts`, button `Find competitions`); the flash results (`TheSportsDB found no competitions for <country>.` when empty; otherwise name, sport, country, and either `Added` or an `Add` form posting `leagueId`); an add-by-id form (`leagueId` `inputmode="numeric"` `pattern="[0-9]{1,9}"`, hint `The id is the number in a TheSportsDB league page address, e.g. 4331 for the Bundesliga.`); the competitions list with status and `Remove`; and `Data from <a href="https://www.thesportsdb.com" rel="noopener noreferrer">TheSportsDB</a>.` — rendered text `Data from TheSportsDB.`. Add the sentence `If you add the same competition as a calendar and from TheSportsDB, its matches appear twice.` under the heading `Sports`.

`TheSportsDbConfiguration.java` — `@Configuration` with the two-property condition; beans `TheSportsDbClient(properties.theSportsDb())`, `TheSportsDbKeys(settings, secretStore, properties)`, `TheSportsDbSchedule(client, keys, settings, properties, zones, Clock.systemUTC())`, `SportsCompetitions(settings, client, keys, schedule, loginService, properties, Clock.systemUTC())`.

`SportsConfiguration.java` — the `SportsSchedule` bean method takes `ObjectProvider<TheSportsDbSchedule>` and passes `getIfAvailable()`.

- [ ] **Step 6: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/sports src/main/resources/templates/fragments/sports-setup.html \
  src/main/resources/application.yaml src/test/resources/application.yaml \
  src/test/java/dev/andre/homecontrol/sources/sports src/test/resources/fixtures/thesportsdb
git commit -m "feat: TheSportsDB fixtures for chosen competitions, cached daily"
```

---

### Task 3: H3 · Competition → provider mapping

**Files:**
- Create: `sources/sports/SportsProviders.java`
- Modify: `sources/sports/SportsItems.java`, `sources/sports/SportsSetupController.java`, `sources/sports/SportsSetupAdvice.java`, `src/main/resources/templates/fragments/sports-setup.html`
- Test: `sources/sports/SportsProvidersTest.java`, `sources/sports/SportsItemsTest.java`, `sources/sports/SportsSetupControllerTest.java`, `sources/sports/thesportsdb/TheSportsDbSetupControllerTest.java`

**Interfaces:**
- Consumes: Task 1 (`SportsSettings` with `provider` on calendars and competitions, `SportsSettingsService.update`, `SportsItems`, `SportsSetupAdvice`, `sports-setup.html`); Task 2 (`CompetitionView`); D4 `StreamingProviders.KNOWN` (key → display name, insertion order); G `ContentChangedEvent` (published by `SportsSettingsService.update`).
- Produces:
  - `final class SportsProviders { static List<Option> options(); static Optional<String> displayName(String key); static String normalise(String raw); static SportsSettings apply(SportsSettings settings, Map<String, String> providerByCompetitionKey); record Option(String key, String name) {} static final String USER_SETTING = " (your setting)"; }`.
  - `POST /setup/sources/sports/providers` with parameters named `provider:<competition key>` (e.g. `provider:calendar:c-3f9a1c2b7d4e=dazn`, `provider:thesportsdb:4331=`) → `302 /setup#sports-providers`, flash `sportsMessage` / `sportsError`.
  - `SportsSetupAdvice.View` gains `List<SportsProviders.Option> providers`; `CalendarView` and `CompetitionView` gain `String provider` (key or `""`).
  - Subtitle suffix `· <Provider name> (your setting)`.

**Mapping rules (normative).**
- `normalise(raw)`: null or blank → null; stripped value must be a `StreamingProviders.KNOWN` key → that key; else `IllegalArgumentException("Unknown streaming service <raw stripped>")`.
- `apply(settings, map)`: every map key must be `calendar:<id>` of an existing calendar or `thesportsdb:<leagueId>` of an existing competition, else `IllegalArgumentException("No competition <key>")`; each value is normalised; entries not in the map keep their provider; returns new settings (nothing is written by `apply`). An empty map → `IllegalArgumentException("Nothing to save")`.
- `options()` = `StreamingProviders.KNOWN` in its order as `Option(key, name)`.
- New calendars and competitions start with provider null. Nothing ever sets a provider except this endpoint (in particular not TheSportsDB's `strTvRights`).
- Subtitle (extends Task 1): when `settings.providerFor(event.competitionKey())` is present → `<when> · <label> · <display name> (your setting)`, e.g. `Live · German Bundesliga · DAZN (your setting)`, `18:30 · Bundesliga 2026/27 · Prime Video (your setting)`.
- Controller: collects every request parameter whose name starts with `provider:` (first value each) into a `LinkedHashMap` keyed by the rest of the name; `settings.update(s -> SportsProviders.apply(s, map))`; success message `Saved. These are your own settings; Home Control does not check broadcast rights.`; `IllegalArgumentException` → `sportsError` with its message; `StorageException` → `Could not save sports settings`.

- [ ] **Step 1: Write the failing tests**

`sources/sports/SportsProvidersTest.java` (settings with calendar `c-3f9a1c2b7d4e` and competition `4331`, both unmapped):
- `normalises`: `dazn` → `dazn`; ` primevideo ` → `primevideo`; `` and null → null; `DAZN` and `sky` → `IllegalArgumentException` `Unknown streaming service DAZN` / `… sky`.
- `appliesToCalendarsAndCompetitions`: `apply(settings, {calendar:c-3f9a1c2b7d4e=dazn, thesportsdb:4331=primevideo})` → providers set; `apply(that, {thesportsdb:4331=""})` → 4331 null, calendar still `dazn`.
- `refusesUnknownKeys`: `{thesportsdb:9999=dazn}` → `No competition thesportsdb:9999`; `{calendar:c-ffffffffffff=dazn}` → `No competition calendar:c-ffffffffffff`; `{nope=dazn}` → `No competition nope`; `{}` → `Nothing to save`; the input settings object is unchanged.
- `optionsFollowTheKnownProviders`: keys equal `StreamingProviders.KNOWN.keySet()` in order; `dazn` → `DAZN`.

`sources/sports/SportsItemsTest.java` — add:
- `mappedCompetitionsNameTheProviderAsTheUsersSetting`: calendar provider `dazn` → Werder Bremen subtitle `Live · Bundesliga 2026/27 · DAZN (your setting)`; competition `4331` provider `primevideo`, event at 16:30Z → `18:30 · German Bundesliga · Prime Video (your setting)`.
- `unmappedCompetitionsNameNoProvider`: subtitle has no ` · ` after the label and never contains `DAZN`.
- `noSubtitleClaimsAProviderWithoutTheLabel` (parameterized over every `KNOWN` key): subtitle containing the provider's name always ends with `(your setting)`.

`sources/sports/SportsSetupControllerTest.java` — add:
- `savesTheMapping`: POST `/setup/sources/sports/providers` `provider:calendar:c-3f9a1c2b7d4e=dazn&provider:thesportsdb:4331=&unrelated=x` → `settings.update` called once; applying the captured operator to the fixture settings gives calendar `dazn`, 4331 null; 302 `/setup#sports-providers`; message `Saved. These are your own settings; Home Control does not check broadcast rights.`
- `mappingErrorsAreFlashed`: `provider:thesportsdb:4331=sky` → `sportsError` `Unknown streaming service sky`; nothing saved (the operator throws inside `update`; the mock `update` is configured to call the operator on the current settings so the exception propagates).
- `theSetupPageLabelsTheMappingAsTheUsersSetting`: settings with calendar `c-3f9a1c2b7d4e` (provider `dazn`) and, when the TheSportsDB module is present (Task 2 test class), competition `4331` (provider null) → body contains `id="sports-providers"`, the column header `Where you watch it (your setting)`, the sentence `This is your own setting: Home Control does not know broadcast rights and does not use TheSportsDB's TV listings.`, `<select name="provider:calendar:c-3f9a1c2b7d4e"`, an `<option value="dazn" selected` inside it, an `<option value="">Not set</option>`, `name="provider:thesportsdb:4331"` with `Not set` selected, the button `Save your settings`, and the text `Events of a competition set to DAZN, Netflix or Prime Video open that app. For other services, paste a link to the event in the play sheet.`; the body never contains `Available on`, `Official`, `Broadcast by` or `Live on DAZN`.
- `noCompetitionsNoMappingForm`: settings without calendars and competitions → no `id="sports-providers"`.

`sources/sports/thesportsdb/TheSportsDbSetupControllerTest.java` — extend `theSetupPageShowsTheSportsDb` to assert the competition rows appear in the mapping form with their names.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*'`
Expected: compilation failure — `SportsProviders` does not exist.

- [ ] **Step 3: Implement**

`sources/sports/SportsProviders.java`:

```java
package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.core.content.StreamingProviders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The household's own answer to "where do you watch this competition". Home Control has no
 * authoritative broadcast data (spec §4.2, §11), so every place that shows it says so.
 */
public final class SportsProviders {

    public static final String USER_SETTING = " (your setting)";

    public record Option(String key, String name) {
    }

    private SportsProviders() {
    }

    public static List<Option> options() {
        return StreamingProviders.KNOWN.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList();
    }

    public static Optional<String> displayName(String key) {
        return Optional.ofNullable(key == null ? null : StreamingProviders.KNOWN.get(key));
    }

    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.strip();
        if (!StreamingProviders.KNOWN.containsKey(key)) {
            throw new IllegalArgumentException("Unknown streaming service " + key);
        }
        return key;
    }

    public static SportsSettings apply(SportsSettings settings, Map<String, String> providerByCompetitionKey) {
        if (providerByCompetitionKey.isEmpty()) {
            throw new IllegalArgumentException("Nothing to save");
        }
        List<SportsSettings.CalendarEntry> calendars = new ArrayList<>(settings.calendars());
        List<SportsSettings.CompetitionEntry> competitions = new ArrayList<>(settings.competitions());
        for (Map.Entry<String, String> entry : providerByCompetitionKey.entrySet()) {
            String key = entry.getKey();
            String provider = normalise(entry.getValue());
            int calendar = indexOf(calendars.stream().map(c -> SportsSettings.calendarKey(c.id())).toList(), key);
            int competition = indexOf(competitions.stream().map(c -> SportsSettings.competitionKey(c.leagueId())).toList(), key);
            if (calendar >= 0) {
                SportsSettings.CalendarEntry c = calendars.get(calendar);
                calendars.set(calendar, new SportsSettings.CalendarEntry(c.id(), c.label(), c.host(), provider, c.addedAt()));
            } else if (competition >= 0) {
                SportsSettings.CompetitionEntry c = competitions.get(competition);
                competitions.set(competition, new SportsSettings.CompetitionEntry(c.leagueId(), c.name(), c.sport(),
                        c.country(), c.badge(), provider, c.addedAt()));
            } else {
                throw new IllegalArgumentException("No competition " + key);
            }
        }
        return settings.withCalendars(calendars).withCompetitions(competitions);
    }

    private static int indexOf(List<String> keys, String key) {
        return keys.indexOf(key);
    }
}
```

`SportsItems.subtitle` — append `SportsProviders.displayName(provider).map(name -> " · " + name + SportsProviders.USER_SETTING).orElse("")`.

`SportsSetupController` — add the handler:

```java
    @PostMapping("/setup/sources/sports/providers")
    public String providers(@RequestParam MultiValueMap<String, String> parameters, RedirectAttributes flash) {
        Map<String, String> mapping = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (name.startsWith("provider:") && !values.isEmpty()) {
                mapping.put(name.substring("provider:".length()), values.getFirst());
            }
        });
        try {
            settings.update(current -> SportsProviders.apply(current, mapping));
            flash.addFlashAttribute("sportsMessage",
                    "Saved. These are your own settings; Home Control does not check broadcast rights.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("sportsError", e.getMessage());
        } catch (StorageException e) {
            log.warn("Could not save sports settings", e);
            flash.addFlashAttribute("sportsError", "Could not save sports settings");
        }
        return "redirect:/setup#sports-providers";
    }
```

`SportsSetupAdvice` — fill `providers` and each view's `provider`.

`sports-setup.html` — after the TheSportsDB block (or after calendars when TheSportsDB is off), when there is at least one calendar or competition: `<form id="sports-providers" method="post" action="/setup/sources/sports/providers">` with heading `Where you watch it`, the paragraph `Tell Home Control where you watch each competition. This is your own setting: Home Control does not know broadcast rights and does not use TheSportsDB's TV listings.`, a `<table>` with headers `Competition`, `From`, `Where you watch it (your setting)`; one row per calendar (`From` = `Calendar`) and per competition (`From` = `TheSportsDB`), each with `<select th:name="'provider:' + ${key}" th:attr="aria-label='Where you watch ' + ${label} + ' (your setting)'">` containing `<option value="">Not set</option>` and one option per `sports.providers()` with `th:selected`; then the paragraph `Events of a competition set to DAZN, Netflix or Prime Video open that app. For other services, paste a link to the event in the play sheet.` and the button `Save your settings` (≥ 44 px). On narrow screens the table stacks (reuse the setup page's existing responsive table style or add `@media (max-width: 40rem) { #sports-providers td { display: block; } }` in `app.css`).

- [ ] **Step 4: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/sports src/main/resources/templates/fragments/sports-setup.html \
  src/main/resources/static/app.css src/test/java/dev/andre/homecontrol/sources/sports
git commit -m "feat: map each competition to where the household watches it, labelled as the user's setting"
```

(Omit `app.css` from `git add` if it was not changed.)

---

### Task 4: H4 · Live now / Today rail and DAZN launch

**Files:**
- Create: `sources/sports/LiveTodayRail.java`
- Modify: `sources/sports/SportsItems.java`, `sources/sports/SportsContentSource.java`, `sources/sports/SportsConfiguration.java`, `src/main/resources/static/js/play-sheet.js`
- Test: `sources/sports/LiveTodayRailTest.java`, `sources/sports/SportsRailTest.java`, `sources/sports/SportsPinUpgradeTest.java`, `sources/sports/SportsItemsTest.java`, `sources/sports/SportsContentSourceTest.java`, `core/playback/LiveEventRoutingTest.java`, `web/StaticAssetsTest.java`

**Interfaces:**
- Consumes: Tasks 1–3; G (`ServiceLinks.appHome/isAppHome/appLink/DAZN`, `PinnedLinks.linkFor`, `PinOffers.offer`, `PinnedShortcuts.addUpgrade/linkFor`, `ContentChangedEvent`, `play-sheet.js` `showPin`); D1 (`ContentSource.defaultRefreshInterval`, `RailCache`); D3 (`PlaybackPlanner.routes`); A (`PlaybackPlanner`, `AppLinkStrategy`, `Route.OpenAppLink`, `Capability`).
- Produces:
  - `final class LiveTodayRail { static final String ID = "live-today"; static final String TITLE = "Live now / Today"; static List<SportsEvent> select(List<SportsEvent> events, Instant now, ZoneId zone, int limit); }`.
  - `SportsItems.toItem(SportsEvent, SportsSettings, ZoneId, Locale, Instant now, PinnedLinks pinnedLinks)` (nullable `pinnedLinks`; the 5-argument form stays and passes null) and `static List<PlayableRef> playables(SportsEvent, SportsSettings, PinnedLinks)`.
  - `SportsContentSource` constructor appends `ObjectProvider<PinnedLinks> pinnedLinks, SportsProperties properties`; `rails()` = `[RailDescriptor("sports", "live-today", "Live now / Today")]` while `available()`; `rail("live-today")`; `defaultRefreshInterval()` = 5 min.
  - `play-sheet.js`: pin prompt wording for `LIVE_EVENT` items.

**Rail rules (normative), `LiveTodayRail.select`.** Keep events whose `EventPhase.of(event, now, zone)` is `LIVE`, `ALL_DAY_TODAY` or `UPCOMING_TODAY`; sort by phase in that order, then `startsAt`, then `title` case-insensitively (`String.CASE_INSENSITIVE_ORDER`), then `itemId`; drop duplicates by `itemId` (first wins); keep the first `limit`. `rail("live-today")`: `events = schedule.events()` (a `ContentSourceException` propagates so D shows the rail's compact error with retry); `now = clock.instant()`; `zone = zones.effective()`; items = `select(events, now, zone, properties.railSize())` mapped with `toItem(…, now, pinnedLinks.getIfAvailable())`; `new Rail(descriptor, items, now)`. Unknown rail id → `IllegalArgumentException("Sports has no rail '<id>'")`. `item(itemId)` uses the same mapping (so a play re-read sees a pin added a moment ago).

**Playable rule (normative).** `pinned = pinnedLinks == null ? empty : pinnedLinks.linkFor("sports", event.itemId())`; present → `[pinned link]`. Else `provider = settings.providerFor(event.competitionKey())`; `ServiceLinks.appHome(provider)` present → `[new PlayableRef.AppLink(appHome, provider)]` (DAZN → `https://www.dazn.com/`, Netflix → `https://www.netflix.com/browse`, Prime Video → `https://app.primevideo.com/`). Else `[]`. The event's phase does not change playables (an ended event still opens the app — DAZN has replays; nothing is queued for an upcoming one).

**Play-sheet wording (normative).** When the sheet's item kind is `LIVE_EVENT`: with a service name → `This opens the <Name> app, not this event. Paste the <Name> link for this event to open it directly.`; without → `Home Control cannot open this event directly. Paste a link to it (for example its page on dazn.com) to pin it.`. Other kinds keep G's text.

- [ ] **Step 1: Write the failing tests**

`core/playback/LiveEventRoutingTest.java` (pure; planner = `new PlaybackPlanner(List.of(new AppLinkStrategy()))` — if the real `HomeControlConfiguration` builds the planner with more strategies whose constructors need no I/O, build the same list, found with `grep -n "new PlaybackPlanner" -r src/main/java`):
- `aDaznEventOpensTheDaznAppOnAppLinkDevices`: `LIVE_EVENT` item with times and `[AppLink(https://www.dazn.com/, dazn)]`, capabilities `{REMOTE_KEYS, APP_LINK}` → `plan` = `Route.OpenAppLink(https://www.dazn.com/, "dazn")`, `describe()` = `Open the DAZN app (not this title)`; `routes` = exactly that one route.
- `aPastedEventLinkOpensTheEvent`: `[AppLink(https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j, dazn)]` → `describe()` = `Open in the DAZN app`.
- `anUnmappedEventHasNothingToPlay`: `LIVE_EVENT` with no playables → `Unroutable` `This item has nothing playable`; `routes` empty.
- `devicesWithoutAppLinksExplainWhy`: DAZN app-home item, capabilities `{CAST_RECEIVER, VOLUME}` and `{MEDIA_RENDERER}` → `Unroutable` whose reason contains `cannot open app links`.
- `routingIgnoresKindAndTime` (parameterized over capability sets `{}`, `{APP_LINK}`, `{CAST_RECEIVER}`, `{APP_LINK, CAST_RECEIVER, MEDIA_RENDERER}`): the same playables as `VIDEO` without times and as `LIVE_EVENT` with past, present and future times → equal `plan` results and equal `routes`.
- `pinOffersForEvents`: `PinOffers.offer` of `sports`/`tsdb:2508361` with the DAZN app home → `Offer("sports/tsdb:2508361", "dazn")`; with no playables → `Offer("sports/tsdb:2508361", null)`; with the pasted event link → empty; ICS id `ics:c-3f9a1c2b7d4e:069e696917c4a665` → `upgradeOf` `sports/ics:c-3f9a1c2b7d4e:069e696917c4a665` matches G's `upgradeOf` pattern `^[a-z0-9][a-z0-9._-]{0,63}/[A-Za-z0-9._:-]{1,128}$`.

`sources/sports/LiveTodayRailTest.java` (zone Berlin; now `2026-09-19T14:00:00Z`; events built inline):
- `ordersLiveThenAllDayThenUpcoming`: input in scrambled order: upcoming `B` 18:30Z, live `Z` 13:30Z, all-day today `Vuelta`, live `A` 13:30Z, upcoming `C` 16:30Z, live `Early` 12:30Z, ended `Old` 11:00–13:00Z, later `Tomorrow` 2026-09-20T15:30Z, finished `Done` 13:30Z FINISHED, all-day tomorrow → `[Early, A, Z, Vuelta, C, B]`.
- `titleTiesAreCaseInsensitiveThenById`: two live events at the same time `alpha` (id `tsdb:2`) and `Alpha` (id `tsdb:1`) → `tsdb:1` first; `beta` after both.
- `respectsTheLimitAndDropsDuplicateIds`: 40 upcoming events → first 30; the same id twice → once.
- `todayEndsAtLocalMidnight`: `2026-09-19T21:59Z` included, `2026-09-19T22:00Z` excluded (Berlin); with zone `America/New_York` the `22:00Z` event is included (18:00 local).

`sources/sports/SportsItemsTest.java` — add:
- `daznCompetitionsOpenTheDaznApp`: calendar provider `dazn` → playables `[AppLink(https://www.dazn.com/, "dazn")]`; `primevideo` → `[AppLink(https://app.primevideo.com/, "primevideo")]`; `netflix` → `[AppLink(https://www.netflix.com/browse, "netflix")]`.
- `servicesWithoutAnAppHomeHaveNoPlayables` (parameterized `wowtv`, `joyn`, `disneyplus`, `rtlplus`, null) → `[]`.
- `aPinnedEventLinkWins`: `PinnedLinks` stub returning `AppLink(https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j, dazn)` for `("sports", "tsdb:2508361")` → exactly that playable, for mapped and unmapped competitions; another item id → the app home.
- `endedEventsStillOpenTheApp`: Bayern (ended) with `dazn` → the app home.

`sources/sports/SportsRailTest.java` — `SportsContentSource` over a stub `SportsSchedule` (Mockito) returning the mapped TheSportsDB fixture events for 18 and 19 September (via `TheSportsDbEventMapper`) plus the Bundesliga ICS occurrences for calendar `c-3f9a1c2b7d4e` (via `IcsOccurrences` and `CalendarSchedule.toEvent`); settings: calendar `c-3f9a1c2b7d4e` "Bundesliga 2026/27" provider `dazn`, competition `4331` "German Bundesliga" provider `dazn`, competition `4328` "English Premier League" provider null; zone Berlin; preferences locale `de-DE`; fixed clock `2026-09-19T14:00:00Z`; empty `ObjectProvider<PinnedLinks>`:
- `offersTheRailOnlyWhenAvailable`: `rails()` = `[RailDescriptor("sports", "live-today", "Live now / Today")]`; with `hasFeeds()` false → empty; `defaultRefreshInterval()` = 5 min.
- `buildsLiveNowAndToday`: `rail("live-today").items()` ids in exactly this order: `ics:c-3f9a1c2b7d4e:069e696917c4a665` (`SV Werder Bremen – FC Augsburg`, live, 13:30Z), `tsdb:2508361` (`Werder Bremen vs Augsburg`, live, 13:30Z), `tsdb:2508362` (`Wolfsburg vs Mainz`, live, 13:30Z), `tsdb:2601002` (`Liverpool vs Everton`, live — starts exactly now, 14:00Z), `tsdb:2601003` (`Brighton vs Fulham`, all-day today), `tsdb:2508365` (`Borussia Dortmund vs RB Leipzig`, 16:30Z), `ics:c-3f9a1c2b7d4e:47f47c4a3b4c720d` (`Borussia Dortmund – RB Leipzig`, 16:30Z), `tsdb:2508366` (`Heidenheim vs Hamburger SV`, 18:30Z). Within 13:30Z the case-insensitive title order is `SV…` < `Werder…` < `Wolfsburg…`; within 16:30Z `…Dortmund vs…` sorts before `…Dortmund –…` because U+2013 is above ASCII letters. Not present: Bayern (ended in both feeds), Arsenal (finished), Stuttgart (tomorrow), the cancelled Hertha match, the postponed and cancelled TheSportsDB events.
- `subtitlesAndPlayables`: `tsdb:2508361` subtitle `Live · German Bundesliga · DAZN (your setting)`, playables `[AppLink(https://www.dazn.com/, dazn)]`, kind `LIVE_EVENT`, `startsAt` `2026-09-19T13:30:00Z`, `endsAt` `15:30:00Z`, artwork the `/small` thumb; `tsdb:2601002` subtitle `Live · English Premier League`, no playables; `tsdb:2601003` subtitle `Today · English Premier League`; `tsdb:2508366` subtitle `20:30 · German Bundesliga · DAZN (your setting)`.
- `failuresSurfaceAsRailErrors`: schedule throws `ContentSourceException("Bundesliga 2026/27: calendar.example.org answered HTTP 500")` → `rail` throws it unchanged.
- `unknownRail`: `rail("today")` → `IllegalArgumentException` `Sports has no rail 'today'`.
- `theRailNeverClaimsAPersonalFeedOrAuthoritativeRights`: no item subtitle or rail title contains `For you`, `Recommended`, `Continue`, `Official`, `on DAZN`; every subtitle mentioning `DAZN` ends with `(your setting)`.
- `itemReadsMatchTheRail`: `item("tsdb:2508361")` equals the rail's item.

`sources/sports/SportsPinUpgradeTest.java` — G's real `PinnedShortcuts` (store in `@TempDir`, `PinnedProperties(true, 200)`, captured publisher) with `ObjectProvider<ContentSources>` over a `ContentSources` containing the `SportsContentSource` from `SportsRailTest`'s setup whose `ObjectProvider<PinnedLinks>` returns the same `PinnedShortcuts`:
- `pastingAnEventLinkUpgradesTheEvent`: `addUpgrade("https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j", "sports/tsdb:2508361")` → pin title `Werder Bremen vs Augsburg`, kind `LIVE_EVENT`, artwork the `/small` thumb, subtitle `DAZN`, `upgradeOf` `sports/tsdb:2508361`; events `ContentChangedEvent("pinned")` and `ContentChangedEvent("sports")`; `source.item("tsdb:2508361")` playables = `[AppLink(https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j, dazn)]`; `PinOffers.offer(that item)` empty; the rail item is the same.
- `unmappedEventsCanBeUpgradedToo`: `addUpgrade(<dazn link>, "sports/tsdb:2601002")` succeeds (offer without service) and the item then has that playable.
- `icsEventIdsWork`: `addUpgrade(<dazn link>, "sports/ics:c-3f9a1c2b7d4e:069e696917c4a665")` succeeds.
- `upgradedEventsStayUpgradedWhenTheMappingChanges`: set 4331's provider to null → the item still has the pinned link.

`sources/sports/SportsContentSourceTest.java` — replace `availableWithACalendarButNoRailsYet` by `offersTheLiveTodayRailWhenAvailable`.

`web/StaticAssetsTest.java` — `/js/play-sheet.js` contains `not this event` and `LIVE_EVENT`, and still contains `sheet-pin`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*' --tests 'dev.andre.homecontrol.core.playback.LiveEventRoutingTest' --tests 'dev.andre.homecontrol.web.StaticAssetsTest'`
Expected: compilation failure — `LiveTodayRail` does not exist; `LiveEventRoutingTest` fails only where it touches sports-independent code if the planner already behaves (the routing tests may pass immediately: they pin existing behaviour for `LIVE_EVENT`, which is the point — record that in the task report).

- [ ] **Step 3: Implement the rail and playables**

`sources/sports/LiveTodayRail.java`:

```java
package dev.andre.homecontrol.sources.sports;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** "Live now / Today": what is on now, then today's all-day events, then what starts later today. */
public final class LiveTodayRail {

    public static final String ID = "live-today";
    public static final String TITLE = "Live now / Today";

    private static final Set<EventPhase> SHOWN =
            EnumSet.of(EventPhase.LIVE, EventPhase.ALL_DAY_TODAY, EventPhase.UPCOMING_TODAY);

    private LiveTodayRail() {
    }

    public static List<SportsEvent> select(List<SportsEvent> events, Instant now, ZoneId zone, int limit) {
        record Ranked(EventPhase phase, SportsEvent event) {
        }
        Set<String> seen = new HashSet<>();
        return events.stream()
                .map(event -> new Ranked(EventPhase.of(event, now, zone), event))
                .filter(ranked -> SHOWN.contains(ranked.phase()))
                .sorted(Comparator.comparing(Ranked::phase)
                        .thenComparing(ranked -> ranked.event().startsAt())
                        .thenComparing(ranked -> ranked.event().title(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ranked -> ranked.event().itemId()))
                .map(Ranked::event)
                .filter(event -> seen.add(event.itemId()))
                .limit(Math.max(0, limit))
                .toList();
    }
}
```

(`EventPhase` declares `LIVE, ALL_DAY_TODAY, UPCOMING_TODAY` first, so enum order is the rail order.)

`SportsItems` — add `playables` per the playable rule and the 6-argument `toItem`.

`SportsContentSource` — per Interfaces; `defaultRefreshInterval()` returns `Duration.ofMinutes(5)`. `SportsConfiguration` passes `ObjectProvider<PinnedLinks>` (bean-method parameter) and `properties`.

- [ ] **Step 4: Play-sheet wording**

`static/js/play-sheet.js` — where `openPlaySheet(tileOrData)` reads the item (D3), keep the item's kind in a module variable (e.g. `let sheetKind = null;` set from `data-kind` / `data.kind`; adapt to D3's real variable if one exists). In G's `showPin(offer)` replace the text assignment with:

```js
    const event = sheetKind === "LIVE_EVENT";
    const noun = event ? "event" : "title";
    document.getElementById("sheet-pin-text").textContent = offer.serviceName
        ? `This opens the ${offer.serviceName} app, not this ${noun}. Paste the ${offer.serviceName} link for this ${noun} to open it directly.`
        : event
            ? "Home Control cannot open this event directly. Paste a link to it (for example its page on dazn.com) to pin it."
            : "Home Control cannot open this title on your services. Paste a link to it (Netflix, Prime Video, YouTube, DAZN or any web link) to pin it.";
```

(G's `PinUpgradeE2eTest` asserts `Paste the Netflix link for this title` for a `MOVIE`, which this keeps.)

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/sports src/main/resources/static/js/play-sheet.js \
  src/test/java/dev/andre/homecontrol/sources/sports src/test/java/dev/andre/homecontrol/core/playback/LiveEventRoutingTest.java \
  src/test/java/dev/andre/homecontrol/web/StaticAssetsTest.java
git commit -m "feat: live now and today rail that opens the DAZN app or a pasted event link"
```

---

### Task 5: H5 · Tests

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/sources/sports/ics/IcsFixtureContractTest.java`, `src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbFixtureContractTest.java`, `src/test/java/dev/andre/homecontrol/web/SportsEndToEndTest.java`, `docs/superpowers/reviews/2026-09-16-sports-dazn-acceptance.md`
- Modify: `src/test/java/dev/andre/homecontrol/core/playback/LiveEventRoutingTest.java`, `README.md`
- Uses unchanged: `FakeCalendarServer`, `FakeTheSportsDbServer`, every ICS and TheSportsDB fixture (Tasks 1–2), A's `FakeRemoteServer`, `CertificateStore`, `AndroidTvSettings.device`, `DeviceManager.adopt/state`, `SecretStore.names`, C's login flow and `JellyfinEndToEndTest` helpers, D's endpoints (`/setup/sources/preferences/locale`, `/sources`, `/sources/{s}/rails/{r}` and `/refresh`, `/devices/{id}/route-preview`, `/devices/{id}/play-attempt`), G's `POST /setup/sources/pinned/upgrade`.

**Interfaces:**
- Consumes: everything above; no production code changes except README.
- Produces: contract tests over every ICS and TheSportsDB fixture; the full capability × playable matrix for `LIVE_EVENT`; an end-to-end proof over real sockets that the Shield receives exactly `https://www.dazn.com/` for a mapped event and exactly the pasted link after a per-event pin, with no calendar token or key in any browser-bound response; the manual checklist; README documentation.

- [ ] **Step 1: Fixture contract tests**

`sources/sports/ics/IcsFixtureContractTest.java`:
- `everyCalendarFixtureIsReadable`: `bundesliga.ics`, `recurring.ics`, `outlook.ics` parse with `skippedEvents` 0 and at least 3 events each; `broken.ics` parses with `skippedEvents` > 0; `not-a-calendar.html` is refused. The directory listing of `/fixtures/ics` contains exactly these five files (a new fixture must be added to this test).
- `validFixturesFollowTheRfcShape`: in the three valid files every `VEVENT` has a `UID`, a `SUMMARY` and a `DTSTART`; every unfolded line of the raw text contains a `:`; every `BEGIN:X` has a matching `END:X` in order; `bundesliga.ics` contains at least one folded continuation line (a physical line starting with a space).
- `occurrencesAreWellFormed`: for each valid fixture expanded over [2026-09-01T00:00Z, 2026-10-10T00:00Z] with fallback `Europe/Berlin` and 120 min → every occurrence has a non-blank summary and `endsAt` after `startsAt`; all-day occurrences start and end at midnight in `Europe/Berlin` and have `allDayDate`.
- `mappedEventsAreWellFormed`: every occurrence mapped with `CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence)` → id matches `^ics:c-3f9a1c2b7d4e:[0-9a-f]{16}$` and ids are unique within a fixture; competition key `calendar:c-3f9a1c2b7d4e`; `SportsItems.toItem` (settings with that calendar, zone Berlin, locale de-DE, now `2026-09-19T14:00Z`) → kind `LIVE_EVENT`, source `sports`, non-blank subtitle, `startsAt`/`endsAt` equal the event's.
- `idsAreStableAcrossParses`: parsing and mapping `bundesliga.ics` twice gives equal id lists.

`sources/sports/thesportsdb/TheSportsDbFixtureContractTest.java`:
- `everyFixtureIsAJsonObject`: every `*.json` under `/fixtures/thesportsdb` parses to an object; exactly 9 files.
- `leagueFixturesHaveTheDocumentedShape`: `lookupleague-*.json` → `leagues` is an array of objects with string `idLeague` of digits, non-blank `strLeague`, `strSport`, `strCountry`, `strBadge` starting with `https://` — or `null` (unknown); `search_all_leagues-germany-soccer.json` → the array is named `countries` and every entry has `idLeague` and `strLeague`.
- `eventFixturesHaveTheDocumentedShape`: `eventsday-*.json` → `events` is an array or `null`; every event object has `idEvent`, `idLeague`, `strStatus` and at least one of `strTimestamp` / `dateEvent`; every non-blank `strTimestamp` matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})?$`; every non-null `strThumb`/`strPoster` is blank or starts with `https://r2.thesportsdb.com/`.
- `fixtureFileNamesMatchTheirContent`: every event in `eventsday-<date>-<league>.json` whose `idEvent` is numeric and whose `idLeague` equals `<league>` has `dateEvent` equal to `<date>` (the deliberately wrong-league event is excluded by its league).
- `mappedEventsAreWellFormed`: every event of every `eventsday-<date>-<league>.json` mapped with `TheSportsDbEventMapper` (league from the file name, zone Berlin, default durations) → id `^tsdb:[0-9]+$`, competition key `thesportsdb:<league>`, non-blank title ≤ 200, `endsAt` after `startsAt`, artwork null or `https://`.
- `noFixtureContainsAPersonalKey`: no fixture text contains `FakeTheSportsDbServer.PERSONAL_KEY`.

- [ ] **Step 2: Planner matrix for `LIVE_EVENT`**

`core/playback/LiveEventRoutingTest.java` — add `liveEventMatrix` (`@ParameterizedTest` with `@MethodSource`), items of kind `LIVE_EVENT` with `startsAt` `2026-09-19T13:30:00Z` and `endsAt` `15:25:00Z`, rows (playables × capabilities → expected `describe()` of `plan`, expected `routes` size):

| Playables | Capabilities | `plan(...).describe()` | `routes` |
|---|---|---|---|
| DAZN app home | `APP_LINK` | `Open the DAZN app (not this title)` | 1 |
| DAZN app home | `REMOTE_KEYS, POWER, VOLUME, APP_LINK` (Shield) | `Open the DAZN app (not this title)` | 1 |
| DAZN app home | `CAST_RECEIVER, VOLUME` | contains `cannot open app links` | 0 |
| DAZN app home | none | contains `cannot open app links` | 0 |
| pasted DAZN event link | `APP_LINK` | `Open in the DAZN app` | 1 |
| Prime Video app home | `APP_LINK` | `Open the Prime Video app (not this title)` | 1 |
| Netflix app home | `APP_LINK` | `Open the Netflix app (not this title)` | 1 |
| none | `APP_LINK` | `This item has nothing playable` | 0 |
| none | none | `This item has nothing playable` | 0 |

Every row is also asserted for the same item with `kind` `VIDEO` and no times → identical results.

- [ ] **Step 3: End-to-end test**

`src/test/java/dev/andre/homecontrol/web/SportsEndToEndTest.java` — structure copied from G's `StreamingLaunchersEndToEndTest` (same `send/get/page/post` helpers, a cookie-keeping `browser` client and a `stranger` client, every browser body recorded):

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SportsEndToEndTest {

    static final String LOGIN = "household password";
    static final String TOKEN = "token-e2e-7f3a91";
    static final String EVENT_LINK = "https://www.dazn.com/de-DE/fixture/ContentId:e2e1a2b3c4d5e6f7g8h9i0";
    static final FakeCalendarServer CALENDARS;
    static final FakeTheSportsDbServer SPORTSDB;
    static Path dataDir;

    static {
        try {
            CALENDARS = new FakeCalendarServer();
            SPORTSDB = new FakeTheSportsDbServer().withStandardResponses();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("sports-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.sports.calendar.allow-loopback", () -> "true");
        registry.add("home-control.sports.thesportsdb.api-base-url", () -> SPORTSDB.apiBase().toString());
    }

    @AfterAll
    static void stop() {
        CALENDARS.close();
        SPORTSDB.close();
    }
    // helpers as in StreamingLaunchersEndToEndTest; Accept: application/json on JSON calls
}
```

Content is generated from the real clock so "live" holds whatever time the test runs (`now` = `Instant.now().truncatedTo(ChronoUnit.MINUTES)`):
- Calendar at `/private/` + `TOKEN` + `/league.ics`, served as `text/calendar` — a `VCALENDAR` with `X-WR-CALNAME:E2E league` and two events: `UID:e2e-live@fixtures.example`, `DTSTART` = now − 30 min, `DTEND` = now + 90 min (UTC basic format `yyyyMMdd'T'HHmmss'Z'`), `SUMMARY:Calendar Live Match`; and `UID:e2e-over@fixtures.example`, now − 5 h to now − 3 h, `SUMMARY:Calendar Finished Match`.
- TheSportsDB: `eventsday.php {d=<UTC date of now − 20 min>, l=4331}` → `{"events":[{"idEvent":"9000001","idLeague":"4331","strLeague":"German Bundesliga","strSport":"Soccer","strEvent":"TheSportsDB Live Match","strHomeTeam":"Home","strAwayTeam":"Away","strTimestamp":"<now − 20 min as yyyy-MM-dd'T'HH:mm:ss, UTC>","dateEvent":"<that date>","strTime":"<HH:mm:ss>","strThumb":null,"strPoster":null,"strStatus":"NS","strPostponed":"no"}]}`.

Test `mappedEventsOpenDaznAndPastedLinksOpenTheEvent` — steps and assertions, in order:
1. `certificates.loadOrCreate("shield-e2e")`; `devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()))` with `FakeRemoteServer shieldRemote` in try-with-resources; `await().until(() -> devices.state("shield-e2e").connected())`.
2. Before any secret: `GET /setup` from `stranger` → 200 and contains `id="sports"`.
3. `POST /setup/sources/sports/competitions` `leagueId=4331` from `browser` → 302 (no secret yet: the free key needs no login); `GET /setup` from `stranger` still 200 and contains `German Bundesliga`; the fake saw `lookupleague.php` with key `123`.
4. `POST /setup/sources/sports/calendars` `url=` + `CALENDARS.url("/private/" + TOKEN + "/league.ics")`, `label=`, `loginPassword=LOGIN`, `loginPasswordConfirmation=LOGIN` → 302; `GET /setup` from `browser` contains `E2E league` and `127.0.0.1` and not `TOKEN`; from `stranger` `GET /sources` → 401.
5. `POST /setup/sources/preferences/locale` `locale=de-DE`, `region=DE`, `providers=dazn` → 302; `POST /setup/sources/sports/time-zone` `timeZone=Europe/Berlin` → 302.
6. Read `sports.json` from `dataDir` with `JsonMapper`: calendar id `c-…`; the file text does not contain `TOKEN` or `/private/`. `POST /setup/sources/sports/providers` with `provider:calendar:<id>=dazn` and `provider:thesportsdb:4331=dazn` → 302; `GET /setup` contains `Where you watch it (your setting)`.
7. `POST /sources/sports/rails/live-today/refresh`; `await().atMost(10 s)` until `GET /sources/sports/rails/live-today` is 200 with `"status":"READY"` and at least 2 items. Parsed items: the first has title `Calendar Live Match`, subtitle `Live · E2E league · DAZN (your setting)`, kind `LIVE_EVENT`, non-null `startsAt`/`endsAt`; the second `TheSportsDB Live Match`, subtitle `Live · German Bundesliga · DAZN (your setting)`, id `tsdb:9000001`. The body does not contain `Calendar Finished Match`, `TOKEN`, `dazn.com`, `playables`, `AppLink`.
8. `GET /devices/shield-e2e/route-preview?source=sports&item=tsdb:9000001` → `$.route.description` `Open the DAZN app (not this title)`, `$.pin.upgradeOf` `sports/tsdb:9000001`, `$.pin.serviceName` `DAZN`.
9. `POST /devices/shield-e2e/play-attempt` `source=sports`, `item=tsdb:9000001` → 200, `played` true; `shieldRemote.nextAppLink()` = `https://www.dazn.com/`.
10. `POST /setup/sources/pinned/upgrade` `url=EVENT_LINK`, `upgradeOf=sports/tsdb:9000001` → 200, `$.title` `TheSportsDB Live Match`.
11. Preview again → `$.route.description` `Open in the DAZN app`, `$.pin` null; play-attempt → `nextAppLink()` = `EVENT_LINK`.
12. `POST /setup/sources/sports/providers` `provider:calendar:<id>=` → 302. The calendar item's id is read from the rail (await the refreshed rail whose `Calendar Live Match` subtitle is `Live · E2E league`); its preview → `$.playable` false, `$.pin.service` null; `POST /devices/shield-e2e/play-attempt` for it → 422 and the body names `This item has nothing playable`; `shieldRemote` received no further app link (use A's `FakeRemoteServer` accessor for "no app link within a timeout"; if the real fake has none, assert the count of received app links is unchanged and say so in the report).
13. `POST /setup/sources/sports/calendars/<id>/remove` → 302; `secrets.names()` no longer contains `sports.calendar.<id>`; await the rail without `Calendar Live Match` and still with `TheSportsDB Live Match`.
14. `secrets.json` does not contain `TOKEN` in plain text; every `SPORTSDB` request used key `123`; the calendar fake received requests only on `/private/<TOKEN>/league.ics`.
15. No recorded browser body contains `TOKEN`, `/private/`, `api/v1/json` or `9876543210`.

A second test `aPersonalKeyIsASecretAndGoesBackToFree` (`@TestMethodOrder`, `@Order(2)`, sharing the logged-in `browser` as a static field): `POST /setup/sources/sports/thesportsdb/key` `key=FakeTheSportsDbServer.PERSONAL_KEY` → 302; `GET /setup` contains `Using your personal key.` and not the key; the next `POST /sources/sports/rails/live-today/refresh` makes TheSportsDB requests with the personal key; `secrets.names()` contains `sports.thesportsdb.key`; `POST /setup/sources/sports/thesportsdb/free-key` → 302; the secret is gone and new requests use `123`.

- [ ] **Step 4: Manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-sports-dazn-acceptance.md`:

```markdown
# Sports and DAZN — acceptance record (2026-09-16)

Plan H "Sports and DAZN", Task 5. No agent running this plan has access to a real NVIDIA Shield, LG or Samsung
TV, a DAZN subscription, real calendar feeds or a TheSportsDB personal key, so every manual check below is
recorded as pending for a human with hardware. None is claimed as passed. Automated coverage:
`SportsEndToEndTest` (fake calendar server, fake TheSportsDB, fake Shield, exact app-link strings, secrets),
fixture contract tests, `LiveEventRoutingTest`, unit tests for the ICS subset, fetch policy, cache and rail order.

## Calendars

1. Add a public sports calendar by its `webcal://` link (e.g. a team or league feed from a fixtures site): it is
   fetched over https, named from the calendar, and its matches appear in "Live now / Today" at the right local
   kick-off times.
   **Pending — requires real hardware.**
2. Add a Google Calendar "secret address in iCal format": setup asks for a login password first; afterwards the
   setup page, page source and network tab never show the secret part of the link.
   **Pending — requires real hardware.**
3. Add a calendar served by a Nextcloud or Radicale server on the LAN (private IP): it loads.
   **Pending — requires real hardware.**
4. Try `http://localhost:8080/…` and `http://169.254.169.254/`: both are refused with the "belongs to this machine
   or its network link" message.
   **Pending — requires real hardware.**
5. A calendar exported from Outlook (Windows time zone names) shows correct times; setup reports no unknown
   time zones.
   **Pending — requires real hardware.**
6. A weekly recurring event (e.g. a league night) appears every week; a monthly one is reported in setup as shown
   only once.
   **Pending — requires real hardware.**

## TheSportsDB

7. Find "Germany" / "Soccer" in setup and add the Bundesliga: today's matches appear; setup states the free key's
   limit of 3 matches per day.
   **Pending — requires real hardware.**
8. Enter a personal TheSportsDB key: it is accepted, all of today's matches appear, and switching back to the
   free key removes the secret.
   **Pending — requires real hardware.**
9. Add 10 competitions and reload the dashboard repeatedly: no "limiting requests" error within a day (daily
   cache).
   **Pending — requires real hardware.**
10. Compare kick-off times with the league's official schedule on a match day around a DST change (last Sunday of
    October): times match in the configured time zone.
    **Pending — requires real hardware.**

## Where you watch it (honesty)

11. The setup page labels the provider column "Where you watch it (your setting)" and says Home Control does not
    know broadcast rights; no competition is preselected as DAZN.
    **Pending — requires real hardware.**
12. On the dashboard every tile that names a service says "(your setting)"; no screen suggests a personalised DAZN
    feed or official broadcast data.
    **Pending — requires real hardware.**

## Shield (Android TV app links)

13. A live event of a competition set to DAZN: the play sheet says "Open the DAZN app (not this title)"; playing
    opens the DAZN app (`https://www.dazn.com/`).
    **Pending — requires real hardware.**
14. Paste a DAZN event link (from dazn.com in a browser) in the play sheet: the sheet then says "Open in the DAZN
    app"; record whether the DAZN app opens that event, the app home, or nothing.
    **Pending — requires real hardware.**
15. With the DAZN app not installed, playing a DAZN event shows the "the app may not be installed" hint.
    **Pending — requires real hardware.**
16. A competition set to Prime Video (e.g. a Champions League Tuesday match) opens the Prime Video app.
    **Pending — requires real hardware.**

## LG webOS and Samsung Tizen (F adapters)

17. webOS: a DAZN event opens `https://www.dazn.com/` in the TV browser (F's default for web links); record whether
    the DAZN webOS app claims it instead.
    **Pending — requires real hardware.**
18. Tizen: a DAZN event is refused with "Samsung TVs cannot open web links …".
    **Pending — requires real hardware.**

## Live state

19. Leave the dashboard open across a kick-off: within 5 minutes the match moves from its time to "Live"; after the
    match (duration elapsed) it disappears from the rail.
    **Pending — requires real hardware.**
20. After local midnight the rail shows the new day's matches without a restart.
    **Pending — requires real hardware.**
```

- [ ] **Step 5: README**

Add a section `## Sport and DAZN` after the Netflix / Prime Video / DAZN section:
- What it is: a "Live now / Today" rail from calendar links you add and from TheSportsDB for competitions you choose; DAZN has no public schedule API, so Home Control does not know what DAZN shows.
- Calendars: Setup → Sports → Calendars; `https://`, `http://` and `webcal://` links; a calendar link is stored as a secret (it can contain a private token), so adding the first one sets the login password; LAN calendar servers work; links to this machine (`localhost`, `127.0.0.1`) and to link-local addresses are refused unless `HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK=true`; what is read (event start, end, title; weekly and daily repeats; other repeat rules show once); refreshed every 6 hours.
- TheSportsDB: find a competition by country and sport or enter its id; the free key shows at most 3 matches per competition per day; a personal key (TheSportsDB supporters) is stored as a secret; fixtures are cached for a day; data comes from a community database and can be wrong; attribution "Data from TheSportsDB".
- Where you watch it: per competition, your own setting; DAZN, Netflix and Prime Video open their app; paste an event link in the play sheet to open that event directly; the pasted link also appears under Pinned links.
- Time zone: Setup → Sports; defaults to the container's `TZ`; set `TZ` in Compose or choose a zone in setup.
- What each TV does with DAZN links (Android TV app link; webOS browser; Tizen refused).
- Security notes: the server fetches calendar links you add; DNS rebinding between the address check and the fetch is not prevented; artwork loads directly from `r2.thesportsdb.com` in the browser.
- Configuration rows: `HOME_CONTROL_SPORTS_ENABLED` (true), `HOME_CONTROL_SPORTS_TIME_ZONE` (empty), `HOME_CONTROL_SPORTS_RAIL_SIZE` (30), `HOME_CONTROL_SPORTS_MAX_CALENDARS` (10), `HOME_CONTROL_SPORTS_MAX_COMPETITIONS` (10), `HOME_CONTROL_SPORTS_DEFAULT_EVENT_DURATION` (120m), `HOME_CONTROL_SPORTS_CALENDAR_REFRESH` (6h), `HOME_CONTROL_SPORTS_CALENDAR_ALLOW_LOOPBACK` (false), `HOME_CONTROL_SPORTS_THESPORTSDB_ENABLED` (true), `HOME_CONTROL_SPORTS_THESPORTSDB_API_BASE_URL`, `HOME_CONTROL_SPORTS_THESPORTSDB_FIXTURES_TTL` (24h); `/data/sports.json` in the data files list.

- [ ] **Step 6: Run everything**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.*' --tests 'dev.andre.homecontrol.core.playback.LiveEventRoutingTest' --tests 'dev.andre.homecontrol.web.SportsEndToEndTest'`, then `.superpowers/gradle.sh build`, then `.superpowers/e2e.sh` (D7's and G's browser tests must still pass with the changed pin wording).
Expected: PASS; BUILD SUCCESSFUL; browser tests pass in Chromium and WebKit.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/sports/ics/IcsFixtureContractTest.java \
  src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbFixtureContractTest.java \
  src/test/java/dev/andre/homecontrol/core/playback/LiveEventRoutingTest.java \
  src/test/java/dev/andre/homecontrol/web/SportsEndToEndTest.java \
  docs/superpowers/reviews/2026-09-16-sports-dazn-acceptance.md README.md
git commit -m "test: sports fixtures, live event routing matrix, end-to-end DAZN launch and acceptance checklist"
```

---

## Final Automated Verification

- [ ] `.superpowers/gradle.sh build` — BUILD SUCCESSFUL.
- [ ] `.superpowers/e2e.sh` — all browser tests pass.
- [ ] `grep -rn "import dev.andre.homecontrol.sources\|import dev.andre.homecontrol.adapters" src/main/java/dev/andre/homecontrol/{core,content,playback,device,web}` — no new hits.
- [ ] `grep -rn "import dev.andre.homecontrol.adapters" src/main/java/dev/andre/homecontrol/sources` — no hits.
- [ ] `grep -rn "HttpClient" src/main/java/dev/andre/homecontrol/sources/sports | grep -v "calendar/CalendarFetcher\|thesportsdb/TheSportsDbClient\|Configuration"` — no hits (only the two clients speak HTTP).
- [ ] `grep -rln "9876543210\|token-abc123\|token-e2e" src/main` — no hits.
- [ ] `grep -rn "strTvRights" src/main` — no hits (community TV listings never drive the mapping).
- [ ] The acceptance checklist has every item "Pending — requires real hardware".

## Out of scope for this plan

- Scraping DAZN's schedule page (spec §4.2 option 3: fragile and against the terms of service).
- Any DAZN account, "continue watching" or personalised data (no public API).
- A per-competition deep link (e.g. a DAZN competition page) in the mapping; per-event pins cover the need.
- Expiring per-event pins after the event (G's pin model has no expiry).
- Full RFC 5545: `VTIMEZONE` rule evaluation, `RDATE`, monthly/yearly rules, `BYSETPOS`, numbered `BYDAY`, `VTODO`/`VJOURNAL`.
- Calendar authentication with user name and password (HTTP Basic/CalDAV); secret links cover common providers.
- Scores, standings, team logos and a match detail view.
- Cross-feed de-duplication of the same match from a calendar and TheSportsDB.
- A time-zone field in D4's locale form (the zone lives in the sports section).
- Notifications when a followed match starts (commands are ephemeral; nothing is scheduled).
