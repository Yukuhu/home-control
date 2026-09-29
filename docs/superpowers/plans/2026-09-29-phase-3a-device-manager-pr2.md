# Phase 3A PR 2: Callers on the Core Interfaces, `DeviceManager` Deleted — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every caller of `DeviceManager` depends on the narrowest of the four `core` device interfaces, the
application's configuration wires them from one `Devices`, and `DeviceManager` is deleted.

**Architecture:** `Devices` becomes the public assembly of the device collaborators. Its accessors return the four
`core` interfaces, and it owns the lifecycle (`start`, `close`, the discovery listener). `HomeControlConfiguration`
exposes it and the four interfaces as beans. Callers move one package at a time. Until the last one has moved,
`DeviceManager` stays as a bean built from the same `Devices`, and the interface beans are `@Primary`. A strict
ArchUnit rule then keeps every class outside `device` off the package, except the configuration.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit 5, AssertJ, Mockito (BDD style), ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-29-phase-3a-device-manager-design.md` (the "PR 2" part of Delivery, and
Decisions 1 and 2). PR 1 (#141) is merged at `254ee77`.

## Global Constraints

- **Behaviour stays the same.** This PR moves dependencies only; no command, merge, event or state changes.
- **Existing installs upgrade in place.** No `/data` format changes.
- `scripts/gradle.sh build` stays green after every task, the test count only rises (2,926 at `254ee77`), and frozen
  ArchUnit violations only fall.
- **Names:** a `DeviceQueries` parameter or field is named `devices`, a `DeviceCommands` one `commands`, a
  `DeviceEnrollment` one `enrollment`, and a `DeviceSettings` one `deviceSettings`. The last avoids the many
  `settings` already in use.
- A class takes only the interfaces whose methods it calls; the caller table below is the reference.
- Commit messages follow Conventional Commits; stage only the files you changed (`git add <paths>`).

## Review Focus

1. **One `Devices` per application.** A second instance, for example a leftover `DeviceManager` bean assembling its
   own, would connect every TV twice. A TV that takes one connection would then flap. Task 8 pins it: a full-app test
   asserts one `Devices` bean and one bean per interface.
2. **Lifecycle on the bean.** `Devices` must start before `ApplicationReadyEvent`, when discovery starts. It must close
   when the context closes, and its discovery listener must be registered once. Task 3 pins `initMethod` and
   `destroyMethod` on the bean. `DiscoveryStartTest` already pins discovery's start, and `DeviceManager` loses its own
   listener in the same task.
3. **Every module switched off.** The context must still start when no `DeviceAdapter` bean exists: `Devices` is
   assembled over an empty list. This is pinned by the existing `ModulesOffTest` context, which runs in every build.
4. **A web test that asserted "no device was touched".** After the split into four mocks it must still fail if any
   device call happens. Task 6 translates every `verifyNoInteractions(devices)` to all four mocks.
5. **A web test that stubbed a call on the wrong mock.** A stub left on the queries mock for an enrollment or command
   method would answer `null` or be ignored. Task 6's rewrite script moves every stub and verify by method name. The
   step after it greps for leftovers.

## The caller table (at `254ee77`)

| Class | Today | Takes |
| --- | --- | --- |
| `adapters.androidtv.PairingService` | `DeviceManager sessions` (adopt) | `DeviceEnrollment enrollment` |
| `adapters.bluetooth.BluetoothPairingService` | `devices` (adopt, device) | `DeviceQueries devices`, `DeviceEnrollment enrollment` |
| `adapters.bluetooth.BluetoothSetupAdvice` | `manager` (devices, state) | `DeviceQueries devices` |
| `adapters.tizen.TizenPairing` | `devices` (attach, devices) | `DeviceQueries devices`, `DeviceEnrollment enrollment` |
| `adapters.webos.WebOsPairing` | `devices` (attach, devices) | `DeviceQueries devices`, `DeviceEnrollment enrollment` |
| `playback.PlaybackService` | `devices` (capabilities, device, execute) | `DeviceQueries devices`, `DeviceCommands commands` |
| `playback.DeepLinkTestService` | `devices` (capabilities, device, execute, foregroundAppReporting, state) | `DeviceQueries devices`, `DeviceCommands commands` |
| `sources.jellyfin.JellyfinConfiguration` | `devices::adapterEnabled` | `DeviceQueries devices` (and passes `commands` on) |
| `sources.jellyfin.JellyfinRouteExecutor` | `devices` (adapterEnabled, execute, state) | `DeviceQueries devices`, `DeviceCommands commands` |
| `sources.jellyfin.JellyfinSetupAdvice` | `deviceManager` (devices) | `DeviceQueries devices` |
| `sources.jellyfin.JellyfinSetupController` | `devices` (device) | `DeviceQueries devices` |
| `sources.jellyfin.JellyfinVlcExecutor` | `devices` (execute, state) | `DeviceQueries devices`, `DeviceCommands commands` |
| `sources.workflows.WorkflowCastRouteExecutor` | `devices` (capabilities, execute) | `DeviceQueries devices`, `DeviceCommands commands` |
| `sources.youtube.YouTubeLoungeRouteExecutor` | `devices` (query) | `DeviceCommands commands` |
| `sources.youtube.YouTubeSetupAdvice` | `manager` (capabilities, devices) | `DeviceQueries devices` |
| `sources.youtube.YouTubeSetupService` | `ObjectProvider<DeviceManager>` (capabilities, device) | `ObjectProvider<DeviceQueries> devices` |
| `web.ContentPlayController` | `devices` (device) | `DeviceQueries devices` |
| `web.DashboardController` | `devices` (queries only) | `DeviceQueries devices` |
| `web.DeepLinkTestController` | `devices` (device) | `DeviceQueries devices` |
| `web.DeviceController` | `devices` (execute) | `DeviceCommands commands` |
| `web.SetupController` | `devices` (queries, enrollment, settings) | `DeviceQueries devices`, `DeviceEnrollment enrollment`, `DeviceSettings deviceSettings` |
| `web.StateController` | `DeviceManager sessions` (states) | `DeviceQueries devices` |

The module configurations that pass these on are `AndroidTvConfiguration`, `BluetoothConfiguration`,
`TizenConfiguration`, `WebOsConfiguration`, `JellyfinConfiguration`, `WorkflowConfiguration` and
`YouTubeConfiguration`. Four files only mention `DeviceManager` in a comment: `core/playback/Route.java`,
`adapters/sonos/SonosDiscovery.java`, `sources/jellyfin/JellyfinPlayableResolver.java` and
`device/DeviceMatching.java`.

## The rewrite script

Calls move by method name. Put this script in the plan's workspace (not in the repository) as `retarget.py`. It
rewrites every call made through one name onto the interface that declares the method. Constructor parameters,
fields, imports and `verifyNoInteractions` are edited by hand; the script prints every line that still names the old
identifier.

```python
"""Moves calls made through one DeviceManager-typed name onto the core interface that declares each method.

Usage: python3 retarget.py FILE OLD QUERIES COMMANDS ENROLLMENT SETTINGS
Each of the last four is the expression that replaces OLD for that interface's methods, or '-' when the file must
not call it. Handles OLD.m(...), OLD::m, and the Mockito forms verify(OLD[, mode]).m(...), given(OLD).m(...),
when(OLD).m(...). Methods of no interface (start, close, onDiscovered, getIfAvailable, ...) are left alone.
"""
import re
import sys

