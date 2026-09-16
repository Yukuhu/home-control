# Smart TV Adapters (Sub-project F) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Control LG webOS and Samsung Tizen TVs from the home control center: find them with one shared SSDP listener, pair through the TV's own on-screen prompt, send keys, volume and power (Wake-on-LAN to switch on), open YouTube and Netflix links in the TV's apps where the platform allows it, and give the setup page a per-device "test deep link" button that reports honestly what the adapter could observe.

**Architecture:** A shared `discovery/ssdp` service (SSDP search/NOTIFY and UPnP device descriptions, next to sub-project B's `discovery/MdnsBrowser`, reused by sub-project I) and two adapter modules on the sub-project A/B contract (`DeviceAdapter` / `DeviceHandle` / `DeviceManager`): `adapters/webos` (SSAP over WebSocket plus the pointer input socket) and `adapters/tizen` (the Samsung remote-control WebSocket on 8002, the REST API on 8001 and DIAL on 8080). Protocol-neutral helpers live in `adapters/net` (a blocking text WebSocket over `java.net.http`, a trust-any-certificate HTTP client scoped to TV connections, Wake-on-LAN) and `adapters/links` (YouTube / Netflix id extraction). A device found by a second adapter merges into the registered device with the same host (`DeviceManager.attach`). Pairing is exposed to the web layer through a brand-free `core.PromptPairing` interface; the deep-link test is a `playback.DeepLinkTestService` that watches `DeviceStateChangedEvent`s for a foreground-app change.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle 9.7.1, Thymeleaf, htmx 2, JUnit 5, AssertJ, Mockito, Awaitility. **No new dependencies:** WebSockets use `java.net.http.HttpClient.newWebSocketBuilder()` (verified in a JDK 25 prototype against a hand-rolled TLS WebSocket server with a certificate for a different host name: the scoped trust-all client connects, `HttpClient.newHttpClient()` fails with `SSLHandshakeException`, and client-side frame fragmentation of a 70 000-character message is reassembled correctly by the fake server code in Task 2); XML uses the JDK DOM parser; test HTTP fakes use the JDK's `com.sun.net.httpserver.HttpServer`.

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §4.1 (webOS and Tizen rows), §5.1 (capabilities, merge by IP), §5.3 (optimistic app links, foreground-app feedback), §6.1 (inputs in the drawer), §7 (adapter packages, one discovery scheduler including SSDP), §8 (webOS client key and Tizen token in `devices.json`), §11 (deep-link risk and the test button), §12 (fake servers). Roadmap: `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md`, section F. Issues: epic #10, tasks #55–#59. Contract this plan builds on: `docs/superpowers/plans/2026-09-16-multi-device-core.md` (sub-project A). Sub-project B (Google Cast) lands before F; see Decisions for what F assumes from it.

**Protocol sources consulted (for the wire formats written out below):** `home-assistant-libs/aiowebostv` (`handshake.py` registration manifest, `endpoints.py` SSAP URIs, `buttons.py` pointer-socket button names, `webos_client.py` ws:3000 → wss:3001 fallback, PROMPT/registered/error handling, `volumeStatus` vs flat volume payloads); `ConnectSDK/Connect-SDK-Android-Core` `WebOSTVService.java` (`system.launcher/launch` with `contentId` + `params`, the Netflix `contentId` format, `system.launcher/open` with `target`); `xchwarze/samsung-tv-ws-api` (`connection.py` URL with base64 `name` and `token`, `ms.channel.connect` / `ms.channel.unauthorized`, `remote.py` `ms.remote.control` and `ed.apps.launch` / `ed.installedApp.get` payloads, `rest.py` `/api/v2/` and `/api/v2/applications/{id}`).

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode.
- Commands are ephemeral: a command that cannot be sent now fails now with a reason. Nothing is queued.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `device`, `playback` and `web` must not import anything under `adapters.webos`, `adapters.tizen`, `discovery.ssdp` or `adapters.net`.
- Route by capability, not by brand. The planner is not changed by this sub-project.
- Honesty about walled gardens: Netflix on Tizen opens the app, not the title; the UI and the checklist say so.
- Persistent state stays in `/data` as JSON written atomically; the existing `devices.json` and `keystore.p12` keep working without re-pairing.
- Every adapter is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; the planner is untouched.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), each ending with the two trailer lines required by `.superpowers/sdd/implementer-common.md`.

Build and tooling (this repository):

