# Google Cast Adapter (Sub-project B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Discover and control Google Cast receivers (Chromecast, Cast TVs and speakers, the Shield's built-in Cast): `_googlecast._tcp` discovery that merges a receiver into the matching Android TV device, a live CASTV2 connection with heartbeat and reconnect, volume/mute/stop, Default Media Receiver playback of direct stream URLs with now-playing state, and planner routes for `CastLoad` and `StreamUrl`.

**Architecture:** A new `adapters/cast` module (Spring `@ConditionalOnProperty`, on by default) with an in-house minimal CASTV2 sender in `adapters/cast/protocol` (TLS to port 8009, 4-byte big-endian length-prefixed `CastMessage` protobuf frames, Jackson 3 JSON payloads) — see the ADR. mDNS moves behind one shared `discovery/MdnsBrowser` (one jmDNS instance) that both the Android TV and Cast discoveries register service types with. `DeviceManager` learns to add pairing-free devices, merge/split devices, compose one `DeviceState` from several adapters, and fall through to the next adapter when the first cannot perform an action. Core gains `Action.SetVolume/Mute/Stop/CastLoad`, `DeviceState.nowPlaying`, `Route.Cast` and two route strategies ordered after app link and before media renderer (spec §5.3).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle (run through `.superpowers/gradle.sh`), Thymeleaf, htmx, vanilla ES modules, protobuf-java 4.36.0 + protoc 4.36.0 (already in `build.gradle.kts`), jmDNS 3.6.3 (already present), BouncyCastle `bcpkix-jdk18on` 1.85 (already present; used by the test fake for its certificate), JUnit 5, AssertJ, Mockito, Awaitility. **No new dependency.** Verified on Maven Central 2026-09-16 and rejected (see ADR): `su.litvak.chromecast:api-v2:0.11.3` (2020-04-09, protobuf 2.6 gencode fails with `IncompatibleClassChangeError` on protobuf-java 4.36), `org.digitalmediaserver:cast-api:0.2.0` (2026-09-15, Jackson 2.12 + pre-22 protobuf gencode flagged vulnerable at runtime).

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §4.1 (Cast row), §5.1 (capabilities, merge by IP/name, manual split/merge), §5.2 (`CastLoad`, `StreamUrl`), §5.3 (planner order), §6.2 (now playing from Cast media status), §7 (adapters, one discovery), §11 (Cast library risk), §12 (fake Cast receiver). Roadmap: `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md`, section B. ADR: `docs/superpowers/specs/2026-09-16-cast-sender-adr.md`. Built on sub-project A: `docs/superpowers/plans/2026-09-16-multi-device-core.md`.

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow. No ADB, no developer mode. Cast is pairing-free.
- Commands are ephemeral: a command or play request that cannot be sent now fails now with a reason. Nothing is queued or retried later.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `playback`, `device` and `web` must not import `adapters.cast.protocol` (or `adapters.androidtv.protocol`). `adapters.cast` must not import `adapters.androidtv` (tests included, except the end-to-end test in Task 7).
- Route by capability, not by brand.
- Persistent state stays in `/data` as JSON written atomically; existing `devices.json` and `keystore.p12` keep working without re-pairing. The Android TV certificate alias stays equal to the device id.
- Every adapter is a Spring `@ConditionalOnProperty` module that can be switched off.
- Every adapter has a fake server in tests; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`).

Epic constraints:

- Build and test only through the Docker wrapper (no local JDK): `.superpowers/gradle.sh build`, single tests with `.superpowers/gradle.sh test --tests '<pattern>'`.
- Sub-project A is the contract. Where the real code under `src/main/java/dev/andre/homecontrol` differs from plan A's listings (names of fields, test helper names), adapt the edit to the real code and keep the behaviour this plan specifies.
- `home-control.cast.enabled` (default `true`) switches the whole Cast module; `shield.discovery-enabled` stays the switch for all mDNS (its legacy name is kept for compatibility).
- Jackson 3 API: use `asString(default)`, `asInt(default)`, `asLong(default)`, `asDouble(default)`, `asBoolean(default)` — always the variants with a default, because the no-argument variants throw on missing nodes in Jackson 3. `JsonNode` is iterable; iterating a missing node yields nothing.
- Cast receiver TLS: trust any certificate (receivers are self-signed), no client certificate, no Cast device authentication.
- All Cast wire formats in this plan are normative; tests pin them. Do not "simplify" JSON field names or namespaces.
- A merged device keeps its adapters in order; the first adapter is primary for status display.
- The manual acceptance checklist is never marked passed by an agent.

## Decisions

- Decision: in-house minimal CASTV2 sender instead of a library — both Java libraries fail our constraints (protobuf 2.6 gencode crashes on protobuf 4.36; the fork brings Jackson 2 and vulnerable pre-22 gencode) and the protocol subset is ~15 JSON messages — cost if wrong: ~1 week of protocol bugs we own; fallback is `cast-api` behind the same `DeviceHandle`, local to `adapters/cast`.
- Decision: `cast_channel.proto` contains only `CastMessage` (verbatim from Chromium, BSD header kept); no `DeviceAuthMessage` — senders are not challenged by receivers — cost if wrong: add the auth messages from the same Chromium file and answer challenges.
- Decision: CONNECT carries `origin`, `userAgent` and `senderInfo` exactly like pychromecast — some Google TV builds refuse a bare CONNECT — cost if wrong: none beyond a few bytes.
- Decision: sender id `sender-0` for every message, like pychromecast — receivers accept it for platform and app channels — cost if wrong: switch to a generated `sender-<n>` id for app transports in `CastConnection.send`.
- Decision: heartbeat PING every 5 s, stale after 15 s without any inbound frame (socket read timeout), both configurable — matches receiver behaviour (they close idle senders after ~30 s) — cost if wrong: tune two properties.
- Decision: one shared jmDNS instance (`discovery/MdnsBrowser`) that adapters register service types with, replacing the Android TV discovery's private instance — avoids a second multicast socket (spec §7 "one discovery") — cost if wrong: a jmDNS listener exception affects both adapters; mitigated by per-listener try/catch.
- Decision: Cast groups (TXT `ca` bit 32 or model `Google Cast Group`) are ignored — a group advertises on a member's IP and would be merged into the wrong device; groups belong to sub-project I — cost if wrong: groups do not appear until I.
- Decision: a discovered receiver is *registered* when any device has a `cast` adapter at that host; merge matching is "same host first, else the single device with the same name" — the device id stays host-derived like Android TV — cost if wrong: DHCP address changes need a re-add (already true for Android TV).
- Decision: pairing-free receivers are never registered automatically; only a receiver matching an existing device is merged automatically (on discovery and when a device is adopted). Unmatched receivers appear on the setup page with an **Add** button — a household may have many Cast speakers the user does not want as chips — cost if wrong: one click per Cast-only device.
- Decision: a split-off device is not merged back automatically because the receiver then counts as registered; no "split" marker is persisted — cost if wrong: none; a user who wants it back uses the merge form.
- Decision: merge and split refuse to move an adapter whose credentials are bound to the device id (`DeviceAdapter.credentialsBoundToDeviceId()`, true for Android TV) and tell the user to merge the other way round — moving it would orphan the keystore alias — cost if wrong: users merge in the offered direction.
- Decision: re-pairing (`adopt`) keeps adapters the registry already has for that id — otherwise re-pairing a merged Shield would silently drop its Cast entry — cost if wrong: none.
- Decision: composed device state = primary adapter's status and power; current app, volume and now-playing from the first adapter in order that reports them — the badge must keep telling the user to re-pair an unpaired Android TV even while its Cast side works — cost if wrong: a merged device whose primary is offline shows DISCONNECTED although Cast still works (commands still succeed via fall-through).
- Decision: `DeviceManager.execute` tries every adapter declaring the required capability in order, falling through on `UnsupportedActionException` and `DeviceOfflineException`; `ActionFailedException` (the device answered "no") is final — the Shield declares `VOLUME` through Android TV, which cannot set absolute volume — cost if wrong: an unintended second route for an action; mitigated because only not-sent failures fall through.
- Decision: `Action.SetVolume(int level)` is a percentage 0–100 and `DeviceState` reports Cast volume as `volumeLevel/100` — matches the existing Android TV state shape — cost if wrong: 1 % granularity.
- Decision: `Action.Stop` requires `CAST_RECEIVER` — no other adapter can stop playback yet — cost if wrong: sub-project I widens `Action.requires()` or adds a capability.
- Decision: new core exception `ActionFailedException`, mapped to HTTP 502 — the device was reachable and refused or did not answer, which is neither 409 (offline) nor 422 (cannot) — cost if wrong: a status-code change in one handler.
- Decision: `Action.CastLoad(receiverAppId, load)` is the single playback action for Cast; `load` is the body of a media-namespace `LOAD` without `type`, `requestId`, `sessionId`. The Default Media Receiver body is built by `core/playback/CastLoads` — `PlayableRef.CastLoad` already carries Cast-shaped payloads in core (spec §5.2), and C5 (Jellyfin receiver) reuses the same action — cost if wrong: payload building moves into the adapter.
- Decision: the DMR `LOAD` body sets both `contentId` and `contentUrl` to the stream URL, `streamType` `BUFFERED`, generic metadata with the item title — CAF receivers prefer `contentUrl`, older ones `contentId` — cost if wrong: live HLS may need `LIVE`, added when a source produces live streams.
- Decision: now playing = first `MEDIA_STATUS` entry of the foreground app's media channel, including media started from a phone; `IDLE` clears it; the session polls `GET_STATUS` every 5 s while playing so the position advances — receivers only push on state changes — cost if wrong: one small message per playing device per interval.
- Decision: `DeviceState` gains `NowPlaying nowPlaying` as the last component and keeps the 7-argument constructor — existing call sites and tests keep compiling — cost if wrong: none.
- Decision: the pasted-link form also attaches a `StreamUrl` when the URL path ends in a known media extension, and is shown for `CAST_RECEIVER` devices too — the only way to exercise the DMR route before Jellyfin exists; spec order still prefers the app link on devices that have both — cost if wrong: a Shield opens a direct .mp4 link through its app-link handler rather than Cast (documented).
- Decision: the fake receiver is written once, complete, in Task 3 (it is test infrastructure for Tasks 3–7); Task 7 adds the end-to-end tests over it and the acceptance checklist — cost if wrong: none.
- Decision: module default `home-control.cast.enabled=true` — Cast is pairing-free, only listens on mDNS and connects to receivers the user registered, and is the headline of release 0.7 — cost if wrong: set `HOME_CONTROL_CAST_ENABLED=false`.

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`. Paths below are relative to those roots unless they start with `src/` or `docs/`.

### Files to create

- `docs/superpowers/specs/2026-09-16-cast-sender-adr.md` — ADR (already written; committed in Task 1).
- `src/main/proto/cast_channel.proto` — `CastMessage` wire format.
- `discovery/MdnsBrowser.java` — the one jmDNS instance; adapters register service types.
- `core/DeviceDiscoveredEvent.java` — published when an adapter's discovery resolves a device.
- `core/DeviceStates.java` — composes one `DeviceState` from several adapters.
- `core/ActionFailedException.java` — the device answered but refused, or never answered.
- `core/NowPlaying.java`, `core/PlaybackState.java` — media state for the device strip.
- `core/playback/CastLoads.java` — Default Media Receiver `LOAD` body for a `StreamUrl`.
- `core/playback/CastLoadStrategy.java`, `core/playback/CastStreamStrategy.java` — planner rung 3.
- `adapters/cast/CastConfiguration.java` — the conditional module.
- `adapters/cast/CastProperties.java` — `home-control.cast.*`.
- `adapters/cast/CastSettings.java` — per-device settings under `adapters.cast`.
- `adapters/cast/CastDiscovery.java` — `_googlecast._tcp` browsing and mapping.
- `adapters/cast/CastAdapter.java` — `DeviceAdapter` for Cast.
- `adapters/cast/CastSession.java` — `DeviceHandle`: connect, reconnect, state, commands, playback.
- `adapters/cast/protocol/CastNamespaces.java`, `CastFraming.java`, `CastTls.java`, `CastConnection.java`, `CastIncoming.java`, `CastDisconnectCause.java`, `CastTimeoutException.java`, `CastPayloads.java`, `ReceiverStatus.java`, `MediaStatus.java`.
- Tests: `discovery/MdnsBrowserTest.java`; `core/DeviceStatesTest.java`, `core/DeviceStateTest.java`; `core/playback/CastLoadsTest.java`; `device/StubAdapter.java`, `device/DeviceManagerMergeTest.java`, `device/DeviceManagerExecuteTest.java`; `adapters/cast/CastDiscoveryTest.java`, `CastAdapterTest.java`, `CastModuleSwitchTest.java`, `CastSessionTest.java`; `adapters/cast/protocol/FakeCastReceiver.java`, `CastFramingTest.java`, `CastChannelSchemaTest.java`, `CastPayloadsTest.java`, `ReceiverStatusTest.java`, `MediaStatusTest.java`, `CastConnectionTest.java`; `web/CastEndToEndTest.java`.
- Fixtures: `src/test/resources/fixtures/cast/receiver-status-backdrop.json`, `receiver-status-default-media-receiver.json`, `media-status-playing.json`, `media-status-partial.json`, `media-status-idle.json`.
- `docs/superpowers/reviews/2026-09-16-google-cast-adapter-acceptance.md` — manual checklist, all items pending.

### Files to modify

- `core/DiscoveredDevice.java` — `attributes` map (4-argument constructor kept).
- `core/DeviceAdapter.java` — `kind()`, `settingsFor(DiscoveredDevice)`, `credentialsBoundToDeviceId()`.
- `core/Action.java` — `SetVolume`, `Mute`, `Stop` (Task 4), `CastLoad` (Task 5).
- `core/DeviceState.java` — `nowPlaying` (Task 5).
- `core/playback/Route.java` — `Cast` (Task 6); `core/playback/PlaybackPlanner.java` — explanations (Task 6); `core/playback/AppLinks.java` — media links (Task 6).
- `device/DeviceManager.java` — composed state, add/merge/split, discovery merge (Task 2), fall-through execute (Task 4).
- `playback/PlaybackService.java` — execute `Route.Cast` (Task 6).
- `HomeControlConfiguration.java` — planner strategy list (Task 6).
- `adapters/androidtv/MdnsDiscovery.java` — registers with `MdnsBrowser` (Task 2).
- `adapters/androidtv/AndroidTvAdapter.java` — `kind()`, `credentialsBoundToDeviceId()` (Task 2).
- `adapters/androidtv/AndroidTvSession.java` — exhaustive `execute` switch (Tasks 4, 5).
- `web/SetupController.java`, `templates/setup.html` — addable receivers, merge, split (Task 2).
- `web/DeviceController.java` — volume, mute, stop, 502 (Task 4).
- `web/DashboardController.java`, `templates/dashboard.html` — capability-aware drawer (Task 4), now playing (Task 5), play form for Cast (Task 6).
- `static/js/state-view.js` — now-playing label (Task 5).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.cast.*`.
- `README.md` — Cast section (Task 7).
- Tests of the above: `core/ActionTest.java`, `core/playback/PlaybackPlannerTest.java`, `core/playback/AppLinksTest.java`, `playback/PlaybackServiceTest.java`, `web/SetupControllerTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`, `web/StaticAssetsTest.java`.

### Files to delete

- None.

---

### Task 1: B1 · Cast sender library choice (ADR)

**Files:**
- Commit: `docs/superpowers/specs/2026-09-16-cast-sender-adr.md` (already written by the planner; the spike code was throwaway and is not in the repo)

**Interfaces:**
- Consumes: nothing.
- Produces: the decision "in-house minimal CASTV2 sender, no new dependency" that Tasks 2–7 implement.

- [ ] **Step 1: Read the ADR and check it names the choice, licence and maintenance risk**

Run: `grep -n "^## " docs/superpowers/specs/2026-09-16-cast-sender-adr.md`
Expected: headings `Decision`, `Options considered`, `Licence`, `Maintenance risk`, `Consequences`.

- [ ] **Step 2: Build (nothing changed in code; proves the tree is green before B starts)**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-16-cast-sender-adr.md
git commit -m "docs: ADR for an in-house Cast sender"
```

---
### Task 2: B2 · Cast discovery and device merge

**Files:**
- Create: `discovery/MdnsBrowser.java`, `core/DeviceDiscoveredEvent.java`, `core/DeviceStates.java`, `adapters/cast/CastConfiguration.java`, `adapters/cast/CastSettings.java`, `adapters/cast/CastDiscovery.java`, `adapters/cast/CastAdapter.java`
- Modify: `core/DiscoveredDevice.java`, `core/DeviceAdapter.java`, `device/DeviceManager.java`, `adapters/androidtv/MdnsDiscovery.java`, `adapters/androidtv/AndroidTvAdapter.java`, `web/SetupController.java`, `src/main/resources/templates/setup.html`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `discovery/MdnsBrowserTest.java`, `core/DeviceStatesTest.java`, `device/StubAdapter.java`, `device/DeviceManagerMergeTest.java`, `adapters/cast/CastDiscoveryTest.java`, `adapters/cast/CastAdapterTest.java`, `adapters/cast/CastModuleSwitchTest.java`, `web/SetupControllerTest.java`

**Interfaces:**
- Consumes (from A): `Device`, `DeviceKind.CAST`, `DeviceState`, `DeviceStatus`, `DeviceAdapter`, `DeviceHandle`, `DeviceRegistry`, `DeviceStateChangedEvent(String deviceId, DeviceState state)`, `DeviceOfflineException`, `UnsupportedActionException`, `MdnsDiscovery.toDevice(String, InetAddress[], int)`, `SetupController`, `JsonFileDeviceRegistry`.
- Produces:
  - `record DiscoveredDevice(String adapterId, String name, String host, int port, Map<String,String> attributes)` plus constructor `DiscoveredDevice(String adapterId, String name, String host, int port)`.
  - `record DeviceDiscoveredEvent(DiscoveredDevice device)`.
  - `DeviceAdapter`: `DeviceKind kind();` `default Optional<Map<String,String>> settingsFor(DiscoveredDevice found)` (empty = needs pairing); `default boolean credentialsBoundToDeviceId()` (false).
  - `final class DeviceStates { static DeviceState compose(List<DeviceState> inAdapterOrder); }`
  - `class MdnsBrowser { MdnsBrowser(boolean enabled); void browse(String serviceType, Listener l); interface Listener { void resolved(MdnsService s); void removed(String serviceType, String name); } record MdnsService(String type, String name, List<InetAddress> addresses, int port, Map<String,String> txt) { Optional<String> host(); } }`
  - `DeviceManager`: `List<DiscoveredDevice> pairable()`, `List<DiscoveredDevice> addable()`, `Device addDiscovered(String adapterId, String host, int port)`, `@EventListener void onDiscovered(DeviceDiscoveredEvent)`, `Device merge(String targetId, String sourceId)`, `Device split(String id, String adapterId)`; `adopt` keeps existing adapters and absorbs matching addable receivers; `state(id)` and published events carry the composed state. Refusals throw `IllegalArgumentException` with a user-facing message.
  - `CastSettings(int port, String castId, String model)` with `ADAPTER_ID = "cast"`, `DEFAULT_PORT = 8009`, `of(Device)`, `from(DiscoveredDevice)`, `toMap()`.
  - `CastDiscovery(MdnsBrowser, ApplicationEventPublisher)` with `SERVICE_TYPE = "_googlecast._tcp.local."`, `List<DiscoveredDevice> devices()`, `static Optional<DiscoveredDevice> toDevice(MdnsService)`.
  - `CastAdapter(CastDiscovery)`: id `cast`, kind `CAST`, no capabilities yet, `connect` returns an offline placeholder handle (Task 3 replaces it).
  - Endpoints: `POST /setup/add` (`adapter`, `host`, `port`), `POST /setup/merge` (`target`, `source`), `POST /setup/split` (`id`, `adapter`) → redirect `/setup`, or the setup page with `error` on refusal.
  - Property `home-control.cast.enabled` (default true).

- [ ] **Step 1: Write the failing core tests**

`src/test/java/dev/andre/homecontrol/core/DeviceStatesTest.java`:

```java
package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceStatesTest {

    private static final Instant EARLY = Instant.parse("2026-09-16T10:00:00Z");
    private static final Instant LATE = Instant.parse("2026-09-16T10:05:00Z");

    @Test
    void noAdaptersMeansDisconnected() {
        assertThat(DeviceStates.compose(List.of()).status()).isEqualTo(DeviceStatus.DISCONNECTED);
    }

    @Test
    void aSingleAdaptersStateIsTheDevicesState() {
        DeviceState only = new DeviceState(DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, EARLY);

        assertThat(DeviceStates.compose(List.of(only))).isEqualTo(only);
    }

    @Test
    void theSecondaryFillsInWhatThePrimaryDoesNotReport() {
        DeviceState androidTv = new DeviceState(DeviceStatus.CONNECTED, true, null, 0, 0, false, EARLY);
        DeviceState cast = new DeviceState(DeviceStatus.CONNECTED, true, "Default Media Receiver", 30, 100, true, LATE);

        DeviceState composed = DeviceStates.compose(List.of(androidTv, cast));

        assertThat(composed.status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(composed.currentApp()).isEqualTo("Default Media Receiver");
        assertThat(composed.volumeLevel()).isEqualTo(30);
        assertThat(composed.volumeMax()).isEqualTo(100);
        assertThat(composed.muted()).isTrue();
        assertThat(composed.updatedAt()).isEqualTo(LATE);
    }

    @Test
    void thePrimaryWinsStatusPowerAppAndVolumeWhenItReportsThem() {
        DeviceState androidTv = new DeviceState(DeviceStatus.UNPAIRED, false, "com.netflix.ninja", 12, 100, false, LATE);
        DeviceState cast = new DeviceState(DeviceStatus.CONNECTED, true, "Netflix", 50, 100, true, EARLY);

        DeviceState composed = DeviceStates.compose(List.of(androidTv, cast));

        assertThat(composed.status()).isEqualTo(DeviceStatus.UNPAIRED);
        assertThat(composed.powerOn()).isFalse();
        assertThat(composed.currentApp()).isEqualTo("com.netflix.ninja");
        assertThat(composed.volumeLevel()).isEqualTo(12);
        assertThat(composed.muted()).isFalse();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.DeviceStatesTest'`
Expected: compilation failure — `DeviceStates` does not exist.

- [ ] **Step 3: Write the core types**

`core/DeviceStates.java`:

```java
package dev.andre.homecontrol.core;

import java.time.Instant;
import java.util.List;

/**
 * One device, several adapters (a Shield is Android TV and Cast): the strip shows one state.
 * Status and power come from the primary (first) adapter so an unpaired Android TV still says
 * so; app and volume come from the first adapter, in order, that reports them.
 */
public final class DeviceStates {

    private DeviceStates() {
    }

    public static DeviceState compose(List<DeviceState> inAdapterOrder) {
        if (inAdapterOrder.isEmpty()) {
            return DeviceState.initial();
        }
        if (inAdapterOrder.size() == 1) {
            return inAdapterOrder.getFirst();
        }
        DeviceState primary = inAdapterOrder.getFirst();
        String currentApp = null;
        DeviceState volumeSource = null;
        Instant updatedAt = primary.updatedAt();
        for (DeviceState state : inAdapterOrder) {
            if (currentApp == null && state.currentApp() != null) {
                currentApp = state.currentApp();
            }
            if (volumeSource == null && state.volumeMax() > 0) {
                volumeSource = state;
            }
            if (state.updatedAt().isAfter(updatedAt)) {
                updatedAt = state.updatedAt();
            }
        }
        if (volumeSource == null) {
            volumeSource = primary;
        }
        return new DeviceState(primary.status(), primary.powerOn(), currentApp,
                volumeSource.volumeLevel(), volumeSource.volumeMax(), volumeSource.muted(), updatedAt);
    }
}
```

`core/DeviceDiscoveredEvent.java`:

```java
package dev.andre.homecontrol.core;

/** An adapter's discovery resolved a device on the network (new or changed). */
public record DeviceDiscoveredEvent(DiscoveredDevice device) {
}
```

`core/DiscoveredDevice.java` (replace):

```java
package dev.andre.homecontrol.core;

import java.util.Map;

/**
 * A device seen on the network by one adapter, paired or not. {@code attributes} carries
 * adapter-specific facts from discovery (for Cast: the mDNS TXT {@code id}, {@code md}, {@code fn}).
 */
public record DiscoveredDevice(String adapterId, String name, String host, int port, Map<String, String> attributes) {

    public DiscoveredDevice {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public DiscoveredDevice(String adapterId, String name, String host, int port) {
        this(adapterId, name, host, port, Map.of());
    }
}
```

`core/DeviceAdapter.java`: add, keeping everything A defined:

```java
    /** The family a device created by this adapter alone belongs to. */
    DeviceKind kind();

    /**
     * Settings for registering a discovered device without pairing, or empty when the device
     * must be paired first. Cast returns settings; Android TV returns empty.
     */
    default Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return Optional.empty();
    }

    /**
     * True when this adapter stores credentials under the device id (Android TV: the certificate
     * alias IS the id), so its entry must never move to a device with another id.
     */
    default boolean credentialsBoundToDeviceId() {
        return false;
    }
```

(imports `java.util.Map`, `java.util.Optional`).

`adapters/androidtv/AndroidTvAdapter.java`: add

```java
    @Override
    public DeviceKind kind() {
        return DeviceKind.ANDROID_TV;
    }

    /** The keystore alias is the device id (plan A); moving the entry would orphan the pairing. */
    @Override
    public boolean credentialsBoundToDeviceId() {
        return true;
    }
```

- [ ] **Step 4: Run the core test**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.DeviceStatesTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing discovery tests**

`src/test/java/dev/andre/homecontrol/discovery/MdnsBrowserTest.java`:

