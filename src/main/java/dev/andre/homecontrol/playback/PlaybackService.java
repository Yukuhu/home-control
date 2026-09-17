package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.stereotype.Service;

/** Plan, then execute, then report. Commands are ephemeral: a failure here is final. */
@Service
public class PlaybackService {

    private final DeviceManager devices;
    private final PlaybackPlanner planner;

    public PlaybackService(DeviceManager devices, PlaybackPlanner planner) {
        this.devices = devices;
        this.planner = planner;
    }

    public Route play(ContentItem item, String deviceId) {
        Device device = devices.device(deviceId)
                .orElseThrow(() -> new DeviceNotFoundException("No device with id " + deviceId));
        Route route = planner.plan(item, devices.capabilities(deviceId));
        switch (route) {
            case Route.OpenAppLink open -> devices.execute(deviceId, open.action());
            case Route.Cast cast -> devices.execute(deviceId, cast.action());
            case Route.CastMessage message -> devices.execute(deviceId, message.action());
            case Route.Unroutable unroutable -> throw new UnroutableException(
                    device.name() + ": " + unroutable.reason());
        }
        return route;
    }
}
