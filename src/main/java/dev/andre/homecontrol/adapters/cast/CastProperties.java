package dev.andre.homecontrol.adapters.cast;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.cast.*}. Defaults make the module work with no configuration at all;
 * a bad value fails startup instead of surfacing later as a busy loop or a flapping connection.
 */
@ConfigurationProperties("home-control.cast")
@Validated
public record CastProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("5") @Positive int heartbeatIntervalSeconds,
                             @DefaultValue("15") @Positive int staleTimeoutSeconds,
                             @DefaultValue("1") @Positive int reconnectInitialDelaySeconds,
                             @DefaultValue("60") @Positive int reconnectMaxDelaySeconds,
                             @DefaultValue("5") @Positive int commandTimeoutSeconds,
                             @DefaultValue("20") @Positive int loadTimeoutSeconds,
                             @DefaultValue("5") @Positive int mediaStatusIntervalSeconds) {

    public CastProperties {
        // The stale timeout is the socket read timeout; a healthy receiver answers each ping, so
        // it must be longer than one heartbeat interval or every idle connection reads as stale.
        if (staleTimeoutSeconds <= heartbeatIntervalSeconds) {
            throw new IllegalArgumentException("home-control.cast.stale-timeout-seconds (" + staleTimeoutSeconds
                    + ") must be greater than home-control.cast.heartbeat-interval-seconds (" + heartbeatIntervalSeconds + ")");
        }
    }
}