```java
package dev.andre.homecontrol.discovery;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class MdnsBrowserTest {

    @Test
    void mapsAResolutionAndPrefersAnIpv4Address() throws Exception {
        InetAddress v6 = InetAddress.getByName("fe80::1");
        InetAddress v4 = InetAddress.getByName("192.168.1.60");

        MdnsBrowser.MdnsService service = MdnsBrowser.toService("_googlecast._tcp.local.", "Chromecast-abc",
                new InetAddress[]{v6, v4}, 8009, Map.of("fn", "Kitchen")).orElseThrow();

        assertThat(service.host()).contains("192.168.1.60");
        assertThat(service.txt()).containsEntry("fn", "Kitchen");
    }

    @Test
    void ignoresAResolutionWithoutAddressOrPort() throws Exception {
        assertThat(MdnsBrowser.toService("t", "n", new InetAddress[0], 8009, Map.of())).isEmpty();
        assertThat(MdnsBrowser.toService("t", "n", new InetAddress[]{InetAddress.getByName("10.0.0.1")}, 0, Map.of()))
                .isEmpty();
    }

    @Test
    void dispatchesOnlyToListenersOfTheResolvedType() throws Exception {
        MdnsBrowser browser = new MdnsBrowser(false);
        List<String> cast = new CopyOnWriteArrayList<>();
        List<String> androidTv = new CopyOnWriteArrayList<>();
        browser.browse("_googlecast._tcp.local.", listener(cast));
        browser.browse("_androidtvremote2._tcp.local.", listener(androidTv));

        browser.dispatchResolved(new MdnsBrowser.MdnsService("_googlecast._tcp.local.", "Chromecast-abc",
                List.of(InetAddress.getByName("10.0.0.9")), 8009, Map.of()));
        browser.dispatchRemoved("_googlecast._tcp.local.", "Chromecast-abc");

        assertThat(cast).containsExactly("resolved Chromecast-abc", "removed Chromecast-abc");
        assertThat(androidTv).isEmpty();
    }

    @Test
    void aFailingListenerDoesNotStopTheOthers() throws Exception {
        MdnsBrowser browser = new MdnsBrowser(false);
        List<String> seen = new CopyOnWriteArrayList<>();
        browser.browse("t", new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void removed(String serviceType, String name) {
            }
        });
        browser.browse("t", listener(seen));

        browser.dispatchResolved(new MdnsBrowser.MdnsService("t", "x", List.of(InetAddress.getByName("10.0.0.9")), 1, Map.of()));

        assertThat(seen).containsExactly("resolved x");
    }

    private static MdnsBrowser.Listener listener(List<String> seen) {
        return new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                seen.add("resolved " + service.name());
            }

            @Override
            public void removed(String serviceType, String name) {
                seen.add("removed " + name);
            }
        };
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/cast/CastDiscoveryTest.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class CastDiscoveryTest {

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final CastDiscovery discovery = new CastDiscovery(new MdnsBrowser(false), published::add);

    private static MdnsBrowser.MdnsService service(String name, String host, Map<String, String> txt) throws Exception {
        return new MdnsBrowser.MdnsService(CastDiscovery.SERVICE_TYPE, name, List.of(InetAddress.getByName(host)), 8009, txt);
    }

    @Test
    void usesTheFriendlyNameAndKeepsIdentityAttributes() throws Exception {
        DiscoveredDevice device = CastDiscovery.toDevice(service("SHIELD-Android-TV-6f1c", "192.168.1.50",
                Map.of("id", "6f1c0e2a9b", "md", "SHIELD Android TV", "fn", "Living Room TV", "ca", "463365", "rs", ""))).orElseThrow();

        assertThat(device).isEqualTo(new DiscoveredDevice("cast", "Living Room TV", "192.168.1.50", 8009,
                Map.of("id", "6f1c0e2a9b", "md", "SHIELD Android TV", "fn", "Living Room TV", "ca", "463365")));
    }

    @Test
    void fallsBackToTheInstanceNameWithoutAFriendlyName() throws Exception {
        assertThat(CastDiscovery.toDevice(service("Chromecast-abc", "10.0.0.9", Map.of())).orElseThrow().name())
                .isEqualTo("Chromecast-abc");
    }

    @Test
    void ignoresCastGroups() throws Exception {
        assertThat(CastDiscovery.toDevice(service("Google-Cast-Group-1", "10.0.0.9", Map.of("ca", "4196645", "md", "Google Cast Group"))))
                .isEmpty();
        assertThat(CastDiscovery.toDevice(service("g", "10.0.0.9", Map.of("ca", "32")))).isEmpty();
    }

    @Test
    void publishesOnceForANewReceiverAndForgetsItWhenRemoved() throws Exception {
        MdnsBrowser.MdnsService kitchen = service("Chromecast-abc", "10.0.0.9", Map.of("fn", "Kitchen"));

        discovery.resolved(kitchen);
        discovery.resolved(kitchen);

        assertThat(discovery.devices()).extracting(DiscoveredDevice::name).containsExactly("Kitchen");
        assertThat(published).singleElement().isInstanceOfSatisfying(DeviceDiscoveredEvent.class,
                event -> assertThat(event.device().host()).isEqualTo("10.0.0.9"));

        discovery.removed("Chromecast-abc");

        assertThat(discovery.devices()).isEmpty();
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/cast/CastAdapterTest.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastAdapterTest {

    private final CastAdapter adapter = new CastAdapter(new CastDiscovery(new MdnsBrowser(false), event -> { }));

    @Test
    void isThePairingFreeCastAdapter() {
        assertThat(adapter.id()).isEqualTo("cast");
        assertThat(adapter.kind()).isEqualTo(DeviceKind.CAST);
        assertThat(adapter.credentialsBoundToDeviceId()).isFalse();
    }

    @Test
    void registersADiscoveredReceiverWithItsPortIdAndModel() {
        DiscoveredDevice found = new DiscoveredDevice("cast", "Kitchen", "10.0.0.9", 8009,
                Map.of("id", "abc123", "md", "Chromecast"));

        assertThat(adapter.settingsFor(found)).contains(Map.of("port", "8009", "castId", "abc123", "model", "Chromecast"));
        assertThat(adapter.settingsFor(new DiscoveredDevice("androidtv", "TV", "10.0.0.5", 6466))).isEmpty();
    }

    @Test
    void readsItsSettingsBackFromADevice() {
        Device device = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8010", "castId", "abc123")), Instant.EPOCH);

        assertThat(CastSettings.of(device)).isEqualTo(new CastSettings(8010, "abc123", null));
    }

    @Test
    void untilTheConnectionExistsTheHandleIsOffline() {
        Device device = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8009")), Instant.EPOCH);

        try (DeviceHandle handle = adapter.connect(device, state -> { })) {
            assertThatThrownBy(() -> handle.execute(new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(DeviceOfflineException.class);
        }
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/cast/CastModuleSwitchTest.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CastModuleSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MdnsBrowser.class, () -> new MdnsBrowser(false))
            .withUserConfiguration(CastConfiguration.class);

    @Test
    void isOnByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(CastAdapter.class).hasSingleBean(CastDiscovery.class));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("home-control.cast.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CastAdapter.class).doesNotHaveBean(CastDiscovery.class));
    }
}
```

- [ ] **Step 6: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.discovery.*' --tests 'dev.andre.homecontrol.adapters.cast.*'`
Expected: compilation failure — `MdnsBrowser`, `CastDiscovery`, `CastAdapter`, `CastSettings`, `CastConfiguration` do not exist.

- [ ] **Step 7: Write the shared mDNS browser**

`discovery/MdnsBrowser.java`:

```java
package dev.andre.homecontrol.discovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The application's one jmDNS instance (spec §7: discovery is one service). Adapters register
 * the service types they care about; nobody else opens a multicast socket.
 *
 * <p>Multicast does not cross a Docker bridge network, so the UI always offers manual entry too.
 */
@Component
public class MdnsBrowser implements AutoCloseable {

    public interface Listener {
        void resolved(MdnsService service);

        void removed(String serviceType, String name);
    }

    public record MdnsService(String type, String name, List<InetAddress> addresses, int port, Map<String, String> txt) {

        public MdnsService {
            addresses = List.copyOf(addresses);
            txt = Map.copyOf(txt);
        }

        /** IPv4 first: home devices are reached over IPv4; link-local IPv6 needs a scope id. */
        public Optional<String> host() {
            return addresses.stream()
                    .min(Comparator.comparingInt(address -> address instanceof Inet4Address ? 0 : 1))
                    .map(InetAddress::getHostAddress);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MdnsBrowser.class);

    private final boolean enabled;
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();

    private JmDNS jmdns;

    public MdnsBrowser(@Value("${shield.discovery-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /** Safe before or after {@link #start()}. */
    public synchronized void browse(String serviceType, Listener listener) {
        List<Listener> forType = listeners.computeIfAbsent(serviceType, type -> new CopyOnWriteArrayList<>());
        boolean firstForType = forType.isEmpty();
        forType.add(listener);
        if (jmdns != null && firstForType) {
            jmdns.addServiceListener(serviceType, new JmdnsListener(serviceType));
        }
    }

    @PostConstruct
    public synchronized void start() {
        if (!enabled) {
            log.info("mDNS discovery is disabled; add devices by host name or address");
            return;
        }
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            listeners.keySet().forEach(type -> jmdns.addServiceListener(type, new JmdnsListener(type)));
            log.info("Listening for {}", listeners.keySet());
        } catch (IOException e) {
            log.warn("Could not start mDNS discovery ({}); use manual entry", e.getMessage());
        }
    }

    /** Pure mapping so it can be tested without multicast. */
    static Optional<MdnsService> toService(String type, String name, InetAddress[] addresses, int port,
                                           Map<String, String> txt) {
        if (addresses == null || addresses.length == 0 || port <= 0) {
            return Optional.empty();
        }
        return Optional.of(new MdnsService(type, name, List.of(addresses), port, txt));
    }

    void dispatchResolved(MdnsService service) {
        for (Listener listener : listeners.getOrDefault(service.type(), List.of())) {
            try {
                listener.resolved(service);
            } catch (RuntimeException e) {
                log.warn("An mDNS listener failed for {}", service.name(), e);
            }
        }
    }

    void dispatchRemoved(String type, String name) {
        for (Listener listener : listeners.getOrDefault(type, List.of())) {
            try {
                listener.removed(type, name);
            } catch (RuntimeException e) {
                log.warn("An mDNS listener failed for {}", name, e);
            }
        }
    }

    private final class JmdnsListener implements ServiceListener {

        private final String type;

        JmdnsListener(String type) {
            this.type = type;
        }

        @Override
        public void serviceAdded(ServiceEvent event) {
            // Resolution arrives via serviceResolved; ask for it explicitly.
            event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1000);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            dispatchRemoved(type, event.getName());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            Map<String, String> txt = new HashMap<>();
            Enumeration<String> names = info.getPropertyNames();
            while (names.hasMoreElements()) {
                String key = names.nextElement();
                String value = info.getPropertyString(key);
                if (value != null) {
                    txt.put(key, value);
                }
            }
            toService(type, info.getName(), info.getInetAddresses(), info.getPort(), txt)
                    .ifPresent(MdnsBrowser.this::dispatchResolved);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (jmdns != null) {
            try {
                jmdns.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
            jmdns = null;
        }
    }
}
```

Rewrite `adapters/androidtv/MdnsDiscovery.java` to register with the browser. Keep `SERVICE_TYPE`, `devices()`, and the static `toDevice(String name, InetAddress[] addresses, int port)` exactly as A left them (its test stays unchanged). Remove the `JmDNS` field, `start()` and the jmDNS parts of `close()`; the class keeps `implements AutoCloseable` with an empty `close()`. Constructors:

```java
    @Autowired
    public MdnsDiscovery(MdnsBrowser browser) {
        browser.browse(SERVICE_TYPE, new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                toDevice(service.name(), service.addresses().toArray(InetAddress[]::new), service.port())
                        .ifPresent(device -> {
                            found.put(service.name(), device);
                            log.info("Discovered {} at {}:{}", device.name(), device.host(), device.port());
                        });
            }

            @Override
            public void removed(String serviceType, String name) {
                found.remove(name);
            }
        });
    }

    /** Outside Spring nothing starts the browser, so this finds nothing: for tests. */
    public MdnsDiscovery(boolean enabled) {
        this(new MdnsBrowser(enabled));
    }
```

- [ ] **Step 8: Write the Cast discovery, settings, placeholder adapter and module**

`adapters/cast/CastSettings.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.LinkedHashMap;
import java.util.Map;

/** The Cast adapter's per-device settings, as stored under {@code adapters.cast}. */
public record CastSettings(int port, String castId, String model) {

    public static final String ADAPTER_ID = "cast";
    public static final int DEFAULT_PORT = 8009;
    static final String PORT = "port";
    static final String CAST_ID = "castId";
    static final String MODEL = "model";

    public static CastSettings of(Device device) {
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no cast adapter");
        }
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new CastSettings(Integer.parseInt(settings.getOrDefault(PORT, String.valueOf(DEFAULT_PORT))),
                settings.get(CAST_ID), settings.get(MODEL));
    }

    /** mDNS TXT {@code id} is the receiver's stable UUID; {@code md} its model name. */
    public static CastSettings from(DiscoveredDevice found) {
        return new CastSettings(found.port(), found.attributes().get("id"), found.attributes().get("md"));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PORT, String.valueOf(port));
        if (castId != null) {
            map.put(CAST_ID, castId);
        }
        if (model != null) {
            map.put(MODEL, model);
        }
        return map;
    }
}
```

`adapters/cast/CastDiscovery.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Finds Cast receivers ({@code _googlecast._tcp}) through the shared mDNS browser. */
public class CastDiscovery {

    public static final String SERVICE_TYPE = "_googlecast._tcp.local.";

    /** TXT {@code ca} capability bit for a multizone group (not a physical receiver). */
    private static final int CAPABILITY_MULTIZONE_GROUP = 32;
    private static final List<String> KEPT_ATTRIBUTES = List.of("id", "md", "fn", "ca");

    private static final Logger log = LoggerFactory.getLogger(CastDiscovery.class);

    private final Map<String, DiscoveredDevice> found = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;

    public CastDiscovery(MdnsBrowser browser, ApplicationEventPublisher events) {
        this.events = events;
        browser.browse(SERVICE_TYPE, new MdnsBrowser.Listener() {
            @Override
            public void resolved(MdnsBrowser.MdnsService service) {
                CastDiscovery.this.resolved(service);
            }

            @Override
            public void removed(String serviceType, String name) {
                CastDiscovery.this.removed(name);
            }
        });
    }

    public List<DiscoveredDevice> devices() {
        return List.copyOf(found.values());
    }

    void resolved(MdnsBrowser.MdnsService service) {
        toDevice(service).ifPresent(device -> {
            DiscoveredDevice previous = found.put(service.name(), device);
            if (!device.equals(previous)) {
                log.info("Discovered Cast receiver {} at {}:{}", device.name(), device.host(), device.port());
                events.publishEvent(new DeviceDiscoveredEvent(device));
            }
        });
    }

    void removed(String name) {
        found.remove(name);
    }

    static Optional<DiscoveredDevice> toDevice(MdnsBrowser.MdnsService service) {
        Optional<String> host = service.host();
        if (host.isEmpty() || isGroup(service.txt())) {
            return Optional.empty();
        }
        String friendlyName = service.txt().getOrDefault("fn", "");
        Map<String, String> attributes = new LinkedHashMap<>();
        KEPT_ATTRIBUTES.forEach(key -> {
            String value = service.txt().get(key);
            if (value != null) {
                attributes.put(key, value);
            }
        });
        return Optional.of(new DiscoveredDevice(CastSettings.ADAPTER_ID,
                friendlyName.isBlank() ? service.name() : friendlyName, host.get(), service.port(), attributes));
    }

    private static boolean isGroup(Map<String, String> txt) {
        if ("Google Cast Group".equals(txt.get("md"))) {
            return true;
        }
        try {
            return (Integer.parseInt(txt.getOrDefault("ca", "0")) & CAPABILITY_MULTIZONE_GROUP) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
```

`adapters/cast/CastAdapter.java` (placeholder `connect`; Task 3 replaces it with a real session):

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Google Cast receivers: Chromecast, Cast TVs and speakers, the Shield's built-in Cast. */
public class CastAdapter implements DeviceAdapter {

    public static final String ID = CastSettings.ADAPTER_ID;

    private final CastDiscovery discovery;

    public CastAdapter(CastDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.CAST;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.noneOf(Capability.class);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        DeviceState offline = DeviceState.initial();
        onChange.accept(offline);
        return new OfflineHandle(offline);
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return discovery.devices();
    }

    @Override
    public Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return ID.equals(found.adapterId()) ? Optional.of(CastSettings.from(found).toMap()) : Optional.empty();
    }

    private record OfflineHandle(DeviceState state) implements DeviceHandle {

        @Override
        public void execute(Action action) {
            throw new DeviceOfflineException("Cast control is not available yet");
        }

        @Override
        public void close() {
        }
    }
}
```

`adapters/cast/CastConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.discovery.MdnsBrowser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Cast module. {@code home-control.cast.enabled=false} removes discovery and the adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.cast", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CastConfiguration {

    @Bean
    public CastDiscovery castDiscovery(MdnsBrowser browser, ApplicationEventPublisher events) {
        return new CastDiscovery(browser, events);
    }

    @Bean
    public CastAdapter castAdapter(CastDiscovery discovery) {
        return new CastAdapter(discovery);
    }
}
```

Append to both `src/main/resources/application.yaml` and `src/test/resources/application.yaml`:

```yaml
home-control:
  cast:
    enabled: true
```

- [ ] **Step 9: Run the discovery and adapter tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.discovery.*' --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS (including A's `MdnsDiscoveryTest`, `AndroidTvAdapterTest`).

- [ ] **Step 10: Write the failing merge tests**

`src/test/java/dev/andre/homecontrol/device/StubAdapter.java`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A scriptable adapter for DeviceManager tests: no network, records what it was asked to do. */
class StubAdapter implements DeviceAdapter {

    private final String id;
    private final DeviceKind kind;
    private final boolean pairingFree;
    private final boolean boundCredentials;
    private final Set<Capability> capabilities;

    final List<DiscoveredDevice> visible = new CopyOnWriteArrayList<>();
    final List<String> forgotten = new CopyOnWriteArrayList<>();
    /** Latest handle per device id. */
    final Map<String, StubHandle> handles = new ConcurrentHashMap<>();

    StubAdapter(String id, DeviceKind kind, boolean pairingFree, boolean boundCredentials, Capability... capabilities) {
        this.id = id;
        this.kind = kind;
        this.pairingFree = pairingFree;
        this.boundCredentials = boundCredentials;
        this.capabilities = EnumSet.noneOf(Capability.class);
        this.capabilities.addAll(List.of(capabilities));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public DeviceKind kind() {
        return kind;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        Set<Capability> copy = EnumSet.noneOf(Capability.class);
        copy.addAll(capabilities);
        return copy;
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        StubHandle handle = new StubHandle(onChange);
        handles.put(device.id(), handle);
        handle.report(DeviceState.initial().withStatus(DeviceStatus.CONNECTED));
        return handle;
    }

    @Override
    public void forget(Device device) {
        forgotten.add(device.id());
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return List.copyOf(visible);
    }

    @Override
    public Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return pairingFree && id.equals(found.adapterId())
                ? Optional.of(Map.of("port", String.valueOf(found.port())))
                : Optional.empty();
    }

    @Override
    public boolean credentialsBoundToDeviceId() {
        return boundCredentials;
    }

    static final class StubHandle implements DeviceHandle {

        private final Consumer<DeviceState> onChange;
        final List<Action> executed = new CopyOnWriteArrayList<>();
        volatile DeviceState state = DeviceState.initial();
        volatile RuntimeException failure;
        volatile boolean closed;

        StubHandle(Consumer<DeviceState> onChange) {
            this.onChange = onChange;
        }

        void report(DeviceState updated) {
            state = updated;
            onChange.accept(updated);
        }

        @Override
        public DeviceState state() {
            return state;
        }

        @Override
        public void execute(Action action) {
            if (failure != null) {
                throw failure;
            }
            executed.add(action);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
```

`src/test/java/dev/andre/homecontrol/device/DeviceManagerMergeTest.java`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.DiscoveredDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceManagerMergeTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, true,
            Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME);
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private DeviceRegistry registry;
    private DeviceManager manager;

    @BeforeEach
    void setUp() {
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        manager = new DeviceManager(registry, List.of(androidtv, cast), published::add);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private static Device shield() {
        return new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466")), Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static DiscoveredDevice receiver(String name, String host) {
        return new DiscoveredDevice("cast", name, host, 8009, Map.of("id", "abc"));
    }

    @Test
    void aReceiverAtTheAddressOfARegisteredDeviceIsMergedIntoIt() {
        registry.save(shield());
        manager.start();

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("SHIELD", "10.0.0.5")));

        Device merged = registry.findById("10-0-0-5").orElseThrow();
        assertThat(List.copyOf(merged.adapters().keySet())).containsExactly("androidtv", "cast");
        assertThat(merged.adapterSettings("cast")).containsEntry("port", "8009");
        assertThat(cast.handles).containsKey("10-0-0-5");
        assertThat(registry.findAll()).hasSize(1);
    }

    @Test
    void aReceiverWithTheSameFriendlyNameIsMergedWhenTheAddressDiffers() {
        registry.save(shield());

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("living room tv ", "10.0.0.77")));

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isTrue();
    }

    @Test
    void anAmbiguousNameIsNotMerged() {
        registry.save(shield());
        registry.save(new Device("10-0-0-6", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.6",
                Map.of("androidtv", Map.of()), Instant.EPOCH));

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("Living Room TV", "10.0.0.77")));

        assertThat(registry.findAll()).noneMatch(device -> device.hasAdapter("cast"));
    }

    @Test
    void anUnmatchedReceiverIsOfferedButNotRegistered() {
        registry.save(shield());
        cast.visible.add(receiver("Kitchen", "10.0.0.9"));

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("Kitchen", "10.0.0.9")));

        assertThat(registry.findAll()).hasSize(1);
        assertThat(manager.addable()).extracting(DiscoveredDevice::name).containsExactly("Kitchen");
        assertThat(manager.pairable()).isEmpty();
    }

    @Test
    void addingAnUnmatchedReceiverRegistersACastDevice() {
        cast.visible.add(receiver("Kitchen", "10.0.0.9"));

        Device added = manager.addDiscovered("cast", "10.0.0.9", 8009);

        assertThat(added.id()).isEqualTo("cast-10-0-0-9");
        assertThat(added.kind()).isEqualTo(DeviceKind.CAST);
        assertThat(added.name()).isEqualTo("Kitchen");
        assertThat(registry.findById("cast-10-0-0-9")).isPresent();
        assertThat(manager.addable()).isEmpty();
        assertThatThrownBy(() -> manager.addDiscovered("cast", "10.0.0.9", 8009))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already added");
        assertThatThrownBy(() -> manager.addDiscovered("cast", "10.0.0.99", 8009))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no longer visible");
    }

    @Test
    void rePairingAMergedDeviceKeepsItsCastEntry() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));

        manager.adopt(new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466", "certificateFingerprint", "AB")), Instant.now()));

        Device device = registry.findById("10-0-0-5").orElseThrow();
        assertThat(List.copyOf(device.adapters().keySet())).containsExactly("androidtv", "cast");
        assertThat(device.adapterSettings("androidtv")).containsEntry("certificateFingerprint", "AB");
    }

    @Test
    void adoptingAnAndroidTvAbsorbsAnUnregisteredReceiverAtTheSameAddress() {
        cast.visible.add(receiver("SHIELD", "10.0.0.5"));

        manager.adopt(shield());

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isTrue();
    }

    @Test
    void mergeMovesTheSourceAdaptersAndDeletesTheSourceWithoutForgettingCredentials() {
        registry.save(shield());
        registry.save(new Device("cast-10-0-0-5", "SHIELD", DeviceKind.CAST, "10.0.0.5",
                Map.of("cast", Map.of("port", "8009")), Instant.EPOCH));
        manager.start();
        published.clear();

        Device merged = manager.merge("10-0-0-5", "cast-10-0-0-5");

        assertThat(List.copyOf(merged.adapters().keySet())).containsExactly("androidtv", "cast");
        assertThat(registry.findById("cast-10-0-0-5")).isEmpty();
        assertThat(cast.forgotten).isEmpty();
        assertThat(published).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                DeviceStateChangedEvent.class, e -> assertThat(e.deviceId()).isEqualTo("cast-10-0-0-5")));
    }

    @Test
    void mergeRefusesToMoveAPairingBoundToTheDeviceId() {
        registry.save(shield());
        registry.save(new Device("cast-10-0-0-5", "SHIELD", DeviceKind.CAST, "10.0.0.5",
                Map.of("cast", Map.of("port", "8009")), Instant.EPOCH));

        assertThatThrownBy(() -> manager.merge("cast-10-0-0-5", "10-0-0-5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("other way round");
        assertThat(registry.findAll()).hasSize(2);
        assertThatThrownBy(() -> manager.merge("10-0-0-5", "10-0-0-5")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitMovesOneAdapterIntoANewDeviceThatIsNotMergedBack() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));
        manager.start();

        Device split = manager.split("10-0-0-5", "cast");

        assertThat(split.id()).isEqualTo("cast-10-0-0-5");
        assertThat(split.name()).isEqualTo("Living Room TV (cast)");
        assertThat(split.kind()).isEqualTo(DeviceKind.CAST);
        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isFalse();

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("Living Room TV", "10.0.0.5")));

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isFalse();
    }

    @Test
    void splitRefusesTheOnlyAdapterAndABoundPairing() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));
        registry.save(new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of()), Instant.EPOCH));

        assertThatThrownBy(() -> manager.split("10-0-0-5", "androidtv"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be split off");
        assertThatThrownBy(() -> manager.split("cast-10-0-0-9", "cast"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only one connection");
    }

    @Test
    void stateAndEventsCarryTheComposedStateOfAllAdapters() {
        registry.save(shield().withAdapter("cast", Map.of("port", "8009")));
        manager.start();
        published.clear();

        cast.handles.get("10-0-0-5").report(new DeviceState(DeviceStatus.CONNECTED, true, "Default Media Receiver",
                40, 100, false, Instant.now()));

        assertThat(manager.state("10-0-0-5").currentApp()).isEqualTo("Default Media Receiver");
        assertThat(manager.state("10-0-0-5").volumeLevel()).isEqualTo(40);
        assertThat(published).last().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
            assertThat(event.deviceId()).isEqualTo("10-0-0-5");
            assertThat(event.state().currentApp()).isEqualTo("Default Media Receiver");
            assertThat(event.state().status()).isEqualTo(DeviceStatus.CONNECTED);
        });
    }
}
```

- [ ] **Step 11: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.device.DeviceManagerMergeTest'`
Expected: compilation failure — `onDiscovered`, `addable`, `pairable`, `addDiscovered`, `merge`, `split` do not exist.

- [ ] **Step 12: Rewrite `DeviceManager`**

Replace `device/DeviceManager.java` with the following. Methods A defined (`devices`, `device`, `defaultDevice`, `states`, `capabilities`, `execute`, `forget`, `discovered`, `close`) keep their behaviour; if the real class has extra members A's later tasks added, keep them.

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceDiscoveredEvent;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStates;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.UnsupportedActionException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Owns one live {@link DeviceHandle} per registered device and adapter, composes their states
 * into one per device, and is the only place devices are added, merged, split or forgotten.
 */
