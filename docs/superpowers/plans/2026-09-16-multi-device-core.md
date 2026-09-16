# Multi-Device Core (Sub-project A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-Shield remote into a multi-device core: a capability-based device model, a pluggable adapter contract with Android TV as its first adapter, a registry that migrates the existing `devices.json` without re-pairing, one live session per registered device, multi-device SSE, a dashboard shell with a device strip and per-device remote drawer, and a playback planner whose first route opens a pasted link in the matching app on an Android TV.

**Architecture:** Keep the Spring Boot / Thymeleaf / htmx / SSE stack. Move everything Remote-v2-specific under `adapters/androidtv` behind a `DeviceAdapter` + `DeviceHandle` contract; introduce `core` (devices, capabilities, actions) and `core/playback` (playable references, routes, planner). `DeviceManager` replaces `DeviceSessionManager` and owns one handle per registered device and adapter. The web layer addresses devices by id everywhere. Registry migration turns the v1 record shape into the v2 shape on first read and rewrites the file.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle 9.7.1, Thymeleaf, htmx 2, vanilla ES modules, protobuf 4.36.0, jmDNS, JUnit 5, AssertJ, Mockito, Awaitility.

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — sections 5 (domain model), 6.1–6.2 (device strip, live state), 7 (architecture), 8 (storage, migration). Sub-project A row of §10. The program roadmap is `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md`.

## Global Constraints

- LAN appliance; one Docker container; host networking for discovery; plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: pairing through Android TV Remote v2 remains the only Shield-side setup. No ADB, no developer mode.
- Commands are ephemeral: a command that cannot be sent immediately is rejected with a reason; nothing is queued or replayed.
- Only adapters speak device protocols. `core`, `playback` and `web` must not import `adapters.androidtv.protocol`.
- The server cannot enumerate installed apps. An app-link route is optimistic: the device opens whatever app claims the URI (spec §5.3).
- Persistent state stays in `/data` as JSON written atomically. The existing `devices.json` and `keystore.p12` must migrate without re-pairing; the certificate alias stays equal to the device id.
- The configuration prefix `shield.*` and the environment variable `SHIELD_KEYSTORE_PASSWORD` keep working. The CasaOS app id `dev.andre.shield-remote` does not change.
- A missing credential is `UNPAIRED`; a wrong keystore password or unreadable store is a named `StorageException` at startup, never an empty store.
- Forgetting a device removes only that device's registry record and its adapter credentials.
- Every task ends with `./gradlew build` green. Java 25 must be installed locally (CI uses `actions/setup-java` with 25; the Gradle toolchain does not auto-provision).
- Commits follow conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`).

---

## File Structure Map

Root package after Task 1: `dev.andre.homecontrol` (sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`). Paths below are relative to those roots unless they start with `src/`.

### Files to create

- `core/Capability.java` — what a device can do; the only thing the planner matches on.
- `core/DeviceKind.java` — coarse device family, for icons and setup copy.
- `core/Action.java` — sealed commands (`PressKey`, `OpenAppLink`), each naming the capability it requires.
- `core/DeviceAdapter.java` — the adapter contract: id, capabilities, connect, forget, discovered.
- `core/DeviceHandle.java` — one live connection: state, execute, close.
- `core/UnsupportedActionException.java` — the device has no adapter with the action's capability.
- `core/playback/PlayableRef.java` — sealed: `AppLink`, `CastLoad`, `JellyfinItem`, `StreamUrl`.
- `core/playback/ContentKind.java`, `core/playback/ContentItem.java` — what a source produces.
- `core/playback/Route.java` — sealed: `OpenAppLink`, `Unroutable`.
- `core/playback/RouteStrategy.java` — one ordered attempt to route an item.
- `core/playback/AppLinkStrategy.java` — the only strategy in this sub-project.
- `core/playback/PlaybackPlanner.java` — tries strategies in order; explains failure.
- `core/playback/UnroutableException.java` — thrown by `PlaybackService` when nothing routes.
- `core/playback/AppLinks.java` — pasted URL → `ContentItem` with service detection.
- `device/DeviceManager.java` — replaces `DeviceSessionManager`: one handle per device and adapter.
- `playback/PlaybackService.java` — plan then execute.
- `adapters/androidtv/AndroidTvSettings.java` — the adapter's per-device settings (port, certificate fingerprint) and the v2 `Device` factory.
- `adapters/androidtv/AndroidTvAdapter.java` — `DeviceAdapter` for Remote v2.
- `web/DeviceController.java` — `/devices/{id}/key/{key}` and `/devices/{id}/play`.
- `web/DashboardController.java` — `GET /` and `GET /remote/{id}`.
- `src/main/resources/templates/dashboard.html` — device strip + remote drawer.
- `src/main/resources/static/js/state-view.js`, `remote-transport.js`, `app.js` — ES modules.
- Tests: `core/ActionTest.java`, `core/DeviceTest.java`, `core/playback/PlaybackPlannerTest.java`, `core/playback/AppLinksTest.java`, `device/DeviceManagerTest.java`, `playback/PlaybackServiceTest.java`, `adapters/androidtv/AndroidTvAdapterTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`, `src/test/resources/devices-v1.json`.

### Files to move (git mv) and modify

- `ShieldApplication.java` → `HomeControlApplication.java`; `ShieldConfiguration.java` → `HomeControlConfiguration.java`.
- `device/Device.java`, `DeviceState.java`, `DeviceStatus.java`, `DeviceOfflineException.java`, `DeviceRegistry.java`, `DeviceStateChangedEvent.java` → `core/`.
- `discovery/DiscoveredDevice.java` → `core/DiscoveredDevice.java` (gains `adapterId`).
- `protocol/RemoteKey.java` → `core/RemoteKey.java`.
- `protocol/*` (everything else) → `adapters/androidtv/protocol/`; proto `java_package` options follow.
- `discovery/MdnsDiscovery.java`, `device/PairingService.java`, `ShieldProperties.java` (→ `AndroidTvProperties.java`), `device/DeviceSession.java` (→ `AndroidTvSession.java`) → `adapters/androidtv/`.
- `device/JsonFileDeviceRegistry.java` — v2 shape, v1 migration.
- `web/DeviceStateBroadcaster.java`, `web/StateController.java` — device-id payloads and per-device snapshot.
- `web/SetupController.java`, `templates/setup.html` — every paired device, forget by id, discovered from all adapters.
- `src/main/resources/static/app.css` — device strip and drawer styles.
- Tests move with their classes; `FakeRemoteServer` captures app links.

### Files to delete

- `device/DeviceSessionManager.java` (Task 6), `web/RemoteController.java` and `templates/remote.html` (Task 9), `src/main/resources/static/app.js` (Task 10), and their tests, replaced as noted.

---

### Task 1: Rename the root package to `dev.andre.homecontrol`

**Files:**
- Move: `src/main/java/dev/andre/shield/**` → `src/main/java/dev/andre/homecontrol/**`
- Move: `src/test/java/dev/andre/shield/**` → `src/test/java/dev/andre/homecontrol/**`
- Modify: every `.java` file (package and import lines), `src/main/proto/remotemessage.proto:5`, `src/main/proto/pairingmessage.proto:4`

**Interfaces:**
- Consumes: nothing.
- Produces: root package `dev.andre.homecontrol`; classes `HomeControlApplication`, `HomeControlConfiguration`. The `shield.*` property prefix and `ShieldProperties` are unchanged in this task.

- [ ] **Step 1: Move the source trees**

```bash
git mv src/main/java/dev/andre/shield src/main/java/dev/andre/homecontrol
git mv src/test/java/dev/andre/shield src/test/java/dev/andre/homecontrol
git mv src/main/java/dev/andre/homecontrol/ShieldApplication.java src/main/java/dev/andre/homecontrol/HomeControlApplication.java
git mv src/main/java/dev/andre/homecontrol/ShieldConfiguration.java src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java
git mv src/test/java/dev/andre/homecontrol/ShieldApplicationTest.java src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java
```

- [ ] **Step 2: Rewrite package, import and class references**

```bash
grep -rl "dev\.andre\.shield" src | xargs sed -i 's/dev\.andre\.shield/dev.andre.homecontrol/g'
grep -rl "ShieldApplication\b" src | xargs sed -i 's/\bShieldApplication\b/HomeControlApplication/g'
grep -rl "ShieldConfiguration\b" src | xargs sed -i 's/\bShieldConfiguration\b/HomeControlConfiguration/g'
grep -rn "dev\.andre\.shield\|ShieldApplication\|ShieldConfiguration" src || echo "clean"
```

