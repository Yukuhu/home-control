package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.APP_CHANGED;
import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.FAILED;
import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.NOT_OBSERVABLE;
import static dev.andre.homecontrol.playback.DeepLinkTestResult.Outcome.NO_CHANGE;

/**
 * The setup page's "Test deep link" (spec §11): opens a known YouTube video through the normal
 * app-link action and watches the device's state events for a different foreground app. It says
 * only what the adapter could observe — an app change at best, never which video plays.
 */
@Service
public class DeepLinkTestService {

    private static final Logger log = LoggerFactory.getLogger(DeepLinkTestService.class);

    private static final String CHECK_THE_SCREEN =
            " No adapter can see which video plays: check the screen for the test video.";

    private final DeviceQueries devices;
    private final DeviceCommands commands;
    private final DeepLinkTestProperties properties;
    /** device id → the running test's queue of state events; one test per device at a time. */
    private final Map<String, BlockingQueue<DeviceState>> watching = new ConcurrentHashMap<>();

    public DeepLinkTestService(DeviceQueries devices, DeviceCommands commands, DeepLinkTestProperties properties) {
        this.devices = devices;
        this.commands = commands;
        this.properties = properties;
    }

    private static DeviceNotFoundException notFound(String deviceId) {
        return new DeviceNotFoundException("No device with id " + deviceId);
    }

    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        BlockingQueue<DeviceState> queue = watching.get(event.deviceId());
        if (queue != null) {
            queue.add(event.state());
        }
    }

    public DeepLinkTestResult run(String deviceId) {
        Device device = devices.device(deviceId).orElseThrow(() -> notFound(deviceId));
        if (!devices.capabilities(deviceId).contains(Capability.APP_LINK)) {
            // A device forgotten since the lookup above has no capabilities either: that is an unknown device.
            devices.device(deviceId).orElseThrow(() -> notFound(deviceId));
            throw new UnsupportedActionException(device.name() + " cannot open app links");
        }
        ForegroundAppReporting reporting = devices.foregroundAppReporting(deviceId);
        String before = devices.state(deviceId).currentApp();
        BlockingQueue<DeviceState> queue = new LinkedBlockingQueue<>();
        if (watching.putIfAbsent(deviceId, queue) != null) {
            return new DeepLinkTestResult(FAILED, before, null, "A deep-link test is already running on " + device.name());
        }
        try {
            try {
                commands.execute(deviceId, new Action.OpenAppLink(properties.youtubeUrl()));
            } catch (DeviceNotFoundException e) {
                // Forgotten since the lookup above: the same 404 as an unknown device, not a test result.
                throw e;
            } catch (ActionFailedException | DeviceOfflineException | UnsupportedActionException e) {
                // Written for the user: the device and what it could not do.
                return new DeepLinkTestResult(FAILED, before, null, "The test link was not opened: " + e.getMessage());
            } catch (RuntimeException e) {
                // Anything else is a fault of this server, whose message may name its internals.
                log.warn("The deep-link test on {} failed unexpectedly", deviceId, e);
                return new DeepLinkTestResult(FAILED, before, null,
                        "The test link was not opened: an unexpected error, which the server's log describes");
            }
            if (reporting == ForegroundAppReporting.NONE) {
                return new DeepLinkTestResult(NOT_OBSERVABLE, before, null, "Sent. " + device.name()
                        + " does not report which app is in front, so this page cannot tell whether YouTube opened."
                        + CHECK_THE_SCREEN);
            }
            Optional<String> after = awaitAnotherApp(queue, before);
            long seconds = Math.max(1, properties.timeout().toSeconds());
            if (after.isPresent()) {
                String how = reporting == ForegroundAppReporting.POLLED
                        ? " (polled; this device only reports a few known apps)." : ".";
                return new DeepLinkTestResult(APP_CHANGED, before, after.get(), device.name() + " switched from "
                        + (before == null ? "no reported app" : before) + " to " + after.get() + how + CHECK_THE_SCREEN);
            }
            return new DeepLinkTestResult(NO_CHANGE, before, before, noChange(reporting, seconds, before, device.name()));
        } finally {
            watching.remove(deviceId, queue);
        }
    }

    /** Why the app in front seemed not to change, worded for how the device reports it. */
    private static String noChange(ForegroundAppReporting reporting, long seconds, String before, String deviceName) {
        if (reporting == ForegroundAppReporting.POLLED) {
            String still = before == null
                    ? "" : " (still " + before + "; if that was YouTube, go to the Home screen and test again)";
            return "No other known app reported itself in front within " + seconds + " seconds" + still
                    + ". This device is polled and its app status may be unavailable on this model; check the screen.";
        }
        String still = before == null ? "" : " (still " + before + ")";
        return "The app in front did not change within " + seconds + " seconds" + still
                + ". If that already was YouTube, go to the Home screen and test again; otherwise the YouTube app"
                + " may be missing or " + deviceName + " ignored the link; check the screen.";
    }

    private Optional<String> awaitAnotherApp(BlockingQueue<DeviceState> queue, String before) {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        try {
            for (long left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
                DeviceState next = queue.poll(left, TimeUnit.NANOSECONDS);
                if (next == null) {
                    break;
                }
                if (next.currentApp() != null && !next.currentApp().equals(before)) {
                    return Optional.of(next.currentApp());
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
