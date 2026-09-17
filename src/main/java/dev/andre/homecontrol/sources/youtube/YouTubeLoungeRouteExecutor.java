package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.device.DeviceManager;

import java.util.Map;
import java.util.UUID;

/**
 * Plays a {@link Route.YouTubeLounge}: asks the YouTube receiver on the Cast device for its screen id,
 * then starts the video through the unofficial Lounge API. Best effort; every failure names the step.
 * Offline and unsupported devices pass through unchanged so the play sheet can say so.
 */
public class YouTubeLoungeRouteExecutor implements RouteExecutor {

    public static final String RECEIVER_APP_ID = "233637DE";
    public static final String MDX_NAMESPACE = "urn:x-cast:com.google.youtube.mdx";
    private static final CastAppQuery SESSION_STATUS = new CastAppQuery(RECEIVER_APP_ID, MDX_NAMESPACE,
            Map.of("type", "getMdxSessionStatus"), "mdxSessionStatus");

    private final DeviceManager devices;
    private final LoungeClient lounge;
    private final YouTubeSetupService setup;

    public YouTubeLoungeRouteExecutor(DeviceManager devices, LoungeClient lounge, YouTubeSetupService setup) {
        this.devices = devices;
        this.lounge = lounge;
        this.setup = setup;
    }

    @Override
    public boolean executes(Route route) {
        return route instanceof Route.YouTubeLounge;
    }

    @Override
    public void execute(Route route, Device device) {
        Route.YouTubeLounge play = (Route.YouTubeLounge) route;
        Map<String, Object> reply;
        try {
            reply = devices.query(device.id(), SESSION_STATUS);
        } catch (ActionFailedException e) {
            throw failed(device, "the YouTube receiver did not answer (" + e.getMessage() + ")");
        }
        if (!(reply.get("data") instanceof Map<?, ?> data) || !(data.get("screenId") instanceof String screenId)
                || screenId.isBlank()) {
            throw failed(device, "the YouTube receiver did not report a screen id");
        }
        try {
            String token = lounge.loungeToken(screenId);
            LoungeClient.LoungeSession session = lounge.bind(token, remoteId());
            lounge.setPlaylist(token, session, play.videoId());
        } catch (LoungeException e) {
            throw failed(device, e.getMessage());
        }
    }

    /** One id per server, so the TV lists Home Control as one remote however often it plays. */
    private String remoteId() {
        YouTubeSettings settings = setup.settings();
        if (settings.loungeRemoteId() != null && !settings.loungeRemoteId().isBlank()) {
            return settings.loungeRemoteId();
        }
        String created = UUID.randomUUID().toString();
        setup.save(settings.withLoungeRemoteId(created));
        return created;
    }

    private static ActionFailedException failed(Device device, String reason) {
        return new ActionFailedException(device.name() + ": YouTube Cast (best effort, unofficial API) failed: " + reason
                + ". Use the YouTube app route, or switch YouTube Cast off for this device in Setup.");
    }
}
