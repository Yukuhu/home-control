package dev.andre.homecontrol.adapters.cast.protocol;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The {@code status} object of a RECEIVER_STATUS message. */
public record ReceiverStatus(double volumeLevel, boolean muted, boolean standBy, List<ReceiverApp> applications) {

    public record ReceiverApp(String appId, String displayName, String sessionId, String transportId,
                              boolean idleScreen, List<String> namespaces) {

        public boolean speaks(String namespace) {
            return namespaces.contains(namespace);
        }
    }

    public static ReceiverStatus parse(JsonNode status) {
        List<ReceiverApp> applications = new ArrayList<>();
        for (JsonNode app : status.path("applications")) {
            List<String> namespaces = new ArrayList<>();
            for (JsonNode namespace : app.path("namespaces")) {
                namespaces.add(namespace.path("name").asString(""));
            }
            applications.add(new ReceiverApp(
                    app.path("appId").asString(""),
                    app.path("displayName").asString(""),
                    app.path("sessionId").asString(""),
                    app.path("transportId").asString(""),
                    app.path("isIdleScreen").asBoolean(false),
                    List.copyOf(namespaces)));
        }
        JsonNode volume = status.path("volume");
        return new ReceiverStatus(volume.path("level").asDouble(0.0), volume.path("muted").asBoolean(false),
                status.path("isStandBy").asBoolean(false), List.copyOf(applications));
    }

    /** The app the user sees, ignoring the Backdrop idle screen. */
    public Optional<ReceiverApp> foregroundApp() {
        return applications.stream().filter(app -> !app.idleScreen()).findFirst();
    }

    public Optional<ReceiverApp> app(String appId) {
        return applications.stream().filter(app -> app.appId().equals(appId)).findFirst();
    }

    public int volumePercent() {
        return (int) Math.round(volumeLevel * 100);
    }
}
