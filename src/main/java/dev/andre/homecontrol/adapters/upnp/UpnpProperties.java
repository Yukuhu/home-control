package dev.andre.homecontrol.adapters.upnp;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.upnp.*}. Renderers push nothing we subscribe to, so state is polled: fast while
 * something plays, slower when idle. A bad value fails startup instead of surfacing as a busy loop.
 */
@ConfigurationProperties("home-control.upnp")
@Validated
public record UpnpProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("2") @Positive int pollIntervalSeconds,
                             @DefaultValue("10") @Positive int idlePollIntervalSeconds,
                             @DefaultValue("5") @Positive int commandTimeoutSeconds,
                             @DefaultValue("3") @Positive int connectTimeoutSeconds,
                             @DefaultValue("1") @Positive int reconnectInitialDelaySeconds,
                             @DefaultValue("60") @Positive int reconnectMaxDelaySeconds) {
}
