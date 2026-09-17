package dev.andre.homecontrol.adapters.sonos;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.sonos.*}. Transport and volume are polled like UPnP renderers; the household's
 * groups are re-read every {@code topologyIntervalSeconds}. A bad value fails startup.
 */
@ConfigurationProperties("home-control.sonos")
@Validated
public record SonosProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("2") @Positive int pollIntervalSeconds,
                              @DefaultValue("10") @Positive int idlePollIntervalSeconds,
                              @DefaultValue("30") @PositiveOrZero int topologyIntervalSeconds,
                              @DefaultValue("5") @Positive int commandTimeoutSeconds,
                              @DefaultValue("3") @Positive int connectTimeoutSeconds,
                              @DefaultValue("1") @Positive int reconnectInitialDelaySeconds,
                              @DefaultValue("60") @Positive int reconnectMaxDelaySeconds) {
}
