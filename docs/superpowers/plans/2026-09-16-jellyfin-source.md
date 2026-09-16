# Jellyfin Source and Playback (Sub-project C) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Jellyfin the first content source: an encrypted secret store with a mandatory single-password login once any secret exists, a Jellyfin connection set up from the setup page, Continue watching / Next up / Latest rails and library search behind a reusable `ContentSource` contract, and play-at-device through all three open routes — the Jellyfin app already open on the device (session `PlayNow`), the Jellyfin Cast receiver, and a direct stream URL for the Default Media Receiver (and later DLNA).

**Architecture:** Three layers, each behind the existing boundaries. (1) `crypto`, `storage/SecretStore`, `security` and `web/LoginController`: AES-256-GCM `secrets.json` whose key is Argon2id(`HOME_CONTROL_SECRET`) or a generated `secret.key`; the login password hash lives inside the encrypted document; a servlet filter (no Spring Security) gates every path once a login exists and refuses cross-origin state changes. (2) `core/content` (`ContentSource`, `Rail`, `RailDescriptor`, `ContentSources`) and `sources/jellyfin` (a `@ConditionalOnProperty` module: `JellyfinClient` over `java.net.http`, setup service and controller, item mapper, content source, image proxy, sessions, Cast message builder, stream builder, resolver, route executor). (3) Playback: sources attach only token-free `PlayableRef.JellyfinItem`s; at play time `PlaybackService` asks `PlayableResolver`s to turn them into concrete references (`JellyfinSession`, `CastMessage`, `StreamUrl`) plus live capabilities, then the pure planner picks the first route in spec §5.3 order (`JellyfinSessionStrategy` → `AppLinkStrategy` → `CastMessageStrategy` → `CastLoadStrategy` → `CastStreamStrategy`), and device routes run through `DeviceManager.execute` while source routes run through a `RouteExecutor`. The Cast adapter gains `Action.CastMessage` (custom-namespace messages), because the Jellyfin receiver does not accept a media `LOAD`.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle (through `.superpowers/gradle.sh`), Thymeleaf, htmx, vanilla ES modules, `java.net.http.HttpClient`, JDK `com.sun.net.httpserver.HttpServer` (test fake), JUnit 5, AssertJ, Mockito, Awaitility. One dependency line is added: `org.bouncycastle:bcprov-jdk18on:1.85` — already on the classpath transitively through `bcpkix-jdk18on:1.85`, now declared because production code uses it directly (`org.bouncycastle.crypto.generators.Argon2BytesGenerator`). Verified 2026-09-16: `https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.85/bcprov-jdk18on-1.85.jar` returns 200 and contains `Argon2BytesGenerator.class` and `Argon2Parameters.class` (latest is 1.86; stay on 1.85 to match `bcpkix`). The RFC 9106 §5.3 Argon2id test vector and the reference-implementation value used in Task 1 were reproduced with that jar on JDK 25. **No Spring Security** (see Decisions).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §4.2 (Jellyfin row: session PlayNow, Cast receiver with server URL and token, direct stream), §5.2 (`ContentItem`, `JellyfinItem` "resolved at play time"), §5.3 (planner order and rules), §6.1 (rails, play sheet, setup login password), §7 (`sources/jellyfin`, `storage` secret store, `@ConditionalOnProperty`), §8 (`sources.json`, `secrets.json`), §9 (login once a secret exists, Argon2, session cookie, rate limit, tokens never leave the server), §12 (fixtures, fakes, MockMvc). Roadmap `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md`, section C (C1–C9, GitHub #33–#41, epic #7). Built on sub-project A (`docs/superpowers/plans/2026-09-16-multi-device-core.md`) and B (`docs/superpowers/plans/2026-09-16-google-cast-adapter.md`, ADR `docs/superpowers/specs/2026-09-16-cast-sender-adr.md`).

**External references (read 2026-09-16):**
- Jellyfin Cast receiver, `github.com/jellyfin/jellyfin-chromecast` master `1a38b03`: `src/components/maincontroller.ts` registers `castReceiverContext.addCustomMessageListener('urn:x-cast:com.connectsdk', …)` and `processMessage` rejects a message without `command`, `serverAddress` or `accessToken` by broadcasting `{"type":"error","message":"Missing one or more required params - command,options,userId,accessToken,serverAddress"}`; `JellyfinApi.setServerInfo(accessToken, serverAddress, receiverName)`; `commandHandler.ts` maps `PlayNow` to `translateItems(data, data.options, command)`; `types/global.d.ts` `PlayRequest { startIndex?, items: BaseItemDto[], startPositionTicks?, mediaSourceId?, audioStreamIndex?, subtitleStreamIndex?, liveStreamId? }`; the receiver has no LOAD interceptor for sender `customData`. Bus messages back to the sender on the same namespace: `playbackstart`, `playbackprogress`, `playbackstop`, `error`, `connectionerror`, `playbackerror`.
- Jellyfin web sender, `github.com/jellyfin/jellyfin-web` `src/plugins/chromecastPlayer/plugin.js` (last change `8dcfc69`, 2026-05-08): `messageNamespace = 'urn:x-cast:com.connectsdk'`; `loadMedia` sends item stubs `{Id, ServerId, Name, Type, MediaType, IsFolder}` as `options.items` with `command`; `sendMessage` adds `userId, deviceId, accessToken, serverAddress, serverId, serverVersion, receiverName` (and optional `maxBitrate`, `subtitleAppearance`, `subtitleBurnIn`); the receiver app id is the user's `Configuration.CastReceiverId` (stable receiver `F007D354`).
- Jellyfin server `v12.1` / `v10.9.0` sources: `Jellyfin.Server.Implementations/Security/AuthorizationContext.cs` parses `Authorization: MediaBrowser Key="value", …` (values URL-decoded, keys `Client`, `Device`, `DeviceId`, `Version`, `Token`) and the `ApiKey` query parameter (present since ≤10.9); `api_key`, `X-Emby-Token`, `X-Emby-Authorization` are legacy and off by default in 12.x. Routes used (all present in both 10.9.0 and 12.1): `GET /System/Info/Public`, `POST /Users/AuthenticateByName`, `GET /Users`, `GET /Users/{userId}`, `POST /Sessions/Logout`, `GET /UserItems/Resume`, `GET /Shows/NextUp`, `GET /Items/Latest`, `GET /Items`, `GET /Items/{itemId}`, `GET /Sessions`, `POST /Sessions/{sessionId}/Playing`, `POST /Items/{itemId}/PlaybackInfo`, `GET /Items/{itemId}/Images/{imageType}` (no `[Authorize]`), `GET /Videos/{itemId}/stream.{container}`, `GET /Audio/{itemId}/stream.{container}`. `SessionInfoDto` fields used: `Id, UserId, Client, DeviceName, DeviceId, RemoteEndPoint, LastActivityDate, SupportsMediaControl`.

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a play request that cannot be routed now fails now with a reason. Nothing is queued.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `playback`, `device`, `web`, `security` and `sources` must not import `adapters.*.protocol`; only `sources.jellyfin` speaks HTTP to Jellyfin.
- Route by capability, not by brand.
- Honesty about walled gardens (not touched by this sub-project).
- Secrets raise the bar: a single-password login is mandatory once any secret is stored. Secrets are encrypted at rest and never reach the browser.
- Persistent state stays in `/data` as JSON files written atomically; `devices.json` and `keystore.p12` keep working without re-pairing. `shield.*` properties and `SHIELD_KEYSTORE_PASSWORD` keep working; the CasaOS app id `dev.andre.shield-remote` does not change.
- Every adapter and source is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; every source has recorded JSON fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`).

Epic constraints:

- Build and test only through the Docker wrapper: `.superpowers/gradle.sh build`; single tests with `.superpowers/gradle.sh test --tests '<pattern>'`.
- Sub-projects A and B are the contract. Where real code under `src/main/java/dev/andre/homecontrol` differs from their plans' listings (field names, helper names, a constructor parameter), adapt the edit to the real code and keep the behaviour this plan specifies; say so in the task report.
- **Device-only deployments are unchanged:** until the first secret is stored, no login, no new cookie, no new response header, no cross-origin refusal on existing endpoints, and neither `secrets.json` nor `secret.key` is created.
- **No secret in any log line, exception message, `toString()`, HTML, JSON or SSE payload:** access tokens, API keys, passwords, password hashes, `secrets.json` plaintext, `StreamUrl` query strings, `CastMessage` bodies. Records that hold them override `toString()` with a redacted form. Tests assert the token string is absent from every browser-bound response in Task 9.
- **Browser-bound content never carries a `PlayableRef`:** rails, search and item JSON use `web/ContentItemView` (id, source, kind, title, subtitle, artwork, progress). The browser plays an item by `source` + `item` id; the server re-reads the item from its source.
- Sources attach only `PlayableRef.JellyfinItem(serverId, itemId, resumeTicks)`; token-bearing references (`CastMessage`, `StreamUrl`) are created only inside `PlaybackService.plan` at play time.
- Jellyfin ≥ 10.9 (the non-deprecated routes above). Authenticate with the `Authorization: MediaBrowser …` header only; the `ApiKey` query parameter appears only in stream URLs handed to devices. Never use `api_key` or `X-Emby-*`.
- The Jellyfin HTTP client never follows redirects (a redirect would replay the `Authorization` header elsewhere) and has connect and request timeouts.
- Security beans are declared in `@Configuration` classes (not `@Component`) and the filter is registered through `FilterRegistrationBean`, so existing `@WebMvcTest` slices of other controllers keep loading; any `@ControllerAdvice` this plan adds takes its dependencies through `ObjectProvider`.
- `home-control.jellyfin.enabled` (default `true`) switches the whole Jellyfin module, including its controllers and controller advice (each carries the same `@ConditionalOnProperty`).
- Jackson 3 API: use `asString(default)`, `asInt(default)`, `asLong(default)`, `asDouble(default)`, `asBoolean(default)` — never the no-argument variants on possibly missing nodes.
- All wire formats in this plan (secrets file, PHC hash string, Authorization header, Jellyfin query parameters and bodies, the Jellyfin receiver message) are normative; tests pin them.
- The manual acceptance checklist is never marked passed by an agent.

## Decisions

- Decision: no Spring Security; a ~150-line `LoginGateFilter` plus `LoginService` — the gate is dynamic ("required only once a secret exists"), there is one password and no users or roles, Spring Security's CSRF tokens would force every A/B `@WebMvcTest` and every htmx/fetch call to carry a token, and its `Argon2PasswordEncoder` is only a wrapper around the BouncyCastle generator we call directly — cost if wrong: moving to Spring Security later is local to `security/` and `web/LoginController`.
- Decision: CSRF defence is Go 1.25's `CrossOriginProtection` algorithm (safe methods pass; `Sec-Fetch-Site: same-origin|none` passes, any other value fails; without it, a missing `Origin` passes and a present one must equal the `Host` authority or a configured trusted origin) plus `SameSite=Lax` — it needs no token plumbing, and `Sec-Fetch-Site` is not sent to plain-HTTP LAN origins so the `Origin` comparison is what protects most installs — cost if wrong: a reverse proxy that rewrites `Host` needs `HOME_CONTROL_TRUSTED_ORIGINS`.
- Decision: the cross-origin check applies to all unsafe requests only while a login is required, and always to `/login`, `/logout`, `/setup/password` and `/setup/sources/**` — keeps device-only deployments byte-for-byte unchanged while protecting the flows that create secrets — cost if wrong: a device-only deployment stays CSRF-able for key presses, as today.
- Decision: login password hash = Argon2id, v=19, m=19456 KiB, t=2, p=1, 16-byte salt, 32-byte tag, PHC string `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>` (standard base64 without padding) — OWASP Password Storage Cheat Sheet minimum; ~50 ms on the dev host; parameters are read back from the string so they can be raised later — cost if wrong: raise constants; old hashes still verify.
- Decision: at most two Argon2 verifications run at once (`Semaphore(2)`, 2 s wait, then HTTP 429) and the rate limiter runs before hashing — each verification holds 19 MiB, so unbounded parallel logins are a memory DoS — cost if wrong: a third simultaneous login waits or retries.
- Decision: rate limiting is in-memory: 5 failed logins per client address per 15 minutes, 50 failures across all addresses per 15 minutes, both answered with 429 and the wait; a success clears that address — behind a reverse proxy every client shares one address, so the global cap is the real brute-force bound (≤ 4 800 guesses/day) — cost if wrong: after a restart the counters reset; tune two properties.
- Decision: `HOME_CONTROL_SECRET` → AES key via Argon2id (same parameters as the password hash, 16-byte random salt stored in `secrets.json`) rather than PBKDF2 or HKDF — the variable is human-chosen, so a memory-hard password KDF is required (HKDF is only for high-entropy input; PBKDF2 is GPU-cheap), and it reuses the primitive we already test — cost if wrong: a KDF change needs a file version bump.
- Decision: without `HOME_CONTROL_SECRET` the key is 32 random bytes, base64 in `/data/secret.key`, mode 0600, created on the first secret write, used directly as the AES key — honest limit documented in the README: this protects `secrets.json` copied or backed up without `secret.key`, not a stolen `/data` — cost if wrong: users who need more set `HOME_CONTROL_SECRET`.
- Decision: `secrets.json` records its key source; a file saved with `secret.key` is re-encrypted with `HOME_CONTROL_SECRET` at startup once that variable is set; a file saved with the variable never silently falls back — a missing variable, a missing key file or a wrong value is a named `StorageException` at startup (mirrors the keystore rule) — cost if wrong: the app refuses to start until the user restores the key or deletes `secrets.json`.
- Decision: the login hash lives inside the encrypted document, and the store enforces "secrets exist ⇔ login exists": the first secrets are written together with a new login in one atomic write, removing the last secret removes the login — login gating is then simply "a login exists", the acceptance rule holds by construction, and there is no window where secrets exist unprotected — cost if wrong: none found.
- Decision: the first secret's form (Jellyfin connect) asks for a new login password when none exists and logs that browser in; once a login exists, every secret-changing request must already be authenticated (the filter guarantees it) — no lockout (the user just chose the password) and no unauthenticated takeover (nothing is stored before the password) — cost if wrong: none found.
- Decision: forgotten password recovery = stop the container and delete `/data/secrets.json` (device-only again, reconnect Jellyfin); no reset variable — any bypass would be an unauthenticated takeover path — cost if wrong: re-entering Jellyfin credentials.
- Decision: sessions are the servlet container's `HttpSession` holding only the login `version`; cookie `HOME_CONTROL_SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` from `HOME_CONTROL_SECURE_COOKIE` (default false, plain HTTP), max-age and idle timeout 30 days, cookie tracking only, session id rotated at login; changing the password issues a new `version`, which logs out every other browser — cost if wrong: sessions do not survive a container restart (re-login).
- Decision: when a login is required, only `GET/POST /login` and `GET /app.css` are open; SSE (`/events`), `/js/**`, `/vendor/**`, image proxy and every JSON endpoint are gated; unauthenticated HTML navigation → 302 `/login?next=…`, htmx → 401 with `HX-Redirect: /login`, everything else → 401 plain text — sub-project D6 must add its manifest and icons to the open list — cost if wrong: a PWA manifest fetch fails until D6.
- Decision: `home-control.jellyfin.enabled` defaults to `true` — the module makes no network call until the user connects a server on the setup page and is release 0.8's headline — cost if wrong: `HOME_CONTROL_JELLYFIN_ENABLED=false`.
- Decision: two authentication modes, password (default) and API key. Password mode calls `AuthenticateByName` and stores only the returned access token, never the password; API key mode stores the key and resolves the user by name through `GET /Users` — the setup page warns that API-key mode hands an administrator key to Cast devices (the receiver needs a token) — cost if wrong: API-key users accept that exposure or switch modes.
- Decision: non-secret Jellyfin settings (server URL, device-facing URL, server id/name/version, user id/name, mode, our `DeviceId`, receiver app id, session links) live in `/data/sources.json` as `{"version":1,"sources":{"jellyfin":{…string map…}}}`; the token lives in `secrets.json` under `jellyfin.token` — spec §8; D4 extends `sources.json` with rail preferences — cost if wrong: D4 migrates a flat string map.
- Decision: a separate "address for TVs and speakers" (defaults to the server URL; setup warns when it is `localhost`, a loopback address or a dotless container name) is used for the Cast receiver's `serverAddress` and for stream URLs — the container often reaches Jellyfin by a Docker name the TV cannot resolve (jellyfin-web solves the same problem with `LocalAddress`) — cost if wrong: one more optional field.
- Decision: `java.net.http.HttpClient` with a small typed client instead of `RestClient` — explicit `Redirect.NEVER`, per-request timeouts and status mapping in ~150 lines with no converter configuration — cost if wrong: spec §7.1 names `RestClient`; swapping is local to `JellyfinClient`.
- Decision: artwork goes through `GET /sources/jellyfin/images/{itemId}/{type}` on our server, which fetches Jellyfin's anonymous image endpoint without any credential — no token can leak, no mixed content behind an HTTPS proxy, phones need not reach Jellyfin, and the artwork is gated by the login — cost if wrong: one proxied request per image (cached by the browser; `tag` URLs are immutable).
- Decision: `ContentSource` lives in `core/content`: `id()`, `displayName()`, `available()` (configured now, no I/O), `rails()` (descriptors, no I/O), `rail(railId)` (I/O, one rail at a time so D1/D2 can fail per rail), `item(itemId)` (I/O, used to play by id), `searchable()`/`search(query, limit)` (optional) — later sources (YouTube, TMDB, pinned, sports) reuse it unchanged — cost if wrong: D1 wraps it.
- Decision: `ContentItem` gains `Double progress` (0–1, null when unstarted) as its last component with the old seven-argument constructor kept — A/B call sites keep compiling; `startsAt`/`endsAt` are left to H — cost if wrong: H adds two components the same way.
- Decision: rails are Continue watching (`/UserItems/Resume`, video only), Next up (`/Shows/NextUp` with `enableResumable=false`, so it does not repeat Continue watching) and Latest in library (`/Items/Latest`, `includeItemTypes=Movie,Episode`, `groupItems=false`, so every entry is directly playable) — cost if wrong: grouped series entries would need a folder-playing route.
- Decision: the Jellyfin Cast route sends a custom message on `urn:x-cast:com.connectsdk` (`command: PlayNow`, item stubs, credentials, `receiverName`) exactly like jellyfin-web — the receiver ignores media `LOAD` `customData`, so B's `Action.CastLoad` cannot drive it; core gains `Action.CastMessage`, `PlayableRef.CastMessage`, `Route.CastMessage` and `CastMessageStrategy` (rung 3, before `CastLoadStrategy`) — cost if wrong: if a future receiver accepts LOAD, switch the resolver to `PlayableRef.CastLoad`.
- Decision: `Action.CastMessage` succeeds once the message is sent and no `{"type":"error"|"connectionerror"|"playbackerror"}` reply arrives from the app within 750 ms; the actual start is visible through B's now-playing state — the receiver validates synchronously but loads media asynchronously, and waiting for `playbackstart` would hold the HTTP request for seconds — cost if wrong: a late playback error only shows as "nothing playing" (like an optimistic app link, spec §5.3).
- Decision: the receiver app id is the Jellyfin user's `Configuration.CastReceiverId` (default `F007D354`) captured at connect time — a server admin may point users at the unstable receiver `6F511C87` — cost if wrong: reconnect Jellyfin after changing it.
- Decision: direct stream = `POST /Items/{id}/PlaybackInfo` with a Default-Media-Receiver-shaped `DeviceProfile` and transcoding disabled; the first media source with `SupportsDirectPlay: true` becomes `{deviceServerUrl}/Videos|Audio/{id}/stream.{container}?static=true&mediaSourceId=…&ApiKey=…` — the server, not us, decides codec compatibility; no transcoding URLs in this sub-project — cost if wrong: files outside mp4/webm/mp3/m4a/flac/ogg with Cast-safe codecs get "no format this device can play directly".
- Decision: the stream route starts at the beginning (B's DMR load body has `currentTime: 0`); resuming is the job of rungs 1 and 3, which carry `startPositionTicks` — cost if wrong: DMR users resume by seeking.
- Decision: `JELLYFIN_CLIENT` is never declared by an adapter; it is a *live* capability `JellyfinPlayableResolver` grants for one play request when it finds a controllable session on the device — only the content server knows whether the app is open, and a static declaration on every Android TV would make the capability meaningless — cost if wrong: an adapter-side capability can be added later without changing the strategy.
- Decision: resolution happens in `PlaybackService.plan` through a core `PlayableResolver` SPI, and source-side routes execute through a core `RouteExecutor` SPI; the planner stays pure — keeps I/O out of the unit-tested matrix and keeps tokens out of anything the browser can see — cost if wrong: none found.
- Decision: rung 1 before the others (spec §5.3): an open Jellyfin app resumes with the user's profile, audio/subtitle preferences and server-side progress; when a session is found the resolver stops (no item fetch, no PlaybackInfo, no token-bearing references built); Cast (rung 3) before the stream because the receiver resumes and reports progress to Jellyfin — cost if wrong: an app in the background may ignore PlayNow (manual acceptance item).
- Decision: session ↔ device matching order: an explicit link set on the setup page (Jellyfin `DeviceId` per device id) is authoritative; else the session whose `RemoteEndPoint` equals one of the device host's addresses (ties: same name, then most recent activity); else the only session whose `DeviceName` equals the device name — Jellyfin behind Docker bridge networking sees every client as the gateway IP, so links are the reliable fallback — cost if wrong: users link manually.
- Decision: `POST /devices/{id}/play` with `source` + `item` plays a source item (a separate controller mapping with a `params` condition next to A's `uri` form), and `GET /devices/{id}/route?source=&item=` returns the planned route description without executing — the play sheet (D3) needs the reason before confirming — cost if wrong: D3 renames endpoints.
- Decision: interim JSON endpoints `GET /sources` and `GET /sources/{sourceId}/rails/{railId}` fetch upstream on each call, and `GET /search?q=` queries searchable sources sequentially — D1 adds the cache and D5 the debounced UI — cost if wrong: slow upstream blocks these endpoints until D1.

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`. Paths are relative to those roots unless they start with `src/`, `docs/` or are top-level files.

### Files to create

- `crypto/Argon2id.java` — Argon2id over BouncyCastle.
- `storage/OwnerOnlyFiles.java` — temp files with mode 0600 for atomic writes.
- `storage/LoginCredential.java` — password hash + version (redacted `toString`).
- `storage/SecretKeySource.java` — AES key from `HOME_CONTROL_SECRET` or `secret.key`.
- `storage/SecretStore.java` — encrypted `secrets.json`.
- `storage/JsonFileSourceSettings.java` — `sources.json` string maps per source.
- `security/Argon2PasswordHasher.java`, `security/LoginService.java`, `security/LoginRateLimiter.java`, `security/CrossOriginGuard.java`, `security/LoginGateFilter.java`, `security/SecurityProperties.java`, `security/SecurityConfiguration.java`, `security/LoginModelAdvice.java`, `security/PasswordRejectedException.java`, `security/LoginRequiredException.java`, `security/LoginBusyException.java`.
- `web/LoginController.java`, `src/main/resources/templates/login.html`.
- `core/content/ContentSource.java`, `RailDescriptor.java`, `Rail.java`, `ContentSourceException.java`, `ContentSources.java`.
- `core/playback/PlayableResolver.java`, `RouteExecutor.java`, `CastMessageStrategy.java`, `JellyfinSessionStrategy.java`.
- `sources/jellyfin/JellyfinConfiguration.java`, `JellyfinProperties.java`, `JellyfinSettings.java`, `JellyfinConnection.java`, `JellyfinException.java`, `JellyfinClient.java`, `JellyfinSetupService.java`, `JellyfinSetupController.java`, `JellyfinSetupAdvice.java`, `JellyfinItemMapper.java`, `JellyfinContentSource.java`, `JellyfinImageController.java`, `JellyfinSession.java`, `JellyfinSessions.java`, `JellyfinCastMessages.java`, `JellyfinStreams.java`, `JellyfinPlayableResolver.java`, `JellyfinRouteExecutor.java`.
- `src/main/resources/templates/fragments/jellyfin-setup.html`.
- `web/ContentItemView.java`, `web/ContentController.java`, `web/ContentPlayController.java`.
- Tests: `crypto/Argon2idTest.java`; `security/Argon2PasswordHasherTest.java`, `LoginServiceTest.java`, `LoginRateLimiterTest.java`, `CrossOriginGuardTest.java`; `storage/SecretKeySourceTest.java`, `SecretStoreTest.java`, `JsonFileSourceSettingsTest.java`; `web/LoginGatingTest.java`, `web/ContentControllerTest.java`, `web/ContentPlayControllerTest.java`, `web/JellyfinEndToEndTest.java`; `core/content/ContentSourcesTest.java`; `core/playback/ContentItemTest.java`; `sources/jellyfin/FakeJellyfinServer.java`, `JellyfinClientTest.java`, `JellyfinSettingsTest.java`, `JellyfinSetupServiceTest.java`, `JellyfinSetupControllerTest.java`, `JellyfinModuleSwitchTest.java`, `JellyfinItemMapperTest.java`, `JellyfinContentSourceTest.java`, `JellyfinImageControllerTest.java`, `JellyfinSessionsTest.java`, `JellyfinCastMessagesTest.java`, `JellyfinStreamsTest.java`, `JellyfinPlayableResolverTest.java`, `JellyfinRouteExecutorTest.java`, `JellyfinFixtureContractTest.java`.
- Fixtures: `src/test/resources/fixtures/secrets/secret.key`, `secrets-keyfile-v1.json`, `secrets-passphrase-v1.json`; `src/test/resources/fixtures/jellyfin/system-info-public.json`, `system-info-public-old.json`, `authenticate-by-name.json`, `users.json`, `user.json`, `resume.json`, `next-up.json`, `latest.json`, `item-episode.json`, `item-movie.json`, `search.json`, `sessions.json`, `playback-info-direct.json`, `playback-info-transcode-only.json`, `playback-info-audio.json`.
- `docs/superpowers/reviews/2026-09-16-jellyfin-source-acceptance.md` — manual checklist, every item pending.

### Files to modify

- `build.gradle.kts` — explicit `bcprov-jdk18on:1.85` (Task 1).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `server.servlet.session.*`, `home-control.security.*` (Task 1), `home-control.jellyfin.*` (Task 2).
- `src/main/resources/templates/setup.html` — login password section (Task 1), Jellyfin fragment include (Task 2).
- `core/playback/ContentItem.java` — `progress` (Task 3); `withPlayables` (Task 7).
- `core/playback/PlayableRef.java` — `CastMessage` (Task 5), `StreamUrl.toString` redaction (Task 6), `JellyfinSession` (Task 7).
- `core/playback/Route.java` — `CastMessage` (Task 5), `JellyfinSession` (Task 7).
- `core/playback/PlaybackPlanner.java` — explanations (Tasks 5, 7).
- `core/Action.java` — `CastMessage` (Task 5).
- `adapters/cast/CastSession.java`, `adapters/cast/protocol/CastPayloads.java` — custom-namespace messages (Task 5); `adapters/androidtv/AndroidTvSession.java` and any other exhaustive `switch` over `Action` — reject `CastMessage` (Task 5).
- `playback/PlaybackService.java` — resolvers, executors, `plan(item, deviceId)` (Tasks 5, 7).
- `HomeControlConfiguration.java` — `ContentSources` bean (Task 3), planner strategy order (Tasks 5, 7).
- `README.md` — login, secrets, Jellyfin (Task 9).
- Tests of the above: `adapters/cast/protocol/FakeCastReceiver.java`, `adapters/cast/CastSessionTest.java`, `core/ActionTest.java`, `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`.

### Files to delete

- None.

---

### Task 1: C1 · Secret store and login

**Files:**
- Modify: `build.gradle.kts`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`, `src/main/resources/templates/setup.html`
- Create: `crypto/Argon2id.java`, `storage/OwnerOnlyFiles.java`, `storage/LoginCredential.java`, `storage/SecretKeySource.java`, `storage/SecretStore.java`, `security/Argon2PasswordHasher.java`, `security/PasswordRejectedException.java`, `security/LoginRequiredException.java`, `security/LoginBusyException.java`, `security/LoginService.java`, `security/LoginRateLimiter.java`, `security/CrossOriginGuard.java`, `security/LoginGateFilter.java`, `security/SecurityProperties.java`, `security/SecurityConfiguration.java`, `security/LoginModelAdvice.java`, `web/LoginController.java`, `src/main/resources/templates/login.html`
- Test: `crypto/Argon2idTest.java`, `security/Argon2PasswordHasherTest.java`, `storage/SecretKeySourceTest.java`, `storage/SecretStoreTest.java`, `security/LoginServiceTest.java`, `security/LoginRateLimiterTest.java`, `security/CrossOriginGuardTest.java`, `web/LoginGatingTest.java`; fixtures `src/test/resources/fixtures/secrets/secret.key`, `secrets-keyfile-v1.json`, `secrets-passphrase-v1.json`

**Interfaces:**
- Consumes: `AndroidTvProperties.dataDir()` (A), `StorageException(String, Throwable)` (existing), `SetupController` (A/B).
- Produces:
  - `final class Argon2id { static byte[] derive(byte[] password, byte[] salt, int memoryKiB, int iterations, int parallelism, int length); static byte[] derive(byte[] password, byte[] salt, byte[] secret, byte[] associatedData, int memoryKiB, int iterations, int parallelism, int length); }`
  - `record LoginCredential(String passwordHash, String version)`.
  - `final class SecretKeySource { SecretKeySource(String passphrase, Path keyFile, SecureRandom random); boolean usesPassphrase(); record KeyHeader(String source, byte[] salt, int memoryKiB, int iterations, int parallelism); }` with constants `SOURCE_PASSPHRASE = "HOME_CONTROL_SECRET"`, `SOURCE_KEY_FILE = "secret.key"`.
  - `class SecretStore { SecretStore(Path file, SecretKeySource keys, SecureRandom random); Optional<String> secret(String name); boolean hasSecrets(); Set<String> names(); Optional<LoginCredential> login(); void putSecrets(Map<String,String>); void putFirstSecrets(Map<String,String>, LoginCredential); void removeSecrets(Collection<String>); void replaceLogin(LoginCredential); }`
  - `final class Argon2PasswordHasher { Argon2PasswordHasher(SecureRandom); String hash(String password); boolean matches(String password, String encoded); }`
  - `class LoginService { LoginService(SecretStore, Argon2PasswordHasher, SecureRandom); boolean loginRequired(); boolean isAuthenticated(HttpServletRequest); boolean authenticate(String password, HttpServletRequest); void logout(HttpServletRequest); void checkNewPassword(String password, String confirmation); void storeSecrets(Map<String,String> secrets, String newPassword, String confirmation, HttpServletRequest); void removeSecrets(Collection<String>); void changePassword(String current, String next, String confirmation, HttpServletRequest); }`
  - `class LoginRateLimiter { LoginRateLimiter(Clock, int perAddress, int total, Duration window); Optional<Duration> blockedFor(String address); void failed(String address); void succeeded(String address); }`
  - `final class CrossOriginGuard { CrossOriginGuard(List<String> trustedOrigins); boolean allows(HttpServletRequest); }`
  - `class LoginGateFilter extends OncePerRequestFilter`.
  - `record SecurityProperties(String secret, List<String> trustedOrigins, int loginAttemptsPerAddress, int loginAttemptsTotal, Duration loginWindow)` bound to `home-control.security`.
  - Endpoints: `GET /login`, `POST /login` (`password`, `next`) → 302 / 401 / 429; `POST /logout` → 302; `POST /setup/password` (`current`, `password`, `confirmation`) → 302 `/setup` with flash `loginMessage` or `loginError`.
  - Model attribute `loginRequired` (boolean) on the setup page.

**Secrets file format (normative).** `secrets.json` is pretty-printed JSON, written atomically, mode 0600:

```json
{
  "format" : "home-control-secrets",
  "version" : 1,
  "key" : { "source" : "secret.key" },
  "cipher" : "AES-256-GCM",
  "nonce" : "<base64, 12 bytes, fresh per write>",
  "ciphertext" : "<base64, AES-256-GCM output including the 128-bit tag>"
}
```

With `HOME_CONTROL_SECRET` the key object is `{"source":"HOME_CONTROL_SECRET","kdf":"argon2id","memoryKiB":19456,"iterations":2,"parallelism":1,"salt":"<base64, 16 bytes>"}` and the AES key is `Argon2id(UTF-8(secret), salt, m, t, p, 32)`. Associated data is the ASCII string `home-control/secrets/v1`. The plaintext is `{"login":{"passwordHash":"<PHC>","version":"<opaque>"}|null,"secrets":{"<name>":"<value>",…}}`. Secret names match `[a-z0-9][a-z0-9.-]{0,63}`; values are 1–16 384 characters. `secret.key` is the standard base64 of 32 random bytes plus a newline, mode 0600.

- [ ] **Step 1: Declare the BouncyCastle provider dependency and the configuration**

In `build.gradle.kts` add below the `bcpkix` line:

```kotlin
    // Argon2id for the login hash and the HOME_CONTROL_SECRET key (already transitive via bcpkix; used directly now).
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
```

Append to `src/main/resources/application.yaml` (merge with the existing `server:` block rather than adding a second one):

```yaml
server:
  servlet:
    session:
      timeout: 30d
      tracking-modes: cookie
      cookie:
        name: HOME_CONTROL_SESSION
        http-only: true
        same-site: lax
        max-age: 30d
        secure: ${HOME_CONTROL_SECURE_COOKIE:false}

home-control:
  security:
    # Optional passphrase for secrets.json; without it a random /data/secret.key is created on first use.
    secret: ${HOME_CONTROL_SECRET:}
    # Extra origins allowed to POST, e.g. https://home.example.org when a proxy rewrites Host.
    trusted-origins: ${HOME_CONTROL_TRUSTED_ORIGINS:}
    login-attempts-per-address: 5
    login-attempts-total: 50
    login-window: 15m
```

Append the same `home-control.security` block to `src/test/resources/application.yaml` with `secret: ""` and `trusted-origins: ""` literal values (no placeholders), and the same `server.servlet.session` block.

- [ ] **Step 2: Write the fixtures**

`src/test/resources/fixtures/secrets/secret.key` (32 bytes of `0x42`):

```text
QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=
```

`src/test/resources/fixtures/secrets/secrets-keyfile-v1.json` (nonce = 12 bytes of `0x24`; the plaintext is `{"login":{"passwordHash":"$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E","version":"fixture-1"},"secrets":{"jellyfin.token":"0123456789abcdef0123456789abcdef"}}` — the hash is of the password `password`):

```json
{
  "format" : "home-control-secrets",
  "version" : 1,
  "key" : { "source" : "secret.key" },
  "cipher" : "AES-256-GCM",
  "nonce" : "JCQkJCQkJCQkJCQk",
  "ciphertext" : "brOoLo6vqBwckwDa3EHg0QOJVzjX2DhPioboOySiiH0wYjWhC3mbJfErjYZrmpeklM06YryNoBucVCZARB252SAacFqOZbmYEY4CLKjiqyJfKsTvDPXyzg8Gd+cdNEm7tROGp3HrSvKC9RW0KfUHUiBRqTC4d9icFV+lrafDVWZpZ+smBTQiYiU/E/Excytum9Sx+gsBj2U9Xjzgm8Qtlsg/PJG1XGw1IyiUGszDOz55QEq/OzjLKMbdFcNXoNiwyQjHTGMiXDht72+HokU8ZpyYRrNXuxynEg=="
}
```

`src/test/resources/fixtures/secrets/secrets-passphrase-v1.json` (same plaintext and nonce; passphrase `correct horse battery staple`, salt bytes `00 01 … 0f`):

```json
{
  "format" : "home-control-secrets",
  "version" : 1,
  "key" : { "source" : "HOME_CONTROL_SECRET", "kdf" : "argon2id", "memoryKiB" : 19456, "iterations" : 2, "parallelism" : 1, "salt" : "AAECAwQFBgcICQoLDA0ODw==" },
  "cipher" : "AES-256-GCM",
  "nonce" : "JCQkJCQkJCQkJCQk",
  "ciphertext" : "js7vkIuROYyr6JNOtJETDumoyrNEOibQlIN9gxNgWx3a0duAamDyEwPDpZRnJqC26L6dCHLKFSbdF3V6Nf3wGViixrbOQtZ6pJRMMa+Rl+42sUagdnOylIbhnGvpjOP963VvJjTLZhx6GaRPRe5wHIZdBzeDe8MDt4OqgjcyD1RUSa5xrFPmwTLkDcdpzfnMx2QVaT17Tvgaf8q1/pJtJYOf7ztbLpOXVt8kF4OT/Lb0guSoo8imi9D7G9b97wgsqZQxfHH5UBbm/ZclcAApxzDYkJ0p9tBakQ=="
}
```

These two ciphertexts and the hash were produced with `bcprov-jdk18on-1.85` and JDK 25 `AES/GCM/NoPadding`; they pin the file format.

- [ ] **Step 3: Write the failing crypto and storage tests**

`src/test/java/dev/andre/homecontrol/crypto/Argon2idTest.java`:

```java
package dev.andre.homecontrol.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class Argon2idTest {

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    @Test
    void reproducesTheRfc9106Argon2idTestVector() {
        byte[] tag = Argon2id.derive(filled(32, 1), filled(16, 2), filled(8, 3), filled(12, 4), 32, 3, 4, 32);

        assertThat(HexFormat.of().formatHex(tag))
                .isEqualTo("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659");
    }

    @Test
    void matchesTheReferenceImplementationForAPlainPasswordAndSalt() {
        byte[] tag = Argon2id.derive("password".getBytes(StandardCharsets.UTF_8),
                "somesalt".getBytes(StandardCharsets.UTF_8), 65536, 2, 1, 32);

        // $argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc
        assertThat(java.util.Base64.getEncoder().withoutPadding().encodeToString(tag))
                .isEqualTo("CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc");
    }
}
```

`src/test/java/dev/andre/homecontrol/security/Argon2PasswordHasherTest.java` — test cases:
- `hashesWithTheOwaspParametersInPhcFormat`: `hasher.hash("correct horse battery")` matches regex `^\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$`; two hashes of the same password differ (random salt).
- `verifiesTheKnownHashOfPassword`: `matches("password", "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E")` is true; `matches("Password", same)` is false.
- `roundTripsAFreshHash`: `matches(p, hash(p))` true, `matches(p + "x", hash(p))` false.
- `rejectsMalformedOrAbusiveHashesWithoutThrowing`: false for `null`, `""`, `"$argon2i$v=19$m=19456,t=2,p=1$c29tZXNhbHQ$PL01amPyeUuxG7H0vIr5X+qHkZvWnHmGBGXFYvh8z2E"` (argon2i), `"$argon2id$v=16$…"`, memory `m=9999999` (above the 262 144 KiB cap), `t=0`, a salt of `"!!"`.

`src/test/java/dev/andre/homecontrol/storage/SecretStoreTest.java` (uses `@TempDir Path dir`; helper `store(String passphrase)` = `new SecretStore(dir.resolve("secrets.json"), new SecretKeySource(passphrase, dir.resolve("secret.key"), new SecureRandom()), new SecureRandom())`; `copyFixture(name, target)` copies from `src/test/resources/fixtures/secrets/`):

```java
    @Test
    void anEmptyDataDirectoryHasNoSecretsAndCreatesNoFiles() throws Exception {
        SecretStore store = store(null);

        assertThat(store.hasSecrets()).isFalse();
        assertThat(store.login()).isEmpty();
        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void readsTheKeyFileFixture() throws Exception {
        copyFixture("secret.key", "secret.key");
        copyFixture("secrets-keyfile-v1.json", "secrets.json");

        SecretStore store = store(null);

        assertThat(store.secret("jellyfin.token")).contains("0123456789abcdef0123456789abcdef");
        assertThat(store.login()).get().extracting(LoginCredential::version).isEqualTo("fixture-1");
    }

    @Test
    void readsThePassphraseFixture() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");

        assertThat(store("correct horse battery staple").secret("jellyfin.token"))
                .contains("0123456789abcdef0123456789abcdef");
    }

    @Test
    void theFirstSecretsAreWrittenTogetherWithTheLoginAndEncrypted() throws Exception {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("jellyfin.token", "tok-123456"), new LoginCredential("$argon2id$hash", "v1"));

        String onDisk = Files.readString(dir.resolve("secrets.json"));
        assertThat(JsonMapper.builder().build().readTree(onDisk).path("format").asString("")).isEqualTo("home-control-secrets");
        assertThat(onDisk).doesNotContain("tok-123456").doesNotContain("argon2id$hash");
        assertThat(Files.getPosixFilePermissions(dir.resolve("secrets.json"))).containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(dir.resolve("secret.key"))).containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
        assertThat(Base64.getDecoder().decode(Files.readString(dir.resolve("secret.key")).strip())).hasSize(32);
        SecretStore reopened = store(null);
        assertThat(reopened.secret("jellyfin.token")).contains("tok-123456");
        assertThat(reopened.login()).contains(new LoginCredential("$argon2id$hash", "v1"));
    }

    @Test
    void secretsAreNeverStoredWithoutALogin() {
        SecretStore store = store(null);

        assertThatThrownBy(() -> store.putSecrets(Map.of("jellyfin.token", "x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(dir.resolve("secrets.json"))).isFalse();
    }

    @Test
    void theFirstSecretsCannotReplaceAnExistingLogin() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));

        assertThatThrownBy(() -> store.putFirstSecrets(Map.of("b", "2"), new LoginCredential("h2", "v2")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.login()).contains(new LoginCredential("h", "v1"));
    }

    @Test
    void removingTheLastSecretRemovesTheLogin() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1", "b", "2"), new LoginCredential("h", "v1"));

        store.removeSecrets(List.of("a"));
        assertThat(store.login()).isPresent();
        store.removeSecrets(List.of("b"));

        assertThat(store.hasSecrets()).isFalse();
        assertThat(store(null).login()).isEmpty();
    }

    @Test
    void eachWriteUsesAFreshNonce() throws Exception {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));
        String first = Files.readString(dir.resolve("secrets.json"));
        store.putSecrets(Map.of("a", "1"));

        assertThat(nonceOf(Files.readString(dir.resolve("secrets.json")))).isNotEqualTo(nonceOf(first));
    }

    @Test
    void aWrongPassphraseIsANamedStorageErrorNotAnEmptyStore() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");

        assertThatThrownBy(() -> store("wrong horse"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("HOME_CONTROL_SECRET is not the value");
    }

    @Test
    void aMissingPassphraseOrKeyFileIsNamed() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");
        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("HOME_CONTROL_SECRET, which is not set");

        copyFixture("secrets-keyfile-v1.json", "secrets.json");
        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("secret.key").hasMessageContaining("missing");
    }

    @Test
    void aTamperedCiphertextIsRejected() throws Exception {
        copyFixture("secret.key", "secret.key");
        String json = Files.readString(fixture("secrets-keyfile-v1.json")).replace("\"brOo", "\"brOp");
        Files.writeString(dir.resolve("secrets.json"), json);

        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("could not be decrypted");
    }

    @Test
    void aKeyFileStoreIsReEncryptedOnceAPassphraseIsConfigured() throws Exception {
        copyFixture("secret.key", "secret.key");
        copyFixture("secrets-keyfile-v1.json", "secrets.json");

        store("a brand new passphrase");

        assertThat(JsonMapper.builder().build().readTree(Files.readString(dir.resolve("secrets.json")))
                .path("key").path("source").asString("")).isEqualTo("HOME_CONTROL_SECRET");
        Files.delete(dir.resolve("secret.key"));
        assertThat(store("a brand new passphrase").secret("jellyfin.token")).contains("0123456789abcdef0123456789abcdef");
    }

    @Test
    void tamperedKdfCostsAreRefusedBeforeDeriving() throws Exception {
        String json = Files.readString(fixture("secrets-passphrase-v1.json")).replace("\"memoryKiB\" : 19456", "\"memoryKiB\" : 99999999");
        Files.writeString(dir.resolve("secrets.json"), json);

        assertThatThrownBy(() -> store("correct horse battery staple")).isInstanceOf(StorageException.class);
    }

    @Test
    void rejectsBadSecretNamesAndValues() {
        SecretStore store = store(null);
        assertThatThrownBy(() -> store.putFirstSecrets(Map.of("Bad Name", "x"), new LoginCredential("h", "v")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.putFirstSecrets(Map.of("ok", ""), new LoginCredential("h", "v")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginCredentialsNeverPrintTheirHash() {
        assertThat(new LoginCredential("$argon2id$secret-hash", "v9").toString()).doesNotContain("secret-hash").contains("v9");
    }
```

(`nonceOf(json)` parses the JSON with a `JsonMapper` and returns `path("nonce").asString("")`; imports `static java.nio.file.attribute.PosixFilePermission.*`.)

`src/test/java/dev/andre/homecontrol/storage/SecretKeySourceTest.java` — test cases:
- `aKeyFileIsCreatedOnlyWhenWritingAndReusedAfterwards`: `usesPassphrase()` false for `null`, `""`, `"   "`; `forWriting(null)` twice returns equal keys and one `secret.key`.
- `aPassphraseKeepsItsSaltAcrossWrites`: `forWriting(null)` then `forWriting(firstHeader)` returns the same salt bytes and the same key; `forWriting(keyFileHeader)` with a passphrase returns a new passphrase header.
- `aKeyFileOfTheWrongLengthIsRefused`: `secret.key` containing base64 of 16 bytes → `StorageException` naming the file.

(`forWriting` and `keyFor` are package-private; the test lives in the same package.)

- [ ] **Step 4: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.crypto.*' --tests 'dev.andre.homecontrol.storage.*' --tests 'dev.andre.homecontrol.security.Argon2PasswordHasherTest'`
Expected: compilation failure — `Argon2id`, `SecretStore`, `SecretKeySource`, `LoginCredential`, `Argon2PasswordHasher` do not exist.

- [ ] **Step 5: Implement the crypto and storage classes**

`crypto/Argon2id.java`:

```java
package dev.andre.homecontrol.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Argon2id, version 0x13 (RFC 9106), through BouncyCastle. Used for the login hash and the secrets key. */
public final class Argon2id {

    private Argon2id() {
    }

    public static byte[] derive(byte[] password, byte[] salt, int memoryKiB, int iterations, int parallelism, int length) {
        return derive(password, salt, null, null, memoryKiB, iterations, parallelism, length);
    }

    public static byte[] derive(byte[] password, byte[] salt, byte[] secret, byte[] associatedData,
                                int memoryKiB, int iterations, int parallelism, int length) {
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKiB)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt);
        if (secret != null) {
            builder.withSecret(secret);
        }
        if (associatedData != null) {
            builder.withAdditional(associatedData);
        }
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(builder.build());
        byte[] out = new byte[length];
        generator.generateBytes(password, out);
        return out;
    }
}
```

`storage/OwnerOnlyFiles.java`:

```java
package dev.andre.homecontrol.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Temp files that only the owner can read, for atomic writes of secrets. */
final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    static Path createTemp(Path directory, String prefix) throws IOException {
        Files.createDirectories(directory);
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(directory, prefix, ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(directory, prefix, ".tmp");
    }
}
```

`storage/LoginCredential.java`:

```java
package dev.andre.homecontrol.storage;

