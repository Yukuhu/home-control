# Phase 3B: The Device Feature Contract

**Status:** approved in conversation on 2026-09-29, section by section. The user chose native implementation and no
written review of this spec or its plan, as for 2A, 2B and 3A.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "3B: The device feature
contract", and finding 4 ("device and playback contracts leak").

## Purpose

Optional device features are expressed in six ways today:
- the `Capability` enum;
- `acceptedBy` overrides on four actions;
- the method-less `WakeOnLanAdapter` marker;
- the `GroupListing` and `InputListing` mixins, found with `isInstance`;
- a `DeviceHandle.query` that throws by default;
- two pairing interfaces.

Capabilities are also coarse. `SelectInput` requires `REMOTE_KEYS`, `JoinGroup` and `LeaveGroup` require
`MEDIA_RENDERER`, and Android TV declares `VOLUME` but refuses `SetVolume` and `Mute`. So sessions throw
"unsupported" to drive the router's fall-through. Jellyfin checks `hasAdapter("androidtv")` in five classes.

After this workstream:
- an adapter declares exactly what its sessions carry out, and an action names every capability that can carry it;
- `CommandRouter` asks only adapters that can do a command;
- what a live connection offers beyond commands is found with one typed lookup;
- Wake-on-LAN is a capability;
- Jellyfin asks for a capability instead of an adapter id.

## Decisions (the user's, 2026-09-29)

1. **Pairing stays as it is.**
   - Android TV's `CodePairing` has two steps with a code, and webOS/Tizen's `PromptPairing` has one blocking step.
     Both are already protocol-free `core` interfaces.
   - `web` no longer imports `androidtv`.

   The roadmap's "one pairing interface" is dropped from 3B.
2. **Jellyfin's capability is `ANDROID_APPS`:** the device runs Android apps and opens `market://` and intent links.
3. **Approach A:**
   - exact capabilities, with `requires()` as an any-of set;
   - one `DeviceHandle.feature(Class)` lookup;
   - Wake-on-LAN as a capability;
   - Jellyfin on `ANDROID_APPS`;
   - Bluetooth's `default ->` arm replaced by explicit cases.
4. **Native implementation, no written reviews** of this spec or its plan.

## Constraints

- **Every command a device can carry out still works.** The only behaviour changes are these:
  - a command reaches fewer adapters that cannot do it;
  - a refusal is worded by the router instead of a session;
  - the Jellyfin setup section no longer offers a device whose Android TV module is switched off.
- **No `/data` format changes.** The Wake-on-LAN setting keys keep their names.
- **Sessions keep their exhaustive `switch` over `Action`.** Their refusal arms stay as a safety net.
- `./gradlew build` stays green, the test count only rises, and frozen ArchUnit violations only fall.

## Design

### 1. Capabilities and actions

`Capability` gains `ANDROID_APPS`, `INPUTS`, `GROUPING` and `WAKE_ON_LAN`, and loses `POWER`, which nothing reads.
`JELLYFIN_CLIENT` stays: the Jellyfin resolver adds it for the planner, and no adapter declares it.

Each adapter declares what its sessions carry out:

| Adapter | Declares |
| --- | --- |
| Android TV | `REMOTE_KEYS`, `APP_LINK`, `ANDROID_APPS` |
| webOS | `REMOTE_KEYS`, `APP_LINK`, `VOLUME`, `INPUTS`, `WAKE_ON_LAN` |
| Tizen | `REMOTE_KEYS`, `APP_LINK`, `VOLUME`, `WAKE_ON_LAN` |
| Cast | `CAST_RECEIVER`, `VOLUME` |
| UPnP | `MEDIA_RENDERER`, `VOLUME` |
| Sonos | `MEDIA_RENDERER`, `VOLUME`, `GROUPING` |
| Bluetooth | `LOCAL_AUDIO_SINK`, `VOLUME` |

`Action.requires()` returns `Set<Capability>`: an adapter needs one of them. `acceptedBy(capabilities)` becomes a
single default, "declares any of `requires()`", and the four overrides go.

| Action | Needs one of |
| --- | --- |
| `PressKey` | `REMOTE_KEYS` |
| `OpenAppLink` | `APP_LINK` |
| `SelectInput` | `INPUTS` |
| `SetVolume`, `Mute` | `VOLUME` |
| `Stop` | `CAST_RECEIVER`, `MEDIA_RENDERER`, `LOCAL_AUDIO_SINK` |
| `PlayMedia`, `Pause`, `Resume` | `MEDIA_RENDERER`, `LOCAL_AUDIO_SINK` |
| `JoinGroup`, `LeaveGroup` | `GROUPING` |
| `CastLoad`, `CastMessage` | `CAST_RECEIVER` |

Each action also names, in a short phrase, what it asks of a device (`Action.purpose()`):

| Action | Purpose |
| --- | --- |
| `PressKey` | "take remote keys" |
| `OpenAppLink` | "open app links" |
| `SelectInput` | "switch inputs" |
| `SetVolume` | "change the volume" |
| `Mute` | "mute" |
| `Stop` | "stop playback" |
| `PlayMedia` | "play a stream" |
| `Pause` | "pause" |
| `Resume` | "resume" |
| `JoinGroup`, `LeaveGroup` | "be grouped" |
| `CastLoad`, `CastMessage` | "receive Cast media" |

