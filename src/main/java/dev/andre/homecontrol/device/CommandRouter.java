package dev.andre.homecontrol.device;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceAdapter;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.ReceiverApps;
import dev.andre.homecontrol.core.UnsupportedActionException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Sends commands and questions to a device through the first of its adapters that can take them. Lock-free. */
final class CommandRouter implements DeviceCommands {

    private static final String NO_DEVICE_PREFIX = "No device with id ";
    private static final String NOT_CONNECTED_SUFFIX = " is not connected";

    private final DeviceRegistry registry;
    private final Map<String, DeviceAdapter> adapters;
    private final DeviceConnections connections;

    CommandRouter(DeviceRegistry registry, Map<String, DeviceAdapter> adapters, DeviceConnections connections) {
        this.registry = registry;
        this.adapters = adapters;
        this.connections = connections;
    }

    /**
     * Tries the device's adapters that declare the needed capability, in order. An adapter that
     * could not even send — unsupported, offline, or declaring the capability without a live
     * handle (not yet connected, or a failed connect) — hands over to the next; an adapter whose
     * device answered "no" ({@link ActionFailedException}) ends it.
     * If nobody could send, the first offline reason wins over the last unsupported one; only a
     * capability none of the device's adapters declare is plainly unsupported. Nothing is
     * retried later (commands are ephemeral).
     */
    @Override
    public void execute(String id, Action action) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(NO_DEVICE_PREFIX + id));
        Map<String, DeviceHandle> deviceHandles = connections.handles(id);
        if (action instanceof Action.Stop) {
            stopEverywhere(device, deviceHandles, action);
            return;
        }
        FallThrough failures = new FallThrough(device);
        for (String adapterId : device.adapters().keySet()) {
            if (accepts(device, adapterId, action::acceptedBy) && failures.sent(deviceHandles.get(adapterId), action)) {
                return;
            }
        }
        throw failures.reason(() -> new UnsupportedActionException(device.name() + " cannot " + action.purpose()));
    }

    /**
     * Stop is sent to every adapter that accepts it (a TV that is both a Cast receiver and a media
     * renderer may be playing through either): done when any of them stopped. Otherwise the first
     * refusal wins, then the first offline reason, then the last unsupported one.
     */
    private void stopEverywhere(Device device, Map<String, DeviceHandle> deviceHandles, Action stop) {
        FallThrough failures = new FallThrough(device);
        boolean stopped = false;
        for (String adapterId : device.adapters().keySet()) {
            if (accepts(device, adapterId, stop::acceptedBy) && failures.stopped(deviceHandles.get(adapterId), stop)) {
                stopped = true;
            }
        }
        if (!stopped) {
            throw failures.reason(() -> new UnsupportedActionException(device.name() + " cannot " + stop.purpose()));
        }
    }

    /**
     * Like {@link #execute}, for a question with an answer: only adapters declaring
     * {@link Capability#CAST_RECEIVER} are asked, with the same fall-through (offline or unsupported
     * hands over; a refusal or no answer, {@link ActionFailedException}, ends it).
     */
    @Override
    public Map<String, Object> query(String id, CastAppQuery query) {
        Device device = registry.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException(NO_DEVICE_PREFIX + id));
        Map<String, DeviceHandle> deviceHandles = connections.handles(id);
        FallThrough failures = new FallThrough(device);
        for (String adapterId : device.adapters().keySet()) {
            DeviceHandle handle = deviceHandles.get(adapterId);
            if (accepts(device, adapterId, capabilities -> capabilities.contains(Capability.CAST_RECEIVER))
                    && failures.reachable(handle)) {
                Optional<ReceiverApps> receiver = handle.feature(ReceiverApps.class);
                if (receiver.isEmpty()) {
                    failures.unsupported(new UnsupportedActionException(device.name() + " cannot ask receiver apps"));
                    continue;
                }
                try {
                    return receiver.get().query(query);
                } catch (DeviceOfflineException e) {
                    failures.offline(e);
                } catch (UnsupportedActionException e) {
                    failures.unsupported(e);
                }
            }
        }
        throw failures.reason(() -> new UnsupportedActionException(device.name() + " is not a Cast receiver"));
    }

    /** True when the adapter is switched on and declares what {@code accepts} asks for on this device. */
    private boolean accepts(Device device, String adapterId, Predicate<Set<Capability>> accepts) {
        DeviceAdapter adapter = adapters.get(adapterId);
        return adapter != null && accepts.test(adapter.capabilities(device));
    }

    /**
     * Why none of a device's adapters could send, collected while falling through them in order:
     * the first refusal (only stop falls through one) wins, then the first offline reason — an
     * adapter without a live handle counts as offline — then the last unsupported one.
     */
    private static final class FallThrough {

        private final Device device;
        private ActionFailedException firstRefusal;
        private DeviceOfflineException firstOffline;
        private UnsupportedActionException lastUnsupported;

        FallThrough(Device device) {
            this.device = device;
        }

        /** False, with the device kept as offline, when the adapter has no live handle. */
        boolean reachable(DeviceHandle handle) {
            if (handle == null) {
                offline(new DeviceOfflineException(device.name() + NOT_CONNECTED_SUFFIX));
                return false;
            }
            return true;
        }

        /** Sends through the handle; false, with the reason kept, when it could not. A refusal propagates. */
        boolean sent(DeviceHandle handle, Action action) {
            if (!reachable(handle)) {
                return false;
            }
            try {
                handle.execute(action);
                return true;
            } catch (DeviceOfflineException e) {
                offline(e);
            } catch (UnsupportedActionException e) {
                unsupported(e);
            }
            return false;
        }

        /** Like {@link #sent}, but a refusal is kept too, so the other adapters still get the stop. */
        boolean stopped(DeviceHandle handle, Action stop) {
            try {
                return sent(handle, stop);
            } catch (ActionFailedException e) {
                if (firstRefusal == null) {
                    firstRefusal = e;
                }
                return false;
            }
        }

        void offline(DeviceOfflineException e) {
            if (firstOffline == null) {
                firstOffline = e;
            }
        }

        void unsupported(UnsupportedActionException e) {
            lastUnsupported = e;
        }

        /** The reason that wins, or {@code otherwise} when no adapter was even asked. */
        RuntimeException reason(Supplier<UnsupportedActionException> otherwise) {
            if (firstRefusal != null) {
                return firstRefusal;
            }
            if (firstOffline != null) {
                return firstOffline;
            }
            if (lastUnsupported != null) {
                return lastUnsupported;
            }
            return otherwise.get();
        }
    }
}
