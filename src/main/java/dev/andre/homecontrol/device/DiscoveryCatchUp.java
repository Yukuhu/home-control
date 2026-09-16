package dev.andre.homecontrol.device;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the automatic merge for receivers discovered during startup, whose events went out before
 * {@link DeviceManager#onDiscovered} was listening. A separate bean, not a listener method on
 * {@link DeviceManager}, so web slice tests that mock the manager see no startup interaction.
 */
@Component
class DiscoveryCatchUp {

    private final DeviceManager devices;

    DiscoveryCatchUp(DeviceManager devices) {
        this.devices = devices;
    }

    @EventListener(ApplicationReadyEvent.class)
    void mergeReceiversSeenDuringStartup() {
        devices.mergeVisibleReceivers();
    }
}