@Service
public class DeviceManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceManager.class);

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters = new LinkedHashMap<>();
    private final ApplicationEventPublisher events;
    /** device id → (adapter id → handle), in the device's adapter order. */
    private final Map<String, Map<String, DeviceHandle>> handles = new ConcurrentHashMap<>();
    /** device id → (adapter id → last state that adapter reported), in the device's adapter order. */
    private final Map<String, Map<String, DeviceState>> reported = new ConcurrentHashMap<>();

    public DeviceManager(DeviceRegistry registry, List<DeviceAdapter> adapters, ApplicationEventPublisher events) {
        this.registry = registry;
        adapters.forEach(adapter -> this.adapters.put(adapter.id(), adapter));
        this.events = events;
    }

    @PostConstruct
    public void start() {
        registry.findAll().forEach(this::connect);
    }

    public List<Device> devices() {
        return registry.findAll().stream()
                .sorted(Comparator.comparing(Device::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<Device> device(String id) {
        return registry.findById(id);
    }

    public Optional<Device> defaultDevice() {
        return registry.findAll().stream().max(Comparator.comparing(Device::lastSeen));
    }

    /** The composed state of the device's adapters; unknown or handle-less devices read as DISCONNECTED. */
    public DeviceState state(String id) {
        Map<String, DeviceState> states = reported.get(id);
        if (states == null) {
            return DeviceState.initial();
        }
        synchronized (states) {
            return DeviceStates.compose(List.copyOf(states.values()));
        }
    }

    public Map<String, DeviceState> states() {
        Map<String, DeviceState> states = new LinkedHashMap<>();
        devices().forEach(device -> states.put(device.id(), state(device.id())));
        return states;
    }

    public Set<Capability> capabilities(String id) {
        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        registry.findById(id).ifPresent(device -> device.adapters().keySet().forEach(adapterId -> {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter != null) {
                capabilities.addAll(adapter.capabilities(device));
            }
        }));
        return capabilities;
    }

    /** Sends through the first of the device's adapters that declares the needed capability. */
    public void execute(String id, Action action) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + id));
        Map<String, DeviceHandle> deviceHandles = handles.getOrDefault(id, Map.of());
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            DeviceHandle handle = deviceHandles.get(adapterId);
            if (adapter != null && handle != null && adapter.capabilities(device).contains(action.requires())) {
                handle.execute(action);
                return;
            }
        }
        throw new UnsupportedActionException(device.name() + " cannot perform " + action);
    }

    /**
     * Registers a freshly paired device and brings it up. Adapters the registry already has for
     * this id (a Cast entry on a re-paired Shield) are kept, and pairing-free receivers seen at
     * the same address or under the same name are merged in.
     */
    public synchronized void adopt(Device device) {
        Device adopted = registry.findById(device.id())
                .map(existing -> keepOtherAdapters(existing, device))
                .orElse(device);
        adopted = absorbAddable(adopted);
        registry.save(adopted);
        connect(adopted);
    }

    public synchronized void forget(String id) {
        Optional<Device> registered = registry.findById(id);
        if (registered.isEmpty()) {
            return;
        }
        Device device = registered.get();
        closeHandles(id);
        device.adapters().keySet().forEach(adapterId -> {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter != null) {
                adapter.forget(device);
            }
        });
        registry.delete(id);
        events.publishEvent(new DeviceStateChangedEvent(id, DeviceState.initial()));
    }

    public List<DiscoveredDevice> discovered() {
        return adapters.values().stream().flatMap(adapter -> adapter.discovered().stream()).toList();
    }

    /** Discovered devices that need the pairing flow. */
    public List<DiscoveredDevice> pairable() {
        return discovered().stream().filter(found -> !pairingFree(found)).toList();
    }

    /** Pairing-free devices on the network that no registered device carries yet. */
    public List<DiscoveredDevice> addable() {
        List<Device> registered = registry.findAll();
        return discovered().stream()
                .filter(this::pairingFree)
                .filter(found -> !isRegistered(registered, found))
                .toList();
    }

    /** The setup page's "Add": merge into the matching device, or register a new one. */
    public synchronized Device addDiscovered(String adapterId, String host, int port) {
        DeviceAdapter adapter = adapters.get(adapterId);
        if (adapter == null) {
            throw new IllegalArgumentException("The " + adapterId + " module is switched off");
        }
        DiscoveredDevice found = adapter.discovered().stream()
                .filter(candidate -> candidate.host().equalsIgnoreCase(host) && candidate.port() == port)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That device is no longer visible on the network"));
        Map<String, String> settings = adapter.settingsFor(found)
                .orElseThrow(() -> new IllegalArgumentException(found.name() + " has to be paired, not added"));
        List<Device> registered = registry.findAll();
        if (isRegistered(registered, found)) {
            throw new IllegalArgumentException(found.name() + " is already added");
        }
        Device device = bestMatch(registered, found)
                .map(target -> target.withAdapter(adapterId, settings))
                .orElseGet(() -> new Device(uniqueId(registered, adapterId, found.host()), found.name(),
                        adapter.kind(), found.host(), Map.of(adapterId, settings), Instant.now()));
        registry.save(device);
        connect(device);
        return device;
    }

    /** Automatic merge (spec §5.1): only into an existing device, never creating one. */
    @EventListener
    public synchronized void onDiscovered(DeviceDiscoveredEvent event) {
        DiscoveredDevice found = event.device();
        DeviceAdapter adapter = adapters.get(found.adapterId());
        if (adapter == null) {
            return;
        }
        Optional<Map<String, String>> settings = adapter.settingsFor(found);
        List<Device> registered = registry.findAll();
        if (settings.isEmpty() || isRegistered(registered, found)) {
            return;
        }
        bestMatch(registered, found).ifPresent(target -> {
            Device merged = target.withAdapter(found.adapterId(), settings.get());
            registry.save(merged);
            connect(merged);
            log.info("Merged {} receiver {} into {}", found.adapterId(), found.name(), target.name());
        });
    }

    /** Moves every adapter of {@code source} into {@code target} and removes {@code source}. */
    public synchronized Device merge(String targetId, String sourceId) {
        if (targetId.equals(sourceId)) {
            throw new IllegalArgumentException("Pick two different devices to merge");
        }
        Device target = registry.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("No device with id " + targetId));
        Device source = registry.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("No device with id " + sourceId));
        Device merged = target;
        for (Map.Entry<String, Map<String, String>> entry : source.adapters().entrySet()) {
            String adapterId = entry.getKey();
            if (target.hasAdapter(adapterId)) {
                throw new IllegalArgumentException(target.name() + " already has a " + adapterId + " connection");
            }
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter != null && adapter.credentialsBoundToDeviceId()) {
                throw new IllegalArgumentException("Merge the other way round: the " + adapterId
                        + " pairing of " + source.name() + " only works under its own id");
            }
            merged = merged.withAdapter(adapterId, entry.getValue());
        }
        // Credentials move with the settings, so the source is removed WITHOUT adapter.forget().
        closeHandles(sourceId);
        registry.delete(sourceId);
        events.publishEvent(new DeviceStateChangedEvent(sourceId, DeviceState.initial()));
        registry.save(merged);
        connect(merged);
        return merged;
    }

    /** Moves one adapter out of a device into a new device of its own. */
    public synchronized Device split(String id, String adapterId) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No device with id " + id));
        if (!device.hasAdapter(adapterId)) {
            throw new IllegalArgumentException(device.name() + " has no " + adapterId + " connection");
        }
        if (device.adapters().size() < 2) {
            throw new IllegalArgumentException(device.name() + " has only one connection; there is nothing to split");
        }
        DeviceAdapter adapter = adapters.get(adapterId);
        if (adapter != null && adapter.credentialsBoundToDeviceId()) {
            throw new IllegalArgumentException("The " + adapterId + " pairing belongs to " + device.name()
                    + " and cannot be split off; split the other connections instead");
        }
        Map<String, Map<String, String>> remaining = new LinkedHashMap<>(device.adapters());
        remaining.remove(adapterId);
        Device rest = new Device(device.id(), device.name(), device.kind(), device.host(), remaining, device.lastSeen());
        Device split = new Device(uniqueId(registry.findAll(), adapterId, device.host()),
                device.name() + " (" + adapterId + ")",
                adapter != null ? adapter.kind() : device.kind(), device.host(),
                Map.of(adapterId, device.adapterSettings(adapterId)), device.lastSeen());
        registry.save(rest);
        registry.save(split);
        connect(rest);
        connect(split);
        return split;
    }

    private boolean pairingFree(DiscoveredDevice found) {
        DeviceAdapter adapter = adapters.get(found.adapterId());
        return adapter != null && adapter.settingsFor(found).isPresent();
    }

    /**
     * Registered once any device carries that adapter at the found address — including a
     * device split off earlier, which is why a split is never merged back automatically.
     */
    static boolean isRegistered(List<Device> registered, DiscoveredDevice found) {
        return registered.stream().anyMatch(device ->
                device.hasAdapter(found.adapterId()) && device.host().equalsIgnoreCase(found.host()));
    }

    /** Same address first; otherwise the single device with the same name. Never one that already has the adapter. */
    static Optional<Device> bestMatch(List<Device> registered, DiscoveredDevice found) {
        List<Device> candidates = registered.stream().filter(device -> !device.hasAdapter(found.adapterId())).toList();
        Optional<Device> byHost = candidates.stream()
                .filter(device -> device.host().equalsIgnoreCase(found.host()))
                .findFirst();
        if (byHost.isPresent()) {
            return byHost;
        }
        List<Device> byName = candidates.stream().filter(device -> sameName(device.name(), found.name())).toList();
        return byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
    }

    static boolean sameName(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }

    static Device keepOtherAdapters(Device existing, Device adopted) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>(existing.adapters());
        merged.putAll(adopted.adapters());
        return new Device(adopted.id(), adopted.name(), existing.kind(), adopted.host(), merged, adopted.lastSeen());
    }

    private Device absorbAddable(Device device) {
        Device result = device;
        for (DiscoveredDevice found : addable()) {
            boolean matches = found.host().equalsIgnoreCase(result.host()) || sameName(found.name(), result.name());
            if (matches && !result.hasAdapter(found.adapterId())) {
                result = result.withAdapter(found.adapterId(),
                        adapters.get(found.adapterId()).settingsFor(found).orElseThrow());
            }
        }
        return result;
    }

    static String uniqueId(List<Device> registered, String adapterId, String host) {
        String base = adapterId + "-" + host.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        Set<String> taken = registered.stream().map(Device::id).collect(Collectors.toSet());
        String id = base;
        for (int n = 2; taken.contains(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private void connect(Device device) {
        closeHandles(device.id());
        Map<String, DeviceState> states = new LinkedHashMap<>();
        device.adapters().keySet().stream()
                .filter(adapters::containsKey)
                .forEach(adapterId -> states.put(adapterId, DeviceState.initial()));
        reported.put(device.id(), states);
        Map<String, DeviceHandle> deviceHandles = new LinkedHashMap<>();
        for (String adapterId : List.copyOf(states.keySet())) {
            deviceHandles.put(adapterId, adapters.get(adapterId).connect(device,
                    state -> report(device.id(), states, adapterId, state)));
        }
        handles.put(device.id(), deviceHandles);
    }

    /** Publishes inside the lock so two adapters' updates reach SSE in the order they were composed. */
    private void report(String deviceId, Map<String, DeviceState> states, String adapterId, DeviceState state) {
        synchronized (states) {
            if (reported.get(deviceId) != states) {
                return; // a handle from a closed generation reporting late
            }
            states.put(adapterId, state);
            events.publishEvent(new DeviceStateChangedEvent(deviceId,
                    DeviceStates.compose(List.copyOf(states.values()))));
        }
    }

    private void closeHandles(String id) {
        reported.remove(id);
        Map<String, DeviceHandle> existing = handles.remove(id);
        if (existing != null) {
            existing.values().forEach(DeviceHandle::close);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        handles.keySet().forEach(this::closeHandles);
    }
}
```

- [ ] **Step 13: Run the manager tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.device.*'`
Expected: PASS — the new merge tests and A's `DeviceManagerTest`.

- [ ] **Step 14: Write the failing setup page tests**

Add to `web/SetupControllerTest.java` (it already has `@MockitoBean DeviceManager devices` and `@MockitoBean PairingService` from plan A; reuse the real field names). Stub `devices.devices()`, `devices.pairable()` and `devices.addable()` to empty lists in a `@BeforeEach` unless a test overrides them, and replace any stubbing of `devices.discovered()` from A with `devices.pairable()`.

```java
    @Test
    void offersDiscoveredCastReceiversWithAnAddButton() throws Exception {
        given(devices.addable()).willReturn(List.of(
                new DiscoveredDevice("cast", "Kitchen speaker", "10.0.0.9", 8009, Map.of())));

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kitchen speaker")))
                .andExpect(content().string(containsString("action=\"/setup/add\"")))
                .andExpect(content().string(containsString("value=\"8009\"")));
    }

    @Test
    void addsADiscoveredReceiver() throws Exception {
        mockMvc.perform(post("/setup/add").param("adapter", "cast").param("host", "10.0.0.9").param("port", "8009"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));

        verify(devices).addDiscovered("cast", "10.0.0.9", 8009);
    }

    @Test
    void mergesAndSplitsDevices() throws Exception {
        mockMvc.perform(post("/setup/merge").param("target", "10-0-0-5").param("source", "cast-10-0-0-5"))
                .andExpect(redirectedUrl("/setup"));
        mockMvc.perform(post("/setup/split").param("id", "10-0-0-5").param("adapter", "cast"))
                .andExpect(redirectedUrl("/setup"));

        verify(devices).merge("10-0-0-5", "cast-10-0-0-5");
        verify(devices).split("10-0-0-5", "cast");
    }

    @Test
    void showsWhyAMergeWasRefused() throws Exception {
        given(devices.merge("a", "b")).willThrow(new IllegalArgumentException("Merge the other way round: nope"));

        mockMvc.perform(post("/setup/merge").param("target", "a").param("source", "b"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Merge the other way round: nope")));
    }

    @Test
    void offersSplitOnlyForDevicesWithSeveralConnectionsAndMergeOnlyWithTwoDevices() throws Exception {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("androidtv", Map.of());
        adapters.put("cast", Map.of());
        Device shield = new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                adapters, Instant.EPOCH);
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of()), Instant.EPOCH);
        given(devices.devices()).willReturn(List.of(kitchen, shield));

        mockMvc.perform(get("/setup"))
                .andExpect(content().string(containsString("action=\"/setup/split\"")))
                .andExpect(content().string(containsString("name=\"adapter\" value=\"cast\"")))
                .andExpect(content().string(not(containsString("name=\"adapter\" value=\"androidtv\""))))
                .andExpect(content().string(containsString("action=\"/setup/merge\"")));
    }
```

Imports needed beyond A's: `DiscoveredDevice`, `Device`, `DeviceKind` (from `core`), `java.util.LinkedHashMap`, `java.util.Map`, `java.time.Instant`, `org.hamcrest.Matchers.not`, `MockMvcRequestBuilders.post`, `MockMvcResultMatchers.redirectedUrl`, `Mockito.verify`, `BDDMockito.given`. The adapters map is a `LinkedHashMap` so "androidtv" is reliably the first (primary) adapter.

- [ ] **Step 15: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.SetupControllerTest'`
Expected: FAIL — no `/setup/add`, `/setup/merge`, `/setup/split`; no `addable` in the model.

- [ ] **Step 16: Implement the setup page**

`SetupController`: `populateSetupModel` sets `discovered` = `devices.pairable()`, `addable` = `devices.addable()`, `paired` = `devices.devices()` (keep `awaitingCode` and anything else A sets). Add:

```java
    @PostMapping("/setup/add")
    public String add(@RequestParam String adapter, @RequestParam String host, @RequestParam int port, Model model) {
        return refusable(model, () -> devices.addDiscovered(adapter, host, port));
    }

    @PostMapping("/setup/merge")
    public String merge(@RequestParam String target, @RequestParam String source, Model model) {
        return refusable(model, () -> devices.merge(target, source));
    }

    @PostMapping("/setup/split")
    public String split(@RequestParam String id, @RequestParam String adapter, Model model) {
        return refusable(model, () -> devices.split(id, adapter));
    }

    /** A refusal is a sentence for the user, shown on the page; success goes back to setup. */
    private String refusable(Model model, Runnable change) {
        try {
            change.run();
            return "redirect:/setup";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            populateSetupModel(model, false);
            return "setup";
        }
    }
```

(`devices` is whatever A named the `DeviceManager` field; the three manager methods return a `Device`, which the `Runnable` lambdas discard.)

`setup.html`: inside the `th:unless="${awaitingCode}"` section, after the discovered list, add:

```html
        <section th:if="${!#lists.isEmpty(addable)}">
            <h2>Ready to add (no pairing needed)</h2>
            <ul>
                <li th:each="device : ${addable}">
                    <form method="post" th:action="@{/setup/add}">
                        <input type="hidden" name="adapter" th:value="${device.adapterId()}">
                        <input type="hidden" name="host" th:value="${device.host()}">
                        <input type="hidden" name="port" th:value="${device.port()}">
                        <span th:text="${device.name()} + ' (' + ${device.host()} + ')'">Kitchen speaker</span>
                        <button type="submit">Add</button>
                    </form>
                </li>
            </ul>
            <p class="hint">A receiver at the same address as a paired TV is added to that TV instead of appearing twice.</p>
        </section>
```

In each paired-device `<li>` (A's list), after the forget form, add split buttons for every adapter except the first:

```html
                <th:block th:if="${device.adapters().size() > 1}">
                    <form method="post" th:action="@{/setup/split}"
                          th:each="adapterId, iteration : ${device.adapters().keySet()}" th:unless="${iteration.first}">
                        <input type="hidden" name="id" th:value="${device.id()}">
                        <input type="hidden" name="adapter" th:value="${adapterId}">
                        <button type="submit" th:text="'Split ' + ${adapterId} + ' into its own device'">Split</button>
                    </form>
                </th:block>
```

After the paired-devices section add:

```html
    <section th:if="${#lists.size(paired) > 1}">
        <h2>Merge devices</h2>
        <p class="hint">If one TV shows up twice — for example as an Android TV and as a Cast receiver — merge them.</p>
        <form method="post" th:action="@{/setup/merge}">
            <label>Move
                <select name="source">
                    <option th:each="device : ${paired}" th:value="${device.id()}" th:text="${device.name()}">Kitchen</option>
                </select>
            </label>
            <label>into
                <select name="target">
                    <option th:each="device : ${paired}" th:value="${device.id()}" th:text="${device.name()}">Shield</option>
                </select>
            </label>
            <button type="submit">Merge</button>
        </form>
    </section>
```

(Thymeleaf renders `name="adapter" value="cast"` with that attribute order because `name` is a literal attribute before `th:value`; if the test's exact substring does not match the rendered order, assert on `value="cast"` inside the split form instead.)

- [ ] **Step 17: Run the web tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL (the application context starts with `MdnsBrowser`, `MdnsDiscovery`, `CastConfiguration`).

- [ ] **Step 18: Commit**

```bash
git add -A
git commit -m "feat: discover Cast receivers and merge them into existing devices"
```

---

### Task 3: B3 · Cast connection handle

**Files:**
- Create: `src/main/proto/cast_channel.proto`, `adapters/cast/protocol/CastNamespaces.java`, `CastFraming.java`, `CastTls.java`, `CastIncoming.java`, `CastDisconnectCause.java`, `CastTimeoutException.java`, `CastPayloads.java`, `CastConnection.java`, `ReceiverStatus.java`; `adapters/cast/CastProperties.java`, `adapters/cast/CastSession.java`
- Modify: `adapters/cast/CastAdapter.java`, `adapters/cast/CastConfiguration.java`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `adapters/cast/protocol/FakeCastReceiver.java`, `CastFramingTest.java`, `CastChannelSchemaTest.java`, `CastPayloadsTest.java`, `ReceiverStatusTest.java`, `CastConnectionTest.java`; `adapters/cast/CastSessionTest.java`, `adapters/cast/CastAdapterTest.java`; fixtures `src/test/resources/fixtures/cast/receiver-status-backdrop.json`, `receiver-status-default-media-receiver.json`

**Interfaces:**
- Consumes: `CastSettings`, `CastDiscovery`, `DeviceHandle`, `DeviceState` (7-component record from A), `DeviceStatus`, `DeviceOfflineException`, `UnsupportedActionException`, `Action.PressKey`, `Action.OpenAppLink`.
- Produces:
  - Generated `dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage` (`ProtocolVersion.CASTV2_1_0`, `PayloadType.STRING|BINARY`).
  - `CastFraming(InputStream, OutputStream)`: `synchronized void write(CastMessage)`, `CastMessage read()` (null on clean EOF), `MAX_MESSAGE_BYTES = 65536`.
  - `CastNamespaces` constants `CONNECTION`, `HEARTBEAT`, `RECEIVER`, `MEDIA`, `SENDER_ID = "sender-0"`, `PLATFORM_RECEIVER_ID = "receiver-0"`, `DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"`.
  - `CastPayloads`: `connect()`, `close()`, `ping()`, `pong()`, `getStatus()`, `launch(appId)`, `stop(sessionId)`, `setVolumeLevel(double)`, `setMuted(boolean)`, `load(sessionId, Map<String,Object>)`, `pause(long)`, `play(long)`, `stopMedia(long)`, `toJson(JsonNode)`, `parse(String)`.
  - `record CastIncoming(String namespace, String sourceId, String destinationId, JsonNode payload)` with `type()`, `requestId()`, `describeFailure()`.
  - `CastConnection`: `static open(host, port, Duration heartbeatInterval, Duration staleTimeout, Listener)`, `connect(destinationId)`, `disconnect(destinationId)`, `send(namespace, destinationId, ObjectNode)`, `int nextRequestId()`, `CastIncoming request(namespace, destinationId, ObjectNode, Duration)`, `Waiter expect(Predicate<CastIncoming>)`, `close()`; `interface Listener { void onMessage(CastIncoming); void onDisconnected(CastDisconnectCause); }`; `Waiter.await(Duration)`, `Waiter.cancel()`.
  - `enum CastDisconnectCause { CLOSED, STALE, ERROR }`; `CastTimeoutException extends IOException`.
  - `record ReceiverStatus(double volumeLevel, boolean muted, boolean standBy, List<ReceiverApp> applications)` with `parse(JsonNode status)`, `foregroundApp()`, `app(String appId)`, `volumePercent()`; `record ReceiverApp(appId, displayName, sessionId, transportId, idleScreen, List<String> namespaces)` with `speaks(namespace)`.
  - `record CastProperties(boolean enabled, int heartbeatIntervalSeconds, int staleTimeoutSeconds, int reconnectInitialDelaySeconds, int reconnectMaxDelaySeconds, int commandTimeoutSeconds, int loadTimeoutSeconds, int mediaStatusIntervalSeconds)` bound to `home-control.cast`.
  - `CastSession implements DeviceHandle`: `CastSession(Device, CastProperties, Consumer<DeviceState>)`, `start()`, `state()`, `execute(Action)`, `close()`.
  - `CastAdapter(CastDiscovery, CastProperties)`; `connect` starts a `CastSession`.

**Wire format (normative).** A CASTV2 channel is a TLS connection to port 8009. Each frame is a 4-byte **big-endian unsigned length** followed by that many bytes of a serialized `CastMessage`. Every message this plan uses has `payload_type = STRING` and a JSON `payload_utf8`. `source_id` is always `sender-0`. Replies swap source and destination. Requests carry an integer `requestId` ≥ 1; the reply echoes it; unsolicited broadcasts carry `requestId: 0` and `destination_id: "*"`.

| Namespace | Direction | JSON |
|---|---|---|
| `urn:x-cast:com.google.cast.tp.connection` | sender → `receiver-0` or a transportId | `{"type":"CONNECT","origin":{},"userAgent":"home-control","senderInfo":{"sdkType":2,"version":"15.605.1.3","browserVersion":"44.0.2403.30","platform":4,"systemVersion":"Macintosh; Intel Mac OS X10_10_3","connectionType":1}}` |
| same | either way | `{"type":"CLOSE"}` (from the receiver: that transport or the whole channel is gone) |
| `urn:x-cast:com.google.cast.tp.heartbeat` | either way | `{"type":"PING"}` → answer `{"type":"PONG"}` |
| `urn:x-cast:com.google.cast.receiver` | sender → `receiver-0` | `{"type":"GET_STATUS","requestId":1}` |
| same | sender → `receiver-0` | `{"type":"LAUNCH","appId":"CC1AD845","requestId":2}` |
| same | sender → `receiver-0` | `{"type":"STOP","sessionId":"<app sessionId>","requestId":3}` |
| same | sender → `receiver-0` | `{"type":"SET_VOLUME","volume":{"level":0.3},"requestId":4}` / `{"type":"SET_VOLUME","volume":{"muted":true},"requestId":5}` |
| same | receiver → sender | `{"type":"RECEIVER_STATUS","requestId":n,"status":{"applications":[{"appId","displayName","isIdleScreen","namespaces":[{"name"}],"sessionId","statusText","transportId"}],"isActiveInput":true,"isStandBy":false,"volume":{"controlType":"attenuation","level":0.5,"muted":false,"stepInterval":0.05}}}`; errors `{"type":"LAUNCH_ERROR","requestId":n,"reason":"NOT_FOUND"}`, `{"type":"INVALID_REQUEST","requestId":n,"reason":"INVALID_COMMAND"}` |
| `urn:x-cast:com.google.cast.media` | sender → app transportId (after CONNECT to it) | `{"type":"LOAD","requestId":6,"sessionId":"<app sessionId>","media":{"contentId":"http://…","contentUrl":"http://…","streamType":"BUFFERED","contentType":"video/mp4","metadata":{"metadataType":0,"title":"…"}},"autoplay":true,"currentTime":0}` |
| same | sender → transportId | `{"type":"GET_STATUS","requestId":7}`, `{"type":"PAUSE","mediaSessionId":1,"requestId":8}`, `{"type":"PLAY","mediaSessionId":1,"requestId":9}`, `{"type":"STOP","mediaSessionId":1,"requestId":10}` |
| same | receiver → sender | `{"type":"MEDIA_STATUS","requestId":n,"status":[{"mediaSessionId":1,"playbackRate":1,"playerState":"PLAYING","currentTime":12.5,"supportedMediaCommands":274447,"media":{"contentId","contentType","streamType","duration":596.5,"metadata":{"metadataType":0,"title"}},"idleReason":"FINISHED"}]}` (`media` and `idleReason` optional; `status` may be `[]`); errors `LOAD_FAILED`, `LOAD_CANCELLED`, `INVALID_REQUEST` with `requestId` |

- [ ] **Step 1: Add the protobuf definition**

`src/main/proto/cast_channel.proto` — the `CastMessage` message is verbatim from Chromium's `cast_channel.proto`; only the header options are ours:

```proto
// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
// Only CastMessage is included; see docs/superpowers/specs/2026-09-16-cast-sender-adr.md.

syntax = "proto2";

package extensions.api.cast_channel;

option java_package = "dev.andre.homecontrol.adapters.cast.protocol.channel";
option java_outer_classname = "CastChannelProto";
option java_multiple_files = true;

message CastMessage {
    // Always pass a version of the protocol for future compatibility
    // requirements.
    enum ProtocolVersion {
        CASTV2_1_0 = 0;
    }
    required ProtocolVersion protocol_version = 1;

    // source and destination ids identify the origin and destination of the
    // message.  They are used to route messages between endpoints that share a
    // device-to-device channel.
    //
    // For messages between applications:
    //   - The sender application id is a unique identifier generated on behalf of
    //     the sender application.
    //   - The receiver id is always the the session id for the application.
    //
    // For messages to or from the sender or receiver platform, the special ids
    // 'sender-0' and 'receiver-0' can be used.
    //
    // For messages intended for all endpoints using a given channel, the
    // wildcard destination_id '*' can be used.
    required string source_id = 2;
    required string destination_id = 3;

    // This is the core multiplexing key.  All messages are sent on a namespace
    // and endpoints sharing a channel listen on one or more namespaces.  The
    // namespace defines the protocol and semantics of the message.
    required string namespace = 4;

    // Encoding and payload info follows.

    // What type of data do we have in this message.
    enum PayloadType {
        STRING = 0;
        BINARY = 1;
    }
    required PayloadType payload_type = 5;

    // Depending on payload_type, exactly one of the following optional fields
    // will always be set.
    optional string payload_utf8 = 6;
    optional bytes payload_binary = 7;
}
```

- [ ] **Step 2: Write the failing protocol unit tests**

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastChannelSchemaTest.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import com.google.protobuf.Descriptors;
import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CastChannelSchemaTest {

    @Test
    void fieldNumbersMatchTheCastV2WireFormat() {
        Descriptors.Descriptor descriptor = CastMessage.getDescriptor();

        assertThat(descriptor.findFieldByName("protocol_version").getNumber()).isEqualTo(1);
        assertThat(descriptor.findFieldByName("source_id").getNumber()).isEqualTo(2);
        assertThat(descriptor.findFieldByName("destination_id").getNumber()).isEqualTo(3);
        assertThat(descriptor.findFieldByName("namespace").getNumber()).isEqualTo(4);
        assertThat(descriptor.findFieldByName("payload_type").getNumber()).isEqualTo(5);
        assertThat(descriptor.findFieldByName("payload_utf8").getNumber()).isEqualTo(6);
        assertThat(descriptor.findFieldByName("payload_binary").getNumber()).isEqualTo(7);
        assertThat(CastMessage.ProtocolVersion.CASTV2_1_0.getNumber()).isZero();
        assertThat(CastMessage.PayloadType.STRING.getNumber()).isZero();
        assertThat(CastMessage.PayloadType.BINARY.getNumber()).isEqualTo(1);
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastFramingTest.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastFramingTest {

    private static CastMessage ping() {
        return CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId("sender-0")
                .setDestinationId("receiver-0")
                .setNamespace(CastNamespaces.HEARTBEAT)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8("{\"type\":\"PING\"}")
                .build();
    }

    @Test
    void prefixesEachMessageWithItsLengthAsFourBigEndianBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new CastFraming(new ByteArrayInputStream(new byte[0]), out).write(ping());

        byte[] wire = out.toByteArray();
        byte[] body = ping().toByteArray();
        assertThat(ByteBuffer.wrap(wire, 0, 4).getInt()).isEqualTo(body.length);
        assertThat(Arrays.copyOfRange(wire, 4, wire.length)).isEqualTo(body);
    }

    @Test
    void readsBackWhatItWroteAndReportsACleanEndAsNull() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastFraming writer = new CastFraming(new ByteArrayInputStream(new byte[0]), out);
        writer.write(ping());
        writer.write(ping());

        CastFraming reader = new CastFraming(new ByteArrayInputStream(out.toByteArray()), OutputStream.nullOutputStream());

        assertThat(reader.read()).isEqualTo(ping());
        assertThat(reader.read()).isEqualTo(ping());
        assertThat(reader.read()).isNull();
    }

    @Test
    void aTruncatedFrameIsAnError() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CastFraming(new ByteArrayInputStream(new byte[0]), out).write(ping());
        byte[] truncated = Arrays.copyOf(out.toByteArray(), out.size() - 3);

        CastFraming reader = new CastFraming(new ByteArrayInputStream(truncated), OutputStream.nullOutputStream());

        assertThatThrownBy(reader::read).isInstanceOf(EOFException.class);
    }

    @Test
    void refusesAFrameLargerThanTheLimit() {
        byte[] header = ByteBuffer.allocate(4).putInt(CastFraming.MAX_MESSAGE_BYTES + 1).array();

        CastFraming reader = new CastFraming(new ByteArrayInputStream(header), OutputStream.nullOutputStream());

        assertThatThrownBy(reader::read).isInstanceOf(IOException.class).hasMessageContaining("limit");
    }
}
```

(import `java.io.OutputStream`.)

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastPayloadsTest.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CastPayloadsTest {

    private static JsonNode json(String text) {
        return CastPayloads.parse(text);
    }

    @Test
    void connectIdentifiesTheSenderLikePychromecast() {
        assertThat(CastPayloads.connect()).isEqualTo(json("""
                {"type":"CONNECT","origin":{},"userAgent":"home-control",
                 "senderInfo":{"sdkType":2,"version":"15.605.1.3","browserVersion":"44.0.2403.30",
                               "platform":4,"systemVersion":"Macintosh; Intel Mac OS X10_10_3","connectionType":1}}
                """));
    }

    @Test
    void platformMessages() {
        assertThat(CastPayloads.close()).isEqualTo(json("{\"type\":\"CLOSE\"}"));
        assertThat(CastPayloads.ping()).isEqualTo(json("{\"type\":\"PING\"}"));
        assertThat(CastPayloads.pong()).isEqualTo(json("{\"type\":\"PONG\"}"));
        assertThat(CastPayloads.getStatus()).isEqualTo(json("{\"type\":\"GET_STATUS\"}"));
    }

    @Test
    void receiverCommands() {
        assertThat(CastPayloads.launch("CC1AD845")).isEqualTo(json("{\"type\":\"LAUNCH\",\"appId\":\"CC1AD845\"}"));
        assertThat(CastPayloads.stop("s-1")).isEqualTo(json("{\"type\":\"STOP\",\"sessionId\":\"s-1\"}"));
        assertThat(CastPayloads.setVolumeLevel(0.3)).isEqualTo(json("{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":0.3}}"));
        assertThat(CastPayloads.setVolumeLevel(1.7)).isEqualTo(json("{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":1.0}}"));
        assertThat(CastPayloads.setMuted(true)).isEqualTo(json("{\"type\":\"SET_VOLUME\",\"volume\":{\"muted\":true}}"));
    }

    @Test
    void loadAddsTypeAndSessionAndDropsAnyRequestIdFromTheBody() {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("contentId", "http://nas.local/a.mp4");
        media.put("contentType", "video/mp4");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "SOMETHING_ELSE");
        body.put("requestId", 99);
        body.put("media", media);
        body.put("autoplay", true);

        assertThat(CastPayloads.load("s-1", body)).isEqualTo(json("""
                {"type":"LOAD","sessionId":"s-1","autoplay":true,
                 "media":{"contentId":"http://nas.local/a.mp4","contentType":"video/mp4"}}
                """));
    }

    @Test
    void mediaCommands() {
        // Compared as text: a long-valued node and a parsed int node are not equal as trees.
        assertThat(CastPayloads.toJson(CastPayloads.pause(3))).isEqualTo("{\"type\":\"PAUSE\",\"mediaSessionId\":3}");
        assertThat(CastPayloads.toJson(CastPayloads.play(3))).isEqualTo("{\"type\":\"PLAY\",\"mediaSessionId\":3}");
        assertThat(CastPayloads.toJson(CastPayloads.stopMedia(3))).isEqualTo("{\"type\":\"STOP\",\"mediaSessionId\":3}");
    }

    @Test
    void incomingMessagesExposeTypeRequestIdAndFailure() {
        CastIncoming reply = new CastIncoming(CastNamespaces.RECEIVER, "receiver-0", "sender-0",
                json("{\"type\":\"LAUNCH_ERROR\",\"requestId\":4,\"reason\":\"NOT_FOUND\"}"));
        CastIncoming broadcast = new CastIncoming(CastNamespaces.MEDIA, "transport-1", "*",
                json("{\"type\":\"LOAD_FAILED\"}"));

        assertThat(reply.type()).isEqualTo("LAUNCH_ERROR");
        assertThat(reply.requestId()).isEqualTo(4);
        assertThat(reply.describeFailure()).isEqualTo("LAUNCH_ERROR: NOT_FOUND");
        assertThat(broadcast.requestId()).isZero();
        assertThat(broadcast.describeFailure()).isEqualTo("LOAD_FAILED");
    }
}
```

(`ObjectNode.equals` compares field maps, so key order does not matter; `put("level", 0.3)` and a parsed `0.3` are both double nodes.)

Fixture `src/test/resources/fixtures/cast/receiver-status-backdrop.json` (a receiver showing its idle screen):

```json
{
  "requestId": 1,
  "status": {
    "applications": [
      {
        "appId": "E8C28D3C",
        "displayName": "Backdrop",
        "iconUrl": "",
        "isIdleScreen": true,
        "launchedFromCloud": false,
        "namespaces": [
          {"name": "urn:x-cast:com.google.cast.debugoverlay"},
          {"name": "urn:x-cast:com.google.cast.cac"},
          {"name": "urn:x-cast:com.google.cast.sse"},
          {"name": "urn:x-cast:com.google.cast.remotecontrol"}
        ],
        "sessionId": "7d2a1c7e-0c9b-4d3e-a1f1-5b2c1b7a0c11",
        "statusText": "",
        "transportId": "7d2a1c7e-0c9b-4d3e-a1f1-5b2c1b7a0c11",
        "universalAppId": "E8C28D3C"
      }
    ],
    "isActiveInput": true,
    "isStandBy": false,
    "userEq": {},
    "volume": {"controlType": "attenuation", "level": 0.25, "muted": true, "stepInterval": 0.05}
  },
  "type": "RECEIVER_STATUS"
}
```

Fixture `src/test/resources/fixtures/cast/receiver-status-default-media-receiver.json`:

```json
{
  "requestId": 0,
  "status": {
    "applications": [
      {
        "appId": "CC1AD845",
        "appType": "WEB",
        "displayName": "Default Media Receiver",
        "iconUrl": "",
        "isIdleScreen": false,
        "launchedFromCloud": false,
        "namespaces": [
          {"name": "urn:x-cast:com.google.cast.debugoverlay"},
          {"name": "urn:x-cast:com.google.cast.cac"},
          {"name": "urn:x-cast:com.google.cast.media"}
        ],
        "sessionId": "b3f1b2a4-9c55-4d0e-8f5f-2f8d7e3c9a01",
        "statusText": "Default Media Receiver",
        "transportId": "b3f1b2a4-9c55-4d0e-8f5f-2f8d7e3c9a01",
        "universalAppId": "CC1AD845"
      }
    ],
    "isActiveInput": false,
    "isStandBy": true,
    "userEq": {},
    "volume": {"controlType": "master", "level": 1.0, "muted": false, "stepInterval": 0.019999999552965164}
  },
  "type": "RECEIVER_STATUS"
}
```

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/ReceiverStatusTest.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiverStatusTest {

    private static ReceiverStatus fixture(String name) throws Exception {
        return ReceiverStatus.parse(CastPayloads.parse(
                Files.readString(Path.of("src/test/resources/fixtures/cast/" + name))).path("status"));
    }

    @Test
    void theIdleScreenIsNotAForegroundApp() throws Exception {
        ReceiverStatus status = fixture("receiver-status-backdrop.json");

        assertThat(status.volumeLevel()).isEqualTo(0.25);
        assertThat(status.volumePercent()).isEqualTo(25);
        assertThat(status.muted()).isTrue();
        assertThat(status.standBy()).isFalse();
        assertThat(status.applications()).singleElement().satisfies(app -> assertThat(app.idleScreen()).isTrue());
        assertThat(status.foregroundApp()).isEmpty();
    }

    @Test
    void aRunningReceiverAppExposesItsSessionTransportAndNamespaces() throws Exception {
        ReceiverStatus status = fixture("receiver-status-default-media-receiver.json");

        ReceiverStatus.ReceiverApp app = status.foregroundApp().orElseThrow();
        assertThat(app.appId()).isEqualTo("CC1AD845");
        assertThat(app.displayName()).isEqualTo("Default Media Receiver");
        assertThat(app.sessionId()).isEqualTo("b3f1b2a4-9c55-4d0e-8f5f-2f8d7e3c9a01");
        assertThat(app.transportId()).isEqualTo("b3f1b2a4-9c55-4d0e-8f5f-2f8d7e3c9a01");
        assertThat(app.speaks(CastNamespaces.MEDIA)).isTrue();
        assertThat(status.app("CC1AD845")).contains(app);
        assertThat(status.app("233637DE")).isEmpty();
        assertThat(status.standBy()).isTrue();
        assertThat(status.volumePercent()).isEqualTo(100);
    }

    @Test
    void aStatusWithoutApplicationsOrVolumeParsesToDefaults() {
        ReceiverStatus status = ReceiverStatus.parse(CastPayloads.parse("{}"));

        assertThat(status.applications()).isEmpty();
        assertThat(status.volumeLevel()).isZero();
        assertThat(status.muted()).isFalse();
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.protocol.*'`
Expected: compilation failure — the protocol classes do not exist (the generated `CastMessage` does).

- [ ] **Step 4: Write the framing, constants, payloads and parsers**

`adapters/cast/protocol/CastNamespaces.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

/** CASTV2 namespaces and well-known endpoint ids. */
public final class CastNamespaces {

    public static final String CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    public static final String HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    public static final String RECEIVER = "urn:x-cast:com.google.cast.receiver";
    public static final String MEDIA = "urn:x-cast:com.google.cast.media";

    /** Our sender id on every channel (pychromecast does the same). */
    public static final String SENDER_ID = "sender-0";
    /** The receiver platform itself: connection, heartbeat and receiver namespaces go here. */
    public static final String PLATFORM_RECEIVER_ID = "receiver-0";
    public static final String DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845";

    private CastNamespaces() {
    }
}
```

`adapters/cast/protocol/CastFraming.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * CASTV2 framing: a 4-byte big-endian length, then that many bytes of {@link CastMessage}.
 * Not protobuf's varint delimiting — the Remote v2 {@code MessageStream} does not apply here.
 */
public final class CastFraming {

    /** Chromium caps a message at 65 535 bytes; anything larger is a broken or hostile peer. */
    public static final int MAX_MESSAGE_BYTES = 65_536;

    private final InputStream in;
    private final OutputStream out;

    public CastFraming(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** One write call per frame so concurrent writers can never interleave header and body. */
    public synchronized void write(CastMessage message) throws IOException {
        byte[] body = message.toByteArray();
        out.write(ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array());
        out.flush();
    }

    /** Returns the next message, or {@code null} if the peer closed the stream between frames. */
    public CastMessage read() throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        byte[] rest = in.readNBytes(3);
        if (rest.length < 3) {
            throw new EOFException("The Cast peer closed the stream inside a frame header");
        }
        long length = ((long) first << 24) | ((rest[0] & 0xFFL) << 16) | ((rest[1] & 0xFFL) << 8) | (rest[2] & 0xFFL);
        if (length > MAX_MESSAGE_BYTES) {
            throw new IOException("A Cast frame of " + length + " bytes exceeds the limit of " + MAX_MESSAGE_BYTES);
        }
        byte[] body = in.readNBytes((int) length);
        if (body.length < length) {
            throw new EOFException("The Cast peer closed the stream inside a frame");
        }
        return CastMessage.parseFrom(body);
    }
}
```

`adapters/cast/protocol/CastIncoming.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import tools.jackson.databind.JsonNode;

/** One inbound string message with its JSON payload already parsed. */
public record CastIncoming(String namespace, String sourceId, String destinationId, JsonNode payload) {

    public String type() {
        return payload.path("type").asString("");
    }

    /** 0 for unsolicited broadcasts. */
    public int requestId() {
        return payload.path("requestId").asInt(0);
    }

    /** "LAUNCH_ERROR: NOT_FOUND", or just the type when the receiver gave no reason. */
    public String describeFailure() {
        String reason = payload.path("reason").asString("");
        return reason.isBlank() ? type() : type() + ": " + reason;
    }
}
```

`adapters/cast/protocol/CastDisconnectCause.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

public enum CastDisconnectCause {
    /** The receiver closed the channel (EOF or CLOSE from receiver-0). */
    CLOSED,
    /** Nothing arrived within the stale timeout, although we kept pinging. */
    STALE,
    /** Any other I/O failure. */
    ERROR
}
```

`adapters/cast/protocol/CastTimeoutException.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import java.io.IOException;

/** The channel is up but the receiver did not answer a request in time. */
public class CastTimeoutException extends IOException {
    public CastTimeoutException(String message) {
        super(message);
    }
}
```

`adapters/cast/protocol/CastPayloads.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Builders for every JSON payload the sender emits. Field names are the wire format. */
public final class CastPayloads {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CastPayloads() {
    }

    /** Mirrors pychromecast's CONNECT; some Google TV builds refuse a bare {"type":"CONNECT"}. */
    public static ObjectNode connect() {
        ObjectNode node = type("CONNECT");
        node.putObject("origin");
        node.put("userAgent", "home-control");
        ObjectNode senderInfo = node.putObject("senderInfo");
        senderInfo.put("sdkType", 2);
        senderInfo.put("version", "15.605.1.3");
        senderInfo.put("browserVersion", "44.0.2403.30");
        senderInfo.put("platform", 4);
        senderInfo.put("systemVersion", "Macintosh; Intel Mac OS X10_10_3");
        senderInfo.put("connectionType", 1);
        return node;
    }

    public static ObjectNode close() {
        return type("CLOSE");
    }

    public static ObjectNode ping() {
        return type("PING");
    }

    public static ObjectNode pong() {
        return type("PONG");
    }

    /** Receiver and media namespaces share the same GET_STATUS shape. */
    public static ObjectNode getStatus() {
        return type("GET_STATUS");
    }

    public static ObjectNode launch(String appId) {
        ObjectNode node = type("LAUNCH");
        node.put("appId", appId);
        return node;
    }

    public static ObjectNode stop(String sessionId) {
        ObjectNode node = type("STOP");
        node.put("sessionId", sessionId);
        return node;
    }

    public static ObjectNode setVolumeLevel(double level) {
        ObjectNode node = type("SET_VOLUME");
        node.putObject("volume").put("level", Math.max(0.0, Math.min(1.0, level)));
        return node;
    }

    public static ObjectNode setMuted(boolean muted) {
        ObjectNode node = type("SET_VOLUME");
        node.putObject("volume").put("muted", muted);
        return node;
    }

    /** {@code body} is a media LOAD without type/requestId/sessionId (see {@code Action.CastLoad}). */
    public static ObjectNode load(String sessionId, Map<String, Object> body) {
        ObjectNode node = type("LOAD");
        node.setAll((ObjectNode) MAPPER.valueToTree(body));
        node.put("type", "LOAD");
        node.put("sessionId", sessionId);
        node.remove("requestId");
        return node;
    }

    public static ObjectNode pause(long mediaSessionId) {
        return mediaCommand("PAUSE", mediaSessionId);
    }

    public static ObjectNode play(long mediaSessionId) {
        return mediaCommand("PLAY", mediaSessionId);
    }

    public static ObjectNode stopMedia(long mediaSessionId) {
        return mediaCommand("STOP", mediaSessionId);
    }

    public static String toJson(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    private static ObjectNode mediaCommand(String type, long mediaSessionId) {
        ObjectNode node = type(type);
        node.put("mediaSessionId", mediaSessionId);
        return node;
    }

    private static ObjectNode type(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        return node;
    }
}
```

`adapters/cast/protocol/ReceiverStatus.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The {@code status} object of a RECEIVER_STATUS message. */
public record ReceiverStatus(double volumeLevel, boolean muted, boolean standBy, List<ReceiverApp> applications) {

    public record ReceiverApp(String appId, String displayName, String sessionId, String transportId,
                              boolean idleScreen, List<String> namespaces) {

        public boolean speaks(String namespace) {
            return namespaces.contains(namespace);
        }
    }

    public static ReceiverStatus parse(JsonNode status) {
        List<ReceiverApp> applications = new ArrayList<>();
        for (JsonNode app : status.path("applications")) {
            List<String> namespaces = new ArrayList<>();
            for (JsonNode namespace : app.path("namespaces")) {
                namespaces.add(namespace.path("name").asString(""));
            }
            applications.add(new ReceiverApp(
                    app.path("appId").asString(""),
                    app.path("displayName").asString(""),
                    app.path("sessionId").asString(""),
                    app.path("transportId").asString(""),
                    app.path("isIdleScreen").asBoolean(false),
                    List.copyOf(namespaces)));
        }
        JsonNode volume = status.path("volume");
        return new ReceiverStatus(volume.path("level").asDouble(0.0), volume.path("muted").asBoolean(false),
                status.path("isStandBy").asBoolean(false), List.copyOf(applications));
    }

    /** The app the user sees, ignoring the Backdrop idle screen. */
    public Optional<ReceiverApp> foregroundApp() {
        return applications.stream().filter(app -> !app.idleScreen()).findFirst();
    }

    public Optional<ReceiverApp> app(String appId) {
        return applications.stream().filter(app -> app.appId().equals(appId)).findFirst();
    }

    public int volumePercent() {
        return (int) Math.round(volumeLevel * 100);
    }
}
```

- [ ] **Step 5: Run the protocol unit tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.protocol.*'`
Expected: PASS.

- [ ] **Step 6: Write the fake receiver**

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/FakeCastReceiver.java` — complete; later tasks use it unchanged:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process Cast receiver speaking enough CASTV2 for the sender (spec §12): virtual
 * connections, heartbeat, receiver status, volume, LAUNCH/STOP, and a Default-Media-Receiver
 * media channel (LOAD, GET_STATUS, PAUSE, PLAY, STOP). One sender connection at a time.
 * Every non-heartbeat message it receives is recorded for assertions.
 */
public class FakeCastReceiver implements AutoCloseable {

    public static final String BACKDROP_APP_ID = "E8C28D3C";
    public static final String DEFAULT_MEDIA_RECEIVER = CastNamespaces.DEFAULT_MEDIA_RECEIVER_APP_ID;

    private record App(String appId, String displayName, String sessionId, String transportId,
                       boolean idleScreen, boolean speaksMedia) {
    }

    private record Media(long mediaSessionId, String contentId, String contentType, String title,
                         double duration, String playerState, double currentTime) {
        Media with(String state, double time) {
            return new Media(mediaSessionId, contentId, contentType, title, duration, state, time);
        }
    }

    private final SSLServerSocket serverSocket;
    private final List<CastIncoming> received = new CopyOnWriteArrayList<>();
    private final Set<String> virtualConnections = ConcurrentHashMap.newKeySet();
    private final Set<String> ignoredTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> refusedApps = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger pings = new AtomicInteger();
    private final AtomicInteger pongs = new AtomicInteger();
    private final AtomicInteger sessions = new AtomicInteger();

    private volatile double volumeLevel = 0.5;
    private volatile boolean muted;
    private volatile App app = backdrop();
    private volatile Media media;
    private volatile boolean silent;
    private volatile boolean failNextLoad;
    private volatile SSLSocket socket;
    private volatile CastFraming framing;
    private volatile boolean closed;

    public FakeCastReceiver() throws Exception {
        this(0);
    }

    /** A fixed port lets a test bring a "rebooted" receiver back where the sender expects it. */
    public FakeCastReceiver(int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(selfSignedKeyManagers(), null, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        Thread.ofVirtual().name("fake-cast-receiver").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    // ---- scripting ----

    public void setVolume(double level, boolean isMuted) {
        volumeLevel = level;
        muted = isMuted;
    }

    /** Puts an app in front as if another sender had launched it; it speaks the media namespace. */
    public void runApp(String appId, String displayName) {
        int n = sessions.incrementAndGet();
        app = new App(appId, displayName, "session-" + n, "transport-" + n, false, true);
        media = null;
    }

    /** Media loaded by someone else; requires {@link #runApp}. */
    public void startMedia(String title, String playerState, double currentTime) {
        media = new Media(1, "http://media.invalid/" + title, "video/mp4", title, 596.5, playerState, currentTime);
    }

    public void setMediaState(String playerState, double currentTime) {
        media = media.with(playerState, currentTime);
    }

    public void pushReceiverStatus() throws IOException {
        send(CastNamespaces.RECEIVER, CastNamespaces.PLATFORM_RECEIVER_ID, "*", receiverStatus(0));
    }

    public void pushMediaStatus(boolean includeMedia) throws IOException {
        send(CastNamespaces.MEDIA, app.transportId(), "*", mediaStatus(0, includeMedia));
    }

    public void ping() throws IOException {
        send(CastNamespaces.HEARTBEAT, CastNamespaces.PLATFORM_RECEIVER_ID, CastNamespaces.SENDER_ID, CastPayloads.ping());
    }

    /** Stop answering anything (heartbeats included) while keeping the socket open. */
    public void goSilent() {
        silent = true;
    }

    public void resume() {
        silent = false;
    }

    /** Record but never answer messages of this type. */
    public void ignore(String type) {
        ignoredTypes.add(type);
    }

    public void refuseLaunch(String appId) {
        refusedApps.add(appId);
    }

    public void failNextLoad() {
        failNextLoad = true;
    }

    public void dropConnection() throws IOException {
        SSLSocket current = socket;
        if (current != null) {
            current.close();
        }
    }

    // ---- observation ----

    public int connections() {
        return connections.get();
    }

    public int pings() {
        return pings.get();
    }

    public int pongs() {
        return pongs.get();
    }

    public Set<String> virtualConnections() {
        return Set.copyOf(virtualConnections);
    }

    public List<CastIncoming> received(String namespace, String type) {
        return received.stream()
                .filter(message -> message.namespace().equals(namespace) && message.type().equals(type))
                .toList();
    }

    public Optional<CastIncoming> last(String namespace, String type) {
        List<CastIncoming> matching = received(namespace, type);
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getLast());
    }

    public double volumeLevel() {
        return volumeLevel;
    }

    public boolean muted() {
        return muted;
    }

    public String runningAppId() {
        return app.appId();
    }

    // ---- protocol ----

    private void serve() {
        while (!closed) {
            try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                connections.incrementAndGet();
                socket = accepted;
                framing = new CastFraming(accepted.getInputStream(), accepted.getOutputStream());
                CastMessage message;
                while ((message = framing.read()) != null) {
                    handle(message);
                }
            } catch (IOException | RuntimeException e) {
                // The sender hung up, the test dropped the connection, or the fake is closing.
            } finally {
                virtualConnections.clear();
            }
        }
    }

    private void handle(CastMessage message) throws IOException {
        CastIncoming incoming = new CastIncoming(message.getNamespace(), message.getSourceId(),
                message.getDestinationId(), CastPayloads.parse(message.getPayloadUtf8()));
        String type = incoming.type();
        if (CastNamespaces.HEARTBEAT.equals(incoming.namespace())) {
            if ("PING".equals(type)) {
                pings.incrementAndGet();
                if (!silent) {
                    reply(incoming, CastPayloads.pong());
                }
            } else if ("PONG".equals(type)) {
                pongs.incrementAndGet();
            }
            return;
        }
        received.add(incoming);
        if (silent || ignoredTypes.contains(type)) {
            return;
        }
        switch (incoming.namespace()) {
            case CastNamespaces.CONNECTION -> {
                if ("CONNECT".equals(type)) {
                    virtualConnections.add(incoming.destinationId());
                } else if ("CLOSE".equals(type)) {
                    virtualConnections.remove(incoming.destinationId());
                }
            }
            case CastNamespaces.RECEIVER -> receiver(incoming, type, incoming.requestId());
            case CastNamespaces.MEDIA -> media(incoming, type, incoming.requestId());
            default -> {
            }
        }
    }

    private void receiver(CastIncoming in, String type, int requestId) throws IOException {
        JsonNode payload = in.payload();
        switch (type) {
            case "GET_STATUS" -> reply(in, receiverStatus(requestId));
            case "SET_VOLUME" -> {
                JsonNode volume = payload.path("volume");
                if (volume.has("level")) {
                    volumeLevel = volume.path("level").asDouble(volumeLevel);
                }
                if (volume.has("muted")) {
                    muted = volume.path("muted").asBoolean(muted);
                }
                reply(in, receiverStatus(requestId));
            }
            case "LAUNCH" -> {
                String appId = payload.path("appId").asString("");
                if (refusedApps.contains(appId)) {
                    reply(in, error("LAUNCH_ERROR", requestId, "NOT_FOUND"));
                    return;
                }
                int n = sessions.incrementAndGet();
                app = new App(appId, DEFAULT_MEDIA_RECEIVER.equals(appId) ? "Default Media Receiver" : "App " + appId,
                        "session-" + n, "transport-" + n, false, true);
                media = null;
                reply(in, receiverStatus(requestId));
            }
            case "STOP" -> {
                if (!app.sessionId().equals(payload.path("sessionId").asString(""))) {
                    reply(in, error("INVALID_REQUEST", requestId, "INVALID_SESSION_ID"));
                    return;
                }
                String stoppedTransport = app.transportId();
                app = backdrop();
                media = null;
                send(CastNamespaces.CONNECTION, stoppedTransport, in.sourceId(), CastPayloads.close());
                reply(in, receiverStatus(requestId));
            }
            default -> reply(in, error("INVALID_REQUEST", requestId, "INVALID_COMMAND"));
        }
    }

    private void media(CastIncoming in, String type, int requestId) throws IOException {
        App current = app;
        if (!current.speaksMedia() || !current.transportId().equals(in.destinationId())
                || !virtualConnections.contains(in.destinationId())) {
            return; // a real receiver drops messages for transports the sender has not connected to
        }
        JsonNode payload = in.payload();
        switch (type) {
            case "LOAD" -> {
                if (failNextLoad) {
                    failNextLoad = false;
                    reply(in, error("LOAD_FAILED", requestId, null));
                    return;
                }
                JsonNode loaded = payload.path("media");
                long id = media == null ? 1 : media.mediaSessionId() + 1;
                String title = loaded.path("metadata").has("title")
                        ? loaded.path("metadata").path("title").asString("") : null;
                media = new Media(id, loaded.path("contentId").asString(""), loaded.path("contentType").asString(""),
                        title, 596.5, "PLAYING", payload.path("currentTime").asDouble(0.0));
                reply(in, mediaStatus(requestId, true));
            }
            case "GET_STATUS" -> reply(in, mediaStatus(requestId, true));
            case "PAUSE" -> {
                if (media != null) {
                    media = media.with("PAUSED", media.currentTime());
                }
                reply(in, mediaStatus(requestId, false));
            }
            case "PLAY" -> {
                if (media != null) {
                    media = media.with("PLAYING", media.currentTime());
                }
                reply(in, mediaStatus(requestId, false));
            }
            case "STOP" -> {
                media = null;
                reply(in, mediaStatus(requestId, false));
            }
            default -> reply(in, error("INVALID_REQUEST", requestId, "INVALID_COMMAND"));
        }
    }

    private ObjectNode receiverStatus(int requestId) {
        ObjectNode payload = message("RECEIVER_STATUS", requestId);
        ObjectNode status = payload.putObject("status");
        App current = app;
        ObjectNode application = status.putArray("applications").addObject();
        application.put("appId", current.appId());
        application.put("displayName", current.displayName());
        application.put("isIdleScreen", current.idleScreen());
        application.put("sessionId", current.sessionId());
        application.put("statusText", current.idleScreen() ? "" : "Ready To Cast");
        application.put("transportId", current.transportId());
        ArrayNode namespaces = application.putArray("namespaces");
        if (current.speaksMedia()) {
            namespaces.addObject().put("name", CastNamespaces.MEDIA);
        }
        status.put("isActiveInput", true);
        status.put("isStandBy", false);
        ObjectNode volume = status.putObject("volume");
        volume.put("controlType", "attenuation");
        volume.put("level", volumeLevel);
        volume.put("muted", muted);
        volume.put("stepInterval", 0.05);
        return payload;
    }

    private ObjectNode mediaStatus(int requestId, boolean includeMedia) {
        ObjectNode payload = message("MEDIA_STATUS", requestId);
        ArrayNode status = payload.putArray("status");
        Media current = media;
        if (current != null) {
            ObjectNode entry = status.addObject();
            entry.put("mediaSessionId", current.mediaSessionId());
            entry.put("playbackRate", 1);
            entry.put("playerState", current.playerState());
            entry.put("currentTime", current.currentTime());
            entry.put("supportedMediaCommands", 274447);
            if ("IDLE".equals(current.playerState())) {
                entry.put("idleReason", "FINISHED");
            }
            if (includeMedia) {
                ObjectNode loaded = entry.putObject("media");
                loaded.put("contentId", current.contentId());
                loaded.put("contentType", current.contentType());
                loaded.put("streamType", "BUFFERED");
                loaded.put("duration", current.duration());
                ObjectNode metadata = loaded.putObject("metadata");
                metadata.put("metadataType", 0);
                if (current.title() != null) {
                    metadata.put("title", current.title());
                }
            }
        }
        return payload;
    }

    private static ObjectNode error(String type, int requestId, String reason) {
        ObjectNode payload = message(type, requestId);
        if (reason != null) {
            payload.put("reason", reason);
        }
        return payload;
    }

    private static ObjectNode message(String type, int requestId) {
        ObjectNode payload = (ObjectNode) CastPayloads.parse("{}");
        payload.put("type", type);
        payload.put("requestId", requestId);
        return payload;
    }

    private static App backdrop() {
        return new App(BACKDROP_APP_ID, "Backdrop", "backdrop-session", "backdrop-transport", true, false);
    }

    private void reply(CastIncoming to, ObjectNode payload) throws IOException {
        send(to.namespace(), to.destinationId(), to.sourceId(), payload);
    }

    private void send(String namespace, String sourceId, String destinationId, ObjectNode payload) throws IOException {
        CastFraming current = framing;
        if (current == null) {
            throw new IOException("No sender is connected to the fake receiver");
        }
        current.write(CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(sourceId)
                .setDestinationId(destinationId)
                .setNamespace(namespace)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8(CastPayloads.toJson(payload))
                .build());
    }

    private static KeyManager[] selfSignedKeyManagers() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=fake-cast-receiver");
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(subject, new BigInteger(64, new SecureRandom()),
                        Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(1))),
                        subject, keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())));
        char[] password = "fake".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("receiver", keyPair.getPrivate(), password, new Certificate[]{certificate});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        return factory.getKeyManagers();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        serverSocket.close();
        dropConnection();
    }
}
```

- [ ] **Step 7: Write the failing connection tests**

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastConnectionTest.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CastConnectionTest {

    private final List<CastIncoming> messages = new CopyOnWriteArrayList<>();
    private final List<CastDisconnectCause> disconnects = new CopyOnWriteArrayList<>();
    private final CastConnection.Listener listener = new CastConnection.Listener() {
        @Override
        public void onMessage(CastIncoming message) {
            messages.add(message);
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            disconnects.add(cause);
        }
    };

    private FakeCastReceiver receiver;
    private CastConnection connection;

    @BeforeEach
    void connect() throws Exception {
        receiver = new FakeCastReceiver();
        connection = CastConnection.open("127.0.0.1", receiver.port(), Duration.ofSeconds(1), Duration.ofSeconds(3), listener);
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        receiver.close();
    }

    @Test
    void opensTheVirtualConnectionToThePlatformReceiverFirst() {
        await().until(() -> receiver.virtualConnections().contains(PLATFORM_RECEIVER_ID));

        CastIncoming connect = receiver.last(CONNECTION, "CONNECT").orElseThrow();
        assertThat(connect.sourceId()).isEqualTo("sender-0");
        assertThat(connect.payload().path("userAgent").asString("")).isEqualTo("home-control");
    }

    @Test
    void sendsHeartbeatPingsAndAnswersTheReceiversPing() throws Exception {
        await().until(() -> receiver.pings() >= 1);

        receiver.ping();

        await().until(() -> receiver.pongs() == 1);
    }

    @Test
    void correlatesARequestWithItsReplyAndStillTellsTheListener() throws Exception {
        receiver.setVolume(0.25, true);

        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofSeconds(3));

        assertThat(reply.type()).isEqualTo("RECEIVER_STATUS");
        assertThat(reply.requestId()).isPositive();
        assertThat(ReceiverStatus.parse(reply.payload().path("status")).volumeLevel()).isEqualTo(0.25);
        await().until(() -> messages.stream().anyMatch(message -> message.requestId() == reply.requestId()));
    }

    @Test
    void anUnansweredRequestTimesOut() {
        receiver.ignore("GET_STATUS");

        assertThatThrownBy(() -> connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofMillis(500)))
                .isInstanceOf(CastTimeoutException.class);
    }

    @Test
    void aSilentReceiverIsReportedStale() {
        receiver.goSilent();

        await().atMost(Duration.ofSeconds(10)).until(() -> disconnects.contains(CastDisconnectCause.STALE));
        assertThat(disconnects).hasSize(1);
    }

    @Test
    void aHangUpIsReportedOnceAndFailsPendingRequests() throws Exception {
        receiver.ignore("GET_STATUS");
        CompletableFuture<Throwable> pending = CompletableFuture.supplyAsync(() -> {
            try {
                connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofSeconds(10));
                return null;
            } catch (IOException e) {
                return e;
            }
        });
        await().until(() -> receiver.last(RECEIVER, "GET_STATUS").isPresent());

        receiver.dropConnection();

        await().until(() -> !disconnects.isEmpty());
        assertThat(disconnects).singleElement().isIn(CastDisconnectCause.CLOSED, CastDisconnectCause.ERROR);
        assertThat(pending.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class).isNotInstanceOf(CastTimeoutException.class);
        assertThatThrownBy(() -> connection.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void closingByTheOwnerIsNotReportedAsADisconnect() throws Exception {
        await().until(() -> receiver.pings() >= 1);

        connection.close();

        Thread.sleep(500);
        assertThat(disconnects).isEmpty();
    }

    @Test
    void anUnreachableReceiverFailsToOpen() throws Exception {
        int unused;
        try (ServerSocket probe = new ServerSocket(0)) {
            unused = probe.getLocalPort();
        }

        assertThatThrownBy(() -> CastConnection.open("127.0.0.1", unused, Duration.ofSeconds(1), Duration.ofSeconds(3), listener))
                .isInstanceOf(IOException.class);
    }

    @Test
    void theStaleTimeoutMustExceedTheHeartbeatInterval() {
        assertThatThrownBy(() -> CastConnection.open("127.0.0.1", receiver.port(), Duration.ofSeconds(3), Duration.ofSeconds(3), listener))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

(`Thread.sleep` in a test is acceptable here: it asserts that something does *not* happen.)

- [ ] **Step 8: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.protocol.CastConnectionTest'`
Expected: compilation failure — `CastConnection` does not exist.

- [ ] **Step 9: Write TLS and the connection**

`adapters/cast/protocol/CastTls.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * TLS for port 8009. Receivers present self-signed certificates and senders do not
 * authenticate them (ADR). An {@link X509ExtendedTrustManager} so JSSE adds no hostname or
 * algorithm checks of its own on top.
 */
final class CastTls {

    private CastTls() {
    }

    static SSLSocket connect(String host, int port, int connectTimeoutMillis, int soTimeoutMillis) throws IOException {
        SSLSocket socket;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TRUST_RECEIVER}, new SecureRandom());
            socket = (SSLSocket) context.getSocketFactory().createSocket();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not build the TLS context for " + host + ":" + port, e);
        }
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(soTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already failing.
            }
            throw e;
        }
    }

    private static final X509ExtendedTrustManager TRUST_RECEIVER = new X509ExtendedTrustManager() {
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
    };
}
```

`adapters/cast/protocol/CastConnection.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * One CASTV2 channel: TLS, framing, the platform virtual connection, heartbeat, and
 * request/reply correlation. Knows nothing about devices or state.
 *
 * <p>Threads: a reader thread delivers every non-heartbeat message to the {@link Listener}
 * and completes waiters; a heartbeat thread pings. Listener callbacks run on the reader thread
 * and must not block — in particular they must never call {@link #request} or
 * {@link Waiter#await}, which wait for that same thread.
 */
public final class CastConnection implements AutoCloseable {

    public interface Listener {
        void onMessage(CastIncoming message);

        /** Called at most once, never after {@link #close()} by the owner. */
        void onDisconnected(CastDisconnectCause cause);
    }

    private static final Logger log = LoggerFactory.getLogger(CastConnection.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    private final SSLSocket socket;
    private final CastFraming framing;
    private final Listener listener;
    private final AtomicInteger requestIds = new AtomicInteger(1);
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat;
    private volatile boolean closed;

    /**
     * Connects, opens the virtual connection to {@code receiver-0}, and starts heartbeat and
     * reader. {@code staleTimeout} is the socket read timeout: with a ping every
     * {@code heartbeatInterval} a healthy receiver always sends something sooner.
     */
    public static CastConnection open(String host, int port, Duration heartbeatInterval, Duration staleTimeout,
                                      Listener listener) throws IOException {
        if (staleTimeout.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("The stale timeout must be longer than the heartbeat interval");
        }
        SSLSocket socket = CastTls.connect(host, port, CONNECT_TIMEOUT_MILLIS, Math.toIntExact(staleTimeout.toMillis()));
        try {
            return new CastConnection(socket, heartbeatInterval, listener);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already failing.
            }
            throw e;
        }
    }

    private CastConnection(SSLSocket socket, Duration heartbeatInterval, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.framing = new CastFraming(socket.getInputStream(), socket.getOutputStream());
        write(CastNamespaces.CONNECTION, CastNamespaces.PLATFORM_RECEIVER_ID, CastPayloads.connect());
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cast-heartbeat").factory());
        long every = heartbeatInterval.toMillis();
        heartbeat.scheduleWithFixedDelay(this::ping, every, every, TimeUnit.MILLISECONDS);
        Thread.ofVirtual().name("cast-reader").start(this::readLoop);
    }

    /** Opens a virtual connection to an app transport (required before talking to it). */
    public void connect(String destinationId) throws IOException {
        send(CastNamespaces.CONNECTION, destinationId, CastPayloads.connect());
    }

    public void disconnect(String destinationId) throws IOException {
        send(CastNamespaces.CONNECTION, destinationId, CastPayloads.close());
    }

    public void send(String namespace, String destinationId, ObjectNode payload) throws IOException {
        if (closed) {
            throw new IOException("The Cast connection is closed");
        }
        write(namespace, destinationId, payload);
    }

    public int nextRequestId() {
        return requestIds.getAndIncrement();
    }

    /** Sends {@code payload} with a fresh {@code requestId} and waits for the message echoing it. */
    public CastIncoming request(String namespace, String destinationId, ObjectNode payload, Duration timeout)
            throws IOException {
        int requestId = nextRequestId();
        payload.put("requestId", requestId);
        Waiter waiter = expect(message -> message.requestId() == requestId);
        try {
            send(namespace, destinationId, payload);
            return waiter.await(timeout);
        } finally {
            waiter.cancel();
        }
    }

    /** Registers interest BEFORE sending, so a fast reply cannot slip past. */
    public Waiter expect(Predicate<CastIncoming> match) {
        Waiter waiter = new Waiter(match);
        waiters.add(waiter);
        if (closed) {
            waiter.future.completeExceptionally(new IOException("The Cast connection is closed"));
        }
        return waiter;
    }

    public final class Waiter {

        private final Predicate<CastIncoming> match;
        private final CompletableFuture<CastIncoming> future = new CompletableFuture<>();

        private Waiter(Predicate<CastIncoming> match) {
            this.match = match;
        }

        public CastIncoming await(Duration timeout) throws IOException {
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new CastTimeoutException("The Cast receiver did not answer within " + timeout.toMillis() + " ms");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for the Cast receiver");
            } finally {
                cancel();
            }
        }

        public void cancel() {
            waiters.remove(this);
        }
    }

    private void ping() {
        try {
            send(CastNamespaces.HEARTBEAT, CastNamespaces.PLATFORM_RECEIVER_ID, CastPayloads.ping());
        } catch (IOException | RuntimeException e) {
            log.debug("Cast heartbeat failed: {}", e.getMessage()); // the reader notices the drop
        }
    }

    private void readLoop() {
        try {
            CastMessage message;
            while (!closed && (message = framing.read()) != null) {
                dispatch(message);
            }
            finish(CastDisconnectCause.CLOSED);
        } catch (SocketTimeoutException e) {
            finish(CastDisconnectCause.STALE);
        } catch (IOException | RuntimeException e) {
            finish(CastDisconnectCause.ERROR);
        }
    }

    private void dispatch(CastMessage message) throws IOException {
        if (message.getPayloadType() != CastMessage.PayloadType.STRING) {
            return; // binary namespaces (device auth) are not used
        }
        CastIncoming incoming;
        try {
            incoming = new CastIncoming(message.getNamespace(), message.getSourceId(), message.getDestinationId(),
                    CastPayloads.parse(message.getPayloadUtf8()));
        } catch (JacksonException e) {
            log.debug("Ignoring a Cast message with an unreadable payload on {}", message.getNamespace());
            return;
        }
        if (CastNamespaces.HEARTBEAT.equals(incoming.namespace())) {
            if ("PING".equals(incoming.type())) {
                send(CastNamespaces.HEARTBEAT, incoming.sourceId(), CastPayloads.pong());
            }
            return;
        }
        if (CastNamespaces.CONNECTION.equals(incoming.namespace()) && "CLOSE".equals(incoming.type())
                && CastNamespaces.PLATFORM_RECEIVER_ID.equals(incoming.sourceId())) {
            finish(CastDisconnectCause.CLOSED);
            return;
        }
        for (Waiter waiter : waiters) {
            if (waiter.match.test(incoming)) {
                waiter.future.complete(incoming);
            }
        }
        try {
            listener.onMessage(incoming);
        } catch (RuntimeException e) {
            log.warn("A Cast message listener failed", e);
        }
    }

    private void finish(CastDisconnectCause cause) {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        shutdown();
        listener.onDisconnected(cause);
    }

    /** Closes quietly: best-effort CLOSE to the receiver, no listener callback. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            write(CastNamespaces.CONNECTION, CastNamespaces.PLATFORM_RECEIVER_ID, CastPayloads.close());
        } catch (IOException | RuntimeException ignored) {
            // Leaving anyway.
        }
        shutdown();
    }

    private void shutdown() {
        heartbeat.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        IOException lost = new IOException("The Cast connection was closed");
        waiters.forEach(waiter -> waiter.future.completeExceptionally(lost));
    }

    private void write(String namespace, String destinationId, ObjectNode payload) throws IOException {
        framing.write(CastMessage.newBuilder()
                .setProtocolVersion(CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(CastNamespaces.SENDER_ID)
                .setDestinationId(destinationId)
                .setNamespace(namespace)
                .setPayloadType(CastMessage.PayloadType.STRING)
                .setPayloadUtf8(CastPayloads.toJson(payload))
                .build());
    }
}
```

- [ ] **Step 10: Run the connection tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.protocol.*'`
Expected: PASS.

- [ ] **Step 11: Write the failing session tests**

`src/test/java/dev/andre/homecontrol/adapters/cast/CastSessionTest.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CastSessionTest {

    /** heartbeat 1 s, stale 3 s, backoff 1–2 s, command 2 s, load 5 s, media poll 1 s. */
    static final CastProperties PROPERTIES = new CastProperties(true, 1, 3, 1, 2, 2, 5, 1);

    private final List<DeviceState> seen = new CopyOnWriteArrayList<>();
    private FakeCastReceiver receiver;
    private CastSession session;

    static Device device(int port) {
        return new Device("cast-127-0-0-1", "Living Room TV", DeviceKind.CAST, "127.0.0.1",
                Map.of("cast", Map.of("port", String.valueOf(port))), Instant.now());
    }

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = new FakeCastReceiver();
    }

    @AfterEach
    void stop() throws Exception {
        if (session != null) {
            session.close();
        }
        receiver.close();
    }

    private CastSession start(int port) {
        session = new CastSession(device(port), PROPERTIES, seen::add);
        session.start();
        return session;
    }

    private void awaitStatus() {
        await().until(() -> session.state().connected() && session.state().volumeMax() == 100);
    }

    @Test
    void connectsAndReportsVolumeMuteAndTheForegroundApp() {
        receiver.setVolume(0.25, true);
        receiver.runApp("233637DE", "YouTube");

        start(receiver.port());

        await().until(() -> "YouTube".equals(session.state().currentApp()));
        DeviceState state = session.state();
        assertThat(state.status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(state.powerOn()).isTrue();
        assertThat(state.volumeLevel()).isEqualTo(25);
        assertThat(state.volumeMax()).isEqualTo(100);
        assertThat(state.muted()).isTrue();
        assertThat(seen).extracting(DeviceState::status).startsWith(DeviceStatus.CONNECTING);
    }

    @Test
    void theIdleScreenIsNotACurrentApp() {
        start(receiver.port());

        awaitStatus();

        assertThat(session.state().currentApp()).isNull();
    }

    @Test
    void followsUnsolicitedReceiverStatus() throws Exception {
        start(receiver.port());
        awaitStatus();

        receiver.setVolume(0.8, false);
        receiver.pushReceiverStatus();

        await().until(() -> session.state().volumeLevel() == 80);
    }

    @Test
    void reconnectsAfterTheReceiverHangsUp() throws Exception {
        start(receiver.port());
        awaitStatus();

        receiver.dropConnection();

        await().until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));
        await().until(() -> receiver.connections() == 2 && session.state().connected());
    }

    @Test
    void aSilentReceiverIsDetectedAndReconnectedWhenItAnswersAgain() {
        start(receiver.port());
        awaitStatus();

        receiver.goSilent();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));

        receiver.setVolume(0.9, false);
        receiver.resume();

        await().atMost(Duration.ofSeconds(15)).until(() -> session.state().connected() && session.state().volumeLevel() == 90);
    }

    @Test
    void anUnreachableReceiverIsDisconnectedAndRetriedUntilItAppears() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        start(port);

        await().until(() -> session.state().status() == DeviceStatus.DISCONNECTED
                && seen.stream().anyMatch(state -> state.status() == DeviceStatus.CONNECTING));

        try (FakeCastReceiver late = new FakeCastReceiver(port)) {
            await().atMost(Duration.ofSeconds(10)).until(() -> session.state().connected());
        }
    }

    @Test
    void remoteKeysAndAppLinksAreNotCastActions() {
        start(receiver.port());

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME)))
                .isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://youtube.com"))))
                .isInstanceOf(UnsupportedActionException.class);
    }
}
```

Replace `untilTheConnectionExistsTheHandleIsOffline` in `CastAdapterTest` with a test against the fake, and construct the adapter with properties:

```java
    private final CastAdapter adapter = new CastAdapter(new CastDiscovery(new MdnsBrowser(false), event -> { }),
            CastSessionTest.PROPERTIES);

    @Test
    void connectStartsASessionThatReachesTheReceiver() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver();
             DeviceHandle handle = adapter.connect(CastSessionTest.device(receiver.port()), state -> { })) {
            await().until(() -> handle.state().connected());
            assertThat(receiver.virtualConnections()).contains("receiver-0");
        }
    }
```

(imports `FakeCastReceiver`, `org.awaitility.Awaitility.await`; drop the now-unused `DeviceOfflineException`, `Action`, `RemoteKey` imports.)

- [ ] **Step 12: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*'`
Expected: compilation failure — `CastProperties`, `CastSession` do not exist; `CastAdapter` has no two-argument constructor.

- [ ] **Step 13: Write properties, session and the real adapter**

`adapters/cast/CastProperties.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code home-control.cast.*}. Defaults make the module work with no configuration at all. */
@ConfigurationProperties("home-control.cast")
public record CastProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("5") int heartbeatIntervalSeconds,
                             @DefaultValue("15") int staleTimeoutSeconds,
                             @DefaultValue("1") int reconnectInitialDelaySeconds,
                             @DefaultValue("60") int reconnectMaxDelaySeconds,
                             @DefaultValue("5") int commandTimeoutSeconds,
                             @DefaultValue("20") int loadTimeoutSeconds,
                             @DefaultValue("5") int mediaStatusIntervalSeconds) {
}
```

`adapters/cast/CastSession.java` (media and commands arrive in Tasks 4 and 5):

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection, shaped like the Android TV session: every mutation of
 * connection and state happens on one scheduler thread; reconnects back off exponentially.
 * Cast has no pairing, so there is no UNPAIRED state.
 */
public class CastSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile CastConnection connection;
    /** Incremented per connection attempt; callbacks from older connections are ignored. */
    private volatile long generation;
    private volatile DeviceState state = DeviceState.initial();
    private volatile ReceiverStatus receiver;
    private volatile Duration backoff;
    private volatile boolean closed;

    public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = CastSettings.of(device);
        this.properties = properties;
        this.onChange = onChange;
        this.backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cast-session-" + device.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no remote keys");
            case Action.OpenAppLink ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and cannot open app links");
        }
    }

    private void connect() {
        if (closed) {
            return;
        }
        long attempt = ++generation;
        update(state.withStatus(DeviceStatus.CONNECTING));
        CastConnection opened = null;
        try {
            opened = CastConnection.open(device.host(), settings.port(),
                    Duration.ofSeconds(properties.heartbeatIntervalSeconds()),
                    Duration.ofSeconds(properties.staleTimeoutSeconds()),
                    new Link(attempt));
            opened.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()); // the reply arrives via Link
            connection = opened;
            backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
            update(state.withStatus(DeviceStatus.CONNECTED));
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            generation++; // anything the failed attempt still reports is stale
            log.debug("Could not reach Cast receiver {} at {}:{}: {}", device.id(), device.host(), settings.port(), e.getMessage());
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        }
    }

    private void handle(CastIncoming message) {
        if (RECEIVER.equals(message.namespace()) && "RECEIVER_STATUS".equals(message.type())) {
            ReceiverStatus status = ReceiverStatus.parse(message.payload().path("status"));
            receiver = status;
            update(state.withPower(!status.standBy())
                    .withCurrentApp(status.foregroundApp().map(ReceiverStatus.ReceiverApp::displayName).orElse(null))
                    .withVolume(status.volumePercent(), 100, status.muted()));
        }
    }

    private void handleDisconnect(CastDisconnectCause cause) {
        connection = null;
        receiver = null;
        log.info("Lost the Cast connection to {} ({}); reconnecting", device.id(), cause);
        update(state.withStatus(DeviceStatus.DISCONNECTED));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        try {
            scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closing.
        }
    }

    /** Hands a reader-thread callback to the scheduler, dropping it if its connection is outdated. */
    private void runOnScheduler(long attempt, Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(() -> {
                if (!closed && attempt == generation) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // close() shut the scheduler down in between.
        }
    }

    private void update(DeviceState updated) {
        state = updated;
        try {
            onChange.accept(updated);
        } catch (Throwable t) {
            log.warn("A device state listener failed for {}", device.id(), t);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        CastConnection current = connection;
        if (current != null) {
            current.close();
        }
    }

    private final class Link implements CastConnection.Listener {

        private final long attempt;

        Link(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onMessage(CastIncoming message) {
            runOnScheduler(attempt, () -> handle(message));
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            runOnScheduler(attempt, () -> handleDisconnect(cause));
        }
    }
}
```

`CastAdapter`: constructor `CastAdapter(CastDiscovery discovery, CastProperties properties)`; replace `connect` and delete `OfflineHandle`:

```java
    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        CastSession session = new CastSession(device, properties, onChange);
        session.start();
        return session;
    }
```

`CastConfiguration`: annotate with `@EnableConfigurationProperties(CastProperties.class)` (import `org.springframework.boot.context.properties.EnableConfigurationProperties`) and change the adapter bean to `castAdapter(CastDiscovery discovery, CastProperties properties)`.

Replace the `home-control.cast` block in `src/main/resources/application.yaml` and `src/test/resources/application.yaml` with:

```yaml
home-control:
  cast:
    enabled: true
    # A PING every 5 s; 15 s without any inbound frame means the receiver is gone.
    heartbeat-interval-seconds: 5
    stale-timeout-seconds: 15
    reconnect-initial-delay-seconds: 1
    reconnect-max-delay-seconds: 60
    command-timeout-seconds: 5
    load-timeout-seconds: 20
    media-status-interval-seconds: 5
```

- [ ] **Step 14: Run the Cast tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*'` then `.superpowers/gradle.sh build`
Expected: PASS (including `CastModuleSwitchTest`); BUILD SUCCESSFUL.

- [ ] **Step 15: Commit**

```bash
git add -A
git commit -m "feat: keep a live CASTV2 connection to every registered Cast receiver"
```

---

### Task 4: B4 · Cast actions

**Files:**
- Create: `core/ActionFailedException.java`
- Modify: `core/Action.java`, `adapters/androidtv/AndroidTvSession.java`, `adapters/cast/CastSession.java`, `adapters/cast/CastAdapter.java`, `device/DeviceManager.java`, `web/DeviceController.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`
- Test: `core/ActionTest.java`, `device/DeviceManagerExecuteTest.java`, `adapters/cast/CastSessionTest.java`, `adapters/cast/CastAdapterTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`

**Interfaces:**
- Consumes: `CastSession` and `FakeCastReceiver` (Task 3), `StubAdapter` (Task 2), `DeviceController`/`DashboardController` (A).
- Produces:
  - `Action.SetVolume(int level)` (0–100, else `IllegalArgumentException`) → `VOLUME`; `Action.Mute(boolean muted)` → `VOLUME`; `Action.Stop()` → `CAST_RECEIVER`.
  - `ActionFailedException(String message)` in `core`.
  - `CastAdapter.capabilities` = `CAST_RECEIVER, VOLUME`.
  - `DeviceManager.execute` falls through adapters declaring the capability on `UnsupportedActionException`/`DeviceOfflineException`; throws the first offline reason, else the last unsupported reason; `ActionFailedException` propagates at once.
  - `POST /devices/{id}/volume` (`level`), `POST /devices/{id}/mute` (`muted`), `POST /devices/{id}/stop` → 204 / 400 / 404 / 409 / 422 / 502.
  - Dashboard model attributes `remoteKeys` (REMOTE_KEYS) and `castControls` (CAST_RECEIVER).

- [ ] **Step 1: Write the failing core and manager tests**

Add to `core/ActionTest.java`:

```java
    @Test
    void volumeActionsRequireVolumeAndStopRequiresACastReceiver() {
        assertThat(new Action.SetVolume(40).requires()).isEqualTo(Capability.VOLUME);
        assertThat(new Action.Mute(true).requires()).isEqualTo(Capability.VOLUME);
        assertThat(new Action.Stop().requires()).isEqualTo(Capability.CAST_RECEIVER);
    }

    @Test
    void aVolumeLevelIsAPercentage() {
        assertThat(new Action.SetVolume(0).level()).isZero();
        assertThat(new Action.SetVolume(100).level()).isEqualTo(100);
        assertThatThrownBy(() -> new Action.SetVolume(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Action.SetVolume(101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Volume must be between 0 and 100");
    }
```

(import `static org.assertj.core.api.Assertions.assertThatThrownBy`.)

`src/test/java/dev/andre/homecontrol/device/DeviceManagerExecuteTest.java`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceManagerExecuteTest {

    @TempDir
    Path dir;

    private final StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, true,
            Capability.REMOTE_KEYS, Capability.APP_LINK, Capability.VOLUME);
    private final StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false,
            Capability.CAST_RECEIVER, Capability.VOLUME);
    private DeviceManager manager;

    @BeforeEach
    void aMergedShield() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("androidtv", Map.of("port", "6466"));
        adapters.put("cast", Map.of("port", "8009"));
        registry.save(new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5", adapters, Instant.now()));
        manager = new DeviceManager(registry, List.of(androidtv, cast), event -> { });
        manager.start();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void theFirstAdapterThatCanDoItIsTheOnlyOneAsked() {
        manager.execute("shield", new Action.SetVolume(10));

        assertThat(androidtv.handles.get("shield").executed).containsExactly(new Action.SetVolume(10));
        assertThat(cast.handles.get("shield").executed).isEmpty();
    }

    @Test
    void anActionTheFirstAdapterCannotDoFallsThroughToTheNext() {
        androidtv.handles.get("shield").failure = new UnsupportedActionException("no absolute volume");

        manager.execute("shield", new Action.SetVolume(40));

        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.SetVolume(40));
    }

    @Test
    void anOfflineFirstAdapterFallsThroughToo() {
        androidtv.handles.get("shield").failure = new DeviceOfflineException("must be paired again");

        manager.execute("shield", new Action.Mute(true));

        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.Mute(true));
    }

    @Test
    void whenNoAdapterCouldSendTheOfflineReasonWins() {
        androidtv.handles.get("shield").failure = new DeviceOfflineException("Shield must be paired again");
        cast.handles.get("shield").failure = new UnsupportedActionException("not this one");

        assertThatThrownBy(() -> manager.execute("shield", new Action.SetVolume(5)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("paired again");
    }

    @Test
    void aRefusalByTheDeviceIsFinal() {
        androidtv.handles.get("shield").failure = new ActionFailedException("Shield refused");

        assertThatThrownBy(() -> manager.execute("shield", new Action.SetVolume(5)))
                .isInstanceOf(ActionFailedException.class);
        assertThat(cast.handles.get("shield").executed).isEmpty();
    }

    @Test
    void onlyAdaptersDeclaringTheCapabilityAreAsked() {
        manager.execute("shield", new Action.Stop());

        assertThat(androidtv.handles.get("shield").executed).isEmpty();
        assertThat(cast.handles.get("shield").executed).containsExactly(new Action.Stop());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.ActionTest' --tests 'dev.andre.homecontrol.device.DeviceManagerExecuteTest'`
Expected: compilation failure — the new actions and `ActionFailedException` do not exist.

- [ ] **Step 3: Implement the core actions and the fall-through**

Add to `core/Action.java`:

```java
    /** Absolute volume as a percentage of the device's range. */
    record SetVolume(int level) implements Action {
        public SetVolume {
            if (level < 0 || level > 100) {
                throw new IllegalArgumentException("Volume must be between 0 and 100");
            }
        }

        @Override
        public Capability requires() {
            return Capability.VOLUME;
        }
    }

    record Mute(boolean muted) implements Action {
        @Override
        public Capability requires() {
            return Capability.VOLUME;
        }
    }

    /** Stop whatever is being cast. */
    record Stop() implements Action {
        @Override
        public Capability requires() {
            return Capability.CAST_RECEIVER;
        }
    }
```

`core/ActionFailedException.java`:

```java
package dev.andre.homecontrol.core;

/** The device was reached but refused the action or never answered. HTTP 502; the message is user-facing. */
public class ActionFailedException extends RuntimeException {
    public ActionFailedException(String message) {
        super(message);
    }
}
```

`AndroidTvSession.execute` — the switch over the sealed `Action` must stay exhaustive:

```java
    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> sendKey(press.key());
            case Action.OpenAppLink open -> openAppLink(open.uri());
            case Action.SetVolume ignored -> throw new UnsupportedActionException(
                    "Android TV Remote v2 has no absolute volume; use the volume keys");
            case Action.Mute ignored -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot set mute directly; use the mute key");
            case Action.Stop ignored -> throw new UnsupportedActionException(
                    "Android TV Remote v2 cannot stop a cast");
        }
    }
```

`DeviceManager.execute` — replace:

```java
    /**
     * Tries the device's adapters that declare the needed capability, in order. An adapter that
     * could not even send (unsupported, offline) hands over to the next; an adapter whose device
     * answered "no" ends it. Nothing is retried later (commands are ephemeral).
     */
    public void execute(String id, Action action) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + id));
        Map<String, DeviceHandle> deviceHandles = handles.getOrDefault(id, Map.of());
        DeviceOfflineException firstOffline = null;
        UnsupportedActionException lastUnsupported = null;
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            DeviceHandle handle = deviceHandles.get(adapterId);
            if (adapter == null || handle == null || !adapter.capabilities(device).contains(action.requires())) {
                continue;
            }
            try {
                handle.execute(action);
                return;
            } catch (DeviceOfflineException e) {
                if (firstOffline == null) {
                    firstOffline = e;
                }
            } catch (UnsupportedActionException e) {
                lastUnsupported = e;
            }
        }
        if (firstOffline != null) {
            throw firstOffline;
        }
        if (lastUnsupported != null) {
            throw lastUnsupported;
        }
        throw new UnsupportedActionException(device.name() + " cannot perform " + action);
    }
```

- [ ] **Step 4: Run the core and manager tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*'`
Expected: PASS (A's `DeviceManagerTest` included).

- [ ] **Step 5: Write the failing Cast command tests**

Add to `CastSessionTest` (imports: `ActionFailedException`, `DeviceOfflineException`, `static ...CastNamespaces.RECEIVER`):

```java
    @Test
    void setsTheVolumeAsAFractionOfOne() {
        start(receiver.port());
        awaitStatus();

        session.execute(new Action.SetVolume(30));

        assertThat(receiver.last(RECEIVER, "SET_VOLUME").orElseThrow().payload().path("volume").path("level").asDouble(-1))
                .isEqualTo(0.3);
        await().until(() -> session.state().volumeLevel() == 30);
    }

    @Test
    void mutesAndUnmutes() {
        start(receiver.port());
        awaitStatus();

        session.execute(new Action.Mute(true));
        await().until(() -> session.state().muted());
        session.execute(new Action.Mute(false));

        await().until(() -> !session.state().muted());
        assertThat(receiver.muted()).isFalse();
    }

    @Test
    void stopsTheForegroundApp() {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        start(receiver.port());
        await().until(() -> "Default Media Receiver".equals(session.state().currentApp()));

        session.execute(new Action.Stop());

        assertThat(receiver.last(RECEIVER, "STOP").orElseThrow().payload().path("sessionId").asString(""))
                .isEqualTo("session-1");
        await().until(() -> session.state().currentApp() == null);
    }

    @Test
    void stoppingWhileNothingIsCastingSendsNothing() {
        start(receiver.port());
        awaitStatus();

        session.execute(new Action.Stop());

        assertThat(receiver.received(RECEIVER, "STOP")).isEmpty();
    }

    @Test
    void aCommandTheReceiverNeverAnswersFailsWithAReason() {
        receiver.ignore("SET_VOLUME");
        start(receiver.port());
        awaitStatus();

        assertThatThrownBy(() -> session.execute(new Action.SetVolume(10)))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("did not answer");
    }

    @Test
    void commandsWhileDisconnectedAreRejectedNotQueued() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        start(port);
        await().until(() -> session.state().status() == DeviceStatus.DISCONNECTED);

        assertThatThrownBy(() -> session.execute(new Action.SetVolume(10)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("not connected");
    }
```

Add to `CastAdapterTest`:

```java
    @Test
    void declaresCastReceiverAndVolume() {
        assertThat(adapter.capabilities(CastSessionTest.device(8009)))
                .containsExactlyInAnyOrder(Capability.CAST_RECEIVER, Capability.VOLUME);
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*'`
Expected: compilation failure (switch in `CastSession.execute` no longer exhaustive), then FAIL on capabilities.

- [ ] **Step 7: Implement the Cast commands**

`CastAdapter.capabilities` returns `EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME)`.

In `CastSession` replace `execute` and add the helpers (imports: `ActionFailedException`, `DeviceOfflineException`, `CastTimeoutException`, `tools.jackson.databind.node.ObjectNode`, `java.util.Optional`):

```java
    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no remote keys");
            case Action.OpenAppLink ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and cannot open app links");
            case Action.SetVolume set -> receiverCommand(CastPayloads.setVolumeLevel(set.level() / 100.0), "set the volume");
            case Action.Mute mute -> receiverCommand(CastPayloads.setMuted(mute.muted()), mute.muted() ? "mute" : "unmute");
            case Action.Stop ignored -> stopForegroundApp();
        }
    }

    /** Receiver-namespace commands are answered with a RECEIVER_STATUS; anything else is a refusal. */
    private void receiverCommand(ObjectNode payload, String what) {
        CastConnection current = requireConnected();
        CastIncoming reply = call(() -> current.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, commandTimeout()), what);
        if (!"RECEIVER_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to " + what + " (" + reply.describeFailure() + ")");
        }
    }

    private void stopForegroundApp() {
        CastConnection current = requireConnected();
        ReceiverStatus status = receiver;
        Optional<ReceiverStatus.ReceiverApp> app = status == null ? Optional.empty() : status.foregroundApp();
        if (app.isEmpty()) {
            return; // nothing is casting, so it is already stopped
        }
        CastIncoming reply = call(() -> current.request(RECEIVER, PLATFORM_RECEIVER_ID,
                CastPayloads.stop(app.get().sessionId()), commandTimeout()), "stop " + app.get().displayName());
        if (!"RECEIVER_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to stop " + app.get().displayName()
                    + " (" + reply.describeFailure() + ")");
        }
    }

    private CastConnection requireConnected() {
        CastConnection current = connection;
        if (current == null || state.status() != DeviceStatus.CONNECTED) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        return current;
    }

    @FunctionalInterface
    private interface CastCall<T> {
        T run() throws IOException;
    }

    /** Maps protocol failures onto the core vocabulary: no answer → failed; lost channel → offline. */
    private <T> T call(CastCall<T> call, String what) {
        try {
            return call.run();
        } catch (CastTimeoutException e) {
            throw new ActionFailedException(device.name() + " did not answer in time when asked to " + what);
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection while trying to " + what);
        }
    }

    private Duration commandTimeout() {
        return Duration.ofSeconds(properties.commandTimeoutSeconds());
    }
```

- [ ] **Step 8: Run the Cast tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS.

- [ ] **Step 9: Write the failing web tests**

Add to `DeviceControllerTest` (imports `ActionFailedException`):

```java
    @Test
    void setsTheVolume() throws Exception {
        mockMvc.perform(post("/devices/shield/volume").param("level", "40")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.SetVolume(40));
    }

    @Test
    void rejectsAVolumeOutsideZeroToHundred() throws Exception {
        mockMvc.perform(post("/devices/shield/volume").param("level", "150"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Volume must be between 0 and 100"));
    }

    @Test
    void mutesAndStops() throws Exception {
        mockMvc.perform(post("/devices/shield/mute").param("muted", "true")).andExpect(status().isNoContent());
        mockMvc.perform(post("/devices/shield/stop")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.Mute(true));
        verify(devices).execute("shield", new Action.Stop());
    }

    @Test
    void volumeForAnUnknownDeviceIsNotFound() throws Exception {
        mockMvc.perform(post("/devices/ghost/volume").param("level", "10")).andExpect(status().isNotFound());
    }

    @Test
    void reportsBadGatewayWhenTheDeviceRefusesOrDoesNotAnswer() throws Exception {
        willThrow(new ActionFailedException("Kitchen did not answer in time when asked to set the volume"))
                .given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/volume").param("level", "10"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Kitchen did not answer in time when asked to set the volume"));
    }
```

Add to `DashboardPageTest` (imports as needed):

```java
    @Test
    void aCastOnlyDeviceGetsCastControlsInsteadOfTheRemote() throws Exception {
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of("port", "8009")), Instant.now());
        given(devices.devices()).willReturn(List.of(kitchen));
        given(devices.defaultDevice()).willReturn(Optional.of(kitchen));
        given(devices.device("cast-10-0-0-9")).willReturn(Optional.of(kitchen));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/volume")))
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/mute")))
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/stop")))
                .andExpect(content().string(not(containsString("/devices/cast-10-0-0-9/key/"))));
    }
```

In A's `showsEveryDeviceAndTheRemoteForTheSelectedOne` add `.andExpect(content().string(not(containsString("/devices/living/volume"))))` — that device has no `CAST_RECEIVER`.

- [ ] **Step 10: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`
Expected: FAIL — 404 for the new endpoints; dashboard has keys and no Cast controls.

- [ ] **Step 11: Implement the endpoints and drawer**

`DeviceController` (imports `ActionFailedException`):

```java
    @PostMapping("/devices/{id}/volume")
    public ResponseEntity<String> volume(@PathVariable String id, @RequestParam int level) {
        return command(id, new Action.SetVolume(level));
    }

    @PostMapping("/devices/{id}/mute")
    public ResponseEntity<String> mute(@PathVariable String id, @RequestParam boolean muted) {
        return command(id, new Action.Mute(muted));
    }

    @PostMapping("/devices/{id}/stop")
    public ResponseEntity<String> stop(@PathVariable String id) {
        return command(id, new Action.Stop());
    }

    /** Volume and stop go straight to the adapter, not through the planner (spec §5.3). */
    private ResponseEntity<String> command(String id, Action action) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        devices.execute(id, action);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ActionFailedException.class)
    public ResponseEntity<String> failed(ActionFailedException e) {
        return text(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
```

(The existing `IllegalArgumentException` handler turns an out-of-range `SetVolume` into 400.)

`DashboardController.dashboard`: compute `Set<Capability> capabilities = devices.capabilities(device.id());` once and add `model.addAttribute("remoteKeys", capabilities.contains(Capability.REMOTE_KEYS));` and `model.addAttribute("castControls", capabilities.contains(Capability.CAST_RECEIVER));` (keep `canOpenLinks`, computed from the same set).

`dashboard.html`: wrap the D-pad section and the three `<section class="row">` key rows in `<th:block th:if="${remoteKeys}"> … </th:block>`. Before the open-link section add:

```html
    <section class="cast" th:if="${castControls}">
        <h2>Cast</h2>
        <label class="volume">Volume
            <input type="range" name="level" min="0" max="100" step="1"
                   th:value="${selectedState.volumeLevel()}"
                   th:attr="hx-post=@{/devices/{id}/volume(id=${id})}" hx-trigger="change">
        </label>
        <div class="row">
            <button th:attr="hx-post=@{/devices/{id}/mute(id=${id})}" hx-vals='{"muted": "true"}'>Mute</button>
            <button th:attr="hx-post=@{/devices/{id}/mute(id=${id})}" hx-vals='{"muted": "false"}'>Unmute</button>
            <button th:attr="hx-post=@{/devices/{id}/stop(id=${id})}">Stop casting</button>
        </div>
    </section>
```

Append to `app.css`:

```css
.cast .volume { display: flex; align-items: center; gap: .75rem; margin-bottom: .75rem; }
.cast .volume input { flex: 1; }
```

- [ ] **Step 12: Run the web tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: set volume, mute and stop on Cast receivers"
```

---

### Task 5: B5 · Default Media Receiver playback

**Files:**
- Create: `core/NowPlaying.java`, `core/PlaybackState.java`, `core/playback/CastLoads.java`, `adapters/cast/protocol/MediaStatus.java`
- Modify: `core/DeviceState.java`, `core/DeviceStates.java`, `core/Action.java`, `adapters/cast/CastSession.java`, `adapters/androidtv/AndroidTvSession.java`, `src/main/resources/templates/dashboard.html`, `src/main/resources/static/js/state-view.js`
- Test: `core/DeviceStateTest.java`, `core/DeviceStatesTest.java`, `core/ActionTest.java`, `core/playback/CastLoadsTest.java`, `adapters/cast/protocol/MediaStatusTest.java`, `adapters/cast/CastSessionTest.java`, `web/DashboardPageTest.java`, `web/StaticAssetsTest.java`; fixtures `src/test/resources/fixtures/cast/media-status-playing.json`, `media-status-partial.json`, `media-status-idle.json`

**Interfaces:**
- Consumes: `CastConnection.expect/request/nextRequestId`, `ReceiverStatus`, `FakeCastReceiver` (LAUNCH, LOAD, media), `PlayableRef.StreamUrl(URI url, String mimeType)` (A).
- Produces:
  - `enum PlaybackState { PLAYING, PAUSED, BUFFERING, IDLE }`; `record NowPlaying(String title, PlaybackState state, double positionSeconds, Double durationSeconds)`.
  - `DeviceState(..., Instant updatedAt, NowPlaying nowPlaying)` + the old 7-argument constructor + `withNowPlaying(NowPlaying)`; SSE JSON gains `"nowPlaying":{"title","state","positionSeconds","durationSeconds"}` or `null`.
  - `DeviceStates.compose` takes `nowPlaying` from the first adapter reporting one.
  - `Action.CastLoad(String receiverAppId, Map<String,Object> load)` → `CAST_RECEIVER`.
  - `CastLoads.DEFAULT_MEDIA_RECEIVER = "CC1AD845"`, `CastLoads.defaultMediaReceiver(PlayableRef.StreamUrl, String title) → Map<String,Object>`.
  - `record MediaStatus(long mediaSessionId, String playerState, double currentTime, String contentId, String title, Double duration, String idleReason)` with `parse(JsonNode statusArray)`, `fillFrom(MediaStatus previous)`, `displayTitle()`, `playbackState()`.
  - `CastSession` executes `CastLoad` (launch if needed → CONNECT transport → LOAD), follows the foreground app's media channel, reports `nowPlaying`, polls position while playing.

- [ ] **Step 1: Write the failing core tests**

`src/test/java/dev/andre/homecontrol/core/DeviceStateTest.java`:

```java
package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceStateTest {

    private static final NowPlaying BUNNY = new NowPlaying("Big Buck Bunny", PlaybackState.PLAYING, 12.5, 596.5);

    @Test
    void theSevenArgumentConstructorHasNothingPlaying() {
        assertThat(new DeviceState(DeviceStatus.CONNECTED, true, null, 1, 100, false, Instant.EPOCH).nowPlaying()).isNull();
        assertThat(DeviceState.initial().nowPlaying()).isNull();
    }

    @Test
    void everyWitherKeepsWhatIsPlaying() {
        DeviceState playing = DeviceState.initial().withNowPlaying(BUNNY);

        assertThat(playing.withStatus(DeviceStatus.CONNECTED).nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withPower(true).nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withCurrentApp("Default Media Receiver").nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withVolume(3, 100, false).nowPlaying()).isEqualTo(BUNNY);
        assertThat(playing.withNowPlaying(null).nowPlaying()).isNull();
    }
}
```

Add to `DeviceStatesTest`:

```java
    @Test
    void nowPlayingComesFromWhicheverAdapterReportsIt() {
        NowPlaying bunny = new NowPlaying("Big Buck Bunny", PlaybackState.PAUSED, 30.0, null);
        DeviceState androidTv = new DeviceState(DeviceStatus.CONNECTED, true, "com.google.android.youtube.tv", 5, 100, false, EARLY);
        DeviceState cast = new DeviceState(DeviceStatus.CONNECTED, true, "Default Media Receiver", 30, 100, false, EARLY, bunny);

        assertThat(DeviceStates.compose(List.of(androidTv, cast)).nowPlaying()).isEqualTo(bunny);
    }
```

Add to `ActionTest`:

```java
    @Test
    void aCastLoadRequiresACastReceiverAndCopiesItsBody() {
        java.util.Map<String, Object> body = new java.util.HashMap<>(java.util.Map.of("autoplay", true));
        Action.CastLoad load = new Action.CastLoad("CC1AD845", body);
        body.put("autoplay", false);

        assertThat(load.requires()).isEqualTo(Capability.CAST_RECEIVER);
        assertThat(load.load()).containsEntry("autoplay", true);
    }
```

`src/test/java/dev/andre/homecontrol/core/playback/CastLoadsTest.java`:

```java
package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CastLoadsTest {

    @Test
    void buildsADefaultMediaReceiverLoadForAStream() {
        Map<String, Object> load = CastLoads.defaultMediaReceiver(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4"), "Big Buck Bunny");

        assertThat(load).containsEntry("autoplay", true).containsEntry("currentTime", 0);
        assertThat(load.get("media")).isEqualTo(Map.of(
                "contentId", "http://nas.local/films/bunny.mp4",
                "contentUrl", "http://nas.local/films/bunny.mp4",
                "contentType", "video/mp4",
                "streamType", "BUFFERED",
                "metadata", Map.of("metadataType", 0, "title", "Big Buck Bunny")));
    }

    @Test
    void omitsABlankTitleAndAssumesMp4WithoutAMimeType() {
        Map<String, Object> load = CastLoads.defaultMediaReceiver(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/x"), null), " ");

        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) load.get("media");
        assertThat(media).containsEntry("contentType", "video/mp4");
        assertThat(media.get("metadata")).isEqualTo(Map.of("metadataType", 0));
        assertThat(CastLoads.DEFAULT_MEDIA_RECEIVER).isEqualTo("CC1AD845");
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*'`
Expected: compilation failure — `NowPlaying`, `PlaybackState`, `Action.CastLoad`, `CastLoads` do not exist.

- [ ] **Step 3: Implement the core changes**

`core/PlaybackState.java`:

```java
package dev.andre.homecontrol.core;

public enum PlaybackState {
    PLAYING, PAUSED, BUFFERING, IDLE
}
```

`core/NowPlaying.java`:

```java
package dev.andre.homecontrol.core;

/**
 * What a device is playing, as far as its adapter can tell (spec §6.2). The position is as of
 * the owning state's {@code updatedAt}; {@code durationSeconds} is null for live or unknown media.
 */
public record NowPlaying(String title, PlaybackState state, double positionSeconds, Double durationSeconds) {
}
```

`core/DeviceState.java` (replace; same methods as before plus `nowPlaying`):

```java
package dev.andre.homecontrol.core;

import java.time.Instant;

/** Last known state of one device. {@code nowPlaying} is null when nothing plays or the adapter cannot tell. */
public record DeviceState(DeviceStatus status, boolean powerOn, String currentApp,
                          int volumeLevel, int volumeMax, boolean muted, Instant updatedAt, NowPlaying nowPlaying) {

    /** For adapters that cannot report media, and every call site that predates it. */
    public DeviceState(DeviceStatus status, boolean powerOn, String currentApp,
                       int volumeLevel, int volumeMax, boolean muted, Instant updatedAt) {
        this(status, powerOn, currentApp, volumeLevel, volumeMax, muted, updatedAt, null);
    }

    public static DeviceState initial() {
        return new DeviceState(DeviceStatus.DISCONNECTED, false, null, 0, 0, false, Instant.now());
    }

    public static DeviceState unpaired() {
        return new DeviceState(DeviceStatus.UNPAIRED, false, null, 0, 0, false, Instant.now());
    }

    public boolean connected() {
        return status == DeviceStatus.CONNECTED;
    }

    public DeviceState withStatus(DeviceStatus newStatus) {
        return new DeviceState(newStatus, powerOn, currentApp, volumeLevel, volumeMax, muted, Instant.now(), nowPlaying);
    }

    public DeviceState withPower(boolean on) {
        return new DeviceState(status, on, currentApp, volumeLevel, volumeMax, muted, Instant.now(), nowPlaying);
    }

    public DeviceState withCurrentApp(String appPackage) {
        return new DeviceState(status, powerOn, appPackage, volumeLevel, volumeMax, muted, Instant.now(), nowPlaying);
    }

    public DeviceState withVolume(int level, int max, boolean isMuted) {
        return new DeviceState(status, powerOn, currentApp, level, max, isMuted, Instant.now(), nowPlaying);
    }

    public DeviceState withNowPlaying(NowPlaying playing) {
        return new DeviceState(status, powerOn, currentApp, volumeLevel, volumeMax, muted, Instant.now(), playing);
    }
}
```

`DeviceStates.compose`: declare `NowPlaying nowPlaying = null;`, inside the loop `if (nowPlaying == null && state.nowPlaying() != null) { nowPlaying = state.nowPlaying(); }`, and return with the 8-argument constructor passing `nowPlaying` last.

Add to `core/Action.java` (imports `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.Map`, `java.util.Objects`):

```java
    /**
     * Start receiver app {@code receiverAppId} if it is not running and send it a media LOAD
     * whose body is {@code load} (without type, requestId, sessionId). Spec §5.2 {@code CastLoad}.
     */
    record CastLoad(String receiverAppId, Map<String, Object> load) implements Action {
        public CastLoad {
            Objects.requireNonNull(receiverAppId, "receiverAppId");
            load = load == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(load));
        }

        @Override
        public Capability requires() {
            return Capability.CAST_RECEIVER;
        }
    }
```

`core/playback/CastLoads.java`:

```java
package dev.andre.homecontrol.core.playback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** LOAD bodies for {@link PlayableRef.CastLoad} and {@code Action.CastLoad}. */
public final class CastLoads {

    /** Google's Default Media Receiver: plays a URL the receiver can fetch directly. */
    public static final String DEFAULT_MEDIA_RECEIVER = "CC1AD845";

    private CastLoads() {
    }

    /**
     * Both {@code contentId} and {@code contentUrl} carry the URL: CAF receivers prefer
     * {@code contentUrl}, older ones only read {@code contentId}. Generic metadata (type 0).
     */
    public static Map<String, Object> defaultMediaReceiver(PlayableRef.StreamUrl stream, String title) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("metadataType", 0);
        if (title != null && !title.isBlank()) {
            metadata.put("title", title);
        }
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("contentId", stream.url().toString());
        media.put("contentUrl", stream.url().toString());
        media.put("contentType", Objects.requireNonNullElse(stream.mimeType(), "video/mp4"));
        media.put("streamType", "BUFFERED");
        media.put("metadata", metadata);
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("media", media);
        load.put("autoplay", true);
        load.put("currentTime", 0);
        return Collections.unmodifiableMap(load);
    }
}
```

`AndroidTvSession.execute`: add `case Action.CastLoad ignored -> throw new UnsupportedActionException("Android TV Remote v2 cannot load Cast media");`.

`CastSession.execute`: add a temporary `case Action.CastLoad ignored -> throw new UnsupportedActionException("not yet");` so the build compiles; Step 8 replaces the file.

- [ ] **Step 4: Run the core tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*'`
Expected: PASS.

- [ ] **Step 5: Write the failing media status tests**

Fixture `src/test/resources/fixtures/cast/media-status-playing.json`:

```json
{
  "type": "MEDIA_STATUS",
  "requestId": 6,
  "status": [
    {
      "mediaSessionId": 1,
      "playbackRate": 1,
      "playerState": "PLAYING",
      "currentTime": 12.5,
      "supportedMediaCommands": 274447,
      "volume": {"level": 1, "muted": false},
      "activeTrackIds": [],
      "media": {
        "contentId": "http://nas.local/films/bunny.mp4",
        "contentUrl": "http://nas.local/films/bunny.mp4",
        "streamType": "BUFFERED",
        "contentType": "video/mp4",
        "metadata": {"metadataType": 0, "title": "Big Buck Bunny"},
        "duration": 596.474195,
        "tracks": [],
        "breakClips": [],
        "breaks": []
      },
      "currentItemId": 1,
      "items": [{"itemId": 1, "media": {"contentId": "http://nas.local/films/bunny.mp4"}, "orderId": 0}],
      "repeatMode": "REPEAT_OFF"
    }
  ]
}
```

Fixture `media-status-partial.json` (receivers omit `media` once the sender has seen it):

```json
{
  "type": "MEDIA_STATUS",
  "requestId": 0,
  "status": [
    {
      "mediaSessionId": 1,
      "playbackRate": 1,
      "playerState": "PAUSED",
      "currentTime": 30.2,
      "supportedMediaCommands": 274447,
      "volume": {"level": 1, "muted": false},
      "currentItemId": 1,
      "repeatMode": "REPEAT_OFF"
    }
  ]
}
```

Fixture `media-status-idle.json`:

```json
{
  "type": "MEDIA_STATUS",
  "requestId": 0,
  "status": [
    {
      "mediaSessionId": 1,
      "playbackRate": 1,
      "playerState": "IDLE",
      "currentTime": 0,
      "supportedMediaCommands": 274447,
      "volume": {"level": 1, "muted": false},
      "idleReason": "FINISHED"
    }
  ]
}
```

`src/test/java/dev/andre/homecontrol/adapters/cast/protocol/MediaStatusTest.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.core.PlaybackState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MediaStatusTest {

    private static List<MediaStatus> fixture(String name) throws Exception {
        return MediaStatus.parse(CastPayloads.parse(
                Files.readString(Path.of("src/test/resources/fixtures/cast/" + name))).path("status"));
    }

    @Test
    void readsAPlayingStatusWithItsMedia() throws Exception {
        MediaStatus status = fixture("media-status-playing.json").getFirst();

        assertThat(status.mediaSessionId()).isEqualTo(1);
        assertThat(status.playbackState()).isEqualTo(PlaybackState.PLAYING);
        assertThat(status.currentTime()).isEqualTo(12.5);
        assertThat(status.title()).isEqualTo("Big Buck Bunny");
        assertThat(status.duration()).isEqualTo(596.474195);
        assertThat(status.contentId()).isEqualTo("http://nas.local/films/bunny.mp4");
        assertThat(status.displayTitle()).isEqualTo("Big Buck Bunny");
    }

    @Test
    void aPartialStatusKeepsWhatThePreviousOneKnewForTheSameSession() throws Exception {
        MediaStatus playing = fixture("media-status-playing.json").getFirst();
        MediaStatus partial = fixture("media-status-partial.json").getFirst();

        assertThat(partial.title()).isNull();
        assertThat(partial.displayTitle()).isEqualTo("Unknown media");

        MediaStatus filled = partial.fillFrom(playing);

        assertThat(filled.title()).isEqualTo("Big Buck Bunny");
        assertThat(filled.duration()).isEqualTo(596.474195);
        assertThat(filled.playbackState()).isEqualTo(PlaybackState.PAUSED);
        assertThat(filled.currentTime()).isEqualTo(30.2);
    }

    @Test
    void doesNotFillFromADifferentMediaSession() throws Exception {
        MediaStatus other = new MediaStatus(2, "PLAYING", 1.0, "http://x/other.mp4", "Other", 10.0, null);

        assertThat(fixture("media-status-partial.json").getFirst().fillFrom(other).title()).isNull();
        assertThat(fixture("media-status-partial.json").getFirst().fillFrom(null).title()).isNull();
    }

    @Test
    void readsIdleAndEmptyStatuses() throws Exception {
        MediaStatus idle = fixture("media-status-idle.json").getFirst();

        assertThat(idle.playbackState()).isEqualTo(PlaybackState.IDLE);
        assertThat(idle.idleReason()).isEqualTo("FINISHED");
        assertThat(MediaStatus.parse(CastPayloads.parse("[]"))).isEmpty();
    }

    @Test
    void anUnknownPlayerStateCountsAsBufferingAndTheFileNameIsAFallbackTitle() {
        MediaStatus loading = new MediaStatus(1, "LOADING", 0, "http://nas.local/films/big%20buck.mp4?token=1", null, null, null);

        assertThat(loading.playbackState()).isEqualTo(PlaybackState.BUFFERING);
        assertThat(loading.displayTitle()).isEqualTo("big buck.mp4");
    }
}
```

- [ ] **Step 6: Implement `MediaStatus`**

`adapters/cast/protocol/MediaStatus.java`:

```java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.core.PlaybackState;
import tools.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One entry of a MEDIA_STATUS {@code status} array. Nullable fields were absent. */
public record MediaStatus(long mediaSessionId, String playerState, double currentTime, String contentId,
                          String title, Double duration, String idleReason) {

    public static List<MediaStatus> parse(JsonNode statusArray) {
        List<MediaStatus> statuses = new ArrayList<>();
        for (JsonNode entry : statusArray) {
            JsonNode media = entry.path("media");
            JsonNode metadata = media.path("metadata");
            statuses.add(new MediaStatus(
                    entry.path("mediaSessionId").asLong(0),
                    entry.path("playerState").asString("IDLE"),
                    entry.path("currentTime").asDouble(0.0),
                    media.has("contentId") ? media.path("contentId").asString("") : null,
                    metadata.has("title") ? metadata.path("title").asString("") : null,
                    media.path("duration").isNumber() ? media.path("duration").asDouble(0.0) : null,
                    entry.has("idleReason") ? entry.path("idleReason").asString("") : null));
        }
        return List.copyOf(statuses);
    }

    /** Receivers send {@code media} once per session; later statuses inherit it. */
    public MediaStatus fillFrom(MediaStatus previous) {
        if (previous == null || previous.mediaSessionId != mediaSessionId) {
            return this;
        }
        return new MediaStatus(mediaSessionId, playerState, currentTime,
                contentId != null ? contentId : previous.contentId,
                title != null ? title : previous.title,
                duration != null ? duration : previous.duration,
                idleReason);
    }

    public PlaybackState playbackState() {
        return switch (playerState) {
            case "PLAYING" -> PlaybackState.PLAYING;
            case "PAUSED" -> PlaybackState.PAUSED;
            case "IDLE" -> PlaybackState.IDLE;
            default -> PlaybackState.BUFFERING;
        };
    }

    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (contentId != null && !contentId.isBlank()) {
            String path = contentId.split("[?#]", 2)[0];
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.isBlank()) {
                return URLDecoder.decode(name, StandardCharsets.UTF_8);
            }
        }
        return "Unknown media";
    }
}
```

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.protocol.MediaStatusTest'`
Expected: PASS.

- [ ] **Step 7: Write the failing playback tests**

Add to `CastSessionTest` (imports: `dev.andre.homecontrol.core.NowPlaying`, `PlaybackState`, `dev.andre.homecontrol.core.playback.CastLoads`, `dev.andre.homecontrol.core.playback.PlayableRef`, `static ...CastNamespaces.MEDIA`, `static ...CastNamespaces.CONNECTION`):

```java
    private static Action.CastLoad bunny() {
        return new Action.CastLoad(CastLoads.DEFAULT_MEDIA_RECEIVER, CastLoads.defaultMediaReceiver(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4"), "Big Buck Bunny"));
    }

    private void awaitFollowing(String title) {
        await().until(() -> session.state().nowPlaying() != null && title.equals(session.state().nowPlaying().title()));
    }

    @Test
    void launchesTheDefaultMediaReceiverAndLoadsTheStream() {
        start(receiver.port());
        awaitStatus();

        session.execute(bunny());

        assertThat(receiver.last(RECEIVER, "LAUNCH").orElseThrow().payload().path("appId").asString("")).isEqualTo("CC1AD845");
        CastIncoming load = receiver.last(MEDIA, "LOAD").orElseThrow();
        assertThat(load.destinationId()).isEqualTo("transport-1");
        assertThat(load.payload().path("sessionId").asString("")).isEqualTo("session-1");
        assertThat(load.payload().path("media").path("contentId").asString("")).isEqualTo("http://nas.local/films/bunny.mp4");
        assertThat(load.payload().path("media").path("contentType").asString("")).isEqualTo("video/mp4");
        assertThat(load.payload().path("autoplay").asBoolean(false)).isTrue();
        assertThat(receiver.virtualConnections()).contains("transport-1");
        awaitFollowing("Big Buck Bunny");
        NowPlaying playing = session.state().nowPlaying();
        assertThat(playing.state()).isEqualTo(PlaybackState.PLAYING);
        assertThat(playing.durationSeconds()).isEqualTo(596.5);
        assertThat(session.state().currentApp()).isEqualTo("Default Media Receiver");
    }

    @Test
    void reusesTheReceiverAppWhenItIsAlreadyRunning() {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        start(receiver.port());
        await().until(() -> "Default Media Receiver".equals(session.state().currentApp()));

        session.execute(bunny());

        assertThat(receiver.received(RECEIVER, "LAUNCH")).isEmpty();
        assertThat(receiver.last(MEDIA, "LOAD").orElseThrow().destinationId()).isEqualTo("transport-1");
    }

    @Test
    void aRefusedLaunchFailsWithTheReceiversReason() {
        receiver.refuseLaunch(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
        start(receiver.port());
        awaitStatus();

        assertThatThrownBy(() -> session.execute(bunny()))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("LAUNCH_ERROR: NOT_FOUND");
        assertThat(receiver.received(MEDIA, "LOAD")).isEmpty();
    }

    @Test
    void aFailedLoadFailsWithTheReceiversAnswer() {
        receiver.failNextLoad();
        start(receiver.port());
        awaitStatus();

        assertThatThrownBy(() -> session.execute(bunny()))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("LOAD_FAILED");
    }

    @Test
    void followsMediaStartedByAnotherSender() throws Exception {
        start(receiver.port());
        awaitStatus();

        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        receiver.startMedia("Song", "PLAYING", 12.0);
        receiver.pushReceiverStatus();

        awaitFollowing("Song");
        assertThat(session.state().nowPlaying().positionSeconds()).isGreaterThanOrEqualTo(12.0);
        assertThat(receiver.last(CONNECTION, "CONNECT").orElseThrow().destinationId()).isEqualTo("transport-1");
    }

    @Test
    void aPartialStatusKeepsTheTitleAndIdleClearsNowPlaying() throws Exception {
        start(receiver.port());
        awaitStatus();
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        receiver.startMedia("Song", "PLAYING", 12.0);
        receiver.pushReceiverStatus();
        awaitFollowing("Song");

        receiver.setMediaState("PAUSED", 30.0);
        receiver.pushMediaStatus(false);

        await().until(() -> session.state().nowPlaying().state() == PlaybackState.PAUSED);
        assertThat(session.state().nowPlaying().title()).isEqualTo("Song");
        assertThat(session.state().nowPlaying().positionSeconds()).isEqualTo(30.0);

        receiver.setMediaState("IDLE", 0);
        receiver.pushMediaStatus(false);

        await().until(() -> session.state().nowPlaying() == null);
    }

    @Test
    void pollsThePositionWhilePlaying() throws Exception {
        start(receiver.port());
        awaitStatus();
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        receiver.startMedia("Song", "PLAYING", 12.0);
        receiver.pushReceiverStatus();
        awaitFollowing("Song");
        int before = receiver.received(MEDIA, "GET_STATUS").size();

        await().until(() -> receiver.received(MEDIA, "GET_STATUS").size() >= before + 2);
    }

    @Test
    void stoppingTheAppClearsNowPlaying() {
        start(receiver.port());
        awaitStatus();
        session.execute(bunny());
        awaitFollowing("Big Buck Bunny");

        session.execute(new Action.Stop());

        await().until(() -> session.state().nowPlaying() == null && session.state().currentApp() == null);
    }
```

(`CastIncoming` import from `adapters.cast.protocol`.)

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.CastSessionTest'`
Expected: FAIL — `CastLoad` is "not yet"; no now-playing.

- [ ] **Step 8: Write the final `CastSession`**

Replace `adapters/cast/CastSession.java`:

```java
package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastConnection;
import dev.andre.homecontrol.adapters.cast.protocol.CastDisconnectCause;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.CastPayloads;
import dev.andre.homecontrol.adapters.cast.protocol.CastTimeoutException;
import dev.andre.homecontrol.adapters.cast.protocol.MediaStatus;
import dev.andre.homecontrol.adapters.cast.protocol.ReceiverStatus;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * One Cast receiver's live connection, shaped like the Android TV session: every mutation of
 * connection and state happens on one scheduler thread; commands run on the caller's thread
 * and fail immediately when they cannot be sent (nothing is queued). Cast has no pairing, so
 * there is no UNPAIRED state.
 */
public class CastSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(CastSession.class);

    private final Device device;
    private final CastSettings settings;
    private final CastProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile CastConnection connection;
    /** Incremented per connection attempt; callbacks from older connections are ignored. */
    private volatile long generation;
    private volatile DeviceState state = DeviceState.initial();
    private volatile ReceiverStatus receiver;
    /** Transport of the foreground app whose media channel we follow, or null. */
    private volatile String mediaTransportId;
    private volatile MediaStatus lastMedia;
    private volatile Duration backoff;
    private volatile boolean closed;

    public CastSession(Device device, CastProperties properties, Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = CastSettings.of(device);
        this.properties = properties;
        this.onChange = onChange;
        this.backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cast-session-" + device.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.execute(this::connect);
        long interval = properties.mediaStatusIntervalSeconds();
        scheduler.scheduleWithFixedDelay(this::pollMediaPosition, interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    // ---- commands (caller's thread) ----

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and has no remote keys");
            case Action.OpenAppLink ignored -> throw new UnsupportedActionException(
                    device.name() + " is a Cast receiver and cannot open app links");
            case Action.SetVolume set -> receiverCommand(CastPayloads.setVolumeLevel(set.level() / 100.0), "set the volume");
            case Action.Mute mute -> receiverCommand(CastPayloads.setMuted(mute.muted()), mute.muted() ? "mute" : "unmute");
            case Action.Stop ignored -> stopForegroundApp();
            case Action.CastLoad load -> load(load.receiverAppId(), load.load());
        }
    }

    private void receiverCommand(ObjectNode payload, String what) {
        CastConnection current = requireConnected();
        CastIncoming reply = call(() -> current.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, commandTimeout()), what);
        if (!"RECEIVER_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to " + what + " (" + reply.describeFailure() + ")");
        }
    }

    private void stopForegroundApp() {
        CastConnection current = requireConnected();
        ReceiverStatus status = receiver;
        Optional<ReceiverStatus.ReceiverApp> app = status == null ? Optional.empty() : status.foregroundApp();
        if (app.isEmpty()) {
            return; // nothing is casting, so it is already stopped
        }
        CastIncoming reply = call(() -> current.request(RECEIVER, PLATFORM_RECEIVER_ID,
                CastPayloads.stop(app.get().sessionId()), commandTimeout()), "stop " + app.get().displayName());
        if (!"RECEIVER_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " refused to stop " + app.get().displayName()
                    + " (" + reply.describeFailure() + ")");
        }
    }

    /** Launch the receiver app unless it already runs, connect to its transport, LOAD. */
    private void load(String appId, Map<String, Object> body) {
        CastConnection current = requireConnected();
        ReceiverStatus.ReceiverApp app = Optional.ofNullable(receiver)
                .flatMap(status -> status.app(appId))
                .orElseGet(() -> launch(current, appId));
        call(() -> {
            current.connect(app.transportId());
            return null;
        }, "reach " + app.displayName());
        CastIncoming reply = call(() -> current.request(MEDIA, app.transportId(),
                CastPayloads.load(app.sessionId(), body), loadTimeout()), "load the media");
        if (!"MEDIA_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not play it (" + reply.describeFailure() + ")");
        }
    }

    private ReceiverStatus.ReceiverApp launch(CastConnection current, String appId) {
        int requestId = current.nextRequestId();
        ObjectNode launch = CastPayloads.launch(appId);
        launch.put("requestId", requestId);
        // The reply to LAUNCH can be a RECEIVER_STATUS still showing the previous app; wait for
        // the status that lists ours, or for an error answering our request.
        CastConnection.Waiter outcome = current.expect(message -> RECEIVER.equals(message.namespace())
                && (("RECEIVER_STATUS".equals(message.type())
                        && ReceiverStatus.parse(message.payload().path("status")).app(appId).isPresent())
                    || (message.requestId() == requestId && !"RECEIVER_STATUS".equals(message.type()))));
        CastIncoming reply = call(() -> {
            try {
                current.send(RECEIVER, PLATFORM_RECEIVER_ID, launch);
                return outcome.await(loadTimeout());
            } finally {
                outcome.cancel();
            }
        }, "start receiver app " + appId);
        if (!"RECEIVER_STATUS".equals(reply.type())) {
            throw new ActionFailedException(device.name() + " could not start receiver app " + appId
                    + " (" + reply.describeFailure() + ")");
        }
        return ReceiverStatus.parse(reply.payload().path("status")).app(appId).orElseThrow();
    }

    private CastConnection requireConnected() {
        CastConnection current = connection;
        if (current == null || state.status() != DeviceStatus.CONNECTED) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        return current;
    }

    @FunctionalInterface
    private interface CastCall<T> {
        T run() throws IOException;
    }

    /** Maps protocol failures onto the core vocabulary: no answer → failed; lost channel → offline. */
    private <T> T call(CastCall<T> call, String what) {
        try {
            return call.run();
        } catch (CastTimeoutException e) {
            throw new ActionFailedException(device.name() + " did not answer in time when asked to " + what);
        } catch (IOException e) {
            throw new DeviceOfflineException(device.name() + " dropped the connection while trying to " + what);
        }
    }

    private Duration commandTimeout() {
        return Duration.ofSeconds(properties.commandTimeoutSeconds());
    }

    private Duration loadTimeout() {
        return Duration.ofSeconds(properties.loadTimeoutSeconds());
    }

    // ---- connection lifecycle and state (scheduler thread) ----

    private void connect() {
        if (closed) {
            return;
        }
        long attempt = ++generation;
        update(state.withStatus(DeviceStatus.CONNECTING));
        CastConnection opened = null;
        try {
            opened = CastConnection.open(device.host(), settings.port(),
                    Duration.ofSeconds(properties.heartbeatIntervalSeconds()),
                    Duration.ofSeconds(properties.staleTimeoutSeconds()),
                    new Link(attempt));
            opened.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()); // the reply arrives via Link
            connection = opened;
            backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
            update(state.withStatus(DeviceStatus.CONNECTED));
        } catch (IOException e) {
            if (opened != null) {
                opened.close();
            }
            generation++; // anything the failed attempt still reports is stale
            log.debug("Could not reach Cast receiver {} at {}:{}: {}", device.id(), device.host(), settings.port(), e.getMessage());
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        }
    }

    private void handle(CastIncoming message) {
        if (RECEIVER.equals(message.namespace()) && "RECEIVER_STATUS".equals(message.type())) {
            onReceiverStatus(ReceiverStatus.parse(message.payload().path("status")));
        } else if (MEDIA.equals(message.namespace()) && "MEDIA_STATUS".equals(message.type())
                && message.sourceId().equals(mediaTransportId)) {
            onMediaStatus(MediaStatus.parse(message.payload().path("status")));
        } else if (CONNECTION.equals(message.namespace()) && "CLOSE".equals(message.type())
                && message.sourceId().equals(mediaTransportId)) {
            mediaTransportId = null;
            lastMedia = null;
            update(state.withNowPlaying(null));
        }
    }

    private void onReceiverStatus(ReceiverStatus status) {
        receiver = status;
        Optional<ReceiverStatus.ReceiverApp> foreground = status.foregroundApp();
        update(state.withPower(!status.standBy())
                .withCurrentApp(foreground.map(ReceiverStatus.ReceiverApp::displayName).orElse(null))
                .withVolume(status.volumePercent(), 100, status.muted()));
        followMedia(foreground.filter(app -> app.speaks(MEDIA)).map(ReceiverStatus.ReceiverApp::transportId).orElse(null));
    }

    /** Subscribes to the media channel of whatever app is in front — including casts started from a phone. */
    private void followMedia(String transportId) {
        if (Objects.equals(transportId, mediaTransportId)) {
            return;
        }
        mediaTransportId = transportId;
        lastMedia = null;
        if (transportId == null) {
            update(state.withNowPlaying(null));
            return;
        }
        CastConnection current = connection;
        if (current == null) {
            return;
        }
        try {
            current.connect(transportId);
            current.send(MEDIA, transportId, CastPayloads.getStatus());
        } catch (IOException e) {
            log.debug("Could not follow media on {}: {}", device.id(), e.getMessage()); // the reader reports the drop
        }
    }

    private void onMediaStatus(List<MediaStatus> statuses) {
        if (statuses.isEmpty()) {
            lastMedia = null;
            update(state.withNowPlaying(null));
            return;
        }
        MediaStatus latest = statuses.getFirst().fillFrom(lastMedia);
        lastMedia = latest;
        PlaybackState playback = latest.playbackState();
        update(state.withNowPlaying(playback == PlaybackState.IDLE ? null
                : new NowPlaying(latest.displayTitle(), playback, latest.currentTime(), latest.duration())));
    }

    /** Receivers only push on changes; ask while playing so the position moves. */
    private void pollMediaPosition() {
        try {
            CastConnection current = connection;
            String transport = mediaTransportId;
            NowPlaying playing = state.nowPlaying();
            if (!closed && current != null && transport != null && playing != null
                    && playing.state() == PlaybackState.PLAYING) {
                current.send(MEDIA, transport, CastPayloads.getStatus());
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Media status poll failed for {}: {}", device.id(), e.getMessage());
        }
    }

    private void handleDisconnect(CastDisconnectCause cause) {
        connection = null;
        receiver = null;
        mediaTransportId = null;
        lastMedia = null;
        log.info("Lost the Cast connection to {} ({}); reconnecting", device.id(), cause);
        update(state.withStatus(DeviceStatus.DISCONNECTED).withNowPlaying(null));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        try {
            scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Closing.
        }
    }

    private void runOnScheduler(long attempt, Runnable task) {
        if (closed) {
            return;
        }
        try {
            scheduler.execute(() -> {
                if (!closed && attempt == generation) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // close() shut the scheduler down in between.
        }
    }

    private void update(DeviceState updated) {
        state = updated;
        try {
            onChange.accept(updated);
        } catch (Throwable t) {
            log.warn("A device state listener failed for {}", device.id(), t);
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        CastConnection current = connection;
        if (current != null) {
            current.close();
        }
    }

    private final class Link implements CastConnection.Listener {

        private final long attempt;

        Link(long attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onMessage(CastIncoming message) {
            runOnScheduler(attempt, () -> handle(message));
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            runOnScheduler(attempt, () -> handleDisconnect(cause));
        }
    }
}
```

- [ ] **Step 9: Run the Cast tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*'`
Expected: PASS (Task 3 and 4 session tests included).

- [ ] **Step 10: Show now playing in the dashboard**

Add to `DashboardPageTest`:

```java
    @Test
    void theChipShowsWhatIsPlaying() throws Exception {
        Device kitchen = new Device("cast-10-0-0-9", "Kitchen", DeviceKind.CAST, "10.0.0.9",
                Map.of("cast", Map.of()), Instant.now());
        given(devices.devices()).willReturn(List.of(kitchen));
        given(devices.defaultDevice()).willReturn(Optional.of(kitchen));
        given(devices.device("cast-10-0-0-9")).willReturn(Optional.of(kitchen));
        given(devices.states()).willReturn(Map.of("cast-10-0-0-9", new DeviceState(DeviceStatus.CONNECTED, true,
                "Default Media Receiver", 30, 100, false, Instant.now(),
                new NowPlaying("Big Buck Bunny", PlaybackState.PLAYING, 12.5, 596.5))));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("Big Buck Bunny")))
                .andExpect(content().string(not(containsString(">Default Media Receiver<"))));
    }
```

In `StaticAssetsTest` add `.andExpect(content().string(containsString("nowPlaying")))` to the `/js/state-view.js` request.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`
Expected: FAIL — the chip shows the current app; the module does not mention `nowPlaying`.

In `dashboard.html` change the chip's app label to one SpEL expression (only the chosen branch is evaluated):

```html
            <span class="app" th:id="'app-' + ${device.id()}"
                  th:text="${states[device.id()].nowPlaying() != null ? states[device.id()].nowPlaying().title() : (states[device.id()].currentApp() ?: 'Nothing playing')}">Nothing playing</span>
```

In `static/js/state-view.js` replace the app label line with `if (app) app.textContent = describePlaying(state);` and add:

```js
// Media reported by the device (Cast) wins over the foreground app name (Android TV).
export function describePlaying(state) {
    const playing = state.nowPlaying;
    if (!playing) return state.currentApp || "Nothing playing";
    const position = formatTime(playing.positionSeconds)
        + (playing.durationSeconds ? ` / ${formatTime(playing.durationSeconds)}` : "");
    const paused = playing.state === "PAUSED" ? " (paused)" : "";
    return `${playing.title}${paused} · ${position}`;
}

function formatTime(seconds) {
    const whole = Math.max(0, Math.floor(seconds || 0));
    return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, "0")}`;
}
```

- [ ] **Step 11: Run the web tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat: play direct streams through the Default Media Receiver with now-playing state"
```

---

### Task 6: B6 · Planner routes for Cast

**Files:**
- Create: `core/playback/CastLoadStrategy.java`, `core/playback/CastStreamStrategy.java`
- Modify: `core/playback/Route.java`, `core/playback/PlaybackPlanner.java`, `core/playback/AppLinks.java`, `playback/PlaybackService.java`, `HomeControlConfiguration.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`
- Test: `core/playback/PlaybackPlannerTest.java`, `core/playback/AppLinksTest.java`, `playback/PlaybackServiceTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`

**Interfaces:**
- Consumes: `PlayableRef.CastLoad(String receiverAppId, Map<String,Object> payload)`, `PlayableRef.StreamUrl`, `RouteStrategy`, `PlaybackPlanner`, `Action.CastLoad`, `CastLoads` (Task 5).
- Produces:
  - `Route.Cast(String receiverAppId, Map<String,Object> load)` with `Action action()` (→ `Action.CastLoad`) and `describe()`: `"Cast with the Default Media Receiver"` (`CC1AD845`), `"Cast with the Jellyfin receiver"` (`F007D354`), else `"Cast with receiver app <id>"`.
  - `CastLoadStrategy` (CAST_RECEIVER + first `CastLoad` → `Route.Cast` with its payload) and `CastStreamStrategy` (CAST_RECEIVER + first `StreamUrl` → `Route.Cast(CC1AD845, CastLoads.defaultMediaReceiver(stream, item.title()))`).
  - Planner bean order: `AppLinkStrategy`, `CastLoadStrategy`, `CastStreamStrategy` — spec §5.3 rungs 2 and 3; sub-project I appends its media-renderer strategy after these, C inserts the Jellyfin session strategy before them.
  - Unroutable explanations: CastLoad → "this device is not a Cast receiver"; StreamUrl → "this device cannot play a direct stream".
  - `AppLinks.fromUrl` adds `StreamUrl(uri, mime)` after the `AppLink` when the path ends in `mp4, m4v, webm, mkv, m3u8, mpd, mp3, m4a, aac, flac, ogg, wav` (case-insensitive); the item title is then the decoded file name and the kind `TRACK` for `audio/*`.
  - Dashboard `canOpenLinks` is true for `APP_LINK` or `CAST_RECEIVER`.

- [ ] **Step 1: Write the failing planner tests**

In `core/playback/PlaybackPlannerTest.java` change the planner field to

```java
    private final PlaybackPlanner planner = new PlaybackPlanner(
            List.of(new AppLinkStrategy(), new CastLoadStrategy(), new CastStreamStrategy()));
```

replace A's `explainsThatOtherReferenceKindsHaveNoRouteYet` with

```java
    @Test
    void explainsThatJellyfinItemsHaveNoRouteYet() {
        Route route = planner.plan(item(new PlayableRef.JellyfinItem("srv", "item", 0)), EnumSet.allOf(Capability.class));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable ->
                assertThat(unroutable.reason()).contains("Jellyfin").contains("not supported yet"));
    }
```

and add:

```java
    private static final PlayableRef.CastLoad JELLYFIN_LOAD =
            new PlayableRef.CastLoad("F007D354", Map.of("media", Map.of("contentId", "item-1")));
    private static final PlayableRef.StreamUrl STREAM =
            new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4");
    private static final PlayableRef.AppLink LINK =
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube");

    @Test
    void castsACastLoadToACastReceiver() {
        Route route = planner.plan(item(JELLYFIN_LOAD), EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        assertThat(route).isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(route.describe()).isEqualTo("Cast with the Jellyfin receiver");
        assertThat(((Route.Cast) route).action()).isEqualTo(new dev.andre.homecontrol.core.Action.CastLoad("F007D354", JELLYFIN_LOAD.payload()));
    }

    @Test
    void castsAStreamThroughTheDefaultMediaReceiverWithTheItemTitle() {
        Route route = planner.plan(item(STREAM), EnumSet.of(Capability.CAST_RECEIVER));

        assertThat(route).isEqualTo(new Route.Cast("CC1AD845", CastLoads.defaultMediaReceiver(STREAM, "Title")));
        assertThat(route.describe()).isEqualTo("Cast with the Default Media Receiver");
        assertThat(new Route.Cast("ABCD1234", Map.of()).describe()).isEqualTo("Cast with receiver app ABCD1234");
    }

    @Test
    void followsSpecOrderAppLinkThenCastLoadThenStream() {
        ContentItem everything = item(STREAM, JELLYFIN_LOAD, LINK);

        assertThat(planner.plan(everything, EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)))
                .isEqualTo(new Route.OpenAppLink(LINK.uri(), "youtube"));
        assertThat(planner.plan(everything, EnumSet.of(Capability.CAST_RECEIVER)))
                .isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
        assertThat(planner.plan(item(STREAM, LINK), EnumSet.of(Capability.CAST_RECEIVER)))
                .isInstanceOfSatisfying(Route.Cast.class, cast -> assertThat(cast.receiverAppId()).isEqualTo("CC1AD845"));
    }

    @Test
    void explainsWhyCastAndStreamsCannotReachADeviceWithoutCast() {
        Route route = planner.plan(item(JELLYFIN_LOAD, STREAM), EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable -> assertThat(unroutable.reason())
                .contains("this device is not a Cast receiver")
                .contains("this device cannot play a direct stream"));
    }

    @Test
    void theApplicationsPlannerUsesTheSpecOrder() {
        PlaybackPlanner configured = new dev.andre.homecontrol.HomeControlConfiguration().playbackPlanner();
        ContentItem everything = item(STREAM, JELLYFIN_LOAD, LINK);

        assertThat(configured.plan(everything, EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER)))
                .isInstanceOf(Route.OpenAppLink.class);
        assertThat(configured.plan(everything, EnumSet.of(Capability.CAST_RECEIVER)))
                .isEqualTo(new Route.Cast("F007D354", JELLYFIN_LOAD.payload()));
    }
```

(If `HomeControlConfiguration.playbackPlanner` takes parameters in the real code, pass whatever it needs; the assertion is about strategy order.)

Add to `core/playback/AppLinksTest.java`:

```java
    @Test
    void aDirectMediaLinkAlsoCarriesAStreamUrlAndItsFileNameAsTitle() {
        String url = "https://media.example.org/films/Big%20Buck%20Bunny.MP4?token=1";

        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.playables()).containsExactly(
                new PlayableRef.AppLink(URI.create(url), "web"),
                new PlayableRef.StreamUrl(URI.create(url), "video/mp4"));
        assertThat(item.title()).isEqualTo("Big Buck Bunny.MP4");
        assertThat(item.kind()).isEqualTo(ContentKind.VIDEO);
    }

    @Test
    void anAudioLinkIsATrack() {
        ContentItem item = AppLinks.fromUrl("http://nas.local/music/song.flac");

        assertThat(item.playables()).contains(new PlayableRef.StreamUrl(URI.create("http://nas.local/music/song.flac"), "audio/flac"));
        assertThat(item.kind()).isEqualTo(ContentKind.TRACK);
    }

    @Test
    void aPageLinkHasNoStream() {
        assertThat(AppLinks.fromUrl("https://www.youtube.com/watch?v=abc").playables()).singleElement()
                .isInstanceOf(PlayableRef.AppLink.class);
    }
```

Add to `playback/PlaybackServiceTest.java`:

```java
    @Test
    void executesACastRoute() {
        PlaybackService castService = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastLoadStrategy(), new CastStreamStrategy())));
        Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now());
        given(devices.device("kitchen")).willReturn(Optional.of(kitchen));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        Route route = castService.play(AppLinks.fromUrl("http://nas.local/films/bunny.mp4"), "kitchen");

        assertThat(route).isInstanceOfSatisfying(Route.Cast.class, cast -> {
            assertThat(cast.receiverAppId()).isEqualTo("CC1AD845");
            verify(devices).execute("kitchen", cast.action());
        });
    }
```

Add to `web/DeviceControllerTest.java`:

```java
    @Test
    void describesACastRoute() throws Exception {
        given(playback.play(any(), eq("shield"))).willReturn(new Route.Cast("CC1AD845", Map.of()));

        mockMvc.perform(post("/devices/shield/play").param("uri", "http://nas.local/films/bunny.mp4"))
                .andExpect(status().isOk())
                .andExpect(content().string("Cast with the Default Media Receiver"));
    }
```

Add to `web/DashboardPageTest.java`, copying the stubs of `aCastOnlyDeviceGetsCastControlsInsteadOfTheRemote` (Task 4):

```java
    @Test
    void aCastOnlyDeviceOffersTheLinkForm() throws Exception {
        // same stubs as aCastOnlyDeviceGetsCastControlsInsteadOfTheRemote
        mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("/devices/cast-10-0-0-9/play")))
                .andExpect(content().string(containsString("Direct media links")));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `Route.Cast`, `CastLoadStrategy`, `CastStreamStrategy` do not exist.

- [ ] **Step 3: Implement the route, strategies and explanations**

Add to `core/playback/Route.java` (imports `java.util.Map`, `java.util.Collections`, `java.util.LinkedHashMap`):

```java
    /** Run a Cast receiver app and send it a LOAD (spec §5.3 rung 3). */
    record Cast(String receiverAppId, Map<String, Object> load) implements Route {

        private static final Map<String, String> RECEIVER_NAMES = Map.of(
                "CC1AD845", "the Default Media Receiver",
                "F007D354", "the Jellyfin receiver");

        public Cast {
            load = load == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(load));
        }

        public Action action() {
            return new Action.CastLoad(receiverAppId, load);
        }

        @Override
        public String describe() {
            String name = RECEIVER_NAMES.get(receiverAppId);
            return name == null ? "Cast with receiver app " + receiverAppId : "Cast with " + name;
        }
    }
```

`core/playback/CastLoadStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 3 of spec §5.3: a Cast receiver and a ready-made CastLoad (Jellyfin receiver, DMR, …). */
public class CastLoadStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.CastLoad.class::isInstance)
                .map(PlayableRef.CastLoad.class::cast)
                .findFirst()
                .map(load -> new Route.Cast(load.receiverAppId(), load.payload()));
    }
}
```

`core/playback/CastStreamStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/**
 * Still rung 3: a direct stream on a Cast receiver goes through the Default Media Receiver —
 * after a source-built CastLoad, before rung 4 (DLNA/Sonos media renderers, sub-project I).
 */
public class CastStreamStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.CAST_RECEIVER)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.StreamUrl.class::isInstance)
                .map(PlayableRef.StreamUrl.class::cast)
                .findFirst()
                .map(stream -> new Route.Cast(CastLoads.DEFAULT_MEDIA_RECEIVER,
                        CastLoads.defaultMediaReceiver(stream, item.title())));
    }
}
```

`PlaybackPlanner.explain`: replace the `CastLoad` and `StreamUrl` cases:

```java
                case PlayableRef.CastLoad ignored -> reasons.add(capabilities.contains(Capability.CAST_RECEIVER)
                        ? "the cast was not accepted" : "this device is not a Cast receiver");
                case PlayableRef.StreamUrl ignored -> reasons.add(capabilities.contains(Capability.CAST_RECEIVER)
                        ? "the stream was not accepted" : "this device cannot play a direct stream");