INTERFACES = {
    "queries": {"devices", "device", "defaultDevice", "state", "states", "capabilities", "foregroundAppReporting",
                "adapterEnabled", "speakerTopology", "inputs"},
    "commands": {"execute", "query"},
    "enrollment": {"adopt", "attach", "addDiscovered", "forget", "merge", "split", "pairable", "addable"},
    "settings": {"wakesOnLan", "wakeOnLanMac", "setWakeOnLanMac"},
}

path, old, *targets = sys.argv[1:]
replacement = dict(zip(INTERFACES, targets))


def target(method):
    for interface, methods in INTERFACES.items():
        if method in methods:
            if replacement[interface] == "-":
                raise SystemExit(f"{path}: {old}.{method} needs the {interface} interface")
            return replacement[interface]
    return None


def call(match):
    receiver = target(match["method"])
    return match[0] if receiver is None else f"{receiver}{match['dot']}{match['method']}"


def mocked(match):
    receiver = target(match["method"])
    return match[0] if receiver is None else f"{match['verb']}({receiver}{match['rest']}){match['dot']}{match['method']}"


text = open(path).read()
text = re.sub(rf"(?<![.\w]){re.escape(old)}(?P<dot>\s*(?:\.|::)\s*)(?P<method>\w+)(?=\s*\(|\b)", call, text)
text = re.sub(rf"\b(?P<verb>verify|given|when)\(\s*{re.escape(old)}\b(?P<rest>[^;]*?)\)(?P<dot>\s*\.\s*)"
              rf"(?P<method>\w+)(?=\s*\()", mocked, text)
