package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.support.DurationText;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * {@code home-control.androidtv.*}: the keystore password (generated and kept in secrets.json when empty) and the
 * connection's waits. A wait of zero fails startup: a zero reconnect delay retries an unreachable Shield in a tight
 * loop, and a zero stale timeout never notices a dead connection.
 */
@ConfigurationProperties("home-control.androidtv")
@Validated
public record AndroidTvProperties(@DefaultValue("true") boolean enabled,
                                  String keystorePassword,
                                  @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                  Duration staleTimeout,
                                  @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                  Duration reconnectInitialDelay,
                                  @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                  Duration reconnectMaxDelay) {

    public AndroidTvProperties {
        if (reconnectMaxDelay != null && reconnectInitialDelay != null
                && reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalArgumentException("home-control.androidtv.reconnect-max-delay ("
                    + DurationText.of(reconnectMaxDelay) + ") must not be less than"
                    + " home-control.androidtv.reconnect-initial-delay ("
                    + DurationText.of(reconnectInitialDelay) + ")");
        }
    }
}
