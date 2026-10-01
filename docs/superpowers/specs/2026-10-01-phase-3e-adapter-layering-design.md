# Phase 3E: Adapter Layering

**Status:** approved in conversation on 2026-10-01, section by section.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "3E: Adapter layering",
after 2D. It meets Phase 4's precondition: an empty frozen ArchUnit store.

## Purpose

Wire protocols are meant to be libraries: they take plain values, know nothing of Spring or the application, and
throw only I/O exceptions; the sessions turn those into what the rest of the system understands. Three gaps remain.

- **25 frozen violations** of `protocolPackagesStandAlone`:

  | Class | Lines | Depends on |
  | --- | --- | --- |
  | `androidtv.protocol.RemoteConnection` | 12 | `core.RemoteKey`, `core.KeyPress` |
  | `cast.protocol.MediaStatus` | 5 | `core.PlaybackState` |
  | `upnp.protocol.ServiceEndpoint` | 4 | `discovery.ssdp.DeviceDescription.Service` |
  | `sonos.protocol.SonosEndpoints` | 2 | `discovery.ssdp.DeviceFetch` |
  | `upnp.protocol.SoapClient` | 1 | `discovery.ssdp.DeviceFetch` |
  | `upnp.protocol.UpnpXml` | 1 | `discovery.ssdp.DeviceDescriptions` |

  The other four frozen rules (package cycles, `sources` → `adapters`, `adapters` → `sources`/`web`, `web` →
  `adapters`) have no violations left.
- **webOS and Tizen have no `protocol` package.** Their connection, REST and DIAL classes sit beside the session and
  take the Spring properties records `WebOsProperties` and `TizenProperties`.
- **Two sessions still do protocol work.** `CastSession` (437 lines) launches receiver apps, waits for namespaces and
  matches replies; `UpnpSession` (280 lines) fetches and checks the device description and SCPD.

## Decisions (the user's, 2026-10-01)

- **Two PRs.** PR 1: the store reaches zero and every rule turns strict. PR 2: the webOS and Tizen protocol packages,
  and the Cast and UPnP choreography moved into their protocol packages.
- **Approach: the SSDP wire helpers become `discovery.ssdp.protocol`.** `DeviceFetch`, `DeviceDescription` and
  `DeviceDescriptions` are used by SSDP discovery and by the Sonos and UPnP protocol code, so they cannot move into
  `adapters.net` without a package cycle. As a protocol package of their own, the existing rule already lets other
  protocol packages use them, and Phase 4's Spring-free `protocols` module picks them up. (Rejected: a new top-level
  `net` package, which the rule would have to name; copies in each protocol package, which would duplicate the
  address-safety check and the hardened XML parser.)
- **All five rules strict, and the freezing machinery removed.** The store, its settings, its CI check and its
  documentation go. ArchUnit's freeze can come back for a future rule that needs it.
- **`RemoteKey` loses its Android key codes.** Android TV maps keys in the adapter, as webOS and Tizen do.
- **Cast's refusals take `DeviceCalls`' wording** ("{name} refused to {what}: {reason}"), as webOS's and Tizen's did
  in 2D.

## Constraints

- Protocol packages (`..protocol..`) depend on neither Spring nor any application package other than `adapters.net`
  and other protocol packages. After PR 1 the rule is strict.
- Protocol classes throw `IOException` and its subclasses: `DeviceTimeoutException`, `DeviceRefusedException` and its
  subclasses, and a protocol's own I/O exceptions. Core exceptions are made only by sessions (through `DeviceCalls`)
  and by the adapters' pairing services.
