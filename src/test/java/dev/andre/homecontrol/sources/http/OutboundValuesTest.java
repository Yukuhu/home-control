package dev.andre.homecontrol.sources.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

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

    @Test
    void onlyGetAndPostWithANonNegativeCapAreRequests() {
        Map<String, String> noHeaders = Map.of();
        OptionalLong noLength = OptionalLong.empty();
        assertThatThrownBy(() -> new OutboundRequest("PUT", ANSWERED, noHeaders, null, null, 0, true, noLength, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Only GET and POST are supported");
        OutboundRequest get = OutboundRequest.get(ANSWERED);
        assertThatThrownBy(() -> get.limitedTo(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A body cap cannot be negative");
    }

    @Test
    void requestsThatDifferInAnyPartAreNotEqual() {
        var request = OutboundRequest.get(ANSWERED).header("Accept", "application/json");

        assertThat(request)
                .isNotEqualTo(OutboundRequest.get(URI.create("https://api.example/other")).header("Accept", "application/json"))
                .isNotEqualTo(OutboundRequest.get(ANSWERED).header("Accept", "text/plain"))
                .isNotEqualTo(OutboundRequest.post(ANSWERED, new byte[0], null).header("Accept", "application/json"))
                .isNotEqualTo(request.limitedTo(10))
                .isNotEqualTo(request.failingFastWhenBusy())
                .isNotEqualTo(request.endingBy(42))
                .isNotEqualTo(request.withErrorBody())
                .isNotEqualTo("GET api.example");
        var post = OutboundRequest.post(ANSWERED, new byte[] {1}, "text/plain");
        assertThat(post).isNotEqualTo(OutboundRequest.post(ANSWERED, new byte[] {1}, "application/json"))
                .isNotEqualTo(OutboundRequest.post(ANSWERED, new byte[] {2}, "text/plain"));
    }

    @Test
    void keepingOnlyNamedHeadersIgnoresTheirCase() {
        var request = OutboundRequest.get(ANSWERED).header("ACCEPT", "text/plain").header("Authorization", "Bearer secret");

        assertThat(request.keepingOnly(Set.of("accept")).headers()).containsExactly(Map.entry("ACCEPT", "text/plain"));
    }
}
