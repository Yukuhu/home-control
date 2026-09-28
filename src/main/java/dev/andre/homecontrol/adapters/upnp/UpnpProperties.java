package dev.andre.homecontrol.adapters.upnp;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.upnp.*}. Renderers push nothing we subscribe to, so state is polled: fast while
 * something plays, slower when idle. A bad value fails startup instead of surfacing as a busy loop.
 */
@ConfigurationProperties("home-control.upnp")
@Validated
public record UpnpProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("2s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration pollInterval,
                             @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration idlePollInterval,
                             @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration commandTimeout,
                             @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration connectTimeout,
                             @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration reconnectInitialDelay,
                             @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration reconnectMaxDelay) {
}
