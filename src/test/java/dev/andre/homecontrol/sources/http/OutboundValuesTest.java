package dev.andre.homecontrol.sources.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Requests and responses compare by their bodies' content and never print a body, a path or a header value. */
class OutboundValuesTest {

    @Test
    void aResponseComparesByContentAndPrintsOnlyItsSize() {
        var body = new OutboundResponse(200, "text/plain", new byte[] {1, 2, 3}, Map.of("x-key", "secret"));
        var same = new OutboundResponse(200, "text/plain", new byte[] {1, 2, 3}, Map.of("x-key", "secret"));

        assertThat(body).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(new OutboundResponse(200, "text/plain", new byte[] {1, 2, 4}, Map.of("x-key", "secret")))
                .isNotEqualTo(new OutboundResponse(302, "text/plain", new byte[] {1, 2, 3}, Map.of("x-key", "secret")));
        assertThat(body).hasToString("OutboundResponse[status=200, 3 bytes]");
        assertThat(body.header("X-Key")).isEqualTo("secret");
    }

    @Test
    void aRequestComparesByContentAndPrintsOnlyItsMethodAndHost() {
        URI uri = URI.create("https://api.example/secret-path?token=secret");
        var post = OutboundRequest.post(uri, "a=1".getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded")
                .header("Authorization", "Bearer secret");

        assertThat(post).isEqualTo(OutboundRequest.post(uri, "a=1".getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").header("Authorization", "Bearer secret"));
        assertThat(post).hasSameHashCodeAs(OutboundRequest.post(uri, "a=1".getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded").header("Authorization", "Bearer secret"));
        assertThat(post).isNotEqualTo(OutboundRequest.get(uri));
        assertThat(post).hasToString("OutboundRequest[POST api.example]");
    }
}