open(path, "w").write(text)
for number, line in enumerate(text.splitlines(), 1):
    if re.search(rf"(?<![.\w]){re.escape(old)}\b", line):
        print(f"{path}:{number}: {line.strip()}")
```

For a `Devices` in the device tests, the targets are `X.queries()`, `X.commands()`, `X.enrollment()` and
`X.settings()`. For split mocks they are the mock names `devices`, `commands`, `enrollment` and `deviceSettings`.

---

### Task 1: `Devices` as the public assembly; the collaborators implement the interfaces

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/device/Devices.java` (record → public final class)
- Modify: `src/main/java/dev/andre/homecontrol/device/RegisteredDevices.java`, `CommandRouter.java`,
  `Enrollment.java`, `AdapterSettingsStore.java` (`implements` the interface; its methods `@Override public`)
- Modify: `src/main/java/dev/andre/homecontrol/device/DeviceManager.java` (delegates through the new API; `discovered()`
  and `updateAdapterSettings(...)` removed, since nothing outside `device` calls them)
- Test: `src/test/java/dev/andre/homecontrol/device/DevicesTest.java`

**Interfaces:**
- Produces:
  - `public final class Devices implements AutoCloseable`;
  - `public static Devices assemble(DeviceRegistry, List<DeviceAdapter>, ApplicationEventPublisher)`;
  - accessors `public DeviceQueries queries()`, `public DeviceCommands commands()`, `public DeviceEnrollment
    enrollment()` and `public DeviceSettings settings()`;
  - `public void start()`, `@EventListener public void onDiscovered(DeviceDiscoveredEvent)` and `@Override public void
    close()`.
- `RegisteredDevices implements DeviceQueries`, `CommandRouter implements DeviceCommands`, `Enrollment implements
  DeviceEnrollment`, `AdapterSettingsStore implements DeviceSettings`. The classes stay package-private.

- [ ] **Step 1: Rewrite `DevicesTest` onto the public API.**

```java
    @Test
    void assembleWiresOneConnectionMapForQueriesCommandsAndEnrollment() {
        StubAdapter stub = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false, Capability.REMOTE_KEYS);
        List<Object> published = new CopyOnWriteArrayList<>();
        Action pressHome = new Action.PressKey(RemoteKey.HOME);

        try (Devices devices = Devices.assemble(new JsonFileDeviceRegistry(dir.resolve("devices.json")),
                List.of(stub), published::add)) {
            devices.start();
            devices.enrollment().adopt(new Device("tv", "TV", DeviceKind.ANDROID_TV, "10.0.0.5",
                    Map.of("stub", Map.of()), Instant.EPOCH));
            devices.commands().execute("tv", pressHome);

            assertThat(devices.queries().state("tv").status()).isEqualTo(DeviceStatus.CONNECTED);
            assertThat(stub.handles.get("tv").executed).containsExactly(pressHome);
        }
        assertThat(stub.handles.get("tv").closed).isTrue();
    }
```

- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.DevicesTest'`. Expected: compile
  failure. `Devices` has no `start()` and is not `AutoCloseable`.
- [ ] **Step 3: Implement.** `Devices`:

