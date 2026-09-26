package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.MacAddress;
import dev.andre.homecontrol.core.TvInput;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the SSAP payloads whose shape changed across webOS versions. */
final class WebOsPayloads {

    private WebOsPayloads() {
    }

    /** webOS 5+ nests {@code volumeStatus{volume,maxVolume,muteStatus}}; older sets send {@code volume} and {@code muted}. */
    static DeviceState volume(DeviceState state, JsonNode payload) {
        JsonNode status = payload.has("volumeStatus") ? payload.path("volumeStatus") : payload;
        int level = status.path("volume").asInt(state.volumeLevel());
        int max = status.path("maxVolume").asInt(100);
        boolean muted = status.has("muteStatus")
                ? status.path("muteStatus").asBoolean(false)
                : status.path("muted").asBoolean(state.muted());
        return state.withVolume(level, max, muted);
    }

    /** The interface carrying the TV's address wins, then a connected one, then any with a MAC. */
    static Optional<String> macAddress(JsonNode payload, String host) {
        List<JsonNode> interfaces = List.of(payload.path("wiredInfo"), payload.path("wifiInfo"));
        return interfaces.stream().filter(nic -> host.equals(nic.path("ipAddress").asString(""))).findFirst()
                .or(() -> interfaces.stream()
                        .filter(nic -> "connected".equalsIgnoreCase(nic.path("state").asString(""))).findFirst())
                .or(() -> interfaces.stream().filter(nic -> !nic.path("macAddress").asString("").isEmpty()).findFirst())
                .map(nic -> nic.path("macAddress").asString(""))
                .flatMap(WebOsPayloads::parseMac);
    }

    static List<TvInput> inputs(JsonNode payload) {
        List<TvInput> inputs = new ArrayList<>();
        for (JsonNode device : payload.path("devices").values()) {
            String id = device.path("id").asString("");
            if (!id.isEmpty()) {
                inputs.add(new TvInput(id, device.path("label").asString(id)));
            }
        }
        return List.copyOf(inputs);
    }

    private static Optional<String> parseMac(String candidate) {
        try {
            return Optional.of(MacAddress.normalize(candidate));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
