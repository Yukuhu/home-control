package dev.andre.homecontrol.sources.sports.thesportsdb;

import dev.andre.homecontrol.sources.sports.SportsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** A configured API base URL may end in slashes. */
class TheSportsDbClientBaseUrlTest {

    private FakeTheSportsDbServer server;

    @BeforeEach
    void start() throws IOException {
        server = new FakeTheSportsDbServer().withStandardResponses();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void trailingSlashesOnTheBaseUrlAreDropped() {
        SportsProperties.TheSportsDb properties = new SportsProperties.TheSportsDb(
                true, URI.create(server.apiBase() + "//"), "123", Duration.ofHours(24), 1, 2, null);

        new TheSportsDbClient(properties).lookupLeague("123", "4331");

        FakeTheSportsDbServer.Recorded lookup = server.last("lookupleague.php");
        assertThat(lookup.key()).isEqualTo("123");
        assertThat(lookup.query()).containsEntry("id", "4331");
    }
}