```java
/**
 * The device collaborators, sharing one registry lock and one connection map, behind the four {@code core}
 * interfaces. {@link #assemble} is the one place they are wired together; the application's configuration exposes
 * each interface as a bean, starts this once the adapters exist, and closes it on shutdown.
 */
public final class Devices implements AutoCloseable {

    private final RegisteredDevices queries;
    private final CommandRouter commands;
    private final Enrollment enrollment;
    private final AdapterSettingsStore settings;
    private final DeviceConnections connections;

    private Devices(RegisteredDevices queries, CommandRouter commands, Enrollment enrollment,
                    AdapterSettingsStore settings, DeviceConnections connections) {
        this.queries = queries;
        this.commands = commands;
        this.enrollment = enrollment;
        this.settings = settings;
        this.connections = connections;
    }

    /** Wires the collaborators over the switched-on adapters, by id; what the handles learn goes to the settings store. */
    public static Devices assemble(DeviceRegistry registry, List<DeviceAdapter> adapters,
                                   ApplicationEventPublisher events) {
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        adapters.forEach(adapter -> byId.put(adapter.id(), adapter));
        RegistryLock lock = new RegistryLock();
        AdapterSettingsStore settings = new AdapterSettingsStore(registry, byId, lock);
        DeviceConnections connections = new DeviceConnections(byId, events, settings::updateAdapterSettings);
        return new Devices(new RegisteredDevices(registry, byId, connections),
                new CommandRouter(registry, byId, connections),
                new Enrollment(registry, byId, connections, lock, events, HostAddresses::lookup),
                settings, connections);
    }

    public DeviceQueries queries() {
        return queries;
    }

    public DeviceCommands commands() {
        return commands;
    }

    public DeviceEnrollment enrollment() {
        return enrollment;
    }

    public DeviceSettings settings() {
        return settings;
    }

    /** Validates, migrates and connects every registered device; see {@link Enrollment#start}. */
    public void start() {
        enrollment.start();
    }

    /** The automatic merge of what discovery announces; see {@link Enrollment#onDiscovered}. */
    @EventListener
    public void onDiscovered(DeviceDiscoveredEvent event) {
        enrollment.onDiscovered(event);
    }

    /** Closes every connection, including one still being completed. */
    @Override
    public void close() {
        connections.closeAll();
    }
}
```

  - Each collaborator: add `implements Device…` and turn each interface method into `@Override public`. Keep the
    other methods package-private: `Enrollment.start`, `onDiscovered` and `discovered`, and
    `AdapterSettingsStore.updateAdapterSettings`.
  - `DeviceManager`:
    - `parts.enrollment().start()` → `parts.start()`;
    - `parts.enrollment().onDiscovered(event)` → `parts.onDiscovered(event)`;
    - `parts.connections().closeAll()` → `parts.close()`;
    - delete `discovered()` and `updateAdapterSettings(...)`.
- [ ] **Step 4:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*'`. Expected: green, same count plus
  none.
- [ ] **Step 5: Commit** `refactor: make Devices the public assembly of the device collaborators behind the core
  interfaces`.

---

### Task 2: The device tests run on `Devices`

**Files:**
- Rename and rewrite (`git mv`):
  - `DeviceManagerTest` → `DevicesTest`, merging in the one test of the existing `DevicesTest`;
  - `DeviceManagerMergeTest` → `DevicesMergeTest`;
  - `DeviceManagerExecuteTest` → `DevicesExecuteTest`;
  - `DeviceManagerFallThroughTest` → `DevicesFallThroughTest`;
  - `DeviceManagerQueryTest` → `DevicesQueryTest`.

  All five are in `src/test/java/dev/andre/homecontrol/device/`.
- Modify: `src/test/java/dev/andre/homecontrol/adapters/tizen/TizenTokenMigrationTest.java`,
  `adapters/webos/WebOsKeyMigrationTest.java`, `adapters/sonos/SonosDiscoveryTest.java` and
  `device/StubAdapter.java` (Javadoc).
- Modify: `DeviceManager.java` (delete the static `uniqueId`, kept only for the fall-through test).

**Interfaces:**
- Consumes: Task 1's `Devices` API.

- [ ] **Step 1: Move the suites.** In each file:
  - `DeviceManager X = new DeviceManager(a, b, c)` → `Devices X = Devices.assemble(a, b, c)`, and the type of every
    field or variable `DeviceManager` → `Devices`;
  - run `retarget.py FILE X X.queries() X.commands() X.enrollment() X.settings()` for each `DeviceManager`-typed name
    (`manager`, `attachManager`, `both`, `speakers`, `manager[0]`: rename `manager[0]` to a local first);
  - rename `manager` to `devices` and `DeviceManagerTest`'s helper `manager(...)` to `devices(...)` where the file has
    no other Java identifier `devices`;
  - `DeviceManager.uniqueId(` → `DeviceMatching.uniqueId(`;
  - Javadoc and comments that say `DeviceManager` name `Devices` or the collaborator instead.

  Assertions do not change.
- [ ] **Step 2: Delete `DeviceManager.uniqueId`**, then run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*'`. Expected: green. The device test count
  is the same as before (the old `DevicesTest` test now lives in the renamed suite).
- [ ] **Step 3: Commit** `test: run the device suites on Devices instead of DeviceManager`, with a body that names the
  five renames.

---

