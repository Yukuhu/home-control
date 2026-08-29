package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.FakeRemoteServer;
import dev.andre.shield.protocol.RemoteKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DeviceSessionTest {

    private FakeRemoteServer fakeDevice;
    private DeviceSession session;

    private static final ShieldProperties PROPERTIES = new ShieldProperties(
            Path.of("./build/test-data"), "shield", false, 10, 1, 4);

    /** A flat 1s retry ramp, so tests that need several attempts do not take a minute. */
    private static final ShieldProperties FAST_RETRY = new ShieldProperties(
            Path.of("./build/test-data"), "shield", false, 10, 1, 1);

    @BeforeEach
    void startSession() throws Exception {
        fakeDevice = new FakeRemoteServer();
        // A null fingerprint means "not pinned yet"; pinning has its own test below.
        Device device = new Device("shield-1", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());
        session = new DeviceSession(device, ClientCertificate.generate("shield-remote"),
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
    void refusesADeviceWhoseCertificateDoesNotMatchThePin() {
        Device impostor = new Device("shield-2", "Impostor", "127.0.0.1", fakeDevice.port(),
                "0000000000000000000000000000000000000000000000000000000000000000", Instant.now());

        try (DeviceSession pinned = new DeviceSession(impostor,
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
    void recoversWhenTheDeviceHangsUpBeforeTheHandshakeCompletesTwice() {
        // Deterministic stand-in for the pre-configure race: the first two connections
        // are torn down before any app-level exchange, exactly the ambiguous verdict a
        // real device could produce by rebooting mid-handshake. The third one goes
        // through normally, so the session must recover rather than latch UNPAIRED.
        fakeDevice.closeNextConnections(2);

        session.start();

        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void keepsRetryingWhenNothingIsListeningOnThePort() throws Exception {
        // Spec section 8 class 1: an unreachable device (asleep, rebooting, moved) is a
        // NETWORK failure, so the session retries with backoff indefinitely. It must never
        // be mistaken for class 2, a certificate rejection, which latches UNPAIRED and
        // never schedules another attempt.
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        Device unreachable = new Device("shield-unreachable", "Unreachable", "127.0.0.1",
                deadPort, null, Instant.now());
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean everUnpaired = new AtomicBoolean();

        try (DeviceSession dead = new DeviceSession(unreachable,
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
            assertThat(dead.state().status()).isEqualTo(DeviceStatus.DISCONNECTED);
        }
    }

    @Test
    void latchesUnpairedAfterThreeConsecutiveAmbiguousVerdicts() {
        // Pins the other half of the rule: a third consecutive ambiguous verdict in a
        // row (never a real fingerprint mismatch here) must still give up and latch.
        fakeDevice.closeNextConnections(3);

        session.start();

        await().until(() -> session.state().status() == DeviceStatus.UNPAIRED);
    }
}
