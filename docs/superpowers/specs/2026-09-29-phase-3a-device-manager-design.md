# Phase 3A: Decompose `DeviceManager`

**Status:** approved in conversation on 2026-09-29, section by section. The user chose to skip the written spec and
plan reviews, as for 2A and 2B, and to implement natively.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "3A: Decompose
`DeviceManager`".

## Purpose

`DeviceManager` holds about 870 lines and one lock for every device. It carries five jobs:
- the connections and composed states;
- routing commands;
- enrollment: adopt, attach, add, forget, the automatic merge, manual merge and split;
- learned settings and the Wake-on-LAN MAC;
- read-only queries.

The lock covers work that can wait on the network or on other code:
- a DNS lookup in `attach`;
- `adapter.connect`;
- events published from adapter callbacks;
- fsync'd registry, secret and keystore writes.

Four slightly different rules decide which device a receiver belongs to. Discovery can start before its listener
exists, which `DiscoveryCatchUp` papers over.

After this workstream:
- each job lives in its own class, behind the narrowest interface its callers need;
- no lock covers DNS, `adapter.connect` or event publication;
- the discovery paths share one matching rule;
- discovery starts only once the application is ready;
- `DeviceManager` is gone.

## Decisions (the user's, 2026-09-29)

1. **End state:** every caller depends on the narrowest interface it uses, and `DeviceManager` is deleted.
2. **Two pull requests.**
   - PR 1 splits the internals behind the unchanged `DeviceManager` API and fixes the locking. The existing
     `DeviceManager` tests pass unchanged.
   - PR 2 moves the callers and the tests onto the new interfaces and deletes `DeviceManager`.
3. **Locking approach A:** one registry lock, with the slow work moved out of it. Per-device locks and a
   single-mutation-thread design were rejected as heavier than the problem.
4. **Matching:** one rule for the three discovery paths (add, automatic merge, absorb on adopt). `attach` keeps its
   host-only rule.
5. **No written reviews** of this spec or its plans; native implementation.

## Constraints

- **Behaviour stays the same** except for:
  - the matching rule's veto on add and automatic merge (Decision 4);
  - discovery starting at application-ready rather than at bean creation.
- **Commands are ephemeral.** A command during a reconnect fails at once with "not connected", as today.
- **Existing installs upgrade in place.** No `/data` format changes.
- `./gradlew build` stays green, the test count only rises, and frozen ArchUnit violations only fall.

## Design

### 1. Interfaces and collaborators

**Four interfaces in `core`**, so adapters, web and sources depend only on the domain model:

| Interface | Methods | Main users |
| --- | --- | --- |
| `DeviceQueries` | `devices()`, `device(id)`, `defaultDevice()`, `state(id)`, `states()`, `capabilities(id)`, `foregroundAppReporting(id)`, `adapterEnabled(adapterId)`, `speakerTopology(id)`, `inputs(id)` | dashboard, playback, Jellyfin, YouTube, setup advice |
| `DeviceCommands` | `execute(id, action)`, `query(id, query)` | device controller, playback, route executors |
| `DeviceEnrollment` | `adopt(device)`, `attach(host, name, kind, adapterId, settings)`, `addDiscovered(adapterId, host, port)`, `forget(id)`, `merge(targetId, sourceId)`, `split(id, adapterId)`, `pairable()`, `addable()` | pairing modules, setup page |
| `DeviceSettings` | `wakesOnLan(id)`, `wakeOnLanMac(id)`, `setWakeOnLanMac(id, mac)` | setup page |

**Implementations in `device`**, package-private where Spring and the tests allow:

- **`DeviceConnections`** owns the handle map, the reported states and `Generation`. It offers:
  - `begin(device)`, which returns a `Connecting` ticket;
  - `complete(ticket)`;
  - `end(id)`, which returns the handles to close;
  - `closeAll()`;
  - the handles and composed state of a device.

  Its own lock guards only these maps.
- **`CommandRouter`** implements `DeviceCommands`: execute, query, stop-everywhere and `FallThrough`, moved as they
  are. Lock-free.
- **`Enrollment`** implements `DeviceEnrollment`, the automatic merge on discovery (`onDiscovered`), and the startup
  `validate`/`migrate`. It owns the registry lock.
- **`DeviceMatching`** holds pure functions over a snapshot: the shared discovery rule, the `attach` rule,
  `uniqueId`, `keepOtherAdapters` and `sameName`. It uses no registry, adapter or DNS.
- **`AdapterSettingsStore`** implements `DeviceSettings`, and the handles' `LearnedSettings` through
  `updateAdapterSettings`. It rewrites device entries under the same registry lock.
- **`RegisteredDevices`** implements `DeviceQueries` from the registry and `DeviceConnections`.
- **`Devices`** has a public `assemble(DeviceRegistry, List<DeviceAdapter>, ApplicationEventPublisher)` that builds and
  wires all of them into one record. Spring's configuration and the tests in other packages use it.

### 2. The lock, connecting and events

- **Two locks, in a fixed order.** The registry lock comes first; `Enrollment` and `AdapterSettingsStore` share it.
  The connections lock comes second and lives inside `DeviceConnections`. Nothing takes the registry lock while
  holding the connections lock. Both cover only in-memory work and local disk writes.
- **Every enrollment operation runs in three phases.**
  1. **Before the lock:** a registry snapshot and the adapters' discovered lists. For `attach`, it also resolves the
     new host and every registered host into an address map (`HostAddresses`).
  2. **Under the registry lock:**
     - re-read the registry and decide with the pre-resolved addresses; a host missing from the map is compared by
       case-insensitive name only, never resolved;
     - write the registry, and run `adapter.forget` or `migrate` (local disk);
     - call `connections.begin(device)` for each device to (re)connect, and `connections.end(id)` for each device
       removed.
  3. **After the lock:** close the handles `end` and `begin` returned, `complete` each ticket, and publish the
     collected events (DISCONNECTED for a forgotten device or a merge source).
