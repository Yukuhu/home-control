package dev.andre.homecontrol.adapters.androidtv.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsSocketsTest {

    private final ClientCertificate client = ClientCertificate.generate("client");

    @Test
    void reportsTheHandshakeFailureTypeAndMessageWithoutAssumingPeerRejection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write("not TLS".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException _) {
                    // The client may close once it detects the invalid TLS response.
                }
            });

            assertThatThrownBy(() -> TlsSockets.connect("127.0.0.1", server.getLocalPort(), client, 2_000))
                    .isInstanceOf(TlsSockets.HandshakeRejectedException.class)
                    .hasMessageContaining("handshake")
                    .hasMessageContaining("failed")
                    .hasMessageContaining("SSLException")
                    .hasCauseInstanceOf(javax.net.ssl.SSLException.class);
        }
    }

    @Test
    void rejectsADifferentPinnedCertificateDuringTheHandshake() throws Exception {
        ClientCertificate expected = ClientCertificate.generate("paired-device");
        ClientCertificate impostor = ClientCertificate.generate("different-device");

        try (HandshakeServer server = new HandshakeServer(impostor)) {
            assertThatThrownBy(() -> TlsSockets.connect("localhost", server.port(), client, 2_000,
                    ClientCertificate.fingerprintOf(expected.certificate())))
                    .isInstanceOf(TlsSockets.CertificateMismatchException.class);
        }
    }

    @Test
    void acceptsThePinnedCertificateEvenAfterItsValidityPeriod() throws Exception {
        ClientCertificate serverIdentity = expiredIdentity();

        try (HandshakeServer server = new HandshakeServer(serverIdentity);
             SSLSocket socket = TlsSockets.connect("localhost", server.port(), client, 2_000,
                     ClientCertificate.fingerprintOf(serverIdentity.certificate()))) {
            assertThat(socket.getInputStream().read()).isEqualTo(42);
            assertThat(socket.getSession().getPeerCertificates()[0])
                    .isEqualTo(serverIdentity.certificate());
        }
    }

    @Test
    void pairingBootstrapPreservesSelfSignedDevicesWithExpiredCertificates() throws Exception {
        try (HandshakeServer server = new HandshakeServer(expiredIdentity());
             SSLSocket socket = TlsSockets.connect("localhost", server.port(), client, 2_000)) {
            assertThat(socket.getInputStream().read()).isEqualTo(42);
        }
    }

    @Test
    void pairingBootstrapAcceptsTheDevicesDummySelfSignatureAfterTlsProvesKeyPossession() throws Exception {
        ClientCertificate device = dummySignatureIdentity();
        assertThatThrownBy(() -> device.certificate().verify(device.certificate().getPublicKey()))
                .isInstanceOf(java.security.SignatureException.class);

        try (HandshakeServer server = new HandshakeServer(device);
             SSLSocket socket = TlsSockets.connect("localhost", server.port(), client, 2_000)) {
            assertThat(socket.getInputStream().read()).isEqualTo(42);
        }
    }

    @Test
    void pairingBootstrapAcceptsTheSelfSignedClientIdentity() {
        assertThatCode(() -> TlsSockets.PAIRING_TRUST.checkClientTrusted(
                new X509Certificate[]{client.certificate()}, "RSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void pairingBootstrapRejectsAnEmptyCertificateChain() {
        assertThatThrownBy(() -> TlsSockets.PAIRING_TRUST.checkServerTrusted(
                new X509Certificate[0], "RSA"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ClientCertificate expiredIdentity() throws Exception {
        KeyPair keyPair = ClientCertificate.generate("expired-device").keyPair();
        return new ClientCertificate(keyPair, certificate(keyPair, keyPair));
    }

    private static ClientCertificate dummySignatureIdentity() throws Exception {
        KeyPair keyPair = ClientCertificate.generate("dummy-signature-device").keyPair();
        X500Name subject = new X500Name("CN=device");
        ContentSigner algorithm = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        ContentSigner dummy = new ContentSigner() {
            @Override
            public org.bouncycastle.asn1.x509.AlgorithmIdentifier getAlgorithmIdentifier() {
                return algorithm.getAlgorithmIdentifier();
            }

            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public byte[] getSignature() {
                return new byte[]{0};
            }
        };
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(subject, BigInteger.ONE,
                        Date.from(Instant.parse("2020-01-01T00:00:00Z")),
                        Date.from(Instant.parse("2040-01-01T00:00:00Z")), subject, keyPair.getPublic())
                        .build(dummy));
        return new ClientCertificate(keyPair, certificate);
    }

    private static X509Certificate certificate(KeyPair subjectKey, KeyPair signingKey) throws Exception {
        X500Name subject = new X500Name("CN=device");
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE,
                Date.from(Instant.parse("2000-01-01T00:00:00Z")),
                Date.from(Instant.parse("2001-01-01T00:00:00Z")),
                subject, subjectKey.getPublic())
                .build(new JcaContentSignerBuilder("SHA256WithRSA").build(signingKey.getPrivate())));
    }

    private static final class HandshakeServer implements AutoCloseable {
        private final SSLServerSocket listener;

        HandshakeServer(ClientCertificate identity) throws Exception {
            SSLContext context = TlsSockets.context(identity);
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
            listener.setSoTimeout(2_000);
            Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(2_000);
                    socket.startHandshake();
                    socket.getOutputStream().write(42);
                } catch (IOException _) {
                    // The client deliberately aborts the handshake when the pin does not match.
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
