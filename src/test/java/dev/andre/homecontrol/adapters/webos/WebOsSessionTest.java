package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.FakeWebSocketServer;
import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.TvInput;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class WebOsSessionTest {

    @TempDir
    Path dir;

    private FakeSsapServer tv;
    private FakeWakeOnLanReceiver receiver;
    private DeviceRegistry registry;
    private WebOsSession session;
    private final List<DeviceState> states = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startTv() throws IOException {
        tv = new FakeSsapServer(false);
        receiver = new FakeWakeOnLanReceiver();
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
    }

    @AfterEach
    void closeEverything() {
        if (session != null) {
            session.close();
        }
        tv.close();
        receiver.close();
    }

    /** Stands in for the device manager: merges what the session learned into the registry. */
    private LearnedSettings learned() {
        return updates -> registry.findById("lg").ifPresent(stored -> {
            Map<String, String> settings = new LinkedHashMap<>(stored.adapterSettings(WebOsAdapter.ADAPTER_ID));
            settings.putAll(updates);
            registry.save(stored.withAdapter(WebOsAdapter.ADAPTER_ID, settings));
        });
    }

    private WebOsSession session(Map<String, String> settings) throws IOException {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1", Map.of("webos", settings), Instant.now());
        registry.save(device);
        WebOsProperties properties = new WebOsProperties(true, tv.port(), FakeWebSocketServer.closedPort(), 2, 2, 2, 1, 2, 0);
        session = new WebOsSession(device, properties, InsecureTls.httpClient(Duration.ofSeconds(2)), registry,
                learned(), new WakeOnLan(receiver.address()), states::add, () -> { });
        return session;
    }

    private WebOsSession started() throws IOException {
        WebOsSession started = session(Map.of("clientKey", FakeSsapServer.CLIENT_KEY));
        started.start();
        return started;
    }

    private void connected() {
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    private void awaitStatus(DeviceStatus status) {
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().status() == status);
    }

    private String storedSetting(String key) {
        return registry.findById("lg").orElseThrow().adapterSettings(WebOsAdapter.ADAPTER_ID).get(key);
    }

    @Test
    void connectsWithTheStoredKeyAndMirrorsAppVolumeAndPower() throws IOException {
        started();
        connected();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            DeviceState state = session.state();
            assertThat(state.currentApp()).isEqualTo("com.webos.app.home");
            assertThat(state.volumeLevel()).isEqualTo(12);
            assertThat(state.volumeMax()).isEqualTo(100);
            assertThat(state.muted()).isFalse();
            assertThat(state.powerOn()).isTrue();
        });
        List<DeviceStatus> statuses = states.stream().map(DeviceState::status).toList();
        assertThat(statuses).contains(DeviceStatus.CONNECTING);
        assertThat(statuses.indexOf(DeviceStatus.CONNECTING)).isLessThan(statuses.indexOf(DeviceStatus.CONNECTED));
    }

    @Test
    void aKeyPressGoesThroughThePointerInputSocket() throws Exception {
        started();
        connected();

        session.execute(new Action.PressKey(RemoteKey.DPAD_UP));

        assertThat(tv.nextButton()).isEqualTo("type:button\nname:UP\n\n");
    }

    @Test
    void playPauseAlternatesPauseAndPlay() throws Exception {
        started();
        connected();

        session.execute(new Action.PressKey(RemoteKey.PLAY_PAUSE));
        session.execute(new Action.PressKey(RemoteKey.PLAY_PAUSE));

        assertThat(tv.nextButton()).isEqualTo("type:button\nname:PAUSE\n\n");
        assertThat(tv.nextButton()).isEqualTo("type:button\nname:PLAY\n\n");
    }

    @Test
    void aKeyWithoutAButtonIsUnsupported() throws IOException {
        started();
        connected();

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.MEDIA_NEXT)))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("LG TV");
    }

    @Test
    void volumeKeysAndAbsoluteVolumeUseSsapAudio() throws Exception {
        started();
        connected();
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().volumeLevel() == 12);

        session.execute(new Action.PressKey(RemoteKey.VOLUME_UP));
        assertThat(tv.nextRequest("ssap://audio/volumeUp")).isNotNull();

        session.execute(new Action.SetVolume(20));
        JsonNode setVolume = tv.nextRequest("ssap://audio/setVolume");
        assertThat(setVolume.path("payload").path("volume").asInt()).isEqualTo(20);
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().volumeLevel() == 20);

        session.execute(new Action.PressKey(RemoteKey.VOLUME_MUTE));
        assertThat(tv.nextRequest("ssap://audio/setMute").path("payload").path("mute").asBoolean()).isTrue();

        session.execute(new Action.Mute(false));
        assertThat(tv.nextRequest("ssap://audio/setMute").path("payload").path("mute").asBoolean(true)).isFalse();
    }

    @Test
    void stopUsesMediaControls() throws Exception {
        started();
        connected();

        session.execute(new Action.Stop());

        assertThat(tv.nextRequest("ssap://media.controls/stop")).isNotNull();
    }

    @Test
    void powerWhileOnTurnsTheTvOff() throws Exception {
        started();
        connected();
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().powerOn());

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(tv.nextRequest("ssap://system/turnOff")).isNotNull();
        assertThat(session.state().powerOn()).isFalse();
    }

    @Test
    void learnsTheMacAddressAfterConnecting() throws IOException {
        started();

        await().atMost(Duration.ofSeconds(5)).until(() -> "A8:23:FE:01:02:03".equals(storedSetting("macAddress")));
    }

    @Test
    void aHandEnteredMacIsNotOverwritten() throws Exception {
        session(Map.of("clientKey", FakeSsapServer.CLIENT_KEY,
                "macAddress", "11:22:33:44:55:66", "macAddressManual", "true")).start();
        connected();

        assertThat(tv.nextRequest(SsapUris.CONNECTION_INFO)).isNotNull();
        Thread.sleep(500);

        assertThat(storedSetting("macAddress")).isEqualTo("11:22:33:44:55:66");
    }

    @Test
    void powerWhileOffWakesTheTvAndReconnects() throws Exception {
        started();
        connected();
        await().atMost(Duration.ofSeconds(5)).until(() -> storedSetting("macAddress") != null);
        tv.refuseConnections(true);
        awaitStatus(DeviceStatus.DISCONNECTED);

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket("A8:23:FE:01:02:03"));
        tv.refuseConnections(false);
        connected();
    }

    @Test
    void powerWhileOffWithoutAMacExplainsTheFix() throws IOException {
        tv.refuseConnections(true);
        started();
        awaitStatus(DeviceStatus.DISCONNECTED);

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.POWER)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("MAC address")
                .hasMessageContaining("setup page");
        assertThat(receiver.received()).isZero();
    }

    @Test
    void keysWhileDisconnectedFailNowAndAreNotReplayed() throws Exception {
        tv.refuseConnections(true);
        started();

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("not connected");
        tv.refuseConnections(false);
        connected();

        assertThat(tv.nextButton()).isNull();
    }

    @Test
    void opensAYouTubeLinkWithContentTarget() throws Exception {
        started();
        connected();

        session.execute(new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ")));

        JsonNode launch = tv.nextRequest("ssap://system.launcher/launch");
        assertThat(launch.path("payload").path("params").path("contentTarget").asString())
                .isEqualTo("https://www.youtube.com/tv?v=aqz-KE-bpKQ");
        await().atMost(Duration.ofSeconds(5)).until(() -> "youtube.leanback.v4".equals(session.state().currentApp()));
    }

    @Test
    void aCastLoadIsNotForTvs() throws IOException {
        started();
        connected();

        assertThatThrownBy(() -> session.execute(new Action.CastLoad("CC1AD845", Map.of())))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void anOrdinaryWebLinkOpensInTheTvBrowser() throws Exception {
        started();
        connected();

        session.execute(new Action.OpenAppLink(URI.create("https://example.org/page")));

        assertThat(tv.nextRequest("ssap://system.launcher/open").path("payload").path("target").asString())
                .isEqualTo("https://example.org/page");
    }

    @Test
    void listsAndSwitchesInputs() throws Exception {
        started();
        connected();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(session.inputs())
                .containsExactly(new TvInput("HDMI_1", "HDMI 1"), new TvInput("HDMI_2", "PlayStation")));
        session.execute(new Action.SelectInput("HDMI_2"));
        assertThat(tv.nextRequest("ssap://tv/switchInput").path("payload").path("inputId").asString())
                .isEqualTo("HDMI_2");

        tv.refuseConnections(true);
        awaitStatus(DeviceStatus.DISCONNECTED);
        assertThat(session.inputs()).isEmpty();
    }

    @Test
    void aForgottenPairingIsUnpairedAndStopsReconnecting() throws Exception {
        session(Map.of("clientKey", "stale")).start();

        awaitStatus(DeviceStatus.UNPAIRED);
        Thread.sleep(3000);

        assertThat(tv.registrations()).isEqualTo(1);
    }

    @Test
    void withoutAClientKeyItNeverConnects() throws Exception {
        session(Map.of()).start();

        awaitStatus(DeviceStatus.UNPAIRED);
        Thread.sleep(1000);

        assertThat(tv.connections()).isZero();
    }

    @Test
    void reconnectsAfterTheTvDropsTheConnection() throws Exception {
        started();
        connected();
        // CONNECTED precedes the initial subscriptions and requests. MAC learning is last;
        // wait for setup to finish so dropping the socket tests an established session.
        await().atMost(Duration.ofSeconds(5)).until(() -> storedSetting("macAddress") != null);
        int initialConnections = tv.connections();

        tv.dropConnections();

        await().atMost(Duration.ofSeconds(5)).until(() -> states.stream()
                .anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));
        connected();
        int connections = tv.connections();
        assertThat(connections).isGreaterThan(initialConnections);
        session.reconnectNow();
        Thread.sleep(500);
        assertThat(tv.connections()).isEqualTo(connections);
    }

    @Test
    void aTvThatStopsAnsweringFailsTheCommandButStaysConnected() throws Exception {
        started();
        connected();
        tv.ignoreRequests(SsapUris.LAUNCH);

        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("did not answer in time");
        assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void aConnectionThatAnswersNothingIsDetectedByTheLivenessCheckAndReopened() throws Exception {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1",
                Map.of("webos", Map.of("clientKey", FakeSsapServer.CLIENT_KEY)), Instant.now());
        registry.save(device);
        WebOsProperties properties = new WebOsProperties(true, tv.port(), FakeWebSocketServer.closedPort(), 2, 1, 2, 1, 2, 0, 1);
        session = new WebOsSession(device, properties, InsecureTls.httpClient(Duration.ofSeconds(2)), registry,
                learned(), new WakeOnLan(receiver.address()), states::add, () -> { });
        session.start();
        connected();
        int connections = tv.connections();

        tv.ignoreRequests(SsapUris.SYSTEM_INFO);

        await().atMost(Duration.ofSeconds(6)).until(() -> states.stream()
                .anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));
        tv.answerRequests(SsapUris.SYSTEM_INFO);
        connected();
        assertThat(tv.connections()).isGreaterThan(connections);
    }

    @Test
    void aLivenessCheckTheTvAnswersKeepsTheConnection() throws Exception {
        Device device = new Device("lg", "LG TV", DeviceKind.WEBOS, "127.0.0.1",
                Map.of("webos", Map.of("clientKey", FakeSsapServer.CLIENT_KEY)), Instant.now());
        registry.save(device);
        WebOsProperties properties = new WebOsProperties(true, tv.port(), FakeWebSocketServer.closedPort(), 2, 1, 2, 1, 2, 0, 1);
        session = new WebOsSession(device, properties, InsecureTls.httpClient(Duration.ofSeconds(2)), registry,
                learned(), new WakeOnLan(receiver.address()), states::add, () -> { });
        session.start();
        connected();
        int connections = tv.connections();

        assertThat(tv.nextRequest(SsapUris.SYSTEM_INFO)).isNotNull();
        Thread.sleep(2500);

        assertThat(tv.connections()).isEqualTo(connections);
        assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void closeStopsEverything() throws Exception {
        started();
        connected();

        session.close();
        states.clear();
        tv.dropConnections();
        Thread.sleep(2000);

        assertThat(states).isEmpty();
    }
}
