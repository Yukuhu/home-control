package dev.andre.shield.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RemoteConnectionTest {

    private FakeRemoteServer device;
    private RemoteConnection connection;

    private final AtomicReference<Boolean> power = new AtomicReference<>();
    private final AtomicReference<String> currentApp = new AtomicReference<>();
    private final AtomicInteger volume = new AtomicInteger(-1);
    private final AtomicBoolean muted = new AtomicBoolean();
    private final AtomicReference<DisconnectCause> disconnect = new AtomicReference<>();

    private final RemoteListener listener = new RemoteListener() {
        @Override
        public void onPower(boolean on) {
            power.set(on);
        }

        @Override
        public void onCurrentApp(String appPackage) {
            currentApp.set(appPackage);
        }

        @Override
        public void onVolume(int level, int max, boolean isMuted) {
            volume.set(level);
            muted.set(isMuted);
        }

        @Override
        public void onDisconnected(DisconnectCause cause) {
            disconnect.set(cause);
        }
    };

    @BeforeEach
    void connect() throws Exception {
        device = new FakeRemoteServer();
        connection = RemoteConnection.connect("127.0.0.1", device.port(),
                ClientCertificate.generate("shield-remote"), 10_000, listener);
        device.awaitHandshake();
    }

    @AfterEach
    void disconnect() throws Exception {
        connection.close();
        device.close();
    }

    @Test
    void answersTheHandshakeSoTheDeviceConsidersUsActive() {
        // awaitHandshake() in setUp already asserts this; make the intent explicit.
        assertThat(disconnect.get()).isNull();
    }

    @Test
    void reportsPowerState() throws Exception {
        device.pushPower(true);

        await().untilAtomic(power, org.hamcrest.Matchers.is(true));
    }

    @Test
    void reportsTheForegroundApp() throws Exception {
        device.pushCurrentApp("com.netflix.ninja");

        await().untilAtomic(currentApp, org.hamcrest.Matchers.is("com.netflix.ninja"));
    }

    @Test
    void reportsVolume() throws Exception {
        device.pushVolume(12, 100, true);

        await().until(() -> volume.get() == 12 && muted.get());
    }

    @Test
    void answersPingsSoTheDeviceDoesNotHangUp() throws Exception {
        device.pushPing(7);

        assertThat(device.nextPong()).isEqualTo(7);
    }

    @Test
    void sendsKeyPressesWithTheVerifiedKeyCode() throws Exception {
        connection.sendKey(RemoteKey.DPAD_UP);

        assertThat(device.nextKeyPress()).isEqualTo(19);
    }

    @Test
    void launchesAppLinks() throws Exception {
        connection.launchAppLink("market://launch?id=com.netflix.ninja");

        assertThat(device.nextAppLink()).isEqualTo("market://launch?id=com.netflix.ninja");
    }

    @Test
    void reportsWhenTheDeviceHangsUp() throws Exception {
        device.hangUp();

        await().untilAtomic(disconnect, org.hamcrest.Matchers.notNullValue());
    }
}