```

`PlaybackService.play`: add `case Route.Cast cast -> devices.execute(deviceId, cast.action());` to the switch.

`HomeControlConfiguration.playbackPlanner`:

```java
    @Bean
    public PlaybackPlanner playbackPlanner() {
        // Strategy order is the preference order of spec §5.3: app link, then Cast (a
        // source-built CastLoad before a bare stream on the Default Media Receiver).
        // Sub-project C puts the Jellyfin session strategy first; I appends media renderers.
        return new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastLoadStrategy(), new CastStreamStrategy()));
    }
```

- [ ] **Step 4: Implement media links**

In `core/playback/AppLinks.java` replace `fromUrl` and add the helpers (imports `java.net.URLDecoder`, `java.nio.charset.StandardCharsets`, `java.util.ArrayList`, `java.util.Map`, `java.util.Optional`):

```java
    /** Extensions whose URL a Cast Default Media Receiver can fetch and play directly. */
    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"), Map.entry("m4v", "video/mp4"), Map.entry("webm", "video/webm"),
            Map.entry("mkv", "video/x-matroska"), Map.entry("m3u8", "application/x-mpegURL"),
            Map.entry("mpd", "application/dash+xml"), Map.entry("mp3", "audio/mpeg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"), Map.entry("flac", "audio/flac"), Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"));

    public static ContentItem fromUrl(String url) {
        URI uri = parse(url);
        String service = serviceOf(uri.getHost().toLowerCase(Locale.ROOT), uri.getPath());
        List<PlayableRef> playables = new ArrayList<>();
        playables.add(new PlayableRef.AppLink(uri, service));
        Optional<String> mediaType = mediaTypeOf(uri.getRawPath());
        mediaType.ifPresent(type -> playables.add(new PlayableRef.StreamUrl(uri, type)));
        String title = mediaType.isPresent() ? fileName(uri.getRawPath()) : uri.getHost();
        ContentKind kind = mediaType.filter(type -> type.startsWith("audio/")).isPresent() ? ContentKind.TRACK : ContentKind.VIDEO;
        return new ContentItem("link:" + uri, "manual", kind, title, null, null, playables);
    }

    static Optional<String> mediaTypeOf(String rawPath) {
        if (rawPath == null) {
            return Optional.empty();
        }
        String name = fileName(rawPath);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? Optional.empty()
                : Optional.ofNullable(MEDIA_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    private static String fileName(String rawPath) {
        return URLDecoder.decode(rawPath.substring(rawPath.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
    }
```

(`URLDecoder` turns `+` into a space; acceptable for a display title.)

`DashboardController`: `canOpenLinks` = `capabilities.contains(Capability.APP_LINK) || capabilities.contains(Capability.CAST_RECEIVER)`. In `dashboard.html` add to the open-link section, after the existing hint:

```html
        <p class="hint">Direct media links (.mp4, .mkv, .webm, .m3u8, .mp3, .flac …) play on Cast devices through the Default Media Receiver; the receiver must be able to reach the URL.</p>
```

- [ ] **Step 5: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.playback.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: route cast loads and direct streams to Cast receivers"
```

---

### Task 7: B7 · Fake Cast receiver and acceptance

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/web/CastEndToEndTest.java`, `docs/superpowers/reviews/2026-09-16-google-cast-adapter-acceptance.md`
- Modify: `README.md`
- Uses unchanged: `adapters/cast/protocol/FakeCastReceiver.java` (Task 3), `adapters/androidtv/protocol/FakeRemoteServer.java` (A)

**Interfaces:**
- Consumes: the whole application context; `DeviceManager.adopt/state/forget`; `CertificateStore`; `AndroidTvSettings.device`; HTTP endpoints `/devices/{id}/play|volume|mute|stop|key/{key}` and `/events`.
- Produces: end-to-end proof over real sockets that the fake speaks enough CASTV2 for connect, heartbeat, receiver status and LOAD through the real application; the manual checklist for the Shield's Cast and one Chromecast.

- [ ] **Step 1: Write the end-to-end tests**

`src/test/java/dev/andre/homecontrol/web/CastEndToEndTest.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Cast adapter through the real application: fake receiver ↔ CastSession ↔ DeviceManager ↔
 * planner ↔ HTTP and SSE. The fake is the in-process CASTV2 receiver from the adapter tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CastEndToEndTest {

    @DynamicPropertySource
    static void fastCastAndAnIsolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("cast-e2e").toString();
        registry.add("shield.data-dir", () -> dataDir);
        registry.add("home-control.cast.heartbeat-interval-seconds", () -> "1");
        registry.add("home-control.cast.stale-timeout-seconds", () -> "3");
        registry.add("home-control.cast.reconnect-max-delay-seconds", () -> "2");
        registry.add("home-control.cast.command-timeout-seconds", () -> "3");
        registry.add("home-control.cast.load-timeout-seconds", () -> "5");
        registry.add("home-control.cast.media-status-interval-seconds", () -> "1");
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    CertificateStore certificates;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Device castDevice(String id, int castPort) {
        return new Device(id, "Kitchen", DeviceKind.CAST, "127.0.0.1",
                Map.of("cast", Map.of("port", String.valueOf(castPort))), Instant.now());
    }

    private void awaitReceiverStatus(String id) {
        await().until(() -> devices.state(id).connected() && devices.state(id).volumeMax() == 100);
    }

    @Test
    void aCastOnlyDevicePlaysAPastedMediaLinkThroughTheDefaultMediaReceiver() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            devices.adopt(castDevice("cast-e2e-play", receiver.port()));
            try {
                awaitReceiverStatus("cast-e2e-play");

                HttpResponse<String> response = post("/devices/cast-e2e-play/play",
                        "uri=" + URLEncoder.encode("http://nas.local/films/bunny.mp4", StandardCharsets.UTF_8));

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("Cast with the Default Media Receiver");
                assertThat(receiver.last(RECEIVER, "LAUNCH")).isPresent();
                CastIncoming load = receiver.last(MEDIA, "LOAD").orElseThrow();
                assertThat(load.payload().path("media").path("contentId").asString("")).isEqualTo("http://nas.local/films/bunny.mp4");
                assertThat(load.payload().path("media").path("metadata").path("title").asString("")).isEqualTo("bunny.mp4");
                await().until(() -> devices.state("cast-e2e-play").nowPlaying() != null);
                assertThat(devices.state("cast-e2e-play").nowPlaying().title()).isEqualTo("bunny.mp4");
                assertThat(devices.state("cast-e2e-play").nowPlaying().state()).isEqualTo(PlaybackState.PLAYING);
            } finally {
                devices.forget("cast-e2e-play");
            }
        }
    }

    @Test
    void volumeMuteAndStopReachTheReceiver() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
            devices.adopt(castDevice("cast-e2e-volume", receiver.port()));
            try {
                await().until(() -> "Default Media Receiver".equals(devices.state("cast-e2e-volume").currentApp()));

                assertThat(post("/devices/cast-e2e-volume/volume", "level=35").statusCode()).isEqualTo(204);
                assertThat(receiver.volumeLevel()).isEqualTo(0.35);
                assertThat(post("/devices/cast-e2e-volume/mute", "muted=true").statusCode()).isEqualTo(204);
                assertThat(receiver.muted()).isTrue();
                assertThat(post("/devices/cast-e2e-volume/stop", "").statusCode()).isEqualTo(204);

                assertThat(receiver.runningAppId()).isEqualTo(FakeCastReceiver.BACKDROP_APP_ID);
                await().until(() -> devices.state("cast-e2e-volume").currentApp() == null);
                assertThat(post("/devices/cast-e2e-volume/key/HOME", "").statusCode()).isEqualTo(422);
            } finally {
                devices.forget("cast-e2e-volume");
            }
        }
    }

    @Test
    void aMergedShieldSendsKeysOverRemoteV2AndVolumeOverCast() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer(); FakeCastReceiver receiver = new FakeCastReceiver()) {
            certificates.loadOrCreate("shield-e2e");
            devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", remote.port(), null, Instant.now())
                    .withAdapter("cast", Map.of("port", String.valueOf(receiver.port()))));
            try {
                await().until(() -> devices.state("shield-e2e").connected()
                        && receiver.virtualConnections().contains("receiver-0"));

                assertThat(post("/devices/shield-e2e/key/HOME", "").statusCode()).isEqualTo(204);
                assertThat(remote.nextKeyPress()).isEqualTo(RemoteKey.HOME.code());
                assertThat(post("/devices/shield-e2e/volume", "level=40").statusCode()).isEqualTo(204);
                assertThat(receiver.volumeLevel()).isEqualTo(0.4);

                receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
                receiver.startMedia("Song", "PLAYING", 3.0);
                receiver.pushReceiverStatus();

                await().until(() -> devices.state("shield-e2e").nowPlaying() != null);
                assertThat(devices.state("shield-e2e").nowPlaying().title()).isEqualTo("Song");
                assertThat(devices.state("shield-e2e").status()).isEqualTo(DeviceStatus.CONNECTED);
            } finally {
                devices.forget("shield-e2e");
            }
        }
    }

    @Test
    void theEventStreamCarriesNowPlayingWithTheDeviceId() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            devices.adopt(castDevice("cast-e2e-sse", receiver.port()));
            try {
                awaitReceiverStatus("cast-e2e-sse");
                List<String> lines = new CopyOnWriteArrayList<>();
                HttpResponse<Stream<String>> response = http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                                .header("Accept", "text/event-stream")
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofLines());
                assertThat(response.statusCode()).isEqualTo(200);
                Thread.ofVirtual().name("cast-sse-reader").start(() -> response.body().forEach(lines::add));
                await().until(() -> lines.stream().anyMatch(line -> line.contains("\"deviceId\":\"cast-e2e-sse\"")));

                receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
                receiver.startMedia("Song", "PLAYING", 3.0);
                receiver.pushReceiverStatus();

                await().until(() -> lines.stream().anyMatch(line -> line.contains("\"deviceId\":\"cast-e2e-sse\"")
                        && line.contains("\"title\":\"Song\"")));
            } finally {
                devices.forget("cast-e2e-sse");
            }
        }
    }

    @Test
    void aReceiverThatGoesSilentShowsDisconnectedAndRecovers() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            devices.adopt(castDevice("cast-e2e-silent", receiver.port()));
            try {
                awaitReceiverStatus("cast-e2e-silent");

                receiver.goSilent();
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> devices.state("cast-e2e-silent").status() == DeviceStatus.DISCONNECTED);

                receiver.setVolume(0.7, false);
                receiver.resume();
                await().atMost(Duration.ofSeconds(15)).until(() -> devices.state("cast-e2e-silent").connected()
                        && devices.state("cast-e2e-silent").volumeLevel() == 70);
            } finally {
                devices.forget("cast-e2e-silent");
            }
        }
    }
}
```

(Import paths for `CertificateStore`, `FakeRemoteServer`, `AndroidTvSettings` follow plan A's Task 4 move; use the real packages if they differ. `SET_VOLUME` is answered before the HTTP call returns, so `receiver.volumeLevel()` is already updated when the 204 arrives.)

- [ ] **Step 2: Run them**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.CastEndToEndTest'`
Expected: PASS. If a test fails, the defect is in Tasks 2–6 code, not in the test's expectations: fix the code (use superpowers:systematic-debugging).