import java.util.Objects;

/** The login password hash (PHC string) and an opaque version that changes with every new password. */
public record LoginCredential(String passwordHash, String version) {

    public LoginCredential {
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(version, "version");
    }

    @Override
    public String toString() {
        return "LoginCredential[version=" + version + "]";
    }
}
```

`storage/SecretKeySource.java`:

```java
package dev.andre.homecontrol.storage;

import dev.andre.homecontrol.crypto.Argon2id;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The AES-256 key of {@code secrets.json}. With {@code HOME_CONTROL_SECRET} set the key is
 * Argon2id(secret, salt); otherwise 32 random bytes in {@code secret.key}, created on the first
 * write so a device-only deployment never gets the file.
 */
public final class SecretKeySource {

    public static final String SOURCE_PASSPHRASE = "HOME_CONTROL_SECRET";
    public static final String SOURCE_KEY_FILE = "secret.key";
    static final int KEY_BYTES = 32;
    static final int SALT_BYTES = 16;
    static final int KDF_MEMORY_KIB = 19456;
    static final int KDF_ITERATIONS = 2;
    static final int KDF_PARALLELISM = 1;

    /** What {@code secrets.json} records about its key. Salt and costs are set for the passphrase source only. */
    public record KeyHeader(String source, byte[] salt, int memoryKiB, int iterations, int parallelism) {
    }

    record Keyed(KeyHeader header, SecretKey key) {
    }

    private final String passphrase;
    private final Path keyFile;
    private final SecureRandom random;
    private KeyHeader derivedFor;
    private SecretKey derivedKey;

    public SecretKeySource(String passphrase, Path keyFile, SecureRandom random) {
        this.passphrase = passphrase;
        this.keyFile = keyFile;
        this.random = random;
    }

    public boolean usesPassphrase() {
        return passphrase != null && !passphrase.isBlank();
    }

    synchronized SecretKey keyFor(KeyHeader header) {
        return switch (header.source()) {
            case SOURCE_PASSPHRASE -> {
                if (!usesPassphrase()) {
                    throw new StorageException("secrets.json is encrypted with HOME_CONTROL_SECRET, which is not set;"
                            + " set it to the value it had when the secrets were saved", null);
                }
                yield derive(header);
            }
            case SOURCE_KEY_FILE -> {
                if (!Files.exists(keyFile)) {
                    throw new StorageException("secrets.json is encrypted with " + keyFile + ", which is missing;"
                            + " restore that file, or delete secrets.json and reconnect your content sources", null);
                }
                yield readKeyFile();
            }
            default -> throw new StorageException("secrets.json names an unknown key source '" + header.source() + "'", null);
        };
    }

    synchronized Keyed forWriting(KeyHeader current) {
        if (usesPassphrase()) {
            KeyHeader header = current != null && SOURCE_PASSPHRASE.equals(current.source())
                    ? current : newPassphraseHeader();
            return new Keyed(header, derive(header));
        }
        SecretKey key = Files.exists(keyFile) ? readKeyFile() : createKeyFile();
        return new Keyed(new KeyHeader(SOURCE_KEY_FILE, null, 0, 0, 0), key);
    }

    private KeyHeader newPassphraseHeader() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new KeyHeader(SOURCE_PASSPHRASE, salt, KDF_MEMORY_KIB, KDF_ITERATIONS, KDF_PARALLELISM);
    }

    private SecretKey derive(KeyHeader header) {
        if (derivedFor != null && Arrays.equals(derivedFor.salt(), header.salt())
                && derivedFor.memoryKiB() == header.memoryKiB() && derivedFor.iterations() == header.iterations()
                && derivedFor.parallelism() == header.parallelism()) {
            return derivedKey;
        }
        byte[] key = Argon2id.derive(passphrase.getBytes(StandardCharsets.UTF_8), header.salt(),
                header.memoryKiB(), header.iterations(), header.parallelism(), KEY_BYTES);
        derivedFor = header;
        derivedKey = new SecretKeySpec(key, "AES");
        return derivedKey;
    }

    private SecretKey readKeyFile() {
        try {
            byte[] key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).strip());
            if (key.length != KEY_BYTES) {
                throw new StorageException(keyFile + " must hold " + KEY_BYTES + " base64-encoded bytes", null);
            }
            return new SecretKeySpec(key, "AES");
        } catch (IOException | IllegalArgumentException e) {
            throw new StorageException("Could not read " + keyFile + "; it must hold " + KEY_BYTES
                    + " base64-encoded bytes", e);
        }
    }

    private SecretKey createKeyFile() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        Path temp = null;
        try {
            temp = OwnerOnlyFiles.createTemp(keyFile.toAbsolutePath().getParent(), ".secret-key-");
            Files.writeString(temp, Base64.getEncoder().encodeToString(key) + "\n", StandardCharsets.US_ASCII);
            Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("Could not create " + keyFile + "; check that /data is bind-mounted and writable", e);
        } finally {
            deleteQuietly(temp);
        }
        return new SecretKeySpec(key, "AES");
    }

    private static void deleteQuietly(Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // a stray temp file is harmless; the next write creates a new one
            }
        }
    }
}
```

`storage/SecretStore.java`:

```java
package dev.andre.homecontrol.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * API keys, tokens and the login hash, encrypted with AES-256-GCM in {@code secrets.json} (spec §8, §9).
 * Invariant: secrets exist if and only if a login exists — secrets are never stored unprotected, and
 * removing the last one returns the deployment to device-only.
 */
public class SecretStore {

    private static final Logger log = LoggerFactory.getLogger(SecretStore.class);

    static final String FORMAT = "home-control-secrets";
    static final int VERSION = 1;
    static final String CIPHER = "AES-256-GCM";
    private static final byte[] AAD = "home-control/secrets/v1".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final int MAX_VALUE_CHARS = 16_384;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path file;
    private final SecretKeySource keys;
    private final SecureRandom random;
    private SecretKeySource.KeyHeader header;
    private LoginCredential login;
    private Map<String, String> secrets = Map.of();

    public SecretStore(Path file, SecretKeySource keys, SecureRandom random) {
        this.file = file;
        this.keys = keys;
        this.random = random;
        load();
        if (header != null && keys.usesPassphrase() && SecretKeySource.SOURCE_KEY_FILE.equals(header.source())) {
            write(login, secrets);
            log.info("Re-encrypted {} with HOME_CONTROL_SECRET; secret.key is no longer needed", file);
        }
    }

    public synchronized Optional<String> secret(String name) {
        return Optional.ofNullable(secrets.get(name));
    }

    public synchronized boolean hasSecrets() {
        return !secrets.isEmpty();
    }

    public synchronized Set<String> names() {
        return Set.copyOf(secrets.keySet());
    }

    public synchronized Optional<LoginCredential> login() {
        return Optional.ofNullable(login);
    }

    /** Adds or replaces secrets. A login must already exist. */
    public synchronized void putSecrets(Map<String, String> values) {
        validate(values);
        if (login == null) {
            throw new IllegalStateException("Set a login password before storing secrets");
        }
        Map<String, String> next = new HashMap<>(secrets);
        next.putAll(values);
        write(login, next);
    }

    /** Stores the first secrets and the login that protects them in one atomic write. */
    public synchronized void putFirstSecrets(Map<String, String> values, LoginCredential newLogin) {
        validate(values);
        if (newLogin == null) {
            throw new IllegalArgumentException("A login is required");
        }
        if (!secrets.isEmpty()) {
            throw new IllegalStateException("Secrets already exist; log in to add more");
        }
        write(newLogin, values);
    }

    /** Removing the last secret also removes the login. */
    public synchronized void removeSecrets(Collection<String> names) {
        Map<String, String> next = new HashMap<>(secrets);
        names.forEach(next::remove);
        if (next.size() == secrets.size()) {
            return;
        }
        write(next.isEmpty() ? null : login, next);
    }

    public synchronized void replaceLogin(LoginCredential newLogin) {
        if (login == null || newLogin == null) {
            throw new IllegalStateException("There is no login to replace");
        }
        write(newLogin, secrets);
    }

    private static void validate(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("No secrets given");
        }
        values.forEach((name, value) -> {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid secret name");
            }
            if (value == null || value.isEmpty() || value.length() > MAX_VALUE_CHARS) {
                throw new IllegalArgumentException("Invalid value for secret " + name);
            }
        });
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (root == null || !FORMAT.equals(root.path("format").asString(""))
                    || root.path("version").asInt(0) != VERSION || !CIPHER.equals(root.path("cipher").asString(""))) {
                throw new StorageException(file + " is not a version " + VERSION + " Home Control secrets file", null);
            }
            SecretKeySource.KeyHeader parsed = parseHeader(root.path("key"));
            byte[] nonce = Base64.getDecoder().decode(root.path("nonce").asString(""));
            byte[] ciphertext = Base64.getDecoder().decode(root.path("ciphertext").asString(""));
            if (nonce.length != NONCE_BYTES) {
                throw new StorageException(file + " has an invalid nonce", null);
            }
            byte[] plaintext = decrypt(keys.keyFor(parsed), nonce, ciphertext, parsed);
            try {
                JsonNode document = mapper.readTree(plaintext);
                JsonNode loginNode = document.path("login");
                LoginCredential loaded = loginNode.isObject()
                        ? new LoginCredential(loginNode.path("passwordHash").asString(""), loginNode.path("version").asString(""))
                        : null;
                Map<String, String> values = new LinkedHashMap<>();
                document.path("secrets").properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString("")));
                header = parsed;
                login = loaded;
                secrets = Map.copyOf(values);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            throw new StorageException("Could not read " + file + "; it is not a readable Home Control secrets file", e);
        }
    }

    private SecretKeySource.KeyHeader parseHeader(JsonNode key) {
        String source = key.path("source").asString("");
        if (!SecretKeySource.SOURCE_PASSPHRASE.equals(source)) {
            return new SecretKeySource.KeyHeader(source, null, 0, 0, 0);
        }
        int memory = key.path("memoryKiB").asInt(0);
        int iterations = key.path("iterations").asInt(0);
        int parallelism = key.path("parallelism").asInt(0);
        byte[] salt = Base64.getDecoder().decode(key.path("salt").asString(""));
        if (!"argon2id".equals(key.path("kdf").asString("")) || memory < 8 || memory > 262_144
                || iterations < 1 || iterations > 10 || parallelism < 1 || parallelism > 8 || salt.length < 16) {
            throw new StorageException(file + " has invalid key derivation parameters", null);
        }
        return new SecretKeySource.KeyHeader(source, salt, memory, iterations, parallelism);
    }

    private byte[] decrypt(SecretKey key, byte[] nonce, byte[] ciphertext, SecretKeySource.KeyHeader parsed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new StorageException(SecretKeySource.SOURCE_PASSPHRASE.equals(parsed.source())
                    ? file + " could not be decrypted: HOME_CONTROL_SECRET is not the value the secrets were saved with"
                    : file + " could not be decrypted with secret.key; the key file belongs to a different secrets.json", e);
        } catch (GeneralSecurityException e) {
            throw new StorageException("Could not decrypt " + file, e);
        }
    }

    private void write(LoginCredential nextLogin, Map<String, String> nextSecrets) {
        ObjectNode document = mapper.createObjectNode();
        if (nextLogin == null) {
            document.putNull("login");
        } else {
            ObjectNode loginNode = document.putObject("login");
            loginNode.put("passwordHash", nextLogin.passwordHash());
            loginNode.put("version", nextLogin.version());
        }
        ObjectNode secretsNode = document.putObject("secrets");
        new TreeMap<>(nextSecrets).forEach(secretsNode::put);
        byte[] plaintext = mapper.writeValueAsBytes(document);

        SecretKeySource.Keyed keyed = keys.forWriting(header);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyed.key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            ciphertext = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available in this JVM", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        ObjectNode key = root.putObject("key");
        SecretKeySource.KeyHeader h = keyed.header();
        key.put("source", h.source());
        if (SecretKeySource.SOURCE_PASSPHRASE.equals(h.source())) {
            key.put("kdf", "argon2id");
            key.put("memoryKiB", h.memoryKiB());
            key.put("iterations", h.iterations());
            key.put("parallelism", h.parallelism());
            key.put("salt", Base64.getEncoder().encodeToString(h.salt()));
        }
        root.put("cipher", CIPHER);
        root.put("nonce", Base64.getEncoder().encodeToString(nonce));
        root.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));

        Path temp = null;
        try {
            temp = OwnerOnlyFiles.createTemp(file.toAbsolutePath().getParent(), ".secrets-");
            Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Could not write " + file + "; check that /data is bind-mounted and writable", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // harmless leftover
                }
            }
        }
        header = h;
        login = nextLogin;
        secrets = Map.copyOf(nextSecrets);
    }
}
```

(If the Jackson 3 version in use names `JsonNode.properties()` differently, use its field-iteration method — `properties()` returns `Set<Map.Entry<String, JsonNode>>` in Jackson 2.15+/3.)

- [ ] **Step 6: Run the crypto and storage tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.crypto.*' --tests 'dev.andre.homecontrol.storage.*'`
Expected: PASS (the hasher test still fails to compile until Step 8 — run it there).

- [ ] **Step 7: Write the failing security unit tests**

`src/test/java/dev/andre/homecontrol/security/CrossOriginGuardTest.java` (build requests with `MockHttpServletRequest`):

```java
class CrossOriginGuardTest {

    private final CrossOriginGuard guard = new CrossOriginGuard(List.of("https://home.example.org"));

    private static MockHttpServletRequest request(String method, String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/setup/sources/jellyfin");
        request.addHeader("Host", "192.168.1.10:8080");
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }

    @Test
    void safeMethodsAlwaysPass() {
        assertThat(guard.allows(request("GET", "Origin", "http://evil.example"))).isTrue();
        assertThat(guard.allows(request("HEAD", "Sec-Fetch-Site", "cross-site"))).isTrue();
    }

    @Test
    void fetchMetadataDecidesWhenPresent() {
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "same-origin"))).isTrue();
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "none"))).isTrue();
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "same-site", "Origin", "http://192.168.1.10:9000"))).isFalse();
        assertThat(guard.allows(request("POST", "Sec-Fetch-Site", "cross-site", "Origin", "http://evil.example"))).isFalse();
    }

    @Test
    void withoutFetchMetadataTheOriginMustMatchTheHost() {
        assertThat(guard.allows(request("POST", "Origin", "http://192.168.1.10:8080"))).isTrue();
        assertThat(guard.allows(request("POST", "Origin", "http://192.168.1.10:9000"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "null"))).isFalse();
        assertThat(guard.allows(request("POST", "Origin", "not a uri"))).isFalse();
    }

    @Test
    void requestsWithNeitherHeaderAreNotFromABrowserAndPass() {
        assertThat(guard.allows(request("POST"))).isTrue();
    }

    @Test
    void trustedOriginsPassEvenWhenAProxyRewroteHost() {
        assertThat(guard.allows(request("POST", "Origin", "https://home.example.org"))).isTrue();
        assertThat(guard.allows(request("POST", "Origin", "https://home.example.org", "Sec-Fetch-Site", "cross-site"))).isTrue();
        assertThat(guard.allows(request("DELETE", "Origin", "https://other.example.org"))).isFalse();
    }
}
```

`src/test/java/dev/andre/homecontrol/security/LoginRateLimiterTest.java` — a mutable clock (`AtomicReference<Instant>` wrapped in a `Clock` subclass); limiter `new LoginRateLimiter(clock, 5, 50, Duration.ofMinutes(15))`. Test cases:
- `allowsFiveFailuresPerAddressThenBlocksUntilTheOldestExpires`: 5 × `failed("10.0.0.2")` → `blockedFor("10.0.0.2")` = `Optional.of(Duration.ofMinutes(15))`; `blockedFor("10.0.0.3")` empty; advance 15 min + 1 s → empty.
- `aSuccessClearsThatAddress`: 4 failures, `succeeded`, 4 failures → not blocked.
- `fiftyFailuresAcrossAddressesBlockEveryone`: 50 failures from 50 addresses → `blockedFor("10.9.9.9")` present.
- `forgetsExpiredAddresses`: 20 000 addresses fail once, advance 16 min, one more call → internal map size ≤ 1 (expose `int trackedAddresses()` package-private).

`src/test/java/dev/andre/homecontrol/security/LoginServiceTest.java` — real `SecretStore` in `@TempDir`, `MockHttpServletRequest`. Test cases:
- `noLoginIsRequiredWhileThereAreNoSecrets`: `loginRequired()` false; `isAuthenticated(new MockHttpServletRequest())` true.
- `theFirstSecretNeedsAValidNewPassword`: `storeSecrets(Map.of("jellyfin.token","t"), "short", "short", req)` → `PasswordRejectedException` with message `The login password needs at least 10 characters`; `"long enough 1", "long enough 2"` → message `The two passwords do not match`; nothing written (`store.hasSecrets()` false).
- `storingTheFirstSecretSetsTheLoginAndLogsThisBrowserIn`: valid pair → `loginRequired()` true; `isAuthenticated(req)` true (same `MockHttpServletRequest`, session created); a fresh request → false.
- `laterSecretsNeedAnAuthenticatedRequest`: after the first, `storeSecrets(..., null, null, new MockHttpServletRequest())` → `LoginRequiredException`; with the logged-in request → stored.
- `authenticateChecksThePasswordAndRotatesTheSessionId`: request with an existing session id `s1`; wrong password → false and no attribute; right password → true, `request.getSession().getId()` differs from `s1`.
- `changingThePasswordLogsOutOtherBrowsers`: two logged-in requests A and B; `changePassword(current, new, new, A)` → A authenticated, B not; wrong current → `PasswordRejectedException("The current password is wrong")`.
- `removingTheLastSecretEndsTheLoginRequirement`: `removeSecrets(List.of("jellyfin.token"))` → `loginRequired()` false.

- [ ] **Step 8: Implement the security classes**

`security/Argon2PasswordHasher.java`:

```java
package dev.andre.homecontrol.security;

import dev.andre.homecontrol.crypto.Argon2id;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Argon2id password hashes in PHC string format (OWASP minimum parameters). */
public final class Argon2PasswordHasher {

    public static final int MEMORY_KIB = 19456;
    public static final int ITERATIONS = 2;
    public static final int PARALLELISM = 1;
    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;
    private static final int MAX_MEMORY_KIB = 262_144;
    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)");
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();

    private final SecureRandom random;

    public Argon2PasswordHasher(SecureRandom random) {
        this.random = random;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = Argon2id.derive(password.getBytes(StandardCharsets.UTF_8), salt,
                MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
    }

    /** False for a wrong password and for any malformed or abusive hash string; never throws. */
    public boolean matches(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        Matcher matcher = PHC.matcher(encoded);
        if (!matcher.matches()) {
            return false;
        }
        int memory = Integer.parseInt(matcher.group(1));
        int iterations = Integer.parseInt(matcher.group(2));
        int parallelism = Integer.parseInt(matcher.group(3));
        if (parallelism < 1 || parallelism > 16 || iterations < 1 || memory < 8 * parallelism || memory > MAX_MEMORY_KIB) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(matcher.group(4));
            expected = Base64.getDecoder().decode(matcher.group(5));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (salt.length < 8 || expected.length < 16 || expected.length > 64) {
            return false;
        }
        byte[] actual = Argon2id.derive(password.getBytes(StandardCharsets.UTF_8), salt,
                memory, iterations, parallelism, expected.length);
        return MessageDigest.isEqual(actual, expected);
    }
}
```

Exceptions (`security/`), each `public class X extends RuntimeException`:
- `PasswordRejectedException(String message)` — the message is user-facing.
- `LoginRequiredException()` — message `Log in first`.
- `LoginBusyException()` — message `The server is busy checking other logins; try again in a moment`.

`security/LoginService.java`:

