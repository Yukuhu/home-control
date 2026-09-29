# Phase 3B: The Device Feature Contract — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adapters declare exactly what their sessions carry out, actions name every capability that can carry
them, a live connection's extras are found with one typed lookup, Wake-on-LAN is a capability, and Jellyfin asks
for `ANDROID_APPS` instead of an adapter id.

**Architecture:** `Capability` gains `ANDROID_APPS`, `INPUTS`, `GROUPING` and `WAKE_ON_LAN` and loses `POWER`.
`Action.requires()` becomes an any-of `Set<Capability>` with a `purpose()` phrase, so `CommandRouter` refuses up
front with "<device> cannot <purpose>". `DeviceHandle.feature(Class)` replaces the `isInstance` checks and the
throwing `query`. The `WakeOnLanAdapter` marker becomes the `WAKE_ON_LAN` capability. Jellyfin checks the new
capability.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit 5, AssertJ, Mockito (BDD style).

**Spec:** `docs/superpowers/specs/2026-09-29-phase-3b-device-features-design.md`

## Global Constraints

- **Every command a device can carry out still works.** The only behaviour changes are these:
  - a command reaches fewer adapters that cannot do it;
  - a refusal is worded by the router;
  - the Jellyfin setup section no longer offers a device whose Android TV module is switched off.
- **No `/data` format changes.** The Wake-on-LAN keys stay `macAddress` and `macAddressManual`.
- **Sessions keep their exhaustive `switch` over `Action`.** Their refusal arms stay.
- `scripts/gradle.sh build` is green after every task, and the test count only rises (2,931 at `092919a`).
- Commits follow Conventional Commits; stage only the files you changed.

## Review Focus

1. **A capability dropped that a screen still reads.** Android TV loses `VOLUME`, and `POWER` goes. The dashboard,
   the setup page and the planner must still show every control a device can use. `DashboardController` reads
   `REMOTE_KEYS`, `CAST_RECEIVER`, `MEDIA_RENDERER`, `LOCAL_AUDIO_SINK` and `APP_LINK`, and none of those change.
   Task 1's adapter tests pin the declarations, and the web and full-app suites run in every task.
2. **Volume on a Shield that also has Cast.** It must reach Cast. Task 2 pins it with a router test on two stub
   adapters declaring the new sets.
3. **Commands between the tasks.** A command must never be refused because an action needs a capability no adapter
   declares yet. Task 1 therefore adds the new declarations before Task 2 changes `requires()`.
4. **A Cast connection asked a receiver-app question.** It must still answer through `ReceiverApps`. A stub handle
   without an answer must still count as unsupported and hand over. Task 3 keeps `DevicesQueryTest` and pins
   `feature` on `CastSession`.
5. **Jellyfin on a Shield whose Android TV module is switched off.** It must not plan the native app. That is what
   `adapterEnabled` did, and the capability now does it. Task 5 pins it in the resolver and the setup advice.

---

### Task 1: Capabilities, and what each adapter declares

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/core/Capability.java`
- Modify the seven adapters' `capabilities(Device)`: `adapters/androidtv/AndroidTvAdapter.java`,
  `webos/WebOsAdapter.java`, `tizen/TizenAdapter.java`, `sonos/SonosAdapter.java`. Cast, UPnP and Bluetooth are
  unchanged.
- Test: `AndroidTvAdapterTest`, `WebOsAdapterTest`, `TizenAdapterTest`, `SonosAdapterTest`, and every other test
  naming `Capability.POWER` (`DevicesTest`, `YouTubeRoutesTest`, `LiveEventRoutingTest` and any others the compiler
  finds).

**Interfaces:**
- Produces:

```java
public enum Capability {
    REMOTE_KEYS, VOLUME, APP_LINK, ANDROID_APPS, INPUTS, GROUPING, WAKE_ON_LAN, CAST_RECEIVER, MEDIA_RENDERER,
    JELLYFIN_CLIENT, LOCAL_AUDIO_SINK
}
```

  with a Javadoc per value; `JELLYFIN_CLIENT` says that no adapter declares it and the Jellyfin resolver adds it.

- [ ] **Step 1: Pin the new declarations in the adapter tests.**

  | Test | Expected set |
  | --- | --- |
  | `AndroidTvAdapterTest` | `REMOTE_KEYS, APP_LINK, ANDROID_APPS` |
  | `WebOsAdapterTest` | `REMOTE_KEYS, APP_LINK, VOLUME, INPUTS, WAKE_ON_LAN` |
  | `TizenAdapterTest` | `REMOTE_KEYS, APP_LINK, VOLUME, WAKE_ON_LAN` |
  | `SonosAdapterTest` | `MEDIA_RENDERER, VOLUME, GROUPING`, in a new test if none pins it |

  Replace every `Capability.POWER` in tests with nothing: drop it from the set.
- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`. Expected: compile failure
  (the new values do not exist).
