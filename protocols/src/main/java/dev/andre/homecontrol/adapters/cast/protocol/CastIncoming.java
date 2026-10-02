package dev.andre.homecontrol.adapters.cast.protocol;

import tools.jackson.databind.JsonNode;

/** One inbound string message with its JSON payload already parsed. */
public record CastIncoming(String namespace, String sourceId, String destinationId, JsonNode payload) {

    public String type() {
        return payload.path("type").asString("");
    }

    /** 0 for unsolicited broadcasts. */
    public int requestId() {
        return payload.path("requestId").asInt(0);
    }

    /** "LAUNCH_ERROR: NOT_FOUND", or just the type when the receiver gave no reason. */
    public String describeFailure() {
        String reason = payload.path("reason").asString("");
        return reason.isBlank() ? type() : type() + ": " + reason;
    }
}