- [ ] **Step 3: Write the manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-google-cast-adapter-acceptance.md`:

```markdown
# Google Cast adapter (sub-project B) — manual acceptance

**Release:** 0.7 · **Epic:** #6 · **Plan:** `docs/superpowers/plans/2026-09-16-google-cast-adapter.md`

Automated coverage: `CastEndToEndTest` (real application against the in-process fake receiver),
`CastSessionTest`, `CastConnectionTest`, `DeviceManagerMergeTest`, `DeviceManagerExecuteTest`,
planner and web tests. The items below need real hardware and have not been run by an agent.

Setup for every item: the container runs with `network_mode: host`; test stream
`https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`.

## NVIDIA Shield (built-in Cast), already paired over Android TV

| # | Check | Result |
|---|---|---|
| 1 | Start the new image against the existing `data/`: the Shield keeps its pairing; within a minute the setup page does not list the Shield's receiver under "Ready to add" and `devices.json` shows a `cast` entry after `androidtv` for the Shield | Pending — requires real hardware |
| 2 | The Shield appears once in the device strip; D-pad keys still work | Pending — requires real hardware |
| 3 | Cast YouTube from a phone to the Shield: the chip shows the video title from Cast media status and the position advances | Pending — requires real hardware |
| 4 | The drawer's volume slider changes the Shield's volume; Mute and Unmute work | Pending — requires real hardware |
| 5 | "Stop casting" ends the phone's cast; the chip returns to the Android TV app name | Pending — requires real hardware |
| 6 | Pasting the test stream on the Shield answers "Open commondatastorage.googleapis.com on the device" (app link wins over Cast by spec order) and the Shield reacts or shows nothing, as documented | Pending — requires real hardware |
| 7 | Setup → "Split cast into its own device" creates a "Shield (cast)" chip; the Shield chip keeps its keys; restarting the container does not merge them back; "Merge devices" (cast into Shield) joins them again | Pending — requires real hardware |
| 8 | Reboot the Shield: the chip goes DISCONNECTED, then CONNECTED again without user action | Pending — requires real hardware |

