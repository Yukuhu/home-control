# Home Control Center — Concept and Architecture

**Date:** 2026-09-16
**Status:** Draft for review. Supersedes the product scope of the vNext roadmap
(2026-08-30); the v0.4/v0.5 remote work remains valid as sub-projects.

## 1. Vision

Home Control turns from a Shield web remote into a **home control center**: one
LAN-hosted website that shows the household's playable devices and its content
sources side by side, and lets the user pick something and play it on a chosen
device with one tap.

The two halves of the dashboard:

- **Devices** — NVIDIA Shield and other Android TV / Google TV boxes, Smart TVs
  (LG webOS, Samsung Tizen, Android TV based sets), Cast receivers, Wi-Fi
  speakers (Cast audio, Sonos, DLNA renderers, AirPlay later), and Bluetooth
  speakers (via the host's Bluetooth adapter, last phase).
- **Content** — Jellyfin (continue watching, next up, latest), YouTube
  (subscriptions, playlists, search), DAZN (live and upcoming sport), Netflix and
  Amazon Prime Video (launch a specific title), plus user-pinned shortcuts.

The core interaction is *select content → pick target → it plays*. The server
figures out the route: the device's native app, Google Cast, DLNA/UPnP, or
Bluetooth audio, in that order of preference where several apply.

## 2. Principles and constraints

Carried over from the existing product:

- **LAN appliance.** Runs as one Docker container on CasaOS or plain Compose.
  No cloud relay. Everything works over plain HTTP on the home network.
- **One build and runtime.** Spring Boot, Thymeleaf, htmx, SSE, vanilla ES
  modules. No Node production build.
- **Plug-and-play.** Device setup is the device's own pairing flow (TV pairing
  code, Cast is pairing-free, webOS prompt on the TV). No ADB, no developer mode.
- **Commands are ephemeral.** A play request that cannot be routed now fails
  now, with a reason. Nothing is queued for later.

New for the control center:

- **Honesty about walled gardens.** Netflix, Prime Video and DAZN have no public
  content APIs. The product launches titles and shows schedules from third-party
  metadata; it never pretends to read a user's Netflix "continue watching".
- **Route by capability, not by brand.** Devices declare capabilities; content
  items declare playable references; a planner matches them. New devices and
  sources plug into that contract rather than into each other.
- **Secrets raise the bar.** The server will hold OAuth tokens and API keys.
  A single-password login becomes mandatory the moment any content source is
  configured (see §9).

## 3. Assumptions made in the absence of answers

The concept was written without a live Q&A. Each assumption is a decision the
user can reverse before the first sub-project starts:

1. **Primary target stays the Shield**, with Cast-capable TVs and speakers as
   the second tier and webOS/Tizen native control as the third.
2. **Jellyfin is self-hosted on the same LAN** and reachable from both the
   server container and the TVs by one stable URL.
3. **Household size is one to a few users** sharing one dashboard; no
   per-user profiles in the first releases.
4. **German market** for DAZN and Prime Video (deep-link domains and sport
   catalogue), configurable by locale.
5. **Bluetooth speakers are the lowest priority** because they need host
   Bluetooth pass-through and make the server an audio player; Wi-Fi speakers
   cover most of the value at a fraction of the risk.
6. **The existing remote UI is kept** as a per-device drawer, not rebuilt.

## 4. Feasibility survey

Confidence: **High** = documented or widely used open protocol; **Medium** =
works in the open-source ecosystem but reverse-engineered or version-dependent;
**Low** = no supported path, best effort only.

### 4.1 Devices

