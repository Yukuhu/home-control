# Wi-Fi Speakers (Sub-project I) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Play music on the household's Wi-Fi speakers: find UPnP/DLNA media renderers and Sonos players through the shared SSDP listener, control them (play a direct stream URL, pause, resume, stop, volume, mute), group and ungroup Sonos rooms from the device drawer, route `StreamUrl` items to any `MEDIA_RENDERER` device as rung 4 of the planner, add Jellyfin music rails whose tracks resolve to direct audio streams, and show now-playing for every renderer.

**Architecture:** Two adapter modules on the A/B/F contract. `adapters/upnp` (switch `home-control.upnp.enabled`) is a generic AVTransport/RenderingControl/ConnectionManager client: `UpnpDiscovery` watches `urn:schemas-upnp-org:device:MediaRenderer:1` on F1's `discovery/ssdp/SsdpDiscovery`, `UpnpAdapter` is pairing-free (B's `settingsFor`), `UpnpSession` resolves the device description on every (re)connect, polls transport, position and volume through a shared `ReconnectingPoller`, and executes commands with hand-written SOAP. The protocol pieces live in `adapters/upnp/protocol` (secure XML, SOAP envelopes and faults, DIDL-Lite, protocol-info matching, UPnP time, renderer commands) and are reused by `adapters/sonos` (switch `home-control.sonos.enabled`), which discovers a whole household from one player's `ZoneGroupTopology#GetZoneGroupState`, sends transport commands to the group coordinator, and implements grouping with `x-rincon:` URIs. Core gains `Action.PlayMedia/Pause/Resume/JoinGroup/LeaveGroup`, `Action.acceptedBy` (so `Stop` reaches renderers), brand-free grouping types (`SpeakerTopology`, `GroupListing`), `Route.Render` and `MediaRendererStrategy` (after B's Cast strategies, spec §5.3 rung 4). Now-playing comes from polled AVTransport state; GENA eventing is not used (see Decisions).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle through `.superpowers/gradle.sh`, Thymeleaf, htmx, vanilla ES modules, `java.net.http.HttpClient` (HTTP/1.1), JDK DOM parser (`javax.xml.parsers`, DTDs disallowed), JDK `com.sun.net.httpserver.HttpServer` for fakes, JUnit 5, AssertJ, Mockito, Awaitility. **No new dependency.** Checked on Maven Central 2026-09-16 and rejected: `org.jupnp:org.jupnp:3.0.5` (last published 2026-08-12) — it brings its own SSDP stack, registry and GENA callback HTTP server, duplicating F1's shared listener (spec §7 "one discovery").

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §4.1 (DLNA/UPnP and Sonos rows), §5.1 (`MEDIA_RENDERER`, `VOLUME`, merge by IP), §5.2 (`StreamUrl`), §5.3 (rung 4: `MEDIA_RENDERER` + `StreamUrl`), §6.1 (grouping on Sonos in the drawer), §6.2 (now playing), §7 (`adapters/upnp`, one discovery scheduler, `@ConditionalOnProperty` modules), §7.1 (plain HTTP + hand-written SOAP unless grouping/eventing needs jupnp), §12 (fake UPnP renderer), §13 (household facts unknown). Roadmap `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md`, section I (I1–I4, GitHub epic #13, tasks #70–#73). Contracts this plan builds on: A `docs/superpowers/plans/2026-09-16-multi-device-core.md`, B `docs/superpowers/plans/2026-09-16-google-cast-adapter.md`, D `docs/superpowers/plans/2026-09-16-dashboard-shell.md` (drawer, play sheet using `GET /devices/{id}/route`), C `docs/superpowers/plans/2026-09-16-jellyfin-source.md` (C3 rails, C6 `JellyfinStreams`, C7 `PlayableResolver`/`PlaybackService`), F `docs/superpowers/plans/2026-09-16-smart-tv-adapters.md` (F1 `discovery/ssdp`, `DeviceManager.attach`, `DeviceState.sameIgnoringTime`, `InputListing` pattern). Execution order is A, B, C, D, F, I: everything those plans produce is assumed to exist.

**Protocol sources consulted (for the wire formats below):** UPnP Forum *AVTransport:1*, *RenderingControl:1*, *ConnectionManager:1* service templates (argument names, `InstanceID` 0, `Channel` `Master`, error codes 701/705/714/716); UPnP Device Architecture 1.1 §3 (SOAP envelope, `SOAPACTION` header, `UPnPError` fault); DLNA guidelines as implemented by MiniDLNA (`DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000`); SoCo `soco/core.py` (`join` → `SetAVTransportURI x-rincon:<uuid>`, `unjoin` → `BecomeCoordinatorOfStandaloneGroup`, `play_uri` with `x-rincon-mp3radio://`), `soco/zonegroupstate.py` (both `ZoneGroupState` shapes, `Invisible` members, `Satellite` children), `soco/services.py` (fixed Sonos control paths on port 1400).

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode. UPnP renderers and Sonos are pairing-free.
- Commands are ephemeral: a command or play request that cannot be sent now fails now with a reason. Nothing is queued or retried later.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `device`, `playback`, `web`, `security` and `sources` must not import anything under `adapters.upnp`, `adapters.sonos` or `discovery.ssdp`.
- Route by capability, not by brand. The planner never looks at `DeviceKind`.
- Persistent state stays in `/data` as JSON written atomically; `devices.json` and `keystore.p12` keep working without re-pairing.
- Every adapter is a Spring `@ConditionalOnProperty` module that can be switched off. Every adapter has a fake server in tests; sources have recorded JSON fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), each ending with the trailer lines required by `.superpowers/sdd/implementer-common.md`.

Build and tooling:

