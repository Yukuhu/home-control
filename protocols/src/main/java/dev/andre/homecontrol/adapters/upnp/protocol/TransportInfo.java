package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Locale;
import java.util.Map;

public record TransportInfo(String state, String status) {

    public static final TransportInfo NONE = new TransportInfo("NO_MEDIA_PRESENT", "OK");

    public static TransportInfo from(Map<String, String> answer) {
        return new TransportInfo(
                answer.getOrDefault("CurrentTransportState", "NO_MEDIA_PRESENT").strip().toUpperCase(Locale.ROOT),
                answer.getOrDefault("CurrentTransportStatus", "OK").strip());
    }

    /** Something is loaded and playing, paused or about to play: poll faster. */
    public boolean active() {
        return switch (state) {
            case "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> true;
            default -> false;
        };
    }
}