### Task 3: Spring wires one `Devices` and exposes the four interfaces

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java`
- Modify: `src/main/java/dev/andre/homecontrol/device/DeviceManager.java`:
  - no longer a `@Service`, with no `@PostConstruct`, `@PreDestroy` or `@EventListener`, and not `AutoCloseable`;
  - its constructor becomes `DeviceManager(Devices)`;
  - it still implements the four interfaces for the callers not yet moved.
- Create: `src/test/java/dev/andre/homecontrol/HomeControlConfigurationTest.java`

**Interfaces:**
- Produces beans:
  - `Devices devices` (`initMethod = "start"`, `destroyMethod = "close"`);
  - `@Primary DeviceQueries deviceQueries`, `@Primary DeviceCommands deviceCommands`, `@Primary DeviceEnrollment
    deviceEnrollment` and `@Primary DeviceSettings deviceSettings`, each the part of that `Devices`;
  - `DeviceManager deviceManager`, built from the same `Devices`.

  `@Primary` stays until Task 8: `DeviceManager` implements the interfaces too, and must not be picked where both
  exist. In the web slice and in module-switch tests only a `DeviceManager` mock exists, so callers moved in Tasks 4
  and 5 get that mock there.

- [ ] **Step 1: Write the test.**

```java
class HomeControlConfigurationTest {

    @TempDir
    Path dir;

    @Test
    void theFourDeviceBeansArePartsOfOneDevices() {
        HomeControlConfiguration configuration = new HomeControlConfiguration();
        try (Devices devices = configuration.devices(new JsonFileDeviceRegistry(dir.resolve("devices.json")),
                List.of(), event -> { })) {
            assertThat(configuration.deviceQueries(devices)).isSameAs(devices.queries());
            assertThat(configuration.deviceCommands(devices)).isSameAs(devices.commands());
            assertThat(configuration.deviceEnrollment(devices)).isSameAs(devices.enrollment());
            assertThat(configuration.deviceSettings(devices)).isSameAs(devices.settings());
        }
    }

    @Test
    void devicesStartWithTheContextAndCloseWithIt() throws Exception {
        Bean bean = HomeControlConfiguration.class.getMethod("devices", DeviceRegistry.class, List.class,
                ApplicationEventPublisher.class).getAnnotation(Bean.class);

        assertThat(bean.initMethod()).isEqualTo("start");
        assertThat(bean.destroyMethod()).isEqualTo("close");
    }
}
```

- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.HomeControlConfigurationTest'`. Expected:
  compile failure (no `devices`, `deviceQueries`, … methods).
- [ ] **Step 3: Implement.** In `HomeControlConfiguration`:

```java
    /**
     * The device collaborators (spec 3A): started once the registry and every switched-on adapter exist, before
     * discovery starts on application-ready, and closed with the context.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public Devices devices(DeviceRegistry registry, List<DeviceAdapter> adapters, ApplicationEventPublisher events) {
        return Devices.assemble(registry, adapters, events);
    }

    // @Primary until DeviceManager, which implements the four interfaces too, is gone (3A PR 2, Task 8).
    @Bean
    @Primary
    public DeviceQueries deviceQueries(Devices devices) {
        return devices.queries();
    }

    @Bean
    @Primary
    public DeviceCommands deviceCommands(Devices devices) {
        return devices.commands();
    }

    @Bean
    @Primary
    public DeviceEnrollment deviceEnrollment(Devices devices) {
        return devices.enrollment();
    }

    @Bean
    @Primary
    public DeviceSettings deviceSettings(Devices devices) {
        return devices.settings();
    }

    /** For the callers that have not moved onto the four interfaces yet. */
    @Bean
    public DeviceManager deviceManager(Devices devices) {
        return new DeviceManager(devices);
    }
```

  `DeviceManager`:
  - drop `@Service`, `start()`, `onDiscovered(...)`, `close()` and `AutoCloseable`, together with their imports;
  - the constructor becomes `public DeviceManager(Devices parts) { this.parts = parts; }`;
  - the class Javadoc says it only forwards for the callers not moved yet.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green. Every `@SpringBootTest` now starts through the new beans.
  The `ModulesOffTest` context covers an empty adapter list.
- [ ] **Step 5: Commit** `refactor: wire one Devices in the configuration and expose the four device interfaces as
  beans`.

---

### Task 4: The adapters' pairing modules take `DeviceEnrollment` and `DeviceQueries`

**Files:**
- Modify (main): `adapters/androidtv/PairingService.java`, `AndroidTvConfiguration.java`,
  `adapters/bluetooth/BluetoothPairingService.java`, `BluetoothSetupAdvice.java`, `BluetoothConfiguration.java`,
  `adapters/tizen/TizenPairing.java`, `TizenConfiguration.java`, `adapters/webos/WebOsPairing.java` and
  `WebOsConfiguration.java`.
- Modify (test): `adapters/androidtv/PairingServiceTest.java`, `AndroidTvModuleSwitchTest.java`,
  `adapters/bluetooth/BluetoothPairingServiceTest.java`, `BluetoothModuleSwitchTest.java`,
  `adapters/tizen/TizenPairingTest.java` and `adapters/webos/WebOsPairingTest.java`.