- [ ] **Step 3: Implement** the enum and the four declarations.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green. `SelectInput` and `JoinGroup` still require
  `REMOTE_KEYS` and `MEDIA_RENDERER`, so nothing is routed differently yet, except that `SetVolume` and `Mute` skip
  Android TV.
- [ ] **Step 5: Commit** `refactor: declare exactly what each device adapter carries out`. The body names the new
  capabilities, `POWER`'s removal, and Android TV no longer declaring `VOLUME` because its session refuses
  `SetVolume` and `Mute`.

---

### Task 2: Actions name every capability that can carry them; the router refuses up front

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/core/Action.java`
- Modify: `src/main/java/dev/andre/homecontrol/device/CommandRouter.java`
- Test: `src/test/java/dev/andre/homecontrol/core/ActionTest.java`, `device/DevicesExecuteTest.java`,
  `device/DevicesFallThroughTest.java`

**Interfaces:**
- Produces:
  - `Set<Capability> requires()`: an adapter needs one of them;
  - `default boolean acceptedBy(Set<Capability> capabilities)`: true when it declares any of `requires()`;
  - `String purpose()`: a short phrase for "<device> cannot <purpose>".

```java
    /** The capabilities an adapter may declare to carry this action; it needs one of them. */
    Set<Capability> requires();

    /** What this action asks of a device, for "<device> cannot <purpose>", e.g. "switch inputs". */
    String purpose();

    /** Whether an adapter declaring {@code capabilities} may be asked to perform this action. */
    default boolean acceptedBy(Set<Capability> capabilities) {
        return !Collections.disjoint(requires(), capabilities);
    }
```

  | Action | `requires()` | `purpose()` |
  | --- | --- | --- |
  | `PressKey` | `REMOTE_KEYS` | "take remote keys" |
  | `OpenAppLink` | `APP_LINK` | "open app links" |
  | `SelectInput` | `INPUTS` | "switch inputs" |
  | `SetVolume` | `VOLUME` | "change the volume" |
  | `Mute` | `VOLUME` | "mute" |
  | `Stop` | `CAST_RECEIVER, MEDIA_RENDERER, LOCAL_AUDIO_SINK` | "stop playback" |
  | `PlayMedia` | `MEDIA_RENDERER, LOCAL_AUDIO_SINK` | "play a stream" |
  | `Pause` | `MEDIA_RENDERER, LOCAL_AUDIO_SINK` | "pause" |
  | `Resume` | `MEDIA_RENDERER, LOCAL_AUDIO_SINK` | "resume" |
  | `JoinGroup`, `LeaveGroup` | `GROUPING` | "be grouped" |
  | `CastLoad`, `CastMessage` | `CAST_RECEIVER` | "receive Cast media" |

  The four `acceptedBy` overrides and the private `playsStreams` go.
- `CommandRouter.execute` and `stopEverywhere` refuse with `device.name() + " cannot " + action.purpose()` when no
  adapter could send. The existing reasons (refusal, offline, unsupported) still win when an adapter tried.

- [ ] **Step 1: Write the failing tests.**
  - `ActionTest`: each action's `requires()` and `purpose()` as in the table, replacing the single-value assertions.
  - `DevicesExecuteTest`:
    - `aSelectInputIsRefusedWithoutReachingAnAdapterThatCannotSwitchInputs`: a stub declaring `REMOTE_KEYS` only.
      Expect `UnsupportedActionException` "Shield cannot switch inputs" and an empty `executed` list on its handle.
    - `joiningAGroupNeverReachesARendererThatCannotGroup`: a `MEDIA_RENDERER` stub, and the same checks.
    - `volumeOnAShieldWithCastReachesCastOnly`: stubs `androidtv` (`REMOTE_KEYS, APP_LINK, ANDROID_APPS`) and `cast`
      (`CAST_RECEIVER, VOLUME`). `SetVolume(30)` lands on the Cast handle only.
  - `DevicesFallThroughTest:117`: the stop refusal now reads "TV cannot stop playback".
- [ ] **Step 2:** run `ActionTest`, `DevicesExecuteTest` and `DevicesFallThroughTest`. Expected: compile failure
  (`purpose()`, `Set` from `requires()`).
- [ ] **Step 3: Implement** the actions and the two router messages.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: let actions name every capability that can carry them, and refuse the rest up
  front`. The body describes the visible change: the refusal wording, and inputs and grouping refused before
  reaching a session.

