package dev.andre.homecontrol.sources.jellyfin;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/** {@code home-control.jellyfin.*}. {@code enabled=false} removes the module entirely (spec §7). */
@ConfigurationProperties("home-control.jellyfin")
@Validated
public record JellyfinProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                 Duration connectTimeout,
                                 @DefaultValue("15s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                 Duration requestTimeout,
                                 @DefaultValue("20") @Positive int railSize,
                                 @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                 Duration startupTimeout) {
}