- Nothing a user sees changes except the visible change at the end of this spec.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` pass at the end of each PR.

## Design

### 1. PR 1: the store reaches zero

**Android TV keys.** `RemoteConnection.sendKey(RemoteKeyCode code, RemoteDirection direction)` takes the generated
protobuf enums it already writes on the wire (`adapters.androidtv.protocol.remote`). A new `AndroidTvKeys` in
`adapters.androidtv` maps:

```java
final class AndroidTvKeys {
    static RemoteKeyCode code(RemoteKey key);          // by name: DPAD_UP → KEYCODE_DPAD_UP, …
    static RemoteDirection direction(KeyPress press);  // SHORT, START_LONG, END_LONG
}
```

`core.RemoteKey` loses its constructor argument and `code()`: the Android numbers were used only by
`RemoteConnection`. The session calls `connection.sendKey(AndroidTvKeys.code(key), AndroidTvKeys.direction(press))`.

**Cast player state.** `MediaStatus.playerState()` returns a protocol enum:

```java
public enum PlayerState { PLAYING, PAUSED, IDLE, BUFFERING } // LOADING and unknown states are BUFFERING
```

`CastSession` maps it to `core.PlaybackState`.

**SSDP wire helpers.** `DeviceFetch`, `DeviceDescription` and `DeviceDescriptions` move unchanged from
`discovery.ssdp` to `discovery.ssdp.protocol`; `SsdpDiscovery`, `SsdpService`, Sonos and UPnP import them from there.
`SonosEndpoints`, `ServiceEndpoint`, `SoapClient` and `UpnpXml` then depend on protocol packages only.

**Rules.** With the store at zero, every rule in `ArchitectureTest` is strict: `protocolPackagesStandAlone`,
`topLevelPackagesAreFreeOfCycles`, `sourcesDoNotDependOnAdapters`, `adaptersDoNotDependOnSourcesOrWeb` and
`webDoesNotDependOnAdapters` lose `freeze(...)`, keeping their descriptions. Removed with them:

- `src/test/archunit-store/` (the five rule files and `stored.rules`);
- the `freeze.*` settings in `src/test/resources/archunit.properties` (the file goes if nothing else is in it);
- the store input of the test task in `build.gradle.kts`;
- the CI step in `.github/workflows/ci.yml` that checks the store is committed;
- the "Frozen violations" section of `docs/dev/architecture.md` and the store bullet in `AGENTS.md`; the CI guide's
  mention of the store.

### 2. PR 2: webOS and Tizen protocol packages

**`adapters.webos.protocol`:** `SsapConnection`, `SsapMessages`, `SsapUris`, `SsapException`,
`SsapPairingException`. Staying in `adapters.webos`: `WebOsPayloads` (it builds `DeviceState` and `TvInput`),
`WebOsKeys`, `WebOsLaunches`, `WebOsLaunch`, `WebOsPairing`, `WebOsSettings`, `WebOsTimings`, the session, the
adapter and the configuration.

**`adapters.tizen.protocol`:** `TizenRemoteConnection`, `TizenMessages`, `TizenRest`, `DialClient`, `DialException`,
`TizenApp`, `TizenDeviceInfo`. Staying in `adapters.tizen`: `TizenKeys`, `TizenLaunches`, `TizenLaunch`,
`TizenPairing`, `TizenSettings`, `TizenTimings`, the session, the adapter and the configuration.

- **No Spring properties.** Each connection or client takes its ports and `Duration`s as plain values. Where several
  travel together, a small protocol record carries them (for example the SSAP port, secure port, connect timeout and
  request timeout). Sessions and pairing services build them from `WebOsProperties` and `TizenProperties`, as the
  `*Timings` records already do.
- **No core helpers.** URLs are built with `java.net.URI`'s multi-argument constructor, which encloses an IPv6
  address in brackets, instead of `core.Hosts.authority`; host validation stays where devices are registered.
  `TizenDeviceInfo` reports the raw Wi-Fi MAC; the session normalises it with `core.MacAddress` before offering it to
  `LearnedMac`.
- **Visibility.** The moved classes and the members their callers use become public.

### 3. PR 2: choreography out of the sessions

**Cast: `adapters.cast.protocol.CastApps`.** (The roadmap's name `ReceiverApps` is taken by the core feature
interface that `CastSession` implements.) It takes a `CastConnection` and the command, load and error-window
`Duration`s, and holds what `CastSession` does today between the caller and the wire:

- start a receiver app unless a given `ReceiverStatus` already lists it, and wait for the status that does;
- wait until a freshly launched app speaks a namespace;
- send a receiver command and expect `RECEIVER_STATUS`;
- connect to an app's transport and load media, expecting `MEDIA_STATUS`;
- send a custom message and treat an error reply within the error window as a refusal;
- send a query and await the asked reply type.

A timeout throws `DeviceTimeoutException`; a refusal throws a new `CastRefusedException extends
DeviceRefusedException` whose message is the receiver's reason. `CastSession` keeps the connection, the receiver and
media state it follows and the position poll, and translates through `DeviceCalls`.

**UPnP: `adapters.upnp.protocol.RendererResolver`.**

```java
public final class RendererResolver {
    public record Renderer(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, // may be null
                           ServiceEndpoint connectionManager, int volumeMax) { }        // may be null
    public RendererResolver(HttpClient http, Duration timeout);
    public Renderer resolve(URI location, String deviceHost, String expectedUdn) throws IOException;
}
```

It keeps today's rules: the description only from the device's own host (`DeviceFetch.isSafeToFetch`), a UDN that
matches when one is expected, services only on the description's host and with a valid service type, the volume
maximum from the SCPD or the default. `UpnpSession` keeps finding the location (announced, else stored) and reading
the sink formats through `RendererCommands`. The resolver's error texts reach only the debug log, without the device
id.

## Testing

- **PR 1:** `AndroidTvKeysTest` (every `RemoteKey` maps to a `RemoteKeyCode`, every `KeyPress` to a direction, with
  spot checks of known codes); a `MediaStatus` player-state test; `DeviceFetchTest` and `DeviceDescriptionsTest` move
  with their classes; `ArchitectureTest` runs every rule strictly. Existing Android TV, Cast, Sonos, UPnP and SSDP
  tests stay green with only import changes.
- **PR 2:** the webOS and Tizen protocol tests (`SsapConnectionTest`, `TizenRemoteConnectionTest`, `TizenRestTest`,
  `DialClientTest`, `TizenMessagesTest`) and the fakes they need move with their classes. `CastAppsTest` against
  `FakeCastReceiver`: launch, namespace wait, load, custom message, query, a refusal with its reason, a timeout.
  `RendererResolverTest` against the UPnP fake renderer: a foreign host, a wrong UDN, a service on another host, a
  malformed service type, the SCPD fallback. Cast tests that pin refusal texts take the new wording.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` at the end of each PR.