```java
package dev.andre.homecontrol.security;

import dev.andre.homecontrol.storage.LoginCredential;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** The single household password (spec §9). A login exists exactly when secrets exist. */
public class LoginService {

    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 1024;
    static final String SESSION_ATTRIBUTE = LoginService.class.getName() + ".version";

    private final SecretStore store;
    private final Argon2PasswordHasher hasher;
    private final SecureRandom random;
    /** Each verification holds ~19 MiB; two at a time bounds memory under a login flood. */
    private final Semaphore verifications = new Semaphore(2);

    public LoginService(SecretStore store, Argon2PasswordHasher hasher, SecureRandom random) {
        this.store = store;
        this.hasher = hasher;
        this.random = random;
    }

    public boolean loginRequired() {
        return store.login().isPresent();
    }

    public boolean isAuthenticated(HttpServletRequest request) {
        Optional<LoginCredential> login = store.login();
        if (login.isEmpty()) {
            return true;
        }
        HttpSession session = request.getSession(false);
        return session != null && login.get().version().equals(session.getAttribute(SESSION_ATTRIBUTE));
    }

    public boolean authenticate(String password, HttpServletRequest request) {
        Optional<LoginCredential> login = store.login();
        if (login.isEmpty()) {
            return true;
        }
        if (!verify(password, login.get())) {
            return false;
        }
        startSession(request, login.get());
        return true;
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public void checkNewPassword(String password, String confirmation) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new PasswordRejectedException("The login password needs at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new PasswordRejectedException("The login password can have at most " + MAX_PASSWORD_LENGTH + " characters");
        }
        if (!password.equals(confirmation)) {
            throw new PasswordRejectedException("The two passwords do not match");
        }
    }

    /**
     * The first secrets need a new login password and log this browser in; later secrets need an
     * already authenticated request. Nothing is stored before the password is accepted.
     */
    public synchronized void storeSecrets(Map<String, String> secrets, String newPassword, String confirmation,
                                          HttpServletRequest request) {
        if (store.login().isEmpty()) {
            checkNewPassword(newPassword, confirmation);
            LoginCredential credential = newCredential(newPassword);
            store.putFirstSecrets(secrets, credential);
            startSession(request, credential);
            return;
        }
        if (!isAuthenticated(request)) {
            throw new LoginRequiredException();
        }
        store.putSecrets(secrets);
    }

    public synchronized void removeSecrets(Collection<String> names) {
        store.removeSecrets(names);
    }

    public synchronized void changePassword(String current, String next, String confirmation, HttpServletRequest request) {
        LoginCredential login = store.login()
                .orElseThrow(() -> new PasswordRejectedException("There is no login password to change"));
        if (!verify(current, login)) {
            throw new PasswordRejectedException("The current password is wrong");
        }
        checkNewPassword(next, confirmation);
        LoginCredential credential = newCredential(next);
        store.replaceLogin(credential);
        startSession(request, credential);
    }

    private boolean verify(String password, LoginCredential login) {
        if (password == null || password.length() > MAX_PASSWORD_LENGTH) {
            return false;
        }
        boolean acquired;
        try {
            acquired = verifications.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoginBusyException();
        }
        if (!acquired) {
            throw new LoginBusyException();
        }
        try {
            return hasher.matches(password, login.passwordHash());
        } finally {
            verifications.release();
        }
    }

    private LoginCredential newCredential(String password) {
        byte[] version = new byte[16];
        random.nextBytes(version);
        return new LoginCredential(hasher.hash(password), Base64.getUrlEncoder().withoutPadding().encodeToString(version));
    }

    private static void startSession(HttpServletRequest request, LoginCredential credential) {
        HttpSession session = request.getSession(true);
        request.changeSessionId(); // no session fixation
        session.setAttribute(SESSION_ATTRIBUTE, credential.version());
    }
}
```

`security/LoginRateLimiter.java`:

```java
package dev.andre.homecontrol.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Failed logins per client address and in total, in a sliding window. In memory; a restart resets it. */
public class LoginRateLimiter {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private final Clock clock;
    private final int perAddress;
    private final int total;
    private final Duration window;
    private final Map<String, Deque<Instant>> failuresByAddress = new HashMap<>();
    private final Deque<Instant> allFailures = new ArrayDeque<>();

    public LoginRateLimiter(Clock clock, int perAddress, int total, Duration window) {
        this.clock = clock;
        this.perAddress = perAddress;
        this.total = total;
        this.window = window;
    }

    /** How long this address must wait before the next attempt, or empty if it may try now. */
    public synchronized Optional<Duration> blockedFor(String address) {
        Instant now = clock.instant();
        prune(now);
        if (allFailures.size() >= total) {
            return Optional.of(Duration.between(now, allFailures.peekFirst().plus(window)));
        }
        Deque<Instant> failures = failuresByAddress.get(address);
        if (failures != null && failures.size() >= perAddress) {
            return Optional.of(Duration.between(now, failures.peekFirst().plus(window)));
        }
        return Optional.empty();
    }

    public synchronized void failed(String address) {
        Instant now = clock.instant();
        prune(now);
        if (failuresByAddress.size() >= MAX_TRACKED_ADDRESSES && !failuresByAddress.containsKey(address)) {
            failuresByAddress.clear(); // the global cap still bounds guessing
        }
        failuresByAddress.computeIfAbsent(address, ignored -> new ArrayDeque<>()).addLast(now);
        allFailures.addLast(now);
    }

    public synchronized void succeeded(String address) {
        failuresByAddress.remove(address);
    }

    synchronized int trackedAddresses() {
        return failuresByAddress.size();
    }

    private void prune(Instant now) {
        Instant cutoff = now.minus(window);
        while (!allFailures.isEmpty() && !allFailures.peekFirst().isAfter(cutoff)) {
            allFailures.removeFirst();
        }
        failuresByAddress.values().forEach(failures -> {
            while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) {
                failures.removeFirst();
            }
        });
        failuresByAddress.values().removeIf(Deque::isEmpty);
    }
}
```

`security/CrossOriginGuard.java`:

```java
package dev.andre.homecontrol.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses cross-origin state-changing browser requests — the algorithm of Go 1.25's
 * {@code http.CrossOriginProtection}: Fetch Metadata when the browser sends it, else Origin vs Host.
 */
public final class CrossOriginGuard {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> trustedOrigins;

    public CrossOriginGuard(List<String> trustedOrigins) {
        this.trustedOrigins = trustedOrigins == null ? Set.of() : trustedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(origin -> origin.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && trustedOrigins.contains(origin.strip().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return site.equals("same-origin") || site.equals("none");
        }
        if (origin == null) {
            return true; // not a browser, or one too old to send either header
        }
        String authority;
        try {
            authority = new URI(origin.strip()).getRawAuthority();
        } catch (URISyntaxException e) {
            return false;
        }
        return authority != null && authority.equalsIgnoreCase(host(request));
    }

    private static String host(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null) {
            return host;
        }
        int port = request.getServerPort();
        return port == 80 || port == 443 || port <= 0 ? request.getServerName() : request.getServerName() + ":" + port;
    }
}
```

`security/LoginGateFilter.java`:

```java
package dev.andre.homecontrol.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Once a login exists every path except the login page and its stylesheet needs an authenticated
 * session — pages, JSON, SSE, scripts and artwork alike (spec §9). Without a login it only guards
 * the flows that create secrets.
 */
public class LoginGateFilter extends OncePerRequestFilter {

    static final Set<String> OPEN_PATHS = Set.of("/login", "/app.css");
    static final List<String> ALWAYS_GUARDED = List.of("/login", "/logout", "/setup/password", "/setup/sources");

    private final LoginService login;
    private final CrossOriginGuard guard;

    public LoginGateFilter(LoginService login, CrossOriginGuard guard) {
        this.login = login;
        this.guard = guard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = path(request);
        boolean required = login.loginRequired();
        if ((required || alwaysGuarded(path)) && !guard.allows(request)) {
            plain(response, HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused");
            return;
        }
        if (!required) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin"); // no-referrer would make Chrome send Origin: null
        if (OPEN_PATHS.contains(path) || login.isAuthenticated(request)) {
            chain.doFilter(request, response);
            return;
        }
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Redirect", "/login");
            plain(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first");
        } else if ("GET".equals(request.getMethod()) && accepts(request, "text/html")) {
            String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect("/login?next=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
        } else {
            plain(response, HttpServletResponse.SC_UNAUTHORIZED, "Log in first");
        }
    }

    /**
     * The container's decoded, normalized path (servlet path + path info). MockMvc leaves the servlet
     * path empty, so fall back to Spring's path within the application there.
     */
    static String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath == null || servletPath.isEmpty()) {
            return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        }
        return servletPath + (request.getPathInfo() == null ? "" : request.getPathInfo());
    }

    private static boolean alwaysGuarded(String path) {
        return ALWAYS_GUARDED.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static boolean accepts(HttpServletRequest request, String type) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(type);
    }

    private static void plain(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(body);
    }
}
```

`security/SecurityProperties.java`:

```java
package dev.andre.homecontrol.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("home-control.security")
public record SecurityProperties(String secret,
                                 List<String> trustedOrigins,
                                 @DefaultValue("5") int loginAttemptsPerAddress,
                                 @DefaultValue("50") int loginAttemptsTotal,
                                 @DefaultValue("15m") Duration loginWindow) {

    @Override
    public String toString() {
        return "SecurityProperties[secret=" + (secret == null || secret.isBlank() ? "unset" : "set")
                + ", trustedOrigins=" + trustedOrigins + "]";
    }
}
```

`security/SecurityConfiguration.java`:

```java
package dev.andre.homecontrol.security;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.storage.SecretKeySource;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.security.SecureRandom;
import java.time.Clock;

/** Secrets and login. Declared here (not as @Components) so controller test slices do not need them. */
@Configuration
public class SecurityConfiguration {

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    public SecretStore secretStore(AndroidTvProperties storage, SecurityProperties security, SecureRandom random) {
        SecretKeySource keys = new SecretKeySource(security.secret(), storage.dataDir().resolve("secret.key"), random);
        return new SecretStore(storage.dataDir().resolve("secrets.json"), keys, random);
    }

    @Bean
    public Argon2PasswordHasher argon2PasswordHasher(SecureRandom random) {
        return new Argon2PasswordHasher(random);
    }

    @Bean
    public LoginService loginService(SecretStore store, Argon2PasswordHasher hasher, SecureRandom random) {
        return new LoginService(store, hasher, random);
    }

    @Bean
    public LoginRateLimiter loginRateLimiter(SecurityProperties security) {
        return new LoginRateLimiter(Clock.systemUTC(), security.loginAttemptsPerAddress(),
                security.loginAttemptsTotal(), security.loginWindow());
    }

    @Bean
    public FilterRegistrationBean<LoginGateFilter> loginGateFilter(LoginService login, SecurityProperties security) {
        FilterRegistrationBean<LoginGateFilter> registration =
                new FilterRegistrationBean<>(new LoginGateFilter(login, new CrossOriginGuard(security.trustedOrigins())));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
```

(If `AndroidTvProperties` has been renamed or the data directory moved in the real code, inject whatever bean A/B use for `dataDir`.)

`security/LoginModelAdvice.java`:

```java
package dev.andre.homecontrol.security;

import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Tells the setup page whether to show the login password section. */
@ControllerAdvice(assignableTypes = SetupController.class)
public class LoginModelAdvice {

    private final ObjectProvider<LoginService> login;

    public LoginModelAdvice(ObjectProvider<LoginService> login) {
        this.login = login;
    }

    @ModelAttribute("loginRequired")
    public boolean loginRequired() {
        LoginService service = login.getIfAvailable();
        return service != null && service.loginRequired();
    }
}
```

- [ ] **Step 9: Run the security unit tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.security.*'`
Expected: PASS.

- [ ] **Step 10: Write the failing gating test**

`src/test/java/dev/andre/homecontrol/web/LoginGatingTest.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoginGatingTest {

    static final String PASSWORD = "household password";
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("login-gating");
        registry.add("shield.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LoginService login;

    /** Each method gets a new context (fresh rate limiter), so the files must go too. */
    @AfterEach
    void deleteSecrets() throws IOException {
        Files.deleteIfExists(dataDir.resolve("secrets.json"));
        Files.deleteIfExists(dataDir.resolve("secret.key"));
    }

    private void storeAFirstSecret() {
        login.storeSecrets(Map.of("jellyfin.token", "0123456789abcdef"), PASSWORD, PASSWORD, new MockHttpServletRequest());
    }

    private MockHttpSession loggedIn() throws Exception {
        MvcResult result = mockMvc.perform(post("/login").header("Host", "localhost").header("Origin", "http://localhost")
                        .param("password", PASSWORD).param("next", "/setup"))
                .andExpect(status().isFound()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void aDeviceOnlyDeploymentIsUnchanged() throws Exception {
        mockMvc.perform(get("/setup").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("X-Frame-Options"));
        mockMvc.perform(post("/devices/nope/key/HOME").header("Origin", "http://evil.example"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(redirectedUrl("/"));
        try (var files = Files.list(dataDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).doesNotContain("secrets.json", "secret.key");
        }
    }

    @Test
    void onceASecretExistsEveryPathButTheLoginPageIsGated() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(get("/setup").accept("text/html")).andExpect(redirectedUrl("/login?next=%2Fsetup"));
        mockMvc.perform(get("/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/vendor/htmx.min.js")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/devices/nope/key/HOME").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
        mockMvc.perform(get("/app.css")).andExpect(status().isOk());
        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(content().string(containsString("name=\"password\"")));
    }

    @Test
    void theRightPasswordOpensTheAppAndTheWrongOneDoesNot() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(post("/login").param("password", "wrong password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Wrong password")));
        MockHttpSession session = loggedIn();

        mockMvc.perform(get("/setup").session(session).accept("text/html")).andExpect(status().isOk());
        mockMvc.perform(post("/logout").session(session)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/setup").session(session).accept("text/html")).andExpect(status().isFound());
    }

    @Test
    void theNextParameterCannotRedirectOffSite() throws Exception {
        storeAFirstSecret();

        mockMvc.perform(post("/login").param("password", PASSWORD).param("next", "//evil.example/x"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void crossOriginPostsAreRefusedOnceALoginExists() throws Exception {
        storeAFirstSecret();
        MockHttpSession session = loggedIn();

        mockMvc.perform(post("/devices/nope/key/HOME").session(session)
                        .header("Host", "localhost").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/login").header("Host", "localhost").header("Origin", "http://evil.example")
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void repeatedFailuresAreRateLimitedEvenForTheRightPassword() throws Exception {
        storeAFirstSecret();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login").param("password", "wrong " + i)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/login").param("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("Too many attempts")));
    }

    @Test
    void changingThePasswordLogsOutOtherBrowsers() throws Exception {
        storeAFirstSecret();
        MockHttpSession phone = loggedIn();
        MockHttpSession laptop = loggedIn();

        mockMvc.perform(post("/setup/password").session(laptop)
                        .param("current", PASSWORD).param("password", "a new household password")
                        .param("confirmation", "a new household password"))
                .andExpect(redirectedUrl("/setup"));

        mockMvc.perform(get("/setup").session(phone).accept("text/html")).andExpect(status().isFound());
        mockMvc.perform(get("/setup").session(laptop).accept("text/html")).andExpect(status().isOk());
    }
}
```

(`MockMvc` requests have no `Origin` header unless set, which the guard treats as a non-browser client — matching real `curl`.)

- [ ] **Step 11: Write the login controller, login page and setup section**

`web/LoginController.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.security.LoginBusyException;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.security.PasswordRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.Optional;

@Controller
public class LoginController {

    private final LoginService login;
    private final LoginRateLimiter limiter;

    public LoginController(LoginService login, LoginRateLimiter limiter) {
        this.login = login;
        this.limiter = limiter;
    }

    @GetMapping("/login")
    public String page(@RequestParam(required = false) String next, HttpServletRequest request, Model model) {
        if (!login.loginRequired()) {
            return "redirect:/";
        }
        if (login.isAuthenticated(request)) {
            return "redirect:" + safeNext(next);
        }
        model.addAttribute("next", safeNext(next));
        return "login";
    }

    @PostMapping("/login")
    public String submit(@RequestParam(required = false) String password, @RequestParam(required = false) String next,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        if (!login.loginRequired()) {
            return "redirect:/";
        }
        model.addAttribute("next", safeNext(next));
        String address = request.getRemoteAddr();
        Optional<Duration> blocked = limiter.blockedFor(address);
        if (blocked.isPresent()) {
            long minutes = Math.max(1, (blocked.get().toSeconds() + 59) / 60);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(blocked.get().toSeconds()));
            model.addAttribute("error", "Too many attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."));
            return "login";
        }
        boolean ok;
        try {
            ok = login.authenticate(password, request);
        } catch (LoginBusyException e) {
            response.setStatus(429);
            model.addAttribute("error", e.getMessage());
            return "login";
        }
        if (!ok) {
            limiter.failed(address);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            model.addAttribute("error", "Wrong password");
            return "login";
        }
        limiter.succeeded(address);
        return "redirect:" + safeNext(next);
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        login.logout(request);
        return login.loginRequired() ? "redirect:/login" : "redirect:/";
    }

    @PostMapping("/setup/password")
    public String changePassword(@RequestParam(required = false) String current, @RequestParam(required = false) String password,
                                 @RequestParam(required = false) String confirmation, HttpServletRequest request,
                                 RedirectAttributes redirect) {
        try {
            login.changePassword(current, password, confirmation, request);
            redirect.addFlashAttribute("loginMessage", "Password changed. Other browsers need to log in again.");
        } catch (PasswordRejectedException | LoginBusyException e) {
            redirect.addFlashAttribute("loginError", e.getMessage());
        }
        return "redirect:/setup";
    }

    /** Only same-application paths: no scheme-relative, backslash or header-splitting tricks. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")
                || next.chars().anyMatch(c -> c < 0x20)) {
            return "/";
        }
        return next;
    }
}
```

`src/main/resources/templates/login.html` — same `<head>` as `setup.html` (title `Home Control · Log in`, `app.css` only, no scripts); body: `<main class="setup">`, `<h1>Log in</h1>`, `<p class="error" th:if="${error}" th:text="${error}">`, a `<form method="post" th:action="@{/login}">` with `<input type="hidden" name="next" th:value="${next}">`, `<input type="password" name="password" autocomplete="current-password" autofocus required>`, a submit button `Log in`, and the hint `Forgot the password? Stop Home Control and delete secrets.json in its data folder, then reconnect your content sources.` The page never echoes the submitted password.

In `setup.html`, at the end of `<main>`, add:

```html
    <section th:if="${loginRequired}">
        <h2>Login password</h2>
        <p class="error" th:if="${loginError}" th:text="${loginError}">Wrong password</p>
        <p class="hint" th:if="${loginMessage}" th:text="${loginMessage}">Password changed</p>
        <form method="post" th:action="@{/setup/password}">
            <input type="password" name="current" autocomplete="current-password" placeholder="Current password" required>
            <input type="password" name="password" autocomplete="new-password" minlength="10" placeholder="New password" required>
            <input type="password" name="confirmation" autocomplete="new-password" minlength="10" placeholder="Repeat new password" required>
            <button type="submit">Change password</button>
        </form>
        <form method="post" th:action="@{/logout}">
            <button type="submit">Log out</button>
        </form>
    </section>
```

Update `SetupControllerTest` only if it fails to load: `LoginModelAdvice` resolves `LoginService` through `ObjectProvider`, so the slice needs no new mock.

- [ ] **Step 12: Run the gating test, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.LoginGatingTest'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL (every A/B test still passes — device-only behaviour is unchanged).

- [ ] **Step 13: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yaml src/test/resources/application.yaml \
  src/main/resources/templates/setup.html src/main/resources/templates/login.html \
  src/main/java/dev/andre/homecontrol/crypto src/main/java/dev/andre/homecontrol/storage \
  src/main/java/dev/andre/homecontrol/security src/main/java/dev/andre/homecontrol/web/LoginController.java \
  src/test/java/dev/andre/homecontrol/crypto src/test/java/dev/andre/homecontrol/storage \
  src/test/java/dev/andre/homecontrol/security src/test/java/dev/andre/homecontrol/web/LoginGatingTest.java \
  src/test/resources/fixtures/secrets
git commit -m "feat: encrypted secret store and a login once secrets exist"
```

---

### Task 2: C2 · Jellyfin client and setup

**Files:**
- Create: `storage/JsonFileSourceSettings.java`, `sources/jellyfin/JellyfinProperties.java`, `JellyfinSettings.java`, `JellyfinConnection.java`, `JellyfinException.java`, `JellyfinClient.java`, `JellyfinSetupService.java`, `JellyfinSetupController.java`, `JellyfinSetupAdvice.java`, `JellyfinConfiguration.java`, `src/main/resources/templates/fragments/jellyfin-setup.html`
- Modify: `HomeControlConfiguration.java`, `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `storage/JsonFileSourceSettingsTest.java`, `sources/jellyfin/FakeJellyfinServer.java`, `JellyfinClientTest.java`, `JellyfinSettingsTest.java`, `JellyfinSetupServiceTest.java`, `JellyfinSetupControllerTest.java`, `JellyfinModuleSwitchTest.java`; fixtures `src/test/resources/fixtures/jellyfin/system-info-public.json`, `system-info-public-old.json`, `authenticate-by-name.json`, `users.json`, `user.json`

**Interfaces:**
- Consumes: `LoginService.checkNewPassword/storeSecrets/removeSecrets/loginRequired`, `SecretStore.secret`, `PasswordRejectedException`, `LoginRequiredException` (Task 1); `SetupController` (A/B); `AndroidTvProperties.dataDir()`.
- Produces:
  - `class JsonFileSourceSettings { JsonFileSourceSettings(Path file); Map<String,String> get(String sourceId); void put(String sourceId, Map<String,String> settings); void remove(String sourceId); }` — file `{"version":1,"sources":{"<id>":{…}}}`.
  - `record JellyfinProperties(boolean enabled, int connectTimeoutSeconds, int requestTimeoutSeconds, int railSize)` bound to `home-control.jellyfin`.
  - `record JellyfinSettings(URI serverUrl, URI deviceServerUrl, String serverId, String serverName, String serverVersion, String userId, String userName, AuthMode authMode, String deviceId, String castReceiverId, Map<String,String> sessionLinks)` with `enum AuthMode { PASSWORD, API_KEY }`, `SOURCE_ID = "jellyfin"`, `TOKEN_SECRET = "jellyfin.token"`, `DEFAULT_CAST_RECEIVER_ID = "F007D354"`, `Map<String,String> toMap()`, `static Optional<JellyfinSettings> from(Map<String,String>)`, `JellyfinSettings withSessionLink(String deviceId, String jellyfinDeviceId)`, `boolean deviceAddressLooksLocal()`.
  - `record JellyfinConnection(URI serverUrl, String token, String deviceId, String userId)` (redacted `toString`).
  - `class JellyfinException extends RuntimeException { enum Kind { INVALID_INPUT, UNREACHABLE, NOT_JELLYFIN, UNSUPPORTED_VERSION, UNAUTHORIZED, USER_NOT_FOUND, NOT_FOUND, SERVER_ERROR, BAD_RESPONSE } Kind kind(); }`
  - `class JellyfinClient { JellyfinClient(JellyfinProperties); static URI normalizeServerUrl(String); static String id(String); JsonNode publicInfo(URI serverUrl); JsonNode authenticateByName(URI serverUrl, String deviceId, String userName, String password); JsonNode get(JellyfinConnection, String path, Map<String,String> query); JsonNode post(JellyfinConnection, String path, Map<String,String> query, JsonNode body); byte[]-returning image fetch is added in Task 3 }`
  - `class JellyfinSetupService { Optional<JellyfinSettings> settings(); Optional<JellyfinConnection> connection(); JellyfinSettings connect(ConnectRequest, HttpServletRequest); String check(); void disconnect(); void save(JellyfinSettings) }` with `record ConnectRequest(String serverUrl, String deviceServerUrl, JellyfinSettings.AuthMode mode, String userName, String password, String apiKey, String loginPassword, String loginPasswordConfirmation)` (redacted `toString`).
  - Endpoints: `POST /setup/sources/jellyfin`, `POST /setup/sources/jellyfin/test`, `POST /setup/sources/jellyfin/disconnect` → 302 `/setup` with flash `jellyfinMessage` / `jellyfinError` / `jellyfinForm`.
  - Model attribute `jellyfin` (`JellyfinSetupAdvice.View`) on the setup page when the module is enabled.
  - Property `home-control.jellyfin.enabled` (default `true`).

**Jellyfin wire format (normative).**
- Every request carries `Accept: application/json` and `Authorization: MediaBrowser Client="Home+Control", Device="Home+Control", DeviceId="<deviceId>", Version="<version>"` followed by `, Token="<token>"` when a token is known. Each value is `URLEncoder.encode(value, UTF_8)` (the server URL-decodes values, so `+` reads as a space).
- `GET {server}/System/Info/Public` (no token) → `{"LocalAddress","ServerName","Version","ProductName","Id","StartupWizardCompleted"}`. Accept only a JSON object with a non-blank `Id` and (if present) a `ProductName` containing `Jellyfin`; require `Version` ≥ 10.9.
- `POST {server}/Users/AuthenticateByName`, `Content-Type: application/json`, body `{"Username":"<name>","Pw":"<password>"}` (no token) → `{"User":{"Id","Name","ServerId","Configuration":{"CastReceiverId"}},"SessionInfo":{…},"AccessToken","ServerId"}`.
- `GET {server}/Users` (API key) → array of user objects; `GET {server}/Users/{userId}` → user object.
- `POST {server}/Sessions/Logout` (token) → 204; revokes a password-mode access token.
- Redirects are never followed. Status mapping: 2xx → JSON (204 or empty body → `MissingNode`); 3xx → `BAD_RESPONSE`; 401/403 → `UNAUTHORIZED`; 404 → `NOT_FOUND`; 5xx → `SERVER_ERROR`; I/O → `UNREACHABLE`; unparsable JSON → `BAD_RESPONSE`. Messages name the server URL, never a token or a query string.

- [ ] **Step 1: Configuration and fixtures**

Append to `src/main/resources/application.yaml` under `home-control:`:

```yaml
  jellyfin:
    enabled: ${HOME_CONTROL_JELLYFIN_ENABLED:true}
    connect-timeout-seconds: 5
    request-timeout-seconds: 15
    rail-size: 20
```

and the same (literal `enabled: true`) to `src/test/resources/application.yaml`.

`src/test/resources/fixtures/jellyfin/system-info-public.json`:

```json
{
  "LocalAddress": "http://192.168.1.20:8096",
  "ServerName": "nas",
  "Version": "10.11.2",
  "ProductName": "Jellyfin Server",
  "OperatingSystem": "",
  "Id": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
  "StartupWizardCompleted": true
}
```

`system-info-public-old.json`: the same with `"Version": "10.8.13"`.

`authenticate-by-name.json`:

```json
{
  "User": {
    "Name": "andre",
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "Id": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "HasPassword": true,
    "HasConfiguredPassword": true,
    "EnableAutoLogin": false,
    "LastLoginDate": "2026-09-16T08:12:03.4567890Z",
    "LastActivityDate": "2026-09-16T08:12:03.4567890Z",
    "Configuration": {
      "AudioLanguagePreference": "",
      "PlayDefaultAudioTrack": true,
      "SubtitleLanguagePreference": "",
      "DisplayMissingEpisodes": false,
      "SubtitleMode": "Default",
      "EnableNextEpisodeAutoPlay": true,
      "CastReceiverId": "F007D354"
    },
    "Policy": { "IsAdministrator": false, "EnableRemoteControlOfOtherUsers": false, "EnableMediaPlayback": true }
  },
  "SessionInfo": {
    "Id": "9f8e7d6c5b4a39281706f5e4d3c2b1a0",
    "UserId": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "UserName": "andre",
    "Client": "Home Control",
    "DeviceName": "Home Control",
    "DeviceId": "hc-test-device",
    "ApplicationVersion": "0.8.0",
    "IsActive": true,
    "SupportsMediaControl": false,
    "SupportsRemoteControl": false,
    "PlayableMediaTypes": [],
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8"
  },
  "AccessToken": "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80",
  "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8"
}
```

`users.json`:

```json
[
  {
    "Name": "admin",
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "Id": "0f0e0d0c0b0a49887766554433221100",
    "HasPassword": true,
    "Configuration": { "CastReceiverId": "F007D354" },
    "Policy": { "IsAdministrator": true }
  },
  {
    "Name": "Andre",
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "Id": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "HasPassword": true,
    "Configuration": { "CastReceiverId": "6F511C87" },
    "Policy": { "IsAdministrator": false }
  }
]
```

`user.json`: the second object of `users.json` on its own (`"Name": "Andre"`, `"CastReceiverId": "6F511C87"`).

- [ ] **Step 2: Write the fake server (test infrastructure, complete)**

`src/test/java/dev/andre/homecontrol/sources/jellyfin/FakeJellyfinServer.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** In-process Jellyfin: canned responses keyed by "METHOD /path", every request recorded. Unknown routes → 404. */
public final class FakeJellyfinServer implements AutoCloseable {

    public static final String SERVER_ID = "4e1a2b3c4d5e4f60718293a4b5c6d7e8";
    public static final String USER_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    public static final String ACCESS_TOKEN = "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80";

    public record Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private record Canned(int status, String contentType, byte[] body) {
    }

    private final HttpServer server;
    private final Map<String, Canned> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public FakeJellyfinServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** System info, AuthenticateByName and the user — enough to connect in password mode. */
    public FakeJellyfinServer withConnectableServer() {
        return respond("GET", "/System/Info/Public", 200, "system-info-public.json")
                .respond("POST", "/Users/AuthenticateByName", 200, "authenticate-by-name.json")
                .respond("GET", "/Users/" + USER_ID, 200, "user.json")
                .respondJson("POST", "/Sessions/Logout", 204, null);
    }

    public FakeJellyfinServer respond(String method, String path, int status, String fixture) {
        return respondBytes(method, path, status, "application/json; charset=utf-8",
                fixture == null ? new byte[0] : fixture(fixture).getBytes(StandardCharsets.UTF_8));
    }

    public FakeJellyfinServer respondJson(String method, String path, int status, String json) {
        return respondBytes(method, path, status, "application/json; charset=utf-8",
                json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8));
    }

    public FakeJellyfinServer respondBytes(String method, String path, int status, String contentType, byte[] body) {
        routes.put(method + " " + path, new Canned(status, contentType, body));
        return this;
    }

    public List<Recorded> requests(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    public Recorded last(String method, String path) {
        List<Recorded> matching = requests(method, path);
        if (matching.isEmpty()) {
            throw new AssertionError("No " + method + " " + path + " received; got " + requests);
        }
        return matching.getLast();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public static String fixture(String name) {
        try (InputStream in = FakeJellyfinServer.class.getResourceAsStream("/fixtures/jellyfin/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            Map<String, String> query = new LinkedHashMap<>();
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    int eq = pair.indexOf('=');
                    String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                    String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    query.put(key, value);
                }
            }
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), query, headers, body));

            Canned canned = routes.get(exchange.getRequestMethod() + " " + uri.getRawPath());
            if (canned == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", canned.contentType());
            if (canned.status() >= 300 && canned.status() < 400) {
                exchange.getResponseHeaders().set("Location", "http://elsewhere.invalid/");
            }
            if (canned.body().length == 0) {
                exchange.sendResponseHeaders(canned.status(), -1);
                return;
            }
            exchange.sendResponseHeaders(canned.status(), canned.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(canned.body());
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 3: Write the failing tests**

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinClientTest.java` — `client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20))` plus a package-private constructor `JellyfinClient(JellyfinProperties, String version)` used with version `"0.8.0"`. Test cases:
- `sendsTheMediaBrowserAuthorizationHeader`: fake `GET /Users/{USER_ID}` → `user.json`; `client.get(new JellyfinConnection(fake.url(), "tok-1", "dev-1", USER_ID), "/Users/" + USER_ID, Map.of())`; the recorded `authorization` header equals exactly `MediaBrowser Client="Home+Control", Device="Home+Control", DeviceId="dev-1", Version="0.8.0", Token="tok-1"`; `accept` is `application/json`.
- `readsPublicSystemInfo`: returns node with `Id` `4e1a2b3c4d5e4f60718293a4b5c6d7e8` and `ServerName` `nas`; the request has no `Token=` in its authorization header.
- `refusesAServerThatIsNotJellyfin`: `respondJson(GET /System/Info/Public, 200, "{\"hello\":\"world\"}")` → `JellyfinException` kind `NOT_JELLYFIN`, message `<url> answered, but it is not a Jellyfin server`; `respondBytes(..., "text/html", "<html>")` → same kind; no route (404) → same kind.
- `refusesJellyfinOlderThan10_9`: `system-info-public-old.json` → kind `UNSUPPORTED_VERSION`, message contains `10.8.13` and `10.9`.
- `authenticatesByNameWithTheDocumentedBody`: recorded body parses to `{"Username":"andre","Pw":"pa ss\"word"}`; `content-type` starts with `application/json`; the authorization header has no `Token=`; the returned node has `AccessToken`.
- `aRejectedLoginIsNamed`: 401 on AuthenticateByName → `UNAUTHORIZED`, message `Jellyfin rejected the user name or password`.
- `mapsStatusesToNamedErrors`: 401 → `UNAUTHORIZED`; 404 → `NOT_FOUND`; 500 → `SERVER_ERROR`; 302 → `BAD_RESPONSE` with exactly one recorded request (redirect not followed); 204 → `node.isMissingNode()`.
- `namesAnUnreachableServerWithoutLeakingTheToken`: bind a `ServerSocket` on port 0, close it, call `get` with token `secret-token-xyz` → `UNREACHABLE`, message starts with `Could not reach Jellyfin at http://127.0.0.1:` and does not contain `secret-token-xyz`.
- `normalizesServerUrls`: `http://nas:8096/` → `http://nas:8096`; `https://h.example/jellyfin/` → `https://h.example/jellyfin`; ` http://10.0.0.2:8096 ` → `http://10.0.0.2:8096`; `nas:8096`, `ftp://nas`, `http://nas/?a=1`, `http://nas/#x`, `http://user@nas`, `""`, `null` → `INVALID_INPUT`.
- `idsAreValidatedBeforeTheyBecomePathSegments`: `JellyfinClient.id("a1b2-C3")` returns it; `id("../Users")`, `id("")`, `id("a/b")` → `IllegalArgumentException`.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSettingsTest.java` — cases:
- `roundTripsThroughAStringMap`: `from(settings.toMap())` equals `settings`, including two session links (keys `link.living-room`, `link.bedroom`).
- `aMapWithoutServerUrlIsNotConfigured`: `from(Map.of())` is empty.
- `flagsDeviceAddressesTvsCannotReach`: `deviceAddressLooksLocal()` true for `http://localhost:8096`, `http://127.0.0.1:8096`, `http://[::1]:8096`, `http://jellyfin:8096`; false for `http://192.168.1.20:8096`, `http://nas.lan:8096`, `https://media.example.org`.

