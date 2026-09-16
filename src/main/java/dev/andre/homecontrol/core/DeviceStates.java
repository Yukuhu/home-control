package dev.andre.homecontrol.core;

import java.time.Instant;
import java.util.List;

/**
 * One device, several adapters (a Shield is Android TV and Cast): the strip shows one state.
 * Status and power come from the primary (first) adapter so an unpaired Android TV still says
 * so; app and volume come from the first adapter, in order, that reports them.
 */
public final class DeviceStates {

    private DeviceStates() {
    }

    public static DeviceState compose(List<DeviceState> inAdapterOrder) {
        if (inAdapterOrder.isEmpty()) {
            return DeviceState.initial();
        }
        if (inAdapterOrder.size() == 1) {
            return inAdapterOrder.getFirst();
        }
        DeviceState primary = inAdapterOrder.getFirst();
        String currentApp = null;
        DeviceState volumeSource = null;
        Instant updatedAt = primary.updatedAt();
        for (DeviceState state : inAdapterOrder) {
            if (currentApp == null && state.currentApp() != null) {
                currentApp = state.currentApp();
            }
            if (volumeSource == null && state.volumeMax() > 0) {
                volumeSource = state;
            }
            if (state.updatedAt().isAfter(updatedAt)) {
                updatedAt = state.updatedAt();
            }
        }
        if (volumeSource == null) {
            volumeSource = primary;
        }
        return new DeviceState(primary.status(), primary.powerOn(), currentApp,
                volumeSource.volumeLevel(), volumeSource.volumeMax(), volumeSource.muted(), updatedAt);
    }
}
