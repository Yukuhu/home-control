# Phase 3A, PR 1: Split `DeviceManager` Behind Its API

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `DeviceManager`'s five jobs into focused collaborators behind its unchanged public API. Take DNS,
`adapter.connect` and event publication out from under its locks, unify the discovery matching rule, split
`AdapterDiscovery` out of `DeviceAdapter`, and start discovery on application-ready.

**Architecture:**
- `DeviceManager` becomes a thin façade over `Devices.assemble(...)`, which wires these collaborators in `device`:
  - `DeviceConnections`: handles and generations, with `begin`/`complete`/`end`;
  - `CommandRouter`;
  - `Enrollment`: the registry lock, operations in three phases;
  - `DeviceMatching`: pure rules;
  - `HostAddresses`: DNS resolved before the lock;
  - `AdapterSettingsStore`;
  - `RegisteredDevices`.
- Four `core` interfaces (`DeviceQueries`, `DeviceCommands`, `DeviceEnrollment`, `DeviceSettings`) are introduced and
  implemented by the façade. PR 2 moves callers onto them.

**Tech Stack:** Java 25, Spring Boot 4.1.1, JUnit 5, AssertJ, Awaitility.

**Spec:** `docs/superpowers/specs/2026-09-29-phase-3a-device-manager-design.md`

## Global Constraints

- **Build.** `scripts/gradle.sh` runs Gradle in Docker, and it is quiet: read `build/test-results/test/*.xml`. Done
  means `scripts/gradle.sh build` is green.
- **The safety net.** The five suites `DeviceManagerTest`, `DeviceManagerMergeTest`, `DeviceManagerExecuteTest`,
  `DeviceManagerFallThroughTest` and `DeviceManagerQueryTest` pass **unchanged** after every task. There are two
  exceptions, each named in its commit message:
  - Task 5 may change a test that asserts the old matching difference;
  - Task 7 replaces `receiversResolvedBeforeTheApplicationWasReadyAreMergedOnceItIs`.
- **`DeviceManager`'s public API and constructor stay** as they are: `DeviceManager(DeviceRegistry, List<DeviceAdapter>,
  ApplicationEventPublisher)`. This includes the package-private static `uniqueId`, which `DeviceManagerFallThroughTest`
  calls.
- **Locks.**
  - The registry lock is taken before the connections lock, and never the other way round.
  - Neither lock covers DNS, `adapter.connect`, `DeviceHandle.close` or `publishEvent`.
- **Messages.** Every exception message and log text that callers or tests see stays word for word.
- **Commits.**
  - Conventional Commits; stage only your own paths.
  - Every message ends with:

    ```
    Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
    ```
- **ArchUnit.** `core` depends only on the JDK. Frozen violations only fall.

## Review Focus

1. **A connect racing a forget or a second connect.**
   - A ticket completed after its device was forgotten, or reconnected, must close its handles and never install them.
   - It must never publish a state for a device that no longer exists.
   - Tests: Task 2 `aSupersededTicketClosesItsHandles`, Task 4 `aForgetRacingAConnectLeavesNoHandle`.
2. **Ordering of old and new handles.** A device's old handles must be closed before its new ones connect, as today,
   because some TVs allow one connection. Test: Task 2 `theOldHandlesCloseBeforeTheNewOnesConnect`.
3. **An adapter that reports during `connect`.** A synchronous first report must reach the new generation, not be
   dropped as stale. Test: Task 2 `aReportDuringConnectCounts`.
4. **`attach` with a host that cannot be resolved,** or a device registered while `attach` was resolving. It must fall
   back to comparing by name and never resolve under the lock. Test: Task 4 `aHostAddedWhileResolvingIsComparedByName`.
5. **Shutdown during a connect.** `close()` must leave no handle open that completes after it. Test: Task 2
   `aTicketCompletedAfterCloseAllIsClosed`.

---

### Task 1: `DeviceMatching`, the pure rules

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/device/DeviceMatching.java`
- Delete: `src/main/java/dev/andre/homecontrol/device/DeviceMerge.java`
- Move and rename: `src/test/java/dev/andre/homecontrol/device/DeviceMergeTest.java` → `DeviceMatchingTest.java`
- Modify: `src/main/java/dev/andre/homecontrol/device/DeviceManager.java`, whose `attach`, `bestMatch`,
  `sameName`, `keepOtherAdapters`, `absorbable` and `uniqueId` delegate or move.

**Interfaces:**
- Produces a package-private `final class DeviceMatching` with these static methods:
  - `Device attach(List<Device> registered, String host, String name, DeviceKind kind, String adapterId,
    Map<String, String> settings, Instant now, BiPredicate<String, String> sameHost)`
  - `Optional<Device> bestMatch(List<Device> registered, DiscoveredDevice found)`
  - `Optional<DiscoveredDevice> absorbable(Device device, List<DiscoveredDevice> receivers, List<Device> others)`
  - `boolean sameName(String a, String b)`
  - `Device keepOtherAdapters(Device existing, Device adopted)`
  - `String uniqueId(List<Device> registered, String adapterId, String host)`

- [ ] **Step 1: Move the tests and add the new ones**

`git mv` `DeviceMergeTest.java` to `DeviceMatchingTest.java`. Rename the class and replace `DeviceMerge.attach` with
`DeviceMatching.attach`. Add:

```java
    @Test
    void aReceiverGoesToTheDeviceAtItsAddressBeforeOneWithItsName() {
        Device byHost = device("a", "Kitchen", "10.0.0.5");
        Device byName = device("b", "Living Room", "10.0.0.6");

        assertThat(DeviceMatching.bestMatch(List.of(byName, byHost), receiver("Living Room", "10.0.0.5")))
                .contains(byHost);
    }

    @Test
    void anAmbiguousNameMatchesNothing() {
        assertThat(DeviceMatching.bestMatch(List.of(device("a", "TV", "10.0.0.5"), device("b", "TV", "10.0.0.6")),
                receiver("TV", "10.0.0.9"))).isEmpty();
    }

    @Test
    void aDeviceThatAlreadyHasTheAdapterIsNeverTheMatch() {
        Device withCast = device("a", "TV", "10.0.0.5").withAdapter("cast", Map.of());

        assertThat(DeviceMatching.bestMatch(List.of(withCast), receiver("TV", "10.0.0.5"))).isEmpty();
    }

    @Test
    void uniqueIdsAreNumberedFromTwo() {
        List<Device> taken = List.of(device("cast-10-0-0-5", "A", "10.0.0.5"), device("cast-10-0-0-5-2", "B", "10.0.0.5"));

        assertThat(DeviceMatching.uniqueId(taken, "cast", "10.0.0.5")).isEqualTo("cast-10-0-0-5-3");
    }

    private static Device device(String id, String name, String host) {
        return new Device(id, name, DeviceKind.ANDROID_TV, host, Map.of("androidtv", Map.of()), Instant.EPOCH);
    }

    private static DiscoveredDevice receiver(String name, String host) {
        return new DiscoveredDevice("cast", name, host, 8009, Map.of());
    }
```

