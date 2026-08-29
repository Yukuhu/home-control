package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.FakeRemoteServer;
import dev.andre.shield.protocol.RemoteKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DeviceSessionTest {

    private FakeRemoteServer fakeDevice;
    private DeviceSession session;

    private static final ShieldProperties PROPERTIES = new ShieldProperties(
            Path.of("./build/test-data"), "shield", false, 10, 1, 4);

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
}
