# Home Control Center — Program Roadmap and Issue Backlog

> **For agentic workers:** this is the program-level plan. It breaks the concept
> into ten sub-projects (A–J) and each sub-project into issue-sized tasks with
> acceptance criteria. Each sub-project gets its own executable, TDD-style plan
> under `docs/superpowers/plans/` when it starts; sub-project A's plan is
> `2026-09-16-multi-device-core.md`. Do not implement from this document
> directly — implement from the per-sub-project plan.

**Goal:** Expand the Shield web remote into a LAN home control center: one
dashboard that shows every playable device and every content source, and plays
a selected item on a chosen device through the best available route.

**Architecture:** One Spring Boot monolith with a capability-based device model,
pluggable device adapters, pluggable content sources, and a playback planner that
matches an item's playable references to a device's capabilities. See spec §5–§7.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Gradle 9.7.1, Thymeleaf, htmx, SSE,
vanilla ES modules, protobuf 4.36.0, jmDNS, JUnit 5, AssertJ, Mockito,
Awaitility, Docker Compose, CasaOS. Added per sub-project: a Cast sender
library (B), Playwright for Java test-only (D), `bluez-dbus` (J).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md`

## Global Constraints

Copied from the spec; every task inherits them.

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a play request that cannot be routed now fails now with a reason. Nothing is queued.
- Only adapters speak device protocols; only sources speak content APIs. The planner and web layer see the domain model only.
- Route by capability, not by brand. New devices and sources plug into the `DeviceAdapter` / `ContentSource` contracts.
- Honesty about walled gardens: Netflix, Prime Video and DAZN are launchers with third-party metadata; the UI never implies a personalised feed from them.
- Secrets raise the bar: a single-password login is mandatory once any secret is stored. Secrets are encrypted at rest and never reach the browser.
- Persistent state stays in `/data` as JSON files written atomically; the existing `devices.json` and `keystore.p12` must migrate without re-pairing.
- Every adapter and source is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; every source has recorded JSON fixtures; the planner has pure unit tests.
- Releases follow conventional commits; `feat:` bumps minor, `fix:` bumps patch.

---

## Dependency graph

```text
A Multi-device core
├── B Google Cast adapter
│   ├── C Jellyfin source + playback  (also needs A)
│   │   ├── D Dashboard shell
│   │   │   ├── E YouTube source
│   │   │   └── G Streaming launchers (TMDB, pinned, Netflix/Prime)
│   │   │       └── H Sports and DAZN
│   │   └── I Wi-Fi speakers  (needs B and C)
│   │       └── J Bluetooth speakers
└── F Smart TV adapters (webOS, Tizen)
```

Each sub-project is independently releasable. Within a sub-project, tasks are
listed in execution order; a task that names a predecessor is blocked by it.

---

## A — Multi-device core

**Delivers:** the domain model and adapter contract, registry v2 with migration
of the current single-Shield file, one live session per registered device,
multi-device SSE, the dashboard shell with a device strip and per-device remote
drawer, the playback planner skeleton, and app-link launching on Android TV
(the pasted-URL form is the first "play something on a device" feature).

Executable plan: `docs/superpowers/plans/2026-09-16-multi-device-core.md`.

| # | Task | Acceptance |
|---|---|---|
| A1 | Rename the root package to `dev.andre.homecontrol` | `./gradlew build` green; `shield.*` configuration prefix and `SHIELD_KEYSTORE_PASSWORD` unchanged; CasaOS app id unchanged. |
| A2 | Core domain: `Capability`, `DeviceKind`, `Action`, `DeviceAdapter`, `DeviceHandle`, `Device` v2, `DeviceStateChangedEvent` with device id | Types compile with unit tests for `Action.requires()` and `Device.adapterSettings()`. No behavior change yet. Blocked by A1. |
| A3 | Registry v2 with v1 migration | A v1 `devices.json` (`host`, `port`, `certificateFingerprint`) loads as one `ANDROID_TV` device with `androidtv` adapter settings and is rewritten in v2 shape; v2 files round-trip; malformed files still raise `StorageException`. Blocked by A2. |
| A4 | Android TV adapter | `AndroidTvAdapter implements DeviceAdapter`; the existing session becomes its `DeviceHandle`; capabilities `REMOTE_KEYS, POWER, VOLUME`; missing credential yields an `UNPAIRED` handle without creating one; keystore verified at adapter start. Blocked by A3. |
| A5 | Restore app-link launching | `RemoteConnection.sendAppLink(uri)` sends `RemoteAppLinkLaunchRequest`; the client advertises `FEATURE_APP_LINK = 512` (mask 614); `Action.OpenAppLink` executes through the handle; `FakeRemoteServer` captures it. Blocked by A4. |
| A6 | `DeviceManager` for every registered device | Starts one handle per registered device and adapter; `state(id)`, `states()`, `capabilities(id)`, `execute(id, action)`, `adopt`, `forget`, `defaultDevice()`, `discovered()`; the "active device only" rule is gone. Blocked by A5. |
| A7 | Multi-device SSE | `state` events carry `{deviceId, state}`; a new tab receives one event per device; the broadcaster's fan-out guarantees survive. Blocked by A6. |
| A8 | Playback domain and planner skeleton | `PlayableRef` (AppLink, CastLoad, JellyfinItem, StreamUrl), `ContentItem`, `Route`, `PlaybackPlanner`, `PlaybackService`, `AppLinks.fromUrl`; planner routes AppLink on `APP_LINK` devices and returns `Unroutable` with a reason for everything else. Pure unit tests over the capability × playable matrix. Blocked by A6. |
| A9 | Web: per-device endpoints and dashboard shell | `POST /devices/{id}/key/{key}`, `POST /devices/{id}/play`, `GET /` with device strip + remote drawer for `?device=`, `GET /remote/{id}`, setup lists every paired device with forget. MockMvc tests. Blocked by A7, A8. |
| A10 | Browser modules | `state-view.js`, `remote-transport.js`, `app.js` entry: per-device badges, target switching, keyboard to selected device, open-link form with route/failure toasts. Blocked by A9. |
| A11 | Docs and acceptance | README describes multiple devices and the open-link form; manual Shield acceptance recorded in `docs/superpowers/reviews/`. Blocked by A10. |

## B — Google Cast adapter

**Delivers:** discovery and control of Cast receivers (Chromecast, Cast TVs,
Cast speakers, the Shield's built-in Cast), Default Media Receiver playback of
direct URLs, and now-playing state. Blocked by A.

| # | Task | Acceptance |
|---|---|---|
| B1 | Spike: Cast sender library choice | Compare `chromecast-java-api-v2`, the DigitalMediaServer `Cast-API` fork, and an in-house minimal sender (protobuf + TLS, like Remote v2). Output: an ADR in `docs/superpowers/specs/` naming the choice, licence, and maintenance risk. Throwaway code only. |
| B2 | Cast discovery and device merge | mDNS `_googlecast._tcp` discovery; a receiver with the same IP or friendly name as an Android TV device merges into that `Device` with an added `cast` adapter entry; manual split/merge on the setup page. Blocked by B1. |
| B3 | Cast connection handle | Connect on 8009 with TLS, virtual connection, heartbeat, receiver status (volume, mute, running app), reconnect with backoff, `DeviceState` updates. Blocked by B2. |
| B4 | Cast actions | `Action.SetVolume`, `Action.Mute`, `Action.Stop` added to core; capabilities `CAST_RECEIVER, VOLUME`; planner-independent execution. Blocked by B3. |
| B5 | Default Media Receiver playback | Launch `CC1AD845`, `LOAD` a `StreamUrl`, media status → `nowPlaying` title and position in `DeviceState`. Blocked by B4. |
| B6 | Planner routes for Cast | `Route.Cast` for `CastLoad` and for `StreamUrl` on `CAST_RECEIVER` devices, placed after app link and before media renderer per spec §5.3. Blocked by B5. |
| B7 | Fake Cast receiver and acceptance | In-process fake speaking enough CASTV2 for connect, heartbeat, receiver status and LOAD; manual acceptance on the Shield's Cast and one Chromecast, recorded in reviews. Blocked by B6. |

## C — Jellyfin source and playback

**Delivers:** the flagship content source with browse, recommend, and all three
play routes, plus the secret store and mandatory login that every later source
relies on. Blocked by A and B.

| # | Task | Acceptance |
|---|---|---|
| C1 | Secret store and login | `secrets.json` encrypted with a key derived from `HOME_CONTROL_SECRET` or a generated `/data/secret.key`; Argon2 password hash; session cookie; rate limiting; login required only when at least one secret exists; device-only deployments unchanged. |
| C2 | Jellyfin client and setup | Server URL, API key or user token, user id, connectivity check with a clear error; `@ConditionalOnProperty` module; setup page section. Blocked by C1. |
| C3 | Rails: Resume, Next Up, Latest | `ContentSource` contract (`id()`, `rails()`); items carry `JellyfinItem` refs, artwork URLs, progress; recorded JSON fixtures. Blocked by C2. |
| C4 | Session remote control | List sessions, match a session to a `Device` by client name/IP, `POST /Sessions/{id}/Playing?playCommand=PlayNow`; only sessions reporting `SupportsMediaControl`. Blocked by C3. |
| C5 | Jellyfin Cast receiver route | Launch receiver `F007D354` with `customData` (server address, access token, user id, item ids, start ticks). Blocked by C4, B6. |
| C6 | Direct-stream URL builder | `StreamUrl` from a Jellyfin item with the container/codec the server reports as direct-playable; used by DMR and later DLNA routes. Blocked by C3. |
| C7 | Planner resolution of `JellyfinItem` | Live session → cast receiver → stream URL, each with the reason shown to the user; `Unroutable` when none applies. Blocked by C5, C6. |
| C8 | Jellyfin search | `GET /search?q=` JSON across the library, used by D5. Blocked by C3. |
| C9 | Tests and acceptance | Contract tests over fixtures; MockMvc for login gating; manual acceptance with the official Jellyfin Android TV app on the Shield. Blocked by C7, C8. |

## D — Dashboard shell

**Delivers:** the content half of the dashboard: rails, play sheet with route
preview, source setup, search, PWA polish. Blocked by C.

| # | Task | Acceptance |
|---|---|---|
| D1 | Rail cache and scheduler | Per-source TTL cache, background refresh, `rail` SSE events; page loads never block on an upstream API. |
| D2 | Rails layout | Horizontal rails in user order; a failed rail shows a compact error with retry, never a gap; phone-first responsive layout. Blocked by D1. |
| D3 | Play sheet | Title, artwork, planned route for the selected device ("Play on Shield via YouTube app"), device switcher, one-tap play, failure toast naming the failed route and the next one. Blocked by D2. |
| D4 | Sources setup | Enable/disable sources, order and toggle rails, refresh intervals, locale/providers. Blocked by D2. |
| D5 | Unified search | One box over every source that implements `search()`, debounced, results in the play sheet. Blocked by D3, C8. |
| D6 | PWA and touch polish | Web app manifest, icons, safe areas, install guidance, the vNext v0.4 touchpad mode for the remote drawer. Blocked by D3. |
| D7 | Playwright harness | Test-only Gradle dependency; Chromium and WebKit in CI; tests for play sheet, device switching, rail failure state, login gating. Blocked by D5, D6. |

## E — YouTube source

Blocked by D.

| # | Task | Acceptance |
|---|---|---|
| E1 | Google OAuth device flow | User enters the code on a phone; refresh token in the secret store; token refresh; setup page instructions for creating the Cloud project. |
| E2 | Subscriptions rail | `subscriptions.list` → uploads playlists → newest videos; hourly cache; quota accounting shown in setup. Blocked by E1. |
| E3 | Watch Later and playlists rails | User-selected playlists as rails. Blocked by E2. |
| E4 | Search | `search.list` capped per day; surfaced in D5. Blocked by E2. |
| E5 | Play routes | `AppLink(https://www.youtube.com/watch?v=ID, "youtube")` primary; Cast via the Lounge API as best effort behind a per-device toggle, documented as such. Blocked by E2. |
| E6 | Fixtures and acceptance | Recorded API fixtures; acceptance on the Shield and one Cast device. Blocked by E3, E4, E5. |

## F — Smart TV adapters

Blocked by A. Independent of B–E.

| # | Task | Acceptance |
|---|---|---|
| F1 | SSDP discovery service | One SSDP listener shared by webOS, Tizen and later UPnP; device merge by IP. |
| F2 | LG webOS adapter | SSAP WebSocket pairing (client key stored per device), keys, volume, inputs, power off, Wake-on-LAN on, app launch with `contentId`/`contentTarget` for YouTube and Netflix; capabilities `REMOTE_KEYS, POWER, VOLUME, APP_LINK`. Blocked by F1. |
| F3 | Samsung Tizen adapter | WebSocket remote on 8002 with token pairing, keys, volume, power (WoL), app launch by id, DIAL YouTube with `v=`; capabilities as F2 minus Netflix deep link. Blocked by F1. |
| F4 | Deep-link test button | Setup page action that launches a known YouTube video on the device and reports whether the foreground app changed. Blocked by F2, F3. |
| F5 | Fake servers and acceptance | Fake SSAP and Tizen servers; acceptance on the household's real TVs. Blocked by F4. |

## G — Streaming launchers

Blocked by D.

| # | Task | Acceptance |
|---|---|---|
| G1 | TMDB client | API key in secret store; search, trending, watch providers by locale; fixtures. |
| G2 | Pinned shortcuts | Paste a URL → service detection (YouTube, Netflix, Prime Video, DAZN, other) → stored item with the right `PlayableRef`s; rail "Pinned". Blocked by G1. |
| G3 | Netflix and Prime app-link builders | Per platform: Android TV `https://www.netflix.com/title/{id}` and `https://app.primevideo.com/detail?gti=…`; webOS `contentId`; Tizen app launch only. Blocked by G2, F2. |
| G4 | Provider-aware trending rail | TMDB trending filtered to configured providers; items without a known service id offer "Open Netflix" app launch and a "pin a URL to upgrade" action. Blocked by G3. |
| G5 | Tests and acceptance | Fixtures for TMDB; acceptance of Netflix and Prime deep links on the Shield. Blocked by G4. |

## H — Sports and DAZN

Blocked by G.

| # | Task | Acceptance |
|---|---|---|
| H1 | ICS calendar source | User-supplied calendar URLs parsed into `LIVE_EVENT` items with start/end. |
| H2 | TheSportsDB fixtures | Fixtures for user-selected competitions; fixtures cached daily. Blocked by H1. |
| H3 | Competition → provider mapping | Setup UI mapping each competition to DAZN or another provider; the UI labels the flag as user-configured. Blocked by H2. |
| H4 | Live now / Today rail and DAZN launch | Rail sorted by start time; play opens the DAZN app via app link, or a pasted per-event deep link. Blocked by H3. |
| H5 | Tests | Fixtures for ICS and TheSportsDB; planner tests for `LIVE_EVENT`. Blocked by H4. |

## I — Wi-Fi speakers

Blocked by B and C.

| # | Task | Acceptance |
|---|---|---|
| I1 | UPnP AVTransport adapter | SSDP `MediaRenderer` discovery, `SetAVTransportURI`, `Play`, `Stop`, `RenderingControl` volume; capability `MEDIA_RENDERER, VOLUME`. Blocked by F1. |
| I2 | Sonos adapter | Sonos discovery, play URL, volume, group topology; grouping controls in the drawer. Blocked by I1. |
| I3 | Audio routes and now-playing | Planner `Route.Render` for `StreamUrl` on `MEDIA_RENDERER`; Jellyfin music rails; now-playing from AVTransport events. Blocked by I2, C6. |
| I4 | Fake renderer and acceptance | Fake UPnP renderer; acceptance on the household's speakers. Blocked by I3. |

## J — Bluetooth speakers

Blocked by I. Optional module; may be dropped depending on the host.

| # | Task | Acceptance |
|---|---|---|
| J1 | Host requirements and packaging | Documented host checklist (BlueZ, D-Bus socket mount, audio stack), Compose and CasaOS variants with the optional mounts, module off by default. |
| J2 | BlueZ pairing UI | Scan, pair, connect, forget via `bluez-dbus`; device appears with `LOCAL_AUDIO_SINK`. Blocked by J1. |
| J3 | Server-side player | `mpv` subprocess playing a `StreamUrl` to the host audio sink; play/stop/volume; now-playing. Blocked by J2. |
| J4 | Acceptance | Verified on a Raspberry Pi class host; failure modes documented. Blocked by J3. |

---

## Release mapping

| Release | Sub-projects | Headline |
|---|---|---|
| 0.6 | A | Several devices, open a link on any of them |
| 0.7 | B | Cast devices |
| 0.8 | C | Jellyfin: continue watching on any device; login |
| 0.9 | D | The dashboard |
| 1.0 | E, F | YouTube and Smart TVs |
| 1.1 | G, H | Netflix, Prime, sport |
| 1.2 | I | Wi-Fi speakers |
| 1.3 | J | Bluetooth speakers |
