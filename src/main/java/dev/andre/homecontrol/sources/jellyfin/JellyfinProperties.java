package dev.andre.homecontrol.sources.jellyfin;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** {@code home-control.jellyfin.*}. {@code enabled=false} removes the module entirely (spec §7). */
@ConfigurationProperties("home-control.jellyfin")
@Validated
public record JellyfinProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("5") @Positive int connectTimeoutSeconds,
                                 @DefaultValue("15") @Positive int requestTimeoutSeconds,
                                 @DefaultValue("20") @Positive int railSize) {
}