- There is no local JDK. Wherever a step says "build", run `.superpowers/gradle.sh build`; focused tests: `.superpowers/gradle.sh test --tests '<pattern>'`. Failing test detail: grep `<failure` in `build/test-results/test/*.xml`.
- Spring Boot 4.1.1: MockMvc test auto-configuration is `org.springframework.boot.webmvc.test.autoconfigure`; `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Jackson 3 (`tools.jackson.databind.*`, version 3.1.5 in the Gradle cache): use `JsonMapper.builder().build()`, `readTree`, `writeValueAsString`; on `JsonNode` use `path(..)`, `asString(default)`, `stringValue(default)`, `asInt(default)`, `asBoolean(default)`, `values()`; `ObjectNode.put/putObject/putArray/set`. Exceptions are unchecked `tools.jackson.core.JacksonException`.

Epic-specific constraints:

- Connecting to a TV without a stored credential makes the TV show a prompt. **Only the pairing flow may connect without a credential.** A session whose credential is missing or rejected is `UNPAIRED` and stops reconnecting until the user pairs again (the Android TV rule from v0.3, applied to TVs).
- Trust-any-certificate TLS is used only by HTTP clients created through `adapters.net.InsecureTls` and only for TV connections. Never install a default `SSLContext`, never set `jdk.internal.httpclient.disableHostnameVerification`.
- Never send on a WebSocket from inside a WebSocket listener callback; hand off to the session's scheduler thread.
- Never log a Tizen token or a webOS client key; log URIs without their query string.
- `DeviceState` is created only through `DeviceState.initial()`, `DeviceState.unpaired()` and its `with…` methods, never through the canonical constructor (sub-project B adds a component).
- Unknown device id → HTTP 404 everywhere in the web layer (ruling from sub-project A).

---

## Decisions

- Decision: F reuses sub-project A's `Action.PressKey(RemoteKey.POWER)` as a state-aware power toggle (connected and on → switch off over the protocol; otherwise → Wake-on-LAN) instead of adding `PowerOn`/`PowerOff` actions — the drawer already has one Power button and the Android TV key is also a toggle — cost if wrong: a later "turn everything off" feature adds explicit actions and updates three handles.
- Decision: F is aligned with sub-project B's plan (`2026-09-16-google-cast-adapter.md`): it uses B's `Action.SetVolume(int level)` (0–100), `Action.Mute(boolean muted)`, `Action.Stop()` (which requires `CAST_RECEIVER`, so the manager never routes it to a TV; the TV handles still implement it for direct use), `Action.CastLoad` (rejected by TV handles), `DeviceState.nowPlaying` (TVs leave it null), `DeviceAdapter.kind()`, `ActionFailedException` (HTTP 502) and the manager's fall-through on `UnsupportedActionException` / `DeviceOfflineException` — cost if wrong: renames in two `switch` statements.
- Decision: TV handles throw `ActionFailedException` when the TV answered with an error (an SSAP `error`, a DIAL 404/503) and `UnsupportedActionException` only when the protocol has no way to do it (no button, no absolute volume on Tizen, a web link on Tizen) — so B's fall-through tries another adapter only in the second case — cost if wrong: a refused launch is not retried through a second adapter of a merged device.
- Decision: prompt-paired TVs merge by host only (equal ignoring case, or equal after `InetAddress.getByName`) through a new `DeviceManager.attach(host, name, kind, adapterId, settings)` that ends in B's `adopt` (which also absorbs a Cast receiver at that address). B's name fallback is not used for TVs because SSDP, SSAP, Tizen REST and Cast report different names for the same set; B's manual merge/split on the setup page covers the rest. New TV devices get B's id convention `<adapterId>-<host slug>` (`webos-192-168-1-60`) — cost if wrong: a TV whose IP changed appears twice until the user merges or forgets one.
- Decision: a merge overlays the new adapter settings onto the existing entry for that adapter and keeps the device's id, name, kind and adapter order — so re-pairing preserves a hand-entered MAC address and the first adapter stays primary — cost if wrong: a stale setting survives a re-pair; forgetting the device clears it.
- Decision: SSDP lives in `discovery/ssdp` next to B's shared `discovery/MdnsBrowser`, as a bean that is always created and does network I/O only when `home-control.ssdp.enabled` is true (default true), mirroring `MdnsBrowser(boolean enabled)` — adapters depend on it without `Optional` injection, and sub-project I reuses `watch` / `services` / `addListener` / `DeviceDescription` — cost if wrong: one idle bean when every SSDP adapter is off.
- Decision: `home-control.webos.enabled` and `home-control.tizen.enabled` default to true — nothing connects until a TV is paired, and discovery of an unpaired TV is passive; plug-and-play wins — cost if wrong: two SSDP search targets per minute on networks without TVs.
- Decision: the MAC address for Wake-on-LAN is learned from the TV itself after every successful connect (webOS `ssap://com.webos.service.connectionmanager/getinfo`, Tizen `GET http://host:8001/api/v2/` → `device.wifiMac`) and stored as `macAddress` in that adapter's settings; the setup page lets the user enter or correct it, which sets `macAddressManual=true` and stops learning. No ARP-table scraping — cost if wrong: a TV that was never on while the server ran cannot be woken until the user types the MAC.
- Decision: webOS connects with `ws://host:3000` first and falls back to `wss://host:3001` (aiowebostv's order, which copes with firmware that disabled the plain port); ports are properties, not per-device settings, so tests point them at fakes — cost if wrong: one refused connection per reconnect on TLS-only firmware.
- Decision: the webOS registration manifest is aiowebostv's unsigned manifest (no `signed`/`signatures` block) — it is what Home Assistant ships today — cost if wrong: a firmware that insists on a signed manifest refuses pairing; the checklist has an item for it.
- Decision: webOS YouTube deep links send both `contentId` and `params.contentTarget` set to `https://www.youtube.com/tv?v=<id>`; Netflix titles send ConnectSDK's `m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F<id>&source_type=4` as both `contentId` and `params.contentId`; Prime Video launches app `amazon` without content; any other https link opens in the TV browser via `ssap://system.launcher/open` — deep-link behaviour varies by firmware (spec §11), the test button exists for exactly this — cost if wrong: the app opens without the title; one string to change.
- Decision: Tizen translates app links as: YouTube video → DIAL `POST http://host:8080/ws/apps/YouTube` with body `v=<id>`; YouTube without a video, Netflix (any link) and Prime Video → `ed.apps.launch` of the installed app found by name in `ed.installedApp.get` (fallback ids `111299001912`, `3201907018807`, `3201910019365`); any other link → `UnsupportedActionException` naming what Samsung TVs can open — honest per roadmap F3 ("minus Netflix deep link") — cost if wrong: users get a clear refusal for links a newer firmware might open.
- Decision: no `WebOsLaunch` / `TizenLaunch` playable references and no generic `LaunchApp` action in this sub-project; handles translate `Action.OpenAppLink(uri)` themselves, so the planner stays brand-free and every source's `AppLink` works on TVs unchanged. Sub-project G3 can add explicit references later — cost if wrong: G3 moves the translation tables into link builders.
- Decision: inputs are a webOS-only drawer extra: `Action.SelectInput(String inputId)` (requires `REMOTE_KEYS`) plus an optional `core.InputListing` interface a handle may implement; Tizen rejects `SelectInput` with a reason because its WebSocket API has no input list — cost if wrong: Tizen users use the TV remote's Source button.
- Decision: pairing is blocking: `POST /setup/prompt-pair` waits until the TV answers or `pairing-timeout-seconds` (webOS 60, Tizen 30) elapse, with the page telling the user to look at the TV — one household, one pairing at a time, no polling endpoint to build — cost if wrong: a servlet thread is held for up to a minute during pairing.
- Decision: a Tizen session never connects without `paired=true` in its settings, and treats both `ms.channel.unauthorized` and "no `ms.channel.connect` within the request timeout" as `UNPAIRED` (the second usually means the TV is showing an Allow prompt because the token is no longer valid) — prevents a prompt every five seconds — cost if wrong: a very slow TV must be re-paired.
- Decision: Tizen has no push state: the session polls `GET /api/v2/` (power, MAC) and `GET /api/v2/applications/{id}` (visible) for the YouTube, Netflix and Prime Video app ids every `poll-interval-seconds` (5); volume stays unknown (`volumeMax` 0) — cost if wrong: `currentApp` is null while any other app is in front.
- Decision: absolute volume and explicit mute (`Action.SetVolume`, `Action.Mute`) are rejected on Tizen with a reason; only volume keys work there — the protocol has no volume read-back, and `KEY_MUTE` is a toggle — cost if wrong: a slider in a later UI is disabled on Samsung sets.
- Decision: `PLAY_PAUSE` on both TVs alternates between the platform's PAUSE and PLAY buttons, starting with PAUSE, because neither exposes a play/pause toggle key — cost if wrong: the first press after an app-side pause does nothing and the second works.
- Decision: foreground-app observability is declared per adapter by a new default method `DeviceAdapter.foregroundAppReporting(Device)` returning `LIVE` (Android TV, webOS, Cast — its receiver status pushes the running app), `POLLED` (Tizen, known apps only) or `NONE` (the default). The deep-link test reports `APP_CHANGED`, `NO_CHANGE`, `NOT_OBSERVABLE` or `FAILED` and always says that no adapter can see *which video* plays — cost if wrong: a wording change.
- Decision: the deep-link test uses `https://www.youtube.com/watch?v=aqz-KE-bpKQ` (Blender Foundation's "Big Buck Bunny", long-lived and region-free) configurable as `home-control.deep-link-test.youtube-url`, and waits `home-control.deep-link-test.timeout` (10 s, two Tizen poll intervals) — cost if wrong: a property change.
- Decision: the webOS client key and the Tizen token are pairing credentials stored in `devices.json` adapter settings as spec §8 says, not in the encrypted secret store of sub-project C — the Android TV client certificate has the same status and anyone on the LAN can re-pair with the TV's consent anyway — cost if wrong: a later migration moves two keys into `secrets.json`.
- Decision: the fake servers are built inside the tasks that need them (F1 fake SSDP responder, F2 fake WebSocket and SSAP servers, F3 fake Tizen server) so every task is test-first; Task 5 (F5) hardens them for failure modes and adds end-to-end Spring tests plus the manual checklist — cost if wrong: none; the roadmap's F5 deliverables all exist at the end.
- Decision: Task 3 (Tizen) runs after Task 2 (webOS) although the roadmap only blocks both on F1, because Tizen reuses `adapters/net`, `adapters/links`, `core.PromptPairing` and the setup-page pairing form that Task 2 creates — cost if wrong: none for a sequential executor.
- Decision: manual acceptance on real TVs cannot be performed by agents; Task 5 writes `docs/superpowers/reviews/2026-09-16-smart-tv-acceptance.md` with every item "Pending — requires real hardware" — cost if wrong: none.

---

## File Structure Map

Roots: `src/main/java/dev/andre/homecontrol/` and `src/test/java/dev/andre/homecontrol/`; paths below are relative to those roots unless they start with `src/` or `docs/`.

### Files to create

- `discovery/ssdp/SsdpProperties.java` — `home-control.ssdp.*`.
- `discovery/ssdp/SsdpMessage.java` — parse search responses / NOTIFY datagrams, build M-SEARCH.
- `discovery/ssdp/SsdpService.java` — one announced service (USN, type, address, location, expiry, description).
- `discovery/ssdp/DeviceDescription.java` — UPnP device description (friendly name, model, UDN, services with control URLs).
- `discovery/ssdp/DeviceDescriptions.java` — XXE-safe parser for the description XML.
- `discovery/ssdp/SsdpListener.java` — callback for alive / byebye.
- `discovery/ssdp/SsdpDiscovery.java` — sockets, periodic M-SEARCH, NOTIFY listener, service cache, description fetch.
- `discovery/ssdp/SsdpConfiguration.java` — bean wiring.
- `core/Hosts.java` — "same host" comparison.
- `device/DeviceMerge.java` — pure merge-by-host rule.
- `core/MacAddress.java` — parse and normalise MAC addresses.
- `core/WakeOnLanAdapter.java` — marker for adapters that wake devices; settings keys.
- `core/PromptPairing.java`, `core/PromptPairingResult.java` — brand-free "pair by accepting a prompt on the device".
- `core/TvInput.java`, `core/InputListing.java` — input list a handle may expose.
- `core/ForegroundAppReporting.java` — `LIVE`, `POLLED`, `NONE`.
- `adapters/net/TextWebSocket.java` — blocking text WebSocket over `java.net.http`.
- `adapters/net/InsecureTls.java` — trust-any-certificate `HttpClient` for TVs.
- `adapters/net/WakeOnLan.java`, `adapters/net/WakeOnLanProperties.java`, `adapters/net/NetConfiguration.java`.
- `adapters/links/ContentLinks.java` — YouTube video id and Netflix title id from URLs.
- `adapters/webos/WebOsProperties.java`, `WebOsSettings.java`, `SsapUris.java`, `SsapMessages.java`, `SsapException.java`, `SsapPairingException.java`, `SsapTimeoutException.java`, `SsapConnection.java`, `WebOsKeys.java`, `WebOsLaunch.java`, `WebOsLaunches.java`, `WebOsPayloads.java`, `WebOsSession.java`, `WebOsPairing.java`, `WebOsAdapter.java`, `WebOsConfiguration.java`.
- `adapters/tizen/TizenProperties.java`, `TizenSettings.java`, `TizenApp.java`, `TizenMessages.java`, `TizenRemoteConnection.java`, `TizenDeviceInfo.java`, `TizenRest.java`, `DialClient.java`, `DialException.java`, `TizenLaunch.java`, `TizenLaunches.java`, `TizenKeys.java`, `TizenSession.java`, `TizenPairing.java`, `TizenAdapter.java`, `TizenConfiguration.java`.
- `playback/DeepLinkTestProperties.java`, `playback/DeepLinkTestResult.java`, `playback/DeepLinkTestService.java`.
- `web/DeepLinkTestController.java`.
- Tests: `discovery/ssdp/SsdpMessageTest.java`, `DeviceDescriptionsTest.java`, `SsdpDiscoveryTest.java`, `FakeSsdpResponder.java`; `device/DeviceMergeTest.java`; `core/MacAddressTest.java`; `adapters/net/TextWebSocketTest.java`, `InsecureTlsTest.java`, `WakeOnLanTest.java`, `FakeWebSocketServer.java`, `FakeWakeOnLanReceiver.java`; `adapters/links/ContentLinksTest.java`; `adapters/webos/SsapConnectionTest.java`, `WebOsKeysTest.java`, `WebOsLaunchesTest.java`, `WebOsPayloadsTest.java`, `WebOsSessionTest.java`, `WebOsPairingTest.java`, `WebOsAdapterTest.java`, `FakeSsapServer.java`; `adapters/tizen/TizenMessagesTest.java`, `TizenRestTest.java`, `DialClientTest.java`, `TizenLaunchesTest.java`, `TizenKeysTest.java`, `TizenRemoteConnectionTest.java`, `TizenSessionTest.java`, `TizenPairingTest.java`, `TizenAdapterTest.java`, `FakeTizenServer.java`; `playback/DeepLinkTestServiceTest.java`; `web/DeepLinkTestControllerTest.java`, `web/PromptPairingSetupTest.java`, `web/WebOsEndToEndTest.java`, `web/TizenEndToEndTest.java`, `web/SmartTvModulesOffTest.java`.
- Fixtures: `src/test/resources/fixtures/ssdp/lg-search-response.txt`, `samsung-search-response.txt`, `lg-description.xml`, `samsung-description.xml`, `sonos-description.xml`; `src/test/resources/fixtures/webos/external-inputs.json`, `connection-info.json`, `volume-webos6.json`, `volume-webos4.json`; `src/test/resources/fixtures/tizen/device-info.json`, `installed-apps.json`.
- `docs/superpowers/reviews/2026-09-16-smart-tv-acceptance.md`.

### Files to modify

- `core/Action.java` — add `SelectInput`.
- `core/DeviceState.java` — add `sameIgnoringTime(DeviceState)`.
- `core/DeviceAdapter.java` — add `default ForegroundAppReporting foregroundAppReporting(Device)`.
- `core/playback/AppLinks.java` — make `serviceOf(String, String)` public.
- `device/DeviceManager.java` — `attach`, `wakesOnLan`, `wakeOnLanMac`, `setWakeOnLanMac`, `inputs`, `foregroundAppReporting`.
- `adapters/androidtv/AndroidTvSession.java`, `adapters/cast/CastSession.java` — `SelectInput` branch; `adapters/androidtv/AndroidTvAdapter.java`, `adapters/cast/CastAdapter.java` — `LIVE` reporting.
- `HomeControlConfiguration.java` — enable `DeepLinkTestProperties`.
- `web/SetupController.java`, `src/main/resources/templates/setup.html` — prompt pairing, MAC field, deep-link test button.
- `web/DeviceController.java` — `POST /devices/{id}/input/{inputId}`.
- `web/DashboardController.java`, `src/main/resources/templates/dashboard.html` — inputs in the drawer.
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.*` properties.
- `README.md` — Smart TVs section and configuration rows.
- `compose.yaml` — host networking comment covers SSDP and Wake-on-LAN.
- Tests: `HomeControlApplicationTest.java`, `core/ActionTest.java`, `core/DeviceStateTest.java`, `device/DeviceManagerTest.java`, `web/SetupControllerTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`, `adapters/androidtv/AndroidTvAdapterTest.java`, `adapters/cast/CastAdapterTest.java`.

### Files to delete

- None.

---
### Task 1: F1 · SSDP discovery service and device merge by host

**Files:**
- Create: `discovery/ssdp/SsdpProperties.java`, `SsdpMessage.java`, `SsdpService.java`, `DeviceDescription.java`, `DeviceDescriptions.java`, `SsdpListener.java`, `SsdpDiscovery.java`, `SsdpConfiguration.java`; `core/Hosts.java`; `device/DeviceMerge.java`
- Modify: `device/DeviceManager.java`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `discovery/ssdp/SsdpMessageTest.java`, `DeviceDescriptionsTest.java`, `SsdpDiscoveryTest.java`, `FakeSsdpResponder.java`; `device/DeviceMergeTest.java`, `device/DeviceManagerTest.java`; fixtures `src/test/resources/fixtures/ssdp/lg-search-response.txt`, `samsung-search-response.txt`, `lg-description.xml`, `samsung-description.xml`, `sonos-description.xml`

**Interfaces:**
- Consumes (sub-projects A and B): `Device(String id, String name, DeviceKind kind, String host, Map<String, Map<String, String>> adapters, Instant lastSeen)` with `adapterSettings`, `hasAdapter`, `withAdapter`; `DeviceKind`; `DeviceAdapter` (with B's `kind()`); `DeviceHandle`; `DeviceManager.adopt(Device)` (B: keeps existing adapters, absorbs addable receivers), `static uniqueId(List<Device>, String, String)`, `states()`, `capabilities(String)`; `DeviceRegistry`; test helper `device/StubAdapter` (B).
- Produces:
  - `record SsdpProperties(boolean enabled, String multicastAddress, int port, int listenPort, int searchIntervalSeconds, int mx)` bound to `home-control.ssdp`.
  - `record SsdpMessage(SsdpMessage.Kind kind, Map<String, String> headers)` with `static Optional<SsdpMessage> parse(byte[] data, int length)`, `Optional<String> header(String)`, `Optional<String> type()`, `boolean isByeBye()`, `Duration maxAge()`, `static byte[] search(String searchTarget, String hostHeader, int mx, String userAgent)`; `enum Kind { SEARCH_RESPONSE, NOTIFY, SEARCH_REQUEST }`.
  - `record SsdpService(String usn, String type, String address, URI location, Map<String, String> headers, Instant expiresAt, DeviceDescription description)` with `withDescription(DeviceDescription)`, `Optional<String> friendlyName()`.
  - `record DeviceDescription(String friendlyName, String manufacturer, String modelName, String udn, List<DeviceDescription.Service> services)`; `record Service(String serviceType, String serviceId, URI controlUrl, URI eventSubUrl, URI scpdUrl)`; `Optional<Service> service(String serviceTypePrefix)`.
  - `DeviceDescriptions.parse(byte[] xml, URI location) → DeviceDescription` (throws `IllegalArgumentException`).
  - `interface SsdpListener { void alive(SsdpService service); default void byebye(SsdpService service) {} }`.
  - `SsdpDiscovery { void start(); void watch(String searchTarget); void addListener(String searchTarget, SsdpListener listener); List<SsdpService> services(String searchTarget); int listenPort(); void close(); }`.
  - `core.Hosts.same(String a, String b) → boolean`.
  - `DeviceMerge.attach(List<Device> registered, String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings, Instant now, BiPredicate<String, String> sameHost) → Device`.
  - `DeviceManager.attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings) → Device`.

- [ ] **Step 1: Confirm sub-project B has landed**

```bash
grep -n "static String uniqueId\|public synchronized void adopt\|DeviceKind kind()" \
  src/main/java/dev/andre/homecontrol/device/DeviceManager.java src/main/java/dev/andre/homecontrol/core/DeviceAdapter.java
ls src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java src/test/java/dev/andre/homecontrol/device/StubAdapter.java
```

Expected: B's `DeviceManager.uniqueId(List<Device>, String adapterId, String host)`, its `adopt` that keeps existing adapters, `DeviceAdapter.kind()`, `discovery/MdnsBrowser` and the test helper `StubAdapter` all exist (plan `docs/superpowers/plans/2026-09-16-google-cast-adapter.md`, Task 2). If B has not landed, stop and report BLOCKED. B has no public "add this adapter with these settings to the device at this host" method (its `addDiscovered` only serves pairing-free adapters and its `bestMatch` skips devices that already carry the adapter), so this task adds `attach`.

- [ ] **Step 2: Add the SSDP fixtures**

`src/test/resources/fixtures/ssdp/lg-search-response.txt` (the file must end with one empty line; tests replace `{host}` and `{port}` and convert line endings to CRLF):

```text
HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
DATE: Tue, 16 Sep 2026 10:00:00 GMT
EXT:
LOCATION: http://{host}:{port}/lg/description.xml
SERVER: WebOS/4.1.0 UPnP/1.0 webOSTV/1.0
ST: urn:lge-com:service:webos-second-screen:1
USN: uuid:e8d7f6a5-1234-4bcd-9ef0-a8b7c6d5e4f3::urn:lge-com:service:webos-second-screen:1
DLNADeviceName.lge.com: %5BLG%5D%20webOS%20TV%20OLED55C9PLA

```

`src/test/resources/fixtures/ssdp/samsung-search-response.txt`:

```text
HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
DATE: Tue, 16 Sep 2026 10:00:00 GMT
EXT:
LOCATION: http://{host}:{port}/samsung/description.xml
SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0
ST: urn:samsung.com:device:RemoteControlReceiver:1
USN: uuid:5d9c0b1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b::urn:samsung.com:device:RemoteControlReceiver:1

```

`src/test/resources/fixtures/ssdp/lg-description.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>
    <friendlyName>[LG] webOS TV OLED55C9PLA</friendlyName>
    <manufacturer>LG Electronics</manufacturer>
    <modelName>WEBOS4.5</modelName>
    <UDN>uuid:e8d7f6a5-1234-4bcd-9ef0-a8b7c6d5e4f3</UDN>
    <serviceList>
      <service>
        <serviceType>urn:lge-com:service:webos-second-screen:1</serviceType>
        <serviceId>urn:lge-com:serviceId:webos-second-screen-3000-3001</serviceId>
        <SCPDURL>/WebOS_SecondScreen/scpd.xml</SCPDURL>
        <controlURL>/WebOS_SecondScreen/control</controlURL>
        <eventSubURL>/WebOS_SecondScreen/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
```

`src/test/resources/fixtures/ssdp/samsung-description.xml`:

```xml
<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:samsung.com:device:RemoteControlReceiver:1</deviceType>
    <friendlyName>[TV] Samsung 8 Series (55)</friendlyName>
    <manufacturer>Samsung Electronics</manufacturer>
    <modelName>UE55TU8079</modelName>
    <UDN>uuid:5d9c0b1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b</UDN>
    <sec:deviceID>M3CDVZ4LWVXQE</sec:deviceID>
    <serviceList>
      <service>
        <serviceType>urn:samsung.com:service:MultiScreenService:1</serviceType>
        <serviceId>urn:samsung.com:serviceId:MultiScreenService</serviceId>
        <SCPDURL>MultiScreenService.xml</SCPDURL>
        <controlURL>/smp_4_</controlURL>
        <eventSubURL>/smp_5_</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
```

`src/test/resources/fixtures/ssdp/sonos-description.xml` (for sub-project I: services live in an embedded device):

```xml
<?xml version="1.0" encoding="utf-8" ?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
    <friendlyName>192.168.1.70 - Sonos One</friendlyName>
    <manufacturer>Sonos, Inc.</manufacturer>
    <modelName>Sonos One</modelName>
    <UDN>uuid:RINCON_000E58A0B1C201400</UDN>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Living Room - Sonos One Media Renderer</friendlyName>
        <UDN>uuid:RINCON_000E58A0B1C201400_MR</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
            <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
            <eventSubURL>/MediaRenderer/AVTransport/Event</eventSubURL>
            <SCPDURL>/xml/AVTransport1.xml</SCPDURL>
          </service>
        </serviceList>
      </device>
    </deviceList>
  </device>
</root>
```

- [ ] **Step 3: Write the failing parser tests**

`src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpMessageTest.java` — test cases:

- `parsesASearchResponseWithCaseInsensitiveHeaders`: load `lg-search-response.txt` with `{host}`=`192.168.1.60`, `{port}`=`1780`, CRLF endings; `parse` → kind `SEARCH_RESPONSE`; `header("location")` and `header("LOCATION")` both `http://192.168.1.60:1780/lg/description.xml`; `type()` = `urn:lge-com:service:webos-second-screen:1`; `maxAge()` = 1800 s; `header("EXT")` is empty (blank values count as absent).
- `parsesNotifyAliveAndByeBye`: `"NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\nUSN: uuid:a::urn:x:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: http://10.0.0.2/d.xml\r\n\r\n"` → kind `NOTIFY`, `type()` = `urn:x:1`, `isByeBye()` false, `maxAge()` 120 s; the same with `NTS: ssdp:byebye` → `isByeBye()` true.
- `acceptsBareLineFeeds`: the alive message with `\n` instead of `\r\n` parses identically.
- `aMissingMaxAgeDefaultsToThirtyMinutes`.
- `rejectsDatagramsThatAreNotSsdp`: `"GET / HTTP/1.1\r\n\r\n"`, `""`, and random bytes → `Optional.empty()`.
- `buildsAnMSearchRequest`: `new String(SsdpMessage.search("urn:x:1", "239.255.255.250:1900", 2, "Linux/1 UPnP/1.1 HomeControl/1"), US_ASCII)` equals exactly `"M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:x:1\r\nUSER-AGENT: Linux/1 UPnP/1.1 HomeControl/1\r\n\r\n"`, and parsing it back yields kind `SEARCH_REQUEST` with `header("ST")` = `urn:x:1`.

`src/test/java/dev/andre/homecontrol/discovery/ssdp/DeviceDescriptionsTest.java` — test cases:

- `readsTheRootDeviceOfAnLgTv`: parse `lg-description.xml` with location `http://192.168.1.60:1780/lg/description.xml` → friendlyName `[LG] webOS TV OLED55C9PLA`, manufacturer `LG Electronics`, udn `uuid:e8d7f6a5-1234-4bcd-9ef0-a8b7c6d5e4f3`, one service whose controlUrl is `http://192.168.1.60:1780/WebOS_SecondScreen/control`.
- `resolvesRelativeServiceUrlsAgainstTheLocation`: `samsung-description.xml` at `http://192.168.1.61:7676/smp_15_` → service `urn:samsung.com:service:MultiScreenService:1` has scpdUrl `http://192.168.1.61:7676/MultiScreenService.xml`; the `sec:deviceID` element does not break parsing.
- `findsServicesOfEmbeddedDevices`: `sonos-description.xml` at `http://192.168.1.70:1400/xml/device_description.xml` → friendlyName `192.168.1.70 - Sonos One` (root device), `service("urn:schemas-upnp-org:service:AVTransport")` present with controlUrl `http://192.168.1.70:1400/MediaRenderer/AVTransport/Control`.
- `refusesADocumentTypeDeclaration`: `<?xml version="1.0"?><!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/passwd">]><root><device><friendlyName>&x;</friendlyName></device></root>` → `IllegalArgumentException`.
- `rejectsADocumentWithoutADevice`: `<root/>` → `IllegalArgumentException` whose message contains the location.

- [ ] **Step 4: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.discovery.ssdp.*'`
Expected: compilation failure — the `discovery.ssdp` types do not exist.

- [ ] **Step 5: Write the value types and parsers**

`discovery/ssdp/SsdpMessage.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One SSDP datagram: HTTP-style start line plus headers, no body (UPnP Device Architecture 2.0 §1). */
public record SsdpMessage(Kind kind, Map<String, String> headers) {

    public enum Kind { SEARCH_RESPONSE, NOTIFY, SEARCH_REQUEST }

    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(30);

    public static Optional<SsdpMessage> parse(byte[] data, int length) {
        if (data == null || length <= 0) {
            return Optional.empty();
        }
        String[] lines = new String(data, 0, length, StandardCharsets.UTF_8).split("\r?\n");
        String start = lines[0].trim().toUpperCase(Locale.ROOT);
        Kind kind;
        if (start.startsWith("HTTP/1.1 200")) {
            kind = Kind.SEARCH_RESPONSE;
        } else if (start.startsWith("NOTIFY * HTTP/1.1")) {
            kind = Kind.NOTIFY;
        } else if (start.startsWith("M-SEARCH * HTTP/1.1")) {
            kind = Kind.SEARCH_REQUEST;
        } else {
            return Optional.empty();
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return Optional.of(new SsdpMessage(kind, Collections.unmodifiableMap(headers)));
    }

    /** A header value; blank values (such as {@code EXT:}) count as absent. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name)).filter(value -> !value.isBlank());
    }

    /** {@code NT} for announcements, {@code ST} for search requests and responses. */
    public Optional<String> type() {
        return kind == Kind.NOTIFY ? header("NT") : header("ST");
    }

    public boolean isByeBye() {
        return kind == Kind.NOTIFY && header("NTS").map("ssdp:byebye"::equalsIgnoreCase).orElse(false);
    }

    public Duration maxAge() {
        Matcher matcher = MAX_AGE.matcher(header("CACHE-CONTROL").orElse(""));
        return matcher.find() ? Duration.ofSeconds(Long.parseLong(matcher.group(1))) : DEFAULT_MAX_AGE;
    }

    public static byte[] search(String searchTarget, String hostHeader, int mx, String userAgent) {
        return ("M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + hostHeader + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: " + mx + "\r\n"
                + "ST: " + searchTarget + "\r\n"
                + "USER-AGENT: " + userAgent + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
```

`discovery/ssdp/DeviceDescription.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** The parts of a UPnP device description adapters need. Services include those of embedded devices. */
public record DeviceDescription(String friendlyName, String manufacturer, String modelName, String udn,
                                List<Service> services) {

    public DeviceDescription {
        services = services == null ? List.of() : List.copyOf(services);
    }

    public record Service(String serviceType, String serviceId, URI controlUrl, URI eventSubUrl, URI scpdUrl) {
    }

    /** The first service whose type starts with {@code serviceTypePrefix} (ignoring the version suffix is the usual use). */
    public Optional<Service> service(String serviceTypePrefix) {
        return services.stream()
                .filter(service -> service.serviceType() != null && service.serviceType().startsWith(serviceTypePrefix))
                .findFirst();
    }
}
```

`discovery/ssdp/DeviceDescriptions.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Parses UPnP device description XML fetched from an SSDP {@code LOCATION}. Device-supplied, so no DTDs. */
public final class DeviceDescriptions {

    private DeviceDescriptions() {
    }

    public static DeviceDescription parse(byte[] xml, URI location) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            Element root = document.getDocumentElement();
            String urlBase = childText(root, "URLBase");
            URI base = urlBase != null ? URI.create(urlBase) : location;
            Element device = firstChild(root, "device");
            if (device == null) {
                throw new IllegalArgumentException("No <device> element in the description at " + location);
            }
            List<DeviceDescription.Service> services = new ArrayList<>();
            NodeList serviceNodes = document.getElementsByTagName("service");
            for (int i = 0; i < serviceNodes.getLength(); i++) {
                Element service = (Element) serviceNodes.item(i);
                services.add(new DeviceDescription.Service(
                        childText(service, "serviceType"),
                        childText(service, "serviceId"),
                        resolve(base, childText(service, "controlURL")),
                        resolve(base, childText(service, "eventSubURL")),
                        resolve(base, childText(service, "SCPDURL"))));
            }
            return new DeviceDescription(childText(device, "friendlyName"), childText(device, "manufacturer"),
                    childText(device, "modelName"), childText(device, "UDN"), services);
        } catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Unreadable device description at " + location + ": " + e.getMessage(), e);
        }
    }

    private static Element firstChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static String localName(Element element) {
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    private static String childText(Element parent, String name) {
        Element child = firstChild(parent, name);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    private static URI resolve(URI base, String value) {
        if (value == null) {
            return null;
        }
        try {
            return base == null ? URI.create(value) : base.resolve(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
```

(The `IllegalArgumentException` for a missing `<device>` is re-wrapped by the catch; its message still contains the location, which is what the test asserts.)

`discovery/ssdp/SsdpService.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * One service a device announced. {@code address} is the host of {@code location} when there is
 * one (that is the address the device wants to be reached on), otherwise the datagram's sender.
 * {@code description} is null until it has been fetched.
 */
public record SsdpService(String usn, String type, String address, URI location, Map<String, String> headers,
                          Instant expiresAt, DeviceDescription description) {

    public SsdpService withDescription(DeviceDescription fetched) {
        return new SsdpService(usn, type, address, location, headers, expiresAt, fetched);
    }

    public Optional<String> friendlyName() {
        return Optional.ofNullable(description).map(DeviceDescription::friendlyName);
    }
}
```

`discovery/ssdp/SsdpListener.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

/**
 * Hears services of one watched search target. Called on the receive thread, possibly many times
 * for the same service (every announcement and search response): return quickly, never block.
 */
public interface SsdpListener {

    void alive(SsdpService service);

    default void byebye(SsdpService service) {
    }
}
```

- [ ] **Step 6: Run the parser tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.discovery.ssdp.SsdpMessageTest' --tests 'dev.andre.homecontrol.discovery.ssdp.DeviceDescriptionsTest'`
Expected: PASS.

- [ ] **Step 7: Write the fake responder and the failing discovery tests**

`src/test/java/dev/andre/homecontrol/discovery/ssdp/FakeSsdpResponder.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A loopback stand-in for "every UPnP device on the LAN": answers M-SEARCH unicast for configured targets. */
public class FakeSsdpResponder implements AutoCloseable {

    private final DatagramSocket socket;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final AtomicInteger searches = new AtomicInteger();

    public FakeSsdpResponder() throws IOException {
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread.ofVirtual().name("fake-ssdp-responder").start(this::serve);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public int searches() {
        return searches.get();
    }

    public void answer(String searchTarget, String response) {
        responses.put(searchTarget, response);
    }

    public void stopAnswering(String searchTarget) {
        responses.remove(searchTarget);
    }

    /** Loads a fixture, fills in {@code {host}} / {@code {port}}, and normalises to CRLF. */
    public static String fixture(String name, String host, int port) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/ssdp/" + name))
                .replace("{host}", host).replace("{port}", String.valueOf(port))
                .replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private void serve() {
        byte[] buffer = new byte[4096];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                return;
            }
            SsdpMessage.parse(packet.getData(), packet.getLength())
                    .filter(message -> message.kind() == SsdpMessage.Kind.SEARCH_REQUEST)
                    .ifPresent(message -> {
                        searches.incrementAndGet();
                        String response = responses.get(message.header("ST").orElse(""));
                        if (response != null) {
                            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                            try {
                                socket.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                            } catch (IOException ignored) {
                                // The discovery side closed; the test is over.
                            }
                        }
                    });
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
```

`src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscoveryTest.java` — setup: a `FakeSsdpResponder`; a JDK `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0` serving `/lg/description.xml` and `/samsung/description.xml` from the fixtures with `Content-Type: text/xml`; a mutable test clock (`class MutableClock extends Clock` with `advance(Duration)`); discovery built as `new SsdpDiscovery(new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1), clock, HttpClient.newHttpClient())` and `start()`ed; everything closed in `@AfterEach`. Test cases (all awaiting with Awaitility, at most 5 s):

- `findsAWatchedServiceAndFetchesItsDescription`: responder answers `urn:lge-com:service:webos-second-screen:1` with `fixture("lg-search-response.txt", "127.0.0.1", http.port)`; `watch` that target → `services(target)` has one service with usn `uuid:e8d7f6a5-1234-4bcd-9ef0-a8b7c6d5e4f3::urn:lge-com:service:webos-second-screen:1`, address `127.0.0.1`, and eventually `friendlyName()` `[LG] webOS TV OLED55C9PLA`.
- `ignoresServicesOfTypesNobodyWatches`: responder answers the Samsung target; only the LG target is watched → after the responder counted at least two searches, `services("urn:samsung.com:device:RemoteControlReceiver:1")` is empty.
- `learnsFromNotifyAndForgetsOnByeBye`: watch `urn:x:1`; send an alive NOTIFY (from `SsdpMessageTest`, location `http://127.0.0.1:<http port>/lg/description.xml`) with a plain `DatagramSocket` to `127.0.0.1:discovery.listenPort()` → service appears; send the byebye for the same USN → `services("urn:x:1")` empty and a registered listener's `byebye` was called once.
- `expiresAServiceAfterItsMaxAge`: find the LG service, `responder.stopAnswering(target)`, wait until `responder.searches()` has grown by 2 more (so no answer is still in flight), `clock.advance(Duration.ofSeconds(1801))` → `services(target)` is empty.
- `listenersHearAliveServicesOfTheirTargetOnly`: two listeners on two targets; only the LG one fires, with the LG service.
- `disabledDiscoveryOpensNoSockets`: `new SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", responder.port(), 0, 1, 1))`, `start()`, `watch(target)` → `listenPort()` is `-1`, and after 1.5 s `responder.searches()` is 0.

- [ ] **Step 8: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.discovery.ssdp.SsdpDiscoveryTest'`
Expected: compilation failure — `SsdpDiscovery` and `SsdpProperties` do not exist.

- [ ] **Step 9: Write the discovery service**

`discovery/ssdp/SsdpProperties.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code port} is where searches are sent; {@code listenPort} is where NOTIFY announcements are
 * received (0 = any free port, for tests). Both are 1900 on a real network.
 */
@ConfigurationProperties("home-control.ssdp")
public record SsdpProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("239.255.255.250") String multicastAddress,
                             @DefaultValue("1900") int port,
                             @DefaultValue("1900") int listenPort,
                             @DefaultValue("60") int searchIntervalSeconds,
                             @DefaultValue("2") int mx) {
}
```

`discovery/ssdp/SsdpDiscovery.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one SSDP listener every UPnP-style adapter shares (spec §7): webOS and Tizen TVs now,
 * DLNA renderers and Sonos in sub-project I. Adapters {@link #watch} their search targets;
 * this service searches for them periodically, listens for NOTIFY announcements, keeps each
 * service until its max-age runs out or it says byebye, and fetches its description once.
 *
 * <p>Multicast does not cross a Docker bridge network, so every adapter also accepts an
 * address typed in by hand.
 */
public class SsdpDiscovery implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SsdpDiscovery.class);
    private static final String USER_AGENT = "Linux/1 UPnP/1.1 HomeControl/1";

    private final SsdpProperties properties;
    private final Clock clock;
    private final HttpClient http;
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    private final Map<String, SsdpService> services = new ConcurrentHashMap<>();
    private final Map<String, List<SsdpListener>> listeners = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile DatagramSocket searchSocket;
    private volatile MulticastSocket notifySocket;
    private volatile ScheduledExecutorService scheduler;

    public SsdpDiscovery(SsdpProperties properties) {
        this(properties, Clock.systemUTC(), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    SsdpDiscovery(SsdpProperties properties, Clock clock, HttpClient http) {
        this.properties = properties;
        this.clock = clock;
        this.http = http;
    }

    public void start() {
        if (!properties.enabled()) {
            log.info("SSDP discovery is disabled; add smart TVs by address");
            return;
        }
        running = true;
        try {
            searchSocket = new DatagramSocket(0);
            Thread.ofVirtual().name("ssdp-responses").start(() -> receive(searchSocket));
        } catch (IOException e) {
            log.warn("SSDP search is unavailable ({}); add smart TVs by address", e.getMessage());
        }
        try {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(properties.listenPort()));
            InetAddress group = InetAddress.getByName(properties.multicastAddress());
            if (group.isMulticastAddress()) {
                joinEverywhere(socket, group);
            }
            notifySocket = socket;
            Thread.ofVirtual().name("ssdp-notify").start(() -> receive(socket));
        } catch (IOException e) {
            log.warn("Not listening for SSDP announcements ({}); periodic searches still run", e.getMessage());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ssdp-search").factory());
        scheduler.scheduleWithFixedDelay(this::searchAll, 0, properties.searchIntervalSeconds(), TimeUnit.SECONDS);
    }

    /** Start looking for {@code searchTarget}; searches for it at once if discovery is running. */
    public void watch(String searchTarget) {
        if (watched.add(searchTarget) && running && scheduler != null) {
            scheduler.execute(() -> search(searchTarget));
        }
    }

    public void addListener(String searchTarget, SsdpListener listener) {
        watch(searchTarget);
        listeners.computeIfAbsent(searchTarget, key -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Live services of one target, ordered by address. Expired entries are dropped on read. */
    public List<SsdpService> services(String searchTarget) {
        Instant now = clock.instant();
        services.values().removeIf(service -> !service.expiresAt().isAfter(now));
        return services.values().stream()
                .filter(service -> service.type().equals(searchTarget))
                .sorted(Comparator.comparing(SsdpService::address))
                .toList();
    }

    /** The port NOTIFY datagrams are received on, or -1 when not listening. */
    public int listenPort() {
        MulticastSocket socket = notifySocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    private void searchAll() {
        watched.forEach(this::search);
    }

    private void search(String searchTarget) {
        DatagramSocket socket = searchSocket;
        if (socket == null) {
            return;
        }
        byte[] request = SsdpMessage.search(searchTarget,
                properties.multicastAddress() + ":" + properties.port(), properties.mx(), USER_AGENT);
        DatagramPacket packet = new DatagramPacket(request, request.length,
                new InetSocketAddress(properties.multicastAddress(), properties.port()));
        try {
            // UDP is lossy; UPnP recommends sending each search more than once.
            socket.send(packet);
            socket.send(packet);
        } catch (IOException e) {
            log.debug("SSDP search for {} failed: {}", searchTarget, e.getMessage());
        }
    }

    private void joinEverywhere(MulticastSocket socket, InetAddress group) throws IOException {
        int joined = 0;
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                if (nic.isUp() && nic.supportsMulticast() && !nic.isLoopback()) {
                    socket.joinGroup(new InetSocketAddress(group, 0), nic);
                    joined++;
                }
            } catch (IOException e) {
                log.debug("Cannot join {} on {}: {}", group, nic.getName(), e.getMessage());
            }
        }
        if (joined == 0) {
            socket.joinGroup(new InetSocketAddress(group, 0), null);
        }
    }

    private void receive(DatagramSocket socket) {
        byte[] buffer = new byte[8192];
        while (running && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running && !socket.isClosed()) {
                    log.debug("SSDP receive failed: {}", e.getMessage());
                    continue;
                }
                return;
            }
            InetAddress sender = packet.getAddress();
            SsdpMessage.parse(packet.getData(), packet.getLength()).ifPresent(message -> handle(message, sender));
        }
    }

    void handle(SsdpMessage message, InetAddress sender) {
        if (message.kind() == SsdpMessage.Kind.SEARCH_REQUEST) {
            return;
        }
        Optional<String> type = message.type();
        Optional<String> usn = message.header("USN");
        if (type.isEmpty() || usn.isEmpty() || !watched.contains(type.get())) {
            return;
        }
        if (message.isByeBye()) {
            SsdpService gone = services.remove(usn.get());
            if (gone != null) {
                listenersOf(gone.type()).forEach(listener -> listener.byebye(gone));
            }
            return;
        }
        URI location = message.header("LOCATION").flatMap(SsdpDiscovery::toUri).orElse(null);
        String address = location != null && location.getHost() != null ? location.getHost() : sender.getHostAddress();
        SsdpService previous = services.get(usn.get());
        DeviceDescription known = previous != null && Objects.equals(previous.location(), location)
                ? previous.description() : null;
        SsdpService seen = new SsdpService(usn.get(), type.get(), address, location, message.headers(),
                clock.instant().plus(message.maxAge()), known);
        services.put(usn.get(), seen);
        if (known == null && location != null) {
            Thread.ofVirtual().name("ssdp-describe").start(() -> describe(seen));
        }
        listenersOf(seen.type()).forEach(listener -> listener.alive(seen));
    }

    private List<SsdpListener> listenersOf(String type) {
        return listeners.getOrDefault(type, List.of());
    }

    private void describe(SsdpService service) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(service.location()).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return;
            }
            DeviceDescription description = DeviceDescriptions.parse(response.body(), service.location());
            services.computeIfPresent(service.usn(), (usn, current) -> current.withDescription(description));
        } catch (IOException | IllegalArgumentException e) {
            log.debug("No description for {}: {}", service.usn(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Optional<URI> toUri(String value) {
        try {
            return Optional.of(URI.create(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (searchSocket != null) {
            searchSocket.close();
        }
        if (notifySocket != null) {
            notifySocket.close();
        }
    }
}
```

`discovery/ssdp/SsdpConfiguration.java`:

```java
package dev.andre.homecontrol.discovery.ssdp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Always present so adapters can depend on it; {@code home-control.ssdp.enabled=false} keeps it off the network. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SsdpProperties.class)
public class SsdpConfiguration {

    @Bean(initMethod = "start", destroyMethod = "close")
    public SsdpDiscovery ssdpDiscovery(SsdpProperties properties) {
        return new SsdpDiscovery(properties);
    }
}
```

`src/main/resources/application.yaml` — under the top-level `home-control:` key (sub-project B created it for `cast`; do not add a second `home-control:` key) add:

```yaml
home-control:
  ssdp:
    # One shared listener for smart TVs (and later DLNA/Sonos). Needs host networking.
    enabled: true
    multicast-address: 239.255.255.250
    port: 1900
    listen-port: 1900
    search-interval-seconds: 60
    mx: 2
```

`src/test/resources/application.yaml` — under its `home-control:` key add:

```yaml
home-control:
  ssdp:
    enabled: false
```

- [ ] **Step 10: Run the SSDP tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.discovery.ssdp.*'`
Expected: PASS. If `learnsFromNotifyAndForgetsOnByeBye` fails only because the container forbids binding a `MulticastSocket`, check the log for "Not listening for SSDP announcements" — the test must then be fixed, not skipped: bind with `listenPort` 0 is always permitted on loopback.

- [ ] **Step 11: Write the failing merge tests**

These tests use sub-project B's scriptable `device/StubAdapter` (constructor `StubAdapter(String id, DeviceKind kind, boolean pairingFree, boolean boundCredentials, Capability... capabilities)`, field `handles` = latest `StubHandle` per device id, `StubHandle.closed`, `StubHandle.executed`). If B's test helper has a different shape, adapt the assertions, not the behaviour.

`src/test/java/dev/andre/homecontrol/device/DeviceMergeTest.java` — pure, `sameHost` = `String::equalsIgnoreCase`, `now` = `Instant.parse("2026-09-16T12:00:00Z")`. Test cases:

- `addsTheAdapterToTheDeviceAtTheSameHostAndKeepsItsIdentity`: registered `Device("192-168-1-60", "Living Room", ANDROID_TV, "192.168.1.60", {androidtv: {port: 6466}}, EPOCH)`; attach host `192.168.1.60`, name `[LG] webOS TV`, kind `WEBOS`, adapter `webos`, settings `{clientKey: k}` → id `192-168-1-60`, name `Living Room`, kind `ANDROID_TV`, adapter keys in order `[androidtv, webos]`, `adapterSettings("webos")` = `{clientKey: k}`, `lastSeen` = now.
- `aDeviceThatAlreadyHasTheAdapterIsStillTheMatch`: registered `Device("webos-192-168-1-60", "LG", WEBOS, "192.168.1.60", {webos: {clientKey: old, macAddress: A8:23:FE:01:02:03, macAddressManual: true}}, EPOCH)`; attach `webos` at the same host with `{clientKey: new}` → same id, settings `{clientKey: new, macAddress: A8:23:FE:01:02:03, macAddressManual: true}` (re-pairing overlays, it never duplicates — unlike B's `bestMatch`, which skips devices that already carry the adapter).
- `registersANewDeviceWithTheAdapterPrefixedIdWhenNoHostMatches`: registered device at `192.168.1.60`; attach at `192.168.1.61`, kind `TIZEN`, adapter `tizen` → id `tizen-192-168-1-61` (B's `DeviceManager.uniqueId` convention), name as given, kind `TIZEN`, host `192.168.1.61`, single adapter `tizen`.
- `aNameMatchIsNotAHostMatch`: registered `Device("x", "[LG] webOS TV", WEBOS, "192.168.1.60", {cast: {}}, EPOCH)`; attach `webos` at `192.168.1.99` with name `[LG] webOS TV` → a new device (TVs merge by host only; see Decisions).

Add to `device/DeviceManagerTest.java` (or B's `DeviceManagerMergeTest.java` if that is where manager-merge tests live now):

- `attachAddsTheAdapterToTheRegisteredDeviceAtTheSameHostAndReconnectsIt`: registry holds `Device("tv", "Living Room TV", DeviceKind.CAST, "10.0.0.60", {alpha: {}}, EPOCH)`; manager over `List.of(alpha, beta)` with `alpha = new StubAdapter("alpha", DeviceKind.CAST, false, false, VOLUME)` and `beta = new StubAdapter("beta", DeviceKind.WEBOS, false, false, REMOTE_KEYS, APP_LINK)`; `start()`; remember `firstAlpha = alpha.handles.get("tv")`; `attach("10.0.0.60", "[LG] webOS TV", DeviceKind.WEBOS, "beta", Map.of("clientKey", "k"))` → returned id `tv`; `registry.findAll()` has one device with adapter keys `[alpha, beta]` and `adapterSettings("beta")` `{clientKey: k}`; `firstAlpha.closed` true and `alpha.handles.get("tv")` is a new handle; `beta.handles` contains `tv`; `capabilities("tv")` = `{VOLUME, REMOTE_KEYS, APP_LINK}`.
- `attachRegistersANewDeviceWhenNoHostMatches`: empty registry → `attach("10.0.0.61", "Samsung", DeviceKind.TIZEN, "beta", Map.of())` returns id `beta-10-0-0-61`, kind `TIZEN`; `states()` contains that id.

- [ ] **Step 12: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.device.*'`
Expected: compilation failure — `DeviceMerge` and `DeviceManager.attach` do not exist.

- [ ] **Step 13: Implement the merge**

`core/Hosts.java`:

```java
package dev.andre.homecontrol.core;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Host strings as the registry and discovery see them. */
public final class Hosts {

    private Hosts() {
    }

    /**
     * Equal ignoring case, or resolving to the same address. Literal IPs never touch DNS; a host
     * name is resolved, which only happens while pairing, never per command or in a listener.
     */
    public static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        try {
            return InetAddress.getByName(a).equals(InetAddress.getByName(b));
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
```

`device/DeviceMerge.java`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * "A device that must be paired was paired, and we may already know it under another adapter"
 * (spec §5.1): the same host is the same device. The existing device keeps its id, name, kind and
 * adapter order (the first adapter stays primary); the adapter's settings are overlaid so values
 * the pairing does not send (a hand-entered MAC) survive a re-pair.
 */
final class DeviceMerge {

    private DeviceMerge() {
    }

    static Device attach(List<Device> registered, String host, String name, DeviceKind kind, String adapterId,
                         Map<String, String> settings, Instant now, BiPredicate<String, String> sameHost) {
        for (Device existing : registered) {
            if (sameHost.test(existing.host(), host)) {
                Map<String, String> merged = new LinkedHashMap<>(existing.adapterSettings(adapterId));
                merged.putAll(settings);
                Device extended = existing.withAdapter(adapterId, merged);
                return new Device(extended.id(), extended.name(), extended.kind(), extended.host(),
                        extended.adapters(), now);
            }
        }
        return new Device(DeviceManager.uniqueId(registered, adapterId, host), name, kind, host,
                Map.of(adapterId, Map.copyOf(settings)), now);
    }
}
```

`Device.withAdapter` puts into a `LinkedHashMap` copy, so replacing an existing key keeps its position — that is what keeps the adapter order.

In `device/DeviceManager.java` add:

```java
    /**
     * For prompt-paired adapters (webOS, Tizen): adds {@code adapterId} with {@code settings} to the
     * registered device at {@code host}, or registers a new device there, and (re)connects it.
     * Goes through {@link #adopt}, so pairing-free receivers at that address are absorbed as well.
     */
    public synchronized Device attach(String host, String name, DeviceKind kind, String adapterId,
                                      Map<String, String> settings) {
        Device merged = DeviceMerge.attach(registry.findAll(), host, name, kind, adapterId, settings,
                Instant.now(), Hosts::same);
        adopt(merged);
        return registry.findById(merged.id()).orElse(merged);
    }
```

(`adopt` is `synchronized` on the same monitor, so the nested call is fine. `uniqueId` is B's package-private static helper; if it is private, make it package-private.)

- [ ] **Step 14: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.device.*'` then `.superpowers/gradle.sh build`
Expected: PASS (including B's manager tests); BUILD SUCCESSFUL.

- [ ] **Step 15: Commit**

```bash
git add -A
git commit -m "feat: shared SSDP discovery and merging a second adapter into the device at the same host"
```

---
### Task 2: F2 · LG webOS adapter

**Files:**
- Create: `core/MacAddress.java`, `core/WakeOnLanAdapter.java`, `core/PromptPairing.java`, `core/PromptPairingResult.java`, `core/TvInput.java`, `core/InputListing.java`; `adapters/net/TextWebSocket.java`, `InsecureTls.java`, `WakeOnLan.java`, `WakeOnLanProperties.java`, `NetConfiguration.java`; `adapters/links/ContentLinks.java`; `adapters/webos/WebOsProperties.java`, `WebOsSettings.java`, `SsapUris.java`, `SsapMessages.java`, `SsapException.java`, `SsapPairingException.java`, `SsapConnection.java`, `WebOsKeys.java`, `WebOsLaunch.java`, `WebOsLaunches.java`, `WebOsPayloads.java`, `WebOsSession.java`, `WebOsPairing.java`, `WebOsAdapter.java`, `WebOsConfiguration.java`
- Modify: `core/Action.java`, `core/DeviceState.java`, `core/playback/AppLinks.java`, `adapters/androidtv/AndroidTvSession.java` (and any other exhaustive `switch (action)`), `device/DeviceManager.java`, `web/SetupController.java`, `web/DeviceController.java`, `web/DashboardController.java`, `src/main/resources/templates/setup.html`, `src/main/resources/templates/dashboard.html`, `src/main/resources/application.yaml`
- Test: `core/MacAddressTest.java`, `core/DeviceStateTest.java`, `core/ActionTest.java`; `adapters/net/FakeWebSocketServer.java`, `FakeWakeOnLanReceiver.java`, `TextWebSocketTest.java`, `InsecureTlsTest.java`, `WakeOnLanTest.java`; `adapters/links/ContentLinksTest.java`; `adapters/webos/FakeSsapServer.java`, `SsapConnectionTest.java`, `WebOsKeysTest.java`, `WebOsLaunchesTest.java`, `WebOsPayloadsTest.java`, `WebOsSessionTest.java`, `WebOsPairingTest.java`, `WebOsAdapterTest.java`; `device/DeviceManagerTest.java`; `web/PromptPairingSetupTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`; fixtures `src/test/resources/fixtures/webos/external-inputs.json`, `connection-info.json`, `volume-webos6.json`, `volume-webos4.json`

**Interfaces:**
- Consumes: Task 1 (`SsdpDiscovery.addListener/services/listenPort`, `SsdpService`, `SsdpProperties`, `FakeSsdpResponder`, `DeviceManager.attach`); sub-project A (`DeviceAdapter`, `DeviceHandle`, `DeviceRegistry`, `JsonFileDeviceRegistry`, `DeviceState`, `DeviceStatus`, `DeviceOfflineException`, `UnsupportedActionException`, `RemoteKey`, `AppLinks`, `ClientCertificate` for test TLS, `SetupController`, `DeviceController`, `DashboardController`); sub-project B (`Action.SetVolume/Mute/Stop/CastLoad`, `ActionFailedException`, `DeviceAdapter.kind()`, `DeviceState.nowPlaying`, `DeviceManager.pairable()/addable()`, test helper `device/StubAdapter`).
- Produces:
  - `core.MacAddress.normalize(String) → String` (`AA:BB:CC:DD:EE:FF`, throws `IllegalArgumentException`), `core.MacAddress.bytes(String) → byte[]`.
  - `interface core.WakeOnLanAdapter extends DeviceAdapter` with constants `MAC_ADDRESS = "macAddress"`, `MAC_ADDRESS_MANUAL = "macAddressManual"`.
  - `interface core.PromptPairing { String adapterId(); String displayName(); String instructions(); PromptPairingResult pair(String host, String name); }`; `sealed interface core.PromptPairingResult` with `Paired(Device device)`, `Declined(String reason)`, `Failed(String reason)`.
  - `record core.TvInput(String id, String label)`; `interface core.InputListing { List<TvInput> inputs(); }`.
  - `record Action.SelectInput(String inputId)` requiring `REMOTE_KEYS`.
  - `DeviceState.sameIgnoringTime(DeviceState other) → boolean`.
  - `public static String AppLinks.serviceOf(String host, String path)`.
  - `TextWebSocket.connect(HttpClient, URI, Duration, TextWebSocket.Listener) → TextWebSocket` with `send(String)`, `isOpen()`, `close()`, `static String withoutQuery(URI)`; `interface Listener { void onText(String text); void onClosed(String reason); }`.
  - `InsecureTls.httpClient(Duration connectTimeout) → HttpClient`.
  - `WakeOnLan(InetSocketAddress target)` with `static byte[] magicPacket(String mac)`, `void wake(String mac) throws IOException`; `record WakeOnLanProperties(String broadcastAddress, int port)` bound to `home-control.wake-on-lan`.
  - `ContentLinks.youtubeVideoId(URI) → Optional<String>`, `ContentLinks.netflixTitleId(URI) → Optional<String>`.
  - `record WebOsProperties(boolean enabled, int port, int securePort, int connectTimeoutSeconds, int requestTimeoutSeconds, int pairingTimeoutSeconds, int reconnectInitialDelaySeconds, int reconnectMaxDelaySeconds, int wakeGraceSeconds)` bound to `home-control.webos`.
  - `WebOsAdapter implements WakeOnLanAdapter` (id `webos`, capabilities `REMOTE_KEYS, POWER, VOLUME, APP_LINK`, SSDP target `urn:lge-com:service:webos-second-screen:1`); `WebOsSession implements DeviceHandle, InputListing`; `WebOsPairing implements PromptPairing`.
  - `DeviceManager.wakesOnLan(String id) → boolean`, `wakeOnLanMac(String id) → Optional<String>`, `setWakeOnLanMac(String id, String mac)`, `inputs(String id) → List<TvInput>`.
  - Web: `POST /setup/prompt-pair` (form `adapter`, `host`, `name`) → redirect `/?device={id}` or the setup page with the reason; `POST /setup/devices/{id}/mac` (form `mac`, blank clears) → redirect `/setup`, 404 unknown device, setup page with the reason when invalid; `POST /devices/{id}/input/{inputId}` → 204 / 404 / 409 / 422.

- [ ] **Step 1: List the exhaustive action switches**

```bash
grep -rn "switch (action)" src/main/java
grep -n "record " src/main/java/dev/andre/homecontrol/core/Action.java
```

Expected: `AndroidTvSession` and B's `CastSession`, and B's actions `PressKey`, `OpenAppLink`, `SetVolume(int level)`, `Mute(boolean muted)`, `Stop()`, `CastLoad(String receiverAppId, Map<String, Object> load)`. Every switch listed needs a `SelectInput` branch in Step 4. If B's action names differ from these, use B's names in the TV sessions below.

- [ ] **Step 2: Write the failing core tests**

`src/test/java/dev/andre/homecontrol/core/MacAddressTest.java` — test cases:

- `normalisesTheUsualSpellings` (parameterized): `a8:23:fe:01:02:03`, `A8-23-FE-01-02-03`, `a823.fe01.0203`, `a823fe010203`, ` A8:23:FE:01:02:03 ` → `A8:23:FE:01:02:03`.
- `rejectsWhatIsNotAMac` (parameterized): `""`, `"zz:23:fe:01:02:03"`, `"a8:23:fe:01:02"`, `"a8:23:fe:01:02:03:04"` → `IllegalArgumentException`; `null` → `IllegalArgumentException` whose message contains `A8:23:FE:01:02:03` (the example).
- `bytesAreTheSixOctets`: `bytes("A8:23:FE:01:02:03")` = `{(byte) 0xA8, 0x23, (byte) 0xFE, 0x01, 0x02, 0x03}`.

Add to `src/test/java/dev/andre/homecontrol/core/DeviceStateTest.java` (B created it):

- `sameIgnoringTimeComparesEverythingButTheTimestamp`: `DeviceState.initial()` and `initial().withStatus(DISCONNECTED)` (a later timestamp) → true; for each of differing status, power, current app, volume level, volume max, muted and now-playing → false.

Add to `core/ActionTest.java`:

- `selectingAnInputRequiresRemoteKeys`: `new Action.SelectInput("HDMI_1").requires()` is `REMOTE_KEYS`.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*'`
Expected: compilation failure — `MacAddress`, `SelectInput`, `sameIgnoringTime` do not exist.

- [ ] **Step 4: Write the core additions**

`core/MacAddress.java`:

```java
package dev.andre.homecontrol.core;

import java.util.HexFormat;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/** A MAC address as the setup page and Wake-on-LAN use it. */
public final class MacAddress {

    private static final Pattern TWELVE_HEX_DIGITS = Pattern.compile("[0-9A-Fa-f]{12}");

    private MacAddress() {
    }

    /** Accepts colons, dashes, dots or nothing between digits; returns {@code AA:BB:CC:DD:EE:FF}. */
    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Enter a MAC address such as A8:23:FE:01:02:03");
        }
        String hex = input.trim().replaceAll("[:\\-.\\s]", "");
        if (!TWELVE_HEX_DIGITS.matcher(hex).matches()) {
            throw new IllegalArgumentException("Not a MAC address: " + input.trim()
                    + " (expected six pairs of hex digits such as A8:23:FE:01:02:03)");
        }
        StringJoiner joined = new StringJoiner(":");
        for (int i = 0; i < 12; i += 2) {
            joined.add(hex.substring(i, i + 2).toUpperCase(Locale.ROOT));
        }
        return joined.toString();
    }

    public static byte[] bytes(String mac) {
        return HexFormat.ofDelimiter(":").parseHex(normalize(mac));
    }
}
```

`core/WakeOnLanAdapter.java`:

```java
package dev.andre.homecontrol.core;

/** An adapter that switches its devices on with a Wake-on-LAN magic packet. The MAC lives in its settings. */
public interface WakeOnLanAdapter extends DeviceAdapter {

    String MAC_ADDRESS = "macAddress";

    /** {@code "true"} once the user typed the MAC; adapters then stop replacing it with what the device reports. */
    String MAC_ADDRESS_MANUAL = "macAddressManual";
}
```

`core/PromptPairing.java`:

```java
package dev.andre.homecontrol.core;

/**
 * Pairing by accepting a prompt on the device itself (webOS, Tizen). The web layer lists every
 * bean of this type without knowing any protocol. {@link #pair} blocks until the device answers
 * or the adapter's pairing timeout elapses, and registers the device through the device manager.
 */
public interface PromptPairing {

    /** The adapter id discovered devices carry, e.g. {@code webos}. */
    String adapterId();

    /** For the setup page, e.g. "LG webOS TV". */
    String displayName();

    /** What the user must do, e.g. "Accept the request on the TV within 60 seconds." */
    String instructions();

    PromptPairingResult pair(String host, String name);
}
```

`core/PromptPairingResult.java`:

```java
package dev.andre.homecontrol.core;

public sealed interface PromptPairingResult {

    record Paired(Device device) implements PromptPairingResult {
    }

    /** The user said no on the device. */
    record Declined(String reason) implements PromptPairingResult {
    }

    /** Unreachable, timed out, or the device answered something unexpected. */
    record Failed(String reason) implements PromptPairingResult {
    }
}
```

`core/TvInput.java`:

```java
package dev.andre.homecontrol.core;

/** One input a TV reported, e.g. {@code HDMI_1} / "HDMI 1". */
public record TvInput(String id, String label) {
}
```

`core/InputListing.java`:

```java
package dev.andre.homecontrol.core;

import java.util.List;

/** Implemented by handles whose device can list its inputs; empty while disconnected. */
public interface InputListing {
    List<TvInput> inputs();
}
```

In `core/Action.java` add:

```java
    /** Switch a TV to an input its handle listed through {@link InputListing}. */
    record SelectInput(String inputId) implements Action {
        @Override
        public Capability requires() {
            return Capability.REMOTE_KEYS;
        }
    }
```

In `core/DeviceState.java` add (compare every component B's record has except `updatedAt`):

```java
    /** Equal in everything the UI shows; {@code updatedAt} is ignored so polling adapters publish only real changes. */
    public boolean sameIgnoringTime(DeviceState other) {
        return other != null && status == other.status && powerOn == other.powerOn
                && java.util.Objects.equals(currentApp, other.currentApp)
                && volumeLevel == other.volumeLevel && volumeMax == other.volumeMax && muted == other.muted
                && java.util.Objects.equals(nowPlaying, other.nowPlaying);
    }
```

In `adapters/androidtv/AndroidTvSession.execute` add the branch below, and an equivalent rejecting branch ("… is a Cast receiver and has no inputs") to `CastSession.execute` and any other `switch (action)` Step 1 found:

```java
            case Action.SelectInput ignored -> throw new UnsupportedActionException(
                    "Android TV does not list its inputs; switch inputs from the Home screen");
```

In `core/playback/AppLinks.java` change `static String serviceOf(String host, String path)` to `public static String serviceOf(String host, String path)`.

- [ ] **Step 5: Run the core tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*'`
Expected: PASS.

- [ ] **Step 6: Write the test WebSocket server, the WoL receiver and the failing `adapters/net` tests**

`src/test/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java` (the handshake, frame parsing and fragment reassembly below were exercised against `java.net.http.WebSocket` on JDK 25 while writing this plan):

```java
package dev.andre.homecontrol.adapters.net;

import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal RFC 6455 server for TV fakes: text frames, fragmentation, ping, close; optional TLS with
 * a self-signed certificate for a host name that is not 127.0.0.1 — exactly what a TV presents.
 */
public final class FakeWebSocketServer implements AutoCloseable {

    @FunctionalInterface
    public interface Handler {
        default void onOpen(Connection connection) {
        }

        void onText(Connection connection, String text);
    }

    public static final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private final String path;
        private final String query;

        Connection(Socket socket, OutputStream out, String path, String query) {
            this.socket = socket;
            this.out = out;
            this.path = path;
            this.query = query;
        }

        public String path() {
            return path;
        }

        /** The raw query string, or null. */
        public String query() {
            return query;
        }

        public void send(String text) {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFrame(int opcode, byte[] payload) {
            try {
                out.write(0x80 | opcode);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length < 65_536) {
                    out.write(126);
                    out.write(payload.length >>> 8);
                    out.write(payload.length & 0xFF);
                } else {
                    out.write(127);
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) (((long) payload.length >>> shift) & 0xFF));
                    }
                }
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final Handler handler;
    private final boolean tls;
    private final List<Connection> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger handshakes = new AtomicInteger();
    private volatile boolean refusing;

    public static FakeWebSocketServer plain(Handler handler) throws IOException {
        return new FakeWebSocketServer(ServerSocketFactory.getDefault(), handler, false);
    }

    public static FakeWebSocketServer tls(Handler handler) throws IOException {
        return new FakeWebSocketServer(tlsContext().getServerSocketFactory(), handler, true);
    }

    private FakeWebSocketServer(ServerSocketFactory factory, Handler handler, boolean tls) throws IOException {
        this.server = factory.createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.handler = handler;
        this.tls = tls;
        Thread.ofVirtual().name("fake-websocket-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public String url(String path) {
        return (tls ? "wss" : "ws") + "://127.0.0.1:" + port() + path;
    }

    /** Completed WebSocket handshakes so far. */
    public int connections() {
        return handshakes.get();
    }

    /** A TV that is switched off: open connections drop, new ones are closed before the handshake. */
    public void refuseConnections(boolean refuse) {
        refusing = refuse;
        if (refuse) {
            dropAll();
        }
    }

    public void dropAll() {
        open.forEach(Connection::close);
        open.clear();
    }

    /** A port on which nothing listens, for "the plain port is closed" tests. */
    public static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                return;
            }
            if (refusing) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Refused anyway.
                }
                continue;
            }
            Thread.ofVirtual().name("fake-websocket").start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        Connection connection = null;
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            String key = null;
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                    key = line.substring(colon + 1).trim();
                }
            }
            if (key == null || requestLine.split(" ").length < 2) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String target = requestLine.split(" ")[1];
            int question = target.indexOf('?');
            connection = new Connection(socket, out,
                    question < 0 ? target : target.substring(0, question),
                    question < 0 ? null : target.substring(question + 1));
            open.add(connection);
            handshakes.incrementAndGet();
            handler.onOpen(connection);
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int first = in.read();
                int second = in.read();
                if (first < 0 || second < 0) {
                    return;
                }
                int opcode = first & 0x0F;
                long length = second & 0x7F;
                if (length == 126) {
                    length = ((long) in.read() << 8) | in.read();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | in.read();
                    }
                }
                byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
                byte[] payload = in.readNBytes((int) length);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                switch (opcode) {
                    case 0x0, 0x1 -> {
                        message.write(payload);
                        if ((first & 0x80) != 0) {
                            String text = message.toString(StandardCharsets.UTF_8);
                            message.reset();
                            handler.onText(connection, text);
                        }
                    }
                    case 0x8 -> {
                        connection.sendFrame(0x8, payload);
                        return;
                    }
                    case 0x9 -> connection.sendFrame(0xA, payload);
                    default -> {
                        // Binary and pong frames are not used by the TV protocols.
                    }
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            // The client went away.
        } finally {
            if (connection != null) {
                open.remove(connection);
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) {
            if (c != '\r') {
                line.write(c);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private static SSLContext tlsContext() {
        try {
            ClientCertificate identity = ClientCertificate.generate("fake-tv.invalid");
            char[] password = "fake".toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("tv", identity.keyPair().getPrivate(), password, new Certificate[]{identity.certificate()});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, new SecureRandom());
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not build the fake TV's TLS identity", e);
        }
    }

    @Override
    public void close() {
        dropAll();
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/net/FakeWakeOnLanReceiver.java` — binds a `DatagramSocket` to `127.0.0.1:0`; a virtual thread copies every received datagram (`Arrays.copyOf(packet.getData(), packet.getLength())`) into a `LinkedBlockingQueue<byte[]>`; API: `int port()`, `InetSocketAddress address()`, `byte[] nextPacket()` (polls 5 s, null when none), `int received()`, `close()`.

`src/test/java/dev/andre/homecontrol/adapters/net/TextWebSocketTest.java` — an echo `FakeWebSocketServer.plain((connection, text) -> connection.send(text))` and `HttpClient.newHttpClient()`; the listener collects texts into a `BlockingQueue<String>` and close reasons into another. Test cases:

- `exchangesTextWithTheServer`: `send("hello")` → `hello` received.
- `reassemblesLargeMessagesInBothDirections`: `send("x".repeat(70_000))` → a 70 000-character echo.
- `reportsTheServerDroppingTheConnectionOnce`: `server.dropAll()` → exactly one close reason within 5 s; `isOpen()` false; `send` then throws `IOException`.
- `closingItDoesNotReportAClose`: `close()` → no close reason within 500 ms.
- `aClosedPortIsAnIOException`: connect to `ws://127.0.0.1:<FakeWebSocketServer.closedPort()>` → `IOException`.
- `errorMessagesNeverContainTheQuery`: connecting to `ws://127.0.0.1:<closed>/api?token=secret` → the `IOException` message does not contain `secret`.

`src/test/java/dev/andre/homecontrol/adapters/net/InsecureTlsTest.java` — test cases:

- `connectsToATvCertificateIssuedForAnotherHost`: `FakeWebSocketServer.tls(echo)`; `TextWebSocket.connect(InsecureTls.httpClient(Duration.ofSeconds(2)), URI.create(server.url("/")), …)` → echo works.
- `theDefaultClientStillRejectsThatCertificate`: the same URL through `HttpClient.newHttpClient()` → `IOException` whose cause chain contains `javax.net.ssl.SSLHandshakeException` (proves nothing global changed).

`src/test/java/dev/andre/homecontrol/adapters/net/WakeOnLanTest.java` — test cases:

- `theMagicPacketIsSixFfBytesAndTheMacSixteenTimes`: `magicPacket("A8:23:FE:01:02:03")` has length 102, bytes 0–5 are `0xFF`, and for every `i` in 0..15 bytes `6 + 6i` to `11 + 6i` equal the MAC.
- `sendsThreePacketsToTheTarget`: `new WakeOnLan(receiver.address()).wake("a8-23-fe-01-02-03")` → the receiver gets three packets equal to the magic packet.
- `anInvalidMacIsRejectedBeforeSending`: `wake("nope")` → `IllegalArgumentException`; the receiver got nothing within 500 ms.

- [ ] **Step 7: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.net.*'`
Expected: compilation failure — `TextWebSocket`, `InsecureTls`, `WakeOnLan` do not exist.

- [ ] **Step 8: Write the `adapters/net` helpers**

`adapters/net/TextWebSocket.java`:

```java
package dev.andre.homecontrol.adapters.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A blocking, text-only facade over {@link java.net.http.WebSocket} for the TV protocols. Messages
 * arrive whole (fragments are reassembled); the listener hears a close at most once and never for
 * a {@link #close()} this side initiated. Listener callbacks run on the HTTP client's threads:
 * never call {@link #send} from inside one.
 */
public final class TextWebSocket implements AutoCloseable {

    public interface Listener {
        void onText(String text);

        void onClosed(String reason);
    }

    private final WebSocket socket;
    private final Duration timeout;
    private final AtomicBoolean closed;

    private TextWebSocket(WebSocket socket, Duration timeout, AtomicBoolean closed) {
        this.socket = socket;
        this.timeout = timeout;
        this.closed = closed;
    }

    public static TextWebSocket connect(HttpClient client, URI uri, Duration timeout, Listener listener)
            throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        WebSocket.Listener adapter = new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    String text = partial.toString();
                    partial.setLength(0);
                    listener.onText(text);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (closed.compareAndSet(false, true)) {
                    listener.onClosed("closed by the device (" + statusCode
                            + (reason == null || reason.isEmpty() ? "" : ", " + reason) + ")");
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (closed.compareAndSet(false, true)) {
                    listener.onClosed(error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            }
        };
        try {
            WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(timeout)
                    .buildAsync(uri, adapter)
                    .get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS);
            return new TextWebSocket(socket, timeout, closed);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("Could not open " + withoutQuery(uri) + ": " + cause.getClass().getSimpleName(), cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out opening " + withoutQuery(uri), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening " + withoutQuery(uri), e);
        }
    }

    /** Sends one complete text message; one sender at a time (the JDK forbids overlapping sends). */
    public synchronized void send(String text) throws IOException {
        if (closed.get()) {
            throw new IOException("The connection is closed");
        }
        try {
            socket.sendText(text, true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Sending failed: " + e.getCause().getClass().getSimpleName(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Sending timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending", e);
        }
    }

    public boolean isOpen() {
        return !closed.get() && !socket.isOutputClosed() && !socket.isInputClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .orTimeout(1, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> socket.abort());
        }
    }

    /** Tizen puts its token in the query string; it must never reach a log or an error message. */
    public static String withoutQuery(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }
}
```

(Error messages use the exception class name, not its message, because JDK connection exceptions can echo the full URI including the query.)

`adapters/net/InsecureTls.java`:

```java
package dev.andre.homecontrol.adapters.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * TVs serve their LAN APIs over TLS with self-signed certificates for names that are not their IP.
 * Clients from here accept any certificate and skip host name checks; being an
 * {@link X509ExtendedTrustManager} is what makes JSSE skip endpoint identification too.
 * Use only for TV connections on the LAN; never make this the default context.
 */
public final class InsecureTls {

    private InsecureTls() {
    }

    public static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .sslContext(trustingAnyCertificate())
                .connectTimeout(connectTimeout)
                .build();
    }

    static SSLContext trustingAnyCertificate() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new AcceptAny()}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is unavailable", e);
        }
    }

    private static final class AcceptAny extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
```

`adapters/net/WakeOnLan.java`:

```java
package dev.andre.homecontrol.adapters.net;

import dev.andre.homecontrol.core.MacAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;

/** Sends the Wake-on-LAN magic packet: six 0xFF bytes, then the MAC sixteen times, as a UDP broadcast. */
public class WakeOnLan {

    private final InetSocketAddress target;

    public WakeOnLan(InetSocketAddress target) {
        this.target = target;
    }

    public static byte[] magicPacket(String mac) {
        byte[] address = MacAddress.bytes(mac);
        byte[] packet = new byte[6 + 16 * 6];
        Arrays.fill(packet, 0, 6, (byte) 0xFF);
        for (int i = 0; i < 16; i++) {
            System.arraycopy(address, 0, packet, 6 + i * 6, 6);
        }
        return packet;
    }

    /** Three copies, because UDP broadcasts get lost and a sleeping NIC may miss the first. */
    public void wake(String mac) throws IOException {
        byte[] packet = magicPacket(mac);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (int i = 0; i < 3; i++) {
                socket.send(new DatagramPacket(packet, packet.length, target));
            }
        }
    }
}
```

`adapters/net/WakeOnLanProperties.java`:

```java
package dev.andre.homecontrol.adapters.net;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Use a subnet broadcast (e.g. 192.168.1.255) if the host has several networks. */
@ConfigurationProperties("home-control.wake-on-lan")
public record WakeOnLanProperties(@DefaultValue("255.255.255.255") String broadcastAddress,
                                  @DefaultValue("9") int port) {
}
```

`adapters/net/NetConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.net;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WakeOnLanProperties.class)
public class NetConfiguration {

    @Bean
    public WakeOnLan wakeOnLan(WakeOnLanProperties properties) {
        return new WakeOnLan(new InetSocketAddress(properties.broadcastAddress(), properties.port()));
    }
}
```

- [ ] **Step 9: Run the `adapters/net` tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.net.*'`
Expected: PASS.

- [ ] **Step 10: Write the failing link-parsing tests, then `ContentLinks`**

`src/test/java/dev/andre/homecontrol/adapters/links/ContentLinksTest.java` — test cases:

- `findsTheYouTubeVideoId` (parameterized, all → `aqz-KE-bpKQ`): `https://www.youtube.com/watch?v=aqz-KE-bpKQ`, `https://www.youtube.com/watch?feature=share&v=aqz-KE-bpKQ&t=30`, `https://m.youtube.com/watch?v=aqz-KE-bpKQ`, `https://youtu.be/aqz-KE-bpKQ`, `https://youtu.be/aqz-KE-bpKQ?si=abc`, `https://www.youtube.com/shorts/aqz-KE-bpKQ`, `https://www.youtube.com/live/aqz-KE-bpKQ`, `https://www.youtube.com/embed/aqz-KE-bpKQ`.
- `noVideoIdWithoutAValidOne` (parameterized, all empty): `https://www.youtube.com/`, `https://www.youtube.com/watch?v=short`, `https://www.youtube.com/channel/UC123`, `https://example.org/watch?v=aqz-KE-bpKQ`.
- `findsTheNetflixTitleId` (parameterized, all → `80057281`): `https://www.netflix.com/title/80057281`, `https://www.netflix.com/de/title/80057281`, `https://www.netflix.com/de-en/title/80057281?s=a`, `https://www.netflix.com/watch/80057281?trackId=1`.
- `noNetflixTitleIdOtherwise`: `https://www.netflix.com/browse`, `https://example.org/title/80057281` → empty.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.links.*'` — Expected: compilation failure. Then write `adapters/links/ContentLinks.java`:

```java
package dev.andre.homecontrol.adapters.links;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content ids inside service URLs, for adapters that launch apps by id instead of by URL. */
public final class ContentLinks {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIDEO_PATH = Pattern.compile("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})(?:/.*)?$");
    private static final Pattern NETFLIX_PATH = Pattern.compile("^(?:/[a-z]{2}(?:-[a-z]{2})?)?/(?:title|watch)/(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private ContentLinks() {
    }

    public static Optional<String> youtubeVideoId(URI uri) {
        String host = host(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host.equals("youtu.be")) {
            return valid(path.startsWith("/") ? path.substring(1) : path);
        }
        if (!host.equals("youtube.com") && !host.endsWith(".youtube.com")) {
            return Optional.empty();
        }
        Optional<String> fromQuery = queryParameter(uri, "v").flatMap(ContentLinks::valid);
        if (fromQuery.isPresent()) {
            return fromQuery;
        }
        Matcher matcher = VIDEO_PATH.matcher(path);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static Optional<String> netflixTitleId(URI uri) {
        String host = host(uri);
        if (!host.equals("netflix.com") && !host.endsWith(".netflix.com")) {
            return Optional.empty();
        }
        Matcher matcher = NETFLIX_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static Optional<String> valid(String candidate) {
        return VIDEO_ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }

    private static Optional<String> queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                return Optional.of(equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }
}
```

Run the same command again. Expected: PASS.

- [ ] **Step 11: Build and commit the shared helpers**

Run: `.superpowers/gradle.sh build` — Expected: BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "feat: WebSocket, TV-scoped TLS, Wake-on-LAN and content-link helpers for TV adapters"
```

- [ ] **Step 12: Add the webOS fixtures**

`src/test/resources/fixtures/webos/external-inputs.json`:

```json
{"returnValue":true,"devices":[
 {"id":"HDMI_1","label":"HDMI 1","port":1,"appId":"com.webos.app.hdmi1","icon":"http://127.0.0.1:3000/resources/hdmi.png","modified":false,"autoav":false,"currentTVStatus":"","subList":[],"subCount":0,"connected":true,"favorite":false},
 {"id":"HDMI_2","label":"PlayStation","port":2,"appId":"com.webos.app.hdmi2","icon":"http://127.0.0.1:3000/resources/hdmi.png","modified":true,"autoav":false,"currentTVStatus":"","subList":[],"subCount":0,"connected":false,"favorite":false}
]}
```

`src/test/resources/fixtures/webos/connection-info.json`:

```json
{"returnValue":true,"wiredInfo":{"macAddress":"a8:23:fe:01:02:03","state":"connected","ipAddress":"127.0.0.1"},"wifiInfo":{"macAddress":"a8:23:fe:01:02:04","state":"disconnected"},"p2pInfo":{"macAddress":"aa:23:fe:01:02:04"}}
```

`src/test/resources/fixtures/webos/volume-webos6.json`:

```json
{"returnValue":true,"subscribed":true,"volumeStatus":{"activeStatus":true,"adjustVolume":true,"maxVolume":100,"muteStatus":false,"volume":12,"mode":"normal","soundOutput":"tv_speaker"},"callerId":"secondscreen.client"}
```

`src/test/resources/fixtures/webos/volume-webos4.json`:

```json
{"returnValue":true,"scenario":"mastervolume_tv_speaker","volume":7,"muted":true,"subscribed":true}
```

- [ ] **Step 13: Write the failing pure webOS tests**

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsKeysTest.java` — test cases:

- `mapsNavigationAndMediaKeysToPointerSocketButtons` (parameterized): `DPAD_UP→UP`, `DPAD_DOWN→DOWN`, `DPAD_LEFT→LEFT`, `DPAD_RIGHT→RIGHT`, `DPAD_CENTER→ENTER`, `BACK→BACK`, `HOME→HOME`, `MENU→MENU`, `MEDIA_STOP→STOP`, `REWIND→REWIND`, `FAST_FORWARD→FASTFORWARD`, `INFO→INFO`, `SETTINGS→QMENU`, `GUIDE→GUIDE`.
- `sessionHandledKeysHaveNoButton`: every key in `WebOsKeys.HANDLED_BY_SESSION` (`POWER`, `VOLUME_UP`, `VOLUME_DOWN`, `VOLUME_MUTE`, `PLAY_PAUSE`) → `Optional.empty()`.
- `everyRemoteKeyIsDecided`: for every `RemoteKey` value, a button exists, or the key is in `HANDLED_BY_SESSION`, or it is `MEDIA_NEXT` / `MEDIA_PREVIOUS` (no webOS button). A key added to `RemoteKey` later fails this test until someone decides.

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsLaunchesTest.java` — compare `launch.ssapUri()` and `SsapMessages.JSON.writeValueAsString(launch.payload())` exactly:

- `aYouTubeVideoLaunchesTheAppWithContentTarget`: `https://www.youtube.com/watch?v=aqz-KE-bpKQ` and `https://youtu.be/aqz-KE-bpKQ` → `ssap://system.launcher/launch`, `{"id":"youtube.leanback.v4","contentId":"https://www.youtube.com/tv?v=aqz-KE-bpKQ","params":{"contentTarget":"https://www.youtube.com/tv?v=aqz-KE-bpKQ"}}`.
- `youTubeWithoutAVideoJustOpensTheApp`: `https://www.youtube.com/` → `{"id":"youtube.leanback.v4"}`.
- `aNetflixTitleUsesTheConnectSdkContentId`: `https://www.netflix.com/title/80057281` → `{"id":"netflix","contentId":"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4","params":{"contentId":"m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F80057281&source_type=4"}}`.
- `netflixWithoutATitleJustOpensTheApp`: `https://www.netflix.com/browse` → `{"id":"netflix"}`.
- `primeVideoOpensTheAmazonApp`: `https://app.primevideo.com/detail?gti=amzn1.dv.gti.1` → `{"id":"amazon"}`.
- `anythingElseOpensInTheTvBrowser`: `https://www.dazn.com/de-DE/home` → `ssap://system.launcher/open`, `{"target":"https://www.dazn.com/de-DE/home"}`; `https://example.org/a?b=c` → `{"target":"https://example.org/a?b=c"}`.

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsPayloadsTest.java` — test cases:

- `readsTheWebOs6VolumeShape`: `volume(DeviceState.initial(), <volume-webos6.json>)` → level 12, max 100, muted false.
- `readsTheOlderFlatVolumeShape`: `volume-webos4.json` → level 7, max 100, muted true.
- `prefersTheInterfaceCarryingTheTvsAddress`: `connection-info.json` with host `127.0.0.1` → `A8:23:FE:01:02:03`.
- `fallsBackToTheConnectedInterface`: `{"wiredInfo":{"macAddress":"a8:23:fe:01:02:03","state":"disconnected"},"wifiInfo":{"macAddress":"a8:23:fe:01:02:04","state":"connected"}}` with host `10.0.0.9` → `A8:23:FE:01:02:04`.
- `anUnparseableMacIsIgnored`: `{"wiredInfo":{"macAddress":"unknown"}}` → empty.
- `listsInputsInTheTvsOrder`: `external-inputs.json` → `[TvInput("HDMI_1","HDMI 1"), TvInput("HDMI_2","PlayStation")]`; `{}` → empty list.

- [ ] **Step 14: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*'`
Expected: compilation failure.

- [ ] **Step 15: Write the pure webOS types**

`adapters/webos/SsapUris.java`:

```java
package dev.andre.homecontrol.adapters.webos;

/** The SSAP endpoints this adapter uses (aiowebostv {@code endpoints.py}). Constants so they can be switch labels. */
final class SsapUris {

    static final String POINTER_INPUT_SOCKET = "ssap://com.webos.service.networkinput/getPointerInputSocket";
    static final String FOREGROUND_APP = "ssap://com.webos.applicationManager/getForegroundAppInfo";
    static final String GET_VOLUME = "ssap://audio/getVolume";
    static final String SET_VOLUME = "ssap://audio/setVolume";
    static final String VOLUME_UP = "ssap://audio/volumeUp";
    static final String VOLUME_DOWN = "ssap://audio/volumeDown";
    static final String SET_MUTE = "ssap://audio/setMute";
    static final String POWER_STATE = "ssap://com.webos.service.tvpower/power/getPowerState";
    static final String TURN_OFF = "ssap://system/turnOff";
    static final String LAUNCH = "ssap://system.launcher/launch";
    static final String OPEN = "ssap://system.launcher/open";
    static final String EXTERNAL_INPUTS = "ssap://tv/getExternalInputList";
    static final String SWITCH_INPUT = "ssap://tv/switchInput";
    static final String MEDIA_STOP = "ssap://media.controls/stop";
    static final String CONNECTION_INFO = "ssap://com.webos.service.connectionmanager/getinfo";
    static final String SYSTEM_INFO = "ssap://system/getSystemInfo";

    private SsapUris() {
    }
}
```

`adapters/webos/SsapMessages.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * SSAP wire format. Every frame is a JSON object {@code {id, type, uri?, payload}}; the TV echoes
 * the id. The registration manifest is aiowebostv's (unsigned; see Decisions).
 */
final class SsapMessages {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final List<String> PERMISSIONS = List.of(
            "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
            "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
            "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
            "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
            "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO", "SEARCH",
            "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
            "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS");

    private SsapMessages() {
    }

    /**
     * {@code {"type":"register","id":"register_0","payload":{"forcePairing":false,"pairingType":"PROMPT",
     * "client-key":"…","manifest":{"manifestVersion":1,"appVersion":"1.1","permissions":[…]}}}};
     * {@code client-key} only when one is stored.
     */
    static String register(String clientKey) {
        ObjectNode message = JSON.createObjectNode();
        message.put("type", "register");
        message.put("id", SsapConnection.REGISTER_ID);
        ObjectNode payload = message.putObject("payload");
        payload.put("forcePairing", false);
        payload.put("pairingType", "PROMPT");
        if (clientKey != null) {
            payload.put("client-key", clientKey);
        }
        ObjectNode manifest = payload.putObject("manifest");
        manifest.put("manifestVersion", 1);
        manifest.put("appVersion", "1.1");
        ArrayNode permissions = manifest.putArray("permissions");
        PERMISSIONS.forEach(permissions::add);
        return JSON.writeValueAsString(message);
    }

    /** {@code {"id":"req_7","type":"request","uri":"ssap://audio/setVolume","payload":{"volume":20}}}. */
    static String command(String id, String type, String uri, ObjectNode payload) {
        ObjectNode message = JSON.createObjectNode();
        message.put("id", id);
        message.put("type", type);
        message.put("uri", uri);
        message.set("payload", payload == null ? JSON.createObjectNode() : payload);
        return JSON.writeValueAsString(message);
    }

    /** The pointer input socket speaks lines, not JSON: {@code type:button\nname:UP\n\n}. */
    static String button(String name) {
        return "type:button\nname:" + name + "\n\n";
    }

    static ObjectNode empty() {
        return JSON.createObjectNode();
    }
}
```

`adapters/webos/SsapException.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import java.io.IOException;

/** The TV answered, but with an error or {@code returnValue: false}. The connection itself is fine. */
class SsapException extends IOException {
    SsapException(String message) {
        super(message);
    }
}
```

`adapters/webos/SsapPairingException.java`:

```java
package dev.andre.homecontrol.adapters.webos;

/** Registration did not produce a client key. Only re-pairing can fix {@code KEY_REJECTED}. */
class SsapPairingException extends SsapException {

    enum Reason { DECLINED, TIMED_OUT, KEY_REJECTED }

    private final Reason reason;

    SsapPairingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
```

`adapters/webos/WebOsKeys.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.RemoteKey;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Remote keys → pointer-socket button names (aiowebostv {@code buttons.py}). POWER, the volume keys
 * and PLAY_PAUSE are handled by the session through SSAP or a toggle; MEDIA_NEXT/PREVIOUS have no
 * webOS button.
 */
final class WebOsKeys {

    static final Set<RemoteKey> HANDLED_BY_SESSION = Set.of(
            RemoteKey.POWER, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.VOLUME_MUTE, RemoteKey.PLAY_PAUSE);

    private static final Map<RemoteKey, String> BUTTONS = new EnumMap<>(Map.ofEntries(
            Map.entry(RemoteKey.DPAD_UP, "UP"),
            Map.entry(RemoteKey.DPAD_DOWN, "DOWN"),
            Map.entry(RemoteKey.DPAD_LEFT, "LEFT"),
            Map.entry(RemoteKey.DPAD_RIGHT, "RIGHT"),
            Map.entry(RemoteKey.DPAD_CENTER, "ENTER"),
            Map.entry(RemoteKey.BACK, "BACK"),
            Map.entry(RemoteKey.HOME, "HOME"),
            Map.entry(RemoteKey.MENU, "MENU"),
            Map.entry(RemoteKey.MEDIA_STOP, "STOP"),
            Map.entry(RemoteKey.REWIND, "REWIND"),
            Map.entry(RemoteKey.FAST_FORWARD, "FASTFORWARD"),
            Map.entry(RemoteKey.INFO, "INFO"),
            Map.entry(RemoteKey.SETTINGS, "QMENU"),
            Map.entry(RemoteKey.GUIDE, "GUIDE")));

    private WebOsKeys() {
    }

    static Optional<String> button(RemoteKey key) {
        return Optional.ofNullable(BUTTONS.get(key));
    }
}
```

`adapters/webos/WebOsLaunch.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import tools.jackson.databind.node.ObjectNode;

/** One SSAP request that opens something on the TV. */
record WebOsLaunch(String ssapUri, ObjectNode payload) {
}
```

`adapters/webos/WebOsLaunches.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.links.ContentLinks;
import dev.andre.homecontrol.core.playback.AppLinks;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.Locale;

/**
 * App link → webOS launch (spec §4.1). Deep-link support differs by firmware; the setup page's
 * test button shows whether it took. Payload shapes follow ConnectSDK's WebOSTVService.
 */
final class WebOsLaunches {

    static final String YOUTUBE = "youtube.leanback.v4";
    static final String NETFLIX = "netflix";
    static final String PRIME_VIDEO = "amazon";

    private WebOsLaunches() {
    }

    static WebOsLaunch forUri(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return switch (AppLinks.serviceOf(host, uri.getPath())) {
            case "youtube" -> ContentLinks.youtubeVideoId(uri)
                    .map(id -> {
                        String target = "https://www.youtube.com/tv?v=" + id;
                        ObjectNode payload = app(YOUTUBE);
                        payload.put("contentId", target);
                        payload.putObject("params").put("contentTarget", target);
                        return new WebOsLaunch(SsapUris.LAUNCH, payload);
                    })
                    .orElseGet(() -> new WebOsLaunch(SsapUris.LAUNCH, app(YOUTUBE)));
            case "netflix" -> ContentLinks.netflixTitleId(uri)
                    .map(id -> {
                        String contentId = "m=http%3A%2F%2Fapi.netflix.com%2Fcatalog%2Ftitles%2Fmovies%2F"
                                + id + "&source_type=4";
                        ObjectNode payload = app(NETFLIX);
                        payload.put("contentId", contentId);
                        payload.putObject("params").put("contentId", contentId);
                        return new WebOsLaunch(SsapUris.LAUNCH, payload);
                    })
                    .orElseGet(() -> new WebOsLaunch(SsapUris.LAUNCH, app(NETFLIX)));
            case "primevideo" -> new WebOsLaunch(SsapUris.LAUNCH, app(PRIME_VIDEO));
            default -> {
                ObjectNode payload = SsapMessages.empty();
                payload.put("target", uri.toString());
                yield new WebOsLaunch(SsapUris.OPEN, payload);
            }
        };
    }

    private static ObjectNode app(String appId) {
        ObjectNode payload = SsapMessages.empty();
        payload.put("id", appId);
        return payload;
    }
}
```

`adapters/webos/WebOsPayloads.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.MacAddress;
import dev.andre.homecontrol.core.TvInput;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the SSAP payloads whose shape changed across webOS versions. */
final class WebOsPayloads {

    private WebOsPayloads() {
    }

    /** webOS 5+ nests {@code volumeStatus{volume,maxVolume,muteStatus}}; older sets send {@code volume} and {@code muted}. */
    static DeviceState volume(DeviceState state, JsonNode payload) {
        JsonNode status = payload.has("volumeStatus") ? payload.path("volumeStatus") : payload;
        int level = status.path("volume").asInt(state.volumeLevel());
        int max = status.path("maxVolume").asInt(100);
        boolean muted = status.has("muteStatus")
                ? status.path("muteStatus").asBoolean(false)
                : status.path("muted").asBoolean(state.muted());
        return state.withVolume(level, max, muted);
    }

    /** The interface carrying the TV's address wins, then a connected one, then any with a MAC. */
    static Optional<String> macAddress(JsonNode payload, String host) {
        List<JsonNode> interfaces = List.of(payload.path("wiredInfo"), payload.path("wifiInfo"));
        return interfaces.stream().filter(nic -> host.equals(nic.path("ipAddress").asString(""))).findFirst()
                .or(() -> interfaces.stream()
                        .filter(nic -> "connected".equalsIgnoreCase(nic.path("state").asString(""))).findFirst())
                .or(() -> interfaces.stream().filter(nic -> !nic.path("macAddress").asString("").isEmpty()).findFirst())
                .map(nic -> nic.path("macAddress").asString(""))
                .flatMap(WebOsPayloads::parseMac);
    }

    static List<TvInput> inputs(JsonNode payload) {
        List<TvInput> inputs = new ArrayList<>();
        for (JsonNode device : payload.path("devices").values()) {
            String id = device.path("id").asString("");
            if (!id.isEmpty()) {
                inputs.add(new TvInput(id, device.path("label").asString(id)));
            }
        }
        return List.copyOf(inputs);
    }

    private static Optional<String> parseMac(String candidate) {
        try {
            return Optional.of(MacAddress.normalize(candidate));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

(Jackson 3: `asString(default)` / `asInt(default)` / `asBoolean(default)` return the default for missing nodes. If an accessor throws for a missing node in the local Jackson version, guard with `isMissingNode()` and keep the semantics.)

- [ ] **Step 16: Run the pure webOS tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsKeysTest' --tests 'dev.andre.homecontrol.adapters.webos.WebOsLaunchesTest' --tests 'dev.andre.homecontrol.adapters.webos.WebOsPayloadsTest'`
Expected: PASS.

- [ ] **Step 17: Write the fake SSAP server and the failing connection tests**

`src/test/java/dev/andre/homecontrol/adapters/webos/FakeSsapServer.java` — its behaviour is the protocol contract the adapter is tested against; implement exactly this:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** An in-process LG TV: SSAP main socket, pointer input socket, the answers of a webOS 5 set. */
public class FakeSsapServer implements AutoCloseable {

    public enum Prompt { ACCEPT, DECLINE, IGNORE }

    public static final String CLIENT_KEY = "5f1c0d7e2b9a4c3d8e7f6a5b4c3d2e1f";
    static final String POINTER_PATH = "/resources/3c1f9a/netinput.pointer.sock";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> INSTALLED = Set.of("youtube.leanback.v4", "netflix", "amazon", "com.webos.app.home");

    private record Subscription(FakeWebSocketServer.Connection connection, String id) {
    }

    private final FakeWebSocketServer server;
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> buttons = new LinkedBlockingQueue<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger registrations = new AtomicInteger();
    private volatile Prompt prompt = Prompt.ACCEPT;
    private volatile String foregroundApp = "com.webos.app.home";
    private volatile int volume = 12;
    private volatile boolean muted;

    public FakeSsapServer(boolean tls) throws IOException {
        FakeWebSocketServer.Handler handler = (connection, text) -> {
            if (POINTER_PATH.equals(connection.path())) {
                buttons.add(text);
            } else {
                onMain(connection, text);
            }
        };
        server = tls ? FakeWebSocketServer.tls(handler) : FakeWebSocketServer.plain(handler);
    }

    public int port() {
        return server.port();
    }

    public int connections() {
        return server.connections();
    }

    public int registrations() {
        return registrations.get();
    }

    public void setPrompt(Prompt answer) {
        prompt = answer;
    }

    public void refuseConnections(boolean refuse) {
        server.refuseConnections(refuse);
    }

    public void dropConnections() {
        server.dropAll();
    }

    /** The next request or subscribe message for {@code uri}, skipping others; null after 5 s. */
    public JsonNode nextRequest(String uri) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (long left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
            JsonNode message = requests.poll(left, TimeUnit.NANOSECONDS);
            if (message != null && uri.equals(message.path("uri").asString(""))) {
                return message;
            }
        }
        return null;
    }

    public String nextButton() throws InterruptedException {
        return buttons.poll(5, TimeUnit.SECONDS);
    }

    public void changeForegroundApp(String appId) {
        foregroundApp = appId;
        push(SsapUris.FOREGROUND_APP, foregroundPayload());
    }

    public void pushVolume(int level, boolean mute) {
        volume = level;
        muted = mute;
        push(SsapUris.GET_VOLUME, volumePayload());
    }

    private void onMain(FakeWebSocketServer.Connection connection, String text) {
        JsonNode message = JSON.readTree(text);
        String type = message.path("type").asString("");
        String id = message.path("id").asString("");
        if (type.equals("register")) {
            register(connection, message.path("payload").path("client-key").asString(""));
            return;
        }
        if (!type.equals("request") && !type.equals("subscribe")) {
            return;
        }
        requests.add(message);
        String uri = message.path("uri").asString("");
        if (type.equals("subscribe")) {
            subscriptions.put(uri, new Subscription(connection, id));
        }
        respond(connection, id, uri, message.path("payload"));
    }

    private void register(FakeWebSocketServer.Connection connection, String key) {
        registrations.incrementAndGet();
        if (CLIENT_KEY.equals(key)) {
            connection.send(registered());
            return;
        }
        connection.send("{\"type\":\"response\",\"id\":\"register_0\",\"payload\":{\"pairingType\":\"PROMPT\",\"returnValue\":true}}");
        switch (prompt) {
            case ACCEPT -> connection.send(registered());
            case DECLINE -> connection.send(
                    "{\"type\":\"error\",\"id\":\"register_0\",\"error\":\"403 User denied access\",\"payload\":{}}");
            case IGNORE -> {
            }
        }
    }

    private void respond(FakeWebSocketServer.Connection connection, String id, String uri, JsonNode payload) {
        switch (uri) {
            case SsapUris.FOREGROUND_APP -> connection.send(response(id, foregroundPayload()));
            case SsapUris.GET_VOLUME -> connection.send(response(id, volumePayload()));
            case SsapUris.POWER_STATE -> connection.send(
                    response(id, "{\"returnValue\":true,\"state\":\"Active\",\"subscribed\":true}"));
            case SsapUris.POINTER_INPUT_SOCKET -> connection.send(
                    response(id, "{\"returnValue\":true,\"socketPath\":\"" + server.url(POINTER_PATH) + "\"}"));
            case SsapUris.EXTERNAL_INPUTS -> connection.send(response(id, fixture("external-inputs.json")));
            case SsapUris.CONNECTION_INFO -> connection.send(response(id, fixture("connection-info.json")));
            case SsapUris.SYSTEM_INFO -> connection.send(
                    response(id, "{\"returnValue\":true,\"modelName\":\"OLED55C9PLA\"}"));
            case SsapUris.LAUNCH -> launch(connection, id, payload.path("id").asString(""));
            case SsapUris.OPEN -> {
                connection.send(response(id, "{\"returnValue\":true,\"id\":\"com.webos.app.browser\"}"));
                changeForegroundApp("com.webos.app.browser");
            }
            case SsapUris.SET_VOLUME -> {
                ok(connection, id);
                pushVolume(payload.path("volume").asInt(volume), muted);
            }
            case SsapUris.SET_MUTE -> {
                ok(connection, id);
                pushVolume(volume, payload.path("mute").asBoolean(muted));
            }
            case SsapUris.VOLUME_UP -> {
                ok(connection, id);
                pushVolume(volume + 1, muted);
            }
            case SsapUris.VOLUME_DOWN -> {
                ok(connection, id);
                pushVolume(volume - 1, muted);
            }
            case SsapUris.TURN_OFF, SsapUris.SWITCH_INPUT, SsapUris.MEDIA_STOP -> ok(connection, id);
            default -> connection.send("{\"type\":\"error\",\"id\":\"" + id
                    + "\",\"error\":\"404 no such service or method\",\"payload\":{}}");
        }
    }

    private void launch(FakeWebSocketServer.Connection connection, String id, String appId) {
        if (!INSTALLED.contains(appId)) {
            connection.send("{\"type\":\"error\",\"id\":\"" + id + "\",\"error\":\"500 Application error\","
                    + "\"payload\":{\"returnValue\":false,\"errorCode\":-101,\"errorText\":\"\\\"" + appId
                    + "\\\" was not found OR Unsupported Application Type\"}}");
            return;
        }
        connection.send(response(id, "{\"returnValue\":true,\"id\":\"" + appId + "\",\"sessionId\":\"c2Vzc2lvbg==\"}"));
        changeForegroundApp(appId);
    }

    private String foregroundPayload() {
        return "{\"subscribed\":true,\"appId\":\"" + foregroundApp + "\",\"returnValue\":true,\"windowId\":\"\",\"processId\":\"\"}";
    }

    private String volumePayload() {
        return "{\"returnValue\":true,\"subscribed\":true,\"volumeStatus\":{\"activeStatus\":true,\"adjustVolume\":true,"
                + "\"maxVolume\":100,\"muteStatus\":" + muted + ",\"volume\":" + volume
                + ",\"mode\":\"normal\",\"soundOutput\":\"tv_speaker\"},\"callerId\":\"secondscreen.client\"}";
    }

    private void push(String uri, String payload) {
        Subscription subscription = subscriptions.get(uri);
        if (subscription != null) {
            subscription.connection().send(response(subscription.id(), payload));
        }
    }

    private static void ok(FakeWebSocketServer.Connection connection, String id) {
        connection.send(response(id, "{\"returnValue\":true}"));
    }

    private static String registered() {
        return "{\"type\":\"registered\",\"id\":\"register_0\",\"payload\":{\"client-key\":\"" + CLIENT_KEY + "\"}}";
    }

    private static String response(String id, String payload) {
        return "{\"type\":\"response\",\"id\":\"" + id + "\",\"payload\":" + payload + "}";
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/webos/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.close();
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/webos/SsapConnectionTest.java` — helper `properties(port, securePort, pairingTimeoutSeconds)` = `new WebOsProperties(true, port, securePort, 2, 2, pairingTimeoutSeconds, 1, 2, 0)`; client `InsecureTls.httpClient(Duration.ofSeconds(2))`; the fake is plain unless stated. Test cases:

- `theRegisterMessageCarriesTheManifestAndOnlyAKnownKey`: parse `SsapMessages.register(null)` → `type` `register`, `id` `register_0`, `payload.pairingType` `PROMPT`, `payload.forcePairing` false, no `payload.client-key`, `manifest.manifestVersion` 1, 37 permissions including `CONTROL_POWER` and `READ_NETWORK_STATE`; `register("k")` has `payload.client-key` `k`.
- `aStoredKeyRegistersWithoutAPrompt`: `register(CLIENT_KEY, 1s)` returns `CLIENT_KEY`.
- `pairingWaitsForTheUserToAccept`: prompt `ACCEPT`, `register(null, 2s)` returns `CLIENT_KEY`.
- `aDeclinedPromptIsDeclined`: prompt `DECLINE` → `SsapPairingException` with reason `DECLINED` and a message containing `403`.
- `anUnansweredPromptTimesOut`: prompt `IGNORE`, `register(null, 1s)` → reason `TIMED_OUT`.
- `aStoredKeyTheTvForgotIsRejected`: `register("stale", 1s)` → reason `KEY_REJECTED`.
- `concurrentRequestsGetTheirOwnAnswers`: after registering, two virtual threads call `request(SYSTEM_INFO, empty())` and `request(SET_VOLUME, {"volume":20})` 20 times each → every `SYSTEM_INFO` payload has `modelName` `OLED55C9PLA`; no `SET_VOLUME` payload has `modelName`.
- `anErrorAnswerIsAnSsapException`: `request("ssap://nope/nothing", empty())` → `SsapException` containing `404 no such service or method`.
- `aRefusedLaunchCarriesTheTvsErrorText`: `request(LAUNCH, {"id":"com.example.missing"})` → message contains `500 Application error` and `was not found`.
- `aSubscriptionDeliversTheFirstAnswerAndLaterPushes`: subscribe `FOREGROUND_APP` collecting `appId`s → `com.webos.app.home`; `server.changeForegroundApp("netflix")` → `netflix` arrives.
- `buttonsUseOnePointerInputSocket`: `button("UP")`, `button("DOWN")` → `nextButton()` returns `"type:button\nname:UP\n\n"` then the DOWN line; `server.connections()` is 2 (main + pointer).
- `fallsBackToTlsWhenThePlainPortIsClosed`: `new FakeSsapServer(true)`, properties with `port = FakeWebSocketServer.closedPort()` and `securePort = tls.port()` → registering with `CLIENT_KEY` works.
- `theTvDroppingTheConnectionIsReportedOnce`: `onClosed` collects reasons; `server.dropConnections()` → exactly one reason within 5 s; a following `request` fails with `IOException` at once (not after the request timeout).

- [ ] **Step 18: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.SsapConnectionTest'`
Expected: compilation failure — `SsapConnection` and `WebOsProperties` do not exist.

- [ ] **Step 19: Write the properties, settings and SSAP connection**

`adapters/webos/WebOsProperties.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code wakeGraceSeconds}: after a Wake-on-LAN packet, how long the TV gets before the first reconnect. */
@ConfigurationProperties("home-control.webos")
public record WebOsProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("3000") int port,
                              @DefaultValue("3001") int securePort,
                              @DefaultValue("3") int connectTimeoutSeconds,
                              @DefaultValue("10") int requestTimeoutSeconds,
                              @DefaultValue("60") int pairingTimeoutSeconds,
                              @DefaultValue("1") int reconnectInitialDelaySeconds,
                              @DefaultValue("30") int reconnectMaxDelaySeconds,
                              @DefaultValue("3") int wakeGraceSeconds) {
}
```

`adapters/webos/WebOsSettings.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.util.Map;

/** The webOS adapter's settings under {@code adapters.webos} in {@code devices.json}. */
public record WebOsSettings(String clientKey, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "webos";
    static final String CLIENT_KEY = "clientKey";

    public static WebOsSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new WebOsSettings(blankToNull(settings.get(CLIENT_KEY)),
                blankToNull(settings.get(WakeOnLanAdapter.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
```

`adapters/webos/SsapConnection.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.TextWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One SSAP session with an LG TV: the JSON main socket (requests matched to answers by id,
 * subscriptions that keep their id) plus the line-based pointer input socket for buttons,
 * opened on first use. Blocking API; never call it from a subscription callback.
 */
final class SsapConnection implements AutoCloseable {

    static final String REGISTER_ID = "register_0";

    private static final Logger log = LoggerFactory.getLogger(SsapConnection.class);

    private final HttpClient http;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonNode>> subscriptions = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonNode> registration = new LinkedBlockingQueue<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile TextWebSocket socket;
    private volatile String closedReason;
    private TextWebSocket pointer; // guarded by this

    private SsapConnection(HttpClient http, WebOsProperties properties) {
        this.http = http;
        this.connectTimeout = Duration.ofSeconds(properties.connectTimeoutSeconds());
        this.requestTimeout = Duration.ofSeconds(properties.requestTimeoutSeconds());
    }

    /** ws://host:port first, then wss://host:securePort (firmware that closed the plain port or insists on TLS). */
    static SsapConnection open(HttpClient http, String host, WebOsProperties properties, Consumer<String> onClosed)
            throws IOException {
        SsapConnection connection = new SsapConnection(http, properties);
        TextWebSocket.Listener listener = new TextWebSocket.Listener() {
            @Override
            public void onText(String text) {
                connection.dispatch(text);
            }

            @Override
            public void onClosed(String reason) {
                connection.failEverythingWaiting(reason);
                onClosed.accept(reason);
            }
        };
        String authority = host.contains(":") ? "[" + host + "]" : host;
        try {
            connection.socket = TextWebSocket.connect(http,
                    URI.create("ws://" + authority + ":" + properties.port()), connection.connectTimeout, listener);
        } catch (IOException plainFailed) {
            try {
                connection.socket = TextWebSocket.connect(http,
                        URI.create("wss://" + authority + ":" + properties.securePort()), connection.connectTimeout, listener);
            } catch (IOException secureFailed) {
                secureFailed.addSuppressed(plainFailed);
                throw secureFailed;
            }
        }
        return connection;
    }

    /**
     * With a stored key the TV answers {@code registered} at once. Without one it answers
     * {@code response} with {@code pairingType: PROMPT}, shows the prompt, then sends
     * {@code registered} (accepted) or {@code error} (declined). A PROMPT despite a stored key means
     * the TV forgot this client.
     */
    String register(String clientKey, Duration promptTimeout) throws IOException {
        registration.clear();
        socket.send(SsapMessages.register(clientKey));
        JsonNode answer = awaitRegistration(requestTimeout);
        if (answer == null) {
            throw new IOException("The TV did not answer the registration within " + requestTimeout.toSeconds() + " seconds");
        }
        if (type(answer).equals("response") && answer.path("payload").path("pairingType").asString("").equals("PROMPT")) {
            if (clientKey != null) {
                throw new SsapPairingException(SsapPairingException.Reason.KEY_REJECTED,
                        "The TV no longer accepts the stored pairing");
            }
            answer = awaitRegistration(promptTimeout);
            if (answer == null) {
                throw new SsapPairingException(SsapPairingException.Reason.TIMED_OUT,
                        "Nobody answered the prompt on the TV within " + promptTimeout.toSeconds() + " seconds");
            }
        }
        return switch (type(answer)) {
            case "registered" -> {
                String key = answer.path("payload").path("client-key").asString("");
                if (key.isEmpty()) {
                    throw new SsapException("The TV registered this client without a client key");
                }
                yield key;
            }
            case "error" -> throw new SsapPairingException(
                    clientKey == null ? SsapPairingException.Reason.DECLINED : SsapPairingException.Reason.KEY_REJECTED,
                    answer.path("error").asString("The TV refused the registration"));
            case "closed" -> throw new IOException("The TV closed the connection during registration");
            default -> throw new SsapException("Unexpected registration answer of type " + type(answer));
        };
    }

    JsonNode request(String uri, ObjectNode payload) throws IOException {
        return payloadOf(uri, send("req_" + ids.incrementAndGet(), "request", uri, payload));
    }

    /** Sends without waiting; for {@code system/turnOff}, whose answer is unreliable while the TV shuts down. */
    void fire(String uri, ObjectNode payload) throws IOException {
        socket.send(SsapMessages.command("req_" + ids.incrementAndGet(), "request", uri, payload));
    }

    /** {@code onPayload} receives the first answer's payload and every later push for this subscription. */
    void subscribe(String uri, Consumer<JsonNode> onPayload) throws IOException {
        String id = "sub_" + ids.incrementAndGet();
        subscriptions.put(id, onPayload);
        try {
            payloadOf(uri, send(id, "subscribe", uri, SsapMessages.empty()));
        } catch (IOException e) {
            subscriptions.remove(id);
            throw e;
        }
    }

    synchronized void button(String name) throws IOException {
        if (pointer == null || !pointer.isOpen()) {
            String path = request(SsapUris.POINTER_INPUT_SOCKET, SsapMessages.empty()).path("socketPath").asString("");
            if (path.isEmpty()) {
                throw new SsapException("The TV did not offer a pointer input socket");
            }
            pointer = TextWebSocket.connect(http, URI.create(path), connectTimeout, new TextWebSocket.Listener() {
                @Override
                public void onText(String text) {
                }

                @Override
                public void onClosed(String reason) {
                    log.debug("Pointer input socket closed: {}", reason);
                }
            });
        }
        pointer.send(SsapMessages.button(name));
    }

    static JsonNode payloadOf(String uri, JsonNode message) throws SsapException {
        JsonNode payload = message.path("payload");
        if (type(message).equals("error")) {
            String errorText = payload.path("errorText").asString("");
            throw new SsapException(uri + " failed: " + message.path("error").asString("unknown error")
                    + (errorText.isEmpty() ? "" : " (" + errorText + ")"));
        }
        JsonNode returnValue = payload.path("returnValue");
        if (!returnValue.isMissingNode() && !returnValue.asBoolean(true)) {
            throw new SsapException(uri + " failed: " + payload.path("errorText").asString("the TV refused"));
        }
        return payload;
    }

    private JsonNode send(String id, String type, String uri, ObjectNode payload) throws IOException {
        if (closedReason != null) {
            throw new IOException("The TV closed the connection: " + closedReason);
        }
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            socket.send(SsapMessages.command(id, type, uri, payload));
            return answer.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("No answer from the TV to " + uri + " within " + requestTimeout.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + uri, e);
        } finally {
            pending.remove(id);
        }
    }

    private JsonNode awaitRegistration(Duration timeout) throws IOException {
        try {
            return registration.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while registering", e);
        }
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = SsapMessages.JSON.readTree(text);
        } catch (JacksonException e) {
            log.debug("Ignoring a message from the TV that is not JSON");
            return;
        }
        String id = message.path("id").asString("");
        if (id.equals(REGISTER_ID)) {
            registration.add(message);
            return;
        }
        CompletableFuture<JsonNode> waiting = pending.remove(id);
        if (waiting != null) {
            waiting.complete(message);
        }
        Consumer<JsonNode> subscriber = subscriptions.get(id);
        if (subscriber != null && type(message).equals("response")) {
            try {
                subscriber.accept(message.path("payload"));
            } catch (RuntimeException e) {
                log.warn("Applying a webOS state update failed", e);
            }
        }
    }

    private void failEverythingWaiting(String reason) {
        closedReason = reason;
        IOException closed = new IOException("The TV closed the connection: " + reason);
        pending.values().forEach(future -> future.completeExceptionally(closed));
        registration.add(SsapMessages.JSON.createObjectNode().put("type", "closed"));
    }

    private static String type(JsonNode message) {
        return message.path("type").asString("");
    }

    @Override
    public synchronized void close() {
        if (pointer != null) {
            pointer.close();
        }
        if (socket != null) {
            socket.close();
        }
    }
}
```

- [ ] **Step 20: Run the connection tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.SsapConnectionTest'`
Expected: PASS.

- [ ] **Step 21: Write the failing session tests**

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsSessionTest.java` — per test: `FakeSsapServer tv = new FakeSsapServer(false)`; `FakeWakeOnLanReceiver receiver`; a `JsonFileDeviceRegistry` in a `@TempDir` holding `new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1", Map.of("webos", settings), Instant.now())` where `settings` defaults to `Map.of("clientKey", FakeSsapServer.CLIENT_KEY)`; `properties = new WebOsProperties(true, tv.port(), FakeWebSocketServer.closedPort(), 2, 2, 2, 1, 2, 0)`; `states = new CopyOnWriteArrayList<DeviceState>()`; `session = new WebOsSession(device, properties, InsecureTls.httpClient(Duration.ofSeconds(2)), registry, new WakeOnLan(receiver.address()), states::add, () -> {})`; `session.start()`; `@AfterEach` closes all. Helper `connected()` awaits (5 s) `session.state().status() == CONNECTED`. Test cases:

- `connectsWithTheStoredKeyAndMirrorsAppVolumeAndPower`: after `connected()`, awaited: `currentApp` `com.webos.app.home`, `volumeLevel` 12, `volumeMax` 100, `muted` false, `powerOn` true; `states` has a `CONNECTING` entry before the first `CONNECTED`.
- `aKeyPressGoesThroughThePointerInputSocket`: `execute(new Action.PressKey(RemoteKey.DPAD_UP))` → `tv.nextButton()` is `"type:button\nname:UP\n\n"`.
- `playPauseAlternatesPauseAndPlay`: two `PLAY_PAUSE` presses → buttons `PAUSE` then `PLAY`.
- `aKeyWithoutAButtonIsUnsupported`: `MEDIA_NEXT` → `UnsupportedActionException` containing `LG TV`.
- `volumeKeysAndAbsoluteVolumeUseSsapAudio`: `VOLUME_UP` → `tv.nextRequest("ssap://audio/volumeUp")` not null; `new Action.SetVolume(20)` → `ssap://audio/setVolume` with payload `volume` 20, and `state().volumeLevel()` becomes 20; `VOLUME_MUTE` while unmuted → `ssap://audio/setMute` payload `mute` true; `new Action.Mute(false)` → payload `mute` false.
- `stopUsesMediaControls`: `new Action.Stop()` → `ssap://media.controls/stop`.
- `powerWhileOnTurnsTheTvOff`: `PressKey(POWER)` → `ssap://system/turnOff`; `state().powerOn()` false.
- `learnsTheMacAddressAfterConnecting`: await registry `adapterSettings("webos").get("macAddress")` = `A8:23:FE:01:02:03`.
- `aHandEnteredMacIsNotOverwritten`: settings add `macAddress=11:22:33:44:55:66`, `macAddressManual=true` → after `connected()` and `tv.nextRequest(SsapUris.CONNECTION_INFO)` returned, wait 500 ms; the registry still has `11:22:33:44:55:66`.
- `powerWhileOffWakesTheTvAndReconnects`: `connected()`, await the MAC learned, `tv.refuseConnections(true)`, await `DISCONNECTED`; `PressKey(POWER)` → `receiver.nextPacket()` equals `WakeOnLan.magicPacket("A8:23:FE:01:02:03")`; `tv.refuseConnections(false)` → `connected()`.
- `powerWhileOffWithoutAMacExplainsTheFix`: `tv.refuseConnections(true)` before `start()`; await `DISCONNECTED`; `PressKey(POWER)` → `DeviceOfflineException` containing `MAC address` and `setup page`; `receiver.received()` 0.
- `keysWhileDisconnectedFailNowAndAreNotReplayed`: `tv.refuseConnections(true)` before `start()`; `PressKey(HOME)` → `DeviceOfflineException` containing `not connected`; `tv.refuseConnections(false)`, `connected()`; `tv.nextButton()` is null.
- `opensAYouTubeLinkWithContentTarget`: `OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))` → `ssap://system.launcher/launch` with `params.contentTarget` `https://www.youtube.com/tv?v=aqz-KE-bpKQ`; `state().currentApp()` becomes `youtube.leanback.v4`.
- `aCastLoadIsNotForTvs`: `new Action.CastLoad("CC1AD845", Map.of())` → `UnsupportedActionException`.
- `anOrdinaryWebLinkOpensInTheTvBrowser`: `OpenAppLink(URI.create("https://example.org/page"))` → `ssap://system.launcher/open` with `target` `https://example.org/page`.
- `listsAndSwitchesInputs`: after `connected()`, `inputs()` is `[HDMI_1/HDMI 1, HDMI_2/PlayStation]`; `execute(new Action.SelectInput("HDMI_2"))` → `ssap://tv/switchInput` payload `inputId` `HDMI_2`; after `tv.refuseConnections(true)` and `DISCONNECTED`, `inputs()` is empty.
- `aForgottenPairingIsUnpairedAndStopsReconnecting`: settings `clientKey=stale` → await `UNPAIRED`; after another 3 s `tv.registrations()` is still 1.
- `withoutAClientKeyItNeverConnects`: settings empty → `UNPAIRED`; after 1 s `tv.connections()` is 0.
- `reconnectsAfterTheTvDropsTheConnection`: `connected()`, `tv.dropConnections()` → `DISCONNECTED` observed in `states`, then `CONNECTED` again; `reconnectNow()` while connected leaves `tv.connections()` unchanged after 500 ms.
- `closeStopsEverything`: `connected()`, `close()`, `states.clear()`, `tv.dropConnections()` → `states` still empty after 2 s.

- [ ] **Step 22: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest'`
Expected: compilation failure — `WebOsSession` does not exist.

- [ ] **Step 23: Write the session**

`adapters/webos/WebOsSession.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.InputListing;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One LG webOS TV. Connects with the stored client key, mirrors foreground app, volume and power
 * into {@link DeviceState}, and reconnects with backoff until closed — unless the TV no longer
 * accepts the key: then it is UNPAIRED and only re-pairing helps (connecting again would re-prompt).
 *
 * <p>Threading: connect, loss and reconnect run on one scheduler thread; commands run on the
 * caller's thread against the current connection and never wait for a reconnect; subscription
 * callbacks only update state.
 */
public class WebOsSession implements DeviceHandle, InputListing {

    private static final Logger log = LoggerFactory.getLogger(WebOsSession.class);
    private static final Set<String> STANDBY_STATES = Set.of("Suspend", "Active Standby", "Power Off");

    private final Device device;
    private final WebOsProperties properties;
    private final HttpClient http;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClose;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean nextPlayPauseIsPlay = new AtomicBoolean();

    private volatile SsapConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile List<TvInput> inputs = List.of();
    private volatile boolean closed;
    private Duration backoff;                   // scheduler thread only
    private ScheduledFuture<?> pendingConnect;  // scheduler thread only

    WebOsSession(Device device, WebOsProperties properties, HttpClient http, DeviceRegistry registry,
                 WakeOnLan wakeOnLan, Consumer<DeviceState> onChange, Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.http = http;
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.onChange = onChange;
        this.onClose = onClose;
        this.backoff = initialBackoff();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("webos-" + device.id()).factory());
    }

    void start() {
        onScheduler(this::connect);
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV announce itself: skip whatever is left of the backoff. */
    void reconnectNow() {
        onScheduler(() -> {
            if (connection == null && state.status() != DeviceStatus.UNPAIRED) {
                backoff = initialBackoff();
                connect();
            }
        });
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public List<TvInput> inputs() {
        return inputs;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> pressKey(press.key());
            case Action.OpenAppLink open -> {
                WebOsLaunch launch = WebOsLaunches.forUri(open.uri());
                call(launch.ssapUri(), launch.payload(), "open " + open.uri());
            }
            case Action.SelectInput select -> call(SsapUris.SWITCH_INPUT,
                    SsapMessages.empty().put("inputId", select.inputId()), "switch to input " + select.inputId());
            case Action.SetVolume volume -> call(SsapUris.SET_VOLUME,
                    SsapMessages.empty().put("volume", Math.clamp(volume.level(), 0, 100)), "set the volume");
            case Action.Mute mute -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", mute.muted()), "mute");
            case Action.Stop ignored -> call(SsapUris.MEDIA_STOP, SsapMessages.empty(), "stop playback");
            case Action.CastLoad ignored -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
        }
    }

    private void pressKey(RemoteKey key) {
        switch (key) {
            case POWER -> togglePower();
            case VOLUME_UP -> call(SsapUris.VOLUME_UP, SsapMessages.empty(), "raise the volume");
            case VOLUME_DOWN -> call(SsapUris.VOLUME_DOWN, SsapMessages.empty(), "lower the volume");
            case VOLUME_MUTE -> call(SsapUris.SET_MUTE, SsapMessages.empty().put("mute", !state.muted()), "mute");
            case PLAY_PAUSE -> button(nextPlayPauseIsPlay.getAndSet(!nextPlayPauseIsPlay.get()) ? "PLAY" : "PAUSE");
            default -> button(WebOsKeys.button(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key + " button")));
        }
    }

    private void button(String name) {
        SsapConnection current = requireConnected();
        try {
            current.button(name);
        } catch (SsapException e) {
            throw new ActionFailedException(device.name() + " refused the " + name + " button: " + e.getMessage());
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection");
        }
    }

    private void call(String uri, ObjectNode payload, String what) {
        SsapConnection current = requireConnected();
        try {
            current.request(uri, payload);
        } catch (SsapException e) {
            throw new ActionFailedException(device.name() + " could not " + what + ": " + e.getMessage());
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection");
        }
    }

    private void togglePower() {
        SsapConnection current = connection;
        if (current != null && state.powerOn()) {
            try {
                current.fire(SsapUris.TURN_OFF, SsapMessages.empty());
            } catch (IOException e) {
                throw new DeviceOfflineException(device.name() + " dropped the connection");
            }
            update(s -> s.withPower(false));
            return;
        }
        String mac = current().adapterSettings(WebOsSettings.ADAPTER_ID).get(WakeOnLanAdapter.MAC_ADDRESS);
        if (mac == null || mac.isBlank()) {
            throw new DeviceOfflineException(device.name() + " is off and no MAC address is known for Wake-on-LAN;"
                    + " switch it on once by hand or enter its MAC address on the setup page");
        }
        try {
            wakeOnLan.wake(mac);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceOfflineException("Could not send the Wake-on-LAN packet: " + e.getMessage());
        }
        onScheduler(() -> {
            cancelPendingConnect();
            backoff = initialBackoff();
            pendingConnect = scheduler.schedule(this::connect, properties.wakeGraceSeconds(), TimeUnit.SECONDS);
        });
    }

    private void connect() {
        cancelPendingConnect();
        if (closed || connection != null) {
            return;
        }
        String clientKey = WebOsSettings.of(current()).clientKey();
        if (clientKey == null) {
            update(ignored -> DeviceState.unpaired());
            return;
        }
        update(s -> s.withStatus(DeviceStatus.CONNECTING));
        AtomicReference<SsapConnection> attempt = new AtomicReference<>();
        SsapConnection opened = null;
        try {
            opened = SsapConnection.open(http, device.host(), properties,
                    reason -> onScheduler(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            String key = opened.register(clientKey, Duration.ofSeconds(properties.requestTimeoutSeconds()));
            connection = opened;
            backoff = initialBackoff();
            if (!key.equals(clientKey)) {
                persist(Map.of(WebOsSettings.CLIENT_KEY, key));
            }
            update(s -> s.withStatus(DeviceStatus.CONNECTED).withPower(true));
            subscribeToState(opened);
            loadInputs(opened);
            learnMacAddress(opened);
        } catch (SsapPairingException e) {
            closeQuietly(opened);
            log.warn("{} no longer accepts this server ({}); pair it again on the setup page", device.name(), e.getMessage());
            update(ignored -> DeviceState.unpaired());
        } catch (IOException e) {
            closeQuietly(opened);
            log.debug("{} is not reachable: {}", device.name(), e.getMessage());
            update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            scheduleReconnect();
        }
    }

    private void subscribeToState(SsapConnection opened) {
        subscribe(opened, SsapUris.FOREGROUND_APP, payload -> {
            String appId = payload.path("appId").asString("");
            update(s -> s.withCurrentApp(appId.isEmpty() ? null : appId));
        });
        subscribe(opened, SsapUris.GET_VOLUME, payload -> update(s -> WebOsPayloads.volume(s, payload)));
        subscribe(opened, SsapUris.POWER_STATE, payload -> {
            String power = payload.path("state").asString("");
            if (!power.isEmpty()) {
                update(s -> s.withPower(!STANDBY_STATES.contains(power)));
            }
        });
    }

    private void subscribe(SsapConnection opened, String uri, Consumer<JsonNode> onPayload) {
        try {
            opened.subscribe(uri, onPayload);
        } catch (IOException e) {
            // Older firmware lacks some services (e.g. tvpower); everything else still works.
            log.debug("{} does not offer {}: {}", device.name(), uri, e.getMessage());
        }
    }

    private void loadInputs(SsapConnection opened) {
        try {
            inputs = WebOsPayloads.inputs(opened.request(SsapUris.EXTERNAL_INPUTS, SsapMessages.empty()));
        } catch (IOException e) {
            log.debug("{} did not list its inputs: {}", device.name(), e.getMessage());
            inputs = List.of();
        }
    }

    private void learnMacAddress(SsapConnection opened) {
        try {
            WebOsPayloads.macAddress(opened.request(SsapUris.CONNECTION_INFO, SsapMessages.empty()), device.host())
                    .ifPresent(mac -> {
                        WebOsSettings settings = WebOsSettings.of(current());
                        if (!settings.macAddressManual() && !mac.equals(settings.macAddress())) {
                            persist(Map.of(WakeOnLanAdapter.MAC_ADDRESS, mac));
                        }
                    });
        } catch (IOException e) {
            log.debug("{} did not report its MAC address: {}", device.name(), e.getMessage());
        }
    }

    private void lost(SsapConnection which, String reason) {
        if (which == null || which != connection) {
            return;
        }
        connection = null;
        which.close();
        inputs = List.of();
        log.info("Lost the connection to {} ({}); reconnecting", device.name(), reason);
        update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(Math.max(1, backoff.toSeconds() * 2), properties.reconnectMaxDelaySeconds()));
        try {
            pendingConnect = scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    private void cancelPendingConnect() {
        if (pendingConnect != null) {
            pendingConnect.cancel(false);
            pendingConnect = null;
        }
    }

    private SsapConnection requireConnected() {
        SsapConnection current = connection;
        if (current != null) {
            return current;
        }
        if (state.status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw new DeviceOfflineException(device.name() + " is not connected");
    }

    /** The registry's copy: settings (MAC, key) may have changed since this handle was created. */
    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    private void persist(Map<String, String> updates) {
        registry.findById(device.id()).ifPresent(stored -> {
            Map<String, String> settings = new LinkedHashMap<>(stored.adapterSettings(WebOsSettings.ADAPTER_ID));
            settings.putAll(updates);
            registry.save(stored.withAdapter(WebOsSettings.ADAPTER_ID, settings));
        });
    }

    /** Publishes only visible changes; entering CONNECTING is always published so every attempt shows. */
    private synchronized void update(UnaryOperator<DeviceState> change) {
        DeviceState next = change.apply(state);
        if (next.sameIgnoringTime(state) && next.status() != DeviceStatus.CONNECTING) {
            return;
        }
        state = next;
        onChange.accept(next);
    }

    private void onScheduler(Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(() -> {
                if (!closed) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    private Duration initialBackoff() {
        return Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
    }

    private static void closeQuietly(SsapConnection opened) {
        if (opened != null) {
            opened.close();
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        SsapConnection current = connection;
        connection = null;
        closeQuietly(current);
        onClose.run();
    }
}
```

`PLAY_PAUSE`: the flag starts false, so the first press sends `PAUSE` and flips the flag; the next sends `PLAY`.

- [ ] **Step 24: Run the session tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsSessionTest'`
Expected: PASS.

- [ ] **Step 25: Write the failing adapter and pairing tests**

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsAdapterTest.java` — the adapter gets a `SsdpDiscovery(new SsdpProperties(false, "127.0.0.1", 1900, 0, 60, 2))` (not started) unless stated. Test cases:

- `declaresKeysPowerVolumeAndAppLinks`: `capabilities(device)` = `{REMOTE_KEYS, POWER, VOLUME, APP_LINK}`; the adapter is a `WakeOnLanAdapter`; `id()` = `webos`; `kind()` = `WEBOS`; `settingsFor(anything)` is empty (TVs must be paired, never "added").
- `connectReturnsAStartedSession`: against a `FakeSsapServer` → the handle reaches `CONNECTED`; after `handle.close()`, `tv.dropConnections()` causes no new connection within 2 s.
- `listsLgTvsTheSharedListenerFound`: a started discovery (`new SsdpProperties(true, "127.0.0.1", responder.port(), 0, 1, 1)`) with a `FakeSsdpResponder` answering the LG target from `lg-search-response.txt` and a JDK `HttpServer` serving `lg-description.xml` → `discovered()` eventually equals `[DiscoveredDevice("webos", "[LG] webOS TV OLED55C9PLA", "127.0.0.1", 3000)]`. With the description server stopped, the name still comes from the URL-decoded `DLNADeviceName.lge.com` header (the same string).
- `anSsdpAnnouncementFromItsTvReconnectsAtOnce`: started discovery; properties with `reconnectInitialDelaySeconds` 30; `tv.refuseConnections(true)` before connecting; await `DISCONNECTED`; `tv.refuseConnections(false)`; send an alive NOTIFY for `urn:lge-com:service:webos-second-screen:1` with `LOCATION: http://127.0.0.1:1/x.xml` to `127.0.0.1:ssdp.listenPort()` → `CONNECTED` within 5 s.

`src/test/java/dev/andre/homecontrol/adapters/webos/WebOsPairingTest.java` — `DeviceManager devices = mock(DeviceManager.class)`; `when(devices.attach(any(), any(), any(), any(), any()))` answers with a `Device` built from the arguments; discovery not started. Test cases:

- `anAcceptedPromptStoresTheClientKeyThroughTheDeviceManager`: prompt `ACCEPT`, `pair("127.0.0.1", "Living Room TV")` → `Paired`; `verify(devices).attach("127.0.0.1", "Living Room TV", DeviceKind.WEBOS, "webos", Map.of("clientKey", FakeSsapServer.CLIENT_KEY))`.
- `withoutANameItUsesTheModelName`: `pair("127.0.0.1", " ")` → `attach` called with name `LG OLED55C9PLA`.
- `aDeclinedPromptIsDeclined`: prompt `DECLINE` → `Declined` whose reason contains `declined`; `attach` never called.
- `anUnansweredPromptFailsAfterTheTimeout`: prompt `IGNORE`, `pairingTimeoutSeconds` 1 → `Failed` whose reason contains `within 1 seconds`.
- `anUnreachableHostFails`: both ports closed → `Failed` whose reason contains `Could not reach an LG webOS TV at 127.0.0.1`.
- `describesItselfForTheSetupPage`: `adapterId()` `webos`, `displayName()` `LG webOS TV`, `instructions()` contains `60 seconds` with pairing timeout 60.

- [ ] **Step 26: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.WebOsAdapterTest' --tests 'dev.andre.homecontrol.adapters.webos.WebOsPairingTest'`
Expected: compilation failure.

- [ ] **Step 27: Write the adapter, the pairing and the module configuration**

`adapters/webos/WebOsAdapter.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpService;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** LG webOS TVs over SSAP (spec §4.1). The client key lives in the device's adapter settings. */
public class WebOsAdapter implements WakeOnLanAdapter {

    public static final String ID = WebOsSettings.ADAPTER_ID;
    public static final String SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1";

    private final WebOsProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final HttpClient http;
    private final Map<String, WebOsSession> sessions = new ConcurrentHashMap<>();

    public WebOsAdapter(WebOsProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry, WakeOnLan wakeOnLan) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.http = InsecureTls.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        // A TV that just woke announces itself: reconnect now instead of waiting out the backoff.
        // Plain string comparison: SSDP listeners must not block on DNS.
        ssdp.addListener(SEARCH_TARGET, service -> sessions.values().stream()
                .filter(session -> session.host().equalsIgnoreCase(service.address()))
                .forEach(WebOsSession::reconnectNow));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.WEBOS;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        AtomicReference<WebOsSession> self = new AtomicReference<>();
        WebOsSession session = new WebOsSession(device, properties, http, registry, wakeOnLan, onChange,
                () -> sessions.remove(device.id(), self.get()));
        self.set(session);
        sessions.put(device.id(), session);
        session.start();
        return session;
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return ssdp.services(SEARCH_TARGET).stream()
                .map(service -> new DiscoveredDevice(ID, name(service), service.address(), properties.port()))
                .toList();
    }

    static String name(SsdpService service) {
        return service.friendlyName()
                .or(() -> Optional.ofNullable(service.headers().get("DLNADeviceName.lge.com"))
                        .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8)))
                .orElse("LG webOS TV");
    }
}
```

`adapters/webos/WebOsPairing.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.device.DeviceManager;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * "Accept the request on your TV": registers without a key and stores the key the TV hands out.
 * The MAC address is not collected here; the session learns it right after connecting.
 */
public class WebOsPairing implements PromptPairing {

    private final WebOsProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceManager devices;
    private final HttpClient http;

    public WebOsPairing(WebOsProperties properties, SsdpDiscovery ssdp, DeviceManager devices) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.devices = devices;
        this.http = InsecureTls.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
    }

    @Override
    public String adapterId() {
        return WebOsAdapter.ID;
    }

    @Override
    public String displayName() {
        return "LG webOS TV";
    }

    @Override
    public String instructions() {
        return "The TV asks whether to allow Home Control. Accept with the TV remote within "
                + properties.pairingTimeoutSeconds() + " seconds.";
    }

    @Override
    public PromptPairingResult pair(String host, String name) {
        SsapConnection connection = null;
        try {
            connection = SsapConnection.open(http, host, properties, reason -> { });
            String key = connection.register(null, Duration.ofSeconds(properties.pairingTimeoutSeconds()));
            Device device = devices.attach(host, deviceName(connection, host, name), DeviceKind.WEBOS,
                    WebOsAdapter.ID, Map.of(WebOsSettings.CLIENT_KEY, key));
            return new PromptPairingResult.Paired(device);
        } catch (SsapPairingException e) {
            return switch (e.reason()) {
                case DECLINED -> new PromptPairingResult.Declined(
                        "The TV declined the pairing request (" + e.getMessage() + ")");
                case TIMED_OUT, KEY_REJECTED -> new PromptPairingResult.Failed(e.getMessage() + "; try again");
            };
        } catch (IOException e) {
            return new PromptPairingResult.Failed("Could not reach an LG webOS TV at " + host + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /** The user's name, else the SSDP name for that host, else "LG " + model, else a generic name. */
    private String deviceName(SsapConnection connection, String host, String name) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return ssdp.services(WebOsAdapter.SEARCH_TARGET).stream()
                .filter(service -> service.address().equalsIgnoreCase(host))
                .map(WebOsAdapter::name)
                .findFirst()
                .orElseGet(() -> {
                    try {
                        String model = connection.request(SsapUris.SYSTEM_INFO, SsapMessages.empty())
                                .path("modelName").asString("");
                        return model.isEmpty() ? "LG webOS TV" : "LG " + model;
                    } catch (IOException e) {
                        return "LG webOS TV";
                    }
                });
    }
}
```

`adapters/webos/WebOsConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The LG webOS module; {@code home-control.webos.enabled=false} removes it entirely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.webos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebOsProperties.class)
public class WebOsConfiguration {

    @Bean
    public WebOsAdapter webOsAdapter(WebOsProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry,
                                     WakeOnLan wakeOnLan) {
        return new WebOsAdapter(properties, ssdp, registry, wakeOnLan);
    }

    @Bean
    public WebOsPairing webOsPairing(WebOsProperties properties, SsdpDiscovery ssdp, DeviceManager devices) {
        return new WebOsPairing(properties, ssdp, devices);
    }
}
```

If `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` does not resolve in Boot 4.1.1, run `unzip -l "$(find ~/.cache/hc-gradle -name 'spring-boot-autoconfigure-4.1.1.jar' | head -1)" | grep ConditionalOnProperty` and import from the package it shows.

`src/main/resources/application.yaml` — under the existing `home-control:` key add:

```yaml
  webos:
    enabled: true
    port: 3000
    secure-port: 3001
    pairing-timeout-seconds: 60
    reconnect-max-delay-seconds: 30
  wake-on-lan:
    # Use the subnet broadcast (e.g. 192.168.1.255) if the host has several networks.
    broadcast-address: 255.255.255.255
    port: 9
```

- [ ] **Step 28: Run the webOS tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*'`
Expected: PASS.

- [ ] **Step 29: Write the failing manager and web tests**

Add to `device/DeviceManagerTest.java` (inside the test class: `static class WakingAdapter extends StubAdapter implements WakeOnLanAdapter` delegating to `StubAdapter(id, DeviceKind.WEBOS, false, false, REMOTE_KEYS, POWER)`; `alpha` is a plain `StubAdapter("alpha", DeviceKind.CAST, false, false, VOLUME)`):

- `onlyDevicesWithAWakeOnLanAdapterWakeOnLan`: device `tv` with adapters `{alpha, waking}`, device `box` with `{alpha}` → `wakesOnLan("tv")` true, `wakesOnLan("box")` false, `wakesOnLan("nope")` false.
- `aHandEnteredMacIsNormalisedAndMarkedManualOnEveryWakingAdapter`: `setWakeOnLanMac("tv", "a8-23-fe-01-02-03")` → registry `adapterSettings("waking")` has `macAddress=A8:23:FE:01:02:03` and `macAddressManual=true`; `adapterSettings("alpha")` unchanged; `wakeOnLanMac("tv")` = `A8:23:FE:01:02:03`; `alpha.handles.get("tv")` is the same handle as before (no reconnect).
- `aBlankMacClearsItSoItIsLearnedAgain`: then `setWakeOnLanMac("tv", " ")` → both keys gone; `wakeOnLanMac("tv")` empty.
- `anInvalidMacIsRejected`: `setWakeOnLanMac("tv", "nope")` → `IllegalArgumentException`; the registry file is unchanged.
- `inputsComeFromTheFirstHandleThatListsThem`: an inline adapter whose handle implements `InputListing` returning `[TvInput("HDMI_1","HDMI 1")]` → `inputs("tv")` equals it; a device whose handles do not list inputs → empty list.

Add to `web/DeviceControllerTest.java`:

- `switchesTheInputOfTheAddressedDevice`: `POST /devices/shield/input/HDMI_2` → 204; `verify(devices).execute("shield", new Action.SelectInput("HDMI_2"))`.
- `anInputOfAnUnknownDeviceIsNotFound`: `POST /devices/ghost/input/HDMI_1` → 404.

Add to `web/DashboardPageTest.java`:

- `showsTheInputsTheTvListed`: stub `devices.inputs("living")` → `[TvInput("HDMI_1","HDMI 1"), TvInput("HDMI_2","PlayStation")]` → page contains `/devices/living/input/HDMI_2` and `PlayStation`.
- `showsNoInputRowWithoutInputs`: `inputs` empty → page does not contain `/input/`.

`src/test/java/dev/andre/homecontrol/web/PromptPairingSetupTest.java` — `@WebMvcTest(SetupController.class)` with `@Import(PromptPairingSetupTest.StubPairingConfiguration.class)`, whose `@Bean StubPairing` implements `PromptPairing` (adapter `webos`, display name `LG webOS TV`, instructions `Accept the request on the TV.`, a settable `PromptPairingResult next`, recorded `host` and `name`); `@MockitoBean DeviceManager devices`; `@MockitoBean` for every other constructor dependency of `SetupController` (the Android TV pairing service); default stubs `devices.devices()`, `devices.pairable()` and `devices.addable()` → empty lists (B's setup model reads `pairable()` for the discovered list). Test cases:

- `aDiscoveredTvGetsAPromptPairingForm`: `pairable()` → `DiscoveredDevice("webos", "[LG] webOS TV", "192.168.1.60", 3000)` → page contains `action="/setup/prompt-pair"`, `name="adapter" value="webos"` (attribute order as rendered), and `Accept the request on the TV.`.
- `anAndroidTvDiscoveryKeepsTheCodePairingForm`: `DiscoveredDevice("androidtv", "Shield", "192.168.1.50", 6466)` → its form posts to `/setup/pair`.
- `manualEntryOffersEveryPromptPairingAdapter`: page contains `value="webos"` inside a `<select name="adapter"` and the text `LG webOS TV`.
- `pairingRedirectsToTheNewDevice`: `next = Paired(new Device("tv", "LG", DeviceKind.WEBOS, "192.168.1.60", Map.of("webos", Map.of()), Instant.now()))`; `POST /setup/prompt-pair` with `adapter=webos`, `host=192.168.1.60`, `name=LG` → redirect `/?device=tv`; the stub recorded `192.168.1.60` and `LG`.
- `aDeclinedPairingShowsTheReason`: `Declined("The TV declined the pairing request")` → 200 containing the reason.
- `aFailedPairingShowsTheReason`: `Failed("Could not reach an LG webOS TV at 10.0.0.9: refused")` → 200 containing the reason.
- `anUnknownAdapterIsExplained`: `adapter=nope` → 200 containing `Unknown device type nope`.
- `theMacFieldAppearsOnlyForDevicesThatWakeOnLan`: `devices()` → `tv` and `box`; `wakesOnLan("tv")` true, `wakeOnLanMac("tv")` → `A8:23:FE:01:02:03`; `wakesOnLan("box")` false → page contains `/setup/devices/tv/mac` and `A8:23:FE:01:02:03`, not `/setup/devices/box/mac`.
- `savesAHandEnteredMac`: `device("tv")` present; `POST /setup/devices/tv/mac` with `mac=a8-23-fe-01-02-03` → redirect `/setup`; `verify(devices).setWakeOnLanMac("tv", "a8-23-fe-01-02-03")`.
- `anInvalidMacShowsTheReason`: `setWakeOnLanMac` throws `IllegalArgumentException("Not a MAC address: nope")` → 200 containing `Not a MAC address: nope`.
- `theMacOfAnUnknownDeviceIsNotFound`: `device("ghost")` empty → 404.

- [ ] **Step 30: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.device.DeviceManagerTest' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure or FAIL — the manager methods, endpoints and template sections do not exist.

- [ ] **Step 31: Implement the manager methods**

In `device/DeviceManager.java` add:

```java
    /** True when one of the device's adapters can switch it on with Wake-on-LAN. */
    public boolean wakesOnLan(String id) {
        return registry.findById(id)
                .map(device -> device.adapters().keySet().stream()
                        .anyMatch(adapterId -> adapters.get(adapterId) instanceof WakeOnLanAdapter))
                .orElse(false);
    }

    public Optional<String> wakeOnLanMac(String id) {
        return registry.findById(id).flatMap(device -> device.adapters().keySet().stream()
                .filter(adapterId -> adapters.get(adapterId) instanceof WakeOnLanAdapter)
                .map(adapterId -> device.adapterSettings(adapterId).get(WakeOnLanAdapter.MAC_ADDRESS))
                .filter(mac -> mac != null && !mac.isBlank())
                .findFirst());
    }

    /**
     * Stores a hand-entered MAC on every Wake-on-LAN adapter of the device and stops adapters from
     * replacing it; blank clears it so they learn it again. No reconnect: handles read the MAC from
     * the registry when they wake the device.
     */
    public synchronized void setWakeOnLanMac(String id, String mac) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + id));
        boolean clear = mac == null || mac.isBlank();
        String normalized = clear ? null : MacAddress.normalize(mac);
        Device updated = device;
        for (String adapterId : device.adapters().keySet()) {
            if (adapters.get(adapterId) instanceof WakeOnLanAdapter) {
                Map<String, String> settings = new LinkedHashMap<>(updated.adapterSettings(adapterId));
                if (clear) {
                    settings.remove(WakeOnLanAdapter.MAC_ADDRESS);
                    settings.remove(WakeOnLanAdapter.MAC_ADDRESS_MANUAL);
                } else {
                    settings.put(WakeOnLanAdapter.MAC_ADDRESS, normalized);
                    settings.put(WakeOnLanAdapter.MAC_ADDRESS_MANUAL, "true");
                }
                updated = updated.withAdapter(adapterId, settings);
            }
        }
        registry.save(updated);
    }

    /** Inputs from the first of the device's handles that lists any; empty when none does. */
    public List<TvInput> inputs(String id) {
        return handles.getOrDefault(id, Map.of()).values().stream()
                .filter(InputListing.class::isInstance)
                .map(handle -> ((InputListing) handle).inputs())
                .filter(list -> !list.isEmpty())
                .findFirst()
                .orElse(List.of());
    }
```

- [ ] **Step 32: Implement the web changes**

`web/DeviceController.java` — add:

```java
    @PostMapping("/devices/{id}/input/{inputId}")
    public ResponseEntity<String> input(@PathVariable String id, @PathVariable String inputId) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        devices.execute(id, new Action.SelectInput(inputId));
        return ResponseEntity.noContent().build();
    }
```

`web/DashboardController.dashboard` — add `model.addAttribute("inputs", devices.inputs(device.id()));`.

`templates/dashboard.html` — after the volume row inside `<main>`:

```html
    <section class="row inputs" th:if="${!#lists.isEmpty(inputs)}" aria-label="Inputs">
        <button th:each="input : ${inputs}" th:text="${input.label()}"
                th:attr="hx-post=@{/devices/{id}/input/{input}(id=${id},input=${input.id()})}">HDMI 1</button>
    </section>
```

`web/SetupController.java` — add a constructor parameter `List<PromptPairing> promptPairings` (Spring injects an empty list when no module is enabled) and these handlers:

```java
    @PostMapping("/setup/prompt-pair")
    public String promptPair(@RequestParam String adapter, @RequestParam String host,
                             @RequestParam(required = false) String name, Model model) {
        Optional<PromptPairing> pairing = promptPairings.stream()
                .filter(candidate -> candidate.adapterId().equals(adapter)).findFirst();
        if (pairing.isEmpty()) {
            model.addAttribute("error", "Unknown device type " + adapter);
            populateSetupModel(model, false);
            return "setup";
        }
        switch (pairing.get().pair(host.trim(), name)) {
            case PromptPairingResult.Paired paired -> {
                return "redirect:/?device=" + UriUtils.encodeQueryParam(paired.device().id(), StandardCharsets.UTF_8);
            }
            case PromptPairingResult.Declined declined -> model.addAttribute("error", declined.reason());
            case PromptPairingResult.Failed failed -> model.addAttribute("error", failed.reason());
        }
        populateSetupModel(model, false);
        return "setup";
    }

    @PostMapping("/setup/devices/{id}/mac")
    public String wakeOnLanMac(@PathVariable String id, @RequestParam(required = false) String mac, Model model) {
        if (devices.device(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        try {
            devices.setWakeOnLanMac(id, mac);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            populateSetupModel(model, false);
            return "setup";
        }
        return "redirect:/setup";
    }
```

and in `populateSetupModel`:

```java
        model.addAttribute("promptPairings", promptPairings);
        model.addAttribute("promptAdapterIds", promptPairings.stream()
                .map(PromptPairing::adapterId).collect(Collectors.toSet()));
        model.addAttribute("promptInstructions", promptPairings.stream()
                .collect(Collectors.toMap(PromptPairing::adapterId, PromptPairing::instructions)));
        Map<String, String> wakeMacs = new LinkedHashMap<>();
        devices.devices().stream().filter(device -> devices.wakesOnLan(device.id()))
                .forEach(device -> wakeMacs.put(device.id(), devices.wakeOnLanMac(device.id()).orElse("")));
        model.addAttribute("wakeMacs", wakeMacs);
```

(`UriUtils` is `org.springframework.web.util.UriUtils`; `ResponseStatusException` is `org.springframework.web.server.ResponseStatusException`. Use the field name the real controller has for `DeviceManager`.)

`templates/setup.html` — three changes:

1. The discovered list's per-device form becomes:

```html
            <li th:each="device : ${discovered}">
                <form method="post"
                      th:action="${promptAdapterIds.contains(device.adapterId())} ? @{/setup/prompt-pair} : @{/setup/pair}">
                    <input type="hidden" name="adapter" th:value="${device.adapterId()}">
                    <input type="hidden" name="host" th:value="${device.host()}">
                    <input type="hidden" name="name" th:value="${device.name()}">
                    <span th:text="${device.name()} + ' (' + ${device.host()} + ')'">Shield</span>
                    <button type="submit">Pair</button>
                    <p class="hint" th:if="${promptAdapterIds.contains(device.adapterId())}"
                       th:text="${promptInstructions[device.adapterId()]}">Accept the request on the TV.</p>
                </form>
            </li>
```

2. After the Android TV "Or enter an address" form:

```html
        <th:block th:if="${!#lists.isEmpty(promptPairings)}">
            <h2>Add a smart TV by address</h2>
            <form method="post" th:action="@{/setup/prompt-pair}">
                <select name="adapter" aria-label="TV type">
                    <option th:each="pairing : ${promptPairings}" th:value="${pairing.adapterId()}"
                            th:text="${pairing.displayName()}">LG webOS TV</option>
                </select>
                <input name="host" placeholder="192.168.1.60" required>
                <input name="name" placeholder="Living Room TV">
                <button type="submit">Pair</button>
            </form>
            <p class="hint">The TV shows a request on screen. This page waits until you answer it.</p>
        </th:block>
```

3. Inside each paired-device list item (sub-project A's list), after the forget form:

```html
                <form method="post" th:if="${wakeMacs.containsKey(device.id())}"
                      th:action="@{/setup/devices/{id}/mac(id=${device.id()})}">
                    <label>Wake-on-LAN MAC
                        <input name="mac" th:value="${wakeMacs[device.id()]}" placeholder="A8:23:FE:01:02:03">
                    </label>
                    <button type="submit">Save</button>
                    <p class="hint">Learned automatically while the TV is on. Switching on over the network
                        also needs the TV's own "turn on via network" setting.</p>
                </form>
```

- [ ] **Step 33: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.adapters.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. `HomeControlApplicationTest` (full context) starts with the webOS module enabled and SSDP disabled.

- [ ] **Step 34: Commit**

```bash
git add -A
git commit -m "feat: LG webOS adapter with prompt pairing, keys, volume, inputs, Wake-on-LAN and app links"
```

---
### Task 3: F3 · Samsung Tizen adapter

**Files:**
- Create: `adapters/tizen/TizenProperties.java`, `TizenSettings.java`, `TizenApp.java`, `TizenMessages.java`, `TizenRemoteConnection.java`, `TizenDeviceInfo.java`, `TizenRest.java`, `DialClient.java`, `DialException.java`, `TizenLaunch.java`, `TizenLaunches.java`, `TizenKeys.java`, `TizenSession.java`, `TizenPairing.java`, `TizenAdapter.java`, `TizenConfiguration.java`
- Modify: `src/main/resources/application.yaml`
- Test: `adapters/tizen/FakeTizenServer.java`, `TizenMessagesTest.java`, `TizenRestTest.java`, `DialClientTest.java`, `TizenLaunchesTest.java`, `TizenKeysTest.java`, `TizenRemoteConnectionTest.java`, `TizenSessionTest.java`, `TizenPairingTest.java`, `TizenAdapterTest.java`; `web/SmartTvModulesOffTest.java`; `HomeControlApplicationTest.java`; fixtures `src/test/resources/fixtures/tizen/device-info.json`, `installed-apps.json`

**Interfaces:**
- Consumes: Task 1 (`SsdpDiscovery`, `SsdpProperties`, `FakeSsdpResponder`, `samsung-search-response.txt`, `samsung-description.xml`, `DeviceManager.attach`); Task 2 (`TextWebSocket`, `InsecureTls`, `WakeOnLan`, `FakeWebSocketServer`, `FakeWakeOnLanReceiver`, `ContentLinks`, `core.PromptPairing`, `core.PromptPairingResult`, `core.WakeOnLanAdapter`, `core.MacAddress`, `DeviceState.sameIgnoringTime`, `AppLinks.serviceOf`, the generic prompt-pairing setup page); sub-projects A/B (`DeviceAdapter` with `kind()`, `DeviceHandle`, `DeviceRegistry`, `Action` incl. `SelectInput/SetVolume/Mute/Stop/CastLoad`, `ActionFailedException`, `UnsupportedActionException`, `DeviceOfflineException`, `RemoteKey`).
- Produces:
  - `record TizenProperties(boolean enabled, int port, int restPort, int dialPort, String clientName, int connectTimeoutSeconds, int requestTimeoutSeconds, int pairingTimeoutSeconds, int pollIntervalSeconds, int wakeGraceSeconds)` bound to `home-control.tizen`.
  - `record TizenSettings(String token, boolean paired, String macAddress, boolean macAddressManual)` with `ADAPTER_ID = "tizen"`, keys `token`, `paired`, `of(Device)`.
  - `TizenMessages.remoteUri(String host, int port, String clientName, String token) → URI`, `key(String code)`, `launchApp(String appId, String actionType)`, `installedAppsRequest()` → JSON strings, `installedApps(JsonNode event) → List<TizenApp>`; `record TizenApp(String appId, String name, int appType)`.
  - `TizenRemoteConnection.open(HttpClient, String host, TizenProperties, String token, Consumer<String> onClosed)` with `Authorization awaitAuthorization(Duration)` (`CONNECTED`, `UNAUTHORIZED`, `NO_ANSWER`), `Optional<String> token()`, `key(String)`, `launchApp(String, String)`, `requestInstalledApps()`, `Optional<List<TizenApp>> installedApps()`, `close()`.
  - `TizenRest(HttpClient, TizenProperties)` with `Optional<TizenDeviceInfo> deviceInfo(String host)`, `Optional<Boolean> appVisible(String host, String appId)`; `record TizenDeviceInfo(String name, String modelName, String powerState, String wifiMac, boolean tokenAuthSupport)` with `boolean on()`, `Optional<String> macAddress()`.
  - `DialClient(HttpClient, TizenProperties)` with `void launch(String host, String app, String body) throws IOException` (throws `DialException` when the TV refuses).
  - `sealed interface TizenLaunch` with `Dial(String app, String body)`, `App(String appId, String name, String actionType)`, `Unsupported(String reason)`; `TizenLaunches.forUri(URI, Optional<List<TizenApp>>) → TizenLaunch`, `TizenLaunches.knownApps(Optional<List<TizenApp>>) → List<TizenLaunch.App>`.
  - `TizenAdapter implements WakeOnLanAdapter` (id `tizen`, kind `TIZEN`, capabilities `REMOTE_KEYS, POWER, VOLUME, APP_LINK`, SSDP target `urn:samsung.com:device:RemoteControlReceiver:1`); `TizenSession implements DeviceHandle`; `TizenPairing implements PromptPairing`.
  - Property `home-control.tizen.enabled` (default true).

Wire formats this task implements (xchwarze/samsung-tv-ws-api):

```text
WebSocket   wss://<host>:8002/api/v2/channels/samsung.remote.control?name=<urlencoded base64(clientName)>[&token=<token>]
TV → us     {"event":"ms.channel.connect","data":{"clients":[…],"id":"…","token":"73184052"}}   (token only when newly issued)
TV → us     {"event":"ms.channel.unauthorized"}                                                (the user pressed Deny)
TV → us     {"event":"ed.edenTV.update",…} / {"event":"ms.voiceApp.hide",…}                     (noise before connect; ignore)
key         {"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_HOME","Option":"false","TypeOfRemote":"SendRemoteKey"}}
launch      {"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host","data":{"action_type":"DEEP_LINK","appId":"3201907018807","metaTag":""}}}
apps        {"method":"ms.channel.emit","params":{"event":"ed.installedApp.get","to":"host"}}
            → {"event":"ed.installedApp.get","from":"host","data":{"data":[{"appId":"…","app_type":2,"name":"YouTube",…}]}}
REST        GET http://<host>:8001/api/v2/                         → {"device":{"PowerState":"on","wifiMac":"…","name":"…","modelName":"…","TokenAuthSupport":"true",…},…}
REST        GET http://<host>:8001/api/v2/applications/<appId>     → {"id":"…","name":"YouTube","running":true,"visible":true,"version":"…"}
DIAL        POST http://<host>:8080/ws/apps/YouTube   Content-Type: text/plain; charset=utf-8   body: v=<videoId>   → 201 Created
```

- [ ] **Step 1: Add the Tizen fixtures**

`src/test/resources/fixtures/tizen/device-info.json`:

```json
{"device":{"FrameTVSupport":"false","GamePadSupport":"true","ImeSyncedSupport":"true","Language":"de_DE","OS":"Tizen","PowerState":"on","TokenAuthSupport":"true","VoiceSupport":"true","WallScreenRatio":"0","WallService":"false","countryCode":"DE","description":"Samsung DTV RCR","developerIP":"0.0.0.0","developerMode":"0","duid":"uuid:5d9c0b1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b","firmwareVersion":"Unknown","id":"uuid:5d9c0b1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b","ip":"127.0.0.1","model":"20_NIKEM_UHD","modelName":"GU55TU8079UXZG","name":"[TV] Samsung 8 Series (55)","networkType":"wired","resolution":"3840x2160","smartHubAgreement":"true","type":"Samsung SmartTV","udn":"uuid:5d9c0b1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b","wifiMac":"70:2A:D5:01:02:03"},"id":"uuid:5d9c0b1a-2c3d-4e5f-8a9b-0c1d2e3f4a5b","isSupport":"{\"DMP_DRM_PLAYREADY\":\"false\",\"DMP_DRM_WIDEVINE\":\"false\",\"DMP_available\":\"true\",\"EDEN_available\":\"true\",\"FrameTVSupport\":\"false\",\"ImeSyncedSupport\":\"true\",\"TokenAuthSupport\":\"true\",\"remote_available\":\"true\",\"remote_fourDirections\":\"true\",\"remote_touchPad\":\"true\",\"remote_voiceControl\":\"true\"}\n","name":"[TV] Samsung 8 Series (55)","remote":"1.0","type":"Samsung SmartTV","uri":"http://127.0.0.1:8001/api/v2/","version":"2.0.25"}
```

`src/test/resources/fixtures/tizen/installed-apps.json`:

```json
{"data":{"data":[
 {"appId":"111299001912","app_type":2,"icon":"/opt/share/webappservice/apps_icon/FirstScreen/111299001912/250x250.png","is_lock":0,"name":"YouTube"},
 {"appId":"3201907018807","app_type":2,"icon":"/opt/share/webappservice/apps_icon/FirstScreen/3201907018807/250x250.png","is_lock":0,"name":"Netflix"},
 {"appId":"org.tizen.browser","app_type":4,"icon":"/usr/share/icons/default/small/org.tizen.browser.png","is_lock":0,"name":"Internet"}
]},"event":"ed.installedApp.get","from":"host"}
```

- [ ] **Step 2: Write the failing pure Tizen tests**

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenMessagesTest.java` — exact strings:

- `theRemoteUrlCarriesTheBase64ClientName`: `remoteUri("192.168.1.61", 8002, "Home Control", null)` → `wss://192.168.1.61:8002/api/v2/channels/samsung.remote.control?name=SG9tZSBDb250cm9s`.
- `aStoredTokenIsAppended`: with token `73184052` → `…?name=SG9tZSBDb250cm9s&token=73184052`.
- `base64PaddingIsUrlEncoded`: client name `TV` → `name=VFY%3D`.
- `ipv6HostsAreBracketed`: host `fe80::1` → starts with `wss://[fe80::1]:8002/`.
- `aKeyIsAClick`: `key("KEY_HOME")` → `{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_HOME","Option":"false","TypeOfRemote":"SendRemoteKey"}}`.
- `anAppLaunchIsAChannelEmitToTheHost`: `launchApp("3201907018807", "DEEP_LINK")` → `{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","to":"host","data":{"action_type":"DEEP_LINK","appId":"3201907018807","metaTag":""}}}`.
- `theInstalledAppsRequest`: `installedAppsRequest()` → `{"method":"ms.channel.emit","params":{"event":"ed.installedApp.get","to":"host"}}`.
- `parsesTheInstalledAppsEvent`: `installed-apps.json` → `[TizenApp("111299001912","YouTube",2), TizenApp("3201907018807","Netflix",2), TizenApp("org.tizen.browser","Internet",4)]`.

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenKeysTest.java`:

- `mapsRemoteKeysToSamsungKeyCodes` (parameterized): `DPAD_UP→KEY_UP`, `DPAD_DOWN→KEY_DOWN`, `DPAD_LEFT→KEY_LEFT`, `DPAD_RIGHT→KEY_RIGHT`, `DPAD_CENTER→KEY_ENTER`, `BACK→KEY_RETURN`, `HOME→KEY_HOME`, `MENU→KEY_MENU`, `VOLUME_UP→KEY_VOLUP`, `VOLUME_DOWN→KEY_VOLDOWN`, `VOLUME_MUTE→KEY_MUTE`, `MEDIA_STOP→KEY_STOP`, `REWIND→KEY_REWIND`, `FAST_FORWARD→KEY_FF`, `INFO→KEY_INFO`, `SETTINGS→KEY_TOOLS`, `GUIDE→KEY_GUIDE`.
- `everyRemoteKeyIsDecided`: every `RemoteKey` has a code, or is in `TizenKeys.HANDLED_BY_SESSION` (`POWER`, `PLAY_PAUSE`), or is `MEDIA_NEXT` / `MEDIA_PREVIOUS`.

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenLaunchesTest.java` — `installed` = the fixture parsed with `TizenMessages.installedApps`:

- `aYouTubeVideoGoesThroughDial`: `https://www.youtube.com/watch?v=aqz-KE-bpKQ` and `https://youtu.be/aqz-KE-bpKQ` → `Dial("YouTube", "v=aqz-KE-bpKQ")` (with and without an installed list).
- `youTubeWithoutAVideoOpensTheInstalledApp`: `https://www.youtube.com/` → `App("111299001912", "YouTube", "DEEP_LINK")`.
- `netflixOpensTheAppButNeverTheTitle`: `https://www.netflix.com/title/80057281` → `App("3201907018807", "Netflix", "DEEP_LINK")`; nothing in the result contains `80057281`.
- `anAppThatIsNotInstalledIsUnsupported`: `https://app.primevideo.com/detail?gti=x` with the fixture list → `Unsupported` whose reason is `Prime Video is not installed on this TV`.
- `withoutAnInstalledListTheWellKnownIdsAreUsed`: `Optional.empty()` → Netflix `3201907018807`, Prime Video `3201910019365`, YouTube `111299001912`.
- `nativeAppsLaunchNatively`: an installed `TizenApp("x","Netflix",4)` → action type `NATIVE_LAUNCH`.
- `webLinksAreUnsupported`: `https://example.org/a` and `https://www.dazn.com/` → `Unsupported` containing `cannot open web links`.
- `knownAppsAreTheThreeServicesThatAreInstalled`: fixture → `[App(111299001912,YouTube), App(3201907018807,Netflix)]`; empty optional → all three fallbacks.

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.*'`
Expected: compilation failure.

- [ ] **Step 4: Write the pure Tizen types**

`adapters/tizen/TizenApp.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

/** One entry of {@code ed.installedApp.get}. {@code appType} 2 = web app (DEEP_LINK), 4 = native (NATIVE_LAUNCH). */
record TizenApp(String appId, String name, int appType) {
}
```

`adapters/tizen/TizenMessages.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** The Samsung remote-control WebSocket wire format (samsung-tv-ws-api {@code connection.py}, {@code remote.py}). */
final class TizenMessages {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private TizenMessages() {
    }

    /** The TV shows {@code name} in its Allow prompt and device list; the token proves an earlier Allow. */
    static URI remoteUri(String host, int port, String clientName, String token) {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        String name = Base64.getEncoder().encodeToString(clientName.getBytes(StandardCharsets.UTF_8));
        StringBuilder uri = new StringBuilder("wss://").append(authority).append(':').append(port)
                .append("/api/v2/channels/samsung.remote.control?name=")
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) {
            uri.append("&token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
        }
        return URI.create(uri.toString());
    }

    static String key(String code) {
        ObjectNode message = JSON.createObjectNode();
        message.put("method", "ms.remote.control");
        ObjectNode params = message.putObject("params");
        params.put("Cmd", "Click");
        params.put("DataOfCmd", code);
        params.put("Option", "false");
        params.put("TypeOfRemote", "SendRemoteKey");
        return JSON.writeValueAsString(message);
    }

    static String launchApp(String appId, String actionType) {
        ObjectNode message = JSON.createObjectNode();
        message.put("method", "ms.channel.emit");
        ObjectNode params = message.putObject("params");
        params.put("event", "ed.apps.launch");
        params.put("to", "host");
        ObjectNode data = params.putObject("data");
        data.put("action_type", actionType);
        data.put("appId", appId);
        data.put("metaTag", "");
        return JSON.writeValueAsString(message);
    }

    static String installedAppsRequest() {
        ObjectNode message = JSON.createObjectNode();
        message.put("method", "ms.channel.emit");
        ObjectNode params = message.putObject("params");
        params.put("event", "ed.installedApp.get");
        params.put("to", "host");
        return JSON.writeValueAsString(message);
    }

    static List<TizenApp> installedApps(JsonNode event) {
        List<TizenApp> apps = new ArrayList<>();
        for (JsonNode app : event.path("data").path("data").values()) {
            String appId = app.path("appId").asString("");
            if (!appId.isEmpty()) {
                apps.add(new TizenApp(appId, app.path("name").asString(""), app.path("app_type").asInt(2)));
            }
        }
        return List.copyOf(apps);
    }
}
```

`adapters/tizen/TizenKeys.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.RemoteKey;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Remote keys → Samsung key codes. POWER and PLAY_PAUSE need session state; MEDIA_NEXT/PREVIOUS have no code. */
final class TizenKeys {

    static final Set<RemoteKey> HANDLED_BY_SESSION = Set.of(RemoteKey.POWER, RemoteKey.PLAY_PAUSE);

    private static final Map<RemoteKey, String> CODES = new EnumMap<>(Map.ofEntries(
            Map.entry(RemoteKey.DPAD_UP, "KEY_UP"),
            Map.entry(RemoteKey.DPAD_DOWN, "KEY_DOWN"),
            Map.entry(RemoteKey.DPAD_LEFT, "KEY_LEFT"),
            Map.entry(RemoteKey.DPAD_RIGHT, "KEY_RIGHT"),
            Map.entry(RemoteKey.DPAD_CENTER, "KEY_ENTER"),
            Map.entry(RemoteKey.BACK, "KEY_RETURN"),
            Map.entry(RemoteKey.HOME, "KEY_HOME"),
            Map.entry(RemoteKey.MENU, "KEY_MENU"),
            Map.entry(RemoteKey.VOLUME_UP, "KEY_VOLUP"),
            Map.entry(RemoteKey.VOLUME_DOWN, "KEY_VOLDOWN"),
            Map.entry(RemoteKey.VOLUME_MUTE, "KEY_MUTE"),
            Map.entry(RemoteKey.MEDIA_STOP, "KEY_STOP"),
            Map.entry(RemoteKey.REWIND, "KEY_REWIND"),
            Map.entry(RemoteKey.FAST_FORWARD, "KEY_FF"),
            Map.entry(RemoteKey.INFO, "KEY_INFO"),
            Map.entry(RemoteKey.SETTINGS, "KEY_TOOLS"),
            Map.entry(RemoteKey.GUIDE, "KEY_GUIDE")));

    private TizenKeys() {
    }

    static Optional<String> code(RemoteKey key) {
        return Optional.ofNullable(CODES.get(key));
    }
}
```

`adapters/tizen/TizenLaunch.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

/** What an app link becomes on a Samsung TV. */
sealed interface TizenLaunch {

    /** DIAL: {@code POST /ws/apps/<app>} with {@code body}. */
    record Dial(String app, String body) implements TizenLaunch {
    }

    /** {@code ed.apps.launch} of an installed app, without content. */
    record App(String appId, String name, String actionType) implements TizenLaunch {
    }

    record Unsupported(String reason) implements TizenLaunch {
    }
}
```

`adapters/tizen/TizenLaunches.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.links.ContentLinks;
import dev.andre.homecontrol.core.playback.AppLinks;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * App link → Tizen launch (spec §4.1): YouTube videos through DIAL with {@code v=}; Netflix and
 * Prime Video open their app without the title (no content deep link on most firmware); other
 * links cannot be opened. App ids differ by model year, so the installed list wins over the
 * well-known ids.
 */
final class TizenLaunches {

    static final Map<String, String> WELL_KNOWN_IDS = wellKnownIds();

    private TizenLaunches() {
    }

    static TizenLaunch forUri(URI uri, Optional<List<TizenApp>> installed) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return switch (AppLinks.serviceOf(host, uri.getPath())) {
            case "youtube" -> ContentLinks.youtubeVideoId(uri)
                    .<TizenLaunch>map(id -> new TizenLaunch.Dial("YouTube", "v=" + id))
                    .orElseGet(() -> app("YouTube", installed));
            case "netflix" -> app("Netflix", installed);
            case "primevideo" -> app("Prime Video", installed);
            default -> new TizenLaunch.Unsupported("Samsung TVs cannot open web links; they open YouTube videos"
                    + " and the YouTube, Netflix and Prime Video apps");
        };
    }

    /** The service apps whose visibility tells the session what is in front. */
    static List<TizenLaunch.App> knownApps(Optional<List<TizenApp>> installed) {
        return WELL_KNOWN_IDS.keySet().stream()
                .map(name -> app(name, installed))
                .filter(TizenLaunch.App.class::isInstance)
                .map(TizenLaunch.App.class::cast)
                .toList();
    }

    static TizenLaunch app(String name, Optional<List<TizenApp>> installed) {
        if (installed.isEmpty()) {
            return new TizenLaunch.App(WELL_KNOWN_IDS.get(name), name, "DEEP_LINK");
        }
        return installed.get().stream()
                .filter(app -> app.name().equalsIgnoreCase(name))
                .findFirst()
                .<TizenLaunch>map(app -> new TizenLaunch.App(app.appId(), app.name(),
                        app.appType() == 4 ? "NATIVE_LAUNCH" : "DEEP_LINK"))
                .orElseGet(() -> new TizenLaunch.Unsupported(name + " is not installed on this TV"));
    }

    private static Map<String, String> wellKnownIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("YouTube", "111299001912");
        ids.put("Netflix", "3201907018807");
        ids.put("Prime Video", "3201910019365");
        return ids;
    }
}
```

- [ ] **Step 5: Run the pure tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.TizenMessagesTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenKeysTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenLaunchesTest'`
Expected: PASS.

- [ ] **Step 6: Write the fake Samsung TV and the failing REST/DIAL tests**

`src/test/java/dev/andre/homecontrol/adapters/tizen/FakeTizenServer.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An in-process Samsung TV: the TLS remote-control WebSocket, the REST API and DIAL (REST and DIAL
 * share one HTTP port here; point both {@code restPort} and {@code dialPort} at {@link #httpPort()}).
 */
public class FakeTizenServer implements AutoCloseable {

    public enum Authorization { ALLOW, DENY, IGNORE }

    public static final String TOKEN = "73184052";
    public static final String YOUTUBE = "111299001912";
    public static final String NETFLIX = "3201907018807";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FakeWebSocketServer remote;
    private final HttpServer http;
    private final BlockingQueue<String> keys = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> launches = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> dialBodies = new LinkedBlockingQueue<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> visible = new ConcurrentHashMap<>(Map.of(YOUTUBE, false, NETFLIX, false));
    private volatile Authorization authorization = Authorization.ALLOW;
    private volatile String powerState = "on";
    private volatile boolean restAvailable = true;
    private volatile boolean dialAvailable = true;
    private volatile boolean issueTokens = true;

    public FakeTizenServer() throws IOException {
        remote = FakeWebSocketServer.tls(new FakeWebSocketServer.Handler() {
            @Override
            public void onOpen(FakeWebSocketServer.Connection connection) {
                open(connection);
            }

            @Override
            public void onText(FakeWebSocketServer.Connection connection, String text) {
                message(connection, text);
            }
        });
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/api/v2/", this::rest);
        http.createContext("/ws/apps/", this::dial);
        http.start();
    }

    public int port() { return remote.port(); }
    public int httpPort() { return http.getAddress().getPort(); }
    public int connections() { return remote.connections(); }
    public List<String> queries() { return List.copyOf(queries); }
    public void setAuthorization(Authorization answer) { authorization = answer; }
    public void setPowerState(String state) { powerState = state; }
    public void setRestAvailable(boolean available) { restAvailable = available; }
    public void setDialAvailable(boolean available) { dialAvailable = available; }
    /** Older firmware accepts clients without ever issuing a token. */
    public void setIssueTokens(boolean issue) { issueTokens = issue; }
    public void setVisible(String appId, boolean isVisible) { visible.put(appId, isVisible); }
    public void dropConnections() { remote.dropAll(); }

    /** Standby without network standby: REST and the WebSocket are both gone. */
    public void switchOff() {
        restAvailable = false;
        remote.refuseConnections(true);
    }

    public void switchOn() {
        restAvailable = true;
        remote.refuseConnections(false);
    }

    public String nextKey() throws InterruptedException { return keys.poll(5, TimeUnit.SECONDS); }
    public String nextLaunch() throws InterruptedException { return launches.poll(5, TimeUnit.SECONDS); }
    public String nextDialBody() throws InterruptedException { return dialBodies.poll(5, TimeUnit.SECONDS); }

    private void open(FakeWebSocketServer.Connection connection) {
        queries.add(connection.query() == null ? "" : connection.query());
        // Real sets chatter before answering; the client must skip this.
        connection.send("{\"event\":\"ed.edenTV.update\",\"data\":{\"update_type\":\"ed.edenApp.update\"}}");
        if (TOKEN.equals(queryParameter(connection.query(), "token"))) {
            connection.send(connectEvent(false));
            return;
        }
        switch (authorization) {
            case ALLOW -> connection.send(connectEvent(issueTokens));
            case DENY -> {
                connection.send("{\"event\":\"ms.channel.unauthorized\"}");
                connection.close();
            }
            case IGNORE -> {
            }
        }
    }

    private void message(FakeWebSocketServer.Connection connection, String text) {
        JsonNode message = JSON.readTree(text);
        JsonNode params = message.path("params");
        switch (message.path("method").asString("")) {
            case "ms.remote.control" -> keys.add(params.path("DataOfCmd").asString(""));
            case "ms.channel.emit" -> {
                String event = params.path("event").asString("");
                if (event.equals("ed.apps.launch")) {
                    String appId = params.path("data").path("appId").asString("");
                    launches.add(appId);
                    showOnly(appId);
                    connection.send("{\"data\":200,\"event\":\"ed.apps.launch\",\"from\":\"host\"}");
                } else if (event.equals("ed.installedApp.get")) {
                    connection.send(fixture("installed-apps.json"));
                }
            }
            default -> {
            }
        }
    }

    private void rest(HttpExchange exchange) throws IOException {
        if (!restAvailable) {
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v2/")) {
            respond(exchange, 200, fixture("device-info.json")
                    .replace("\"PowerState\":\"on\"", "\"PowerState\":\"" + powerState + "\""));
        } else if (path.startsWith("/api/v2/applications/")) {
            String appId = path.substring("/api/v2/applications/".length());
            Boolean isVisible = visible.get(appId);
            if (isVisible == null) {
                respond(exchange, 404, "{\"code\":404,\"message\":\"Not Found\",\"status\":404}");
            } else {
                respond(exchange, 200, "{\"id\":\"" + appId + "\",\"name\":\"" + (appId.equals(YOUTUBE) ? "YouTube" : "Netflix")
                        + "\",\"running\":" + isVisible + ",\"version\":\"1.0.0\",\"visible\":" + isVisible + "}");
            }
        } else {
            respond(exchange, 404, "");
        }
    }

    private void dial(HttpExchange exchange) throws IOException {
        if (!dialAvailable || !exchange.getRequestMethod().equals("POST")
                || !exchange.getRequestURI().getPath().equals("/ws/apps/YouTube")) {
            respond(exchange, 404, "");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        dialBodies.add(contentType + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        showOnly(YOUTUBE);
        exchange.getResponseHeaders().add("LOCATION", "http://127.0.0.1:" + httpPort() + "/ws/apps/YouTube/run");
        respond(exchange, 201, "");
    }

    private void showOnly(String appId) {
        visible.replaceAll((id, ignored) -> false);
        visible.put(appId, true);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static String connectEvent(boolean withToken) {
        return "{\"data\":{\"clients\":[{\"attributes\":{\"name\":\"SG9tZSBDb250cm9s\"},\"connectTime\":1726480800000,"
                + "\"deviceName\":\"SG9tZSBDb250cm9s\",\"id\":\"c1a2b3\",\"isHost\":false}],\"id\":\"c1a2b3\""
                + (withToken ? ",\"token\":\"" + TOKEN + "\"" : "") + "},\"event\":\"ms.channel.connect\"}";
    }

    private static String queryParameter(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/fixtures/tizen/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        remote.close();
        http.stop(0);
    }
}
```

(`nextDialBody()` returns `"<Content-Type>|<body>"` so tests can assert both.)

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenRestTest.java` — `properties(fake)` = `new TizenProperties(true, fake.port(), fake.httpPort(), fake.httpPort(), "Home Control", 2, 2, 2, 1, 0)`, client `InsecureTls.httpClient(Duration.ofSeconds(2))`. Test cases:

- `readsTheDeviceInfo`: `deviceInfo("127.0.0.1")` → name `[TV] Samsung 8 Series (55)`, modelName `GU55TU8079UXZG`, powerState `on`, `on()` true, `macAddress()` `70:2A:D5:01:02:03`, `tokenAuthSupport` true.
- `standbyIsNotOn`: `fake.setPowerState("standby")` → `on()` false.
- `aSetWithoutPowerStateCountsAsOn`: `parseDeviceInfo` of `{"device":{"name":"TV"}}` → `on()` true, `macAddress()` empty.
- `anUnreachableTvHasNoInfo`: `fake.setRestAvailable(false)` → `Optional.empty()` within the request timeout.
- `appVisibilityComesFromTheApplicationsEndpoint`: `setVisible(YOUTUBE, true)` → `appVisible("127.0.0.1", YOUTUBE)` = `Optional.of(true)`; Netflix → `Optional.of(false)`; `"nope"` (404) → empty.

`src/test/java/dev/andre/homecontrol/adapters/tizen/DialClientTest.java`:

- `startsYouTubeWithTheVideoParameter`: `launch("127.0.0.1", "YouTube", "v=aqz-KE-bpKQ")` → `fake.nextDialBody()` = `text/plain; charset=utf-8|v=aqz-KE-bpKQ`.
- `aMissingDialAppIsADialException`: `setDialAvailable(false)` → `DialException` whose message is `YouTube is not available over DIAL on this TV`.
- `anUnreachableTvIsAnIoExceptionButNotADialException`: DIAL port = `FakeWebSocketServer.closedPort()` → `IOException` that is not a `DialException`.

- [ ] **Step 7: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.TizenRestTest' --tests 'dev.andre.homecontrol.adapters.tizen.DialClientTest'`
Expected: compilation failure.

- [ ] **Step 8: Write properties, settings, REST and DIAL clients**

`adapters/tizen/TizenProperties.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code clientName} is what the TV shows in its Allow prompt and its list of connected devices. */
@ConfigurationProperties("home-control.tizen")
public record TizenProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("8002") int port,
                              @DefaultValue("8001") int restPort,
                              @DefaultValue("8080") int dialPort,
                              @DefaultValue("Home Control") String clientName,
                              @DefaultValue("3") int connectTimeoutSeconds,
                              @DefaultValue("5") int requestTimeoutSeconds,
                              @DefaultValue("30") int pairingTimeoutSeconds,
                              @DefaultValue("5") int pollIntervalSeconds,
                              @DefaultValue("3") int wakeGraceSeconds) {
}
```

`adapters/tizen/TizenSettings.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.util.Map;

/**
 * Under {@code adapters.tizen}: {@code paired=true} once the user allowed us (older firmware issues
 * no token, so the flag, not the token, says whether connecting is allowed), the token if any,
 * and the Wake-on-LAN MAC.
 */
public record TizenSettings(String token, boolean paired, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "tizen";
    static final String TOKEN = "token";
    static final String PAIRED = "paired";

    public static TizenSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new TizenSettings(blankToNull(settings.get(TOKEN)), "true".equals(settings.get(PAIRED)),
                blankToNull(settings.get(WakeOnLanAdapter.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
```

`adapters/tizen/TizenDeviceInfo.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.core.MacAddress;

import java.util.Optional;

/** The parts of {@code GET /api/v2/} the adapter uses. */
record TizenDeviceInfo(String name, String modelName, String powerState, String wifiMac, boolean tokenAuthSupport) {

    /** Sets older than 2018 do not report PowerState; answering at all then means on. */
    boolean on() {
        return powerState.isEmpty() || powerState.equalsIgnoreCase("on");
    }

    /** Named {@code wifiMac}, but it is the MAC of the active interface, wired or not. */
    Optional<String> macAddress() {
        try {
            return wifiMac.isEmpty() ? Optional.empty() : Optional.of(MacAddress.normalize(wifiMac));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

`adapters/tizen/TizenRest.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** The TV's REST API on 8001. Every failure is "no answer": the TV may be off or the model may lack the endpoint. */
final class TizenRest {

    private final HttpClient http;
    private final TizenProperties properties;

    TizenRest(HttpClient http, TizenProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    Optional<TizenDeviceInfo> deviceInfo(String host) {
        return getJson(host, "/api/v2/").map(TizenRest::parseDeviceInfo);
    }

    Optional<Boolean> appVisible(String host, String appId) {
        return getJson(host, "/api/v2/applications/" + appId).map(app -> app.path("visible").asBoolean(false));
    }

    static TizenDeviceInfo parseDeviceInfo(JsonNode root) {
        JsonNode device = root.path("device");
        return new TizenDeviceInfo(
                device.path("name").asString(root.path("name").asString("")),
                device.path("modelName").asString(""),
                device.path("PowerState").asString(""),
                device.path("wifiMac").asString(""),
                device.path("TokenAuthSupport").asString("").equals("true"));
    }

    private Optional<JsonNode> getJson(String host, String path) {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + authority + ":" + properties.restPort() + path))
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(TizenMessages.JSON.readTree(response.body())) : Optional.empty();
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
```

`adapters/tizen/DialException.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import java.io.IOException;

/** The TV answered the DIAL request and refused. */
class DialException extends IOException {
    DialException(String message) {
        super(message);
    }
}
```

`adapters/tizen/DialClient.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** DIAL application launch (DIAL 2.x §6.1): {@code POST http://host:8080/ws/apps/<app>} with the app's arguments. */
final class DialClient {

    private final HttpClient http;
    private final TizenProperties properties;

    DialClient(HttpClient http, TizenProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    void launch(String host, String app, String body) throws IOException {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + authority + ":" + properties.dialPort() + "/ws/apps/" + app))
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting " + app, e);
        }
        switch (response.statusCode()) {
            case 200, 201 -> {
            }
            case 404 -> throw new DialException(app + " is not available over DIAL on this TV");
            case 503 -> throw new DialException("The TV could not start " + app + " right now");
            default -> throw new DialException("The TV answered " + response.statusCode() + " when asked to start " + app);
        }
    }
}
```

- [ ] **Step 9: Run the REST and DIAL tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.TizenRestTest' --tests 'dev.andre.homecontrol.adapters.tizen.DialClientTest'`
Expected: PASS.

- [ ] **Step 10: Write the failing remote-connection and session tests**

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenRemoteConnectionTest.java`:

- `aKnownTokenConnectsWithoutAPrompt`: `open(…, TOKEN, …)` → `awaitAuthorization(2s)` = `CONNECTED`; `token()` empty (the fake re-issues no token for a known one); the fake's last query contains `name=SG9tZSBDb250cm9s&token=73184052`.
- `anAllowedPromptHandsOutAToken`: `open(…, null, …)`, authorization `ALLOW` → `CONNECTED`, `token()` = `73184052`; the query has no `token=`.
- `aDeniedPromptIsUnauthorized`: `DENY` → `UNAUTHORIZED`.
- `anUnansweredPromptIsNoAnswer`: `IGNORE`, `awaitAuthorization(1s)` → `NO_ANSWER`.
- `sendsKeysAndLaunchesAndLearnsInstalledApps`: after `CONNECTED`: `key("KEY_HOME")` → `fake.nextKey()` `KEY_HOME`; `launchApp(NETFLIX, "DEEP_LINK")` → `fake.nextLaunch()` `3201907018807`; `requestInstalledApps()` → `installedApps()` eventually contains `TizenApp("111299001912","YouTube",2)`.
- `theTvClosingTheConnectionIsReportedOnce`: `fake.dropConnections()` → one reason.

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenSessionTest.java` — per test: `FakeTizenServer tv`; `FakeWakeOnLanReceiver receiver`; a `JsonFileDeviceRegistry` in a `@TempDir` with `new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "127.0.0.1", Map.of("tizen", settings), Instant.now())`, `settings` defaulting to `{paired: true, token: 73184052}`; `properties = new TizenProperties(true, tv.port(), tv.httpPort(), tv.httpPort(), "Home Control", 2, 2, 2, 1, 0)`; `session = new TizenSession(device, properties, InsecureTls.httpClient(Duration.ofSeconds(2)), registry, new WakeOnLan(receiver.address()), states::add, () -> {})`; `session.start()`. Helper `connected()` awaits `CONNECTED` and `powerOn`. Test cases:

- `connectsWithTheStoredTokenAndReportsPowerOn`: `connected()`; `currentApp` null; `volumeMax` 0 (volume unknown on Tizen).
- `keysAreRemoteControlClicks`: `HOME` → `KEY_HOME`; `DPAD_CENTER` → `KEY_ENTER`; `BACK` → `KEY_RETURN`; `VOLUME_UP` → `KEY_VOLUP`.
- `playPauseAlternatesPauseAndPlay`: `KEY_PAUSE` then `KEY_PLAY`.
- `aKeyWithoutACodeIsUnsupported`: `MEDIA_NEXT` → `UnsupportedActionException`.
- `absoluteVolumeMuteInputsAndCastAreUnsupportedWithAReason`: `SetVolume(20)` and `Mute(true)` → `UnsupportedActionException` containing `volume up, down and mute`; `SelectInput("HDMI1")` → containing `Source button`; `CastLoad("CC1AD845", Map.of())` → `UnsupportedActionException`.
- `stopSendsTheStopKey`: `Stop()` → `KEY_STOP`.
- `aYouTubeVideoStartsThroughDialAndShowsAsTheCurrentApp`: `OpenAppLink("https://www.youtube.com/watch?v=aqz-KE-bpKQ")` → `tv.nextDialBody()` ends with `|v=aqz-KE-bpKQ`; within 3 s `state().currentApp()` = `YouTube`.
- `netflixOpensTheInstalledAppWithoutTheTitle`: await installed apps (500 ms after `connected()`); `OpenAppLink("https://www.netflix.com/title/80057281")` → `tv.nextLaunch()` = `3201907018807`; `currentApp` becomes `Netflix`.
- `aWebLinkIsRefusedWithWhatSamsungCanOpen`: `https://example.org/a` → `UnsupportedActionException` containing `cannot open web links`.
- `anAppThatIsNotInstalledIsRefused`: `https://app.primevideo.com/detail?gti=x` after the installed list arrived → `UnsupportedActionException` containing `Prime Video is not installed`.
- `aDialRefusalIsAnActionFailure`: `tv.setDialAvailable(false)`; YouTube video link → `ActionFailedException` containing `not available over DIAL`.
- `learnsTheMacFromTheRestApi`: await registry `macAddress` = `70:2A:D5:01:02:03`; with `macAddressManual=true` and `macAddress=11:22:33:44:55:66` preset, it stays.
- `standbyMeansPoweredOffAndDisconnected`: `connected()`; `tv.setPowerState("standby")` → `DISCONNECTED`, `powerOn` false; `setPowerState("on")` → `connected()`.
- `powerWhileOnSendsTheKey`: `PressKey(POWER)` → `KEY_POWER`; `powerOn` false.
- `powerWhileOffWakesTheTv`: `connected()`, MAC learned, `tv.switchOff()`, await `DISCONNECTED`; `PressKey(POWER)` → `receiver.nextPacket()` equals `WakeOnLan.magicPacket("70:2A:D5:01:02:03")`; `tv.switchOn()` → `connected()`.
- `powerWhileOffWithoutAMacExplainsTheFix`: `tv.switchOff()` before start, no MAC → `DeviceOfflineException` containing `setup page`.
- `aRejectedTokenIsUnpairedAndStopsTrying`: settings token `999`, `tv.setAuthorization(DENY)` → `UNPAIRED`; after 3 s `tv.connections()` is 1.
- `aPromptInsteadOfAConnectIsUnpaired`: settings token `999`, `IGNORE` → `UNPAIRED` after the 2 s request timeout; no further connection attempts.
- `neverConnectsWithoutPairing`: settings `{}` → `UNPAIRED`; `tv.connections()` 0 after 2 s.
- `aNewlyIssuedTokenIsStored`: settings `{paired: true}` (no token), `ALLOW` → registry `token` becomes `73184052`.
- `reconnectsOnTheNextPollAfterADrop`: `connected()`, `tv.dropConnections()` → `DISCONNECTED` observed, then `connected()`.
- `worksWithoutTheRestApi`: `tv.setRestAvailable(false)` before start → `connected()` (WebSocket only), registry has no `macAddress`.
- `closeStopsPolling`: `close()`, `states.clear()`, `tv.dropConnections()` → `states` empty after 3 s.

- [ ] **Step 11: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.*'`
Expected: compilation failure — `TizenRemoteConnection` and `TizenSession` do not exist.

- [ ] **Step 12: Write the remote connection and the session**

`adapters/tizen/TizenRemoteConnection.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.TextWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The remote-control channel of one Samsung TV. After opening, the TV decides: a known token (or an
 * Allow on screen) yields {@code ms.channel.connect}, Deny yields {@code ms.channel.unauthorized},
 * and an unanswered prompt yields nothing. Commands are fire-and-forget; the TV does not answer them.
 */
final class TizenRemoteConnection implements AutoCloseable {

    enum Authorization { CONNECTED, UNAUTHORIZED, NO_ANSWER }

    private static final Logger log = LoggerFactory.getLogger(TizenRemoteConnection.class);

    private final BlockingQueue<String> channelEvents = new LinkedBlockingQueue<>();
    private volatile TextWebSocket socket;
    private volatile String issuedToken;
    private volatile List<TizenApp> installedApps;

    private TizenRemoteConnection() {
    }

    static TizenRemoteConnection open(HttpClient http, String host, TizenProperties properties, String token,
                                      Consumer<String> onClosed) throws IOException {
        TizenRemoteConnection connection = new TizenRemoteConnection();
        connection.socket = TextWebSocket.connect(http,
                TizenMessages.remoteUri(host, properties.port(), properties.clientName(), token),
                Duration.ofSeconds(properties.connectTimeoutSeconds()),
                new TextWebSocket.Listener() {
                    @Override
                    public void onText(String text) {
                        connection.dispatch(text);
                    }

                    @Override
                    public void onClosed(String reason) {
                        connection.channelEvents.add("closed");
                        onClosed.accept(reason);
                    }
                });
        return connection;
    }

    Authorization awaitAuthorization(Duration timeout) throws IOException {
        try {
            String event = channelEvents.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null) {
                return Authorization.NO_ANSWER;
            }
            return switch (event) {
                case "ms.channel.connect" -> Authorization.CONNECTED;
                case "ms.channel.unauthorized" -> Authorization.UNAUTHORIZED;
                default -> throw new IOException("The TV closed the connection before answering");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the TV", e);
        }
    }

    /** A token the TV issued on this connection (only after a fresh Allow). */
    Optional<String> token() {
        return Optional.ofNullable(issuedToken);
    }

    void key(String code) throws IOException {
        socket.send(TizenMessages.key(code));
    }

    void launchApp(String appId, String actionType) throws IOException {
        socket.send(TizenMessages.launchApp(appId, actionType));
    }

    void requestInstalledApps() throws IOException {
        socket.send(TizenMessages.installedAppsRequest());
    }

    /** Empty until the TV answered {@link #requestInstalledApps()}. */
    Optional<List<TizenApp>> installedApps() {
        return Optional.ofNullable(installedApps);
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = TizenMessages.JSON.readTree(text);
        } catch (JacksonException e) {
            log.debug("Ignoring a message from the TV that is not JSON");
            return;
        }
        switch (message.path("event").asString("")) {
            case "ms.channel.connect" -> {
                String token = message.path("data").path("token").asString("");
                if (!token.isEmpty()) {
                    issuedToken = token;
                }
                channelEvents.add("ms.channel.connect");
            }
            case "ms.channel.unauthorized" -> channelEvents.add("ms.channel.unauthorized");
            case "ed.installedApp.get" -> installedApps = TizenMessages.installedApps(message);
            case "ms.error" -> log.debug("The TV reported an error: {}", message.path("data").path("message").asString(""));
            default -> {
                // ed.edenTV.update, ms.voiceApp.hide, ed.apps.launch results, client (dis)connects: not needed.
            }
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
        }
    }
}
```

`adapters/tizen/TizenSession.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.WakeOnLanAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One Samsung Tizen TV. Samsung pushes no state, so a poll (every {@code pollIntervalSeconds})
 * reads power and MAC from the REST API, (re)opens the remote channel with the stored token when
 * the TV is on, and derives the current app from the visibility of the known service apps.
 * A token the TV no longer accepts makes the session UNPAIRED and stops it: connecting again would
 * put the Allow prompt on screen every few seconds.
 */
public class TizenSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(TizenSession.class);

    private final Device device;
    private final TizenProperties properties;
    private final HttpClient http;
    private final TizenRest rest;
    private final DialClient dial;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final Consumer<DeviceState> onChange;
    private final Runnable onClose;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean nextPlayPauseIsPlay = new AtomicBoolean();

    private volatile TizenRemoteConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile boolean closed;
    private volatile boolean stopped;

    TizenSession(Device device, TizenProperties properties, HttpClient http, DeviceRegistry registry,
                 WakeOnLan wakeOnLan, Consumer<DeviceState> onChange, Runnable onClose) {
        this.device = device;
        this.properties = properties;
        this.http = http;
        this.rest = new TizenRest(http, properties);
        this.dial = new DialClient(http, properties);
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.onChange = onChange;
        this.onClose = onClose;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("tizen-" + device.id()).factory());
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::poll, 0, properties.pollIntervalSeconds(), TimeUnit.SECONDS);
    }

    String host() {
        return device.host();
    }

    /** SSDP heard the TV: poll now instead of at the next interval. */
    void pollNow() {
        onScheduler(this::poll);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> pressKey(press.key());
            case Action.OpenAppLink open -> openAppLink(open.uri());
            case Action.SelectInput ignored -> throw new UnsupportedActionException(
                    device.name() + " does not list its inputs; use the Source button of the TV remote");
            case Action.SetVolume ignored -> throw volumeKeysOnly();
            case Action.Mute ignored -> throw volumeKeysOnly();
            case Action.Stop ignored -> sendKey("KEY_STOP");
            case Action.CastLoad ignored -> throw new UnsupportedActionException(device.name() + " is not a Cast receiver");
        }
    }

    private UnsupportedActionException volumeKeysOnly() {
        return new UnsupportedActionException(device.name() + " only takes volume up, down and mute keys");
    }

    private void pressKey(RemoteKey key) {
        switch (key) {
            case POWER -> togglePower();
            case PLAY_PAUSE -> sendKey(nextPlayPauseIsPlay.getAndSet(!nextPlayPauseIsPlay.get()) ? "KEY_PLAY" : "KEY_PAUSE");
            default -> sendKey(TizenKeys.code(key).orElseThrow(() ->
                    new UnsupportedActionException(device.name() + " has no " + key + " key")));
        }
    }

    private void sendKey(String code) {
        TizenRemoteConnection current = requireConnected();
        try {
            current.key(code);
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection");
        }
    }

    private void openAppLink(URI uri) {
        TizenRemoteConnection current = requireConnected();
        switch (TizenLaunches.forUri(uri, current.installedApps())) {
            case TizenLaunch.Dial launch -> {
                try {
                    dial.launch(device.host(), launch.app(), launch.body());
                } catch (DialException e) {
                    throw new ActionFailedException(device.name() + ": " + e.getMessage());
                } catch (IOException e) {
                    throw new DeviceOfflineException(device.name() + " did not answer the DIAL request");
                }
            }
            case TizenLaunch.App app -> {
                try {
                    current.launchApp(app.appId(), app.actionType());
                } catch (IOException e) {
                    throw new DeviceOfflineException(device.name() + " dropped the connection");
                }
            }
            case TizenLaunch.Unsupported unsupported -> throw new UnsupportedActionException(
                    device.name() + ": " + unsupported.reason());
        }
    }

    private void togglePower() {
        if (connection != null && state.powerOn()) {
            sendKey("KEY_POWER");
            update(s -> s.withPower(false));
            return;
        }
        String mac = current().adapterSettings(TizenSettings.ADAPTER_ID).get(WakeOnLanAdapter.MAC_ADDRESS);
        if (mac == null || mac.isBlank()) {
            throw new DeviceOfflineException(device.name() + " is off and no MAC address is known for Wake-on-LAN;"
                    + " switch it on once by hand or enter its MAC address on the setup page");
        }
        try {
            wakeOnLan.wake(mac);
        } catch (IOException | IllegalArgumentException e) {
            throw new DeviceOfflineException("Could not send the Wake-on-LAN packet: " + e.getMessage());
        }
        try {
            scheduler.schedule(this::poll, properties.wakeGraceSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    private void poll() {
        if (closed || stopped) {
            return;
        }
        try {
            Optional<TizenDeviceInfo> info = rest.deviceInfo(device.host());
            info.ifPresent(this::learnMacAddress);
            if (info.isPresent() && !info.get().on()) {
                dropConnection();
                update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
                return;
            }
            if (connection == null && !connect()) {
                return;
            }
            String app = visibleKnownApp();
            update(s -> s.withStatus(DeviceStatus.CONNECTED).withPower(true).withCurrentApp(app));
        } catch (RuntimeException e) {
            // Never let an exception cancel the fixed-delay schedule.
            log.warn("Polling {} failed", device.name(), e);
        }
    }

    private boolean connect() {
        TizenSettings settings = TizenSettings.of(current());
        if (!settings.paired()) {
            stopped = true;
            update(ignored -> DeviceState.unpaired());
            return false;
        }
        AtomicReference<TizenRemoteConnection> attempt = new AtomicReference<>();
        TizenRemoteConnection opened = null;
        try {
            opened = TizenRemoteConnection.open(http, device.host(), properties, settings.token(),
                    reason -> onScheduler(() -> lost(attempt.get(), reason)));
            attempt.set(opened);
            TizenRemoteConnection.Authorization answer =
                    opened.awaitAuthorization(Duration.ofSeconds(properties.requestTimeoutSeconds()));
            if (answer == TizenRemoteConnection.Authorization.CONNECTED) {
                connection = opened;
                opened.token().filter(token -> !token.equals(settings.token()))
                        .ifPresent(token -> persist(Map.of(TizenSettings.TOKEN, token)));
                opened.requestInstalledApps();
                return true;
            }
            opened.close();
            stopped = true;
            log.warn("{} did not accept the stored pairing ({}); pair it again on the setup page", device.name(), answer);
            update(ignored -> DeviceState.unpaired());
            return false;
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            connection = null;
            update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
            return false;
        }
    }

    private String visibleKnownApp() {
        TizenRemoteConnection current = connection;
        if (current == null) {
            return null;
        }
        for (TizenLaunch.App app : TizenLaunches.knownApps(current.installedApps())) {
            if (rest.appVisible(device.host(), app.appId()).orElse(false)) {
                return app.name();
            }
        }
        return null;
    }

    private void learnMacAddress(TizenDeviceInfo info) {
        info.macAddress().ifPresent(mac -> {
            TizenSettings settings = TizenSettings.of(current());
            if (!settings.macAddressManual() && !mac.equals(settings.macAddress())) {
                persist(Map.of(WakeOnLanAdapter.MAC_ADDRESS, mac));
            }
        });
    }

    private void lost(TizenRemoteConnection which, String reason) {
        if (which == null || which != connection) {
            return;
        }
        dropConnection();
        log.info("Lost the connection to {} ({})", device.name(), reason);
        update(s -> s.withStatus(DeviceStatus.DISCONNECTED).withPower(false).withCurrentApp(null));
    }

    private void dropConnection() {
        TizenRemoteConnection current = connection;
        connection = null;
        if (current != null) {
            current.close();
        }
    }

    private TizenRemoteConnection requireConnected() {
        TizenRemoteConnection current = connection;
        if (current != null) {
            return current;
        }
        if (state.status() == DeviceStatus.UNPAIRED) {
            throw new DeviceOfflineException(device.name() + " must be paired again before it can be controlled");
        }
        throw new DeviceOfflineException(device.name() + " is not connected");
    }

    private Device current() {
        return registry.findById(device.id()).orElse(device);
    }

    private void persist(Map<String, String> updates) {
        registry.findById(device.id()).ifPresent(stored -> {
            Map<String, String> settings = new LinkedHashMap<>(stored.adapterSettings(TizenSettings.ADAPTER_ID));
            settings.putAll(updates);
            registry.save(stored.withAdapter(TizenSettings.ADAPTER_ID, settings));
        });
    }

    private synchronized void update(UnaryOperator<DeviceState> change) {
        DeviceState next = change.apply(state);
        if (next.sameIgnoringTime(state)) {
            return;
        }
        state = next;
        onChange.accept(next);
    }

    private void onScheduler(Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException e) {
            // Closed meanwhile.
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        dropConnection();
        onClose.run();
    }
}
```

Note: the first poll publishes `DISCONNECTED` → `CONNECTED` directly; Tizen never publishes `CONNECTING`, because a poll every few seconds against a switched-off TV would otherwise emit two SSE events per interval.

- [ ] **Step 13: Run the connection and session tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.TizenRemoteConnectionTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenSessionTest'`
Expected: PASS.

- [ ] **Step 14: Write the failing adapter and pairing tests**

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenAdapterTest.java`:

- `declaresKeysPowerVolumeAndAppLinks`: `capabilities` = `{REMOTE_KEYS, POWER, VOLUME, APP_LINK}`; `id()` `tizen`; `kind()` `TIZEN`; a `WakeOnLanAdapter`; `settingsFor(anything)` empty.
- `listsSamsungTvsTheSharedListenerFound`: started discovery with a `FakeSsdpResponder` answering `urn:samsung.com:device:RemoteControlReceiver:1` from `samsung-search-response.txt` and an `HttpServer` serving `samsung-description.xml` → `discovered()` eventually `[DiscoveredDevice("tizen", "[TV] Samsung 8 Series (55)", "127.0.0.1", 8002)]`; without a description the name is `Samsung TV`.
- `anSsdpAnnouncementTriggersAnImmediatePoll`: `pollIntervalSeconds` 30; `tv.switchOff()` before connecting; await `DISCONNECTED`; `tv.switchOn()`; send an alive NOTIFY for the Samsung target with `LOCATION: http://127.0.0.1:1/x.xml` to `ssdp.listenPort()` → `CONNECTED` within 5 s.

`src/test/java/dev/andre/homecontrol/adapters/tizen/TizenPairingTest.java` — `DeviceManager devices = mock(DeviceManager.class)` answering `attach` with a `Device` built from its arguments:

- `anAllowedConnectionStoresThePairingAndToken`: `ALLOW`, `pair("127.0.0.1", null)` → `Paired`; `verify(devices).attach("127.0.0.1", "[TV] Samsung 8 Series (55)", DeviceKind.TIZEN, "tizen", Map.of("paired", "true", "token", "73184052"))`; the fake's query has no `token=`.
- `theUsersNameWins`: `pair("127.0.0.1", "Bedroom TV")` → attached with name `Bedroom TV`.
- `firmwareWithoutTokensStillPairs`: `tv.setIssueTokens(false)` → settings `{paired: true}` only.
- `aDeniedConnectionIsDeclined`: `DENY` → `Declined` containing `declined`; `attach` never called.
- `anUnansweredPromptFails`: `IGNORE`, `pairingTimeoutSeconds` 1 → `Failed` containing `within 1 seconds`.
- `anUnreachableHostFails`: WebSocket port closed → `Failed` containing `Could not reach a Samsung TV at 127.0.0.1`.
- `describesItselfForTheSetupPage`: `adapterId()` `tizen`, `displayName()` `Samsung TV`, `instructions()` contains `Allow` and `30 seconds`.

- [ ] **Step 15: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.TizenAdapterTest' --tests 'dev.andre.homecontrol.adapters.tizen.TizenPairingTest'`
Expected: compilation failure.

- [ ] **Step 16: Write the adapter, the pairing and the module configuration**

`adapters/tizen/TizenAdapter.java` — mirrors `WebOsAdapter`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.WakeOnLanAdapter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Samsung Tizen TVs: remote-control WebSocket, REST API and DIAL (spec §4.1). */
public class TizenAdapter implements WakeOnLanAdapter {

    public static final String ID = TizenSettings.ADAPTER_ID;
    public static final String SEARCH_TARGET = "urn:samsung.com:device:RemoteControlReceiver:1";

    private final TizenProperties properties;
    private final SsdpDiscovery ssdp;
    private final DeviceRegistry registry;
    private final WakeOnLan wakeOnLan;
    private final HttpClient http;
    private final Map<String, TizenSession> sessions = new ConcurrentHashMap<>();

    public TizenAdapter(TizenProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry, WakeOnLan wakeOnLan) {
        this.properties = properties;
        this.ssdp = ssdp;
        this.registry = registry;
        this.wakeOnLan = wakeOnLan;
        this.http = InsecureTls.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        ssdp.addListener(SEARCH_TARGET, service -> sessions.values().stream()
                .filter(session -> session.host().equalsIgnoreCase(service.address()))
                .forEach(TizenSession::pollNow));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.TIZEN;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        AtomicReference<TizenSession> self = new AtomicReference<>();
        TizenSession session = new TizenSession(device, properties, http, registry, wakeOnLan, onChange,
                () -> sessions.remove(device.id(), self.get()));
        self.set(session);
        sessions.put(device.id(), session);
        session.start();
        return session;
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return ssdp.services(SEARCH_TARGET).stream()
                .map(service -> new DiscoveredDevice(ID, service.friendlyName().orElse("Samsung TV"),
                        service.address(), properties.port()))
                .toList();
    }
}
```

`adapters/tizen/TizenPairing.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;
import dev.andre.homecontrol.device.DeviceManager;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** "Allow Home Control?" on the TV: connect without a token and keep the token the TV hands out. */
public class TizenPairing implements PromptPairing {

    private final TizenProperties properties;
    private final DeviceManager devices;
    private final HttpClient http;
    private final TizenRest rest;

    public TizenPairing(TizenProperties properties, DeviceManager devices) {
        this.properties = properties;
        this.devices = devices;
        this.http = InsecureTls.httpClient(Duration.ofSeconds(properties.connectTimeoutSeconds()));
        this.rest = new TizenRest(http, properties);
    }

    @Override
    public String adapterId() {
        return TizenAdapter.ID;
    }

    @Override
    public String displayName() {
        return "Samsung TV";
    }

    @Override
    public String instructions() {
        return "The TV asks whether to allow \"" + properties.clientName() + "\". Choose Allow with the TV remote within "
                + properties.pairingTimeoutSeconds() + " seconds.";
    }

    @Override
    public PromptPairingResult pair(String host, String name) {
        try (TizenRemoteConnection connection = TizenRemoteConnection.open(http, host, properties, null, reason -> { })) {
            return switch (connection.awaitAuthorization(Duration.ofSeconds(properties.pairingTimeoutSeconds()))) {
                case CONNECTED -> {
                    Map<String, String> settings = new LinkedHashMap<>();
                    settings.put(TizenSettings.PAIRED, "true");
                    connection.token().ifPresent(token -> settings.put(TizenSettings.TOKEN, token));
                    String deviceName = name != null && !name.isBlank() ? name.trim()
                            : rest.deviceInfo(host).map(TizenDeviceInfo::name).filter(n -> !n.isBlank()).orElse("Samsung TV");
                    yield new PromptPairingResult.Paired(
                            devices.attach(host, deviceName, DeviceKind.TIZEN, TizenAdapter.ID, settings));
                }
                case UNAUTHORIZED -> new PromptPairingResult.Declined("The TV declined the connection request");
                case NO_ANSWER -> new PromptPairingResult.Failed("Nobody allowed the connection on the TV within "
                        + properties.pairingTimeoutSeconds() + " seconds; try again");
            };
        } catch (IOException e) {
            return new PromptPairingResult.Failed("Could not reach a Samsung TV at " + host + ": " + e.getMessage());
        }
    }
}
```

`adapters/tizen/TizenConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Samsung Tizen module; {@code home-control.tizen.enabled=false} removes it entirely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.tizen", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TizenProperties.class)
public class TizenConfiguration {

    @Bean
    public TizenAdapter tizenAdapter(TizenProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry,
                                     WakeOnLan wakeOnLan) {
        return new TizenAdapter(properties, ssdp, registry, wakeOnLan);
    }

    @Bean
    public TizenPairing tizenPairing(TizenProperties properties, DeviceManager devices) {
        return new TizenPairing(properties, devices);
    }
}
```

`src/main/resources/application.yaml` — under `home-control:` add:

```yaml
  tizen:
    enabled: true
    port: 8002
    rest-port: 8001
    dial-port: 8080
    client-name: Home Control
    pairing-timeout-seconds: 30
    poll-interval-seconds: 5
```

- [ ] **Step 17: Prove both TV modules can be switched off**

`src/test/java/dev/andre/homecontrol/web/SmartTvModulesOffTest.java` — `@SpringBootTest(properties = {"home-control.webos.enabled=false", "home-control.tizen.enabled=false", "shield.data-dir=build/tmp/tv-modules-off-test"})` with `@Autowired ApplicationContext context`:

- `noTvAdapterOrPairingExists`: `context.getBeansOfType(PromptPairing.class)` is empty; `getBeansOfType(WebOsAdapter.class)` and `getBeansOfType(TizenAdapter.class)` are empty; `SsdpDiscovery` still exists (shared, idle).

Add to `src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java`:

- `bothTvModulesAreOnByDefault`: `getBeansOfType(PromptPairing.class).keySet()` has two entries and one `WebOsAdapter` and one `TizenAdapter` bean exist.

- [ ] **Step 18: Run the Tizen tests and the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. The setup page now offers "Samsung TV" in the manual prompt-pairing form without any web change.

- [ ] **Step 19: Commit**

```bash
git add -A
git commit -m "feat: Samsung Tizen adapter with token pairing, keys, Wake-on-LAN, app launch and DIAL YouTube"
```

---
### Task 4: F4 · Deep-link test button

**Files:**
- Create: `core/ForegroundAppReporting.java`, `playback/DeepLinkTestProperties.java`, `playback/DeepLinkTestResult.java`, `playback/DeepLinkTestService.java`, `web/DeepLinkTestController.java`
- Modify: `core/DeviceAdapter.java`, `device/DeviceManager.java`, `adapters/androidtv/AndroidTvAdapter.java`, `adapters/cast/CastAdapter.java`, `adapters/webos/WebOsAdapter.java`, `adapters/tizen/TizenAdapter.java`, `HomeControlConfiguration.java`, `web/SetupController.java`, `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`
- Test: `playback/DeepLinkTestServiceTest.java`, `web/DeepLinkTestControllerTest.java`, `web/SetupControllerTest.java`, `device/DeviceManagerTest.java`, `adapters/androidtv/AndroidTvAdapterTest.java`, `adapters/cast/CastAdapterTest.java`, `adapters/webos/WebOsAdapterTest.java`, `adapters/tizen/TizenAdapterTest.java`

**Interfaces:**
- Consumes: `DeviceManager.device/capabilities/state/execute`, `DeviceStateChangedEvent(String deviceId, DeviceState state)` (B: carries the composed state), `Action.OpenAppLink`, `Capability.APP_LINK`, `DeviceOfflineException`, `UnsupportedActionException`, `ActionFailedException`, the setup page from Task 2.
- Produces:
  - `enum core.ForegroundAppReporting { LIVE, POLLED, NONE }` (declaration order = best first).
  - `DeviceAdapter`: `default ForegroundAppReporting foregroundAppReporting(Device device) { return ForegroundAppReporting.NONE; }`; overridden: Android TV `LIVE`, Cast `LIVE`, webOS `LIVE`, Tizen `POLLED`.
  - `DeviceManager.foregroundAppReporting(String id) → ForegroundAppReporting` (best over the device's adapters; `NONE` for unknown ids).
  - `record DeepLinkTestProperties(URI youtubeUrl, Duration timeout)` bound to `home-control.deep-link-test`.
  - `record DeepLinkTestResult(DeepLinkTestResult.Outcome outcome, String appBefore, String appAfter, String message)`; `enum Outcome { APP_CHANGED, NO_CHANGE, NOT_OBSERVABLE, FAILED }`.
  - `DeepLinkTestService.run(String deviceId) → DeepLinkTestResult` (throws `DeviceOfflineException` for an unknown id, `UnsupportedActionException` without `APP_LINK`); `@EventListener onStateChanged(DeviceStateChangedEvent)`.
  - `POST /setup/devices/{id}/deep-link-test` → 200 `text/html` fragment `<p class="deep-link-result <outcome-in-kebab-case>">escaped message</p>`, 404 unknown device, 422 no app links.

What the button can honestly report per adapter (the result messages say exactly this):

| Adapter | Foreground app signal | Reporting |
|---|---|---|
| Android TV (Remote v2) | current-app event pushed by the device | `LIVE` |
| Cast | receiver status pushes the running app | `LIVE` |
| LG webOS | `ssap://com.webos.applicationManager/getForegroundAppInfo` subscription | `LIVE` |
| Samsung Tizen | `GET /api/v2/applications/{id}` → `visible`, polled, only for YouTube, Netflix and Prime Video; some models lack the endpoint | `POLLED` |
| anything else | none | `NONE` → "Sent; check the screen" |

No adapter can see *which video* plays, so every message ends by asking the user to check the screen for the test video.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/andre/homecontrol/playback/DeepLinkTestServiceTest.java` — `DeviceManager devices = mock(DeviceManager.class)`; `properties = new DeepLinkTestProperties(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"), Duration.ofMillis(300))`; `service = new DeepLinkTestService(devices, properties)`; a device `lg` named `LG TV` with `capabilities` `{APP_LINK}` and `state("lg")` whose `currentApp` is `com.webos.app.home`. To simulate the TV, stub `execute` with `doAnswer` that calls `service.onStateChanged(new DeviceStateChangedEvent("lg", …))` from a new thread. Test cases:

- `reportsTheNewForegroundApp`: reporting `LIVE`; execute answers with a state whose `currentApp` is `youtube.leanback.v4` → outcome `APP_CHANGED`, `appBefore` `com.webos.app.home`, `appAfter` `youtube.leanback.v4`; message contains `switched from com.webos.app.home to youtube.leanback.v4` and `check the screen`; `verify(devices).execute("lg", new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ")))`.
- `stateEventsThatKeepTheSameAppAreNotAChange`: execute answers with a volume change only (same app) → `NO_CHANGE` after the 300 ms timeout; message contains `did not change` and `still com.webos.app.home`.
- `eventsOfOtherDevicesAreIgnored`: execute answers with an event for device `other` showing `youtube.leanback.v4` → `NO_CHANGE`.
- `aPolledDeviceSaysSo`: reporting `POLLED`, change to `YouTube` → `APP_CHANGED`, message contains `polled`.
- `aPolledDeviceWithoutChangeBlamesNothing`: reporting `POLLED`, no event → `NO_CHANGE`, message contains `may be unavailable on this model`.
- `aDeviceThatCannotReportItsAppIsNotObservable`: reporting `NONE` → `NOT_OBSERVABLE` immediately (well under the timeout), message contains `does not report which app is in front`; the link was still sent.
- `aFailedSendIsReportedWithTheReason`: execute throws `new ActionFailedException("LG TV could not open …: 500 Application error")` → `FAILED`, message contains `500 Application error`; same for `DeviceOfflineException("LG TV is not connected")`.
- `aDeviceWithoutAppLinksIsRejected`: capabilities `{REMOTE_KEYS}` → `UnsupportedActionException` containing `cannot open app links`; `execute` never called.
- `anUnknownDeviceIsOffline`: `device("ghost")` empty → `DeviceOfflineException`.
- `aSecondTestOnTheSameDeviceWhileOneRunsIsRefused`: reporting `LIVE`, timeout 1 s, no events; start `run("lg")` on a virtual thread, wait until `execute` was called once, then `run("lg")` on the test thread → `FAILED` with `already running`; the first run then ends `NO_CHANGE`.

`src/test/java/dev/andre/homecontrol/web/DeepLinkTestControllerTest.java` — `@WebMvcTest(DeepLinkTestController.class)`, `@MockitoBean DeviceManager devices`, `@MockitoBean DeepLinkTestService tests`:

- `rendersTheOutcomeAsAnEscapedFragment`: `device("lg")` present; `run("lg")` returns `APP_CHANGED` with message `LG <b>TV</b> switched`; `POST /setup/devices/lg/deep-link-test` → 200, content type compatible with `text/html`, body equals `<p class="deep-link-result app-changed">LG &lt;b&gt;TV&lt;/b&gt; switched</p>`.
- `anUnknownDeviceIsNotFound`: `device("ghost")` empty → 404.
- `aDeviceWithoutAppLinksIsUnprocessable`: `run` throws `UnsupportedActionException("Speaker cannot open app links")` → 422 with that text.

Add to `web/SetupControllerTest.java`:

- `offersTheDeepLinkTestOnlyForDevicesThatOpenAppLinks`: `devices()` → `lg` and `speaker`; `capabilities("lg")` `{APP_LINK}`, `capabilities("speaker")` `{VOLUME}` → page contains `/setup/devices/lg/deep-link-test` and `hx-target="next .deep-link-output"`, not `/setup/devices/speaker/deep-link-test`.

Add to `device/DeviceManagerTest.java` (with `StubAdapter` subclasses overriding `foregroundAppReporting`):

- `foregroundAppReportingIsTheBestOfTheDevicesAdapters`: a device with a `NONE` and a `POLLED` adapter → `POLLED`; with `POLLED` and `LIVE` → `LIVE`; unknown id → `NONE`.

Add to the adapter tests: `AndroidTvAdapterTest.reportsTheForegroundAppLive`, `CastAdapterTest.reportsTheForegroundAppLive`, `WebOsAdapterTest.reportsTheForegroundAppLive`, `TizenAdapterTest.pollsTheForegroundApp` (`POLLED`).

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.playback.DeepLinkTestServiceTest' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*'`
Expected: compilation failure.

- [ ] **Step 3: Implement reporting in core, manager and adapters**

`core/ForegroundAppReporting.java`:

```java
package dev.andre.homecontrol.core;

/** How an adapter learns which app is in front. Declared best first. */
public enum ForegroundAppReporting {
    /** The device pushes every change. */
    LIVE,
    /** The adapter polls, and may only recognise some apps. */
    POLLED,
    /** The adapter cannot tell. */
    NONE
}
```

`core/DeviceAdapter.java` — add:

```java
    /** Whether and how this adapter reports the foreground app; the deep-link test words its answer by it. */
    default ForegroundAppReporting foregroundAppReporting(Device device) {
        return ForegroundAppReporting.NONE;
    }
```

Override it in `AndroidTvAdapter` and `CastAdapter` (`LIVE`), `WebOsAdapter` (`LIVE`), `TizenAdapter` (`POLLED`).

`device/DeviceManager.java` — add:

```java
    public ForegroundAppReporting foregroundAppReporting(String id) {
        return registry.findById(id)
                .flatMap(device -> device.adapters().keySet().stream()
                        .map(adapters::get)
                        .filter(Objects::nonNull)
                        .map(adapter -> adapter.foregroundAppReporting(device))
                        .min(Comparator.naturalOrder()))
                .orElse(ForegroundAppReporting.NONE);
    }
```

- [ ] **Step 4: Implement the service**

`playback/DeepLinkTestProperties.java`:

```java
package dev.andre.homecontrol.playback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/** The test video (Blender Foundation's "Big Buck Bunny", long-lived and region-free) and how long to watch for a change. */
@ConfigurationProperties("home-control.deep-link-test")
public record DeepLinkTestProperties(@DefaultValue("https://www.youtube.com/watch?v=aqz-KE-bpKQ") URI youtubeUrl,
                                     @DefaultValue("10s") Duration timeout) {
}
```

`playback/DeepLinkTestResult.java`:

```java
package dev.andre.homecontrol.playback;

/** {@code appBefore}/{@code appAfter} are whatever the device reports as current app, or null. */
public record DeepLinkTestResult(Outcome outcome, String appBefore, String appAfter, String message) {

    public enum Outcome { APP_CHANGED, NO_CHANGE, NOT_OBSERVABLE, FAILED }
}
```

`playback/DeepLinkTestService.java`:

```java
package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.APP_CHANGED;
import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.FAILED;
import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.NOT_OBSERVABLE;
import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.NO_CHANGE;

/**
 * The setup page's "Test deep link" (spec §11): opens a known YouTube video through the normal
 * app-link action and watches the device's state events for a different foreground app. It says
 * only what the adapter could observe — an app change at best, never which video plays.
 */
@Service
public class DeepLinkTestService {

    private static final String CHECK_THE_SCREEN =
            " No adapter can see which video plays: check the screen for the test video.";

    private final DeviceManager devices;
    private final DeepLinkTestProperties properties;
    private final Map<String, BlockingQueue<DeviceState>> watching = new ConcurrentHashMap<>();

    public DeepLinkTestService(DeviceManager devices, DeepLinkTestProperties properties) {
        this.devices = devices;
        this.properties = properties;
    }

    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        BlockingQueue<DeviceState> queue = watching.get(event.deviceId());
        if (queue != null) {
            queue.add(event.state());
        }
    }

    public DeepLinkTestResult run(String deviceId) {
        Device device = devices.device(deviceId)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + deviceId));
        if (!devices.capabilities(deviceId).contains(Capability.APP_LINK)) {
            throw new UnsupportedActionException(device.name() + " cannot open app links");
        }
        ForegroundAppReporting reporting = devices.foregroundAppReporting(deviceId);
        String before = devices.state(deviceId).currentApp();
        BlockingQueue<DeviceState> queue = new LinkedBlockingQueue<>();
        if (watching.putIfAbsent(deviceId, queue) != null) {
            return new DeepLinkTestResult(FAILED, before, null, "A deep-link test is already running on " + device.name());
        }
        try {
            try {
                devices.execute(deviceId, new Action.OpenAppLink(properties.youtubeUrl()));
            } catch (RuntimeException e) {
                return new DeepLinkTestResult(FAILED, before, null, "The test link was not opened: " + e.getMessage());
            }
            if (reporting == ForegroundAppReporting.NONE) {
                return new DeepLinkTestResult(NOT_OBSERVABLE, before, null, "Sent. " + device.name()
                        + " does not report which app is in front, so this page cannot tell whether YouTube opened."
                        + CHECK_THE_SCREEN);
            }
            Optional<String> after = awaitAnotherApp(queue, before);
            long seconds = Math.max(1, properties.timeout().toSeconds());
            if (after.isPresent()) {
                String how = reporting == ForegroundAppReporting.POLLED
                        ? " (polled; this device only reports a few known apps)." : ".";
                return new DeepLinkTestResult(APP_CHANGED, before, after.get(), device.name() + " switched from "
                        + (before == null ? "no reported app" : before) + " to " + after.get() + how + CHECK_THE_SCREEN);
            }
            String message = reporting == ForegroundAppReporting.POLLED
                    ? "No known app reported itself in front within " + seconds + " seconds. This device is polled and its"
                    + " app status may be unavailable on this model; check the screen."
                    : "The app in front did not change within " + seconds + " seconds"
                    + (before == null ? "" : " (still " + before + ")")
                    + ". If that already was YouTube, go to the Home screen and test again; otherwise the YouTube app"
                    + " may be missing or " + device.name() + " ignored the link.";
            return new DeepLinkTestResult(NO_CHANGE, before, before, message);
        } finally {
            watching.remove(deviceId, queue);
        }
    }

    private Optional<String> awaitAnotherApp(BlockingQueue<DeviceState> queue, String before) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        try {
            for (long left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
                DeviceState next = queue.poll(left, TimeUnit.NANOSECONDS);
                if (next == null) {
                    break;
                }
                if (next.currentApp() != null && !next.currentApp().equals(before)) {
                    return Optional.of(next.currentApp());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
```

In `HomeControlConfiguration` add `DeepLinkTestProperties.class` to `@EnableConfigurationProperties` (create the annotation if the class has none). `src/main/resources/application.yaml` — under `home-control:` add:

```yaml
  deep-link-test:
    youtube-url: https://www.youtube.com/watch?v=aqz-KE-bpKQ
    timeout: 10s
```

- [ ] **Step 5: Implement the endpoint and the button**

`web/DeepLinkTestController.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.DeepLinkTestResult;
import dev.andre.homecontrol.playback.DeepLinkTestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.Locale;

/** htmx target of the setup page's "Test deep link" button; blocks for at most the configured timeout. */
@RestController
public class DeepLinkTestController {

    private final DeviceManager devices;
    private final DeepLinkTestService tests;

    public DeepLinkTestController(DeviceManager devices, DeepLinkTestService tests) {
        this.devices = devices;
        this.tests = tests;
    }

    @PostMapping(path = "/setup/devices/{id}/deep-link-test")
    public ResponseEntity<String> test(@PathVariable String id) {
        if (devices.device(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body("No device with id " + id);
        }
        try {
            DeepLinkTestResult result = tests.run(id);
            String outcome = result.outcome().name().toLowerCase(Locale.ROOT).replace('_', '-');
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                    .body("<p class=\"deep-link-result " + outcome + "\">" + HtmlUtils.htmlEscape(result.message()) + "</p>");
        } catch (UnsupportedActionException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }
}
```

(Use `HttpStatus.UNPROCESSABLE_ENTITY` if that is the constant sub-project A settled on.)

`web/SetupController.populateSetupModel` — add:

```java
        model.addAttribute("deepLinkTestable", devices.devices().stream()
                .map(Device::id)
                .filter(id -> devices.capabilities(id).contains(Capability.APP_LINK))
                .collect(Collectors.toSet()));
```

`templates/setup.html` — make sure `<head>` loads htmx (`<script th:src="@{/vendor/htmx.min.js}" defer></script>`), then inside each paired-device list item add:

```html
                <th:block th:if="${deepLinkTestable.contains(device.id())}">
                    <button type="button" hx-swap="innerHTML" hx-target="next .deep-link-output"
                            th:attr="hx-post=@{/setup/devices/{id}/deep-link-test(id=${device.id()})}">Test deep link</button>
                    <span class="deep-link-output" aria-live="polite"></span>
                    <p class="hint">Opens a YouTube test video on the device and reports what the device told us.
                        Takes up to 10 seconds; the device must be on.</p>
                </th:block>
```

(`next .deep-link-output` rather than an id selector: device ids such as `192-168-1-50` are not valid CSS ids.)

- [ ] **Step 6: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: deep-link test button that reports what the device could observe"
```

---

### Task 5: F5 · Fake servers hardened, end-to-end tests and acceptance checklist

**Files:**
- Create: `adapters/webos/SsapTimeoutException.java`; `web/WebOsEndToEndTest.java`, `web/TizenEndToEndTest.java`; `docs/superpowers/reviews/2026-09-16-smart-tv-acceptance.md`
- Modify: `adapters/webos/SsapConnection.java`, `adapters/webos/WebOsSession.java`; test fakes `adapters/webos/FakeSsapServer.java`, `adapters/tizen/FakeTizenServer.java`, `discovery/ssdp/FakeSsdpResponder.java`; `adapters/webos/SsapConnectionTest.java`, `WebOsSessionTest.java`, `adapters/tizen/TizenSessionTest.java`, `discovery/ssdp/SsdpDiscoveryTest.java`; `README.md`, `compose.yaml`
- Test: the two end-to-end tests above plus the added failure-mode cases

**Interfaces:**
- Consumes: everything from Tasks 1–4; B's `DeviceManager`, `DeviceController` (key/play endpoints and 502 mapping), `SetupController`.
- Produces: `SsapTimeoutException extends IOException`; fake-server hooks `FakeSsapServer.ignoreRequests(String uri)`, `FakeSsapServer.sendRaw(String text)`, `FakeTizenServer.sendRaw(String text)`, `FakeSsdpResponder.sendGarbage()`; the manual checklist; README "Smart TVs" section.

- [ ] **Step 1: Write the failing failure-mode tests**

Add hooks to the fakes:
- `FakeSsapServer.ignoreRequests(String uri)` — requests for that URI are recorded but never answered.
- `FakeSsapServer.sendRaw(String text)` and `FakeTizenServer.sendRaw(String text)` — send a text frame to every open main connection (keep the open `Connection`s in a list in each fake).
- `FakeSsdpResponder.sendGarbage(int port)` — sends `" not ssdp"` and `"HTTP/1.1 200 OK\r\nST: urn:lge-com:service:webos-second-screen:1\r\n\r\n"` (no USN) to `127.0.0.1:port`.

New cases:
- `SsapConnectionTest.garbageFromTheTvIsIgnored`: `sendRaw("not json")` and `sendRaw("{\"type\":\"response\",\"id\":\"nobody\"}")` → a following `request(SYSTEM_INFO)` still succeeds.
- `SsapConnectionTest.anUnansweredRequestTimesOutAsSuch`: `ignoreRequests(SsapUris.SET_VOLUME)` → `request(SET_VOLUME, …)` throws `SsapTimeoutException` after about the 2 s request timeout; the connection stays open (`request(SYSTEM_INFO)` succeeds).
- `WebOsSessionTest.aTvThatStopsAnsweringFailsTheCommandButStaysConnected`: `ignoreRequests(SsapUris.LAUNCH)` → `OpenAppLink(youtube)` throws `ActionFailedException` containing `did not answer in time`; `state().status()` still `CONNECTED`.
- `TizenSessionTest.errorsAndNoiseFromTheTvAreIgnored`: `sendRaw("{\"event\":\"ms.error\",\"data\":{\"message\":\"unrecognized method value\"}}")`, `sendRaw("garbage")` → `HOME` still reaches the fake as `KEY_HOME`.
- `SsdpDiscoveryTest.garbageDatagramsAreIgnored`: `sendGarbage(discovery.listenPort())` → no service, no exception, and a following valid NOTIFY is still learned.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.discovery.*'`
Expected: FAIL — `SsapTimeoutException` does not exist and the session maps a timeout to `DeviceOfflineException`.

- [ ] **Step 2: Distinguish "did not answer" from "connection lost"**

`adapters/webos/SsapTimeoutException.java`:

```java
package dev.andre.homecontrol.adapters.webos;

import java.io.IOException;

/** The connection is up but the TV did not answer this request in time. */
class SsapTimeoutException extends IOException {
    SsapTimeoutException(String message) {
        super(message);
    }
}
```

In `SsapConnection.send`, the `TimeoutException` branch throws `new SsapTimeoutException("No answer from the TV to " + uri + " within " + requestTimeout.toSeconds() + " seconds")`. In `WebOsSession.call` and `WebOsSession.button`, add before the `IOException` branch:

```java
        } catch (SsapTimeoutException e) {
            throw new ActionFailedException(device.name() + " did not answer in time when asked to " + what);
```

(In `button`, `what` is `"press " + name`.) This matches B's rule: a reachable device that does not answer is an `ActionFailedException` (HTTP 502), not an offline device.

Run the Step 1 command again. Expected: PASS.

- [ ] **Step 3: Write the webOS end-to-end test**

`src/test/java/dev/andre/homecontrol/web/WebOsEndToEndTest.java` — the whole path through Spring with real sockets:

```java
@SpringBootTest
@AutoConfigureMockMvc
class WebOsEndToEndTest {

    static final FakeSsapServer TV;
    static final FakeSsdpResponder SSDP;
    static final HttpServer DESCRIPTIONS;
    static final FakeWakeOnLanReceiver WOL;
    static final Path DATA;

    static {
        try {
            TV = new FakeSsapServer(false);
            WOL = new FakeWakeOnLanReceiver();
            DESCRIPTIONS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            DESCRIPTIONS.createContext("/lg/description.xml", exchange -> { /* serve fixtures/ssdp/lg-description.xml as text/xml */ });
            DESCRIPTIONS.start();
            SSDP = new FakeSsdpResponder();
            SSDP.answer(WebOsAdapter.SEARCH_TARGET,
                    FakeSsdpResponder.fixture("lg-search-response.txt", "127.0.0.1", DESCRIPTIONS.getAddress().getPort()));
            DATA = Files.createTempDirectory("webos-e2e");
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
        registry.add("home-control.webos.port", TV::port);
        registry.add("home-control.webos.secure-port", () -> closedPort());
        registry.add("home-control.webos.reconnect-max-delay-seconds", () -> "2");
        registry.add("home-control.webos.wake-grace-seconds", () -> "0");
        registry.add("home-control.tizen.enabled", () -> "false");
        registry.add("home-control.wake-on-lan.broadcast-address", () -> "127.0.0.1");
        registry.add("home-control.wake-on-lan.port", WOL::port);
        registry.add("home-control.deep-link-test.timeout", () -> "5s");
    }

    // @AfterAll closes TV, SSDP, DESCRIPTIONS, WOL.
}
```

(`closedPort()` wraps `FakeWebSocketServer.closedPort()` and rethrows as unchecked.) One test method, `discoversPairsControlsWakesAndTestsALgTv`, with `@Autowired MockMvc mockMvc`, `@Autowired DeviceManager devices`, `@Autowired DeviceRegistry registry`, asserting in order (Awaitility, 10 s each):

1. `GET /setup` eventually contains `[LG] webOS TV OLED55C9PLA` and `action="/setup/prompt-pair"`.
2. `POST /setup/prompt-pair` with `adapter=webos`, `host=127.0.0.1` (no name) → 3xx to `/?device=webos-127-0-0-1`; `registry.findById("webos-127-0-0-1")` has kind `WEBOS`, name `[LG] webOS TV OLED55C9PLA`, `adapterSettings("webos").get("clientKey")` = `FakeSsapServer.CLIENT_KEY`.
3. `devices.state("webos-127-0-0-1").status()` becomes `CONNECTED`; the registry's `macAddress` becomes `A8:23:FE:01:02:03`; `GET /?device=webos-127-0-0-1` contains `/devices/webos-127-0-0-1/input/HDMI_2`.
4. `POST /devices/webos-127-0-0-1/key/DPAD_UP` → 204 and `TV.nextButton()` = `"type:button\nname:UP\n\n"`.
5. `POST /devices/webos-127-0-0-1/input/HDMI_2` → 204 and `TV.nextRequest("ssap://tv/switchInput")` has `payload.inputId` `HDMI_2`.
6. `POST /devices/webos-127-0-0-1/play` with `uri=https://www.youtube.com/watch?v=aqz-KE-bpKQ` → 200 `Open in the YouTube app`; `TV.nextRequest("ssap://system.launcher/launch")` has `payload.params.contentTarget` `https://www.youtube.com/tv?v=aqz-KE-bpKQ`.
7. `TV.changeForegroundApp("com.webos.app.home")`, await `devices.state(...).currentApp()` = `com.webos.app.home`; `POST /setup/devices/webos-127-0-0-1/deep-link-test` → 200 containing `deep-link-result app-changed` and `youtube.leanback.v4`.
8. `TV.refuseConnections(true)` → state `DISCONNECTED`; `POST /devices/webos-127-0-0-1/key/HOME` → 409; `POST /devices/webos-127-0-0-1/key/POWER` → 204 and `WOL.nextPacket()` equals `WakeOnLan.magicPacket("A8:23:FE:01:02:03")`; `TV.refuseConnections(false)` → `CONNECTED` again.
9. `POST /setup/forget` with `id=webos-127-0-0-1` → redirect; the registry no longer has the device; `devices.states()` has no such key.

- [ ] **Step 4: Write the Tizen end-to-end test**

`src/test/java/dev/andre/homecontrol/web/TizenEndToEndTest.java` — same structure with `FakeTizenServer TV`, the Samsung SSDP fixture and description, `home-control.webos.enabled=false`, `home-control.tizen.port=TV.port()`, `rest-port` and `dial-port` = `TV.httpPort()`, `poll-interval-seconds=1`, `wake-grace-seconds=0`, `pairing-timeout-seconds=5`. One test method, `discoversPairsControlsWakesAndTestsASamsungTv`:

1. `GET /setup` eventually contains `[TV] Samsung 8 Series (55)`.
2. `POST /setup/prompt-pair` with `adapter=tizen`, `host=127.0.0.1` → redirect `/?device=tizen-127-0-0-1`; registry settings `paired=true`, `token=73184052`; the fake's first query has no `token=`.
3. Await `CONNECTED`; registry `macAddress` = `70:2A:D5:01:02:03`; a later fake query contains `token=73184052`.
4. `POST /devices/tizen-127-0-0-1/key/HOME` → 204, `TV.nextKey()` = `KEY_HOME`.
5. `POST /devices/tizen-127-0-0-1/play` with `uri=https://www.netflix.com/title/80057281` → 200; `TV.nextLaunch()` = `3201907018807`.
6. `POST /devices/tizen-127-0-0-1/play` with `uri=https://example.org/page` → 422 with a body containing `cannot open web links`.
7. `POST /setup/devices/tizen-127-0-0-1/deep-link-test` → 200 containing `deep-link-result app-changed`, `YouTube` and `polled`; `TV.nextDialBody()` ends with `|v=aqz-KE-bpKQ`.
8. `TV.setDialAvailable(false)`; `POST /devices/tizen-127-0-0-1/play` with a YouTube video link → 502 containing `not available over DIAL`.
9. `TV.switchOff()` → `DISCONNECTED`; `POST …/key/POWER` → 204 and `WOL.nextPacket()` equals `WakeOnLan.magicPacket("70:2A:D5:01:02:03")`; `TV.switchOn()` → `CONNECTED`.
10. `POST /setup/forget` with `id=tizen-127-0-0-1` → redirect; the registry no longer has the device. (UNPAIRED after a revoked token is covered by `TizenSessionTest`.)

- [ ] **Step 5: Run the end-to-end tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.WebOsEndToEndTest' --tests 'dev.andre.homecontrol.web.TizenEndToEndTest'`
Expected: PASS. If a step is flaky, raise that step's Awaitility timeout; never add sleeps before assertions. Step 8 of the webOS test expects 409 for a key on a switched-off TV: that is what sub-project B's fall-through rethrows when the only adapter is offline; if B maps it differently, assert B's status.

Commit:

```bash
git add -A
git commit -m "test: harden the fake TVs and cover LG and Samsung end to end"
```

- [ ] **Step 6: Write the manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-smart-tv-acceptance.md`:

```markdown
# Smart TV adapters (sub-project F) — manual acceptance

Agents cannot operate real TVs. Every item below is **Pending — requires real hardware** until a
person with the household's TVs runs it and replaces the status with Passed/Failed plus notes.
Household TV models are unknown (spec §13, question 1); record them here first.

- LG model / webOS version: _unknown_
- Samsung model / model year / firmware: _unknown_
- Host: Docker with `network_mode: host`: _unknown_

## LG webOS

| # | Check | Status |
|---|---|---|
| L1 | The TV appears under "Devices on this network" within a minute of opening /setup | Pending — requires real hardware |
| L2 | Pair shows the "allow Home Control" prompt on the TV; accepting redirects to the dashboard | Pending — requires real hardware |
| L3 | Declining the prompt shows "declined" on the setup page and stores nothing | Pending — requires real hardware |
| L4 | D-pad, OK, Back, Home, Menu, Info, Guide, Settings, Stop, Rewind, Fast forward act on the TV | Pending — requires real hardware |
| L5 | Play/Pause alternates pause and play in the YouTube app | Pending — requires real hardware |
| L6 | Volume up/down/mute change the TV volume and the chip shows the new level | Pending — requires real hardware |
| L7 | The drawer lists the TV's inputs with their custom names; tapping one switches input | Pending — requires real hardware |
| L8 | Power turns the TV off; the chip shows it off within a few seconds | Pending — requires real hardware |
| L9 | With "Turn on via Wi-Fi" / "Mobile TV On" enabled, Power switches the TV on and it reconnects | Pending — requires real hardware |
| L10 | The MAC address was learned automatically (setup page shows it) and matches the TV's network settings | Pending — requires real hardware |
| L11 | Opening a YouTube video link starts that video (not just the app) — record the webOS version | Pending — requires real hardware |
| L12 | Opening a Netflix title link opens that title (not just the app) — record the result | Pending — requires real hardware |
| L13 | A Prime Video link opens the Prime Video app | Pending — requires real hardware |
| L14 | An ordinary https link opens in the TV browser | Pending — requires real hardware |
| L15 | "Test deep link" reports "switched from … to youtube.leanback.v4" and the test video plays | Pending — requires real hardware |
| L16 | Firmware that closed port 3000 still connects over wss://…:3001 | Pending — requires real hardware |
| L17 | The unsigned registration manifest is accepted (no permission error on pairing) | Pending — requires real hardware |
| L18 | After a factory reset of the TV the device shows UNPAIRED and pairing again restores control | Pending — requires real hardware |
| L19 | Switching the TV on with its own remote reconnects within seconds (SSDP announcement) | Pending — requires real hardware |

## Samsung Tizen

| # | Check | Status |
|---|---|---|
| S1 | The TV appears under "Devices on this network" | Pending — requires real hardware |
| S2 | Pair shows the Allow prompt naming "Home Control"; Allow redirects to the dashboard and a token is stored | Pending — requires real hardware |
| S3 | Deny shows "declined" | Pending — requires real hardware |
| S4 | D-pad, OK, Back, Home, Menu, volume and mute keys act on the TV | Pending — requires real hardware |
| S5 | Absolute volume is refused with "only takes volume up, down and mute keys" | Pending — requires real hardware |
| S6 | Power turns the TV off; with "Power On with Mobile" enabled, Power switches it on again | Pending — requires real hardware |
| S7 | The MAC address was learned from the REST API | Pending — requires real hardware |
| S8 | Standby is shown as off (REST PowerState) within two poll intervals | Pending — requires real hardware |
| S9 | A YouTube video link starts that video through DIAL | Pending — requires real hardware |
| S10 | A Netflix link opens the Netflix app (no title — expected); record the app id found | Pending — requires real hardware |
| S11 | A Prime Video link opens Prime Video, or says it is not installed | Pending — requires real hardware |
| S12 | An ordinary web link is refused with the "cannot open web links" message | Pending — requires real hardware |
| S13 | "Test deep link" reports YouTube in front (polled) and the test video plays | Pending — requires real hardware |
| S14 | The current app label shows YouTube/Netflix while those apps are open (if the model has the applications endpoint) | Pending — requires real hardware |
| S15 | After removing "Home Control" from the TV's device list the device shows UNPAIRED without the prompt reappearing every few seconds | Pending — requires real hardware |

## Both

| # | Check | Status |
|---|---|---|
| B1 | A TV that is also registered under another adapter at the same IP (e.g. a Cast receiver) is merged into one chip when paired | Pending — requires real hardware |
| B2 | Nothing breaks with `HOME_CONTROL_WEBOS_ENABLED=false` / `HOME_CONTROL_TIZEN_ENABLED=false` | Pending — requires real hardware |
| B3 | With bridge networking, discovery finds nothing but pairing by address works (Wake-on-LAN may not) | Pending — requires real hardware |

## Findings

_None yet._
```

- [ ] **Step 7: Document smart TVs**

`README.md` — add a section `## Smart TVs (LG webOS, Samsung Tizen)` after the Cast section with: pairing (the prompt on the TV; the page waits up to 60 s for LG and 30 s for Samsung); what works on each brand as a table (keys, volume incl. absolute on LG only, inputs on LG only, power off, power on via Wake-on-LAN, YouTube video, Netflix title on LG / app only on Samsung, Prime Video app, web links on LG only); the TV settings Wake-on-LAN needs (LG: "Turn on via Wi-Fi" or "Mobile TV On"; Samsung: "Power On with Mobile" / network standby) and that the MAC is learned while the TV is on or can be typed on the setup page; the "Test deep link" button and that it cannot see which video plays; that discovery (SSDP, UDP 1900) and Wake-on-LAN (UDP broadcast) need host networking. Add configuration rows:

| Property | Default | Meaning |
|---|---|---|
| `home-control.ssdp.enabled` | `true` | SSDP discovery for smart TVs |
| `home-control.webos.enabled` | `true` | LG webOS module |
| `home-control.webos.pairing-timeout-seconds` | `60` | How long pairing waits for the prompt |
| `home-control.tizen.enabled` | `true` | Samsung Tizen module |
| `home-control.tizen.client-name` | `Home Control` | Name shown in the Samsung Allow prompt |
| `home-control.tizen.poll-interval-seconds` | `5` | How often Samsung state is polled |
| `home-control.wake-on-lan.broadcast-address` | `255.255.255.255` | Use the subnet broadcast on multi-homed hosts |
| `home-control.deep-link-test.youtube-url` | Big Buck Bunny on YouTube | Video the test button opens |

`compose.yaml` — extend the host-networking comment: "Host networking is REQUIRED for mDNS and SSDP discovery and for Wake-on-LAN broadcasts".

- [ ] **Step 8: Build and commit the documentation**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add README.md compose.yaml docs/superpowers/reviews/2026-09-16-smart-tv-acceptance.md
git commit -m "docs: smart TV setup, limits and the manual acceptance checklist"
```
