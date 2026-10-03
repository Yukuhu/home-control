package dev.andre.homecontrol.web;

import dev.andre.homecontrol.HomeControlApplication;
import dev.andre.homecontrol.content.RailsChangedEvent;
import dev.andre.homecontrol.testsupport.EventStreamReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Stopping the application while a browser holds {@code /events} open. An event stream never ends by itself, and
 * graceful shutdown waits for every request still in flight, so unless the streams are ended first every stop with a
 * dashboard open, or with a closed tab the server has not yet noticed, sat out the whole 30 s shutdown-phase timeout.
 */
class EventStreamShutdownEndToEndTest {

    @Test
    void closingTheApplicationEndsAnOpenEventStreamInsteadOfWaitingForIt() throws Exception {
        String dataDir = Files.createTempDirectory("shield-sse-shutdown").toString();
        ConfigurableApplicationContext app = new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--home-control.data-dir=" + dataDir);
        HttpClient http = HttpClient.newHttpClient();
        EventStreamReader events = null;
        try {
            int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            CompletableFuture<HttpResponse<Stream<String>>> pending = http.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                            .header("Accept", "text/event-stream")
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());
            // A stream's headers go out with its first event, and this application has no device or rail to report:
            // announce an unchanged rail list until the response arrives, which also proves the stream is open.
            await().until(() -> {
                app.publishEvent(new RailsChangedEvent(List.of()));
                return pending.isDone();
            });
            HttpResponse<Stream<String>> response = pending.get();
            assertThat(response.statusCode()).isEqualTo(200);
            events = new EventStreamReader(response);

            long started = System.nanoTime();
            app.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - started);

            // Well under the 30 s a waiting graceful shutdown takes, and under Docker's 10 s stop grace period.
            assertThat(closing).isLessThan(Duration.ofSeconds(10));
        } finally {
            // shutdownNow, not close: close() would wait for the stream if the application failed to end it.
            http.shutdownNow();
            app.close();
            if (events != null) {
                events.awaitEnd(Duration.ofSeconds(5));
            }
        }
    }

    /** Nothing to report, so the heartbeat is the stream's first write: it opens the response and keeps it open. */
    @Test
    void anIdleStreamGetsAKeepAliveCommentOnItsOwn() throws Exception {
        String dataDir = Files.createTempDirectory("shield-sse-heartbeat").toString();
        ConfigurableApplicationContext app = new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--home-control.data-dir=" + dataDir, "--home-control.events.heartbeat-interval=1s");
        HttpClient http = HttpClient.newHttpClient();
        EventStreamReader events = null;
        try {
            int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            HttpResponse<Stream<String>> response = http.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                            .header("Accept", "text/event-stream")
                            .build(),
                    HttpResponse.BodyHandlers.ofLines()).get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            EventStreamReader reader = new EventStreamReader(response);
            events = reader;

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(reader.lines())
                    .anySatisfy(line -> assertThat(line).startsWith(":").contains("keep-alive")));
            // An empty install's snapshot is just its empty list of rails; everything after it is the keep-alive.
            assertThat(reader.lines()).filteredOn(line -> line.startsWith("data:")).containsExactly("data:{\"rails\":[]}");
        } finally {
            http.shutdownNow();
            app.close();
            if (events != null) {
                events.awaitEnd(Duration.ofSeconds(5));
            }
        }
    }
}
