# Phase 3E, PR 1: The Frozen Store Reaches Zero — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The 25 frozen violations of `protocolPackagesStandAlone` go, every ArchUnit rule turns strict, and the
freezing machinery is removed.

**Architecture:** Three changes clear the store: Android TV's `RemoteConnection` takes the protobuf key code and
direction it writes (an `AndroidTvKeys` map in the adapter replaces the Android numbers in `core.RemoteKey`); Cast's
`MediaStatus` reports its own `PlayerState`, which `CastSession` maps; the SSDP wire helpers move into a protocol
package of their own, `discovery.ssdp.protocol`. Then the five frozen rules lose `freeze(...)`, and the store, its
settings, its Gradle input, its CI check, `CycleViolations` and the docs about freezing go.

**Tech Stack:** Java 25, Spring Boot 4.1.1, ArchUnit 1.5.1, protobuf-generated Remote v2 messages, JUnit 5, AssertJ.
Build with `scripts/gradle.sh` (Gradle in the `gradle:jdk25` image; `-q`, silent on success).

**Spec:** `docs/superpowers/specs/2026-10-01-phase-3e-adapter-layering-design.md`, section 1 and the PR 1 lines of
Testing and Delivery. Branch `refactor/adapter-layering` from main `8a30363`; the spec is commit `283803d`.

## Global Constraints

- Protocol packages (`..protocol..`) depend on neither Spring nor any application package other than `adapters.net`
  and other protocol packages. After this PR the rule is strict.
- Nothing a user sees changes in PR 1: every remote key sends the Android key code it sent before, and every Cast
  player state shows as it did.
- Never refreeze. While the store exists (Tasks 1–3), a fixed violation leaves it in the same commit: the test run
  removes the line, and the commit includes the smaller store file.
- `scripts/gradle.sh build` green at the end of every task; `scripts/e2e.sh -Pe2eBrowsers=chromium` at the end of the
  PR.
- Commits follow Conventional Commits, stage only the task's files (`git add <paths>`, `git rm`, `git mv`; never
  `git add -A`), and end with:

  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
  ```

## Review Focus

1. **Every remote key sends the Android key code it sent before.** A wrong entry would press another key (for example
   `PLAY_PAUSE` is `KEYCODE_MEDIA_PLAY_PAUSE`, 85, not a `KEYCODE_PLAY_PAUSE`). Pinned by
   `AndroidTvKeysTest.everyKeySendsTheAndroidKeyCodeItAlwaysSent`, a table of all 22 keys with their old numbers
   (Task 1).
2. **A long press still sends START_LONG and END_LONG.** Pinned by `AndroidTvKeysTest.pressesMapToTheirDirections`
   and `RemoteConnectionTest.sendsLongPressDirections` (Task 1).
3. **A Cast receiver that reports LOADING, or a state this server does not know, shows as buffering, not idle** (idle
   clears what is playing). Pinned by `MediaStatusTest.loadingAndUnknownStatesAreBuffering` (Task 2).
4. **SSDP discovery still fetches and reads device descriptions after the move.** Pinned by the moved
   `DeviceFetchTest` and `DeviceDescriptionsTest` and the unchanged SSDP, Sonos and UPnP discovery tests (Task 3).
5. **No rule is left frozen without a store.** With the store deleted, a rule still wrapped in `freeze(...)` fails
   the build instead of passing; Task 4 watches that happen before it unwraps the rules, and checks that a new
   protocol violation fails the strict rule.

## Plan rulings (where this plan departs from the spec's text)

1. **`MediaStatus.state()`, not `playerState()`.** `MediaStatus` is a record whose `playerState` component already
   holds Cast's raw string, so `playerState()` is taken. The enum is `MediaStatus.PlayerState`.
2. **`AndroidTvKeys.code` is an exhaustive switch, not a lookup by name.** Three keys have other names on the wire
   (`PLAY_PAUSE` → `KEYCODE_MEDIA_PLAY_PAUSE`, `REWIND` → `KEYCODE_MEDIA_REWIND`, `FAST_FORWARD` →
   `KEYCODE_MEDIA_FAST_FORWARD`); a switch over `RemoteKey` fails to compile when a key is added without a code.
3. **`RemoteConnection` keeps one `sendKey`**, `sendKey(RemoteKeyCode, RemoteDirection)`. The short-press overload
   goes; its tests pass `RemoteDirection.SHORT`.
4. **`CycleViolations` and its test are deleted** with the freezing: they only matched frozen cycles.
5. **`archunit.properties` is deleted**: it holds nothing but the freeze settings.

## File structure

| File | Change |
| --- | --- |
| `adapters/androidtv/AndroidTvKeys.java` | new: `RemoteKey` → `RemoteKeyCode`, `KeyPress` → `RemoteDirection` |
| `adapters/androidtv/protocol/RemoteConnection.java` | `sendKey(RemoteKeyCode, RemoteDirection)` |
| `adapters/androidtv/AndroidTvSession.java` | sends through `AndroidTvKeys` |
| `core/RemoteKey.java` | no Android numbers |
| `adapters/cast/protocol/MediaStatus.java` | `PlayerState`, `state()` |
| `adapters/cast/CastSession.java` | maps `PlayerState` to `PlaybackState` |
| `discovery/ssdp/{DeviceFetch,DeviceDescription,DeviceDescriptions}.java` | moved to `discovery/ssdp/protocol/` |
| `ArchitectureTest.java`, `CycleViolations.java`, its test | rules strict; helper deleted |
| `src/test/archunit-store/`, `src/test/resources/archunit.properties` | deleted |
| `build.gradle.kts`, `.github/workflows/ci.yml` | store input and check removed |
| `docs/dev/architecture.md`, `docs/dev/ci-and-releases.md`, `AGENTS.md` | no freezing; measures |

Paths are under `src/main/java/dev/andre/homecontrol/` and `src/test/java/dev/andre/homecontrol/` unless they start
with `src/`, `docs/`, `.github/` or are at the repository root.

---

### Task 1: Android TV maps its keys; `RemoteConnection` speaks key codes

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvKeys.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/RemoteConnection.java`,
  `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java`,
  `src/main/java/dev/andre/homecontrol/core/RemoteKey.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvKeysTest.java` (new),
  `adapters/androidtv/protocol/RemoteConnectionTest.java`, `adapters/androidtv/AndroidTvAdapterTest.java`,
  `web/CastEndToEndTest.java`, `device/DevicesTest.java`, `sources/jellyfin/JellyfinRouteExecutorTest.java`
