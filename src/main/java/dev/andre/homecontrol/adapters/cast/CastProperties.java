package dev.andre.homecontrol.adapters.cast;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.cast.*}. Defaults make the module work with no configuration at all;
 * a bad value fails startup instead of surfacing later as a busy loop or a flapping connection.
 */
@ConfigurationProperties("home-control.cast")
@Validated
public record CastProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration heartbeatInterval,
                             @DefaultValue("15s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration staleTimeout,
                             @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration reconnectInitialDelay,
                             @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration reconnectMaxDelay,
                             @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration commandTimeout,
                             @DefaultValue("20s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration loadTimeout,
                             @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration mediaStatusInterval) {

    public CastProperties {
        // The stale timeout is the socket read timeout; a healthy receiver answers each ping, so
        // it must be longer than one heartbeat interval or every idle connection reads as stale.
        if (staleTimeout != null && heartbeatInterval != null && staleTimeout.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("home-control.cast.stale-timeout (" + staleTimeout
                    + ") must be greater than home-control.cast.heartbeat-interval (" + heartbeatInterval + ")");
        }
    }
}
