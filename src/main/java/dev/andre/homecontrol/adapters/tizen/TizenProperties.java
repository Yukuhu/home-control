package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.tizen.protocol.TizenOptions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.tizen.*}. {@code clientName} is what the TV shows in its Allow prompt and its
 * list of connected devices. A bad value fails startup instead of surfacing later as a busy poll loop.
 */
@ConfigurationProperties("home-control.tizen")
@Validated
public record TizenProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("8002") @Min(1) @Max(65535) int port,
                              @DefaultValue("8001") @Min(1) @Max(65535) int restPort,
                              @DefaultValue("8080") @Min(1) @Max(65535) int dialPort,
                              @DefaultValue("Home Control") @NotBlank String clientName,
                              @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration connectTimeout,
                              @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration requestTimeout,
                              @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration pairingTimeout,
                              @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration pollInterval,
                              @DefaultValue("3s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 0)
                              Duration wakeGrace) {

    /** The protocol clients' ports, name and waits. */
    public TizenOptions protocol() {
        return new TizenOptions(port, restPort, dialPort, clientName, connectTimeout, requestTimeout);
    }
}
