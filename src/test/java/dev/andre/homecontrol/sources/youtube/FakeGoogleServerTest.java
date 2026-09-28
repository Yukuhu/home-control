package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.sources.youtube.FakeGoogleServer.Canned;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FakeGoogleServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private FakeGoogleServer google;

    @BeforeEach
    void start() throws IOException {
        google = new FakeGoogleServer();
    }

    @AfterEach
    void stop() {
        google.close();
        client.close();
    }

    private HttpResponse<String> getProbe() throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(google.base() + "/probe")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void resetRestoresTheFreshFake() throws Exception {
        google.respond("GET", "/probe", Canned.json(200, "{}"));
        assertThat(getProbe().statusCode()).isEqualTo(200);
        assertThat(google.requests()).hasSize(1);

        google.reset();

        assertThat(google.requests()).isEmpty();
        HttpResponse<String> fresh = getProbe();
        assertThat(fresh.statusCode()).isEqualTo(404);
        assertThat(fresh.body()).contains("no fake route");
    }
}