---

### Task 3: One typed lookup for what a connection offers

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/core/ReceiverApps.java`
- Modify:
  - `core/DeviceHandle.java`: add `feature`, remove `query`;
  - `adapters/cast/CastSession.java`: implements `ReceiverApps`;
  - `device/RegisteredDevices.java`;
  - `device/CommandRouter.java`;
  - `core/GroupListing.java`, `core/InputListing.java`: Javadoc only.
- Test:
  - `device/StubAdapter.java`: `StubHandle implements ReceiverApps`, and its `query` without an answer still throws
    "This connection cannot ask receiver apps";
  - `adapters/cast/CastSessionTest.java`, `adapters/webos/WebOsSessionTest.java`,
    `adapters/sonos/SonosSessionTest.java`;
  - a new `core/DeviceHandleTest.java`.

**Interfaces:**
- Produces:

```java
    /**
     * What this connection offers beyond commands, such as its inputs ({@link InputListing}), its speaker grouping
     * ({@link GroupListing}) or its receiver apps ({@link ReceiverApps}); empty when it offers none of that kind.
     */
    default <T> Optional<T> feature(Class<T> type) {
        return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
    }
```

```java
/** A connection that can ask a receiver app a question: Cast. Found with {@link DeviceHandle#feature}. */
public interface ReceiverApps {

    /** Asks the receiver app and returns its reply, now or never (same exceptions as {@link DeviceHandle#execute}). */
    Map<String, Object> query(CastAppQuery query);
}
```

- [ ] **Step 1: Write the failing tests.**
  - `DeviceHandleTest`:
    - `aHandleOffersTheFeaturesItImplements`: a local handle implementing `InputListing` returns itself for
      `InputListing` and empty for `GroupListing`;
    - `aHandleWithoutAFeatureOffersNone`.
  - Session tests, one assertion each in an existing connected test:
    - `CastSessionTest`: `session.feature(ReceiverApps.class)` is present;
    - `WebOsSessionTest`: `feature(InputListing.class)`;
    - `SonosSessionTest`: `feature(GroupListing.class)`.
- [ ] **Step 2:** run them. Expected: compile failure (`feature`, `ReceiverApps`).
- [ ] **Step 3: Implement.**
  - `RegisteredDevices.speakerTopology` and `inputs` map `handle.feature(GroupListing.class)` and
    `handle.feature(InputListing.class)`.
  - `CommandRouter.query` asks `handle.feature(ReceiverApps.class)`. When it is empty, it records
    `failures.unsupported(new UnsupportedActionException(device.name() + " cannot ask receiver apps"))` and moves on.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green, and `DevicesQueryTest` unchanged.
- [ ] **Step 5: Commit** `refactor: find what a device connection offers with one typed feature lookup`.

---

### Task 4: Wake-on-LAN as a capability

**Files:**
- Delete: `src/main/java/dev/andre/homecontrol/core/WakeOnLanAdapter.java`
- Create: `src/main/java/dev/andre/homecontrol/core/WakeOnLan.java`

```java
/** The adapter settings of a device woken with a Wake-on-LAN magic packet ({@link Capability#WAKE_ON_LAN}). */
public final class WakeOnLan {

    /** The MAC the magic packet goes to. */
    public static final String MAC_ADDRESS = "macAddress";
    /** {@code "true"} once the user typed the MAC; adapters then stop replacing it with what the device reports. */
    public static final String MAC_ADDRESS_MANUAL = "macAddressManual";

    private WakeOnLan() {
    }
}
```

- Modify:
  - `adapters/webos/WebOsAdapter.java`, `WebOsSettings.java`, `WebOsSession.java`;
  - `adapters/tizen/TizenAdapter.java`, `TizenSettings.java`, `TizenSession.java`;
  - `device/AdapterSettingsStore.java`.
- Test: `WebOsAdapterTest`, `TizenAdapterTest`, `device/DevicesTest.java` (its waking adapter declares
  `WAKE_ON_LAN` instead of implementing the marker).

- [ ] **Step 1: Move the tests first.**
  - The adapter tests assert `WAKE_ON_LAN` (already in their capability sets from Task 1) instead of `isInstanceOf(
    WakeOnLanAdapter.class)`.
  - `DevicesTest`'s waking adapter drops `implements WakeOnLanAdapter` and declares `WAKE_ON_LAN`.
  - `WakeOnLanAdapter.MAC_ADDRESS` becomes `WakeOnLan.MAC_ADDRESS` everywhere in tests.
- [ ] **Step 2:** run `device.*` and `adapters.*`. Expected: failures. `AdapterSettingsStore` still looks for the
  marker, so the waking adapter's MAC is not kept.
- [ ] **Step 3: Implement.**
  - The adapters implement `DeviceAdapter, AdapterDiscovery`.
  - The settings classes and sessions use `WakeOnLan`.
  - `AdapterSettingsStore`'s three `instanceof WakeOnLanAdapter` checks become a private `wakesOnLan(Device,
    String adapterId)` that asks the adapter's capabilities.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: make Wake-on-LAN a capability instead of a marker interface`.

