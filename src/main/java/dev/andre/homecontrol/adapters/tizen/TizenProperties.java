package dev.andre.homecontrol.adapters.tizen;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
                              @DefaultValue("3") @Positive int connectTimeoutSeconds,
                              @DefaultValue("5") @Positive int requestTimeoutSeconds,
                              @DefaultValue("30") @Positive int pairingTimeoutSeconds,
                              @DefaultValue("5") @Positive int pollIntervalSeconds,
                              @DefaultValue("3") @PositiveOrZero int wakeGraceSeconds) {
}