`src/test/java/dev/andre/homecontrol/storage/JsonFileSourceSettingsTest.java` — cases: `anAbsentFileHasNoSettings` (no file created by `get`); `putWritesAtomicallyAndRoundTrips` (the file parses to `version` 1 with a `sources` object; compare parsed JSON, not pretty-printer spacing); `removeDropsOnlyThatSource`; `aMalformedFileIsANamedStorageException`.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSetupServiceTest.java` — real `SecretStore`, `LoginService`, `JsonFileSourceSettings` in `@TempDir`, `FakeJellyfinServer`, `MockHttpServletRequest`. Cases:
- `connectsWithAPasswordAndStoresOnlyTheToken`: `connect(new ConnectRequest(fake.url() + "/", null, PASSWORD, "andre", "user pw", null, "household pw 1", "household pw 1"), req)` → settings with `serverId` `4e1a…`, `serverName` `nas`, `serverVersion` `10.11.2`, `userId` `a1b2…`, `userName` `andre`, `castReceiverId` `F007D354`, `deviceServerUrl` = `serverUrl` without trailing slash, a 32-hex `deviceId`; `secretStore.secret("jellyfin.token")` = `6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80`; `sources.json` does not contain the token nor `user pw`; `loginService.loginRequired()` true; `loginService.isAuthenticated(req)` true.
- `theLoginPasswordIsCheckedBeforeContactingJellyfin`: login password `short` → `PasswordRejectedException`; `fake.requests()` empty; nothing stored.
- `connectsWithAnApiKeyByUserName`: fake `GET /Users` → `users.json`; mode `API_KEY`, api key `api-key-123`, user `andre` (case differs from `Andre`) → `userId` `a1b2…`, `castReceiverId` `6F511C87`; the `/Users` request carried `Token="api-key-123"`; stored secret = `api-key-123`.
- `anUnknownUserIsNamed`: user `nobody` → kind `USER_NOT_FOUND`, message `No Jellyfin user named 'nobody'`; nothing stored.
- `reconnectingKeepsTheDeviceIdAndLinksAndNeedsTheLogin`: after a first connect, `save(settings.withSessionLink("shield", "jf-dev"))`; a second `connect` with a fresh unauthenticated request → `LoginRequiredException`; with the authenticated request → same `deviceId`, `sessionLinks` kept, and `POST /Sessions/Logout` was sent with the *old* token only if the new token differs (fixture returns the same token → no logout).
- `checkReportsServerVersionAndUser`: → `Connected to nas (Jellyfin 10.11.2) as Andre`.
- `disconnectRevokesAPasswordTokenAndEndsTheLoginRequirement`: → `POST /Sessions/Logout` recorded with the token, `settings()` empty, `loginRequired()` false.
- `disconnectStillWorksWhenJellyfinIsDown`: close the fake first → no exception, settings removed.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSetupControllerTest.java` — `@WebMvcTest(JellyfinSetupController.class)` with `@MockitoBean JellyfinSetupService setup`. Cases:
- `aSuccessfulConnectRedirectsWithAMessage`: POST all fields → 302 `/setup`, flash `jellyfinMessage` `Connected to nas as andre`; the `ConnectRequest` captured has mode `PASSWORD` for `mode=password` and `API_KEY` for `mode=api-key`.
- `aFailureKeepsTheNonSecretFieldsButNeverThePasswordOrKey`: service throws `JellyfinException(UNAUTHORIZED, "Jellyfin rejected the user name or password")` → flash `jellyfinError` with that text; flash `jellyfinForm` contains `serverUrl`, `deviceServerUrl`, `mode`, `userName` and no key holding `pw-secret` or `key-secret` values.
- `testAndDisconnectReportTheirOutcome`: `/test` → flash `jellyfinMessage` = `check()` result, or `jellyfinError` on exception; `/disconnect` → `verify(setup).disconnect()`, flash `Jellyfin disconnected`.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinModuleSwitchTest.java` — `@SpringBootTest(properties = "home-control.jellyfin.enabled=false")` with `@AutoConfigureMockMvc` and an isolated `shield.data-dir` (`@DynamicPropertySource`, temp dir). Case `theModuleCanBeSwitchedOff`: the context has no bean of type `JellyfinClient`, `JellyfinSetupService`, `JellyfinSetupController`, `JellyfinSetupAdvice`; `GET /setup` → 200 and the body does not contain `Jellyfin`; `POST /setup/sources/jellyfin` → 404.

- [ ] **Step 4: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.storage.JsonFileSourceSettingsTest'`
Expected: compilation failure — the Jellyfin classes and `JsonFileSourceSettings` do not exist.

- [ ] **Step 5: Implement storage, settings and the client**

`storage/JsonFileSourceSettings.java` — same atomic write pattern as `JsonFileDeviceRegistry` (temp file in the same directory, `ATOMIC_MOVE` + `REPLACE_EXISTING`); `synchronized` methods; `get` returns `Map.of()` when the file or source is absent and never creates the file; unreadable JSON or a root without `"sources"` object → `StorageException("Could not read source settings " + file + "; check file permissions and JSON integrity", e)`. Values are strings; `put` replaces that source's map; `remove` deletes the key and rewrites. Register the bean in `HomeControlConfiguration`:

```java
    @Bean
    public JsonFileSourceSettings sourceSettings(AndroidTvProperties properties) {
        return new JsonFileSourceSettings(properties.dataDir().resolve("sources.json"));
    }
```

`sources/jellyfin/JellyfinProperties.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("home-control.jellyfin")
public record JellyfinProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("5") int connectTimeoutSeconds,
                                 @DefaultValue("15") int requestTimeoutSeconds,
                                 @DefaultValue("20") int railSize) {
}
```

`sources/jellyfin/JellyfinSettings.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Non-secret Jellyfin settings kept in sources.json. The token lives in the secret store. */
public record JellyfinSettings(URI serverUrl, URI deviceServerUrl, String serverId, String serverName,
                               String serverVersion, String userId, String userName, AuthMode authMode,
                               String deviceId, String castReceiverId, Map<String, String> sessionLinks) {

    public static final String SOURCE_ID = "jellyfin";
    public static final String TOKEN_SECRET = "jellyfin.token";
    public static final String DEFAULT_CAST_RECEIVER_ID = "F007D354";
    private static final String LINK_PREFIX = "link.";
    private static final Pattern IP_V4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public enum AuthMode { PASSWORD, API_KEY }

    public JellyfinSettings {
        sessionLinks = sessionLinks == null ? Map.of() : Map.copyOf(sessionLinks);
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("serverUrl", serverUrl.toString());
        map.put("deviceServerUrl", deviceServerUrl.toString());
        map.put("serverId", serverId);
        map.put("serverName", serverName);
        map.put("serverVersion", serverVersion);
        map.put("userId", userId);
        map.put("userName", userName);
        map.put("authMode", authMode.name());
        map.put("deviceId", deviceId);
        map.put("castReceiverId", castReceiverId);
        new TreeMap<>(sessionLinks).forEach((device, jellyfinDevice) -> map.put(LINK_PREFIX + device, jellyfinDevice));
        return map;
    }

    public static Optional<JellyfinSettings> from(Map<String, String> map) {
        if (map == null || map.get("serverUrl") == null || map.get("userId") == null) {
            return Optional.empty();
        }
        Map<String, String> links = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key.startsWith(LINK_PREFIX)) {
                links.put(key.substring(LINK_PREFIX.length()), value);
            }
        });
        return Optional.of(new JellyfinSettings(URI.create(map.get("serverUrl")),
                URI.create(map.getOrDefault("deviceServerUrl", map.get("serverUrl"))),
                map.get("serverId"), map.get("serverName"), map.get("serverVersion"), map.get("userId"),
                map.get("userName"), AuthMode.valueOf(map.getOrDefault("authMode", AuthMode.PASSWORD.name())),
                map.get("deviceId"), map.getOrDefault("castReceiverId", DEFAULT_CAST_RECEIVER_ID), links));
    }

    /** A blank {@code jellyfinDeviceId} removes the link. */
    public JellyfinSettings withSessionLink(String deviceId, String jellyfinDeviceId) {
        Map<String, String> links = new LinkedHashMap<>(sessionLinks);
        if (jellyfinDeviceId == null || jellyfinDeviceId.isBlank()) {
            links.remove(deviceId);
        } else {
            links.put(deviceId, jellyfinDeviceId);
        }
        return new JellyfinSettings(serverUrl, deviceServerUrl, serverId, serverName, serverVersion, userId, userName,
                authMode, this.deviceId, castReceiverId, links);
    }

    /** True when a TV or speaker is unlikely to resolve or reach this address (loopback or a Docker service name). */
    public boolean deviceAddressLooksLocal() {
        String host = deviceServerUrl.getHost();
        if (host == null) {
            return true;
        }
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        return bare.equalsIgnoreCase("localhost") || bare.startsWith("127.") || bare.equals("::1")
                || (!bare.contains(".") && !bare.contains(":") && !IP_V4.matcher(bare).matches());
    }
}
```

`sources/jellyfin/JellyfinConnection.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import java.net.URI;

/** Everything needed for an authenticated call. {@code userId} may be null while resolving an API key's user. */
public record JellyfinConnection(URI serverUrl, String token, String deviceId, String userId) {

    @Override
    public String toString() {
        return "JellyfinConnection[serverUrl=" + serverUrl + ", userId=" + userId + "]";
    }
}
```

`sources/jellyfin/JellyfinException.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

/** A Jellyfin call failed; the message is user-facing and never contains a token. */
public class JellyfinException extends RuntimeException {

    public enum Kind { INVALID_INPUT, UNREACHABLE, NOT_JELLYFIN, UNSUPPORTED_VERSION, UNAUTHORIZED, USER_NOT_FOUND, NOT_FOUND, SERVER_ERROR, BAD_RESPONSE }

    private final Kind kind;

    public JellyfinException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
```

(Task 3 changes the superclass to `ContentSourceException`.)

`sources/jellyfin/JellyfinClient.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only class that speaks HTTP to Jellyfin (spec §7: only sources speak content APIs). */
public class JellyfinClient {

    static final String CLIENT_NAME = "Home Control";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    private final HttpClient http;
    private final JellyfinProperties properties;
    private final String version;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public JellyfinClient(JellyfinProperties properties) {
        this(properties, Optional.ofNullable(JellyfinClient.class.getPackage().getImplementationVersion()).orElse("0.0.0"));
    }

    JellyfinClient(JellyfinProperties properties, String version) {
        this.properties = properties;
        this.version = version;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // a redirect would replay the Authorization header
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
    }

    /** http(s) scheme, a host, no user info, query or fragment; trailing slashes removed. */
    public static URI normalizeServerUrl(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new URISyntaxException(trimmed, "not a plain http(s) URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new JellyfinException(JellyfinException.Kind.INVALID_INPUT,
                    "Enter the Jellyfin address as http://host:8096 (or https://…)");
        }
    }

    /** Jellyfin ids are GUIDs (with or without dashes); anything else never becomes a path segment. */
    public static String id(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a Jellyfin id");
        }
        return value;
    }

    public JsonNode publicInfo(URI serverUrl) {
        JsonNode info;
        try {
            info = send(serverUrl, request(serverUrl, "/System/Info/Public", Map.of(), null, null).GET());
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.NOT_FOUND || e.kind() == JellyfinException.Kind.BAD_RESPONSE) {
                throw notJellyfin(serverUrl);
            }
            throw e;
        }
        String product = info.path("ProductName").asString("Jellyfin Server");
        if (!info.isObject() || info.path("Id").asString("").isBlank() || !product.contains("Jellyfin")) {
            throw notJellyfin(serverUrl);
        }
        String serverVersion = info.path("Version").asString("");
        Matcher matcher = VERSION.matcher(serverVersion);
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 10
                || (Integer.parseInt(matcher.group(1)) == 10 && Integer.parseInt(matcher.group(2)) < 9)) {
            throw new JellyfinException(JellyfinException.Kind.UNSUPPORTED_VERSION,
                    "Jellyfin " + serverVersion + " is too old; Home Control needs Jellyfin 10.9 or newer");
        }
        return info;
    }

    public JsonNode authenticateByName(URI serverUrl, String deviceId, String userName, String password) {
        ObjectNode body = mapper.createObjectNode();
        body.put("Username", userName);
        body.put("Pw", password);
        try {
            return send(serverUrl, request(serverUrl, "/Users/AuthenticateByName", Map.of(), deviceId, null)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))));
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.UNAUTHORIZED) {
                throw new JellyfinException(JellyfinException.Kind.UNAUTHORIZED, "Jellyfin rejected the user name or password");
            }
            throw e;
        }
    }

    public JsonNode get(JellyfinConnection connection, String path, Map<String, String> query) {
        return send(connection.serverUrl(),
                request(connection.serverUrl(), path, query, connection.deviceId(), connection.token()).GET());
    }

    /** {@code body} may be null for commands such as {@code /Sessions/{id}/Playing}. */
    public JsonNode post(JellyfinConnection connection, String path, Map<String, String> query, JsonNode body) {
        HttpRequest.Builder builder = request(connection.serverUrl(), path, query, connection.deviceId(), connection.token());
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
        }
        return send(connection.serverUrl(), builder);
    }

    String authorization(String deviceId, String token) {
        StringBuilder header = new StringBuilder("MediaBrowser ")
                .append("Client=\"").append(encode(CLIENT_NAME)).append("\", ")
                .append("Device=\"").append(encode(CLIENT_NAME)).append("\", ")
                .append("DeviceId=\"").append(encode(deviceId == null ? "home-control" : deviceId)).append("\", ")
                .append("Version=\"").append(encode(version)).append('"');
        if (token != null) {
            header.append(", Token=\"").append(encode(token)).append('"');
        }
        return header.toString();
    }

    private HttpRequest.Builder request(URI serverUrl, String path, Map<String, String> query, String deviceId, String token) {
        StringJoiner joined = new StringJoiner("&", "?", "");
        joined.setEmptyValue("");
        query.forEach((key, value) -> {
            if (value != null) {
                joined.add(encode(key) + "=" + encode(value));
            }
        });
        return HttpRequest.newBuilder(URI.create(serverUrl + path + joined))
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", authorization(deviceId, token));
    }

    private JsonNode send(URI serverUrl, HttpRequest.Builder builder) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw unreachable(serverUrl, "connection timed out");
        } catch (HttpTimeoutException e) {
            throw unreachable(serverUrl, "no answer in time");
        } catch (ConnectException e) {
            throw unreachable(serverUrl, e.getCause() instanceof UnresolvedAddressException ? "unknown host" : "connection refused");
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(serverUrl, "interrupted");
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE,
                    "Jellyfin at " + serverUrl + " redirected elsewhere; enter the final server address");
        }
        if (status == 401 || status == 403) {
            throw new JellyfinException(JellyfinException.Kind.UNAUTHORIZED,
                    "Jellyfin rejected the stored credentials; reconnect Jellyfin on the setup page");
        }
        if (status == 404) {
            throw new JellyfinException(JellyfinException.Kind.NOT_FOUND, "Jellyfin at " + serverUrl + " does not know that");
        }
        if (status >= 400) {
            throw new JellyfinException(JellyfinException.Kind.SERVER_ERROR, "Jellyfin at " + serverUrl + " answered HTTP " + status);
        }
        if (response.body().length == 0) {
            return MissingNode.getInstance();
        }
        try {
            return mapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an unreadable answer");
        }
    }

    private static JellyfinException unreachable(URI serverUrl, String reason) {
        return new JellyfinException(JellyfinException.Kind.UNREACHABLE, "Could not reach Jellyfin at " + serverUrl
                + " (" + reason + "). Check the address and that Home Control can reach it.");
    }

    private static JellyfinException notJellyfin(URI serverUrl) {
        return new JellyfinException(JellyfinException.Kind.NOT_JELLYFIN, serverUrl + " answered, but it is not a Jellyfin server");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

(`mapper.writeValueAsBytes` and `readTree(byte[])` throw unchecked `JacksonException` in Jackson 3. If the local `JsonMapper` lacks `readTree(byte[])`, use `readTree(new String(bytes, UTF_8))`.)

- [ ] **Step 6: Implement the setup service, controller, advice and module configuration**

`sources/jellyfin/JellyfinSetupService.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class JellyfinSetupService {

    public record ConnectRequest(String serverUrl, String deviceServerUrl, JellyfinSettings.AuthMode mode,
                                 String userName, String password, String apiKey,
                                 String loginPassword, String loginPasswordConfirmation) {
        @Override
        public String toString() {
            return "ConnectRequest[serverUrl=" + serverUrl + ", mode=" + mode + ", userName=" + userName + "]";
        }
    }

    private final JellyfinClient client;
    private final JsonFileSourceSettings sources;
    private final SecretStore secrets;
    private final LoginService login;

    public JellyfinSetupService(JellyfinClient client, JsonFileSourceSettings sources, SecretStore secrets, LoginService login) {
        this.client = client;
        this.sources = sources;
        this.secrets = secrets;
        this.login = login;
    }

    public Optional<JellyfinSettings> settings() {
        return JellyfinSettings.from(sources.get(JellyfinSettings.SOURCE_ID));
    }

    public Optional<JellyfinConnection> connection() {
        return settings().flatMap(settings -> secrets.secret(JellyfinSettings.TOKEN_SECRET)
                .map(token -> new JellyfinConnection(settings.serverUrl(), token, settings.deviceId(), settings.userId())));
    }

    public JellyfinSettings connect(ConnectRequest request, HttpServletRequest http) {
        URI server = JellyfinClient.normalizeServerUrl(request.serverUrl());
        URI deviceServer = request.deviceServerUrl() == null || request.deviceServerUrl().isBlank()
                ? server : JellyfinClient.normalizeServerUrl(request.deviceServerUrl());
        JellyfinSettings.AuthMode mode = request.mode() == null ? JellyfinSettings.AuthMode.PASSWORD : request.mode();
        if (!login.loginRequired()) {
            login.checkNewPassword(request.loginPassword(), request.loginPasswordConfirmation()); // before any network call
        }
        if (request.userName() == null || request.userName().isBlank()) {
            throw new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Enter the Jellyfin user name");
        }
        Optional<JellyfinSettings> previous = settings();
        Optional<String> previousToken = secrets.secret(JellyfinSettings.TOKEN_SECRET);
        String deviceId = previous.map(JellyfinSettings::deviceId)
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));

        JsonNode info = client.publicInfo(server);
        String token;
        JsonNode user;
        if (mode == JellyfinSettings.AuthMode.PASSWORD) {
            JsonNode authenticated = client.authenticateByName(server, deviceId, request.userName().strip(),
                    request.password() == null ? "" : request.password());
            token = authenticated.path("AccessToken").asString("");
            user = authenticated.path("User");
        } else {
            if (request.apiKey() == null || request.apiKey().isBlank()) {
                throw new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Enter the Jellyfin API key");
            }
            token = request.apiKey().strip();
            user = findUser(client.get(new JellyfinConnection(server, token, deviceId, null), "/Users", Map.of()),
                    request.userName().strip());
        }
        if (token.isBlank() || user.path("Id").asString("").isBlank()) {
            throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin did not return a usable login");
        }
        String receiver = user.path("Configuration").path("CastReceiverId").asString("");
        JellyfinSettings next = new JellyfinSettings(server, deviceServer, info.path("Id").asString(""),
                info.path("ServerName").asString(""), info.path("Version").asString(""),
                user.path("Id").asString(""), user.path("Name").asString(request.userName().strip()), mode, deviceId,
                receiver.isBlank() ? JellyfinSettings.DEFAULT_CAST_RECEIVER_ID : receiver,
                previous.map(JellyfinSettings::sessionLinks).orElse(Map.of()));
        try {
            login.storeSecrets(Map.of(JellyfinSettings.TOKEN_SECRET, token), request.loginPassword(),
                    request.loginPasswordConfirmation(), http);
        } catch (RuntimeException e) {
            if (mode == JellyfinSettings.AuthMode.PASSWORD) {
                revokeQuietly(new JellyfinConnection(server, token, deviceId, next.userId()));
            }
            throw e;
        }
        sources.put(JellyfinSettings.SOURCE_ID, next.toMap());
        previous.filter(old -> old.authMode() == JellyfinSettings.AuthMode.PASSWORD)
                .ifPresent(old -> previousToken.filter(oldToken -> !oldToken.equals(token))
                        .ifPresent(oldToken -> revokeQuietly(new JellyfinConnection(old.serverUrl(), oldToken, old.deviceId(), old.userId()))));
        return next;
    }

    /** Session links and other non-secret changes. */
    public void save(JellyfinSettings settings) {
        sources.put(JellyfinSettings.SOURCE_ID, settings.toMap());
    }

    public String check() {
        JellyfinSettings settings = settings().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
        JellyfinConnection connection = connection().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.UNAUTHORIZED, "The Jellyfin token is missing; reconnect Jellyfin"));
        JsonNode info = client.publicInfo(settings.serverUrl());
        JsonNode user = client.get(connection, "/Users/" + JellyfinClient.id(settings.userId()), Map.of());
        return "Connected to " + info.path("ServerName").asString("Jellyfin") + " (Jellyfin "
                + info.path("Version").asString("?") + ") as " + user.path("Name").asString(settings.userName());
    }

    public void disconnect() {
        Optional<JellyfinSettings> settings = settings();
        settings.filter(s -> s.authMode() == JellyfinSettings.AuthMode.PASSWORD)
                .flatMap(ignored -> connection())
                .ifPresent(this::revokeQuietly);
        sources.remove(JellyfinSettings.SOURCE_ID);
        login.removeSecrets(List.of(JellyfinSettings.TOKEN_SECRET));
    }

    private static JsonNode findUser(JsonNode users, String name) {
        for (JsonNode user : users) {
            if (user.path("Name").asString("").equalsIgnoreCase(name)) {
                return user;
            }
        }
        throw new JellyfinException(JellyfinException.Kind.USER_NOT_FOUND, "No Jellyfin user named '" + name + "'");
    }

    private void revokeQuietly(JellyfinConnection connection) {
        try {
            client.post(connection, "/Sessions/Logout", Map.of(), null);
        } catch (JellyfinException ignored) {
            // best effort: the token stays valid on the server until an admin removes the device
        }
    }
}
```

`sources/jellyfin/JellyfinSetupController.java` — `@Controller` with `@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)`:
- `POST /setup/sources/jellyfin` with `@RequestParam(required = false)` `serverUrl`, `deviceServerUrl`, `mode` (`password` | `api-key`, default `password`), `userName`, `password`, `apiKey`, `loginPassword`, `loginPasswordConfirmation`; builds `ConnectRequest`; on success flash `jellyfinMessage` = `"Connected to " + serverName + " as " + userName`; catches `JellyfinException`, `PasswordRejectedException`, `LoginRequiredException`, `IllegalStateException` (a concurrent first secret) → flash `jellyfinError` = message and flash `jellyfinForm` = `Map.of("serverUrl", …, "deviceServerUrl", …, "mode", …, "userName", …)` with nulls replaced by `""`; returns `redirect:/setup`.
- `POST /setup/sources/jellyfin/test` → flash `jellyfinMessage` = `setup.check()` or `jellyfinError` on `JellyfinException`.
- `POST /setup/sources/jellyfin/disconnect` → `setup.disconnect()`, flash `jellyfinMessage` = `Jellyfin disconnected`.

`sources/jellyfin/JellyfinSetupAdvice.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)
public class JellyfinSetupAdvice {

    /** What the setup page shows about Jellyfin. Never holds a token. */
    public record View(boolean configured, String serverName, String serverVersion, String serverUrl,
                       String deviceServerUrl, String userName, String mode, boolean deviceAddressLooksLocal,
                       boolean needsLoginPassword) {
    }

    private final ObjectProvider<JellyfinSetupService> setup;
    private final ObjectProvider<LoginService> login;

    public JellyfinSetupAdvice(ObjectProvider<JellyfinSetupService> setup, ObjectProvider<LoginService> login) {
        this.setup = setup;
        this.login = login;
    }

    @ModelAttribute("jellyfin")
    public View jellyfin() {
        JellyfinSetupService service = setup.getIfAvailable();
        LoginService loginService = login.getIfAvailable();
        boolean needsPassword = loginService == null || !loginService.loginRequired();
        if (service == null) {
            return new View(false, null, null, null, null, null, "password", false, needsPassword);
        }
        return service.settings()
                .map(s -> new View(true, s.serverName(), s.serverVersion(), s.serverUrl().toString(),
                        s.deviceServerUrl().toString(), s.userName(),
                        s.authMode() == JellyfinSettings.AuthMode.API_KEY ? "api-key" : "password",
                        s.deviceAddressLooksLocal(), needsPassword))
                .orElseGet(() -> new View(false, null, null, null, null, null, "password", false, needsPassword));
    }
}
```

`sources/jellyfin/JellyfinConfiguration.java` — `@Configuration` with the same `@ConditionalOnProperty`; beans `JellyfinClient jellyfinClient(JellyfinProperties)` and `JellyfinSetupService jellyfinSetupService(JellyfinClient, JsonFileSourceSettings, SecretStore, LoginService)`. Later tasks add their beans here.

`src/main/resources/templates/fragments/jellyfin-setup.html` — one `<section th:fragment="section">` titled `Jellyfin`:
- `<p class="error" th:if="${jellyfinError}" th:text="${jellyfinError}">` and `<p class="hint" th:if="${jellyfinMessage}" th:text="${jellyfinMessage}">`.
- When `${jellyfin.configured()}`: text `Connected to <serverName> (Jellyfin <serverVersion>) as <userName>` and `Server <serverUrl>`, `TVs and speakers use <deviceServerUrl>`; if `deviceAddressLooksLocal()` a `<p class="error">` warning `TVs and speakers cannot reach <deviceServerUrl>. Reconnect with the address they should use.`; if mode is `api-key` the hint `API-key mode hands an administrator key to Cast devices. Prefer a user login.`; forms posting to `/setup/sources/jellyfin/test` (button `Test connection`) and `/setup/sources/jellyfin/disconnect` (button `Disconnect`).
- Always (heading `Connect` or `Reconnect`): a form posting to `/setup/sources/jellyfin` with `serverUrl` (`type="url"`, required, placeholder `http://192.168.1.20:8096`, value from `${jellyfinForm?.serverUrl}` or the configured URL), `deviceServerUrl` (optional, label `Address for TVs and speakers (if different)`), a `mode` select (`password` → `User name and password (recommended)`, `api-key` → `API key`), `userName` (required), `password` (`type="password"`, `autocomplete="off"`, never pre-filled), `apiKey` (`type="password"`, never pre-filled), and — only when `${jellyfin.needsLoginPassword()}` — `loginPassword` and `loginPasswordConfirmation` (`type="password"`, `minlength="10"`, `autocomplete="new-password"`) under the text `Connecting a content source stores a secret, so Home Control will require this login password from now on.`

In `setup.html`, before the login password section, add:

```html
    <th:block th:if="${jellyfin != null}">
        <section th:replace="~{fragments/jellyfin-setup :: section}"></section>
    </th:block>
```

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.storage.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/storage/JsonFileSourceSettings.java \
  src/main/java/dev/andre/homecontrol/sources src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java \
  src/main/resources/templates/fragments/jellyfin-setup.html src/main/resources/templates/setup.html \
  src/main/resources/application.yaml src/test/resources/application.yaml \
  src/test/java/dev/andre/homecontrol/storage/JsonFileSourceSettingsTest.java \
  src/test/java/dev/andre/homecontrol/sources src/test/resources/fixtures/jellyfin
