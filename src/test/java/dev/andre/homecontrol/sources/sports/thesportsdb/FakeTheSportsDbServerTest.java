package dev.andre.homecontrol.sources.sports.thesportsdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FakeTheSportsDbServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private FakeTheSportsDbServer sportsDb;

    @BeforeEach
    void start() throws IOException {
        sportsDb = new FakeTheSportsDbServer();
    }

    @AfterEach
    void stop() {
        sportsDb.close();
        client.close();
    }

    private HttpResponse<String> lookupLeague(String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(sportsDb.apiBase() + "/" + key + "/lookupleague.php?id=4328"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = FakeTheSportsDbServerTest.class.getResourceAsStream("/fixtures/thesportsdb/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void resetRestoresTheFreshFake() throws Exception {
        sportsDb.respondJson("lookupleague.php", Map.of("id", "4328"), 200, "{\"leagues\":[]}");
        assertThat(lookupLeague(FakeTheSportsDbServer.FREE_KEY).body()).isEqualTo("{\"leagues\":[]}");
        assertThat(sportsDb.count("lookupleague.php")).isEqualTo(1);

        sportsDb.reset();

        assertThat(sportsDb.count("lookupleague.php")).isZero();
        HttpResponse<String> fresh = lookupLeague(FakeTheSportsDbServer.FREE_KEY);
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(fresh.body()).isEqualTo(fixture("lookupleague-unknown.json"));
        assertThat(lookupLeague("999").statusCode()).isEqualTo(400);
    }
}
