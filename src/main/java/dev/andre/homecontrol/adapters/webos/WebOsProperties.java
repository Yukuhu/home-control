package dev.andre.homecontrol.adapters.webos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.webos.*}. {@code wakeGraceSeconds}: after a Wake-on-LAN packet, how long the
 * TV gets before the first reconnect. A bad value fails startup instead of surfacing later as a
 * busy reconnect loop.
 */
@ConfigurationProperties("home-control.webos")
@Validated
public record WebOsProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("3000") @Min(1) @Max(65535) int port,
                              @DefaultValue("3001") @Min(1) @Max(65535) int securePort,
                              @DefaultValue("3") @Positive int connectTimeoutSeconds,
                              @DefaultValue("10") @Positive int requestTimeoutSeconds,
                              @DefaultValue("60") @Positive int pairingTimeoutSeconds,
                              @DefaultValue("1") @Positive int reconnectInitialDelaySeconds,
                              @DefaultValue("30") @Positive int reconnectMaxDelaySeconds,
                              @DefaultValue("3") @PositiveOrZero int wakeGraceSeconds) {

    public WebOsProperties {
        if (reconnectMaxDelaySeconds < reconnectInitialDelaySeconds) {
            throw new IllegalArgumentException("home-control.webos.reconnect-max-delay-seconds (" + reconnectMaxDelaySeconds
                    + ") must not be less than home-control.webos.reconnect-initial-delay-seconds ("
                    + reconnectInitialDelaySeconds + ")");
        }
    }
}