| Device class | Control | Play content | Confidence | Notes |
|---|---|---|---|---|
| Android TV / Google TV (Shield, Sony, TCL, Chromecast with Google TV) | Remote v2 (existing) | Remote v2 `RemoteAppLinkLaunchRequest` with an `https://` or scheme URI; also Google Cast | High | The v0.3 removal of app launching was about deriving a URI from a *package name*. Content sources supply the URI directly, so the objection no longer applies. |
| Google Cast receivers (Chromecast, Cast-enabled TVs and speakers, Shield's built-in Cast) | CASTV2 over TLS 8009: volume, stop, media commands | Default Media Receiver for direct URLs; Jellyfin receiver `F007D354`; YouTube receiver via the Lounge API | High for DMR and Jellyfin; Medium for YouTube | Java sender libraries exist (`chromecast-java-api-v2` on Maven Central, and the DigitalMediaServer fork). The project already speaks protobuf over TLS for Remote v2, so an in-house minimal sender is a fallback. |
| LG webOS TV | SSAP WebSocket (pairing prompt on TV): keys, volume, inputs, app launch | `ssap://system.launcher/launch` with `contentId` / `contentTarget` for YouTube and Netflix | Medium | Deep-link support varies by webOS version; Home Assistant has open issues on some models. Wake-on-LAN for power on. |
| Samsung Tizen TV | WebSocket remote API on 8002 (token prompt on TV): keys, app launch by app id | DIAL for YouTube (`v=` parameter); Netflix launch without content on most firmware | Medium for control, Low for content deep links | App ids differ by model year. |
| DLNA / UPnP media renderers (many TVs, AV receivers, Wi-Fi speakers) | SSDP discovery, AVTransport SOAP | `SetAVTransportURI` with a direct stream URL | High | Jellyfin can serve direct-stream URLs; YouTube cannot. |
| Sonos | UPnP with Sonos extensions; SSDP discovery | Direct URL playback, grouping, volume | High | Well-documented by SoCo / node-sonos. |
| AirPlay speakers (HomePod, AirPlay 2 receivers) | RAOP / AirPlay 2 | Audio stream from the server | Medium | Open implementations exist (pyatv, owntone) but none in Java; deferred. |
| Bluetooth speakers | Host BlueZ over D-Bus | Server decodes the stream and plays through the host audio stack (PipeWire or bluealsa) to the A2DP sink | Low–Medium | Requires host D-Bus socket mount, host Bluetooth adapter, and a running audio stack on the host. Works on a Raspberry Pi style host; awkward on a NAS. |

### 4.2 Content sources

| Source | Browse / recommend | Play on device | Confidence | Notes |
|---|---|---|---|---|
| Jellyfin | Full REST API with user token: Resume, NextUp, Latest, Search, libraries | (a) Session remote control `/Sessions/{id}/Playing?playCommand=PlayNow` if the Jellyfin client on the target is running and reports `SupportsMediaControl`; (b) Cast to the Jellyfin receiver with server URL and access token in `customData`; (c) DLNA/DMR with a direct-stream URL | High | Richest source. All three routes are open. |
| YouTube | Data API v3 with OAuth: subscriptions → channel upload playlists, Watch Later, playlists, search. No official "home recommendations" endpoint since the `home` activity feed was removed. | Android TV: app-link `https://www.youtube.com/watch?v=ID`; webOS: launch with content target; Tizen: DIAL `YouTube` with `v=`; Cast: YouTube receiver through the Lounge API | High for launch on Android TV; Medium for Cast (Lounge API is reverse-engineered and breaks periodically) | 10 000 quota units per day; subscriptions feed is affordable if cached and refreshed hourly. Search costs 100 units per call. |
| Netflix | No API. Metadata from TMDB (search, trending, watch providers) and the user's pinned titles | Android TV: app-link `https://www.netflix.com/title/{id}`; webOS: launch `netflix` with contentId; Tizen: app launch only | Medium for launch, none for personalised feed | TMDB exposes provider availability, not Netflix ids; the Netflix id comes from the Netflix URL the user pastes when pinning, or from a TMDB external-id lookup where available. |
| Amazon Prime Video | No API. Same TMDB route | Android TV: `https://app.primevideo.com/detail?gti=…` or `https://www.amazon.de/gp/video/detail/{ASIN}`; the app handles both | Medium | The GTI/ASIN must come from a pasted Prime URL. |
| DAZN | No public API. Options: (1) user-supplied ICS calendar of competitions; (2) a public sports fixture API (TheSportsDB free tier) filtered to the user's competitions; (3) scraping DAZN's schedule page, fragile and against ToS | Android TV: launch the DAZN app via `https://www.dazn.com/…` app link; a per-event deep link exists only if the user pastes it | Low for schedule accuracy vs DAZN's own rights, Medium for launching | Ship (1) and (2). The "on DAZN" flag is user-configured per competition. |

### 4.3 Conclusions

- **Everything the user asked for is buildable**, but with two honest limits:
  Netflix/Prime/DAZN work as *launchers with third-party metadata*, and
  Bluetooth speakers need host-level plumbing that not every CasaOS box has.
- **Android TV app links plus Google Cast cover most of the household** and are
  the cheapest two routes. They come first.
- **Jellyfin is the flagship source** because it is the only one where browse,
  recommend, and every play route are fully open.

## 5. Domain model

### 5.1 Devices and capabilities

```text
Device { id, name, kind, addresses, adapterIds[], capabilities: Set<Capability>, state }

Capability =
  REMOTE_KEYS        // D-pad, media keys (Remote v2, webOS, Tizen)
  POWER              // on/off, standby; may need Wake-on-LAN
  VOLUME             // absolute or relative volume, mute
  APP_LINK           // open a URI in the app registered for it
  CAST_RECEIVER      // run a Cast receiver app with a LOAD payload
  MEDIA_RENDERER     // play a direct URL (DLNA AVTransport, Sonos, Cast DMR)
  JELLYFIN_CLIENT    // a controllable Jellyfin session may be running here
  LOCAL_AUDIO_SINK   // server-side audio output (Bluetooth)
```

One physical device may be backed by several **adapters**: a Shield is both an
Android TV (Remote v2) and a Cast receiver. Discovery merges them by IP and
mDNS instance name into one `Device` with the union of capabilities. The user
can split or merge devices manually on the setup page when heuristics fail.

Adapters implement a narrow interface and nothing else:

```text
DeviceAdapter {
  kind(); discover(); connect(device); disconnect(device);
  capabilities(device); state(device): Flow<DeviceState>;
  execute(device, Action): Result
}
```

### 5.2 Content and playable references

```text
ContentItem { id, sourceId, kind (MOVIE|EPISODE|VIDEO|LIVE_EVENT|TRACK|APP),
              title, subtitle, artwork, progress?, startsAt?, endsAt?,
              playables: List<PlayableRef> }

PlayableRef =
  AppLink(uri, preferredPackages[])              // https://www.youtube.com/watch?v=..
  CastLoad(receiverAppId, payload)               // Jellyfin receiver, DMR, YouTube
  JellyfinItem(serverId, itemId, resumeTicks)    // resolved at play time to a session command, a CastLoad, or a StreamUrl
  StreamUrl(url, mime, headers?)                 // DLNA, Sonos, DMR, local audio
  WebOsLaunch(appId, contentId?), TizenLaunch(appId, dialParams?)
```

A source returns items with every reference it can construct. The planner,
not the source, decides which is used.

### 5.3 Playback planner

```text
plan(item, device) -> Route | Unroutable(reason)

Preference order (first match wins):
 1. JELLYFIN_CLIENT session on device is live         -> session PlayNow
 2. APP_LINK and item has AppLink for a package on it -> app-link launch
 3. CAST_RECEIVER and item has CastLoad               -> cast
 4. MEDIA_RENDERER and item has StreamUrl             -> DLNA / Sonos / DMR
 5. LOCAL_AUDIO_SINK and item is audio with StreamUrl -> server player
```

Rules:

- Order 1 before 2 because the native app already open resumes with the
  user's own profile and subtitles; relaunching it through an app link can
  drop the resume position on some apps.
- The route and its reason are shown in the UI *before* the user confirms
  ("Play on Shield via YouTube app"). Failure toasts name the route that failed
  and the next one, if any, so the user can retry differently.
- Volume and power actions go straight to the adapter with the matching
  capability; they do not go through the planner.

## 6. Dashboard

### 6.1 Layout

- **Device strip (top, sticky):** one chip per device with name, power/online
  badge, current app or now-playing title, volume. Tapping selects the *target*
  device; a long-press or the chevron opens that device's **remote drawer**
  (the existing remote UI, now per device, plus adapter-specific extras such as
  inputs on a TV or grouping on Sonos).
- **Rails (main area):** horizontal content rails from every configured source,
  in a user-ordered list: *Continue watching* (Jellyfin), *Next up*, *Live
  now / Today* (sport), *New from your subscriptions* (YouTube), *Pinned*,
  *Latest in library*, *Trending on your services* (TMDB filtered by
  configured providers). A rail that fails to load shows a compact error with
  a retry, never an empty gap.
- **Play sheet:** tapping an item shows title, artwork, the planned route for
  the selected device, and a device switcher. One tap plays. Items with only an
  APP route say "Opens in the Netflix app".
- **Search:** one box across Jellyfin, YouTube (quota-aware, debounced), and
  TMDB.
- **Setup:** devices (discovered, paired, manual), sources (credentials, rail
  toggles, refresh intervals), locale/providers, login password.

### 6.2 Live state

The existing SSE stream becomes multi-device: each event carries a device id.
The device strip and the open drawer subscribe to it. Now-playing information
comes from Cast media status, Jellyfin session polling, and Remote v2
foreground app, whichever the adapter offers.

### 6.3 Clients

Phone-first responsive layout, same PWA rules as the vNext spec §5.6. The
dashboard is the new `/`; the classic remote stays reachable at `/remote/{deviceId}`.

## 7. Architecture

The Spring Boot monolith stays. Packages become feature modules with the
existing `shield` package retired into an adapter:

```text
dev.andre.homecontrol
  core/         Device, Capability, ContentItem, PlayableRef, Route, planner
  adapters/
    androidtv/  existing protocol/device/discovery code (Remote v2)
    cast/       CASTV2 sender, DMR + Jellyfin + YouTube receiver controllers
    webos/      SSAP WebSocket client, pairing key storage
    tizen/      Samsung WebSocket remote, DIAL client
    upnp/       SSDP discovery, AVTransport (DLNA), Sonos extensions
    bluetooth/  BlueZ D-Bus + local player (last phase, optional module)
  sources/
    jellyfin/   API client, rails, session lookup, stream URL builder
    youtube/    OAuth device flow, subscriptions cache, search
    tmdb/       search, trending, watch providers, external-id lookup
    sports/     ICS + TheSportsDB fixtures, competition→provider mapping
    pinned/     user-managed shortcuts (any URL + target service)
  playback/     PlaybackService: plan → execute → report
  web/          dashboard, setup, remote drawer, SSE, JSON endpoints
  storage/      DataDirectory (existing), JSON registries, secret store
```

Each adapter and source is a Spring `@ConditionalOnProperty` module so a
deployment can turn off what it does not own. Cross-cutting rules:

- **Only adapters speak device protocols; only sources speak content APIs.**
  The planner and web layer see the domain model only.
- **Discovery is one scheduler** fanning out to mDNS (`_androidtvremote2._tcp`,
  `_googlecast._tcp`), SSDP (DLNA, Sonos, Samsung, webOS), and adapter-specific
  probes, then merging into the device registry.
- **Rails are cached** per source with a TTL and refreshed by a scheduler;
  page loads never block on an upstream API. SSE pushes "rail updated" events.
- **Virtual threads** for the many small blocking I/O clients (Java 25 is
  already the toolchain).

### 7.1 Third-party dependencies (new)

| Need | Choice | Why |
|---|---|---|
| Cast sender | `chromecast-java-api-v2` (Maven Central) or the DigitalMediaServer fork; fallback in-house minimal sender | Only maintained Java options; protocol is small. |
| WebSocket client (webOS, Tizen) | Spring's `StandardWebSocketClient` / Java `HttpClient.newWebSocketBuilder()` | Already available; no new dependency. |
| UPnP / SSDP | Plain HTTP + hand-written SOAP envelopes, or `jupnp` if grouping/eventing is needed | AVTransport is a handful of calls; jupnp adds weight. |
| Jellyfin, TMDB, YouTube, TheSportsDB | Spring `RestClient` with small typed clients | Keep it boring. |
| OAuth (YouTube) | Google's device authorization flow, implemented directly | Device flow suits a headless LAN box; no redirect URL needed. |
| Bluetooth | `bluez-dbus` (hypfvieh) + `mpv`/`ffplay` subprocess on the host audio stack | Only if the phase ships; isolated module. |

## 8. Data and storage

Stays file-based in `/data`, one JSON file per registry, atomic writes as
today:

- `devices.json` — devices, adapters, manual merges, per-adapter pairing data
  (Remote v2 cert alias, webOS client key, Tizen token).
- `sources.json` — configured sources and rail preferences.
- `secrets.json` — API keys and OAuth refresh tokens, encrypted with a key
  derived from `HOME_CONTROL_SECRET` (env) or a generated file in `/data`.
- `pinned.json`, `sports.json` — user content.
- `cache/` — rail snapshots and artwork cache, safe to delete.

Migration: the existing `devices.json` entries become one Android TV device
with the `androidtv` adapter; the keystore stays where it is.

## 9. Security

Once any source or secret is configured, the app requires a login: one
password stored as an Argon2 hash, session cookie, rate-limited. Device-only
deployments may stay password-less to preserve today's behavior. HTTPS remains
the reverse proxy's job, as documented today. OAuth tokens never leave the
server; the browser only sees rails.

## 10. Roadmap and decomposition

Each row is one sub-project with its own spec → plan → implementation cycle,
independently releasable, in this order:

| # | Sub-project | Delivers | Depends on |
|---|---|---|---|
| A | **Multi-device core** | Domain model, adapter interface, device registry v2 with migration, multi-device SSE, device strip, per-device remote drawer; Android TV adapter regains `APP_LINK` | — |
| B | **Google Cast adapter** | Discovery, connect, volume/stop, Default Media Receiver, now-playing | A |
| C | **Jellyfin source + playback** | Rails (Resume, NextUp, Latest), search, session PlayNow, Cast receiver load, direct-stream URL; login requirement + secret store | A, B |
| D | **Dashboard shell** | Rails layout, play sheet, planner UI, setup pages for sources, PWA polish | C |
| E | **YouTube source** | OAuth device flow, subscriptions and playlists rails, search; app-link route on Android TV; Cast via Lounge as best effort | D |
| F | **Smart TV adapters** | LG webOS, Samsung Tizen: keys, power (WoL), volume, app launch, YouTube/Netflix deep links where supported | A |
| G | **Streaming launchers** | TMDB source, pinned shortcuts, Netflix / Prime Video app links, provider-aware trending rail | D |
| H | **Sports and DAZN** | ICS + TheSportsDB fixtures, competition→provider mapping, live/today rail, DAZN launch | G |
| I | **Wi-Fi speakers** | UPnP/DLNA renderers and Sonos: discovery, play StreamUrl, volume, grouping | B, C |
| J | **Bluetooth speakers** | Host BlueZ pairing UI, server-side player, `LOCAL_AUDIO_SINK`; documented host requirements | I |

The vNext v0.4 (touch/PWA) and v0.5 (text input) work folds into A and D
rather than being scheduled separately.

Sub-project A is the first to brainstorm in detail; its spec must nail the
adapter interface because everything else conforms to it.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| YouTube Cast via the Lounge API breaks after a YouTube change | YouTube cast route fails | App-link route on Android TV is primary; Cast is marked best effort and can be disabled per device. |
| Deep-link behavior differs across TV firmware | A title opens the app but not the content | Planner surfaces the route; the setup page has a per-device "test deep link" button; fallback to app-only launch with a toast. |
| YouTube quota exhaustion | Rails go stale | Hourly cached subscriptions, search debounced and capped per day, quota use displayed in setup. |
| TMDB has no Netflix/Prime ids | Trending items cannot deep link | Show "Open Netflix" app launch and let the user pin a pasted URL to upgrade the item. |
| DAZN schedule inaccuracy | Wrong "on DAZN" flag | Flag is a user mapping per competition; UI labels it as such. |
| Bluetooth host plumbing not available | Phase J unusable on some hosts | Optional module, documented host checklist, no impact on other features. |
| Cast library abandonment | Adapter rots | Interface is thin; an in-house sender is a bounded fallback (protobuf + TLS already in the codebase). |
| Secrets on a LAN box | Token theft | Mandatory login when secrets exist, encrypted at rest, reverse proxy guidance for exposure. |

## 12. Testing strategy

- **Adapters:** fake servers in tests as today (`FakeRemoteServer`); add a
  fake Cast receiver, a fake SSAP server, a fake UPnP renderer. Every adapter
  has connect / disconnect / execute / state tests against its fake.
- **Sources:** recorded JSON fixtures for each API; contract tests that a
  fixture produces the expected `ContentItem`s and `PlayableRef`s.
- **Planner:** pure unit tests over capability × playable matrices, including
  every `Unroutable` reason.
- **Web:** MockMvc tests for endpoints; Playwright (test-only) for the play
  sheet, device switching, rail failure states, and login gating.
- **Manual acceptance per release:** a checklist per real device model the
  household owns, recorded in `docs/superpowers/reviews/`.

## 13. Open questions for the user

1. Which TVs and speakers exactly are in the household (brands, models)? This
   sets the order of F, I, and J.
2. Is Jellyfin already running, and on which URL? Does the Shield run the
   official Jellyfin Android TV app?
3. Is a YouTube Google Cloud project acceptable (needed for OAuth and quota),
   or should YouTube start as pinned links only?
4. Is the Bluetooth host a Raspberry Pi style machine with a usable adapter,
   or a NAS? The answer may drop phase J entirely.
5. Should the classic remote remain the landing page until the dashboard has
   at least one configured source?