Expected: the final grep prints `clean`. `casaos/docker-compose.yml` still says `id: dev.andre.shield-remote`; leave it, that is the CasaOS app id.

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; the same tests that passed before pass now.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: rename root package to dev.andre.homecontrol"
```

---

### Task 2: Core domain types

**Files:**
- Create: `core/Capability.java`, `core/DeviceKind.java`, `core/Action.java`, `core/DeviceAdapter.java`, `core/DeviceHandle.java`, `core/UnsupportedActionException.java`
- Move: `protocol/RemoteKey.java` → `core/RemoteKey.java`
- Test: `core/ActionTest.java`

**Interfaces:**
- Consumes: `RemoteKey` (moved), `DeviceState`, `Device` (still in `device` until Task 3).
- Produces: `Capability`, `DeviceKind`, `Action` with `Capability requires()`, `DeviceAdapter { String id(); Set<Capability> capabilities(Device); DeviceHandle connect(Device, Consumer<DeviceState>); default void forget(Device); List<DiscoveredDevice> discovered(); }`, `DeviceHandle { DeviceState state(); void execute(Action); void close(); }`.

- [ ] **Step 1: Move `RemoteKey` to `core`**

```bash
mkdir -p src/main/java/dev/andre/homecontrol/core
git mv src/main/java/dev/andre/homecontrol/protocol/RemoteKey.java src/main/java/dev/andre/homecontrol/core/RemoteKey.java
sed -i 's/^package dev.andre.homecontrol.protocol;/package dev.andre.homecontrol.core;/' src/main/java/dev/andre/homecontrol/core/RemoteKey.java
grep -rl "dev.andre.homecontrol.protocol.RemoteKey" src | xargs sed -i 's/dev.andre.homecontrol.protocol.RemoteKey/dev.andre.homecontrol.core.RemoteKey/g'
```

Classes in the `protocol` package that used `RemoteKey` without an import (same package) now need `import dev.andre.homecontrol.core.RemoteKey;` — `RemoteConnection.java` is the one; add it.

- [ ] **Step 2: Write the failing test**

`src/test/java/dev/andre/homecontrol/core/ActionTest.java`:

```java
package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ActionTest {

    @Test
    void aKeyPressRequiresRemoteKeys() {
        assertThat(new Action.PressKey(RemoteKey.HOME).requires()).isEqualTo(Capability.REMOTE_KEYS);
    }

    @Test
    void openingAnAppLinkRequiresAppLink() {
        Action action = new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=abc"));
        assertThat(action.requires()).isEqualTo(Capability.APP_LINK);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.core.ActionTest'`
Expected: compilation failure, `Action` and `Capability` do not exist.

- [ ] **Step 4: Write the types**

`core/Capability.java`:

```java
package dev.andre.homecontrol.core;

/** What a device can do. The planner matches an item's playable references against these (spec §5.1). */
public enum Capability {
    REMOTE_KEYS, POWER, VOLUME, APP_LINK, CAST_RECEIVER, MEDIA_RENDERER, JELLYFIN_CLIENT, LOCAL_AUDIO_SINK
}
```

`core/DeviceKind.java`:

```java
package dev.andre.homecontrol.core;

/** Coarse device family, used for icons and setup copy — never for routing decisions. */
public enum DeviceKind {
    ANDROID_TV, CAST, WEBOS, TIZEN, UPNP, SONOS, BLUETOOTH
}
```

`core/Action.java`:

```java
package dev.andre.homecontrol.core;

import java.net.URI;

/** A command for one device. Each action names the capability an adapter must declare to accept it. */
public sealed interface Action {

    Capability requires();

    record PressKey(RemoteKey key) implements Action {
        @Override
        public Capability requires() {
            return Capability.REMOTE_KEYS;
        }
    }

    /** Ask the device to open a URI in whatever app claims it. Optimistic by design (spec §5.3). */
    record OpenAppLink(URI uri) implements Action {
        @Override
        public Capability requires() {
            return Capability.APP_LINK;
        }
    }
}
```

`core/DeviceHandle.java`:

```java
package dev.andre.homecontrol.core;

/** One live connection to one device through one adapter. Reconnects on its own until closed. */
public interface DeviceHandle extends AutoCloseable {

    DeviceState state();

    /**
     * Sends the action now or throws: {@link dev.andre.homecontrol.device.DeviceOfflineException}
     * when not connected, {@link UnsupportedActionException} when this adapter cannot do it.
     * Nothing is queued (global constraint).
     */
    void execute(Action action);

    @Override
    void close();
}
```

`core/DeviceAdapter.java`:

```java
package dev.andre.homecontrol.core;

import dev.andre.homecontrol.device.Device;
import dev.andre.homecontrol.device.DeviceState;
import dev.andre.homecontrol.discovery.DiscoveredDevice;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The only thing that speaks a device protocol. One bean per protocol family; a
 * {@link Device} lists the adapter ids that apply to it with per-adapter settings.
 */
public interface DeviceAdapter {

    /** Stable key used in {@code Device.adapters} and in registry files, e.g. {@code androidtv}. */
    String id();

    Set<Capability> capabilities(Device device);

    /**
     * Brings the device up and returns immediately. {@code onChange} is called with every state
     * transition, including the first; the handle keeps reconnecting until closed.
     */
    DeviceHandle connect(Device device, Consumer<DeviceState> onChange);

    /** Removes credentials this adapter stored for the device. Default: nothing to remove. */
    default void forget(Device device) {
    }

    /** Devices this adapter has seen on the network, paired or not. */
    List<DiscoveredDevice> discovered();
}
```

(The imports of `Device`, `DeviceState` and `DiscoveredDevice` change to `core` in Task 3 when those types move.)

`core/UnsupportedActionException.java`:

```java
package dev.andre.homecontrol.core;

/** The device has no adapter declaring the capability the action requires. HTTP 422. */
public class UnsupportedActionException extends RuntimeException {
    public UnsupportedActionException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Run the test and the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.core.ActionTest'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add the core device model — capabilities, actions, adapter contract"
```

---

### Task 3: `Device` v2 and registry migration

**Files:**
- Move: `device/Device.java`, `DeviceState.java`, `DeviceStatus.java`, `DeviceOfflineException.java`, `DeviceRegistry.java`, `DeviceStateChangedEvent.java` → `core/`; `discovery/DiscoveredDevice.java` → `core/DiscoveredDevice.java`
- Create: `adapters/androidtv/AndroidTvSettings.java`
- Modify: `device/JsonFileDeviceRegistry.java`, `device/DeviceSession.java`, `device/DeviceSessionManager.java`, `device/PairingService.java`, `discovery/MdnsDiscovery.java`, `core/DeviceAdapter.java` imports
- Test: `core/DeviceTest.java`, `device/JsonFileDeviceRegistryTest.java`, `src/test/resources/devices-v1.json`; every test calling `new Device(`

**Interfaces:**
- Consumes: `DeviceKind`.
- Produces: `Device(String id, String name, DeviceKind kind, String host, Map<String, Map<String, String>> adapters, Instant lastSeen)` with `adapterSettings(String)`, `hasAdapter(String)`, `withAdapter(String, Map)`; `AndroidTvSettings(int port, String certificateFingerprint)` with `ADAPTER_ID = "androidtv"`, `of(Device)`, `toMap()`, and the factory `device(id, name, host, port, fingerprint, lastSeen)`; `DiscoveredDevice(String adapterId, String name, String host, int port)`.

- [ ] **Step 1: Move the domain records to `core`**

```bash
cd src/main/java/dev/andre/homecontrol
for f in Device DeviceState DeviceStatus DeviceOfflineException DeviceRegistry DeviceStateChangedEvent; do
  git mv device/$f.java core/$f.java
  sed -i 's/^package dev.andre.homecontrol.device;/package dev.andre.homecontrol.core;/' core/$f.java
done
git mv discovery/DiscoveredDevice.java core/DiscoveredDevice.java
sed -i 's/^package dev.andre.homecontrol.discovery;/package dev.andre.homecontrol.core;/' core/DiscoveredDevice.java
cd -
for f in Device DeviceState DeviceStatus DeviceOfflineException DeviceRegistry DeviceStateChangedEvent; do
  grep -rl "dev.andre.homecontrol.device.$f\b" src | xargs -r sed -i "s/dev.andre.homecontrol.device.$f\b/dev.andre.homecontrol.core.$f/g"
done
grep -rl "dev.andre.homecontrol.discovery.DiscoveredDevice" src | xargs -r sed -i 's/dev.andre.homecontrol.discovery.DiscoveredDevice/dev.andre.homecontrol.core.DiscoveredDevice/g'
```

Files still in `device` and `discovery` that used these types without imports (same package) need explicit imports now: `DeviceSession`, `DeviceSessionManager`, `JsonFileDeviceRegistry`, `PairingService`, `MdnsDiscovery`, and their tests. Add `import dev.andre.homecontrol.core.*;` lines for each type used. Fix `core/DeviceAdapter.java` to import from `core` (same package: delete the three imports).

- [ ] **Step 2: Write the failing `Device` test**

`src/test/java/dev/andre/homecontrol/core/DeviceTest.java`:

```java
package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTest {

    @Test
    void exposesPerAdapterSettingsAndAnEmptyMapForUnknownAdapters() {
        Device device = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of("port", "6466")), Instant.EPOCH);

        assertThat(device.adapterSettings("androidtv")).containsEntry("port", "6466");
        assertThat(device.adapterSettings("cast")).isEmpty();
        assertThat(device.hasAdapter("androidtv")).isTrue();
        assertThat(device.hasAdapter("cast")).isFalse();
    }

    @Test
    void keepsAdapterOrderSoTheFirstListedAdapterIsThePrimaryOne() {
        Map<String, Map<String, String>> adapters = new LinkedHashMap<>();
        adapters.put("androidtv", Map.of());
        adapters.put("cast", Map.of());
        Device device = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                adapters, Instant.EPOCH);

        assertThat(List.copyOf(device.adapters().keySet())).containsExactly("androidtv", "cast");
    }

    @Test
    void withAdapterAddsWithoutMutatingTheOriginal() {
        Device original = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of(), Instant.EPOCH);

        Device extended = original.withAdapter("cast", Map.of("port", "8009"));

        assertThat(original.hasAdapter("cast")).isFalse();
        assertThat(extended.adapterSettings("cast")).containsEntry("port", "8009");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.core.DeviceTest'`
Expected: compilation failure — `Device` still has the v1 signature.

- [ ] **Step 4: Rewrite `Device`**

`core/Device.java`:

```java
package dev.andre.homecontrol.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A registered device. {@code adapters} maps adapter id → that adapter's own settings
 * (strings only, so the registry file stays readable and adapter-agnostic). Insertion
 * order is significant: the first adapter is the primary one for state display.
 */
public record Device(String id, String name, DeviceKind kind, String host,
                     Map<String, Map<String, String>> adapters, Instant lastSeen) {

    public Device {
        Map<String, Map<String, String>> ordered = new LinkedHashMap<>();
        if (adapters != null) {
            adapters.forEach((adapterId, settings) ->
                    ordered.put(adapterId, settings == null ? Map.of() : Map.copyOf(settings)));
        }
        adapters = Collections.unmodifiableMap(ordered);
    }

    public Map<String, String> adapterSettings(String adapterId) {
        return adapters.getOrDefault(adapterId, Map.of());
    }

    public boolean hasAdapter(String adapterId) {
        return adapters.containsKey(adapterId);
    }

    public Device withAdapter(String adapterId, Map<String, String> settings) {
        Map<String, Map<String, String>> extended = new LinkedHashMap<>(adapters);
        extended.put(adapterId, settings);
        return new Device(id, name, kind, host, extended, lastSeen);
    }
}
```

`core/DiscoveredDevice.java`:

```java
package dev.andre.homecontrol.core;

/** A device seen on the network by one adapter, paired or not. */
public record DiscoveredDevice(String adapterId, String name, String host, int port) {
}
```

Update `MdnsDiscovery.toDevice` to `new DiscoveredDevice("androidtv", name, addresses[0].getHostAddress(), port)` and `MdnsDiscoveryTest` accordingly.

- [ ] **Step 5: Add `AndroidTvSettings`**

`adapters/androidtv/AndroidTvSettings.java`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Android TV adapter's per-device settings, as stored under {@code adapters.androidtv}. */
public record AndroidTvSettings(int port, String certificateFingerprint) {

    public static final String ADAPTER_ID = "androidtv";
    public static final int DEFAULT_PORT = 6466;
    static final String PORT = "port";
    static final String FINGERPRINT = "certificateFingerprint";

    public static AndroidTvSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        if (!device.hasAdapter(ADAPTER_ID)) {
            throw new IllegalArgumentException("Device " + device.id() + " has no androidtv adapter");
        }
        return new AndroidTvSettings(
                Integer.parseInt(settings.getOrDefault(PORT, String.valueOf(DEFAULT_PORT))),
                settings.get(FINGERPRINT));
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(PORT, String.valueOf(port));
        if (certificateFingerprint != null) {
            map.put(FINGERPRINT, certificateFingerprint);
        }
        return map;
    }

    /** The certificate alias is the device id, as it always was; the keystore needs no migration. */
    public static String certificateAlias(Device device) {
        return device.id();
    }

    /** Same argument order as the v1 {@code Device} constructor, so call sites are a rename. */
    public static Device device(String id, String name, String host, int port,
                                String certificateFingerprint, Instant lastSeen) {
        return new Device(id, name, DeviceKind.ANDROID_TV, host,
                Map.of(ADAPTER_ID, new AndroidTvSettings(port, certificateFingerprint).toMap()),
                lastSeen);
    }
}
```

- [ ] **Step 6: Update the call sites**

- `device/DeviceSession.java`: add a field `private final AndroidTvSettings settings;` set in the constructor via `AndroidTvSettings.of(device)`; replace `device.port()` with `settings.port()` and `device.certificateFingerprint()` with `settings.certificateFingerprint()`.
- `device/DeviceSessionManager.java`: replace every `device.certificateAlias()` with `AndroidTvSettings.certificateAlias(device)`.
- `device/PairingService.java`: replace `new Device(current.deviceId(), current.name(), current.host(), REMOTE_PORT, ClientCertificate.fingerprintOf(paired.serverCertificate()), Instant.now())` with `AndroidTvSettings.device(` and the same arguments.
- `device/JsonFileDeviceRegistry.validateDevices`: replace the `host`/`port` checks with: `host` required, `kind` required (`device.kind() == null` → "kind is required"), and `adapters` must be non-null (the record guarantees it).
- Tests: `grep -rln "new Device(" src/test | xargs sed -i 's/new Device(/AndroidTvSettings.device(/g'` and add `import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;` to each file the grep listed (`DeviceSessionManagerTest`, `DeviceSessionTest`, `JsonFileDeviceRegistryTest`, `DeviceStateStreamEndToEndTest`, `RemotePageTest`). In `RemotePageTest` replace `new dev.andre.shield.discovery.DiscoveredDevice("Living Room Shield", "192.168.1.50", 6466)` with `new DiscoveredDevice("androidtv", "Living Room Shield", "192.168.1.50", 6466)` (import from `core`).

- [ ] **Step 7: Write the failing migration test**

`src/test/resources/devices-v1.json` — exactly what v0.3 wrote:

```json
[{"id":"192-168-1-50","name":"Living Room Shield","host":"192.168.1.50","port":6466,"certificateFingerprint":"AB:CD","lastSeen":"2026-08-29T18:00:00Z"}]
```

Add to `device/JsonFileDeviceRegistryTest.java`:

```java
    @Test
    void migratesAVersionOneFileToTheAdapterShapeAndRewritesIt() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/devices-v1.json"), file);
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);

        List<Device> devices = registry.findAll();

        assertThat(devices).singleElement().satisfies(device -> {
            assertThat(device.id()).isEqualTo("192-168-1-50");
            assertThat(device.kind()).isEqualTo(DeviceKind.ANDROID_TV);
            assertThat(device.host()).isEqualTo("192.168.1.50");
            assertThat(device.adapterSettings("androidtv"))
                    .containsEntry("port", "6466")
                    .containsEntry("certificateFingerprint", "AB:CD");
            assertThat(device.lastSeen()).isEqualTo(Instant.parse("2026-08-29T18:00:00Z"));
        });
        String rewritten = Files.readString(file);
        assertThat(rewritten).contains("\"kind\":\"ANDROID_TV\"").contains("\"adapters\"")
                .doesNotContain("\"certificateFingerprint\":\"AB:CD\",\"lastSeen\"");
    }

    @Test
    void aVersionOneRecordWithoutAFingerprintMigratesWithoutOne() throws Exception {
        Path file = dir.resolve("devices.json");
        Files.writeString(file, "[{\"id\":\"x\",\"name\":\"X\",\"host\":\"10.0.0.9\",\"port\":6466,"
                + "\"certificateFingerprint\":null,\"lastSeen\":\"2026-08-29T18:00:00Z\"}]");

        Device device = new JsonFileDeviceRegistry(file).findAll().getFirst();

        assertThat(device.adapterSettings("androidtv")).containsEntry("port", "6466")
                .doesNotContainKey("certificateFingerprint");
    }
