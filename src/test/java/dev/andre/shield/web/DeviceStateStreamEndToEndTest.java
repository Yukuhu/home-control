package dev.andre.shield.web;

import dev.andre.shield.device.Device;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.DeviceStatus;
import dev.andre.shield.protocol.FakeRemoteServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spec §9's named end-to-end case: an inbound volume message from the device propagating
 * all the way out as an SSE event. Wires the fake device server, a real {@code DeviceSession},
 * the Spring event publisher, the broadcaster's own fan-out thread and a real HTTP client on
 * {@code /events} — the one path where the protocol layer's threading meets the web layer's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceStateStreamEndToEndTest {

    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        // A directory of its own, so no keystore or devices.json left by another run
        // brings up a session for some other device alongside this test's.
        String dataDir = Files.createTempDirectory("shield-sse-e2e").toString();
        registry.add("shield.data-dir", () -> dataDir);
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceSessionManager sessions;

    @Test
    void streamsAnInboundVolumeMessageOutAsAnSseEvent() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        try (FakeRemoteServer fakeDevice = new FakeRemoteServer()) {
            sessions.adopt(new Device("shield-sse", "Test Shield", "127.0.0.1", fakeDevice.port(),
                    null, Instant.now()));
            await().until(() -> sessions.state().status() == DeviceStatus.CONNECTED);

            List<String> lines = new CopyOnWriteArrayList<>();
            HttpResponse<Stream<String>> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                            .header("Accept", "text/event-stream")
                            .timeout(Duration.ofSeconds(10))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(response.statusCode()).isEqualTo(200);
            Thread.ofVirtual().name("sse-e2e-reader").start(() -> response.body().forEach(lines::add));

            // The current state arrives immediately, which also proves the subscription is live
            // before the device pushes anything.
            await().until(() -> lines.stream().anyMatch(line -> line.startsWith("data:")));

            fakeDevice.pushVolume(12, 100, true);

            await().until(() -> lines.stream().anyMatch(line ->
                    line.contains("\"volumeLevel\":12") && line.contains("\"muted\":true")));
            assertThat(lines).contains("event:state");
        } finally {
            // shutdownNow, not close: an SSE stream never ends by itself, and close() would
            // block waiting for this one to.
            http.shutdownNow();
            sessions.forget("shield-sse");
        }
    }
}
