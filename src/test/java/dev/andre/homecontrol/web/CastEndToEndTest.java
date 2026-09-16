package dev.andre.homecontrol.web;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvSettings;
import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Cast adapter through the real application: fake receiver ↔ CastSession ↔ DeviceManager ↔
 * planner ↔ HTTP and SSE. The fake is the in-process CASTV2 receiver from the adapter tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CastEndToEndTest {

    @DynamicPropertySource
    static void fastCastAndAnIsolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        String dataDir = Files.createTempDirectory("cast-e2e").toString();
        registry.add("shield.data-dir", () -> dataDir);
        registry.add("home-control.cast.heartbeat-interval-seconds", () -> "1");
        registry.add("home-control.cast.stale-timeout-seconds", () -> "3");
        registry.add("home-control.cast.reconnect-max-delay-seconds", () -> "2");
        registry.add("home-control.cast.command-timeout-seconds", () -> "3");
        registry.add("home-control.cast.load-timeout-seconds", () -> "5");
        registry.add("home-control.cast.media-status-interval-seconds", () -> "1");
    }

    @LocalServerPort
    int port;

    @Autowired
    DeviceManager devices;

    @Autowired
    CertificateStore certificates;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Device castDevice(String id, int castPort) {
        return new Device(id, "Kitchen", DeviceKind.CAST, "127.0.0.1",
                Map.of("cast", Map.of("port", String.valueOf(castPort))), Instant.now());
    }

    private void awaitReceiverStatus(String id) {
        await().until(() -> devices.state(id).connected() && devices.state(id).volumeMax() == 100);
    }

    @Test
    void aCastOnlyDevicePlaysAPastedMediaLinkThroughTheDefaultMediaReceiver() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            devices.adopt(castDevice("cast-e2e-play", receiver.port()));
            try {
                awaitReceiverStatus("cast-e2e-play");

                HttpResponse<String> response = post("/devices/cast-e2e-play/play",
                        "uri=" + URLEncoder.encode("http://nas.local/films/bunny.mp4", StandardCharsets.UTF_8));

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("Cast with the Default Media Receiver");
                assertThat(receiver.last(RECEIVER, "LAUNCH")).isPresent();
                CastIncoming load = receiver.last(MEDIA, "LOAD").orElseThrow();
                assertThat(load.payload().path("media").path("contentId").asString("")).isEqualTo("http://nas.local/films/bunny.mp4");
                assertThat(load.payload().path("media").path("metadata").path("title").asString("")).isEqualTo("bunny.mp4");
                await().until(() -> devices.state("cast-e2e-play").nowPlaying() != null);
                assertThat(devices.state("cast-e2e-play").nowPlaying().title()).isEqualTo("bunny.mp4");
                assertThat(devices.state("cast-e2e-play").nowPlaying().state()).isEqualTo(PlaybackState.PLAYING);
            } finally {
                devices.forget("cast-e2e-play");
            }
        }
    }

    @Test
    void volumeMuteAndStopReachTheReceiver() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
            devices.adopt(castDevice("cast-e2e-volume", receiver.port()));
            try {
                await().until(() -> "Default Media Receiver".equals(devices.state("cast-e2e-volume").currentApp()));

                assertThat(post("/devices/cast-e2e-volume/volume", "level=35").statusCode()).isEqualTo(204);
                assertThat(receiver.volumeLevel()).isEqualTo(0.35);
                assertThat(post("/devices/cast-e2e-volume/mute", "muted=true").statusCode()).isEqualTo(204);
                assertThat(receiver.muted()).isTrue();
                assertThat(post("/devices/cast-e2e-volume/stop", "").statusCode()).isEqualTo(204);

                assertThat(receiver.runningAppId()).isEqualTo(FakeCastReceiver.BACKDROP_APP_ID);
                await().until(() -> devices.state("cast-e2e-volume").currentApp() == null);
                assertThat(post("/devices/cast-e2e-volume/key/HOME", "").statusCode()).isEqualTo(422);
            } finally {
                devices.forget("cast-e2e-volume");
            }
        }
    }

    @Test
    void aMergedShieldSendsKeysOverRemoteV2AndVolumeOverCast() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer(); FakeCastReceiver receiver = new FakeCastReceiver()) {
            certificates.loadOrCreate("shield-e2e");
            devices.adopt(AndroidTvSettings.device("shield-e2e", "Shield", "127.0.0.1", remote.port(), null, Instant.now())
                    .withAdapter("cast", Map.of("port", String.valueOf(receiver.port()))));
            try {
                await().until(() -> devices.state("shield-e2e").connected()
                        && receiver.virtualConnections().contains("receiver-0"));

                assertThat(post("/devices/shield-e2e/key/HOME", "").statusCode()).isEqualTo(204);
                assertThat(remote.nextKeyPress()).isEqualTo(RemoteKey.HOME.code());
                assertThat(post("/devices/shield-e2e/volume", "level=40").statusCode()).isEqualTo(204);
                assertThat(receiver.volumeLevel()).isEqualTo(0.4);

                receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
                receiver.startMedia("Song", "PLAYING", 3.0);
                receiver.pushReceiverStatus();

                await().until(() -> devices.state("shield-e2e").nowPlaying() != null);
                assertThat(devices.state("shield-e2e").nowPlaying().title()).isEqualTo("Song");
                assertThat(devices.state("shield-e2e").status()).isEqualTo(DeviceStatus.CONNECTED);
            } finally {
                devices.forget("shield-e2e");
            }
        }
    }

    @Test
    void theEventStreamCarriesNowPlayingWithTheDeviceId() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            devices.adopt(castDevice("cast-e2e-sse", receiver.port()));
            try {
                awaitReceiverStatus("cast-e2e-sse");
                List<String> lines = new CopyOnWriteArrayList<>();
                HttpResponse<Stream<String>> response = http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                                .header("Accept", "text/event-stream")
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofLines());
                assertThat(response.statusCode()).isEqualTo(200);
                Thread.ofVirtual().name("cast-sse-reader").start(() -> response.body().forEach(lines::add));
                await().until(() -> lines.stream().anyMatch(line -> line.contains("\"deviceId\":\"cast-e2e-sse\"")));

                receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
                receiver.startMedia("Song", "PLAYING", 3.0);
                receiver.pushReceiverStatus();

                await().until(() -> lines.stream().anyMatch(line -> line.contains("\"deviceId\":\"cast-e2e-sse\"")
                        && line.contains("\"title\":\"Song\"")));
            } finally {
                // shutdownNow, not close: an SSE stream never ends by itself, and close() would
                // block waiting for this one to (mirrors DeviceStateStreamEndToEndTest).
                http.shutdownNow();
                devices.forget("cast-e2e-sse");
            }
        }
    }

    @Test
    void aReceiverThatGoesSilentShowsDisconnectedAndRecovers() throws Exception {
        try (FakeCastReceiver receiver = new FakeCastReceiver()) {
            devices.adopt(castDevice("cast-e2e-silent", receiver.port()));
            try {
                awaitReceiverStatus("cast-e2e-silent");

                receiver.goSilent();
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> devices.state("cast-e2e-silent").status() == DeviceStatus.DISCONNECTED);

                receiver.setVolume(0.7, false);
                receiver.resume();
                await().atMost(Duration.ofSeconds(15)).until(() -> devices.state("cast-e2e-silent").connected()
                        && devices.state("cast-e2e-silent").volumeLevel() == 70);
            } finally {
                devices.forget("cast-e2e-silent");
            }
        }
    }
}
