package dev.andre.homecontrol.playback;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * {@code home-control.deep-link-test.*}: the test video (Blender Foundation's "Big Buck Bunny",
 * long-lived and region-free) and how long to watch for a change.
 */
@ConfigurationProperties("home-control.deep-link-test")
@Validated
public record DeepLinkTestProperties(@DefaultValue("https://www.youtube.com/watch?v=aqz-KE-bpKQ") @NotNull URI youtubeUrl,
                                     @DefaultValue("10s") @NotNull Duration timeout) {

    public DeepLinkTestProperties {
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("home-control.deep-link-test.timeout must be positive");
        }
    }
}
