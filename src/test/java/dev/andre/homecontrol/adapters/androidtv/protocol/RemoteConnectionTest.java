package dev.andre.homecontrol.adapters.androidtv.protocol;

import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteDirection;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteMessage;
import dev.andre.homecontrol.adapters.androidtv.protocol.remote.RemoteStart;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
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

    /** Mimics a device that has forgotten the pairing: it rejects the client certificate outright. */
    private static final X509TrustManager REJECT_CLIENT_CERTIFICATE = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("rejected for test: simulating an unpaired device");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // This server-side trust manager only rejects client certificates.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
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
    void advertisesKeyImePowerVolumeAndAppLink() {
        assertThat(device.clientConfigureFeatures()).isEqualTo(614);
        assertThat(device.clientActiveFeatures()).isEqualTo(614);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outgoingCommandsKeepAnOtherwiseQuietConnectionAlive(boolean appLinks) throws Exception {
        reconnectWithTimeout(1_000);

        // Also leave a frame partly read: timeout handling must not discard the
        // parser's position while commands are keeping the connection active.
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        RemoteMessage.newBuilder().setRemoteStart(RemoteStart.newBuilder().setStarted(true))
                .build().writeDelimitedTo(encoded);
        byte[] frame = encoded.toByteArray();
        device.pushRaw(Arrays.copyOf(frame, frame.length - 1));

        // Android TV postpones its pings while it receives commands. Keep sending
        // for more than two idle windows without any incoming state or ping traffic.
        await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100))
                .during(Duration.ofMillis(2_200)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(disconnect.get()).as("active command traffic must not become stale").isNull();
            if (appLinks) {
                connection.sendAppLink("https://www.youtube.com/");
                assertThat(device.nextAppLink()).isEqualTo("https://www.youtube.com/");
            } else {
                connection.sendKey(RemoteKey.DPAD_DOWN);
                assertThat(device.nextKeyPress()).isEqualTo(20);
            }
        });

        assertThat(disconnect.get()).isNull();
        device.pushRaw(new byte[]{frame[frame.length - 1]});
        await().untilAtomic(power, org.hamcrest.Matchers.is(true));
        device.pushPing(19);
        assertThat(device.nextPong()).isEqualTo(19);
        // A genuinely idle connection must still expire once commands stop.
        await().untilAtomic(disconnect, org.hamcrest.Matchers.is(DisconnectCause.STALE));
    }

    @Test
    void incomingStateKeepsTheConnectionAliveWithoutOutgoingCommands() throws Exception {
        reconnectWithTimeout(1_000);

        AtomicInteger level = new AtomicInteger();
        await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100))
                .during(Duration.ofMillis(2_200)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            int expected = level.incrementAndGet();
            device.pushVolume(expected, 100, false);
            await().untilAtomic(volume, org.hamcrest.Matchers.is(expected));
            assertThat(disconnect.get()).isNull();
        });

        assertThat(disconnect.get()).isNull();
        await().untilAtomic(disconnect, org.hamcrest.Matchers.is(DisconnectCause.STALE));
    }

    @Test
    void ownerCloseDoesNotLaterReportAnIdleDisconnect() throws Exception {
        reconnectWithTimeout(300);

        connection.close();

        await().until(() -> device.connectionsEnded() == 1);
        await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2))
                .untilAtomic(disconnect, org.hamcrest.Matchers.nullValue());
    }

    private void reconnectWithTimeout(int timeoutMillis) throws Exception {
        connection.close();
        device.close();
        device = new FakeRemoteServer();
        connection = RemoteConnection.connect("127.0.0.1", device.port(),
                ClientCertificate.generate("shield-remote"), timeoutMillis, listener);
        device.awaitHandshake();
    }

    @Test
    void sendsKeyPressesWithTheVerifiedKeyCode() throws Exception {
        connection.sendKey(RemoteKey.DPAD_UP);

        assertThat(device.nextKeyPress()).isEqualTo(19);
    }

    @Test
    void sendsLongPressDirections() throws Exception {
        connection.sendKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG);
        connection.sendKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG);

        await().until(() -> device.receivedKeyPresses().size() >= 2);
        assertThat(device.receivedKeyPresses()).containsExactly(
                Map.entry(23, RemoteDirection.START_LONG), Map.entry(23, RemoteDirection.END_LONG));
    }

    @Test
    void sendsAnAppLinkLaunchRequest() throws Exception {
        connection.sendAppLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertThat(device.nextAppLink()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    }

    @Test
    void reportsWhenTheDeviceHangsUp() throws Exception {
        device.hangUp();

        await().untilAtomic(disconnect, org.hamcrest.Matchers.is(DisconnectCause.CLOSED));
    }

    @Test
    void reportsUnpairedWhenTheDeviceRejectsOurCertificate() throws Exception {
        ClientCertificate rejectingDeviceIdentity = ClientCertificate.generate("rejecting-fake-shield");
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(rejectingDeviceIdentity),
                new TrustManager[]{REJECT_CLIENT_CERTIFICATE}, new SecureRandom());

        try (SSLServerSocket rejectingServer =
                     (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0)) {
            rejectingServer.setNeedClientAuth(true);
            Thread.ofVirtual().name("rejecting-fake-shield").start(() -> {
                try (SSLSocket accepted = (SSLSocket) rejectingServer.accept()) {
                    // Driving the handshake from the server side is what makes the
                    // TrustManager actually run and reject the client's certificate.
                    accepted.startHandshake();
                } catch (Exception _) {
                    // The handshake is expected to fail on this side too.
                }
            });

            AtomicReference<DisconnectCause> rejectedDisconnect = new AtomicReference<>();
            RemoteListener rejectingListener = new RemoteListener() {
                @Override
                public void onDisconnected(DisconnectCause cause) {
                    rejectedDisconnect.set(cause);
                }
            };

            // The rejection reaches the client one of two ways, depending on TLS handshake
            // timing (verified empirically, not just in theory: run head-to-head against this
            // same fake rejecting server many times, connect() itself throws UnpairedException
            // only a minority of the time). Either the TLS layer fails before startHandshake()
            // returns, so connect() throws UnpairedException directly - or startHandshake()
            // looks like it succeeded from the client's side, and the certificate_unknown alert
            // only arrives on the connection's first post-handshake read, reported through the
            // listener as DisconnectCause.UNPAIRED instead. Both are the same "you must re-pair"
            // signal to the caller, so both are accepted outcomes here.
            try {
                RemoteConnection rejected = RemoteConnection.connect("127.0.0.1",
                        rejectingServer.getLocalPort(), ClientCertificate.generate("shield-remote"),
                        10_000, rejectingListener);
                await().untilAtomic(rejectedDisconnect, org.hamcrest.Matchers.is(DisconnectCause.UNPAIRED));
                rejected.close();
            } catch (RemoteConnection.UnpairedException _) {
                // Also an acceptable outcome - see comment above.
            }
        }
    }

    @Test
    void reportsStaleWhenNothingArrivesWithinTheTimeout() throws Exception {
        AtomicReference<DisconnectCause> staleDisconnect = new AtomicReference<>();
        RemoteListener staleListener = new RemoteListener() {
            @Override
            public void onDisconnected(DisconnectCause cause) {
                staleDisconnect.set(cause);
            }
        };

        try (FakeRemoteServer silentDevice = new FakeRemoteServer()) {
            // staleTimeoutMillis also bounds every individual read during the TLS handshake,
            // because TlsSockets.connect() calls setSoTimeout() before startHandshake().
            // After TLS, the idle watchdog covers the configure/active exchange and commands.
            // 300ms must stay comfortably larger than a localhost
            // handshake round trip; if this is ever tightened enough to violate that, the test
            // fails loudly with a SocketTimeoutException escaping connect() itself, rather than
            // silently mis-asserting.
            try (RemoteConnection _ = RemoteConnection.connect("127.0.0.1", silentDevice.port(),
                    ClientCertificate.generate("shield-remote"), 300, staleListener)) {
                silentDevice.awaitHandshake();

                await().untilAtomic(staleDisconnect, org.hamcrest.Matchers.is(DisconnectCause.STALE));
            }
        }
    }
}
