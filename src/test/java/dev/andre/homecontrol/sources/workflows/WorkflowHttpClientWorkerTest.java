package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class WorkflowHttpClientWorkerTest {

    @Test
    void anErrorOnTheWorkerFailsTheCallerAtOnceAndReleasesItsPermit() {
        // Far longer than the class timeout: a caller left waiting for its deadline would fail the test.
        var properties = new WorkflowProperties(true, true, Duration.ofSeconds(1), Duration.ofSeconds(60), 4, 2_097_152, 3);
        var policy = new WorkflowUrlPolicy(true, host -> {
            throw new AssertionError("resolver bug");
        });
        try (var client = new WorkflowHttpClient(properties, policy)) {
            var media = URI.create("http://fixture.invalid/media");
            // More calls than permits: each failed worker must have given its permit back.
            for (int call = 0; call < 6; call++) {
                assertThatThrownBy(() -> client.checkMedia(media))
                        .isInstanceOf(WorkflowException.class)
                        .hasMessageContaining("request failed");
            }
        }
    }

    @Test
    void aFetchResponseComparesAndPrintsItsBodyByContent() {
        var body = new WorkflowHttpClient.FetchResponse(new byte[]{1, 2, 3}, null);
        var same = new WorkflowHttpClient.FetchResponse(new byte[]{1, 2, 3}, null);
        var redirect = new WorkflowHttpClient.FetchResponse(null, "/next");

        assertThat(body).isEqualTo(same).hasSameHashCodeAs(same)
                .isNotEqualTo(new WorkflowHttpClient.FetchResponse(new byte[]{1, 2, 4}, null))
                .isNotEqualTo(redirect)
                .isNotEqualTo("body");
        assertThat(redirect).isEqualTo(new WorkflowHttpClient.FetchResponse(null, "/next"))
                .isNotEqualTo(new WorkflowHttpClient.FetchResponse(null, "/other"));
        assertThat(body).hasToString("FetchResponse[body=3 bytes, redirectLocation=null]");
        assertThat(redirect).hasToString("FetchResponse[body=none, redirectLocation=/next]");
    }
}