## One Chromecast (or Chromecast with Google TV / Cast speaker)

| # | Check | Result |
|---|---|---|
| 9 | Setup lists the Chromecast under "Ready to add" with its friendly name; Add creates a chip | Pending — requires real hardware |
| 10 | Pasting the test stream shows "Cast with the Default Media Receiver" and the video plays on the TV | Pending — requires real hardware |
| 11 | The chip shows "BigBuckBunny.mp4 · m:ss / 9:56" and the position advances roughly every 5 s | Pending — requires real hardware |
| 12 | Pause from the TV's own remote or a phone: the chip shows "(paused)" | Pending — requires real hardware |
| 13 | A Cast group containing the Chromecast is not offered on the setup page | Pending — requires real hardware |
| 14 | Unplug the Chromecast for a minute and plug it back in: the chip recovers on its own | Pending — requires real hardware |
| 15 | With `HOME_CONTROL_CAST_ENABLED=false` the Chromecast chip shows no Cast controls, nothing is offered under "Ready to add", and the Shield still works over Android TV | Pending — requires real hardware |

## Findings

None recorded yet.
```

- [ ] **Step 4: Document Cast in the README**

Add after the "Opening links on a device" section:

```markdown
## Cast devices

Chromecasts, TVs and speakers with Google Cast — including the Shield's built-in Cast — need
no pairing.

