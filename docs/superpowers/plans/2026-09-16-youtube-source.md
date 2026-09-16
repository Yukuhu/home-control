# YouTube Source (Sub-project E) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add YouTube as a content source: the user connects their own Google Cloud OAuth client through Google's device authorization grant ("enter this code on your phone"), the dashboard shows *New from your subscriptions*, an honest *Watch Later* rail and user-selected playlist rails, YouTube search appears in the unified search without burning the daily quota, every item plays through the app-link route (`https://www.youtube.com/watch?v=ID` → the YouTube app on Android TV, LG webOS and Samsung Tizen), and Cast-only devices can optionally play through the reverse-engineered YouTube Lounge API behind a per-device switch that is off by default.

**Architecture:** One `@ConditionalOnProperty` module `sources/youtube` on the contracts of sub-projects A–D and F. (1) Authorization: `YouTubeHttp` (a small `java.net.http` client, no redirects), `GoogleOAuthClient` (device code, token polling, refresh, revoke), `YouTubeAuthorizationService` (one pending authorization in memory, polled on a background thread), `GoogleTokens` (access token in memory only). Client id, client secret and refresh token live in C's `SecretStore`; nothing token-shaped reaches the browser. (2) Content: `QuotaLedger` (units per Pacific-time day, persisted in `/data/youtube-quota.json`), `YouTubeApiClient` (Data API v3 GETs with quota charging and error mapping), `SubscriptionsFeed` (subscriptions → uploads playlists → newest videos, round-robin within a per-refresh channel budget), `YouTubePlaylists` (Watch Later and chosen playlists), `YouTubeSearch` (cached, capped per day), `YouTubeContentSource` (C's `ContentSource`; D's `RailCache` refreshes it hourly). Artwork goes through `GET /sources/youtube/thumbnails/{videoId}` like C's image proxy. (3) Playback: items carry only `PlayableRef.AppLink(watch URL, "youtube")`; A's `AppLinkStrategy` routes it and F's adapters translate it. For Cast devices with the switch on, `YouTubeLoungeResolver` (C's `PlayableResolver` SPI) adds `PlayableRef.YouTubeLounge(videoId)`; `YouTubeLoungeStrategy` routes it to `Route.YouTubeLounge`, which `YouTubeLoungeRouteExecutor` (C's `RouteExecutor` SPI) runs: ask the Cast YouTube receiver `233637DE` for its screen id through a new `DeviceManager.query` (B's Cast session), get a lounge token, bind a lounge session, send `setPlaylist`. D5's search gains "on-demand" sources so a metered search runs only when the user taps "Search YouTube".

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle through `.superpowers/gradle.sh`, Thymeleaf, htmx 2, vanilla ES modules, `java.net.http.HttpClient`, JDK `com.sun.net.httpserver.HttpServer` (test fake), JUnit 5, AssertJ, Mockito, Awaitility. **No new dependency** (OAuth, Data API and Lounge are plain HTTPS form posts and JSON; Jackson 3 is already on the classpath).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §4.2 (YouTube row: Data API v3 with OAuth; subscriptions → upload playlists, Watch Later, playlists, search; no home feed; app link on Android TV, webOS content target, Tizen DIAL, Cast through the Lounge API; 10 000 units/day, search 100 units), §5.2–§5.3 (playable references, planner order, optimistic app links), §6.1 (rails "New from your subscriptions", search quota-aware and debounced, setup), §7 (`sources/youtube`: OAuth device flow, subscriptions cache, search; `@ConditionalOnProperty`; `RestClient`-style small typed clients), §7.1 (OAuth device flow implemented directly), §8 (`sources.json`, `secrets.json`), §9 (tokens never leave the server), §11 risks ("Lounge API breaks", "quota exhaustion"), §12 (fixtures). Roadmap `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md` section E (E1–E6, GitHub #49–#54, epic #9). Built on plans A `2026-09-16-multi-device-core.md`, B `2026-09-16-google-cast-adapter.md`, C `2026-09-16-jellyfin-source.md`, D `2026-09-16-dashboard-shell.md`, F `2026-09-16-smart-tv-adapters.md`. Execution order of the program: A, B, C, D, F, then this plan.

**External references (read 2026-09-16):**
- Google Identity, "OAuth 2.0 for TV and Limited-Input Device Applications": `POST https://oauth2.googleapis.com/device/code` (`client_id`, `scope`) → `device_code`, `user_code`, `verification_url`, `expires_in`, `interval`; `POST https://oauth2.googleapis.com/token` (`client_id`, `client_secret`, `device_code`, `grant_type=urn:ietf:params:oauth:grant-type:device_code`) → `access_token`, `expires_in`, `refresh_token`, `scope`, `token_type` or `error` ∈ `authorization_pending` (HTTP 428), `slow_down` (403), `access_denied` (403), `expired_token` (400), `invalid_client` (401, also "Invalid client type."); refresh with `grant_type=refresh_token` (`invalid_grant` when revoked or expired); revoke with `POST https://oauth2.googleapis.com/revoke` (`token`). The device flow's allowed-scopes list includes `https://www.googleapis.com/auth/youtube.readonly`. RFC 8628 §3.5: on `slow_down` the interval grows by 5 s. Refresh tokens of an External app whose consent screen is in *Testing* expire after 7 days.
- YouTube Data API v3 reference and quota calculator: `subscriptions.list`, `channels.list`, `playlistItems.list`, `playlists.list`, `videos.list` cost 1 unit; `search.list` costs 100; default project quota 10 000 units/day, reset at midnight Pacific Time; error body `{"error":{"code","message","errors":[{"domain","reason","message"}]}}` with reasons `quotaExceeded`, `accessNotConfigured`, `insufficientPermissions`, `playlistNotFound`, `authError`. `search.list` snippet titles are HTML-escaped. Since 2016 `playlistItems.list` for `WL` (Watch Later) returns no items for most accounts.
- YouTube Lounge API (unofficial, no documentation from Google). Behaviour taken from two open-source clients, reimplemented here from their observable wire format, no code copied: `casttube` (MIT, `ur1katz/casttube`, `YouTubeSession.py`: `get_lounge_token_batch` with `screen_ids`, `bc/bind` with `RID`/`VER=8`/`CVER=1`, SID from `["c","<sid>",…]`, gsessionid from `["S","<id>"]`, `req0__sc=setPlaylist`, `req0_videoId`) and `pyytlounge` (GPL-3.0, `FabioGNR/pyytlounge` master `wrapper.py`: `pairing/get_screen` with `pairing_code`; bind body `app=web, mdx-version=3, name, id, device=REMOTE_CONTROL, capabilities, magnaKey=cloudPairedDevice, ui=false, theme=cl, loungeIdToken` at `bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error`; commands `count=1, ofs=<n>, req0__sc=<command>, req0_<param>` with query `name, loungeIdToken, SID, AID, gsessionid, device=REMOTE_CONTROL, app=youtube-desktop, VER=8, v=2, RID`; responses are length-prefixed chunks of `[[eventId,[type,…args]],…]`). pychromecast's YouTube controller: receiver app id `233637DE`, namespace `urn:x-cast:com.google.youtube.mdx`, request `{"type":"getMdxSessionStatus"}`, reply `{"type":"mdxSessionStatus","data":{"screenId":…}}`.

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works. (Calling Google's APIs is the source's job; nothing of ours runs in a cloud.)
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a play request that cannot be routed now fails now with a reason. Nothing is queued.
- Only adapters speak device protocols; only sources speak content APIs. The Cast part of the Lounge route (asking the YouTube receiver for its screen id) goes through `DeviceManager.query` into B's Cast adapter; the HTTP part lives in `sources/youtube`. `core`, `content`, `playback`, `device` and `web` must not import `sources.youtube` or `adapters.*.protocol`.
- Route by capability, not by brand.
- Honesty about walled gardens: YouTube has no home-recommendations endpoint; the UI never calls a rail "Recommended". Watch Later is shown only when the API really returns it, otherwise the rail says why.
- Secrets raise the bar: login is mandatory once any secret exists (C1). The client id, client secret and refresh token are secrets; the access token exists only in memory; none of them, nor the device code, lounge token, SID or gsessionid, reach any HTML, JSON, SSE payload, log line, exception message or `toString()`.
- Persistent state stays in `/data` as JSON written atomically (`sources.json` through C's `JsonFileSourceSettings`, `secrets.json` through C's `SecretStore`, the new `youtube-quota.json`).
- Every adapter and source is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; every source has recorded JSON fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`).

Epic constraints:

- Build and test only through the Docker wrapper: `.superpowers/gradle.sh build`; single tests with `.superpowers/gradle.sh test --tests '<pattern>'`.
- Plans A, B, C, D and F are the contract. Where real code differs from their listings (a field name, a helper, a constructor parameter), adapt the edit to the real code, keep the behaviour this plan specifies, and say so in the task report. Names this plan relies on: `ContentSource` (`id`, `displayName`, `available`, `rails`, `rail`, `item`, `searchable`, `search`, `defaultRefreshInterval`), `ContentSources`, `Rail`, `RailDescriptor`, `ContentSourceException`, `ContentItem` (8 components, with `progress`), `ContentItemView`, `PlayableRef` (`AppLink`, `CastLoad`, `CastMessage`, `JellyfinItem`, `JellyfinSession`, `StreamUrl`), `Route` (`OpenAppLink`, `Cast`, `CastMessage`, `JellyfinSession`, `Unroutable`), `RouteStrategy`, `PlaybackPlanner` (`plan`, `routes`, `explain`), `PlayableResolver` (+ `Resolution`), `RouteExecutor`, `RouteKeys`, `PlaybackService` (`plan`, `play`, `preview`, `attempt`, private `execute`), `DeviceManager` (`devices`, `device`, `capabilities`, `execute`), `DeviceHandle`, `Capability.CAST_RECEIVER`, `Action.CastMessage`, `ActionFailedException`, `DeviceOfflineException`, `UnsupportedActionException`, `CastSession` (`requireConnected`, `launch`, `awaitNamespace`, `call`, `receiver`, `loadTimeout`), `CastConnection` (`connect`, `send`, `expect`), `CastPayloads.custom`, `CastTimeoutException`, `FakeCastReceiver` (`appSpeaks`, `answerCustom`), `SecretStore` (`secret`, `putSecrets`), `LoginService` (`loginRequired`, `storeSecrets`, `removeSecrets`), `PasswordRejectedException`, `LoginRequiredException`, `JsonFileSourceSettings` (`get`, `put`, `remove`), `SetupController`, `SearchService`, `SearchOutcome`, `SearchController`, `ContentController` (`/search`), `fragments/search.html`, `fragments/rails :: tile`, `RailCache`, `AndroidTvProperties.dataDir()`.
- `home-control.youtube.enabled` (default `true`) switches the whole module: every bean, controller and controller advice in `sources/youtube` carries `@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)`.
- Every Google and YouTube base URL is a property (`oauth-base-url`, `api-base-url`, `lounge-base-url`, `thumbnail-base-url`), so tests point the module at `FakeGoogleServer`. No test ever reaches the internet.
- The YouTube HTTP client never follows redirects and has connect and request timeouts. Exception messages name the host at most, never a query string, form body or header.
- Quota is charged *before* each Data API call (a failed call still costs Google units). No Data API call happens without a charge; no search runs as a side effect of typing.
- Jackson 3 API: `asString(default)`, `asInt(default)`, `asLong(default)`, `asBoolean(default)` on possibly missing nodes.
- All wire formats in this plan (OAuth form posts and replies, Data API queries, `youtube-quota.json`, `sources.json` keys, Lounge requests and the bind chunk format, the Cast MDX messages) are normative; tests pin them.
- The Lounge route is labelled "best effort" everywhere it is shown (setup, route description, failure message, README).
- The manual acceptance checklist is never marked passed by an agent.

## Decisions

- Decision: the user brings their own Google Cloud project and an OAuth client of type "TVs and Limited Input devices"; client id and client secret are pasted on the setup page and stored in `secrets.json` (`youtube.client-id`, `youtube.client-secret`) — a shared client id would need Google verification and a shared 10 000-unit quota; the device flow needs the client secret on the token endpoint — cost if wrong: a one-time 10-minute setup the page walks through step by step.
- Decision: scope is only `https://www.googleapis.com/auth/youtube.readonly` — every call in this plan is a read; `youtube` (write) would make the consent screen scarier for no gain — cost if wrong: a future "add to playlist" feature needs a re-consent.
- Decision: the setup page tells the user to set the consent screen's publishing status to *In production* (the "unverified app" warning is acceptable for a single household) — in *Testing* Google expires refresh tokens after 7 days, which would look like a bug; when refresh fails with `invalid_grant` the rail error names both causes — cost if wrong: weekly reconnects for users who ignore the hint, with a clear message.
- Decision: the whole connect flow is one form (client id, client secret, and a new login password when none exists) that stores the client secrets through `LoginService.storeSecrets` and immediately starts a device authorization; `POST …/authorize` restarts it with the stored client — storing the client first guarantees a login exists before the refresh token arrives on a background thread (which has no request and calls `SecretStore.putSecrets` directly) — cost if wrong: a user who abandons the flow keeps a login password with only client secrets stored; "Disconnect" removes them.
- Decision: one pending authorization at a time, held in memory; `device_code` never leaves the server; the browser sees `user_code`, `verification_url`, the expiry and a state (`PENDING`, `CONNECTED`, `DENIED`, `EXPIRED`, `FAILED`); the setup fragment polls `GET /setup/sources/youtube/authorization` every 3 s with htmx while pending, and sends `HX-Refresh: true` once connected — no SSE plumbing for a one-off flow, works without JS by reloading — cost if wrong: a restart during the 30-minute window loses the pending code (start again).
- Decision: server-side polling runs on one daemon scheduler thread ticking every second; a poll is sent only when `now ≥ nextPollAt`; `authorization_pending` waits `interval`, `slow_down` adds 5 s to the interval (RFC 8628), `access_denied` → `DENIED`, `expired_token` or local expiry → `EXPIRED`, `invalid_client` → `FAILED` with a message naming the client type, network errors keep polling — Google's documented behaviour; the one-second tick keeps tests fast by calling `pollOnce()` directly with a mutable clock — cost if wrong: tune one constant.
- Decision: access tokens are cached in memory until 60 s before `expires_in`; a 401 from the Data API invalidates the cache and the call is retried once with a fresh token; `invalid_grant` on refresh marks the authorization revoked in memory (rails fail with a reconnect message, `available()` stays true so the rail error is visible) and does not delete secrets automatically — deleting the last secret would silently remove the login (C's "secrets exist ⇔ login exists") — cost if wrong: a revoked authorization stays stored until the user reconnects or disconnects.
- Decision: "Disconnect" revokes the refresh token at Google (best effort, failures logged without the token), removes all three YouTube secrets and the account-specific settings (channel, Watch Later switch, playlists), keeps the per-device Lounge switches and the lounge remote id — the Lounge route needs no Google account — cost if wrong: none found.
- Decision: `home-control.youtube.enabled` defaults to `true` — the module makes no network call until the user connects an OAuth client (Lounge is off per device), and YouTube is release 1.0's headline — cost if wrong: `HOME_CONTROL_YOUTUBE_ENABLED=false`.
- Decision: `java.net.http.HttpClient` in a ~120-line `YouTubeHttp` (same reasons as C's Jellyfin client: explicit `Redirect.NEVER`, timeouts, status mapping) instead of `RestClient` — cost if wrong: swapping is local to `YouTubeHttp`.
- Decision: quota accounting charges the documented unit cost before each call (`subscriptions.list`, `channels.list`, `playlistItems.list`, `playlists.list`, `videos.list` = 1, `search.list` = 100) into a ledger keyed by the date in `America/Los_Angeles`, persisted atomically to `/data/youtube-quota.json` on every charge; `home-control.youtube.daily-quota-units` (default 10 000) is the budget; a call that would exceed it is refused locally with `QUOTA_EXHAUSTED`; a Google `quotaExceeded` reply sets the day's usage to the budget — Google does not expose remaining quota, so local accounting is the only way to show usage and stop early; the budget is configurable for projects with a raised or shared quota — cost if wrong: the displayed number drifts from Google's console when the same project is used elsewhere; Google's own `quotaExceeded` still stops us.
- Decision: a corrupt `youtube-quota.json` is renamed to `youtube-quota.json.corrupt-<epochSeconds>` with a WARN log and counting restarts at zero — a usage counter must not stop the app from starting (unlike the device registry) — cost if wrong: at most one day over budget, which Google's `quotaExceeded` then stops.
- Decision: the subscriptions rail is built from an in-memory feed: the subscription list and the channel → uploads-playlist map refresh every `subscriptions-refresh` (24 h); each rail refresh polls `playlistItems.list` for at most `channels-per-refresh` (30) channels, least recently polled first, `videos-per-channel` (5) newest items each; the rail merges every cached channel's videos, sorted by the video's publish time — with 200 subscriptions an hourly refresh costs 30 units (720/day) instead of 200 (4 800/day), and every channel is still seen at least every ~7 hours — cost if wrong: a new upload from a rarely polled channel appears a few hours late; raise `channels-per-refresh`.
- Decision: the uploads playlist id always comes from `channels.list` `contentDetails.relatedPlaylists.uploads` (batched 50 ids per call), never derived by rewriting `UC…` to `UU…` — the rewrite is an undocumented convention — cost if wrong: one unit per 50 channels per day.
- Decision: `YouTubeContentSource.defaultRefreshInterval()` is `refresh-interval` (60 min) and every rail additionally keeps a 15-minute minimum spacing between upstream refreshes (`min-refresh-spacing`), returning the cached result in between — D4 lets a user set a 1-minute interval, which would burn the quota in hours — cost if wrong: a manual Retry within 15 minutes of a success shows the same items.
- Decision: when the quota runs out mid-refresh, the subscriptions feed returns what it has (the rail stays READY with slightly stale channels); when the ledger refuses the very first call of a refresh, the rail fails with `YouTube's daily API quota is used up (<used> of <budget> units). Rails refresh again after midnight Pacific time (<HH:mm> here).` — D's `RailCache` keeps the last items and shows "Couldn't refresh" with that message — cost if wrong: none found.
- Decision: rail ids are `subscriptions`, `watch-later` and `pl-<first 16 hex chars of SHA-256(playlistId)>` — D4's rail keys allow only lower-case `[a-z0-9._-]`, and playlist ids are mixed-case and 34 characters — cost if wrong: a 64-bit hash collision between two of one user's playlists (negligible).
- Decision: Watch Later is an opt-in rail (setup checkbox, default off); `playlistItems.list?playlistId=WL` returning no items or `playlistNotFound` fails the rail with `YouTube does not share Watch Later with other apps for most accounts (an API change in 2016). Save videos to one of your own playlists and show that playlist here instead.`; if Google does return items they are shown — an account with a truly empty Watch Later is indistinguishable from the restriction, and saying so is more honest than an empty rail — cost if wrong: a user with an empty but readable Watch Later sees the explanation instead of "Nothing here right now".
- Decision: playlists are chosen on the setup page after an explicit "Load my playlists" (`playlists.list mine=true`, 1 unit per page, at most 10 pages); selections are stored in `sources.json` as `playlist.<playlistId>` = title; rails show the playlist in its own order (position), `rail-size` items — cost if wrong: a renamed playlist keeps its old title until reloaded and saved.
- Decision: search is `search.list part=snippet type=video maxResults=min(limit,25)`, capped by `searches-per-day` (20 → 2 000 units) in addition to the unit budget, with results cached in memory for `search-cache-ttl` (6 h, 50 queries, key = lower-cased whitespace-collapsed query) — the cap keeps rails alive, the cache makes repeated searches free — cost if wrong: tune two properties.
- Decision: D5's as-you-type search would call `search.list` on every debounced keystroke (≈ 100 units each), so core gains `ContentSource.searchOnDemand()` (default `false`) and `searchNote()`; `SearchService.search` skips on-demand sources, the results fragment shows a "Search YouTube" button with "17 of 20 YouTube searches left today", which calls the new `GET /search/results/{sourceId}?q=` (`SearchService.searchSource`); the JSON `/search` gains an optional `source` parameter — a metered search must be an explicit act; this is the "quota-aware, debounced" of spec §6.1 made concrete — cost if wrong: one extra tap for YouTube results.
- Decision: YouTube items are `ContentItem(id=videoId, sourceId="youtube", kind=VIDEO, title, subtitle=channel title, artwork=/sources/youtube/thumbnails/<videoId>, playables=[AppLink(https://www.youtube.com/watch?v=<videoId>, "youtube")], progress=null)` — the watch URL is what A's app-link route, F's webOS `contentTarget` translation and F's Tizen DIAL translation all understand; no YouTube-specific reference is needed for the primary route — cost if wrong: none found.
- Decision: artwork is proxied through `GET /sources/youtube/thumbnails/{videoId}`, which fetches `<thumbnail-base-url>/vi/<videoId>/mqdefault.jpg` (320×180, exists for every public video) without credentials and answers with `Cache-Control: private, max-age=86400` — same reasons as C's Jellyfin image proxy (login-gated, no third-party request from the phone, no dependency on thumbnail URLs being stored) — cost if wrong: server bandwidth for thumbnails.
- Decision: `ContentSource.item(videoId)` answers from an in-memory map of the last 1 000 videos seen in any rail or search, and only otherwise calls `videos.list` (1 unit) — playing a tile must not cost quota — cost if wrong: after a restart the first play of an old tile costs one unit.
- Decision: **Lounge is implemented now, as a documented best-effort minimum with fakes**, not deferred: per device switch (default off, only offered for devices with `CAST_RECEIVER`), one play = Cast `getMdxSessionStatus` on the YouTube receiver `233637DE` → `get_lounge_token_batch` → `bc/bind` → `setPlaylist`; no session reuse, no now-playing, no queue, no retries — the roadmap requires the toggle and the route, a minimal version is ~300 lines behind fakes, and an explicit failure message is better than a missing route on a Chromecast that has no other way to play YouTube — cost if wrong: when YouTube changes the protocol the route fails with a clear message and the switch can be turned off; the app-link route is unaffected.
- Decision: the screen id comes from the Cast receiver (MDX namespace), not from TV-code pairing (`pairing/get_screen` with the code from the TV's "Link with TV code") — the devices that show a TV code (Android TV, Google TV, webOS, Tizen) already play YouTube through the app-link route, and Cast-only devices (Chromecast, Cast speakers with screens) cannot show a code — cost if wrong: adding TV-code pairing later is one more endpoint and a stored screen id per device.
- Decision: the Cast request/response needed for the screen id is a new core type `CastAppQuery(receiverAppId, namespace, message, replyType)` with `DeviceHandle.query` (default: unsupported) and `DeviceManager.query(deviceId, query)` (adapters with `CAST_RECEIVER`, same fall-through as `execute`) — `Action`s return nothing, and making an action carry a reply future would complicate every exhaustive `switch` over `Action` — cost if wrong: a later generic request/response mechanism replaces one method.
- Decision: the Lounge route is added by `YouTubeLoungeResolver`, a `PlayableResolver` for `AppLink`s with service `youtube` whose URL has a video id: it returns the app link unchanged plus `PlayableRef.YouTubeLounge(videoId)` when the device's switch is on and the device has `CAST_RECEIVER`, else just the app link — so pasted YouTube links on the open-link form get the route too, and devices without the switch see no change — cost if wrong: a future second resolver for `AppLink`s must compose with this one (`PlaybackService` uses the first resolver that `resolves`).
- Decision: planner order becomes `JellyfinSessionStrategy`, `AppLinkStrategy`, `YouTubeLoungeStrategy`, `CastMessageStrategy`, `CastLoadStrategy`, `CastStreamStrategy` — the app link stays first (spec §5.3, and the roadmap calls it primary); on a merged Shield (APP_LINK + CAST_RECEIVER) Lounge becomes D3's "Try Cast with the YouTube receiver (best effort)" after a failed app link — cost if wrong: one list order.
- Decision: route key `youtube-lounge`, not optimistic in D3's sense (the D3 "app may not be installed" hint is app-link specific) — cost if wrong: none.
- Decision: the Lounge remote identifies itself as name `Home Control` with a random UUID `lounge.remoteId` generated once and stored in `sources.json` — a stable id avoids a new "device connected" banner identity on every play — cost if wrong: none.
- Decision: on any Lounge failure the route throws `ActionFailedException("<device>: YouTube Cast (best effort, unofficial API) failed: <step reason>")`, which D3 shows with the next route; YouTube HTTP failures name only the step (`lounge token`, `bind`, `setPlaylist`) and the HTTP status — cost if wrong: none.
- Decision: the YouTube video id extraction in `sources/youtube/YouTubeVideoIds` mirrors F's `adapters/links/ContentLinks.youtubeVideoId` rules instead of importing it — sources must not import adapters — cost if wrong: two copies of ~20 lines; both are pinned by the same test URLs.

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`. Paths are relative to those roots unless they start with `src/`, `docs/` or are top-level files.

### Files to create

- `sources/youtube/YouTubeProperties.java` — `home-control.youtube.*`.
- `sources/youtube/YouTubeConfiguration.java` — module beans.
- `sources/youtube/YouTubeException.java` — kinds of failure, user-facing messages.
- `sources/youtube/YouTubeHttp.java` — no-redirect HTTP client, form and query encoding.
- `sources/youtube/YouTubeSettings.java` — the `youtube` map in `sources.json`.
- `sources/youtube/GoogleOAuthClient.java` — device code, token poll, refresh, revoke.
- `sources/youtube/GoogleTokens.java` — in-memory access token.
- `sources/youtube/YouTubeAuthorizationService.java` — pending device authorization and background polling.
- `sources/youtube/YouTubeSetupService.java`, `YouTubeSetupController.java`, `YouTubeSetupAdvice.java`.
- `src/main/resources/templates/fragments/youtube-setup.html` — setup section and authorization fragment.
- `sources/youtube/QuotaLedger.java` — units per Pacific day, persisted.
- `sources/youtube/YouTubeApiClient.java` — Data API GETs.
- `sources/youtube/YouTubeVideo.java`, `YouTubeVideoMapper.java`, `KnownVideos.java` — mapping and the item lookup cache.
- `sources/youtube/YouTubeAccount.java` — the connected channel's title.
- `sources/youtube/SubscriptionsFeed.java` — subscriptions → uploads → newest videos.
- `sources/youtube/YouTubeContentSource.java` — the `ContentSource`.
- `sources/youtube/YouTubeThumbnailController.java` — artwork proxy.
- `sources/youtube/YouTubePlaylists.java` — Watch Later, playlists.
- `sources/youtube/YouTubeSearch.java` — capped, cached search.
- `sources/youtube/YouTubeVideoIds.java` — video id from a URL.
- `sources/youtube/LoungeClient.java`, `LoungeException.java` — lounge token, bind, setPlaylist.
- `sources/youtube/YouTubeLoungeResolver.java`, `YouTubeLoungeRouteExecutor.java`.
- `core/CastAppQuery.java`; `core/playback/YouTubeLoungeStrategy.java`.
- Tests: `sources/youtube/FakeGoogleServer.java`, `MutableClock.java`, `YouTubeHttpTest.java`, `YouTubeSettingsTest.java`, `GoogleOAuthClientTest.java`, `GoogleTokensTest.java`, `YouTubeAuthorizationServiceTest.java`, `YouTubeSetupServiceTest.java`, `YouTubeSetupControllerTest.java`, `YouTubeModuleSwitchTest.java`, `QuotaLedgerTest.java`, `YouTubeApiClientTest.java`, `YouTubeVideoMapperTest.java`, `SubscriptionsFeedTest.java`, `YouTubeContentSourceTest.java`, `YouTubeThumbnailControllerTest.java`, `YouTubePlaylistsTest.java`, `YouTubeSearchTest.java`, `YouTubeVideoIdsTest.java`, `LoungeClientTest.java`, `YouTubeLoungeResolverTest.java`, `YouTubeLoungeRouteExecutorTest.java`, `YouTubeFixtureContractTest.java`; `core/playback/YouTubeRoutesTest.java`; `device/DeviceManagerQueryTest.java`; `web/YouTubeEndToEndTest.java`.
- Fixtures `src/test/resources/fixtures/youtube/`: `oauth-device-code.json`, `oauth-token-pending.json`, `oauth-token-slow-down.json`, `oauth-token-denied.json`, `oauth-token-expired.json`, `oauth-token-granted.json`, `oauth-invalid-client-type.json`, `oauth-refresh-granted.json`, `oauth-refresh-invalid-grant.json`, `channels-mine.json`, `subscriptions-page-1.json`, `subscriptions-page-2.json`, `channels-uploads.json`, `playlist-items-uploads-kurzgesagt.json`, `playlist-items-uploads-blender.json`, `playlist-items-uploads-nasa.json`, `playlist-items-empty.json`, `playlist-items-playlist.json`, `playlists-mine.json`, `search-videos.json`, `videos-by-id.json`, `error-quota-exceeded.json`, `error-api-not-enabled.json`, `error-playlist-not-found.json`, `error-unauthorized.json`, `lounge-token-batch.json`, `lounge-bind.txt`, `cast-mdx-session-status.json`.
- `docs/superpowers/reviews/2026-09-16-youtube-source-acceptance.md` — manual checklist, every item pending.

### Files to modify

- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.youtube.*` (Task 1).
- `src/main/resources/templates/setup.html` — YouTube fragment include (Task 1).
- `core/content/ContentSource.java` — `searchOnDemand()`, `searchNote()` (Task 4).
- `content/SearchService.java`, `web/SearchController.java`, `web/ContentController.java`, `src/main/resources/templates/fragments/search.html`, `src/main/resources/static/app.css` — on-demand search (Task 4).
- `core/DeviceHandle.java`, `device/DeviceManager.java`, `adapters/cast/CastSession.java`, `adapters/cast/protocol/CastPayloads.java` — `CastAppQuery` (Task 5).
- `core/playback/PlayableRef.java`, `core/playback/Route.java`, `core/playback/PlaybackPlanner.java`, `core/playback/RouteKeys.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java` — the Lounge route (Task 5).
- `README.md` — YouTube section (Task 6).
- Tests of the above: `content/SearchServiceTest.java`, `web/SearchControllerTest.java`, `web/ContentControllerTest.java`, `adapters/cast/CastSessionTest.java`, `core/playback/PlaybackPlannerTest.java`, `core/playback/RouteKeysTest.java`, `playback/PlaybackServiceTest.java`.

### Files to delete

- None.

---
### Task 1: E1 · Google OAuth device flow

**Files:**
- Create: `sources/youtube/YouTubeProperties.java`, `YouTubeException.java`, `YouTubeHttp.java`, `YouTubeSettings.java`, `GoogleOAuthClient.java`, `GoogleTokens.java`, `YouTubeAuthorizationService.java`, `YouTubeSetupService.java`, `YouTubeSetupController.java`, `YouTubeSetupAdvice.java`, `YouTubeConfiguration.java`, `src/main/resources/templates/fragments/youtube-setup.html`
- Modify: `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `sources/youtube/FakeGoogleServer.java`, `MutableClock.java`, `YouTubeHttpTest.java`, `YouTubeSettingsTest.java`, `GoogleOAuthClientTest.java`, `GoogleTokensTest.java`, `YouTubeAuthorizationServiceTest.java`, `YouTubeSetupServiceTest.java`, `YouTubeSetupControllerTest.java`, `YouTubeModuleSwitchTest.java`; fixtures `src/test/resources/fixtures/youtube/oauth-device-code.json`, `oauth-token-pending.json`, `oauth-token-slow-down.json`, `oauth-token-denied.json`, `oauth-token-expired.json`, `oauth-token-granted.json`, `oauth-invalid-client-type.json`, `oauth-refresh-granted.json`, `oauth-refresh-invalid-grant.json`

**Interfaces:**
- Consumes (C): `SecretStore.secret(String) → Optional<String>`, `SecretStore.putSecrets(Map<String,String>)`, `LoginService.loginRequired()`, `LoginService.storeSecrets(Map<String,String>, String newPassword, String confirmation, HttpServletRequest)`, `LoginService.removeSecrets(Collection<String>)`, `PasswordRejectedException`, `LoginRequiredException`, `JsonFileSourceSettings.get/put` (C2, D4 keeps `preferences`), `ContentSourceException(String)`/`(String, Throwable)` (C3), `SetupController` (A), `LoginGateFilter.ALWAYS_GUARDED` covers `/setup/sources/**` (C1). The pattern of `JellyfinSetupAdvice`, `JellyfinSetupController`, `JellyfinModuleSwitchTest` and `FakeJellyfinServer` (C2).
- Produces:
  - `record YouTubeProperties(boolean enabled, URI oauthBaseUrl, URI apiBaseUrl, URI loungeBaseUrl, URI thumbnailBaseUrl, int connectTimeoutSeconds, int requestTimeoutSeconds, int dailyQuotaUnits, int searchesPerDay, int railSize, int channelsPerRefresh, int videosPerChannel, Duration subscriptionsRefresh, int maxSubscriptionPages, Duration refreshInterval, Duration minRefreshSpacing, Duration searchCacheTtl)` bound to `home-control.youtube` (all components used by later tasks are declared now).
  - `class YouTubeException extends ContentSourceException { enum Kind { INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, UNAUTHORIZED, REVOKED, FORBIDDEN, NOT_FOUND, QUOTA_EXHAUSTED, SEARCH_LIMIT, SERVER_ERROR, BAD_RESPONSE } YouTubeException(Kind, String); YouTubeException(Kind, String, String reason); Kind kind(); String reason(); }`.
  - `class YouTubeHttp { YouTubeHttp(YouTubeProperties); Response get(URI, Map<String,String> headers); Response postForm(URI, Map<String,String> form, Map<String,String> headers); static URI uri(URI base, String path, Map<String,String> query); static String form(Map<String,String>); record Response(int status, String contentType, byte[] body) { JsonNode json(); String text(); boolean ok(); } }`.
  - `record YouTubeSettings(Instant connectedAt, String channelId, String channelTitle, boolean watchLater, Map<String,String> playlists, Set<String> loungeDevices, String loungeRemoteId)` with `SOURCE_ID = "youtube"`, `CLIENT_ID = "youtube.client-id"`, `CLIENT_SECRET = "youtube.client-secret"`, `REFRESH_TOKEN = "youtube.refresh-token"`, `static YouTubeSettings from(Map<String,String>)`, `Map<String,String> toMap()`, withers `withConnection(Instant, String channelId, String channelTitle)`, `withoutAccount()`, `withWatchLater(boolean)`, `withPlaylists(Map<String,String>)`, `withLoungeDevice(String deviceId, boolean enabled)`, `withLoungeRemoteId(String)`.
  - `class GoogleOAuthClient { static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly"; static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"; GoogleOAuthClient(YouTubeHttp, URI oauthBaseUrl, Clock); DeviceCode requestDeviceCode(String clientId); TokenPoll poll(String clientId, String clientSecret, String deviceCode); AccessToken refresh(String clientId, String clientSecret, String refreshToken); void revoke(String token); }` with `record DeviceCode(String deviceCode, String userCode, URI verificationUrl, Instant expiresAt, Duration interval)`, `record AccessToken(String value, Instant expiresAt)`, `sealed interface TokenPoll { record Granted(AccessToken accessToken, String refreshToken); record Pending(); record SlowDown(); record Denied(); record Expired(); record Failed(String error, String description); }` — every record holding a token or device code has a redacted `toString()`.
  - `class GoogleTokens { GoogleTokens(GoogleOAuthClient, SecretStore, Clock); synchronized String accessToken(); synchronized void prime(AccessToken); synchronized void invalidate(); synchronized void reset(); synchronized boolean revoked(); boolean hasClient(); boolean hasRefreshToken(); }`.
  - `class YouTubeAuthorizationService implements AutoCloseable { YouTubeAuthorizationService(GoogleOAuthClient, SecretStore, GoogleTokens, JsonFileSourceSettings, Clock, boolean backgroundPolling); Status start(); Status status(); void cancel(); boolean pollOnce(); void onConnected(Runnable listener); void close(); enum State { IDLE, PENDING, CONNECTED, DENIED, EXPIRED, FAILED } record Status(State state, String userCode, URI verificationUrl, Instant expiresAt, String message) }`.
  - `class YouTubeSetupService { YouTubeSetupService(SecretStore, LoginService, JsonFileSourceSettings, GoogleOAuthClient, GoogleTokens, YouTubeAuthorizationService); YouTubeSettings settings(); void save(YouTubeSettings); boolean hasClient(); boolean connected(); YouTubeAuthorizationService.Status connect(ConnectRequest, HttpServletRequest); YouTubeAuthorizationService.Status authorize(); void cancel(); String check(); void disconnect(); record ConnectRequest(String clientId, String clientSecret, String loginPassword, String loginPasswordConfirmation) }` (redacted `toString`).
  - Endpoints (all `302 /setup#youtube`, flash `youtubeMessage` / `youtubeError` / `youtubeForm` (`clientId` only)): `POST /setup/sources/youtube/connect`, `POST /setup/sources/youtube/authorize`, `POST /setup/sources/youtube/cancel`, `POST /setup/sources/youtube/test`, `POST /setup/sources/youtube/disconnect`; `GET /setup/sources/youtube/authorization` → fragment `fragments/youtube-setup :: authorization` (header `HX-Refresh: true` when the state is `CONNECTED`).
  - Model attribute `youtube` (`YouTubeSetupAdvice.View`) on the setup page when the module is enabled.
  - Property `home-control.youtube.enabled` (default `true`).

**OAuth wire format (normative).** Every request is `POST`, `Content-Type: application/x-www-form-urlencoded`, `Accept: application/json`, form values `URLEncoder.encode(value, UTF_8)` joined in the order listed.
- Device code: `<oauth>/device/code`, body `client_id=<id>&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.readonly` → 200 `{"device_code","user_code","expires_in","interval","verification_url"}`. `interval` missing → 5 s. `verification_url` missing → `https://www.google.com/device`.
- Poll: `<oauth>/token`, body `client_id=<id>&client_secret=<secret>&device_code=<code>&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code` → 200 `{"access_token","expires_in","refresh_token","scope","token_type"}` → `Granted` (a 200 without `refresh_token` → `Failed("no_refresh_token", "Google did not return a refresh token")`). Any non-2xx with a JSON `error` → by `error`: `authorization_pending` → `Pending`, `slow_down` → `SlowDown`, `access_denied` → `Denied`, `expired_token` → `Expired`, `invalid_client` → throw `UNAUTHORIZED` (message below), anything else → `Failed(error, error_description)`. Non-JSON non-2xx: 5xx → `SERVER_ERROR`, else `BAD_RESPONSE`.
- Refresh: `<oauth>/token`, body `client_id=<id>&client_secret=<secret>&refresh_token=<token>&grant_type=refresh_token` → 200 `{"access_token","expires_in","scope","token_type"}`; `invalid_grant` → `REVOKED`; `invalid_client` → `UNAUTHORIZED`.
- Revoke: `<oauth>/revoke`, body `token=<token>` → 200 (anything else is ignored by the caller).
- `invalid_client` message: if `error_description` contains `client type` (case-insensitive) → `Google says this OAuth client cannot use the device flow. Create a client of type “TVs and Limited Input devices”.`; else `Google rejected the client ID or client secret.`
- `REVOKED` message: `Google no longer accepts the saved YouTube authorization. It was revoked, or it expired after 7 days because the OAuth consent screen is still in “Testing”. Reconnect YouTube on the setup page.`
- `AccessToken.expiresAt` = `clock.instant() + expires_in seconds` (missing → 3600).

**`sources.json` keys (normative)**, all strings in the `youtube` map: `connectedAt` (ISO-8601 instant), `channelId`, `channelTitle`, `watchLater` (`true` only when on), `playlist.<playlistId>` = title (one key per selected playlist), `lounge.devices` (device ids joined with `,`, sorted), `lounge.remoteId`. Blank or missing values are omitted from `toMap()`; unknown keys are ignored.

- [ ] **Step 1: Configuration**

Append to `src/main/resources/application.yaml` under the existing `home-control:` block:

```yaml
  youtube:
    enabled: ${HOME_CONTROL_YOUTUBE_ENABLED:true}
    oauth-base-url: https://oauth2.googleapis.com
    api-base-url: https://www.googleapis.com/youtube/v3
    lounge-base-url: https://www.youtube.com/api/lounge
    thumbnail-base-url: https://i.ytimg.com
    connect-timeout-seconds: 5
    request-timeout-seconds: 15
    # Your Cloud project's daily YouTube Data API quota (10000 unless Google raised it).
    daily-quota-units: 10000
    # Each search costs 100 units.
    searches-per-day: 20
    rail-size: 30
    channels-per-refresh: 30
    videos-per-channel: 5
    subscriptions-refresh: 24h
    max-subscription-pages: 20
    refresh-interval: 60m
    min-refresh-spacing: 15m
    search-cache-ttl: 6h
```

Append the same block to `src/test/resources/application.yaml` with literal values (no placeholders), `enabled: true`, and every base URL set to `http://127.0.0.1:9` (a closed port, so a test that forgets the fake fails fast instead of reaching Google).

`sources/youtube/YouTubeProperties.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("home-control.youtube")
public record YouTubeProperties(@DefaultValue("true") boolean enabled,
                                @DefaultValue("https://oauth2.googleapis.com") URI oauthBaseUrl,
                                @DefaultValue("https://www.googleapis.com/youtube/v3") URI apiBaseUrl,
                                @DefaultValue("https://www.youtube.com/api/lounge") URI loungeBaseUrl,
                                @DefaultValue("https://i.ytimg.com") URI thumbnailBaseUrl,
                                @DefaultValue("5") int connectTimeoutSeconds,
                                @DefaultValue("15") int requestTimeoutSeconds,
                                @DefaultValue("10000") int dailyQuotaUnits,
                                @DefaultValue("20") int searchesPerDay,
                                @DefaultValue("30") int railSize,
                                @DefaultValue("30") int channelsPerRefresh,
                                @DefaultValue("5") int videosPerChannel,
                                @DefaultValue("24h") Duration subscriptionsRefresh,
                                @DefaultValue("20") int maxSubscriptionPages,
                                @DefaultValue("60m") Duration refreshInterval,
                                @DefaultValue("15m") Duration minRefreshSpacing,
                                @DefaultValue("6h") Duration searchCacheTtl) {
}
```

(The application uses `@ConfigurationPropertiesScan`, so no registration is needed. Tests build instances with a helper `static YouTubeProperties testProperties(URI fakeBase)` in `FakeGoogleServer` that sets `oauthBaseUrl = <base>/oauth`, `apiBaseUrl = <base>/youtube/v3`, `loungeBaseUrl = <base>/lounge`, `thumbnailBaseUrl = <base>/thumbs`, timeouts 2/5, quota 10000, searches 20, rail size 30, channels 30, videos 5, `24h`, 20 pages, `60m`, `15m`, `6h`.)

- [ ] **Step 2: Write the fixtures**

`src/test/resources/fixtures/youtube/oauth-device-code.json`:

```json
{
  "device_code": "AH-1Ng2mZpLr7eXq3VfTbUdWk9sJcYhRn4oPaGiEl8uBx0MwD",
  "user_code": "GQVQ-JKEC",
  "expires_in": 1800,
  "interval": 5,
  "verification_url": "https://www.google.com/device"
}
```

`oauth-token-pending.json` (served with HTTP 428): `{"error": "authorization_pending", "error_description": "Precondition Required"}`

`oauth-token-slow-down.json` (403): `{"error": "slow_down", "error_description": "Forbidden"}`

`oauth-token-denied.json` (403): `{"error": "access_denied", "error_description": "Forbidden"}`

`oauth-token-expired.json` (400): `{"error": "expired_token", "error_description": "Bad Request"}`

`oauth-invalid-client-type.json` (401): `{"error": "invalid_client", "error_description": "Invalid client type."}`

`oauth-token-granted.json` (200):

```json
{
  "access_token": "ya29.a0AfB_byFixtureAccessTokenGranted0001",
  "expires_in": 3599,
  "refresh_token": "1//0gFixtureRefreshTokenGranted-0001",
  "scope": "https://www.googleapis.com/auth/youtube.readonly",
  "token_type": "Bearer"
}
```

`oauth-refresh-granted.json` (200):

```json
{
  "access_token": "ya29.a0AfB_byFixtureAccessTokenRefreshed02",
  "expires_in": 3599,
  "scope": "https://www.googleapis.com/auth/youtube.readonly",
  "token_type": "Bearer"
}
```

`oauth-refresh-invalid-grant.json` (400): `{"error": "invalid_grant", "error_description": "Token has been expired or revoked."}`

(Write each one-line fixture pretty-printed like the others.)

- [ ] **Step 3: Write the fake Google server**

`src/test/java/dev/andre/homecontrol/sources/youtube/FakeGoogleServer.java` — one JDK `HttpServer` on `127.0.0.1:0` with virtual-thread executor, serving four prefixes. It records every request and answers from scripted responses. Later tasks add routes by calling `respond` (no new class is needed):

```java
package dev.andre.homecontrol.sources.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** Google OAuth, YouTube Data API v3, Lounge and thumbnails in one in-process fake. */
public final class FakeGoogleServer implements AutoCloseable {

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> form,
                           Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    public record Canned(int status, String contentType, byte[] body) {
        public static Canned json(int status, String json) {
            return new Canned(status, "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        }

        public static Canned fixture(int status, String name) {
            return json(status, FakeGoogleServer.fixture(name));
        }
    }

    private record Rule(String method, String path, Predicate<Recorded> when, Deque<Canned> answers) {
    }

    private final HttpServer server;
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeGoogleServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public YouTubeProperties properties() {
        URI base = base();
        return new YouTubeProperties(true, URI.create(base + "/oauth"), URI.create(base + "/youtube/v3"),
                URI.create(base + "/lounge"), URI.create(base + "/thumbs"), 2, 5, 10000, 20, 30, 30, 5,
                Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6));
    }

    public static String fixture(String name) {
        try (InputStream in = FakeGoogleServer.class.getResourceAsStream("/fixtures/youtube/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Answers {@code method path} (path without query) with the given responses in order; the last one
     * repeats. Later rules win over earlier ones, so a test can override a default.
     */
    public FakeGoogleServer respond(String method, String path, Canned... answers) {
        return respondWhen(method, path, request -> true, answers);
    }

    public FakeGoogleServer respondWhen(String method, String path, Predicate<Recorded> when, Canned... answers) {
        rules.addFirst(new Rule(method, path, when, new ArrayDeque<>(List.of(answers))));
        return this;
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public List<Recorded> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public int count(String path) {
        return requests(path).size();
    }

    /** OAuth defaults: device code, then pending once, then granted; refresh granted; revoke 200. */
    public FakeGoogleServer oauthApproves() {
        respond("POST", "/oauth/device/code", Canned.fixture(200, "oauth-device-code.json"));
        respond("POST", "/oauth/token", Canned.fixture(428, "oauth-token-pending.json"),
                Canned.fixture(200, "oauth-token-granted.json"));
        respondWhen("POST", "/oauth/token", r -> "refresh_token".equals(r.form().get("grant_type")),
                Canned.fixture(200, "oauth-refresh-granted.json"));
        respond("POST", "/oauth/revoke", Canned.json(200, "{}"));
        return this;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String contentType = headers.getOrDefault("content-type", "");
        Recorded recorded = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                decode(exchange.getRequestURI().getRawQuery()),
                contentType.startsWith("application/x-www-form-urlencoded") ? decode(body) : Map.of(),
                headers, body);
        requests.add(recorded);
        Canned answer = rules.stream()
                .filter(rule -> rule.method().equals(recorded.method()) && rule.path().equals(recorded.path())
                        && rule.when().test(recorded))
                .findFirst()
                .map(rule -> {
                    synchronized (rule.answers()) {
                        return rule.answers().size() > 1 ? rule.answers().pollFirst() : rule.answers().peekFirst();
                    }
                })
                .orElse(Canned.json(404, "{\"error\":{\"code\":404,\"message\":\"no fake route\",\"errors\":[]}}"));
        exchange.getResponseHeaders().add("Content-Type", answer.contentType());
        exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
        if (answer.body().length > 0) {
            exchange.getResponseBody().write(answer.body());
        }
        exchange.close();
    }

    static Map<String, String> decode(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.merge(key, value, (a, b) -> a + "," + b);
        }
        return values;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 4: Write the failing tests**

`YouTubeHttpTest` (against `FakeGoogleServer`):
- `encodesFormsInOrder`: `YouTubeHttp.form(LinkedHashMap{"a b"→"x&y", "scope"→"https://www.googleapis.com/auth/youtube.readonly"})` = `a+b=x%26y&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.readonly`.
- `buildsQueryUrisSkippingNullValues`: `uri(URI("http://h/youtube/v3"), "/subscriptions", {part→snippet, pageToken→null, mine→true})` = `http://h/youtube/v3/subscriptions?part=snippet&mine=true`.
- `postsAFormWithHeaders`: `postForm(<base>/oauth/device/code, {client_id→c}, {X-Test→1})` → the recorded request has `content-type` `application/x-www-form-urlencoded`, `accept` `application/json`, form `client_id=c`, header `x-test` `1`; the response's `json()` is the fixture.
- `neverFollowsRedirects`: fake answers 302 with `Location: http://example.org/` → `Response.status()` = 302 and only one request recorded.
- `anUnreachableHostIsUnreachableWithoutTheQuery`: `get(URI("http://127.0.0.1:9/x?access_token=secret"), Map.of())` → `YouTubeException` kind `UNREACHABLE`, message `Could not reach 127.0.0.1` and not containing `secret`.
- `unparsableJsonIsABadResponse`: body `not json` → `response.json()` throws `YouTubeException` kind `BAD_RESPONSE`.

`YouTubeSettingsTest`:
- `roundTripsEveryKey`: a settings value with all components → `toMap()` has exactly the keys listed in the normative section (two playlists → two `playlist.` keys; lounge devices `b,a` stored as `a,b`) → `from(map)` equals the original.
- `emptyMapIsNotConnected`: `from(Map.of())` → `connectedAt` null, `watchLater` false, empty playlists and devices, null remote id.
- `withoutAccountKeepsLounge`: `withoutAccount()` clears connection, channel, watch later and playlists and keeps `loungeDevices` and `loungeRemoteId`.
- `playlistsAreOrderedByTitle`: `from` of `playlist.PLb`=`Zebra`, `playlist.PLa`=`apple` → `playlists().keySet()` = `[PLa, PLb]` (case-insensitive title order).

`GoogleOAuthClientTest` (fake + `Clock.fixed(2026-09-16T10:00:00Z)`):
- `requestsADeviceCode`: fixture → `DeviceCode` with `userCode` `GQVQ-JKEC`, `verificationUrl` `https://www.google.com/device`, `expiresAt` 10:30:00Z, `interval` 5 s; recorded form = `{client_id: "123-abc.apps.googleusercontent.com", scope: "https://www.googleapis.com/auth/youtube.readonly"}` in that order (assert the raw body string).
- `deviceCodeToStringHidesTheCode`: `toString()` does not contain `AH-1Ng2m`.
- `pollMapsEveryGoogleAnswer` (parameterized): 428 pending → `Pending`; 403 slow_down → `SlowDown`; 403 access_denied → `Denied`; 400 expired_token → `Expired`; 400 `{"error":"invalid_grant","error_description":"Malformed auth code."}` → `Failed("invalid_grant","Malformed auth code.")`; 200 granted → `Granted` whose access token value is `ya29.a0AfB_byFixtureAccessTokenGranted0001`, expires 10:59:59Z, refresh token `1//0gFixtureRefreshTokenGranted-0001`.
- `pollSendsTheDeviceGrant`: raw body = `client_id=cid&client_secret=csecret&device_code=AH-1&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code`.
- `aWrongClientTypeIsExplained`: 401 `oauth-invalid-client-type.json` on `/oauth/device/code` → `YouTubeException` `UNAUTHORIZED` with message containing `TVs and Limited Input devices`.
- `refreshUsesTheRefreshGrant`: raw body `client_id=cid&client_secret=csecret&refresh_token=1%2F%2F0gX&grant_type=refresh_token`; result value `ya29.a0AfB_byFixtureAccessTokenRefreshed02`.
- `aRevokedRefreshTokenIsRevoked`: 400 invalid_grant → `YouTubeException` `REVOKED` with message containing `Testing` and `Reconnect YouTube`.
- `aGrantWithoutRefreshTokenFails`: 200 `oauth-refresh-granted.json` on the device poll → `Failed("no_refresh_token", …)`.
- `grantedToStringIsRedacted`: `Granted.toString()` and `AccessToken.toString()` contain neither token.

`GoogleTokensTest` (mock `GoogleOAuthClient`, mock `SecretStore` returning `cid`, `csecret`, `rt`; mutable clock `MutableClock` — a small test `Clock` subclass in the same package with `advance(Duration)`):
- `refreshesOnceAndCaches`: two `accessToken()` calls → one `refresh("cid","csecret","rt")`.
- `refreshesAMinuteBeforeExpiry`: token expiring at now+10 min; advance 9 min 1 s → second refresh.
- `invalidateForcesARefresh`.
- `withoutClientOrRefreshTokenItIsNotConfigured`: `secret(REFRESH_TOKEN)` empty → `YouTubeException` `NOT_CONFIGURED` message `YouTube is not connected`.
- `aRevokedGrantIsRememberedUntilReset`: refresh throws `REVOKED` → `accessToken()` throws `REVOKED`; the second call throws `REVOKED` without calling `refresh` again; `revoked()` true; after `reset()` it refreshes again.
- `primeAvoidsARefresh`: `prime(new AccessToken("ya29.x", now+1h))` → `accessToken()` = `ya29.x`, no refresh.

`YouTubeAuthorizationServiceTest` (fake server, `MutableClock` at 10:00Z, real `GoogleOAuthClient`, mock `SecretStore` with client secrets, mock `GoogleTokens`, real `JsonFileSourceSettings` in a temp dir, `backgroundPolling=false`):
- `startShowsTheCodeButNeverTheDeviceCode`: `start()` → `PENDING`, user code `GQVQ-JKEC`, url `https://www.google.com/device`, expires 10:30Z; `status().toString()` does not contain `AH-1Ng2m`.
- `pollsOnlyWhenDue`: after `start()`, `pollOnce()` at 10:00:00 sends nothing (0 token requests); advance 5 s → one request.
- `pendingWaitsTheInterval`: fake answers pending then granted; advance 5 s → poll (pending); advance 4 s → no request; advance 1 s → poll (granted).
- `slowDownAddsFiveSeconds`: slow_down then granted; after the slow_down, advance 9 s → no request; advance 1 s (10 s total) → request.
- `aGrantStoresTheRefreshTokenAndPrimesTheAccessToken`: granted → `verify(secrets).putSecrets(Map.of("youtube.refresh-token","1//0gFixtureRefreshTokenGranted-0001"))`, `verify(tokens).reset()` then `verify(tokens).prime(…)`, state `CONNECTED` message `YouTube connected`, settings `connectedAt` = the clock instant, the `onConnected` listener ran once, `pollOnce()` returns false afterwards.
- `denialExpiryAndFailureEndThePending` (parameterized): denied → `DENIED` `Access was denied on the Google page. Start again to retry.`; expired_token → `EXPIRED` `The code expired before it was entered. Start again.`; local expiry (advance 31 min, no request sent) → `EXPIRED`; `Failed("invalid_grant","Malformed auth code.")` → `FAILED` `Google refused the authorization (invalid_grant: Malformed auth code.)`.
- `networkTroubleKeepsPolling`: fake closed after start (`close()` then advance 5 s) → still `PENDING` with message `Could not reach Google; still trying`.
- `cancelReturnsToIdle`: `cancel()` → `IDLE`, next `pollOnce()` sends nothing.
- `startWithoutClientIsRefused`: secrets empty → `YouTubeException` `NOT_CONFIGURED` `Save the OAuth client ID and secret first`.
- `aSecondStartReplacesThePending`: two `start()` calls → two device-code requests; state `PENDING` for the second code.

`YouTubeSetupServiceTest` (mocks for `LoginService`, `GoogleOAuthClient`, `GoogleTokens`, `YouTubeAuthorizationService`; mock `SecretStore`; real settings file):
- `connectValidatesTheClientId` (parameterized invalid: blank, `abc`, `123-abc.apps.googleusercontent.com.evil.org`) → `YouTubeException` `INVALID_INPUT` `That does not look like an OAuth client ID (it ends in .apps.googleusercontent.com)`; valid `123456789012-abc123def456.apps.googleusercontent.com` accepted.
- `connectStoresBothSecretsWithTheLoginPasswordThenStarts`: `verify(login).storeSecrets(Map.of("youtube.client-id", id, "youtube.client-secret", "GOCSPX-abc"), "pw-1234567890", "pw-1234567890", request)` then `verify(authorization).start()`; order verified with `inOrder`.
- `aBlankSecretKeepsTheStoredOne`: `secret(CLIENT_SECRET)` present, request secret blank → `storeSecrets` map has only `youtube.client-id`; no stored secret and blank → `INVALID_INPUT` `Enter the client secret`.
- `aPasswordProblemStoresNothing`: `storeSecrets` throws `PasswordRejectedException` → propagated; `authorization.start()` never called.
- `disconnectRevokesRemovesAndKeepsLounge`: refresh token `rt` stored; settings with account and lounge devices → `verify(authorization).cancel()`, `verify(oauth).revoke("rt")`, `verify(login).removeSecrets(List.of("youtube.client-id","youtube.client-secret","youtube.refresh-token"))`, `verify(tokens).reset()`; settings afterwards = `withoutAccount()` of the old ones.
- `aFailedRevokeStillDisconnects`: `revoke` throws `UNREACHABLE` → secrets still removed.
- `checkRefreshesTheToken`: `check()` → `verify(tokens).invalidate()` then `accessToken()`; returns `Google accepted the saved authorization`.
- `connectRequestToStringHidesSecrets`: contains neither the client secret nor the passwords.

`YouTubeSetupControllerTest` — `@WebMvcTest(YouTubeSetupController.class)` (imports as in C's `JellyfinSetupControllerTest`) with `@MockitoBean YouTubeSetupService setup`:
- `connectRedirectsWithTheCodeHint`: POST `/setup/sources/youtube/connect` with `clientId`, `clientSecret`, `loginPassword`, `loginPasswordConfirmation` → 302 `/setup#youtube`, flash `youtubeMessage` `Enter the code on your phone`; the captured `ConnectRequest` carries the four values.
- `aFailureKeepsOnlyTheClientId`: service throws `YouTubeException(INVALID_INPUT, "Enter the client secret")` → flash `youtubeError` with that text and `youtubeForm` = `{clientId=…}` only (no secret, no password).
- `passwordAndLoginProblemsAreShown`: `PasswordRejectedException("The two passwords do not match")` → `youtubeError` with that text; `LoginRequiredException` → `youtubeError` `Log in first`.
- `authorizeCancelTestDisconnect`: each endpoint calls the service method and flashes `Enter the code on your phone` / `Cancelled` / the `check()` result / `YouTube disconnected`; a `YouTubeException` becomes `youtubeError`.
- `theAuthorizationFragmentPollsWhilePending`: `status()` PENDING → 200, body contains `GQVQ-JKEC`, `https://www.google.com/device` and `hx-trigger="every 3s"`, no `HX-Refresh` header.
- `theAuthorizationFragmentRefreshesOnceConnected`: CONNECTED → header `HX-Refresh: true`, body without `hx-trigger`.
- For `/authorization` the service method is `setup.authorizationStatus()` — add `YouTubeAuthorizationService.Status authorizationStatus()` delegating to `authorization.status()` to `YouTubeSetupService`.

`YouTubeModuleSwitchTest` — `@SpringBootTest(properties = "home-control.youtube.enabled=false")`, `@AutoConfigureMockMvc`, isolated `shield.data-dir` through `@DynamicPropertySource` (temp dir), as C's `JellyfinModuleSwitchTest`. Case `theModuleCanBeSwitchedOff`: no beans of `GoogleOAuthClient`, `YouTubeSetupService`, `YouTubeSetupController`, `YouTubeSetupAdvice`; `GET /setup` → 200 without `YouTube`; `POST /setup/sources/youtube/connect` → 404.

- [ ] **Step 5: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*'`
Expected: compilation failure — the YouTube classes do not exist.

- [ ] **Step 6: Implement the exception, HTTP client and settings**

`sources/youtube/YouTubeException.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A YouTube or Google failure with a user-facing message. Never carries a token, form body or query string. */
public class YouTubeException extends ContentSourceException {

    public enum Kind {
        INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, UNAUTHORIZED, REVOKED, FORBIDDEN, NOT_FOUND,
        QUOTA_EXHAUSTED, SEARCH_LIMIT, SERVER_ERROR, BAD_RESPONSE
    }

    private final Kind kind;
    private final String reason;

    public YouTubeException(Kind kind, String message) {
        this(kind, message, null);
    }

    /** {@code reason} is Google's machine-readable reason, e.g. {@code quotaExceeded}; may be null. */
    public YouTubeException(Kind kind, String message, String reason) {
        super(message);
        this.kind = kind;
        this.reason = reason;
    }

    public Kind kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }
}
```

`sources/youtube/YouTubeHttp.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** Plain HTTPS to Google: no redirects, timeouts, errors that never echo credentials. */
public class YouTubeHttp {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Response(int status, String contentType, byte[] body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (JacksonException e) {
                throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google sent an answer that is not JSON");
            }
        }
    }

    private final HttpClient http;
    private final Duration requestTimeout;

    public YouTubeHttp(YouTubeProperties properties) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
        this.requestTimeout = Duration.ofSeconds(properties.requestTimeoutSeconds());
    }

    public Response get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET();
        headers.forEach(builder::header);
        return send(uri, builder);
    }

    public Response postForm(URI uri, Map<String, String> form, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return send(uri, builder);
    }

    private Response send(URI uri, HttpRequest.Builder builder) {
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""), response.body());
        } catch (IOException e) {
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach " + uri.getHost());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Interrupted while calling " + uri.getHost());
        }
    }

    /** {@code base + path + ?query}; null values are skipped; insertion order is kept. */
    public static URI uri(URI base, String path, Map<String, String> query) {
        String encoded = form(query);
        return URI.create(base.toString() + path + (encoded.isEmpty() ? "" : "?" + encoded));
    }

    public static String form(Map<String, String> values) {
        StringJoiner joined = new StringJoiner("&");
        values.forEach((key, value) -> {
            if (value != null) {
                joined.add(encode(key) + "=" + encode(value));
            }
        });
        return joined.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

`sources/youtube/YouTubeSettings.java` — the record from **Interfaces**. Compact constructor: `playlists` copied into a `LinkedHashMap` sorted by title with `String.CASE_INSENSITIVE_ORDER` then id, wrapped unmodifiable (null → empty); `loungeDevices` copied into an unmodifiable `TreeSet` (null → empty). `from(map)`: `connectedAt` = `Instant.parse` when present and parsable, else null; `watchLater` = `"true".equals(...)`; every key starting with `playlist.` whose suffix matches `[A-Za-z0-9_-]{2,64}` becomes a playlist; `lounge.devices` split on `,`, blanks dropped. `toMap()` writes the keys of the normative list in that order into a `LinkedHashMap`, omitting nulls, blanks, `watchLater=false` and an empty device set. `withoutAccount()` = `new YouTubeSettings(null, null, null, false, Map.of(), loungeDevices, loungeRemoteId)`. `static final YouTubeSettings EMPTY = from(Map.of())`.

- [ ] **Step 7: Implement the OAuth client**

`sources/youtube/GoogleOAuthClient.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Google's OAuth 2.0 device authorization grant for "TVs and Limited Input devices". */
public class GoogleOAuthClient {

    public static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    public static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    static final URI DEFAULT_VERIFICATION_URL = URI.create("https://www.google.com/device");

    public record DeviceCode(String deviceCode, String userCode, URI verificationUrl, Instant expiresAt, Duration interval) {
        @Override
        public String toString() {
            return "DeviceCode[userCode=" + userCode + ", expiresAt=" + expiresAt + ", interval=" + interval + "]";
        }
    }

    public record AccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessToken[expiresAt=" + expiresAt + "]";
        }
    }

    public sealed interface TokenPoll {
        record Granted(AccessToken accessToken, String refreshToken) implements TokenPoll {
            @Override
            public String toString() {
                return "Granted[" + accessToken + "]";
            }
        }

        record Pending() implements TokenPoll {
        }

        record SlowDown() implements TokenPoll {
        }

        record Denied() implements TokenPoll {
        }

        record Expired() implements TokenPoll {
        }

        record Failed(String error, String description) implements TokenPoll {
        }
    }

    private final YouTubeHttp http;
    private final URI base;
    private final Clock clock;

    public GoogleOAuthClient(YouTubeHttp http, URI oauthBaseUrl, Clock clock) {
        this.http = http;
        this.base = oauthBaseUrl;
        this.clock = clock;
    }

    public DeviceCode requestDeviceCode(String clientId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("scope", SCOPE);
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/device/code"), form, Map.of());
        if (!response.ok()) {
            throw failure(response);
        }
        JsonNode json = response.json();
        String deviceCode = json.path("device_code").asString("");
        String userCode = json.path("user_code").asString("");
        if (deviceCode.isBlank() || userCode.isBlank()) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google did not return a device code");
        }
        String url = json.path("verification_url").asString("");
        return new DeviceCode(deviceCode, userCode,
                url.isBlank() ? DEFAULT_VERIFICATION_URL : URI.create(url),
                clock.instant().plusSeconds(json.path("expires_in").asLong(1800)),
                Duration.ofSeconds(Math.max(1, json.path("interval").asLong(5))));
    }

    public TokenPoll poll(String clientId, String clientSecret, String deviceCode) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("device_code", deviceCode);
        form.put("grant_type", DEVICE_GRANT);
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/token"), form, Map.of());
        if (response.ok()) {
            JsonNode json = response.json();
            String refresh = json.path("refresh_token").asString("");
            if (refresh.isBlank()) {
                return new TokenPoll.Failed("no_refresh_token", "Google did not return a refresh token");
            }
            return new TokenPoll.Granted(accessToken(json), refresh);
        }
        String error = errorCode(response);
        return switch (error) {
            case "authorization_pending" -> new TokenPoll.Pending();
            case "slow_down" -> new TokenPoll.SlowDown();
            case "access_denied" -> new TokenPoll.Denied();
            case "expired_token" -> new TokenPoll.Expired();
            case "" -> throw failure(response);
            case "invalid_client" -> throw failure(response);
            default -> new TokenPoll.Failed(error, response.json().path("error_description").asString(""));
        };
    }

    public AccessToken refresh(String clientId, String clientSecret, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");
        YouTubeHttp.Response response = http.postForm(URI.create(base + "/token"), form, Map.of());
        if (response.ok()) {
            return accessToken(response.json());
        }
        if ("invalid_grant".equals(errorCode(response))) {
            throw new YouTubeException(YouTubeException.Kind.REVOKED, "Google no longer accepts the saved YouTube"
                    + " authorization. It was revoked, or it expired after 7 days because the OAuth consent screen"
                    + " is still in “Testing”. Reconnect YouTube on the setup page.", "invalid_grant");
        }
        throw failure(response);
    }

    public void revoke(String token) {
        http.postForm(URI.create(base + "/revoke"), Map.of("token", token), Map.of());
    }

    private AccessToken accessToken(JsonNode json) {
        String value = json.path("access_token").asString("");
        if (value.isBlank()) {
            throw new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google did not return an access token");
        }
        return new AccessToken(value, clock.instant().plusSeconds(json.path("expires_in").asLong(3600)));
    }

    private static String errorCode(YouTubeHttp.Response response) {
        try {
            return response.json().path("error").asString("");
        } catch (YouTubeException notJson) {
            return "";
        }
    }

    private static YouTubeException failure(YouTubeHttp.Response response) {
        String error = errorCode(response);
        if ("invalid_client".equals(error)) {
            String description = response.json().path("error_description").asString("").toLowerCase(Locale.ROOT);
            return new YouTubeException(YouTubeException.Kind.UNAUTHORIZED, description.contains("client type")
                    ? "Google says this OAuth client cannot use the device flow. Create a client of type"
                    + " “TVs and Limited Input devices”."
                    : "Google rejected the client ID or client secret.", error);
        }
        if (!error.isEmpty()) {
            return new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "Google refused the request (" + error + ")", error);
        }
        return new YouTubeException(response.status() >= 500 ? YouTubeException.Kind.SERVER_ERROR
                : YouTubeException.Kind.BAD_RESPONSE, "Google answered HTTP " + response.status());
    }
}
```

- [ ] **Step 8: Implement tokens and the authorization service**

`sources/youtube/GoogleTokens.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.SecretStore;

import java.time.Clock;
import java.time.Duration;

/** The access token lives only here, in memory. */
public class GoogleTokens {

    private static final Duration EARLY = Duration.ofSeconds(60);

    private final GoogleOAuthClient oauth;
    private final SecretStore secrets;
    private final Clock clock;
    private GoogleOAuthClient.AccessToken current;
    private YouTubeException revoked;

    public GoogleTokens(GoogleOAuthClient oauth, SecretStore secrets, Clock clock) {
        this.oauth = oauth;
        this.secrets = secrets;
        this.clock = clock;
    }

    public synchronized String accessToken() {
        if (revoked != null) {
            throw revoked;
        }
        if (current != null && clock.instant().isBefore(current.expiresAt().minus(EARLY))) {
            return current.value();
        }
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        String clientSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).orElse(null);
        String refreshToken = secrets.secret(YouTubeSettings.REFRESH_TOKEN).orElse(null);
        if (clientId == null || clientSecret == null || refreshToken == null) {
            throw new YouTubeException(YouTubeException.Kind.NOT_CONFIGURED, "YouTube is not connected");
        }
        try {
            current = oauth.refresh(clientId, clientSecret, refreshToken);
            return current.value();
        } catch (YouTubeException e) {
            if (e.kind() == YouTubeException.Kind.REVOKED) {
                revoked = e;
            }
            throw e;
        }
    }

    public synchronized void prime(GoogleOAuthClient.AccessToken token) {
        current = token;
    }

    public synchronized void invalidate() {
        current = null;
    }

    public synchronized void reset() {
        current = null;
        revoked = null;
    }

    public synchronized boolean revoked() {
        return revoked != null;
    }

    public boolean hasClient() {
        return secrets.secret(YouTubeSettings.CLIENT_ID).isPresent() && secrets.secret(YouTubeSettings.CLIENT_SECRET).isPresent();
    }

    public boolean hasRefreshToken() {
        return secrets.secret(YouTubeSettings.REFRESH_TOKEN).isPresent();
    }
}
```

`sources/youtube/YouTubeAuthorizationService.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One pending device authorization at a time; polled in the background; the device code never leaves here. */
public class YouTubeAuthorizationService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YouTubeAuthorizationService.class);
    private static final Duration SLOW_DOWN_STEP = Duration.ofSeconds(5);

    public enum State { IDLE, PENDING, CONNECTED, DENIED, EXPIRED, FAILED }

    public record Status(State state, String userCode, URI verificationUrl, Instant expiresAt, String message) {
        static Status of(State state, String message) {
            return new Status(state, null, null, null, message);
        }
    }

    private final GoogleOAuthClient oauth;
    private final SecretStore secrets;
    private final GoogleTokens tokens;
    private final JsonFileSourceSettings settings;
    private final Clock clock;
    private final List<Runnable> connectedListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;

    private GoogleOAuthClient.DeviceCode pending;
    private Duration interval;
    private Instant nextPollAt;
    private Status status = Status.of(State.IDLE, null);

    public YouTubeAuthorizationService(GoogleOAuthClient oauth, SecretStore secrets, GoogleTokens tokens,
                                       JsonFileSourceSettings settings, Clock clock, boolean backgroundPolling) {
        this.oauth = oauth;
        this.secrets = secrets;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        if (backgroundPolling) {
            scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                    .name("youtube-authorization").daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        } else {
            scheduler = null;
        }
    }

    public void onConnected(Runnable listener) {
        connectedListeners.add(listener);
    }

    public Status start() {
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse(null);
        if (clientId == null || secrets.secret(YouTubeSettings.CLIENT_SECRET).isEmpty()) {
            throw new YouTubeException(YouTubeException.Kind.NOT_CONFIGURED, "Save the OAuth client ID and secret first");
        }
        GoogleOAuthClient.DeviceCode code = oauth.requestDeviceCode(clientId);
        synchronized (this) {
            pending = code;
            interval = code.interval();
            nextPollAt = clock.instant().plus(interval);
            status = new Status(State.PENDING, code.userCode(), code.verificationUrl(), code.expiresAt(), null);
            return status;
        }
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized void cancel() {
        pending = null;
        status = Status.of(State.IDLE, null);
    }

    /** One poll if one is due. Returns true while the authorization is still pending. */
    public boolean pollOnce() {
        GoogleOAuthClient.DeviceCode code;
        synchronized (this) {
            if (pending == null) {
                return false;
            }
            Instant now = clock.instant();
            if (!now.isBefore(pending.expiresAt())) {
                return finish(State.EXPIRED, "The code expired before it was entered. Start again.");
            }
            if (now.isBefore(nextPollAt)) {
                return true;
            }
            code = pending;
        }
        String clientId = secrets.secret(YouTubeSettings.CLIENT_ID).orElse("");
        String clientSecret = secrets.secret(YouTubeSettings.CLIENT_SECRET).orElse("");
        GoogleOAuthClient.TokenPoll result;
        try {
            result = oauth.poll(clientId, clientSecret, code.deviceCode());
        } catch (YouTubeException e) {
            synchronized (this) {
                if (pending != code) {
                    return pending != null;
                }
                if (e.kind() == YouTubeException.Kind.UNREACHABLE || e.kind() == YouTubeException.Kind.SERVER_ERROR) {
                    nextPollAt = clock.instant().plus(interval);
                    status = new Status(State.PENDING, code.userCode(), code.verificationUrl(), code.expiresAt(),
                            "Could not reach Google; still trying");
                    return true;
                }
                return finish(State.FAILED, e.getMessage());
            }
        }
        synchronized (this) {
            if (pending != code) {
                return pending != null; // cancelled or restarted meanwhile
            }
            switch (result) {
                case GoogleOAuthClient.TokenPoll.Pending ignored -> {
                    nextPollAt = clock.instant().plus(interval);
                    return true;
                }
                case GoogleOAuthClient.TokenPoll.SlowDown ignored -> {
                    interval = interval.plus(SLOW_DOWN_STEP);
                    nextPollAt = clock.instant().plus(interval);
                    return true;
                }
                case GoogleOAuthClient.TokenPoll.Denied ignored -> {
                    return finish(State.DENIED, "Access was denied on the Google page. Start again to retry.");
                }
                case GoogleOAuthClient.TokenPoll.Expired ignored -> {
                    return finish(State.EXPIRED, "The code expired before it was entered. Start again.");
                }
                case GoogleOAuthClient.TokenPoll.Failed failed -> {
                    return finish(State.FAILED, "Google refused the authorization (" + failed.error()
                            + (failed.description().isBlank() ? "" : ": " + failed.description()) + ")");
                }
                case GoogleOAuthClient.TokenPoll.Granted granted -> {
                    secrets.putSecrets(Map.of(YouTubeSettings.REFRESH_TOKEN, granted.refreshToken()));
                    tokens.reset();
                    tokens.prime(granted.accessToken());
                    YouTubeSettings current = YouTubeSettings.from(settings.get(YouTubeSettings.SOURCE_ID));
                    settings.put(YouTubeSettings.SOURCE_ID,
                            current.withConnection(clock.instant(), current.channelId(), current.channelTitle()).toMap());
                    finish(State.CONNECTED, "YouTube connected");
                }
            }
        }
        for (Runnable listener : connectedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("YouTube post-connect step failed: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean finish(State state, String message) {
        pending = null;
        status = Status.of(state, message);
        return false;
    }

    private void tick() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("YouTube authorization poll failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
```

- [ ] **Step 9: Implement setup service, controller, advice, configuration and template**

`sources/youtube/YouTubeSetupService.java` — constructor from **Interfaces**. Rules:
- `CLIENT_ID_PATTERN = Pattern.compile("^[0-9]{6,20}-[a-z0-9]{8,64}\\.apps\\.googleusercontent\\.com$", CASE_INSENSITIVE)`.
- `settings()` = `YouTubeSettings.from(sourceSettings.get("youtube"))`; `save(s)` = `sourceSettings.put("youtube", s.toMap())`.
- `hasClient()` = `tokens.hasClient()`; `connected()` = `tokens.hasClient() && tokens.hasRefreshToken()`.
- `connect(request, http)`: trim `clientId`; invalid → `INVALID_INPUT` message from the test; `clientSecret` trimmed; blank and no stored secret → `INVALID_INPUT` `Enter the client secret`; longer than 200 → `INVALID_INPUT` `That client secret is too long`. Build the map (client id always, secret only when non-blank), call `login.storeSecrets(map, request.loginPassword(), request.loginPasswordConfirmation(), http)`; if the stored client id changed, also `login.removeSecrets(List.of(REFRESH_TOKEN))` only when a refresh token exists **and** another secret remains (never removes the last secret) and `tokens.reset()`; then `return authorization.start()`.
- `authorize()` = `authorization.start()`; `cancel()` = `authorization.cancel()`; `authorizationStatus()` = `authorization.status()`.
- `check()`: `tokens.invalidate(); tokens.accessToken(); return "Google accepted the saved authorization";` (Task 2 replaces the text).
- `disconnect()`: `authorization.cancel()`; if a refresh token exists, `oauth.revoke(token)` inside `try/catch (YouTubeException e)` logging `log.info("Could not revoke the YouTube authorization at Google: {}", e.getMessage())`; `login.removeSecrets(List.of(CLIENT_ID, CLIENT_SECRET, REFRESH_TOKEN))`; `save(settings().withoutAccount())`; `tokens.reset()`.
- `record ConnectRequest(...)` with `toString()` = `ConnectRequest[clientId=<id>]`.

`sources/youtube/YouTubeSetupController.java` — `@Controller` with the module's `@ConditionalOnProperty`; handlers exactly as `JellyfinSetupController` (flash attributes via `RedirectAttributes`, redirect `redirect:/setup#youtube`); catch `YouTubeException`, `PasswordRejectedException` → `youtubeError` = message; `LoginRequiredException` → `Log in first`. `connect` success flash `youtubeMessage` `Enter the code on your phone`; on failure also flash `youtubeForm` = `Map.of("clientId", clientId == null ? "" : clientId)`. `GET /setup/sources/youtube/authorization` adds model `authorization` = `setup.authorizationStatus()` and, when `CONNECTED`, `response.setHeader("HX-Refresh", "true")`; returns `"fragments/youtube-setup :: authorization"`.

`sources/youtube/YouTubeSetupAdvice.java` — like `JellyfinSetupAdvice` (`@ControllerAdvice(assignableTypes = SetupController.class)`, module condition, `ObjectProvider<YouTubeSetupService>`, `ObjectProvider<LoginService>`), model attribute `youtube`:

```java
    /** What the setup page shows about YouTube. Never holds a secret or a device code. */
    public record View(boolean hasClient, boolean connected, boolean revoked, String channelTitle,
                       YouTubeAuthorizationService.Status authorization, boolean needsLoginPassword) {
    }
```

`revoked` comes from `GoogleTokens.revoked()` — expose it as `YouTubeSetupService.revoked()`.

`sources/youtube/YouTubeConfiguration.java` — `@Configuration` with the module condition. Beans: `Clock youtubeClock()` → `Clock.systemDefaultZone()` (named bean, so other `Clock` beans are not disturbed; inject with `@Qualifier("youtubeClock")`), `YouTubeHttp youTubeHttp(YouTubeProperties)`, `GoogleOAuthClient googleOAuthClient(YouTubeHttp, YouTubeProperties, @Qualifier("youtubeClock") Clock)` with `properties.oauthBaseUrl()`, `GoogleTokens googleTokens(GoogleOAuthClient, SecretStore, Clock)`, `@Bean(destroyMethod = "close") YouTubeAuthorizationService youTubeAuthorizationService(GoogleOAuthClient, SecretStore, GoogleTokens, JsonFileSourceSettings, Clock)` with `backgroundPolling = true`, `YouTubeSetupService youTubeSetupService(SecretStore, LoginService, JsonFileSourceSettings, GoogleOAuthClient, GoogleTokens, YouTubeAuthorizationService)`. Later tasks add beans here.

`src/main/resources/templates/fragments/youtube-setup.html` — two fragments:

1. `<section th:fragment="section" id="youtube">` with heading `YouTube`:
   - `youtubeError` / `youtubeMessage` paragraphs like Jellyfin's.
   - When `${youtube.connected()}`: `Connected` (plus ` as <channelTitle>` when present); if `revoked()` a `<p class="error">` `Google no longer accepts the saved authorization. Connect again below.`; forms posting to `/setup/sources/youtube/test` (button `Test connection`) and `/setup/sources/youtube/disconnect` (button `Disconnect`).
   - `<div id="youtube-authorization" th:replace="~{fragments/youtube-setup :: authorization}">` rendered with `authorization` = `${youtube.authorization()}` (use `th:with="authorization=${youtube.authorization()}"` on a wrapping `th:block`).
   - A `<details th:open="${!youtube.hasClient()}">` with `<summary>How to create your own Google OAuth client (about 10 minutes)</summary>` and this ordered list (normative wording):
     1. `Open https://console.cloud.google.com/ and create a project, for example “Home Control”.`
     2. `APIs & Services → Library → “YouTube Data API v3” → Enable.`
     3. `APIs & Services → OAuth consent screen (Google Auth Platform → Branding/Audience): user type External, app name “Home Control”, your e-mail as support and developer contact. Add the scope …/auth/youtube.readonly under Data Access.`
     4. `Audience: add your Google account as a test user, then press “Publish app” so the status is “In production”. In “Testing”, Google ends the authorization after 7 days. Google will show “Google hasn’t verified this app” — that is expected for your own project; continue with “Advanced”.`
     5. `Clients (Credentials) → Create client → Application type “TVs and Limited Input devices” → Create. Copy the client ID and client secret into the form below.`
     6. `Press Connect. Home Control shows a code; open google.com/device on your phone, enter the code and allow read-only access to YouTube.`
     7. `Quota: the project gets 10 000 units a day. Home Control refreshes subscriptions hourly (about 30 units) and allows 20 searches a day (100 units each). Usage is shown here.`
   - Connect form posting to `/setup/sources/youtube/connect`: `clientId` (text, required, placeholder `123456789012-abc….apps.googleusercontent.com`, value `${youtubeForm?.clientId}`), `clientSecret` (`type="password"`, `autocomplete="off"`, required only when `!hasClient()`, hint `Leave empty to keep the saved secret` when `hasClient()`), and when `needsLoginPassword()` the same two login password fields and sentence as the Jellyfin fragment; button `Connect` (or `Reconnect` when connected).
   - When `hasClient() && !connected()` and the authorization is not pending: a form posting to `/setup/sources/youtube/authorize` with button `Show a new code`.
2. `<div th:fragment="authorization" id="youtube-authorization" …>`:
   - `PENDING`: attributes `hx-get="/setup/sources/youtube/authorization" hx-trigger="every 3s" hx-swap="outerHTML"`; content `<p>On your phone, open <a th:href="${authorization.verificationUrl()}" th:text="${authorization.verificationUrl()}" target="_blank" rel="noopener">…</a> and enter</p><p class="user-code" th:text="${authorization.userCode()}">GQVQ-JKEC</p><p class="hint">The code is valid until <time th:text="${#temporals.format(authorization.expiresAt(), 'HH:mm')}">…</time>.</p>` (format the instant in the server zone: pass `ZonedDateTime` if `#temporals` rejects `Instant` — add `expiresAtLocal` to the model), the optional `message` as `<p class="hint">`, and a form posting to `/setup/sources/youtube/cancel` with button `Cancel`.
   - `DENIED`, `EXPIRED`, `FAILED`: `<p class="error" th:text="${authorization.message()}">` and no `hx-*` attributes.
   - `CONNECTED`: `<p class="hint">YouTube connected</p>`; `IDLE`: empty div.

Add `.user-code { font: 600 2rem/1.2 ui-monospace, monospace; letter-spacing: .1em; }` to `app.css`.

In `setup.html`, directly after the Jellyfin include:

```html
    <th:block th:if="${youtube != null}">
        <section th:replace="~{fragments/youtube-setup :: section}"></section>
    </th:block>
```

- [ ] **Step 10: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL (including C's `LoginGatingTest` and `JellyfinModuleSwitchTest`).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/youtube src/main/resources/templates/fragments/youtube-setup.html \
  src/main/resources/templates/setup.html src/main/resources/static/app.css \
  src/main/resources/application.yaml src/test/resources/application.yaml \
  src/test/java/dev/andre/homecontrol/sources/youtube src/test/resources/fixtures/youtube
git commit -m "feat: connect YouTube through Google's device authorization flow"
```

---

### Task 2: E2 · Subscriptions rail

**Files:**
- Create: `sources/youtube/QuotaLedger.java`, `YouTubeApiClient.java`, `YouTubeVideo.java`, `YouTubeVideoMapper.java`, `KnownVideos.java`, `YouTubeAccount.java`, `SubscriptionsFeed.java`, `YouTubeContentSource.java`, `YouTubeThumbnailController.java`
- Modify: `sources/youtube/YouTubeSetupService.java`, `YouTubeSetupAdvice.java`, `YouTubeConfiguration.java`, `src/main/resources/templates/fragments/youtube-setup.html`
- Test: `sources/youtube/QuotaLedgerTest.java`, `YouTubeApiClientTest.java`, `YouTubeVideoMapperTest.java`, `SubscriptionsFeedTest.java`, `YouTubeContentSourceTest.java`, `YouTubeThumbnailControllerTest.java`, `YouTubeSetupServiceTest.java`; fixtures `channels-mine.json`, `subscriptions-page-1.json`, `subscriptions-page-2.json`, `channels-uploads.json`, `playlist-items-uploads-kurzgesagt.json`, `playlist-items-uploads-blender.json`, `playlist-items-uploads-nasa.json`, `videos-by-id.json`, `error-quota-exceeded.json`, `error-api-not-enabled.json`, `error-playlist-not-found.json`, `error-unauthorized.json`

**Interfaces:**
- Consumes: `ContentSource`, `Rail(RailDescriptor, List<ContentItem>, Instant)`, `RailDescriptor(sourceId, id, title)`, `ContentSourceException`, `ContentItem` 8-argument constructor, `ContentKind.VIDEO`, `PlayableRef.AppLink(URI, String)` (A/C3); `ContentSource.defaultRefreshInterval()` (D1); `AndroidTvProperties.dataDir()` (A); from Task 1: `YouTubeHttp`, `YouTubeException`, `YouTubeProperties`, `GoogleTokens.accessToken/invalidate`, `YouTubeSetupService.connected/settings/save`, `YouTubeAuthorizationService.onConnected`, `FakeGoogleServer`, `MutableClock`.
- Produces:
  - `class QuotaLedger { static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles"); enum Call { SUBSCRIPTIONS_LIST, CHANNELS_LIST, PLAYLIST_ITEMS_LIST, PLAYLISTS_LIST, VIDEOS_LIST, SEARCH_LIST; String apiName(); int units(); } QuotaLedger(Path file, Clock clock, int dailyUnits, int searchesPerDay); synchronized void charge(Call); synchronized void markExhausted(); synchronized Usage usage(); record Usage(LocalDate day, int units, int dailyUnits, int searches, int searchesPerDay, Map<String,Integer> calls, ZonedDateTime resetsAt) { int searchesLeft(); boolean exhausted(); } static String resetPhrase(ZonedDateTime resetsAt); }`.
  - `class YouTubeApiClient { YouTubeApiClient(YouTubeHttp, URI apiBaseUrl, GoogleTokens, QuotaLedger); JsonNode get(QuotaLedger.Call call, String resource, Map<String,String> query); }`.
  - `record YouTubeVideo(String id, String title, String channelTitle, Instant publishedAt)` with `ContentItem toItem()`, `static final String SOURCE_ID = "youtube"`, `static URI watchUrl(String id)`, `static boolean validId(String)`.
  - `final class YouTubeVideoMapper { static Optional<YouTubeVideo> fromPlaylistItem(JsonNode); static Optional<YouTubeVideo> fromVideo(JsonNode); static List<YouTubeVideo> playlistItems(JsonNode response); }` (Task 4 adds `fromSearchResult`).
  - `class KnownVideos { KnownVideos(int capacity); void remember(Collection<YouTubeVideo>); Optional<YouTubeVideo> find(String id); }` (LRU, thread-safe).
  - `class YouTubeAccount { YouTubeAccount(YouTubeApiClient, YouTubeSetupService); String refreshChannel(); }` → the channel title.
  - `class SubscriptionsFeed { SubscriptionsFeed(YouTubeApiClient, YouTubeProperties, Clock); synchronized List<YouTubeVideo> refresh(); synchronized void clear(); }`.
  - `class YouTubeContentSource implements ContentSource` — `YouTubeContentSource(YouTubeSetupService, SubscriptionsFeed, YouTubeApiClient, KnownVideos, YouTubeProperties, Clock)`; id `youtube`, name `YouTube`, rail `subscriptions` "New from your subscriptions"; `defaultRefreshInterval()` = `refreshInterval`; `void forgetAccount()` (clears caches).
  - `GET /sources/youtube/thumbnails/{videoId}` → `image/jpeg` 200 / 400 / 404 / 502.
  - `YouTubeSetupService.check()` → `Connected as <title>. <units> of <budget> quota units used today.`; `YouTubeSetupAdvice.View` gains the last component `QuotaView quota` with `record QuotaView(int units, int dailyUnits, int searches, int searchesPerDay, String resets, List<CallCount> calls)` and `record CallCount(String api, int count, int units)`.

**Data API wire format (normative).** Every call: `GET <api-base-url>/<resource>?<query in the order listed>`, headers `Authorization: Bearer <access token>` and `Accept: application/json`; quota charged before the request.
- `subscriptions?part=snippet&mine=true&maxResults=50[&pageToken=<t>]` (`SUBSCRIPTIONS_LIST`) → `items[].snippet.resourceId.channelId`, `items[].snippet.title`, `nextPageToken`.
- `channels?part=contentDetails&id=<comma-joined ≤ 50 ids>&maxResults=50` (`CHANNELS_LIST`) → `items[].id`, `items[].contentDetails.relatedPlaylists.uploads`.
- `playlistItems?part=snippet,contentDetails&playlistId=<id>&maxResults=<n>` (`PLAYLIST_ITEMS_LIST`) → `items[].snippet` (`title`, `videoOwnerChannelTitle`, `videoOwnerChannelId`, `resourceId.videoId`, `publishedAt`), `items[].contentDetails` (`videoId`, `videoPublishedAt`).
- `channels?part=snippet&mine=true` (`CHANNELS_LIST`) → `items[0].id`, `items[0].snippet.title`.
- `videos?part=snippet&id=<videoId>` (`VIDEOS_LIST`) → `items[0].id`, `items[0].snippet.title`, `.channelTitle`, `.publishedAt`.
- Errors → `{"error":{"code","message","errors":[{"domain","reason","message"}]}}`. Mapping in order: 401 → `tokens.invalidate()`, retry once (no second charge); a second 401 → `UNAUTHORIZED` `Google rejected the YouTube authorization; reconnect YouTube on the setup page`. 403 with reason `quotaExceeded` or `dailyLimitExceeded` → `ledger.markExhausted()` and `QUOTA_EXHAUSTED` with the exhausted message below. 403 with reason `accessNotConfigured` (or top-level `status` `PERMISSION_DENIED` whose message contains `has not been used` or `is disabled`) → `FORBIDDEN` `The YouTube Data API v3 is not enabled in your Google Cloud project. Enable it, wait a few minutes and try again.`. 403 `insufficientPermissions` → `FORBIDDEN` `The saved authorization does not include read access to YouTube; reconnect YouTube.`. Other 403 → `FORBIDDEN` `YouTube refused the request (<reason>)`. 404 → `NOT_FOUND` `YouTube could not find it (<reason>)` with `reason()` = the reason. 5xx → `SERVER_ERROR` `YouTube is having problems (HTTP <code>)`. Anything else non-2xx → `BAD_RESPONSE` `YouTube answered HTTP <code>`.

**Quota ledger (normative).** Units: `subscriptions.list`, `channels.list`, `playlistItems.list`, `playlists.list`, `videos.list` = 1; `search.list` = 100. The day is `LocalDate.now(clock.withZone(PACIFIC))`; a new day starts from zero. `charge(call)`: if `call == SEARCH_LIST` and `searches >= searchesPerDay` → `SEARCH_LIMIT` `You have used today's <searchesPerDay> YouTube searches. More <resetPhrase>.`; if `units + call.units() > dailyUnits` → `QUOTA_EXHAUSTED` `YouTube's daily API quota is used up (<units> of <dailyUnits> units). Rails refresh again <resetPhrase>.`; else add units, count the call (and the search), write the file. `markExhausted()` sets `units = dailyUnits` and writes. `resetsAt` = next midnight in `PACIFIC` converted to `clock.getZone()`; `resetPhrase` = `after midnight Pacific time (<HH:mm> here)`. File `<dataDir>/youtube-quota.json`, written atomically (temp file in the same directory + `ATOMIC_MOVE`, `REPLACE_EXISTING`):

```json
{
  "version" : 1,
  "day" : "2026-09-16",
  "units" : 212,
  "searches" : 1,
  "calls" : { "subscriptions.list" : 4, "channels.list" : 5, "playlistItems.list" : 103, "search.list" : 1 }
}
```

A missing file is an empty ledger. An unreadable file, a `version` other than 1 or a missing `day` → rename to `youtube-quota.json.corrupt-<epochSeconds>`, log WARN, empty ledger. A stored `day` other than today → empty ledger for today (the file is rewritten on the next charge).

**Video mapping (normative).** A playlist item maps when `contentDetails.videoId` (fallback `snippet.resourceId.videoId`) matches `^[A-Za-z0-9_-]{11}$` and it is not unavailable — unavailable means `snippet.title` is `Private video` or `Deleted video`, or `snippet.videoOwnerChannelId` is missing. Title `snippet.title`; channel `snippet.videoOwnerChannelTitle` (fallback `snippet.channelTitle`); published `contentDetails.videoPublishedAt` (fallback `snippet.publishedAt`, else `Instant.EPOCH`; unparsable → `Instant.EPOCH`). `toItem()` = `new ContentItem(id, "youtube", ContentKind.VIDEO, title, channelTitle (null when blank), URI.create("/sources/youtube/thumbnails/" + id), List.of(new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=" + id), "youtube")), null)`.

**Subscriptions feed (normative).** State: `subscriptions` (ordered `channelId → title`), `subscriptionsFetchedAt`, `uploads` (`channelId → uploads playlist id`, kept across refreshes), `polled` (`channelId → (List<YouTubeVideo>, Instant polledAt)`), `lastResult`, `lastRefreshAt`.
1. If `lastResult != null` and `now < lastRefreshAt + minRefreshSpacing` → return `lastResult` (no calls).
2. If `subscriptions` is null or `now ≥ subscriptionsFetchedAt + subscriptionsRefresh`: page `subscriptions.list` until no `nextPageToken` or `maxSubscriptionPages` pages; replace `subscriptions`; drop `polled` and `uploads` entries of channels no longer subscribed. Then call `channels.list` for channels without an `uploads` entry, in batches of 50 in subscription order; channels missing from the answer or without an `uploads` value get `""` (never polled).
3. Candidates = subscribed channels with a non-blank uploads id, ordered by `polledAt` ascending (never polled first, ties in subscription order); poll the first `channelsPerRefresh`: `playlistItems.list` with `maxResults = videosPerChannel`; success → `polled.put(channel, (videos, now))`; `NOT_FOUND` → `polled.put(channel, (List.of(), now))`.
4. `QUOTA_EXHAUSTED` from any call: stop further calls; if `polled` is empty (nothing to show) rethrow, else continue with step 5. `REVOKED`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_CONFIGURED`: rethrow. Any other `YouTubeException` in step 3: skip that channel (keep its previous videos), log at DEBUG. Any exception in step 2: rethrow.
5. Result = all videos in `polled`, de-duplicated by id, sorted by `publishedAt` descending then id, first `railSize`; set `lastResult`, `lastRefreshAt = now`.
`clear()` resets all state.

- [ ] **Step 1: Write the fixtures**

Channel ids used everywhere: Kurzgesagt `UCsXVk37bltHxD1rDPwtNM8Q` (uploads `UUsXVk37bltHxD1rDPwtNM8Q`), Blender `UCSMOQeBJ2RAnuFungnQOxLg` (uploads `UUSMOQeBJ2RAnuFungnQOxLg`), NASA `UCLA_DiR1FfKNvjuUpBHmylQ` (uploads `UULA_DiR1FfKNvjuUpBHmylQ`).

`channels-mine.json`:

```json
{
  "kind": "youtube#channelListResponse",
  "etag": "Qk9fDeA3xw1dsvWmPzCj0sXw2hE",
  "pageInfo": { "totalResults": 1, "resultsPerPage": 5 },
  "items": [
    {
      "kind": "youtube#channel",
      "etag": "b3JpZ2luYWwtZXRhZy1taW5l",
      "id": "UC4fixtureHomeControl00a",
      "snippet": {
        "title": "Andre at Home",
        "description": "",
        "publishedAt": "2012-03-01T10:00:00Z",
        "thumbnails": { "default": { "url": "https://yt3.ggpht.com/fixture=s88-c-k-c0x00ffffff-no-rj", "width": 88, "height": 88 } },
        "localized": { "title": "Andre at Home", "description": "" }
      }
    }
  ]
}
```

`subscriptions-page-1.json`:

```json
{
  "kind": "youtube#subscriptionListResponse",
  "etag": "c3Vic2NyaXB0aW9ucy1wYWdlLTE",
  "nextPageToken": "CAIQAA",
  "pageInfo": { "totalResults": 3, "resultsPerPage": 2 },
  "items": [
    {
      "kind": "youtube#subscription",
      "etag": "c3ViLWt1cnpnZXNhZ3Q",
      "id": "yJ8M0x0XgT3kQeHc9Wq1vB2nL7rP4sD6fA5gH8jK0mN",
      "snippet": {
        "publishedAt": "2019-05-12T18:21:07.123456Z",
        "title": "Kurzgesagt – In a Nutshell",
        "description": "Videos explaining things with optimistic nihilism.",
        "resourceId": { "kind": "youtube#channel", "channelId": "UCsXVk37bltHxD1rDPwtNM8Q" },
        "channelId": "UC4fixtureHomeControl00a",
        "thumbnails": { "default": { "url": "https://yt3.ggpht.com/kurzgesagt=s88-c-k-c0x00ffffff-no-rj" } }
      }
    },
    {
      "kind": "youtube#subscription",
      "etag": "c3ViLWJsZW5kZXI",
      "id": "yJ8M0x0XgT3kQeHc9Wq1vB2nL7rP4sD6fA5gH8jK0mO",
      "snippet": {
        "publishedAt": "2020-01-02T08:00:00Z",
        "title": "Blender",
        "description": "Blender is the free and open source 3D creation suite.",
        "resourceId": { "kind": "youtube#channel", "channelId": "UCSMOQeBJ2RAnuFungnQOxLg" },
        "channelId": "UC4fixtureHomeControl00a",
        "thumbnails": { "default": { "url": "https://yt3.ggpht.com/blender=s88-c-k-c0x00ffffff-no-rj" } }
      }
    }
  ]
}
```

`subscriptions-page-2.json` — same envelope without `nextPageToken`, `pageInfo.resultsPerPage` 1, one item: id `yJ8M0x0XgT3kQeHc9Wq1vB2nL7rP4sD6fA5gH8jK0mP`, title `NASA`, `resourceId.channelId` `UCLA_DiR1FfKNvjuUpBHmylQ`, `publishedAt` `2021-07-20T20:17:00Z`.

`channels-uploads.json`:

```json
{
  "kind": "youtube#channelListResponse",
  "etag": "Y2hhbm5lbHMtdXBsb2Fkcw",
  "pageInfo": { "totalResults": 3, "resultsPerPage": 3 },
  "items": [
    { "kind": "youtube#channel", "etag": "a3Vyeg", "id": "UCsXVk37bltHxD1rDPwtNM8Q",
      "contentDetails": { "relatedPlaylists": { "likes": "", "uploads": "UUsXVk37bltHxD1rDPwtNM8Q" } } },
    { "kind": "youtube#channel", "etag": "Ymxlbg", "id": "UCSMOQeBJ2RAnuFungnQOxLg",
      "contentDetails": { "relatedPlaylists": { "likes": "", "uploads": "UUSMOQeBJ2RAnuFungnQOxLg" } } },
    { "kind": "youtube#channel", "etag": "bmFzYQ", "id": "UCLA_DiR1FfKNvjuUpBHmylQ",
      "contentDetails": { "relatedPlaylists": { "likes": "", "uploads": "UULA_DiR1FfKNvjuUpBHmylQ" } } }
  ]
}
```

`playlist-items-uploads-kurzgesagt.json`:

```json
{
  "kind": "youtube#playlistItemListResponse",
  "etag": "cGxheWxpc3QtaXRlbXMta3Vyeg",
  "nextPageToken": "EAAaBlBUOkNBVQ",
  "items": [
    {
      "kind": "youtube#playlistItem",
      "etag": "aXRlbS1rdXJ6LTE",
      "id": "VVVzWFZrMzdibHRIeEQxckRQd3ROTThRLkt6MWFUNW5NM3BR",
      "snippet": {
        "publishedAt": "2026-09-15T14:00:12Z",
        "channelId": "UCsXVk37bltHxD1rDPwtNM8Q",
        "title": "What If the Moon Turned Into a Black Hole?",
        "description": "The Moon is our constant companion…",
        "thumbnails": {
          "default": { "url": "https://i.ytimg.com/vi/Kz1aT5nM3pQ/default.jpg", "width": 120, "height": 90 },
          "medium": { "url": "https://i.ytimg.com/vi/Kz1aT5nM3pQ/mqdefault.jpg", "width": 320, "height": 180 },
          "high": { "url": "https://i.ytimg.com/vi/Kz1aT5nM3pQ/hqdefault.jpg", "width": 480, "height": 360 }
        },
        "channelTitle": "Kurzgesagt – In a Nutshell",
        "playlistId": "UUsXVk37bltHxD1rDPwtNM8Q",
        "position": 0,
        "resourceId": { "kind": "youtube#video", "videoId": "Kz1aT5nM3pQ" },
        "videoOwnerChannelTitle": "Kurzgesagt – In a Nutshell",
        "videoOwnerChannelId": "UCsXVk37bltHxD1rDPwtNM8Q"
      },
      "contentDetails": { "videoId": "Kz1aT5nM3pQ", "videoPublishedAt": "2026-09-15T14:00:12Z" }
    },
    {
      "kind": "youtube#playlistItem",
      "etag": "aXRlbS1rdXJ6LTI",
      "id": "VVVzWFZrMzdibHRIeEQxckRQd3ROTThRLkhoN0xxMld2OXNF",
      "snippet": {
        "publishedAt": "2026-09-01T14:00:00Z",
        "channelId": "UCsXVk37bltHxD1rDPwtNM8Q",
        "title": "The Largest Star in the Universe",
        "description": "",
        "thumbnails": { "medium": { "url": "https://i.ytimg.com/vi/Hh7Lq2Wv9sE/mqdefault.jpg", "width": 320, "height": 180 } },
        "channelTitle": "Kurzgesagt – In a Nutshell",
        "playlistId": "UUsXVk37bltHxD1rDPwtNM8Q",
        "position": 1,
        "resourceId": { "kind": "youtube#video", "videoId": "Hh7Lq2Wv9sE" },
        "videoOwnerChannelTitle": "Kurzgesagt – In a Nutshell",
        "videoOwnerChannelId": "UCsXVk37bltHxD1rDPwtNM8Q"
      },
      "contentDetails": { "videoId": "Hh7Lq2Wv9sE", "videoPublishedAt": "2026-09-01T14:00:00Z" }
    }
  ],
  "pageInfo": { "totalResults": 214, "resultsPerPage": 2 }
}
```

`playlist-items-uploads-blender.json` — same envelope (`playlistId` `UUSMOQeBJ2RAnuFungnQOxLg`, `totalResults` 2) with two items: (1) videoId `aqz-KE-bpKQ`, title `Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film`, owner `Blender` / `UCSMOQeBJ2RAnuFungnQOxLg`, `videoPublishedAt` `2026-09-10T12:00:00Z`, `snippet.publishedAt` `2026-09-10T12:05:00Z`, position 0; (2) an unavailable entry: `snippet.title` `Private video`, `snippet.description` `This video is private.`, `snippet.thumbnails` `{}`, no `videoOwnerChannelTitle`/`videoOwnerChannelId`, `resourceId.videoId` `Rt4Yb8Nc1xZ`, `contentDetails` `{"videoId":"Rt4Yb8Nc1xZ"}` (no `videoPublishedAt`), position 1.

`playlist-items-uploads-nasa.json` — one item: videoId `Pm6Jd3Fg0kU`, title `Artemis II: Crew Walkout & Launch`, owner `NASA` / `UCLA_DiR1FfKNvjuUpBHmylQ`, `videoPublishedAt` `2026-09-12T09:30:00Z`, `totalResults` 1, no `nextPageToken`.

`videos-by-id.json`:

```json
{
  "kind": "youtube#videoListResponse",
  "etag": "dmlkZW9zLWJ5LWlk",
  "items": [
    {
      "kind": "youtube#video",
      "etag": "dmlkZW8td3E5",
      "id": "Wq9Ze2Lr5tA",
      "snippet": {
        "publishedAt": "2026-08-30T16:45:00Z",
        "channelId": "UCSMOQeBJ2RAnuFungnQOxLg",
        "title": "Blender 5.0 Reveal",
        "description": "",
        "thumbnails": { "medium": { "url": "https://i.ytimg.com/vi/Wq9Ze2Lr5tA/mqdefault.jpg", "width": 320, "height": 180 } },
        "channelTitle": "Blender",
        "categoryId": "28",
        "liveBroadcastContent": "none"
      }
    }
  ],
  "pageInfo": { "totalResults": 1, "resultsPerPage": 1 }
}
```

`error-quota-exceeded.json` (403):

```json
{
  "error": {
    "code": 403,
    "message": "The request cannot be completed because you have exceeded your <a href=\"/youtube/v3/getting-started#quota\">quota</a>.",
    "errors": [
      {
        "message": "The request cannot be completed because you have exceeded your <a href=\"/youtube/v3/getting-started#quota\">quota</a>.",
        "domain": "youtube.quota",
        "reason": "quotaExceeded"
      }
    ]
  }
}
```

`error-api-not-enabled.json` (403):

```json
{
  "error": {
    "code": 403,
    "message": "YouTube Data API v3 has not been used in project 123456789012 before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/youtube.googleapis.com/overview?project=123456789012 then retry.",
    "errors": [
      {
        "message": "YouTube Data API v3 has not been used in project 123456789012 before or it is disabled.",
        "domain": "usageLimits",
        "reason": "accessNotConfigured",
        "extendedHelp": "https://console.developers.google.com"
      }
    ],
    "status": "PERMISSION_DENIED",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "SERVICE_DISABLED",
        "domain": "googleapis.com",
        "metadata": { "service": "youtube.googleapis.com", "consumer": "projects/123456789012" }
      }
    ]
  }
}
```

`error-playlist-not-found.json` (404):

```json
{
  "error": {
    "code": 404,
    "message": "The playlist identified with the request's <code>playlistId</code> parameter cannot be found.",
    "errors": [
      {
        "message": "The playlist identified with the request's <code>playlistId</code> parameter cannot be found.",
        "domain": "youtube.playlistItem",
        "reason": "playlistNotFound",
        "location": "playlistId",
        "locationType": "parameter"
      }
    ]
  }
}
```

`error-unauthorized.json` (401):

```json
{
  "error": {
    "code": 401,
    "message": "Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential.",
    "errors": [
      { "message": "Invalid Credentials", "domain": "global", "reason": "authError", "location": "Authorization", "locationType": "header" }
    ],
    "status": "UNAUTHENTICATED"
  }
}
```

Add to `FakeGoogleServer` a scripting helper used by this and later tasks:

```java
    /** Channel, three subscriptions on two pages, their uploads playlists and their newest videos. */
    public FakeGoogleServer youtubeLibrary() {
        respondWhen("GET", "/youtube/v3/channels", r -> "true".equals(r.query().get("mine")),
                Canned.fixture(200, "channels-mine.json"));
        respondWhen("GET", "/youtube/v3/channels", r -> "contentDetails".equals(r.query().get("part")),
                Canned.fixture(200, "channels-uploads.json"));
        respondWhen("GET", "/youtube/v3/subscriptions", r -> !r.query().containsKey("pageToken"),
                Canned.fixture(200, "subscriptions-page-1.json"));
        respondWhen("GET", "/youtube/v3/subscriptions", r -> "CAIQAA".equals(r.query().get("pageToken")),
                Canned.fixture(200, "subscriptions-page-2.json"));
        playlist("UUsXVk37bltHxD1rDPwtNM8Q", "playlist-items-uploads-kurzgesagt.json");
        playlist("UUSMOQeBJ2RAnuFungnQOxLg", "playlist-items-uploads-blender.json");
        playlist("UULA_DiR1FfKNvjuUpBHmylQ", "playlist-items-uploads-nasa.json");
        respond("GET", "/youtube/v3/videos", Canned.fixture(200, "videos-by-id.json"));
        return this;
    }

    public FakeGoogleServer playlist(String playlistId, String fixture) {
        return respondWhen("GET", "/youtube/v3/playlistItems", r -> playlistId.equals(r.query().get("playlistId")),
                Canned.fixture(200, fixture));
    }

    /** A 1×1 JPEG-looking body for any thumbnail. */
    public FakeGoogleServer thumbnails() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, (byte) 0xFF, (byte) 0xD9};
        return respondWhen("GET", "/thumbs/vi/aqz-KE-bpKQ/mqdefault.jpg", r -> true, new Canned(200, "image/jpeg", jpeg));
    }
```

(`respondWhen` matches exact paths, so the thumbnail rule is per video id; tests needing another id add their own rule.)

- [ ] **Step 2: Write the failing tests**

`QuotaLedgerTest` (temp dir, `MutableClock` at `2026-09-16T10:00:00Z` with zone `Europe/Berlin`):
- `chargesDocumentedUnits`: charge `SUBSCRIPTIONS_LIST`, `CHANNELS_LIST`, `PLAYLIST_ITEMS_LIST` ×3, `SEARCH_LIST` → `usage()` units 105, searches 1, calls `{subscriptions.list=1, channels.list=1, playlistItems.list=3, search.list=1}`.
- `persistsAndReloads`: after charges, a new ledger on the same file reports the same usage; the file equals the normative JSON shape (assert with a JSON tree comparison of `version`, `day`, `units`, `searches`, `calls`).
- `theDayIsPacific`: clock at `2026-09-17T06:59:00Z` (still 16 Sep 23:59 in Los Angeles) → day `2026-09-16`, usage kept; advance 1 min → day `2026-09-17`, units 0.
- `resetsAtMidnightPacificInLocalTime`: at `2026-09-16T10:00:00Z` → `resetsAt` = `2026-09-17T09:00+02:00[Europe/Berlin]`; `resetPhrase` = `after midnight Pacific time (09:00 here)`.
- `refusesBeyondTheBudget`: `dailyUnits` 3 → three list charges pass; the fourth throws `QUOTA_EXHAUSTED` with message `YouTube's daily API quota is used up (3 of 3 units). Rails refresh again after midnight Pacific time (09:00 here).` and units stay 3.
- `searchesHaveTheirOwnCap`: `searchesPerDay` 2 → third `SEARCH_LIST` throws `SEARCH_LIMIT` `You have used today's 2 YouTube searches. More after midnight Pacific time (09:00 here).`; a search that would exceed the unit budget throws `QUOTA_EXHAUSTED`.
- `markExhaustedFillsTheDay`: → units = daily, `exhausted()` true, next charge refused.
- `aCorruptFileIsSetAsideNotFatal`: file content `{nope` → ledger starts at 0; a sibling `youtube-quota.json.corrupt-<n>` exists.
- `anOldDayStartsFresh`: file with `"day":"2026-09-15","units":9000` → usage units 0 for `2026-09-16`.

`YouTubeApiClientTest` (fake + real `QuotaLedger` in temp dir + mock `GoogleTokens` returning `ya29.first` then `ya29.second`):
- `sendsBearerAndQueryInOrder`: `get(SUBSCRIPTIONS_LIST, "subscriptions", LinkedHashMap{part=snippet, mine=true, maxResults=50})` → recorded path `/youtube/v3/subscriptions`, raw query `part=snippet&mine=true&maxResults=50`, header `authorization` `Bearer ya29.first`; ledger units 1.
- `retriesOnceAfterA401`: 401 `error-unauthorized.json` then 200 → result from the second answer; `verify(tokens).invalidate()`; second request carries `Bearer ya29.second`; ledger units 1.
- `aSecond401IsUnauthorized`.
- `quotaExceededMarksTheLedger`: 403 `error-quota-exceeded.json` → `QUOTA_EXHAUSTED`; `ledger.usage().exhausted()`.
- `apiNotEnabledIsExplained`: 403 `error-api-not-enabled.json` → `FORBIDDEN` with message containing `not enabled in your Google Cloud project`.
- `notFoundCarriesTheReason`: 404 `error-playlist-not-found.json` → `NOT_FOUND`, `reason()` `playlistNotFound`.
- `serverErrors`: 503 plain text → `SERVER_ERROR` `YouTube is having problems (HTTP 503)`.
- `noQuotaNoRequest`: ledger with budget 0 → `QUOTA_EXHAUSTED` and zero recorded requests.
- `messagesNeverContainTheToken`: for every failure above, `getMessage()` contains no `ya29`.

`YouTubeVideoMapperTest` (fixtures read with a `JsonMapper`):
- `mapsUploads`: kurzgesagt fixture → two videos, first `YouTubeVideo("Kz1aT5nM3pQ", "What If the Moon Turned Into a Black Hole?", "Kurzgesagt – In a Nutshell", 2026-09-15T14:00:12Z)`.
- `skipsUnavailableVideos`: blender fixture → only `aqz-KE-bpKQ`, published `2026-09-10T12:00:00Z` (from `videoPublishedAt`, not `snippet.publishedAt`).
- `rejectsBadIds`: an item whose `videoId` is `short` → empty.
- `toItemBuildsTheAppLinkAndThumbnail`: `toItem()` of `aqz-KE-bpKQ` → source `youtube`, kind `VIDEO`, subtitle `Blender`, artwork `/sources/youtube/thumbnails/aqz-KE-bpKQ`, playables exactly `[AppLink(https://www.youtube.com/watch?v=aqz-KE-bpKQ, "youtube")]`, progress null.
- `mapsAVideoResource`: `videos-by-id.json` → `Wq9Ze2Lr5tA`, `Blender 5.0 Reveal`, `Blender`, `2026-08-30T16:45:00Z`.

`SubscriptionsFeedTest` (fake with `oauthApproves().youtubeLibrary()`, `GoogleTokens` mock returning `ya29.t`, real ledger, `MutableClock`):
- `buildsTheRailNewestFirst`: `refresh()` ids = `[Kz1aT5nM3pQ, Pm6Jd3Fg0kU, aqz-KE-bpKQ, Hh7Lq2Wv9sE]`; requests: 2 × `/subscriptions` (second with `pageToken=CAIQAA`), 1 × `/channels` with `id=UCsXVk37bltHxD1rDPwtNM8Q,UCSMOQeBJ2RAnuFungnQOxLg,UCLA_DiR1FfKNvjuUpBHmylQ`, 3 × `/playlistItems` with `maxResults=5` and `part=snippet,contentDetails`; ledger units 6.
- `respectsTheMinimumSpacing`: second `refresh()` 10 min later → no new requests, same list; 15 min later → 3 more `/playlistItems` and no `/subscriptions`.
- `pollsLeastRecentlyPolledChannelsWithinTheBudget`: properties with `channelsPerRefresh` 2 → first refresh polls Kurzgesagt and Blender; after 15 min polls NASA and Kurzgesagt (the older of the two polled ones by subscription-order tie-break); the rail after the second refresh includes NASA's video.
- `refreshesTheSubscriptionListDaily`: advance 24 h → `/subscriptions` requested again; a channel absent from the new list (script page 1 without Blender and no page 2) disappears from the rail.
- `aMissingUploadsPlaylistIsRemembered`: channel answer without NASA → NASA never polled; next day's refresh still does not call `/channels` for it again within the same subscription set (only new channels are looked up).
- `aChannelWithoutUploadsIsEmptyNotAnError`: NASA's playlist answers 404 `playlistNotFound` → rail has the other videos, no exception.
- `quotaMidwayKeepsWhatItHas`: budget 4 → `refresh()` returns Kurzgesagt's two videos only (subscriptions 2 + channels 1 + one playlist = 4 units; Blender's playlist is refused locally, NASA is not attempted) without throwing; exactly 4 requests recorded.
- `quotaBeforeAnythingFails`: budget 0 → `QUOTA_EXHAUSTED`.
- `revokedAuthorizationFails`: tokens mock throws `REVOKED` → rethrown.
- `anApiThatIsNotEnabledFails`: `/subscriptions` answers `error-api-not-enabled.json` → `FORBIDDEN`.

`YouTubeContentSourceTest` (mocks for `YouTubeSetupService`, `SubscriptionsFeed`; fake for `videos`):
- `unavailableWithoutConnection`: `connected()` false → `available()` false, `rails()` empty.
- `oneRailWhenConnected`: `rails()` = `[RailDescriptor("youtube","subscriptions","New from your subscriptions")]`; `displayName()` `YouTube`; `defaultRefreshInterval()` 60 min.
- `railReturnsItemsAndRemembersThem`: feed returns two videos → `rail("subscriptions")` items in order, `fetchedAt` = clock instant; `item("Kz1aT5nM3pQ")` answered from `KnownVideos` without any request.
- `unknownItemsCostOneVideosCall`: `item("Wq9Ze2Lr5tA")` → one `/videos` request with `part=snippet&id=Wq9Ze2Lr5tA`, ledger units 1; `item("bad")` → empty, no request; `/videos` answering `{"items":[]}` → empty.
- `unknownRailIsIllegal`: `rail("nope")` → `IllegalArgumentException`.
- `feedFailuresAreContentSourceExceptions`: feed throws `YouTubeException(QUOTA_EXHAUSTED, …)` → `rail` throws it unchanged (it is a `ContentSourceException`).

`YouTubeThumbnailControllerTest` — `@WebMvcTest(YouTubeThumbnailController.class)` with `@MockitoBean YouTubeHttp http` and a `@TestConfiguration` providing `YouTubeProperties` whose `thumbnailBaseUrl` is `http://thumbs.test`:
- `proxiesTheMediumThumbnail`: `http.get(URI("http://thumbs.test/vi/aqz-KE-bpKQ/mqdefault.jpg"), Map.of())` returns 200 JPEG bytes → 200, `Content-Type: image/jpeg`, `Cache-Control: private, max-age=86400`, same bytes; no `Authorization` header passed.
- `rejectsInvalidIds`: `/sources/youtube/thumbnails/..%2F` and `/sources/youtube/thumbnails/abc` → 400, `http` never called.
- `missingIs404AndFailuresAre502`: upstream 404 → 404; `YouTubeException(UNREACHABLE)` → 502 text `Could not load the thumbnail`.

`YouTubeSetupServiceTest` add:
- `checkNamesTheChannelAndQuota`: `YouTubeAccount.refreshChannel()` returns `Andre at Home`; ledger usage 212 of 10000 → `check()` = `Connected as Andre at Home. 212 of 10000 quota units used today.`
(Give `YouTubeSetupService` a setter-free way to reach these: add constructor parameters `ObjectProvider<YouTubeAccount> account, QuotaLedger ledger` — update the Task 1 bean method and tests accordingly; a missing account bean keeps Task 1's text.)

`YouTubeAccount` test inside `YouTubeSetupServiceTest`: `refreshChannelStoresTitleAndId`: `channels-mine.json` → settings `channelId` `UC4fixtureHomeControl00a`, `channelTitle` `Andre at Home`, one `CHANNELS_LIST` charge.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*'`
Expected: compilation failure — `QuotaLedger`, `YouTubeApiClient`, `SubscriptionsFeed` and the others do not exist.

- [ ] **Step 4: Implement the ledger and the API client**

`sources/youtube/QuotaLedger.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** YouTube Data API units per Pacific-time day, charged before each call and kept in /data/youtube-quota.json. */
public class QuotaLedger {

    private static final Logger log = LoggerFactory.getLogger(QuotaLedger.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    public static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    public enum Call {
        SUBSCRIPTIONS_LIST("subscriptions.list", 1),
        CHANNELS_LIST("channels.list", 1),
        PLAYLIST_ITEMS_LIST("playlistItems.list", 1),
        PLAYLISTS_LIST("playlists.list", 1),
        VIDEOS_LIST("videos.list", 1),
        SEARCH_LIST("search.list", 100);

        private final String apiName;
        private final int units;

        Call(String apiName, int units) {
            this.apiName = apiName;
            this.units = units;
        }

        public String apiName() {
            return apiName;
        }

        public int units() {
            return units;
        }
    }

    public record Usage(LocalDate day, int units, int dailyUnits, int searches, int searchesPerDay,
                        Map<String, Integer> calls, ZonedDateTime resetsAt) {
        public int searchesLeft() {
            return Math.max(0, searchesPerDay - searches);
        }

        public boolean exhausted() {
            return units >= dailyUnits;
        }
    }

    private final Path file;
    private final Clock clock;
    private final int dailyUnits;
    private final int searchesPerDay;
    private LocalDate day;
    private int units;
    private int searches;
    private final Map<String, Integer> calls = new LinkedHashMap<>();

    public QuotaLedger(Path file, Clock clock, int dailyUnits, int searchesPerDay) {
        this.file = file;
        this.clock = clock;
        this.dailyUnits = dailyUnits;
        this.searchesPerDay = searchesPerDay;
        this.day = today();
        load();
    }

    public synchronized void charge(Call call) {
        roll();
        if (call == Call.SEARCH_LIST && searches >= searchesPerDay) {
            throw new YouTubeException(YouTubeException.Kind.SEARCH_LIMIT, "You have used today's " + searchesPerDay
                    + " YouTube searches. More " + resetPhrase(resetsAt()) + ".");
        }
        if (units + call.units() > dailyUnits) {
            throw exhaustedException();
        }
        units += call.units();
        if (call == Call.SEARCH_LIST) {
            searches++;
        }
        calls.merge(call.apiName(), 1, Integer::sum);
        write();
    }

    public synchronized void markExhausted() {
        roll();
        units = Math.max(units, dailyUnits);
        write();
    }

    public synchronized Usage usage() {
        roll();
        return new Usage(day, units, dailyUnits, searches, searchesPerDay,
                Collections.unmodifiableMap(new LinkedHashMap<>(calls)), resetsAt());
    }

    YouTubeException exhaustedException() {
        return new YouTubeException(YouTubeException.Kind.QUOTA_EXHAUSTED, "YouTube's daily API quota is used up ("
                + Math.min(units, dailyUnits) + " of " + dailyUnits + " units). Rails refresh again "
                + resetPhrase(resetsAt()) + ".", "quotaExceeded");
    }

    public static String resetPhrase(ZonedDateTime resetsAt) {
        return "after midnight Pacific time (" + resetsAt.format(DateTimeFormatter.ofPattern("HH:mm")) + " here)";
    }

    private ZonedDateTime resetsAt() {
        return day.plusDays(1).atStartOfDay(PACIFIC).withZoneSameInstant(clock.getZone());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(PACIFIC));
    }

    private void roll() {
        LocalDate now = today();
        if (!now.equals(day)) {
            day = now;
            units = 0;
            searches = 0;
            calls.clear();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
            if (root.path("version").asInt(0) != 1 || root.path("day").asString("").isBlank()) {
                throw new IllegalStateException("unexpected shape");
            }
            if (!LocalDate.parse(root.path("day").asString("")).equals(day)) {
                return;
            }
            units = root.path("units").asInt(0);
            searches = root.path("searches").asInt(0);
            root.path("calls").properties().forEach(entry -> calls.put(entry.getKey(), entry.getValue().asInt(0)));
        } catch (IOException | RuntimeException e) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + clock.instant().getEpochSecond());
            log.warn("YouTube quota file {} is unreadable ({}); moved to {} and counting from zero", file, e.getMessage(), aside);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                log.warn("Could not move {} aside: {}", file, moveFailed.getMessage());
            }
            units = 0;
            searches = 0;
            calls.clear();
        }
    }

    private void write() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("day", day.toString());
        root.put("units", units);
        root.put("searches", searches);
        ObjectNode callsNode = root.putObject("calls");
        calls.forEach(callsNode::put);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".youtube-quota-", ".tmp");
            Files.write(temp, MAPPER.writeValueAsBytes(root));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
```

(If Jackson 3's `JsonNode.properties()` is named differently in the version on the classpath, use the equivalent entry iteration, e.g. `propertyStream()`; keep the behaviour.)

`sources/youtube/YouTubeApiClient.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

/** YouTube Data API v3 reads: quota first, bearer token, one retry after a 401, errors mapped to user-facing text. */
public class YouTubeApiClient {

    private final YouTubeHttp http;
    private final URI base;
    private final GoogleTokens tokens;
    private final QuotaLedger ledger;

    public YouTubeApiClient(YouTubeHttp http, URI apiBaseUrl, GoogleTokens tokens, QuotaLedger ledger) {
        this.http = http;
        this.base = apiBaseUrl;
        this.tokens = tokens;
        this.ledger = ledger;
    }

    public JsonNode get(QuotaLedger.Call call, String resource, Map<String, String> query) {
        ledger.charge(call);
        URI uri = YouTubeHttp.uri(base, "/" + resource, query);
        YouTubeHttp.Response response = send(uri);
        if (response.status() == 401) {
            tokens.invalidate();
            response = send(uri);
            if (response.status() == 401) {
                throw new YouTubeException(YouTubeException.Kind.UNAUTHORIZED,
                        "Google rejected the YouTube authorization; reconnect YouTube on the setup page", "authError");
            }
        }
        if (response.ok()) {
            return response.json();
        }
        throw failure(response);
    }

    private YouTubeHttp.Response send(URI uri) {
        return http.get(uri, Map.of("Authorization", "Bearer " + tokens.accessToken(), "Accept", "application/json"));
    }

    private YouTubeException failure(YouTubeHttp.Response response) {
        int status = response.status();
        JsonNode error = errorBody(response);
        String reason = error.path("errors").path(0).path("reason").asString("");
        if (status == 403) {
            if (reason.equals("quotaExceeded") || reason.equals("dailyLimitExceeded")) {
                ledger.markExhausted();
                return ledger.exhaustedException();
            }
            String message = error.path("message").asString("");
            if (reason.equals("accessNotConfigured") || ("PERMISSION_DENIED".equals(error.path("status").asString(""))
                    && (message.contains("has not been used") || message.contains("is disabled")))) {
                return new YouTubeException(YouTubeException.Kind.FORBIDDEN, "The YouTube Data API v3 is not enabled"
                        + " in your Google Cloud project. Enable it, wait a few minutes and try again.", reason);
            }
            if (reason.equals("insufficientPermissions")) {
                return new YouTubeException(YouTubeException.Kind.FORBIDDEN,
                        "The saved authorization does not include read access to YouTube; reconnect YouTube.", reason);
            }
            return new YouTubeException(YouTubeException.Kind.FORBIDDEN,
                    "YouTube refused the request (" + (reason.isBlank() ? "HTTP 403" : reason) + ")", reason);
        }
        if (status == 404) {
            return new YouTubeException(YouTubeException.Kind.NOT_FOUND,
                    "YouTube could not find it (" + (reason.isBlank() ? "HTTP 404" : reason) + ")", reason);
        }
        if (status >= 500) {
            return new YouTubeException(YouTubeException.Kind.SERVER_ERROR, "YouTube is having problems (HTTP " + status + ")");
        }
        return new YouTubeException(YouTubeException.Kind.BAD_RESPONSE, "YouTube answered HTTP " + status, reason);
    }

    private static JsonNode errorBody(YouTubeHttp.Response response) {
        try {
            return response.json().path("error");
        } catch (YouTubeException notJson) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
    }
}
```

- [ ] **Step 5: Implement mapping, known videos, account and feed**

`sources/youtube/YouTubeVideo.java` — the record, `validId` = `id != null && id.matches("[A-Za-z0-9_-]{11}")`, `watchUrl(id)` = `URI.create("https://www.youtube.com/watch?v=" + id)`, `toItem()` per the normative mapping.

`sources/youtube/YouTubeVideoMapper.java` — implements the normative mapping (a private `instant(String)` returning `Instant.EPOCH` on blank or `DateTimeParseException`; `playlistItems(response)` maps `response.path("items")` in order, dropping empties).

`sources/youtube/KnownVideos.java` — `LinkedHashMap` with `accessOrder=true` and `removeEldestEntry` beyond `capacity`, all methods `synchronized`.

`sources/youtube/YouTubeAccount.java` — `refreshChannel()`: `api.get(CHANNELS_LIST, "channels", LinkedHashMap{part=snippet, mine=true})`; first item's `id` and `snippet.title` (missing → `BAD_RESPONSE` `YouTube did not return your channel`); `setup.save(setup.settings().withConnection(connectedAtOrNow, id, title))`; returns title.

`sources/youtube/SubscriptionsFeed.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** "New from your subscriptions": subscriptions → uploads playlists → newest videos, inside a per-refresh budget. */
public class SubscriptionsFeed {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsFeed.class);
    private static final Set<YouTubeException.Kind> FATAL = Set.of(YouTubeException.Kind.REVOKED,
            YouTubeException.Kind.UNAUTHORIZED, YouTubeException.Kind.FORBIDDEN, YouTubeException.Kind.NOT_CONFIGURED);

    private record Polled(List<YouTubeVideo> videos, Instant at) {
    }

    private final YouTubeApiClient api;
    private final YouTubeProperties properties;
    private final Clock clock;

    private LinkedHashMap<String, String> subscriptions;
    private Instant subscriptionsFetchedAt;
    private final Map<String, String> uploads = new HashMap<>();
    private final Map<String, Polled> polled = new HashMap<>();
    private List<YouTubeVideo> lastResult;
    private Instant lastRefreshAt;

    public SubscriptionsFeed(YouTubeApiClient api, YouTubeProperties properties, Clock clock) {
        this.api = api;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized List<YouTubeVideo> refresh() {
        Instant now = clock.instant();
        if (lastResult != null && now.isBefore(lastRefreshAt.plus(properties.minRefreshSpacing()))) {
            return lastResult;
        }
        try {
            if (subscriptions == null || !now.isBefore(subscriptionsFetchedAt.plus(properties.subscriptionsRefresh()))) {
                loadSubscriptions(now);
            }
            pollChannels(now);
        } catch (YouTubeException e) {
            if (e.kind() != YouTubeException.Kind.QUOTA_EXHAUSTED || polled.isEmpty()) {
                throw e;
            }
        }
        lastResult = merge();
        lastRefreshAt = now;
        return lastResult;
    }

    public synchronized void clear() {
        subscriptions = null;
        subscriptionsFetchedAt = null;
        uploads.clear();
        polled.clear();
        lastResult = null;
        lastRefreshAt = null;
    }

    private void loadSubscriptions(Instant now) {
        LinkedHashMap<String, String> fresh = new LinkedHashMap<>();
        String pageToken = null;
        for (int page = 0; page < properties.maxSubscriptionPages(); page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "snippet");
            query.put("mine", "true");
            query.put("maxResults", "50");
            query.put("pageToken", pageToken);
            JsonNode response = api.get(QuotaLedger.Call.SUBSCRIPTIONS_LIST, "subscriptions", query);
            for (JsonNode item : response.path("items")) {
                String channelId = item.path("snippet").path("resourceId").path("channelId").asString("");
                if (!channelId.isBlank()) {
                    fresh.put(channelId, item.path("snippet").path("title").asString(""));
                }
            }
            pageToken = response.path("nextPageToken").asString("");
            if (pageToken.isBlank()) {
                break;
            }
        }
        subscriptions = fresh;
        subscriptionsFetchedAt = now;
        uploads.keySet().retainAll(fresh.keySet());
        polled.keySet().retainAll(fresh.keySet());
        List<String> unknown = fresh.keySet().stream().filter(id -> !uploads.containsKey(id)).toList();
        for (int from = 0; from < unknown.size(); from += 50) {
            List<String> batch = unknown.subList(from, Math.min(unknown.size(), from + 50));
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "contentDetails");
            query.put("id", String.join(",", batch));
            query.put("maxResults", "50");
            JsonNode response = api.get(QuotaLedger.Call.CHANNELS_LIST, "channels", query);
            for (String id : batch) {
                uploads.put(id, "");
            }
            for (JsonNode channel : response.path("items")) {
                String id = channel.path("id").asString("");
                if (batch.contains(id)) {
                    uploads.put(id, channel.path("contentDetails").path("relatedPlaylists").path("uploads").asString(""));
                }
            }
        }
    }

    private void pollChannels(Instant now) {
        List<String> order = new ArrayList<>(subscriptions.keySet());
        List<String> candidates = order.stream()
                .filter(id -> !uploads.getOrDefault(id, "").isBlank())
                .sorted(Comparator.comparing((String id) -> polled.containsKey(id) ? polled.get(id).at() : Instant.MIN)
                        .thenComparing(order::indexOf))
                .limit(properties.channelsPerRefresh())
                .toList();
        for (String channelId : candidates) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "snippet,contentDetails");
            query.put("playlistId", uploads.get(channelId));
            query.put("maxResults", String.valueOf(properties.videosPerChannel()));
            try {
                JsonNode response = api.get(QuotaLedger.Call.PLAYLIST_ITEMS_LIST, "playlistItems", query);
                polled.put(channelId, new Polled(YouTubeVideoMapper.playlistItems(response), now));
            } catch (YouTubeException e) {
                if (e.kind() == YouTubeException.Kind.NOT_FOUND) {
                    polled.put(channelId, new Polled(List.of(), now));
                } else if (e.kind() == YouTubeException.Kind.QUOTA_EXHAUSTED || FATAL.contains(e.kind())) {
                    throw e;
                } else {
                    log.debug("Skipping channel {} this time: {}", channelId, e.getMessage());
                }
            }
        }
    }

    private List<YouTubeVideo> merge() {
        Map<String, YouTubeVideo> unique = new LinkedHashMap<>();
        polled.values().forEach(p -> p.videos().forEach(v -> unique.putIfAbsent(v.id(), v)));
        return unique.values().stream()
                .sorted(Comparator.comparing(YouTubeVideo::publishedAt).reversed().thenComparing(YouTubeVideo::id))
                .limit(properties.railSize())
                .toList();
    }
}
```

- [ ] **Step 6: Implement the content source, thumbnails and setup additions**

`sources/youtube/YouTubeContentSource.java` — per **Interfaces**:
- `static final String SUBSCRIPTIONS = "subscriptions"`.
- `available()` = `setup.connected()` (no I/O). `rails()` = `available() ? List.of(new RailDescriptor("youtube", SUBSCRIPTIONS, "New from your subscriptions")) : List.of()`.
- `rail(railId)`: `SUBSCRIPTIONS` → `videos = feed.refresh()`, `known.remember(videos)`, `new Rail(descriptor, videos.stream().map(YouTubeVideo::toItem).toList(), clock.instant())`; any other id → `IllegalArgumentException("YouTube has no rail " + railId)`.
- `item(itemId)`: invalid id → `Optional.empty()`; `known.find` → `toItem`; else `api.get(VIDEOS_LIST, "videos", LinkedHashMap{part=snippet, id=itemId})` → `items[0]` through `YouTubeVideoMapper.fromVideo`, remembered; `NOT_FOUND` → empty.
- `defaultRefreshInterval()` = `properties.refreshInterval()`.
- `forgetAccount()` → `feed.clear()` (Task 3 also clears playlist memos).

`sources/youtube/YouTubeThumbnailController.java` — `@RestController` with the module condition; `@GetMapping("/sources/youtube/thumbnails/{videoId}")`; invalid id → 400 text `Not a YouTube video id`; `http.get(YouTubeHttp.uri(properties.thumbnailBaseUrl(), "/vi/" + videoId + "/mqdefault.jpg", Map.of()), Map.of())`; 200 → bytes with `MediaType.IMAGE_JPEG` and `CacheControl.maxAge(Duration.ofDays(1)).cachePrivate()`; 404 → 404; other status or `YouTubeException` → 502 text `Could not load the thumbnail`.

`YouTubeConfiguration` — add beans: `QuotaLedger youTubeQuotaLedger(AndroidTvProperties storage, YouTubeProperties p, Clock clock)` → `new QuotaLedger(storage.dataDir().resolve("youtube-quota.json"), clock, p.dailyQuotaUnits(), p.searchesPerDay())`; `YouTubeApiClient`; `KnownVideos(1000)`; `YouTubeAccount`; `SubscriptionsFeed`; `YouTubeContentSource` (a `ContentSource` bean, so C's `ContentSources` and D's `RailCache` pick it up); and in the `YouTubeAuthorizationService` bean method no change — instead register the post-connect hook in a bean method `@Bean ApplicationRunner youTubeConnectHook(YouTubeAuthorizationService authorization, YouTubeAccount account, YouTubeContentSource source)` that calls `authorization.onConnected(() -> { source.forgetAccount(); account.refreshChannel(); })`. `YouTubeSetupService.disconnect()` also calls `source.forgetAccount()` — pass `ObjectProvider<YouTubeContentSource>` to avoid a cycle.

`YouTubeSetupAdvice.View` — append `QuotaView quota` built from `ledger.usage()`: `resets` = `QuotaLedger.resetPhrase(usage.resetsAt())`, `calls` in the ledger's insertion order with `units = count × Call.units()` (look the call up by `apiName`).

`fragments/youtube-setup.html` — when `hasClient()`, add under the connection status:

```html
<p class="hint" th:with="q=${youtube.quota()}">
  Quota today: <strong th:text="|${q.units()} of ${q.dailyUnits()} units|">212 of 10000 units</strong>,
  <span th:text="|${q.searches()} of ${q.searchesPerDay()} searches|">1 of 20 searches</span>;
  resets <span th:text="${q.resets()}">after midnight Pacific time (09:00 here)</span>.
</p>
<ul class="hint quota-calls" th:if="${!#lists.isEmpty(youtube.quota().calls())}">
  <li th:each="c : ${youtube.quota().calls()}" th:text="|${c.api()}: ${c.count()} calls, ${c.units()} units|"></li>
</ul>
```

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/youtube src/main/resources/templates/fragments/youtube-setup.html \
  src/test/java/dev/andre/homecontrol/sources/youtube src/test/resources/fixtures/youtube
git commit -m "feat: YouTube subscriptions rail with quota accounting"
```

---

### Task 3: E3 · Watch Later and playlists rails

**Files:**
- Create: `sources/youtube/YouTubePlaylists.java`
- Modify: `sources/youtube/YouTubeContentSource.java`, `YouTubeSetupService.java`, `YouTubeSetupController.java`, `YouTubeSetupAdvice.java`, `YouTubeConfiguration.java`, `src/main/resources/templates/fragments/youtube-setup.html`
- Test: `sources/youtube/YouTubePlaylistsTest.java`, `YouTubeContentSourceTest.java`, `YouTubeSetupServiceTest.java`, `YouTubeSetupControllerTest.java`; fixtures `playlists-mine.json`, `playlist-items-playlist.json`, `playlist-items-empty.json`

**Interfaces:**
- Consumes: `YouTubeApiClient.get`, `QuotaLedger.Call.PLAYLISTS_LIST/PLAYLIST_ITEMS_LIST`, `YouTubeVideoMapper.playlistItems`, `YouTubeVideo`, `KnownVideos`, `YouTubeSettings.watchLater/playlists/withWatchLater/withPlaylists`, `YouTubeSetupService.settings/save`, `YouTubeContentSource` (Task 2); D4's rail-key rule `^[a-z0-9][a-z0-9._-]{0,63}/[a-z0-9][a-z0-9._-]{0,63}$` and `RailCache.reconcile()` picking up changed `rails()` on its next tick.
- Produces:
  - `class YouTubePlaylists { static final String WATCH_LATER_ID = "WL"; static final String WATCH_LATER_UNAVAILABLE = "…"; YouTubePlaylists(YouTubeApiClient, YouTubeProperties, Clock); List<PlaylistSummary> mine(); List<YouTubeVideo> items(String playlistId); List<YouTubeVideo> watchLater(); Optional<PlaylistSummary> loaded(String playlistId); void clear(); static String railId(String playlistId); record PlaylistSummary(String id, String title, int itemCount) }`.
  - `YouTubeContentSource.rails()` = subscriptions, then `watch-later` "Watch Later" when switched on, then one `pl-<hash>` rail per selected playlist titled with the stored title, in title order; `rail()` serves them.
  - `YouTubeSetupService.loadPlaylists() → List<PlaylistSummary>`, `choosePlaylists(List<String> playlistIds)`, `setWatchLater(boolean)`.
  - Endpoints: `POST /setup/sources/youtube/playlists/load` → 302 `/setup#youtube` (flash `youtubeMessage` `Found <n> playlists` or `youtubeError`); `POST /setup/sources/youtube/playlists` (repeated `playlist`) → 302 (flash `Playlists saved`); `POST /setup/sources/youtube/watch-later` (`enabled=true|false`) → 302 (flash `Watch Later shown` / `Watch Later hidden`).
  - `YouTubeSetupAdvice.View` gains the last two components `boolean watchLater` and `List<PlaylistOption> playlists` with `record PlaylistOption(String id, String title, int itemCount, boolean selected)` — loaded playlists (after "Load my playlists") plus stored selections that are not in the loaded list.

**Playlist wire format and rules (normative).**
- `playlists?part=snippet,contentDetails&mine=true&maxResults=50[&pageToken=<t>]` (`PLAYLISTS_LIST`), at most 10 pages → `items[].id`, `items[].snippet.title`, `items[].contentDetails.itemCount`. `mine()` stores the result as the "loaded" list (in memory) and returns it sorted by title (case-insensitive).
- Items of a playlist: `playlistItems?part=snippet,contentDetails&playlistId=<id>&maxResults=<min(railSize, 50)>` (`PLAYLIST_ITEMS_LIST`), one page, in playlist order (no re-sorting).
- `railId(playlistId)` = `"pl-" + HexFormat.of().formatHex(SHA-256(playlistId UTF-8)).substring(0, 16)`.
- Watch Later = items of `WL`; a result with no mapped items, or `NOT_FOUND`, throws `ContentSourceException(WATCH_LATER_UNAVAILABLE)` where `WATCH_LATER_UNAVAILABLE` = `YouTube does not share Watch Later with other apps for most accounts (an API change in 2016). Save videos to one of your own playlists and show that playlist here instead.`
- A selected playlist answering `NOT_FOUND` → `ContentSourceException("The playlist “<title>” no longer exists or is private to another account; choose it again on the setup page")`.
- Each rail (`watch-later`, every `pl-…`) keeps the last success and returns it without calls within `minRefreshSpacing` of that success (the memo is keyed by playlist id; failures are not memoized).
- Choosing playlists: every id must match `[A-Za-z0-9_-]{2,64}` and be in the loaded list (else `INVALID_INPUT` `Load your playlists again, then choose`); an empty selection is allowed; the stored map is `id → loaded title`. At most 20 selected playlists (`INVALID_INPUT` `Choose at most 20 playlists`).

- [ ] **Step 1: Write the fixtures**

`playlists-mine.json`:

```json
{
  "kind": "youtube#playlistListResponse",
  "etag": "cGxheWxpc3RzLW1pbmU",
  "pageInfo": { "totalResults": 2, "resultsPerPage": 50 },
  "items": [
    {
      "kind": "youtube#playlist",
      "etag": "cGwtZXZlbmluZw",
      "id": "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG",
      "snippet": {
        "publishedAt": "2024-02-11T19:00:00Z",
        "channelId": "UC4fixtureHomeControl00a",
        "title": "Watch this evening",
        "description": "",
        "thumbnails": { "medium": { "url": "https://i.ytimg.com/vi/aqz-KE-bpKQ/mqdefault.jpg", "width": 320, "height": 180 } },
        "channelTitle": "Andre at Home",
        "localized": { "title": "Watch this evening", "description": "" }
      },
      "contentDetails": { "itemCount": 2 }
    },
    {
      "kind": "youtube#playlist",
      "etag": "cGwta2lkcw",
      "id": "PLx0sYbCqOb8Q_CLZC2BdBSKEEB59BOPUM",
      "snippet": {
        "publishedAt": "2023-11-03T08:30:00Z",
        "channelId": "UC4fixtureHomeControl00a",
        "title": "Kids science",
        "description": "",
        "thumbnails": {},
        "channelTitle": "Andre at Home",
        "localized": { "title": "Kids science", "description": "" }
      },
      "contentDetails": { "itemCount": 17 }
    }
  ]
}
```

`playlist-items-playlist.json` — the playlist-item envelope (`playlistId` `PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG`, `totalResults` 2) with two items in position order: (0) videoId `Wq9Ze2Lr5tA`, title `Blender 5.0 Reveal`, owner `Blender` / `UCSMOQeBJ2RAnuFungnQOxLg`, `snippet.publishedAt` `2026-09-14T20:00:00Z` (added to the playlist), `videoPublishedAt` `2026-08-30T16:45:00Z`; (1) videoId `Kz1aT5nM3pQ`, the Kurzgesagt video from Task 2, `snippet.publishedAt` `2026-09-15T21:00:00Z`, `videoPublishedAt` `2026-09-15T14:00:12Z`. `snippet.channelId` and `snippet.channelTitle` are the playlist owner (`UC4fixtureHomeControl00a`, `Andre at Home`), so the test proves the mapper uses `videoOwnerChannelTitle`.

`playlist-items-empty.json`:

```json
{
  "kind": "youtube#playlistItemListResponse",
  "etag": "cGxheWxpc3QtaXRlbXMtZW1wdHk",
  "items": [],
  "pageInfo": { "totalResults": 0, "resultsPerPage": 50 }
}
```

- [ ] **Step 2: Write the failing tests**

`YouTubePlaylistsTest` (fake with `youtubeLibrary()` plus `respond("GET","/youtube/v3/playlists", fixture playlists-mine.json)`, `playlist("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG","playlist-items-playlist.json")`, `playlist("WL","playlist-items-empty.json")`; mock tokens, real ledger, `MutableClock`):
- `listsMyPlaylistsByTitle`: `mine()` → `[PlaylistSummary(PLx0sYbCqOb8Q_CLZC2BdBSKEEB59BOPUM, "Kids science", 17), PlaylistSummary(PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG, "Watch this evening", 2)]`; raw query `part=snippet%2CcontentDetails&mine=true&maxResults=50`; `loaded("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")` present.
- `pagesAtMostTen`: every page answers with `nextPageToken` `X` → exactly 10 requests.
- `itemsKeepPlaylistOrderAndOwnerChannel`: `items("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")` ids `[Wq9Ze2Lr5tA, Kz1aT5nM3pQ]`, first channel title `Blender`; `maxResults=30`.
- `railIdsAreLowerCaseAndStable`: `railId("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")` matches `^pl-[0-9a-f]{16}$`, equals the first 16 hex chars of the SHA-256 computed in the test, and differs for the other playlist; `"youtube/" + railId` matches D4's rail-key pattern.
- `watchLaterIsHonest`: `watchLater()` with the empty fixture → `ContentSourceException` with exactly `WATCH_LATER_UNAVAILABLE`; with `error-playlist-not-found.json` (404) → the same message; with `playlist-items-playlist.json` served for `WL` → two videos.
- `railsAreMemoizedWithinTheSpacing`: two `items(id)` calls 5 min apart → one request; 15 min later → two requests; a failure is not memoized (404 then 200 → second call requests again).

`YouTubeContentSourceTest` add:
- `railsFollowTheSettings`: settings `watchLater=true`, playlists `{PLx0…YG: "Watch this evening", PLx0…UM: "Kids science"}` → `rails()` = `subscriptions` "New from your subscriptions", `watch-later` "Watch Later", `pl-<hash UM>` "Kids science", `pl-<hash YG>` "Watch this evening".
- `playlistRailServesItsItems`: `rail(railId(YG))` → items `[Wq9Ze2Lr5tA, Kz1aT5nM3pQ]`, remembered in `KnownVideos`.
- `aDeselectedPlaylistRailIsUnknown`: `rail("pl-0000000000000000")` → `IllegalArgumentException`.
- `aVanishedPlaylistSaysSo`: playlist 404 → `ContentSourceException` `The playlist “Watch this evening” no longer exists or is private to another account; choose it again on the setup page`.
- `watchLaterRailExplainsTheRestriction`: `rail("watch-later")` with the empty fixture → message `WATCH_LATER_UNAVAILABLE`.

`YouTubeSetupServiceTest` add:
- `choosePlaylistsStoresLoadedTitles`: after `loadPlaylists()`, `choosePlaylists(List.of("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"))` → settings playlists `{PLx0…YG: "Watch this evening"}`; `choosePlaylists(List.of())` → empty.
- `unknownOrTooManyPlaylistsAreRefused`: an id not loaded → `INVALID_INPUT` `Load your playlists again, then choose`; `../etc` → same; 21 loaded ids → `Choose at most 20 playlists`.
- `watchLaterSwitch`: `setWatchLater(true)` → settings `watchLater` true.

`YouTubeSetupControllerTest` add:
- `loadChooseAndWatchLaterEndpoints`: `POST /setup/sources/youtube/playlists/load` → `verify(setup).loadPlaylists()`, flash `Found 2 playlists`; `POST /setup/sources/youtube/playlists` with `playlist=A&playlist=B` → `choosePlaylists(List.of("A","B"))`, flash `Playlists saved`; no `playlist` parameter → `choosePlaylists(List.of())`; `POST /setup/sources/youtube/watch-later` `enabled=true` → `setWatchLater(true)`, flash `Watch Later shown`; a `YouTubeException` → `youtubeError`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*'`
Expected: compilation failure — `YouTubePlaylists` does not exist.

- [ ] **Step 4: Implement playlists**

`sources/youtube/YouTubePlaylists.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.content.ContentSourceException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The user's own playlists and the Watch Later list, as far as the API still exposes it. */
public class YouTubePlaylists {

    public static final String WATCH_LATER_ID = "WL";
    public static final String WATCH_LATER_UNAVAILABLE = "YouTube does not share Watch Later with other apps for most"
            + " accounts (an API change in 2016). Save videos to one of your own playlists and show that playlist here instead.";
    private static final int MAX_PAGES = 10;

    public record PlaylistSummary(String id, String title, int itemCount) {
    }

    private record Memo(List<YouTubeVideo> videos, Instant at) {
    }

    private final YouTubeApiClient api;
    private final YouTubeProperties properties;
    private final Clock clock;
    private final Map<String, Memo> memos = new HashMap<>();
    private Map<String, PlaylistSummary> loaded = Map.of();

    public YouTubePlaylists(YouTubeApiClient api, YouTubeProperties properties, Clock clock) {
        this.api = api;
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized List<PlaylistSummary> mine() {
        List<PlaylistSummary> found = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("part", "snippet,contentDetails");
            query.put("mine", "true");
            query.put("maxResults", "50");
            query.put("pageToken", pageToken);
            JsonNode response = api.get(QuotaLedger.Call.PLAYLISTS_LIST, "playlists", query);
            for (JsonNode item : response.path("items")) {
                String id = item.path("id").asString("");
                if (id.matches("[A-Za-z0-9_-]{2,64}")) {
                    found.add(new PlaylistSummary(id, item.path("snippet").path("title").asString(id),
                            item.path("contentDetails").path("itemCount").asInt(0)));
                }
            }
            pageToken = response.path("nextPageToken").asString("");
            if (pageToken.isBlank()) {
                break;
            }
        }
        found.sort(Comparator.comparing(PlaylistSummary::title, String.CASE_INSENSITIVE_ORDER).thenComparing(PlaylistSummary::id));
        Map<String, PlaylistSummary> byId = new LinkedHashMap<>();
        found.forEach(p -> byId.put(p.id(), p));
        loaded = byId;
        return List.copyOf(found);
    }

    public synchronized Optional<PlaylistSummary> loaded(String playlistId) {
        return Optional.ofNullable(loaded.get(playlistId));
    }

    public synchronized List<PlaylistSummary> loadedList() {
        return List.copyOf(loaded.values());
    }

    public synchronized List<YouTubeVideo> items(String playlistId) {
        Instant now = clock.instant();
        Memo memo = memos.get(playlistId);
        if (memo != null && now.isBefore(memo.at().plus(properties.minRefreshSpacing()))) {
            return memo.videos();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("part", "snippet,contentDetails");
        query.put("playlistId", playlistId);
        query.put("maxResults", String.valueOf(Math.min(properties.railSize(), 50)));
        List<YouTubeVideo> videos = YouTubeVideoMapper.playlistItems(
                api.get(QuotaLedger.Call.PLAYLIST_ITEMS_LIST, "playlistItems", query));
        memos.put(playlistId, new Memo(videos, now));
        return videos;
    }

    public List<YouTubeVideo> watchLater() {
        List<YouTubeVideo> videos;
        try {
            videos = items(WATCH_LATER_ID);
        } catch (YouTubeException e) {
            if (e.kind() == YouTubeException.Kind.NOT_FOUND) {
                throw new ContentSourceException(WATCH_LATER_UNAVAILABLE);
            }
            throw e;
        }
        if (videos.isEmpty()) {
            synchronized (this) {
                memos.remove(WATCH_LATER_ID);
            }
            throw new ContentSourceException(WATCH_LATER_UNAVAILABLE);
        }
        return videos;
    }

    public synchronized void clear() {
        memos.clear();
        loaded = Map.of();
    }

    public static String railId(String playlistId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(playlistId.getBytes(StandardCharsets.UTF_8));
            return "pl-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
```

`YouTubeContentSource` — constructor gains `YouTubePlaylists playlists` (update the bean and Task 2 tests). `rails()`: after subscriptions, `watch-later` when `settings.watchLater()`, then for each `settings.playlists()` entry `new RailDescriptor("youtube", YouTubePlaylists.railId(id), title)`. `rail(railId)`: `watch-later` (only when switched on, else `IllegalArgumentException`) → `playlists.watchLater()`; `pl-…` → find the selected playlist whose `railId` matches (none → `IllegalArgumentException`), then `playlists.items(id)`, mapping `NOT_FOUND` to the vanished-playlist message; remember videos in `KnownVideos`. `forgetAccount()` also calls `playlists.clear()`.

`YouTubeSetupService` — gains `ObjectProvider<YouTubePlaylists>`; `loadPlaylists()` = `playlists.mine()`; `choosePlaylists(ids)` per the rules; `setWatchLater(enabled)` = `save(settings().withWatchLater(enabled))`.

`YouTubeSetupController` — the three endpoints; `@RequestParam(name = "playlist", required = false) List<String> playlist`.

`YouTubeSetupAdvice.View` — `watchLater` from settings; `playlists` = `playlists.loadedList()` mapped with `selected = settings.playlists().containsKey(id)`, followed by stored selections not in that list (`itemCount` 0).

`fragments/youtube-setup.html` — when `connected()`, a `<fieldset>` "Rails":
- A form posting to `/setup/sources/youtube/watch-later` with a hidden `enabled` value toggled by two submit buttons (`Show Watch Later` / `Hide Watch Later`), and the hint `YouTube stopped sharing Watch Later with other apps for most accounts in 2016. If the rail says so, save videos to a playlist of your own and show that instead.`
- A form posting to `/setup/sources/youtube/playlists/load` with button `Load my playlists` and hint `Costs 1 quota unit per 50 playlists.`
- When `playlists` is not empty, a form posting to `/setup/sources/youtube/playlists` with one checkbox per option (`name="playlist"`, `th:value="${p.id()}"`, `th:checked="${p.selected()}"`, label `title (itemCount videos)`) and button `Save playlists`. Hint: `Each shown playlist refreshes hourly and costs 1 unit per refresh. Order rails in “Sources” above.`

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/youtube src/main/resources/templates/fragments/youtube-setup.html \
  src/test/java/dev/andre/homecontrol/sources/youtube src/test/resources/fixtures/youtube
git commit -m "feat: YouTube Watch Later and chosen playlists as rails"
```

---

### Task 4: E4 · Search

**Files:**
- Create: `sources/youtube/YouTubeSearch.java`
- Modify: `core/content/ContentSource.java`, `content/SearchService.java`, `web/SearchController.java`, `web/ContentController.java`, `src/main/resources/templates/fragments/search.html`, `src/main/resources/static/app.css`, `sources/youtube/YouTubeVideoMapper.java`, `sources/youtube/YouTubeContentSource.java`, `sources/youtube/YouTubeConfiguration.java`
- Test: `sources/youtube/YouTubeSearchTest.java`, `YouTubeVideoMapperTest.java`, `YouTubeContentSourceTest.java`, `content/SearchServiceTest.java`, `web/SearchControllerTest.java`, `web/ContentControllerTest.java`; fixture `search-videos.json`

**Interfaces:**
- Consumes: D5's `SearchService(ContentSources, RailPreferences, ContentProperties, ExecutorService)` with `search(query, limit)` and its per-future deadline handling, `SearchOutcome(query, hits, failures)` with `Hits(ContentSource, List<ContentItem>)`/`Failure(ContentSource, String)`, `SearchController.results` (`state` ∈ `empty|short|long|done`, `hits`, `failures`, `SearchHitsView`, `SearchFailureView`), `fragments/search :: results`, `fragments/rails :: tile(item)`, C8/D5 `GET /search?q=&limit=` JSON (`SearchResponse`, `SearchResult`, `SearchError`); `ContentSources.searchable()/find(id)`, `RailPreferences.sourceEnabled(id)`; from this plan `YouTubeApiClient`, `QuotaLedger` (`charge(SEARCH_LIST)`, `usage().searchesLeft()`), `KnownVideos`, `YouTubeVideo`.
- Produces:
  - `ContentSource`: `default boolean searchOnDemand() { return false; }` — a source whose searches are metered is searched only on an explicit request; `default Optional<String> searchNote() { return Optional.empty(); }` — a short user-facing note shown next to the on-demand button.
  - `SearchService.search(query, limit)` skips sources with `searchOnDemand()`; `SearchService.searchSource(String sourceId, String query, int limit) → SearchOutcome` (one source, same deadline and failure wording; throws `IllegalArgumentException("No searchable source " + sourceId)` when the source is unknown, not searchable, unavailable or disabled); `SearchService.onDemandSources() → List<ContentSource>` (searchable, available, enabled, on-demand, in `ContentSources` order).
  - `GET /search/results?q=` (state `done`) adds model `onDemand` = `List<OnDemandView(String sourceId, String sourceName, String note)>`; `GET /search/results/{sourceId}?q=` → fragment `fragments/search :: source-results` (always 200; validation as `/search/results`; unknown source → `<p class="rail-error" role="alert">No searchable source <id></p>`).
  - `GET /search?q=&limit=&source=` — with `source` → only that source through `searchSource` (unknown → 404 text `No searchable source <id>`); without → unchanged (on-demand sources absent).
  - `final class YouTubeSearch { YouTubeSearch(YouTubeApiClient, KnownVideos, YouTubeProperties, Clock); List<YouTubeVideo> search(String query, int limit); static String cacheKey(String query); static String unescapeHtml(String); }`; `YouTubeVideoMapper.fromSearchResult(JsonNode)`.
  - `YouTubeContentSource`: `searchable()` = `true`; `searchOnDemand()` = `true`; `searchNote()` = `Optional.of("<left> of <perDay> YouTube searches left today")` (or `YouTube searches used up until <HH:mm>` when none left); `search(query, limit)` → items.

**Search wire format and rules (normative).**
- `search?part=snippet&type=video&maxResults=<min(max(limit,1),25)>&q=<query>` (`SEARCH_LIST`, 100 units, counted against `searches-per-day`) → `items[].id.videoId`, `items[].snippet.title` (HTML-escaped), `.channelTitle` (HTML-escaped), `.publishedAt`, `.liveBroadcastContent`.
- Mapping: valid `id.videoId` required; title and channel through `unescapeHtml`; `liveBroadcastContent` `upcoming` items are dropped (they cannot play yet); published from `snippet.publishedAt`.
- `unescapeHtml`: `&amp;` `&lt;` `&gt;` `&quot;` `&#39;` `&apos;` and decimal `&#NNN;` / hex `&#xHH;` entities; anything else unchanged; `&amp;` handled last-in-effect so `&amp;lt;` becomes `&lt;` (single decoding pass left to right).
- Cache: key = `query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)`; an entry holds the videos (the full `maxResults` answer) and its time; a hit younger than `searchCacheTtl` returns the first `limit` videos with no quota charge and no request; at most 50 entries, least recently used evicted. Failures are not cached.
- Every returned video is remembered in `KnownVideos` so the play sheet can re-read it by id without quota.

- [ ] **Step 1: Write the fixture**

`search-videos.json`:

```json
{
  "kind": "youtube#searchListResponse",
  "etag": "c2VhcmNoLXZpZGVvcw",
  "nextPageToken": "CAUQAA",
  "regionCode": "DE",
  "pageInfo": { "totalResults": 1000000, "resultsPerPage": 3 },
  "items": [
    {
      "kind": "youtube#searchResult",
      "etag": "c2VhcmNoLTE",
      "id": { "kind": "youtube#video", "videoId": "aqz-KE-bpKQ" },
      "snippet": {
        "publishedAt": "2014-11-10T14:05:47Z",
        "channelId": "UCSMOQeBJ2RAnuFungnQOxLg",
        "title": "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film",
        "description": "Big Buck Bunny tells the story of a giant rabbit with a heart bigger than himself.",
        "thumbnails": { "medium": { "url": "https://i.ytimg.com/vi/aqz-KE-bpKQ/mqdefault.jpg", "width": 320, "height": 180 } },
        "channelTitle": "Blender",
        "liveBroadcastContent": "none",
        "publishTime": "2014-11-10T14:05:47Z"
      }
    },
    {
      "kind": "youtube#searchResult",
      "etag": "c2VhcmNoLTI",
      "id": { "kind": "youtube#video", "videoId": "Hh7Lq2Wv9sE" },
      "snippet": {
        "publishedAt": "2026-09-01T14:00:00Z",
        "channelId": "UCsXVk37bltHxD1rDPwtNM8Q",
        "title": "Bunnies &amp; Black Holes: &quot;Why&quot; It&#39;s Not Fine",
        "description": "",
        "thumbnails": { "medium": { "url": "https://i.ytimg.com/vi/Hh7Lq2Wv9sE/mqdefault.jpg", "width": 320, "height": 180 } },
        "channelTitle": "Kurzgesagt &ndash; In a Nutshell",
        "liveBroadcastContent": "none",
        "publishTime": "2026-09-01T14:00:00Z"
      }
    },
    {
      "kind": "youtube#searchResult",
      "etag": "c2VhcmNoLTM",
      "id": { "kind": "youtube#video", "videoId": "Rt4Yb8Nc1xZ" },
      "snippet": {
        "publishedAt": "2026-09-20T18:00:00Z",
        "channelId": "UCLA_DiR1FfKNvjuUpBHmylQ",
        "title": "Bunny Nebula Live Premiere",
        "description": "",
        "thumbnails": {},
        "channelTitle": "NASA",
        "liveBroadcastContent": "upcoming",
        "publishTime": "2026-09-20T18:00:00Z"
      }
    }
  ]
}
```

(`&ndash;` is deliberately not decoded — the test pins that unknown named entities stay as they are.)

- [ ] **Step 2: Write the failing tests**

`YouTubeSearchTest` (fake `respond("GET","/youtube/v3/search", fixture search-videos.json)`, mock tokens, real ledger with `searchesPerDay` 2, `MutableClock`):
- `searchesVideosOnce`: `search("Big  Bunny", 10)` → ids `[aqz-KE-bpKQ, Hh7Lq2Wv9sE]` (upcoming dropped); raw query `part=snippet&type=video&maxResults=10&q=Big++Bunny`; ledger units 100, searches 1.
- `unescapesTitles`: second title `Bunnies & Black Holes: "Why" It's Not Fine`; channel `Kurzgesagt &ndash; In a Nutshell`.
- `unescapeHtmlCases` (parameterized): `a&amp;lt;b` → `a&lt;b`; `&#65;&#x42;` → `AB`; `&bogus;` → `&bogus;`; `5 &gt; 3` → `5 > 3`.
- `aRepeatedQueryIsFree`: `search("big bunny", 5)` then `search("  BIG   bunny ", 2)` 1 h later → one request, second result size 2, ledger searches 1.
- `theCacheExpires`: 6 h 1 min later → second request.
- `theDailyCapIsEnforced`: three different queries → third throws `SEARCH_LIMIT`, two requests recorded.
- `limitIsCappedAt25`: `search("x", 50)` → `maxResults=25`.
- `resultsAreRemembered`: after a search, `known.find("Hh7Lq2Wv9sE")` present.
- `failuresAreNotCached`: 403 quota error then success → second call issues a request.

`YouTubeContentSourceTest` add:
- `searchIsOnDemandWithANote`: `searchable()` and `searchOnDemand()` true; ledger with 1 of 20 searches used → `searchNote()` = `Optional.of("19 of 20 YouTube searches left today")`; all used → `YouTube searches used up until 09:00`.
- `searchMapsItems`: `search("bunny", 20)` → `ContentItemView`-compatible items with app links.

`SearchServiceTest` add (D5's test style, stub `ContentSource`s):
- `onDemandSourcesAreSkippedInTheUnifiedSearch`: sources `jellyfin` (normal) and `youtube` (`searchOnDemand` true) → `search("star", 20)` hits only `jellyfin`; `youtube.search` never called.
- `searchSourceRunsOneOnDemandSource`: `searchSource("youtube","star",20)` → one `Hits` for youtube; a `ContentSourceException("You have used today's 20 YouTube searches. …")` → one `Failure` with that message.
- `searchSourceRefusesUnknownDisabledOrUnavailable`: unknown id, a disabled source (`preferences.sourceEnabled` false), a source with `available()` false, a non-searchable source → `IllegalArgumentException("No searchable source <id>")`.
- `onDemandSourcesListsEligibleOnes`.

`SearchControllerTest` add (`@MockitoBean SearchService`):
- `offersOnDemandSourcesAfterResults`: `search` returns hits for jellyfin; `onDemandSources()` returns a youtube stub with note `19 of 20 YouTube searches left today` → body contains `hx-get="/search/results/youtube?q=star"`, `Search YouTube` and the note.
- `onDemandSourcesAlsoWhenNothingFound`: no hits → `Nothing found for “star”` and the button.
- `noButtonForShortQueries`: `q=s` → hint only, `onDemandSources()` not called.
- `sourceResultsFragment`: `GET /search/results/youtube?q=star` → `searchSource("youtube","star",20)` hits → a `section.rail.search-hits` with tiles; a failure → `<p class="rail-error"` with the message; `IllegalArgumentException` → `No searchable source youtube`; `q=s` → `Type at least 2 characters` and `searchSource` not called.

`ContentControllerTest` add:
- `searchWithSourceUsesSearchSource`: `GET /search?q=star&source=youtube` → `searchSource("youtube","star",20)`; JSON shape unchanged; unknown → 404 text `No searchable source nope`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `YouTubeSearch`, `searchOnDemand`, `searchSource` do not exist.

- [ ] **Step 4: Core and unified search**

`core/content/ContentSource.java` — add:

```java
    /**
     * True when each search costs a scarce budget (e.g. YouTube's 100 quota units). Such sources are left out of
     * the as-you-type search and searched only when the user asks for them (spec §6.1 "quota-aware").
     */
    default boolean searchOnDemand() {
        return false;
    }

    /** A short note next to the on-demand search button, e.g. "17 of 20 YouTube searches left today". */
    default Optional<String> searchNote() {
        return Optional.empty();
    }
```

`content/SearchService.java` — refactor D5's loop into `private SearchOutcome collect(String query, Map<ContentSource, Future<List<ContentItem>>> pending)` holding the deadline and failure logic unchanged. Then:

```java
    public SearchOutcome search(String query, int limit) {
        Map<ContentSource, Future<List<ContentItem>>> pending = new LinkedHashMap<>();
        for (ContentSource source : sources.searchable()) {
            if (preferences.sourceEnabled(source.id()) && !source.searchOnDemand()) {
                pending.put(source, executor.submit(() -> source.search(query, limit)));
            }
        }
        return collect(query, pending);
    }

    public SearchOutcome searchSource(String sourceId, String query, int limit) {
        ContentSource source = sources.searchable().stream()
                .filter(candidate -> candidate.id().equals(sourceId) && preferences.sourceEnabled(sourceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No searchable source " + sourceId));
        Map<ContentSource, Future<List<ContentItem>>> pending = new LinkedHashMap<>();
        pending.put(source, executor.submit(() -> source.search(query, limit)));
        return collect(query, pending);
    }

    public List<ContentSource> onDemandSources() {
        return sources.searchable().stream()
                .filter(source -> source.searchOnDemand() && preferences.sourceEnabled(source.id()))
                .toList();
    }
```

(`ContentSources.searchable()` already requires `available()`.)

`web/SearchController.java` — in `results`, when `state` is `done` add `onDemand` = `searchService.onDemandSources()` mapped to `record OnDemandView(String sourceId, String sourceName, String note)` (`note` = `searchNote().orElse(null)`). Add:

```java
    @GetMapping("/search/results/{sourceId}")
    public String sourceResults(@PathVariable String sourceId, @RequestParam(required = false) String q, Model model) {
        // same q validation → state; for "done":
        //   try { outcome = searchService.searchSource(sourceId, query, 20); hits/failures as in results() }
        //   catch (IllegalArgumentException e) { model.addAttribute("sourceError", e.getMessage()); }
        return "fragments/search :: source-results";
    }
```

(Extract the validation and the hits/failures model filling from `results` into private helpers so both handlers share them; include hits with empty item lists here as `Nothing found on <name>` instead of skipping them.)

`templates/fragments/search.html` — inside the `done` block of `results`, after the failures and the "Nothing found" hint:

```html
        <div class="search-on-demand" th:each="source : ${onDemand}">
            <button type="button" class="search-more"
                    th:attr="hx-get=@{/search/results/{id}(id=${source.sourceId()},q=${query})}"
                    hx-target="closest div" hx-swap="outerHTML"
                    th:text="|Search ${source.sourceName()}|">Search YouTube</button>
            <span class="hint" th:if="${source.note() != null}" th:text="${source.note()}">19 of 20 YouTube searches left today</span>
        </div>
```

and a new fragment:

```html
<th:block th:fragment="source-results">
    <p class="hint" th:if="${state == 'short'}">Type at least 2 characters</p>
    <p class="hint" th:if="${state == 'long'}">Search for 2 to 100 characters</p>
    <p class="rail-error" role="alert" th:if="${sourceError != null}" th:text="${sourceError}">No searchable source</p>
    <th:block th:if="${state == 'done' and sourceError == null}">
        <section class="rail search-hits" th:each="hit : ${hits}" th:attr="data-source=${hit.sourceId()}">
            <header><h2 th:text="${hit.sourceName()}">YouTube</h2></header>
            <div class="tiles" role="list" th:if="${!#lists.isEmpty(hit.items())}">
                <div role="listitem" th:each="item : ${hit.items()}">
                    <button th:replace="~{fragments/rails :: tile(${item})}"></button>
                </div>
            </div>
            <p class="rail-empty" th:if="${#lists.isEmpty(hit.items())}" th:text="|Nothing found on ${hit.sourceName()}|"></p>
        </section>
        <p class="rail-error" role="alert" th:each="failure : ${failures}" th:text="${failure.message()}"></p>
    </th:block>
</th:block>
```

(The htmx URL is built by Thymeleaf's `@{…}` so the query is URL-encoded; the button has `min-height: 44px`. Tiles in these results open the play sheet through D3's delegated click handler.)

`app.css` — `.search-on-demand { display: flex; gap: .75rem; align-items: center; flex-wrap: wrap; padding: 0 1rem; } .search-more { min-height: 44px; padding: .5rem 1rem; border-radius: 999px; }`.

`web/ContentController.search` — add `@RequestParam(required = false) String source`; when non-blank call `searchService.searchSource(source, query, clamped)` inside `try/catch (IllegalArgumentException e)` → `404 text/plain` with the message; build the same `SearchResponse`.

- [ ] **Step 5: YouTube search**

`YouTubeVideoMapper.fromSearchResult(JsonNode)` per the normative mapping.

`sources/youtube/YouTubeSearch.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** search.list, 100 units per call, capped per day, cached so repeating a query costs nothing. */
public class YouTubeSearch {

    private static final int MAX_RESULTS = 25;
    private static final int CACHE_ENTRIES = 50;
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|#39);");

    private record Cached(List<YouTubeVideo> videos, Instant at) {
    }

    private final YouTubeApiClient api;
    private final KnownVideos known;
    private final YouTubeProperties properties;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    public YouTubeSearch(YouTubeApiClient api, KnownVideos known, YouTubeProperties properties, Clock clock) {
        this.api = api;
        this.known = known;
        this.properties = properties;
        this.clock = clock;
    }

    public List<YouTubeVideo> search(String query, int limit) {
        String key = cacheKey(query);
        int wanted = Math.min(Math.max(limit, 1), MAX_RESULTS);
        Optional<List<YouTubeVideo>> hit = cached(key, wanted);
        if (hit.isPresent()) {
            return hit.get();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("part", "snippet");
        params.put("type", "video");
        params.put("maxResults", String.valueOf(wanted));
        params.put("q", query.strip());
        var response = api.get(QuotaLedger.Call.SEARCH_LIST, "search", params);
        List<YouTubeVideo> videos = new java.util.ArrayList<>();
        for (var item : response.path("items")) {
            YouTubeVideoMapper.fromSearchResult(item).ifPresent(videos::add);
        }
        known.remember(videos);
        synchronized (cache) {
            cache.put(key, new Cached(List.copyOf(videos), clock.instant()));
        }
        return videos.stream().limit(wanted).toList();
    }

    private Optional<List<YouTubeVideo>> cached(String key, int wanted) {
        synchronized (cache) {
            Cached entry = cache.get(key);
            if (entry == null || !clock.instant().isBefore(entry.at().plus(properties.searchCacheTtl()))) {
                return Optional.empty();
            }
            return Optional.of(entry.videos().stream().limit(wanted).toList());
        }
    }

    public static String cacheKey(String query) {
        return query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String unescapeHtml(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                default -> {
                    int codePoint = entity.startsWith("#x") || entity.startsWith("#X")
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    yield Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : matcher.group();
                }
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
```

A live cache entry answers any limit with its first `limit` videos, even when it holds fewer than asked for (add test `aLargerLimitIsServedFromTheSmallerCachedAnswer`: `search("bunny", 1)` then `search("bunny", 10)` → one request, second result size 1).

`YouTubeContentSource` — constructor gains `YouTubeSearch search, QuotaLedger ledger`; implement the three search methods; `search` maps `YouTubeVideo::toItem`.

`YouTubeConfiguration` — `@Bean YouTubeSearch youTubeSearch(YouTubeApiClient, KnownVideos, YouTubeProperties, Clock)`; update the content source bean.

- [ ] **Step 6: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS (including D5's existing search tests and C8's JSON tests); BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/content/ContentSource.java src/main/java/dev/andre/homecontrol/content \
  src/main/java/dev/andre/homecontrol/web src/main/java/dev/andre/homecontrol/sources/youtube \
  src/main/resources/templates/fragments/search.html src/main/resources/static/app.css \
  src/test/java/dev/andre/homecontrol/content src/test/java/dev/andre/homecontrol/web \
  src/test/java/dev/andre/homecontrol/sources/youtube src/test/resources/fixtures/youtube
git commit -m "feat: quota-capped YouTube search offered on demand in the unified search"
```

---

### Task 5: E5 · Play routes

**Files:**
- Create: `core/CastAppQuery.java`, `core/playback/YouTubeLoungeStrategy.java`, `sources/youtube/YouTubeVideoIds.java`, `sources/youtube/LoungeException.java`, `sources/youtube/LoungeClient.java`, `sources/youtube/YouTubeLoungeResolver.java`, `sources/youtube/YouTubeLoungeRouteExecutor.java`
- Modify: `core/DeviceHandle.java`, `device/DeviceManager.java`, `adapters/cast/CastSession.java`, `adapters/cast/protocol/CastPayloads.java`, `core/playback/PlayableRef.java`, `core/playback/Route.java`, `core/playback/PlaybackPlanner.java`, `core/playback/RouteKeys.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java`, `sources/youtube/YouTubeSetupService.java`, `YouTubeSetupController.java`, `YouTubeSetupAdvice.java`, `YouTubeConfiguration.java`, `src/main/resources/templates/fragments/youtube-setup.html`
- Test: `core/playback/YouTubeRoutesTest.java`, `core/playback/PlaybackPlannerTest.java`, `core/playback/RouteKeysTest.java`, `playback/PlaybackServiceTest.java`, `device/DeviceManagerQueryTest.java`, `adapters/cast/CastSessionTest.java`, `sources/youtube/YouTubeVideoIdsTest.java`, `LoungeClientTest.java`, `YouTubeLoungeResolverTest.java`, `YouTubeLoungeRouteExecutorTest.java`, `YouTubeSetupServiceTest.java`, `YouTubeSetupControllerTest.java`; fixtures `lounge-token-batch.json`, `lounge-bind.txt`, `cast-mdx-session-status.json`

**Interfaces:**
- Consumes: `PlayableRef.AppLink(URI, String service)`, `AppLinkStrategy`, `Route.OpenAppLink.describe()` = `Open in the YouTube app` (A); `DeviceManager` handle map and the fall-through of B4's `execute`, `Capability.CAST_RECEIVER`, `DeviceOfflineException`, `UnsupportedActionException`, `ActionFailedException`, `DeviceNotFoundException` (A/B); B's `CastSession` (`requireConnected()`, `launch(CastConnection, String appId)`, `receiver` field, `call(Callable, String)`, command timeout from `CastProperties.commandTimeoutSeconds()`), `CastConnection.connect/send/expect`, `CastConnection.Waiter.await/cancel`, `CastIncoming.namespace/sourceId/type/payload`, `ReceiverStatus.app`, `ReceiverApp.speaks/transportId/displayName`, `FakeCastReceiver` with C5's `appSpeaks` and `answerCustom`; C5's `CastSession.awaitNamespace`, `CUSTOM_ERROR_TYPES`, `CastPayloads.custom`; C7's `PlayableResolver`, `RouteExecutor`, `PlaybackService` resolver loop, `PlaybackPlanner.explain` switch; D3's `PlaybackPlanner.routes`, `RouteKeys`, `PlaybackService.execute` switch; from this plan `YouTubeHttp`, `YouTubeException`, `YouTubeSettings.loungeDevices/loungeRemoteId/withLoungeDevice/withLoungeRemoteId`, `YouTubeSetupService.settings/save`, `FakeGoogleServer`.
- Produces:
  - `record CastAppQuery(String receiverAppId, String namespace, Map<String,Object> message, String replyType)` in `core`.
  - `DeviceHandle`: `default Map<String,Object> query(CastAppQuery query) { throw new UnsupportedActionException("This connection cannot ask receiver apps"); }`.
  - `DeviceManager.query(String deviceId, CastAppQuery query) → Map<String,Object>`.
  - `CastSession.query(CastAppQuery)`; `CastPayloads.toMap(JsonNode) → Map<String,Object>`.
  - `PlayableRef.YouTubeLounge(String videoId)` (`kindLabel` `YouTube Cast`); `Route.YouTubeLounge(String videoId)` with `describe()` = `Cast with the YouTube receiver (best effort)`; `YouTubeLoungeStrategy` (CAST_RECEIVER + first `YouTubeLounge`); `RouteKeys.key` → `youtube-lounge`; planner bean order `JellyfinSessionStrategy, AppLinkStrategy, YouTubeLoungeStrategy, CastMessageStrategy, CastLoadStrategy, CastStreamStrategy`.
  - `final class YouTubeVideoIds { static Optional<String> fromUrl(URI) }`.
  - `class LoungeClient { static final String NAME = "Home Control"; LoungeClient(YouTubeHttp, URI loungeBaseUrl); String loungeToken(String screenId); LoungeSession bind(String loungeToken, String remoteId); void setPlaylist(String loungeToken, LoungeSession session, String videoId); static LoungeSession parseBind(String body); record LoungeSession(String sid, String gsessionId, long lastEventId) }` (redacted `toString`); `class LoungeException extends RuntimeException { LoungeException(String step, String detail) }` with message `<step>: <detail>`.
  - `class YouTubeLoungeResolver implements PlayableResolver { YouTubeLoungeResolver(YouTubeSetupService) }`; `class YouTubeLoungeRouteExecutor implements RouteExecutor { static final String RECEIVER_APP_ID = "233637DE"; static final String MDX_NAMESPACE = "urn:x-cast:com.google.youtube.mdx"; YouTubeLoungeRouteExecutor(DeviceManager, LoungeClient, YouTubeSetupService) }`.
  - `YouTubeSetupService.setLounge(String deviceId, boolean enabled)`; `POST /setup/sources/youtube/lounge` (`device`, `enabled`) → 302 `/setup#youtube` (flash `YouTube Cast switched on for <name>` / `YouTube Cast switched off for <name>`); `YouTubeSetupAdvice.View` gains the last component `List<LoungeDeviceView> loungeDevices` with `record LoungeDeviceView(String id, String name, boolean enabled)` — every registered device with `CAST_RECEIVER`, in `DeviceManager.devices()` order.

**Cast MDX exchange (normative).** Receiver app `233637DE`, namespace `urn:x-cast:com.google.youtube.mdx`, after LAUNCH (unless running), namespace announced, CONNECT to the app transport: sender → app `{"type":"getMdxSessionStatus"}`; app → sender `{"type":"mdxSessionStatus","data":{"screenId":"<id>","deviceId":"<uuid>"}}`. `CastSession.query` returns the reply payload as a map (the whole object including `type`); a reply of type `error`, `connectionerror` or `playbackerror` → `ActionFailedException("<device> refused the request (<message or type>)")`; no reply within the command timeout → B's `call` wording `<device> did not answer in time when asked to answer <replyType>`.

**Lounge wire format (normative; unofficial, best effort).** All requests `POST`, `Content-Type: application/x-www-form-urlencoded`, form values URL-encoded in the order listed, via `YouTubeHttp.postForm` (which also sends `Accept: application/json`).
1. Lounge token: `<lounge>/pairing/get_lounge_token_batch`, body `screen_ids=<screenId>` → 200 `{"screens":[{"screenId","loungeToken","expiration",…}]}`. Non-200 → `LoungeException("lounge token", "YouTube answered HTTP <n>")`; no non-blank `screens[0].loungeToken` → `LoungeException("lounge token", "YouTube did not issue a lounge token for this screen")`.
2. Bind: `<lounge>/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error`, body `app=web&mdx-version=3&name=Home+Control&id=<remoteId>&device=REMOTE_CONTROL&capabilities=que%2Cdsdtr%2Catp&magnaKey=cloudPairedDevice&ui=false&theme=cl&loungeIdToken=<token>` → 200 text: repeated chunks, each a line with a decimal length followed by a JSON array `[[<eventId>,["<type>",…args]],…]` that may span several lines. `["c","<SID>",…]` gives the SID, `["S","<gsessionid>"]` the gsessionid; the last event id is the largest `eventId` seen. 401 → `LoungeException("bind", "YouTube rejected the lounge token")`; other non-200 → `LoungeException("bind", "YouTube answered HTTP <n>")`; SID or gsessionid missing → `LoungeException("bind", "YouTube's answer had no session")`.
3. Play: `<lounge>/bc/bind?name=Home+Control&loungeIdToken=<token>&SID=<sid>&AID=<lastEventId>&gsessionid=<gsessionid>&device=REMOTE_CONTROL&app=youtube-desktop&VER=8&v=2&RID=2`, body `count=1&ofs=1&req0__sc=setPlaylist&req0_videoId=<videoId>` → 200. 400, 404 or 410 → `LoungeException("setPlaylist", "YouTube dropped the lounge session (HTTP <n>)")`; other non-200 → `LoungeException("setPlaylist", "YouTube answered HTTP <n>")`.
4. `YouTubeException(UNREACHABLE)` from `YouTubeHttp` is wrapped as `LoungeException(<step>, "could not reach YouTube")`.

**Route execution (normative).** `YouTubeLoungeRouteExecutor.execute(Route.YouTubeLounge route, Device device)`:
1. `devices.query(device.id(), new CastAppQuery("233637DE", "urn:x-cast:com.google.youtube.mdx", Map.of("type", "getMdxSessionStatus"), "mdxSessionStatus"))`. `DeviceOfflineException` and `UnsupportedActionException` propagate unchanged (D3 maps them to 409/422). `ActionFailedException e` → fail with `the YouTube receiver did not answer (<e.message>)`.
2. `screenId` = `reply.data.screenId` (string); blank or missing → fail with `the YouTube receiver did not report a screen id`.
3. `remoteId` = `settings.loungeRemoteId()`, or a new `UUID.randomUUID().toString()` saved with `withLoungeRemoteId` when null.
4. `token = lounge.loungeToken(screenId)`; `session = lounge.bind(token, remoteId)`; `lounge.setPlaylist(token, session, route.videoId())`; `LoungeException e` → fail with `e.getMessage()`.
5. fail = `throw new ActionFailedException(device.name() + ": YouTube Cast (best effort, unofficial API) failed: " + reason + ". Use the YouTube app route, or switch YouTube Cast off for this device in Setup.")`.

- [ ] **Step 1: Write the fixtures**

`lounge-token-batch.json`:

```json
{
  "screens": [
    {
      "screenId": "fixture-screen-6hq3r1ukd0n5mc3t2v8p",
      "refreshIntervalInMillis": 1123200000,
      "remoteRefreshIntervalMs": 79200000,
      "refreshIntervalMs": 1123200000,
      "loungeTokenLifespanMs": 1209600000,
      "loungeToken": "AGdO5p8FixtureLoungeToken-xYz0123456789",
      "remoteRefreshIntervalInMillis": 79200000,
      "expiration": 1789372800000
    }
  ]
}
```

`lounge-bind.txt` (exact content; the length lines are what YouTube sends and the parser does not rely on them):

```text
278
[[0,["c","8A3F2E1D0C9B8A77","",8]
]
,[1,["S","fixture-gsessionid-Qm9vYmFy"]]
,[2,["loungeStatus",{"devices":"[{\"app\":\"lb-v4\",\"name\":\"YouTube on TV\",\"id\":\"fixture-screen-6hq3r1ukd0n5mc3t2v8p\",\"type\":\"LOUNGE_SCREEN\"}]"}]]
,[3,["playlistModified",{"videoIds":""}]]
]
52
[[4,["onAutoplayModeChanged",{"autoplayMode":"UNSUPPORTED"}]]
]
```

`cast-mdx-session-status.json`:

```json
{ "type": "mdxSessionStatus", "data": { "screenId": "fixture-screen-6hq3r1ukd0n5mc3t2v8p", "deviceId": "4b7e4c3a-1d2f-4e5a-9b8c-7d6e5f4a3b2c" } }
```

Add to `FakeGoogleServer`:

```java
    /** Lounge: token for the fixture screen, a bind answer, setPlaylist accepted. */
    public FakeGoogleServer loungeAccepts() {
        respond("POST", "/lounge/pairing/get_lounge_token_batch", Canned.fixture(200, "lounge-token-batch.json"));
        respondWhen("POST", "/lounge/bc/bind", r -> "1".equals(r.query().get("RID")),
                new Canned(200, "text/plain; charset=utf-8", fixture("lounge-bind.txt").getBytes(StandardCharsets.UTF_8)));
        respondWhen("POST", "/lounge/bc/bind", r -> "2".equals(r.query().get("RID")),
                new Canned(200, "text/plain; charset=utf-8", "7\n[[5,[]]\n".getBytes(StandardCharsets.UTF_8)));
        return this;
    }
```

- [ ] **Step 2: Write the failing tests**

`core/playback/YouTubeRoutesTest.java` — pure; `planner = new HomeControlConfiguration().playbackPlanner()` (if the bean method needs arguments, build the same strategy list by hand in the order above); `item` = a `ContentItem` built like `YouTubeVideo.toItem()` for `aqz-KE-bpKQ` (write it out; core tests must not import `sources`); `lounge` = the same item with playables `[AppLink, YouTubeLounge("aqz-KE-bpKQ")]`:
- `androidTvOpensTheYouTubeApp`: caps `{REMOTE_KEYS, POWER, VOLUME, APP_LINK}` → `Route.OpenAppLink(https://www.youtube.com/watch?v=aqz-KE-bpKQ, "youtube")`, `describe()` `Open in the YouTube app`.
- `smartTvsUseTheSameAppLink`: caps `{REMOTE_KEYS, POWER, VOLUME, APP_LINK}` (webOS/Tizen from F) → the same route (the adapters translate it to `contentTarget` / DIAL).
- `aCastOnlyDeviceWithoutTheSwitchCannotPlayIt`: caps `{CAST_RECEIVER, VOLUME}` with `item` → `Unroutable` whose reason is `this device cannot open app links`.
- `aCastOnlyDeviceWithTheSwitchCastsThroughLounge`: caps `{CAST_RECEIVER, VOLUME}` with `lounge` → `Route.YouTubeLounge("aqz-KE-bpKQ")`, `describe()` `Cast with the YouTube receiver (best effort)`.
- `aMergedShieldPrefersTheAppAndOffersLoungeNext`: caps `{REMOTE_KEYS, POWER, VOLUME, APP_LINK, CAST_RECEIVER}` with `lounge` → `plan` = `OpenAppLink`; `routes` = `[OpenAppLink, YouTubeLounge]`.
- `aSpeakerCannotPlayYouTube`: caps `{MEDIA_RENDERER, VOLUME}` with `lounge` → `Unroutable` with `this device cannot open app links; this device is not a Cast receiver`.

`RouteKeysTest` add `youtubeLoungeKey`: `key(new Route.YouTubeLounge("x"))` = `youtube-lounge`; `optimistic` false. `PlaybackPlannerTest` add `loungeNeedsACastReceiver` (explanation text as above).

`playback/PlaybackServiceTest` add:
- `aLoungeRouteRunsThroughItsExecutor`: planner with `YouTubeLoungeStrategy`; device caps `{CAST_RECEIVER}`; item with `YouTubeLounge`; executor stub `executes` true → `play` returns the route and the stub received `(route, device)`; `devices.execute` never called.
- `withoutAnExecutorTheLoungeRouteIsSwitchedOff`: no executors → `UnroutableException` `Kitchen: YouTube is switched off on this server`.
- `attemptFallsThroughToLoungeAfterAFailedAppLink` (D3): caps `{APP_LINK, CAST_RECEIVER}`; `devices.execute` throws `ActionFailedException("Shield refused to open the link")` → `PlayAttempt.Failed` with `remaining` = `[YouTubeLounge]`; `attempt(item, id, Set.of("app-link"))` → `Played` via the executor.

`device/DeviceManagerQueryTest` (A/B's `StubAdapter` style; stub handles implementing `query`):
- `asksTheFirstCastAdapter`: device with adapters `androidtv` (no `CAST_RECEIVER`) and `cast` (declares it, handle answers `{type=mdxSessionStatus}`) → returned map; the Android TV handle's `query` never called.
- `offlineCastSideIsOffline`: `cast` adapter declared but no handle → `DeviceOfflineException` `<name> is not connected`.
- `noCastAdapterIsUnsupported`: → `UnsupportedActionException` `<name> is not a Cast receiver`.
- `failuresPropagate`: handle throws `ActionFailedException` → rethrown unchanged.
- `unknownDevice`: → `DeviceNotFoundException`.

`adapters/cast/CastSessionTest` add (C5's fake receiver setup):
- `aQueryLaunchesTheAppAndReturnsItsReply`: `receiver.appSpeaks("233637DE", MDX)`, `receiver.answerCustom(MDX, <cast-mdx-session-status.json as ObjectNode>)` → `session.query(new CastAppQuery("233637DE", MDX, Map.of("type","getMdxSessionStatus"), "mdxSessionStatus"))` returns a map whose `data.screenId` is `fixture-screen-6hq3r1ukd0n5mc3t2v8p`; `receiver.last(RECEIVER, "LAUNCH")` has `appId` `233637DE`; `receiver.received(MDX, "")` has one message `{"type":"getMdxSessionStatus"}`.
- `anErrorReplyFailsTheQuery`: `answerCustom(MDX, {"type":"error","message":"nope"})` → `ActionFailedException` containing `refused the request (nope)`.
- `noReplyTimesOut`: `appSpeaks` without `answerCustom` → `ActionFailedException` containing `did not answer in time` (use B's short test timeouts).
- `aDisconnectedSessionIsOffline`: before connect → `DeviceOfflineException`.

`sources/youtube/YouTubeVideoIdsTest` — the same parameterized URLs as F's `ContentLinksTest`: `findsTheVideoId` (`https://www.youtube.com/watch?v=aqz-KE-bpKQ`, `https://www.youtube.com/watch?feature=share&v=aqz-KE-bpKQ&t=30`, `https://m.youtube.com/watch?v=aqz-KE-bpKQ`, `https://youtu.be/aqz-KE-bpKQ`, `https://youtu.be/aqz-KE-bpKQ?si=abc`, `https://www.youtube.com/shorts/aqz-KE-bpKQ`, `https://www.youtube.com/live/aqz-KE-bpKQ`, `https://www.youtube.com/embed/aqz-KE-bpKQ` → `aqz-KE-bpKQ`) and `noVideoId` (`https://www.youtube.com/`, `https://www.youtube.com/watch?v=short`, `https://www.youtube.com/channel/UC123`, `https://example.org/watch?v=aqz-KE-bpKQ`, `https://notyoutube.com/watch?v=aqz-KE-bpKQ` → empty).

`LoungeClientTest` (fake `loungeAccepts()`):
- `getsALoungeToken`: `loungeToken("fixture-screen-6hq3r1ukd0n5mc3t2v8p")` = `AGdO5p8FixtureLoungeToken-xYz0123456789`; raw body `screen_ids=fixture-screen-6hq3r1ukd0n5mc3t2v8p`; `content-type` form.
- `bindsWithTheDocumentedForm`: `bind(token, "4f1c…")` → recorded raw query `RID=1&VER=8&CVER=1&auth_failure_option=send_error`; raw body exactly `app=web&mdx-version=3&name=Home+Control&id=4f1c…&device=REMOTE_CONTROL&capabilities=que%2Cdsdtr%2Catp&magnaKey=cloudPairedDevice&ui=false&theme=cl&loungeIdToken=AGdO5p8FixtureLoungeToken-xYz0123456789`; session `sid` `8A3F2E1D0C9B8A77`, `gsessionId` `fixture-gsessionid-Qm9vYmFy`, `lastEventId` 4.
- `parseBindHandlesChunksAcrossLines`: `parseBind(fixture)` as above; `parseBind("12\n[[0,[\"noop\"]]]\n")` → `LoungeException` `bind: YouTube's answer had no session`.
- `setsThePlaylist`: raw query `name=Home+Control&loungeIdToken=AGdO…&SID=8A3F2E1D0C9B8A77&AID=4&gsessionid=fixture-gsessionid-Qm9vYmFy&device=REMOTE_CONTROL&app=youtube-desktop&VER=8&v=2&RID=2`; raw body `count=1&ofs=1&req0__sc=setPlaylist&req0_videoId=aqz-KE-bpKQ`.
- `failuresNameTheStep` (parameterized): token 500 → `lounge token: YouTube answered HTTP 500`; token `{"screens":[]}` → `lounge token: YouTube did not issue a lounge token for this screen`; bind 401 → `bind: YouTube rejected the lounge token`; play 410 → `setPlaylist: YouTube dropped the lounge session (HTTP 410)`; closed server → `lounge token: could not reach YouTube`. No message contains the lounge token, SID or gsessionid.
- `sessionToStringIsRedacted`.

`YouTubeLoungeResolverTest` (mock `YouTubeSetupService` with settings whose `loungeDevices` = `{kitchen}`):
- `resolvesOnlyYouTubeVideoLinks`: `resolves(AppLink(watch?v=aqz-KE-bpKQ,"youtube"))` true; `AppLink(https://www.youtube.com/,"youtube")` false; `AppLink(netflix…,"netflix")` false; `StreamUrl` false.
- `addsLoungeForSwitchedOnCastDevices`: device `kitchen`, caps `{CAST_RECEIVER}` → playables `[the AppLink, YouTubeLounge("aqz-KE-bpKQ")]`, no live caps, no notes.
- `leavesOtherDevicesAlone`: device `living` (switch off) with `{CAST_RECEIVER, APP_LINK}` → `[the AppLink]`; device `kitchen` without `CAST_RECEIVER` → `[the AppLink]`.

`YouTubeLoungeRouteExecutorTest` (mock `DeviceManager`; real `LoungeClient` on the fake; mock setup service with a settings holder):
- `castsThroughTheLounge`: `devices.query` returns the MDX fixture as a map → token, bind and setPlaylist requests in that order with `req0_videoId=aqz-KE-bpKQ`; the query sent was `CastAppQuery("233637DE", "urn:x-cast:com.google.youtube.mdx", {type=getMdxSessionStatus}, "mdxSessionStatus")`.
- `aRemoteIdIsCreatedOnceAndReused`: settings without remote id → saved settings carry a UUID; the bind body's `id` equals it; a second play with those settings sends the same id and saves nothing.
- `receiverProblemsAreExplicit`: `query` throws `ActionFailedException("Kitchen did not answer in time when asked to answer mdxSessionStatus")` → `ActionFailedException` whose message starts `Kitchen: YouTube Cast (best effort, unofficial API) failed: the YouTube receiver did not answer (` and ends with `switch YouTube Cast off for this device in Setup.`; a reply without `data.screenId` → `…failed: the YouTube receiver did not report a screen id…`.
- `offlineAndUnsupportedPassThrough`: `DeviceOfflineException` and `UnsupportedActionException` from `query` are rethrown unchanged.
- `loungeFailuresAreExplicit`: bind 401 → message contains `failed: bind: YouTube rejected the lounge token`.
- `executesOnlyLoungeRoutes`: `executes(new Route.YouTubeLounge("x"))` true; `executes(new Route.OpenAppLink(…))` false.

`YouTubeSetupServiceTest` add:
- `loungeSwitchNeedsACastDevice`: `DeviceManager` mock: `kitchen` with `{CAST_RECEIVER}`, `living` with `{APP_LINK}` → `setLounge("kitchen", true)` stores `kitchen`; `setLounge("living", true)` → `INVALID_INPUT` `Only Cast devices can use YouTube Cast`; `setLounge("gone", true)` → `INVALID_INPUT` `No device with id gone`; `setLounge("gone", false)` removes it without checks.

`YouTubeSetupControllerTest` add:
- `loungeEndpoint`: `POST /setup/sources/youtube/lounge` `device=kitchen&enabled=true` → `verify(setup).setLounge("kitchen", true)`, flash `YouTube Cast switched on for Kitchen` (the service returns the device name — make `setLounge` return `String` name).

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.adapters.cast.*' --tests 'dev.andre.homecontrol.sources.youtube.*'`
Expected: compilation failure — `CastAppQuery`, `Route.YouTubeLounge` and the Lounge classes do not exist.

- [ ] **Step 4: Core types and the planner**

`core/CastAppQuery.java`:

```java
package dev.andre.homecontrol.core;

import java.util.Map;

/**
 * A question for a Cast receiver app whose answer the caller needs, e.g. the YouTube receiver's lounge screen id.
 * {@code message} is sent unchanged on {@code namespace}; the first reply whose {@code type} is {@code replyType} answers.
 */
public record CastAppQuery(String receiverAppId, String namespace, Map<String, Object> message, String replyType) {

    public CastAppQuery {
        message = Map.copyOf(message);
    }
}
```

`core/DeviceHandle.java` — add:

```java
    /** Asks a receiver app and returns its reply. Only Cast connections answer; others are unsupported. */
    default java.util.Map<String, Object> query(CastAppQuery query) {
        throw new UnsupportedActionException("This connection cannot ask receiver apps");
    }
```

`device/DeviceManager.java` — add (read the handle map exactly the way the real `execute` does, including any lock):

```java
    /** Like {@link #execute}, for a question with an answer; only adapters declaring CAST_RECEIVER are asked. */
    public Map<String, Object> query(String id, CastAppQuery query) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException("No device with id " + id));
        Map<String, DeviceHandle> deviceHandles = handles.getOrDefault(id, Map.of());
        RuntimeException offline = null;
        RuntimeException unsupported = null;
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null || !adapter.capabilities(device).contains(Capability.CAST_RECEIVER)) {
                continue;
            }
            DeviceHandle handle = deviceHandles.get(adapterId);
            if (handle == null) {
                offline = offline != null ? offline : new DeviceOfflineException(device.name() + " is not connected");
                continue;
            }
            try {
                return handle.query(query);
            } catch (DeviceOfflineException e) {
                offline = offline != null ? offline : e;
            } catch (UnsupportedActionException e) {
                unsupported = e;
            }
        }
        if (offline != null) {
            throw offline;
        }
        if (unsupported != null) {
            throw unsupported;
        }
        throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
    }
```

`core/playback/PlayableRef.java` — add:

```java
    /** A YouTube video to start through the unofficial Lounge API on a Cast receiver (best effort). */
    record YouTubeLounge(String videoId) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "YouTube Cast";
        }
    }
```

`core/playback/Route.java` — add:

```java
    /** Executed by a source-side {@link RouteExecutor}; the Cast part goes through DeviceManager.query. */
    record YouTubeLounge(String videoId) implements Route {
        @Override
        public String describe() {
            return "Cast with the YouTube receiver (best effort)";
        }
    }
```

`core/playback/YouTubeLoungeStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Cast rung for YouTube videos the Lounge resolver attached (only for devices whose switch is on). */
public class YouTubeLoungeStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.YouTubeLounge.class::isInstance)
                .map(PlayableRef.YouTubeLounge.class::cast)
                .findFirst()
                .map(lounge -> new Route.YouTubeLounge(lounge.videoId()));
    }
}
```

Then fix every exhaustive switch the compiler reports (find them with `grep -rn "case Route\.\|case PlayableRef\." src/main`):
- `PlaybackPlanner.explain`: `case PlayableRef.YouTubeLounge ignored -> reasons.add("this device is not a Cast receiver");`
- `RouteKeys.key`: `case Route.YouTubeLounge ignored -> "youtube-lounge";`
- `PlaybackService.execute`:

```java
            case Route.YouTubeLounge lounge -> executors.stream()
                    .filter(executor -> executor.executes(lounge))
                    .findFirst()
                    .orElseThrow(() -> new UnroutableException(device.name() + ": YouTube is switched off on this server"))
                    .execute(lounge, device);
```

- `HomeControlConfiguration.playbackPlanner`: `new PlaybackPlanner(List.of(new JellyfinSessionStrategy(), new AppLinkStrategy(), new YouTubeLoungeStrategy(), new CastMessageStrategy(), new CastLoadStrategy(), new CastStreamStrategy()))` (keep any strategy F or I added in its place).

- [ ] **Step 5: Cast session query**

`adapters/cast/protocol/CastPayloads.java` — add:

```java
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode payload) {
        return MAPPER.convertValue(payload, Map.class);
    }
```

`adapters/cast/CastSession.java` — add (next to C5's `customMessage`):

```java
    @Override
    public Map<String, Object> query(CastAppQuery query) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(query.receiverAppId()))
                .orElseGet(() -> launch(current, query.receiverAppId()));
        ReceiverStatus.ReceiverApp app = running.speaks(query.namespace())
                ? running : awaitNamespace(current, query.receiverAppId(), query.namespace());
        call(() -> {
            current.connect(app.transportId());
            return null;
        }, "reach " + app.displayName());
        CastConnection.Waiter answer = current.expect(incoming -> query.namespace().equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && (query.replyType().equals(incoming.type()) || CUSTOM_ERROR_TYPES.contains(incoming.type())));
        try {
            CastIncoming reply = call(() -> {
                current.send(query.namespace(), app.transportId(), CastPayloads.custom(query.message()));
                return answer.await(Duration.ofSeconds(properties.commandTimeoutSeconds()));
            }, "answer " + query.replyType());
            if (!query.replyType().equals(reply.type())) {
                String reason = reply.payload().path("message").asString("");
                throw new ActionFailedException(device.name() + " refused the request ("
                        + (reason.isBlank() ? reply.type() : reason) + ")");
            }
            return CastPayloads.toMap(reply.payload());
        } finally {
            answer.cancel();
        }
    }
```

(Use the session's real field names for `properties`, `device`, `receiver`; `call` is B's helper that turns `CastTimeoutException` into `ActionFailedException("<device> did not answer in time when asked to <what>")` and I/O into `DeviceOfflineException`.)

- [ ] **Step 6: YouTube side**

`sources/youtube/YouTubeVideoIds.java` — same rules as F's `ContentLinks.youtubeVideoId`: host lower-cased; `youtu.be` → first path segment; `youtube.com` or a subdomain → `v` query parameter on `/watch`, or the segment after `/shorts/`, `/live/`, `/embed/`; the id must match `[A-Za-z0-9_-]{11}`.

`sources/youtube/LoungeException.java`:

```java
package dev.andre.homecontrol.sources.youtube;

/** A failed Lounge step. The message names the step and never contains a token or session id. */
public class LoungeException extends RuntimeException {
    public LoungeException(String step, String detail) {
        super(step + ": " + detail);
    }
}
```

`sources/youtube/LoungeClient.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The unofficial YouTube Lounge API, reduced to "start this video on that screen". Best effort: YouTube changes it
 * without notice. Wire format as observed in casttube (MIT) and pyytlounge; no code taken from either.
 */
public class LoungeClient {

    public static final String NAME = "Home Control";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record LoungeSession(String sid, String gsessionId, long lastEventId) {
        @Override
        public String toString() {
            return "LoungeSession[lastEventId=" + lastEventId + "]";
        }
    }

    private final YouTubeHttp http;
    private final URI base;

    public LoungeClient(YouTubeHttp http, URI loungeBaseUrl) {
        this.http = http;
        this.base = loungeBaseUrl;
    }

    public String loungeToken(String screenId) {
        YouTubeHttp.Response response = post("lounge token", URI.create(base + "/pairing/get_lounge_token_batch"),
                Map.of("screen_ids", screenId));
        if (!response.ok()) {
            throw new LoungeException("lounge token", "YouTube answered HTTP " + response.status());
        }
        String token;
        try {
            token = response.json().path("screens").path(0).path("loungeToken").asString("");
        } catch (YouTubeException notJson) {
            token = "";
        }
        if (token.isBlank()) {
            throw new LoungeException("lounge token", "YouTube did not issue a lounge token for this screen");
        }
        return token;
    }

    public LoungeSession bind(String loungeToken, String remoteId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("RID", "1");
        query.put("VER", "8");
        query.put("CVER", "1");
        query.put("auth_failure_option", "send_error");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("app", "web");
        form.put("mdx-version", "3");
        form.put("name", NAME);
        form.put("id", remoteId);
        form.put("device", "REMOTE_CONTROL");
        form.put("capabilities", "que,dsdtr,atp");
        form.put("magnaKey", "cloudPairedDevice");
        form.put("ui", "false");
        form.put("theme", "cl");
        form.put("loungeIdToken", loungeToken);
        YouTubeHttp.Response response = post("bind", YouTubeHttp.uri(base, "/bc/bind", query), form);
        if (response.status() == 401) {
            throw new LoungeException("bind", "YouTube rejected the lounge token");
        }
        if (!response.ok()) {
            throw new LoungeException("bind", "YouTube answered HTTP " + response.status());
        }
        return parseBind(response.text());
    }

    public void setPlaylist(String loungeToken, LoungeSession session, String videoId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("name", NAME);
        query.put("loungeIdToken", loungeToken);
        query.put("SID", session.sid());
        query.put("AID", String.valueOf(session.lastEventId()));
        query.put("gsessionid", session.gsessionId());
        query.put("device", "REMOTE_CONTROL");
        query.put("app", "youtube-desktop");
        query.put("VER", "8");
        query.put("v", "2");
        query.put("RID", "2");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("count", "1");
        form.put("ofs", "1");
        form.put("req0__sc", "setPlaylist");
        form.put("req0_videoId", videoId);
        YouTubeHttp.Response response = post("setPlaylist", YouTubeHttp.uri(base, "/bc/bind", query), form);
        if (Set.of(400, 404, 410).contains(response.status())) {
            throw new LoungeException("setPlaylist", "YouTube dropped the lounge session (HTTP " + response.status() + ")");
        }
        if (!response.ok()) {
            throw new LoungeException("setPlaylist", "YouTube answered HTTP " + response.status());
        }
    }

    /** Length-prefixed chunks of {@code [[eventId,[type,…]],…]}; a chunk's JSON may span lines. */
    public static LoungeSession parseBind(String body) {
        String sid = "";
        String gsessionId = "";
        long lastEventId = -1;
        StringBuilder chunk = new StringBuilder();
        for (String line : body.split("\n")) {
            if (chunk.isEmpty() && line.strip().matches("\\d+")) {
                continue;
            }
            chunk.append(line).append('\n');
            JsonNode events;
            try {
                events = MAPPER.readTree(chunk.toString());
            } catch (JacksonException incomplete) {
                continue;
            }
            chunk.setLength(0);
            for (JsonNode event : events) {
                lastEventId = Math.max(lastEventId, event.path(0).asLong(-1));
                JsonNode payload = event.path(1);
                switch (payload.path(0).asString("")) {
                    case "c" -> sid = payload.path(1).asString("");
                    case "S" -> gsessionId = payload.path(1).asString("");
                    default -> {
                    }
                }
            }
        }
        if (sid.isBlank() || gsessionId.isBlank()) {
            throw new LoungeException("bind", "YouTube's answer had no session");
        }
        return new LoungeSession(sid, gsessionId, Math.max(lastEventId, 0));
    }

    private YouTubeHttp.Response post(String step, URI uri, Map<String, String> form) {
        try {
            return http.postForm(uri, form, Map.of());
        } catch (YouTubeException e) {
            throw new LoungeException(step, "could not reach YouTube");
        }
    }
}
```

`sources/youtube/YouTubeLoungeResolver.java`:

```java
package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;

import java.util.List;
import java.util.Set;

/** Adds the best-effort Lounge reference next to a YouTube app link, only for Cast devices whose switch is on. */
public class YouTubeLoungeResolver implements PlayableResolver {

    private final YouTubeSetupService setup;

    public YouTubeLoungeResolver(YouTubeSetupService setup) {
        this.setup = setup;
    }

    @Override
    public boolean resolves(PlayableRef ref) {
        return ref instanceof PlayableRef.AppLink link && "youtube".equals(link.service())
                && YouTubeVideoIds.fromUrl(link.uri()).isPresent();
    }

    @Override
    public Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities) {
        PlayableRef.AppLink link = (PlayableRef.AppLink) ref;
        if (!capabilities.contains(Capability.CAST_RECEIVER) || !setup.settings().loungeDevices().contains(device.id())) {
            return new Resolution(List.of(link), Set.of(), List.of());
        }
        String videoId = YouTubeVideoIds.fromUrl(link.uri()).orElseThrow();
        return new Resolution(List.of(link, new PlayableRef.YouTubeLounge(videoId)), Set.of(), List.of());
    }
}
```

`sources/youtube/YouTubeLoungeRouteExecutor.java` — implements **Route execution** exactly (reading `data.screenId` from the `Map<String,Object>` with `instanceof Map<?,?> data` and `data.get("screenId") instanceof String id`).

`YouTubeSetupService` — gains `ObjectProvider<DeviceManager> devices`; `String setLounge(String deviceId, boolean enabled)` per the test (returns the device name, or the id when the device is gone); `View.loungeDevices` built in the advice from `DeviceManager.devices()` filtered by `capabilities(id).contains(CAST_RECEIVER)`.

`YouTubeSetupController` — `POST /setup/sources/youtube/lounge`.

`YouTubeConfiguration` — beans `LoungeClient youTubeLoungeClient(YouTubeHttp, YouTubeProperties)` (`loungeBaseUrl`), `YouTubeLoungeResolver`, `YouTubeLoungeRouteExecutor youTubeLoungeRouteExecutor(DeviceManager, LoungeClient, YouTubeSetupService)`. `PlaybackService` picks resolvers and executors up through C7's `ObjectProvider`s.

`fragments/youtube-setup.html` — a `<fieldset>` shown whenever the module is enabled (it needs no Google account):

```html
<fieldset class="youtube-lounge">
  <legend>YouTube Cast (best effort)</legend>
  <p class="hint">Chromecasts and other Cast-only screens cannot open the YouTube app link. For them Home Control can
    start videos through YouTube's unofficial “Lounge” interface that phones use. Google does not document it and
    changes it without notice, so it can stop working at any time. Devices that can open the YouTube app always try
    the app first.</p>
  <p class="hint" th:if="${#lists.isEmpty(youtube.loungeDevices())}">No Cast devices are registered.</p>
  <form method="post" action="/setup/sources/youtube/lounge" th:each="d : ${youtube.loungeDevices()}">
    <input type="hidden" name="device" th:value="${d.id()}">
    <input type="hidden" name="enabled" th:value="${!d.enabled()}">
    <span th:text="${d.name()}">Kitchen</span>
    <button type="submit" th:text="${d.enabled()} ? 'Switch YouTube Cast off' : 'Switch YouTube Cast on'">Switch YouTube Cast on</button>
  </form>
</fieldset>
```

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.adapters.cast.*' --tests 'dev.andre.homecontrol.sources.youtube.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS (including A's app-link tests, B's Cast tests, C's Jellyfin route tests, D's play-attempt tests and F's deep-link tests); BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core src/main/java/dev/andre/homecontrol/device \
  src/main/java/dev/andre/homecontrol/adapters/cast src/main/java/dev/andre/homecontrol/playback \
  src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java src/main/java/dev/andre/homecontrol/sources/youtube \
  src/main/resources/templates/fragments/youtube-setup.html \
  src/test/java/dev/andre/homecontrol/core src/test/java/dev/andre/homecontrol/device \
  src/test/java/dev/andre/homecontrol/adapters/cast src/test/java/dev/andre/homecontrol/playback \
  src/test/java/dev/andre/homecontrol/sources/youtube src/test/resources/fixtures/youtube
git commit -m "feat: play YouTube through the app link, with best-effort Lounge casting per device"
```

---

### Task 6: E6 · Fixtures and acceptance

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/sources/youtube/YouTubeFixtureContractTest.java`, `src/test/java/dev/andre/homecontrol/web/YouTubeEndToEndTest.java`, `docs/superpowers/reviews/2026-09-16-youtube-source-acceptance.md`
- Modify: `README.md`
- Uses unchanged: `FakeGoogleServer` (Tasks 1–5), `FakeCastReceiver` (B + C5), `FakeRemoteServer` (A), every fixture under `src/test/resources/fixtures/youtube/`.

**Interfaces:**
- Consumes: the whole application context; the device-adoption helpers used by C9's `JellyfinEndToEndTest` (`DeviceManager.adopt/state/forget`, `CertificateStore.loadOrCreate`, `AndroidTvSettings.device`, a Cast device with a `cast` adapter entry pointing at `FakeCastReceiver`); endpoints `/setup`, `/setup/sources/youtube/connect|authorization|lounge|disconnect`, `/login`, `/sources/youtube/rails/{id}`, `/sources/youtube/rails/{id}/refresh`, `/sources/youtube/thumbnails/{id}`, `/search/results`, `/search/results/{sourceId}`, `/devices/{id}/route-preview`, `/devices/{id}/play-attempt`; `YouTubeVideoMapper`, `LoungeClient.parseBind`, `GoogleOAuthClient`, `YouTubeApiClient`.
- Produces: contract tests pinning every fixture's shape; an end-to-end proof over real sockets of connect → authorize → gated rails → quota display → on-demand search → app-link play on Android TV → Lounge play on a Cast device → explicit Lounge failure → disconnect, with no credential in any browser-bound response; the manual checklist; README documentation.

- [ ] **Step 1: Fixture contract test**

`YouTubeFixtureContractTest` (plain JUnit, `JsonMapper`):
- `theFixtureSetIsDeliberate`: the file names in `src/test/resources/fixtures/youtube/` (list the classpath directory through `Path.of(getClass().getResource("/fixtures/youtube").toURI())`) equal exactly the 28 names in the File Structure Map fixture list.
- `everyJsonFixtureParses`: every `*.json` parses to an object.
- `oauthFixturesHaveGooglesShape`: `oauth-device-code.json` has non-blank `device_code`, `user_code`, `verification_url` and positive `expires_in`, `interval`; every `oauth-token-*`/`oauth-refresh-*`/`oauth-invalid-*` fixture has either (`access_token`, `expires_in`, `token_type` `Bearer`) or (`error`, `error_description`); `oauth-token-granted.json` alone among the success ones has `refresh_token`.
- `listResponsesHaveKindItemsAndPageInfo`: every `subscriptions-*`, `channels-*`, `playlist-items-*`, `playlists-*`, `search-*`, `videos-*` fixture has `kind` ending with `ListResponse`, an `items` array, and `pageInfo.totalResults ≥ items.size()` (search excepted from the inequality).
- `errorFixturesHaveGooglesErrorShape`: every `error-*` fixture has `error.code`, `error.message` and `error.errors[0].reason`.
- `everyPlaylistItemIsMappableOrDeliberatelyUnavailable`: across all `playlist-items-*` fixtures, each item either maps through `YouTubeVideoMapper.fromPlaylistItem` or has `snippet.title` ∈ {`Private video`, `Deleted video`}; the unavailable count is exactly 1.
- `videoIdsAreValid`: every `videoId` and `id.videoId` in all fixtures matches `[A-Za-z0-9_-]{11}`.
- `searchTitlesAreEscapedLikeTheApi`: at least one `search-videos.json` title contains `&amp;` and `&#39;`.
- `uploadsPlaylistsBelongToSubscribedChannels`: each `channels-uploads.json` id appears as a `resourceId.channelId` in the subscription pages, and each uploads id has a matching `playlist-items-uploads-*.json` whose items' `playlistId` equals it.
- `loungeFixturesParse`: `lounge-token-batch.json` has a non-blank `screens[0].loungeToken`; `LoungeClient.parseBind(lounge-bind.txt)` gives SID `8A3F2E1D0C9B8A77`; `cast-mdx-session-status.json` `type` is `mdxSessionStatus` and its `data.screenId` equals the token fixture's `screenId`.

- [ ] **Step 2: End-to-end test**

`src/test/java/dev/andre/homecontrol/web/YouTubeEndToEndTest.java` — `@SpringBootTest(webEnvironment = RANDOM_PORT)`; a static `FakeGoogleServer GOOGLE` started in a static initializer; `@DynamicPropertySource` sets `shield.data-dir` to a fresh temp dir, `home-control.youtube.oauth-base-url` = `GOOGLE.base() + "/oauth"`, `api-base-url` = `/youtube/v3`, `lounge-base-url` = `/lounge`, `thumbnail-base-url` = `/thumbs`, `home-control.content.rails.scheduler-enabled=false`, and whatever C9's end-to-end test sets for Cast and Android TV fakes. HTTP through `java.net.http.HttpClient` with a cookie manager and redirects disabled, sending `Origin: http://localhost:<port>` on POSTs (C1's cross-origin guard). Script `GOOGLE.oauthApproves().youtubeLibrary().thumbnails()` and override the device code with `interval: 1` (`respond("POST","/oauth/device/code", Canned.json(200, <fixture with "interval": 1>))`), `GOOGLE.respond("GET","/youtube/v3/search", fixture search-videos.json)`. A `FakeRemoteServer` paired as Android TV device `shield`, a `FakeCastReceiver` registered as Cast-only device `kitchen` named `Kitchen` with `appSpeaks("233637DE", MDX)` and `answerCustom(MDX, cast-mdx-session-status.json)`. Collect every response body the test receives in `List<String> bodies`.

Ordered steps in one test method `youtubeFromConnectToCast` (use `@TestMethodOrder` only if split):
1. `GET /setup` → 200 without login; body contains `YouTube`, `TVs and Limited Input devices`, `YouTube Cast (best effort)` and a `Kitchen` switch.
2. `POST /setup/sources/youtube/connect` with `clientId=123456789012-abc123def456.apps.googleusercontent.com`, `clientSecret=GOCSPX-fixtureClientSecret`, `loginPassword` and confirmation `correct-horse-1` → 302 `/setup#youtube`; a session cookie is set; the fake recorded a device-code request.
3. Poll `GET /setup/sources/youtube/authorization` (Awaitility, ≤ 10 s) until the response has `HX-Refresh: true`; earlier answers contain `GQVQ-JKEC`. The fake then has a token request with `grant_type=urn:ietf:params:oauth:grant-type:device_code` and a `/youtube/v3/channels?mine=true` request (post-connect hook).
4. A second client without the cookie: `GET /sources/youtube/rails/subscriptions` → 401 (C1 gate); `GET /` → 302 to `/login`.
5. `GET /setup` → contains `Connected as Andre at Home` and `of 10000 units`.
6. `POST /sources/youtube/rails/subscriptions/refresh` → 202; await `GET /sources/youtube/rails/subscriptions` → 200 with `status` `READY` and item ids `[Kz1aT5nM3pQ, Pm6Jd3Fg0kU, aqz-KE-bpKQ, Hh7Lq2Wv9sE]`; artwork `/sources/youtube/thumbnails/Kz1aT5nM3pQ`.
7. `GET /sources/youtube/thumbnails/aqz-KE-bpKQ` → 200 `image/jpeg`.
8. `GET /search/results?q=bunny` → contains `Search YouTube` and `20 of 20 YouTube searches left today`; `GOOGLE.count("/youtube/v3/search")` = 0. `GET /search/results/youtube?q=bunny` → contains `data-item="aqz-KE-bpKQ"`; count = 1. Repeat → count still 1.
9. `POST /devices/shield/play-attempt` `source=youtube&item=aqz-KE-bpKQ` → 200 JSON `played` true, `route.key` `app-link`, `route.description` `Open in the YouTube app`; the `FakeRemoteServer` captured the app link `https://www.youtube.com/watch?v=aqz-KE-bpKQ`.
10. `GET /devices/kitchen/route-preview?source=youtube&item=aqz-KE-bpKQ` → `playable` false, `reason` contains `this device cannot open app links`.
11. `POST /setup/sources/youtube/lounge` `device=kitchen&enabled=true` → 302; route preview → `route.key` `youtube-lounge`, description `Cast with the YouTube receiver (best effort)`.
12. `GOOGLE.loungeAccepts()`; `POST /devices/kitchen/play-attempt` → 200 `played` true; the fake receiver got `{"type":"getMdxSessionStatus"}` on the MDX namespace after a `LAUNCH` of `233637DE`; `GOOGLE.requests("/lounge/bc/bind")` has two requests, the second with form `req0_videoId=aqz-KE-bpKQ`.
13. `GOOGLE.respondWhen("POST","/lounge/bc/bind", r -> "1".equals(r.query().get("RID")), Canned.json(401, "{}"))`; play-attempt → 502, `message` contains `Kitchen: YouTube Cast (best effort, unofficial API) failed: bind: YouTube rejected the lounge token`.
14. `GET /setup` → contains `107 of 10000 units` and `1 of 20 searches` (channels.list 2 — the post-connect channel lookup and the uploads lookup — + subscriptions.list 2 + playlistItems.list 3 + search.list 100; playing known tiles costs nothing). Quota exhaustion itself is covered by `QuotaLedgerTest`, `YouTubeApiClientTest` and `SubscriptionsFeedTest`.
15. `POST /setup/sources/youtube/disconnect` → 302; the fake recorded `/oauth/revoke` with `token=1//0gFixtureRefreshTokenGranted-0001`; `GET /setup` with the cookie → contains the Connect form again; a new cookie-less client can `GET /` without login (only YouTube secrets existed); the `Kitchen` switch is still on.
16. No secret leaked: none of `bodies` contains `GOCSPX-fixtureClientSecret`, `1//0gFixture`, `ya29.`, `AH-1Ng2m`, `AGdO5p8Fixture`, `8A3F2E1D0C9B8A77`, `fixture-gsessionid`; the application log captured with `OutputCaptureExtension` contains none of them either.

(If C9's test infrastructure registers devices differently, follow it; keep the device ids `shield` and `kitchen` or adapt the assertions.)

- [ ] **Step 3: Acceptance checklist**

`docs/superpowers/reviews/2026-09-16-youtube-source-acceptance.md`:

```markdown
# YouTube source — manual acceptance (sub-project E)

Automated coverage: `YouTubeEndToEndTest` and the unit tests run against `FakeGoogleServer`, `FakeCastReceiver` and
`FakeRemoteServer`. Nothing below has been run against Google, YouTube or real devices. An agent must never mark an
item passed.

Environment to record: Home Control version, Google Cloud project (publishing status), Shield OS and YouTube app
version, Chromecast model and firmware, LG webOS version, Samsung model year.

## Google authorization

| # | Check | Result |
|---|---|---|
| 1 | Follow the setup page's steps in a fresh Google Cloud project; the OAuth client of type “TVs and Limited Input devices” is accepted | Pending — requires real hardware |
| 2 | Connect: the page shows a code; entering it at google.com/device on a phone and allowing access turns the page to “Connected as <channel>” within a few seconds | Pending — requires real hardware |
| 3 | Deny on the phone: the page says access was denied; let a code expire (30 min): the page says it expired | Pending — requires real hardware |
| 4 | A client of type “Web application” is refused with the “TVs and Limited Input devices” message | Pending — requires real hardware |
| 5 | Restart the container: still connected, rails load without a new code | Pending — requires real hardware |
| 6 | Consent screen left in “Testing”: after 7 days the rails show the reconnect message naming “Testing” (record the date) | Pending — requires real hardware |
| 7 | Remove Home Control's access at myaccount.google.com → Security → Third-party access: rails show the reconnect message | Pending — requires real hardware |
| 8 | YouTube Data API v3 not enabled: the rail says it is not enabled in the Cloud project | Pending — requires real hardware |
| 9 | Disconnect: the grant disappears from the Google account's third-party access list; secrets.json no longer contains YouTube entries | Pending — requires real hardware |

## Rails, quota and search

| # | Check | Result |
|---|---|---|
| 10 | “New from your subscriptions” lists the same recent uploads as youtube.com/feed/subscriptions (ignoring Shorts and live) | Pending — requires real hardware |
| 11 | After a day of normal use the quota shown in Setup is within 5 % of the Cloud console's “Queries per day” for the YouTube Data API | Pending — requires real hardware |
| 12 | Watch Later switched on: record whether YouTube returns items or the rail shows the 2016 explanation | Pending — requires real hardware |
| 13 | Load playlists, choose two: both appear as rails in playlist order; reorder and hide them in Sources | Pending — requires real hardware |
| 14 | Typing in search does not use quota; “Search YouTube” shows results and the remaining-searches note decreases by one; repeating the same search does not | Pending — requires real hardware |
| 15 | After the daily search cap the button's result explains when searches return | Pending — requires real hardware |
| 16 | Thumbnails load on a phone and the image requests go to Home Control, not to i.ytimg.com | Pending — requires real hardware |

## Playback

| # | Check | Result |
|---|---|---|
| 17 | Shield (Android TV): playing a subscription tile starts that video in the YouTube app (“Open in the YouTube app”) | Pending — requires real hardware |
| 18 | Shield with the YouTube app not in the foreground / after a reboot: the video still starts | Pending — requires real hardware |
| 19 | LG webOS: the same tile starts the video (F's content target) — record the webOS version | Pending — requires real hardware |
| 20 | Samsung Tizen: the same tile starts the video through DIAL | Pending — requires real hardware |
| 21 | Chromecast with YouTube Cast off: the play sheet says the device cannot open app links | Pending — requires real hardware |
| 22 | Chromecast with YouTube Cast on: “Cast with the YouTube receiver (best effort)” starts the video; record how long it takes and whether a phone's YouTube app shows “Home Control” as connected | Pending — requires real hardware |
| 23 | Merged Shield (Android TV + Cast) with YouTube Cast on: app link first; if it fails, the toast offers the YouTube Cast route and it works | Pending — requires real hardware |
| 24 | A Cast device with the YouTube receiver already playing something: the new video replaces it | Pending — requires real hardware |
| 25 | A pasted youtu.be link on the open-link form plays on the Shield and (with the switch on) on the Chromecast | Pending — requires real hardware |
| 26 | Record any Lounge failure message seen and the date; confirm that switching YouTube Cast off restores the plain behaviour | Pending — requires real hardware |

## Findings

(none recorded yet)
```

- [ ] **Step 4: Document**

In `README.md` add a section `## YouTube` after the Jellyfin section:
- What it shows: New from your subscriptions (hourly), optional Watch Later (and why it is usually unavailable), chosen playlists; search on request only; no recommendations (YouTube offers no API for the home feed).
- Setting up your own Google Cloud project (the seven steps from the setup page, shortened) and why it is your own project (quota, no shared client); publish the consent screen to avoid the 7-day expiry.
- Quota: 10 000 units a day per project, what costs what (subscriptions refresh ≈ 30 units per hour with default settings, a search 100 units, 20 searches/day), usage in Setup, reset at midnight Pacific time; properties `home-control.youtube.daily-quota-units`, `searches-per-day`, `channels-per-refresh`, `refresh-interval`.
- Privacy: client secret and refresh token are encrypted in `/data/secrets.json`; the access token stays in memory; thumbnails are proxied; disconnecting revokes the grant.
- Playing: the YouTube app route on Android TV, LG and Samsung; YouTube Cast for Cast-only devices is **best effort** through YouTube's unofficial Lounge interface, off by default per device, may break without notice, and never replaces the app route.
- `HOME_CONTROL_YOUTUBE_ENABLED=false` switches the module off.

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.YouTubeFixtureContractTest' --tests 'dev.andre.homecontrol.web.YouTubeEndToEndTest'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/youtube/YouTubeFixtureContractTest.java \
  src/test/java/dev/andre/homecontrol/web/YouTubeEndToEndTest.java \
  docs/superpowers/reviews/2026-09-16-youtube-source-acceptance.md README.md
git commit -m "test: YouTube end-to-end over fakes, fixture contracts and the acceptance checklist"
```

---

## Final Automated Verification

```bash
.superpowers/gradle.sh clean build
.superpowers/e2e.sh
docker compose up --build
```

Expected: build green; D7's browser suite still green (the search fragment gained an on-demand button only when an on-demand source exists); the container starts against an existing `./data` without `youtube-quota.json`, `GET /setup` shows the YouTube section with the Cloud-project steps and makes no request to Google.

## Out of scope for this plan

- TV-code pairing (`pairing/get_screen`) and Lounge sessions for non-Cast screens; Lounge now-playing, queueing, session reuse and remote control (pause, seek) of YouTube playback.
- Recommendations or a home feed (no API exists); Shorts filtering; live-stream rails; members-only and age-restricted handling beyond what the YouTube app does.
- Writing to YouTube (adding to playlists, likes); `youtube` write scope.
- Multiple Google accounts or per-user YouTube profiles.
- Automatic retry of the next route after a Lounge failure (commands are ephemeral; D3 offers the retry).
- Resume positions for YouTube items (the Data API does not expose watch progress).
