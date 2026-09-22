package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AndroidTvSessionTest {

    private FakeRemoteServer fakeDevice;
    private AndroidTvSession session;

    private static final AndroidTvProperties PROPERTIES = new AndroidTvProperties(
            Path.of("./build/test-data"), "shield", false, 10, 1, 4);

    /** A flat 1s retry ramp, so tests that need several attempts do not take a minute. */
    private static final AndroidTvProperties FAST_RETRY = new AndroidTvProperties(
            Path.of("./build/test-data"), "shield", false, 10, 1, 1);

    /** As {@link #FAST_RETRY}, but with the 1s stale timeout a stalled handshake needs. */
    private static final AndroidTvProperties SHORT_TIMEOUT = new AndroidTvProperties(
            Path.of("./build/test-data"), "shield", false, 1, 1, 1);

    @BeforeEach
    void startSession() throws Exception {
        fakeDevice = new FakeRemoteServer();
        // A null fingerprint means "not pinned yet"; pinning has its own test below.
        Device device = AndroidTvSettings.device("shield-1", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());
        session = new AndroidTvSession(device, ClientCertificate.generate("shield-remote"),
                PROPERTIES, state -> {
        });
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
    void refusesCommandsWhileDisconnected() {
        assertThatThrownBy(() -> session.sendKey(RemoteKey.DPAD_UP))
                .isInstanceOf(DeviceOfflineException.class);
    }

    @Test
    void deliversKeyPressesWhileConnected() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        session.sendKey(RemoteKey.DPAD_UP);

        assertThat(fakeDevice.nextKeyPress()).isEqualTo(19);
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
                ClientCertificate.generate("shield-remote"), PROPERTIES, state -> {
        })) {
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
                })) {
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
                })) {
            wedged.start();

            await().atMost(Duration.ofSeconds(20)).until(() -> attempts.get() >= 3);
        }
    }

    @Test
    void doesNotContinueConnectingAfterAStateListenerThrowsAnError() throws Exception {
        Device device = AndroidTvSettings.device("shield-listener-error", "Test Shield", "127.0.0.1",
                fakeDevice.port(), null, Instant.now());
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch nextCallback = new CountDownLatch(1);

        try (AndroidTvSession failing = new AndroidTvSession(device,
                ClientCertificate.generate("shield-remote"), PROPERTIES, state -> {
                    if (state.powerOn()) {
                        nextCallback.countDown();
                    } else if (state.status() == DeviceStatus.CONNECTING) {
                        connecting.countDown();
                        throw new LinkageError("a subscriber cannot load its dependency");
                    }
                })) {
            failing.start();
            assertThat(connecting.await(5, TimeUnit.SECONDS)).isTrue();

            // This callback runs after the connect task, so the assertion does not race
            // the task continuing into a network connection after the listener failed.
            failing.onPower(true);
            assertThat(nextCallback.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(fakeDevice.connections()).isZero();
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

        try (AndroidTvSession retrying = sessionWith(SHORT_TIMEOUT)) {
            retrying.start();

            gate.awaitEntered();
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> fakeDevice.connections() == 3
                            && retrying.state().status() == DeviceStatus.DISCONNECTED);
            gate.release();

            await().atMost(Duration.ofSeconds(40))
                    .until(() -> retrying.state().status() == DeviceStatus.CONNECTED);
        }
    }

    /** The same device as {@link #session}, on a flat 1s ramp so the latch tests stay quick. */
    private AndroidTvSession fastRetryingSession() {
        return sessionWith(FAST_RETRY);
    }

    private AndroidTvSession sessionWith(AndroidTvProperties properties) {
        return new AndroidTvSession(
                AndroidTvSettings.device("shield-1", "Test Shield", "127.0.0.1", fakeDevice.port(), null, Instant.now()),
                ClientCertificate.generate("shield-remote"), properties, state -> {
        });
    }
}