- Store: `src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d` (12 lines go)

**Interfaces:**
- Produces: `static RemoteKeyCode AndroidTvKeys.code(RemoteKey key)`, `static RemoteDirection
  AndroidTvKeys.direction(KeyPress press)` (package-private, in `adapters.androidtv`);
  `public void RemoteConnection.sendKey(RemoteKeyCode code, RemoteDirection direction) throws IOException`.
  `RemoteKey` keeps its constants and `supportsLongPress()`, and loses `code()`.

- [ ] **Step 1: Write the failing test**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvKeysTest.java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class AndroidTvKeysTest {

    /** The Android key codes each key sent while they were part of core.RemoteKey. */
    private static final Map<RemoteKey, Integer> SENT = Map.ofEntries(
            entry(RemoteKey.DPAD_UP, 19),
            entry(RemoteKey.DPAD_DOWN, 20),
            entry(RemoteKey.DPAD_LEFT, 21),
            entry(RemoteKey.DPAD_RIGHT, 22),
            entry(RemoteKey.DPAD_CENTER, 23),
            entry(RemoteKey.BACK, 4),
            entry(RemoteKey.HOME, 3),
            entry(RemoteKey.MENU, 82),
            entry(RemoteKey.POWER, 26),
            entry(RemoteKey.WAKEUP, 224),
            entry(RemoteKey.VOLUME_UP, 24),
            entry(RemoteKey.VOLUME_DOWN, 25),
            entry(RemoteKey.VOLUME_MUTE, 164),
            entry(RemoteKey.PLAY_PAUSE, 85),
            entry(RemoteKey.MEDIA_NEXT, 87),
            entry(RemoteKey.MEDIA_PREVIOUS, 88),
            entry(RemoteKey.MEDIA_STOP, 86),
            entry(RemoteKey.REWIND, 89),
            entry(RemoteKey.FAST_FORWARD, 90),
            entry(RemoteKey.INFO, 165),
            entry(RemoteKey.SETTINGS, 176),
            entry(RemoteKey.GUIDE, 172));

    @Test
    void everyKeySendsTheAndroidKeyCodeItAlwaysSent() {
        assertThat(SENT).containsOnlyKeys(RemoteKey.values());
        SENT.forEach((key, code) -> assertThat(AndroidTvKeys.code(key).getNumber()).as(key.name()).isEqualTo(code));
    }

    @Test
    void pressesMapToTheirDirections() {
        assertThat(AndroidTvKeys.direction(KeyPress.SHORT)).isEqualTo(RemoteDirection.SHORT);
        assertThat(AndroidTvKeys.direction(KeyPress.START_LONG)).isEqualTo(RemoteDirection.START_LONG);
        assertThat(AndroidTvKeys.direction(KeyPress.END_LONG)).isEqualTo(RemoteDirection.END_LONG);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.androidtv.AndroidTvKeysTest`
Expected: compilation fails: `AndroidTvKeys` does not exist.

- [ ] **Step 3: Write the map, and move the key path onto it**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvKeys.java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteKeyCode;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;

/** The Android key code and press direction Remote v2 sends for each key of the remote. */
final class AndroidTvKeys {

    private AndroidTvKeys() {
    }

    static RemoteKeyCode code(RemoteKey key) {
        return switch (key) {
            case DPAD_UP -> RemoteKeyCode.KEYCODE_DPAD_UP;
            case DPAD_DOWN -> RemoteKeyCode.KEYCODE_DPAD_DOWN;
            case DPAD_LEFT -> RemoteKeyCode.KEYCODE_DPAD_LEFT;
            case DPAD_RIGHT -> RemoteKeyCode.KEYCODE_DPAD_RIGHT;
            case DPAD_CENTER -> RemoteKeyCode.KEYCODE_DPAD_CENTER;
            case BACK -> RemoteKeyCode.KEYCODE_BACK;
            case HOME -> RemoteKeyCode.KEYCODE_HOME;
            case MENU -> RemoteKeyCode.KEYCODE_MENU;
            case POWER -> RemoteKeyCode.KEYCODE_POWER;
            case WAKEUP -> RemoteKeyCode.KEYCODE_WAKEUP;
            case VOLUME_UP -> RemoteKeyCode.KEYCODE_VOLUME_UP;
            case VOLUME_DOWN -> RemoteKeyCode.KEYCODE_VOLUME_DOWN;
            case VOLUME_MUTE -> RemoteKeyCode.KEYCODE_VOLUME_MUTE;
            case PLAY_PAUSE -> RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE;
            case MEDIA_NEXT -> RemoteKeyCode.KEYCODE_MEDIA_NEXT;
            case MEDIA_PREVIOUS -> RemoteKeyCode.KEYCODE_MEDIA_PREVIOUS;
            case MEDIA_STOP -> RemoteKeyCode.KEYCODE_MEDIA_STOP;
            case REWIND -> RemoteKeyCode.KEYCODE_MEDIA_REWIND;
            case FAST_FORWARD -> RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD;
            case INFO -> RemoteKeyCode.KEYCODE_INFO;
            case SETTINGS -> RemoteKeyCode.KEYCODE_SETTINGS;
            case GUIDE -> RemoteKeyCode.KEYCODE_GUIDE;
        };
    }

    static RemoteDirection direction(KeyPress press) {
        return switch (press) {
            case SHORT -> RemoteDirection.SHORT;
            case START_LONG -> RemoteDirection.START_LONG;
            case END_LONG -> RemoteDirection.END_LONG;
        };
    }
}
```

In `RemoteConnection`, the two `sendKey` methods and `direction(KeyPress)` become one method, and the imports of
`dev.andre.homecontrol.core.KeyPress` and `dev.andre.homecontrol.core.RemoteKey` go:

```java
    /** Presses a key: {@code SHORT} for a tap, {@code START_LONG} and {@code END_LONG} for the edges of a hold. */
    public void sendKey(RemoteKeyCode code, RemoteDirection direction) throws IOException {
        write(RemoteMessage.newBuilder()
                .setRemoteKeyInject(RemoteKeyInject.newBuilder()
                        .setKeyCode(code)
                        .setDirection(direction))
                .build());
    }
```

In `AndroidTvSession.sendKey(RemoteKey, KeyPress)`:

```java
        DeviceCalls.run(device.name(), "press " + key,
                () -> current.sendKey(AndroidTvKeys.code(key), AndroidTvKeys.direction(press)));
```

`core.RemoteKey` keeps its constants and `supportsLongPress()` and loses the numbers:

```java
// File: src/main/java/dev/andre/homecontrol/core/RemoteKey.java
package dev.andre.homecontrol.core;

/** The keys of the remote. Each adapter maps them onto its own device's codes. */
public enum RemoteKey {

    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_CENTER,
    BACK,
    HOME,
    MENU,
    POWER,
    WAKEUP,
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MUTE,
    PLAY_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_STOP,
    REWIND,
    FAST_FORWARD,
    INFO,
    SETTINGS,
    GUIDE;

    /** Whether this key accepts a start/end long press instead of just a short tap. */
    public boolean supportsLongPress() {
        return switch (this) {
            case DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME -> true;
            default -> false;
        };
    }
}
```

The tests that used the numbers or the old overloads:

- `RemoteConnectionTest`: `connection.sendKey(RemoteKey.DPAD_DOWN)` →
  `connection.sendKey(RemoteKeyCode.KEYCODE_DPAD_DOWN, RemoteDirection.SHORT)`; `connection.sendKey(RemoteKey.DPAD_UP)`
  → `connection.sendKey(RemoteKeyCode.KEYCODE_DPAD_UP, RemoteDirection.SHORT)`;
  `connection.sendKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG)` and `… END_LONG)` →
  `connection.sendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER, RemoteDirection.START_LONG)` and `… END_LONG)`. Imports: add
  `dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteKeyCode`, remove `core.KeyPress` and
  `core.RemoteKey`.
- `AndroidTvAdapterTest`: `isEqualTo(RemoteKey.DPAD_UP.code())` → `isEqualTo(19)`.
- `CastEndToEndTest` and `DevicesTest` (twice): `isEqualTo(RemoteKey.HOME.code())` → `isEqualTo(3)`.
- `JellyfinRouteExecutorTest`: `key.key().code() == 224` → `key.key() == RemoteKey.WAKEUP`.

Then `grep -rn "\.code()" src/main src/test src/e2e | grep -i remotekey` prints nothing.

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.*' --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS. `git diff --stat src/test/archunit-store` shows the store file 12 lines shorter: every
`RemoteConnection` line is gone.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green; the store file has 13 lines.

- [ ] **Step 6: Commit**

Stage `AndroidTvKeys.java`, `AndroidTvKeysTest.java`, `RemoteConnection.java`, `AndroidTvSession.java`,
`RemoteKey.java`, the five changed tests and the store file. Message:

```
refactor: Android TV maps its keys in the adapter; RemoteConnection speaks key codes

RemoteConnection took the core RemoteKey and KeyPress, so a wire protocol knew the app's domain
types, and core.RemoteKey carried Android's key numbers for the one adapter that used them. Now
RemoteConnection takes the protobuf RemoteKeyCode and RemoteDirection it writes, and AndroidTvKeys
maps the remote's keys onto them, as WebOsKeys and TizenKeys do for their TVs. Every key sends the
code it sent before. Twelve frozen violations leave the store.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 2: Cast's player state stays in its protocol

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/cast/protocol/MediaStatus.java`,
  `src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/cast/protocol/MediaStatusTest.java`
- Store: `src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d` (5 lines go)

**Interfaces:**
- Produces: `public enum MediaStatus.PlayerState { PLAYING, PAUSED, IDLE, BUFFERING }` and `public PlayerState
  MediaStatus.state()`. `MediaStatus.playbackState()` goes.

- [ ] **Step 1: Write the failing test**

In `MediaStatusTest`, the four assertions on `playbackState()` assert `state()` instead:
`status.playbackState()).isEqualTo(PlaybackState.PLAYING)` → `status.state()).isEqualTo(MediaStatus.PlayerState.PLAYING)`,
and the same for `PAUSED`, `IDLE` and `BUFFERING`; the import of `dev.andre.homecontrol.core.PlaybackState` goes. Add:

```java
    @Test
    void loadingAndUnknownStatesAreBuffering() {
        assertThat(status("LOADING").state()).isEqualTo(MediaStatus.PlayerState.BUFFERING);
        assertThat(status("SOMETHING_NEWER").state()).isEqualTo(MediaStatus.PlayerState.BUFFERING);
    }

    private static MediaStatus status(String playerState) {
        return new MediaStatus(1, playerState, 0.0, null, null, null, null);
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.cast.protocol.MediaStatusTest`
Expected: compilation fails: `state()` and `MediaStatus.PlayerState` do not exist.

- [ ] **Step 3: Give `MediaStatus` its own state, and map it in the session**

In `MediaStatus`, `playbackState()` and the import of `dev.andre.homecontrol.core.PlaybackState` go; inside the
record:

```java
    /** Cast's player states, as this server tells them apart. */
    public enum PlayerState { PLAYING, PAUSED, IDLE, BUFFERING }

    /** LOADING and anything a newer receiver invents count as buffering. */
    public PlayerState state() {
        return switch (playerState) {
            case "PLAYING" -> PlayerState.PLAYING;
            case "PAUSED" -> PlayerState.PAUSED;
            case "IDLE" -> PlayerState.IDLE;
            case null, default -> PlayerState.BUFFERING;
        };
    }
```

In `CastSession.Link.onMediaStatus`, `PlaybackState playback = latest.playbackState();` becomes
`PlaybackState playback = playback(latest.state());`, and `Link` gains:

```java
        private static PlaybackState playback(MediaStatus.PlayerState state) {
            return switch (state) {
                case PLAYING -> PlaybackState.PLAYING;
                case PAUSED -> PlaybackState.PAUSED;
                case IDLE -> PlaybackState.IDLE;
                case BUFFERING -> PlaybackState.BUFFERING;
            };
        }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*' --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS; the store file loses its five `MediaStatus` lines.

- [ ] **Step 5: Run the build**

Run: `scripts/gradle.sh build`
Expected: green; the store file has 8 lines.

- [ ] **Step 6: Commit**

Stage `MediaStatus.java`, `CastSession.java`, `MediaStatusTest.java` and the store file. Message:

```
refactor: Cast's player state stays in its protocol

MediaStatus returned the core PlaybackState, so the wire protocol knew the app's domain types.
It now reports Cast's own PlayerState, and CastSession maps it; LOADING and unknown states still
show as buffering. Five frozen violations leave the store.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 3: The SSDP wire helpers become `discovery.ssdp.protocol`

**Files:**
- Move: `src/main/java/dev/andre/homecontrol/discovery/ssdp/{DeviceFetch,DeviceDescription,DeviceDescriptions}.java`
  → `src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/`
- Move: `src/test/java/dev/andre/homecontrol/discovery/ssdp/{DeviceFetchTest,DeviceDescriptionsTest}.java`
  → `src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol/`
- Modify (imports): `discovery/ssdp/SsdpDiscovery.java`, `discovery/ssdp/SsdpService.java`,
  `adapters/sonos/SonosDiscovery.java`, `adapters/sonos/protocol/SonosEndpoints.java`,
  `adapters/upnp/UpnpDiscovery.java`, `adapters/upnp/UpnpSession.java`, `adapters/upnp/protocol/ServiceEndpoint.java`,
  `adapters/upnp/protocol/SoapClient.java`, `adapters/upnp/protocol/UpnpXml.java`, and the test
  `adapters/upnp/UpnpDiscoveryTest.java`
- Store: `src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d` (the last 8 lines go)

**Interfaces:**
- Produces: `dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch`, `.DeviceDescription`,
  `.DeviceDescriptions`, unchanged apart from their package.

- [ ] **Step 1: See the violations the move removes**

Run: `grep -c "discovery.ssdp" src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d`
Expected: `8`. These are the tests of this task: the next `ArchitectureTest` run must drop them, and nothing else may
fail.

- [ ] **Step 2: Move the classes and their tests**

```bash
mkdir -p src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol
git mv src/main/java/dev/andre/homecontrol/discovery/ssdp/DeviceFetch.java src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/
git mv src/main/java/dev/andre/homecontrol/discovery/ssdp/DeviceDescription.java src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/
git mv src/main/java/dev/andre/homecontrol/discovery/ssdp/DeviceDescriptions.java src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/
git mv src/test/java/dev/andre/homecontrol/discovery/ssdp/DeviceFetchTest.java src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol/
git mv src/test/java/dev/andre/homecontrol/discovery/ssdp/DeviceDescriptionsTest.java src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol/
```

In the five moved files, `package dev.andre.homecontrol.discovery.ssdp;` becomes
`package dev.andre.homecontrol.discovery.ssdp.protocol;`. In every file listed under "Modify (imports)", the imports
`dev.andre.homecontrol.discovery.ssdp.DeviceFetch`, `.DeviceDescription` and `.DeviceDescriptions` point at
`dev.andre.homecontrol.discovery.ssdp.protocol.…`. `SsdpDiscovery` and `SsdpService` used them from their own package
without imports; they gain the imports they need (`SsdpDiscovery`: `DeviceDescription`, `DeviceDescriptions`,
`DeviceFetch`; `SsdpService`: `DeviceDescription`). Keep each import block sorted.

Then `grep -rn "discovery\.ssdp\.Device" src` prints only `discovery.ssdp.protocol.Device…` imports.

- [ ] **Step 3: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.discovery.*' --tests 'dev.andre.homecontrol.adapters.sonos.*' --tests 'dev.andre.homecontrol.adapters.upnp.*' --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS; the store file `3fa162ba-…` is empty (0 lines).

- [ ] **Step 4: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 5: Commit**

Stage the five moves (`git mv` already staged them), their edits, the ten import changes and the store file. Message:

```
refactor: the SSDP wire helpers become discovery.ssdp.protocol

DeviceFetch, DeviceDescription and DeviceDescriptions are wire code that SSDP discovery and the
Sonos and UPnP protocols share. In discovery.ssdp they made four protocol classes depend on an
application package; as a protocol package of their own they are what the rule allows, and
Phase 4's protocols module will take them. They move unchanged. The last eight frozen violations
leave the store.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 4: Every architecture rule is strict; the frozen store and its machinery go

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`
- Delete: `src/test/java/dev/andre/homecontrol/CycleViolations.java`,
  `src/test/java/dev/andre/homecontrol/CycleViolationsTest.java`, `src/test/archunit-store/` (five rule files and
  `stored.rules`), `src/test/resources/archunit.properties`
- Modify: `build.gradle.kts`, `.github/workflows/ci.yml`, `docs/dev/architecture.md`, `docs/dev/ci-and-releases.md`,
  `AGENTS.md`

**Interfaces:**
- Consumes: Tasks 1–3 (the store at zero).
- Produces: `ArchitectureTest` with every rule strict; no freezing anywhere.

- [ ] **Step 1: Delete the store and its settings, and see the frozen rules fail**

```bash
git rm -r -q src/test/archunit-store src/test/resources/archunit.properties
```

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.ArchitectureTest`
Expected: FAIL: the five frozen rules (`protocolPackagesStandAlone`, `topLevelPackagesAreFreeOfCycles`,
`sourcesDoNotDependOnAdapters`, `adaptersDoNotDependOnSourcesOrWeb`, `webDoesNotDependOnAdapters`) fail because
ArchUnit may not create a store. This is Review Focus 5: a rule left frozen cannot pass silently.

- [ ] **Step 2: Make the rules strict**

In `ArchitectureTest`, each of the five rules loses its `freeze(` and the matching `)` before the semicolon, keeping
its description and `because` text. `topLevelPackagesAreFreeOfCycles` also loses
`.associateViolationLinesVia(new CycleViolations())` and its Javadoc line "Frozen per cycle, …". The import
`static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze` goes. The class Javadoc becomes:

```java
/** The package rules of docs/dev/architecture.md, checked on every build. Every rule is strict. */
```

The five rules then read:

```java
    @ArchTest
    static final ArchRule protocolPackagesStandAlone = noClasses()
            .that().resideInAPackage("..protocol..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .orShould().dependOnClassesThat(resideInAPackage("dev.andre.homecontrol..")
                    .and(not(resideInAnyPackage("..protocol..", "dev.andre.homecontrol.adapters.net.."))))
            .because("wire protocols are libraries: they take plain values and know nothing of Spring or the app");

    @ArchTest
    static final ArchRule topLevelPackagesAreFreeOfCycles = slices()
            .matching("dev.andre.homecontrol.(*)..")
            .should().beFreeOfCycles()
            .because("packages in a cycle cannot be understood, tested or split apart on their own");

    @ArchTest
    static final ArchRule sourcesDoNotDependOnAdapters = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.sources..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.adapters..")
            .because("sources see devices only through the domain model in core");

    @ArchTest
    static final ArchRule adaptersDoNotDependOnSourcesOrWeb = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("dev.andre.homecontrol.sources..",
                    "dev.andre.homecontrol.web..")
            .because("adapters speak device protocols and nothing else");

    @ArchTest
    static final ArchRule webDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.web..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.adapters..")
            .because("the web layer sees the domain model only");
```

Delete `CycleViolations.java` and `CycleViolationsTest.java` (`git rm`).

- [ ] **Step 3: Run the rules, and see a new protocol violation fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS.

Add for a moment, to `MediaStatus`, the method
`static dev.andre.homecontrol.core.PlaybackState probe() { return null; }`, and run the same command.
Expected: FAIL, `protocolPackagesStandAlone` naming `MediaStatus.probe()`. Remove the method; run again: PASS.

- [ ] **Step 4: Remove the store from the build, CI and the docs**

`build.gradle.kts`: in `tasks.test`, the comment "ArchitectureTest compares the code with the committed store …" and
the `inputs.dir("src/test/archunit-store")…` line go; `finalizedBy(tasks.jacocoTestReport)` stays.

`.github/workflows/ci.yml`: the comment starting "ArchitectureTest freezes today's package-rule violations" and the
step "Check the frozen architecture violations are committed" go, up to the next step.

`docs/dev/ci-and-releases.md`, row "Build and test": "Runs `./gradlew build` with the full test suite, checks that the
frozen architecture violations are committed, and uploads the reports." becomes "Runs `./gradlew build` with the full
test suite and uploads the reports."

`AGENTS.md`, section "Architecture rules": the bullet starting "Rules the code still breaks are frozen" becomes:

```markdown
- Every package rule is strict. A change that breaks one changes the code, not the rule; a rule that needs an
  exception names it in the rule, with its reason.
```

`docs/dev/architecture.md`:
- "Dependencies": the sentence "The frozen violations below are the only other dependencies, and the roadmap removes
  them." goes.
- `discovery` row of the package table: after "mDNS and SSDP discovery." add "The wire code SSDP shares with the
  Sonos and UPnP protocols (the safe description fetch and the description parser) is the protocol package
  `discovery.ssdp.protocol`."
- Rule table: the five `frozen: …` statuses read `strict`.
- The whole section "## Frozen violations", from its heading to "workstreams, which remove them.", goes.
- "Progress measures": `| Frozen ArchUnit violations | 89 | 25 |` becomes `| Frozen ArchUnit violations | 89 | 0 (every
  rule strict) |`.

Then `grep -rn "archunit-store\|freeze\|[Ff]rozen" AGENTS.md docs/dev build.gradle.kts .github src/test` prints only
the measures row ("Frozen ArchUnit violations").

- [ ] **Step 5: Run the build and the browser tests**

Run: `scripts/gradle.sh build`
Expected: green.

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium`
Expected: 68 tests, 0 failures.

- [ ] **Step 6: Commit**

Stage the deletions (`git rm` staged them), `ArchitectureTest.java`, `build.gradle.kts`, `ci.yml`,
`architecture.md`, `ci-and-releases.md` and `AGENTS.md`. Message:

```
test: every architecture rule is strict; the frozen store goes

The frozen store reached zero: no protocol class depends on the application any more, and the
other four frozen rules had no violations left. Every rule in ArchitectureTest is now strict, and
the freezing goes with it: the store, the freeze settings, the Gradle input, the CI check that the
store was committed, CycleViolations, and the guide's section on frozen violations. AGENTS.md says
the rules are strict. This meets Phase 4's precondition.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

## Self-review

- **Spec coverage (section 1 and PR 1's Testing and Delivery).** Android TV keys and `RemoteKey` without numbers
  (Task 1); Cast's player state mapped in the session (Task 2); the SSDP helpers in `discovery.ssdp.protocol` (Task 3);
  all five rules strict and the store, settings, Gradle input, CI step, guide section, `AGENTS.md` bullet and CI guide
  mention removed (Task 4); `AndroidTvKeysTest`, the `MediaStatus` test, the moved SSDP tests (Tasks 1–3); build and
  browser tests (Task 4). Four commits in the spec's order.
- **Measures:** frozen violations 25 → 13 after Task 1, 8 after Task 2, 0 after Task 3; frozen rules 5 → 0 after
  Task 4.
- **Types.** `AndroidTvKeys.code`/`direction` (Task 1) are used only in Task 1; `MediaStatus.PlayerState` and
  `state()` (Task 2) only in Task 2; the moved classes keep their names (Task 3).