(Use the test class's existing imports; add `DiscoveredDevice` and `Optional` if they are missing.)

- [ ] **Step 2: Run it and watch it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.DeviceMatchingTest'`
Expected: compilation FAILS: `cannot find symbol: class DeviceMatching`.

- [ ] **Step 3: Implement**

`DeviceMatching.java`:
- the class Javadoc is `/** Which registered device a paired or discovered adapter belongs to: pure functions over a
  snapshot, no registry, adapters or DNS. */`;
- `attach` is `DeviceMerge.attach`'s body, moved as it is, with its Javadoc and `@SuppressWarnings("java:S107")`;
- `bestMatch`, `sameName`, `keepOtherAdapters`, `absorbable` (made package-private static) and `uniqueId` are
  `DeviceManager`'s bodies, moved as they are, with their Javadocs;
- it calls `DeviceManager.uniqueId` as `DeviceMatching.uniqueId`.

Delete `DeviceMerge.java`. In `DeviceManager`:
- `attach` calls `DeviceMatching.attach`;
- `addDiscovered` and `onDiscovered` call `DeviceMatching.bestMatch`;
- `absorbAddable` calls `DeviceMatching.absorbable`;
- `adopt` calls `DeviceMatching.keepOtherAdapters`;
- delete the moved private and static methods, and keep one delegator for the unchanged test:

```java
    /** Kept for {@code DeviceManagerFallThroughTest}; see {@link DeviceMatching#uniqueId}. */
    static String uniqueId(List<Device> registered, String adapterId, String host) {
        return DeviceMatching.uniqueId(registered, adapterId, host);
    }
```

- [ ] **Step 4: Run and watch them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*'`
Expected: PASS, including all five `DeviceManager` suites unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/device src/test/java/dev/andre/homecontrol/device/DeviceMatchingTest.java src/test/java/dev/andre/homecontrol/device/DeviceMergeTest.java
git commit -m "refactor: gather the device matching rules in one pure class"
```

---

### Task 2: `DeviceConnections` with `begin`, `complete` and `end`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/device/DeviceConnections.java`
- Create: `src/test/java/dev/andre/homecontrol/device/DeviceConnectionsTest.java`
- Create: `src/test/java/dev/andre/homecontrol/device/BlockingAdapter.java` (a `StubAdapter` whose `connect` waits on a
  latch)
- Modify: `DeviceManager.java`, where `handles`, `reported`, `Generation`, `connect`, `tryConnect`, `closeHandles` and
  `close` move out.

**Interfaces:**
- Consumes: nothing new.
- Produces a package-private `final class DeviceConnections`:
  - `DeviceConnections(Map<String, DeviceAdapter> adapters, ApplicationEventPublisher events, LearnedSink learned)`,
    where `interface LearnedSink { void store(String deviceId, String adapterId, Map<String, String> updates); }` is
    nested;
  - `record Connecting(Device device, Generation generation, List<DeviceHandle> previous)`;
  - `Connecting begin(Device device)`: under the connections lock;
  - `void complete(Connecting connecting)`: outside every lock;
  - `List<DeviceHandle> end(String id)`: under the connections lock; the caller closes the returned handles;
  - `void closeAll()`;
  - `Map<String, DeviceHandle> handles(String id)`: unmodifiable, empty when none;
  - `DeviceState state(String id)`.

- [ ] **Step 1: Write the failing tests**

`BlockingAdapter`:

```java
package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceState;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** A {@link StubAdapter} whose {@code connect} waits until {@link #release} for the devices it is told to hold. */
class BlockingAdapter extends StubAdapter {

    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    private final String heldDeviceId;

    BlockingAdapter(String id, String heldDeviceId) {
        super(id, DeviceKind.ANDROID_TV, false, false);
        this.heldDeviceId = heldDeviceId;
    }

    void release() {
        released.countDown();
    }

    @Override
    public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
        if (device.id().equals(heldDeviceId)) {
            entered.countDown();
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return super.connect(device, onChange);
    }
}
```

`DeviceConnectionsTest`:

```java
package dev.andre.homecontrol.device;

class DeviceConnectionsTest {

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    private static Device device(String id, String adapterId) {
        return new Device(id, id, DeviceKind.ANDROID_TV, "10.0.0.5", Map.of(adapterId, Map.of()), Instant.EPOCH);
    }

    private DeviceConnections connections(DeviceAdapter... adapters) {
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        for (DeviceAdapter adapter : adapters) {
            byId.put(adapter.id(), adapter);
        }
        return new DeviceConnections(byId, published::add, (deviceId, adapterId, updates) -> { });
    }

    @Test
    void aBlockedConnectDelaysNoOtherDevice() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        StubAdapter fast = new StubAdapter("fast", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(slow, fast);
        DeviceConnections.Connecting a = connections.begin(device("a", "slow"));
        background.submit(() -> connections.complete(a));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        connections.complete(connections.begin(device("b", "fast")));
        connections.end("c");

        assertThat(connections.state("b").status()).isEqualTo(DeviceStatus.CONNECTED);
        slow.release();
        await().until(() -> connections.state("a").status() == DeviceStatus.CONNECTED);
    }

    @Test
    void aSupersededTicketClosesItsHandles() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        DeviceConnections connections = connections(slow);
        DeviceConnections.Connecting first = connections.begin(device("a", "slow"));
        Future<?> completing = background.submit(() -> connections.complete(first));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        connections.end("a");
        slow.release();
        completing.get(5, TimeUnit.SECONDS);

        assertThat(connections.handles("a")).isEmpty();
        assertThat(slow.handles.get("a").closed).isTrue();
    }

    @Test
    void theOldHandlesCloseBeforeTheNewOnesConnect() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        StubAdapter.StubHandle old = adapter.handles.get("a");

        DeviceConnections.Connecting again = connections.begin(device("a", "stub"));
        assertThat(old.closed).isFalse();
        connections.complete(again);

        assertThat(old.closed).isTrue();
        assertThat(adapter.handles.get("a")).isNotSameAs(old);
        assertThat(connections.handles("a")).containsValue(adapter.handles.get("a"));
    }

    @Test
    void aReportDuringConnectCounts() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);

        connections.complete(connections.begin(device("a", "stub")));

        assertThat(connections.state("a").status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(published).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                DeviceStateChangedEvent.class, e -> assertThat(e.state().status()).isEqualTo(DeviceStatus.CONNECTED)));
    }

    @Test
    void aFailingAdapterLeavesNoHandlesAndPublishesDisconnected() {
        StubAdapter good = new StubAdapter("good", DeviceKind.ANDROID_TV, false, false);
        StubAdapter bad = new StubAdapter("bad", DeviceKind.CAST, false, false) {
            @Override
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                throw new IllegalStateException("no");
            }
        };
        DeviceConnections connections = connections(good, bad);
        Device both = new Device("a", "a", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("good", Map.of(), "bad", Map.of()), Instant.EPOCH);

        connections.complete(connections.begin(both));

        assertThat(connections.handles("a")).isEmpty();
        assertThat(good.handles.get("a").closed).isTrue();
        assertThat(published.getLast()).isInstanceOfSatisfying(DeviceStateChangedEvent.class,
                e -> assertThat(e.state()).isEqualTo(DeviceState.initial()));
    }

    @Test
    void aLateReportFromAnOldGenerationIsIgnored() {
        StubAdapter adapter = new StubAdapter("stub", DeviceKind.ANDROID_TV, false, false);
        DeviceConnections connections = connections(adapter);
        connections.complete(connections.begin(device("a", "stub")));
        StubAdapter.StubHandle old = adapter.handles.get("a");
        connections.complete(connections.begin(device("a", "stub")));
        published.clear();

        old.report(DeviceState.initial().withStatus(DeviceStatus.CONNECTING));

        assertThat(published).isEmpty();
        assertThat(connections.state("a").status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void aTicketCompletedAfterCloseAllIsClosed() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        DeviceConnections connections = connections(slow);
        DeviceConnections.Connecting ticket = connections.begin(device("a", "slow"));
        Future<?> completing = background.submit(() -> connections.complete(ticket));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        connections.closeAll();
        slow.release();
        completing.get(5, TimeUnit.SECONDS);

        assertThat(slow.handles.get("a").closed).isTrue();
        assertThat(connections.handles("a")).isEmpty();
    }
}
```

Add the imports the code uses: AssertJ `assertThat`, Awaitility `await`, `java.util.concurrent.*`, `java.util.*`,
`java.time.Instant`, `java.util.function.Consumer`, and the `core` types.

- [ ] **Step 2: Run it and watch it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.DeviceConnectionsTest'`
Expected: compilation FAILS: `cannot find symbol: class DeviceConnections`.

- [ ] **Step 3: Implement**

```java
package dev.andre.homecontrol.device;

/**
 * The live handles of every device and their composed state. A connect runs in two steps.
 * - {@link #begin} installs a new {@link Generation} and takes the old handles out, under this class's lock. Callers
 *   begin while holding the registry lock, so connects and removals follow the order of the registry changes.
 * - {@link #complete} closes the old handles and calls {@code adapter.connect} outside every lock. It installs the new
 *   handles only if their generation is still current; otherwise it closes them.
 * The lock guards only these maps; it never covers {@code connect}, {@code close} or publishing an event.
 */
final class DeviceConnections {

    /** Where a handle's learned settings go: the adapter settings store. */
    @FunctionalInterface
    interface LearnedSink {
        void store(String deviceId, String adapterId, Map<String, String> updates);
    }

    /** A connect begun under the registry lock and completed after it is released. */
    record Connecting(Device device, Generation generation, List<DeviceHandle> previous) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeviceConnections.class);

    private final Map<String, DeviceAdapter> adapters;
    private final ApplicationEventPublisher events;
    private final LearnedSink learned;
    /** device id → (adapter id → handle), in the device's adapter order. */
    private final Map<String, Map<String, DeviceHandle>> handles = new ConcurrentHashMap<>();
    /** device id → its current generation; a report from any other generation is ignored. */
    private final Map<String, Generation> reported = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    DeviceConnections(Map<String, DeviceAdapter> adapters, ApplicationEventPublisher events, LearnedSink learned) {
        this.adapters = adapters;
        this.events = events;
        this.learned = learned;
    }

    Connecting begin(Device device) {
        List<String> adapterIds = device.adapters().keySet().stream().filter(adapters::containsKey).toList();
        Generation generation = new Generation(device.id(), adapterIds);
        synchronized (lock) {
            reported.put(device.id(), generation);
            Map<String, DeviceHandle> previous = handles.remove(device.id());
            return new Connecting(device, generation, previous == null ? List.of() : List.copyOf(previous.values()));
        }
    }

    /**
     * A failing adapter never leaves the device half-connected: the handles opened so far are closed, the device keeps
     * none, and a DISCONNECTED state is published, because its previous handle was closed and silenced, and what it
     * last published would otherwise stay on every screen.
     */
    void complete(Connecting connecting) {
        connecting.previous().forEach(DeviceHandle::close);
        Device device = connecting.device();
        Generation generation = connecting.generation();
        Map<String, DeviceHandle> opened = new LinkedHashMap<>();
        for (String adapterId : generation.adapterIds) {
            try {
                opened.put(adapterId, adapters.get(adapterId).connect(device,
                        state -> generation.report(adapterId, state),
                        updates -> learned.store(device.id(), adapterId, updates)));
            } catch (RuntimeException e) {
                log.warn("Could not connect {} via the {} adapter; leaving it disconnected", device.id(), adapterId, e);
                opened.values().forEach(DeviceHandle::close);
                boolean current;
                synchronized (lock) {
                    current = reported.remove(device.id(), generation);
                }
                if (current) {
                    events.publishEvent(new DeviceStateChangedEvent(device.id(), DeviceState.initial()));
                }
                return;
            }
        }
        boolean installed;
        synchronized (lock) {
            installed = reported.get(device.id()) == generation;
            if (installed) {
                handles.put(device.id(), opened);
            }
        }
        if (!installed) {
            opened.values().forEach(DeviceHandle::close);
        }
    }

    List<DeviceHandle> end(String id) {
        synchronized (lock) {
            reported.remove(id);
            Map<String, DeviceHandle> previous = handles.remove(id);
            return previous == null ? List.of() : List.copyOf(previous.values());
        }
    }

    void closeAll() {
        List<DeviceHandle> open = new ArrayList<>();
        synchronized (lock) {
            reported.clear();
            handles.values().forEach(deviceHandles -> open.addAll(deviceHandles.values()));
            handles.clear();
        }
        open.forEach(DeviceHandle::close);
    }

    Map<String, DeviceHandle> handles(String id) {
        return Collections.unmodifiableMap(handles.getOrDefault(id, Map.of()));
    }

    DeviceState state(String id) {
        Generation generation = reported.get(id);
        return generation == null ? DeviceState.initial() : generation.composed();
    }

    /** One connect of one device; its own monitor keeps a device's reports in the order they were composed. */
    final class Generation {
        private final String deviceId;
        private final List<String> adapterIds;
        private final Map<String, DeviceState> states = new LinkedHashMap<>();

        Generation(String deviceId, List<String> adapterIds) {
            this.deviceId = deviceId;
            this.adapterIds = adapterIds;
            adapterIds.forEach(adapterId -> states.put(adapterId, DeviceState.initial()));
        }

        synchronized DeviceState composed() {
            return DeviceStates.compose(List.copyOf(states.values()));
        }

        synchronized void report(String adapterId, DeviceState state) {
            if (reported.get(deviceId) != this) {
                return; // a handle of a replaced or ended generation reporting late
            }
            states.put(adapterId, state);
            events.publishEvent(new DeviceStateChangedEvent(deviceId, composed()));
        }
    }
}
```

In `DeviceManager`:
- `connections = new DeviceConnections(this.adapters, events, this::updateAdapterSettings)` is built in the
  constructor.
- `private void connect(Device device)` becomes `connections.complete(connections.begin(device))`. It is still called
  inside the `synchronized (lock)` blocks for now; Task 4 moves `complete` out.
- `closeHandles(id)` becomes `connections.end(id).forEach(DeviceHandle::close)`.
- `state(id)` returns `connections.state(id)`. `execute`, `query`, `speakerTopology` and `inputs` read
  `connections.handles(id)` in place of `handles.getOrDefault(id, Map.of())`.
- `close()` becomes `connections.closeAll()`.
- Delete `handles`, `reported`, `Generation`, `tryConnect` and the long `lock` Javadoc paragraph about handles. Keep a
  short one: the lock guards registry read-modify-writes.

- [ ] **Step 4: Run and watch them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*'`
Expected: PASS (the new 7 tests and the five suites unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/device/DeviceConnections.java src/main/java/dev/andre/homecontrol/device/DeviceManager.java \
  src/test/java/dev/andre/homecontrol/device/DeviceConnectionsTest.java src/test/java/dev/andre/homecontrol/device/BlockingAdapter.java
git commit -m "refactor: give device connections their own class, connecting outside the lock in two steps"
```

---

### Task 3: `CommandRouter`, `AdapterSettingsStore` and `RegisteredDevices`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/device/CommandRouter.java`
- Create: `src/main/java/dev/andre/homecontrol/device/AdapterSettingsStore.java`
- Create: `src/main/java/dev/andre/homecontrol/device/RegisteredDevices.java`
- Create: `src/main/java/dev/andre/homecontrol/device/RegistryLock.java`
- Modify: `DeviceManager.java`

**Interfaces:**
- Consumes: `DeviceConnections.handles(id)` and `state(id)`.
- Produces, all package-private:
  - `final class RegistryLock { }`, a monitor object with no members, shared by `Enrollment` and
    `AdapterSettingsStore`.
  - `final class CommandRouter`:
    - `CommandRouter(DeviceRegistry, Map<String, DeviceAdapter>, DeviceConnections)`;
    - `void execute(String id, Action action)`;
    - `Map<String, Object> query(String id, CastAppQuery query)`.
  - `final class AdapterSettingsStore`:
    - `AdapterSettingsStore(DeviceRegistry, Map<String, DeviceAdapter>, RegistryLock)`;
    - `void updateAdapterSettings(String id, String adapterId, Map<String, String> updates)`;
    - `void setWakeOnLanMac(String id, String mac)`;
    - `boolean wakesOnLan(String id)`;
    - `Optional<String> wakeOnLanMac(String id)`.
  - `final class RegisteredDevices`:
    - `RegisteredDevices(DeviceRegistry, Map<String, DeviceAdapter>, DeviceConnections)`;
    - `List<Device> devices()`, `Optional<Device> device(String id)`, `Optional<Device> defaultDevice()`;
    - `DeviceState state(String id)`, `Map<String, DeviceState> states()`;
    - `Set<Capability> capabilities(String id)`, `ForegroundAppReporting foregroundAppReporting(String id)`;
    - `boolean adapterEnabled(String adapterId)`;
    - `Optional<SpeakerTopology> speakerTopology(String id)`, `List<TvInput> inputs(String id)`.

This is a pure move, pinned by the five suites.
- [ ] **Step 1** is to run them before the move, to see them green:
  `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*'`.
- [ ] **Step 2: Move the code.**
  - `execute`, `stopEverywhere`, `query`, `accepts` and the nested `FallThrough` move into `CommandRouter`, as they
    are, with their Javadocs and messages. `NO_DEVICE_PREFIX` and `NOT_CONNECTED_SUFFIX` move with them.
  - `updateAdapterSettings`, `setWakeOnLanMac`, `wakesOnLan` and `wakeOnLanMac` move into `AdapterSettingsStore`,
    using `synchronized (lock)` on the `RegistryLock` passed in.
  - The queries move into `RegisteredDevices`.
  - `DeviceManager` builds all three in its constructor, sharing one `RegistryLock` with its own `synchronized`
    blocks: replace `private final Object lock = new Object()` with `private final RegistryLock lock = new
    RegistryLock()`. It delegates each public method with one line.
  - The `DeviceConnections` learned sink becomes `settings::updateAdapterSettings`.
- [ ] **Step 3: Run** the five suites and `ArchitectureTest`.
  Expected: PASS unchanged.
- [ ] **Step 4: Commit**
  `refactor: move command routing, adapter settings and device queries out of DeviceManager`.

---

### Task 4: `Enrollment` in three phases, with `HostAddresses`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/device/Enrollment.java`
- Create: `src/main/java/dev/andre/homecontrol/device/HostAddresses.java`
- Create: `src/test/java/dev/andre/homecontrol/device/EnrollmentTest.java`
- Create: `src/test/java/dev/andre/homecontrol/device/HostAddressesTest.java`
- Modify: `DeviceManager.java`

**Interfaces:**
- Consumes:
  - `DeviceConnections.begin`, `complete` and `end` (Task 2);
  - `RegistryLock` (Task 3);
  - `DeviceMatching` (Task 1).
- Produces:
  - `final class HostAddresses`:
    - `static HostAddresses resolve(Collection<String> hosts, Function<String, Optional<InetAddress>> resolver)`;
    - `boolean same(String a, String b)`: equal ignoring case, or both resolved to the same address. A host that is
      not in the map is compared by name only;
    - `static Optional<InetAddress> lookup(String host)`: `InetAddress.getByName`, empty on `UnknownHostException`.
  - `final class Enrollment`:
    - `Enrollment(DeviceRegistry, Map<String, DeviceAdapter>, DeviceConnections, RegistryLock,
      ApplicationEventPublisher, Function<String, Optional<InetAddress>> resolver)`;
    - `void start()`;
    - `void adopt(Device)`;
    - `void forget(String id)`;
    - `Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings)`;
    - `Device addDiscovered(String adapterId, String host, int port)`;
    - `void onDiscovered(DeviceDiscoveredEvent)`;
    - `void mergeVisibleReceivers()` (until Task 7);
    - `Device merge(String targetId, String sourceId)`;
    - `Device split(String id, String adapterId)`;
    - `List<DiscoveredDevice> discovered()`, `pairable()` and `addable()`.

- [ ] **Step 1: Write the failing tests**

`HostAddressesTest`:

```java
class HostAddressesTest {

    @Test
    void namesThatResolveToOneAddressAreTheSameHost() throws Exception {
        InetAddress nas = InetAddress.getByAddress("nas.lan", new byte[] {10, 0, 0, 5});
        HostAddresses addresses = HostAddresses.resolve(List.of("nas.lan", "10.0.0.5"),
                host -> host.equals("nas.lan") ? Optional.of(nas)
                        : Optional.of(InetAddress.getByAddress(new byte[] {10, 0, 0, 5})));

        assertThat(addresses.same("nas.lan", "10.0.0.5")).isTrue();
        assertThat(addresses.same("NAS.lan", "nas.lan")).isTrue();
    }

    @Test
    void aHostThatWasNotResolvedIsComparedByNameOnly() {
        HostAddresses addresses = HostAddresses.resolve(List.of("10.0.0.5"), host -> Optional.empty());

        assertThat(addresses.same("new.lan", "10.0.0.5")).isFalse();
        assertThat(addresses.same("new.lan", "NEW.lan")).isTrue();
    }
}
```

(Make `resolve`'s resolver a small checked-exception-free lambda. Wrap `getByAddress` in a helper that rethrows as
`AssertionError` if Java insists on the checked `UnknownHostException`.)

`EnrollmentTest`:

```java
class EnrollmentTest {

    @TempDir
    Path dir;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        background.shutdownNow();
    }

    private record Wiring(DeviceRegistry registry, DeviceConnections connections, Enrollment enrollment) {
    }

    private Wiring wire(Function<String, Optional<InetAddress>> resolver, DeviceAdapter... adapters) {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        Map<String, DeviceAdapter> byId = new LinkedHashMap<>();
        for (DeviceAdapter adapter : adapters) {
            byId.put(adapter.id(), adapter);
        }
        RegistryLock lock = new RegistryLock();
        AdapterSettingsStore settings = new AdapterSettingsStore(registry, byId, lock);
        DeviceConnections connections = new DeviceConnections(byId, published::add, settings::updateAdapterSettings);
        return new Wiring(registry, connections,
                new Enrollment(registry, byId, connections, lock, published::add, resolver));
    }

    private static Device device(String id, String adapterId, String host) {
        return new Device(id, id, DeviceKind.ANDROID_TV, host, Map.of(adapterId, Map.of()), Instant.EPOCH);
    }

    @Test
    void attachResolvesWithoutHoldingTheLock() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        StubAdapter webos = new StubAdapter("webos", DeviceKind.WEBOS, false, false);
        Wiring wiring = wire(host -> {
            resolving.countDown();
            try {
                answer.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }, webos);
        wiring.registry().save(device("other", "webos", "10.0.0.9"));
        Future<Device> attaching = background.submit(() ->
                wiring.enrollment().attach("tv.lan", "TV", DeviceKind.WEBOS, "webos", Map.of("keyRef", "r")));
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

        wiring.enrollment().forget("other");

        assertThat(wiring.registry().findById("other")).isEmpty();
        answer.countDown();
        assertThat(attaching.get(5, TimeUnit.SECONDS).adapterSettings("webos")).containsEntry("keyRef", "r");
    }

    @Test
    void aHostAddedWhileResolvingIsComparedByName() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        StubAdapter webos = new StubAdapter("webos", DeviceKind.WEBOS, false, false);
        StubAdapter other = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, false);
        Wiring wiring = wire(host -> {
            resolving.countDown();
            try {
                answer.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }, webos, other);
        Future<Device> attaching = background.submit(() ->
                wiring.enrollment().attach("10.0.0.7", "TV", DeviceKind.WEBOS, "webos", Map.of()));
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();
        wiring.enrollment().adopt(device("shield", "androidtv", "10.0.0.7"));

        answer.countDown();

        assertThat(attaching.get(5, TimeUnit.SECONDS).id()).isEqualTo("shield");
    }

    @Test
    void aForgetRacingAConnectLeavesNoHandle() throws Exception {
        BlockingAdapter slow = new BlockingAdapter("slow", "a");
        Wiring wiring = wire(HostAddresses::lookup, slow);
        Future<?> adopting = background.submit(() -> wiring.enrollment().adopt(device("a", "slow", "10.0.0.5")));
        assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();

        wiring.enrollment().forget("a");
        slow.release();
        adopting.get(5, TimeUnit.SECONDS);

        assertThat(wiring.registry().findById("a")).isEmpty();
        assertThat(wiring.connections().handles("a")).isEmpty();
        assertThat(slow.handles.get("a").closed).isTrue();
    }
}
```

`adopt` of device `a` blocks in `complete`, after the lock. `forget` then gets the lock at once, which proves the lock
is free while connecting.

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.EnrollmentTest' --tests 'dev.andre.homecontrol.device.HostAddressesTest'`
Expected: compilation FAILS: no `Enrollment` or `HostAddresses`.

- [ ] **Step 3: Implement `HostAddresses`**

```java
/**
 * Host names resolved before the registry lock is taken, so comparing hosts under it never waits on DNS. A host that
 * was not resolved (registered meanwhile, or unknown) is compared by name only.
 */
final class HostAddresses {

    private final Map<String, InetAddress> resolved;

    private HostAddresses(Map<String, InetAddress> resolved) {
        this.resolved = resolved;
    }

    static HostAddresses resolve(Collection<String> hosts, Function<String, Optional<InetAddress>> resolver) {
        Map<String, InetAddress> resolved = new HashMap<>();
        for (String host : new LinkedHashSet<>(hosts)) {
            resolver.apply(host).ifPresent(address -> resolved.put(host.toLowerCase(Locale.ROOT), address));
        }
        return new HostAddresses(resolved);
    }

    /** Literal IPs never touch DNS; a name is resolved, which only happens while pairing. */
    static Optional<InetAddress> lookup(String host) {
        try {
            return Optional.of(InetAddress.getByName(host));
        } catch (UnknownHostException _) {
            return Optional.empty();
        }
    }

    boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        InetAddress first = resolved.get(a.toLowerCase(Locale.ROOT));
        InetAddress second = resolved.get(b.toLowerCase(Locale.ROOT));
        return first != null && first.equals(second);
    }
}
```

- [ ] **Step 4: Implement `Enrollment`**

Every public operation follows one shape:

```java
    /** What one operation does to the connections and the screens once the registry lock is released. */
    private final class AfterLock {
        private final List<DeviceConnections.Connecting> connects = new ArrayList<>();
        private final List<DeviceHandle> toClose = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();

        void connect(Device device) {
            connects.add(connections.begin(device));
        }

        void remove(String id) {
            toClose.addAll(connections.end(id));
            removed.add(id);
        }

        void run() {
            toClose.forEach(DeviceHandle::close);
            connects.forEach(connections::complete);
            removed.forEach(id -> events.publishEvent(new DeviceStateChangedEvent(id, DeviceState.initial())));
        }
    }
```

Each operation's `synchronized (lock)` body is `DeviceManager`'s current body, with these changes:
- `connect(x)` becomes `after.connect(x)`;
- `closeHandles(id)` becomes `after.remove(id)`, for devices that disappear (forget, the merge source);
- `after.run()` runs after the `synchronized` block;
- the events `forget` and `merge` publish today (DISCONNECTED for the removed id) come from `after.remove`, so drop
  the explicit `publishEvent` calls.

Specifically:
- **`start()`:**
  - outside the lock: `registry.findAll()`, then `validate` each (unchanged);
  - inside: for each device, `after.connect(migrate(device))`, where `migrate` is the current body without its own
    `synchronized`;
  - then `after.run()`.
- **`adopt(device)`:**
  - before the lock: `List<DiscoveredDevice> visible = discovered();`;
  - inside: the current logic, with `absorbAddable(device, visible)` using `visible` in place of calling
    `addable()` (filter `visible` by `pairingFree` and not `isRegistered` against the locked registry snapshot);
  - `registry.save(adopted); after.connect(adopted);`.
- **`attach(...)`:**
  - before the lock: `List<Device> snapshot = registry.findAll();`, then `HostAddresses addresses =
    HostAddresses.resolve(concat(host, snapshot hosts), resolver);` and `List<DiscoveredDevice> visible =
    discovered();`;
  - inside: `DeviceMatching.attach(registry.findAll(), host, name, kind, adapterId, settings, Instant.now(),
    addresses::same)`, then the adopt logic (a private `adoptLocked(device, visible, after)` that `adopt` shares),
    returning the saved device;
  - after `after.run()`, return it.
- **`forget(id)`:**
  - inside: find the device (return if absent), run `adapter.forget` for each running adapter,
    `registry.delete(id)`, `after.remove(id)`;
  - after: `after.run()`.
- **`addDiscovered`:** before the lock, `adapter.discovered()`, which is in memory; inside, the current body with
  `after.connect`.
- **`onDiscovered`:** `settingsFor` before the lock, as today. Inside, the carrier check and
  `reconnectIfMoved(device, adapterId, settings, after)`, or the `bestMatch` merge with `after.connect`.
- **`merge`:** inside, the current body, with `after.remove(sourceId)` and `after.connect(merged)`.
- **`split`:** inside, the current body, with `after.connect(rest)` and `after.connect(split)`.
- **`mergeVisibleReceivers`**, `discovered`, `pairable`, `addable`, `pairingFree` and `isRegistered` move as they are.

`DeviceManager` builds
`enrollment = new Enrollment(registry, adapters, connections, lock, events, HostAddresses::lookup)` and delegates:
- `start()` (still `@PostConstruct`);
- `onDiscovered` (still the `@EventListener`, delegating);
- `adopt`, `forget`, `attach`, `addDiscovered`, `merge`, `split`, `mergeVisibleReceivers`, `discovered`, `pairable`
  and `addable`.

Delete the moved private helpers from `DeviceManager`, and its `synchronized` blocks. After this task, `DeviceManager`
holds no lock of its own.

- [ ] **Step 5: Run and watch them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS. The five suites are unchanged; the webOS and Tizen migration tests use the real manager.

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor: enroll devices in three phases, resolving hosts and connecting outside the registry lock"
```

(Stage the four new files and `DeviceManager.java` by name.)

---

### Task 5: One matching rule for the discovery paths

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/device/DeviceMatching.java`
- Modify: `src/test/java/dev/andre/homecontrol/device/DeviceMatchingTest.java`, `EnrollmentTest.java`

**Interfaces:**
- Produces, on `DeviceMatching`:
  - `Optional<Device> owner(List<Device> registered, DiscoveredDevice found)`, which replaces `bestMatch`;
  - `Optional<DiscoveredDevice> absorbable(...)`, unchanged in signature.

  Both use one private rule.

- [ ] **Step 1: Write the failing tests**

In `DeviceMatchingTest`:

```java
    @Test
    void aNameMatchIsVetoedWhenAnotherDeviceSitsAtTheReceiversAddress() {
        Device named = device("a", "Living Room", "10.0.0.6");
        Device atTheAddress = device("b", "Kitchen", "10.0.0.9").withAdapter("cast", Map.of());

        assertThat(DeviceMatching.owner(List.of(named, atTheAddress), receiver("Living Room", "10.0.0.9"))).isEmpty();
    }

    @Test
    void aNameMatchIsVetoedWhenAnotherDeviceHasThatName() {
        Device named = device("a", "Living Room", "10.0.0.6");
        Device sameNameWithCast = device("b", "Living Room", "10.0.0.7").withAdapter("cast", Map.of());

        assertThat(DeviceMatching.owner(List.of(named, sameNameWithCast), receiver("Living Room", "10.0.0.9"))).isEmpty();
    }
```

Rename the Task 1 `bestMatch` tests to call `owner`.

In `EnrollmentTest`:

```java
    @Test
    void addingAReceiverWhoseNameBelongsToTwoDevicesRegistersANewOne() {
        StubAdapter androidtv = new StubAdapter("androidtv", DeviceKind.ANDROID_TV, false, false);
        StubAdapter cast = new StubAdapter("cast", DeviceKind.CAST, true, false);
        Wiring wiring = wire(HostAddresses::lookup, androidtv, cast);
        wiring.registry().save(new Device("a", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.6",
                Map.of("androidtv", Map.of()), Instant.EPOCH));
        wiring.registry().save(new Device("b", "Living Room", DeviceKind.CAST, "10.0.0.7",
                Map.of("cast", Map.of("host", "10.0.0.7", "port", "8009")), Instant.EPOCH));
        cast.visible.add(new DiscoveredDevice("cast", "Living Room", "10.0.0.9", 8009, Map.of()));

        Device added = wiring.enrollment().addDiscovered("cast", "10.0.0.9", 8009);

        assertThat(added.id()).isNotIn("a", "b");
        assertThat(wiring.registry().findById("a").orElseThrow().hasAdapter("cast")).isFalse();
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.DeviceMatchingTest' --tests 'dev.andre.homecontrol.device.EnrollmentTest'`
Expected: compilation FAILS on `owner`. After renaming a stub to delegate to `bestMatch`, the two veto tests and the
add test fail on the old rule.

- [ ] **Step 3: Implement the one rule**

```java
    /**
     * The device a discovered receiver belongs to, for "Add" and the automatic merge. Among devices without the
     * receiver's adapter, it is the one at the receiver's address. Otherwise it is the single one with the receiver's
     * name, unless that name or that address belongs to another registered device, where the receiver would belong
     * instead.
     */
    static Optional<Device> owner(List<Device> registered, DiscoveredDevice found) {
        List<Device> candidates = registered.stream().filter(device -> !device.hasAdapter(found.adapterId())).toList();
        return pick(candidates, found.host(), found.name(), Device::host, Device::name,
                chosen -> registered.stream().anyMatch(other -> !other.id().equals(chosen.id())
                        && (sameName(other.name(), found.name()) || other.host().equalsIgnoreCase(found.host()))));
    }

    /** The receiver a newly adopted device absorbs: the same rule, seen from the device. */
    static Optional<DiscoveredDevice> absorbable(Device device, List<DiscoveredDevice> receivers, List<Device> others) {
        return pick(receivers, device.host(), device.name(), DiscoveredDevice::host, DiscoveredDevice::name,
                chosen -> others.stream().anyMatch(other ->
                        sameName(other.name(), device.name()) || other.host().equalsIgnoreCase(chosen.host())));
    }

    /** Same address first; otherwise the single same-named candidate, unless it belongs elsewhere. */
    private static <T> Optional<T> pick(List<T> candidates, String host, String name, Function<T, String> hostOf,
                                        Function<T, String> nameOf, Predicate<T> belongsElsewhere) {
        Optional<T> byHost = candidates.stream().filter(c -> hostOf.apply(c).equalsIgnoreCase(host)).findFirst();
        if (byHost.isPresent()) {
            return byHost;
        }
        List<T> byName = candidates.stream().filter(c -> sameName(nameOf.apply(c), name)).toList();
        if (byName.size() != 1 || belongsElsewhere.test(byName.getFirst())) {
            return Optional.empty();
        }
        return Optional.of(byName.getFirst());
    }
```

- In `Enrollment`, `bestMatch` becomes `owner`; delete `bestMatch`.
- Keep `absorbable`'s behaviour exactly: its veto already matched this rule.

- [ ] **Step 4: Run the device and adapter suites**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*'`
Expected: PASS. If a test in `DeviceManagerMergeTest` asserted a name merge that the veto now refuses, change only that
test's expectation, and name it and the reason in the commit message.

- [ ] **Step 5: Commit**

`feat: add and auto-merge refuse a receiver whose name or address belongs to another device`. The body explains the
one rule and names any test changed.

---

### Task 6: `AdapterDiscovery`, split out of `DeviceAdapter`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/core/AdapterDiscovery.java`
- Modify: `src/main/java/dev/andre/homecontrol/core/DeviceAdapter.java`, which drops `discovered`, `settingsFor`,
  `hostOf`, `carries` and `credentialsBoundToDeviceId`.
- Modify: every adapter class (`grep -rln "implements DeviceAdapter\|implements WakeOnLanAdapter"
  src/main/java src/test/java src/e2e/java`) to `implements DeviceAdapter, AdapterDiscovery`, or the matching
  `WakeOnLanAdapter` form.
- Modify: `Enrollment.java`, `DeviceManager.java`.

**Interfaces:**
- Produces:

```java
package dev.andre.homecontrol.core;

/**
 * How an adapter takes part in enrollment: the devices it sees on the network, and how its entry in a registered
 * device matches, moves and merges. Connecting and commands are {@link DeviceAdapter}'s.
 */
public interface AdapterDiscovery {

    /** The same id as the adapter's {@link DeviceAdapter#id()}. */
    String id();

    /** Devices this adapter has seen on the network, paired or not. */
    List<DiscoveredDevice> discovered();

    default Optional<Map<String, String>> settingsFor(DiscoveredDevice found) {
        return Optional.empty();
    }

    default String hostOf(Device device) {
        return device.host();
    }

    default boolean carries(Device device, DiscoveredDevice found) {
        return device.hasAdapter(id()) && hostOf(device).equalsIgnoreCase(found.host());
    }

    default boolean credentialsBoundToDeviceId() {
        return false;
    }
}
```

Move the Javadocs of the five methods over as they are.
- `Enrollment` keeps two maps:
  - `Map<String, DeviceAdapter> adapters`, for `forget`, `migrate` and `validate`;
  - `Map<String, AdapterDiscovery> discoveries`, built from `adapters` with `adapter instanceof AdapterDiscovery d`.
- Every call to `discovered`, `settingsFor`, `carries`, `hostOf` or `credentialsBoundToDeviceId` goes through
  `discoveries`. An adapter that is not an `AdapterDiscovery` has none of them: nothing visible and no settings,
  `hostOf` = `device.host()`, not bound.

- [ ] **Step 1:** add to `EnrollmentTest`:

```java
    @Test
    void anAdapterWithoutDiscoveryAddsNothingAndStillConnects() {
        DeviceAdapter plain = new DeviceAdapter() {
            public String id() { return "plain"; }
            public DeviceKind kind() { return DeviceKind.ANDROID_TV; }
            public Set<Capability> capabilities(Device device) { return Set.of(); }
            public DeviceHandle connect(Device device, Consumer<DeviceState> onChange) {
                return new StubAdapter.StubHandle(onChange);
            }
        };
        Wiring wiring = wire(HostAddresses::lookup, plain);

        wiring.enrollment().adopt(device("a", "plain", "10.0.0.5"));

        assertThat(wiring.enrollment().discovered()).isEmpty();
        assertThat(wiring.connections().handles("a")).containsKey("plain");
    }
```

- [ ] **Step 2:** run it. Expected: compilation FAILS, because the anonymous `DeviceAdapter` doesn't implement
  `discovered()`.
- [ ] **Step 3:** implement as above. Update `StubAdapter` (`implements DeviceAdapter, AdapterDiscovery`), the e2e
  `FakeDeviceAdapter`, and any test adapter that overrides the five methods.
- [ ] **Step 4:** run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests
  'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.ArchitectureTest'`, then
  `scripts/gradle.sh compileE2eJava`. Expected: PASS.
- [ ] **Step 5: Commit** `refactor: split discovery and merge rules out of DeviceAdapter into AdapterDiscovery`.

---

### Task 7: Discovery starts on application-ready; `DiscoveryCatchUp` goes

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java` (`@PostConstruct start()` →
  `@EventListener(ApplicationReadyEvent.class)`)
- Modify: `src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpConfiguration.java` (drop `initMethod = "start"`)
  and `SsdpDiscovery.java` (`@EventListener(ApplicationReadyEvent.class)` on `start`)
- Modify: whatever starts the Android TV `MdnsDiscovery` (`grep -rn "MdnsDiscovery\|discovery.start()"
  src/main/java/dev/andre/homecontrol/adapters/androidtv`)
- Delete: `src/main/java/dev/andre/homecontrol/device/DiscoveryCatchUp.java`; `mergeVisibleReceivers` in `Enrollment`
  and `DeviceManager`
- Modify: `DeviceManagerMergeTest.receiversResolvedBeforeTheApplicationWasReadyAreMergedOnceItIs`
- Create: `src/test/java/dev/andre/homecontrol/discovery/DiscoveryStartTest.java`

- [ ] **Step 1: Write the failing test**

```java
class DiscoveryStartTest {

    @Test
    void mdnsAndSsdpStartOnlyOnceTheApplicationIsReady() throws Exception {
        assertThat(MdnsBrowser.class.getMethod("start").isAnnotationPresent(jakarta.annotation.PostConstruct.class))
                .isFalse();
        EventListener mdns = MdnsBrowser.class.getMethod("start").getAnnotation(EventListener.class);
        EventListener ssdp = SsdpDiscovery.class.getMethod("start").getAnnotation(EventListener.class);

        assertThat(mdns).isNotNull();
        assertThat(mdns.value()).containsExactly(ApplicationReadyEvent.class);
        assertThat(ssdp).isNotNull();
        assertThat(ssdp.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
```

Mirror the assertion for the Android TV `MdnsDiscovery` if its `start` is a method on a bean. If it is called from
another bean's lifecycle, assert that caller instead.

`DeviceManagerMergeTest.receiversResolvedBeforeTheApplicationWasReadyAreMergedOnceItIs` becomes
`aReceiverAnnouncedAfterStartupIsMergedByTheListener`:

```java
    @Test
    void aReceiverAnnouncedAfterStartupIsMergedByTheListener() {
        registry.save(shield());
        manager.start();
        cast.visible.add(receiver("SHIELD", "10.0.0.5"));

        manager.onDiscovered(new DeviceDiscoveredEvent(receiver("SHIELD", "10.0.0.5")));

        assertThat(registry.findById("10-0-0-5").orElseThrow().hasAdapter("cast")).isTrue();
        assertThat(manager.addable()).isEmpty();
    }
```

- [ ] **Step 2:** run it. Expected: `DiscoveryStartTest` FAILS: `start` is a `@PostConstruct` or has no listener.
- [ ] **Step 3:** implement:
  - move the annotations;
  - delete `DiscoveryCatchUp` and both `mergeVisibleReceivers`;
  - check with `grep -rn "mergeVisibleReceivers\|DiscoveryCatchUp" src/` that nothing is left.
- [ ] **Step 4:** run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests
  'dev.andre.homecontrol.discovery.*' --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.web.*'`.
  Expected: PASS.
  - Full-app tests get `ApplicationReadyEvent` from `SpringBootTest`.
  - A context-runner test that relied on discovery starting at bean creation now publishes the event itself. Record
    that as a ruling.
- [ ] **Step 5: Commit** `refactor: start discovery once the application is ready and drop the startup catch-up`. The
  body names the replaced test.

---

### Task 8: The four `core` interfaces, and the thin façade

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/core/DeviceQueries.java`, `DeviceCommands.java`,
  `DeviceEnrollment.java` and `DeviceSettings.java`
- Create: `src/main/java/dev/andre/homecontrol/device/Devices.java`
- Modify: `DeviceManager.java` (`implements DeviceQueries, DeviceCommands, DeviceEnrollment, DeviceSettings,
  AutoCloseable`)
- Create: `src/test/java/dev/andre/homecontrol/device/DevicesTest.java`

**Interfaces:**
- `DeviceQueries`:
  - `List<Device> devices()`, `Optional<Device> device(String id)`, `Optional<Device> defaultDevice()`;
  - `DeviceState state(String id)`, `Map<String, DeviceState> states()`;
  - `Set<Capability> capabilities(String id)`, `ForegroundAppReporting foregroundAppReporting(String id)`;
  - `boolean adapterEnabled(String adapterId)`;
  - `Optional<SpeakerTopology> speakerTopology(String id)`, `List<TvInput> inputs(String id)`.
- `DeviceCommands`: `void execute(String id, Action action)` and `Map<String, Object> query(String id, CastAppQuery
  query)`.
- `DeviceEnrollment`:
  - `void adopt(Device device)`;
  - `Device attach(String host, String name, DeviceKind kind, String adapterId, Map<String, String> settings)`;
  - `Device addDiscovered(String adapterId, String host, int port)`;
  - `void forget(String id)`;
  - `Device merge(String targetId, String sourceId)`, `Device split(String id, String adapterId)`;
  - `List<DiscoveredDevice> pairable()`, `List<DiscoveredDevice> addable()`.
- `DeviceSettings`: `boolean wakesOnLan(String id)`, `Optional<String> wakeOnLanMac(String id)` and `void
  setWakeOnLanMac(String id, String mac)`.
- `Devices`: `public record Devices(RegisteredDevices queries, CommandRouter commands, Enrollment enrollment,
  AdapterSettingsStore settings, DeviceConnections connections)`, with `public static Devices
  assemble(DeviceRegistry registry, List<DeviceAdapter> adapters, ApplicationEventPublisher events)`.
  - Its component types are package-private, so the record's accessors are package-private.
  - In PR 2 the collaborators implement the `core` interfaces and the accessors return the interfaces.
- Each interface's Javadoc is taken from `DeviceManager`'s method Javadocs.

- [ ] **Step 1:** `DevicesTest.assembleWiresOneConnectionMapForQueriesCommandsAndEnrollment()`:
  - build with a `StubAdapter`;
  - adopt a device through `devices.enrollment()`;
  - assert `devices.queries().state(id)` is CONNECTED, and that `devices.commands().execute(id, new
    Action.PressKey(...))` reaches the stub handle. Use an action the stub declares.
- [ ] **Step 2:** watch it fail to compile.
- [ ] **Step 3:** implement:
  - `DeviceManager`'s constructor becomes `Devices devices = Devices.assemble(registry, adapters, events)`, and each
    method delegates;
  - `@PostConstruct start`, `@PreDestroy close` and the `@EventListener onDiscovered` stay on `DeviceManager` for
    PR 1;
  - add `@Override` on every method from the interfaces;
  - check that `wc -l DeviceManager.java` is under 200.
- [ ] **Step 4:** run `scripts/gradle.sh build`. Expected: green, with the five suites unchanged apart from the two
  commits named above.
- [ ] **Step 5: Commit** `refactor: describe devices through four core interfaces and make DeviceManager a thin façade`.

---

### Task 9: Docs, and the full build

- [ ] Update the `device` row of `docs/dev/architecture.md` to name the collaborators and the four `core` interfaces.
  Mention that PR 2 moves callers onto the interfaces.
- [ ] Run `scripts/gradle.sh build`. Expected: green, with the test count at 2,892 or more.
- [ ] Run `scripts/gradle.sh compileE2eJava`, then `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Commit** `docs: describe the device package's collaborators`.
