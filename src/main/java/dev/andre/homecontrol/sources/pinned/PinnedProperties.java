package dev.andre.homecontrol.sources.pinned;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("home-control.pinned")
public record PinnedProperties(@DefaultValue("true") boolean enabled, @DefaultValue("200") int maxPins) {
}