- There is no local JDK. "Build" means `.superpowers/gradle.sh build`; focused tests `.superpowers/gradle.sh test --tests '<pattern>'`. Failing test detail: grep `<failure` in `build/test-results/test/*.xml`.
- Spring Boot 4.1.1: MockMvc test auto-configuration is `org.springframework.boot.webmvc.test.autoconfigure`; `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- A, B, C, D and F are the contract. Where real code under `src/main/java/dev/andre/homecontrol` differs from their plans' listings (field names, helper names, where D moved the drawer markup — D's plan `2026-09-16-dashboard-shell.md` puts the A/B remote sections into `<aside id="remote-drawer">`, so this plan's drawer sections go inside that aside next to B's `section.cast`), adapt the edit to the real code, keep the behaviour this plan specifies, and say so in the task report.

Epic constraints:

- `adapters.sonos` may import `adapters.upnp.protocol` but never `adapters.upnp` itself, so Sonos works with `home-control.upnp.enabled=false`. `adapters.upnp.protocol` may import `core` and `discovery.ssdp` but no Spring type.
- Every XML document a device sends (descriptions, SCPDs, SOAP answers, DIDL-Lite, zone group state) is parsed only through `UpnpXml.parse` or F1's `DeviceDescriptions.parse`: DTDs disallowed, external entities and XInclude off. Never search XML with string functions or regular expressions.
- Stream URLs may carry a Jellyfin `ApiKey` (C6). `Action.PlayMedia`, `Route.Render` and `SoapRequest` override `toString()` without the query; a SOAP envelope, a `CurrentURI` or a `TrackURI` is never logged, never put in an exception message and never sent to the browser. Now-playing titles come from metadata, never from a URL.
- Device HTTP clients are created by `SoapClient.httpClient(Duration)`: HTTP/1.1 only (no `Upgrade: h2c`), redirects never followed, connect timeout; every request has a request timeout; SOAP answers are capped at 4 MiB.
- A control, SCPD or description URL on a different host than the description location is refused (the session never posts a stream URL to another host).
- `DeviceState` is created only through `DeviceState.initial()`/`unpaired()` and its `with…` methods; sessions publish only when `sameIgnoringTime` (F) reports a change, and publish the first state on `start()`.
- Commands run on the caller's thread and then ask the session's poll loop for an immediate poll; state is written only by the poll loop.
- Error mapping for commands (B's rule): the device could not be reached → `DeviceOfflineException` (409); the device answered with a UPnP fault, an unreadable answer or no answer in time → `ActionFailedException` (502); the device cannot do it at all (no such feature, a format its sink list excludes) → `UnsupportedActionException` (422, and `DeviceManager` falls through to the next adapter).
- Unknown device id → HTTP 404 everywhere in the web layer.
- Real speakers cannot be operated by agents: the acceptance checklist marks every item "Pending — requires real hardware" and is never marked passed.

## Decisions

- Decision: no jupnp; hand-written SOAP over `java.net.http` on top of F1's `SsdpDiscovery` — AVTransport, RenderingControl, ConnectionManager and ZoneGroupTopology need eleven actions, and jupnp 3.0.5 would start a second SSDP stack, a registry and a GENA callback server next to F1's listener (spec §7 "one discovery") — cost if wrong: ~900 lines of protocol code we own; jupnp can replace `adapters/upnp/protocol` behind the same sessions.
- Decision: now-playing is polled (`GetTransportInfo` + `GetPositionInfo` every `poll-interval-seconds`=2 while a transport is active, `idle-poll-interval-seconds`=10 otherwise), no GENA eventing — GENA needs a callback HTTP endpoint reachable from every speaker (host port, firewall), subscription renewal, `LastChange` parsing and per-vendor quirks, while B already polls Cast media status for position; roadmap I3's "AVTransport events" are met by state-change events on SSE derived from polling — cost if wrong: up to 2 s (10 s idle) until a change made elsewhere shows; GENA can later feed the same `publish` path.
- Decision: two modules, `home-control.upnp.enabled` and `home-control.sonos.enabled`, both default `true` — both are pairing-free and only listen until the user adds a speaker or B's `onDiscovered` merges a renderer into an already registered device at the same host (so a registered LG/Samsung TV gains `MEDIA_RENDERER`) — cost if wrong: a merged TV is polled every 10 s; set `HOME_CONTROL_UPNP_ENABLED=false`.
- Decision: generic UPnP ignores Sonos players (USN `uuid:RINCON_…` or manufacturer starting with `Sonos`) while the Sonos module is enabled — Sonos also announces an embedded `MediaRenderer:1` and would otherwise appear twice — cost if wrong: switching Sonos off exposes players as plain renderers, which is intended.
- Decision: UPnP settings store `udn`, `location` and `model`; the session fetches the description on every (re)connect from the latest SSDP location announced for that UDN, else the stored one — many renderers pick a new HTTP port at boot — cost if wrong: one GET per reconnect.
- Decision: control, SCPD and description URLs must share the host of the description location — a spoofed description must not make the server POST credential-bearing stream URLs to another host — cost if wrong: a renderer whose services live on a second address is unusable.
- Decision: `SoapClient.httpClient` forces HTTP/1.1, and F1's public `SsdpDiscovery(SsdpProperties)` constructor is changed to build its description client with HTTP/1.1 too — the JDK default (`HTTP_2`) sends `Upgrade: h2c` on plain HTTP, which embedded UPnP servers reject or mishandle — cost if wrong: none.
- Decision: before `SetAVTransportURI`, the stream's MIME type is matched against the renderer's `ConnectionManager#GetProtocolInfo` sink (http-get entries, `*`, and aliases such as `audio/flac`↔`audio/x-flac`); no match → `UnsupportedActionException("<name> cannot play <mime>")`; an unknown sink (no ConnectionManager or a fault) accepts everything — a speaker told to play a video fails immediately with a reason instead of silently — cost if wrong: a renderer that under-reports its sink refuses a playable file.
- Decision: DIDL-Lite `res@protocolInfo` is `http-get:*:<renderer's spelling of the type>:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000` for generic renderers (MiniDLNA's values; picky DLNA TVs refuse `*`) and `http-get:*:<type>:*` for Sonos (what SoCo sends) — cost if wrong: one constant per module.
- Decision: `SetAVTransportURI` answered with fault 701 or 705 is retried once after a `Stop` — several renderers only accept a new URI when stopped — cost if wrong: one extra round trip on those faults.
- Decision: absolute volume is a percentage mapped through the RenderingControl SCPD's `Volume` `allowedValueRange` maximum (default 100); Sonos is 0–100 — matches B's `Action.SetVolume` percentage — cost if wrong: renderers with a non-zero minimum are slightly off.
- Decision: new actions `Action.PlayMedia(URI url, String mimeType, String title, String subtitle)`, `Action.Pause()`, `Action.Resume()` require `MEDIA_RENDERER`; B's `Action.Stop` keeps `requires() == CAST_RECEIVER` and gains `acceptedBy(Set<Capability>)` true for `CAST_RECEIVER` or `MEDIA_RENDERER`, which `DeviceManager.execute` uses instead of `contains(requires())` — B's decision anticipated widening `Stop`, and this keeps B's tests and Cast routing unchanged — cost if wrong: Cast pause/resume need the same widening later.
- Decision: Sonos control paths are the fixed ones (`/MediaRenderer/AVTransport/Control`, `/MediaRenderer/RenderingControl/Control`, `/MediaRenderer/ConnectionManager/Control`, `/ZoneGroupTopology/Control`) on the host and port of the player's location (1400) — stable across S1/S2 firmware and what SoCo and node-sonos use; saves a description fetch per player — cost if wrong: a firmware path change breaks one constant.
- Decision: Sonos discovery reads `GetZoneGroupState` from any announcing player (at most once per `topology-interval-seconds`=30 per household) and lists every *visible* member by room name (`ZoneName`); bonded stereo partners, surrounds and subs (`Invisible="1"`, `Satellite`) are not devices — one chip per room, as in the Sonos app — cost if wrong: one SOAP call per household per 30 s.
- Decision: Sonos transport commands (`SetAVTransportURI`, `Play`, `Pause`, `Stop`, `GetTransportInfo`, `GetPositionInfo`) go to the group coordinator; volume and mute stay on the speaker itself (RenderingControl, not GroupRenderingControl) — the chip is one room; playing on a grouped room plays on its group, as Sonos does — cost if wrong: a group-volume slider is added later through `GroupRenderingControl`.
- Decision: grouping uses `SetAVTransportURI(x-rincon:<coordinator uuid>)` on the joining player and `BecomeCoordinatorOfStandaloneGroup` to leave (SoCo's `join`/`unjoin`), not `GroupManagement#AddMember`, which needs the member's boot sequence and is internal to Sonos controllers — cost if wrong: none known.
- Decision: grouping is brand-free in core — `Action.JoinGroup(String memberId)` / `Action.LeaveGroup()` (require `MEDIA_RENDERER`) plus `core.GroupListing` implemented by handles that know a topology, mirroring F's `SelectInput` + `InputListing`; generic renderers reject the actions as unsupported. The drawer lists "Join <group>" per other group and "Leave group"; the endpoints answer `204` with `HX-Refresh: true` — cost if wrong: another grouping protocol (AirPlay) maps onto the same types.
- Decision: `x-rincon-mp3radio://` is used only for `http` + `audio/mpeg` URLs whose last path segment has no extension (Icecast/Shoutcast style); files, including Jellyfin's `stream.<container>` URLs, are sent as plain `http` — Sonos range-requests files but needs the radio scheme for endless MP3 streams — cost if wrong: a misdetected stream plays as radio (no seek bar).
- Decision: `Route.Render(URI url, String mimeType, String title, String subtitle)` with `describe()` = `Stream directly to this device (DLNA/UPnP)` is produced by `MediaRendererStrategy`, placed after B's `CastStreamStrategy` (spec §5.3 rung 4); title and subtitle go into DIDL-Lite; artwork is omitted because item artwork is a relative URL behind the login gate — cost if wrong: speakers with displays show no cover.
- Decision: Jellyfin music rails are `music-recent` "Recently played music" (`GET /Items`, `includeItemTypes=Audio`, `recursive=true`, `filters=IsPlayed`, `sortBy=DatePlayed`, `sortOrder=Descending`) and `music-latest` "Latest music" (`GET /Items/Latest`, `includeItemTypes=Audio`, `groupItems=false`), tracks only, appended after C's three rails — albums and playlists need a queue route, which no sub-project has yet — cost if wrong: D4's rail toggles hide them.
- Decision: music streams use C6's `/Audio/{id}/stream.{container}?static=true&mediaSourceId=…&ApiKey=…` unchanged, not `/Audio/{id}/universal` — `universal` negotiates transcoding and may redirect to HLS, which DLNA renderers cannot follow; C7's resolver already builds the stream for `MEDIA_RENDERER` devices — cost if wrong: formats outside C6's profile (mp3, aac, flac, ogg) get "no format this device can play directly".
- Decision: now-playing title = DIDL `dc:title` from `TrackMetaData`, else the title this server sent for that exact `TrackURI`, else `Unknown title`; states `PLAYING`→`PLAYING`, `PAUSED_PLAYBACK`/`PAUSED_RECORDING`→`PAUSED`, `TRANSITIONING`→`BUFFERING`, anything else clears now-playing (B's rule for `IDLE`) — cost if wrong: a wording change.
- Decision: one reusable `ReconnectingPoller` (single virtual-thread scheduler per session: connect with doubling backoff, poll at a link-chosen delay, `pollNow`, `reconnectNow`) serves both sessions; an SSDP announcement of a known UDN/uuid calls `reconnectNow` (like F's webOS) — cost if wrong: none.
- Decision: tests with two Sonos players bind the fakes to `127.0.0.2` and `127.0.0.3` because B treats one adapter per host as registered — Linux routes all of `127.0.0.0/8` to loopback (CI and the Docker build are Linux) — cost if wrong: the two-player tests need a non-Linux alias.
- Decision: two renderers of the same adapter on one host (e.g. two software renderers on a PC) are not supported — B's `isRegistered` matches adapter + host — cost if wrong: the second shows as "already added".
- Decision: the fake renderer is written complete in Task 1 (with a `Layout` so Task 2 extends it into a Sonos player and household), Task 4 adds failure hooks, end-to-end tests over SSDP and Jellyfin, and the checklist — every task stays test-first — cost if wrong: none.

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`. Paths are relative to those roots unless they start with `src/`, `docs/` or are top-level files.

### Files to create

- `core/RedactedUris.java` — prints a URI without its query (Task 1).
- `core/GroupMember.java`, `core/SpeakerGroup.java`, `core/SpeakerTopology.java`, `core/GroupListing.java` — brand-free speaker grouping (Task 2).
- `core/playback/MediaRendererStrategy.java` — planner rung 4 (Task 3).
- `adapters/upnp/protocol/UpnpXml.java` — secure DOM parsing, escaping, element helpers.
- `adapters/upnp/protocol/SoapRequest.java`, `SoapClient.java`, `SoapFault.java`, `SoapTimeoutException.java` — SOAP over HTTP/1.1.
- `adapters/upnp/protocol/UpnpActions.java` — normative AVTransport/RenderingControl/ConnectionManager requests.
- `adapters/upnp/protocol/ServiceEndpoint.java`, `TransportInfo.java`, `VolumeReading.java`, `VolumeRange.java`, `ProtocolInfo.java`, `DidlLite.java`, `RendererCommands.java`, `ReconnectingPoller.java` (Task 1); `UpnpTime.java`, `PositionInfo.java`, `PlayedItem.java`, `NowPlayings.java` (Task 3).
- `adapters/upnp/UpnpProperties.java`, `UpnpSettings.java`, `UpnpDiscovery.java`, `UpnpSession.java`, `UpnpAdapter.java`, `UpnpConfiguration.java`.
- `adapters/sonos/protocol/SonosEndpoints.java`, `SonosActions.java`, `SonosUris.java`, `ZoneGroupState.java`.
- `adapters/sonos/SonosProperties.java`, `SonosSettings.java`, `SonosDiscovery.java`, `SonosSession.java`, `SonosAdapter.java`, `SonosConfiguration.java`.
- Tests: `core/SpeakerTopologyTest.java`; `core/playback/MediaRendererStrategyTest.java`; `adapters/upnp/protocol/UpnpXmlTest.java`, `SoapClientTest.java`, `DidlLiteTest.java`, `ProtocolInfoTest.java`, `VolumeRangeTest.java`, `RendererCommandsTest.java`, `ReconnectingPollerTest.java`, `UpnpTimeTest.java`, `NowPlayingsTest.java`; `adapters/upnp/FakeUpnpRenderer.java`, `UpnpDiscoveryTest.java`, `UpnpSessionTest.java`, `UpnpAdapterTest.java`, `UpnpModuleSwitchTest.java`; `adapters/sonos/FakeSonosHousehold.java`, `FakeSonosPlayer.java`, `adapters/sonos/protocol/ZoneGroupStateTest.java`, `SonosUrisTest.java`, `adapters/sonos/SonosDiscoveryTest.java`, `SonosSessionTest.java`, `SonosAdapterTest.java`, `SonosModuleSwitchTest.java`; `web/SpeakersEndToEndTest.java`, `web/SpeakerJellyfinEndToEndTest.java`.
- Fixtures: `src/test/resources/fixtures/ssdp/renderer-search-response.txt`; `src/test/resources/fixtures/upnp/renderer-description.xml`, `rendering-control-scpd.xml`, `set-av-transport-uri-envelope.xml`, `didl-track.xml`, `position-metadata.xml`; `src/test/resources/fixtures/sonos/zone-group-state.xml`, `zone-group-state-legacy.xml`; `src/test/resources/fixtures/jellyfin/music-recent.json`, `music-latest.json`, `item-track.json`.
- `docs/superpowers/reviews/2026-09-16-wifi-speakers-acceptance.md` — manual checklist, every item pending.

### Files to modify

- `core/Action.java` — `acceptedBy`, `PlayMedia`, `Pause`, `Resume`, widened `Stop` (Task 1); `JoinGroup`, `LeaveGroup` (Task 2).
- `device/DeviceManager.java` — `execute` uses `acceptedBy` (Task 1); `speakerTopology(String)` (Task 2).
- `discovery/ssdp/SsdpDiscovery.java` — HTTP/1.1 description client (Task 1).
- Every exhaustive `switch` over `Action` — `adapters/androidtv/AndroidTvSession.java`, `adapters/cast/CastSession.java`, `adapters/webos/WebOsSession.java`, `adapters/tizen/TizenSession.java` (Tasks 1, 2) and `adapters/upnp/UpnpSession.java` (Task 2); find them with `grep -rln "case Action.PressKey" src/main/java`.
- `core/playback/Route.java` — `Render`; `core/playback/PlaybackPlanner.java` — stream explanation; `playback/PlaybackService.java` — execute `Render`; `HomeControlConfiguration.java` — strategy order (Task 3).
- `adapters/upnp/protocol/DidlLite.java`, `RendererCommands.java`, `adapters/upnp/UpnpSession.java`, `adapters/sonos/SonosSession.java` — now-playing (Task 3).
- `adapters/upnp/UpnpDiscovery.java`, `UpnpConfiguration.java` — ignore Sonos players (Task 2).
- `sources/jellyfin/JellyfinContentSource.java` — music rails (Task 3).
- `web/DeviceController.java` — pause, resume (Task 1), group join/leave (Task 2).
- `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css` — renderer controls (Task 1), grouping (Task 2), link form for renderers (Task 3).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.upnp.*` (Task 1), `home-control.sonos.*` (Task 2).
- `README.md` — Wi-Fi speakers section and configuration rows (Task 4).
- Tests: `core/ActionTest.java`, `device/DeviceManagerExecuteTest.java`, `device/DeviceManagerTest.java`, `discovery/ssdp/SsdpDiscoveryTest.java`, `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`, `sources/jellyfin/JellyfinContentSourceTest.java`, `sources/jellyfin/JellyfinPlayableResolverTest.java`, `sources/jellyfin/JellyfinFixtureContractTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`.

### Files to delete

- None.

---

### Task 1: I1 · UPnP AVTransport adapter

**Files:**
- Create: `core/RedactedUris.java`; `adapters/upnp/protocol/UpnpXml.java`, `SoapRequest.java`, `SoapClient.java`, `SoapFault.java`, `SoapTimeoutException.java`, `UpnpActions.java`, `ServiceEndpoint.java`, `TransportInfo.java`, `VolumeReading.java`, `VolumeRange.java`, `ProtocolInfo.java`, `DidlLite.java`, `RendererCommands.java`, `ReconnectingPoller.java`; `adapters/upnp/UpnpProperties.java`, `UpnpSettings.java`, `UpnpDiscovery.java`, `UpnpSession.java`, `UpnpAdapter.java`, `UpnpConfiguration.java`
- Modify: `core/Action.java`, `device/DeviceManager.java`, `discovery/ssdp/SsdpDiscovery.java`, every exhaustive `switch` over `Action` (`AndroidTvSession`, `CastSession`, `WebOsSession`, `TizenSession`), `web/DeviceController.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `core/ActionTest.java`, `device/DeviceManagerExecuteTest.java`, `discovery/ssdp/SsdpDiscoveryTest.java`, `adapters/upnp/protocol/UpnpXmlTest.java`, `SoapClientTest.java`, `DidlLiteTest.java`, `ProtocolInfoTest.java`, `VolumeRangeTest.java`, `RendererCommandsTest.java`, `ReconnectingPollerTest.java`, `adapters/upnp/FakeUpnpRenderer.java`, `UpnpDiscoveryTest.java`, `UpnpSessionTest.java`, `UpnpAdapterTest.java`, `UpnpModuleSwitchTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`; fixtures `src/test/resources/fixtures/ssdp/renderer-search-response.txt`, `src/test/resources/fixtures/upnp/renderer-description.xml`, `rendering-control-scpd.xml`, `set-av-transport-uri-envelope.xml`, `didl-track.xml`

**Interfaces:**
- Consumes: A — `Device`, `DeviceKind.UPNP`, `DeviceAdapter`, `DeviceHandle`, `DeviceState`, `DeviceStatus`, `DeviceOfflineException`, `UnsupportedActionException`, `Capability.MEDIA_RENDERER/VOLUME`, `DeviceController`, `DashboardController`. B — `DeviceAdapter.kind()/settingsFor(DiscoveredDevice)`, `DiscoveredDevice(adapterId, name, host, port, attributes)`, `DeviceDiscoveredEvent`, `Action.SetVolume/Mute/Stop/CastLoad`, `ActionFailedException`, `DeviceState.withNowPlaying`, `DeviceManager.execute` (fall-through), test helper `device/StubAdapter(String id, DeviceKind kind, boolean pairingFree, boolean boundCredentials, Capability... capabilities)` with `handles` and `StubHandle.executed`. C — `Action.CastMessage`. F — `SsdpDiscovery.addListener/services/watch`, `SsdpService`, `SsdpProperties`, `DeviceDescription`, `DeviceDescriptions.parse`, `FakeSsdpResponder` (with `fixture(name, host, port)`), `Action.SelectInput`, `DeviceState.sameIgnoringTime`.
- Produces:
  - `Action`: `default boolean acceptedBy(Set<Capability> capabilities)`; `record PlayMedia(URI url, String mimeType, String title, String subtitle)` (→ `MEDIA_RENDERER`, redacted `toString`); `record Pause()`, `record Resume()` (→ `MEDIA_RENDERER`); `Stop.acceptedBy` true for `CAST_RECEIVER` or `MEDIA_RENDERER`.
  - `core.RedactedUris.withoutQuery(URI) → String`.
  - `UpnpXml`: `static Element parse(byte[])`, `static Element parse(String)`, `static String escape(String)`, `static String localName(Node)`, `static List<Element> childElements(Element)`, `static Optional<String> childText(Element, String)`, `static List<Element> descendants(Element, String)`, `static Optional<Element> firstDescendant(Element, String)` — all throwing `IllegalArgumentException` for unreadable XML.
  - `record SoapRequest(String serviceType, String action, Map<String,String> arguments)`; `class SoapFault extends Exception { int errorCode(); String description(); }`; `class SoapTimeoutException extends IOException`; `final class SoapClient { SoapClient(HttpClient, Duration); static HttpClient httpClient(Duration connectTimeout); static String envelope(SoapRequest); Map<String,String> call(URI controlUrl, SoapRequest) throws IOException, SoapFault; }`.
  - `UpnpActions` constants `AV_TRANSPORT`, `RENDERING_CONTROL`, `CONNECTION_MANAGER` (type prefixes) and `setAvTransportUri(type, uri, metadata)`, `play(type)`, `pause(type)`, `stop(type)`, `getTransportInfo(type)`, `getPositionInfo(type)`, `becomeCoordinatorOfStandaloneGroup(type)`, `getVolume(type)`, `setVolume(type, int)`, `getMute(type)`, `setMute(type, boolean)`, `getProtocolInfo(type)`.
  - `record ServiceEndpoint(String serviceType, URI controlUrl, URI scpdUrl)` with `of(DeviceDescription.Service)`; `record TransportInfo(String state, String status)` with `NONE`, `from(Map)`, `active()`; `record VolumeReading(int percent, boolean muted)`; `VolumeRange.maximum(byte[])`, `toDevice(int percent, int max)`, `toPercent(int value, int max)`; `ProtocolInfo.UNKNOWN`, `parseSink(String)`, `known()`, `Optional<String> match(String mimeType)`; `DidlLite.DLNA_STREAMING`, `DidlLite.item(URI, String contentFormat, String title, String artist, String additionalInfo)`, `DidlLite.upnpClass(String)`.
  - `RendererCommands(SoapClient, String deviceName)`: `playUri(ServiceEndpoint, ProtocolInfo, Action.PlayMedia, String additionalInfo)`, `transport(ServiceEndpoint, SoapRequest, String what)`, `setVolume(ServiceEndpoint, int percent, int max)`, `setMute(ServiceEndpoint, boolean)`, `TransportInfo transportInfo(ServiceEndpoint)`, `VolumeReading volume(ServiceEndpoint, int max)`, `ProtocolInfo sink(ServiceEndpoint)`, `<T> T run(String what, SoapCall<T>)`.
  - `ReconnectingPoller(String name, Duration initialBackoff, Duration maxBackoff, Link)` with `start()`, `connected()`, `pollNow()`, `reconnectNow()`, `close()`; `interface Link { void connect() throws Exception; void poll() throws Exception; Duration nextPollDelay(); void disconnected(Exception cause); }`.
  - `record UpnpProperties(boolean enabled, int pollIntervalSeconds, int idlePollIntervalSeconds, int commandTimeoutSeconds, int connectTimeoutSeconds, int reconnectInitialDelaySeconds, int reconnectMaxDelaySeconds)` bound to `home-control.upnp`.
  - `record UpnpSettings(String udn, URI location, String model)` with `ADAPTER_ID = "upnp"`, `of(Device)`, `from(DiscoveredDevice)`, `toMap()`.
  - `UpnpDiscovery(SsdpDiscovery, ApplicationEventPublisher)` with `SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"`, `List<DiscoveredDevice> devices()`, `Optional<URI> location(String udn)`, `onAlive(Consumer<String> udnListener)`, `static Optional<DiscoveredDevice> toDevice(SsdpService)`, `close()`.
  - `UpnpSession implements DeviceHandle` — `UpnpSession(Device, UpnpProperties, HttpClient, Function<String, Optional<URI>> locator, Consumer<DeviceState>, Runnable onClosed)`, `start()`, `udn()`, `reconnectNow()`.
  - `UpnpAdapter(UpnpProperties, UpnpDiscovery)`: id `upnp`, kind `UPNP`, capabilities `MEDIA_RENDERER, VOLUME`, pairing-free.
  - `POST /devices/{id}/pause`, `POST /devices/{id}/resume` → 204 / 404 / 409 / 422 / 502. Dashboard model attribute `rendererControls` (`MEDIA_RENDERER`).

**SOAP wire format (normative).** `POST <controlURL>` over HTTP/1.1 with headers `Content-Type: text/xml; charset="utf-8"`, `SOAPACTION: "<serviceType>#<action>"` (quotes included), `User-Agent: Linux/1 UPnP/1.1 HomeControl/1`. Body (one line, no whitespace between elements; argument values XML-escaped once: `&`→`&amp;`, `<`→`&lt;`, `>`→`&gt;`, `"`→`&quot;`, `'`→`&apos;`):

```xml
<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:ACTION xmlns:u="SERVICE_TYPE"><Arg1>value</Arg1>…</u:ACTION></s:Body></s:Envelope>
```

| Service | Action | Arguments in order | Answer arguments read |
|---|---|---|---|
| AVTransport | `SetAVTransportURI` | `InstanceID`=0, `CurrentURI`, `CurrentURIMetaData` (escaped DIDL-Lite, or empty) | — |
| AVTransport | `Play` | `InstanceID`=0, `Speed`=1 | — |
| AVTransport | `Pause`, `Stop`, `GetTransportInfo`, `GetPositionInfo`, `BecomeCoordinatorOfStandaloneGroup` (Sonos) | `InstanceID`=0 | `CurrentTransportState`, `CurrentTransportStatus` / `TrackDuration`, `TrackMetaData`, `TrackURI`, `RelTime` |
| RenderingControl | `GetVolume` / `GetMute` | `InstanceID`=0, `Channel`=Master | `CurrentVolume` / `CurrentMute` (`0`/`1`) |
| RenderingControl | `SetVolume` / `SetMute` | `InstanceID`=0, `Channel`=Master, `DesiredVolume` (device units) / `DesiredMute` (`1`/`0`) | — |
| ConnectionManager | `GetProtocolInfo` | none | `Sink` (comma list of `protocol:network:contentFormat:additionalInfo`) |

A success is HTTP 200 whose body contains an element with local name `<ACTION>Response`; its child elements are the answer arguments. A failure is HTTP 500:

```xml
<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>
```

Exact `SetAVTransportURI` for URL `http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&ApiKey=t`, type `audio/flac`, title `Bunny Song`, artist `The Rabbits`, additional info `DidlLite.DLNA_STREAMING` — this is the content of the two fixtures below (each a single line, compared after `strip()`).

- [ ] **Step 1: Add the fixtures**

`src/test/resources/fixtures/upnp/didl-track.xml`:

```xml
<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>Bunny Song</dc:title><upnp:artist>The Rabbits</upnp:artist><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&amp;mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&amp;ApiKey=t</res></item></DIDL-Lite>
```

`src/test/resources/fixtures/upnp/set-av-transport-uri-envelope.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><CurrentURI>http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&amp;mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&amp;ApiKey=t</CurrentURI><CurrentURIMetaData>&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot;&gt;&lt;item id=&quot;1&quot; parentID=&quot;0&quot; restricted=&quot;1&quot;&gt;&lt;dc:title&gt;Bunny Song&lt;/dc:title&gt;&lt;upnp:artist&gt;The Rabbits&lt;/upnp:artist&gt;&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;&lt;res protocolInfo=&quot;http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000&quot;&gt;http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&amp;amp;mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&amp;amp;ApiKey=t&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>
```

`src/test/resources/fixtures/ssdp/renderer-search-response.txt` (ends with one empty line; `FakeSsdpResponder.fixture` fills `{host}`/`{port}` and converts to CRLF):

```text
HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
DATE: Wed, 16 Sep 2026 10:00:00 GMT
EXT:
LOCATION: http://{host}:{port}/description.xml
SERVER: Linux/5.15 UPnP/1.0 StreamBox/2.4
ST: urn:schemas-upnp-org:device:MediaRenderer:1
USN: uuid:5f9ec1b3-ed59-4f00-a3c1-2d2b4a1e0001::urn:schemas-upnp-org:device:MediaRenderer:1

```

`src/test/resources/fixtures/upnp/renderer-description.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <friendlyName>Kitchen Speaker</friendlyName>
    <manufacturer>Acme Audio</manufacturer>
    <modelName>StreamBox 2</modelName>
    <UDN>uuid:5f9ec1b3-ed59-4f00-a3c1-2d2b4a1e0001</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>
        <controlURL>/upnp/control/RenderingControl1</controlURL>
        <eventSubURL>/upnp/event/RenderingControl1</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/scpd/ConnectionManager1.xml</SCPDURL>
        <controlURL>/upnp/control/ConnectionManager1</controlURL>
        <eventSubURL>/upnp/event/ConnectionManager1</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/scpd/AVTransport1.xml</SCPDURL>
        <controlURL>/upnp/control/AVTransport1</controlURL>
        <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
```

`src/test/resources/fixtures/upnp/rendering-control-scpd.xml` (`{volumeMax}` is replaced by the fake and the tests):

```xml
<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType><allowedValueList><allowedValue>Master</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>{volumeMax}</maximum><step>1</step></allowedValueRange></stateVariable>
  </serviceStateTable>
</scpd>
```

- [ ] **Step 2: Write the failing core tests**

Add to `core/ActionTest.java`:
- `mediaRendererActionsRequireAMediaRenderer`: `new Action.PlayMedia(URI.create("http://nas/a.flac"), "audio/flac", "A", null).requires()`, `new Action.Pause().requires()`, `new Action.Resume().requires()` are `MEDIA_RENDERER`.
- `stopReachesCastReceiversAndMediaRenderers`: `new Action.Stop().requires()` is still `CAST_RECEIVER`; `acceptedBy(EnumSet.of(MEDIA_RENDERER))` and `acceptedBy(EnumSet.of(CAST_RECEIVER))` true; `acceptedBy(EnumSet.of(VOLUME, REMOTE_KEYS))` false; `new Action.PressKey(RemoteKey.HOME).acceptedBy(EnumSet.of(REMOTE_KEYS))` true and `acceptedBy(EnumSet.of(VOLUME))` false.
- `playMediaNeedsAUrlAndDefaultsTheType`: null url → `IllegalArgumentException`; blank mime → `application/octet-stream`.
- `playMediaNeverPrintsTheStreamCredential`: `new Action.PlayMedia(URI.create("http://nas:8096/Audio/x/stream.flac?static=true&ApiKey=secret-key"), "audio/flac", "Song", null).toString()` contains `http://nas:8096/Audio/x/stream.flac?…` and not `secret-key`.

Add to `device/DeviceManagerExecuteTest.java`:
- `stopReachesAMediaRendererWithoutCast`: registry holds `Device("speaker", "Speaker", DeviceKind.UPNP, "10.0.0.30", {upnp: {}}, now)`; manager over `new StubAdapter("upnp", DeviceKind.UPNP, true, false, MEDIA_RENDERER, VOLUME)`; `start()`; `execute("speaker", new Action.Stop())` → the stub handle's `executed` contains `Stop`; `execute("speaker", new Action.PressKey(RemoteKey.HOME))` → `UnsupportedActionException`.

Add to `discovery/ssdp/SsdpDiscoveryTest.java`:
- `fetchesDescriptionsOverHttp11`: an `HttpServer` context for `/lg/description.xml` records `exchange.getProtocol()` and `exchange.getRequestHeaders().getFirst("Upgrade")`; a discovery built with the **public** constructor `new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1))` finds the LG service → recorded protocol `HTTP/1.1`, `Upgrade` null.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.ActionTest' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.discovery.ssdp.SsdpDiscoveryTest'`
Expected: compilation failure (`PlayMedia`, `acceptedBy` missing); after they compile, `fetchesDescriptionsOverHttp11` fails with an `Upgrade: h2c` header.

- [ ] **Step 3: Implement the core changes**

`core/RedactedUris.java`:

```java
package dev.andre.homecontrol.core;

import java.net.URI;

/** Stream URLs can carry credentials in their query (Jellyfin ApiKey); logs and messages get this form. */
public final class RedactedUris {

    private RedactedUris() {
    }

    public static String withoutQuery(URI uri) {
        if (uri == null) {
            return "null";
        }
        String where = uri.getScheme() == null || uri.getHost() == null
                ? String.valueOf(uri.getRawPath())
                : uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "") + uri.getRawPath();
        return where + (uri.getRawQuery() == null ? "" : "?…");
    }
}
```

In `core/Action.java` add (imports `java.util.Set`):

```java
    /** Whether an adapter declaring {@code capabilities} may be asked to perform this action. */
    default boolean acceptedBy(Set<Capability> capabilities) {
        return capabilities.contains(requires());
    }

    /** Play a direct stream on a media renderer (spec §5.3 rung 4). The URL may carry a credential. */
    record PlayMedia(URI url, String mimeType, String title, String subtitle) implements Action {
        public PlayMedia {
            if (url == null) {
                throw new IllegalArgumentException("A stream URL is required");
            }
            mimeType = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.strip();
        }

        @Override
        public Capability requires() {
            return Capability.MEDIA_RENDERER;
        }

        @Override
        public String toString() {
            return "PlayMedia[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + ", title=" + title + "]";
        }
    }

    record Pause() implements Action {
        @Override
        public Capability requires() {
            return Capability.MEDIA_RENDERER;
        }
    }

    record Resume() implements Action {
        @Override
        public Capability requires() {
            return Capability.MEDIA_RENDERER;
        }
    }
```

and in B's `Stop` record add:

```java
        /** Cast receivers and media renderers both stop playback (B kept requires() for its tests). */
        @Override
        public boolean acceptedBy(Set<Capability> capabilities) {
            return capabilities.contains(Capability.CAST_RECEIVER) || capabilities.contains(Capability.MEDIA_RENDERER);
        }
```

`device/DeviceManager.execute`: replace the condition `!adapter.capabilities(device).contains(action.requires())` with `!action.acceptedBy(adapter.capabilities(device))` (and any other `contains(action.requires())` in the class).

Exhaustive switches: run `grep -rln "case Action.PressKey" src/main/java` and add to each handle's `switch` (except the UPnP session written below):

```java
            case Action.PlayMedia ignored -> throw new UnsupportedActionException(device.name() + " cannot play a direct stream");
            case Action.Pause ignored -> throw new UnsupportedActionException(device.name() + " cannot pause a direct stream");
            case Action.Resume ignored -> throw new UnsupportedActionException(device.name() + " cannot resume a direct stream");
```

(Use the handle's existing way of naming the device — `device.name()` in B/F sessions. If a switch uses a `default` branch, nothing to add.)

`discovery/ssdp/SsdpDiscovery.java`: in the public constructor replace `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()` with `HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()` and add a one-line comment: embedded UPnP servers reject the `Upgrade: h2c` header the JDK sends by default.

Run the Step 2 command. Expected: PASS.

- [ ] **Step 4: Write the failing protocol tests**

`src/test/java/dev/andre/homecontrol/adapters/upnp/protocol/UpnpXmlTest.java`:
- `escapesTheFiveXmlCharacters`: `escape("a&b<c>d\"e'f")` = `a&amp;b&lt;c&gt;d&quot;e&apos;f`; `escape(null)` = `""`.
- `readsElementsIgnoringPrefixes`: parse `<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetVolumeResponse xmlns:u="urn:x"><CurrentVolume>35</CurrentVolume><Note>&lt;b&gt;</Note></u:GetVolumeResponse></s:Body></s:Envelope>` → `firstDescendant(root, "GetVolumeResponse")` present; its `childElements` local names `[CurrentVolume, Note]`; `childText(response, "Note")` = `<b>`; `descendants(root, "Body")` has one element; `firstDescendant(root, "Envelope")` is the root itself.
- `refusesADocumentTypeDeclaration`: `<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><r>&x;</r>` → `IllegalArgumentException`.
- `rejectsGarbage`: `parse("not xml")` and `parse(new byte[0])` → `IllegalArgumentException`.

`SoapClientTest` — a JDK `HttpServer` on `127.0.0.1:0` whose handler records method, protocol, headers and body and answers what the test configured; client `new SoapClient(SoapClient.httpClient(Duration.ofSeconds(1)), Duration.ofMillis(500))`:
- `buildsTheNormativeEnvelope`: `SoapClient.envelope(UpnpActions.setAvTransportUri("urn:schemas-upnp-org:service:AVTransport:1", URL, Files.readString(didl-track.xml).strip()))` equals `set-av-transport-uri-envelope.xml` stripped (URL is the one above); `envelope(UpnpActions.play("urn:schemas-upnp-org:service:AVTransport:1"))` equals `<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>`; `envelope(UpnpActions.setVolume("urn:schemas-upnp-org:service:RenderingControl:1", 40))` equals the same frame with `<u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>40</DesiredVolume></u:SetVolume>`; `setMute(type, true)` has `<DesiredMute>1</DesiredMute>`; `getProtocolInfo(type)` has no argument elements.
- `postsOverHttp11WithTheSoapActionHeader`: server answers 200 with a `GetVolumeResponse` holding `CurrentVolume` 35 → `call(url, UpnpActions.getVolume("urn:schemas-upnp-org:service:RenderingControl:1"))` = `{CurrentVolume=35}`; recorded method `POST`, protocol `HTTP/1.1`, `SOAPACTION` = `"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume"`, `Content-Type` = `text/xml; charset="utf-8"`, no `Upgrade` header, body equals `envelope(...)`.
- `aUpnpErrorIsASoapFault`: 500 with the fault envelope above → `SoapFault` with `errorCode()` 714, `description()` `Illegal MIME-type`, message `UPnP error 714: Illegal MIME-type`; a fault without `errorDescription` and code 701 → description `Transition not available`.
- `aSilentDeviceIsATimeout`: handler sleeps 2 s → `SoapTimeoutException`.
- `otherStatusesAreConnectionProblems`: 404 → an `IOException` that is neither `SoapTimeoutException` nor wrapped in `SoapFault`; a closed port → `IOException`.
- `anUnreadableAnswerIsAFault`: 200 with body `garbage` → `SoapFault` code 0; 200 with a well-formed envelope lacking `GetVolumeResponse` → `SoapFault` code 0.
- `requestsNeverPrintTheirArguments`: `UpnpActions.setAvTransportUri(type, "http://h/x?ApiKey=secret-key", "").toString()` does not contain `secret-key` and contains `SetAVTransportURI`.

`DidlLiteTest`:
- `describesAnAudioTrackForDlnaRenderers`: `DidlLite.item(URI.create(URL), "audio/flac", "Bunny Song", "The Rabbits", DidlLite.DLNA_STREAMING)` equals `didl-track.xml` stripped.
- `classesFollowTheMimeType`: `upnpClass("video/mp4")` = `object.item.videoItem.movie`; `"image/jpeg"` → `object.item.imageItem.photo`; `"audio/L16;rate=44100"` → `object.item.audioItem.musicTrack`; `"application/x-mpegURL"` → `object.item`.
- `escapesTitlesAndOmitsAMissingArtist`: `item(URI.create("http://h/a.mp3"), "audio/mpeg", "Tom & Jerry <Live>", null, "*")` contains `<dc:title>Tom &amp; Jerry &lt;Live&gt;</dc:title>`, `protocolInfo="http-get:*:audio/mpeg:*"`, and no `upnp:artist`; a blank title becomes `Home Control`.

`ProtocolInfoTest`:
- `matchesExactTypesAliasesAndWildcards`: sink `http-get:*:audio/mpeg:*,http-get:*:audio/x-flac:DLNA.ORG_PN=FLAC,rtsp-rtp-udp:*:video/mp4:*,http-get:*:audio/L16;rate=44100;channels=2:*` → `known()` true; `match("audio/mpeg")` = `audio/mpeg`; `match("audio/flac")` = `audio/x-flac`; `match("video/mp4")` empty (only RTSP); `match("audio/L16")` = `audio/L16;rate=44100;channels=2`; `match("AUDIO/MPEG")` = `audio/mpeg`.
- `aWildcardEntryAcceptsAnything`: `http-get:*:*:*` → `match("video/webm")` = `video/webm`.
- `anUnknownSinkAcceptsAnything`: `ProtocolInfo.UNKNOWN.match("audio/ogg")` = `audio/ogg`; `parseSink("")` and `parseSink(null)` are not `known()`.

`VolumeRangeTest`:
- `readsTheVolumeMaximumFromTheScpd`: fixture with `{volumeMax}` → `60` gives 60; a SCPD without a `Volume` variable, unreadable bytes, or maximum `0` give 100.
- `convertsBetweenPercentAndDeviceUnits`: `toDevice(50, 60)` = 30; `toDevice(100, 60)` = 60; `toPercent(30, 60)` = 50; `toPercent(80, 60)` = 100; `toPercent(-3, 100)` = 0; `toPercent(5, 0)` = 5 (max ≤ 0 treated as 100).

`ReconnectingPollerTest` — a scriptable `Link` recording call timestamps; `initialBackoff` 100 ms, `maxBackoff` 400 ms unless stated; every poller closed in `@AfterEach`:
- `connectsThenPollsAtTheDelayTheLinkAsks`: `nextPollDelay` 50 ms → within 1 s `connect` once, `poll` ≥ 3 times, `connected()` true.
- `aFailedConnectIsRetriedWithDoublingBackoff`: `connect` throws `IOException` three times then succeeds → `disconnected` called 3 times; gaps between attempts ≥ 90, 180, 360 ms; then `connected()`.
- `anIoFailureWhilePollingReconnects`: `poll` throws `IOException` once → `disconnected` once, `connect` called a second time, polling resumes.
- `otherPollFailuresKeepPolling`: `poll` throws `new SoapFault(501, "Action Failed")` every time → `disconnected` never called, `poll` count keeps growing.
- `reconnectNowSkipsTheBackoff`: `initialBackoff` 10 s, `maxBackoff` 60 s; `connect` fails once, then succeeds → after the first failure `reconnectNow()` → connected within 1 s.
- `pollNowPollsAtOnce`: `nextPollDelay` 10 s → after connect, `pollNow()` → second `poll` within 1 s.
- `closeStopsEverything`: after `close()`, no further `connect`/`poll` for 500 ms and `connected()` false.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.protocol.*'`
Expected: compilation failure — the protocol package does not exist.

- [ ] **Step 5: Implement the protocol package**

`adapters/upnp/protocol/UpnpXml.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Device-supplied XML, parsed defensively: no DTDs, no external entities, prefixes ignored. */
public final class UpnpXml {

    private UpnpXml() {
    }

    public static Element parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw new IllegalArgumentException("Unreadable XML: empty document");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // fatal errors throw; nothing printed to stderr
            return builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Unreadable XML: " + e.getMessage(), e);
        }
    }

    public static Element parse(String xml) {
        return parse(xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8));
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String localName(Node node) {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    public static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    /** Trimmed text of the first child with that local name; blank counts as absent. */
    public static Optional<String> childText(Element parent, String localName) {
        return childElements(parent).stream()
                .filter(child -> localName.equals(localName(child)))
                .map(child -> child.getTextContent().trim())
                .filter(text -> !text.isEmpty())
                .findFirst();
    }

    /** Every element below {@code root} (not root itself) with that local name, in document order. */
    public static List<Element> descendants(Element root, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (localName.equals(localName(element))) {
                found.add(element);
            }
        }
        return found;
    }

    /** {@code root} itself if it has that local name, else its first such descendant. */
    public static Optional<Element> firstDescendant(Element root, String localName) {
        if (localName.equals(localName(root))) {
            return Optional.of(root);
        }
        return descendants(root, localName).stream().findFirst();
    }
}
```

`adapters/upnp/protocol/SoapRequest.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One UPnP action call. Argument order is significant on the wire. */
public record SoapRequest(String serviceType, String action, Map<String, String> arguments) {

    public SoapRequest {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** Arguments can hold stream URLs with credentials; never print them. */
    @Override
    public String toString() {
        return "SoapRequest[" + serviceType + "#" + action + "]";
    }
}
```

`adapters/upnp/protocol/SoapFault.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Map;

/** The device answered, and said no (UPnP error), or answered something unreadable (code 0). */
public class SoapFault extends Exception {

    private static final Map<Integer, String> KNOWN = Map.of(
            401, "Invalid Action", 402, "Invalid Args", 501, "Action Failed",
            701, "Transition not available", 702, "No contents", 705, "Transport is locked",
            714, "Illegal MIME-type", 716, "Resource not found", 718, "Invalid InstanceID");

    private final int errorCode;
    private final String description;

    public SoapFault(int errorCode, String description) {
        this(errorCode, describe(errorCode, description), true);
    }

    private SoapFault(int errorCode, String description, boolean resolved) {
        super(errorCode > 0 ? "UPnP error " + errorCode + (description.isEmpty() ? "" : ": " + description) : description);
        this.errorCode = errorCode;
        this.description = description;
    }

    private static String describe(int errorCode, String description) {
        String given = description == null ? "" : description.strip();
        return given.isEmpty() || given.equals("UPnPError") ? KNOWN.getOrDefault(errorCode, given) : given;
    }

    public int errorCode() {
        return errorCode;
    }

    public String description() {
        return description;
    }
}
```

`adapters/upnp/protocol/SoapTimeoutException.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.io.IOException;

/** The device accepted the connection but did not answer in time. */
public class SoapTimeoutException extends IOException {
    public SoapTimeoutException(String message) {
        super(message);
    }
}
```

`adapters/upnp/protocol/SoapClient.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** UPnP control: one SOAP POST per action, HTTP/1.1, answers capped (UDA 1.1 §3). */
public final class SoapClient {

    public static final String USER_AGENT = "Linux/1 UPnP/1.1 HomeControl/1";
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String ENVELOPE_START = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>";
    private static final String ENVELOPE_END = "</s:Body></s:Envelope>";

    private final HttpClient http;
    private final Duration timeout;

    public SoapClient(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    /** The only way device HTTP clients are built: HTTP/1.1 (no h2c upgrade), no redirects. */
    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
    }

    public static String envelope(SoapRequest request) {
        StringBuilder xml = new StringBuilder(ENVELOPE_START)
                .append("<u:").append(request.action()).append(" xmlns:u=\"").append(request.serviceType()).append("\">");
        request.arguments().forEach((name, value) -> xml.append('<').append(name).append('>')
                .append(UpnpXml.escape(value)).append("</").append(name).append('>'));
        return xml.append("</u:").append(request.action()).append('>').append(ENVELOPE_END).toString();
    }

    public Map<String, String> call(URI controlUrl, SoapRequest request) throws IOException, SoapFault {
        HttpRequest httpRequest = HttpRequest.newBuilder(controlUrl)
                .timeout(timeout)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPACTION", "\"" + request.serviceType() + "#" + request.action() + "\"")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(envelope(request), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw e; // unreachable, not slow
        } catch (HttpTimeoutException e) {
            throw new SoapTimeoutException("No answer to " + request.action() + " within " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while calling " + request.action());
        }
        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new IOException("The answer to " + request.action() + " is larger than " + MAX_RESPONSE_BYTES + " bytes");
        }
        return switch (response.statusCode()) {
            case 200 -> arguments(body, request.action());
            case 500 -> throw fault(body, request.action());
            default -> throw new IOException("HTTP " + response.statusCode() + " from " + controlUrl.getHost()
                    + " for " + request.action());
        };
    }

    static Map<String, String> arguments(byte[] body, String action) throws SoapFault {
        try {
            Element response = UpnpXml.firstDescendant(UpnpXml.parse(body), action + "Response")
                    .orElseThrow(() -> new IllegalArgumentException("no " + action + "Response"));
            Map<String, String> values = new LinkedHashMap<>();
            UpnpXml.childElements(response).forEach(child -> values.put(UpnpXml.localName(child), child.getTextContent()));
            return Collections.unmodifiableMap(values);
        } catch (IllegalArgumentException e) {
            throw new SoapFault(0, "Unreadable answer to " + action);
        }
    }

    static SoapFault fault(byte[] body, String action) {
        try {
            Element root = UpnpXml.parse(body);
            Optional<Element> error = UpnpXml.firstDescendant(root, "UPnPError");
            int code = error.flatMap(e -> UpnpXml.childText(e, "errorCode")).map(Integer::parseInt).orElse(0);
            String description = error.flatMap(e -> UpnpXml.childText(e, "errorDescription"))
                    .or(() -> UpnpXml.firstDescendant(root, "faultstring").map(e -> e.getTextContent().trim()))
                    .orElse("");
            return code == 0 && description.isEmpty() ? new SoapFault(0, "HTTP 500 for " + action) : new SoapFault(code, description);
        } catch (IllegalArgumentException e) { // includes NumberFormatException
            return new SoapFault(0, "HTTP 500 for " + action);
        }
    }
}
```

`adapters/upnp/protocol/UpnpActions.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** The UPnP actions this project sends, with arguments in template order. */
public final class UpnpActions {

    /** Service type prefixes (the version suffix is taken from the device description). */
    public static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:";
    public static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:";
    public static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:";

    private UpnpActions() {
    }

    public static SoapRequest setAvTransportUri(String serviceType, String uri, String metadata) {
        return request(serviceType, "SetAVTransportURI", "InstanceID", "0", "CurrentURI", uri, "CurrentURIMetaData", metadata);
    }

    public static SoapRequest play(String serviceType) {
        return request(serviceType, "Play", "InstanceID", "0", "Speed", "1");
    }

    public static SoapRequest pause(String serviceType) {
        return request(serviceType, "Pause", "InstanceID", "0");
    }

    public static SoapRequest stop(String serviceType) {
        return request(serviceType, "Stop", "InstanceID", "0");
    }

    public static SoapRequest getTransportInfo(String serviceType) {
        return request(serviceType, "GetTransportInfo", "InstanceID", "0");
    }

    public static SoapRequest getPositionInfo(String serviceType) {
        return request(serviceType, "GetPositionInfo", "InstanceID", "0");
    }

    /** Sonos extension on AVTransport: leave the current group. */
    public static SoapRequest becomeCoordinatorOfStandaloneGroup(String serviceType) {
        return request(serviceType, "BecomeCoordinatorOfStandaloneGroup", "InstanceID", "0");
    }

    public static SoapRequest getVolume(String serviceType) {
        return request(serviceType, "GetVolume", "InstanceID", "0", "Channel", "Master");
    }

    public static SoapRequest setVolume(String serviceType, int deviceVolume) {
        return request(serviceType, "SetVolume", "InstanceID", "0", "Channel", "Master", "DesiredVolume", String.valueOf(deviceVolume));
    }

    public static SoapRequest getMute(String serviceType) {
        return request(serviceType, "GetMute", "InstanceID", "0", "Channel", "Master");
    }

    public static SoapRequest setMute(String serviceType, boolean muted) {
        return request(serviceType, "SetMute", "InstanceID", "0", "Channel", "Master", "DesiredMute", muted ? "1" : "0");
    }

    public static SoapRequest getProtocolInfo(String serviceType) {
        return request(serviceType, "GetProtocolInfo");
    }

    public static SoapRequest request(String serviceType, String action, String... namesAndValues) {
        Map<String, String> arguments = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            arguments.put(namesAndValues[i], namesAndValues[i + 1] == null ? "" : namesAndValues[i + 1]);
        }
        return new SoapRequest(serviceType, action, arguments);
    }
}
```

`adapters/upnp/protocol/ServiceEndpoint.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;

import java.net.URI;

public record ServiceEndpoint(String serviceType, URI controlUrl, URI scpdUrl) {

    public static ServiceEndpoint of(DeviceDescription.Service service) {
        return new ServiceEndpoint(service.serviceType(), service.controlUrl(), service.scpdUrl());
    }
}
```

`adapters/upnp/protocol/TransportInfo.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Locale;
import java.util.Map;

public record TransportInfo(String state, String status) {

    public static final TransportInfo NONE = new TransportInfo("NO_MEDIA_PRESENT", "OK");

    public static TransportInfo from(Map<String, String> answer) {
        return new TransportInfo(
                answer.getOrDefault("CurrentTransportState", "NO_MEDIA_PRESENT").strip().toUpperCase(Locale.ROOT),
                answer.getOrDefault("CurrentTransportStatus", "OK").strip());
    }

    /** Something is loaded and playing, paused or about to play: poll faster. */
    public boolean active() {
        return switch (state) {
            case "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> true;
            default -> false;
        };
    }
}
```

`adapters/upnp/protocol/VolumeReading.java`: `public record VolumeReading(int percent, boolean muted) {}`.

`adapters/upnp/protocol/VolumeRange.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import org.w3c.dom.Element;

/** RenderingControl volumes are device units; the rest of the system speaks percent (B). */
public final class VolumeRange {

    public static final int DEFAULT_MAXIMUM = 100;

    private VolumeRange() {
    }

    public static int maximum(byte[] scpd) {
        try {
            for (Element variable : UpnpXml.descendants(UpnpXml.parse(scpd), "stateVariable")) {
                if (UpnpXml.childText(variable, "name").filter("Volume"::equals).isPresent()) {
                    int max = UpnpXml.firstDescendant(variable, "allowedValueRange")
                            .flatMap(range -> UpnpXml.childText(range, "maximum"))
                            .map(Integer::parseInt)
                            .orElse(DEFAULT_MAXIMUM);
                    return max > 0 ? max : DEFAULT_MAXIMUM;
                }
            }
        } catch (IllegalArgumentException e) {
            // unreadable SCPD or a non-numeric maximum
        }
        return DEFAULT_MAXIMUM;
    }

    public static int toDevice(int percent, int max) {
        int range = max > 0 ? max : DEFAULT_MAXIMUM;
        return (int) Math.round(Math.clamp(percent, 0, 100) * range / 100.0);
    }

    public static int toPercent(int value, int max) {
        int range = max > 0 ? max : DEFAULT_MAXIMUM;
        return (int) Math.clamp(Math.round(value * 100.0 / range), 0, 100);
    }
}
```

`adapters/upnp/protocol/ProtocolInfo.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** A renderer's ConnectionManager sink list, reduced to what it can fetch over HTTP. */
public final class ProtocolInfo {

    public static final ProtocolInfo UNKNOWN = new ProtocolInfo(false, List.of());

    private static final List<Set<String>> ALIASES = List.of(
            Set.of("audio/mpeg", "audio/mp3", "audio/x-mpeg"),
            Set.of("audio/flac", "audio/x-flac"),
            Set.of("audio/mp4", "audio/x-m4a", "audio/m4a"),
            Set.of("audio/ogg", "application/ogg", "audio/x-ogg"),
            Set.of("audio/wav", "audio/x-wav", "audio/wave"),
            Set.of("video/x-matroska", "video/x-mkv"));

    private final boolean known;
    /** Content formats of {@code http-get} entries, spelled as the renderer spells them. */
    private final List<String> httpFormats;

    private ProtocolInfo(boolean known, List<String> httpFormats) {
        this.known = known;
        this.httpFormats = List.copyOf(httpFormats);
    }

    public static ProtocolInfo parseSink(String sink) {
        if (sink == null || sink.isBlank()) {
            return UNKNOWN;
        }
        List<String> formats = new ArrayList<>();
        for (String entry : sink.split(",")) {
            String[] parts = entry.strip().split(":", 4);
            if (parts.length == 4 && parts[0].equalsIgnoreCase("http-get") && !parts[2].isBlank()) {
                formats.add(parts[2].strip());
            }
        }
        return new ProtocolInfo(true, formats);
    }

    public boolean known() {
        return known;
    }

    /** The content format to announce for {@code mimeType}, or empty when the renderer cannot fetch it over HTTP. */
    public Optional<String> match(String mimeType) {
        if (!known) {
            return Optional.of(mimeType);
        }
        String wanted = base(mimeType);
        for (String format : httpFormats) {
            if (format.equals("*")) {
                return Optional.of(mimeType);
            }
            if (base(format).equals(wanted)) {
                return Optional.of(format);
            }
        }
        Set<String> family = ALIASES.stream().filter(set -> set.contains(wanted)).findFirst().orElse(Set.of());
        return httpFormats.stream().filter(format -> family.contains(base(format))).findFirst();
    }

    private static String base(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        return (semicolon < 0 ? mimeType : mimeType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }
}
```

`adapters/upnp/protocol/DidlLite.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.net.URI;
import java.util.Locale;

/** DIDL-Lite metadata for SetAVTransportURI. Built unescaped; SoapClient escapes it once more on the wire. */
public final class DidlLite {

    /** Byte-range seeking, not converted, DLNA 1.5 streaming flags (MiniDLNA's values). */
    public static final String DLNA_STREAMING = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";

    private static final String OPEN = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">";

    private DidlLite() {
    }

    public static String item(URI url, String contentFormat, String title, String artist, String additionalInfo) {
        StringBuilder xml = new StringBuilder(OPEN)
                .append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
                .append("<dc:title>").append(UpnpXml.escape(title == null || title.isBlank() ? "Home Control" : title)).append("</dc:title>");
        if (artist != null && !artist.isBlank()) {
            xml.append("<upnp:artist>").append(UpnpXml.escape(artist)).append("</upnp:artist>");
        }
        return xml.append("<upnp:class>").append(upnpClass(contentFormat)).append("</upnp:class>")
                .append("<res protocolInfo=\"").append(UpnpXml.escape("http-get:*:" + contentFormat + ":" + additionalInfo)).append("\">")
                .append(UpnpXml.escape(url.toString()))
                .append("</res></item></DIDL-Lite>")
                .toString();
    }

    public static String upnpClass(String contentFormat) {
        String type = contentFormat == null ? "" : contentFormat.strip().toLowerCase(Locale.ROOT);
        if (type.startsWith("audio/")) {
            return "object.item.audioItem.musicTrack";
        }
        if (type.startsWith("video/")) {
            return "object.item.videoItem.movie";
        }
        if (type.startsWith("image/")) {
            return "object.item.imageItem.photo";
        }
        return "object.item";
    }
}
```

`adapters/upnp/protocol/RendererCommands.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;

import java.io.IOException;

/** AVTransport / RenderingControl commands with the epic's error mapping; shared by UPnP and Sonos sessions. */
public final class RendererCommands {

    @FunctionalInterface
    public interface SoapCall<T> {
        T call() throws IOException, SoapFault;
    }

    private final SoapClient soap;
    private final String deviceName;

    public RendererCommands(SoapClient soap, String deviceName) {
        this.soap = soap;
        this.deviceName = deviceName;
    }

    public void playUri(ServiceEndpoint avTransport, ProtocolInfo sink, Action.PlayMedia play, String additionalInfo) {
        String format = sink.match(play.mimeType())
                .orElseThrow(() -> new UnsupportedActionException(deviceName + " cannot play " + play.mimeType()));
        String metadata = DidlLite.item(play.url(), format, play.title(), play.subtitle(), additionalInfo);
        SoapRequest load = UpnpActions.setAvTransportUri(avTransport.serviceType(), play.url().toString(), metadata);
        run("play the stream", () -> {
            try {
                soap.call(avTransport.controlUrl(), load);
            } catch (SoapFault fault) {
                if (fault.errorCode() != 701 && fault.errorCode() != 705) {
                    throw fault;
                }
                // Several renderers take a new URI only when stopped.
                try {
                    soap.call(avTransport.controlUrl(), UpnpActions.stop(avTransport.serviceType()));
                } catch (SoapFault ignored) {
                    // already stopped
                }
                soap.call(avTransport.controlUrl(), load);
            }
            return soap.call(avTransport.controlUrl(), UpnpActions.play(avTransport.serviceType()));
        });
    }

    public void transport(ServiceEndpoint endpoint, SoapRequest request, String what) {
        run(what, () -> soap.call(endpoint.controlUrl(), request));
    }

    public void setVolume(ServiceEndpoint renderingControl, int percent, int max) {
        run("set the volume", () -> soap.call(renderingControl.controlUrl(),
                UpnpActions.setVolume(renderingControl.serviceType(), VolumeRange.toDevice(percent, max))));
    }

    public void setMute(ServiceEndpoint renderingControl, boolean muted) {
        run(muted ? "mute" : "unmute", () -> soap.call(renderingControl.controlUrl(),
                UpnpActions.setMute(renderingControl.serviceType(), muted)));
    }

    public TransportInfo transportInfo(ServiceEndpoint avTransport) throws IOException, SoapFault {
        return TransportInfo.from(soap.call(avTransport.controlUrl(), UpnpActions.getTransportInfo(avTransport.serviceType())));
    }

    public VolumeReading volume(ServiceEndpoint renderingControl, int max) throws IOException, SoapFault {
        String volume = soap.call(renderingControl.controlUrl(), UpnpActions.getVolume(renderingControl.serviceType()))
                .getOrDefault("CurrentVolume", "0").strip();
        String mute = soap.call(renderingControl.controlUrl(), UpnpActions.getMute(renderingControl.serviceType()))
                .getOrDefault("CurrentMute", "0").strip();
        try {
            return new VolumeReading(VolumeRange.toPercent(Integer.parseInt(volume), max),
                    mute.equals("1") || mute.equalsIgnoreCase("true"));
        } catch (NumberFormatException e) {
            throw new SoapFault(0, "Unreadable volume");
        }
    }

    public ProtocolInfo sink(ServiceEndpoint connectionManager) throws IOException, SoapFault {
        return ProtocolInfo.parseSink(soap.call(connectionManager.controlUrl(),
                UpnpActions.getProtocolInfo(connectionManager.serviceType())).getOrDefault("Sink", ""));
    }

    public <T> T run(String what, SoapCall<T> call) {
        try {
            return call.call();
        } catch (SoapFault fault) {
            throw new ActionFailedException(deviceName + " refused to " + what + " (" + fault.getMessage() + ")");
        } catch (SoapTimeoutException e) {
            throw new ActionFailedException(deviceName + " did not answer in time when asked to " + what);
        } catch (IOException e) {
            throw new DeviceOfflineException(deviceName + " could not be reached to " + what);
        }
    }
}
```

`adapters/upnp/protocol/ReconnectingPoller.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One device's loop on one virtual thread: connect with doubling backoff, then poll at the delay
 * the link chooses. An {@link IOException} from a poll means the device is gone; anything else is
 * logged and polling continues. All {@link Link} callbacks run on the loop thread.
 */
public final class ReconnectingPoller implements AutoCloseable {

    public interface Link {
        void connect() throws Exception;

        void poll() throws Exception;

        Duration nextPollDelay();

        void disconnected(Exception cause);
    }

    private static final Logger log = LoggerFactory.getLogger(ReconnectingPoller.class);

    private final String name;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Link link;
    private final ScheduledExecutorService loop;

    private volatile boolean connected;
    private volatile boolean closed;
    /** Loop thread only. */
    private Duration backoff;
    private ScheduledFuture<?> pending;

    public ReconnectingPoller(String name, Duration initialBackoff, Duration maxBackoff, Link link) {
        this.name = name;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.link = link;
        this.backoff = initialBackoff;
        this.loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(name).factory());
    }

    public void start() {
        submit(this::attemptConnect);
    }

    public boolean connected() {
        return connected && !closed;
    }

    public void pollNow() {
        submit(() -> {
            if (connected) {
                cancelPending();
                pollOnce();
            }
        });
    }

    public void reconnectNow() {
        submit(() -> {
            if (!connected) {
                cancelPending();
                backoff = initialBackoff;
                attemptConnect();
            }
        });
    }

    private void attemptConnect() {
        if (closed) {
            return;
        }
        try {
            link.connect();
            connected = true;
            backoff = initialBackoff;
            schedule(this::pollOnce, link.nextPollDelay());
        } catch (Exception e) {
            connected = false;
            link.disconnected(e);
            scheduleReconnect();
        }
    }

    private void pollOnce() {
        if (closed || !connected) {
            return;
        }
        try {
            link.poll();
        } catch (IOException e) {
            connected = false;
            link.disconnected(e);
            scheduleReconnect();
            return;
        } catch (Exception e) {
            log.debug("{}: poll failed: {}", name, e.getMessage());
        }
        schedule(this::pollOnce, link.nextPollDelay());
    }

    private void scheduleReconnect() {
        Duration delay = backoff;
        Duration doubled = backoff.multipliedBy(2);
        backoff = doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
        schedule(this::attemptConnect, delay);
    }

    private void schedule(Runnable task, Duration delay) {
        cancelPending();
        try {
            pending = loop.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        try {
            loop.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        loop.shutdownNow();
    }
}
```

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.protocol.*'`
Expected: PASS except `RendererCommandsTest`, which does not exist yet.

- [ ] **Step 6: Write the fake renderer**

`src/test/java/dev/andre/homecontrol/adapters/upnp/FakeUpnpRenderer.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpXml;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.discovery.ssdp.FakeSsdpResponder;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An in-process UPnP media renderer: JDK HttpServer serving a device description, the
 * RenderingControl SCPD and SOAP control endpoints, with just enough state to observe a session.
 */
public class FakeUpnpRenderer implements AutoCloseable {

    public static final String UDN = "uuid:5f9ec1b3-ed59-4f00-a3c1-2d2b4a1e0001";
    public static final String FRIENDLY_NAME = "Kitchen Speaker";
    public static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String AUDIO_SINK =
            "http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/mp4:*,http-get:*:audio/ogg:*";

    /** Where things live on the device. Task 2 adds a Sonos layout. */
    public record Layout(String descriptionPath, String avTransportPath, String renderingControlPath,
                         String connectionManagerPath, String descriptionFixture) {
        public static final Layout GENERIC = new Layout("/description.xml", "/upnp/control/AVTransport1",
                "/upnp/control/RenderingControl1", "/upnp/control/ConnectionManager1", "fixtures/upnp/renderer-description.xml");
    }

    public record Call(String path, String soapAction, String action, Map<String, String> arguments) {
        public String argument(String name) {
            return arguments.getOrDefault(name, "");
        }
    }

    /** Thrown from {@link #perform} to answer with a UPnP fault. */
    protected static final class Fault extends RuntimeException {
        final int code;
        final String description;

        public Fault(int code, String description) {
            super(description, null, false, false);
            this.code = code;
            this.description = description;
        }
    }

    private record PlannedFault(int code, String description, int remaining) {
    }

    private final Layout layout;
    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, PlannedFault> faults = new ConcurrentHashMap<>();

    protected volatile String transportState = "NO_MEDIA_PRESENT";
    protected volatile String currentUri = "";
    protected volatile String currentMetadata = "";
    private volatile int volume = 20;
    private volatile int volumeMax = 100;
    private volatile boolean muted;
    private volatile String sink = AUDIO_SINK;
    private volatile String relTime = "0:00:00";
    private volatile String trackDuration = "0:00:00";
    private volatile boolean echoMetadata = true;
    private volatile boolean hangUp;

    public FakeUpnpRenderer() throws IOException {
        this("127.0.0.1", Layout.GENERIC);
    }

    protected FakeUpnpRenderer(String bindAddress, Layout layout) throws IOException {
        this.layout = layout;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String host() {
        return server.getAddress().getAddress().getHostAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI location() {
        return URI.create("http://" + host() + ":" + port() + layout.descriptionPath());
    }

    public String searchResponse() throws IOException {
        return FakeSsdpResponder.fixture("renderer-search-response.txt", host(), port());
    }

    /** A registered device for this renderer, as B's addDiscovered would store it. */
    public Device device(String id) {
        return new Device(id, FRIENDLY_NAME, DeviceKind.UPNP, host(),
                Map.of("upnp", Map.of("udn", UDN, "location", location().toString(), "model", "Acme Audio StreamBox 2")),
                Instant.now());
    }

    // --- test hooks -------------------------------------------------------------------------

    public void fail(String action, int code, String description, int times) {
        faults.put(action, new PlannedFault(code, description, times));
    }

    public void hangUp(boolean hangUp) {
        this.hangUp = hangUp;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    public void setVolumeMax(int volumeMax) {
        this.volumeMax = volumeMax;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public void setPosition(String relTime, String trackDuration) {
        this.relTime = relTime;
        this.trackDuration = trackDuration;
    }

    /** false: GetPositionInfo answers TrackMetaData NOT_IMPLEMENTED, as many cheap renderers do. */
    public void echoMetadata(boolean echo) {
        this.echoMetadata = echo;
    }

    /** Something another controller started. */
    public void playElsewhere(String uri, String metadata) {
        currentUri = uri;
        currentMetadata = metadata;
        transportState = "PLAYING";
    }

    public String transportState() {
        return transportState;
    }

    public String currentUri() {
        return currentUri;
    }

    public String currentMetadata() {
        return currentMetadata;
    }

    public int volume() {
        return volume;
    }

    public boolean muted() {
        return muted;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> calls(String action) {
        return calls.stream().filter(call -> call.action().equals(action)).toList();
    }

    /** Calls that change something (everything but Get*), in order. */
    public List<String> commandNames() {
        return calls.stream().map(Call::action).filter(action -> !action.startsWith("Get")).toList();
    }

    public void clearCalls() {
        calls.clear();
    }

    // --- HTTP ----------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        if (hangUp) {
            exchange.close(); // no status line: the client sees an I/O error
            return;
        }
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                String document = document(path);
                reply(exchange, document == null ? 404 : 200, document == null ? "" : document);
                return;
            }
            String serviceType = serviceType(path);
            if (!"POST".equals(exchange.getRequestMethod()) || serviceType == null) {
                reply(exchange, 404, "");
                return;
            }
            soap(exchange, path, serviceType);
        }
    }

    protected String document(String path) throws IOException {
        if (path.equals(layout.descriptionPath())) {
            return resource(layout.descriptionFixture());
        }
        if (path.equals("/scpd/RenderingControl1.xml")) {
            return resource("fixtures/upnp/rendering-control-scpd.xml").replace("{volumeMax}", String.valueOf(volumeMax));
        }
        return null;
    }

    protected String serviceType(String path) {
        if (path.equals(layout.avTransportPath())) {
            return AV_TRANSPORT;
        }
        if (path.equals(layout.renderingControlPath())) {
            return RENDERING_CONTROL;
        }
        if (path.equals(layout.connectionManagerPath())) {
            return CONNECTION_MANAGER;
        }
        return null;
    }

    private void soap(HttpExchange exchange, String path, String serviceType) throws IOException {
        Element body = UpnpXml.firstDescendant(UpnpXml.parse(exchange.getRequestBody().readAllBytes()), "Body").orElseThrow();
        Element request = UpnpXml.childElements(body).getFirst();
        String action = UpnpXml.localName(request);
        Map<String, String> arguments = new LinkedHashMap<>();
        UpnpXml.childElements(request).forEach(argument -> arguments.put(UpnpXml.localName(argument), argument.getTextContent()));
        calls.add(new Call(path, exchange.getRequestHeaders().getFirst("SOAPACTION"), action,
                Collections.unmodifiableMap(arguments)));
        try {
            PlannedFault planned = faults.get(action);
            if (planned != null) {
                if (planned.remaining() <= 1) {
                    faults.remove(action);
                } else {
                    faults.put(action, new PlannedFault(planned.code(), planned.description(), planned.remaining() - 1));
                }
                throw new Fault(planned.code(), planned.description());
            }
            reply(exchange, 200, responseEnvelope(serviceType, action, perform(serviceType, action, arguments)));
        } catch (Fault fault) {
            reply(exchange, 500, faultEnvelope(fault.code, fault.description));
        }
    }

    /** Device behaviour. Subclasses (Sonos) intercept actions and delegate the rest here. */
    protected Map<String, String> perform(String serviceType, String action, Map<String, String> arguments) {
        return switch (action) {
            case "SetAVTransportURI" -> {
                currentUri = arguments.getOrDefault("CurrentURI", "");
                currentMetadata = arguments.getOrDefault("CurrentURIMetaData", "");
                transportState = "STOPPED";
                yield Map.of();
            }
            case "Play" -> {
                if (currentUri.isEmpty()) {
                    throw new Fault(701, "Transition not available");
                }
                transportState = "PLAYING";
                yield Map.of();
            }
            case "Pause" -> {
                transportState = "PAUSED_PLAYBACK";
                yield Map.of();
            }
            case "Stop" -> {
                transportState = currentUri.isEmpty() ? "NO_MEDIA_PRESENT" : "STOPPED";
                yield Map.of();
            }
            case "GetTransportInfo" -> ordered("CurrentTransportState", transportState,
                    "CurrentTransportStatus", "OK", "CurrentSpeed", "1");
            case "GetPositionInfo" -> ordered("Track", currentUri.isEmpty() ? "0" : "1",
                    "TrackDuration", trackDuration,
                    "TrackMetaData", echoMetadata ? currentMetadata : "NOT_IMPLEMENTED",
                    "TrackURI", currentUri, "RelTime", relTime, "AbsTime", "NOT_IMPLEMENTED",
                    "RelCount", "2147483647", "AbsCount", "2147483647");
            case "GetVolume" -> ordered("CurrentVolume", String.valueOf(volume));
            case "SetVolume" -> {
                volume = Integer.parseInt(arguments.getOrDefault("DesiredVolume", "0"));
                yield Map.of();
            }
            case "GetMute" -> ordered("CurrentMute", muted ? "1" : "0");
            case "SetMute" -> {
                String desired = arguments.getOrDefault("DesiredMute", "0");
                muted = desired.equals("1") || desired.equalsIgnoreCase("true");
                yield Map.of();
            }
            case "GetProtocolInfo" -> ordered("Source", "", "Sink", sink);
            default -> throw new Fault(401, "Invalid Action");
        };
    }

    protected static Map<String, String> ordered(String... namesAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            map.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return map;
    }

    static String responseEnvelope(String serviceType, String action, Map<String, String> result) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
                .append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">");
        result.forEach((name, value) -> xml.append('<').append(name).append('>').append(UpnpXml.escape(value))
                .append("</").append(name).append('>'));
        return xml.append("</u:").append(action).append("Response></s:Body></s:Envelope>").toString();
    }

    static String faultEnvelope(int code, String description) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>"
                + "<faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>" + code + "</errorCode><errorDescription>" + UpnpXml.escape(description) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=\"utf-8\"");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    protected static String resource(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/" + name));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

`RendererCommandsTest` (in `adapters/upnp/protocol`; it may use the fake from `adapters.upnp` test package through its public API) — `FakeUpnpRenderer fake`; endpoints `new ServiceEndpoint(AV_TRANSPORT, URI("http://127.0.0.1:<port>/upnp/control/AVTransport1"), null)` and the RenderingControl equivalent; `commands = new RendererCommands(new SoapClient(SoapClient.httpClient(1 s), 1 s), "Kitchen Speaker")`; `PlayMedia song = new PlayMedia(URI.create("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits")`:
- `playsAUrlTheRendererAccepts`: `playUri(av, ProtocolInfo.parseSink(AUDIO_SINK), song, DLNA_STREAMING)` → `fake.commandNames()` = `[SetAVTransportURI, Play]`; `currentUri()` is the URL; `currentMetadata()` contains `protocolInfo="http-get:*:audio/flac:DLNA.ORG_OP=01` and `<dc:title>Bunny Song</dc:title>`; `transportState()` `PLAYING`; the recorded `soapAction` of the first call is `"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"`.
- `retriesOnceAfterStoppingWhenTheTransportIsLocked`: `fake.fail("SetAVTransportURI", 705, "Transport is locked", 1)` → `commandNames()` = `[SetAVTransportURI, Stop, SetAVTransportURI, Play]`.
- `refusesAFormatTheRendererCannotPlay`: `playUri(av, ProtocolInfo.parseSink(AUDIO_SINK), new PlayMedia(URI("http://h/film.mp4"), "video/mp4", "Film", null), "*")` → `UnsupportedActionException` with message `Kitchen Speaker cannot play video/mp4`; `fake.calls()` empty.
- `mapsFaultsTimeoutsAndLostConnections`: `fake.fail("Play", 701, "Transition not available", 1)`; `transport(av, UpnpActions.play(AV_TRANSPORT), "resume playback")` → `ActionFailedException` `Kitchen Speaker refused to resume playback (UPnP error 701: Transition not available)`; `fake.hangUp(true)` → `DeviceOfflineException` `Kitchen Speaker could not be reached to resume playback`.
- `readsTransportVolumeAndSink`: `transportInfo(av)` state `NO_MEDIA_PRESENT`, `active()` false; `fake.setVolume(30)` → `volume(rc, 60)` = `VolumeReading(50, false)`; `sink(cm)` matches `audio/flac`.
- `setsVolumeInDeviceUnits`: `setVolume(rc, 50, 60)` → `fake.volume()` 30; `setMute(rc, true)` → `fake.muted()` true and the recorded `DesiredMute` is `1`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.protocol.*'`
Expected: PASS.

- [ ] **Step 7: Write the failing adapter tests**

`UpnpSessionTest` — `FakeUpnpRenderer fake`; `properties = new UpnpProperties(true, 1, 1, 1, 1, 1, 2)`; `List<DeviceState> states = new CopyOnWriteArrayList<>()`; `session = new UpnpSession(fake.device("kitchen"), properties, SoapClient.httpClient(Duration.ofSeconds(1)), udn -> Optional.empty(), states::add, () -> {})`, `start()`; closed in `@AfterEach`; awaits ≤ 5 s:
- `reportsDisconnectedFirstThenConnectedWithVolume`: `states.getFirst().status()` `DISCONNECTED`; eventually `session.state()` status `CONNECTED`, `powerOn` true, `volumeLevel` 20, `volumeMax` 100, `muted` false.
- `playsPausesResumesAndStops`: await connected; `execute(new PlayMedia(URI("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits"))` → `fake.transportState()` `PLAYING`, `currentUri()` equals; `execute(new Pause())` → `PAUSED_PLAYBACK`; `execute(new Resume())` → `PLAYING` and the last `Play` call has `Speed` `1`; `execute(new Stop())` → `STOPPED`.
- `setsVolumeAndMute`: `execute(new SetVolume(55))` → `fake.volume()` 55; `execute(new Mute(true))` → `fake.muted()`; eventually `state()` `volumeLevel` 55 and `muted` true.
- `usesTheDevicesVolumeRange`: a second fake with `setVolumeMax(50)` and `setVolume(25)` before the session starts → `volumeLevel` 50; `execute(new SetVolume(100))` → fake volume 50.
- `rejectsWhatARendererCannotDo`: `PressKey(HOME)`, `OpenAppLink(https://x)`, `SelectInput("HDMI_1")` → `UnsupportedActionException`.
- `isOfflineWhileTheRendererIsGone`: `fake.hangUp(true)` → eventually `DISCONNECTED`; `execute(new SetVolume(10))` → `DeviceOfflineException`; `fake.hangUp(false)` → `CONNECTED` again within 5 s.
- `followsTheAnnouncedLocation`: device settings `location` = `http://127.0.0.1:9/description.xml` (nothing listens), locator `udn -> UDN.equals(udn) ? Optional.of(fake.location()) : Optional.empty()` → `CONNECTED`.
- `closeStopsPolling`: after connected, `close()`; call count stays equal over 2.5 s.

`UpnpDiscoveryTest` — `FakeSsdpResponder responder`, `FakeUpnpRenderer fake`, `responder.answer(UpnpDiscovery.SEARCH_TARGET, fake.searchResponse())`, `SsdpDiscovery ssdp = new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1))` started, `List<Object> events` as the publisher (`events::add`), `discovery = new UpnpDiscovery(ssdp, events::add)`:
- `findsARendererWithItsDescription`: eventually `devices()` is one `DiscoveredDevice("upnp", "Kitchen Speaker", "127.0.0.1", fake.port(), {udn=uuid:5f9e…0001, location=<fake.location()>, model=Acme Audio StreamBox 2})`.
- `publishesOneDiscoveryEventPerChange`: after `responder.searches()` has grown by 4 more, `events` holds exactly one `DeviceDiscoveredEvent` with that device.
- `knowsTheLatestLocationOfAUdn`: `location(FakeUpnpRenderer.UDN)` = `fake.location()`; `location("uuid:nope")` empty.
- `tellsListenersWhichRendererAnnouncedItself`: `onAlive(udns::add)` → `udns` contains the UDN.
- `ignoresDevicesWithoutAvTransport`: pure — `UpnpDiscovery.toDevice(new SsdpService("uuid:x::urn:schemas-upnp-org:device:MediaRenderer:1", SEARCH_TARGET, "10.0.0.9", URI("http://10.0.0.9:49152/d.xml"), Map.of(), Instant.MAX, new DeviceDescription("TV", "Acme", "X", "uuid:x", List.of(new DeviceDescription.Service("urn:schemas-upnp-org:service:RenderingControl:1", "rc", URI("http://10.0.0.9:49152/rc"), null, null)))))` → empty; the same without a description → empty.

`UpnpAdapterTest`:
- `isAPairingFreeMediaRenderer`: id `upnp`, kind `UPNP`, `capabilities(any)` = `{MEDIA_RENDERER, VOLUME}`; `settingsFor(DiscoveredDevice("upnp", "Kitchen Speaker", "10.0.0.30", 49152, {udn, location, model}))` = `{udn, location, model}`; for adapter id `cast` → empty.
- `connectsARegisteredRenderer`: `adapter.connect(fake.device("kitchen"), states::add)` → eventually `CONNECTED`; `close()`.

`UpnpModuleSwitchTest` — `ApplicationContextRunner` with `.withBean(SsdpDiscovery.class, () -> new SsdpDiscovery(new SsdpProperties(false, "239.255.255.250", 1900, 1900, 60, 2)))` and `.withUserConfiguration(UpnpConfiguration.class)`: `isOnByDefault` (has `UpnpAdapter` and `UpnpDiscovery`), `canBeSwitchedOff` (`home-control.upnp.enabled=false` → neither bean).

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.*'`
Expected: compilation failure — the `adapters.upnp` classes do not exist.

- [ ] **Step 8: Implement the adapter module**

`adapters/upnp/UpnpProperties.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("home-control.upnp")
public record UpnpProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("2") int pollIntervalSeconds,
                             @DefaultValue("10") int idlePollIntervalSeconds,
                             @DefaultValue("5") int commandTimeoutSeconds,
                             @DefaultValue("3") int connectTimeoutSeconds,
                             @DefaultValue("1") int reconnectInitialDelaySeconds,
                             @DefaultValue("60") int reconnectMaxDelaySeconds) {
}
```

`adapters/upnp/UpnpSettings.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-device settings under {@code adapters.upnp}. The location is a hint; the UDN is the identity. */
public record UpnpSettings(String udn, URI location, String model) {

    public static final String ADAPTER_ID = "upnp";
    static final String UDN = "udn";
    static final String LOCATION = "location";
    static final String MODEL = "model";

    public static UpnpSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no upnp adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new UpnpSettings(settings.get(UDN), uri(settings.get(LOCATION)), settings.get(MODEL));
    }

    public static UpnpSettings from(DiscoveredDevice found) {
        return new UpnpSettings(found.attributes().get(UDN), uri(found.attributes().get(LOCATION)), found.attributes().get(MODEL));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (udn != null) {
            map.put(UDN, udn);
        }
        if (location != null) {
            map.put(LOCATION, location.toString());
        }
        if (model != null) {
            map.put(MODEL, model);
        }
        return map;
    }

    private static URI uri(String value) {
        try {
            return value == null || value.isBlank() ? null : URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
```

`adapters/upnp/UpnpDiscovery.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** UPnP/DLNA media renderers seen through the shared SSDP listener (spec §7). */
public class UpnpDiscovery implements AutoCloseable {

    public static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final int DESCRIPTION_RETRIES = 5;
    private static final Logger log = LoggerFactory.getLogger(UpnpDiscovery.class);

    private final SsdpDiscovery ssdp;
    private final ApplicationEventPublisher events;
    private final Map<String, DiscoveredDevice> announced = new ConcurrentHashMap<>();
    private final List<Consumer<String>> aliveListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("upnp-discovery").factory());

    public UpnpDiscovery(SsdpDiscovery ssdp, ApplicationEventPublisher events) {
        this.ssdp = ssdp;
        this.events = events;
        // SSDP listeners must return at once: hand off to our own thread.
        ssdp.addListener(SEARCH_TARGET, service -> submit(() -> seen(service.usn(), 0)));
    }

    public List<DiscoveredDevice> devices() {
        return ssdp.services(SEARCH_TARGET).stream().map(this::map).flatMap(Optional::stream).toList();
    }

    /** The description address most recently announced for {@code udn}. */
    public Optional<URI> location(String udn) {
        return ssdp.services(SEARCH_TARGET).stream()
                .filter(service -> service.location() != null)
                .filter(service -> udn.equalsIgnoreCase(udnOf(service.usn()))
                        || (service.description() != null && udn.equalsIgnoreCase(service.description().udn())))
                .map(SsdpService::location)
                .findFirst();
    }

    public void onAlive(Consumer<String> udnListener) {
        aliveListeners.add(udnListener);
    }

    void seen(String usn, int attempt) {
        Optional<SsdpService> service = ssdp.services(SEARCH_TARGET).stream().filter(s -> s.usn().equals(usn)).findFirst();
        if (service.isEmpty()) {
            return;
        }
        if (service.get().description() == null) {
            // SsdpDiscovery fetches descriptions after telling listeners; look again shortly.
            if (attempt < DESCRIPTION_RETRIES) {
                try {
                    worker.schedule(() -> seen(usn, attempt + 1), 1, TimeUnit.SECONDS);
                } catch (RejectedExecutionException ignored) {
                    // closing
                }
            }
            return;
        }
        map(service.get()).ifPresent(found -> {
            aliveListeners.forEach(listener -> listener.accept(found.attributes().get(UpnpSettings.UDN)));
            DiscoveredDevice previous = announced.put(usn, found);
            if (!found.equals(previous)) {
                log.info("Discovered media renderer {} at {}", found.name(), found.host());
                events.publishEvent(new DeviceDiscoveredEvent(found));
            }
        });
    }

    /** Hook for Task 2 (Sonos filtering); plain mapping here. */
    Optional<DiscoveredDevice> map(SsdpService service) {
        return toDevice(service);
    }

    static Optional<DiscoveredDevice> toDevice(SsdpService service) {
        DeviceDescription description = service.description();
        URI location = service.location();
        if (description == null || location == null || description.service(UpnpActions.AV_TRANSPORT).isEmpty()) {
            return Optional.empty();
        }
        String udn = description.udn() != null ? description.udn() : udnOf(service.usn());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(UpnpSettings.UDN, udn);
        attributes.put(UpnpSettings.LOCATION, location.toString());
        String model = String.join(" ", java.util.stream.Stream.of(description.manufacturer(), description.modelName())
                .filter(Objects::nonNull).filter(part -> !part.isBlank()).toList());
        if (!model.isBlank()) {
            attributes.put(UpnpSettings.MODEL, model);
        }
        String name = description.friendlyName() == null || description.friendlyName().isBlank()
                ? "Media renderer at " + service.address() : description.friendlyName();
        int port = location.getPort() > 0 ? location.getPort() : 80;
        return Optional.of(new DiscoveredDevice(UpnpSettings.ADAPTER_ID, name, service.address(), port, attributes));
    }

    static String udnOf(String usn) {
        int separator = usn.indexOf("::");
        return separator < 0 ? usn : usn.substring(0, separator);
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
```

`adapters/upnp/UpnpSession.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.DidlLite;
import dev.andre.homecontrol.adapters.upnp.protocol.ProtocolInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ReconnectingPoller;
import dev.andre.homecontrol.adapters.upnp.protocol.RendererCommands;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.TransportInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeRange;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeReading;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.DeviceDescriptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** One registered UPnP/DLNA renderer: resolves its services, polls its state, plays URLs. */
public class UpnpSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(UpnpSession.class);

    /** Resolved services; {@code renderingControl} may be null. */
    record Endpoints(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, int volumeMax, ProtocolInfo sink) {
    }

    private final Device device;
    private final UpnpSettings settings;
    private final UpnpProperties properties;
    private final HttpClient http;
    private final RendererCommands commands;
    private final Function<String, Optional<URI>> locator;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClosed;
    private final ReconnectingPoller poller;

    private volatile Endpoints endpoints;
    private volatile TransportInfo transport = TransportInfo.NONE;
    private volatile DeviceState state = DeviceState.initial();

    public UpnpSession(Device device, UpnpProperties properties, HttpClient http,
                       Function<String, Optional<URI>> locator, Consumer<DeviceState> onChange, Runnable onClosed) {
        this.device = device;
        this.settings = UpnpSettings.of(device);
        this.properties = properties;
        this.http = http;
        this.commands = new RendererCommands(new SoapClient(http, Duration.ofSeconds(properties.commandTimeoutSeconds())), device.name());
        this.locator = locator;
        this.onChange = onChange;
        this.onClosed = onClosed;
        this.poller = new ReconnectingPoller("upnp-" + device.id(),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()), new Link());
    }

    public void start() {
        onChange.accept(state);
        poller.start();
    }

    public String udn() {
        return settings.udn();
    }

    public void reconnectNow() {
        poller.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        Endpoints current = endpoints;
        if (current == null || !poller.connected()) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        String av = current.avTransport().serviceType();
        try {
            switch (action) {
                case Action.PlayMedia play -> commands.playUri(current.avTransport(), current.sink(), play, DidlLite.DLNA_STREAMING);
                case Action.Pause ignored -> commands.transport(current.avTransport(), UpnpActions.pause(av), "pause");
                case Action.Resume ignored -> commands.transport(current.avTransport(), UpnpActions.play(av), "resume playback");
                case Action.Stop ignored -> commands.transport(current.avTransport(), UpnpActions.stop(av), "stop playback");
                case Action.SetVolume volume -> commands.setVolume(volumeControl(current), volume.level(), current.volumeMax());
                case Action.Mute mute -> commands.setMute(volumeControl(current), mute.muted());
                case Action.PressKey ignored -> throw unsupported("has no remote keys");
                case Action.OpenAppLink ignored -> throw unsupported("cannot open app links");
                case Action.SelectInput ignored -> throw unsupported("has no inputs");
                case Action.CastLoad ignored -> throw unsupported("is not a Cast receiver");
                case Action.CastMessage ignored -> throw unsupported("is not a Cast receiver");
            }
        } finally {
            poller.pollNow();
        }
    }

    private ServiceEndpoint volumeControl(Endpoints current) {
        if (current.renderingControl() == null) {
            throw unsupported("has no volume control");
        }
        return current.renderingControl();
    }

    private UnsupportedActionException unsupported(String what) {
        return new UnsupportedActionException(device.name() + " is a media renderer and " + what);
    }

    private Endpoints resolve() throws IOException, InterruptedException {
        URI location = Optional.ofNullable(settings.udn()).flatMap(locator).orElse(settings.location());
        if (location == null || location.getHost() == null) {
            throw new IOException(device.id() + " has no description address");
        }
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(location)
                .timeout(Duration.ofSeconds(properties.commandTimeoutSeconds())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for the description of " + device.id());
        }
        DeviceDescription description;
        try {
            description = DeviceDescriptions.parse(response.body(), location);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unreadable description for " + device.id());
        }
        ServiceEndpoint avTransport = service(description, UpnpActions.AV_TRANSPORT, location)
                .orElseThrow(() -> new IOException(device.id() + " offers no usable AVTransport service"));
        ServiceEndpoint renderingControl = service(description, UpnpActions.RENDERING_CONTROL, location).orElse(null);
        ServiceEndpoint connectionManager = service(description, UpnpActions.CONNECTION_MANAGER, location).orElse(null);
        int volumeMax = renderingControl == null ? 0 : volumeMaximum(renderingControl, location);
        ProtocolInfo sink = ProtocolInfo.UNKNOWN;
        if (connectionManager != null) {
            try {
                sink = commands.sink(connectionManager);
            } catch (SoapFault fault) {
                log.debug("{} did not list its formats: {}", device.id(), fault.getMessage());
            }
        }
        return new Endpoints(avTransport, renderingControl, volumeMax, sink);
    }

    /** Services on another host than the description are refused (epic constraint). */
    private Optional<ServiceEndpoint> service(DeviceDescription description, String typePrefix, URI location) {
        return description.service(typePrefix).map(ServiceEndpoint::of).filter(endpoint -> {
            boolean sameHost = endpoint.controlUrl() != null && endpoint.controlUrl().getHost() != null
                    && endpoint.controlUrl().getHost().equalsIgnoreCase(location.getHost());
            if (!sameHost) {
                log.warn("Ignoring {} of {}: its control URL is not on {}", typePrefix, device.id(), location.getHost());
            }
            return sameHost;
        });
    }

    private int volumeMaximum(ServiceEndpoint renderingControl, URI location) {
        URI scpd = renderingControl.scpdUrl();
        if (scpd == null || scpd.getHost() == null || !scpd.getHost().equalsIgnoreCase(location.getHost())) {
            return VolumeRange.DEFAULT_MAXIMUM;
        }
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(scpd)
                    .timeout(Duration.ofSeconds(properties.commandTimeoutSeconds())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? VolumeRange.maximum(response.body()) : VolumeRange.DEFAULT_MAXIMUM;
        } catch (IOException e) {
            return VolumeRange.DEFAULT_MAXIMUM;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VolumeRange.DEFAULT_MAXIMUM;
        }
    }

    /** Reads the device and publishes; runs on the poll loop only. */
    private void readState(Endpoints current) throws IOException, SoapFault {
        TransportInfo info = commands.transportInfo(current.avTransport());
        transport = info;
        DeviceState next = state.withStatus(DeviceStatus.CONNECTED).withPower(true);
        if (current.renderingControl() != null) {
            try {
                VolumeReading volume = commands.volume(current.renderingControl(), current.volumeMax());
                next = next.withVolume(volume.percent(), 100, volume.muted());
            } catch (SoapFault fault) {
                log.debug("{} did not report its volume: {}", device.id(), fault.getMessage());
            }
        }
        publish(next);
    }

    private synchronized void publish(DeviceState next) {
        DeviceState previous = state;
        state = next;
        if (!next.sameIgnoringTime(previous)) {
            try {
                onChange.accept(next);
            } catch (RuntimeException e) {
                log.warn("A device state listener failed for {}", device.id(), e);
            }
        }
    }

    @Override
    public void close() {
        poller.close();
        endpoints = null;
        onClosed.run();
    }

    private final class Link implements ReconnectingPoller.Link {

        @Override
        public void connect() throws Exception {
            Endpoints resolved = resolve();
            endpoints = resolved;
            try {
                readState(resolved);
            } catch (Exception e) {
                endpoints = null;
                throw e;
            }
        }

        @Override
        public void poll() throws Exception {
            Endpoints current = endpoints;
            if (current != null) {
                readState(current);
            }
        }

        @Override
        public Duration nextPollDelay() {
            return Duration.ofSeconds(transport.active() ? properties.pollIntervalSeconds() : properties.idlePollIntervalSeconds());
        }

        @Override
        public void disconnected(Exception cause) {
            endpoints = null;
            transport = TransportInfo.NONE;
            log.debug("Media renderer {} unreachable: {}", device.id(), cause.getMessage());
            publish(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
        }
    }
}
```

Note: `execute` checks `poller.connected()`; `Link.connect` sets `endpoints` before `readState` so the first `publish(CONNECTED)` happens while `poller.connected()` may still be false for a few microseconds — a command arriving in that instant is rejected as offline, which is honest.

`adapters/upnp/UpnpAdapter.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** UPnP/DLNA media renderers (spec §4.1): TVs, AV receivers, Wi-Fi speakers. Pairing-free. */
public class UpnpAdapter implements DeviceAdapter {

    public static final String ID = UpnpSettings.ADAPTER_ID;

    private final UpnpProperties properties;
    private final UpnpDiscovery discovery;
    private final HttpClient http;
    private final Map<String, UpnpSession> sessions = new ConcurrentHashMap<>();

    public UpnpAdapter(UpnpProperties properties, UpnpDiscovery discovery) {
        this.properties = properties;
        this.discovery = discovery;
        this.http = SoapClient.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        // A renderer that announces itself is back: skip the backoff.
        discovery.onAlive(udn -> sessions.values().stream()
                .filter(session -> udn != null && udn.equalsIgnoreCase(session.udn()))
                .forEach(UpnpSession::reconnectNow));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.UPNP;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        AtomicReference<UpnpSession> self = new AtomicReference<>();
        UpnpSession session = new UpnpSession(device, properties, http, discovery::location, onChange,
                () -> sessions.remove(device.id(), self.get()));
        self.set(session);
        sessions.put(device.id(), session);
        session.start();
        return session;
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return discovery.devices();
    }

    @Override
    public Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return ID.equals(found.adapterId()) ? Optional.of(UpnpSettings.from(found).toMap()) : Optional.empty();
    }
}
```

`adapters/upnp/UpnpConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The UPnP renderer module. {@code home-control.upnp.enabled=false} removes discovery and the adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.upnp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(UpnpProperties.class)
public class UpnpConfiguration {

    @Bean(destroyMethod = "close")
    public UpnpDiscovery upnpDiscovery(SsdpDiscovery ssdp, ApplicationEventPublisher events) {
        return new UpnpDiscovery(ssdp, events);
    }

    @Bean
    public UpnpAdapter upnpAdapter(UpnpProperties properties, UpnpDiscovery discovery) {
        return new UpnpAdapter(properties, discovery);
    }
}
```

`src/main/resources/application.yaml` — under the existing top-level `home-control:` key add:

```yaml
  upnp:
    # DLNA/UPnP media renderers found over SSDP (needs host networking).
    enabled: true
    poll-interval-seconds: 2
    idle-poll-interval-seconds: 10
    command-timeout-seconds: 5
    connect-timeout-seconds: 3
    reconnect-initial-delay-seconds: 1
    reconnect-max-delay-seconds: 60
```

`src/test/resources/application.yaml` — under `home-control:` add `upnp: { enabled: true }` in block style (SSDP is already disabled there, so nothing is discovered).

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.*'`
Expected: PASS.

- [ ] **Step 9: Write the failing web tests**

`web/DeviceControllerTest.java`:
- `pausesAndResumes`: `POST /devices/shield/pause` → 204 and `verify(devices).execute("shield", new Action.Pause())`; `POST /devices/shield/resume` → 204 and `new Action.Resume()`; `POST /devices/ghost/pause` → 404.

`web/DashboardPageTest.java` — `aMediaRendererGetsPlaybackControls`: device `Device("upnp-10-0-0-30", "Kitchen Speaker", DeviceKind.UPNP, "10.0.0.30", {upnp: {udn: uuid:x}}, now)`, capabilities `{MEDIA_RENDERER, VOLUME}` (stubs as in B's `aCastOnlyDeviceGetsCastControlsInsteadOfTheRemote`) → page contains `/devices/upnp-10-0-0-30/pause`, `/resume`, `/stop`, `/volume`, `/mute` and not `/devices/upnp-10-0-0-30/key/`. In B's Cast-only test add `not(containsString("/devices/cast-10-0-0-9/pause"))`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`
Expected: FAIL — 404 for pause/resume, no playback section.

- [ ] **Step 10: Implement the web changes**

`web/DeviceController.java` (next to B's volume/mute/stop, reusing its `command` helper):

```java
    @PostMapping("/devices/{id}/pause")
    public ResponseEntity<String> pause(@PathVariable String id) {
        return command(id, new Action.Pause());
    }

    @PostMapping("/devices/{id}/resume")
    public ResponseEntity<String> resume(@PathVariable String id) {
        return command(id, new Action.Resume());
    }
```

`web/DashboardController.dashboard`: `model.addAttribute("rendererControls", capabilities.contains(Capability.MEDIA_RENDERER));` (from the capability set B computes once).

`dashboard.html` — next to B's `<section class="cast" …>` (wherever D placed the drawer sections):

```html
    <section class="renderer" th:if="${rendererControls}" aria-label="Playback">
        <h2>Playback</h2>
        <label class="volume" th:unless="${castControls}">Volume
            <input type="range" name="level" min="0" max="100" step="1"
                   th:value="${selectedState.volumeLevel()}"
                   th:attr="hx-post=@{/devices/{id}/volume(id=${id})}" hx-trigger="change">
        </label>
        <div class="row">
            <button th:attr="hx-post=@{/devices/{id}/pause(id=${id})}">Pause</button>
            <button th:attr="hx-post=@{/devices/{id}/resume(id=${id})}">Play</button>
            <button th:attr="hx-post=@{/devices/{id}/stop(id=${id})}">Stop</button>
            <th:block th:unless="${castControls}">
                <button th:attr="hx-post=@{/devices/{id}/mute(id=${id})}" hx-vals='{"muted": "true"}'>Mute</button>
                <button th:attr="hx-post=@{/devices/{id}/mute(id=${id})}" hx-vals='{"muted": "false"}'>Unmute</button>
            </th:block>
        </div>
    </section>
```

`app.css`: change B's `.cast .volume` and `.cast .volume input` selectors to `.cast .volume, .renderer .volume` and `.cast .volume input, .renderer .volume input`.

- [ ] **Step 11: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat: UPnP media renderers — discovery, playback, volume and playback controls"
```

---

### Task 2: I2 · Sonos adapter

**Files:**
- Create: `core/GroupMember.java`, `core/SpeakerGroup.java`, `core/SpeakerTopology.java`, `core/GroupListing.java`; `adapters/sonos/protocol/SonosEndpoints.java`, `SonosActions.java`, `SonosUris.java`, `ZoneGroupState.java`; `adapters/sonos/SonosProperties.java`, `SonosSettings.java`, `SonosDiscovery.java`, `SonosSession.java`, `SonosAdapter.java`, `SonosConfiguration.java`
- Modify: `core/Action.java`, `device/DeviceManager.java`, every exhaustive `switch` over `Action` (now including `adapters/upnp/UpnpSession.java`), `adapters/upnp/UpnpDiscovery.java`, `adapters/upnp/UpnpConfiguration.java`, `web/DeviceController.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/app.css`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `core/ActionTest.java`, `core/SpeakerTopologyTest.java`, `device/DeviceManagerTest.java`, `adapters/upnp/UpnpDiscoveryTest.java`, `adapters/upnp/FakeUpnpRenderer.java` (unchanged API), `adapters/sonos/FakeSonosHousehold.java`, `FakeSonosPlayer.java`, `adapters/sonos/protocol/ZoneGroupStateTest.java`, `SonosUrisTest.java`, `adapters/sonos/SonosDiscoveryTest.java`, `SonosSessionTest.java`, `SonosAdapterTest.java`, `SonosModuleSwitchTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`; fixtures `src/test/resources/fixtures/sonos/zone-group-state.xml`, `zone-group-state-legacy.xml`

**Interfaces:**
- Consumes: Task 1 (`UpnpXml`, `SoapClient`, `SoapFault`, `UpnpActions`, `ServiceEndpoint`, `TransportInfo`, `ProtocolInfo`, `RendererCommands`, `ReconnectingPoller`, `FakeUpnpRenderer` with `Layout`, `perform`, `ordered`, `Fault`, `document`, `serviceType`, `Action.PlayMedia/Pause/Resume`); F1 (`SsdpDiscovery`, `SsdpService`, `FakeSsdpResponder`); B (`DeviceDiscoveredEvent`, `DeviceAdapter.settingsFor/kind`, `DeviceManager.handles`); F (`InputListing` pattern, `DeviceState.sameIgnoringTime`).
- Produces:
  - `record GroupMember(String memberId, String name)`; `record SpeakerGroup(String coordinatorId, List<GroupMember> members)` with `boolean contains(String memberId)`, `String label()`; `record SpeakerTopology(String selfId, List<SpeakerGroup> groups)` with `Optional<SpeakerGroup> ownGroup()`, `List<SpeakerGroup> otherGroups()`, `boolean grouped()`; `interface GroupListing { Optional<SpeakerTopology> speakerTopology(); }`.
  - `Action.JoinGroup(String memberId)` and `Action.LeaveGroup()` → `MEDIA_RENDERER`.
  - `DeviceManager.speakerTopology(String id) → Optional<SpeakerTopology>`.
  - `ZoneGroupState(List<Group> groups)` with `static parse(String xml)`, `Optional<Group> groupOf(String uuid)`, `Optional<Member> member(String uuid)`, `List<Member> visibleMembers()`; `record Group(String coordinator, String id, List<Member> members)` with `contains`, `coordinatorMember()`, `visibleMembers()`; `record Member(String uuid, URI location, String zoneName, boolean invisible)` with `host()`, `port()`.
  - `SonosEndpoints` (paths, service types, `DEFAULT_PORT = 1400`, `endpoint(host, port, path, type)`); `SonosActions.getZoneGroupState()`; `SonosUris.groupWith(uuid)`, `groupedTo(trackUri)`, `forPlayback(URI, mime)`.
  - `record SonosProperties(boolean enabled, int pollIntervalSeconds, int idlePollIntervalSeconds, int topologyIntervalSeconds, int commandTimeoutSeconds, int connectTimeoutSeconds, int reconnectInitialDelaySeconds, int reconnectMaxDelaySeconds)` bound to `home-control.sonos`.
  - `record SonosSettings(String uuid, int port)` with `ADAPTER_ID = "sonos"`, `of`, `from`, `toMap`.
  - `SonosDiscovery(SsdpDiscovery, SonosProperties, ApplicationEventPublisher)` with `SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"`, `devices()`, `onAlive(Consumer<String> uuid)`, `close()`.
  - `SonosSession implements DeviceHandle, GroupListing`; `SonosAdapter(SonosProperties, SonosDiscovery)` id `sonos`, kind `SONOS`, capabilities `MEDIA_RENDERER, VOLUME`, pairing-free.
  - `UpnpDiscovery(SsdpDiscovery, ApplicationEventPublisher, boolean ignoreSonos)`; `static Optional<DiscoveredDevice> toDevice(SsdpService, boolean ignoreSonos)`.
  - `POST /devices/{id}/group/join/{memberId}`, `POST /devices/{id}/group/leave` → 204 with `HX-Refresh: true` / 404 / 409 / 422 / 502. Dashboard model attribute `speakerTopology` (nullable).

**Sonos wire format (normative).** Control URLs are `http://<player host>:<port, 1400>` plus: AVTransport `/MediaRenderer/AVTransport/Control` (`urn:schemas-upnp-org:service:AVTransport:1`), RenderingControl `/MediaRenderer/RenderingControl/Control` (`…:RenderingControl:1`), ConnectionManager `/MediaRenderer/ConnectionManager/Control` (`…:ConnectionManager:1`), ZoneGroupTopology `/ZoneGroupTopology/Control` (`urn:schemas-upnp-org:service:ZoneGroupTopology:1`). Envelopes as in Task 1.
- Topology: `GetZoneGroupState` (no arguments) → answer argument `ZoneGroupState` whose text is an XML document, either `<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_…" ID="…">…</ZoneGroup></ZoneGroups><VanishedDevices/></ZoneGroupState>` (current firmware) or `<ZoneGroups>…</ZoneGroups>` (older firmware). Each `ZoneGroup` has direct `ZoneGroupMember` children with attributes `UUID`, `Location`, `ZoneName`, optional `Invisible="1"` (bonded stereo partner / surround / sub); `Satellite` children of a member are never devices.
- Join a group: on the joining player's AVTransport, `SetAVTransportURI` with `CurrentURI` = `x-rincon:<coordinator uuid>` and empty `CurrentURIMetaData`. Exact envelope: `<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><CurrentURI>x-rincon:RINCON_000E58A0B1C201400</CurrentURI><CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>`.
- Leave: `BecomeCoordinatorOfStandaloneGroup` (`InstanceID` 0) on the leaving player's AVTransport.
- Play: `SetAVTransportURI` + `Play` on the **coordinator**; DIDL additional info `*`; `CurrentURI` from `SonosUris.forPlayback`.
- SSDP answer of a player: `ST: urn:schemas-upnp-org:device:ZonePlayer:1`, `USN: uuid:RINCON_…::urn:schemas-upnp-org:device:ZonePlayer:1`, `LOCATION: http://<ip>:1400/xml/device_description.xml`, `X-RINCON-HOUSEHOLD: Sonos_…`.

- [ ] **Step 1: Add the topology fixtures**

`src/test/resources/fixtures/sonos/zone-group-state.xml` (the unescaped text of a `ZoneGroupState` answer: a stereo pair in the living room, a kitchen, an office with a sub):

```xml
<ZoneGroupState><ZoneGroups><ZoneGroup Coordinator="RINCON_000E58A0B1C201400" ID="RINCON_000E58A0B1C201400:3471562718"><ZoneGroupMember UUID="RINCON_000E58A0B1C201400" Location="http://192.168.1.70:1400/xml/device_description.xml" ZoneName="Living Room" Icon="" Configuration="1" SoftwareVersion="85.0-65020" SWGen="2" BootSeq="98" ChannelMapSet="RINCON_000E58A0B1C201400:LF,LF;RINCON_000E58A0B1C201401:RF,RF"/><ZoneGroupMember UUID="RINCON_000E58A0B1C201401" Location="http://192.168.1.72:1400/xml/device_description.xml" ZoneName="Living Room" Invisible="1" SoftwareVersion="85.0-65020" SWGen="2" BootSeq="97"/></ZoneGroup><ZoneGroup Coordinator="RINCON_000E58C3D4E501400" ID="RINCON_000E58C3D4E501400:1122334455"><ZoneGroupMember UUID="RINCON_000E58C3D4E501400" Location="http://192.168.1.71:1400/xml/device_description.xml" ZoneName="Kitchen" SoftwareVersion="85.0-65020" SWGen="2" BootSeq="40"/></ZoneGroup><ZoneGroup Coordinator="RINCON_000E58F6A7B801400" ID="RINCON_000E58F6A7B801400:998877"><ZoneGroupMember UUID="RINCON_000E58F6A7B801400" Location="http://192.168.1.73:1400/xml/device_description.xml" ZoneName="Office &amp; Studio" SoftwareVersion="85.0-65020" SWGen="2" BootSeq="12"><Satellite UUID="RINCON_000E58F6A7B901400" Location="http://192.168.1.74:1400/xml/device_description.xml" ZoneName="Office &amp; Studio" Invisible="1" SoftwareVersion="85.0-65020"/></ZoneGroupMember></ZoneGroup></ZoneGroups><VanishedDevices></VanishedDevices></ZoneGroupState>
```

`src/test/resources/fixtures/sonos/zone-group-state-legacy.xml`:

```xml
<ZoneGroups><ZoneGroup Coordinator="RINCON_000E58A0B1C201400" ID="RINCON_000E58A0B1C201400:57"><ZoneGroupMember UUID="RINCON_000E58A0B1C201400" Location="http://192.168.1.70:1400/xml/device_description.xml" ZoneName="Living Room" SoftwareVersion="57.3-77280"/><ZoneGroupMember UUID="RINCON_000E58C3D4E501400" Location="http://192.168.1.71:1400/xml/device_description.xml" ZoneName="Kitchen" SoftwareVersion="57.3-77280"/></ZoneGroup></ZoneGroups>
```

- [ ] **Step 2: Write the failing core and protocol tests**

`core/ActionTest.java`: `groupingRequiresAMediaRenderer` — `new Action.JoinGroup("RINCON_1").requires()` and `new Action.LeaveGroup().requires()` are `MEDIA_RENDERER`; `new Action.JoinGroup(" ")` → `IllegalArgumentException` with message `Pick a speaker to join`.

`core/SpeakerTopologyTest.java` — topology `selfId` `K`, groups `[SpeakerGroup("L", [GroupMember("L","Living Room"), GroupMember("K","Kitchen")]), SpeakerGroup("O", [GroupMember("O","Office")])]`:
- `findsTheOwnGroupAndTheOthers`: `ownGroup()` is the first group; `otherGroups()` is `[Office group]`; `grouped()` true; `label()` of the first is `Living Room + Kitchen`.
- `aSpeakerAloneIsNotGrouped`: selfId `O` → `grouped()` false; `otherGroups()` has the living room group.
- `anUnknownSelfHasNoOwnGroup`: selfId `X` → `ownGroup()` empty; `otherGroups()` both groups.

`device/DeviceManagerTest.java` — `speakerTopologyComesFromTheFirstHandleThatHasOne`: an inline adapter whose handle implements `GroupListing` returning a topology → `speakerTopology("speaker")` equals it; a device whose handles do not implement it → empty; unknown id → empty.

`adapters/sonos/protocol/ZoneGroupStateTest.java`:
- `readsGroupsMembersAndCoordinators`: modern fixture → 3 groups; first group coordinator `RINCON_000E58A0B1C201400`, id `RINCON_000E58A0B1C201400:3471562718`, members `[RINCON_000E58A0B1C201400 (visible), RINCON_000E58A0B1C201401 (invisible)]`; `member("RINCON_000E58A0B1C201400")` has host `192.168.1.70`, port 1400, zoneName `Living Room`; the office zone name is `Office & Studio`.
- `visibleMembersSkipBondedSpeakersAndSatellites`: `visibleMembers()` uuids in order `[RINCON_000E58A0B1C201400, RINCON_000E58C3D4E501400, RINCON_000E58F6A7B801400]`; `member("RINCON_000E58F6A7B901400")` empty.
- `groupOfFindsTheGroupOfAnyMember`: `groupOf("RINCON_000E58A0B1C201401")` is the living room group; `coordinatorMember()` of it is the uuid `…1400` member; `groupOf("RINCON_NOPE")` empty.
- `readsTheLegacyShape`: legacy fixture → 1 group with 2 visible members.
- `refusesDoctypesAndGarbage`: `parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><ZoneGroups>&e;</ZoneGroups>")`, `parse("")`, `parse("nope")` → `IllegalArgumentException`; a member with `Location="::bad"` is skipped, not fatal.

`adapters/sonos/protocol/SonosUrisTest.java`:
- `joiningAGroupPointsAtItsCoordinator`: `groupWith("RINCON_000E58A0B1C201400")` = `x-rincon:RINCON_000E58A0B1C201400`; `groupedTo("x-rincon:RINCON_X")` = `RINCON_X`; `groupedTo("http://h/a.mp3")` and `groupedTo(null)` empty.
- `filesPlayAsTheyAre`: `forPlayback(URI("http://nas.local/music/song.flac"), "audio/flac")` unchanged; `http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.mp3?static=true&ApiKey=t` with `audio/mpeg` unchanged; `https://radio.example.org/live` with `audio/mpeg` unchanged.
- `anExtensionlessMp3StreamIsRadio`: `forPlayback(URI("http://radio.example.org:8000/live?sid=1"), "audio/mpeg")` = `x-rincon-mp3radio://radio.example.org:8000/live?sid=1`; `http://radio.example.org/` with `audio/mpeg` → `x-rincon-mp3radio://radio.example.org/`; `http://radio.example.org/live` with `audio/aac` unchanged.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.sonos.protocol.*'`
Expected: compilation failure — the grouping types and the Sonos protocol package do not exist.

- [ ] **Step 3: Implement core grouping and the Sonos protocol package**

`core/GroupMember.java`: `public record GroupMember(String memberId, String name) {}`

`core/SpeakerGroup.java`:

```java
package dev.andre.homecontrol.core;

import java.util.List;
import java.util.stream.Collectors;

/** Speakers playing in sync; the coordinator is listed first. */
public record SpeakerGroup(String coordinatorId, List<GroupMember> members) {

    public SpeakerGroup {
        members = List.copyOf(members);
    }

    public boolean contains(String memberId) {
        return members.stream().anyMatch(member -> member.memberId().equals(memberId));
    }

    public String label() {
        return members.stream().map(GroupMember::name).collect(Collectors.joining(" + "));
    }
}
```

`core/SpeakerTopology.java`:

```java
package dev.andre.homecontrol.core;

import java.util.List;
import java.util.Optional;

/** The speaker groups a device can see, from that device's point of view ({@code selfId}). */
public record SpeakerTopology(String selfId, List<SpeakerGroup> groups) {

    public SpeakerTopology {
        groups = List.copyOf(groups);
    }

    public Optional<SpeakerGroup> ownGroup() {
        return groups.stream().filter(group -> group.contains(selfId)).findFirst();
    }

    public List<SpeakerGroup> otherGroups() {
        return groups.stream().filter(group -> !group.contains(selfId)).toList();
    }

    public boolean grouped() {
        return ownGroup().map(group -> group.members().size() > 1).orElse(false);
    }
}
```

`core/GroupListing.java`:

```java
package dev.andre.homecontrol.core;

import java.util.Optional;

/** Implemented by handles whose device can be grouped with others; empty while unknown or disconnected. */
public interface GroupListing {
    Optional<SpeakerTopology> speakerTopology();
}
```

`core/Action.java` — add:

```java
    /** Join the speaker group that contains {@code memberId} (a {@link GroupMember#memberId()}). */
    record JoinGroup(String memberId) implements Action {
        public JoinGroup {
            if (memberId == null || memberId.isBlank()) {
                throw new IllegalArgumentException("Pick a speaker to join");
            }
        }

        @Override
        public Capability requires() {
            return Capability.MEDIA_RENDERER;
        }
    }

    record LeaveGroup() implements Action {
        @Override
        public Capability requires() {
            return Capability.MEDIA_RENDERER;
        }
    }
```

Every exhaustive switch (`grep -rln "case Action.PressKey" src/main/java`), including `UpnpSession.execute` (message helper `unsupported("cannot be grouped")`):

```java
            case Action.JoinGroup ignored -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
            case Action.LeaveGroup ignored -> throw new UnsupportedActionException(device.name() + " cannot be grouped");
```

`device/DeviceManager.java` — add (import `GroupListing`, `SpeakerTopology`):

```java
    /** Grouping as seen by the first of the device's handles that knows it; empty otherwise. */
    public Optional<SpeakerTopology> speakerTopology(String id) {
        return handles.getOrDefault(id, Map.of()).values().stream()
                .filter(GroupListing.class::isInstance)
                .map(handle -> ((GroupListing) handle).speakerTopology())
                .flatMap(Optional::stream)
                .findFirst();
    }
```

`adapters/sonos/protocol/SonosEndpoints.java`:

```java
package dev.andre.homecontrol.adapters.sonos.protocol;

import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;

import java.net.URI;

/** Fixed Sonos control paths (identical on S1 and S2 firmware; SoCo services.py). */
public final class SonosEndpoints {

    public static final int DEFAULT_PORT = 1400;
    public static final String AV_TRANSPORT_PATH = "/MediaRenderer/AVTransport/Control";
    public static final String RENDERING_CONTROL_PATH = "/MediaRenderer/RenderingControl/Control";
    public static final String CONNECTION_MANAGER_PATH = "/MediaRenderer/ConnectionManager/Control";
    public static final String ZONE_GROUP_TOPOLOGY_PATH = "/ZoneGroupTopology/Control";
    public static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";
    public static final String ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";

    private SonosEndpoints() {
    }

    public static ServiceEndpoint endpoint(String host, int port, String path, String serviceType) {
        return new ServiceEndpoint(serviceType, URI.create("http://" + host + ":" + port + path), null);
    }
}
```

`adapters/sonos/protocol/SonosActions.java`:

```java
package dev.andre.homecontrol.adapters.sonos.protocol;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapRequest;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;

public final class SonosActions {

    private SonosActions() {
    }

    public static SoapRequest getZoneGroupState() {
        return UpnpActions.request(SonosEndpoints.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState");
    }
}
```

`adapters/sonos/protocol/SonosUris.java`:

```java
package dev.andre.homecontrol.adapters.sonos.protocol;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class SonosUris {

    private static final String GROUP_PREFIX = "x-rincon:";

    private SonosUris() {
    }

    public static String groupWith(String coordinatorUuid) {
        return GROUP_PREFIX + coordinatorUuid;
    }

    /** The coordinator a member's TrackURI points at, if it is following a group. */
    public static Optional<String> groupedTo(String trackUri) {
        return trackUri != null && trackUri.startsWith(GROUP_PREFIX)
                ? Optional.of(trackUri.substring(GROUP_PREFIX.length())) : Optional.empty();
    }

    /** Endless MP3 streams (Icecast style: http, audio/mpeg, no file extension) need Sonos' radio scheme. */
    public static URI forPlayback(URI url, String mimeType) {
        if (!"http".equalsIgnoreCase(url.getScheme()) || mimeType == null
                || !mimeType.strip().toLowerCase(Locale.ROOT).startsWith("audio/mpeg")) {
            return url;
        }
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.contains(".")) {
            return url;
        }
        return URI.create("x-rincon-mp3radio://" + url.getRawAuthority() + (path.isEmpty() ? "/" : path)
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery()));
    }
}
```

`adapters/sonos/protocol/ZoneGroupState.java`:

```java
package dev.andre.homecontrol.adapters.sonos.protocol;

import dev.andre.homecontrol.adapters.upnp.protocol.UpnpXml;
import org.w3c.dom.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A Sonos household's groups, from ZoneGroupTopology#GetZoneGroupState (both firmware shapes). */
public record ZoneGroupState(List<Group> groups) {

    public record Member(String uuid, URI location, String zoneName, boolean invisible) {
        public String host() {
            return location.getHost();
        }

        public int port() {
            return location.getPort() > 0 ? location.getPort() : SonosEndpoints.DEFAULT_PORT;
        }
    }

    public record Group(String coordinator, String id, List<Member> members) {
        public Group {
            members = List.copyOf(members);
        }

        public boolean contains(String uuid) {
            return members.stream().anyMatch(member -> member.uuid().equals(uuid));
        }

        public Optional<Member> coordinatorMember() {
            return members.stream().filter(member -> member.uuid().equals(coordinator)).findFirst();
        }

        public List<Member> visibleMembers() {
            return members.stream().filter(member -> !member.invisible()).toList();
        }
    }

    public ZoneGroupState {
        groups = List.copyOf(groups);
    }

    public static ZoneGroupState parse(String xml) {
        Element root = UpnpXml.parse(xml);
        List<Group> groups = new ArrayList<>();
        List<Element> groupElements = "ZoneGroup".equals(UpnpXml.localName(root)) ? List.of(root) : UpnpXml.descendants(root, "ZoneGroup");
        for (Element group : groupElements) {
            List<Member> members = new ArrayList<>();
            for (Element member : UpnpXml.childElements(group)) {
                if (!"ZoneGroupMember".equals(UpnpXml.localName(member))) {
                    continue; // Satellite elements are children of members, never of groups
                }
                String uuid = member.getAttribute("UUID");
                URI location;
                try {
                    location = URI.create(member.getAttribute("Location"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (uuid.isBlank() || location.getHost() == null) {
                    continue;
                }
                members.add(new Member(uuid, location, member.getAttribute("ZoneName"), "1".equals(member.getAttribute("Invisible"))));
            }
            groups.add(new Group(group.getAttribute("Coordinator"), group.getAttribute("ID"), members));
        }
        return new ZoneGroupState(groups);
    }

    public Optional<Group> groupOf(String uuid) {
        return groups.stream().filter(group -> group.contains(uuid)).findFirst();
    }

    public Optional<Member> member(String uuid) {
        return groups.stream().flatMap(group -> group.members().stream()).filter(member -> member.uuid().equals(uuid)).findFirst();
    }

    public List<Member> visibleMembers() {
        return groups.stream().flatMap(group -> group.visibleMembers().stream()).toList();
    }
}
```

Run the Step 2 command. Expected: PASS.

- [ ] **Step 4: Write the Sonos fakes**

`src/test/java/dev/andre/homecontrol/adapters/sonos/FakeSonosHousehold.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.protocol.UpnpXml;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Several fake players sharing one topology. Bind players to distinct loopback addresses (127.0.0.2, …). */
public class FakeSonosHousehold implements AutoCloseable {

    public static final String HOUSEHOLD = "Sonos_7yHxP2wqKbL1aZ3mN5cD9eF0gH";

    private final List<FakeSonosPlayer> players = new CopyOnWriteArrayList<>();
    /** member uuid → coordinator uuid */
    private final Map<String, String> coordinatorOf = new ConcurrentHashMap<>();

    public FakeSonosPlayer addPlayer(String bindAddress, String uuid, String zoneName) throws IOException {
        FakeSonosPlayer player = new FakeSonosPlayer(this, bindAddress, uuid, zoneName);
        players.add(player);
        coordinatorOf.put(uuid, uuid);
        return player;
    }

    public synchronized void removePlayer(FakeSonosPlayer player) {
        leave(player.uuid());
        players.remove(player);
        coordinatorOf.remove(player.uuid());
        player.close();
    }

    public synchronized void join(String member, String coordinator) {
        coordinatorOf.put(member, coordinatorOf(coordinator));
    }

    /** The leaving player stands alone; if it coordinated others, the first of them takes over. */
    public synchronized void leave(String member) {
        List<String> followers = coordinatorOf.entrySet().stream()
                .filter(entry -> entry.getValue().equals(member) && !entry.getKey().equals(member))
                .map(Map.Entry::getKey).sorted().toList();
        coordinatorOf.put(member, member);
        followers.forEach(follower -> coordinatorOf.put(follower, followers.getFirst()));
    }

    public String coordinatorOf(String uuid) {
        return coordinatorOf.getOrDefault(uuid, uuid);
    }

    public boolean isCoordinator(String uuid) {
        return coordinatorOf(uuid).equals(uuid);
    }

    public synchronized String zoneGroupState() {
        StringBuilder xml = new StringBuilder("<ZoneGroupState><ZoneGroups>");
        for (FakeSonosPlayer coordinator : players) {
            if (!isCoordinator(coordinator.uuid())) {
                continue;
            }
            xml.append("<ZoneGroup Coordinator=\"").append(coordinator.uuid()).append("\" ID=\"")
                    .append(coordinator.uuid()).append(":1\">");
            for (FakeSonosPlayer member : players) {
                if (coordinatorOf(member.uuid()).equals(coordinator.uuid())) {
                    xml.append("<ZoneGroupMember UUID=\"").append(member.uuid()).append("\" Location=\"")
                            .append(member.location()).append("\" ZoneName=\"").append(UpnpXml.escape(member.zoneName()))
                            .append("\" SoftwareVersion=\"85.0-65020\" BootSeq=\"98\"/>");
                }
            }
            xml.append("</ZoneGroup>");
        }
        return xml.append("</ZoneGroups><VanishedDevices></VanishedDevices></ZoneGroupState>").toString();
    }

    @Override
    public void close() {
        players.forEach(FakeSonosPlayer::close);
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/sonos/FakeSonosPlayer.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** One Sonos player: fixed Sonos paths, ZoneGroupTopology, x-rincon joining, coordinator-only transport. */
public class FakeSonosPlayer extends FakeUpnpRenderer {

    static final Layout SONOS = new Layout("/xml/device_description.xml", "/MediaRenderer/AVTransport/Control",
            "/MediaRenderer/RenderingControl/Control", "/MediaRenderer/ConnectionManager/Control",
            "fixtures/ssdp/sonos-description.xml");
    static final String ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1";
    private static final Set<String> COORDINATOR_ONLY = Set.of("SetAVTransportURI", "Play", "Pause", "Stop");

    private final FakeSonosHousehold household;
    private final String uuid;
    private final String zoneName;

    FakeSonosPlayer(FakeSonosHousehold household, String bindAddress, String uuid, String zoneName) throws IOException {
        super(bindAddress, SONOS);
        this.household = household;
        this.uuid = uuid;
        this.zoneName = zoneName;
        setSink("http-get:*:audio/mpeg:*,http-get:*:audio/flac:*,http-get:*:audio/mp4:*,x-rincon-mp3radio:*:*:*,x-rincon:*:*:*");
    }

    public String uuid() {
        return uuid;
    }

    public String zoneName() {
        return zoneName;
    }

    @Override
    public String searchResponse() {
        return "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nEXT:\r\nLOCATION: " + location()
                + "\r\nSERVER: Linux UPnP/1.0 Sonos/85.0-65020 (ZPS1)\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\nUSN: uuid:"
                + uuid + "::urn:schemas-upnp-org:device:ZonePlayer:1\r\nX-RINCON-HOUSEHOLD: " + FakeSonosHousehold.HOUSEHOLD
                + "\r\nX-RINCON-BOOTSEQ: 98\r\n\r\n";
    }

    @Override
    public Device device(String id) {
        return new Device(id, zoneName, DeviceKind.SONOS, host(),
                Map.of("sonos", Map.of("uuid", uuid, "port", String.valueOf(port()))), Instant.now());
    }

    @Override
    protected String serviceType(String path) {
        return path.equals("/ZoneGroupTopology/Control") ? ZONE_GROUP_TOPOLOGY : super.serviceType(path);
    }

    @Override
    protected Map<String, String> perform(String serviceType, String action, Map<String, String> arguments) {
        if (ZONE_GROUP_TOPOLOGY.equals(serviceType)) {
            if (!action.equals("GetZoneGroupState")) {
                throw new Fault(401, "Invalid Action");
            }
            return ordered("ZoneGroupState", household.zoneGroupState());
        }
        if (AV_TRANSPORT.equals(serviceType)) {
            String uri = arguments.getOrDefault("CurrentURI", "");
            if (action.equals("SetAVTransportURI") && uri.startsWith("x-rincon:")) {
                household.join(uuid, uri.substring("x-rincon:".length()));
                return Map.of();
            }
            if (action.equals("BecomeCoordinatorOfStandaloneGroup")) {
                household.leave(uuid);
                return Map.of();
            }
            boolean coordinator = household.isCoordinator(uuid);
            if (!coordinator && COORDINATOR_ONLY.contains(action)) {
                throw new Fault(701, "Transition not available"); // what a member answers; sessions must never hit it
            }
            if (!coordinator && action.equals("GetPositionInfo")) {
                return ordered("Track", "1", "TrackDuration", "0:00:00", "TrackMetaData", "",
                        "TrackURI", "x-rincon:" + household.coordinatorOf(uuid), "RelTime", "0:00:00",
                        "AbsTime", "NOT_IMPLEMENTED", "RelCount", "2147483647", "AbsCount", "2147483647");
            }
        }
        return super.perform(serviceType, action, arguments);
    }
}
```

(`FakeUpnpRenderer.device(String)` and `searchResponse()` are non-final so they can be overridden; `searchResponse()` in the base declares `throws IOException`, the override may drop it.)

- [ ] **Step 5: Write the failing adapter tests**

Constants for all Sonos tests: `LIVING = "RINCON_000E58A0B1C201400"` at `127.0.0.2` "Living Room", `KITCHEN = "RINCON_000E58C3D4E501400"` at `127.0.0.3` "Kitchen" (`household.addPlayer(...)`); households closed in `@AfterEach`.

`SonosDiscoveryTest` — `FakeSsdpResponder responder` answering `SonosDiscovery.SEARCH_TARGET` with `living.searchResponse()`; `SsdpDiscovery` as in Task 1's discovery test; `discovery = new SonosDiscovery(ssdp, new SonosProperties(true, 1, 1, 0, 1, 1, 1, 2), events::add)`:
- `findsEveryVisiblePlayerFromOneAnnouncement`: eventually `devices()` = `[DiscoveredDevice("sonos", "Kitchen", "127.0.0.3", kitchen.port(), {uuid=KITCHEN}), DiscoveredDevice("sonos", "Living Room", "127.0.0.2", living.port(), {uuid=LIVING})]` (sorted by name).
- `publishesOneEventPerPlayer`: after 4 more searches, exactly two `DeviceDiscoveredEvent`s.
- `dropsPlayersThatLeftTheHousehold`: `household.removePlayer(kitchen)` → eventually `devices()` only the living room.
- `tellsListenersWhichPlayerAnnouncedItself`: `onAlive(uuids::add)` → contains `LIVING`.
- `hiddenMembersAreNotDevices`: pure `SonosDiscovery.toDevices(ZoneGroupState.parse(<modern fixture>))` → names `[Living Room, Kitchen, Office & Studio]` with hosts `192.168.1.70/.71/.73`, port 1400.

`SonosSessionTest` — `properties = new SonosProperties(true, 1, 1, 1, 1, 1, 1, 2)`; `session(FakeSonosPlayer p) = new SonosSession(p.device("sonos-" + p.uuid()), properties, SoapClient.httpClient(1 s), states::add, () -> {})` then `start()`; `song = new PlayMedia(URI("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits")`:
- `connectsAndReadsTheSpeakersOwnVolume`: kitchen session → `CONNECTED`, `volumeLevel` 20.
- `aStandaloneSpeakerPlaysItself`: living session, `execute(song)` → `living.commandNames()` = `[SetAVTransportURI, Play]`; `living.currentMetadata()` contains `protocolInfo="http-get:*:audio/flac:*"`.
- `aGroupMemberPlaysThroughItsCoordinator`: `household.join(KITCHEN, LIVING)`; kitchen session (topology read at connect) → `execute(song)` → `living.commandNames()` = `[SetAVTransportURI, Play]`, `kitchen.commandNames()` empty; `execute(new Pause())` → living got `Pause`.
- `volumeStaysWithTheSpeaker`: grouped as above; kitchen `execute(new SetVolume(33))` → `kitchen.volume()` 33, `living.volume()` 20.
- `joinsAndLeavesGroups`: kitchen session → `execute(new JoinGroup(LIVING))` → kitchen's last `SetAVTransportURI` has `CurrentURI` `x-rincon:RINCON_000E58A0B1C201400` and empty `CurrentURIMetaData`; `household.coordinatorOf(KITCHEN)` = `LIVING`; eventually `speakerTopology()` has `grouped()` true and own group label `Living Room + Kitchen`; `execute(new LeaveGroup())` → kitchen got `BecomeCoordinatorOfStandaloneGroup`; eventually `grouped()` false.
- `joiningTheOwnGroupAndLeavingAloneChangeNothing`: kitchen alone → `execute(new LeaveGroup())` and `execute(new JoinGroup(KITCHEN))` → `kitchen.commandNames()` empty.
- `joiningAnUnknownSpeakerFails`: `execute(new JoinGroup("RINCON_NOPE"))` → `ActionFailedException` containing `cannot find that speaker`.
- `reportsTheHouseholdTopology`: living session → `speakerTopology()` has `selfId` `LIVING`, two groups, `otherGroups()` = `[SpeakerGroup(KITCHEN, [GroupMember(KITCHEN, "Kitchen")])]`.
- `rejectsWhatASpeakerCannotDo`: `PressKey`, `OpenAppLink`, `SelectInput` → `UnsupportedActionException`; `execute(new PlayMedia(URI("http://h/film.mp4"), "video/mp4", "Film", null))` → `UnsupportedActionException` `Living Room cannot play video/mp4`.
- `isOfflineWhileThePlayerIsGone`: `living.hangUp(true)` → `DISCONNECTED`; `execute(new Pause())` → `DeviceOfflineException`; `speakerTopology()` empty.

`SonosAdapterTest`: `isAPairingFreeMediaRenderer` (id `sonos`, kind `SONOS`, capabilities `{MEDIA_RENDERER, VOLUME}`, `settingsFor(DiscoveredDevice("sonos", "Kitchen", "10.0.0.71", 1400, {uuid: RINCON_X}))` = `{uuid=RINCON_X, port=1400}`, other adapter → empty); `connectsARegisteredPlayer`.

`SonosModuleSwitchTest`: as `UpnpModuleSwitchTest` with `SonosConfiguration` and `home-control.sonos.enabled`.

`UpnpDiscoveryTest` — add `skipsSonosPlayersWhileTheSonosModuleHandlesThem`: an `SsdpService` with USN `uuid:RINCON_000E58A0B1C201400_MR::urn:schemas-upnp-org:device:MediaRenderer:1` and a description with an AVTransport service and manufacturer `Sonos, Inc.` → `toDevice(service, true)` empty, `toDevice(service, false)` present; the generic renderer's service → present for both. Update Task 1's test construction to `new UpnpDiscovery(ssdp, events::add, true)`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: compilation failure — Sonos classes and the new `UpnpDiscovery` signature do not exist.

- [ ] **Step 6: Implement the Sonos module**

`adapters/upnp/UpnpDiscovery.java`: add a `boolean ignoreSonos` constructor parameter (third) and field; `map(service)` returns `toDevice(service, ignoreSonos)`; replace `toDevice(SsdpService)` by:

```java
    static Optional<DiscoveredDevice> toDevice(SsdpService service, boolean ignoreSonos) {
        if (ignoreSonos && isSonos(service)) {
            return Optional.empty();
        }
        // … the Task 1 body unchanged …
    }

    static boolean isSonos(SsdpService service) {
        return service.usn().startsWith("uuid:RINCON_")
                || (service.description() != null && service.description().manufacturer() != null
                    && service.description().manufacturer().startsWith("Sonos"));
    }
```

`adapters/upnp/UpnpConfiguration.upnpDiscovery` takes `Environment environment` (import `org.springframework.core.env.Environment`) and passes `environment.getProperty("home-control.sonos.enabled", Boolean.class, true)`.

`adapters/sonos/SonosProperties.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("home-control.sonos")
public record SonosProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("2") int pollIntervalSeconds,
                              @DefaultValue("10") int idlePollIntervalSeconds,
                              @DefaultValue("30") int topologyIntervalSeconds,
                              @DefaultValue("5") int commandTimeoutSeconds,
                              @DefaultValue("3") int connectTimeoutSeconds,
                              @DefaultValue("1") int reconnectInitialDelaySeconds,
                              @DefaultValue("60") int reconnectMaxDelaySeconds) {
}
```

`adapters/sonos/SonosSettings.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-device settings under {@code adapters.sonos}: the player's RINCON id and HTTP port. */
public record SonosSettings(String uuid, int port) {

    public static final String ADAPTER_ID = "sonos";
    static final String UUID = "uuid";
    static final String PORT = "port";

    public static SonosSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no sonos adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        int port;
        try {
            port = Integer.parseInt(settings.getOrDefault(PORT, String.valueOf(SonosEndpoints.DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            port = SonosEndpoints.DEFAULT_PORT;
        }
        return new SonosSettings(settings.get(UUID), port);
    }

    public static SonosSettings from(DiscoveredDevice found) {
        return new SonosSettings(found.attributes().get(UUID), found.port());
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (uuid != null) {
            map.put(UUID, uuid);
        }
        map.put(PORT, String.valueOf(port));
        return map;
    }
}
```

`adapters/sonos/SonosDiscovery.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosActions;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.adapters.sonos.protocol.ZoneGroupState;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Sonos rooms: any announcing player tells us its whole household through GetZoneGroupState. */
public class SonosDiscovery implements AutoCloseable {

    public static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1";
    private static final Logger log = LoggerFactory.getLogger(SonosDiscovery.class);

    private final SsdpDiscovery ssdp;
    private final SonosProperties properties;
    private final ApplicationEventPublisher events;
    private final SoapClient soap;
    private final Clock clock;
    /** household → (uuid → room) */
    private final Map<String, Map<String, DiscoveredDevice>> households = new ConcurrentHashMap<>();
    private final Map<String, Instant> refreshedAt = new ConcurrentHashMap<>();
    private final List<Consumer<String>> aliveListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("sonos-discovery").factory());

    public SonosDiscovery(SsdpDiscovery ssdp, SonosProperties properties, ApplicationEventPublisher events) {
        this(ssdp, properties, events, Clock.systemUTC());
    }

    SonosDiscovery(SsdpDiscovery ssdp, SonosProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.ssdp = ssdp;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.soap = new SoapClient(SoapClient.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds())),
                Duration.ofSeconds(properties.commandTimeoutSeconds()));
        ssdp.addListener(SEARCH_TARGET, service -> submit(() -> seen(service)));
    }

    public List<DiscoveredDevice> devices() {
        return households.values().stream().flatMap(rooms -> rooms.values().stream())
                .sorted(Comparator.comparing(DiscoveredDevice::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void onAlive(Consumer<String> uuidListener) {
        aliveListeners.add(uuidListener);
    }

    void seen(SsdpService service) {
        String uuid = uuidOf(service.usn());
        aliveListeners.forEach(listener -> listener.accept(uuid));
        String household = service.headers().getOrDefault("X-RINCON-HOUSEHOLD", "default");
        Instant last = refreshedAt.getOrDefault(household, Instant.EPOCH);
        boolean known = households.getOrDefault(household, Map.of()).containsKey(uuid);
        if (known && Duration.between(last, clock.instant()).toSeconds() < properties.topologyIntervalSeconds()) {
            return;
        }
        String address = service.address();
        int port = service.location() != null && service.location().getPort() > 0 ? service.location().getPort() : SonosEndpoints.DEFAULT_PORT;
        try {
            String xml = soap.call(SonosEndpoints.endpoint(address, port, SonosEndpoints.ZONE_GROUP_TOPOLOGY_PATH,
                    SonosEndpoints.ZONE_GROUP_TOPOLOGY).controlUrl(), SonosActions.getZoneGroupState()).getOrDefault("ZoneGroupState", "");
            List<DiscoveredDevice> rooms = toDevices(ZoneGroupState.parse(xml));
            refreshedAt.put(household, clock.instant());
            Map<String, DiscoveredDevice> previous = households.getOrDefault(household, Map.of());
            Map<String, DiscoveredDevice> current = new LinkedHashMap<>();
            rooms.forEach(room -> current.put(room.attributes().get(SonosSettings.UUID), room));
            households.put(household, current);
            current.forEach((id, room) -> {
                if (!room.equals(previous.get(id))) {
                    log.info("Discovered Sonos room {} at {}", room.name(), room.host());
                    events.publishEvent(new DeviceDiscoveredEvent(room));
                }
            });
        } catch (IOException | SoapFault | IllegalArgumentException e) {
            log.debug("No zone group state from {}: {}", address, e.getMessage());
        }
    }

    static List<DiscoveredDevice> toDevices(ZoneGroupState state) {
        return state.visibleMembers().stream()
                .map(member -> new DiscoveredDevice(SonosSettings.ADAPTER_ID, member.zoneName(), member.host(), member.port(),
                        Map.of(SonosSettings.UUID, member.uuid())))
                .toList();
    }

    static String uuidOf(String usn) {
        String withoutPrefix = usn.startsWith("uuid:") ? usn.substring(5) : usn;
        int separator = withoutPrefix.indexOf("::");
        return separator < 0 ? withoutPrefix : withoutPrefix.substring(0, separator);
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
```

(`SsdpService.headers()` is F1's case-insensitive map.)

`adapters/sonos/SonosSession.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.sonos.protocol.SonosActions;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints;
import dev.andre.homecontrol.adapters.sonos.protocol.SonosUris;
import dev.andre.homecontrol.adapters.sonos.protocol.ZoneGroupState;
import dev.andre.homecontrol.adapters.upnp.protocol.ProtocolInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.ReconnectingPoller;
import dev.andre.homecontrol.adapters.upnp.protocol.RendererCommands;
import dev.andre.homecontrol.adapters.upnp.protocol.ServiceEndpoint;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.adapters.upnp.protocol.SoapFault;
import dev.andre.homecontrol.adapters.upnp.protocol.TransportInfo;
import dev.andre.homecontrol.adapters.upnp.protocol.UpnpActions;
import dev.andre.homecontrol.adapters.upnp.protocol.VolumeReading;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.GroupListing;
import dev.andre.homecontrol.core.GroupMember;
import dev.andre.homecontrol.core.SpeakerGroup;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints.AV_TRANSPORT;
import static dev.andre.homecontrol.adapters.sonos.protocol.SonosEndpoints.AV_TRANSPORT_PATH;

/** One Sonos room: transport through its group coordinator, volume on itself, grouping controls. */
public class SonosSession implements DeviceHandle, GroupListing {

    private static final Logger log = LoggerFactory.getLogger(SonosSession.class);

    private final Device device;
    private final SonosSettings settings;
    private final SonosProperties properties;
    private final SoapClient soap;
    private final RendererCommands commands;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClosed;
    private final Clock clock;
    private final ReconnectingPoller poller;

    private volatile ZoneGroupState topology;
    private volatile Instant topologyReadAt = Instant.EPOCH;
    private volatile ProtocolInfo sink = ProtocolInfo.UNKNOWN;
    private volatile TransportInfo transport = TransportInfo.NONE;
    private volatile DeviceState state = DeviceState.initial();

    public SonosSession(Device device, SonosProperties properties, HttpClient http,
                        Consumer<DeviceState> onChange, Runnable onClosed) {
        this(device, properties, http, onChange, onClosed, Clock.systemUTC());
    }

    SonosSession(Device device, SonosProperties properties, HttpClient http,
                 Consumer<DeviceState> onChange, Runnable onClosed, Clock clock) {
        this.device = device;
        this.settings = SonosSettings.of(device);
        this.properties = properties;
        this.soap = new SoapClient(http, Duration.ofSeconds(properties.commandTimeoutSeconds()));
        this.commands = new RendererCommands(soap, device.name());
        this.onChange = onChange;
        this.onClosed = onClosed;
        this.clock = clock;
        this.poller = new ReconnectingPoller("sonos-" + device.id(),
                Duration.ofSeconds(properties.reconnectInitialDelaySeconds()),
                Duration.ofSeconds(properties.reconnectMaxDelaySeconds()), new Link());
    }

    public void start() {
        onChange.accept(state);
        poller.start();
    }

    public String uuid() {
        return settings.uuid();
    }

    public void reconnectNow() {
        poller.reconnectNow();
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public Optional<SpeakerTopology> speakerTopology() {
        ZoneGroupState current = topology;
        if (current == null || !poller.connected()) {
            return Optional.empty();
        }
        List<SpeakerGroup> groups = current.groups().stream()
                .map(group -> new SpeakerGroup(group.coordinator(), group.visibleMembers().stream()
                        .sorted(Comparator.comparing(member -> !member.uuid().equals(group.coordinator())))
                        .map(member -> new GroupMember(member.uuid(), member.zoneName()))
                        .toList()))
                .filter(group -> !group.members().isEmpty())
                .toList();
        return Optional.of(new SpeakerTopology(settings.uuid(), groups));
    }

    @Override
    public void execute(Action action) {
        if (!poller.connected()) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        try {
            switch (action) {
                case Action.PlayMedia play -> commands.playUri(coordinatorAvTransport(), sink,
                        new Action.PlayMedia(SonosUris.forPlayback(play.url(), play.mimeType()), play.mimeType(), play.title(), play.subtitle()),
                        "*");
                case Action.Pause ignored -> commands.transport(coordinatorAvTransport(), UpnpActions.pause(AV_TRANSPORT), "pause");
                case Action.Resume ignored -> commands.transport(coordinatorAvTransport(), UpnpActions.play(AV_TRANSPORT), "resume playback");
                case Action.Stop ignored -> commands.transport(coordinatorAvTransport(), UpnpActions.stop(AV_TRANSPORT), "stop playback");
                case Action.SetVolume volume -> commands.setVolume(renderingControl(), volume.level(), 100);
                case Action.Mute mute -> commands.setMute(renderingControl(), mute.muted());
                case Action.JoinGroup join -> join(join.memberId());
                case Action.LeaveGroup ignored -> leave();
                case Action.PressKey ignored -> throw unsupported("has no remote keys");
                case Action.OpenAppLink ignored -> throw unsupported("cannot open app links");
                case Action.SelectInput ignored -> throw unsupported("has no inputs");
                case Action.CastLoad ignored -> throw unsupported("is not a Cast receiver");
                case Action.CastMessage ignored -> throw unsupported("is not a Cast receiver");
            }
        } finally {
            poller.pollNow();
        }
    }

    private void join(String memberId) {
        ZoneGroupState current = commands.run("look up the speaker groups", this::readTopology);
        ZoneGroupState.Group target = current.groupOf(memberId)
                .orElseThrow(() -> new ActionFailedException(device.name() + " cannot find that speaker; it may have left the network"));
        if (target.contains(settings.uuid())) {
            return;
        }
        String name = target.coordinatorMember().map(ZoneGroupState.Member::zoneName).orElse("that group");
        commands.transport(own(AV_TRANSPORT_PATH, AV_TRANSPORT),
                UpnpActions.setAvTransportUri(AV_TRANSPORT, SonosUris.groupWith(target.coordinator()), ""), "join " + name);
        topologyReadAt = Instant.EPOCH;
    }

    private void leave() {
        ZoneGroupState current = commands.run("look up the speaker groups", this::readTopology);
        boolean alone = current.groupOf(settings.uuid()).map(group -> group.visibleMembers().size() <= 1).orElse(true);
        if (alone) {
            return;
        }
        commands.transport(own(AV_TRANSPORT_PATH, AV_TRANSPORT),
                UpnpActions.becomeCoordinatorOfStandaloneGroup(AV_TRANSPORT), "leave the group");
        topologyReadAt = Instant.EPOCH;
    }

    private ZoneGroupState readTopology() throws IOException, SoapFault {
        String xml = soap.call(own(SonosEndpoints.ZONE_GROUP_TOPOLOGY_PATH, SonosEndpoints.ZONE_GROUP_TOPOLOGY).controlUrl(),
                SonosActions.getZoneGroupState()).getOrDefault("ZoneGroupState", "");
        try {
            ZoneGroupState parsed = ZoneGroupState.parse(xml);
            topology = parsed;
            topologyReadAt = clock.instant();
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new SoapFault(0, "Unreadable zone group state");
        }
    }

    /** Transport commands must reach the group coordinator; a standalone room coordinates itself. */
    private ServiceEndpoint coordinatorAvTransport() {
        ZoneGroupState current = topology;
        if (current != null) {
            Optional<ZoneGroupState.Member> coordinator = current.groupOf(settings.uuid())
                    .flatMap(ZoneGroupState.Group::coordinatorMember)
                    .filter(member -> !member.uuid().equals(settings.uuid()));
            if (coordinator.isPresent()) {
                return SonosEndpoints.endpoint(coordinator.get().host(), coordinator.get().port(), AV_TRANSPORT_PATH, AV_TRANSPORT);
            }
        }
        return own(AV_TRANSPORT_PATH, AV_TRANSPORT);
    }

    private ServiceEndpoint renderingControl() {
        return own(SonosEndpoints.RENDERING_CONTROL_PATH, SonosEndpoints.RENDERING_CONTROL);
    }

    private ServiceEndpoint own(String path, String serviceType) {
        return SonosEndpoints.endpoint(device.host(), settings.port(), path, serviceType);
    }

    private UnsupportedActionException unsupported(String what) {
        return new UnsupportedActionException(device.name() + " is a Sonos speaker and " + what);
    }

    private void readState() throws IOException, SoapFault {
        TransportInfo info = commands.transportInfo(coordinatorAvTransport());
        VolumeReading volume = commands.volume(renderingControl(), 100);
        transport = info;
        publish(state.withStatus(DeviceStatus.CONNECTED).withPower(true).withVolume(volume.percent(), 100, volume.muted()));
    }

    private synchronized void publish(DeviceState next) {
        DeviceState previous = state;
        state = next;
        if (!next.sameIgnoringTime(previous)) {
            try {
                onChange.accept(next);
            } catch (RuntimeException e) {
                log.warn("A device state listener failed for {}", device.id(), e);
            }
        }
    }

    @Override
    public void close() {
        poller.close();
        onClosed.run();
    }

    private final class Link implements ReconnectingPoller.Link {

        @Override
        public void connect() throws Exception {
            readTopology();
            try {
                sink = commands.sink(own(SonosEndpoints.CONNECTION_MANAGER_PATH, SonosEndpoints.CONNECTION_MANAGER));
            } catch (SoapFault fault) {
                sink = ProtocolInfo.UNKNOWN;
            }
            readState();
        }

        @Override
        public void poll() throws Exception {
            if (Duration.between(topologyReadAt, clock.instant()).toSeconds() >= properties.topologyIntervalSeconds()) {
                try {
                    readTopology();
                } catch (SoapFault fault) {
                    log.debug("{}: topology unavailable: {}", device.id(), fault.getMessage());
                }
            }
            readState();
        }

        @Override
        public Duration nextPollDelay() {
            return Duration.ofSeconds(transport.active() ? properties.pollIntervalSeconds() : properties.idlePollIntervalSeconds());
        }

        @Override
        public void disconnected(Exception cause) {
            transport = TransportInfo.NONE;
            log.debug("Sonos player {} unreachable: {}", device.id(), cause.getMessage());
            publish(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
        }
    }
}
```

The `join`/`leave` reads of the topology happen on the caller's thread and assign `volatile` fields; the poll loop may overwrite them with an equally fresh read — both are valid snapshots.

`adapters/sonos/SonosAdapter.java` — same shape as `UpnpAdapter`: constructor `(SonosProperties properties, SonosDiscovery discovery)` builds `SoapClient.httpClient(connect timeout)` and registers `discovery.onAlive(uuid -> sessions.values().stream().filter(s -> uuid.equals(s.uuid())).forEach(SonosSession::reconnectNow))`; `id()` `sonos`; `kind()` `SONOS`; `capabilities` `EnumSet.of(MEDIA_RENDERER, VOLUME)`; `connect` creates, registers (removed on close) and starts a `SonosSession`; `discovered()` → `discovery.devices()`; `settingsFor(found)` → `SonosSettings.from(found).toMap()` for adapter id `sonos`, else empty.

`adapters/sonos/SonosConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Sonos module. {@code home-control.sonos.enabled=false} removes it; Sonos players then show up as plain UPnP renderers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.sonos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SonosProperties.class)
public class SonosConfiguration {

    @Bean(destroyMethod = "close")
    public SonosDiscovery sonosDiscovery(SsdpDiscovery ssdp, SonosProperties properties, ApplicationEventPublisher events) {
        return new SonosDiscovery(ssdp, properties, events);
    }

    @Bean
    public SonosAdapter sonosAdapter(SonosProperties properties, SonosDiscovery discovery) {
        return new SonosAdapter(properties, discovery);
    }
}
```

`application.yaml` (main) under `home-control:`:

```yaml
  sonos:
    # Sonos rooms found over SSDP; grouping from the device drawer.
    enabled: true
    poll-interval-seconds: 2
    idle-poll-interval-seconds: 10
    topology-interval-seconds: 30
    command-timeout-seconds: 5
    connect-timeout-seconds: 3
    reconnect-initial-delay-seconds: 1
    reconnect-max-delay-seconds: 60
```

Test `application.yaml`: `sonos: enabled: true` under `home-control:`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS. If binding `127.0.0.2` fails with `BindException`, the test host is not Linux; do not skip — report it.

- [ ] **Step 7: Write the failing web tests**

`web/DeviceControllerTest.java`:
- `joinsAndLeavesSpeakerGroups`: `POST /devices/kitchen/group/join/RINCON_000E58A0B1C201400` → 204, header `HX-Refresh` `true`, `verify(devices).execute("kitchen", new Action.JoinGroup("RINCON_000E58A0B1C201400"))`; `POST /devices/kitchen/group/leave` → 204 with `HX-Refresh`, `new Action.LeaveGroup()`; `POST /devices/ghost/group/leave` → 404; `ActionFailedException` from `execute` → 502 with the message.

`web/DashboardPageTest.java` — `aGroupableSpeakerShowsItsGroups`: device `sonos-10-0-0-71` "Kitchen" (`SONOS`, capabilities `{MEDIA_RENDERER, VOLUME}`), `devices.speakerTopology("sonos-10-0-0-71")` → `SpeakerTopology("K", [SpeakerGroup("L", [GroupMember("L", "Living Room"), GroupMember("K", "Kitchen")]), SpeakerGroup("O", [GroupMember("O", "Office")])])` → page contains `Living Room + Kitchen`, `/devices/sonos-10-0-0-71/group/leave`, `/devices/sonos-10-0-0-71/group/join/O` and `Join Office`; with `Optional.empty()` (stub default for other tests: `given(devices.speakerTopology(any())).willReturn(Optional.empty())` in `@BeforeEach`) the page contains no `/group/`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`
Expected: FAIL — 404s and no group section.

- [ ] **Step 8: Implement the web changes**

`web/DeviceController.java`:

```java
    @PostMapping("/devices/{id}/group/join/{memberId}")
    public ResponseEntity<String> joinGroup(@PathVariable String id, @PathVariable String memberId) {
        return regroup(id, new Action.JoinGroup(memberId));
    }

    @PostMapping("/devices/{id}/group/leave")
    public ResponseEntity<String> leaveGroup(@PathVariable String id) {
        return regroup(id, new Action.LeaveGroup());
    }

    /** Grouping changes the whole drawer (and other rooms' chips): let htmx reload the page. */
    private ResponseEntity<String> regroup(String id, Action action) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        devices.execute(id, action);
        return ResponseEntity.noContent().header("HX-Refresh", "true").build();
    }
```

`web/DashboardController.dashboard`: `model.addAttribute("speakerTopology", devices.speakerTopology(device.id()).orElse(null));`

`dashboard.html` — after the renderer section from Task 1:

```html
    <section class="speaker-group" th:if="${speakerTopology != null}" aria-label="Speaker group">
        <h2>Group</h2>
        <p th:if="${speakerTopology.grouped()}">Playing together:
            <span th:text="${speakerTopology.ownGroup().get().label()}">Living Room + Kitchen</span></p>
        <p th:unless="${speakerTopology.grouped()}">Playing on its own.</p>
        <div class="row">
            <button th:each="group : ${speakerTopology.otherGroups()}" th:text="'Join ' + ${group.label()}"
                    th:attr="hx-post=@{/devices/{id}/group/join/{member}(id=${id},member=${group.coordinatorId()})}">Join Office</button>
            <button th:if="${speakerTopology.grouped()}"
                    th:attr="hx-post=@{/devices/{id}/group/leave(id=${id})}">Leave group</button>
        </div>
    </section>
```

`app.css`: `.speaker-group .row { flex-wrap: wrap; }`

- [ ] **Step 9: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: Sonos rooms — discovery by household, coordinator playback, volume and grouping"
```

---

### Task 3: I3 · Audio routes and now-playing

**Files:**
- Create: `core/playback/MediaRendererStrategy.java`; `adapters/upnp/protocol/UpnpTime.java`, `PositionInfo.java`, `PlayedItem.java`, `NowPlayings.java`
- Modify: `core/playback/Route.java`, `core/playback/PlaybackPlanner.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java`, `adapters/upnp/protocol/DidlLite.java`, `adapters/upnp/protocol/RendererCommands.java`, `adapters/upnp/UpnpSession.java`, `adapters/sonos/SonosSession.java`, `sources/jellyfin/JellyfinContentSource.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`
- Test: `core/playback/MediaRendererStrategyTest.java`, `core/playback/PlaybackPlannerTest.java`, `playback/PlaybackServiceTest.java`, `adapters/upnp/protocol/UpnpTimeTest.java`, `NowPlayingsTest.java`, `DidlLiteTest.java`, `adapters/upnp/UpnpSessionTest.java`, `adapters/sonos/SonosSessionTest.java`, `sources/jellyfin/JellyfinContentSourceTest.java`, `JellyfinPlayableResolverTest.java`, `JellyfinFixtureContractTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`; fixtures `src/test/resources/fixtures/upnp/position-metadata.xml`, `src/test/resources/fixtures/jellyfin/music-recent.json`, `music-latest.json`, `item-track.json`

**Interfaces:**
- Consumes: A/B/C — `PlayableRef.StreamUrl(URI url, String mimeType)`, `ContentItem` (title, subtitle), `RouteStrategy`, `PlaybackPlanner`, `Route` (`OpenAppLink`, `Cast`, `CastMessage`, `JellyfinSession`, `Unroutable`), `PlaybackService.play/plan` (C7 version with resolvers and executors), `HomeControlConfiguration.playbackPlanner()` (C7 order), `JellyfinContentSource` (C3: `RESUME`, `NEXT_UP`, `LATEST`, `listQuery`, `client.get`), `JellyfinItemMapper` (Audio → `TRACK`, subtitle = artists), `JellyfinPlayableResolver` (C7: builds `StreamUrl` for `MEDIA_RENDERER` devices), `JellyfinStreams` (C6), `FakeJellyfinServer`, `playback-info-audio.json`, `sessions.json`, B's `NowPlaying(String title, PlaybackState state, double positionSeconds, Double durationSeconds)` and `PlaybackState`. Task 1/2 — `Action.PlayMedia`, `RendererCommands`, `UpnpSession`, `SonosSession`, `FakeUpnpRenderer.setPosition/echoMetadata/playElsewhere`.
- Produces:
  - `Route.Render(URI url, String mimeType, String title, String subtitle)` with `Action action()` (→ `Action.PlayMedia` with the same four values), `describe()` = `Stream directly to this device (DLNA/UPnP)`, redacted `toString()`.
  - `MediaRendererStrategy` (MEDIA_RENDERER + first `StreamUrl` → `Render(url, mimeType, item.title(), item.subtitle())`); planner bean order `JellyfinSessionStrategy, AppLinkStrategy, CastMessageStrategy, CastLoadStrategy, CastStreamStrategy, MediaRendererStrategy`.
  - Planner explanation for `StreamUrl`: `the stream was not accepted` when the device is a Cast receiver or media renderer, else `this device cannot play a direct stream` (unchanged text).
  - `UpnpTime.seconds(String) → Double` (null when absent); `record PositionInfo(String trackUri, String trackMetadata, Double positionSeconds, Double durationSeconds)` with `from(Map)`; `record PlayedItem(String uri, String title)`; `NowPlayings.of(TransportInfo, PositionInfo, PlayedItem) → NowPlaying` (nullable); `DidlLite.title(String) → Optional<String>`; `RendererCommands.positionInfo(ServiceEndpoint) → PositionInfo`.
  - `DeviceState.nowPlaying` reported by `UpnpSession` and `SonosSession` (a grouped Sonos room shows its coordinator's).
  - Jellyfin rails `music-recent` "Recently played music" and `music-latest` "Latest music".
  - Dashboard `canOpenLinks` also true for `MEDIA_RENDERER`.

**Jellyfin music rail queries (normative).** Both with C3's list query (`userId`, `limit`, `enableUserData=true`, `enableImageTypes=Primary,Thumb,Backdrop`, `imageTypeLimit=1`) plus:
- `music-recent`: `GET /Items` + `includeItemTypes=Audio`, `recursive=true`, `filters=IsPlayed`, `sortBy=DatePlayed`, `sortOrder=Descending` → `{"Items":[…]}`.
- `music-latest`: `GET /Items/Latest` + `includeItemTypes=Audio`, `groupItems=false` → `[…]`.

**Now-playing mapping (normative).** `CurrentTransportState` `PLAYING` → `PLAYING`; `PAUSED_PLAYBACK`, `PAUSED_RECORDING` → `PAUSED`; `TRANSITIONING` → `BUFFERING`; anything else → no now-playing. Title: first non-blank `title` element (any prefix) of `TrackMetaData` parsed as DIDL-Lite; else the title this session sent in its last `PlayMedia` when `TrackURI` equals that URI exactly; else `Unknown title`. Position: `RelTime` in seconds (0 when absent). Duration: `TrackDuration` in seconds when > 0, else null. UPnP times are `H+:MM:SS[.F+]` or `H+:MM:SS.F0/F1`; `NOT_IMPLEMENTED`, blank or malformed → absent.

- [ ] **Step 1: Add the fixtures**

`src/test/resources/fixtures/upnp/position-metadata.xml` (DIDL a Sonos returns for a track started by another controller):

```xml
<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="-1" parentID="-1" restricted="true"><res protocolInfo="http-get:*:audio/flac:*" duration="0:04:12">http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee02/stream.flac?static=true&amp;ApiKey=t</res><upnp:class>object.item.audioItem.musicTrack</upnp:class><dc:title>Carrot Waltz</dc:title><dc:creator>Clara Hopkins</dc:creator><upnp:album>Garden Suite</upnp:album></item></DIDL-Lite>
```

`src/test/resources/fixtures/jellyfin/item-track.json`:

```json
{
  "Name": "Bunny Song",
  "ServerId": "4e1a2b3c4d5e4f60718293a4b5c6d7e8",
  "Id": "c0ffee00c0ffee00c0ffee00c0ffee01",
  "RunTimeTicks": 1870000000,
  "IsFolder": false,
  "IndexNumber": 1,
  "Type": "Audio",
  "MediaType": "Audio",
  "Album": "Meadow",
  "AlbumId": "a1b0c2d3e4f5061728394a5b6c7d8e9f",
  "AlbumPrimaryImageTag": "d4e5f6a7b8c9",
  "Artists": ["The Rabbits"],
  "ArtistItems": [{ "Name": "The Rabbits", "Id": "b0a1c2d3e4f5061728394a5b6c7d8e9f" }],
  "AlbumArtist": "The Rabbits",
  "LocationType": "FileSystem",
  "UserData": {
    "PlaybackPositionTicks": 0,
    "PlayCount": 3,
    "IsFavorite": true,
    "LastPlayedDate": "2026-09-15T18:12:40.0000000Z",
    "Played": true,
    "Key": "The Rabbits-Meadow-0001-0001Bunny Song"
  },
  "ImageTags": {},
  "BackdropImageTags": []
}
```

`src/test/resources/fixtures/jellyfin/music-recent.json`: `{"Items":[<item-track.json object>, <second track>],"TotalRecordCount":2,"StartIndex":0}` where the second track is the same shape with `"Name": "Carrot Waltz"`, `"Id": "c0ffee00c0ffee00c0ffee00c0ffee02"`, `"RunTimeTicks": 2520000000`, `"Album": "Garden Suite"`, `"AlbumId": "a2b0c2d3e4f5061728394a5b6c7d8e9f"`, `"AlbumPrimaryImageTag": "e5f6a7b8c9d0"`, `"Artists": ["Clara Hopkins"]`, `"ArtistItems": [{"Name": "Clara Hopkins", "Id": "b1a1c2d3e4f5061728394a5b6c7d8e9f"}]`, `"AlbumArtist": "Clara Hopkins"`, `UserData` `{"PlaybackPositionTicks": 0, "PlayCount": 1, "IsFavorite": false, "LastPlayedDate": "2026-09-14T08:02:11.0000000Z", "Played": true, "Key": "Clara Hopkins-Garden Suite-0001-0003Carrot Waltz"}`. Write both objects out in full (no placeholders in the file).

`src/test/resources/fixtures/jellyfin/music-latest.json`: a root array `[<Carrot Waltz object with "PlayCount": 0, "Played": false and no "LastPlayedDate">, <Bunny Song object>]`, again written out in full.

- [ ] **Step 2: Write the failing planner tests**

`core/playback/MediaRendererStrategyTest.java`:
- `rendersTheFirstStreamWithTheItemsTitleAndSubtitle`: item title `Bunny Song`, subtitle `The Rabbits`, playables `[AppLink(…), StreamUrl(URI("http://nas/a.flac"), "audio/flac"), StreamUrl(URI("http://nas/b.mp3"), "audio/mpeg")]`, capabilities `{MEDIA_RENDERER}` → `Route.Render(URI("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits")`; its `action()` equals `new Action.PlayMedia(URI("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits")`; `describe()` = `Stream directly to this device (DLNA/UPnP)`.
- `needsAMediaRenderer`: `{CAST_RECEIVER, VOLUME}` → empty; no `StreamUrl` → empty.
- `neverPrintsTheStreamCredential`: `new Route.Render(URI("http://h:8096/Audio/x/stream.flac?ApiKey=secret-key"), "audio/flac", "T", null).toString()` contains `http://h:8096/Audio/x/stream.flac?…`, not `secret-key`.

`core/playback/PlaybackPlannerTest.java` — planner field gets `new MediaRendererStrategy()` appended; add:
- `aMediaRendererGetsTheStream`: `planner.plan(item(STREAM), EnumSet.of(MEDIA_RENDERER, VOLUME))` is a `Route.Render` with `STREAM.url()`.
- `castComesBeforeTheMediaRenderer`: `EnumSet.of(CAST_RECEIVER, MEDIA_RENDERER)` → `Route.Cast` with `CC1AD845`; `EnumSet.of(APP_LINK, MEDIA_RENDERER)` with `item(STREAM, LINK)` → `Route.OpenAppLink` (spec order: an app link wins).
- `explainsThatAStreamWasNotAcceptedByARenderer`: a strategy list without `MediaRendererStrategy` and `{MEDIA_RENDERER}` → reason contains `the stream was not accepted`; `{REMOTE_KEYS}` → `this device cannot play a direct stream`.
- `theApplicationsPlannerEndsWithTheMediaRenderer`: `new HomeControlConfiguration().playbackPlanner()` (pass whatever the real bean method needs) routes `item(STREAM)` on `{MEDIA_RENDERER}` to `Route.Render` and on `{CAST_RECEIVER, MEDIA_RENDERER}` to `Route.Cast`.

`playback/PlaybackServiceTest.java` — `executesARenderRoute`: planner `List.of(new AppLinkStrategy(), new CastLoadStrategy(), new CastStreamStrategy(), new MediaRendererStrategy())`, device `Device("upnp-10-0-0-30", "Kitchen Speaker", UPNP, "10.0.0.30", {upnp: {}}, now)`, capabilities `{MEDIA_RENDERER, VOLUME}` → `play(AppLinks.fromUrl("http://nas.local/music/song.flac"), id)` is a `Route.Render`; `verify(devices).execute(id, new Action.PlayMedia(URI("http://nas.local/music/song.flac"), "audio/flac", "song.flac", null))`.

`web/DeviceControllerTest.java` — `describesARenderRoute`: `playback.play(any(), eq("shield"))` returns `new Route.Render(URI("http://nas/a.flac"), "audio/flac", "A", null)` → `POST /devices/shield/play uri=http://nas/a.flac` → 200 `Stream directly to this device (DLNA/UPnP)`.

`web/DashboardPageTest.java` — `aMediaRendererOffersTheLinkForm`: the Task 1 renderer device stubs → page contains `/devices/upnp-10-0-0-30/play`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `Route.Render` and `MediaRendererStrategy` do not exist.

- [ ] **Step 3: Implement the route**

`core/playback/Route.java` — add (imports `dev.andre.homecontrol.core.RedactedUris`):

```java
    /** Hand a direct stream to a DLNA/UPnP/Sonos media renderer (spec §5.3 rung 4). */
    record Render(URI url, String mimeType, String title, String subtitle) implements Route {

        public Action action() {
            return new Action.PlayMedia(url, mimeType, title, subtitle);
        }

        @Override
        public String describe() {
            return "Stream directly to this device (DLNA/UPnP)";
        }

        @Override
        public String toString() {
            return "Render[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + ", title=" + title + "]";
        }
    }
```

`core/playback/MediaRendererStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 4 of spec §5.3: a media renderer (DLNA, Sonos) plays the item's direct stream. After every Cast rung. */
public class MediaRendererStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.MEDIA_RENDERER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.StreamUrl.class::isInstance)
                .map(PlayableRef.StreamUrl.class::cast)
                .findFirst()
                .map(stream -> new Route.Render(stream.url(), stream.mimeType(), item.title(), item.subtitle()));
    }
}
```

`PlaybackPlanner.explain` — the `StreamUrl` case becomes:

```java
                case PlayableRef.StreamUrl ignored -> reasons.add(
                        capabilities.contains(Capability.CAST_RECEIVER) || capabilities.contains(Capability.MEDIA_RENDERER)
                                ? "the stream was not accepted" : "this device cannot play a direct stream");
```

`PlaybackService.play` — add `case Route.Render render -> devices.execute(deviceId, render.action());` to the exhaustive switch (any other exhaustive `switch` over `Route` in the code base — search `case Route.Unroutable` — gets the same branch or a describe-only branch).

`HomeControlConfiguration.playbackPlanner` — append `new MediaRendererStrategy()` as the last strategy and update the comment: "…then bare streams on the Default Media Receiver, then media renderers (DLNA/UPnP/Sonos)."

`DashboardController`: `canOpenLinks` = `APP_LINK || CAST_RECEIVER || MEDIA_RENDERER`. In `dashboard.html`, after B's direct-media-links hint add `<p class="hint" th:if="${rendererControls}">Direct audio links (.mp3, .flac, .m4a, .ogg …) play on speakers as a direct stream; the speaker must be able to reach the URL.</p>`.

Run the Step 2 command. Expected: PASS.

- [ ] **Step 4: Write the failing now-playing tests**

`adapters/upnp/protocol/UpnpTimeTest.java`:
- `readsUpnpDurations`: `seconds("0:03:07")` = 187.0; `"1:02:03.500"` → 3723.5; `"00:00:05.1/4"` → 5.25; `"+0:00:10"` → 10.0; `"12:00:00"` → 43200.0.
- `absentOrMalformedIsNull`: `null`, `""`, `"NOT_IMPLEMENTED"`, `"3:07"`, `"0:00:05.1/0"` → null.

`adapters/upnp/protocol/DidlLiteTest.java` — add `readsTheTitleFromMetadata`: `title(<position-metadata.xml>)` = `Carrot Waltz`; `title(didl-track.xml)` = `Bunny Song`; `title("NOT_IMPLEMENTED")`, `title("")`, `title(null)`, `title("<DIDL-Lite")`, `title("<!DOCTYPE x><x/>")` → empty (never throws).

`adapters/upnp/protocol/NowPlayingsTest.java` — `PositionInfo.from(Map.of("TrackURI", "http://nas/a.flac", "TrackMetaData", <didl-track.xml>, "RelTime", "0:00:42", "TrackDuration", "0:03:07"))`:
- `mapsTransportStates`: `PLAYING` → `NowPlaying("Bunny Song", PLAYING, 42.0, 187.0)`; `PAUSED_PLAYBACK` → `PAUSED`; `TRANSITIONING` → `BUFFERING`; `STOPPED`, `NO_MEDIA_PRESENT` → null.
- `fallsBackToTheTitleThisServerSent`: metadata `NOT_IMPLEMENTED`, `PlayedItem("http://nas/a.flac", "Bunny Song")` → title `Bunny Song`; `PlayedItem("http://nas/other.flac", "Other")` → `Unknown title`; `PlayedItem` null → `Unknown title`.
- `aZeroDurationIsUnknown`: `TrackDuration` `0:00:00` → `durationSeconds` null; `RelTime` `NOT_IMPLEMENTED` → position 0.0.

`adapters/upnp/UpnpSessionTest.java` — add:
- `reportsWhatIsPlaying`: `fake.setPosition("0:00:42", "0:03:07")`; `execute(new PlayMedia(URI("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits"))` → eventually `state().nowPlaying()` = `NowPlaying("Bunny Song", PLAYING, 42.0, 187.0)`; `execute(new Pause())` → eventually state `PAUSED`; `execute(new Stop())` → eventually `nowPlaying()` null.
- `usesTheTitleItSentWhenTheRendererForgetsMetadata`: `fake.echoMetadata(false)` → after playing, `nowPlaying().title()` = `Bunny Song`.
- `showsPlaybackStartedByAnotherController`: `fake.playElsewhere("http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee02/stream.flac?static=true&ApiKey=t", <position-metadata.xml>)` → eventually title `Carrot Waltz`; no published state's `toString()` contains `ApiKey`.
- `pollsFasterWhilePlaying`: properties `pollIntervalSeconds` 1, `idlePollIntervalSeconds` 30; after connect, `fake.playElsewhere(...)` then `session.execute(new Pause())` (forces an immediate poll) → within 3 s at least 2 more `GetPositionInfo` calls.

`adapters/sonos/SonosSessionTest.java` — add `groupMembersShowTheCoordinatorsNowPlaying`: `household.join(KITCHEN, LIVING)`; living plays `song` via its own session → kitchen session's `state().nowPlaying().title()` eventually `Bunny Song`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: compilation failure — `UpnpTime`, `PositionInfo`, `NowPlayings`, `DidlLite.title` do not exist.

- [ ] **Step 5: Implement now-playing**

`adapters/upnp/protocol/UpnpTime.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UPnP AVTransport times: H+:MM:SS[.F+] or H+:MM:SS.F0/F1. */
public final class UpnpTime {

    private static final Pattern TIME = Pattern.compile("^[+-]?(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d+)(?:/(\\d+))?)?$");

    private UpnpTime() {
    }

    public static Double seconds(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = TIME.matcher(value.strip());
        if (!m.matches()) {
            return null;
        }
        double seconds = Long.parseLong(m.group(1)) * 3600.0 + Integer.parseInt(m.group(2)) * 60.0 + Integer.parseInt(m.group(3));
        if (m.group(4) != null) {
            if (m.group(5) != null) {
                long denominator = Long.parseLong(m.group(5));
                if (denominator == 0) {
                    return null;
                }
                seconds += Long.parseLong(m.group(4)) / (double) denominator;
            } else {
                seconds += Double.parseDouble("0." + m.group(4));
            }
        }
        return seconds;
    }
}
```

(The test `"00:00:05.1/4"` has a two-digit hour group, which `\d+` accepts; `"3:07"` fails the pattern.)

`adapters/upnp/protocol/PositionInfo.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Map;

public record PositionInfo(String trackUri, String trackMetadata, Double positionSeconds, Double durationSeconds) {

    public static PositionInfo from(Map<String, String> answer) {
        Double duration = UpnpTime.seconds(answer.get("TrackDuration"));
        return new PositionInfo(answer.getOrDefault("TrackURI", "").strip(), answer.getOrDefault("TrackMetaData", ""),
                UpnpTime.seconds(answer.get("RelTime")), duration != null && duration > 0 ? duration : null);
    }

    @Override
    public String toString() {
        return "PositionInfo[position=" + positionSeconds + ", duration=" + durationSeconds + "]";
    }
}
```

`adapters/upnp/protocol/PlayedItem.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

/** What this session last asked the renderer to play; the URI may carry a credential. */
public record PlayedItem(String uri, String title) {
    @Override
    public String toString() {
        return "PlayedItem[title=" + title + "]";
    }
}
```

`adapters/upnp/protocol/NowPlayings.java`:

```java
package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;

import java.util.Optional;

public final class NowPlayings {

    private NowPlayings() {
    }

    /** Null when nothing is loaded and playing, paused or buffering. */
    public static NowPlaying of(TransportInfo transport, PositionInfo position, PlayedItem lastPlayed) {
        PlaybackState state = switch (transport.state()) {
            case "PLAYING" -> PlaybackState.PLAYING;
            case "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlaybackState.PAUSED;
            case "TRANSITIONING" -> PlaybackState.BUFFERING;
            default -> null;
        };
        if (state == null) {
            return null;
        }
        String title = DidlLite.title(position.trackMetadata())
                .or(() -> Optional.ofNullable(lastPlayed)
                        .filter(played -> played.uri().equals(position.trackUri()))
                        .map(PlayedItem::title)
                        .filter(played -> !played.isBlank()))
                .orElse("Unknown title");
        return new NowPlaying(title, state, position.positionSeconds() == null ? 0.0 : position.positionSeconds(),
                position.durationSeconds());
    }
}
```

`DidlLite.java` — add (imports `java.nio.charset.StandardCharsets`, `java.util.Optional`):

```java
    /** The first non-blank title of a DIDL-Lite document; empty for NOT_IMPLEMENTED, blanks or unreadable XML. */
    public static Optional<String> title(String didl) {
        if (didl == null || didl.isBlank() || didl.strip().equalsIgnoreCase("NOT_IMPLEMENTED")) {
            return Optional.empty();
        }
        try {
            return UpnpXml.descendants(UpnpXml.parse(didl.getBytes(StandardCharsets.UTF_8)), "title").stream()
                    .map(element -> element.getTextContent().strip())
                    .filter(text -> !text.isEmpty())
                    .findFirst();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
```

`RendererCommands.java` — add:

```java
    public PositionInfo positionInfo(ServiceEndpoint avTransport) throws IOException, SoapFault {
        return PositionInfo.from(soap.call(avTransport.controlUrl(), UpnpActions.getPositionInfo(avTransport.serviceType())));
    }
```

`UpnpSession.java`:
- field `private volatile PlayedItem lastPlayed;`
- in `execute`, the `PlayMedia` branch becomes `{ commands.playUri(current.avTransport(), current.sink(), play, DidlLite.DLNA_STREAMING); lastPlayed = new PlayedItem(play.url().toString(), play.title()); }`.
- in `readState`, after reading `info`:

```java
        NowPlaying nowPlaying = null;
        if (info.active()) {
            try {
                nowPlaying = NowPlayings.of(info, commands.positionInfo(current.avTransport()), lastPlayed);
            } catch (SoapFault fault) {
                nowPlaying = NowPlayings.of(info, new PositionInfo("", "", null, null), lastPlayed);
            }
        }
        DeviceState next = state.withStatus(DeviceStatus.CONNECTED).withPower(true).withNowPlaying(nowPlaying);
```

`SonosSession.java`: same field; in the `PlayMedia` branch remember `new PlayedItem(<the rewritten URI>.toString(), play.title())` after `playUri`; in `readState` read `positionInfo(coordinatorAvTransport())` when `info.active()` and publish `withNowPlaying(NowPlayings.of(info, position, lastPlayed))` (null otherwise). A member's session did not send the coordinator's URI, so its title comes from the coordinator's metadata — the fake echoes the DIDL it received, which is what a real coordinator does.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS.

- [ ] **Step 6: Write the failing Jellyfin tests**

`sources/jellyfin/JellyfinContentSourceTest.java`:
- rename `offersThreeRailsInOrder` to `offersFiveRailsInOrder`: ids `resume, next-up, latest, music-recent, music-latest`; titles `Continue watching, Next up, Latest in library, Recently played music, Latest music`.
- `recentlyPlayedMusicAsksForPlayedTracks`: fake `GET /Items` → `music-recent.json` → `rail("music-recent")` has 2 items, the first `TRACK` titled `Bunny Song` with subtitle `The Rabbits` and artwork `/sources/jellyfin/images/a1b0c2d3e4f5061728394a5b6c7d8e9f/Primary?tag=d4e5f6a7b8c9`; recorded query equals exactly `{userId=<USER_ID>, limit=20, includeItemTypes=Audio, recursive=true, filters=IsPlayed, sortBy=DatePlayed, sortOrder=Descending, enableUserData=true, enableImageTypes=Primary,Thumb,Backdrop, imageTypeLimit=1}`.
- `latestMusicAsksForUngroupedTracks`: fake `GET /Items/Latest` → `music-latest.json` → 2 items, first `Carrot Waltz`; query has `includeItemTypes=Audio` and `groupItems=false`.

`sources/jellyfin/JellyfinPlayableResolverTest.java` — `aMediaRendererGetsTheAudioStreamOnly` (use the existing test's setup: connected settings, fake server): device `Device("upnp-10-0-0-30", "Kitchen Speaker", UPNP, "10.0.0.30", {upnp: {}}, now)`; fake `GET /Sessions` → `sessions.json`, `GET /Items/c0ffee00c0ffee00c0ffee00c0ffee01` → `item-track.json`, `POST /Items/c0ffee00c0ffee00c0ffee00c0ffee01/PlaybackInfo` → `playback-info-audio.json`; `resolve(JellyfinItem(SERVER_ID, "c0ffee00c0ffee00c0ffee00c0ffee01", 0), item, device, EnumSet.of(MEDIA_RENDERER, VOLUME))` → playables exactly `[StreamUrl(<deviceServerUrl>/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&ApiKey=<token>, "audio/flac")]` (no `CastMessage`), no live capabilities, notes `[no Jellyfin app is open on Kitchen Speaker]`. (C7 already implements this; the test pins the speaker path. If it fails, fix C7's resolver, not the test.)

`sources/jellyfin/JellyfinFixtureContractTest.java` — add `music-recent.json:Items` and `music-latest.json:` to the parameterized rail fixtures, and include `item-track.json` in the credential check.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*'`
Expected: FAIL — the music rails do not exist.

- [ ] **Step 7: Implement the music rails**

`JellyfinContentSource.java`:

```java
    static final RailDescriptor MUSIC_RECENT = new RailDescriptor(JellyfinSettings.SOURCE_ID, "music-recent", "Recently played music");
    static final RailDescriptor MUSIC_LATEST = new RailDescriptor(JellyfinSettings.SOURCE_ID, "music-latest", "Latest music");
```

`rails()` returns `List.of(RESUME, NEXT_UP, LATEST, MUSIC_RECENT, MUSIC_LATEST)` when available; `rail(railId)` gains:

```java
            case "music-recent" -> new Rail(MUSIC_RECENT, JellyfinItemMapper.toItems(client.get(connection, "/Items",
                    listQuery(connection, "includeItemTypes", "Audio", "recursive", "true", "filters", "IsPlayed",
                            "sortBy", "DatePlayed", "sortOrder", "Descending")).path("Items")), clock.instant());
            case "music-latest" -> new Rail(MUSIC_LATEST, JellyfinItemMapper.toItems(client.get(connection, "/Items/Latest",
                    listQuery(connection, "includeItemTypes", "Audio", "groupItems", "false"))), clock.instant());
```

(If D4 added rail preferences that list known rail ids, register the two new ids there with default "shown".)

- [ ] **Step 8: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.*' --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.adapters.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: stream to media renderers, speaker now-playing and Jellyfin music rails"
```

---

### Task 4: I4 · Fake renderer and acceptance

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/web/SpeakersEndToEndTest.java`, `src/test/java/dev/andre/homecontrol/web/SpeakerJellyfinEndToEndTest.java`, `docs/superpowers/reviews/2026-09-16-wifi-speakers-acceptance.md`
- Modify: `adapters/upnp/FakeUpnpRenderer.java` (failure hooks), `adapters/upnp/UpnpSessionTest.java`, `adapters/upnp/protocol/SoapClientTest.java`, `adapters/sonos/SonosSessionTest.java`, `README.md`
- Uses unchanged: `FakeSsdpResponder` (F1/F5), `FakeSonosHousehold`/`FakeSonosPlayer` (Task 2), `FakeJellyfinServer` (C), every fixture above.

**Interfaces:**
- Consumes: the whole application context; `DeviceManager.addDiscovered/adopt/state/states/forget/speakerTopology`; `SsdpDiscovery.listenPort()`; endpoints `/setup`, `/setup/add` (B), `/setup/forget`, `/devices/{id}/play|pause|resume|stop|volume|mute|group/join/{member}|group/leave|route`, `/`, `/sources/jellyfin/rails/{id}`, `/setup/sources/jellyfin`.
- Produces: `FakeUpnpRenderer.delayAnswers(Duration)`, `answerRaw(String action, int status, String body)`, `overrideDescription(String xml)`; end-to-end proof over real sockets from SSDP to SOAP; the manual checklist; README section.

- [ ] **Step 1: Write the failing failure-mode tests**

Add to `FakeUpnpRenderer`:
- `delayAnswers(Duration delay)` — every SOAP answer waits that long before replying (`Thread.sleep` in `soap`, before `perform`); `Duration.ZERO` switches it off.
- `answerRaw(String action, int status, String body)` — the next and every later call of that action is recorded and answered with exactly that status and body (cleared by `answerRaw(action, 0, null)`).
- `overrideDescription(String xml)` — `document(layout.descriptionPath())` returns this text instead of the fixture while non-null.

New cases:
- `UpnpSessionTest.aSlowRendererFailsTheCommandAndRecovers`: command timeout 1 s; after connected, `delayAnswers(Duration.ofSeconds(2))` → `execute(new SetVolume(30))` throws `ActionFailedException` containing `did not answer in time`; `delayAnswers(Duration.ZERO)` → eventually `CONNECTED` again (a timed-out poll may have disconnected it meanwhile).
- `UpnpSessionTest.garbageAnswersAreIgnoredWhilePolling`: `answerRaw("GetVolume", 200, "not xml")` → state stays `CONNECTED` for 3 s; `answerRaw("Pause", 200, "<x/>")` → `execute(new Pause())` throws `ActionFailedException` containing `Unreadable answer to Pause`.
- `UpnpSessionTest.aDoctypeInAnAnswerIsRefused`: `answerRaw("GetTransportInfo", 200, "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>")` → no exception escapes the loop; the session stays `CONNECTED` and no published state contains `root:`.
- `UpnpSessionTest.controlUrlsOnAnotherHostAreRefused`: before start, `overrideDescription(<renderer-description.xml with the AVTransport controlURL replaced by http://192.0.2.1:1/upnp/control/AVTransport1>)` → for 3 s the state is never `CONNECTED` and `fake.calls("SetAVTransportURI")` and every SOAP call list is empty.
- `UpnpSessionTest.aDescriptionThatIsNotXmlIsAConnectFailure`: `overrideDescription("<html>")` → never `CONNECTED`; after `overrideDescription(null)` → `CONNECTED` within the backoff (≤ 5 s).
- `SoapClientTest.anOversizedAnswerIsRefused`: server answers 200 with 4 MiB + 1 byte → `IOException` whose message contains `larger than`.
- `SonosSessionTest.aVanishedCoordinatorMakesTheMemberOffline`: `household.join(KITCHEN, LIVING)`; kitchen connected; `living.hangUp(true)` → kitchen state eventually `DISCONNECTED` (its poll reads the coordinator); `living.hangUp(false)` → kitchen `CONNECTED`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: compilation failure for the new hooks; after adding them, any failure is a real defect in Tasks 1–3 — fix the production code in this task and name it in the commit body.

- [ ] **Step 2: Write the speakers end-to-end test**

`src/test/java/dev/andre/homecontrol/web/SpeakersEndToEndTest.java` — `@SpringBootTest` with `@AutoConfigureMockMvc`, real sockets, structured like F5's `WebOsEndToEndTest`:

```java
@SpringBootTest
@AutoConfigureMockMvc
class SpeakersEndToEndTest {

    static final String LIVING = "RINCON_000E58A0B1C201400";
    static final String KITCHEN = "RINCON_000E58C3D4E501400";
    static final FakeSsdpResponder SSDP;
    static final FakeUpnpRenderer RENDERER;
    static final FakeSonosHousehold HOUSEHOLD;
    static final FakeSonosPlayer LIVING_ROOM;
    static final FakeSonosPlayer KITCHEN_ROOM;
    static final Path DATA;

    static {
        try {
            RENDERER = new FakeUpnpRenderer();
            HOUSEHOLD = new FakeSonosHousehold();
            LIVING_ROOM = HOUSEHOLD.addPlayer("127.0.0.2", LIVING, "Living Room");
            KITCHEN_ROOM = HOUSEHOLD.addPlayer("127.0.0.3", KITCHEN, "Kitchen");
            SSDP = new FakeSsdpResponder();
            SSDP.answer(UpnpDiscovery.SEARCH_TARGET, RENDERER.searchResponse());
            SSDP.answer(SonosDiscovery.SEARCH_TARGET, LIVING_ROOM.searchResponse());
            DATA = Files.createTempDirectory("speakers-e2e");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shield.data-dir", DATA::toString);
        registry.add("home-control.ssdp.enabled", () -> "true");
        registry.add("home-control.ssdp.multicast-address", () -> "127.0.0.1");
        registry.add("home-control.ssdp.port", SSDP::port);
        registry.add("home-control.ssdp.listen-port", () -> "0");
        registry.add("home-control.ssdp.search-interval-seconds", () -> "1");
        registry.add("home-control.webos.enabled", () -> "false");
        registry.add("home-control.tizen.enabled", () -> "false");
        registry.add("home-control.upnp.poll-interval-seconds", () -> "1");
        registry.add("home-control.upnp.idle-poll-interval-seconds", () -> "1");
        registry.add("home-control.upnp.reconnect-initial-delay-seconds", () -> "30");
        registry.add("home-control.upnp.reconnect-max-delay-seconds", () -> "60");
        registry.add("home-control.sonos.poll-interval-seconds", () -> "1");
        registry.add("home-control.sonos.idle-poll-interval-seconds", () -> "1");
        registry.add("home-control.sonos.topology-interval-seconds", () -> "1");
    }

    // @AfterAll closes SSDP, RENDERER, HOUSEHOLD.
}
```

Autowire `MockMvc mockMvc`, `DeviceManager devices`, `DeviceRegistry registry`, `SsdpDiscovery ssdp`. Awaitility ≤ 10 s per wait. Test `discoversControlsAndGroupsSpeakers`, in order:

1. `GET /setup` eventually contains `Kitchen Speaker`, `Living Room` and `Kitchen` (B's addable list).
2. `POST /setup/add` `adapter=upnp`, `host=127.0.0.1`, `port=<RENDERER.port()>` → 3xx; `registry.findById("upnp-127-0-0-1")` has kind `UPNP` and `adapterSettings("upnp").get("udn")` = `FakeUpnpRenderer.UDN`.
3. `devices.state("upnp-127-0-0-1")` becomes `CONNECTED` with `volumeLevel` 20; `GET /?device=upnp-127-0-0-1` contains `/devices/upnp-127-0-0-1/pause` and `/devices/upnp-127-0-0-1/play`.
4. `POST /devices/upnp-127-0-0-1/volume level=40` → 204; `RENDERER.volume()` 40.
5. `POST /devices/upnp-127-0-0-1/play uri=http://127.0.0.1:9/music/Bunny%20Song.flac` → 200 `Stream directly to this device (DLNA/UPnP)`; `RENDERER.currentUri()` equals the URL; `currentMetadata()` contains `<dc:title>Bunny Song.flac</dc:title>` and `audio/flac`; state `nowPlaying` title `Bunny Song.flac`, `PLAYING`.
6. `POST …/pause` → 204 → `nowPlaying.state()` `PAUSED`; `POST …/resume` → `PLAYING`; `POST …/stop` → `nowPlaying` null.
7. `POST …/play uri=http://127.0.0.1:9/films/bunny.mp4` → 422 with body `Kitchen Speaker cannot play video/mp4`.
8. `RENDERER.hangUp(true)` → `DISCONNECTED`; `POST …/volume level=10` → 409; `RENDERER.hangUp(false)`; send the NOTIFY `"NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nNTS: ssdp:alive\r\nUSN: uuid:5f9ec1b3-ed59-4f00-a3c1-2d2b4a1e0001::urn:schemas-upnp-org:device:MediaRenderer:1\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: " + RENDERER.location() + "\r\n\r\n"` from a `DatagramSocket` to `127.0.0.1:ssdp.listenPort()` → `CONNECTED` within 10 s although the reconnect backoff is 30 s.
9. `POST /setup/add adapter=sonos host=127.0.0.2 port=<LIVING_ROOM.port()>` and the same for `127.0.0.3` → both registered with kind `SONOS` (ids `sonos-127-0-0-2`, `sonos-127-0-0-3`) and eventually `CONNECTED`.
10. `GET /?device=sonos-127-0-0-3` contains `/devices/sonos-127-0-0-3/group/join/RINCON_000E58A0B1C201400` and `Join Living Room`.
11. `POST /devices/sonos-127-0-0-3/group/join/RINCON_000E58A0B1C201400` → 204 with `HX-Refresh: true`; `HOUSEHOLD.coordinatorOf(KITCHEN)` = `LIVING`; eventually `devices.speakerTopology("sonos-127-0-0-3")` is `grouped()`.
12. `LIVING_ROOM.clearCalls(); KITCHEN_ROOM.clearCalls();` then `POST /devices/sonos-127-0-0-3/play uri=http://127.0.0.1:9/music/song.mp3` → 200; `LIVING_ROOM.commandNames()` = `[SetAVTransportURI, Play]`; `KITCHEN_ROOM.commandNames()` empty; eventually `devices.state("sonos-127-0-0-3").nowPlaying().title()` = `song.mp3`.
13. `POST /devices/sonos-127-0-0-3/group/leave` → 204; `KITCHEN_ROOM.commandNames()` ends with `BecomeCoordinatorOfStandaloneGroup`; `HOUSEHOLD.isCoordinator(KITCHEN)` true.
14. `POST /devices/upnp-127-0-0-1/key/HOME` → 422 (a renderer has no remote keys).
15. `POST /setup/forget id=…` for all three devices → the registry is empty and `devices.states()` has none of the ids.

- [ ] **Step 3: Write the Jellyfin speaker end-to-end test**

`src/test/java/dev/andre/homecontrol/web/SpeakerJellyfinEndToEndTest.java` — copy the class frame of C9's `JellyfinEndToEndTest` (Spring Boot on a random port, temp data dir, `HttpClient` with a cookie manager for the browser and one without for a stranger, the `send/get/page/post` helpers, collecting every browser response body) and SSDP disabled. One test `playsAJellyfinTrackOnASpeakerWithoutLeakingTheToken`:

```java
        try (FakeJellyfinServer jellyfin = new FakeJellyfinServer().withConnectableServer()
                     .respond("GET", "/Items", 200, "music-recent.json")
                     .respond("GET", "/Sessions", 200, "sessions.json")
                     .respond("GET", "/Items/" + TRACK, 200, "item-track.json")
                     .respond("POST", "/Items/" + TRACK + "/PlaybackInfo", 200, "playback-info-audio.json");
             FakeUpnpRenderer speaker = new FakeUpnpRenderer()) {
            devices.adopt(speaker.device("speaker-e2e"));
            try {
                // connect Jellyfin exactly as C9 does (sets the login password and logs the browser in)
                // …
                await().until(() -> devices.state("speaker-e2e").status() == DeviceStatus.CONNECTED);
                assertThat(send(browser, get("/sources/jellyfin/rails/music-recent")).body()).contains("Bunny Song").contains("The Rabbits");
                assertThat(send(browser, get("/devices/speaker-e2e/route?source=jellyfin&item=" + TRACK)).body())
                        .isEqualTo("Stream directly to this device (DLNA/UPnP)");
                HttpResponse<String> played = send(browser, post("/devices/speaker-e2e/play", Map.of("source", "jellyfin", "item", TRACK)));
                assertThat(played.statusCode()).isEqualTo(200);
                assertThat(speaker.currentUri()).startsWith(jellyfin.url() + "/Audio/" + TRACK + "/stream.flac?static=true")
                        .contains("ApiKey=" + ACCESS_TOKEN);   // the device needs the key
                assertThat(speaker.currentMetadata()).contains("<dc:title>Bunny Song</dc:title>").contains("<upnp:artist>The Rabbits</upnp:artist>");
                await().until(() -> devices.state("speaker-e2e").nowPlaying() != null
                        && devices.state("speaker-e2e").nowPlaying().title().equals("Bunny Song"));
                assertThat(browserBodies).noneMatch(body -> body.contains(ACCESS_TOKEN) || body.contains("ApiKey"));
            } finally {
                devices.forget("speaker-e2e");
            }
        }
```

with `TRACK = "c0ffee00c0ffee00c0ffee00c0ffee01"`. Also open `/events` (SSE) for the browser for 3 s after playing and add what it received to `browserBodies` before the final assertion. (The renderer at `127.0.0.1` matches no Jellyfin session, so rung 1 is skipped; it is not a Cast receiver, so the stream is the only route.)

- [ ] **Step 4: Run the new tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.SpeakersEndToEndTest' --tests 'dev.andre.homecontrol.web.SpeakerJellyfinEndToEndTest' --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS. A flaky wait gets a longer Awaitility timeout, never a sleep. If step 7 answers 409 or 502 instead of 422, check B's `DeviceController` mapping for `UnsupportedActionException` before changing the assertion.

- [ ] **Step 5: Commit the tests**

```bash
git add -A
git commit -m "test: speakers end to end over SSDP, SOAP and Jellyfin with failure-mode fakes"
```

- [ ] **Step 6: Write the manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-wifi-speakers-acceptance.md`:

```markdown
# Wi-Fi speakers (sub-project I) — manual acceptance

Automated coverage: `SpeakersEndToEndTest`, `SpeakerJellyfinEndToEndTest` and the unit tests of Tasks 1–4,
all against in-process fakes (`FakeUpnpRenderer`, `FakeSonosHousehold`, `FakeSsdpResponder`, `FakeJellyfinServer`).
Agents cannot operate real speakers. Every item below is **Pending — requires real hardware** until a person
with the household's speakers runs it and replaces the status with Passed/Failed plus notes.
Household speakers are unknown (spec §13); record them first.

- DLNA/UPnP renderers (brand, model, firmware): _unknown_
- Sonos players (models, S1/S2, firmware version, stereo pairs / subs): _unknown_
- Host: Docker with `network_mode: host`: _unknown_

## UPnP / DLNA renderers

| # | Check | Status |
|---|---|---|
| U1 | The renderer appears under discovered devices on /setup within a minute, with its friendly name | Pending — requires real hardware |
| U2 | "Add" registers it; the chip turns connected and shows the speaker's volume | Pending — requires real hardware |
| U3 | A renderer built into a TV that is already registered (webOS/Tizen/Cast) merges into that chip instead of a new one | Pending — requires real hardware |
| U4 | Pasting a direct .mp3 link plays it; the chip shows the file name as now playing within 2 s | Pending — requires real hardware |
| U5 | A .flac link plays (or is refused with "cannot play audio/flac" if the device lacks FLAC — record which) | Pending — requires real hardware |
| U6 | Pause, Play and Stop in the drawer act within a second; now playing follows | Pending — requires real hardware |
| U7 | The volume slider sets the speaker volume; the speaker's own buttons are reflected within 10 s | Pending — requires real hardware |
| U8 | Mute and unmute work | Pending — requires real hardware |
| U9 | A video link on an audio-only speaker is refused with a clear reason | Pending — requires real hardware |
| U10 | Playback started from another app (e.g. BubbleUPnP) shows its title as now playing | Pending — requires real hardware |
| U11 | After power-cycling the speaker it reconnects on its own (it may pick a new port) | Pending — requires real hardware |
| U12 | A strict DLNA TV accepts the stream with the DLNA.ORG flags (record the model) | Pending — requires real hardware |

## Sonos

| # | Check | Status |
|---|---|---|
| S1 | Every room appears once by room name; stereo pair partners, subs and surrounds do not appear | Pending — requires real hardware |
| S2 | Sonos players do not additionally appear as plain UPnP renderers | Pending — requires real hardware |
| S3 | Adding a room connects it and shows its volume | Pending — requires real hardware |
| S4 | A direct .mp3/.flac link plays with the title shown in the Sonos app | Pending — requires real hardware |
| S5 | An Icecast MP3 radio URL without a file extension plays (x-rincon-mp3radio) | Pending — requires real hardware |
| S6 | "Join <room>" groups the rooms; the Sonos app shows the same group | Pending — requires real hardware |
| S7 | Playing on a grouped room plays on the whole group | Pending — requires real hardware |
| S8 | Volume on a grouped room changes only that room | Pending — requires real hardware |
| S9 | "Leave group" separates the room; the rest of the group keeps playing | Pending — requires real hardware |
| S10 | Grouping changed in the Sonos app shows in the drawer within 30 s | Pending — requires real hardware |
| S11 | Now playing shows titles of Spotify/radio started from the Sonos app | Pending — requires real hardware |
| S12 | Pause/Play/Stop on a grouped room act on the group | Pending — requires real hardware |

## Jellyfin music

| # | Check | Status |
|---|---|---|
| J1 | "Recently played music" and "Latest music" rails list tracks with artist and album art | Pending — requires real hardware |
| J2 | Playing a track on a UPnP speaker plays it from the start with title and artist on devices with a display | Pending — requires real hardware |
| J3 | Playing a track on a Sonos room plays it; FLAC and MP3 libraries both work | Pending — requires real hardware |
| J4 | A track in a format the speaker cannot play gives a reason instead of silence | Pending — requires real hardware |
| J5 | The play sheet names the route "Stream directly to this device (DLNA/UPnP)" before playing | Pending — requires real hardware |

## General

| # | Check | Status |
|---|---|---|
| G1 | `HOME_CONTROL_UPNP_ENABLED=false` removes renderers; Sonos still works | Pending — requires real hardware |
| G2 | `HOME_CONTROL_SONOS_ENABLED=false` removes the Sonos module; players appear as plain renderers | Pending — requires real hardware |
| G3 | With bridge networking nothing is discovered and nothing breaks | Pending — requires real hardware |
| G4 | CPU and network use stay negligible with all speakers idle for an hour | Pending — requires real hardware |

## Findings

_None yet._
```

- [ ] **Step 7: Document Wi-Fi speakers**

`README.md` — add `## Wi-Fi speakers (DLNA/UPnP and Sonos)` after the Smart TVs section: speakers are found over SSDP (UDP 1900, host networking required) and added with **Add** on the setup page (a renderer inside an already registered TV is merged automatically); what works (play a direct link or a Jellyfin track, pause/play/stop, volume, mute, now playing; Sonos grouping from the drawer, playing on a grouped room plays on its group, volume stays per room); limits (the speaker must reach the stream URL — set Jellyfin's "address for TVs and speakers"; only formats the speaker lists are sent; now playing refreshes every 2 s while playing and 10 s when idle; bonded Sonos speakers show as one room; two renderers on one IP are not supported). Configuration rows:

| Property | Default | Meaning |
|---|---|---|
| `home-control.upnp.enabled` | `true` | DLNA/UPnP media renderer module |
| `home-control.upnp.poll-interval-seconds` | `2` | State polling while something plays |
| `home-control.upnp.idle-poll-interval-seconds` | `10` | State polling while idle |
| `home-control.sonos.enabled` | `true` | Sonos module (off: players appear as plain renderers) |
| `home-control.sonos.topology-interval-seconds` | `30` | How often group topology is re-read |

- [ ] **Step 8: Build and commit the documentation**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add README.md docs/superpowers/reviews/2026-09-16-wifi-speakers-acceptance.md
git commit -m "docs: Wi-Fi speakers setup, limits and the manual acceptance checklist"
```

---

## Final Automated Verification

- [ ] `.superpowers/gradle.sh build` — BUILD SUCCESSFUL with every test above.
- [ ] `grep -rn "adapters\.upnp\|adapters\.sonos\|discovery\.ssdp" src/main/java/dev/andre/homecontrol/{core,device,playback,web,sources,security}` — no matches.
- [ ] `grep -rn "import dev.andre.homecontrol.adapters.upnp\.[A-Z]" src/main/java/dev/andre/homecontrol/adapters/sonos` — no matches (Sonos uses only `adapters.upnp.protocol`).
- [ ] `grep -rn "DocumentBuilderFactory" src/main/java` — only `UpnpXml` and F1's `DeviceDescriptions`.
- [ ] `grep -rn "HttpClient.newBuilder\|HttpClient.newHttpClient" src/main/java/dev/andre/homecontrol/adapters/upnp src/main/java/dev/andre/homecontrol/adapters/sonos` — only `SoapClient.httpClient`.
- [ ] The acceptance checklist has no item other than "Pending — requires real hardware".

## Out of scope for this plan

- GENA eventing (`SUBSCRIBE`/`NOTIFY`); polling covers now-playing.
- Sonos group volume (`GroupRenderingControl`), queues, favourites, Sonos music services, line-in and TV inputs.
- Album, playlist and artist playback (a queue route); Jellyfin transcoding URLs for formats outside C6's profile.
- Cover art in DIDL-Lite (needs an absolute, unauthenticated artwork URL).
- AirPlay speakers (spec: later) and Bluetooth speakers (sub-project J).
- Cast pause/resume through `Action.Pause`/`Resume`.