---

### Task 5: Jellyfin asks for `ANDROID_APPS`

**Files:**
- Modify:
  - `sources/jellyfin/JellyfinPlayableResolver.java`: its `Predicate<String> adapterEnabled` goes;
  - `JellyfinConfiguration.java`, `JellyfinRouteExecutor.java`, `JellyfinVlcExecutor.java`,
    `JellyfinSetupController.java`, `JellyfinSetupAdvice.java`;
  - `core/DeviceQueries.java` and `device/RegisteredDevices.java`: `adapterEnabled` goes.
- Test: `JellyfinPlayableResolverTest`, `JellyfinRouteExecutorTest`, `JellyfinVlcExecutorTest`,
  `JellyfinSetupControllerTest`, `DevicesTest` (its `adapterEnabled` assertions go), and the setup advice's test.

**Interfaces:**
- A device runs the Jellyfin app when `capabilities(id).contains(Capability.ANDROID_APPS)`. The resolver also keeps
  its `REMOTE_KEYS` check, and the executors ask `devices.capabilities(device.id())`.

- [ ] **Step 1: Move the tests first.**
  - Every `hasAdapter`, `adapterEnabled` or `withAdapter("androidtv", …)` setup that stands for "runs the app" gives
    the device `ANDROID_APPS` in its stubbed capabilities instead.
  - New: `JellyfinPlayableResolverTest.aShieldWhoseAndroidTvModuleIsOffDoesNotPlanTheNativeApp`, with capabilities
    `REMOTE_KEYS, APP_LINK` (no `ANDROID_APPS`).
  - New, in the setup advice's test: a device without `ANDROID_APPS` is offered as not running the app.
- [ ] **Step 2:** run `sources.jellyfin.*`. Expected: failures on the new tests and compile failures where the
  resolver's constructor changed.
- [ ] **Step 3: Implement** the five checks, drop the resolver's predicate and `adapterEnabled`.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green, and `grep -rn 'hasAdapter("androidtv")'
  src/main/java/dev/andre/homecontrol/sources` prints nothing.
- [ ] **Step 5: Commit** `refactor: let Jellyfin ask for Android apps instead of the Android TV adapter`. The body
  names the visible change in the setup section.

---

### Task 6: Bluetooth's switch is exhaustive

**Files:**
- Modify: `adapters/bluetooth/BluetoothSpeakerSession.java`
- Test: `adapters/bluetooth/BluetoothSpeakerSessionTest.java`

- [ ] **Step 1: Pin the refusals.** In `remoteKeysAreUnsupported` or a sibling test, assert that `PressKey`,
  `OpenAppLink`, `SelectInput`, `JoinGroup`, `LeaveGroup`, `CastLoad` and `CastMessage` each throw
  `UnsupportedActionException` with the session's existing message ("<name> is a Bluetooth speaker and cannot handle
  <Action>").
- [ ] **Step 2:** run it. Expected: green. This task keeps behaviour and changes only what the compiler checks. The
  test pins the messages the explicit arms must keep.
- [ ] **Step 3: Replace `default ->`** with one arm listing those seven actions, `case Action.PressKey _,
  Action.OpenAppLink _, … -> throw unsupported(action);`, through a private helper that keeps the message.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green.
- [ ] **Step 5: Commit** `refactor: make the Bluetooth session's action switch exhaustive`.

---

### Task 7: The architecture guide

**Files:**
- Modify: `docs/dev/architecture.md`. The `core` row names capabilities (what an adapter declares) and connection
  features (`DeviceHandle.feature`: `InputListing`, `GroupListing`, `ReceiverApps`).

- [ ] **Step 1:** edit the row.
- [ ] **Step 2:** `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava` and `scripts/e2e.sh
  -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 3: Commit** `docs: describe device capabilities and connection features`.
