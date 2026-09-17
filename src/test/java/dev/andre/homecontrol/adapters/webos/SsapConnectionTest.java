package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsapConnectionTest {

    private final HttpClient http = InsecureTls.httpClient(Duration.ofSeconds(2));
    private FakeSsapServer server;
    private SsapConnection connection;

    static WebOsProperties properties(int port, int securePort, int pairingTimeoutSeconds) {
        return new WebOsProperties(true, port, securePort, 2, 2, pairingTimeoutSeconds, 1, 2, 0);
    }

    @BeforeEach
    void startTv() throws IOException {
        server = new FakeSsapServer(false);
    }

    @AfterEach
    void stopTv() {
        if (connection != null) {
            connection.close();
        }
        server.close();
    }

    private SsapConnection open() throws IOException {
        connection = SsapConnection.open(http, "127.0.0.1",
                properties(server.port(), FakeWebSocketServer.closedPort(), 2), reason -> { });
        return connection;
    }

    @Test
    void theRegisterMessageCarriesTheManifestAndOnlyAKnownKey() {
        JsonNode message = SsapMessages.JSON.readTree(SsapMessages.register(null));
        JsonNode payload = message.path("payload");

        assertThat(message.path("type").asString()).isEqualTo("register");
        assertThat(message.path("id").asString()).isEqualTo("register_0");
        assertThat(payload.path("pairingType").asString()).isEqualTo("PROMPT");
        assertThat(payload.path("forcePairing").asBoolean(true)).isFalse();
        assertThat(payload.has("client-key")).isFalse();
        assertThat(payload.path("manifest").path("manifestVersion").asInt()).isEqualTo(1);
        List<String> permissions = payload.path("manifest").path("permissions").valueStream()
                .map(JsonNode::asString).toList();
        assertThat(permissions).hasSize(37).contains("CONTROL_POWER", "READ_NETWORK_STATE");
        assertThat(SsapMessages.JSON.readTree(SsapMessages.register("k")).path("payload").path("client-key").asString())
                .isEqualTo("k");
    }

    @Test
    void aStoredKeyRegistersWithoutAPrompt() throws IOException {
        assertThat(open().register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1))).isEqualTo(FakeSsapServer.CLIENT_KEY);
    }

    @Test
    void pairingWaitsForTheUserToAccept() throws IOException {
        server.setPrompt(FakeSsapServer.Prompt.ACCEPT);

        assertThat(open().register(null, Duration.ofSeconds(2))).isEqualTo(FakeSsapServer.CLIENT_KEY);
    }

    @Test
    void aDeclinedPromptIsDeclined() throws IOException {
        server.setPrompt(FakeSsapServer.Prompt.DECLINE);
        SsapConnection opened = open();

        assertThatThrownBy(() -> opened.register(null, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(SsapPairingException.class,
                        e -> assertThat(e.reason()).isEqualTo(SsapPairingException.Reason.DECLINED))
                .hasMessageContaining("403");
    }

    @Test
    void anUnansweredPromptTimesOut() throws IOException {
        server.setPrompt(FakeSsapServer.Prompt.IGNORE);
        SsapConnection opened = open();

        assertThatThrownBy(() -> opened.register(null, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(SsapPairingException.class,
                        e -> assertThat(e.reason()).isEqualTo(SsapPairingException.Reason.TIMED_OUT));
    }

    @Test
    void aStoredKeyTheTvForgotIsRejected() throws IOException {
        SsapConnection opened = open();

        assertThatThrownBy(() -> opened.register("stale", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(SsapPairingException.class,
                        e -> assertThat(e.reason()).isEqualTo(SsapPairingException.Reason.KEY_REJECTED));
    }

    @Test
    void concurrentRequestsGetTheirOwnAnswers() throws Exception {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));
        List<JsonNode> infos = new CopyOnWriteArrayList<>();
        List<JsonNode> volumes = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        Thread info = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    infos.add(opened.request(SsapUris.SYSTEM_INFO, SsapMessages.empty()));
                } catch (IOException e) {
                    failures.add(e);
                }
            }
        });
        Thread volume = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    volumes.add(opened.request(SsapUris.SET_VOLUME, SsapMessages.empty().put("volume", 20)));
                } catch (IOException e) {
                    failures.add(e);
                }
            }
        });
        info.join();
        volume.join();

        assertThat(failures).isEmpty();
        assertThat(infos).hasSize(20).allSatisfy(payload ->
                assertThat(payload.path("modelName").asString("")).isEqualTo("OLED55C9PLA"));
        assertThat(volumes).hasSize(20).allSatisfy(payload -> assertThat(payload.has("modelName")).isFalse());
    }

    @Test
    void garbageFromTheTvIsIgnored() throws IOException {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));

        server.sendRaw("not json");
        server.sendRaw("{\"type\":\"response\",\"id\":\"nobody\"}");

        assertThat(opened.request(SsapUris.SYSTEM_INFO, SsapMessages.empty()).path("modelName").asString(""))
                .isEqualTo("OLED55C9PLA");
    }

    @Test
    void anUnansweredRequestTimesOutAsSuch() throws IOException {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));
        server.ignoreRequests(SsapUris.SET_VOLUME);
        long started = System.nanoTime();

        assertThatThrownBy(() -> opened.request(SsapUris.SET_VOLUME, SsapMessages.empty().put("volume", 5)))
                .isInstanceOf(SsapTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1800), Duration.ofSeconds(4));
        assertThat(opened.request(SsapUris.SYSTEM_INFO, SsapMessages.empty()).path("modelName").asString(""))
                .isEqualTo("OLED55C9PLA");
    }

    @Test
    void anErrorAnswerIsAnSsapException() throws IOException {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));

        assertThatThrownBy(() -> opened.request("ssap://nope/nothing", SsapMessages.empty()))
                .isInstanceOf(SsapException.class)
                .hasMessageContaining("404 no such service or method");
    }

    @Test
    void aRefusedLaunchCarriesTheTvsErrorText() throws IOException {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));

        assertThatThrownBy(() -> opened.request(SsapUris.LAUNCH, SsapMessages.empty().put("id", "com.example.missing")))
                .isInstanceOf(SsapException.class)
                .hasMessageContaining("500 Application error")
                .hasMessageContaining("was not found");
    }

    @Test
    void aSubscriptionDeliversTheFirstAnswerAndLaterPushes() throws Exception {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));
        BlockingQueue<String> apps = new LinkedBlockingQueue<>();

        opened.subscribe(SsapUris.FOREGROUND_APP, payload -> apps.add(payload.path("appId").asString("")));

        assertThat(apps.poll(5, TimeUnit.SECONDS)).isEqualTo("com.webos.app.home");
        server.changeForegroundApp("netflix");
        assertThat(apps.poll(5, TimeUnit.SECONDS)).isEqualTo("netflix");
    }

    @Test
    void buttonsUseOnePointerInputSocket() throws Exception {
        SsapConnection opened = open();
        opened.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));

        opened.button("UP");
        opened.button("DOWN");

        assertThat(server.nextButton()).isEqualTo("type:button\nname:UP\n\n");
        assertThat(server.nextButton()).isEqualTo("type:button\nname:DOWN\n\n");
        assertThat(server.connections()).isEqualTo(2);
    }

    @Test
    void fallsBackToTlsWhenThePlainPortIsClosed() throws IOException {
        try (FakeSsapServer tls = new FakeSsapServer(true)) {
            connection = SsapConnection.open(http, "127.0.0.1",
                    properties(FakeWebSocketServer.closedPort(), tls.port(), 2), reason -> { });

            assertThat(connection.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1)))
                    .isEqualTo(FakeSsapServer.CLIENT_KEY);
        }
    }

    @Test
    void theTvDroppingTheConnectionIsReportedOnce() throws Exception {
        BlockingQueue<String> reasons = new LinkedBlockingQueue<>();
        connection = SsapConnection.open(http, "127.0.0.1",
                properties(server.port(), FakeWebSocketServer.closedPort(), 2), reasons::add);
        connection.register(FakeSsapServer.CLIENT_KEY, Duration.ofSeconds(1));

        server.dropConnections();

        assertThat(reasons.poll(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(reasons.poll(500, TimeUnit.MILLISECONDS)).isNull();
        long started = System.nanoTime();
        assertThatThrownBy(() -> connection.request(SsapUris.SYSTEM_INFO, SsapMessages.empty()))
                .isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }
}
