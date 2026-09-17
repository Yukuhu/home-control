package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class UpnpSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final UpnpProperties properties = new UpnpProperties(true, 1, 1, 1, 1, 1, 2);
    private final List<DeviceState> states = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private FakeUpnpRenderer fake;
    private UpnpSession session;

    @BeforeEach
    void setUp() throws IOException {
        fake = track(new FakeUpnpRenderer());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : closeables.reversed()) {
            closeable.close();
        }
    }

    private <T extends AutoCloseable> T track(T closeable) {
        closeables.add(closeable);
        return closeable;
    }

    private UpnpSession start(Device device, Function<String, Optional<URI>> locator) {
        UpnpSession started = track(new UpnpSession(device, properties, SoapClient.httpClient(Duration.ofSeconds(1)),
                locator, states::add, () -> { }));
        started.start();
        return started;
    }

    private UpnpSession startConnected() {
        session = start(fake.device("kitchen"), udn -> Optional.empty());
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
        return session;
    }

    private static Device withLocation(Device device, String location) {
        return new Device(device.id(), device.name(), DeviceKind.UPNP, device.host(),
                Map.of("upnp", Map.of("udn", FakeUpnpRenderer.UDN, "location", location)), Instant.now());
    }

    @Test
    void reportsDisconnectedFirstThenConnectedWithVolume() {
        session = start(fake.device("kitchen"), udn -> Optional.empty());

        assertThat(states.getFirst().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        await().atMost(WAIT).untilAsserted(() -> {
            DeviceState state = session.state();
            assertThat(state.status()).isEqualTo(DeviceStatus.CONNECTED);
            assertThat(state.powerOn()).isTrue();
            assertThat(state.volumeLevel()).isEqualTo(20);
            assertThat(state.volumeMax()).isEqualTo(100);
            assertThat(state.muted()).isFalse();
        });
    }

    @Test
    void playsPausesResumesAndStops() {
        startConnected();

        session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits"));
        assertThat(fake.transportState()).isEqualTo("PLAYING");
        assertThat(fake.currentUri()).isEqualTo("http://127.0.0.1:9/music/song.flac");

        session.execute(new Action.Pause());
        assertThat(fake.transportState()).isEqualTo("PAUSED_PLAYBACK");

        session.execute(new Action.Resume());
        assertThat(fake.transportState()).isEqualTo("PLAYING");
        assertThat(fake.calls("Play").getLast().argument("Speed")).isEqualTo("1");

        session.execute(new Action.Stop());
        assertThat(fake.transportState()).isEqualTo("STOPPED");
    }

    @Test
    void setsVolumeAndMute() {
        startConnected();

        session.execute(new Action.SetVolume(55));
        assertThat(fake.volume()).isEqualTo(55);
        session.execute(new Action.Mute(true));
        assertThat(fake.muted()).isTrue();

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(session.state().volumeLevel()).isEqualTo(55);
            assertThat(session.state().muted()).isTrue();
        });
    }

    @Test
    void usesTheDevicesVolumeRange() throws IOException {
        FakeUpnpRenderer small = track(new FakeUpnpRenderer());
        small.setVolumeMax(50);
        small.setVolume(25);
        session = start(small.device("small"), udn -> Optional.empty());

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
            assertThat(session.state().volumeLevel()).isEqualTo(50);
        });
        session.execute(new Action.SetVolume(100));
        assertThat(small.volume()).isEqualTo(50);
    }

    @Test
    void rejectsWhatARendererCannotDo() {
        startConnected();

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME))).isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://x"))))
                .isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.SelectInput("HDMI_1"))).isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void isOfflineWhileTheRendererIsGone() {
        startConnected();

        fake.hangUp(true);
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.DISCONNECTED);
        assertThatThrownBy(() -> session.execute(new Action.SetVolume(10))).isInstanceOf(DeviceOfflineException.class);

        fake.hangUp(false);
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void followsTheAnnouncedLocation() {
        Device moved = withLocation(fake.device("kitchen"), "http://127.0.0.1:9/description.xml");
        session = start(moved, udn -> FakeUpnpRenderer.UDN.equals(udn) ? Optional.of(fake.location()) : Optional.empty());

        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void neverFetchesAStoredLocationOffTheDevicesAddress() throws InterruptedException {
        // A location naming another host than the registered device (or a host name) is never requested.
        Device forged = new Device("kitchen", "Kitchen Speaker", DeviceKind.UPNP, "127.0.0.2",
                Map.of("upnp", Map.of("udn", FakeUpnpRenderer.UDN, "location", fake.location().toString())), Instant.now());
        session = start(forged, udn -> Optional.empty());
        Device named = withLocation(fake.device("named"), "http://localhost:" + fake.port() + "/description.xml");
        UpnpSession byName = start(named, udn -> Optional.empty());

        Thread.sleep(1500);

        assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(byName.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(fake.requestedPaths()).isEmpty();
    }

    @Test
    void refusesControlUrlsOnAnotherHost() throws Exception {
        // The description sends control (and SCPD) requests to another renderer, named by host name.
        FakeUpnpRenderer victim = track(new FakeUpnpRenderer());
        FakeUpnpRenderer redirecting = track(new FakeUpnpRenderer() {
            @Override
            protected String document(String path) throws IOException {
                String document = super.document(path);
                return document == null ? null : document.replace("<specVersion>",
                        "<URLBase>http://localhost:" + victim.port() + "/</URLBase><specVersion>");
            }
        });
        session = start(redirecting.device("redirecting"), udn -> Optional.empty());

        Thread.sleep(1500);

        assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        assertThat(redirecting.requestedPaths()).isNotEmpty();
        assertThat(victim.calls()).isEmpty();
        assertThat(victim.requestedPaths()).isEmpty();
    }

    @Test
    void reportsWhatIsPlaying() {
        startConnected();
        fake.setPosition("0:00:42", "0:03:07");

        session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying())
                .isEqualTo(new NowPlaying("Bunny Song", PlaybackState.PLAYING, 42.0, 187.0)));

        session.execute(new Action.Pause());
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying().state()).isEqualTo(PlaybackState.PAUSED));

        session.execute(new Action.Stop());
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNull());
    }

    @Test
    void usesTheTitleItSentWhenTheRendererForgetsMetadata() {
        startConnected();
        fake.echoMetadata(false);

        session.execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/song.flac"), "audio/flac", "Bunny Song", "The Rabbits"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull()
                .extracting(NowPlaying::title).isEqualTo("Bunny Song"));
    }

    @Test
    void showsPlaybackStartedByAnotherController() throws IOException {
        startConnected();

        fake.playElsewhere("http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee02/stream.flac?static=true&ApiKey=t",
                Files.readString(Path.of("src/test/resources/fixtures/upnp/position-metadata.xml")));

        await().atMost(WAIT).untilAsserted(() -> assertThat(session.state().nowPlaying()).isNotNull()
                .extracting(NowPlaying::title).isEqualTo("Carrot Waltz"));
        assertThat(states).allSatisfy(state -> assertThat(state.toString()).doesNotContain("ApiKey"));
    }

    @Test
    void pollsFasterWhilePlaying() throws IOException {
        session = track(new UpnpSession(fake.device("kitchen"), new UpnpProperties(true, 1, 30, 1, 1, 1, 2),
                SoapClient.httpClient(Duration.ofSeconds(1)), udn -> Optional.empty(), states::add, () -> { }));
        session.start();
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fake.playElsewhere("http://127.0.0.1:9/elsewhere.flac", "");
        session.execute(new Action.Pause()); // forces an immediate poll, which sees an active transport
        int before = fake.calls("GetPositionInfo").size();

        await().atMost(Duration.ofSeconds(3)).until(() -> fake.calls("GetPositionInfo").size() >= before + 2);
    }

    @Test
    void closeStopsPolling() throws InterruptedException {
        startConnected();

        session.close();
        Thread.sleep(300); // a call already on the wire may still land
        int calls = fake.calls().size();
        Thread.sleep(2500);

        assertThat(fake.calls()).hasSize(calls);
    }
}
