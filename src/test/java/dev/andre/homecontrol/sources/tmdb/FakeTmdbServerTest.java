package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FakeTmdbServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private FakeTmdbServer tmdb;

    @BeforeEach
    void start() throws IOException {
        tmdb = new FakeTmdbServer();
    }

    @AfterEach
    void stop() {
        tmdb.close();
        client.close();
    }

    private HttpResponse<String> getConfiguration() throws Exception {
        return client.send(HttpRequest.newBuilder(tmdb.url().resolve("/3/configuration")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void resetRestoresTheFreshFake() throws Exception {
        tmdb.respondJson("GET", "/3/configuration", 200, "{}");
        assertThat(getConfiguration().statusCode()).isEqualTo(200);
        assertThat(tmdb.requests()).hasSize(1);

        tmdb.reset();

        assertThat(tmdb.requests()).isEmpty();
        HttpResponse<String> fresh = getConfiguration();
        assertThat(fresh.statusCode()).isEqualTo(404);
        assertThat(fresh.body()).isEqualTo(FakeTmdbServer.fixture("not-found.json"));
    }
}
