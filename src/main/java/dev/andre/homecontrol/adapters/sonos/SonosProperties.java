package dev.andre.homecontrol.adapters.sonos;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.sonos.*}. Transport and volume are polled like UPnP renderers; the household's
 * groups are re-read every {@code topologyInterval}. A bad value fails startup.
 */
@ConfigurationProperties("home-control.sonos")
@Validated
public record SonosProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("2s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration pollInterval,
                              @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration idlePollInterval,
                              @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 0)
                              Duration topologyInterval,
                              @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration commandTimeout,
                              @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration connectTimeout,
                              @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration reconnectInitialDelay,
                              @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration reconnectMaxDelay) {
}
