package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.LaunchedMedia;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.adapters.androidtv.protocol.DisconnectCause;
import dev.andre.homecontrol.adapters.androidtv.protocol.RemoteConnection;
import dev.andre.homecontrol.adapters.androidtv.protocol.RemoteListener;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.testsupport.RecordingStateListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AndroidTvSessionTest {

    private FakeRemoteServer fakeDevice;
    private AndroidTvSession session;

    /** A 10 s stale timeout, longer than any test; backoff 50–200 ms. */
    private static final AndroidTvTimings TIMINGS =
            new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofMillis(200));
    /** A flat 50 ms retry ramp, so tests that need several attempts take well under a second. */
    private static final AndroidTvTimings FAST_RETRY =
            new AndroidTvTimings(Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofMillis(50));
    /** A 1 s stale timeout for the stalled-handshake test, retrying like FAST_RETRY. */
    private static final AndroidTvTimings SHORT_TIMEOUT =
            new AndroidTvTimings(Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(50));

    @BeforeEach
    void startSession() throws Exception {
        fakeDevice = new FakeRemoteServer();
        // A null fingerprint means "not pinned yet"; pinning has its own test below.
        Device device = AndroidTvSettings.device("shield-1", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());
        session = new AndroidTvSession(device, ClientCertificate.generate("shield-remote"),
                TIMINGS, state -> {
        }, null);
    }

    @AfterEach
    void stopSession() throws Exception {
        session.close();
        fakeDevice.close();
    }

    @Test
    void reachesConnectedOnceTheHandshakeCompletes() {
        session.start();

        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void staysConnectingAfterTlsUntilTheRemoteHandshakeCompletes() throws Exception {
        FakeRemoteServer.ConnectionGate gate = fakeDevice.pauseNextRemoteHandshake();
        ClientCertificate credential = ClientCertificate.generate("shield-remote");
        AtomicReference<RemoteListener> attempt = new AtomicReference<>();
        Device device = AndroidTvSettings.device("shield-gated", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());

        try (AndroidTvSession gated = new AndroidTvSession(device, credential, TIMINGS, state -> {
        }, listener -> {
            attempt.set(listener);
            return RemoteConnection.connect("127.0.0.1", fakeDevice.port(), credential, 10_000, listener);
        })) {
            gated.start();
            gate.awaitEntered();
            await().until(() -> attempt.get() != null);

            // The callback reaches the loop after connect() returned, so TLS is complete.
            attempt.get().onPower(true);
            await().until(() -> gated.state().powerOn());
            assertThat(gated.state().status()).isEqualTo(DeviceStatus.CONNECTING);
            assertThatThrownBy(() -> gated.sendKey(RemoteKey.DPAD_UP))
                    .isInstanceOf(DeviceOfflineException.class);

            gate.release();
            await().until(() -> gated.state().status() == DeviceStatus.CONNECTED);
        }
    }

    @Test
    void reflectsStateThatTheDevicePushes() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fakeDevice.pushVolume(12, 100, true);
        fakeDevice.pushCurrentApp("com.netflix.ninja");
        fakeDevice.pushPower(true);

        await().until(() -> session.state().volumeLevel() == 12
                && session.state().muted()
                && "com.netflix.ninja".equals(session.state().currentApp())
                && session.state().powerOn());
    }

    @Test
    void showsTheMediaItLaunchedOnceItsAppIsInFront() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        session.execute(launchInVlc());
        assertThat(fakeDevice.nextAppLink()).isEqualTo("vlc://http://nas.lan/stream");
        fakeDevice.pushCurrentApp("org.videolan.vlc");

        await().untilAsserted(() -> assertThat(session.state().nowPlaying())
                .isEqualTo(new NowPlaying("Big Buck Bunny", PlaybackState.PLAYING, null, 600.0)));
    }

    @Test
    void forgetsTheLaunchedMediaWhenAnotherAppComesToTheFront() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);
        session.execute(launchInVlc());
        fakeDevice.pushCurrentApp("org.videolan.vlc");
        await().until(() -> session.state().nowPlaying() != null);

        fakeDevice.pushCurrentApp("com.netflix.ninja");

        await().until(() -> "com.netflix.ninja".equals(session.state().currentApp()));
        assertThat(session.state().nowPlaying()).isNull();
    }

    @Test
    void forgetsTheLaunchedMediaWhenTheDevicePowersOff() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);
        fakeDevice.pushPower(true);
        session.execute(launchInVlc());
        fakeDevice.pushCurrentApp("org.videolan.vlc");
        await().until(() -> session.state().nowPlaying() != null);

        fakeDevice.pushPower(false);

        await().until(() -> !session.state().powerOn());
        assertThat(session.state().nowPlaying()).isNull();
    }

    @Test
    void keepsTheLaunchedMediaAcrossAReconnect() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);
        session.execute(launchInVlc());
        fakeDevice.pushCurrentApp("org.videolan.vlc");
        await().until(() -> session.state().nowPlaying() != null);

        fakeDevice.hangUp();
        await().until(() -> fakeDevice.connections() == 2
                && session.state().status() == DeviceStatus.CONNECTED);
        fakeDevice.pushCurrentApp("org.videolan.vlc");
        fakeDevice.pushVolume(7, 100, false);

        await().until(() -> session.state().volumeLevel() == 7);
        assertThat(session.state().nowPlaying()).isNotNull();
    }

    @Test
    void anAppLinkWithoutMediaShowsNothingPlaying() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        session.execute(new Action.OpenAppLink(URI.create("https://www.netflix.com/title/80057281")));
        assertThat(fakeDevice.nextAppLink()).isNotNull();
        fakeDevice.pushCurrentApp("com.netflix.ninja");

        await().until(() -> "com.netflix.ninja".equals(session.state().currentApp()));
        assertThat(session.state().nowPlaying()).isNull();
    }

    private static Action.OpenAppLink launchInVlc() {
        return new Action.OpenAppLink(URI.create("vlc://http://nas.lan/stream"),
                new LaunchedMedia("org.videolan.vlc", "Big Buck Bunny", 600.0));
    }

    @Test
    void refusesCommandsWhileDisconnected() {
        assertThatThrownBy(() -> session.sendKey(RemoteKey.DPAD_UP))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Test Shield is not connected");
    }

    @Test
    void deliversKeyPressesWhileConnected() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        session.sendKey(RemoteKey.DPAD_UP);

        assertThat(fakeDevice.nextKeyPress()).isEqualTo(19);
    }

    @Test
    void aLongPressReachesTheTvAsItsStartAndEnd() {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        session.sendKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG);
        session.sendKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG);

        await().atMost(Duration.ofSeconds(5)).until(() -> fakeDevice.receivedKeyPresses().size() == 2);
        assertThat(fakeDevice.receivedKeyPresses()).containsExactly(
                Map.entry(23, RemoteDirection.START_LONG), Map.entry(23, RemoteDirection.END_LONG));
    }

    @Test
    void closingWhileAConnectIsInFlightClosesTheConnectionItThenOpens() throws Exception {
        // close() can run while connect() is still blocked in the TLS handshake on the
        // session's own thread. The connection that handshake produces afterwards belongs to
        // nobody, so the session must close it rather than leave its socket and reader open.
        FakeRemoteServer.ConnectionGate gate = fakeDevice.delayNextConnection();
        session.start();
        gate.awaitEntered();

        session.close();
        gate.release();

        // Well inside the 10s stale timeout, which would otherwise end the connection too.
        await().atMost(Duration.ofSeconds(5)).until(() -> fakeDevice.connectionsEnded() == 1);
    }

    @Test
    void refusesADeviceWhoseCertificateDoesNotMatchThePin() {
        Device impostor = AndroidTvSettings.device("shield-2", "Impostor", "127.0.0.1", fakeDevice.port(),
                "0000000000000000000000000000000000000000000000000000000000000000", Instant.now());

        try (AndroidTvSession pinned = new AndroidTvSession(impostor,
                ClientCertificate.generate("shield-remote"), TIMINGS, state -> {
        }, null)) {
            pinned.start();

            await().until(() -> pinned.state().status() == DeviceStatus.UNPAIRED);
        }
    }

    @Test
    void reconnectsAfterTheDeviceHangsUp() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fakeDevice.hangUp();

        await().until(() -> fakeDevice.connections() >= 2
                && session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void recoversWhenTheDeviceHangsUpBeforeTheHandshakeCompletesRepeatedly() {
        // Deterministic stand-in for the pre-configure race: the first four connections
        // are torn down before any app-level exchange, exactly the ambiguous verdict a
        // real device could produce while rebooting. The fifth goes through normally, so
        // the session must recover rather than latch UNPAIRED.
        fakeDevice.closeNextConnections(4);

        try (AndroidTvSession retrying = fastRetryingSession()) {
            retrying.start();

            await().atMost(Duration.ofSeconds(30))
                    .until(() -> retrying.state().status() == DeviceStatus.CONNECTED);
        }
    }

    @Test
    void keepsRetryingWhenNothingIsListeningOnThePort() throws Exception {
        // Spec section 8 class 1: an unreachable device (asleep, rebooting, moved) is a
        // NETWORK failure, so the session retries with backoff indefinitely. It must never
        // be mistaken for class 2, a certificate rejection, which latches UNPAIRED and
        // never schedules another attempt.
        Device unreachable = AndroidTvSettings.device("shield-unreachable", "Unreachable", "127.0.0.1",
                closedPort(), null, Instant.now());
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean everUnpaired = new AtomicBoolean();

        try (AndroidTvSession dead = new AndroidTvSession(unreachable,
                ClientCertificate.generate("shield-remote"), FAST_RETRY, state -> {
                    if (state.status() == DeviceStatus.CONNECTING) {
                        attempts.incrementAndGet();
                    } else if (state.status() == DeviceStatus.UNPAIRED) {
                        everUnpaired.set(true);
                    }
                }, null)) {
            dead.start();

            // Comfortably more attempts than the ambiguous-verdict latch threshold, so a
            // session that miscounts connect failures as ambiguous verdicts has latched by now.
            await().atMost(Duration.ofSeconds(30)).until(() -> attempts.get() >= 7);
            assertThat(everUnpaired).isFalse();

            // The listener sees CONNECTING before the refused attempt resolves, so the state
            // read right after the 7th attempt may still be CONNECTING; wait for it to settle
            // rather than sampling it at a fixed instant.
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> dead.state().status() == DeviceStatus.DISCONNECTED);
            assertThat(everUnpaired).isFalse();
        }
    }

    @Test
    void keepsReconnectingWhenAStateListenerThrows() throws Exception {
        // In production onChange publishes a Spring event, which is delivered synchronously
        // on this session's own control thread. A subscriber's unchecked exception must not
        // abort the transition it was told about: scheduleReconnect() still has to run, or
        // the session wedges permanently and silently.
        Device unreachable = AndroidTvSettings.device("shield-listener-throws", "Unreachable", "127.0.0.1",
                closedPort(), null, Instant.now());
        AtomicInteger attempts = new AtomicInteger();

        try (AndroidTvSession wedged = new AndroidTvSession(unreachable,
                ClientCertificate.generate("shield-remote"), FAST_RETRY, state -> {
                    if (state.status() == DeviceStatus.CONNECTING) {
                        attempts.incrementAndGet();
                    }
                    throw new IllegalStateException("a wedged subscriber");
                }, null)) {
            wedged.start();

            await().atMost(Duration.ofSeconds(20)).until(() -> attempts.get() >= 3);
        }
    }

    @Test
    void doesNotContinueConnectingAfterAStateListenerThrowsAnError() throws Exception {
        Device device = AndroidTvSettings.device("shield-listener-error", "Test Shield", "127.0.0.1",
                fakeDevice.port(), null, Instant.now());
        CountDownLatch connecting = new CountDownLatch(1);

        try (AndroidTvSession failing = new AndroidTvSession(device,
                ClientCertificate.generate("shield-remote"), TIMINGS, state -> {
                    if (state.status() == DeviceStatus.CONNECTING) {
                        connecting.countDown();
                        throw new LinkageError("a subscriber cannot load its dependency");
                    }
                }, null)) {
            failing.start();
            assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue();

            // Ten times the 50 ms first retry: an attempt that went on, or a retry, would have connected by now.
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .until(() -> fakeDevice.connections() == 0);
            assertThat(failing.state().status()).isEqualTo(DeviceStatus.CONNECTING);
        }
    }

    /** A port that was bound and released, so connecting to it is refused immediately. */
    private static int closedPort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    void latchesUnpairedAfterFiveConsecutiveAmbiguousVerdicts() {
        // Pins the other half of the rule: once the ambiguous verdicts have spanned a
        // reboot-sized window (never a real fingerprint mismatch here), the session gives
        // up and latches.
        fakeDevice.closeNextConnections(5);

        try (AndroidTvSession retrying = fastRetryingSession()) {
            retrying.start();

            await().atMost(Duration.ofSeconds(30))
                    .until(() -> retrying.state().status() == DeviceStatus.UNPAIRED);
        }
    }

    @Test
    void latchesWhenRemoteRejectsAfterTlsBeforeConfiguration() {
        // On real hardware a certificate alert can reach the reader only after the
        // client-side TLS handshake appears successful. Hold the fake before Configure
        // and deliver that verdict through the same asynchronous listener path.
        var gates = new ArrayDeque<FakeRemoteServer.ConnectionGate>();
        for (int i = 0; i < 5; i++) {
            gates.add(fakeDevice.pauseNextRemoteHandshake());
        }
        AtomicBoolean publishedConnected = new AtomicBoolean();
        Device device = AndroidTvSettings.device("shield-after-tls", "Test Shield", "127.0.0.1",
                fakeDevice.port(), null, Instant.now());
        ClientCertificate credential = ClientCertificate.generate("shield-remote");

        try (AndroidTvSession retrying = new AndroidTvSession(device,
                credential, FAST_RETRY, state -> {
                    if (state.status() == DeviceStatus.CONNECTED) {
                        publishedConnected.set(true);
                    }
                }, listener -> {
                    FakeRemoteServer.ConnectionGate gate = gates.remove();
                    RemoteConnection opened = RemoteConnection.connect("127.0.0.1", fakeDevice.port(),
                            credential, 10_000, listener);
                    try {
                        listener.onDisconnected(DisconnectCause.UNPAIRED);
                        return opened;
                    } finally {
                        opened.close();
                        gate.release();
                    }
                })) {
            retrying.start();

            await().atMost(Duration.ofSeconds(20))
                    .until(() -> retrying.state().status() == DeviceStatus.UNPAIRED);
            assertThat(fakeDevice.connections()).isEqualTo(5);
            assertThat(publishedConnected).isFalse();
        }
    }

    @Test
    void doesNotLatchWhenANetworkFailureSeparatesTheAmbiguousVerdicts() throws Exception {
        // Two ambiguous verdicts, a network-class failure (the device accepts the connection
        // but never speaks, so the TLS handshake times out), then three more ambiguous
        // verdicts: five in total, but never five in a row. The network failure is positive
        // evidence that the device was not refusing our certificate — it was not answering
        // at all — so the count starts over and a merely flaky device is never told to
        // re-pair. The seventh connection is served normally.
        fakeDevice.closeNextConnections(2);
        FakeRemoteServer.ConnectionGate gate = fakeDevice.stallNextConnection();
        fakeDevice.closeNextConnections(3);

        RecordingStateListener states = new RecordingStateListener();
        try (AndroidTvSession retrying = sessionWith(SHORT_TIMEOUT, states)) {
            retrying.start();

            gate.awaitEntered();
            // While the gate holds the fake, the session retries every 1 s and is DISCONNECTED for only 50 ms of
            // each cycle: read what it published instead of polling for that moment.
            await().atMost(Duration.ofSeconds(5)).until(() -> attemptEnded(states, 3));
            gate.release();

            await().atMost(Duration.ofSeconds(40))
                    .until(() -> retrying.state().status() == DeviceStatus.CONNECTED);
        }
    }

    /** The same device as {@link #session}, on a flat 1s ramp so the latch tests stay quick. */
    private AndroidTvSession fastRetryingSession() {
        return sessionWith(FAST_RETRY);
    }

    private AndroidTvSession sessionWith(AndroidTvTimings timings) {
        return sessionWith(timings, state -> {
        });
    }

    private AndroidTvSession sessionWith(AndroidTvTimings timings, Consumer<DeviceState> states) {
        return new AndroidTvSession(
                AndroidTvSettings.device("shield-1", "Test Shield", "127.0.0.1", fakeDevice.port(), null, Instant.now()),
                ClientCertificate.generate("shield-remote"), timings, states, null);
    }

    /** Whether the session published DISCONNECTED after its {@code attempt}-th CONNECTING. */
    private static boolean attemptEnded(RecordingStateListener states, int attempt) {
        int attempts = 0;
        for (DeviceState state : states.all()) {
            if (state.status() == DeviceStatus.CONNECTING) {
                attempts++;
            } else if (state.status() == DeviceStatus.DISCONNECTED && attempts == attempt) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aPushThatChangesNothingIsNotPublishedAgain() throws Exception {
        RecordingStateListener states = new RecordingStateListener();
        Device device = AndroidTvSettings.device("shield-recorded", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());

        try (AndroidTvSession recorded = new AndroidTvSession(device, ClientCertificate.generate("shield-remote"),
                TIMINGS, states, null)) {
            recorded.start();
            await().until(() -> recorded.state().status() == DeviceStatus.CONNECTED);
            fakeDevice.pushVolume(12, 100, false);
            await().until(() -> recorded.state().volumeLevel() == 12);
            int published = states.all().size();

            fakeDevice.pushVolume(12, 100, false);
            fakeDevice.pushVolume(13, 100, false);

            await().until(() -> recorded.state().volumeLevel() == 13);
            assertThat(states.all()).hasSize(published + 1);
        }
    }
}
