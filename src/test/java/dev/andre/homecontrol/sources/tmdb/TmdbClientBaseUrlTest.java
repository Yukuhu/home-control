package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A configured API base URL may end in slashes. */
class TmdbClientBaseUrlTest {

    private FakeTmdbServer fake;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeTmdbServer().withStandardResponses();
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void trailingSlashesOnTheBaseUrlAreDropped() {
        TmdbProperties properties = new TmdbProperties(true, URI.create(fake.apiBase() + "//"), null,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 20, 40,
                Duration.ofHours(24), Duration.ofHours(24), null);

        new TmdbClient(properties).get(TmdbCredential.parse(FakeTmdbServer.READ_TOKEN), "/authentication", Map.of());

        assertThat(fake.count("GET", "/3/authentication")).isEqualTo(1);
    }
}