git commit -m "feat: connect a Jellyfin server from the setup page"
```

---

### Task 3: C3 · Rails: Resume, Next Up, Latest

**Files:**
- Create: `core/content/ContentSource.java`, `core/content/RailDescriptor.java`, `core/content/Rail.java`, `core/content/ContentSourceException.java`, `core/content/ContentSources.java`, `sources/jellyfin/JellyfinItemMapper.java`, `sources/jellyfin/JellyfinContentSource.java`, `sources/jellyfin/JellyfinImageController.java`, `web/ContentItemView.java`, `web/ContentController.java`
- Modify: `core/playback/ContentItem.java`, `sources/jellyfin/JellyfinException.java`, `sources/jellyfin/JellyfinClient.java`, `sources/jellyfin/JellyfinConfiguration.java`, `HomeControlConfiguration.java`
- Test: `core/playback/ContentItemTest.java`, `core/content/ContentSourcesTest.java`, `sources/jellyfin/JellyfinItemMapperTest.java`, `sources/jellyfin/JellyfinContentSourceTest.java`, `sources/jellyfin/JellyfinImageControllerTest.java`, `sources/jellyfin/JellyfinClientTest.java`, `web/ContentControllerTest.java`; fixtures `src/test/resources/fixtures/jellyfin/resume.json`, `next-up.json`, `latest.json`, `item-episode.json`, `item-movie.json`

**Interfaces:**
- Consumes: `ContentItem`, `ContentKind`, `PlayableRef.JellyfinItem(String serverId, String itemId, long resumeTicks)` (A); `JellyfinClient`, `JellyfinSetupService.connection()/settings()`, `JellyfinProperties.railSize()`, `FakeJellyfinServer` (Task 2).
- Produces:
  - `ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle, URI artwork, List<PlayableRef> playables, Double progress)` plus the unchanged seven-argument constructor (progress null).
  - `interface ContentSource { String id(); String displayName(); default boolean available() { return true; } List<RailDescriptor> rails(); Rail rail(String railId); Optional<ContentItem> item(String itemId); default boolean searchable() { return false; } default List<ContentItem> search(String query, int limit) }` — `rails()` does no I/O and is empty while `!available()`; `rail`, `item`, `search` do I/O and throw `ContentSourceException` with a user-facing message; `rail` throws `IllegalArgumentException` for an unknown rail id.
  - `record RailDescriptor(String sourceId, String id, String title)`; `record Rail(RailDescriptor descriptor, List<ContentItem> items, Instant fetchedAt)`; `class ContentSourceException extends RuntimeException` (`(String)`, `(String, Throwable)`); `class ContentSources { ContentSources(List<ContentSource>); List<ContentSource> all(); Optional<ContentSource> find(String id); List<ContentSource> searchable(); }` (searchable = `searchable() && available()`).
  - `JellyfinException extends ContentSourceException`.
  - `final class JellyfinItemMapper { static Optional<ContentItem> toItem(JsonNode item); static List<ContentItem> toItems(JsonNode array); static final String IMAGE_PATH = "/sources/jellyfin/images/"; }`
  - `class JellyfinContentSource implements ContentSource` — id `jellyfin`, rails `resume` "Continue watching", `next-up` "Next up", `latest` "Latest in library".
  - `JellyfinClient.Image(String contentType, byte[] bytes)` and `Optional<Image> image(URI serverUrl, String itemId, String type, String tag, int maxWidth)` — anonymous (no `Authorization` header).
  - `GET /sources/jellyfin/images/{itemId}/{type}?tag=&width=` → image bytes / 400 / 404 / 502.
  - `record ContentItemView(String id, String sourceId, String kind, String title, String subtitle, String artwork, Double progress)` with `static ContentItemView of(ContentItem)`.
  - `GET /sources` → `[{"id","name","available","searchable","rails":[{"id","title"}]}]`; `GET /sources/{sourceId}/rails/{railId}` → `{"sourceId","id","title","fetchedAt","items":[ContentItemView…]}` / 404 / 502 text.

**Jellyfin rail queries (normative).** All with the `Authorization` header from Task 2 and `enableImageTypes=Primary,Thumb,Backdrop`, `imageTypeLimit=1`, `enableUserData=true`, `limit=<railSize>`, `userId=<userId>`:
- Continue watching: `GET /UserItems/Resume` + `mediaTypes=Video` → `{"Items":[…]}`.
- Next up: `GET /Shows/NextUp` + `enableResumable=false` → `{"Items":[…]}`.
- Latest in library: `GET /Items/Latest` + `includeItemTypes=Movie,Episode`, `groupItems=false` → `[…]`.
- One item: `GET /Items/{itemId}?userId=<userId>` → item object.
- Artwork: `GET /Items/{itemId}/Images/{type}?maxWidth=<w>&quality=90[&tag=<tag>]`, no `Authorization` header.

**Item mapping (normative).** Skip items without `Id` or with `IsFolder: true`. `Type` → kind: `Movie`→`MOVIE`, `Episode`→`EPISODE`, `Audio`→`TRACK`, anything else→`VIDEO`. Episode: title `SeriesName` (fallback `Name`), subtitle `S<ParentIndexNumber>:E<IndexNumber> · <Name>` (only `E<n>` when the season is missing; just `Name` when both are missing). Track: title `Name`, subtitle `Artists` joined with `, ` (fallback `AlbumArtist`, else null). Others: title `Name`, subtitle `ProductionYear` when > 0. Artwork (first that exists): own `ImageTags.Primary` → `SeriesId` + `SeriesPrimaryImageTag` → `AlbumId` + `AlbumPrimaryImageTag` → null; URI `/sources/jellyfin/images/<id>/Primary?tag=<tag>`. Progress: null when `UserData.Played`; else `UserData.PlayedPercentage / 100` when > 0; else `PlaybackPositionTicks / RunTimeTicks` when both > 0; else null (capped at 1). Playables: exactly `[JellyfinItem(ServerId, Id, max(0, UserData.PlaybackPositionTicks))]`.

- [ ] **Step 1: Write the fixtures**

`src/test/resources/fixtures/jellyfin/resume.json`:

```json
{
  "Items": [
    {
      "Name": "The Long Night",
      "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
      "Id": "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b",
      "RunTimeTicks": 14400000000,
      "IsFolder": false,
      "IndexNumber": 5,
      "ParentIndexNumber": 2,
      "Type": "Episode",
      "SeriesName": "Northern Lights",
      "SeriesId": "7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29",
      "SeasonId": "6b5a4f3e2d1c0b9a8f7e6d5c4b3a2918",
      "SeriesPrimaryImageTag": "b8a7c6d5e4f3",
      "SeasonName": "Season 2",
      "MediaType": "Video",
      "LocationType": "FileSystem",
      "UserData": {
        "PlaybackPositionTicks": 6120000000,
        "PlayCount": 0,
        "IsFavorite": false,
        "LastPlayedDate": "2026-09-15T20:41:12.0000000Z",
        "Played": false,
        "PlayedPercentage": 42.5,
        "Key": "7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29002005"
      },
      "ImageTags": { "Primary": "1a2b3c4d5e6f" },
      "BackdropImageTags": [],
      "ParentBackdropItemId": "7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29",
      "ParentBackdropImageTags": ["9f8e7d6c5b4a"]
    },
    {
      "Name": "Big Buck Bunny",
      "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
      "Id": "b1c2d3e4f5061728394a5b6c7d8e9f01",
      "RunTimeTicks": 5964800000,
      "ProductionYear": 2008,
      "IsFolder": false,
      "Type": "Movie",
      "MediaType": "Video",
      "LocationType": "FileSystem",
      "UserData": {
        "PlaybackPositionTicks": 1491200000,
        "PlayCount": 0,
        "IsFavorite": true,
        "Played": false,
        "Key": "b1c2d3e4f5061728394a5b6c7d8e9f01"
      },
      "ImageTags": { "Primary": "c0ffeec0ffee", "Logo": "10901090aa01" },
      "BackdropImageTags": ["d00dd00dd00d"]
    }
  ],
  "TotalRecordCount": 2,
  "StartIndex": 0
}
```

`next-up.json`:

```json
{
  "Items": [
    {
      "Name": "Aurora",
      "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
      "Id": "5a4b3c2d1e0f49382716a5b4c3d2e1f0",
      "RunTimeTicks": 15000000000,
      "IsFolder": false,
      "IndexNumber": 6,
      "ParentIndexNumber": 2,
      "Type": "Episode",
      "SeriesName": "Northern Lights",
      "SeriesId": "7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29",
      "SeriesPrimaryImageTag": "b8a7c6d5e4f3",
      "MediaType": "Video",
      "UserData": { "PlaybackPositionTicks": 0, "PlayCount": 0, "IsFavorite": false, "Played": false, "Key": "7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29002006" },
      "ImageTags": {},
      "BackdropImageTags": []
    }
  ],
  "TotalRecordCount": 1,
  "StartIndex": 0
}
```

`latest.json`:

```json
[
  {
    "Name": "Sintel",
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "Id": "d4c3b2a1f0e94837261504a3b2c1d0e9",
    "RunTimeTicks": 8880000000,
    "ProductionYear": 2010,
    "IsFolder": false,
    "Type": "Movie",
    "MediaType": "Video",
    "UserData": { "PlaybackPositionTicks": 0, "PlayCount": 0, "IsFavorite": false, "Played": false, "Key": "d4c3b2a1f0e94837261504a3b2c1d0e9" },
    "ImageTags": { "Primary": "5117e15117e1" },
    "BackdropImageTags": []
  },
  {
    "Name": "Pilot",
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "Id": "e5d4c3b2a1f04938271605b4c3d2e1f0",
    "RunTimeTicks": 26000000000,
    "IsFolder": false,
    "IndexNumber": 1,
    "ParentIndexNumber": 1,
    "Type": "Episode",
    "SeriesName": "Harbour Town",
    "SeriesId": "f6e5d4c3b2a14938271605b4c3d2e1f0",
    "SeriesPrimaryImageTag": "4a4b4c4d4e4f",
    "MediaType": "Video",
    "UserData": { "PlaybackPositionTicks": 0, "PlayCount": 1, "IsFavorite": false, "Played": true, "Key": "f6e5d4c3b2a14938271605b4c3d2e1f0001001" },
    "ImageTags": { "Primary": "0e0e0e0e0e0e" },
    "BackdropImageTags": []
  },
  {
    "Name": "Harbour Town",
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "Id": "f6e5d4c3b2a14938271605b4c3d2e1f0",
    "IsFolder": true,
    "Type": "Series",
    "ChildCount": 1,
    "UserData": { "UnplayedItemCount": 0, "PlaybackPositionTicks": 0, "PlayCount": 0, "IsFavorite": false, "Played": false, "Key": "f6e5d4c3b2a14938271605b4c3d2e1f0" },
    "ImageTags": { "Primary": "4a4b4c4d4e4f" }
  }
]
```

(The folder entry proves the mapper skips anything not directly playable, even though the query excludes series.)

`item-episode.json`: the first object of `resume.json` on its own, plus `"Overview": "The lights fail on the longest night of the year."` and `"Path": "/media/tv/Northern Lights/Season 02/Northern Lights S02E05.mp4"`.

`item-movie.json`: the second object of `resume.json` on its own, plus `"Overview": "A giant rabbit takes revenge."`.

- [ ] **Step 2: Write the failing tests**

`src/test/java/dev/andre/homecontrol/core/playback/ContentItemTest.java` — cases: `theSevenArgumentConstructorHasNoProgress`; `progressIsAFractionBetweenZeroAndOne` (`-0.1`, `1.1`, `NaN` → `IllegalArgumentException`; `0.0` and `1.0` accepted); `playablesAreCopied`.

`src/test/java/dev/andre/homecontrol/core/content/ContentSourcesTest.java` — two stub sources (anonymous classes, `jellyfin` searchable and available, `pinned` not searchable); cases: `findsBySourceId`, `searchableSkipsSourcesThatCannotSearchOrAreUnavailable` (a third searchable source with `available()` false is excluded), `duplicateSourceIdsAreAProgrammingError` (`IllegalStateException`).

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinItemMapperTest.java` (parse fixtures with a `JsonMapper`):

```java
    @Test
    void mapsAResumableEpisode() {
        ContentItem item = JellyfinItemMapper.toItems(fixture("resume.json").path("Items")).getFirst();

        assertThat(item.id()).isEqualTo("3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b");
        assertThat(item.sourceId()).isEqualTo("jellyfin");
        assertThat(item.kind()).isEqualTo(ContentKind.EPISODE);
        assertThat(item.title()).isEqualTo("Northern Lights");
        assertThat(item.subtitle()).isEqualTo("S2:E5 · The Long Night");
        assertThat(item.artwork()).isEqualTo(URI.create("/sources/jellyfin/images/3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b/Primary?tag=1a2b3c4d5e6f"));
        assertThat(item.progress()).isEqualTo(0.425);
        assertThat(item.playables()).containsExactly(new PlayableRef.JellyfinItem(
                "4e1a2b3c4d5e4f60718293a4b5c6d7e8", "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b", 6_120_000_000L));
    }

    @Test
    void mapsAMovieWithItsYearAndComputesProgressFromTicks() {
        ContentItem item = JellyfinItemMapper.toItems(fixture("resume.json").path("Items")).get(1);

        assertThat(item.kind()).isEqualTo(ContentKind.MOVIE);
        assertThat(item.title()).isEqualTo("Big Buck Bunny");
        assertThat(item.subtitle()).isEqualTo("2008");
        assertThat(item.progress()).isEqualTo(0.25);
    }

    @Test
    void fallsBackToTheSeriesPosterAndHasNoProgressWhenUnstarted() {
        ContentItem item = JellyfinItemMapper.toItems(fixture("next-up.json").path("Items")).getFirst();

        assertThat(item.artwork()).isEqualTo(URI.create("/sources/jellyfin/images/7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a29/Primary?tag=b8a7c6d5e4f3"));
        assertThat(item.progress()).isNull();
        assertThat(item.playables()).singleElement().extracting(ref -> ((PlayableRef.JellyfinItem) ref).resumeTicks()).isEqualTo(0L);
    }

    @Test
    void skipsFoldersAndPlayedItemsHaveNoProgress() {
        List<ContentItem> latest = JellyfinItemMapper.toItems(fixture("latest.json"));

        assertThat(latest).extracting(ContentItem::title).containsExactly("Sintel", "Harbour Town");
        assertThat(latest.get(1).subtitle()).isEqualTo("S1:E1 · Pilot");
        assertThat(latest.get(1).progress()).isNull();
    }
```

plus `aTrackShowsItsArtistsAndAlbumArt` (inline JSON `{"Id":"c0ffee00c0ffee00c0ffee00c0ffee01","ServerId":"s","Name":"Bunny Song","Type":"Audio","MediaType":"Audio","Artists":["The Rabbits","Hare"],"AlbumId":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","AlbumPrimaryImageTag":"77aa","ImageTags":{}}` → kind `TRACK`, subtitle `The Rabbits, Hare`, artwork album URI) and `anItemWithoutIdIsSkipped`.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSourceTest.java` — `FakeJellyfinServer`, real `JellyfinClient`, mocked `JellyfinSetupService` returning `Optional.of(new JellyfinConnection(fake.url(), "tok", "hc-test-device", USER_ID))` (or empty), `Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), UTC)`, `JellyfinProperties(true, 2, 5, 20)`. Cases:
- `offersNoRailsUntilConnected`: connection empty → `available()` false, `rails()` empty, and no request made.
- `offersThreeRailsInOrder`: ids `resume`, `next-up`, `latest`; titles `Continue watching`, `Next up`, `Latest in library`.
- `continueWatchingUsesTheResumeQuery`: `rail("resume")` → 2 items, `fetchedAt` = the fixed instant; recorded query equals exactly `{userId=a1b2…, limit=20, mediaTypes=Video, enableUserData=true, enableImageTypes=Primary,Thumb,Backdrop, imageTypeLimit=1}` and the request carries `Token="tok"`.
- `nextUpLeavesResumableEpisodesToContinueWatching`: query has `enableResumable=false`.
- `latestAsksForDirectlyPlayableItems`: query has `includeItemTypes=Movie,Episode` and `groupItems=false`; 2 items.
- `anUnknownRailIsRejected`: `IllegalArgumentException`.
- `readsOneItem`: `GET /Items/b1c2…` → `item-movie.json` → present with query `userId`; unknown id (404) → empty; `item("../x")` → empty without a request.
- `upstreamFailuresAreContentSourceExceptions`: 500 on resume → `ContentSourceException` whose message contains `answered HTTP 500`.

`JellyfinClientTest` — add `fetchesImagesAnonymously`: `respondBytes("GET", "/Items/b1c2…/Images/Primary", 200, "image/jpeg", {1,2,3})` → `image(url, id, "Primary", "c0ffeec0ffee", 480)` has content type `image/jpeg` and 3 bytes; the recorded request has no `authorization` header and query `{maxWidth=480, quality=90, tag=c0ffeec0ffee}`; 404 → empty; `text/html` 200 → `BAD_RESPONSE`; a 10 MiB + 1 byte body → `BAD_RESPONSE`.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinImageControllerTest.java` — `@WebMvcTest(JellyfinImageController.class)`, `@MockitoBean JellyfinClient client`, `@MockitoBean JellyfinSetupService setup` (settings present with server `http://nas:8096`). Cases:
- `servesATaggedImageAsImmutable`: 200, `Content-Type: image/jpeg`, `Cache-Control: private, max-age=31536000, immutable`; `verify(client).image(URI.create("http://nas:8096"), "b1c2…", "Primary", "c0ffeec0ffee", 480)`.
- `anUntaggedImageIsCachedBriefly`: `Cache-Control: private, max-age=3600`.
- `widthIsClamped`: `width=99999` → `1920`; `width=0` → `480`.
- `rejectsIdsTypesAndTagsThatAreNotJellyfinShaped`: `/sources/jellyfin/images/..%2Fx/Primary` → 400 (or 404 from path matching — assert `is4xxClientError`), type `Chapter` → 400, tag `a b` → 400.
- `missingConfigurationOrImageIs404AndUpstreamFailureIs502`.

`src/test/java/dev/andre/homecontrol/web/ContentControllerTest.java` — `@WebMvcTest(ContentController.class)`, `@MockitoBean ContentSources sources`. Cases:
- `listsSourcesWithTheirRails`: JSON `[{"id":"jellyfin","name":"Jellyfin","available":true,"searchable":false,"rails":[{"id":"resume","title":"Continue watching"}]}]`.
- `servesARailAsViewsWithoutPlayableReferences`: a rail with one item carrying `JellyfinItem("srv","item-1",99)`, artwork `/sources/jellyfin/images/item-1/Primary?tag=t`, progress `0.5` → `$.items[0].id` `item-1`, `$.items[0].kind` `EPISODE`, `$.items[0].artwork`, `$.items[0].progress` `0.5`, `$.fetchedAt` present; the body contains neither `playables` nor `resumeTicks` nor `srv`.
- `unknownSourceOrRailIs404`: unknown source → 404 text `No content source jellyfinx`; `IllegalArgumentException` from `rail` → 404 with its message.
- `anUpstreamFailureIs502WithTheReason`: `ContentSourceException("Could not reach Jellyfin at http://nas:8096 (connection refused). …")` → 502, `text/plain`, that message.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.web.ContentControllerTest'`
Expected: compilation failure — the content contract, mapper, source, image endpoint and views do not exist.

- [ ] **Step 4: Implement the core contract and `ContentItem.progress`**

`core/playback/ContentItem.java`:

```java
package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.List;

/**
 * What a content source produces and a rail shows. {@code subtitle}, {@code artwork} and
 * {@code progress} (fraction watched, 0–1) may be null. {@code artwork} may be a relative URI
 * served by this application.
 */
public record ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                          URI artwork, List<PlayableRef> playables, Double progress) {

    public ContentItem {
        playables = playables == null ? List.of() : List.copyOf(playables);
        if (progress != null && (progress.isNaN() || progress < 0.0 || progress > 1.0)) {
            throw new IllegalArgumentException("progress must be between 0 and 1");
        }
    }

    public ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                       URI artwork, List<PlayableRef> playables) {
        this(id, sourceId, kind, title, subtitle, artwork, playables, null);
    }
}
```

`core/content/ContentSource.java`:

```java
package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;

import java.util.List;
import java.util.Optional;

/**
 * A content source (spec §5.2, §7). Only sources speak content APIs. Items carry every playable
 * reference the source can build without secrets; token-bearing references are made at play time.
 */
public interface ContentSource {

    /** Stable key, e.g. {@code jellyfin}; also the {@code source} request parameter. */
    String id();

    String displayName();

    /** Configured and usable right now (no I/O). */
    default boolean available() {
        return true;
    }

    /** The rails this source offers now, in its default order. No I/O; empty while unavailable. */
    List<RailDescriptor> rails();

    /** Loads one rail. I/O. Unknown id → IllegalArgumentException; upstream failure → ContentSourceException. */
    Rail rail(String railId);

    /** Re-reads one item by the id it had in a rail or search result. I/O. Empty when it no longer exists. */
    Optional<ContentItem> item(String itemId);

    default boolean searchable() {
        return false;
    }

    /** I/O. Only called when {@link #searchable()} and {@link #available()}. */
    default List<ContentItem> search(String query, int limit) {
        throw new UnsupportedOperationException(displayName() + " cannot search");
    }
}
```

`core/content/RailDescriptor.java`: `public record RailDescriptor(String sourceId, String id, String title) {}`.

`core/content/Rail.java`:

```java
package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;

import java.time.Instant;
import java.util.List;

/** One loaded rail. {@code fetchedAt} lets the rail cache (D1) decide staleness. */
public record Rail(RailDescriptor descriptor, List<ContentItem> items, Instant fetchedAt) {
    public Rail {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
```

`core/content/ContentSourceException.java`: `public class ContentSourceException extends RuntimeException` with constructors `(String message)` and `(String message, Throwable cause)`; Javadoc "The message is user-facing and names the source; it never contains a credential."

`core/content/ContentSources.java`:

```java
package dev.andre.homecontrol.core.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every enabled content source, in bean order. */
public class ContentSources {

    private final Map<String, ContentSource> byId = new LinkedHashMap<>();

    public ContentSources(List<ContentSource> sources) {
        for (ContentSource source : sources) {
            if (byId.putIfAbsent(source.id(), source) != null) {
                throw new IllegalStateException("Two content sources share the id " + source.id());
            }
        }
    }

    public List<ContentSource> all() {
        return List.copyOf(byId.values());
    }

    public Optional<ContentSource> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<ContentSource> searchable() {
        return byId.values().stream().filter(s -> s.searchable() && s.available()).toList();
    }
}
```

In `HomeControlConfiguration`:

```java
    @Bean
    public ContentSources contentSources(ObjectProvider<ContentSource> sources) {
        return new ContentSources(sources.orderedStream().toList());
    }
```

Change `JellyfinException` to `extends ContentSourceException` (its constructor calls `super(message)`).

- [ ] **Step 5: Implement the mapper, the source and the image endpoint**

`sources/jellyfin/JellyfinItemMapper.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Jellyfin BaseItemDto → ContentItem. Only token-free references are attached. */
public final class JellyfinItemMapper {

    public static final String IMAGE_PATH = "/sources/jellyfin/images/";

    private JellyfinItemMapper() {
    }

    public static List<ContentItem> toItems(JsonNode array) {
        List<ContentItem> items = new ArrayList<>();
        for (JsonNode node : array) {
            toItem(node).ifPresent(items::add);
        }
        return items;
    }

    public static Optional<ContentItem> toItem(JsonNode item) {
        String id = item.path("Id").asString("");
        if (id.isBlank() || item.path("IsFolder").asBoolean(false)) {
            return Optional.empty();
        }
        ContentKind kind = switch (item.path("Type").asString("")) {
            case "Movie" -> ContentKind.MOVIE;
            case "Episode" -> ContentKind.EPISODE;
            case "Audio" -> ContentKind.TRACK;
            default -> ContentKind.VIDEO;
        };
        String name = item.path("Name").asString("");
        String title;
        String subtitle;
        switch (kind) {
            case EPISODE -> {
                title = item.path("SeriesName").asString(name);
                subtitle = episodeLabel(item, name);
            }
            case TRACK -> {
                title = name;
                subtitle = artists(item);
            }
            default -> {
                title = name;
                int year = item.path("ProductionYear").asInt(0);
                subtitle = year > 0 ? String.valueOf(year) : null;
            }
        }
        JsonNode userData = item.path("UserData");
        long position = Math.max(0, userData.path("PlaybackPositionTicks").asLong(0));
        return Optional.of(new ContentItem(id, JellyfinSettings.SOURCE_ID, kind, title, subtitle, artwork(item),
                List.of(new PlayableRef.JellyfinItem(item.path("ServerId").asString(""), id, position)),
                progress(item, userData)));
    }

    static String episodeLabel(JsonNode item, String name) {
        int season = item.path("ParentIndexNumber").asInt(-1);
        int episode = item.path("IndexNumber").asInt(-1);
        String number = episode < 0 ? null : season < 0 ? "E" + episode : "S" + season + ":E" + episode;
        if (number == null) {
            return name.isBlank() ? null : name;
        }
        return name.isBlank() ? number : number + " · " + name;
    }

    static String artists(JsonNode item) {
        List<String> names = new ArrayList<>();
        for (JsonNode artist : item.path("Artists")) {
            String value = artist.asString("");
            if (!value.isBlank()) {
                names.add(value);
            }
        }
        if (!names.isEmpty()) {
            return String.join(", ", names);
        }
        String albumArtist = item.path("AlbumArtist").asString("");
        return albumArtist.isBlank() ? null : albumArtist;
    }

    static Double progress(JsonNode item, JsonNode userData) {
        if (userData.path("Played").asBoolean(false)) {
            return null;
        }
        double percentage = userData.path("PlayedPercentage").asDouble(0.0);
        if (percentage > 0) {
            return Math.min(1.0, percentage / 100.0);
        }
        long position = userData.path("PlaybackPositionTicks").asLong(0);
        long runtime = item.path("RunTimeTicks").asLong(0);
        return position > 0 && runtime > 0 ? Math.min(1.0, (double) position / runtime) : null;
    }

    static URI artwork(JsonNode item) {
        String own = item.path("ImageTags").path("Primary").asString("");
        if (!own.isBlank()) {
            return image(item.path("Id").asString(""), own);
        }
        String seriesId = item.path("SeriesId").asString("");
        String seriesTag = item.path("SeriesPrimaryImageTag").asString("");
        if (!seriesId.isBlank() && !seriesTag.isBlank()) {
            return image(seriesId, seriesTag);
        }
        String albumId = item.path("AlbumId").asString("");
        String albumTag = item.path("AlbumPrimaryImageTag").asString("");
        if (!albumId.isBlank() && !albumTag.isBlank()) {
            return image(albumId, albumTag);
        }
        return null;
    }

    private static URI image(String itemId, String tag) {
        return URI.create(IMAGE_PATH + itemId + "/Primary?tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8));
    }
}
```

`sources/jellyfin/JellyfinContentSource.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JellyfinContentSource implements ContentSource {

    static final RailDescriptor RESUME = new RailDescriptor(JellyfinSettings.SOURCE_ID, "resume", "Continue watching");
    static final RailDescriptor NEXT_UP = new RailDescriptor(JellyfinSettings.SOURCE_ID, "next-up", "Next up");
    static final RailDescriptor LATEST = new RailDescriptor(JellyfinSettings.SOURCE_ID, "latest", "Latest in library");
    private static final String IMAGE_TYPES = "Primary,Thumb,Backdrop";

    private final JellyfinClient client;
    private final JellyfinSetupService setup;
    private final JellyfinProperties properties;
    private final Clock clock;

    public JellyfinContentSource(JellyfinClient client, JellyfinSetupService setup, JellyfinProperties properties, Clock clock) {
        this.client = client;
        this.setup = setup;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String id() {
        return JellyfinSettings.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "Jellyfin";
    }

    @Override
    public boolean available() {
        return setup.connection().isPresent();
    }

    @Override
    public List<RailDescriptor> rails() {
        return available() ? List.of(RESUME, NEXT_UP, LATEST) : List.of();
    }

    @Override
    public Rail rail(String railId) {
        JellyfinConnection connection = connection();
        return switch (railId) {
            case "resume" -> new Rail(RESUME, JellyfinItemMapper.toItems(client.get(connection, "/UserItems/Resume",
                    listQuery(connection, "mediaTypes", "Video")).path("Items")), clock.instant());
            case "next-up" -> new Rail(NEXT_UP, JellyfinItemMapper.toItems(client.get(connection, "/Shows/NextUp",
                    listQuery(connection, "enableResumable", "false")).path("Items")), clock.instant());
            case "latest" -> new Rail(LATEST, JellyfinItemMapper.toItems(client.get(connection, "/Items/Latest",
                    listQuery(connection, "includeItemTypes", "Movie,Episode", "groupItems", "false"))), clock.instant());
            default -> throw new IllegalArgumentException("Jellyfin has no rail '" + railId + "'");
        };
    }

    @Override
    public Optional<ContentItem> item(String itemId) {
        JellyfinConnection connection = connection();
        String id;
        try {
            id = JellyfinClient.id(itemId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try {
            return JellyfinItemMapper.toItem(client.get(connection, "/Items/" + id, Map.of("userId", connection.userId())));
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    JellyfinConnection connection() {
        return setup.connection().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
    }

    Map<String, String> listQuery(JellyfinConnection connection, String... extra) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", connection.userId());
        query.put("limit", String.valueOf(properties.railSize()));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            query.put(extra[i], extra[i + 1]);
        }
        query.put("enableUserData", "true");
        query.put("enableImageTypes", IMAGE_TYPES);
        query.put("imageTypeLimit", "1");
        return query;
    }
}
```

Add to `JellyfinConfiguration`: `@Bean JellyfinContentSource jellyfinContentSource(JellyfinClient, JellyfinSetupService, JellyfinProperties)` with `Clock.systemUTC()`.

In `JellyfinClient` extract the query joining from `request(...)` into `private static String queryString(Map<String, String> query)` (returns `""` or `?a=b&c=d`), then add:

```java
    public record Image(String contentType, byte[] bytes) {
    }

    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Jellyfin's item image endpoint is anonymous; no credential is sent, so none can leak. */
    public Optional<Image> image(URI serverUrl, String itemId, String type, String tag, int maxWidth) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("maxWidth", String.valueOf(maxWidth));
        query.put("quality", "90");
        if (tag != null) {
            query.put("tag", tag);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl + "/Items/" + id(itemId) + "/Images/" + type + queryString(query)))
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Accept", "image/*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(serverUrl, "interrupted");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !contentType.startsWith("image/")) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent no image");
            }
            byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new JellyfinException(JellyfinException.Kind.BAD_RESPONSE, "Jellyfin at " + serverUrl + " sent an oversized image");
            }
            return Optional.of(new Image(contentType, bytes));
        } catch (IOException e) {
            throw unreachable(serverUrl, e.getClass().getSimpleName());
        }
    }
```

`sources/jellyfin/JellyfinImageController.java` — `@Controller` + the module's `@ConditionalOnProperty`; `@GetMapping("/sources/jellyfin/images/{itemId}/{type}")` returning `ResponseEntity<byte[]>`:
- `itemId` must pass `JellyfinClient.id`, `type` ∈ `{Primary, Thumb, Backdrop, Logo}`, `tag` null or `[A-Za-z0-9]{1,64}` → else 400 (empty body).
- `width` null or < 1 → 480; > 1920 → 1920.
- `setup.settings()` empty → 404; `client.image(...)` empty → 404; `JellyfinException` → 502 (empty body).
- 200 with the upstream content type, `X-Content-Type-Options: nosniff`, and `Cache-Control: private, max-age=31536000, immutable` when `tag` is set, else `private, max-age=3600`.

- [ ] **Step 6: Implement the JSON views and controller**

`web/ContentItemView.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.playback.ContentItem;

/** The only shape in which content reaches the browser: no playable references, so no secrets. */
public record ContentItemView(String id, String sourceId, String kind, String title, String subtitle,
                              String artwork, Double progress) {

    public static ContentItemView of(ContentItem item) {
        return new ContentItemView(item.id(), item.sourceId(), item.kind().name(), item.title(), item.subtitle(),
                item.artwork() == null ? null : item.artwork().toString(), item.progress());
    }
}
```

`web/ContentController.java` — `@RestController` over `ContentSources`:
- `GET /sources` → `List<SourceView>` with `record SourceView(String id, String name, boolean available, boolean searchable, List<RailView> rails)` and `record RailView(String id, String title)`.
- `GET /sources/{sourceId}/rails/{railId}` → `record RailContentView(String sourceId, String id, String title, Instant fetchedAt, List<ContentItemView> items)`; unknown source → 404 `text/plain` `No content source <id>`.
- `@ExceptionHandler(IllegalArgumentException.class)` → 404 text with the message; `@ExceptionHandler(ContentSourceException.class)` → 502 text with the message.
- Class Javadoc: "Interim: fetches upstream on every call. Sub-project D1 puts a cache in front."

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.web.ContentControllerTest'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core src/main/java/dev/andre/homecontrol/sources \
  src/main/java/dev/andre/homecontrol/web/ContentItemView.java src/main/java/dev/andre/homecontrol/web/ContentController.java \
  src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java \
  src/test/java/dev/andre/homecontrol/core src/test/java/dev/andre/homecontrol/sources \
  src/test/java/dev/andre/homecontrol/web/ContentControllerTest.java src/test/resources/fixtures/jellyfin
git commit -m "feat: content source contract and Jellyfin rails"
```

---

### Task 4: C4 · Session remote control

**Files:**
- Create: `sources/jellyfin/JellyfinSession.java`, `sources/jellyfin/JellyfinSessions.java`
- Modify: `sources/jellyfin/JellyfinSetupService.java`, `JellyfinSetupController.java`, `JellyfinSetupAdvice.java`, `JellyfinConfiguration.java`, `src/main/resources/templates/fragments/jellyfin-setup.html`
- Test: `sources/jellyfin/JellyfinSessionsTest.java`, `JellyfinSetupServiceTest.java`, `JellyfinSetupControllerTest.java`; fixture `src/test/resources/fixtures/jellyfin/sessions.json`

**Interfaces:**
- Consumes: `Device` (A: `id()`, `name()`, `host()`), `DeviceManager.devices()` (A), `JellyfinClient.get/post/id`, `JellyfinSetupService.settings/connection/save` (Task 2).
- Produces:
  - `record JellyfinSession(String id, String deviceId, String deviceName, String client, String remoteAddress, Instant lastActivity, boolean supportsMediaControl)`.
  - `class JellyfinSessions { JellyfinSessions(JellyfinClient, JellyfinSetupService); JellyfinSessions(JellyfinClient, JellyfinSetupService, Function<String, Set<String>> addressesOfHost); List<JellyfinSession> controllable(); Optional<JellyfinSession> sessionFor(Device); void playNow(String sessionId, String itemId, long startPositionTicks); static Optional<JellyfinSession> match(String deviceName, Set<String> deviceAddresses, List<JellyfinSession> sessions, String linkedJellyfinDeviceId); static String normalizeAddress(String); static Set<String> resolve(String host); }`
  - `JellyfinSetupService.link(String jellyfinDeviceId, String deviceId)` (blank device id unlinks that session).
  - `POST /setup/sources/jellyfin/links` (`session`, `device`) → 302 `/setup`.
  - `JellyfinSetupAdvice.View` gains `List<SessionOption> sessions`, `String sessionsError`, `List<DeviceOption> devices` with `record SessionOption(String jellyfinDeviceId, String label, String linkedDeviceId)` and `record DeviceOption(String id, String name)`.

**Session wire format (normative).**
- `GET /Sessions?controllableByUserId=<userId>` → array of `SessionInfoDto`; keep entries with `SupportsMediaControl: true` whose `DeviceId` differs from our own `deviceId`. `LastActivityDate` is ISO-8601 with up to seven fraction digits (`Instant.parse` accepts it); unparsable → `Instant.EPOCH`.
- `POST /Sessions/<sessionId>/Playing?playCommand=PlayNow&itemIds=<itemId>[&startPositionTicks=<ticks>]` with no body → 204. `startPositionTicks` is omitted when 0. 404 → `JellyfinException(NOT_FOUND, "The Jellyfin app on that device has closed its session")`.
- Matching (first rule that applies wins): (1) a link for the device id exists → the session with that `DeviceId` (most recent activity), or none if absent — a link is never overridden by guessing; (2) sessions whose normalized `RemoteEndPoint` is among the device host's normalized addresses → if one, it; if several, the one whose `DeviceName` equals the device name ignoring case, else the most recent `LastActivityDate`; (3) exactly one session whose `DeviceName` equals the device name ignoring case; (4) none.
- Address normalization: trim, lower-case; `[v6]:port` → `v6`; `a.b.c.d:port` (exactly one colon) → `a.b.c.d`; `::ffff:a.b.c.d` → `a.b.c.d`; drop an IPv6 zone `%…`. A device host resolves to itself plus every `InetAddress.getAllByName(host)` address (normalized); an unknown host resolves to itself only.

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/jellyfin/sessions.json`:

