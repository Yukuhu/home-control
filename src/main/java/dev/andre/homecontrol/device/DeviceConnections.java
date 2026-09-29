package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * The live handles of every device and their composed state. A connect runs in two steps.
 * <ul>
 *   <li>{@link #begin} installs a new {@link Generation} and retires the old handles, under this class's lock.
 *       Callers begin while holding the registry lock, so connects and removals follow the order of the registry
 *       changes.</li>
 *   <li>{@link #complete} closes the retired handles and calls {@code adapter.connect} outside every lock. It installs
 *       the new handles only if their generation is still current, and closes them otherwise. A ticket superseded
 *       before it reaches an adapter connects nothing more: its successor does.</li>
 * </ul>
 * One device's completions take turns, and retired handles wait here until a turn closes them, so a device's old
 * connections are always closed before a new one opens: a TV that takes one connection never sees two. A turn holds up
 * no other device and is never taken while holding the registry lock or this class's lock, which guards only the maps
 * and never covers {@code connect}, {@code close} or publishing an event. Only the current generation stores what its
 * handles learn. A handle that fails to close is logged and dropped, so it stops no other close or connect.
 */
final class DeviceConnections {

    /**
     * Where a handle's learned settings go: the adapter settings store, which writes them only while {@code current}
     * holds under the registry lock.
     */
    @FunctionalInterface
    interface LearnedSink {
        void store(String deviceId, String adapterId, Map<String, String> updates, BooleanSupplier current);
    }

    /** A connect begun under the registry lock and completed after it is released. */
    record Connecting(Device device, Generation generation) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeviceConnections.class);

    private final Map<String, DeviceAdapter> adapters;
    private final ApplicationEventPublisher events;
    private final LearnedSink learned;
    /** device id → (adapter id → handle), in the device's adapter order. */
    private final Map<String, Map<String, DeviceHandle>> handles = new ConcurrentHashMap<>();
    /** device id → handles that {@link #begin} or {@link #end} took out and no turn has closed yet; under the lock. */
    private final Map<String, List<DeviceHandle>> retired = new HashMap<>();
    /** device id → its current generation; a report from any other generation is ignored. */
    private final Map<String, Generation> reported = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    /** device id → the turn its completions and closes take, one at a time. */
    private final Map<String, ReentrantLock> turns = new ConcurrentHashMap<>();

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
            retire(device.id());
        }
        return new Connecting(device, generation);
    }

    /**
     * A failing adapter never leaves the device half-connected: the handles opened so far are closed, the device
     * keeps none, and a DISCONNECTED state is published. Its previous handle was closed and silenced, so what that
     * handle last published would otherwise stay on every screen.
     */
    void complete(Connecting connecting) {
        inTurn(connecting.device().id(), () -> completeInTurn(connecting));
    }

    private void completeInTurn(Connecting connecting) {
        Device device = connecting.device();
        Generation generation = connecting.generation();
        takeRetired(device.id()).forEach(DeviceConnections::closeQuietly);
        Map<String, DeviceHandle> opened = new LinkedHashMap<>();
        for (String adapterId : generation.adapterIds) {
            if (!generation.current()) {
                opened.values().forEach(DeviceConnections::closeQuietly);
                return;
            }
            try {
                opened.put(adapterId, adapters.get(adapterId).connect(device,
                        state -> generation.report(adapterId, state),
                        updates -> learned.store(device.id(), adapterId, updates, generation::current)));
            } catch (RuntimeException e) {
                log.warn("Could not connect {} via the {} adapter; leaving it disconnected", device.id(), adapterId, e);
                opened.values().forEach(DeviceConnections::closeQuietly);
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
            opened.values().forEach(DeviceConnections::closeQuietly);
        }
    }

    /** Closes a handle; one that fails to close is logged and dropped, so it stops no other close, connect or event. */
    static void closeQuietly(DeviceHandle handle) {
        try {
            handle.close();
        } catch (RuntimeException e) {
            log.warn("Could not close a device connection; dropping it", e);
        }
    }

    /** Removes the device's generation and retires its handles; {@link #closeRetired} closes them. */
    void end(String id) {
        synchronized (lock) {
            reported.remove(id);
            retire(id);
        }
    }

    /**
     * Closes the device's retired handles in its turn, so a device added again under the same id connects only once
     * they are closed. With nothing retired it takes no turn, so it never waits on a connect it has no part in.
     */
    void closeRetired(String id) {
        boolean waiting;
        synchronized (lock) {
            waiting = retired.containsKey(id);
        }
        if (waiting) {
            inTurn(id, () -> takeRetired(id).forEach(DeviceConnections::closeQuietly));
        }
    }

    void closeAll() {
        List<DeviceHandle> open = new ArrayList<>();
        synchronized (lock) {
            reported.clear();
            handles.values().forEach(deviceHandles -> open.addAll(deviceHandles.values()));
            handles.clear();
            retired.values().forEach(open::addAll);
            retired.clear();
        }
        open.forEach(DeviceConnections::closeQuietly);
    }

    Map<String, DeviceHandle> handles(String id) {
        return Collections.unmodifiableMap(handles.getOrDefault(id, Map.of()));
    }

    /** The composed state of the device's adapters; an unknown or handle-less device reads as DISCONNECTED. */
    DeviceState state(String id) {
        Generation generation = reported.get(id);
        return generation == null ? DeviceState.initial() : generation.composed();
    }

    private void inTurn(String id, Runnable work) {
        ReentrantLock turn = turns.computeIfAbsent(id, key -> new ReentrantLock());
        turn.lock();
        try {
            work.run();
        } finally {
            turn.unlock();
        }
    }

    /** Under the lock: the device's installed handles join those waiting to be closed. */
    private void retire(String id) {
        Map<String, DeviceHandle> installed = handles.remove(id);
        if (installed != null) {
            retired.computeIfAbsent(id, key -> new ArrayList<>()).addAll(installed.values());
        }
    }

    private List<DeviceHandle> takeRetired(String id) {
        synchronized (lock) {
            List<DeviceHandle> taken = retired.remove(id);
            return taken == null ? List.of() : taken;
        }
    }

    /**
     * One connect of one device: adapter id → the state that adapter last reported, in the device's adapter order.
     * Its own monitor guards the entries and keeps one device's reports in the order they were composed.
     */
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

        /** True while no later {@link #begin} or {@link #end} has replaced this generation. */
        boolean current() {
            return reported.get(deviceId) == this;
        }

        /** Publishes the composed state inside this monitor, so two adapters' updates reach SSE in the order they were composed. */
        synchronized void report(String adapterId, DeviceState state) {
            if (!current()) {
                return; // a handle of a replaced or ended generation reporting late
            }
            states.put(adapterId, state);
            events.publishEvent(new DeviceStateChangedEvent(deviceId, composed()));
        }
    }
}
