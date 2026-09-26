package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YouTubeHttpTest {

    private FakeGoogleServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    void encodesFormsInOrder() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("a b", "x&y");
        values.put("scope", "https://www.googleapis.com/auth/youtube.readonly");

        assertThat(YouTubeHttp.form(values))
                .isEqualTo("a+b=x%26y&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube.readonly");
    }

    @Test
    void buildsQueryUrisSkippingNullValues() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("part", "snippet");
        query.put("pageToken", null);
        query.put("mine", "true");

        URI uri = YouTubeHttp.uri(URI.create("http://h/youtube/v3"), "/subscriptions", query);

        assertThat(uri).isEqualTo(URI.create("http://h/youtube/v3/subscriptions?part=snippet&mine=true"));
    }

    @Test
    void postsAFormWithHeaders() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("POST", "/oauth/device/code", FakeGoogleServer.Canned.fixture(200, "oauth-device-code.json"));
        YouTubeHttp http = new YouTubeHttp(fake.properties());

        YouTubeHttp.Response response = http.postForm(URI.create(fake.base() + "/oauth/device/code"),
                Map.of("client_id", "c"), Map.of("X-Test", "1"));

        FakeGoogleServer.Recorded recorded = fake.requests("/oauth/device/code").getFirst();
        assertThat(recorded.header("content-type")).startsWith("application/x-www-form-urlencoded");
        assertThat(recorded.header("accept")).isEqualTo("application/json");
        assertThat(recorded.form()).isEqualTo(Map.of("client_id", "c"));
        assertThat(recorded.header("x-test")).isEqualTo("1");
        assertThat(response.json().path("user_code").asString()).isEqualTo("GQVQ-JKEC");
    }

    @Test
    void neverFollowsRedirects() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("GET", "/redirect", FakeGoogleServer.Canned.json(302, "{}"));
        YouTubeHttp http = new YouTubeHttp(fake.properties());

        YouTubeHttp.Response response = http.get(URI.create(fake.base() + "/redirect"), Map.of());

        assertThat(response.status()).isEqualTo(302);
        assertThat(fake.count("/redirect")).isEqualTo(1);
    }

    @Test
    void anUnreachableHostIsUnreachableWithoutTheQuery() throws IOException {
        fake = new FakeGoogleServer();
        YouTubeHttp http = new YouTubeHttp(fake.properties());
        URI unreachable = URI.create("http://127.0.0.1:9/x?access_token=secret");
        Map<String, String> noHeaders = Map.of();

        assertThatThrownBy(() -> http.get(unreachable, noHeaders))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.UNREACHABLE);
        assertThatThrownBy(() -> http.get(unreachable, noHeaders))
                .hasMessage("Could not reach 127.0.0.1")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
    }

    @Test
    void unparsableJsonIsABadResponse() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("GET", "/bad", new FakeGoogleServer.Canned(200, "text/plain", "not json".getBytes(StandardCharsets.UTF_8)));
        YouTubeHttp http = new YouTubeHttp(fake.properties());

        YouTubeHttp.Response response = http.get(URI.create(fake.base() + "/bad"), Map.of());

        assertThatThrownBy(response::json)
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.BAD_RESPONSE);
    }

    @Test
    void aResponseBiggerThanTheCapIsRejectedWithoutBufferingItAll() throws IOException {
        fake = new FakeGoogleServer();
        fake.respond("GET", "/big", new FakeGoogleServer.Canned(200, "application/json",
                new byte[YouTubeHttp.MAX_RESPONSE_BYTES + 1]));
        YouTubeHttp http = new YouTubeHttp(fake.properties());
        URI oversized = URI.create(fake.base() + "/big");
        Map<String, String> noHeaders = Map.of();

        assertThatThrownBy(() -> http.get(oversized, noHeaders))
                .isInstanceOf(YouTubeException.class)
                .extracting(e -> ((YouTubeException) e).kind())
                .isEqualTo(YouTubeException.Kind.BAD_RESPONSE);
        assertThatThrownBy(() -> http.get(oversized, noHeaders))
                .hasMessage("Google sent an oversized response");
    }
}
