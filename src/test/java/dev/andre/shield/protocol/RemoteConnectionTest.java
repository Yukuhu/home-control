package dev.andre.shield.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
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
                } catch (Exception e) {
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
            } catch (RemoteConnection.UnpairedException expected) {
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
            try (RemoteConnection staleConnection = RemoteConnection.connect("127.0.0.1", silentDevice.port(),
                    ClientCertificate.generate("shield-remote"), 300, staleListener)) {
                silentDevice.awaitHandshake();

                await().untilAtomic(staleDisconnect, org.hamcrest.Matchers.is(DisconnectCause.STALE));
            }
        }
    }
}