`CommandRouter` asks only adapters that declare one of `requires()`. When none of the device's adapters does, it
refuses with an `UnsupportedActionException` that reads "<device name> cannot <purpose>", for example "Shield cannot
switch inputs". When some adapter declares it but none could send, the router's existing fall-through reasons apply
unchanged.

Visible effects:
- The dashboard's Power button is unaffected: it sends a remote key.
- Volume on a Shield that also has Cast goes straight to Cast; today Android TV refuses it first.
- On a Shield without Cast, volume is still refused, but the router words the refusal instead of the Android TV
  session.
- Choosing an input on an Android TV or a Samsung TV is refused by the router, and so is grouping a UPnP renderer.
  None of these commands reaches a session any more.

### 2. Features on a live connection

`DeviceHandle` gains `<T> Optional<T> feature(Class<T> type)`. By default it returns the handle itself when the
handle implements `type`, and otherwise nothing. A handle may override it to hand out a separate object.

There are three feature types in `core`:
- `InputListing` (webOS), unchanged;
- `GroupListing` (Sonos), unchanged;
- `ReceiverApps` (Cast), new, with `Map<String, Object> query(CastAppQuery query)`. It replaces the throwing default
  `DeviceHandle.query`, which goes.

Callers use `feature(...)`:
- `RegisteredDevices.inputs` and `speakerTopology`;
- `CommandRouter.query`. A Cast handle without `ReceiverApps` counts as unsupported and hands over, as the thrown
  exception does today.

Each feature pairs with a capability: `INPUTS` with `InputListing`, `GROUPING` with `GroupListing`, and
`CAST_RECEIVER` with `ReceiverApps`.

### 3. Wake-on-LAN

- The `WakeOnLanAdapter` marker goes; webOS and Tizen declare `WAKE_ON_LAN`.
- `AdapterSettingsStore` asks `adapter.capabilities(device).contains(WAKE_ON_LAN)` wherever it tested `instanceof
  WakeOnLanAdapter`.
- The setting keys `macAddress` and `macAddressManual` move, unchanged, into a constants class, `core.WakeOnLan`.

### 4. Jellyfin

The five `hasAdapter("androidtv")` checks become `capabilities(id).contains(ANDROID_APPS)`:

| Class | Change |
| --- | --- |
| `JellyfinPlayableResolver` | Keeps its `REMOTE_KEYS` check; its `adapterEnabled` predicate goes. |
| `JellyfinRouteExecutor` | Its `adapterEnabled("androidtv")` check goes. |
| `JellyfinVlcExecutor` | The check becomes the capability. |
| `JellyfinSetupController` | The check becomes the capability. |
| `JellyfinSetupAdvice` | The check becomes the capability. |

A switched-off adapter declares nothing, so the capability also covers "module switched on".
`DeviceQueries.adapterEnabled` has no other caller outside `device`, so it goes.

### 5. Sessions

Bluetooth's `execute` switch replaces `default ->` with explicit refusal arms, so a new action is a compile error in
every session. The `default` arms in the webOS and Tizen remote-key switches are over `RemoteKey`, not `Action`, and
stay.

## Delivery

One PR, `refactor/device-features`, from main `092919a`. One commit per step, the build green after each:
1. `Capability` and `Action`: the four new values, `POWER` removed, `requires()` as a set, one `acceptedBy`,
   `purpose()`.
2. Exact adapter declarations, and `CommandRouter` refusing up front with a named reason.
3. `DeviceHandle.feature(Class)` and `ReceiverApps`. `InputListing` and `GroupListing` are reached through it.
4. Wake-on-LAN as a capability, with `core.WakeOnLan`.
5. Jellyfin on `ANDROID_APPS`; `adapterEnabled` leaves `DeviceQueries`.
6. Bluetooth's explicit refusal arms.
7. The architecture guide's `core` row.

## Testing

- **`ActionTest`** pins `requires()` and `purpose()` for every action.
- **One test per adapter** pins its capability set against the table in section 1.
- **Router tests:**
  - `SelectInput` on a Shield is refused without reaching the Android TV connection;
  - `JoinGroup` never reaches a UPnP renderer;
  - volume on a Shield with Cast reaches Cast and not Android TV;
  - a refusal names what is missing.
- **Session tests:** webOS offers `InputListing`, Sonos offers `GroupListing`, and Cast offers `ReceiverApps` through
  `feature`. A handle without a feature returns empty.
- **Wake-on-LAN and Jellyfin tests** keep their assertions, with the capability in place of the marker or the adapter
  id. A new test checks that the Jellyfin setup section does not offer a device whose Android TV module is off.

## Measures

- Optional-feature mechanisms go from six to three:
  - capabilities, for what an adapter can do;
  - `feature(Class)`, for what a live connection offers;
  - the two pairing interfaces, for enrolment.
- `hasAdapter("androidtv")` in `sources`: from five to zero.
- `UnsupportedActionException`s thrown only to drive fall-through: none. The session arms stay as a safety net.
- Test count only rises; frozen ArchUnit violations only fall.

## Out of scope

- Merging the pairing interfaces (Decision 1).
- Playback routes, the planner and `PlayableRef` (3C).
- The adapter lifecycle helpers (2D) and adapter layering (3E).
