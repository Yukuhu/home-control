package dev.andre.homecontrol.adapters.net;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code home-control.wake-on-lan.*}. Use a subnet broadcast (e.g. 192.168.1.255) if the host has
 * several networks. A bad value fails startup instead of surfacing when a TV should wake.
 */
@ConfigurationProperties("home-control.wake-on-lan")
@Validated
public record WakeOnLanProperties(@DefaultValue("255.255.255.255") @NotBlank String broadcastAddress,
                                  @DefaultValue("9") @Min(1) @Max(65535) int port) {
}