- **`begin` and `complete`.**
  - `begin(device)` installs a new `Generation` for the device and takes out its old handles, all under the
    connections lock. Because the caller still holds the registry lock, connects and removals follow the order of the
    registry changes.
  - `complete(ticket)` closes the old handles and calls `adapter.connect` for each adapter, outside every lock. It
    then installs the new handles only if the ticket's generation is still current. If a later `begin` or `end`
    superseded it, it closes them instead.
  - If an adapter fails, the handles opened so far are closed, the device keeps no handles, and DISCONNECTED is
    published after the lock, as today.
- **State events** keep publishing inside their generation's own monitor, so one device's updates stay in order. That
  monitor is never the registry lock.
- **Commands and reads** stay lock-free.
- **Startup:** `validate` runs for every entry first. Then each device is migrated and begun under the registry lock,
  and every ticket completes after the lock is released.

### 3. Matching and discovery

- **The shared discovery rule** (`DeviceMatching.owner`) is used by `addDiscovered`, the automatic merge's second
  step, and absorbing receivers on adopt. A receiver belongs to:
  1. the device at the same host (case-insensitive), among devices without that adapter;
  2. otherwise, the single device with the same name among those devices. The match is vetoed when another
     registered device has that name or sits at the receiver's address.

  The absorb direction (a device choosing among receivers) applies the same checks from the other side.
  - Add gains the veto, so it creates a new device instead.
  - The automatic merge gains it too, so it does nothing.
- **`attach` keeps its rule:** the first device at the same host, even one that already has the adapter (a re-pair);
  otherwise a new device. TV names are too generic to match on.
- **The automatic merge's first step stays:** a device that already carries the receiver by the adapter's identity is
  re-pointed when the receiver moves, never merged.
- **`core.AdapterDiscovery`** takes `id()`, `discovered()`, `settingsFor(found)`, `hostOf(device)`,
  `carries(device, found)` and `credentialsBoundToDeviceId()` from `DeviceAdapter`. Each adapter class implements both.
  `Enrollment` and `RegisteredDevices` (for `pairable`/`addable`) see `AdapterDiscovery`; `DeviceConnections` and
  `CommandRouter` see `DeviceAdapter`.
- **Discovery starts on `ApplicationReadyEvent`:**
  - `MdnsBrowser` starts there instead of in `@PostConstruct`;
  - SSDP starts there instead of through `initMethod`;
  - so does the Android TV `MdnsDiscovery`.

  No `DeviceDiscoveredEvent` can precede its listener, so `DiscoveryCatchUp` and `mergeVisibleReceivers` are deleted.

## Delivery

**PR 1 (`refactor/device-manager`)**, one commit per step, with `DeviceManagerTest`, `DeviceManagerMergeTest`,
`DeviceManagerExecuteTest`, `DeviceManagerFallThroughTest` and `DeviceManagerQueryTest` green after each:
1. `DeviceMatching`, with unit tests.
2. `DeviceConnections` with `begin`, `complete` and `end`.
3. `CommandRouter`, `AdapterSettingsStore` and `RegisteredDevices`.
4. `Enrollment` in three phases, with `HostAddresses` for `attach`.
5. The shared discovery rule. A pinned test that asserts the old difference changes in this commit, named in its
   message.
6. `AdapterDiscovery`.
7. Discovery on `ApplicationReadyEvent`, and `DiscoveryCatchUp` deleted.
8. The four `core` interfaces, implemented by the thin `DeviceManager`.
9. The device row of `docs/dev/architecture.md`.

**PR 2 (`refactor/device-callers`)**:
- every caller takes its narrowest interface;
- the module configurations pass the new beans;
- the web slice mocks the interfaces;
- the device tests move onto the collaborators through `Devices.assemble`, with the same assertions;
- a new strict ArchUnit rule: no class outside `device` depends on it, except the application's configuration
  (`HomeControlConfiguration`);
- `DeviceManager` is deleted, and the docs are updated.

## Testing

- **Unchanged in PR 1:** the 75 tests in the five `DeviceManager` suites. The one deliberate exception is the matching
  commit.
- **`DeviceMatchingTest`:**
  - host before name;
  - an ambiguous name gives no match;
  - the veto when the name or the receiver's address belongs to another device;
  - `attach` matching a device that already has the adapter;
  - `uniqueId` numbering.
- **`DeviceConnectionsTest`:**
  - an adapter whose `connect` blocks on a latch does not delay `begin`, `complete` or `end` for another device;
  - a ticket superseded by `begin` or `end` closes its handles;
  - a failing adapter leaves no handles and publishes DISCONNECTED;
  - a late report from an old generation is ignored.
- **`EnrollmentTest`:**
  - a resolver blocked on a latch while `attach` resolves does not delay `forget` of another device;
  - a forget racing a connect leaves no handle for the forgotten device;
  - add and automatic merge refuse a vetoed name match.
- **Discovery:** a test that mDNS and SSDP have not started before `ApplicationReadyEvent`. The catch-up test becomes a
  test that a receiver resolved at startup is merged by the normal listener.

## Measures

- The largest class in `device` goes from about 870 lines to about 250 (end of PR 2).
- No DNS lookup, `adapter.connect` or event publication runs under a lock, pinned by the tests above.
- Test count only rises; frozen ArchUnit violations only fall; PR 2 adds a strict rule.

## Out of scope

- The adapter lifecycle helpers (2D).
- The device feature contract (3B).
- Playback routes (3C).
- Any change to what commands do.
