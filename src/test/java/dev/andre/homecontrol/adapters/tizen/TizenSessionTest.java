package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.InsecureTls;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.adapters.tizen.protocol.FakeTizenServer;
import dev.andre.homecontrol.adapters.tizen.protocol.TizenDeviceInfo;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.testsupport.InMemoryDeviceSecrets;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
import dev.andre.homecontrol.testsupport.RecordingStateListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class TizenSessionTest {

    private static final String KEY_REF = "0123456789abcdef";

    private static final Map<String, String> PAIRED = Map.of("paired", "true", "token", FakeTizenServer.TOKEN);

    /** Poll 200 ms (so the first handshake backoff is 400 ms), no wake grace, Allow/Deny within 500 ms. */
    private static final TizenTimings TIMINGS =
            new TizenTimings(Duration.ofMillis(200), Duration.ZERO, Duration.ofMillis(500), TizenTimings.HANDSHAKE_BACKOFF_CAP);

    @TempDir
    Path dir;

    private FakeTizenServer tv;
    private FakeWakeOnLanReceiver receiver;
    private DeviceRegistry registry;
    private TizenSession session;
    private final RecordingStateListener states = new RecordingStateListener();
    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();

    @BeforeEach
    void setUp() throws IOException {
        tv = new FakeTizenServer();
        receiver = new FakeWakeOnLanReceiver();
        registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        tv.close();
        receiver.close();
    }

    /** Stands in for the device manager: merges what the session learned into the registry. */
    private LearnedSettings learned() {
        return updates -> registry.findById("samsung").ifPresent(stored -> {
            Map<String, String> settings = new LinkedHashMap<>(stored.adapterSettings(TizenAdapter.ADAPTER_ID));
            settings.putAll(updates);
            registry.save(stored.withAdapter(TizenAdapter.ADAPTER_ID, settings));
        });
    }

    private TizenSession start(Map<String, String> settings) {
        return start(settings, TIMINGS);
    }

    private TizenSession start(Map<String, String> settings, TizenTimings timings) {
        return start(settings, timings, states);
    }

    private TizenSession start(Map<String, String> settings, TizenTimings timings, Consumer<DeviceState> listener) {
        Device device = new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "127.0.0.1",
                Map.of("tizen", stored(settings)), Instant.now());
        registry.save(device);
        session = new TizenSession(device, properties(tv), timings, InsecureTls.httpClient(Duration.ofSeconds(2)),
                registry, learned(), secrets, new WakeOnLan(receiver.address()), listener, () -> { });
        session.start();
        return session;
    }

    private void connected() {
        await().atMost(Duration.ofSeconds(5)).until(() ->
                session.state().status() == DeviceStatus.CONNECTED && session.state().powerOn());
    }

    private void awaitStatus(DeviceStatus status) {
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().status() == status);
    }

    /**
     * Starts connected and waits until the session has the TV's installed-app list. Until the list arrives the
     * session falls back to the well-known app ids; Prime Video is not installed on the fake TV, so its
     * well-known id answering "visible" shows it as the current app exactly until the list has been read.
     */
    private void startWithInstalledApps() {
        tv.setVisible(FakeTizenServer.PRIME_VIDEO, true);
        start(PAIRED);
        connected();
        await().atMost(Duration.ofSeconds(5)).until(() -> session.state().currentApp() == null);
    }

    /** Settings as the device manager keeps them since 2B: the token as a device secret, named by keyRef. */
    private Map<String, String> stored(Map<String, String> settings) {
        Map<String, String> stored = new LinkedHashMap<>(settings);
        String token = stored.remove("token");
        if (token != null) {
            secrets.putDeviceSecret(TizenSettings.secretName(KEY_REF), token);
            stored.put("keyRef", KEY_REF);
        }
        return stored;
    }

    /** A stored setting; {@code token} is looked up as the device secret the entry's keyRef names. */
    private String stored(String key) {
        Map<String, String> settings = registry.findById("samsung").orElseThrow().adapterSettings(TizenAdapter.ADAPTER_ID);
        if (!"token".equals(key)) {
            return settings.get(key);
        }
        String keyRef = settings.get("keyRef");
        return keyRef == null ? null : secrets.deviceSecret(TizenSettings.secretName(keyRef)).orElse(null);
    }

    @Test
    void connectsWithTheStoredTokenAndReportsPowerOn() {
        start(PAIRED);
        connected();

        assertThat(session.state().currentApp()).isNull();
        assertThat(session.state().volumeMax()).isZero();
    }

    @Test
    void aKeyTheTvLacksIsNamedForPeople() {
        start(PAIRED);
        connected();
        var next = new Action.PressKey(RemoteKey.MEDIA_NEXT);
        var heldNext = new Action.PressKey(RemoteKey.MEDIA_NEXT, KeyPress.START_LONG);

        assertThatThrownBy(() -> session.execute(next))
                .isInstanceOf(UnsupportedActionException.class).hasMessage("Samsung TV has no next track key");
        assertThatThrownBy(() -> session.execute(heldNext))
                .isInstanceOf(UnsupportedActionException.class).hasMessage("Samsung TV cannot hold next track");
    }

    @Test
    void keysAreRemoteControlClicks() throws Exception {
        start(PAIRED);
        connected();

        session.execute(new Action.PressKey(RemoteKey.HOME));
        session.execute(new Action.PressKey(RemoteKey.DPAD_CENTER));
        session.execute(new Action.PressKey(RemoteKey.BACK));
        session.execute(new Action.PressKey(RemoteKey.VOLUME_UP));

        assertThat(tv.nextKey()).isEqualTo("KEY_HOME");
        assertThat(tv.nextKey()).isEqualTo("KEY_ENTER");
        assertThat(tv.nextKey()).isEqualTo("KEY_RETURN");
        assertThat(tv.nextKey()).isEqualTo("KEY_VOLUP");
    }

    @Test
    void aLongPressHoldsTheKeyUntilItEnds() throws Exception {
        start(PAIRED);
        connected();

        session.execute(new Action.PressKey(RemoteKey.DPAD_UP, KeyPress.START_LONG));
        session.execute(new Action.PressKey(RemoteKey.DPAD_UP, KeyPress.END_LONG));

        assertThat(tv.nextCommand()).isEqualTo("Press KEY_UP");
        assertThat(tv.nextCommand()).isEqualTo("Release KEY_UP");
    }

    @Test
    void errorsAndNoiseFromTheTvAreIgnored() throws Exception {
        start(PAIRED);
        connected();

        tv.sendRaw("{\"event\":\"ms.error\",\"data\":{\"message\":\"unrecognized method value\"}}");
        tv.sendRaw("garbage");
        session.execute(new Action.PressKey(RemoteKey.HOME));

        assertThat(tv.nextKey()).isEqualTo("KEY_HOME");
        assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void playPauseAlternatesPauseAndPlay() throws Exception {
        start(PAIRED);
        connected();

        session.execute(new Action.PressKey(RemoteKey.PLAY_PAUSE));
        session.execute(new Action.PressKey(RemoteKey.PLAY_PAUSE));

        assertThat(tv.nextKey()).isEqualTo("KEY_PAUSE");
        assertThat(tv.nextKey()).isEqualTo("KEY_PLAY");
    }

    @Test
    void aKeyWithoutACodeIsUnsupported() {
        start(PAIRED);
        connected();

        var nextTrack = new Action.PressKey(RemoteKey.MEDIA_NEXT);
        assertThatThrownBy(() -> session.execute(nextTrack))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void absoluteVolumeMuteInputsAndCastAreUnsupportedWithAReason() {
        start(PAIRED);
        connected();

        var absoluteVolume = new Action.SetVolume(20);
        assertThatThrownBy(() -> session.execute(absoluteVolume))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("volume up, down and mute");
        var mute = new Action.Mute(true);
        assertThatThrownBy(() -> session.execute(mute))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("volume up, down and mute");
        var selectInput = new Action.SelectInput("HDMI1");
        assertThatThrownBy(() -> session.execute(selectInput))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("Source button");
        var castLoad = new Action.CastLoad("CC1AD845", Map.of());
        assertThatThrownBy(() -> session.execute(castLoad))
                .isInstanceOf(UnsupportedActionException.class);
    }

    /** Stop needs a cast to stop, which a Tizen TV does not offer; its stop key is a plain key press. */
    @Test
    void stopIsRefused() {
        start(PAIRED);
        connected();
        var stop = new Action.Stop();

        assertThatThrownBy(() -> session.execute(stop))
                .isInstanceOf(UnsupportedActionException.class).hasMessage("Samsung TV cannot stop a cast");
    }

    @Test
    void aYouTubeVideoStartsThroughDialAndShowsAsTheCurrentApp() throws Exception {
        start(PAIRED);
        connected();

        session.execute(new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ")));

        assertThat(tv.nextDialBody()).endsWith("|v=aqz-KE-bpKQ");
        await().atMost(Duration.ofSeconds(3)).until(() -> "YouTube".equals(session.state().currentApp()));
    }

    @Test
    void netflixOpensTheInstalledAppWithoutTheTitle() throws Exception {
        startWithInstalledApps();

        session.execute(new Action.OpenAppLink(URI.create("https://www.netflix.com/title/80057281")));

        assertThat(tv.nextLaunch()).isEqualTo("3201907018807");
        await().atMost(Duration.ofSeconds(3)).until(() -> "Netflix".equals(session.state().currentApp()));
    }

    @Test
    void aWebLinkIsRefusedWithWhatSamsungCanOpen() {
        start(PAIRED);
        connected();

        var webLink = new Action.OpenAppLink(URI.create("https://example.org/a"));
        assertThatThrownBy(() -> session.execute(webLink))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("cannot open web links");
    }

    @Test
    void anAppThatIsNotInstalledIsRefused() {
        startWithInstalledApps();

        var primeVideo = new Action.OpenAppLink(URI.create("https://app.primevideo.com/detail?gti=x"));
        assertThatThrownBy(() -> session.execute(primeVideo))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("Prime Video is not installed");
    }

    @Test
    void aDialRefusalIsAnActionFailure() {
        tv.setDialAvailable(false);
        start(PAIRED);
        connected();

        var youTubeVideo = new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"));
        assertThatThrownBy(() -> session.execute(youTubeVideo))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("not available over DIAL");
    }

    @Test
    void learnsTheMacFromTheRestApi() {
        start(PAIRED);

        await().atMost(Duration.ofSeconds(5)).until(() -> "70:2A:D5:01:02:03".equals(stored("macAddress")));
    }

    @Test
    void aHandEnteredMacIsNotOverwritten() {
        start(Map.of("paired", "true", "token", FakeTizenServer.TOKEN,
                "macAddress", "11:22:33:44:55:66", "macAddressManual", "true"));
        connected();

        // Every poll (200 ms interval) reads another MAC from the REST API.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> "11:22:33:44:55:66".equals(stored("macAddress")));
    }

    @Test
    void standbyMeansPoweredOffAndDisconnected() {
        start(PAIRED);
        connected();

        tv.setPowerState("standby");
        awaitStatus(DeviceStatus.DISCONNECTED);
        assertThat(session.state().powerOn()).isFalse();

        tv.setPowerState("on");
        connected();
    }

    @Test
    void powerWhileOnSendsTheKey() throws Exception {
        start(PAIRED);
        connected();
        // Only what the session publishes from here on counts: the fake TV stays on, so a poll may report it on
        // again right after the session has published "off".
        await().atMost(Duration.ofSeconds(5)).until(() -> !states.all().isEmpty() && states.last().powerOn());
        states.clear();

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(tv.nextKey()).isEqualTo("KEY_POWER");
        await().atMost(Duration.ofSeconds(5)).until(() -> states.all().stream().anyMatch(state -> !state.powerOn()));
    }

    @Test
    void powerOnAClosedSessionWakesNothing() {
        start(PAIRED);
        connected();
        await().atMost(Duration.ofSeconds(5)).until(() -> stored("macAddress") != null);
        session.close();

        var power = new Action.PressKey(RemoteKey.POWER);
        assertThatThrownBy(() -> session.execute(power))
                .isInstanceOf(DeviceOfflineException.class).hasMessageContaining("not connected");
        assertThat(receiver.received()).isZero();
    }

    @Test
    void powerWhileOffWakesTheTv() throws Exception {
        start(PAIRED);
        connected();
        await().atMost(Duration.ofSeconds(5)).until(() -> stored("macAddress") != null);
        tv.switchOff();
        awaitStatus(DeviceStatus.DISCONNECTED);

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket("70:2A:D5:01:02:03"));
        tv.switchOn();
        connected();
    }

    @Test
    void powerWhileOffWithoutAMacExplainsTheFix() {
        tv.switchOff();
        start(PAIRED);
        awaitStatus(DeviceStatus.DISCONNECTED);

        var power = new Action.PressKey(RemoteKey.POWER);
        assertThatThrownBy(() -> session.execute(power))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("setup page");
        assertThat(receiver.received()).isZero();
    }

    @Test
    void aRejectedTokenIsUnpairedAndStopsTrying() {
        tv.setAuthorization(FakeTizenServer.Authorization.DENY);
        start(Map.of("paired", "true", "token", "999"));

        awaitStatus(DeviceStatus.UNPAIRED);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> tv.connections() == 1);
    }

    @Test
    void anUnansweredHandshakeIsTransientKeepsTheTokenAndRetriesWithBackoff() {
        tv.setAuthorization(FakeTizenServer.Authorization.IGNORE);
        // A wider poll (400 ms, so an 800 ms first backoff) than TIMINGS: with a 200 ms poll the no-backoff
        // and with-backoff arrival times (~700 ms and ~900 ms) both land inside any window short enough to
        // finish quickly, so the assertion below could not tell a broken backoff from a working one.
        TizenTimings timings = new TizenTimings(Duration.ofMillis(400), Duration.ZERO, Duration.ofMillis(500),
                TizenTimings.HANDSHAKE_BACKOFF_CAP);
        start(Map.of("paired", "true", "token", "999"), timings);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(() -> tv.connections() == 1);
        // 500 ms request timeout plus an 800 ms backoff; without the backoff the retry would come at the next
        // 400 ms poll, about 900 ms after the first connection.
        await("no retry during the request timeout and the backoff").during(Duration.ofMillis(1100))
                .atMost(Duration.ofSeconds(3)).until(() -> tv.connections() == 1);
        assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(stored("token")).isEqualTo("999");
        assertThat(stored("paired")).isEqualTo("true");

        await().atMost(Duration.ofSeconds(5)).until(() -> tv.connections() >= 2);
        assertThat(states.all()).noneMatch(state -> state.status() == DeviceStatus.UNPAIRED);

        tv.setAuthorization(FakeTizenServer.Authorization.ALLOW);
        await().atMost(Duration.ofSeconds(10)).until(() -> session.state().status() == DeviceStatus.CONNECTED);
        assertThat(stored("token")).isEqualTo(FakeTizenServer.TOKEN);
    }

    @Test
    void neverConnectsWithoutPairing() {
        start(Map.of());

        awaitStatus(DeviceStatus.UNPAIRED);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> tv.connections() == 0);
    }

    @Test
    void aNewlyIssuedTokenIsStored() {
        tv.setAuthorization(FakeTizenServer.Authorization.ALLOW);
        start(Map.of("paired", "true"));

        await().atMost(Duration.ofSeconds(5)).until(() -> FakeTizenServer.TOKEN.equals(stored("token")));
    }

    @Test
    void reconnectsOnTheNextPollAfterADrop() {
        start(PAIRED);
        connected();

        tv.dropConnections();

        states.awaitStatus(DeviceStatus.DISCONNECTED, Duration.ofSeconds(5));
        connected();
    }

    @Test
    void worksWithoutTheRestApi() {
        tv.setRestAvailable(false);
        start(PAIRED);

        connected();
        assertThat(stored("macAddress")).isNull();
    }

    @Test
    void closeStopsPolling() {
        start(PAIRED);
        connected();

        session.close();
        states.clear();
        tv.dropConnections();

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> states.all().isEmpty());
    }

    @Test
    void aFailingStateListenerNeverReachesAButtonPress() throws Exception {
        start(PAIRED, TIMINGS, state -> {
            states.accept(state);
            throw new IllegalStateException("a subscriber failed");
        });
        connected();

        var power = new Action.PressKey(RemoteKey.POWER);
        assertThatCode(() -> session.execute(power)).doesNotThrowAnyException();

        assertThat(tv.nextKey()).isEqualTo("KEY_POWER");
    }

    @Test
    void aWokenTvIsPolledAfterTheWakeGraceNotTheNextInterval() throws Exception {
        TizenTimings slowPoll = new TizenTimings(Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofMillis(500),
                TizenTimings.HANDSHAKE_BACKOFF_CAP);
        Map<String, String> settings = new LinkedHashMap<>(PAIRED);
        settings.put("macAddress", "70:2A:D5:01:02:03");
        start(settings, slowPoll);
        connected();
        tv.switchOff();
        tv.dropConnections();
        awaitStatus(DeviceStatus.DISCONNECTED);
        tv.switchOn();

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(receiver.nextPacket()).containsExactly(WakeOnLan.magicPacket("70:2A:D5:01:02:03"));
        connected();
    }

    @Test
    void aReportedMacIsNormalisedAndAGarbledOneIgnored() {
        assertThat(TizenSession.reportedMac(new TizenDeviceInfo("TV", "QE55", "on", "70-2a-d5-01-02-03", true)))
                .contains("70:2A:D5:01:02:03");
        assertThat(TizenSession.reportedMac(new TizenDeviceInfo("TV", "QE55", "on", "not a mac", true))).isEmpty();
        assertThat(TizenSession.reportedMac(new TizenDeviceInfo("TV", "QE55", "on", "", true))).isEmpty();
    }

    private static TizenProperties properties(FakeTizenServer fake) {
        return new TizenProperties(true, fake.port(), fake.httpPort(), fake.httpPort(), "Home Control",
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(0));
    }

}
