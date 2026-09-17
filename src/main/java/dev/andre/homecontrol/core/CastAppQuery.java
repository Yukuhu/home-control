package dev.andre.homecontrol.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A question for a Cast receiver app whose answer the caller needs, e.g. a receiver's screen id.
 * {@code message} is sent unchanged on {@code namespace}; the first reply whose {@code type} is {@code replyType} answers.
 */
public record CastAppQuery(String receiverAppId, String namespace, Map<String, Object> message, String replyType) {

    public CastAppQuery {
        message = message == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(message));
    }
}
