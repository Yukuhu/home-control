package dev.andre.homecontrol.sources.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requests and responses compare by their bodies' content and never print a body, a path or a header value. */
class OutboundValuesTest {

    private static final URI ANSWERED = URI.create("https://api.example/answer");

    @Test
    void aBodyCapWithNoRoomForOneMoreByteIsRefused() {
        OutboundRequest request = OutboundRequest.get(ANSWERED);

        assertThatThrownBy(() -> request.limitedTo(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeadlineKeepsARequestFailingFastWhenBusy() {
        assertThat(OutboundRequest.get(ANSWERED).failingFastWhenBusy().endingBy(42).waitForSlot()).isFalse();
        assertThat(OutboundRequest.get(ANSWERED).endingBy(42).waitForSlot()).isTrue();
    }

    @Test
    void aResponseComparesByContentAndPrintsOnlyItsSize() {
        var body = new OutboundResponse(200, "text/plain", new byte[] {1, 2, 3}, Map.of("x-key", "secret"), ANSWERED);
        var same = new OutboundResponse(200, "text/plain", new byte[] {1, 2, 3}, Map.of("x-key", "secret"), ANSWERED);

        assertThat(body).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(new OutboundResponse(200, "text/plain", new byte[] {1, 2, 4}, Map.of("x-key", "secret"), ANSWERED))
                .isNotEqualTo(new OutboundResponse(302, "text/plain", new byte[] {1, 2, 3}, Map.of("x-key", "secret"), ANSWERED))
                .hasToString("OutboundResponse[status=200, 3 bytes]");
        assertThat(body.header("X-Key")).isEqualTo("secret");
    }

    @Test
    void aRequestComparesByContentAndPrintsOnlyItsMethodAndHost() {
        URI uri = URI.create("https://api.example/secret-path?token=secret");
        var post = OutboundRequest.post(uri, "a=1".getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded")
                .header("Authorization", "Bearer secret");
        var same = OutboundRequest.post(uri, "a=1".getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded")
                .header("Authorization", "Bearer secret");

        assertThat(post).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(OutboundRequest.get(uri))
                .hasToString("OutboundRequest[POST api.example]");
    }
}