- Open **Setup**. Receivers found on the network appear under **Ready to add**; press **Add**.
- A receiver at the same address, or with the same name, as a paired Android TV is added to
  that TV automatically, so the Shield shows up once with remote keys *and* Cast volume.
- If that guess is wrong, use **Split** on the device, or **Merge devices** to join two
  entries. An Android TV pairing always stays with its own entry: merge the Cast entry into
  the TV, not the other way round.
- A Cast device's drawer has a volume slider, **Mute**, **Unmute** and **Stop casting**.
- Paste a direct media link (`.mp4`, `.mkv`, `.webm`, `.m3u8`, `.mpd`, `.mp3`, `.m4a`, `.aac`,
  `.flac`, `.ogg`, `.wav`) into **Open a link on this device** to play it with Google's Default
  Media Receiver. The receiver downloads the URL itself, so it must be reachable from the TV or
  speaker. On a device that can also open app links (an Android TV) the app link is tried
  first; split off its Cast entry if you want to cast such links instead.
- The device strip shows what a Cast device is playing, including casts started from a phone.
- Cast discovery needs `network_mode: host`, like Android TV discovery. Cast groups are not
  shown yet.
- Switch the whole Cast module off with `HOME_CONTROL_CAST_ENABLED=false`. Timings live under
  `home-control.cast.*` in `application.yaml`.
```

- [ ] **Step 5: Full verification**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL, every test green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: end-to-end Cast tests and the manual acceptance checklist"
```

---

## Final Automated Verification

```bash
.superpowers/gradle.sh clean build
grep -rn "adapters\.cast\.protocol" src/main/java/dev/andre/homecontrol/core src/main/java/dev/andre/homecontrol/device src/main/java/dev/andre/homecontrol/playback src/main/java/dev/andre/homecontrol/web || echo "layering clean"
grep -rn "adapters\.androidtv" src/main/java/dev/andre/homecontrol/adapters/cast || echo "cast independent of androidtv"
```

Expected: BUILD SUCCESSFUL; both greps print their "clean" message.

## Out of scope for this plan

Cast groups and multizone (sub-project I), the Jellyfin receiver `F007D354` payload (C5 builds a `PlayableRef.CastLoad` that `CastLoadStrategy` already routes), YouTube over the Lounge API (E), media seek/queue controls, Cast device authentication, re-resolving a receiver whose DHCP address changed.