## Delivery

**PR 1** (`refactor/adapter-layering`, from main `8a30363`):

1. `refactor:` Android TV maps its keys in the adapter; `RemoteConnection` speaks key codes (store −12).
2. `refactor:` Cast's player state stays in its protocol (store −5).
3. `refactor:` the SSDP wire helpers become `discovery.ssdp.protocol` (store −8).
4. `test:` every architecture rule is strict; the frozen store and its machinery go (with the docs, `AGENTS.md` and
   the CI step).

**PR 2** (planned after PR 1 merges): webOS protocol package, Tizen protocol package, `CastApps` (Cast's refusal
wording changes here), `RendererResolver`, then `docs:` for the architecture guide.

No ADR: freezing can be brought back for a rule that needs it.

## Visible changes

- Cast's refusals read "{name} refused to {what}: {reason}", for example "Living Room TV refused to stop Default Media
  Receiver: INVALID_REQUEST: INVALID_SESSION_ID" instead of "… (INVALID_REQUEST: INVALID_SESSION_ID)", and "Living Room
  TV refused to load the media: LOAD_FAILED" instead of "… could not play it (LOAD_FAILED)".

## Measures

| | Before | After |
| --- | --- | --- |
| Frozen violations | 25 | 0 (no store) |
| Frozen rules | 5 | 0 |
| Packages with wire code outside `protocol` | 3 (`webos`, `tizen`, `discovery.ssdp`) | 0 |
| `CastSession` | 437 lines | about 290 |
| `UpnpSession` | 280 lines | about 210 |

## Out of scope

- `WebOsPayloads`, `TizenLaunches` and `WebOsLaunches`, which build core types or read content links: they stay with
  their sessions.
- Bluetooth (no protocol package; its BlueZ and mpv code is already behind interfaces).
- Phase 4's module split itself.
- The deferred minors of 2D (Reconnector threading contract, Tizen key codes in messages, the executor rule's nested
  classes).
