package dev.andre.homecontrol.sources.pinned;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("home-control.pinned")
@Validated
public record PinnedProperties(@DefaultValue("true") boolean enabled, @DefaultValue("200") @Positive int maxPins) {
}
