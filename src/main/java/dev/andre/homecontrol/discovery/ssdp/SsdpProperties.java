package dev.andre.homecontrol.discovery.ssdp;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.ssdp.*}. {@code port} is where searches are sent; {@code listenPort} is
 * where NOTIFY announcements are received (0 = any free port, for tests). Both are 1900 on a
 * real network. A bad value fails startup instead of surfacing later as a busy loop.
 */
@ConfigurationProperties("home-control.ssdp")
@Validated
public record SsdpProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("239.255.255.250") @NotBlank String multicastAddress,
                             @DefaultValue("1900") @Min(0) @Max(65535) int port,
                             @DefaultValue("1900") @Min(0) @Max(65535) int listenPort,
                             @DefaultValue("60") @Positive int searchIntervalSeconds,
                             @DefaultValue("2") @Positive int mx) {
}