```json
[
  {
    "PlayState": { "CanSeek": false, "IsPaused": false, "IsMuted": false, "RepeatMode": "RepeatNone", "PlaybackOrder": "Default" },
    "AdditionalUsers": [],
    "Capabilities": { "PlayableMediaTypes": ["Audio", "Video"], "SupportedCommands": ["DisplayContent", "Play", "SetVolume"], "SupportsMediaControl": true, "SupportsPersistentIdentifier": true },
    "RemoteEndPoint": "192.168.1.50",
    "PlayableMediaTypes": ["Audio", "Video"],
    "Id": "1d2c3b4a59687f6e5d4c3b2a19081726",
    "UserId": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "UserName": "andre",
    "Client": "Android TV",
    "LastActivityDate": "2026-09-16T08:30:11.1234567Z",
    "LastPlaybackCheckIn": "0001-01-01T00:00:00.0000000Z",
    "DeviceName": "SHIELD Android TV",
    "DeviceId": "b2c4d6e8f0a1c3e5",
    "ApplicationVersion": "0.19.4",
    "IsActive": true,
    "SupportsMediaControl": true,
    "SupportsRemoteControl": true,
    "NowPlayingQueue": [],
    "HasCustomDeviceName": false,
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
    "SupportedCommands": ["DisplayContent", "Play", "SetVolume"]
  },
  {
    "PlayState": { "CanSeek": false, "IsPaused": false, "IsMuted": false, "RepeatMode": "RepeatNone", "PlaybackOrder": "Default" },
    "RemoteEndPoint": "192.168.1.30",
    "PlayableMediaTypes": ["Audio", "Video"],
    "Id": "2e3d4c5b6a7980f1e2d3c4b5a6978801",
    "UserId": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "UserName": "andre",
    "Client": "Jellyfin Web",
    "LastActivityDate": "2026-09-16T08:25:00.0000000Z",
    "DeviceName": "Firefox",
    "DeviceId": "TW96aWxsYS81LjAgKFgxMTsgTGludXg",
    "ApplicationVersion": "10.11.2",
    "IsActive": true,
    "SupportsMediaControl": true,
    "SupportsRemoteControl": true,
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8"
  },
  {
    "RemoteEndPoint": "192.168.1.10",
    "PlayableMediaTypes": [],
    "Id": "3f4e5d6c7b8a9f0e1d2c3b4a59687f6e",
    "UserId": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "UserName": "andre",
    "Client": "Home Control",
    "LastActivityDate": "2026-09-16T08:31:00.0000000Z",
    "DeviceName": "Home Control",
    "DeviceId": "hc-test-device",
    "ApplicationVersion": "0.8.0",
    "IsActive": true,
    "SupportsMediaControl": false,
    "SupportsRemoteControl": false,
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8"
  },
  {
    "RemoteEndPoint": "192.168.1.50",
    "PlayableMediaTypes": ["Audio", "Video"],
    "Id": "4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d",
    "UserId": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "UserName": "andre",
    "Client": "Jellyfin Media Player",
    "LastActivityDate": "2026-09-14T19:02:44.0000000Z",
    "DeviceName": "Living Room HTPC",
    "DeviceId": "jmp-001",
    "ApplicationVersion": "1.12.0",
    "IsActive": false,
    "SupportsMediaControl": false,
    "SupportsRemoteControl": true,
    "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8"
  }
]
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSessionsTest.java` — `FakeJellyfinServer` with `GET /Sessions` → `sessions.json`; mocked `JellyfinSetupService` with connection `(fake.url(), "tok", "hc-test-device", USER_ID)` and settings whose `sessionLinks` the test sets; address function `host -> JellyfinSessions.resolve(host)` (IP literals do not hit DNS). Helpers `device(name, host)` = `new Device("dev-" + name, name, DeviceKind.ANDROID_TV, host, Map.of(), Instant.EPOCH)` and `session(id, deviceId, name, address, instant)`. Cases:
- `listsControllableSessionsExceptOurOwn`: `controllable()` → ids `1d2c…`, `2e3d…` in fixture order; request query `{controllableByUserId=a1b2…}` with `Token="tok"`; first session has `client` `Android TV`, `deviceName` `SHIELD Android TV`, `remoteAddress` `192.168.1.50`, `lastActivity` `2026-09-16T08:30:11.1234567Z`.
- `matchesTheDeviceByAddress`: `sessionFor(device("Living Room", "192.168.1.50"))` → `1d2c…` (the non-controllable HTPC on the same IP is ignored).
- `anExplicitLinkWinsAndIsAuthoritative`: link `dev-Living Room` → `TW96aWxsYS81LjAgKFgxMTsgTGludXg` → Firefox session; link to `gone-device` → empty although the address matches.
- `fallsBackToAUniqueDeviceName`: `device("firefox", "10.9.9.9")` → Firefox; pure `match("Twin", Set.of("10.0.0.1"), [two sessions named "Twin" elsewhere], null)` → empty.
- `amongSeveralAddressMatchesPrefersTheNameThenTheMostRecent`: pure `match` with three sessions at `172.17.0.1`: names `A` (08:00), `B` (09:00), `Shield` (07:00) → for device name `Shield` → `Shield`; for device name `Other` → `B`.
- `normalizesAddresses`: `::ffff:192.168.1.50` → `192.168.1.50`; `192.168.1.50:51234` → `192.168.1.50`; `[FE80::1%eth0]:8096` → `fe80::1`; `FE80::1` → `fe80::1`; ` 10.0.0.2 ` → `10.0.0.2`.
- `playNowSendsTheDocumentedCommand`: fake `POST /Sessions/1d2c…/Playing` → 204; `playNow("1d2c…", "3f2a…", 6_120_000_000L)` → query exactly `{playCommand=PlayNow, itemIds=3f2a…, startPositionTicks=6120000000}`, empty body; `playNow(…, 0)` → query without `startPositionTicks`.
- `aClosedSessionIsNamed`: 404 → `JellyfinException` kind `NOT_FOUND`, message `The Jellyfin app on that device has closed its session`.
- `sessionIdsAndItemIdsAreValidated`: `playNow("../x", "3f2a…", 0)` → `IllegalArgumentException`, no request.

`JellyfinSetupServiceTest` — add `linkingASessionReplacesItsPreviousDeviceAndBlankUnlinks`: link `jf-1` to `shield`, then `jf-1` to `bedroom` → links `{bedroom=jf-1}`; link `jf-1` to `""` → links empty.

`JellyfinSetupControllerTest` — add `linksASessionToADevice`: POST `/setup/sources/jellyfin/links` `session=jf-1&device=shield` → 302 `/setup`, `verify(setup).link("jf-1", "shield")`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*'`
Expected: compilation failure — `JellyfinSession`, `JellyfinSessions`, `link` do not exist.

- [ ] **Step 4: Implement sessions**

`sources/jellyfin/JellyfinSession.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import java.time.Instant;

/** A Jellyfin client session (an app instance) as the server reports it. */
public record JellyfinSession(String id, String deviceId, String deviceName, String client, String remoteAddress,
                              Instant lastActivity, boolean supportsMediaControl) {
}
```

`sources/jellyfin/JellyfinSessions.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Device;
import tools.jackson.databind.JsonNode;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Finds the Jellyfin app open on a device and tells it to play (spec §4.2 route a, §5.3 rung 1). */
public class JellyfinSessions {

    private final JellyfinClient client;
    private final JellyfinSetupService setup;
    private final Function<String, Set<String>> addressesOfHost;

    public JellyfinSessions(JellyfinClient client, JellyfinSetupService setup) {
        this(client, setup, JellyfinSessions::resolve);
    }

    public JellyfinSessions(JellyfinClient client, JellyfinSetupService setup, Function<String, Set<String>> addressesOfHost) {
        this.client = client;
        this.setup = setup;
        this.addressesOfHost = addressesOfHost;
    }

    public List<JellyfinSession> controllable() {
        JellyfinConnection connection = connection();
        JsonNode sessions = client.get(connection, "/Sessions", Map.of("controllableByUserId", connection.userId()));
        List<JellyfinSession> result = new ArrayList<>();
        for (JsonNode node : sessions) {
            JellyfinSession session = new JellyfinSession(node.path("Id").asString(""), node.path("DeviceId").asString(""),
                    node.path("DeviceName").asString(""), node.path("Client").asString(""),
                    normalizeAddress(node.path("RemoteEndPoint").asString("")), instant(node.path("LastActivityDate").asString("")),
                    node.path("SupportsMediaControl").asBoolean(false));
            if (session.supportsMediaControl() && !session.id().isBlank() && !session.deviceId().equals(connection.deviceId())) {
                result.add(session);
            }
        }
        return result;
    }

    public Optional<JellyfinSession> sessionFor(Device device) {
        JellyfinSettings settings = setup.settings().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
        return match(device.name(), addressesOfHost.apply(device.host()), controllable(), settings.sessionLinks().get(device.id()));
    }

    public void playNow(String sessionId, String itemId, long startPositionTicks) {
        String session = JellyfinClient.id(sessionId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("playCommand", "PlayNow");
        query.put("itemIds", JellyfinClient.id(itemId));
        if (startPositionTicks > 0) {
            query.put("startPositionTicks", String.valueOf(startPositionTicks));
        }
        try {
            client.post(connection(), "/Sessions/" + session + "/Playing", query, null);
        } catch (JellyfinException e) {
            if (e.kind() == JellyfinException.Kind.NOT_FOUND) {
                throw new JellyfinException(JellyfinException.Kind.NOT_FOUND, "The Jellyfin app on that device has closed its session");
            }
            throw e;
        }
    }

    public static Optional<JellyfinSession> match(String deviceName, Set<String> deviceAddresses,
                                                  List<JellyfinSession> sessions, String linkedJellyfinDeviceId) {
        Comparator<JellyfinSession> recent = Comparator.comparing(JellyfinSession::lastActivity);
        if (linkedJellyfinDeviceId != null && !linkedJellyfinDeviceId.isBlank()) {
            return sessions.stream().filter(s -> s.deviceId().equals(linkedJellyfinDeviceId)).max(recent);
        }
        List<JellyfinSession> byAddress = sessions.stream()
                .filter(s -> !s.remoteAddress().isBlank() && deviceAddresses.contains(s.remoteAddress()))
                .toList();
        if (byAddress.size() == 1) {
            return Optional.of(byAddress.getFirst());
        }
        if (byAddress.size() > 1) {
            return byAddress.stream().filter(s -> s.deviceName().equalsIgnoreCase(deviceName)).max(recent)
                    .or(() -> byAddress.stream().max(recent));
        }
        List<JellyfinSession> byName = sessions.stream().filter(s -> s.deviceName().equalsIgnoreCase(deviceName)).toList();
        return byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
    }

    public static String normalizeAddress(String raw) {
        String address = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            address = end < 0 ? address.substring(1) : address.substring(1, end);
        } else if (address.chars().filter(c -> c == ':').count() == 1) {
            address = address.substring(0, address.indexOf(':'));
        }
        if (address.startsWith("::ffff:") && address.substring(7).contains(".")) {
            address = address.substring(7);
        }
        int zone = address.indexOf('%');
        return zone < 0 ? address : address.substring(0, zone);
    }

    public static Set<String> resolve(String host) {
        Set<String> addresses = new LinkedHashSet<>();
        addresses.add(normalizeAddress(host));
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                addresses.add(normalizeAddress(address.getHostAddress()));
            }
        } catch (UnknownHostException | SecurityException ignored) {
            // the literal host is all we can compare
        }
        return addresses;
    }

    private JellyfinConnection connection() {
        return setup.connection().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
```

Add to `JellyfinSetupService`:

```java
    /** Pins a Jellyfin app (by its DeviceId) to one device; a blank device id unlinks it. */
    public void link(String jellyfinDeviceId, String deviceId) {
        JellyfinSettings settings = settings().orElseThrow(() ->
                new JellyfinException(JellyfinException.Kind.INVALID_INPUT, "Jellyfin is not connected"));
        for (Map.Entry<String, String> entry : settings.sessionLinks().entrySet()) {
            if (entry.getValue().equals(jellyfinDeviceId)) {
                settings = settings.withSessionLink(entry.getKey(), null);
            }
        }
        if (deviceId != null && !deviceId.isBlank()) {
            settings = settings.withSessionLink(deviceId, jellyfinDeviceId);
        }
        save(settings);
    }
```

Add `@Bean JellyfinSessions jellyfinSessions(JellyfinClient, JellyfinSetupService)` to `JellyfinConfiguration`; `POST /setup/sources/jellyfin/links` to `JellyfinSetupController` (flash `jellyfinMessage` `Link saved` or `jellyfinError`).

In `JellyfinSetupAdvice` inject `ObjectProvider<JellyfinSessions>` and `ObjectProvider<DeviceManager>`; when configured, fill `sessions` from `controllable()` with label `DeviceName · Client · remoteAddress` and `linkedDeviceId` from the reverse of `settings.sessionLinks()`, `sessionsError` from a caught `JellyfinException` message, and `devices` from `DeviceManager.devices()` as `(id, name)`; when not configured all three are empty/null. In the fragment, under the connected block, add a `Jellyfin apps` list: one form per session posting `session` (hidden) and a `device` select (first option value `""` `Not linked`, then every device, `selected` when equal to `linkedDeviceId`) with button `Link`, the hint `Home Control finds the Jellyfin app on a device by its network address. Link it here when Jellyfin sees a different address (for example behind Docker networking).`, and `sessionsError` as an error line.

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/jellyfin src/main/resources/templates/fragments/jellyfin-setup.html \
  src/test/java/dev/andre/homecontrol/sources/jellyfin src/test/resources/fixtures/jellyfin/sessions.json
