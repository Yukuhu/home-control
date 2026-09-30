# Architecture

How the code is organised, the rules the build enforces on it, and how the architecture roadmap
([spec](../superpowers/specs/2026-09-27-architecture-roadmap-design.md)) is measured.

## Package map

All code lives under `dev.andre.homecontrol`, with `HomeControlApplication` and `HomeControlConfiguration` at its
root.

| Package | Holds |
| --- | --- |
| `config` | The configuration root (`HomeControlProperties`), the list of modules that can be switched off (`Module`, `@ConditionalOnModule`), `SetupSection`, which a module extends to add its section (template `fragments/<id>-setup`) to the setup page, `Json`, the one JSON mapper the application's own code uses (it refuses nesting deeper than 64 levels; device protocols in `adapters` keep their own), and `LegacyPropertyNames`, which keeps renamed configuration keys working. |
| `core` | The domain model every other package builds on: devices, actions and device states, the adapter contract (`DeviceAdapter`, `DeviceHandle`, and `AdapterDiscovery` for adapters that find devices on the network), capabilities (what an adapter declares its sessions carry out; each action names the ones that can carry it), connection features found with `DeviceHandle.feature` (`InputListing`, `GroupListing`, `ReceiverApps`), and what the rest of the application may ask of devices (`DeviceQueries`, `DeviceCommands`, `DeviceEnrollment`, `DeviceSettings`). `core.content` holds content sources, items and rails. `core.playback` holds the references every source shares, the five device routes, the open extension points `SourceRef` and `DelegatedRoute` that sources implement, and the playback planner with its preference ladder (`Rung`). It depends only on the JDK. |
| `device` | The known devices, their connections and state, merging what discovery finds, and sending commands to a device's adapters. `Devices.assemble` wires one collaborator per job: `RegisteredDevices` answers queries, `CommandRouter` sends commands, `Enrollment` adds, merges, splits and forgets devices, `AdapterSettingsStore` keeps Wake-on-LAN and learned settings, and `DeviceConnections` holds one handle per device and adapter. `DeviceMatching` holds the pure rules that decide which device a discovered one belongs to. Enrollment decides under one registry lock, but resolves host names before it and connects and publishes events after it. At startup it lets each adapter check and bring up to date its own settings (`DeviceAdapter.validate`, `migrate`). `HomeControlConfiguration` exposes the four `core` interfaces as beans; nothing else outside `device` depends on it. `JsonFileDeviceRegistry` stores the paired devices in `devices.json`. |
| `adapters` | One package per device protocol: `androidtv`, `cast`, `webos`, `tizen`, `upnp`, `sonos`, `bluetooth`. Each is a module that can be switched off, with its wire protocol in a `protocol` subpackage where it has one. `adapters.net` (TLS, WebSockets, Wake-on-LAN), `adapters.links` (content ids in service links) and `adapters.support` (TV pairing keys kept as device secrets) are shared. |
| `discovery` | mDNS and SSDP discovery. Both start listening once the application is ready, so every listener of a `DeviceDiscoveredEvent` exists before the first one is published. |
| `sources` | One package per content source: `jellyfin`, `youtube`, `tmdb`, `sports`, `pinned`, `workflows`. Each is a module that can be switched off. A source that plays through its own references declares them, their routes and its route strategy in its package, and runs a content service's playback API with a `RouteExecutor`; see [ADR 0004](../adr/0004-device-control-from-sources.md). `sources.http` is shared: `GuardedHttpClient`, the one way a source reaches the network (pinned to the addresses `OutboundAddressPolicy` approves, one deadline, a body cap, failures that name only the host), and `HttpUrls`, the one parser for outbound links; see [ADR 0005](../adr/0005-outbound-http-for-content-sources.md). |
| `content` | The rail cache, search across sources, and source preferences. |
| `playback` | `PlaybackService`, which plans a route for an item on a device, carries it out and reports the outcome, and the deep-link test. |
| `web` | Controllers, view models and the event stream behind the dashboard and setup pages. The setup page lists the devices and content sources sections of whichever modules are on, from their `SetupSection` beans, and every page shares its head, header and first-password fields from `templates/fragments/layout.html`. No page carries script of its own: each page's code is in its ES module under `static/js`. `ErrorAdvice` answers every controller's core exceptions with one status and a plain-text body, for example 404 for an unknown device. `EventStream` sends each open tab device state, rail updates and rail lists over server-sent events, with a heartbeat comment so a reverse proxy keeps a quiet stream open. |
| `security` | Login, the host allowlist, cross-origin protection and the security headers, among them a Content-Security-Policy that allows scripts only from this server. A controller that needs the login takes a `LoginContext` argument and passes it on; `LoginService` and services and stores decide with it, so nothing outside the web edge reads the HTTP request or session. |
| `storage` | The data directory, the one writer for its files (`AtomicFiles`), the versioned JSON files the stores hold (`VersionedJsonFile`), the encrypted secret store and its key, and source settings. See [ADR 0002](../adr/0002-versioned-data-files-and-device-secrets.md). |
| `crypto` | Argon2id hashing for the login password and the secret key. |

## Dependencies

The dependencies between the top-level packages that the code has and the rules allow. Every package may also depend
on `core`, which depends on nothing, and on `config`, the configuration root; those arrows are left out. The frozen violations below are the only other
dependencies, and the roadmap removes them.

```mermaid
flowchart TD
    sources --> content
    sources --> security
    sources --> storage
    web --> playback
    web --> content
    web --> security
    web --> storage
    content --> storage
    device --> storage
    adapters --> discovery
    adapters --> storage
    security --> storage
    security --> crypto
    storage --> crypto
```