```

(The fixture path works because Gradle runs tests with the project directory as the working directory.)

- [ ] **Step 8: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.device.JsonFileDeviceRegistryTest'`
Expected: FAIL — the v1 document has no `kind`, so validation raises `StorageException`.

- [ ] **Step 9: Implement the migration**

In `device/JsonFileDeviceRegistry.java` replace `findAll()` with:

```java
    @Override
    public synchronized List<Device> findAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("registry document must be a JSON array");
            }
            List<Device> devices = new ArrayList<>();
            boolean migrated = false;
            for (JsonNode node : root) {
                if (node.isObject() && !node.has("kind")) {
                    node = migrateVersionOne((ObjectNode) node);
                    migrated = true;
                }
                devices.add(mapper.treeToValue(node, Device.class));
            }
            validateDevices(devices);
            if (migrated) {
                writeAll(devices);
            }
            return devices;
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            throw new StorageException(
                    "Could not read device registry " + file
                            + "; check file permissions and JSON integrity",
                    e);
        }
    }

    /**
     * v0.3 wrote {@code {id, name, host, port, certificateFingerprint, lastSeen}} for the one
     * Android TV. v2 keeps id/name/host/lastSeen and moves the rest under
     * {@code adapters.androidtv} — the certificate alias is still the id, so the keystore is
     * untouched and nobody re-pairs (spec §8).
     */
    private ObjectNode migrateVersionOne(ObjectNode v1) {
        ObjectNode androidtv = mapper.createObjectNode();
        androidtv.put("port", v1.path("port").asInt(6466));
        JsonNode fingerprint = v1.get("certificateFingerprint");
        if (fingerprint != null && !fingerprint.isNull()) {
            androidtv.put("certificateFingerprint", fingerprint.asText());
        }
        ObjectNode adapters = mapper.createObjectNode();
        adapters.set("androidtv", androidtv);

        ObjectNode v2 = mapper.createObjectNode();
        v2.set("id", v1.get("id"));
        v2.set("name", v1.get("name"));
        v2.put("kind", "ANDROID_TV");
        v2.set("host", v1.get("host"));
        v2.set("adapters", adapters);
        v2.set("lastSeen", v1.get("lastSeen"));
        return v2;
    }
```

Add imports `tools.jackson.databind.JsonNode` and `tools.jackson.databind.node.ObjectNode`. The adapter's `port` is stored as a JSON number here but `Device` declares `Map<String, String>`; Jackson coerces scalars to strings on read, so `adapterSettings("androidtv").get("port")` is `"6466"`. To keep the file consistent, `writeAll` (unchanged) serialises the migrated `Device`, whose map values are already strings.

- [ ] **Step 10: Run the registry tests, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.device.JsonFileDeviceRegistryTest'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL. Existing tests that construct devices now go through `AndroidTvSettings.device` and still pass.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: adapter-shaped device registry with migration of the v0.3 file"
```

---

### Task 4: The Android TV adapter

**Files:**
- Move: `protocol/**` (except `RemoteKey`, already moved) → `adapters/androidtv/protocol/`; `discovery/MdnsDiscovery.java`, `device/PairingService.java`, `device/DeviceSession.java` (→ `AndroidTvSession.java`), `ShieldProperties.java` (→ `adapters/androidtv/AndroidTvProperties.java`) → `adapters/androidtv/`; the matching tests
- Modify: `src/main/proto/*.proto` `java_package`, `HomeControlConfiguration.java`, `HomeControlApplication.java` (`@ConfigurationPropertiesScan` or `@EnableConfigurationProperties` target), `device/DeviceSessionManager.java`, `web/RemoteController.java`
- Create: `adapters/androidtv/AndroidTvAdapter.java`
- Test: `adapters/androidtv/AndroidTvAdapterTest.java`

**Interfaces:**
- Consumes: `DeviceAdapter`, `DeviceHandle`, `Action`, `AndroidTvSettings`, `CertificateStore`, `MdnsDiscovery`.
- Produces: `AndroidTvAdapter` (Spring `@Component`, id `androidtv`, capabilities `REMOTE_KEYS, POWER, VOLUME`); `AndroidTvSession implements DeviceHandle` with `execute(Action)`; `AndroidTvProperties` bound to prefix `shield`.

- [ ] **Step 1: Move the protocol and adapter classes**

```bash
cd src/main/java/dev/andre/homecontrol
mkdir -p adapters/androidtv
git mv protocol adapters/androidtv/protocol
git mv discovery/MdnsDiscovery.java adapters/androidtv/MdnsDiscovery.java
git mv device/PairingService.java adapters/androidtv/PairingService.java
git mv device/DeviceSession.java adapters/androidtv/AndroidTvSession.java
git mv ShieldProperties.java adapters/androidtv/AndroidTvProperties.java
rmdir discovery
cd ../../../../../..
cd src/test/java/dev/andre/homecontrol
mkdir -p adapters/androidtv
git mv protocol adapters/androidtv/protocol
git mv discovery/MdnsDiscoveryTest.java adapters/androidtv/MdnsDiscoveryTest.java
git mv device/PairingServiceTest.java adapters/androidtv/PairingServiceTest.java
git mv device/DeviceSessionTest.java adapters/androidtv/AndroidTvSessionTest.java
rmdir discovery
cd ../../../../../..
grep -rl "dev.andre.homecontrol.protocol" src | xargs sed -i 's/dev\.andre\.homecontrol\.protocol/dev.andre.homecontrol.adapters.androidtv.protocol/g'
grep -rl "dev.andre.homecontrol.discovery.MdnsDiscovery\|dev.andre.homecontrol.device.PairingService\|dev.andre.homecontrol.device.DeviceSession\b\|dev.andre.homecontrol.ShieldProperties" src | xargs sed -i \
  -e 's/dev\.andre\.homecontrol\.discovery\.MdnsDiscovery/dev.andre.homecontrol.adapters.androidtv.MdnsDiscovery/g' \
  -e 's/dev\.andre\.homecontrol\.device\.PairingService/dev.andre.homecontrol.adapters.androidtv.PairingService/g' \
  -e 's/dev\.andre\.homecontrol\.device\.DeviceSession\b/dev.andre.homecontrol.adapters.androidtv.AndroidTvSession/g' \
  -e 's/dev\.andre\.homecontrol\.ShieldProperties/dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties/g'
grep -rl "\bShieldProperties\b" src | xargs sed -i 's/\bShieldProperties\b/AndroidTvProperties/g'
grep -rl "\bDeviceSession\b" src | xargs sed -i 's/\bDeviceSession\b/AndroidTvSession/g'
grep -rl "\bDeviceSessionTest\b" src | xargs sed -i 's/\bDeviceSessionTest\b/AndroidTvSessionTest/g'
```

Then fix package declarations of every moved file to its new package (`sed -i 's/^package .*/package dev.andre.homecontrol.adapters.androidtv;/'` for the four adapter-level classes and their tests; `...adapters.androidtv.protocol` for the protocol classes). Keep `@ConfigurationProperties("shield")` on `AndroidTvProperties`. Check `HomeControlApplication` for `@ConfigurationPropertiesScan` / `@EnableConfigurationProperties(ShieldProperties.class)` and point it at the new class.

- [ ] **Step 2: Build to confirm the move is purely mechanical**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Fix any missing import the sed could not infer (classes that were package-local to `device` and now live in `adapters.androidtv`: `DeviceSessionManager` needs `import dev.andre.homecontrol.adapters.androidtv.AndroidTvSession;`).

- [ ] **Step 3: Write the failing adapter test**

