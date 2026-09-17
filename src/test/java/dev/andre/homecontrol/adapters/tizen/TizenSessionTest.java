package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
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
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LearnedSettings;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.device.JsonFileDeviceRegistry;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class TizenSessionTest {

    private static final Map<String, String> PAIRED = Map.of("paired", "true", "token", FakeTizenServer.TOKEN);

    @TempDir
    Path dir;

    private FakeTizenServer tv;
    private FakeWakeOnLanReceiver receiver;
    private DeviceRegistry registry;
    private TizenSession session;
    private final List<DeviceState> states = new CopyOnWriteArrayList<>();

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
            Map<String, String> settings = new LinkedHashMap<>(stored.adapterSettings(TizenAdapter.ID));
            settings.putAll(updates);
            registry.save(stored.withAdapter(TizenAdapter.ID, settings));
        });
    }

    private TizenSession start(Map<String, String> settings) {
        Device device = new Device("samsung", "Samsung TV", DeviceKind.TIZEN, "127.0.0.1",
                Map.of("tizen", settings), Instant.now());
        registry.save(device);
        session = new TizenSession(device, TizenRestTest.properties(tv), InsecureTls.httpClient(Duration.ofSeconds(2)),
                registry, learned(), new WakeOnLan(receiver.address()), states::add, () -> { });
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

    private String stored(String key) {
        return registry.findById("samsung").orElseThrow().adapterSettings(TizenAdapter.ID).get(key);
    }

    @Test
    void connectsWithTheStoredTokenAndReportsPowerOn() {
        start(PAIRED);
        connected();

        assertThat(session.state().currentApp()).isNull();
        assertThat(session.state().volumeMax()).isZero();
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

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.MEDIA_NEXT)))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void absoluteVolumeMuteInputsAndCastAreUnsupportedWithAReason() {
        start(PAIRED);
        connected();

        assertThatThrownBy(() -> session.execute(new Action.SetVolume(20)))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("volume up, down and mute");
        assertThatThrownBy(() -> session.execute(new Action.Mute(true)))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("volume up, down and mute");
        assertThatThrownBy(() -> session.execute(new Action.SelectInput("HDMI1")))
                .isInstanceOf(UnsupportedActionException.class).hasMessageContaining("Source button");
        assertThatThrownBy(() -> session.execute(new Action.CastLoad("CC1AD845", Map.of())))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void stopSendsTheStopKey() throws Exception {
        start(PAIRED);
        connected();

        session.execute(new Action.Stop());

        assertThat(tv.nextKey()).isEqualTo("KEY_STOP");
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
        start(PAIRED);
        connected();
        Thread.sleep(500);

        session.execute(new Action.OpenAppLink(URI.create("https://www.netflix.com/title/80057281")));

        assertThat(tv.nextLaunch()).isEqualTo("3201907018807");
        await().atMost(Duration.ofSeconds(3)).until(() -> "Netflix".equals(session.state().currentApp()));
    }

    @Test
    void aWebLinkIsRefusedWithWhatSamsungCanOpen() {
        start(PAIRED);
        connected();

        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://example.org/a"))))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("cannot open web links");
    }

    @Test
    void anAppThatIsNotInstalledIsRefused() throws Exception {
        start(PAIRED);
        connected();
        Thread.sleep(500);

        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://app.primevideo.com/detail?gti=x"))))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("Prime Video is not installed");
    }

    @Test
    void aDialRefusalIsAnActionFailure() {
        tv.setDialAvailable(false);
        start(PAIRED);
        connected();

        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("not available over DIAL");
    }

    @Test
    void learnsTheMacFromTheRestApi() {
        start(PAIRED);

        await().atMost(Duration.ofSeconds(5)).until(() -> "70:2A:D5:01:02:03".equals(stored("macAddress")));
    }

    @Test
    void aHandEnteredMacIsNotOverwritten() throws Exception {
        start(Map.of("paired", "true", "token", FakeTizenServer.TOKEN,
                "macAddress", "11:22:33:44:55:66", "macAddressManual", "true"));
        connected();
        Thread.sleep(1500);

        assertThat(stored("macAddress")).isEqualTo("11:22:33:44:55:66");
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

        session.execute(new Action.PressKey(RemoteKey.POWER));

        assertThat(tv.nextKey()).isEqualTo("KEY_POWER");
        assertThat(states.getLast().powerOn()).isFalse();
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

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.POWER)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("setup page");
        assertThat(receiver.received()).isZero();
    }

    @Test
    void aRejectedTokenIsUnpairedAndStopsTrying() throws Exception {
        tv.setAuthorization(FakeTizenServer.Authorization.DENY);
        start(Map.of("paired", "true", "token", "999"));

        awaitStatus(DeviceStatus.UNPAIRED);
        Thread.sleep(3000);

        assertThat(tv.connections()).isEqualTo(1);
    }

    @Test
    void anUnansweredHandshakeIsTransientKeepsTheTokenAndRetriesWithBackoff() throws Exception {
        tv.setAuthorization(FakeTizenServer.Authorization.IGNORE);
        long started = System.nanoTime();
        start(Map.of("paired", "true", "token", "999"));

        // The first attempt waits out the 2 s request timeout; the next one is at least 2 s later.
        await().atMost(Duration.ofSeconds(5)).until(() -> Duration.ofNanos(System.nanoTime() - started).toMillis() > 2500
                && tv.connections() == 1);
        assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(stored("token")).isEqualTo("999");
        assertThat(stored("paired")).isEqualTo("true");
        Thread.sleep(Math.max(0, 3500 - Duration.ofNanos(System.nanoTime() - started).toMillis()));
        assertThat(tv.connections()).as("no retry during the backoff").isEqualTo(1);

        await().atMost(Duration.ofSeconds(8)).until(() -> tv.connections() >= 2);
        assertThat(states).noneMatch(state -> state.status() == DeviceStatus.UNPAIRED);

        tv.setAuthorization(FakeTizenServer.Authorization.ALLOW);
        await().atMost(Duration.ofSeconds(15)).until(() -> session.state().status() == DeviceStatus.CONNECTED);
        assertThat(stored("token")).isEqualTo(FakeTizenServer.TOKEN);
    }

    @Test
    void neverConnectsWithoutPairing() throws Exception {
        start(Map.of());

        awaitStatus(DeviceStatus.UNPAIRED);
        Thread.sleep(2000);

        assertThat(tv.connections()).isZero();
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

        await().atMost(Duration.ofSeconds(5)).until(() -> states.stream()
                .anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));
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
    void closeStopsPolling() throws Exception {
        start(PAIRED);
        connected();

        session.close();
        states.clear();
        tv.dropConnections();
        Thread.sleep(3000);

        assertThat(states).isEmpty();
    }
}