No top-level package depends on `device`. Only the application's configuration in the root package does: it
assembles the device package and exposes the four `core` device interfaces as beans, through which every other
package sees devices.

## Package rules

`src/test/java/dev/andre/homecontrol/ArchitectureTest.java` checks these rules on every build.

| Rule | Status |
| --- | --- |
| `core` depends only on the JDK | strict |
| Content sources are independent of each other, apart from the shared `sources.http` | strict |
| Device adapters are independent of each other, apart from the shared `adapters.net`, `adapters.links` and `adapters.support`, and Sonos using `adapters.upnp.protocol` | strict |
| `java.net.http`, Apache HttpClient 5, jmDNS and D-Bus are used only in `adapters`, `sources` and `discovery` | strict |
| Inside `sources`, only `sources.http` uses `java.net.http` or Apache HttpClient 5 | strict |
| Outside `adapters`, only `config` builds a `JsonMapper` | strict |
| Nothing outside `device` depends on it, except the application's configuration (`HomeControlConfiguration`): callers use the four `core` device interfaces | strict |
| `jakarta.servlet` is used only in `web`, `security`, controllers and controller advice | strict |
| `..protocol..` packages depend on neither Spring nor any application package other than `adapters.net` and other protocol packages | frozen: 42 |
| No cycles between the top-level packages | frozen: 0 |
| `sources` does not depend on `adapters` | frozen: 0 |
| `adapters` depends on neither `sources` nor `web` | frozen: 0 |
| `web` does not depend on `adapters` | frozen: 0 |

`src/test/java/dev/andre/homecontrol/TestArchitectureTest.java` checks one rule on the tests: tests under `core` do
not use `sources`. A source's own types are tested in its module; tests that need every module, such as the route
keys and the application's preference ladder, sit in `playback`.

## Frozen violations

A frozen rule records the violations it had when it was frozen in `src/test/archunit-store/`. It fails only on new
ones.

- **Fixing a violation** removes it from the store the next time the tests run. Commit the smaller store with the fix.
  CI fails when a build leaves the store changed.
- **A frozen violation whose text changes** is reported as new, and the failing run drops the old entry from the store.
  This happens when a method with a frozen violation is renamed or gains a parameter. Copy the violation from the
  failure message into that rule's store file, in place of the old line and in the same commit as the change, so the
  review sees a one-for-one swap. `stored.rules` names each rule's file, and each violation is one line.
- **After a failing run,** restore the store (`git checkout -- src/test/archunit-store`) before running again, unless
  you meant to change it: a failing run can already have removed entries.
- **Never refreeze to make a build pass.** `freeze.refreeze` stays `false`.
- **Changing a frozen rule's description** (its `because` text included) makes ArchUnit treat it as a new rule and
  record all of its current violations again. Delete the old rule's line in `stored.rules` and its file in the same
  commit. The store diff shows the change, so review it like code.
- **Package cycles are frozen per cycle**, by the packages they run through (`CycleViolations`). ArchUnit lists up to
  20 concrete dependencies under each cycle, and an unrelated edit along the cycle would otherwise make a known cycle
  look new. So a new dependency along an already frozen cycle is not reported; only a new cycle is. Removing a
  back-edge breaks its cycle and removes the entry.

The number of frozen violations only goes down. It is the progress measure for the roadmap's Phase 2 and 3
workstreams, which remove them.

## Progress measures

The roadmap's measures, updated by each workstream that moves them.

| Measure | Baseline (2026-09-27) | Now |
| --- | --- | --- |
| Frozen ArchUnit violations | 89 | 42 |
| Largest class | 813 lines (`DeviceManager`) | 526 lines (`CastSession`) |
| HTTP request loops in content sources | 6 | 1 (`GuardedHttpClient`) |
| `JsonMapper` builders | 20 | 5 (`config.Json` and four adapter mappers) |
| Summed test-class time | 495 s (one JVM) | 320 s (one JVM), 569 s (four JVMs) |
| `test` task wall time | not measured | 3 min 27 s (four JVMs, 4 CPUs) |
| Spring context starts per test run | 67 (one JVM) | 22 (four JVMs: 7, 7, 5, 3) |
| CI "Build and test" job time | about 9 min | 3 min 45 s |
| Wall-clock upper-bound assertions | 9 | 1 |
| Copies of `MutableClock` | 5, one of them nested in `SsdpDiscoveryTest` | 1 |

Since #117 the unit tests run in up to four JVMs at once. Summed class time and context starts count all of them, and
a class takes longer while it shares the CPUs, so compare runs with the same number of JVMs.

Context starts are Spring Boot's `Started …` log lines in the test results, nested test classes included. The 43
recorded before Phase 1.3d-3 left out two nested ones. Each JVM now starts one web slice, one modules-off context and
one full-application context. The rest are the nine tests listed in `SharedContextRulesTest.OWN_CONTEXT` and
`BluetoothClassLoadingTest`, which starts the application in a JVM of its own. The `test` task's wall time did not fall
with the context starts.

The nine counted at the baseline are now outcome assertions. `EventStreamShutdownEndToEndTest`, added since, keeps
its bound, because the elapsed time of closing the application is the behaviour it tests.

`YouTubeEndToEndTest` took about 13 s: 4.7 s of Spring context start, 3.2 s of certificate generation and a real Cast
connect before its first request, and 4.7 s for the rest of the journey, about 2–3 s of it OAuth polling at the
production floor of one poll a second. Since Phase 1.3d-3 it shares the full-application context and takes 3.6 s in a
one-JVM run.