`src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvAdapterTest.java`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AndroidTvAdapterTest {

    @TempDir
    Path dir;

    private AndroidTvProperties properties() {
        return new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
    }

    private AndroidTvAdapter adapter(CertificateStore certificates) {
        return new AndroidTvAdapter(certificates, properties(), new MdnsDiscovery(false));
    }

    @Test
    void declaresRemoteKeysPowerAndVolume() {
        Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", 6466, null, Instant.now());

        assertThat(adapter(new CertificateStore(properties().keystoreFile(), "shield".toCharArray()))
                .capabilities(device))
                .containsExactlyInAnyOrder(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME);
    }

    @Test
    void withoutACredentialTheHandleIsUnpairedAndNoCredentialIsCreated() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
            Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", remote.port(), null, Instant.now());
            List<DeviceState> seen = new CopyOnWriteArrayList<>();

            try (DeviceHandle handle = adapter(certificates).connect(device, seen::add)) {
                assertThat(handle.state().status()).isEqualTo(DeviceStatus.UNPAIRED);
                assertThatThrownBy(() -> handle.execute(new Action.PressKey(RemoteKey.HOME)))
                        .isInstanceOf(DeviceOfflineException.class)
                        .hasMessageContaining("paired");
            }
            assertThat(seen).extracting(DeviceState::status).containsExactly(DeviceStatus.UNPAIRED);
            assertThat(remote.connections()).isZero();
            assertThat(Files.exists(properties().keystoreFile())).isFalse();
        }
    }

    @Test
    void executesAKeyPressOnceConnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate("shield");
            Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", remote.port(), null, Instant.now());

            try (DeviceHandle handle = adapter(certificates).connect(device, state -> { })) {
                await().until(() -> handle.state().status() == DeviceStatus.CONNECTED);

                handle.execute(new Action.PressKey(RemoteKey.DPAD_UP));

                assertThat(remote.nextKeyPress()).isEqualTo(RemoteKey.DPAD_UP.code());
            }
        }
    }

    @Test
    void anUnreadableKeystoreFailsAtAdapterStart() {
        new CertificateStore(properties().keystoreFile(), "correct".toCharArray()).loadOrCreate("x");
        AndroidTvAdapter adapter = adapter(new CertificateStore(properties().keystoreFile(), "wrong".toCharArray()));

        assertThatThrownBy(adapter::verifyCredentialStore)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("password");
    }

    @Test
    void forgetDeletesOnlyThatDevicesCredential() {
        CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
        certificates.loadOrCreate("forgotten");
        certificates.loadOrCreate("kept");
        Device device = AndroidTvSettings.device("forgotten", "Shield", "127.0.0.1", 6466, null, Instant.now());

        adapter(certificates).forget(device);

        assertThat(certificates.load("forgotten")).isEmpty();
        assertThat(certificates.load("kept")).isPresent();
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapterTest'`
Expected: compilation failure — `AndroidTvAdapter` does not exist and `AndroidTvSession` is not a `DeviceHandle`.

- [ ] **Step 5: Make `AndroidTvSession` a `DeviceHandle`**

In `adapters/androidtv/AndroidTvSession.java`:

```java
public class AndroidTvSession implements RemoteListener, DeviceHandle {
```

Add, next to `sendKey`:

```java
    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> sendKey(press.key());
            case Action.OpenAppLink ignored -> throw new UnsupportedActionException(
                    "App links arrive in the next task");
        }
    }
```

(Task 5 replaces the `OpenAppLink` branch.) `close()` already exists and matches the interface. Imports: `dev.andre.homecontrol.core.Action`, `DeviceHandle`, `UnsupportedActionException`.

- [ ] **Step 6: Write the adapter**

`adapters/androidtv/AndroidTvAdapter.java`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DiscoveredDevice;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Android TV Remote v2: the Shield and every other Android TV / Google TV box. */
@Component
public class AndroidTvAdapter implements DeviceAdapter {

    public static final String ID = AndroidTvSettings.ADAPTER_ID;

    private final CertificateStore certificates;
    private final AndroidTvProperties properties;
    private final MdnsDiscovery discovery;

    public AndroidTvAdapter(CertificateStore certificates, AndroidTvProperties properties,
                            MdnsDiscovery discovery) {
        this.certificates = certificates;
        this.properties = properties;
        this.discovery = discovery;
    }

    /** A wrong keystore password must stop startup loudly, not look like "no devices paired". */
    @PostConstruct
    public void verifyCredentialStore() {
        certificates.verifyReadable();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<Capability> capabilities(Device device) {
        return EnumSet.of(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME);
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        Optional<ClientCertificate> credential = certificates.load(AndroidTvSettings.certificateAlias(device));
        if (credential.isEmpty()) {
            // Load-only: pairing is the only flow allowed to create a credential (v0.3).
            DeviceState unpaired = DeviceState.unpaired();
            onChange.accept(unpaired);
            return new UnpairedHandle(unpaired);
        }
        AndroidTvSession session = new AndroidTvSession(device, credential.get(), properties, onChange);
        session.start();
        return session;
    }

    @Override
    public void forget(Device device) {
        certificates.delete(AndroidTvSettings.certificateAlias(device));
    }

    @Override
    public List<DiscoveredDevice> discovered() {
        return discovery.devices();
    }

    /** A registered device whose credential is gone: visible, controllable only after re-pairing. */
    private record UnpairedHandle(DeviceState state) implements DeviceHandle {

        @Override
        public void execute(Action action) {
            throw new DeviceOfflineException("This device must be paired again before it can be controlled");
        }

        @Override
        public void close() {
        }
    }
}
```

- [ ] **Step 7: Route `DeviceSessionManager` through the adapter (temporary, replaced in Task 6)**

In `device/DeviceSessionManager.java`: inject `AndroidTvAdapter adapter` instead of `CertificateStore` and `AndroidTvProperties`; `startRegisteredDevices()` drops the `certificates.verifyReadable()` line; `startSession(device)` becomes

```java
        DeviceHandle handle = adapter.connect(device,
                state -> events.publishEvent(new DeviceStateChangedEvent(state)));
        sessions.put(device.id(), handle);
```

with `sessions` typed `Map<String, DeviceHandle>`; `forget` calls `adapter.forget(device)` instead of `certificates.delete(...)`; `active()` returns `Optional<DeviceHandle>`. In `web/RemoteController.key` replace `session().sendKey(remoteKey)` with `session().execute(new Action.PressKey(remoteKey))`. Update `DeviceSessionManagerTest` to build the manager with `new AndroidTvAdapter(certificates, properties, new MdnsDiscovery(false))`; delete its `emptyRegistryStillRejectsAnUnreadableKeystore` test (now `anUnreadableKeystoreFailsAtAdapterStart`). Update `RemoteControllerTest` to mock `DeviceHandle` and `verify(session).execute(new Action.PressKey(RemoteKey.DPAD_UP))`.

- [ ] **Step 8: Run the adapter tests, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.adapters.androidtv.*'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: wrap Remote v2 in an Android TV device adapter"
```

---

### Task 5: Restore app-link launching

**Files:**
- Modify: `adapters/androidtv/protocol/RemoteConnection.java`, `adapters/androidtv/AndroidTvSession.java`, `adapters/androidtv/AndroidTvAdapter.java`
- Test: `adapters/androidtv/protocol/FakeRemoteServer.java`, `adapters/androidtv/protocol/RemoteConnectionTest.java`, `adapters/androidtv/AndroidTvAdapterTest.java`

**Interfaces:**
- Consumes: `Action.OpenAppLink`.
- Produces: `RemoteConnection.sendAppLink(String uri)`; `FakeRemoteServer.nextAppLink()`; client feature mask `614`; capability `APP_LINK` on Android TV.

- [ ] **Step 1: Teach the fake device to capture app links**

In `FakeRemoteServer`: add `private final BlockingQueue<String> appLinks = new LinkedBlockingQueue<>();`, the accessor

```java
    public String nextAppLink() throws InterruptedException {
        return appLinks.poll(5, TimeUnit.SECONDS);
    }
```

and in `handle()`'s dispatch chain, before the `hasRemotePingResponse` branch:

```java
            } else if (message.hasRemoteAppLinkLaunchRequest()) {
                appLinks.add(message.getRemoteAppLinkLaunchRequest().getAppLink());
```

- [ ] **Step 2: Write the failing tests**

In `RemoteConnectionTest` rename `advertisesOnlyImplementedV03Features` to `advertisesKeyImePowerVolumeAndAppLink` and expect `614` for both masks. Add:

```java
    @Test
    void sendsAnAppLinkLaunchRequest() throws Exception {
        connection.sendAppLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertThat(device.nextAppLink()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    }
```

(`connection` is the test's connected `RemoteConnection` field; use whatever name that class already uses.)

In `AndroidTvAdapterTest` change `declaresRemoteKeysPowerAndVolume` to also expect `Capability.APP_LINK`, and add:

```java
    @Test
    void opensAnAppLinkOnceConnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate("shield");
            Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", remote.port(), null, Instant.now());

            try (DeviceHandle handle = adapter(certificates).connect(device, state -> { })) {
                await().until(() -> handle.state().status() == DeviceStatus.CONNECTED);

                handle.execute(new Action.OpenAppLink(java.net.URI.create("https://www.netflix.com/title/80057281")));

                assertThat(remote.nextAppLink()).isEqualTo("https://www.netflix.com/title/80057281");
            }
        }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew test --tests 'dev.andre.homecontrol.adapters.androidtv.*'`
Expected: FAIL — mask is 102, `sendAppLink` missing, `OpenAppLink` throws `UnsupportedActionException`.

- [ ] **Step 4: Implement**

`RemoteConnection`:

```java
    private static final int FEATURE_APP_LINK = 1 << 9;  // 512, RemoteAppLinkLaunchRequest
    private static final int CLIENT_FEATURES = FEATURE_KEY
            | FEATURE_IME_RECEIVE
            | FEATURE_POWER
            | FEATURE_VOLUME
            | FEATURE_APP_LINK;

    /**
     * Asks the device to open {@code uri} with whatever app claims it. There is no reply:
     * success shows up, if at all, as a later current-app event (spec §5.3).
     */
    public void sendAppLink(String uri) throws IOException {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteAppLinkLaunchRequest(RemoteAppLinkLaunchRequest.newBuilder().setAppLink(uri))
                .build());
    }
```

with `import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteAppLinkLaunchRequest;`.

`AndroidTvSession`:

```java
    public void openAppLink(URI uri) {
        RemoteConnection current = requireConnected();
        try {
            current.sendAppLink(uri.toString());
        } catch (IOException e) {
            throw new DeviceOfflineException("The device dropped the connection while opening " + uri);
        }
    }

    @Override
    public void execute(Action action) {
        switch (action) {
            case Action.PressKey press -> sendKey(press.key());
            case Action.OpenAppLink open -> openAppLink(open.uri());
        }
    }
```

`AndroidTvAdapter.capabilities` returns `EnumSet.of(REMOTE_KEYS, POWER, VOLUME, APP_LINK)`.

- [ ] **Step 5: Run the tests, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.adapters.androidtv.*'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: open app links on Android TV through Remote v2"
```

---

### Task 6: `DeviceManager` — one handle per registered device

**Files:**
- Create: `device/DeviceManager.java`
- Delete: `device/DeviceSessionManager.java`, `device/DeviceSessionManagerTest.java`
- Modify: `core/DeviceStateChangedEvent.java`, `web/DeviceStateBroadcaster.java` (compile fix only), `web/StateController.java` (compile fix only), `web/RemoteController.java`, `web/SetupController.java`, `adapters/androidtv/PairingService.java`, `web/DeviceStateStreamEndToEndTest.java`, `web/RemoteControllerTest.java`, `web/RemotePageTest.java`, `web/SetupControllerTest.java`, `adapters/androidtv/PairingServiceTest.java`
- Test: `device/DeviceManagerTest.java`

**Interfaces:**
- Consumes: `DeviceRegistry`, `List<DeviceAdapter>`, `ApplicationEventPublisher`.
- Produces: `DeviceStateChangedEvent(String deviceId, DeviceState state)`; `DeviceManager { List<Device> devices(); Optional<Device> device(String id); Optional<Device> defaultDevice(); DeviceState state(String id); Map<String, DeviceState> states(); Set<Capability> capabilities(String id); void execute(String id, Action action); void adopt(Device device); void forget(String id); List<DiscoveredDevice> discovered(); }`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/device/DeviceManagerTest.java`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.MdnsDiscovery;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DeviceManagerTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher publisher = published::add;

    private AndroidTvProperties properties() {
        return new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
    }

    private CertificateStore certificates() {
        return new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
    }

    private DeviceManager manager(DeviceRegistry registry, CertificateStore certificates) {
        return new DeviceManager(registry,
                List.of(new AndroidTvAdapter(certificates, properties(), new MdnsDiscovery(false))),
                publisher);
    }

    @Test
    void connectsEveryRegisteredDeviceAndReportsStatePerId() throws Exception {
        try (FakeRemoteServer living = new FakeRemoteServer(); FakeRemoteServer bedroom = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("living", "Living Room", "127.0.0.1", living.port(), null,
                    Instant.parse("2026-09-01T10:00:00Z")));
            registry.save(AndroidTvSettings.device("bedroom", "Bedroom", "127.0.0.1", bedroom.port(), null,
                    Instant.parse("2026-09-02T10:00:00Z")));
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("living");
            certificates.loadOrCreate("bedroom");

            try (DeviceManager manager = manager(registry, certificates)) {
                manager.start();

                await().until(() -> manager.state("living").status() == DeviceStatus.CONNECTED
                        && manager.state("bedroom").status() == DeviceStatus.CONNECTED);
                assertThat(manager.states()).containsOnlyKeys("living", "bedroom");
                assertThat(manager.defaultDevice()).get().extracting(Device::id).isEqualTo("bedroom");
                assertThat(published).anySatisfy(event -> assertThat(event)
                        .isInstanceOfSatisfying(DeviceStateChangedEvent.class, e ->
                                assertThat(e.deviceId()).isEqualTo("living")));
            }
        }
    }

    @Test
    void executesAgainstTheAddressedDeviceOnly() throws Exception {
        try (FakeRemoteServer living = new FakeRemoteServer(); FakeRemoteServer bedroom = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("living", "Living Room", "127.0.0.1", living.port(), null, Instant.now()));
            registry.save(AndroidTvSettings.device("bedroom", "Bedroom", "127.0.0.1", bedroom.port(), null, Instant.now()));
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("living");
            certificates.loadOrCreate("bedroom");

            try (DeviceManager manager = manager(registry, certificates)) {
                manager.start();
                await().until(() -> manager.state("bedroom").status() == DeviceStatus.CONNECTED);

                manager.execute("bedroom", new Action.PressKey(RemoteKey.HOME));

                assertThat(bedroom.nextKeyPress()).isEqualTo(RemoteKey.HOME.code());
                assertThat(living.nextKeyPress()).isNull();
            }
        }
    }

    @Test
    void anUnknownDeviceIsOfflineAndAnUnsupportedActionIsRejected() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("speaker", "Speaker", DeviceKind.UPNP, "10.0.0.7",
                Map.of("upnp", Map.of()), Instant.now()));

        try (DeviceManager manager = manager(registry, certificates())) {
            manager.start();

            assertThatThrownBy(() -> manager.execute("nope", new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(DeviceOfflineException.class);
            assertThatThrownBy(() -> manager.execute("speaker", new Action.PressKey(RemoteKey.HOME)))
                    .isInstanceOf(UnsupportedActionException.class);
            assertThat(manager.capabilities("speaker")).isEmpty();
            assertThat(manager.state("speaker").status()).isEqualTo(DeviceStatus.DISCONNECTED);
        }
    }

    @Test
    void forgetClosesTheHandleRemovesTheRecordAndItsCredentialAndPublishesDisconnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
            registry.save(AndroidTvSettings.device("gone", "Gone", "127.0.0.1", remote.port(), null, Instant.now()));
            CertificateStore certificates = certificates();
            certificates.loadOrCreate("gone");
            certificates.loadOrCreate("kept");

            try (DeviceManager manager = manager(registry, certificates)) {
                manager.start();
                await().until(() -> manager.state("gone").status() == DeviceStatus.CONNECTED);
                published.clear();

                manager.forget("gone");

                assertThat(registry.findById("gone")).isEmpty();
                assertThat(certificates.load("gone")).isEmpty();
                assertThat(certificates.load("kept")).isPresent();
                assertThat(manager.states()).doesNotContainKey("gone");
                assertThat(published).last().isInstanceOfSatisfying(DeviceStateChangedEvent.class, event -> {
                    assertThat(event.deviceId()).isEqualTo("gone");
                    assertThat(event.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
                });
            }
        }
    }

    @Test
    void capabilitiesAreTheUnionOverTheDevicesAdapters() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(AndroidTvSettings.device("shield", "Shield", "127.0.0.1", 6466, null, Instant.now()));

        try (DeviceManager manager = manager(registry, certificates())) {
            assertThat(manager.capabilities("shield")).contains(Capability.REMOTE_KEYS, Capability.APP_LINK);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.device.DeviceManagerTest'`
Expected: compilation failure — `DeviceManager` does not exist; `DeviceStateChangedEvent` has no `deviceId`.

- [ ] **Step 3: Give the event a device id**

`core/DeviceStateChangedEvent.java`:

```java
package dev.andre.homecontrol.core;

/** Published on every state transition of any device; the SSE layer forwards it as-is. */
public record DeviceStateChangedEvent(String deviceId, DeviceState state) {
}
```

Compile fixes only (behavior tests come in Task 7): in `DeviceStateBroadcaster.broadcast` send `.data(event)` instead of `.data(event.state())`; in `DeviceStateBroadcasterTest.event()` use `new DeviceStateChangedEvent("test", DeviceState.initial())`.

- [ ] **Step 4: Write `DeviceManager`**

`device/DeviceManager.java`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DiscoveredDevice;
import dev.andre.homecontrol.core.UnsupportedActionException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one live {@link DeviceHandle} per registered device and adapter, and is the only
 * thing the web layer talks to about devices. Every device gets a handle now that
 * {@link DeviceStateChangedEvent} carries the device id — the v1 "active device only" rule
 * existed solely because it did not.
 */
@Service
public class DeviceManager implements AutoCloseable {

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters = new LinkedHashMap<>();
    private final ApplicationEventPublisher events;
    /** device id → (adapter id → handle), in the device's adapter order. */
    private final Map<String, Map<String, DeviceHandle>> handles = new ConcurrentHashMap<>();

    public DeviceManager(DeviceRegistry registry, List<DeviceAdapter> adapters,
                         ApplicationEventPublisher events) {
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

    /** The most recently paired device: what {@code /} shows when no device is selected. */
    public Optional<Device> defaultDevice() {
        return registry.findAll().stream().max(Comparator.comparing(Device::lastSeen));
    }

    /** The primary adapter's state; an unknown or handle-less device reads as DISCONNECTED. */
    public DeviceState state(String id) {
        Map<String, DeviceHandle> deviceHandles = handles.get(id);
        if (deviceHandles == null || deviceHandles.isEmpty()) {
            return DeviceState.initial();
        }
        return deviceHandles.values().iterator().next().state();
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

    /** Registers a freshly paired device and brings it up. */
    public void adopt(Device device) {
        registry.save(device);
        connect(device);
    }

    public void forget(String id) {
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

    private void connect(Device device) {
        closeHandles(device.id());
        Map<String, DeviceHandle> deviceHandles = new LinkedHashMap<>();
        device.adapters().keySet().forEach(adapterId -> {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter != null) {
                deviceHandles.put(adapterId, adapter.connect(device,
                        state -> events.publishEvent(new DeviceStateChangedEvent(device.id(), state))));
            }
        });
        handles.put(device.id(), deviceHandles);
    }

    private void closeHandles(String id) {
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

`connect()` calls `adapter.connect` before `handles.put`; the adapter's first `onChange` callback publishes an event but nobody reads `handles` from it, so the window is harmless.

- [ ] **Step 5: Replace `DeviceSessionManager` everywhere**

```bash
git rm src/main/java/dev/andre/homecontrol/device/DeviceSessionManager.java src/test/java/dev/andre/homecontrol/device/DeviceSessionManagerTest.java
grep -rl "DeviceSessionManager" src | xargs sed -i 's/DeviceSessionManager/DeviceManager/g'
```

Then by hand:
- `PairingService`: field type is now `DeviceManager`; `adopt` unchanged.
- `RemoteController.key`: `sessions.execute(defaultId(), new Action.PressKey(remoteKey))` where `defaultId()` is `sessions.defaultDevice().map(Device::id).orElseThrow(() -> new DeviceOfflineException("No device is paired"))`. `remote()` uses `sessions.defaultDevice()` and `sessions.state(id)`. (Task 9 deletes this controller.)
- `SetupController.populateSetupModel`: `model.addAttribute("discovered", sessions.discovered()); model.addAttribute("paired", sessions.defaultDevice().orElse(null));` and drop the `MdnsDiscovery` constructor argument.
- `StateController.events()`: send `new DeviceStateChangedEvent(id, sessions.state(id))` for the default device only — Task 7 sends every device.
- Tests: `RemoteControllerTest` stubs `sessions.defaultDevice()` and verifies `sessions.execute("shield", new Action.PressKey(RemoteKey.DPAD_UP))`; `RemotePageTest` and `SetupControllerTest` stub `defaultDevice()`, `state(any())`, `discovered()` and drop the `MdnsDiscovery` mock; `PairingServiceTest` constructs a `DeviceManager` with the Android TV adapter; `DeviceStateStreamEndToEndTest` uses `sessions.state("shield-sse")`.

- [ ] **Step 6: Run the manager tests, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.device.DeviceManagerTest'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: keep a live session for every registered device"
```

---

### Task 7: Multi-device SSE

**Files:**
- Modify: `web/DeviceStateBroadcaster.java`, `web/StateController.java`
- Test: `web/DeviceStateBroadcasterTest.java`, `web/StateControllerTest.java` (new), `web/DeviceStateStreamEndToEndTest.java`

**Interfaces:**
- Consumes: `DeviceManager.states()`, `DeviceStateChangedEvent(deviceId, state)`.
- Produces: SSE event name `state`, JSON body `{"deviceId": "...", "state": {status, powerOn, currentApp, volumeLevel, volumeMax, muted, updatedAt}}`; a new subscriber receives one such event per registered device before anything else.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dev/andre/homecontrol/web/StateControllerTest.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StateController.class)
class StateControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    @Test
    void aNewSubscriberGetsOneStateEventPerDevice() throws Exception {
        Map<String, DeviceState> states = new LinkedHashMap<>();
        states.put("living", new DeviceState(DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.EPOCH));
        states.put("bedroom", DeviceState.unpaired());
        given(devices.states()).willReturn(states);
        given(broadcaster.subscribe()).willReturn(new SseEmitter(0L));

        MvcResult result = mockMvc.perform(get("/events").accept("text/event-stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:state");
        assertThat(body.indexOf("\"deviceId\":\"living\"")).isLessThan(body.indexOf("\"deviceId\":\"bedroom\""));
        assertThat(body).contains("\"currentApp\":\"com.netflix.ninja\"").contains("\"status\":\"UNPAIRED\"");
    }
}
```

In `DeviceStateBroadcaster` make the send a package-private seam the test can override:

```java
    /** The one place an event becomes an SSE frame; package-private so a test can observe the object. */
    void sendData(SseEmitter emitter, DeviceStateChangedEvent event) throws IOException {
        emitter.send(SseEmitter.event().name("state").data(event));
    }
```

and have `broadcast` call `sendData(emitter, event)`. Then add to `DeviceStateBroadcasterTest`:

```java
    @Test
    void forwardsTheDeviceIdWithTheState() {
        List<DeviceStateChangedEvent> sent = new CopyOnWriteArrayList<>();
        DeviceStateBroadcaster recording = new DeviceStateBroadcaster() {
            @Override
            void sendData(SseEmitter emitter, DeviceStateChangedEvent event) {
                sent.add(event);
            }
        };
        recording.register(new SseEmitter(0L));

        recording.onStateChanged(new DeviceStateChangedEvent("bedroom", DeviceState.unpaired()));

        await().until(() -> !sent.isEmpty());
        assertThat(sent.getFirst().deviceId()).isEqualTo("bedroom");
        assertThat(sent.getFirst().state().status()).isEqualTo(DeviceStatus.UNPAIRED);
        recording.shutdown();
    }
```

Update `DeviceStateStreamEndToEndTest`: adopt two fake devices (`shield-a`, `shield-b`), await both `CONNECTED`, open `/events`, assert the first two `data:` lines contain `"deviceId":"shield-a"` and `"deviceId":"shield-b"` in either order, then `fakeB.pushVolume(12, 100, true)` and assert a later line contains both `"deviceId":"shield-b"` and `"volumeLevel":12`. Forget both in `finally`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'dev.andre.homecontrol.web.StateControllerTest' --tests 'dev.andre.homecontrol.web.DeviceStateBroadcasterTest' --tests 'dev.andre.homecontrol.web.DeviceStateStreamEndToEndTest'`
Expected: FAIL — `/events` sends one event for the default device only.

- [ ] **Step 3: Implement**

`StateController.events()`:

```java
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() throws IOException {
        SseEmitter emitter = broadcaster.subscribe();
        try {
            // One snapshot per device so a new tab paints every chip before anything changes.
            for (Map.Entry<String, DeviceState> entry : devices.states().entrySet()) {
                emitter.send(SseEmitter.event().name("state")
                        .data(new DeviceStateChangedEvent(entry.getKey(), entry.getValue())));
            }
        } catch (IOException e) {
            broadcaster.unsubscribe(emitter);
            throw e;
        }
        return emitter;
    }
```

`DeviceStateBroadcaster.broadcast` now routes through `sendData` (Step 1); the wire format is asserted end to end.

- [ ] **Step 4: Run the web tests, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.web.*'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: stream state for every device with its id"
```

---

### Task 8: Playback domain and planner skeleton

**Files:**
- Create: `core/playback/PlayableRef.java`, `ContentKind.java`, `ContentItem.java`, `Route.java`, `RouteStrategy.java`, `AppLinkStrategy.java`, `PlaybackPlanner.java`, `UnroutableException.java`, `AppLinks.java`; `playback/PlaybackService.java`
- Test: `core/playback/PlaybackPlannerTest.java`, `core/playback/AppLinksTest.java`, `playback/PlaybackServiceTest.java`

**Interfaces:**
- Consumes: `Capability`, `Action.OpenAppLink`, `DeviceManager.capabilities/execute/device`.
- Produces: `PlayableRef` (`AppLink(URI uri, String service)`, `CastLoad(String receiverAppId, Map<String,Object> payload)`, `JellyfinItem(String serverId, String itemId, long resumeTicks)`, `StreamUrl(URI url, String mimeType)`), `ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle, URI artwork, List<PlayableRef> playables)`, `Route` (`OpenAppLink(URI uri, String service)` with `action()`, `Unroutable(String reason)`), `String Route.describe()`, `RouteStrategy { Optional<Route> route(ContentItem, Set<Capability>); }`, `PlaybackPlanner(List<RouteStrategy>)` with `Route plan(ContentItem, Set<Capability>)`, `PlaybackService.play(ContentItem, String deviceId) → Route`, `AppLinks.fromUrl(String) → ContentItem`.

- [ ] **Step 1: Write the failing planner test**

`src/test/java/dev/andre/homecontrol/core/playback/PlaybackPlannerTest.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackPlannerTest {

    private final PlaybackPlanner planner = new PlaybackPlanner(List.of(new AppLinkStrategy()));

    private static ContentItem item(PlayableRef... playables) {
        return new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(playables));
    }

    @Test
    void routesAnAppLinkToADeviceThatCanOpenAppLinks() {
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");

        Route route = planner.plan(item(new PlayableRef.AppLink(uri, "youtube")),
                EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK));

        assertThat(route).isEqualTo(new Route.OpenAppLink(uri, "youtube"));
        assertThat(route.describe()).isEqualTo("Open in the YouTube app");
    }

    @Test
    void explainsWhyAnAppLinkCannotReachADeviceWithoutTheCapability() {
        Route route = planner.plan(item(new PlayableRef.AppLink(URI.create("https://x"), "web")),
                EnumSet.of(Capability.MEDIA_RENDERER));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class,
                unroutable -> assertThat(unroutable.reason()).contains("cannot open app links"));
    }

    @Test
    void explainsThatOtherReferenceKindsHaveNoRouteYet() {
        Route route = planner.plan(item(
                        new PlayableRef.CastLoad("CC1AD845", Map.of()),
                        new PlayableRef.JellyfinItem("srv", "item", 0),
                        new PlayableRef.StreamUrl(URI.create("http://nas/a.mp4"), "video/mp4")),
                EnumSet.allOf(Capability.class));

        assertThat(route).isInstanceOfSatisfying(Route.Unroutable.class, unroutable ->
                assertThat(unroutable.reason())
                        .contains("cast").contains("Jellyfin").contains("stream")
                        .contains("not supported yet"));
    }

    @Test
    void anItemWithNothingPlayableIsUnroutable() {
        Route route = planner.plan(item(), Set.of(Capability.APP_LINK));

        assertThat(route).isEqualTo(new Route.Unroutable("This item has nothing playable"));
    }

    @Test
    void theFirstStrategyThatRoutesWins() {
        RouteStrategy never = (item, caps) -> java.util.Optional.empty();
        PlaybackPlanner ordered = new PlaybackPlanner(List.of(never, new AppLinkStrategy()));
        URI uri = URI.create("https://www.netflix.com/title/1");

        assertThat(ordered.plan(item(new PlayableRef.AppLink(uri, "netflix")), Set.of(Capability.APP_LINK)))
                .isEqualTo(new Route.OpenAppLink(uri, "netflix"));
    }
}
```

`src/test/java/dev/andre/homecontrol/core/playback/AppLinksTest.java`:

```java
package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppLinksTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=abc, youtube",
            "https://youtu.be/abc, youtube",
            "https://m.youtube.com/watch?v=abc, youtube",
            "https://www.netflix.com/title/80057281, netflix",
            "https://app.primevideo.com/detail?gti=amzn1.dv.gti.1, primevideo",
            "https://www.amazon.de/gp/video/detail/B08XYZ, primevideo",
            "https://www.dazn.com/de-DE/home, dazn",
            "https://example.org/anything, web",
    })
    void detectsTheServiceFromTheHost(String url, String service) {
        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.playables()).singleElement()
                .isEqualTo(new PlayableRef.AppLink(URI.create(url), service));
        assertThat(item.sourceId()).isEqualTo("manual");
        assertThat(item.kind()).isEqualTo(ContentKind.VIDEO);
        assertThat(item.title()).isEqualTo(URI.create(url).getHost());
    }

    @Test
    void rejectsAnythingThatIsNotAnHttpUrl() {
        assertThatThrownBy(() -> AppLinks.fromUrl("ftp://x/y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppLinks.fromUrl("not a url")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppLinks.fromUrl("")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'dev.andre.homecontrol.core.playback.*'`
Expected: compilation failure — none of the types exist.

- [ ] **Step 3: Write the playback types**

`core/playback/PlayableRef.java`:

```java
package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.Map;

/**
 * One way an item could be played. A source attaches every reference it can build; the
 * planner picks. Variants beyond {@link AppLink} are defined here so later sub-projects
 * conform to one contract, but only app links route in sub-project A (spec §5.2).
 */
public sealed interface PlayableRef {

    /** Human word for the reference kind, used in "no route" explanations. */
    String kindLabel();

    /** A URI the device should hand to whichever app claims it. {@code service} is a lower-case key such as {@code youtube}. */
    record AppLink(URI uri, String service) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "app link";
        }
    }

    record CastLoad(String receiverAppId, Map<String, Object> payload) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "cast";
        }
    }

    record JellyfinItem(String serverId, String itemId, long resumeTicks) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "Jellyfin";
        }
    }

    record StreamUrl(URI url, String mimeType) implements PlayableRef {
        @Override
        public String kindLabel() {
            return "direct stream";
        }
    }
}
```

`core/playback/ContentKind.java`:

```java
package dev.andre.homecontrol.core.playback;

public enum ContentKind {
    MOVIE, EPISODE, VIDEO, LIVE_EVENT, TRACK, APP
}
```

`core/playback/ContentItem.java`:

```java
package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.util.List;

/** What a content source produces and a rail shows. {@code subtitle} and {@code artwork} may be null. */
public record ContentItem(String id, String sourceId, ContentKind kind, String title, String subtitle,
                          URI artwork, List<PlayableRef> playables) {

    public ContentItem {
        playables = playables == null ? List.of() : List.copyOf(playables);
    }
}
```

`core/playback/Route.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;

import java.net.URI;
import java.util.Map;

/** The planner's answer: an executable route, or the reason there is none. Shown to the user before playing. */
public sealed interface Route {

    String describe();

    record OpenAppLink(URI uri, String service) implements Route {

        private static final Map<String, String> SERVICE_NAMES = Map.of(
                "youtube", "YouTube", "netflix", "Netflix", "primevideo", "Prime Video",
                "dazn", "DAZN", "jellyfin", "Jellyfin");

        public Action action() {
            return new Action.OpenAppLink(uri);
        }

        @Override
        public String describe() {
            String name = SERVICE_NAMES.get(service);
            return name == null ? "Open " + uri.getHost() + " on the device" : "Open in the " + name + " app";
        }
    }

    record Unroutable(String reason) implements Route {
        @Override
        public String describe() {
            return reason;
        }
    }
}
```

`core/playback/RouteStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** One rung of the preference ladder in spec §5.3. Strategies are tried in list order. */
@FunctionalInterface
public interface RouteStrategy {
    Optional<Route> route(ContentItem item, Set<Capability> capabilities);
}
```

`core/playback/AppLinkStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Optional;
import java.util.Set;

/** Rung 2 of spec §5.3: the device can open app links and the item has one. */
public class AppLinkStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.APP_LINK)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.AppLink.class::isInstance)
                .map(PlayableRef.AppLink.class::cast)
                .findFirst()
                .map(link -> new Route.OpenAppLink(link.uri(), link.service()));
    }
}
```

`core/playback/PlaybackPlanner.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Matches an item's playable references to a device's capabilities. Pure: no I/O, no
 * device state, so the capability × reference matrix is unit-testable (spec §12).
 */
public class PlaybackPlanner {

    private final List<RouteStrategy> strategies;

    public PlaybackPlanner(List<RouteStrategy> strategies) {
        this.strategies = List.copyOf(strategies);
    }

    public Route plan(ContentItem item, Set<Capability> capabilities) {
        if (item.playables().isEmpty()) {
            return new Route.Unroutable("This item has nothing playable");
        }
        for (RouteStrategy strategy : strategies) {
            Optional<Route> route = strategy.route(item, capabilities);
            if (route.isPresent()) {
                return route.get();
            }
        }
        return new Route.Unroutable(String.join("; ", explain(item, capabilities)));
    }

    private static List<String> explain(ContentItem item, Set<Capability> capabilities) {
        Set<String> reasons = new LinkedHashSet<>();
        for (PlayableRef ref : item.playables()) {
            switch (ref) {
                case PlayableRef.AppLink ignored -> reasons.add(capabilities.contains(Capability.APP_LINK)
                        ? "the app link was not accepted" : "this device cannot open app links");
                case PlayableRef.CastLoad ignored -> reasons.add("cast playback is not supported yet");
                case PlayableRef.JellyfinItem ignored -> reasons.add("Jellyfin playback is not supported yet");
                case PlayableRef.StreamUrl ignored -> reasons.add("direct stream playback is not supported yet");
            }
        }
        return new ArrayList<>(reasons);
    }
}
```

`core/playback/UnroutableException.java`:

```java
package dev.andre.homecontrol.core.playback;

/** Nothing on the item can reach the device. HTTP 422; the message is user-facing. */
public class UnroutableException extends RuntimeException {
    public UnroutableException(String message) {
        super(message);
    }
}
```

`core/playback/AppLinks.java`:

```java
package dev.andre.homecontrol.core.playback;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Turns a pasted URL into an ad-hoc {@link ContentItem} with one {@link PlayableRef.AppLink}. */
public final class AppLinks {

    private AppLinks() {
    }

    public static ContentItem fromUrl(String url) {
        URI uri = parse(url);
        String service = serviceOf(uri.getHost().toLowerCase(Locale.ROOT), uri.getPath());
        return new ContentItem("link:" + uri, "manual", ContentKind.VIDEO, uri.getHost(), null, null,
                List.of(new PlayableRef.AppLink(uri, service)));
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Enter a link to open");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That is not a valid link", e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http and https links can be opened on a device");
        }
        return uri;
    }

    /** Host-based detection; the service key drives the route description and later per-platform link builders. */
    static String serviceOf(String host, String path) {
        if (host.endsWith("youtube.com") || host.equals("youtu.be")) {
            return "youtube";
        }
        if (host.endsWith("netflix.com")) {
            return "netflix";
        }
        if (host.endsWith("primevideo.com") || (host.contains("amazon.") && path != null && path.contains("/video/"))) {
            return "primevideo";
        }
        if (host.endsWith("dazn.com")) {
            return "dazn";
        }
        return "web";
    }
}
```

- [ ] **Step 4: Run the planner tests**

Run: `./gradlew test --tests 'dev.andre.homecontrol.core.playback.*'`
Expected: PASS.

- [ ] **Step 5: Write the failing service test**

`src/test/java/dev/andre/homecontrol/playback/PlaybackServiceTest.java`:

```java
package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.playback.AppLinkStrategy;
import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

class PlaybackServiceTest {

    private final DeviceManager devices = mock(DeviceManager.class);
    private final PlaybackService service = new PlaybackService(devices,
            new PlaybackPlanner(List.of(new AppLinkStrategy())));
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());

    @Test
    void plansAndExecutesTheRoute() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");

        Route route = service.play(AppLinks.fromUrl(uri.toString()), "shield");

        assertThat(route).isEqualTo(new Route.OpenAppLink(uri, "youtube"));
        verify(devices).execute("shield", new Action.OpenAppLink(uri));
    }

    @Test
    void namesTheDeviceWhenNothingRoutes() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.noneOf(Capability.class));

        assertThatThrownBy(() -> service.play(AppLinks.fromUrl("https://example.org/a"), "shield"))
                .isInstanceOf(UnroutableException.class)
                .hasMessageContaining("Shield")
                .hasMessageContaining("cannot open app links");
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void anUnknownDeviceIsOffline() {
        given(devices.device("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.play(AppLinks.fromUrl("https://example.org/a"), "ghost"))
                .isInstanceOf(DeviceOfflineException.class);
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.playback.PlaybackServiceTest'`
Expected: compilation failure — `PlaybackService` does not exist.

- [ ] **Step 7: Write the service and register the planner bean**

`playback/PlaybackService.java`:

```java
package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.stereotype.Service;

/** Plan, then execute, then report. Commands are ephemeral: a failure here is final. */
@Service
public class PlaybackService {

    private final DeviceManager devices;
    private final PlaybackPlanner planner;

    public PlaybackService(DeviceManager devices, PlaybackPlanner planner) {
        this.devices = devices;
        this.planner = planner;
    }

    public Route play(ContentItem item, String deviceId) {
        Device device = devices.device(deviceId)
                .orElseThrow(() -> new DeviceOfflineException("No device with id " + deviceId));
        Route route = planner.plan(item, devices.capabilities(deviceId));
        switch (route) {
            case Route.OpenAppLink open -> devices.execute(deviceId, open.action());
            case Route.Unroutable unroutable -> throw new UnroutableException(
                    device.name() + ": " + unroutable.reason());
        }
        return route;
    }
}
```

In `HomeControlConfiguration` add:

```java
    @Bean
    public PlaybackPlanner playbackPlanner() {
        // Strategy order is the preference order of spec §5.3; later sub-projects insert theirs.
        return new PlaybackPlanner(List.of(new AppLinkStrategy()));
    }
```

- [ ] **Step 8: Run the service test, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.playback.PlaybackServiceTest'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: playback planner with an app-link route"
```

---

### Task 9: Per-device web endpoints and the dashboard shell

**Files:**
- Create: `web/DeviceController.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`
- Delete: `web/RemoteController.java`, `src/main/resources/templates/remote.html`, `web/RemoteControllerTest.java`, `web/RemotePageTest.java`
- Modify: `web/SetupController.java`, `src/main/resources/templates/setup.html`
- Test: `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`, `web/SetupControllerTest.java`

**Interfaces:**
- Consumes: `DeviceManager`, `PlaybackService`, `AppLinks`.
- Produces: `POST /devices/{id}/key/{key}` → 204 / 400 unknown key / 404 unknown device / 409 offline / 422 unsupported; `POST /devices/{id}/play` (form field `uri`) → 200 `text/plain` route description / 400 bad link / 404 / 409 / 422 reason; `GET /` → `dashboard` view or redirect to `/setup` when nothing is paired; `GET /remote/{id}` → redirect `/?device={id}`; setup page lists every paired device.

- [ ] **Step 1: Write the failing controller tests**

`src/test/java/dev/andre/homecontrol/web/DeviceControllerTest.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
class DeviceControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    PlaybackService playback;

    @BeforeEach
    void aPairedShield() {
        given(devices.device("shield")).willReturn(Optional.of(new Device("shield", "Shield",
                DeviceKind.ANDROID_TV, "10.0.0.5", Map.of("androidtv", Map.of()), Instant.now())));
        given(devices.device("ghost")).willReturn(Optional.empty());
    }

    @Test
    void sendsAKeyToTheAddressedDevice() throws Exception {
        mockMvc.perform(post("/devices/shield/key/DPAD_UP")).andExpect(status().isNoContent());

        verify(devices).execute("shield", new Action.PressKey(RemoteKey.DPAD_UP));
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mockMvc.perform(post("/devices/shield/key/EJECT_TAPE")).andExpect(status().isBadRequest());
    }

    @Test
    void reportsAnUnknownDeviceAsNotFound() throws Exception {
        mockMvc.perform(post("/devices/ghost/key/HOME")).andExpect(status().isNotFound());
    }

    @Test
    void reportsConflictWhenTheDeviceIsOffline() throws Exception {
        willThrow(new DeviceOfflineException("offline")).given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/key/HOME"))
                .andExpect(status().isConflict())
                .andExpect(content().string("offline"));
    }

    @Test
    void reportsUnprocessableWhenTheDeviceCannotDoThat() throws Exception {
        willThrow(new UnsupportedActionException("Shield cannot perform that")).given(devices).execute(eq("shield"), any());

        mockMvc.perform(post("/devices/shield/key/HOME")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void playsAPastedLinkAndDescribesTheRoute() throws Exception {
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");
        given(playback.play(any(), eq("shield"))).willReturn(new Route.OpenAppLink(uri, "youtube"));

        mockMvc.perform(post("/devices/shield/play").param("uri", uri.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Open in the YouTube app"));
    }

    @Test
    void rejectsALinkThatIsNotHttp() throws Exception {
        mockMvc.perform(post("/devices/shield/play").param("uri", "ftp://nope"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Only http and https links can be opened on a device"));
    }

    @Test
    void explainsWhyALinkCannotBePlayed() throws Exception {
        given(playback.play(any(), eq("shield")))
                .willThrow(new UnroutableException("Shield: this device cannot open app links"));

        mockMvc.perform(post("/devices/shield/play").param("uri", "https://example.org/a"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string("Shield: this device cannot open app links"));
    }
}
```

(`status().isUnprocessableContent()` is the Spring 7 name for 422; if the local Spring version only has `isUnprocessableEntity()`, use that.)

`src/test/java/dev/andre/homecontrol/web/DashboardPageTest.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
class DashboardPageTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    PlaybackService playback;

    private static Device device(String id, String name, Instant lastSeen) {
        return new Device(id, name, DeviceKind.ANDROID_TV, "10.0.0." + id.length(),
                Map.of("androidtv", Map.of()), lastSeen);
    }

    @Test
    void redirectsToSetupWhenNothingIsPaired() throws Exception {
        given(devices.devices()).willReturn(List.of());

        mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup"));
    }

    @Test
    void showsEveryDeviceAndTheRemoteForTheSelectedOne() throws Exception {
        Device living = device("living", "Living Room", Instant.parse("2026-09-01T00:00:00Z"));
        Device bedroom = device("bedroom", "Bedroom", Instant.parse("2026-09-02T00:00:00Z"));
        given(devices.devices()).willReturn(List.of(bedroom, living));
        given(devices.device("living")).willReturn(Optional.of(living));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.state("living")).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.now()));
        given(devices.state("bedroom")).willReturn(DeviceState.unpaired());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS, Capability.APP_LINK));

        mockMvc.perform(get("/").param("device", "living"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"status-living\"")))
                .andExpect(content().string(containsString("id=\"status-bedroom\"")))
                .andExpect(content().string(containsString("/devices/living/key/DPAD_UP")))
                .andExpect(content().string(not(containsString("/devices/bedroom/key/"))))
                .andExpect(content().string(containsString("com.netflix.ninja")))
                .andExpect(content().string(containsString("/devices/living/play")))
                .andExpect(content().string(containsString("data-device=\"living\"")));
    }

    @Test
    void fallsBackToTheDefaultDeviceWhenNoneIsSelected() throws Exception {
        Device bedroom = device("bedroom", "Bedroom", Instant.now());
        given(devices.devices()).willReturn(List.of(bedroom));
        given(devices.defaultDevice()).willReturn(Optional.of(bedroom));
        given(devices.device("bedroom")).willReturn(Optional.of(bedroom));
        given(devices.state(any())).willReturn(DeviceState.initial());
        given(devices.capabilities(any())).willReturn(EnumSet.of(Capability.REMOTE_KEYS));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/devices/bedroom/key/HOME")))
                .andExpect(content().string(not(containsString("/devices/bedroom/play"))));
    }

    @Test
    void theClassicRemotePathRedirectsToTheDashboard() throws Exception {
        mockMvc.perform(get("/remote/living"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?device=living"));
    }
}
```

Update `SetupControllerTest` (and its second, page-rendering case moved here from `RemotePageTest`): stub `devices.devices()` with two devices and assert both names and two `name="id"` hidden inputs for forget appear; stub `devices.discovered()` with `new DiscoveredDevice("androidtv", "Living Room Shield", "192.168.1.50", 6466)`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'dev.andre.homecontrol.web.*'`
Expected: compilation failure — `DeviceController`, `DashboardController` missing.

- [ ] **Step 3: Write the controllers**

`web/DeviceController.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/** Commands addressed to one device. Every failure is a plain-text reason the UI can toast. */
@RestController
public class DeviceController {

    private final DeviceManager devices;
    private final PlaybackService playback;

    public DeviceController(DeviceManager devices, PlaybackService playback) {
        this.devices = devices;
        this.playback = playback;
    }

    @PostMapping("/devices/{id}/key/{key}")
    public ResponseEntity<String> key(@PathVariable String id, @PathVariable String key) {
        RemoteKey remoteKey;
        try {
            remoteKey = RemoteKey.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return text(HttpStatus.BAD_REQUEST, "Unknown key " + key);
        }
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        devices.execute(id, new Action.PressKey(remoteKey));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/devices/{id}/play", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> play(@PathVariable String id, @RequestParam String uri) {
        if (devices.device(id).isEmpty()) {
            return text(HttpStatus.NOT_FOUND, "No device with id " + id);
        }
        Route route = playback.play(AppLinks.fromUrl(uri), id);
        return text(HttpStatus.OK, route.describe());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badLink(IllegalArgumentException e) {
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

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }
}
```

(`HttpStatus.UNPROCESSABLE_CONTENT` is the Spring 7 constant; use `UNPROCESSABLE_ENTITY` if the compiler objects.)

`web/DashboardController.java`:

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Optional;

/** The device strip plus the remote drawer of the selected device (spec §6.1). Rails arrive in sub-project D. */
@Controller
public class DashboardController {

    private final DeviceManager devices;

    public DashboardController(DeviceManager devices) {
        this.devices = devices;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(name = "device", required = false) String deviceId, Model model) {
        List<Device> all = devices.devices();
        if (all.isEmpty()) {
            return "redirect:/setup";
        }
        Optional<Device> selected = Optional.ofNullable(deviceId).flatMap(devices::device)
                .or(devices::defaultDevice)
                .or(() -> Optional.of(all.getFirst()));
        Device device = selected.get();
        model.addAttribute("devices", all);
        model.addAttribute("states", devices.states());
        model.addAttribute("selected", device);
        model.addAttribute("selectedState", devices.state(device.id()));
        model.addAttribute("canOpenLinks", devices.capabilities(device.id()).contains(Capability.APP_LINK));
        return "dashboard";
    }

    @GetMapping("/remote/{id}")
    public String remote(@PathVariable String id) {
        return "redirect:/?device=" + id;
    }
}
```

- [ ] **Step 4: Write the dashboard template and update setup**

`src/main/resources/templates/dashboard.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <title>Home Control</title>
    <link rel="stylesheet" th:href="@{/app.css}">
    <script th:src="@{/vendor/htmx.min.js}" defer></script>
    <script type="module" th:src="@{/js/app.js}"></script>
</head>
<body th:attr="data-device=${selected.id()}">
<header class="strip">
    <nav class="devices" aria-label="Devices">
        <a th:each="device : ${devices}" class="chip"
           th:classappend="${device.id() == selected.id()} ? 'selected'"
           th:href="@{/(device=${device.id()})}" th:attr="data-device=${device.id()}">
            <span class="name" th:text="${device.name()}">Shield</span>
            <span class="badge" th:id="'status-' + ${device.id()}"
                  th:classappend="${states[device.id()].connected()} ? 'ok' : 'off'"
                  th:text="${states[device.id()].status()}">DISCONNECTED</span>
            <span class="app" th:id="'app-' + ${device.id()}"
                  th:text="${states[device.id()].currentApp()} ?: 'Nothing playing'">Nothing playing</span>
        </a>
    </nav>
    <a th:href="@{/setup}">Setup</a>
</header>

<main hx-swap="none" th:with="id=${selected.id()}">
    <h1 th:text="${selected.name()}">Shield</h1>
    <p class="meta">
        <span th:id="'vol-' + ${id}" th:text="${selectedState.muted()} ? 'muted' : ('vol ' + ${selectedState.volumeLevel()})">vol</span>
    </p>

    <section class="dpad">
        <button th:attr="hx-post=@{/devices/{id}/key/DPAD_UP(id=${id})}" class="up" aria-label="Up">▲</button>
        <button th:attr="hx-post=@{/devices/{id}/key/DPAD_LEFT(id=${id})}" class="left" aria-label="Left">◀</button>
        <button th:attr="hx-post=@{/devices/{id}/key/DPAD_CENTER(id=${id})}" class="ok">OK</button>
        <button th:attr="hx-post=@{/devices/{id}/key/DPAD_RIGHT(id=${id})}" class="right" aria-label="Right">▶</button>
        <button th:attr="hx-post=@{/devices/{id}/key/DPAD_DOWN(id=${id})}" class="down" aria-label="Down">▼</button>
    </section>

    <section class="row">
        <button th:attr="hx-post=@{/devices/{id}/key/BACK(id=${id})}">Back</button>
        <button th:attr="hx-post=@{/devices/{id}/key/HOME(id=${id})}">Home</button>
        <button th:attr="hx-post=@{/devices/{id}/key/MENU(id=${id})}">Menu</button>
        <button th:attr="hx-post=@{/devices/{id}/key/POWER(id=${id})}">Power</button>
    </section>

    <section class="row">
        <button th:attr="hx-post=@{/devices/{id}/key/MEDIA_PREVIOUS(id=${id})}" aria-label="Previous">⏮</button>
        <button th:attr="hx-post=@{/devices/{id}/key/REWIND(id=${id})}" aria-label="Rewind">⏪</button>
        <button th:attr="hx-post=@{/devices/{id}/key/PLAY_PAUSE(id=${id})}" aria-label="Play or pause">⏯</button>
        <button th:attr="hx-post=@{/devices/{id}/key/FAST_FORWARD(id=${id})}" aria-label="Fast forward">⏩</button>
        <button th:attr="hx-post=@{/devices/{id}/key/MEDIA_NEXT(id=${id})}" aria-label="Next">⏭</button>
    </section>

    <section class="row">
        <button th:attr="hx-post=@{/devices/{id}/key/VOLUME_DOWN(id=${id})}">Vol −</button>
        <button th:attr="hx-post=@{/devices/{id}/key/VOLUME_MUTE(id=${id})}">Mute</button>
        <button th:attr="hx-post=@{/devices/{id}/key/VOLUME_UP(id=${id})}">Vol +</button>
    </section>

    <section class="open-link" th:if="${canOpenLinks}">
        <h2>Open a link on this device</h2>
        <form th:attr="hx-post=@{/devices/{id}/play(id=${id})}" hx-target="#open-result" hx-swap="innerHTML">
            <input name="uri" type="url" inputmode="url" placeholder="https://www.youtube.com/watch?v=…" required>
            <button type="submit">Play</button>
        </form>
        <p id="open-result" class="hint" aria-live="polite"></p>
        <p class="hint">The device opens whichever app claims the link. If nothing happens, that app may not be installed.</p>
    </section>
</main>

<div id="toast" hidden></div>
</body>
</html>
```

`setup.html`: replace the single `paired` section with a list:

```html
    <section th:if="${!#lists.isEmpty(paired)}">
        <h2>Paired devices</h2>
        <ul>
            <li th:each="device : ${paired}">
                <form method="post" th:action="@{/setup/forget}">
                    <input type="hidden" name="id" th:value="${device.id()}">
                    <span th:text="${device.name()} + ' at ' + ${device.host()}">Shield</span>
                    <button type="submit">Forget</button>
                </form>
            </li>
        </ul>
        <p class="hint">
            Forgetting removes the stored pairing credential. You will need to pair again
            before that device can be controlled.
        </p>
    </section>
```

and change `SetupController.populateSetupModel` to `model.addAttribute("paired", devices.devices());`. The discovered-devices list shows `device.name()` and `device.host()` unchanged. Add `<a th:href="@{/}">← Home</a>` in place of `← Remote`.

Delete `RemoteController`, `remote.html`, `RemoteControllerTest`, `RemotePageTest`:

```bash
git rm src/main/java/dev/andre/homecontrol/web/RemoteController.java src/main/resources/templates/remote.html \
       src/test/java/dev/andre/homecontrol/web/RemoteControllerTest.java src/test/java/dev/andre/homecontrol/web/RemotePageTest.java
```

- [ ] **Step 5: Run the web tests, then the build**

Run: `./gradlew test --tests 'dev.andre.homecontrol.web.*'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: dashboard shell with a device strip and per-device remote"
```

---

### Task 10: Browser modules

**Files:**
- Create: `src/main/resources/static/js/state-view.js`, `src/main/resources/static/js/remote-transport.js`, `src/main/resources/static/js/app.js`
- Delete: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/app.css`
- Test: `web/StaticAssetsTest.java` (new)

**Interfaces:**
- Consumes: SSE `state` events `{deviceId, state}`; `document.body.dataset.device`; endpoints from Task 9.
- Produces: `applyState(deviceId, state)`; `sendKey(deviceId, key)`; `openLink(deviceId, uri)`; toasts from `htmx:responseError` showing the server's plain-text reason.

- [ ] **Step 1: Write the failing asset test**

`src/test/java/dev/andre/homecontrol/web/StaticAssetsTest.java`:

```java
package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shield.data-dir=build/tmp/static-assets-test")
@AutoConfigureMockMvc
class StaticAssetsTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void servesTheEsModulesTheDashboardLoads() throws Exception {
        mockMvc.perform(get("/js/app.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("import")));
        mockMvc.perform(get("/js/state-view.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function applyState")));
        mockMvc.perform(get("/js/remote-transport.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("export function sendKey")));
        mockMvc.perform(get("/app.js")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'dev.andre.homecontrol.web.StaticAssetsTest'`
Expected: FAIL — `/js/app.js` is 404.

- [ ] **Step 3: Write the modules**

`static/js/state-view.js`:

```js
// Paints one device's live state into whichever elements the page has for it. The device
// strip has a badge and app label per device; the drawer additionally has a volume label
// for the selected device. Missing elements are skipped so the same module serves both.
export function applyState(deviceId, state) {
    const status = document.getElementById(`status-${deviceId}`);
    if (status) {
        status.textContent = state.status;
        status.classList.toggle("ok", state.status === "CONNECTED");
        status.classList.toggle("off", state.status !== "CONNECTED");
    }
    const app = document.getElementById(`app-${deviceId}`);
    if (app) app.textContent = state.currentApp || "Nothing playing";
    const volume = document.getElementById(`vol-${deviceId}`);
    if (volume) volume.textContent = state.muted ? "muted" : `vol ${state.volumeLevel}`;
}

export function subscribe(onState) {
    const source = new EventSource("/events");
    source.addEventListener("state", (event) => {
        const { deviceId, state } = JSON.parse(event.data);
        onState(deviceId, state);
    });
    return source;
}
```

`static/js/remote-transport.js`:

```js
// Every command names its device. A non-2xx reply carries a plain-text reason from the
// server; callers surface it, never retry, never queue (commands are ephemeral).
async function post(url, body) {
    const response = await fetch(url, { method: "POST", body });
    if (!response.ok) {
        throw new Error((await response.text()) || "The device is not connected");
    }
    return response;
}

export function sendKey(deviceId, key) {
    return post(`/devices/${encodeURIComponent(deviceId)}/key/${key}`);
}

export async function openLink(deviceId, uri) {
    const form = new URLSearchParams({ uri });
    const response = await post(`/devices/${encodeURIComponent(deviceId)}/play`, form);
    return response.text();
}
```

`static/js/app.js`:

```js
import { applyState, subscribe } from "./state-view.js";
import { sendKey } from "./remote-transport.js";

const selectedDevice = () => document.body.dataset.device;

subscribe(applyState);

function toast(message) {
    const el = document.getElementById("toast");
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => (el.hidden = true), 3000);
}

// htmx drives the buttons and the open-link form; failures carry the server's reason.
document.body.addEventListener("htmx:responseError", (event) => {
    toast(event.detail.xhr.responseText || "The device is not connected");
});
document.body.addEventListener("htmx:sendError", () => toast("Cannot reach the server"));

// Keyboard control for desktop use, always aimed at the selected device.
const KEYS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT",
    ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", Backspace: "BACK",
    " ": "PLAY_PAUSE", h: "HOME", m: "VOLUME_MUTE",
};

document.addEventListener("keydown", (event) => {
    if (event.target.tagName === "INPUT") return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    sendKey(selectedDevice(), key).catch((error) => toast(error.message));
});
```

Remove the old file: `git rm src/main/resources/static/app.js`.

Append to `app.css`:

```css
.strip { flex-wrap: wrap; }
.devices { display: flex; gap: .5rem; overflow-x: auto; flex: 1; }
.chip { display: grid; grid-template-columns: auto auto; gap: .15rem .5rem; align-items: center;
        padding: .5rem .75rem; border-radius: .75rem; background: var(--btn); color: var(--fg);
        text-decoration: none; border: 1px solid #313640; white-space: nowrap; }
.chip.selected { border-color: var(--accent); }
.chip .name { font-weight: 600; }
.chip .app { grid-column: 1 / -1; font-size: .8rem; opacity: .8; overflow: hidden;
             text-overflow: ellipsis; max-width: 12rem; }
main h1 { font-size: 1.25rem; margin: 0 0 .25rem; }
.meta { margin: 0 0 1rem; opacity: .8; }
.open-link form { display: flex; gap: .5rem; }
.open-link input { flex: 1; padding: .7rem; border-radius: .5rem; border: 1px solid #313640;
                   background: #1b1e24; color: var(--fg); }
.hint { font-size: .85rem; opacity: .8; }
```

- [ ] **Step 4: Run the asset test, then the build, then look at it**

Run: `./gradlew test --tests 'dev.andre.homecontrol.web.StaticAssetsTest'` then `./gradlew build`
Expected: PASS; BUILD SUCCESSFUL.

Then `./gradlew bootRun --args='--shield.data-dir=build/devdata'`, open `http://localhost:8080`, confirm: redirect to setup when empty; after pairing, the chip shows the Shield with a live badge; the D-pad works; pasting a YouTube URL into the open-link form starts the video on the Shield and the badge's app label changes to `com.google.android.youtube.tv`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: browser modules for per-device state, commands and open-link"
```

---

### Task 11: Documentation and acceptance

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/reviews/2026-XX-XX-multi-device-core-acceptance.md` (date of the acceptance run)

- [ ] **Step 1: Update the README**

Retitle to "Home Control" with the first paragraph: "A small Spring Boot web app that controls the devices on your home network from one page. Today: NVIDIA Shield and other Android TV devices — a browser remote per device with live state, and an open-link form that starts a YouTube, Netflix or Prime Video link in the matching app on the TV." Under **Pairing**, say pairing can be repeated for several devices and each appears in the device strip. Add a section:

```markdown
## Opening links on a device

Paste an `https://` link into the **Open a link on this device** box and press Play. The
device opens whichever app claims the link — for example a YouTube watch URL starts in the
YouTube app. The app cannot tell whether the target app is installed: if nothing happens on
the TV, install the app or open the link another way.
```

Under **Configuration** note that `devices.json` is upgraded in place on first start and that the upgrade keeps existing pairings. Remove the sentence saying the remote "intentionally does not offer app shortcuts"; replace it with a pointer to the open-link section.

- [ ] **Step 2: Manual acceptance on the real Shield**

Record in the review file, one line each with the result:

1. Start the new image against a copy of a v0.3 `data/` directory: no re-pairing needed; the Shield connects.
2. Pair a second Android TV device (or the same Shield at a second address if only one exists): both chips show live state; keys go to the selected chip only.
3. Kill the network to one device: only its badge goes DISCONNECTED; the other keeps working.
4. Open a YouTube watch URL, a Netflix title URL, and a `https://example.org` URL: the first two open in their apps, the third produces "Open example.org on the device" and the TV either shows a browser or nothing (documented behaviour).
5. Forget one device: its chip disappears, the other is unaffected, `keystore.p12` still holds the other alias.

- [ ] **Step 3: Full verification**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, every test green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: describe multiple devices and opening links"
```

---

## Final Automated Verification

```bash
./gradlew clean build
docker compose up --build
```

Expected: build green; the container starts against the existing `./data`, logs the migrated device, and the dashboard renders at `http://<host>:8080` with the paired Shield in the strip.

## Out of scope for this plan

Cast, Jellyfin, rails, login, Smart TV adapters, speakers — see the roadmap. `DeviceState.nowPlaying` is added by sub-project B when the first adapter can report it; adding it now would be a field nothing writes.