**Interfaces:**
- Produces:
  - `PairingService(CertificateStore, DeviceEnrollment enrollment, DataDirectory)`;
  - `BluetoothPairingService(BluezClient, DeviceQueries devices, DeviceEnrollment enrollment, BluetoothProperties[,
    Clock])`;
  - `BluetoothSetupAdvice(DeviceQueries devices, …)`;
  - `TizenPairing(TizenProperties, DeviceQueries devices, DeviceEnrollment enrollment, DeviceSecrets)`;
  - `WebOsPairing(WebOsProperties, SsdpDiscovery, DeviceQueries devices, DeviceEnrollment enrollment,
    DeviceSecrets)`.

- [ ] **Step 1: Move the tests first.**
  - Each `mock(DeviceManager.class)` becomes the mocks of the interfaces its class takes, named as in Global
    Constraints.
  - Run `retarget.py FILE devices devices - enrollment -` (`sessions` in `PairingServiceTest`).
  - `AndroidTvModuleSwitchTest`: `@Autowired DeviceManager devices` → `@Autowired DeviceEnrollment enrollment`, then
    run `retarget.py`.
  - `BluetoothModuleSwitchTest`: `.withBean(DeviceManager.class, …)` → one `.withBean` per interface
    `BluetoothConfiguration` needs (`DeviceQueries`, `DeviceEnrollment`).
- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.*'`. Expected: compile failures in the
  moved tests (the constructors still take `DeviceManager`).
- [ ] **Step 3: Move the classes** to the signatures above.
  - Run `retarget.py` on each main file for its old name (`sessions`, `devices`, `manager`).
  - Fix constructor parameters, fields and imports by hand.
  - Pass the new beans in the four configurations.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green, and `grep -rn DeviceManager
  src/main/java/dev/andre/homecontrol/adapters` prints only `SonosDiscovery`'s comment.
- [ ] **Step 5: Commit** `refactor: let the pairing modules enroll devices through DeviceEnrollment`.

---

### Task 5: The content sources take the narrow interfaces

**Files:**
- Modify (main):
  - `sources/jellyfin/JellyfinConfiguration.java`, `JellyfinRouteExecutor.java`, `JellyfinSetupAdvice.java`,
    `JellyfinSetupController.java`, `JellyfinVlcExecutor.java`, and `JellyfinPlayableResolver.java` (comment);
  - `sources/workflows/WorkflowCastRouteExecutor.java`, `WorkflowConfiguration.java`;
  - `sources/youtube/YouTubeConfiguration.java`, `YouTubeLoungeRouteExecutor.java`, `YouTubeSetupAdvice.java`,
    `YouTubeSetupService.java`.
- Modify (test):
  - `sources/jellyfin/JellyfinPlayableResolverTest.java`, `JellyfinRouteExecutorTest.java`,
    `JellyfinVlcExecutorTest.java`;
  - `sources/workflows/WorkflowCastRouteExecutorTest.java`, `WorkflowContentSourceTest.java`,
    `WorkflowModuleSwitchTest.java`;
  - `sources/youtube/YouTubeLoungeRouteExecutorTest.java`, `YouTubeSetupServiceTest.java`.

**Interfaces:**
- Produces:
  - `JellyfinRouteExecutor(JellyfinSessions, DeviceQueries devices, DeviceCommands commands, Duration[, …])`;
  - `JellyfinVlcExecutor(JellyfinSetupService, JellyfinClient, DeviceQueries devices, DeviceCommands commands,
    Duration)`;
  - `WorkflowCastRouteExecutor(WorkflowStore, WorkflowRunner, DeviceQueries devices, DeviceCommands commands,
    RailPreferences)`;
  - `YouTubeLoungeRouteExecutor(DeviceCommands commands, LoungeClient, YouTubeSetupService)`;
  - `YouTubeSetupService(…, ObjectProvider<DeviceQueries> devices)`;
  - `JellyfinSetupAdvice`, `JellyfinSetupController` and `YouTubeSetupAdvice` take `DeviceQueries devices`.

- [ ] **Step 1: Move the tests first.**
  - Split each `mock(DeviceManager.class)` into `DeviceQueries devices` plus `DeviceCommands commands` where the class
    sends commands.
  - Run `retarget.py FILE devices devices commands - -`, and `manager` in `YouTubeSetupServiceTest`.
  - `WorkflowModuleSwitchTest`: one `.withBean` per interface `WorkflowConfiguration` needs.
- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.*'`. Expected: compile failures in
  the moved tests.
