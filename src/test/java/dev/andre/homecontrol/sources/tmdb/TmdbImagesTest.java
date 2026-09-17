package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbImagesTest {

    FakeTmdbServer fake;
    MutableClock clock;
    TmdbCredential bearer;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
        clock = MutableClock.at(Instant.parse("2026-09-16T10:00:00Z"));
        bearer = TmdbCredential.parse(FakeTmdbServer.READ_TOKEN);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private TmdbImages images(TmdbProperties properties) {
        return new TmdbImages(new TmdbClient(properties), properties, clock);
    }

    private TmdbProperties properties(URI imageBaseUrl) {
        return new TmdbProperties(true, fake.apiBase(), imageBaseUrl, 1, 1, 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);
    }

    @Test
    void buildsPosterUrisFromTheConfiguration() {
        TmdbImages images = images(properties(null));

        URI uri = images.poster(bearer, "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg");

        assertThat(uri).isEqualTo(URI.create("https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"));

        images.poster(bearer, "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg");
        assertThat(fake.count("GET", "/3/configuration")).isEqualTo(1);
    }

    @Test
    void picksTheBestSizeWhenW342IsMissing() {
        fake.respondJson("GET", "/3/configuration", 200, """
                {"images":{"secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["w92","w500","w780","original"]}}""");
        TmdbImages images = images(properties(null));

        assertThat(images.poster(bearer, "/x.jpg"))
                .isEqualTo(URI.create("https://image.tmdb.org/t/p/w500/x.jpg"));

        fake.respondJson("GET", "/3/configuration", 200, """
                {"images":{"secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["original"]}}""");
        TmdbImages second = images(properties(null));
        assertThat(second.poster(bearer, "/x.jpg"))
                .isEqualTo(URI.create("https://image.tmdb.org/t/p/original/x.jpg"));
    }

    @Test
    void ignoresAnInsecureBase() {
        fake.respondJson("GET", "/3/configuration", 200, """
                {"images":{"secure_base_url":"http://evil.example/","poster_sizes":["w342"]}}""");
        TmdbImages images = images(properties(null));

        assertThat(images.poster(bearer, "/x.jpg"))
                .isEqualTo(URI.create("https://image.tmdb.org/t/p/w342/x.jpg"));
    }

    @Test
    void fallsBackWhenConfigurationFailsAndRetriesLater() {
        fake.respondJson("GET", "/3/configuration", 500, "{}");
        TmdbImages images = images(properties(null));

        URI first = images.poster(bearer, "/x.jpg");
        assertThat(first).isEqualTo(URI.create("https://image.tmdb.org/t/p/w342/x.jpg"));

        clock.advance(Duration.ofMinutes(5));
        images.poster(bearer, "/x.jpg");
        assertThat(fake.count("GET", "/3/configuration")).isEqualTo(1);

        clock.advance(Duration.ofMinutes(6));
        images.poster(bearer, "/x.jpg");
        assertThat(fake.count("GET", "/3/configuration")).isEqualTo(2);
    }

    @Test
    void anOverrideSkipsTheConfiguration() {
        TmdbImages images = images(properties(URI.create("https://img.example/t/p/")));

        URI uri = images.poster(bearer, "/x.jpg");

        assertThat(uri).isEqualTo(URI.create("https://img.example/t/p/w342/x.jpg"));
        assertThat(fake.count("GET", "/3/configuration")).isZero();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"x.jpg", "//evil.example/x.jpg", "/../x.jpg", "/a/b.jpg", "/x.jpg?y", "/x.gif"})
    void rejectsMalformedPaths(String path) {
        TmdbImages images = images(properties(null));

        assertThat(images.poster(bearer, path)).isNull();
    }
}
