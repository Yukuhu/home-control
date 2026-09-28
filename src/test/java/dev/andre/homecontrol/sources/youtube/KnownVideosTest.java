package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnownVideosTest {

    private static final YouTubeVideo VIDEO =
            new YouTubeVideo("Kz1aT5nM3pQ", "A", "Chan", Instant.parse("2026-09-15T14:00:12Z"));

    @Test
    void remembersVideosByTheirId() {
        KnownVideos known = new KnownVideos(10);

        known.remember(List.of(VIDEO));

        assertThat(known.find("Kz1aT5nM3pQ")).contains(VIDEO);
    }

    @Test
    void resetForgetsEveryVideo() {
        KnownVideos known = new KnownVideos(10);
        known.remember(List.of(VIDEO));

        known.reset();

        assertThat(known.find("Kz1aT5nM3pQ")).isEmpty();
    }
}