- [ ] **Step 3: Move the classes** to the signatures above.
  - Run `retarget.py` on each main file, and edit the declarations by hand.
  - Update the three configurations.
  - `JellyfinPlayableResolver`'s comment names `DeviceQueries.adapterEnabled`.
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green. `grep -rn DeviceManager
  src/main/java/dev/andre/homecontrol/sources` prints nothing.
- [ ] **Step 5: Commit** `refactor: let the content sources see devices through DeviceQueries and DeviceCommands`.

---

### Task 6: Playback and the web layer take the narrow interfaces; the web slice mocks them

**Files:**
- Modify (main):
  - `playback/PlaybackService.java` (all three constructors), `playback/DeepLinkTestService.java`;
  - `web/ContentPlayController.java`, `DashboardController.java`, `DeepLinkTestController.java`,
    `DeviceController.java`, `SetupController.java`, `StateController.java`.
- Modify (test):
  - `testsupport/WebSliceTest.java`;
  - every class that extends it and names `devices` (20 files; `grep -rlE "extends WebSliceTest" src/test/java | xargs
    grep -lw devices`);
  - `web/ContentPlayWithoutPinsTest.java`, `playback/PlaybackServiceTest.java`, `playback/DeepLinkTestServiceTest.java`;
  - `web/DashboardPageTest.java` (comment).

**Interfaces:**
- Produces:
  - `PlaybackService(DeviceQueries devices, DeviceCommands commands, PlaybackPlanner planner, …)`;
  - `DeepLinkTestService(DeviceQueries devices, DeviceCommands commands, DeepLinkTestProperties)`;
  - `SetupController(…, DeviceQueries devices, DeviceEnrollment enrollment, DeviceSettings deviceSettings, …)`;
  - `DeviceController(…, DeviceCommands commands, …)`, and every other controller takes `DeviceQueries devices`.
- `WebSliceTest` offers `protected DeviceQueries devices`, `protected DeviceCommands commands`, `protected
  DeviceEnrollment enrollment` and `protected DeviceSettings deviceSettings`, all `@MockitoBean`.

- [ ] **Step 1: Move the tests first.**
  - `WebSliceTest`: replace the `DeviceManager` mock with the four mocks above, and run `retarget.py` on it (its
    `stubSafeDefaults` stubs `pairable`, `addable` and the like).
  - Run `retarget.py FILE devices devices commands enrollment deviceSettings` on each subclass and on the three plain
    tests.
  - Split `ContentPlayWithoutPinsTest`'s, `PlaybackServiceTest`'s and `DeepLinkTestServiceTest`'s `DeviceManager`
    mock into the mocks their class takes.
  - Every `verifyNoInteractions(devices)` or `verifyNoMoreInteractions(devices)` becomes the same call on all four
    mocks, so "nothing touched a device" keeps its meaning.
  - Then `grep -rnE "(given|when|verify)\(devices[,)]" src/test/java` and check each remaining hit calls a
    `DeviceQueries` method.
