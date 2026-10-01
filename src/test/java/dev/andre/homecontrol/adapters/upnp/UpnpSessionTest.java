package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.testsupport.RecordingStateListener;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class UpnpSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** Poll 100 ms idle and playing, command 1 s, reconnect 50–200 ms. */
    private final UpnpTimings timings = new UpnpTimings(Duration.ofMillis(100), Duration.ofMillis(100),
            Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200));
    private final RecordingStateListener states = new RecordingStateListener();
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
        return start(device, locator, states);
    }

    private UpnpSession start(Device device, Function<String, Optional<URI>> locator, Consumer<DeviceState> onChange) {
        UpnpSession started = track(new UpnpSession(device, timings, SoapClient.httpClient(Duration.ofSeconds(1)),
                locator, onChange, () -> { }));
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

        assertThat(states.all().getFirst().status()).isEqualTo(DeviceStatus.DISCONNECTED);
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

        var homeKey = new Action.PressKey(RemoteKey.HOME);
        assertThatThrownBy(() -> session.execute(homeKey)).isInstanceOf(UnsupportedActionException.class);
        var appLink = new Action.OpenAppLink(URI.create("https://x"));
        assertThatThrownBy(() -> session.execute(appLink))
                .isInstanceOf(UnsupportedActionException.class);
        var selectInput = new Action.SelectInput("HDMI_1");
        assertThatThrownBy(() -> session.execute(selectInput)).isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void isOfflineWhileTheRendererIsGone() {
        startConnected();

        fake.hangUp(true);
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.DISCONNECTED);
        var offlineVolume = new Action.SetVolume(10);
        assertThatThrownBy(() -> session.execute(offlineVolume)).isInstanceOf(DeviceOfflineException.class);

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
    void neverFetchesAStoredLocationOffTheDevicesAddress() {
        // A location naming another host than the registered device (or a host name) is never requested.
        Device forged = new Device("kitchen", "Kitchen Speaker", DeviceKind.UPNP, "127.0.0.2",
                Map.of("upnp", Map.of("udn", FakeUpnpRenderer.UDN, "location", fake.location().toString())), Instant.now());
        session = start(forged, udn -> Optional.empty());
        Device named = withLocation(fake.device("named"), "http://localhost:" + fake.port() + "/description.xml");
        UpnpSession byName = start(named, udn -> Optional.empty());

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
            assertThat(byName.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
            assertThat(fake.requestedPaths()).isEmpty();
        });
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

        await().atMost(WAIT).until(() -> !redirecting.requestedPaths().isEmpty());
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(session.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
            assertThat(victim.calls()).isEmpty();
            assertThat(victim.requestedPaths()).isEmpty();
        });
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
        assertThat(states.all()).allSatisfy(state -> assertThat(state.toString()).doesNotContain("ApiKey"));
    }

    @Test
    void pollsFasterWhilePlaying() {
        session = track(new UpnpSession(fake.device("kitchen"),
                new UpnpTimings(Duration.ofMillis(100), Duration.ofSeconds(30), Duration.ofSeconds(1),
                        Duration.ofMillis(50), Duration.ofMillis(200)),
                SoapClient.httpClient(Duration.ofSeconds(1)), udn -> Optional.empty(), states, () -> { }));
        session.start();
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fake.playElsewhere("http://127.0.0.1:9/elsewhere.flac", "");
        session.execute(new Action.Pause()); // forces an immediate poll, which sees an active transport
        int before = fake.calls("GetPositionInfo").size();

        await().atMost(Duration.ofSeconds(3)).until(() -> fake.calls("GetPositionInfo").size() >= before + 2);
    }

    @Test
    void aSlowRendererFailsTheCommandAndRecovers() {
        startConnected();

        fake.delayAnswers(Duration.ofSeconds(2));
        var slowVolume = new Action.SetVolume(30);
        assertThatThrownBy(() -> session.execute(slowVolume))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("did not answer in time");

        fake.delayAnswers(Duration.ZERO);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED));
    }

    @Test
    void garbageAnswersAreIgnoredWhilePolling() {
        startConnected();

        fake.answerRaw("GetVolume", 200, "not xml");
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                .until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fake.answerRaw("Pause", 200, "<x/>");
        var unreadablePause = new Action.Pause();
        assertThatThrownBy(() -> session.execute(unreadablePause))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("Unreadable answer to Pause");
    }

    @Test
    void aDoctypeInAnAnswerIsRefused() {
        startConnected();

        fake.answerRaw("GetTransportInfo", 200,
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>");

        int polls = fake.calls("GetTransportInfo").size();
        await().atMost(WAIT).until(() -> fake.calls("GetTransportInfo").size() >= polls + 2);
        assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(states.all()).noneMatch(state -> state.toString().contains("root:"));
    }

    @Test
    void controlUrlsOnAnotherHostAreRefused() throws Exception {
        fake.overrideDescription(Files.readString(Path.of("src/test/resources/fixtures/upnp/renderer-description.xml"))
                .replace("<controlURL>/upnp/control/AVTransport1</controlURL>",
                        "<controlURL>http://192.0.2.1:1/upnp/control/AVTransport1</controlURL>"));
        session = start(fake.device("kitchen"), udn -> Optional.empty());

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2))
                .until(() -> session.state().status() != DeviceStatus.CONNECTED);
        assertThat(fake.calls("SetAVTransportURI")).isEmpty();
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void aDescriptionThatIsNotXmlIsAConnectFailure() {
        fake.overrideDescription("<html>");
        session = start(fake.device("kitchen"), udn -> Optional.empty());

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))
                .until(() -> session.state().status() != DeviceStatus.CONNECTED);

        fake.overrideDescription(null);
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    private static String description() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/upnp/renderer-description.xml"));
    }

    @Test
    void aServiceWithAMalformedTypeIsLeftOut() throws Exception {
        fake.overrideDescription(description().replace(
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>",
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1#x</serviceType>"));
        startConnected();

        var setVolume = new Action.SetVolume(30);
        assertThatThrownBy(() -> session.execute(setVolume))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("has no volume control");
    }

    @Test
    void anScpdOffTheDescriptionsHostKeepsTheDefaultVolumeRange() throws Exception {
        fake.setVolumeMax(50);
        fake.setVolume(25);
        fake.overrideDescription(description().replace("<SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>",
                "<SCPDURL>http://192.0.2.1:1/scpd/RenderingControl1.xml</SCPDURL>"));
        startConnected();

        assertThat(session.state().volumeLevel()).isEqualTo(25);
        assertThat(fake.requestedPaths()).doesNotContain("/scpd/RenderingControl1.xml");
    }

    @Test
    void anScpdThatCannotBeReadKeepsTheDefaultVolumeRange() throws Exception {
        fake.setVolumeMax(50);
        fake.setVolume(25);
        fake.overrideDescription(description().replace("<SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>",
                "<SCPDURL>/scpd/missing.xml</SCPDURL>"));
        startConnected();

        assertThat(session.state().volumeLevel()).isEqualTo(25);
        assertThat(fake.requestedPaths()).contains("/scpd/missing.xml");
    }

    @Test
    void aFirstReadingThatFailsLeavesTheRendererOffline() {
        fake.fail("GetTransportInfo", 501, "Action Failed", 1);
        session = track(new UpnpSession(fake.device("kitchen"),
                new UpnpTimings(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(1),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                SoapClient.httpClient(Duration.ofSeconds(1)), udn -> Optional.empty(), states, () -> { }));
        session.start();
        await().atMost(WAIT).until(() -> fake.calls("GetTransportInfo").size() == 1);

        var pause = new Action.Pause();
        await().atMost(WAIT).untilAsserted(() -> assertThatThrownBy(() -> session.execute(pause))
                .isInstanceOf(DeviceOfflineException.class));
        assertThat(states.all()).extracting(DeviceState::status).doesNotContain(DeviceStatus.CONNECTED);
    }

    @Test
    void aFailingStateListenerDoesNotStopPolling() {
        session = start(fake.device("kitchen"), udn -> Optional.empty(), state -> {
            states.accept(state);
            throw new IllegalStateException("a subscriber failed");
        });

        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void aListenerThatFailedOnceStillReceivesLaterUpdates() {
        AtomicBoolean failed = new AtomicBoolean();
        session = start(fake.device("kitchen"), udn -> Optional.empty(), state -> {
            states.accept(state);
            if (state.status() == DeviceStatus.CONNECTED && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("listener bug");
            }
        });
        await().atMost(WAIT).until(failed::get);

        fake.setVolume(35);

        await().atMost(WAIT).until(() -> states.last().volumeLevel() == 35);
    }

    @Test
    void anAnnouncedLocationOffTheDevicesHostIsRefused() throws Exception {
        // A datagram from 127.0.0.2 claiming this renderer's UDN must not move the session there.
        FakeUpnpRenderer impostor = track(new FakeUpnpRenderer("127.0.0.2", FakeUpnpRenderer.Layout.GENERIC));
        Device kitchen = withLocation(fake.device("kitchen"), "http://127.0.0.1:9/description.xml");
        session = start(kitchen, udn -> Optional.of(impostor.location()));

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))
                .until(() -> session.state().status() != DeviceStatus.CONNECTED);
        assertThat(impostor.requestedPaths()).isEmpty();
        assertThat(impostor.calls()).isEmpty();
    }

    @Test
    void aDescriptionOfAnotherDeviceIsRefused() throws Exception {
        fake.overrideDescription(Files.readString(Path.of("src/test/resources/fixtures/upnp/renderer-description.xml"))
                .replace(FakeUpnpRenderer.UDN, "uuid:00000000-0000-0000-0000-000000000bad"));
        session = start(fake.device("kitchen"), udn -> Optional.empty());

        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))
                .until(() -> session.state().status() != DeviceStatus.CONNECTED);
        assertThat(fake.calls()).isEmpty();
    }

    @Test
    void closeStopsPolling() {
        startConnected();

        long closedAt = System.nanoTime();
        session.close();

        // A call already on the wire may still land shortly after; nothing may be sent later.
        long lastAllowed = closedAt + Duration.ofMillis(300).toNanos();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(fake.calls()).allSatisfy(call -> assertThat(call.receivedNanos()).isLessThan(lastAllowed)));
    }

    @Test
    void aConnectThatFinishesAfterCloseNeitherPublishesNorTakesCommands() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        session = start(fake.device("kitchen"), udn -> {
            resolving.countDown();
            awaitIgnoringInterrupts(release);
            return Optional.empty();
        });
        assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

        session.close();
        int publishedAtClose = states.all().size();
        release.countDown();

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> states.all().size() == publishedAtClose);
        assertThatThrownBy(() -> session.execute(new Action.Pause())).isInstanceOf(DeviceOfflineException.class);
    }

    /** A step that had already finished when close() interrupted the loop: it goes on as if nothing happened. */
    @SuppressWarnings("java:S2142") // swallowing the interrupt is the point: it models a step that no longer sees it
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        while (true) {
            try {
                if (latch.await(10, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException _) {
                // keep waiting
            }
        }
    }
}