git commit -m "feat: find and command the Jellyfin app open on a device"
```

---

### Task 5: C5 · Jellyfin Cast receiver route

**Files:**
- Create: `core/playback/CastMessageStrategy.java`, `sources/jellyfin/JellyfinCastMessages.java`
- Modify: `core/Action.java`, `core/playback/PlayableRef.java`, `core/playback/Route.java`, `core/playback/PlaybackPlanner.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java`, `adapters/cast/protocol/CastPayloads.java`, `adapters/cast/CastSession.java`, `adapters/androidtv/AndroidTvSession.java` (and every other exhaustive `switch` over `Action` — find them with `grep -rn "case Action.CastLoad" src/main`)
- Test: `core/ActionTest.java`, `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`, `adapters/cast/protocol/FakeCastReceiver.java`, `adapters/cast/CastSessionTest.java`, `sources/jellyfin/JellyfinCastMessagesTest.java`

**Interfaces:**
- Consumes (B): `Action.CastLoad`, `Route.Cast`, `CastLoadStrategy`, `CastStreamStrategy`, `CastSession` (`requireConnected`, `launch`, `call`, `loadTimeout`, `receiver` field), `CastConnection.connect/send/expect`, `CastConnection.Waiter.await/cancel`, `CastTimeoutException`, `CastIncoming.type()`, `ReceiverStatus.app(appId)`, `ReceiverApp.speaks(namespace)`, `CastPayloads.getStatus()`, `CastNamespaces.RECEIVER/PLATFORM_RECEIVER_ID`, `FakeCastReceiver` (`send`, `receiverStatus`, `virtualConnections`, `app`), `ActionFailedException`. From this plan: `JellyfinSettings` (Task 2), `item-episode.json` (Task 3).
- Produces:
  - `Action.CastMessage(String receiverAppId, String namespace, Map<String,Object> message)` → `CAST_RECEIVER` (redacted `toString`).
  - `PlayableRef.CastMessage(String receiverAppId, String namespace, Map<String,Object> message, String receiverLabel)` (`kindLabel` `cast`, redacted `toString`).
  - `Route.CastMessage(String receiverAppId, String namespace, Map<String,Object> message, String receiverLabel)` with `Action action()` and `describe()` = `"Cast with " + receiverLabel` (redacted `toString`).
  - `CastMessageStrategy` (CAST_RECEIVER + first `PlayableRef.CastMessage`).
  - Planner bean order: `AppLinkStrategy`, `CastMessageStrategy`, `CastLoadStrategy`, `CastStreamStrategy`.
  - `CastPayloads.custom(Map<String,Object>) → ObjectNode`.
  - `CastSession` executes `CastMessage`: launch the app if it is not running → wait until its `RECEIVER_STATUS` lists `namespace` → CONNECT to its transport → send the body unchanged → fail with `ActionFailedException` if a message on that namespace from that transport with `type` `error`, `connectionerror` or `playbackerror` arrives within 750 ms.
  - `FakeCastReceiver.appSpeaks(String appId, String namespace)`, `FakeCastReceiver.answerCustom(String namespace, ObjectNode reply)`.
  - `final class JellyfinCastMessages { static final String NAMESPACE = "urn:x-cast:com.connectsdk"; static final String RECEIVER_LABEL = "the Jellyfin receiver"; static Map<String,Object> playNow(JellyfinSettings, String accessToken, JsonNode item, long startPositionTicks, String receiverName); static PlayableRef.CastMessage playable(JellyfinSettings, String accessToken, JsonNode item, long startPositionTicks, String receiverName); }`

**Jellyfin receiver message (normative)** — as jellyfin-web's `chromecastPlayer` sends it; namespace `urn:x-cast:com.connectsdk`, receiver app id = the user's `CastReceiverId` (default `F007D354`), sent after LAUNCH and CONNECT to the app transport, with no `type`/`requestId`/`sessionId` added:

```json
{
  "options": {
    "items": [ { "Id": "<itemId>", "ServerId": "<serverId>", "Name": "<Name>", "Type": "<Type>", "MediaType": "<MediaType>", "IsFolder": false } ],
    "startPositionTicks": 6120000000
  },
  "command": "PlayNow",
  "userId": "<userId>",
  "deviceId": "<our DeviceId>",
  "accessToken": "<token>",
  "serverAddress": "<deviceServerUrl>",
  "serverId": "<serverId>",
  "serverVersion": "<serverVersion>",
  "receiverName": "<device name>"
}
```

`startPositionTicks` is omitted when 0. `serverAddress` is the device-facing URL, not the container-facing one.

- [ ] **Step 1: Write the failing core tests**

Add to `core/ActionTest.java`:

```java
    @Test
    void aCastMessageRequiresACastReceiverCopiesItsBodyAndNeverPrintsIt() {
        Map<String, Object> body = new HashMap<>(Map.of("accessToken", "secret-token"));
        Action.CastMessage message = new Action.CastMessage("F007D354", "urn:x-cast:com.connectsdk", body);
        body.put("accessToken", "changed");

        assertThat(message.requires()).isEqualTo(Capability.CAST_RECEIVER);
        assertThat(message.message()).containsEntry("accessToken", "secret-token");
        assertThat(message.toString()).doesNotContain("secret-token").contains("F007D354");
        assertThatThrownBy(() -> new Action.CastMessage("F007D354", "com.connectsdk", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

Add to `core/playback/PlaybackPlannerTest.java` (and change the planner field to `List.of(new AppLinkStrategy(), new CastMessageStrategy(), new CastLoadStrategy(), new CastStreamStrategy())`):

```java
    private static final PlayableRef.CastMessage JELLYFIN_MESSAGE = new PlayableRef.CastMessage(
            "F007D354", "urn:x-cast:com.connectsdk", Map.of("command", "PlayNow", "accessToken", "tok-1"), "the Jellyfin receiver");

    @Test
    void castsACustomMessageBeforeACastLoadOrAStream() {
        Route route = planner.plan(item(STREAM, JELLYFIN_LOAD, JELLYFIN_MESSAGE), EnumSet.of(Capability.CAST_RECEIVER));

        assertThat(route).isEqualTo(new Route.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                JELLYFIN_MESSAGE.message(), "the Jellyfin receiver"));
        assertThat(route.describe()).isEqualTo("Cast with the Jellyfin receiver");
        assertThat(((Route.CastMessage) route).action()).isEqualTo(new Action.CastMessage("F007D354",
                "urn:x-cast:com.connectsdk", JELLYFIN_MESSAGE.message()));
        assertThat(route.toString()).doesNotContain("tok-1");
        assertThat(JELLYFIN_MESSAGE.toString()).doesNotContain("tok-1");
    }

    @Test
    void aCustomMessageNeedsACastReceiver() {
        assertThat(planner.plan(item(JELLYFIN_MESSAGE), EnumSet.of(Capability.APP_LINK)))
                .isInstanceOfSatisfying(Route.Unroutable.class, u -> assertThat(u.reason()).contains("this device is not a Cast receiver"));
    }
```

(and extend `theApplicationsPlannerUsesTheSpecOrder` with `configured.plan(item(JELLYFIN_LOAD, JELLYFIN_MESSAGE), EnumSet.of(CAST_RECEIVER))` being a `Route.CastMessage`.)

Add to `playback/PlaybackServiceTest.java` a case `executesACastMessageRoute`: capabilities `CAST_RECEIVER`, item with only `JELLYFIN_MESSAGE` → returned route is `Route.CastMessage`, `verify(devices).execute("kitchen", route.action())`.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinCastMessagesTest.java`:

```java
class JellyfinCastMessagesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JellyfinSettings settings = new JellyfinSettings(URI.create("http://jellyfin:8096"),
            URI.create("http://192.168.1.20:8096"), "4e1a2b3c4d5e4f60718293a4b5c6d7e8", "nas", "10.11.2",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90", "andre", JellyfinSettings.AuthMode.PASSWORD,
            "0123456789abcdef0123456789abcdef", "F007D354", Map.of());

    @Test
    void buildsTheMessageJellyfinWebSends() {
        JsonNode item = mapper.readTree(FakeJellyfinServer.fixture("item-episode.json"));

        Map<String, Object> message = JellyfinCastMessages.playNow(settings, "6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80", item, 6_120_000_000L, "Living Room TV");

        assertThat(mapper.readTree(mapper.writeValueAsString(message))).isEqualTo(mapper.readTree("""
                {"options":{"items":[{"Id":"3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b","ServerId":"4e1a2b3c4d5e4f60718293a4b5c6d7e8",
                  "Name":"The Long Night","Type":"Episode","MediaType":"Video","IsFolder":false}],
                  "startPositionTicks":6120000000},
                 "command":"PlayNow","userId":"a1b2c3d4e5f60718293a4b5c6d7e8f90","deviceId":"0123456789abcdef0123456789abcdef",
                 "accessToken":"6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80","serverAddress":"http://192.168.1.20:8096",
                 "serverId":"4e1a2b3c4d5e4f60718293a4b5c6d7e8","serverVersion":"10.11.2","receiverName":"Living Room TV"}
                """));
    }

    @Test
    void startsFromTheBeginningWithoutAStartPosition() {
        JsonNode item = mapper.readTree(FakeJellyfinServer.fixture("item-movie.json"));

        assertThat(((Map<?, ?>) JellyfinCastMessages.playNow(settings, "t", item, 0, "Kitchen").get("options")))
                .doesNotContainKey("startPositionTicks");
    }

    @Test
    void thePlayableUsesTheUsersReceiverApp() {
        JsonNode item = mapper.readTree(FakeJellyfinServer.fixture("item-movie.json"));
        JellyfinSettings unstable = new JellyfinSettings(settings.serverUrl(), settings.deviceServerUrl(), settings.serverId(),
                settings.serverName(), settings.serverVersion(), settings.userId(), settings.userName(), settings.authMode(),
                settings.deviceId(), "6F511C87", Map.of());

        PlayableRef.CastMessage playable = JellyfinCastMessages.playable(unstable, "t", item, 0, "Kitchen");

        assertThat(playable.receiverAppId()).isEqualTo("6F511C87");
        assertThat(playable.namespace()).isEqualTo("urn:x-cast:com.connectsdk");
        assertThat(playable.receiverLabel()).isEqualTo("the Jellyfin receiver");
    }
}
```

- [ ] **Step 2: Extend the fake receiver and write the failing Cast session tests**

In `src/test/java/dev/andre/homecontrol/adapters/cast/protocol/FakeCastReceiver.java` add fields and scripting methods:

```java
    private final Map<String, Set<String>> appNamespaces = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> customReplies = new ConcurrentHashMap<>();

    /** Apps with this id also list {@code namespace} in RECEIVER_STATUS (custom receivers such as Jellyfin's). */
    public void appSpeaks(String appId, String namespace) {
        appNamespaces.computeIfAbsent(appId, id -> ConcurrentHashMap.newKeySet()).add(namespace);
    }

    /** Answer every message on {@code namespace} sent to the running app's transport with {@code reply}. */
    public void answerCustom(String namespace, ObjectNode reply) {
        customReplies.put(namespace, reply);
    }
```

In `receiverStatus(int)`, after the MEDIA namespace entry:

```java
        appNamespaces.getOrDefault(current.appId(), Set.of())
                .forEach(namespace -> namespaces.addObject().put("name", namespace));
```

In `handle(CastMessage)`, replace the empty `default -> { }` branch of the namespace switch:

```java
            default -> {
                App current = app;
                ObjectNode reply = customReplies.get(incoming.namespace());
                if (reply != null && current.transportId().equals(incoming.destinationId())
                        && virtualConnections.contains(incoming.destinationId())) {
                    send(incoming.namespace(), current.transportId(), incoming.sourceId(), reply.deepCopy());
                }
            }
```

Add to `adapters/cast/CastSessionTest.java` (reuse its existing helpers for a connected session against a `FakeCastReceiver` with short timeouts; `NS = "urn:x-cast:com.connectsdk"`):
- `aCustomMessageLaunchesTheReceiverAndIsSentUnchanged`: `receiver.appSpeaks("F007D354", NS)`; `session.execute(new Action.CastMessage("F007D354", NS, Map.of("command", "PlayNow", "options", Map.of("items", List.of(Map.of("Id", "abc"))))))` returns; `receiver.last(RECEIVER, "LAUNCH")` has `appId` `F007D354`; `receiver.virtualConnections()` contains the app's transport; `receiver.received(NS, "")` has one message whose payload has `command` `PlayNow`, `options.items[0].Id` `abc`, and no `requestId`, `sessionId` or `type`.
- `aRunningReceiverIsReused`: `receiver.appSpeaks("F007D354", NS)`, `receiver.runApp("F007D354", "Jellyfin")`, `receiver.pushReceiverStatus()`, await `state().currentApp()` = `Jellyfin`; execute → no `LAUNCH` recorded.
- `aSynchronousReceiverErrorFailsTheAction`: `appSpeaks`, `answerCustom(NS, {"type":"error","message":"Missing one or more required params - command,options,userId,accessToken,serverAddress"})` → `ActionFailedException` with message containing `refused to play it (Missing one or more required params`.
- `aReceiverThatNeverSpeaksTheNamespaceTimesOut`: no `appSpeaks` → `ActionFailedException` containing `did not answer in time`.
- `anOfflineReceiverRejectsTheMessage`: session not connected → `DeviceOfflineException`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.adapters.cast.*' --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinCastMessagesTest'`
Expected: compilation failure — `Action.CastMessage`, `PlayableRef.CastMessage`, `Route.CastMessage`, `CastMessageStrategy`, `JellyfinCastMessages` do not exist.

- [ ] **Step 4: Implement the core types, strategy and service case**

Add to `core/Action.java`:

```java
    /**
     * Start receiver app {@code receiverAppId} if needed and send {@code message} on its custom
     * {@code namespace} (for receivers that do not take a media LOAD, such as Jellyfin's).
     */
    record CastMessage(String receiverAppId, String namespace, Map<String, Object> message) implements Action {
        public CastMessage {
            Objects.requireNonNull(receiverAppId, "receiverAppId");
            if (namespace == null || !namespace.startsWith("urn:x-cast:")) {
                throw new IllegalArgumentException("A Cast namespace starts with urn:x-cast:");
            }
            message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
        }

        @Override
        public Capability requires() {
            return Capability.CAST_RECEIVER;
        }

        /** The body can carry credentials; never print it. */
        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }
```

Add to `core/playback/PlayableRef.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`):

```java
    /** A custom-namespace message for a Cast receiver app. Built at play time only: it may carry a token. */
    record CastMessage(String receiverAppId, String namespace, Map<String, Object> message, String receiverLabel)
            implements PlayableRef {
        public CastMessage {
            message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
        }

        @Override
        public String kindLabel() {
            return "cast";
        }

        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }
```

Add to `core/playback/Route.java`:

```java
    /** Run a Cast receiver app and send it a custom message (spec §5.3 rung 3). */
    record CastMessage(String receiverAppId, String namespace, Map<String, Object> message, String receiverLabel)
            implements Route {
        public CastMessage {
            message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
        }

        public Action action() {
            return new Action.CastMessage(receiverAppId, namespace, message);
        }

        @Override
        public String describe() {
            return "Cast with " + receiverLabel;
        }

        @Override
        public String toString() {
            return "CastMessage[receiverAppId=" + receiverAppId + ", namespace=" + namespace + "]";
        }
    }
```

`core/playback/CastMessageStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 3 of spec §5.3, first variant: a receiver app driven by custom messages (Jellyfin), before LOADs and bare streams. */
public class CastMessageStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.CastMessage.class::isInstance)
                .map(PlayableRef.CastMessage.class::cast)
                .findFirst()
                .map(m -> new Route.CastMessage(m.receiverAppId(), m.namespace(), m.message(), m.receiverLabel()));
    }
}
```

`PlaybackPlanner.explain`: add

```java
                case PlayableRef.CastMessage ignored -> reasons.add(capabilities.contains(Capability.CAST_RECEIVER)
                        ? "the cast was not accepted" : "this device is not a Cast receiver");
```

`PlaybackService.play`: add `case Route.CastMessage message -> devices.execute(deviceId, message.action());`.

`HomeControlConfiguration.playbackPlanner`: `List.of(new AppLinkStrategy(), new CastMessageStrategy(), new CastLoadStrategy(), new CastStreamStrategy())` with the comment updated to name the custom-message variant.

In `AndroidTvSession.execute` (and every other exhaustive switch over `Action` found by the grep) add `case Action.CastMessage ignored -> throw new UnsupportedActionException("<adapter> cannot run Cast receiver apps");` using that class's existing wording pattern.

- [ ] **Step 5: Implement custom messages in the Cast adapter**

Add to `adapters/cast/protocol/CastPayloads.java`:

```java
    /** A custom-namespace body, sent as given — no type, requestId or sessionId is added. */
    public static ObjectNode custom(Map<String, Object> message) {
        return (ObjectNode) MAPPER.valueToTree(message);
    }
```

In `adapters/cast/CastSession.java` add `case Action.CastMessage message -> customMessage(message.receiverAppId(), message.namespace(), message.message());` to `execute`, and:

```java
    private static final Set<String> CUSTOM_ERROR_TYPES = Set.of("error", "connectionerror", "playbackerror");
    /** Receivers validate a custom request synchronously; media loading continues after we return. */
    private static final Duration CUSTOM_MESSAGE_ERROR_WINDOW = Duration.ofMillis(750);

    /** Launch the app unless it runs, wait until it speaks {@code namespace}, connect, send; a quick error reply fails. */
    private void customMessage(String appId, String namespace, Map<String, Object> message) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp running = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        ReceiverStatus.ReceiverApp app = running.speaks(namespace) ? running : awaitNamespace(current, appId, namespace);
        call(() -> {
            current.connect(app.transportId());
            return null;
        }, "reach " + app.displayName());
        CastConnection.Waiter rejection = current.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && CUSTOM_ERROR_TYPES.contains(incoming.type()));
        try {
            call(() -> {
                current.send(namespace, app.transportId(), CastPayloads.custom(message));
                return null;
            }, "send the request to " + app.displayName());
            CastIncoming error;
            try {
                error = rejection.await(CUSTOM_MESSAGE_ERROR_WINDOW);
            } catch (CastTimeoutException quiet) {
                return; // no rejection: the receiver took the request
            } catch (IOException e) {
                throw new DeviceOfflineException(device.name() + " dropped the connection while starting playback");
            }
            String reason = error.payload().path("message").asString("");
            throw new ActionFailedException(device.name() + " refused to play it (" + (reason.isBlank() ? error.type() : reason) + ")");
        } finally {
            rejection.cancel();
        }
    }

    /** A freshly launched custom receiver announces its namespaces in a later RECEIVER_STATUS. */
    private ReceiverStatus.ReceiverApp awaitNamespace(CastConnection current, String appId, String namespace) {
        CastConnection.Waiter ready = current.expect(incoming -> RECEIVER.equals(incoming.namespace())
                && "RECEIVER_STATUS".equals(incoming.type())
                && ReceiverStatus.parse(incoming.payload().path("status")).app(appId)
                        .filter(candidate -> candidate.speaks(namespace)).isPresent());
        CastIncoming status = call(() -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus());
                return ready.await(loadTimeout());
            } finally {
                ready.cancel();
            }
        }, "start receiver app " + appId);
        return ReceiverStatus.parse(status.payload().path("status")).app(appId).orElseThrow();
    }
```

(Imports as needed: `java.util.Set`, `CastTimeoutException`, `java.io.IOException`. The error text on timeout comes from B's `call`: `<device> did not answer in time when asked to start receiver app F007D354`.)

- [ ] **Step 6: Implement the Jellyfin message builder**

`sources/jellyfin/JellyfinCastMessages.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PlayNow request the Jellyfin Cast receiver (jellyfin-chromecast) accepts on its custom
 * namespace — the same shape jellyfin-web's chromecastPlayer plugin sends.
 */
public final class JellyfinCastMessages {

    public static final String NAMESPACE = "urn:x-cast:com.connectsdk";
    public static final String RECEIVER_LABEL = "the Jellyfin receiver";

    private JellyfinCastMessages() {
    }

    public static Map<String, Object> playNow(JellyfinSettings settings, String accessToken, JsonNode item,
                                              long startPositionTicks, String receiverName) {
        Map<String, Object> stub = new LinkedHashMap<>();
        stub.put("Id", item.path("Id").asString(""));
        stub.put("ServerId", item.path("ServerId").asString(settings.serverId()));
        stub.put("Name", item.path("Name").asString(""));
        stub.put("Type", item.path("Type").asString(""));
        stub.put("MediaType", item.path("MediaType").asString(""));
        stub.put("IsFolder", item.path("IsFolder").asBoolean(false));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("items", List.of(Collections.unmodifiableMap(stub)));
        if (startPositionTicks > 0) {
            options.put("startPositionTicks", startPositionTicks);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("options", Collections.unmodifiableMap(options));
        message.put("command", "PlayNow");
        message.put("userId", settings.userId());
        message.put("deviceId", settings.deviceId());
        message.put("accessToken", accessToken);
        message.put("serverAddress", settings.deviceServerUrl().toString());
        message.put("serverId", settings.serverId());
        message.put("serverVersion", settings.serverVersion());
        message.put("receiverName", receiverName);
        return Collections.unmodifiableMap(message);
    }

    public static PlayableRef.CastMessage playable(JellyfinSettings settings, String accessToken, JsonNode item,
                                                   long startPositionTicks, String receiverName) {
        return new PlayableRef.CastMessage(settings.castReceiverId(), NAMESPACE,
                playNow(settings, accessToken, item, startPositionTicks, receiverName), RECEIVER_LABEL);
    }
}
```

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.sources.jellyfin.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core src/main/java/dev/andre/homecontrol/playback \
  src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java src/main/java/dev/andre/homecontrol/adapters \
  src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinCastMessages.java \
  src/test/java/dev/andre/homecontrol/core src/test/java/dev/andre/homecontrol/playback \
  src/test/java/dev/andre/homecontrol/adapters src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinCastMessagesTest.java
git commit -m "feat: cast Jellyfin items through the Jellyfin receiver"
```

---

### Task 6: C6 · Direct-stream URL builder

**Files:**
- Create: `sources/jellyfin/JellyfinStreams.java`
- Modify: `core/playback/PlayableRef.java` (`StreamUrl.toString`), `sources/jellyfin/JellyfinConfiguration.java`
- Test: `sources/jellyfin/JellyfinStreamsTest.java`; fixtures `src/test/resources/fixtures/jellyfin/playback-info-direct.json`, `playback-info-transcode-only.json`, `playback-info-audio.json`

**Interfaces:**
- Consumes: `PlayableRef.StreamUrl(URI url, String mimeType)` (A), `JellyfinClient.post/id`, `JellyfinConnection` (Task 2), `item-episode.json` (Task 3).
- Produces:
  - `class JellyfinStreams { JellyfinStreams(JellyfinClient); Optional<PlayableRef.StreamUrl> directStream(JellyfinConnection, URI deviceServerUrl, JsonNode item); static ObjectNode playbackInfoRequest(String userId); static Optional<PlayableRef.StreamUrl> fromPlaybackInfo(URI deviceServerUrl, String token, JsonNode item, JsonNode playbackInfo); }`
  - `PlayableRef.StreamUrl.toString()` prints the URL without its query.

**PlaybackInfo request (normative).** `POST /Items/<itemId>/PlaybackInfo`, `Content-Type: application/json`, token header, body:

```json
{
  "UserId": "<userId>",
  "MaxStreamingBitrate": 120000000,
  "StartTimeTicks": 0,
  "EnableDirectPlay": true,
  "EnableDirectStream": false,
  "EnableTranscoding": false,
  "AllowVideoStreamCopy": false,
  "AllowAudioStreamCopy": false,
  "AutoOpenLiveStream": false,
  "DeviceProfile": {
    "Name": "Home Control direct play",
    "MaxStreamingBitrate": 120000000,
    "MaxStaticBitrate": 120000000,
    "MusicStreamingTranscodingBitrate": 192000,
    "DirectPlayProfiles": [
      { "Container": "mp4,m4v", "Type": "Video", "VideoCodec": "h264", "AudioCodec": "aac,mp3" },
      { "Container": "webm", "Type": "Video", "VideoCodec": "vp8,vp9", "AudioCodec": "vorbis,opus" },
      { "Container": "mp3", "Type": "Audio", "AudioCodec": "mp3" },
      { "Container": "m4a,mp4", "Type": "Audio", "AudioCodec": "aac" },
      { "Container": "flac", "Type": "Audio", "AudioCodec": "flac" },
      { "Container": "ogg,webm", "Type": "Audio", "AudioCodec": "vorbis,opus" }
    ],
    "TranscodingProfiles": [],
    "ContainerProfiles": [],
    "CodecProfiles": [],
    "SubtitleProfiles": []
  }
}
```

**Stream URL (normative).** For the first `MediaSources[]` entry with `SupportsDirectPlay: true`, a non-blank `Id`, and a `Container` token (the value may be a comma list such as `mov,mp4,m4a,3gp,3g2,mj2`; tokens trimmed and lower-cased, first token with a known MIME type wins): video items (`MediaType` ≠ `Audio`) → `<deviceServerUrl>/Videos/<itemId>/stream.<container>?static=true&mediaSourceId=<id>&ApiKey=<token>`; audio → `<deviceServerUrl>/Audio/<itemId>/stream.<container>?static=true&mediaSourceId=<id>&ApiKey=<token>`; values URL-encoded. MIME: video `mp4`/`m4v` → `video/mp4`, `webm` → `video/webm`; audio `mp3` → `audio/mpeg`, `m4a`/`mp4` → `audio/mp4`, `flac` → `audio/flac`, `ogg` → `audio/ogg`, `webm` → `audio/webm`. No entry → empty.

- [ ] **Step 1: Write the fixtures**

`playback-info-direct.json`:

```json
{
  "MediaSources": [
    {
      "Protocol": "File",
      "Id": "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b",
      "Path": "/media/tv/Northern Lights/Season 02/Northern Lights S02E05.mp4",
      "Type": "Default",
      "Container": "mov,mp4,m4a,3gp,3g2,mj2",
      "Size": 734003200,
      "Name": "Northern Lights S02E05",
      "IsRemote": false,
      "RunTimeTicks": 14400000000,
      "ReadAtNativeFramerate": false,
      "SupportsTranscoding": false,
      "SupportsDirectStream": true,
      "SupportsDirectPlay": true,
      "IsInfiniteStream": false,
      "RequiresOpening": false,
      "RequiresClosing": false,
      "MediaStreams": [
        { "Codec": "h264", "Type": "Video", "Index": 0, "Width": 1920, "Height": 1080, "BitRate": 3800000, "IsDefault": true },
        { "Codec": "aac", "Type": "Audio", "Index": 1, "Channels": 2, "Language": "eng", "IsDefault": true }
      ],
      "Bitrate": 4077777,
      "DefaultAudioStreamIndex": 1
    }
  ],
  "PlaySessionId": "8b7a6c5d4e3f2a1b0c9d8e7f6a5b4c3d"
}
```

`playback-info-transcode-only.json`:

```json
{
  "MediaSources": [
    {
      "Protocol": "File",
      "Id": "b1c2d3e4f5061728394a5b6c7d8e9f01",
      "Path": "/media/movies/Big Buck Bunny (2008)/Big Buck Bunny.mkv",
      "Container": "mkv",
      "SupportsTranscoding": false,
      "SupportsDirectStream": false,
      "SupportsDirectPlay": false,
      "MediaStreams": [
        { "Codec": "hevc", "Type": "Video", "Index": 0, "Width": 3840, "Height": 2160 },
        { "Codec": "truehd", "Type": "Audio", "Index": 1, "Channels": 8 }
      ],
      "Bitrate": 38000000
    }
  ],
  "PlaySessionId": "1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f"
}
```

`playback-info-audio.json`:

```json
{
  "MediaSources": [
    {
      "Protocol": "File",
      "Id": "c0ffee00c0ffee00c0ffee00c0ffee01",
      "Path": "/media/music/The Rabbits/Meadow/01 Bunny Song.flac",
      "Container": "flac",
      "SupportsTranscoding": false,
      "SupportsDirectStream": true,
      "SupportsDirectPlay": true,
      "MediaStreams": [ { "Codec": "flac", "Type": "Audio", "Index": 0, "Channels": 2, "SampleRate": 44100 } ],
      "Bitrate": 912000
    }
  ],
  "PlaySessionId": "2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f70"
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinStreamsTest.java` — cases:
- `asksJellyfinWhetherTheReceiverCanPlayTheFileAsIs`: `FakeJellyfinServer` `POST /Items/3f2a…/PlaybackInfo` → `playback-info-direct.json`; `new JellyfinStreams(client).directStream(new JellyfinConnection(fake.url(), "tok", "dev", USER_ID), URI.create("http://192.168.1.20:8096"), item-episode)` → present; the recorded body parses to exactly the normative request JSON above with `UserId` = `USER_ID`; `content-type` starts with `application/json`; header has `Token="tok"`.
- `buildsAStaticVideoUrlOnTheDeviceAddress`: pure `fromPlaybackInfo(URI.create("http://192.168.1.20:8096"), "t k", item-episode, direct)` → `StreamUrl(URI.create("http://192.168.1.20:8096/Videos/3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b/stream.mp4?static=true&mediaSourceId=3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b&ApiKey=t+k"), "video/mp4")` (mov is skipped because it has no known type).
- `anAudioItemUsesTheAudioStream`: item `{"Id":"c0ffee00c0ffee00c0ffee00c0ffee01","MediaType":"Audio"}` + `playback-info-audio.json` → `…/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&ApiKey=t`, `audio/flac`.
- `noDirectlyPlayableSourceMeansNoStream`: `playback-info-transcode-only.json` → empty; `{"MediaSources":[]}` → empty; direct play true but `Container` `avi` → empty.
- `theStreamUrlNeverPrintsItsKey`: `new PlayableRef.StreamUrl(URI.create("http://h:8096/Videos/x/stream.mp4?static=true&ApiKey=secret-key"), "video/mp4").toString()` contains `http://h:8096/Videos/x/stream.mp4` and not `secret-key`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinStreamsTest'`
Expected: compilation failure — `JellyfinStreams` does not exist.

- [ ] **Step 4: Implement**

`sources/jellyfin/JellyfinStreams.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A direct stream URL for renderers that fetch media themselves (Cast Default Media Receiver now,
 * DLNA later). Jellyfin decides direct-playability against a Cast-shaped device profile; no transcoding.
 */
public class JellyfinStreams {

    static final long MAX_BITRATE = 120_000_000L;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<String, String> VIDEO_TYPES = Map.of("mp4", "video/mp4", "m4v", "video/mp4", "webm", "video/webm");
    private static final Map<String, String> AUDIO_TYPES = Map.of("mp3", "audio/mpeg", "m4a", "audio/mp4", "mp4", "audio/mp4",
            "flac", "audio/flac", "ogg", "audio/ogg", "webm", "audio/webm");

    private final JellyfinClient client;

    public JellyfinStreams(JellyfinClient client) {
        this.client = client;
    }

    public Optional<PlayableRef.StreamUrl> directStream(JellyfinConnection connection, URI deviceServerUrl, JsonNode item) {
        String itemId = JellyfinClient.id(item.path("Id").asString(""));
        JsonNode info = client.post(connection, "/Items/" + itemId + "/PlaybackInfo", Map.of(), playbackInfoRequest(connection.userId()));
        return fromPlaybackInfo(deviceServerUrl, connection.token(), item, info);
    }

    public static ObjectNode playbackInfoRequest(String userId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("UserId", userId);
        body.put("MaxStreamingBitrate", MAX_BITRATE);
        body.put("StartTimeTicks", 0);
        body.put("EnableDirectPlay", true);
        body.put("EnableDirectStream", false);
        body.put("EnableTranscoding", false);
        body.put("AllowVideoStreamCopy", false);
        body.put("AllowAudioStreamCopy", false);
        body.put("AutoOpenLiveStream", false);
        ObjectNode profile = body.putObject("DeviceProfile");
        profile.put("Name", "Home Control direct play");
        profile.put("MaxStreamingBitrate", MAX_BITRATE);
        profile.put("MaxStaticBitrate", MAX_BITRATE);
        profile.put("MusicStreamingTranscodingBitrate", 192000);
        ArrayNode direct = profile.putArray("DirectPlayProfiles");
        directPlay(direct, "mp4,m4v", "Video", "h264", "aac,mp3");
        directPlay(direct, "webm", "Video", "vp8,vp9", "vorbis,opus");
        directPlay(direct, "mp3", "Audio", null, "mp3");
        directPlay(direct, "m4a,mp4", "Audio", null, "aac");
        directPlay(direct, "flac", "Audio", null, "flac");
        directPlay(direct, "ogg,webm", "Audio", null, "vorbis,opus");
        profile.putArray("TranscodingProfiles");
        profile.putArray("ContainerProfiles");
        profile.putArray("CodecProfiles");
        profile.putArray("SubtitleProfiles");
        return body;
    }

    public static Optional<PlayableRef.StreamUrl> fromPlaybackInfo(URI deviceServerUrl, String token, JsonNode item, JsonNode info) {
        boolean audio = "Audio".equals(item.path("MediaType").asString(""));
        Map<String, String> types = audio ? AUDIO_TYPES : VIDEO_TYPES;
        String itemId = JellyfinClient.id(item.path("Id").asString(""));
        for (JsonNode source : info.path("MediaSources")) {
            String mediaSourceId = source.path("Id").asString("");
            if (!source.path("SupportsDirectPlay").asBoolean(false) || mediaSourceId.isBlank()) {
                continue;
            }
            Optional<String> container = Arrays.stream(source.path("Container").asString("").split(","))
                    .map(part -> part.strip().toLowerCase(Locale.ROOT))
                    .filter(types::containsKey)
                    .findFirst();
            if (container.isEmpty()) {
                continue;
            }
            String url = deviceServerUrl + (audio ? "/Audio/" : "/Videos/") + itemId + "/stream." + container.get()
                    + "?static=true&mediaSourceId=" + encode(mediaSourceId) + "&ApiKey=" + encode(token);
            return Optional.of(new PlayableRef.StreamUrl(URI.create(url), types.get(container.get())));
        }
        return Optional.empty();
    }

    private static void directPlay(ArrayNode profiles, String container, String type, String videoCodec, String audioCodec) {
        ObjectNode profile = profiles.addObject();
        profile.put("Container", container);
        profile.put("Type", type);
        if (videoCodec != null) {
            profile.put("VideoCodec", videoCodec);
        }
        profile.put("AudioCodec", audioCodec);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

In `core/playback/PlayableRef.StreamUrl` add:

```java
        /** Stream URLs may carry an ApiKey; print them without the query. */
        @Override
        public String toString() {
            String where = url.getScheme() == null ? url.getRawPath()
                    : url.getScheme() + "://" + url.getRawAuthority() + url.getRawPath();
            return "StreamUrl[url=" + where + (url.getRawQuery() == null ? "" : "?…") + ", mimeType=" + mimeType + "]";
        }
```

Add `@Bean JellyfinStreams jellyfinStreams(JellyfinClient)` to `JellyfinConfiguration`.

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.core.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/jellyfin src/main/java/dev/andre/homecontrol/core/playback/PlayableRef.java \
  src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinStreamsTest.java \
  src/test/resources/fixtures/jellyfin/playback-info-direct.json src/test/resources/fixtures/jellyfin/playback-info-transcode-only.json \
  src/test/resources/fixtures/jellyfin/playback-info-audio.json
git commit -m "feat: direct stream URLs for Jellyfin items"
```

---

### Task 7: C7 · Planner resolution of `JellyfinItem`

**Files:**
- Create: `core/playback/PlayableResolver.java`, `core/playback/RouteExecutor.java`, `core/playback/JellyfinSessionStrategy.java`, `sources/jellyfin/JellyfinPlayableResolver.java`, `sources/jellyfin/JellyfinRouteExecutor.java`, `web/ContentPlayController.java`
- Modify: `core/playback/PlayableRef.java`, `core/playback/Route.java`, `core/playback/ContentItem.java`, `core/playback/PlaybackPlanner.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java`, `sources/jellyfin/JellyfinConfiguration.java`
- Test: `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`, `sources/jellyfin/JellyfinPlayableResolverTest.java`, `sources/jellyfin/JellyfinRouteExecutorTest.java`, `web/ContentPlayControllerTest.java`

**Interfaces:**
- Consumes: `Device`, `Capability`, `DeviceManager.device/capabilities/execute`, `DeviceOfflineException`, `UnsupportedActionException`, `ActionFailedException` (A/B); `ContentSources.find`, `ContentSource.item`, `ContentSourceException` (Task 3); `JellyfinSessions.sessionFor/playNow` (Task 4); `JellyfinCastMessages.playable` (Task 5); `JellyfinStreams.directStream` (Task 6); `JellyfinSetupService.settings/connection`, `JellyfinClient.get/id` (Task 2).
- Produces:
  - `PlayableRef.JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client)` (`kindLabel` `Jellyfin app`).
  - `Route.JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client)` with `describe()` = `Play in the open Jellyfin app (<client>)`, or `Play in the open Jellyfin app` when the client is blank.
  - `JellyfinSessionStrategy` (JELLYFIN_CLIENT + first `PlayableRef.JellyfinSession`).
  - `interface PlayableResolver { boolean resolves(PlayableRef ref); Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities); record Resolution(List<PlayableRef> playables, Set<Capability> liveCapabilities, List<String> notes) { static Resolution note(String); } }`
  - `interface RouteExecutor { boolean executes(Route route); void execute(Route route, Device device); }`
  - `ContentItem withPlayables(List<PlayableRef>)`.
  - `PlaybackService(DeviceManager, PlaybackPlanner)` (kept), `PlaybackService(DeviceManager, PlaybackPlanner, List<PlayableResolver>, List<RouteExecutor>)`, and the Spring constructor taking `ObjectProvider`s; `Route plan(ContentItem, String deviceId)`; `Route play(ContentItem, String deviceId)`.
  - Planner bean order: `JellyfinSessionStrategy`, `AppLinkStrategy`, `CastMessageStrategy`, `CastLoadStrategy`, `CastStreamStrategy`.
  - `JellyfinPlayableResolver implements PlayableResolver`, `JellyfinRouteExecutor implements RouteExecutor` (beans in the Jellyfin module).
  - `POST /devices/{id}/play` with `source` + `item` → 200 text route description / 400 / 404 / 409 / 422 / 502; `GET /devices/{id}/route?source=&item=` → 200 text description of the planned route, 422 with the reason when unroutable, nothing executed.

**Resolution rules (normative).** `PlaybackService.plan(item, deviceId)`: capabilities = `DeviceManager.capabilities(deviceId)`; for each playable, the first resolver with `resolves(ref)` replaces it with `resolution.playables()`, adds `liveCapabilities` and collects `notes`; unresolvable refs pass through unchanged. If nothing playable remains and notes exist → `Unroutable(notes joined with "; ")`; otherwise the pure planner decides, and an `Unroutable` is prefixed with the notes. `JellyfinPlayableResolver` for `JellyfinItem(serverId, itemId, resumeTicks)`:
1. Not connected → note `Jellyfin is not connected`. A non-blank `serverId` different from the connected server's id → note `this item is from a different Jellyfin server`.
2. `JellyfinSessions.sessionFor(device)` present → return only `JellyfinSession(session.id, itemId, resumeTicks, session.client)` with live capability `JELLYFIN_CLIENT` (rung 1; nothing else is fetched and no token-bearing reference is built). Absent → note `no Jellyfin app is open on <device name>`; `JellyfinException` → note `could not ask Jellyfin which apps are open (<message>)`.
3. Device has neither `CAST_RECEIVER` nor `MEDIA_RENDERER` → return the notes only.
4. `GET /Items/<itemId>?userId=` (failure → note `could not load the item from Jellyfin (<message>)`, return).
5. `CAST_RECEIVER` → add `JellyfinCastMessages.playable(settings, token, item, resumeTicks, device.name())`.
6. `JellyfinStreams.directStream(connection, settings.deviceServerUrl(), item)` → add it, or note `Jellyfin reports no format this device can play directly`; `JellyfinException` → note `could not ask Jellyfin how to stream the item (<message>)`. (Built even when rung 3 wins, so the explanation and D3's retry-with-next-route have it.)

- [ ] **Step 1: Write the failing planner and service tests**

In `core/playback/PlaybackPlannerTest.java` set the planner field to `List.of(new JellyfinSessionStrategy(), new AppLinkStrategy(), new CastMessageStrategy(), new CastLoadStrategy(), new CastStreamStrategy())`, replace B's `explainsThatJellyfinItemsHaveNoRouteYet` with the first test below, and add the rest:

```java
    private static final PlayableRef.JellyfinSession OPEN_APP =
            new PlayableRef.JellyfinSession("1d2c3b4a59687f6e5d4c3b2a19081726", "item-1", 600L, "Android TV");

    @Test
    void anUnresolvedJellyfinItemMeansTheSourceIsSwitchedOff() {
        assertThat(planner.plan(item(new PlayableRef.JellyfinItem("srv", "item-1", 0)), EnumSet.allOf(Capability.class)))
                .isEqualTo(new Route.Unroutable("Jellyfin is switched off on this server"));
    }

    @Test
    void anOpenJellyfinAppComesFirst() {
        Route route = planner.plan(item(LINK, JELLYFIN_MESSAGE, STREAM, OPEN_APP),
                EnumSet.of(Capability.JELLYFIN_CLIENT, Capability.APP_LINK, Capability.CAST_RECEIVER));

        assertThat(route).isEqualTo(new Route.JellyfinSession("1d2c3b4a59687f6e5d4c3b2a19081726", "item-1", 600L, "Android TV"));
        assertThat(route.describe()).isEqualTo("Play in the open Jellyfin app (Android TV)");
        assertThat(new Route.JellyfinSession("s", "i", 0, " ").describe()).isEqualTo("Play in the open Jellyfin app");
    }

    @Test
    void aSessionReferenceWithoutTheLiveCapabilityDoesNotRoute() {
        assertThat(planner.plan(item(OPEN_APP), EnumSet.of(Capability.APP_LINK)))
                .isEqualTo(new Route.Unroutable("the open Jellyfin app cannot be controlled"));
    }

    @Test
    void jellyfinFallsBackFromSessionToReceiverToStream() {
        ContentItem resolved = item(JELLYFIN_MESSAGE, STREAM);

        assertThat(planner.plan(resolved, EnumSet.of(Capability.CAST_RECEIVER))).isInstanceOf(Route.CastMessage.class);
        assertThat(planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER)))
                .isInstanceOfSatisfying(Route.Cast.class, cast -> assertThat(cast.receiverAppId()).isEqualTo("CC1AD845"));
    }
```

(and in `theApplicationsPlannerUsesTheSpecOrder` assert `configured.plan(item(LINK, OPEN_APP), EnumSet.of(JELLYFIN_CLIENT, APP_LINK))` is a `Route.JellyfinSession`.)

In `playback/PlaybackServiceTest.java` add (with a `RouteExecutor executor = mock(RouteExecutor.class)` and a lambda-free stub resolver class in the test):

```java
    private static final PlayableRef.JellyfinItem WANTED = new PlayableRef.JellyfinItem("srv", "item-1", 600L);
    private static final ContentItem JELLYFIN_ITEM = new ContentItem("item-1", "jellyfin", ContentKind.EPISODE,
            "Northern Lights", null, null, List.of(WANTED));

    private static PlayableResolver resolverReturning(PlayableResolver.Resolution resolution) {
        return new PlayableResolver() {
            @Override
            public boolean resolves(PlayableRef ref) {
                return ref instanceof PlayableRef.JellyfinItem;
            }

            @Override
            public Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities) {
                return resolution;
            }
        };
    }

    @Test
    void aResolvedSessionRunsThroughItsExecutorNotTheDevice() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        given(executor.executes(any())).willReturn(true);
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(
                        List.of(new PlayableRef.JellyfinSession("s1", "item-1", 600L, "Android TV")),
                        Set.of(Capability.JELLYFIN_CLIENT), List.of()))),
                List.of(executor));

        Route route = service.play(JELLYFIN_ITEM, "shield");

        assertThat(route).isEqualTo(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"));
        verify(executor).execute(route, shield);
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void resolverNotesExplainWhyNothingRoutes() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(List.of(), Set.of(),
                        List.of("no Jellyfin app is open on Shield")))),
                List.of());

        assertThatThrownBy(() -> service.play(JELLYFIN_ITEM, "shield"))
                .isInstanceOf(UnroutableException.class)
                .hasMessage("Shield: no Jellyfin app is open on Shield");
    }

    @Test
    void notesPrefixThePlannersOwnReasons() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(
                        List.of(new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4?ApiKey=k"), "video/mp4")),
                        Set.of(), List.of("no Jellyfin app is open on Shield")))),
                List.of());

        assertThat(service.plan(JELLYFIN_ITEM, "shield")).isEqualTo(new Route.Unroutable(
                "no Jellyfin app is open on Shield; this device cannot play a direct stream"));
        verify(devices, never()).execute(any(), any());
    }
```

- [ ] **Step 2: Write the failing resolver, executor and controller tests**

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolverTest.java` — `FakeJellyfinServer` with `GET /Sessions` → `sessions.json`, `GET /Items/3f2a…` → `item-episode.json`, `POST /Items/3f2a…/PlaybackInfo` → `playback-info-direct.json`; real `JellyfinClient`, `JellyfinSessions` (address function = `JellyfinSessions::resolve`), `JellyfinStreams`; mocked `JellyfinSetupService` returning settings (server id `4e1a…`, device URL `http://192.168.1.20:8096`, receiver `F007D354`, our device id `hc-test-device`) and connection `(fake.url(), ACCESS_TOKEN, "hc-test-device", USER_ID)`. `WANTED = new PlayableRef.JellyfinItem("4e1a…", "3f2a…", 6_120_000_000L)`. Cases:
- `anOpenAppWinsAndNothingElseIsFetched`: device `Living Room` at `192.168.1.50`, capabilities `APP_LINK, CAST_RECEIVER` → playables exactly `[JellyfinSession("1d2c…", "3f2a…", 6120000000, "Android TV")]`, live `{JELLYFIN_CLIENT}`, notes empty; `fake.requests("GET", "/Items/3f2a…")` and the PlaybackInfo requests are empty.
- `aCastDeviceGetsTheReceiverMessageThenTheStream`: device `Kitchen` at `10.0.0.9`, capabilities `CAST_RECEIVER, VOLUME` → playables `[CastMessage(F007D354, urn:x-cast:com.connectsdk, message with accessToken ACCESS_TOKEN and options.startPositionTicks 6120000000 and receiverName Kitchen, "the Jellyfin receiver"), StreamUrl(http://192.168.1.20:8096/Videos/3f2a…/stream.mp4?static=true&mediaSourceId=3f2a…&ApiKey=6c1f…, video/mp4)]`; live empty; notes `[no Jellyfin app is open on Kitchen]`.
- `aDeviceThatCanOnlyOpenLinksGetsOnlyTheNote`: `APP_LINK` only → no playables, note, no `/Items` request.
- `aServerWithoutADirectFormatAddsANote`: PlaybackInfo → `playback-info-transcode-only.json` → only the `CastMessage` plus note `Jellyfin reports no format this device can play directly`.
- `notConnectedOrAnotherServerIsExplained`: connection empty → note `Jellyfin is not connected`; `JellyfinItem("other-server", …)` → note `this item is from a different Jellyfin server`.
- `jellyfinDownIsANoteNotAnException`: close the fake → notes contain `could not ask Jellyfin which apps are open` and `could not load the item from Jellyfin` (capabilities `CAST_RECEIVER`), no exception.
- `resolvesOnlyJellyfinItems`: `resolves(new PlayableRef.AppLink(...))` false.

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutorTest.java` — mocked `JellyfinSessions`. Cases: `executesOnlySessionRoutes` (`executes(Route.Cast)` false); `tellsTheSessionToPlay` (`verify(sessions).playNow("s1", "item-1", 600L)`); `aClosedSessionOrUnreachableServerIsAFailedAction` (`JellyfinException(NOT_FOUND, "The Jellyfin app on that device has closed its session")` → `ActionFailedException` with message `Jellyfin could not start playback on Shield (The Jellyfin app on that device has closed its session)`).

`src/test/java/dev/andre/homecontrol/web/ContentPlayControllerTest.java` — `@WebMvcTest(ContentPlayController.class)`, `@MockitoBean DeviceManager devices`, `@MockitoBean ContentSources sources`, `@MockitoBean PlaybackService playback`; a mocked `ContentSource jellyfin` whose `item("3f2a…")` returns an item. Cases:
- `playsASourceItemAndDescribesTheRoute`: `POST /devices/shield/play` `source=jellyfin&item=3f2a…` → 200 `text/plain` `Play in the open Jellyfin app (Android TV)`; `verify(playback).play(theItem, "shield")`.
- `previewsTheRouteWithoutPlaying`: `GET /devices/shield/route?source=jellyfin&item=3f2a…` with `playback.plan` → `Route.CastMessage(…, "the Jellyfin receiver")` → 200 `Cast with the Jellyfin receiver`; `verify(playback, never()).play(any(), any())`; an `Unroutable("no Jellyfin app is open on Shield")` → 422 with that text.
- `unknownDeviceSourceOrItemIs404`: each → 404 with `No device with id ghost`, `No content source nope`, `No such item`.
- `failuresMapToStatusCodes`: `DeviceOfflineException` → 409; `UnroutableException` → 422; `ActionFailedException` → 502; `ContentSourceException` (from `item`) → 502; all with the message as body.
- `thePastedLinkFormIsUnaffected`: not in this slice — covered by A's `DeviceControllerTest` (unchanged) and Task 9's end-to-end test.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.web.ContentPlayControllerTest'`
Expected: compilation failure — the session reference and route, the SPIs, the resolver, the executor and the controller do not exist.

- [ ] **Step 4: Implement the core types**

Add to `core/playback/PlayableRef.java`:

```java
    /** An open, controllable Jellyfin app on the device (spec §5.3 rung 1). Created at play time by a resolver. */
    record JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "Jellyfin app";
        }
    }
```

Add to `core/playback/Route.java`:

```java
    /** Tell the Jellyfin app already open on the device to play the item. Executed by a RouteExecutor, not an adapter. */
    record JellyfinSession(String sessionId, String itemId, long startPositionTicks, String client) implements Route {
        @Override
        public String describe() {
            return client == null || client.isBlank()
                    ? "Play in the open Jellyfin app"
                    : "Play in the open Jellyfin app (" + client + ")";
        }
    }
```

`core/playback/JellyfinSessionStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/**
 * Rung 1 of spec §5.3: the Jellyfin app is open on the device. It resumes with the user's own
 * profile, audio and subtitle choices, so it beats relaunching anything.
 */
public class JellyfinSessionStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.JELLYFIN_CLIENT)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.JellyfinSession.class::isInstance)
                .map(PlayableRef.JellyfinSession.class::cast)
                .findFirst()
                .map(s -> new Route.JellyfinSession(s.sessionId(), s.itemId(), s.startPositionTicks(), s.client()));
    }
}
```

`core/playback/PlayableResolver.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;

import java.util.List;
import java.util.Set;

/**
 * Turns a source's abstract reference (e.g. {@link PlayableRef.JellyfinItem}) into concrete ones at
 * play time. Resolvers may do I/O and may build references that carry credentials; those never
 * leave the server. The planner itself stays pure.
 */
public interface PlayableResolver {

    boolean resolves(PlayableRef ref);

    Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities);

    /**
     * {@code liveCapabilities} are true for this request only (an app is open right now);
     * {@code notes} explain missing routes and are shown only when nothing routes.
     */
    record Resolution(List<PlayableRef> playables, Set<Capability> liveCapabilities, List<String> notes) {
        public Resolution {
            playables = List.copyOf(playables);
            liveCapabilities = Set.copyOf(liveCapabilities);
            notes = List.copyOf(notes);
        }

        public static Resolution note(String note) {
            return new Resolution(List.of(), Set.of(), List.of(note));
        }
    }
}
```

`core/playback/RouteExecutor.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Device;

/** Runs routes that go through a content server instead of a device adapter (e.g. Jellyfin session PlayNow). */
public interface RouteExecutor {

    boolean executes(Route route);

    /** Throws {@code ActionFailedException} with a user-facing reason when the command is refused. */
    void execute(Route route, Device device);
}
```

Add to `core/playback/ContentItem.java`:

```java
    public ContentItem withPlayables(List<PlayableRef> replacement) {
        return new ContentItem(id, sourceId, kind, title, subtitle, artwork, replacement, progress);
    }
```

`PlaybackPlanner.explain` — replace the `JellyfinItem` case and add the session case:

```java
                case PlayableRef.JellyfinItem ignored -> reasons.add("Jellyfin is switched off on this server");
                case PlayableRef.JellyfinSession ignored -> reasons.add("the open Jellyfin app cannot be controlled");
```

`HomeControlConfiguration.playbackPlanner`:

```java
    @Bean
    public PlaybackPlanner playbackPlanner() {
        // Preference order of spec §5.3: an open Jellyfin app, an app link, Cast (custom-message
        // receivers, then LOADs, then bare streams on the Default Media Receiver). Sub-project I
        // appends media renderers.
        return new PlaybackPlanner(List.of(new JellyfinSessionStrategy(), new AppLinkStrategy(),
                new CastMessageStrategy(), new CastLoadStrategy(), new CastStreamStrategy()));
    }
```

- [ ] **Step 5: Implement the playback service**

`playback/PlaybackService.java` (adapt imports to the real code):

```java
package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Resolve, plan, execute, report. Commands are ephemeral: a failure here is final. */
@Service
public class PlaybackService {

    private final DeviceManager devices;
    private final PlaybackPlanner planner;
    private final List<PlayableResolver> resolvers;
    private final List<RouteExecutor> executors;

    public PlaybackService(DeviceManager devices, PlaybackPlanner planner) {
        this(devices, planner, List.of(), List.of());
    }

    @Autowired
    public PlaybackService(DeviceManager devices, PlaybackPlanner planner,
                           ObjectProvider<PlayableResolver> resolvers, ObjectProvider<RouteExecutor> executors) {
        this(devices, planner, resolvers.orderedStream().toList(), executors.orderedStream().toList());
    }

    public PlaybackService(DeviceManager devices, PlaybackPlanner planner,
                           List<PlayableResolver> resolvers, List<RouteExecutor> executors) {
        this.devices = devices;
        this.planner = planner;
        this.resolvers = List.copyOf(resolvers);
        this.executors = List.copyOf(executors);
    }

    /** The route the item would take now, without playing it. May do I/O through resolvers. */
    public Route plan(ContentItem item, String deviceId) {
        return plan(item, device(deviceId));
    }

    public Route play(ContentItem item, String deviceId) {
        Device device = device(deviceId);
        Route route = plan(item, device);
        switch (route) {
            case Route.OpenAppLink open -> devices.execute(deviceId, open.action());
            case Route.Cast cast -> devices.execute(deviceId, cast.action());
            case Route.CastMessage message -> devices.execute(deviceId, message.action());
            case Route.JellyfinSession session -> executors.stream()
                    .filter(executor -> executor.executes(session))
                    .findFirst()
                    .orElseThrow(() -> new UnroutableException(device.name() + ": Jellyfin is switched off on this server"))
                    .execute(session, device);
            case Route.Unroutable unroutable -> throw new UnroutableException(device.name() + ": " + unroutable.reason());
        }
        return route;
    }

    private Route plan(ContentItem item, Device device) {
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        capabilities.addAll(devices.capabilities(device.id()));
        List<PlayableRef> playables = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (PlayableRef ref : item.playables()) {
            Optional<PlayableResolver> resolver = resolvers.stream().filter(r -> r.resolves(ref)).findFirst();
            if (resolver.isEmpty()) {
                playables.add(ref);
                continue;
            }
            PlayableResolver.Resolution resolution = resolver.get().resolve(ref, item, device, Set.copyOf(capabilities));
            playables.addAll(resolution.playables());
            capabilities.addAll(resolution.liveCapabilities());
            notes.addAll(resolution.notes());
        }
        if (playables.isEmpty() && !notes.isEmpty()) {
            return new Route.Unroutable(String.join("; ", notes));
        }
        Route route = planner.plan(item.withPlayables(playables), capabilities);
        if (route instanceof Route.Unroutable unroutable && !notes.isEmpty()) {
            return new Route.Unroutable(String.join("; ", notes) + "; " + unroutable.reason());
        }
        return route;
    }

    private Device device(String deviceId) {
        return devices.device(deviceId)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + deviceId));
    }
}
```

(Keep whatever extra behaviour B's `PlaybackService` already has; the switch must stay exhaustive over `Route`.)

- [ ] **Step 6: Implement the resolver, executor and controller**

`sources/jellyfin/JellyfinPlayableResolver.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Jellyfin item → open app session, else Jellyfin receiver message and direct stream (spec §4.2, §5.3). */
public class JellyfinPlayableResolver implements PlayableResolver {

    private final JellyfinSetupService setup;
    private final JellyfinSessions sessions;
    private final JellyfinClient client;
    private final JellyfinStreams streams;

    public JellyfinPlayableResolver(JellyfinSetupService setup, JellyfinSessions sessions, JellyfinClient client, JellyfinStreams streams) {
        this.setup = setup;
        this.sessions = sessions;
        this.client = client;
        this.streams = streams;
    }

    @Override
    public boolean resolves(PlayableRef ref) {
        return ref instanceof PlayableRef.JellyfinItem;
    }

    @Override
    public Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities) {
        PlayableRef.JellyfinItem wanted = (PlayableRef.JellyfinItem) ref;
        Optional<JellyfinSettings> settings = setup.settings();
        Optional<JellyfinConnection> connection = setup.connection();
        if (settings.isEmpty() || connection.isEmpty()) {
            return Resolution.note("Jellyfin is not connected");
        }
        if (wanted.serverId() != null && !wanted.serverId().isBlank()
                && !wanted.serverId().equalsIgnoreCase(settings.get().serverId())) {
            return Resolution.note("this item is from a different Jellyfin server");
        }
        List<String> notes = new ArrayList<>();
        try {
            Optional<JellyfinSession> open = sessions.sessionFor(device);
            if (open.isPresent()) {
                return new Resolution(List.of(new PlayableRef.JellyfinSession(open.get().id(), wanted.itemId(),
                        wanted.resumeTicks(), open.get().client())), Set.of(Capability.JELLYFIN_CLIENT), List.of());
            }
            notes.add("no Jellyfin app is open on " + device.name());
        } catch (JellyfinException e) {
            notes.add("could not ask Jellyfin which apps are open (" + e.getMessage() + ")");
        }
        boolean cast = capabilities.contains(Capability.CAST_RECEIVER);
        if (!cast && !capabilities.contains(Capability.MEDIA_RENDERER)) {
            return new Resolution(List.of(), Set.of(), notes);
        }
        JsonNode fetched;
        try {
            fetched = client.get(connection.get(), "/Items/" + JellyfinClient.id(wanted.itemId()),
                    Map.of("userId", connection.get().userId()));
        } catch (JellyfinException | IllegalArgumentException e) {
            notes.add("could not load the item from Jellyfin (" + e.getMessage() + ")");
            return new Resolution(List.of(), Set.of(), notes);
        }
        List<PlayableRef> playables = new ArrayList<>();
        if (cast) {
            playables.add(JellyfinCastMessages.playable(settings.get(), connection.get().token(), fetched,
                    wanted.resumeTicks(), device.name()));
        }
        try {
            streams.directStream(connection.get(), settings.get().deviceServerUrl(), fetched).ifPresentOrElse(playables::add,
                    () -> notes.add("Jellyfin reports no format this device can play directly"));
        } catch (JellyfinException e) {
            notes.add("could not ask Jellyfin how to stream the item (" + e.getMessage() + ")");
        }
        return new Resolution(playables, Set.of(), notes);
    }
}
```

`sources/jellyfin/JellyfinRouteExecutor.java`:

```java
package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;

public class JellyfinRouteExecutor implements RouteExecutor {

    private final JellyfinSessions sessions;

    public JellyfinRouteExecutor(JellyfinSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean executes(Route route) {
        return route instanceof Route.JellyfinSession;
    }

    @Override
    public void execute(Route route, Device device) {
        Route.JellyfinSession session = (Route.JellyfinSession) route;
        try {
            sessions.playNow(session.sessionId(), session.itemId(), session.startPositionTicks());
        } catch (JellyfinException | IllegalArgumentException e) {
            throw new ActionFailedException("Jellyfin could not start playback on " + device.name() + " (" + e.getMessage() + ")");
        }
    }
}
```

Add to `JellyfinConfiguration`: `@Bean JellyfinPlayableResolver jellyfinPlayableResolver(JellyfinSetupService, JellyfinSessions, JellyfinClient, JellyfinStreams)` and `@Bean JellyfinRouteExecutor jellyfinRouteExecutor(JellyfinSessions)`.

`web/ContentPlayController.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Plays a source item on a device. The browser sends only {@code source} and {@code item}; the server
 * re-reads the item, so no playable reference or credential ever comes from or goes to the browser.
 */
@RestController
public class ContentPlayController {

    private final DeviceManager devices;
    private final ContentSources sources;
    private final PlaybackService playback;

    public ContentPlayController(DeviceManager devices, ContentSources sources, PlaybackService playback) {
        this.devices = devices;
        this.sources = sources;
        this.playback = playback;
    }

    @PostMapping(path = "/devices/{id}/play", params = {"source", "item"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> play(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        return text(HttpStatus.OK, playback.play(content.get(), id).describe());
    }

    @GetMapping(path = "/devices/{id}/route", params = {"source", "item"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> route(@PathVariable String id, @RequestParam String source, @RequestParam String item) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Optional<ContentItem> content = find(source, item);
        if (content.isEmpty()) {
            return notFound(source);
        }
        Route route = playback.plan(content.get(), id);
        return route instanceof Route.Unroutable unroutable
                ? text(HttpStatus.UNPROCESSABLE_CONTENT, unroutable.reason())
                : text(HttpStatus.OK, route.describe());
    }

    private Optional<ContentItem> find(String source, String item) {
        return sources.find(source).flatMap(found -> found.item(item));
    }

    private ResponseEntity<String> notFound(String source) {
        return sources.find(source).isEmpty()
                ? text(HttpStatus.NOT_FOUND, "No content source " + source)
                : text(HttpStatus.NOT_FOUND, "No such item");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> bad(IllegalArgumentException e) {
        return text(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DeviceOfflineException.class)
    public ResponseEntity<String> offline(DeviceOfflineException e) {
        return text(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({UnsupportedActionException.class, UnroutableException.class})
    public ResponseEntity<String> cannot(RuntimeException e) {
        return text(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler({ActionFailedException.class, ContentSourceException.class})
    public ResponseEntity<String> failed(RuntimeException e) {
        return text(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }
}
```

(Use `UNPROCESSABLE_ENTITY` if the Spring version in use lacks `UNPROCESSABLE_CONTENT`, as A's controller does.)

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS (including A's `DeviceControllerTest` for the `uri` form); BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/playback src/main/java/dev/andre/homecontrol/playback \
  src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java src/main/java/dev/andre/homecontrol/sources/jellyfin \
  src/main/java/dev/andre/homecontrol/web/ContentPlayController.java \
  src/test/java/dev/andre/homecontrol/core/playback src/test/java/dev/andre/homecontrol/playback \
  src/test/java/dev/andre/homecontrol/sources/jellyfin src/test/java/dev/andre/homecontrol/web/ContentPlayControllerTest.java
git commit -m "feat: play Jellyfin items through the open app, the Jellyfin receiver or a direct stream"
```

---

### Task 8: C8 · Jellyfin search

**Files:**
- Modify: `sources/jellyfin/JellyfinContentSource.java`, `web/ContentController.java`
- Test: `sources/jellyfin/JellyfinContentSourceTest.java`, `web/ContentControllerTest.java`; fixture `src/test/resources/fixtures/jellyfin/search.json`

**Interfaces:**
- Consumes: `ContentSource.searchable/search/available`, `ContentSources.searchable()` (Task 3); `JellyfinItemMapper` (Task 3).
- Produces:
  - `JellyfinContentSource.searchable()` → `true`; `search(String query, int limit)` → mapped items.
  - `GET /search?q=<text>&limit=<n>` → `200 application/json` `{"query":"…","results":[{"sourceId":"…","sourceName":"…","items":[ContentItemView…]}],"errors":[{"sourceId":"…","message":"…"}]}`; `q` trimmed must be 2–100 characters, else `400 text/plain` `Search for 2 to 100 characters`; `limit` defaults to 20 and is clamped to 1–50. Sources are queried in `ContentSources` order; a failing source becomes an `errors` entry and the others still answer.

**Search query (normative).** `GET /Items?userId=<userId>&searchTerm=<q>&recursive=true&includeItemTypes=Movie,Episode,Video,MusicVideo,Audio&limit=<n>&enableUserData=true&enableImageTypes=Primary,Thumb,Backdrop&imageTypeLimit=1` → `{"Items":[…],"TotalRecordCount":n}`.

- [ ] **Step 1: Write the fixture**

`src/test/resources/fixtures/jellyfin/search.json`:

```json
{
  "Items": [
    {
      "Name": "Big Buck Bunny",
      "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
      "Id": "b1c2d3e4f5061728394a5b6c7d8e9f01",
      "RunTimeTicks": 5964800000,
      "ProductionYear": 2008,
      "IsFolder": false,
      "Type": "Movie",
      "MediaType": "Video",
      "UserData": { "PlaybackPositionTicks": 1491200000, "Played": false, "PlayedPercentage": 25.0 },
      "ImageTags": { "Primary": "c0ffeec0ffee" }
    },
    {
      "Name": "Bunny Hop",
      "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
      "Id": "0b0b0b0b0b0b4c4c8d8d9e9e0f0f1a1a",
      "IsFolder": false,
      "IndexNumber": 3,
      "ParentIndexNumber": 1,
      "Type": "Episode",
      "SeriesName": "Meadow Tales",
      "SeriesId": "1a1a0f0f9e9e8d8d4c4c0b0b0b0b0b0b",
      "SeriesPrimaryImageTag": "3e3e3e3e",
      "MediaType": "Video",
      "UserData": { "PlaybackPositionTicks": 0, "Played": false },
      "ImageTags": {}
    },
    {
      "Name": "Bunny Song",
      "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
      "Id": "c0ffee00c0ffee00c0ffee00c0ffee01",
      "IsFolder": false,
      "Type": "Audio",
      "MediaType": "Audio",
      "Artists": ["The Rabbits"],
      "AlbumArtist": "The Rabbits",
      "AlbumId": "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
      "AlbumPrimaryImageTag": "77aa77aa",
      "UserData": { "PlaybackPositionTicks": 0, "Played": false },
      "ImageTags": {}
    }
  ],
  "TotalRecordCount": 3,
  "StartIndex": 0
}
```

- [ ] **Step 2: Write the failing tests**

`JellyfinContentSourceTest` — add:
- `searchesTheWholeLibraryWithTheDocumentedQuery`: fake `GET /Items` → `search.json`; `search("bunny hop", 10)` → titles `Big Buck Bunny`, `Meadow Tales`, `Bunny Song`; kinds `MOVIE`, `EPISODE`, `TRACK`; recorded query exactly `{userId=a1b2…, searchTerm=bunny hop, recursive=true, includeItemTypes=Movie,Episode,Video,MusicVideo,Audio, limit=10, enableUserData=true, enableImageTypes=Primary,Thumb,Backdrop, imageTypeLimit=1}`.
- `isSearchable`: `searchable()` true.

`ContentControllerTest` — add (two mocked sources `jellyfin` and `tmdb`, both searchable, via `sources.searchable()`):
- `searchesEverySearchableSource`: `GET /search?q=bunny` → `$.query` `bunny`, `$.results[0].sourceId` `jellyfin`, `$.results[0].sourceName` `Jellyfin`, `$.results[0].items[0].title`; `verify(jellyfin).search("bunny", 20)`; the body contains no `playables`.
- `aFailingSourceIsReportedBesideTheOthers`: `tmdb.search` throws `ContentSourceException("TMDB is unreachable")` → `$.results` has one entry, `$.errors[0]` = `{"sourceId":"tmdb","message":"TMDB is unreachable"}`, status 200.
- `rejectsQueriesThatAreTooShortOrTooLong`: `q=" a "` → 400 `Search for 2 to 100 characters`; 101 characters → 400; missing `q` → 400.
- `clampsTheLimit`: `limit=500` → `search("bunny", 50)`; `limit=0` → `search("bunny", 1)`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinContentSourceTest' --tests 'dev.andre.homecontrol.web.ContentControllerTest'`
Expected: FAIL — `search` throws `UnsupportedOperationException` and `/search` is 404.

- [ ] **Step 4: Implement**

Add to `JellyfinContentSource`:

```java
    @Override
    public boolean searchable() {
        return true;
    }

    @Override
    public List<ContentItem> search(String query, int limit) {
        JellyfinConnection connection = connection();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("userId", connection.userId());
        params.put("searchTerm", query);
        params.put("recursive", "true");
        params.put("includeItemTypes", "Movie,Episode,Video,MusicVideo,Audio");
        params.put("limit", String.valueOf(limit));
        params.put("enableUserData", "true");
        params.put("enableImageTypes", IMAGE_TYPES);
        params.put("imageTypeLimit", "1");
        return JellyfinItemMapper.toItems(client.get(connection, "/Items", params).path("Items"));
    }
```

Add to `web/ContentController`:

```java
    public record SearchResult(String sourceId, String sourceName, List<ContentItemView> items) {
    }

    public record SearchError(String sourceId, String message) {
    }

    public record SearchResponse(String query, List<SearchResult> results, List<SearchError> errors) {
    }

    /** Used by the unified search box (D5). Sequential for now; a slow source delays the answer. */
    @GetMapping(path = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2 || query.length() > 100) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Search for 2 to 100 characters");
        }
        int clamped = Math.max(1, Math.min(50, limit));
        List<SearchResult> results = new ArrayList<>();
        List<SearchError> errors = new ArrayList<>();
        for (ContentSource source : sources.searchable()) {
            try {
                results.add(new SearchResult(source.id(), source.displayName(),
                        source.search(query, clamped).stream().map(ContentItemView::of).toList()));
            } catch (ContentSourceException e) {
                errors.add(new SearchError(source.id(), e.getMessage()));
            }
        }
        return ResponseEntity.ok(new SearchResponse(query, results, errors));
    }
```

(The existing class-level `ContentSourceException` handler does not interfere because the exception is caught here.)

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java \
  src/main/java/dev/andre/homecontrol/web/ContentController.java \
  src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSourceTest.java \
  src/test/java/dev/andre/homecontrol/web/ContentControllerTest.java src/test/resources/fixtures/jellyfin/search.json
git commit -m "feat: search the Jellyfin library"
```

---

### Task 9: C9 · Tests and acceptance

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinFixtureContractTest.java`, `src/test/java/dev/andre/homecontrol/web/JellyfinEndToEndTest.java`, `docs/superpowers/reviews/2026-09-16-jellyfin-source-acceptance.md`
- Modify: `README.md`
- Uses unchanged: `FakeJellyfinServer` (Task 2), `FakeCastReceiver` (B + Task 5), `FakeRemoteServer` (A), every fixture above.

**Interfaces:**
- Consumes: the whole application context; `DeviceManager.adopt/state/forget`, `CertificateStore.loadOrCreate`, `AndroidTvSettings.device` (A); endpoints `/setup/sources/jellyfin`, `/setup/sources/jellyfin/links`, `/setup/sources/jellyfin/disconnect`, `/login`, `/sources`, `/sources/jellyfin/rails/{id}`, `/sources/jellyfin/images/{id}/{type}`, `/search`, `/devices/{id}/play`, `/devices/{id}/route`, `/setup`.
- Produces: contract tests over every Jellyfin fixture; an end-to-end proof over real sockets of connect → login gating → rails → search → all route kinds → disconnect, with no credential in any browser-bound response; the manual checklist.

- [ ] **Step 1: Write the fixture contract test**

`src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinFixtureContractTest.java` — cases:
- `everyFixtureIsValidJson`: list `Path.of(getClass().getResource("/fixtures/jellyfin").toURI())`, parse each `*.json` with a `JsonMapper` without exception; at least 15 files.
- `railAndSearchFixturesProduceWellFormedItems` (`@ParameterizedTest` over `resume.json:Items`, `next-up.json:Items`, `latest.json:`, `search.json:Items` — the part after `:` is the array field, empty for a root array): every mapped item has a non-blank `id` matching `[0-9a-f]{32}`, `sourceId` `jellyfin`, a non-blank `title`, `progress` null or within 0–1, `artwork` null or starting with `/sources/jellyfin/images/` and containing `?tag=`, and exactly one playable, a `JellyfinItem` whose `serverId` is `4e1a2b3c4d5e4f60718293a4b5c6d7e8`, whose `itemId` equals the item id and whose `resumeTicks` ≥ 0.
- `noMappedItemCarriesACredential`: the `toString()` of every mapped item from every fixture contains neither `6c1f0e5a9b8d4c7e8f2a3b4c5d6e7f80` nor `ApiKey`.
- `sessionFixtureMatchesTheDocumentedShape`: every entry of `sessions.json` has `Id`, `DeviceId`, `DeviceName`, `Client`, `RemoteEndPoint`, `LastActivityDate`, `SupportsMediaControl`.
- `playbackInfoFixturesDecideDirectPlay`: `JellyfinStreams.fromPlaybackInfo` is present for `playback-info-direct.json` and `playback-info-audio.json` (with matching item media types) and empty for `playback-info-transcode-only.json`.

- [ ] **Step 2: Write the end-to-end test**

`src/test/java/dev/andre/homecontrol/web/JellyfinEndToEndTest.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer.ACCESS_TOKEN;
import static dev.andre.homecontrol.sources.jellyfin.FakeJellyfinServer.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Jellyfin through the real application over real sockets: fake Jellyfin ↔ source ↔ resolver ↔
 * planner ↔ Android TV and Cast adapters ↔ HTTP, with the login gate in front.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JellyfinEndToEndTest {

    static final String EPISODE = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";
    static final String SHIELD_SESSION = "1d2c3b4a59687f6e5d4c3b2a19081726";
    static final String SHIELD_JELLYFIN_DEVICE = "b2c4d6e8f0a1c3e5";
    static final String LOGIN = "household password";
    static Path dataDir;

    @DynamicPropertySource
    static void isolatedAndFast(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("jellyfin-e2e");
        registry.add("shield.data-dir", dataDir::toString);
        registry.add("home-control.cast.command-timeout-seconds", () -> "3");
        registry.add("home-control.cast.load-timeout-seconds", () -> "5");
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    CertificateStore certificates;

    private final HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final HttpClient stranger = HttpClient.newHttpClient();
    private final List<String> browserBodies = new ArrayList<>();

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (client == browser) {
            browserBodies.add(response.body());
        }
        return response;
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    /** A page navigation: unauthenticated ones are redirected to /login instead of answered 401. */
    private HttpRequest.Builder page(String path) {
        return get(path).header("Accept", "text/html");
    }

    private HttpRequest.Builder post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    @Test
    void connectBrowseAndPlayThroughEveryRouteWithoutLeakingTheToken() throws Exception {
        try (FakeJellyfinServer jellyfin = new FakeJellyfinServer().withConnectableServer()
                     .respond("GET", "/UserItems/Resume", 200, "resume.json")
                     .respond("GET", "/Items", 200, "search.json")
                     .respond("GET", "/Sessions", 200, "sessions.json")
                     .respond("GET", "/Items/" + EPISODE, 200, "item-episode.json")
                     .respond("POST", "/Items/" + EPISODE + "/PlaybackInfo", 200, "playback-info-direct.json")
                     .respondJson("POST", "/Sessions/" + SHIELD_SESSION + "/Playing", 204, null)
                     .respondBytes("GET", "/Items/" + EPISODE + "/Images/Primary", 200, "image/jpeg", new byte[]{1, 2, 3});
             FakeRemoteServer shieldRemote = new FakeRemoteServer();
             FakeCastReceiver kitchenCast = new FakeCastReceiver()) {

            certificates.loadOrCreate("shield-e2e");
            devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", shieldRemote.port(), null, Instant.now()));
            kitchenCast.appSpeaks("F007D354", "urn:x-cast:com.connectsdk");
            devices.adopt(new Device("kitchen-e2e", "Kitchen", DeviceKind.CAST, "127.0.0.1",
                    Map.of("cast", Map.of("port", String.valueOf(kitchenCast.port()))), Instant.now()));
            try {
                // Device-only: open without login.
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);

                // Connecting Jellyfin sets the login password and logs this browser in.
                HttpResponse<String> connected = send(browser, post("/setup/sources/jellyfin", Map.of(
                        "serverUrl", jellyfin.url().toString(), "mode", "password", "userName", "andre",
                        "password", "user password", "loginPassword", LOGIN, "loginPasswordConfirmation", LOGIN)));
                assertThat(connected.statusCode()).isEqualTo(302);
                assertThat(send(browser, page("/setup")).body()).contains("Connected to nas");

                // From now on everything is gated for other clients, images and SSE included.
                assertThat(send(stranger, get("/sources")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, get("/events")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, get("/sources/jellyfin/images/" + EPISODE + "/Primary?tag=1a2b3c4d5e6f")).statusCode()).isEqualTo(401);
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(302);

                // Rails, artwork and search for the logged-in browser.
                HttpResponse<String> rail = send(browser, get("/sources/jellyfin/rails/resume"));
                assertThat(rail.statusCode()).isEqualTo(200);
                assertThat(rail.body()).contains("Northern Lights").contains("/sources/jellyfin/images/" + EPISODE + "/Primary?tag=1a2b3c4d5e6f");
                assertThat(send(browser, get("/sources/jellyfin/images/" + EPISODE + "/Primary?tag=1a2b3c4d5e6f")).statusCode()).isEqualTo(200);
                assertThat(jellyfin.last("GET", "/Items/" + EPISODE + "/Images/Primary").header("authorization")).isNull();
                HttpResponse<String> search = send(browser, get("/search?q=bunny"));
                assertThat(search.body()).contains("Big Buck Bunny").contains("Meadow Tales").contains("Bunny Song");

                // Rung 1: the Shield's Jellyfin app is linked on the setup page, then commanded.
                assertThat(send(browser, post("/setup/sources/jellyfin/links",
                        Map.of("session", SHIELD_JELLYFIN_DEVICE, "device", "shield-e2e"))).statusCode()).isEqualTo(302);
                HttpResponse<String> onShield = send(browser, post("/devices/shield-e2e/play", Map.of("source", "jellyfin", "item", EPISODE)));
                assertThat(onShield.statusCode()).isEqualTo(200);
                assertThat(onShield.body()).isEqualTo("Play in the open Jellyfin app (Android TV)");
                assertThat(jellyfin.last("POST", "/Sessions/" + SHIELD_SESSION + "/Playing").query()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "playCommand", "PlayNow", "itemIds", EPISODE, "startPositionTicks", "6120000000"));

                // Rung 3: the Kitchen Cast device has no Jellyfin app, so the Jellyfin receiver gets the request.
                await().until(() -> devices.state("kitchen-e2e").connected());
                HttpResponse<String> preview = send(browser, get("/devices/kitchen-e2e/route?source=jellyfin&item=" + EPISODE));
                assertThat(preview.body()).isEqualTo("Cast with the Jellyfin receiver");
                assertThat(kitchenCast.received("urn:x-cast:com.connectsdk", "")).isEmpty();
                HttpResponse<String> onKitchen = send(browser, post("/devices/kitchen-e2e/play", Map.of("source", "jellyfin", "item", EPISODE)));
                assertThat(onKitchen.statusCode()).isEqualTo(200);
                assertThat(onKitchen.body()).isEqualTo("Cast with the Jellyfin receiver");
                CastIncoming request = kitchenCast.received("urn:x-cast:com.connectsdk", "").getLast();
                assertThat(request.payload().path("command").asString("")).isEqualTo("PlayNow");
                assertThat(request.payload().path("accessToken").asString("")).isEqualTo(ACCESS_TOKEN);
                assertThat(request.payload().path("userId").asString("")).isEqualTo(USER_ID);
                assertThat(request.payload().path("serverAddress").asString("")).isEqualTo(jellyfin.url().toString());
                assertThat(request.payload().path("options").path("items").path(0).path("Id").asString("")).isEqualTo(EPISODE);
                assertThat(request.payload().path("options").path("startPositionTicks").asLong(0)).isEqualTo(6_120_000_000L);

                // Nothing the browser received, and nothing in sources.json, holds the token.
                assertThat(browserBodies).noneMatch(body -> body.contains(ACCESS_TOKEN) || body.contains("ApiKey"));
                assertThat(Files.readString(dataDir.resolve("sources.json"))).doesNotContain(ACCESS_TOKEN);
                assertThat(Files.readString(dataDir.resolve("secrets.json"))).doesNotContain(ACCESS_TOKEN);

                // Disconnecting removes the last secret: the deployment is device-only again.
                assertThat(send(browser, post("/setup/sources/jellyfin/disconnect", Map.of())).statusCode()).isEqualTo(302);
                assertThat(jellyfin.requests("POST", "/Sessions/Logout")).isNotEmpty();
                assertThat(send(stranger, page("/setup")).statusCode()).isEqualTo(200);
            } finally {
                devices.forget("shield-e2e");
                devices.forget("kitchen-e2e");
            }
        }
    }
}
```

(If `DeviceState` in the real code has no `connected()` helper, await `status() == DeviceStatus.CONNECTED` as B's end-to-end test does. The Shield has no Cast adapter here, so rung 1 is the only route it can take for this item; the Kitchen at `127.0.0.1` matches no session because `sessions.json` reports LAN addresses and neither device name.)

- [ ] **Step 3: Run the new tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinFixtureContractTest' --tests 'dev.andre.homecontrol.web.JellyfinEndToEndTest' --tests 'dev.andre.homecontrol.web.LoginGatingTest'`
Expected: PASS. A failure here is a real integration defect in Tasks 1–8: fix it in the task's code (not in the test) and name the fix in the commit body.

- [ ] **Step 4: Write the manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-jellyfin-source-acceptance.md`:

```markdown
# Jellyfin Source and Playback — Manual Acceptance (sub-project C, release 0.8)

Automated coverage: `JellyfinEndToEndTest`, `LoginGatingTest`, `JellyfinFixtureContractTest` and the unit
tests of Tasks 1–8, all against in-process fakes. Nothing below has been run on real hardware by an agent.

Setup assumed: Jellyfin 10.9+ on the LAN; an NVIDIA Shield paired over Android TV (with its Cast side
merged per sub-project B) running the official Jellyfin Android TV app; one Chromecast or Cast TV.

## Secrets and login

| # | Check | Result |
|---|---|---|
| 1 | Fresh install: `/`, `/setup` and the remote work without a login; `/data` has no `secrets.json` or `secret.key` | Pending — requires real hardware |
| 2 | Connect Jellyfin with user + password and a new login password; the browser stays logged in; `/data/secrets.json` and `/data/secret.key` exist with mode 0600 and contain no readable token | Pending — requires real hardware |
| 3 | A second phone is sent to `/login`; the wrong password is refused; after five wrong attempts it must wait; the right password opens the dashboard | Pending — requires real hardware |
| 4 | Change the password on the setup page: the other phone is logged out | Pending — requires real hardware |
| 5 | Restart the container: login still required, Jellyfin still connected | Pending — requires real hardware |
| 6 | Set `HOME_CONTROL_SECRET` and restart: `secrets.json` now names `HOME_CONTROL_SECRET`; starting with a different value fails with a named error | Pending — requires real hardware |
| 7 | Disconnect Jellyfin: the login requirement disappears | Pending — requires real hardware |

## Jellyfin content

| # | Check | Result |
|---|---|---|
| 8 | `GET /sources/jellyfin/rails/resume`, `next-up`, `latest` list the same items as the Jellyfin web client's home rows | Pending — requires real hardware |
| 9 | Artwork loads through `/sources/jellyfin/images/…` from a phone that cannot reach the Jellyfin server directly | Pending — requires real hardware |
| 10 | `GET /search?q=<title>` finds movies, episodes and songs | Pending — requires real hardware |
| 11 | "Test connection" reports server name, version and user; a wrong URL gives "Could not reach Jellyfin at …" | Pending — requires real hardware |

## Play routes

| # | Check | Result |
|---|---|---|
| 12 | Jellyfin Android TV app open in the foreground on the Shield: `POST /devices/<shield>/play` with a Continue-watching item answers "Play in the open Jellyfin app (Android TV)" and resumes at the saved position with the user's subtitle choice | Pending — requires real hardware |
| 13 | Same with the Jellyfin app in the background (Home pressed): record whether the app comes to the front and plays | Pending — requires real hardware |
| 14 | Jellyfin behind Docker bridge networking (sessions show a gateway address): the Shield is not matched until linked on the setup page, then rung 12 works | Pending — requires real hardware |
| 15 | Jellyfin app closed, Shield Cast side merged: the route becomes "Cast with the Jellyfin receiver" and playback resumes on the Shield | Pending — requires real hardware |
| 16 | Chromecast: "Cast with the Jellyfin receiver" starts playback; the device strip shows the title from Cast media status; Jellyfin's dashboard shows the Chromecast session progressing | Pending — requires real hardware |
| 17 | Jellyfin reached by the container as `http://jellyfin:8096`: the setup page warns; with the TV address set, rung 15/16 works | Pending — requires real hardware |
| 18 | Jellyfin 12 with legacy authorization disabled: rails, session play and the Jellyfin receiver still work (the receiver builds its own stream URLs — note any failure) | Pending — requires real hardware |
| 19 | `GET /devices/<id>/route?source=jellyfin&item=<id>` names the same route that play then uses, for the Shield and the Chromecast | Pending — requires real hardware |

## Findings

(none recorded yet)
```

- [ ] **Step 5: Document**

In `README.md` add a section `## Content sources and login` covering: the login is required only after a content source is connected, and applies to every page, the live updates and artwork; connecting Jellyfin (server URL, optional address for TVs, user login recommended over API key, why); where secrets live (`/data/secrets.json`, encrypted with `/data/secret.key` or with `HOME_CONTROL_SECRET`), the honest limit (copying the whole `/data` folder copies the key unless `HOME_CONTROL_SECRET` is used), and that changing or losing `HOME_CONTROL_SECRET` stops the app from starting until restored; forgotten password = stop the container, delete `/data/secrets.json`, reconnect sources; `HOME_CONTROL_SECURE_COOKIE=true` behind an HTTPS reverse proxy and `HOME_CONTROL_TRUSTED_ORIGINS` when the proxy rewrites the Host header; `HOME_CONTROL_JELLYFIN_ENABLED=false` switches the module off; the three play routes in preference order and the setup-page session link for Docker-networked Jellyfin; Jellyfin 10.9 or newer.

- [ ] **Step 6: Build**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinFixtureContractTest.java \
  src/test/java/dev/andre/homecontrol/web/JellyfinEndToEndTest.java \
  docs/superpowers/reviews/2026-09-16-jellyfin-source-acceptance.md README.md
git commit -m "test: Jellyfin end-to-end over fakes, fixture contracts and the acceptance checklist"
```

---

## Out of scope for this plan

- Rail cache, scheduler and `rail` SSE events (D1); rails layout and play sheet UI (D2, D3); unified search UI (D5); Playwright login-gating tests (D7).
- Jellyfin transcoding URLs, subtitles and audio-track selection for the stream route; music rails (I3).
- Retrying the next route automatically after a failed one (commands are ephemeral; D3 offers the retry).
- Per-user profiles; more than one Jellyfin server.