- [ ] **Step 2:** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.playback.*'
  --tests 'dev.andre.homecontrol.sources.*'`. Expected: failures. The slice context cannot start while controllers
  still ask for `DeviceManager`, and the playback tests do not compile.
- [ ] **Step 3: Move the classes** to the signatures above. Run `retarget.py` on each and edit the declarations by
  hand (`StateController`'s `sessions` becomes `devices`).
- [ ] **Step 4:** `scripts/gradle.sh build`. Expected: green. `grep -rln DeviceManager src/main/java` prints only
  `HomeControlConfiguration`, `DeviceManager` itself and the comment files of Task 8.
- [ ] **Step 5: Commit** `refactor: let playback and the web layer use the narrow device interfaces, and mock them in
  the web slice`.

---

### Task 7: The full-application and browser tests use the interface beans

**Files:**
- Modify (test):
  - `CastDisabledSmokeTest.java`;
  - `testsupport/FullAppReset.java`, `FullAppResetTest.java`;
  - `web/BluetoothJellyfinEndToEndTest.java`, `BluetoothSpeakerEndToEndTest.java`, `CastEndToEndTest.java`,
    `DeviceStateStreamEndToEndTest.java`, `JellyfinEndToEndTest.java`, `SpeakerJellyfinEndToEndTest.java`,
    `SpeakersEndToEndTest.java`, `SportsEndToEndTest.java`, `StreamingLaunchersEndToEndTest.java`,
    `TizenEndToEndTest.java`, `WebOsEndToEndTest.java`, `WorkflowEndToEndTest.java`, `YouTubeEndToEndTest.java`;
  - `src/e2e/java/dev/andre/homecontrol/e2e/E2eApplicationTest.java`.

- [ ] **Step 1: Move each file.**
  - `@Autowired DeviceManager devices` (or `sessions`) → `@Autowired DeviceQueries devices`, plus `@Autowired
    DeviceEnrollment enrollment` where the file adopts or forgets.
  - Run `retarget.py FILE devices devices - enrollment -` (`sessions` in `DeviceStateStreamEndToEndTest`).
  - `FullAppReset.reset`: `app.getBean(DeviceQueries.class)` and `app.getBean(DeviceEnrollment.class)`.
- [ ] **Step 2:** `scripts/gradle.sh build`, then `scripts/gradle.sh compileE2eJava`. Expected: both green.
  `grep -rln DeviceManager src/test/java src/e2e/java` prints only `CycleViolationsTest`, whose strings are sample
  violation text.
- [ ] **Step 3: Commit** `test: let the full-application tests reach devices through the interface beans`.

---

### Task 8: `DeviceManager` deleted; a strict rule keeps callers off `device`

**Files:**
- Delete: `src/main/java/dev/andre/homecontrol/device/DeviceManager.java`
- Modify: `HomeControlConfiguration.java` (drop the `deviceManager` bean and the four `@Primary`)
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java` (the new rule)
- Modify: `src/test/java/dev/andre/homecontrol/testsupport/FullAppResetTest.java` (one-`Devices` test)
- Modify (comments that name the device manager):
  - `core/playback/Route.java`, `adapters/sonos/SonosDiscovery.java`, `device/DeviceMatching.java`;
  - `core/LearnedSettings.java`, `core/DeviceAdapter.java`, `core/PromptPairing.java`;
  - `device/JsonFileDeviceRegistry.java`;
  - `adapters/webos/WebOsSession.java`, `WebOsAdapter.java`, `adapters/tizen/TizenSession.java`, `TizenAdapter.java`.
- Modify (docs): `docs/dev/architecture.md` (the `device` row, the dependency diagram, the progress measures).

- [ ] **Step 1: Write the one-`Devices` test** in `FullAppResetTest`, which already starts the full application:

```java
    @Test
    void theApplicationHoldsOneDevicesAndOneBeanPerDeviceInterface() {
        assertThat(context.getBeansOfType(Devices.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceQueries.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceCommands.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceEnrollment.class)).hasSize(1);
        assertThat(context.getBeansOfType(DeviceSettings.class)).hasSize(1);
    }
```

  (Use the test class's existing `ApplicationContext`, or autowire one.)
- [ ] **Step 2: Watch it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.testsupport.FullAppResetTest'`. Expected: FAIL with two `DeviceQueries` beans
  (`deviceQueries`, `deviceManager`).
- [ ] **Step 3: Write the rule without its exception, and watch it fail.**

```java
    @ArchTest
    static final ArchRule onlyTheConfigurationReachesIntoDevice = noClasses()
            .that().resideOutsideOfPackage("dev.andre.homecontrol.device..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.device..")
            .because("callers see devices through the four core interfaces; only the application's configuration "
                    + "wires the device package");
```

  Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`. Expected: FAIL, naming only
  `HomeControlConfiguration`. Then add `.and().doNotBelongToAnyOf(HomeControlConfiguration.class)` after the `that()`
  clause.
- [ ] **Step 4: Delete.**
  - `git rm` `DeviceManager.java`, and drop the `deviceManager` bean, the four `@Primary` and their comment.
  - Reword the comments listed above. "The device manager" becomes what now does the job: `Devices`, `Enrollment`,
    `DeviceConnections` or "the device package".
  - `docs/dev/architecture.md`:
    - The `device` row loses the sentence about `DeviceManager` and gains: "`HomeControlConfiguration` exposes the
      four interfaces as beans; nothing else outside `device` depends on it (`ArchitectureTest`)."
    - The diagram loses `sources --> device`, `web --> device`, `playback --> device` and `adapters --> device`. A
      sentence under it says that only the application's configuration in the root package reaches into `device`.
- [ ] **Step 5:** `scripts/gradle.sh build`. Expected: green, with at least 2,929 tests.
  - `grep -rn "DeviceManager" src docs --include=*.java --include=*.md | grep -v docs/superpowers` prints only
    `CycleViolationsTest`'s sample strings.
  - Then run `scripts/gradle.sh compileE2eJava` and `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 6: Commit** `refactor: delete DeviceManager and keep every caller off the device package`.
