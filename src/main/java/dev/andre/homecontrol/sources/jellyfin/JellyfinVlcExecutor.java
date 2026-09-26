package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.device.DeviceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves credentials only during execution; sends the VLC playback link once. */
public class JellyfinVlcExecutor implements RouteExecutor {
    private static final Logger log = LoggerFactory.getLogger(JellyfinVlcExecutor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JellyfinSetupService setup;
    private final JellyfinClient client;
    private final DeviceManager devices;
    private final Duration timeout;

    public JellyfinVlcExecutor(JellyfinSetupService setup, JellyfinClient client, DeviceManager devices, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Startup timeout must be positive");
        this.setup = setup;
        this.client = client;
        this.devices = devices;
        this.timeout = timeout;
    }

    @Override
    public boolean executes(Route route) {
        return route instanceof Route.JellyfinVlc;
    }

    @Override
    public void execute(Route route, Device device) {
        if (!(route instanceof Route.JellyfinVlc(var itemId)) || !device.hasAdapter("androidtv")) {
            throw new IllegalArgumentException("VLC needs an Android TV device");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            URI link = resolveWithinDeadline(itemId, deadline);
            wake(device, deadline);
            checkDeadline(deadline);
            log.info("Sending VLC playback link to {}", device.id());
            // The link also starts playback. Never retry it after a successful socket write.
            devices.execute(device.id(), new Action.OpenAppLink(link));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new ActionFailedException("VLC startup was interrupted");
        } catch (DeviceOfflineException _) {
            // Adapter errors can contain the authenticated URI; never pass their text to the browser.
            throw new DeviceOfflineException("The remote connection to " + device.name() + " was lost while starting VLC");
        } catch (JellyfinException | IllegalArgumentException _) {
            throw new ActionFailedException("Could not prepare the Jellyfin stream for VLC; check the Jellyfin connection and media availability");
        }
    }

    private URI resolveWithinDeadline(String itemId, long deadline) throws InterruptedException {
        FutureTask<URI> lookup = new FutureTask<>(() -> streamLink(itemId));
        Thread.ofVirtual().name("jellyfin-vlc-stream").start(lookup);
        try {
            return lookup.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException _) {
            throw new ActionFailedException("Jellyfin did not provide a VLC stream in time");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new ActionFailedException("Could not prepare the Jellyfin stream for VLC");
        } finally {
            // This worker only resolves the link; a late result cannot launch playback.
            lookup.cancel(true);
        }
    }

    private URI streamLink(String itemId) {
        String id = JellyfinClient.id(itemId);
        JellyfinSettings settings = setup.settings().orElseThrow(() -> new IllegalArgumentException("Jellyfin not configured"));
        JellyfinConnection connection = setup.connection().orElseThrow(() -> new IllegalArgumentException("Jellyfin not connected"));
        var item = client.get(connection, "/Items/" + id, Map.of("userId", connection.userId()));
        String type = item.path("MediaType").asString("");
        if (!"Video".equals(type) && !"Audio".equals(type)) {
            throw new ActionFailedException("VLC can open Jellyfin video and audio items only");
        }
        var request = JSON.createObjectNode();
        request.put("UserId", connection.userId());
        request.put("EnableDirectPlay", true);
        request.put("EnableDirectStream", false);
        request.put("EnableTranscoding", false);
        request.put("AutoOpenLiveStream", false);
        var info = client.post(connection, "/Items/" + id + "/PlaybackInfo", Map.of(), request);
        for (var source : info.path("MediaSources")) {
            String sourceId = source.path("Id").asString("");
            if (sourceId.isBlank() || !source.path("SupportsDirectPlay").asBoolean(false)
                    || source.path("RequiresOpening").asBoolean(false)
                    || source.path("RequiresClosing").asBoolean(false)
                    || source.path("IsInfiniteStream").asBoolean(false)) continue;
            // VLC, unlike the Cast renderer, can fetch the original container (including MKV).
            String stream = settings.deviceServerUrl() + ("Audio".equals(type) ? "/Audio/" : "/Videos/")
                    + id + "/stream?static=true&mediaSourceId=" + encode(sourceId) + "&api_key=" + encode(connection.token());
            // VLC's MediaWrapper.manageVLCMrl removes precisely this prefix.
            return URI.create("vlc://" + stream);
        }
        throw new ActionFailedException("Jellyfin has no direct stream for VLC; use the Jellyfin app for this item");
    }

    private void wake(Device device, long deadline) throws InterruptedException {
        long nextWake = 0;
        while (true) {
            checkDeadline(deadline);
            var state = devices.state(device.id());
            if (state.status() == DeviceStatus.UNPAIRED) {
                throw new ActionFailedException(device.name() + " must be paired before VLC can start");
            }
            if (state.connected() && state.powerOn()) return;
            if (state.connected() && System.nanoTime() >= nextWake) {
                try {
                    devices.execute(device.id(), new Action.PressKey(RemoteKey.WAKEUP));
                } catch (DeviceOfflineException _) {
                    // Only the idempotent wake command can be retried after reconnecting.
                }
                nextWake = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            }
            TimeUnit.NANOSECONDS.sleep(Math.clamp(deadline - System.nanoTime(), 0, Duration.ofMillis(250).toNanos()));
        }
    }

    private static void checkDeadline(long deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (System.nanoTime() >= deadline) throw new ActionFailedException("The device did not become ready to open VLC in time");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
