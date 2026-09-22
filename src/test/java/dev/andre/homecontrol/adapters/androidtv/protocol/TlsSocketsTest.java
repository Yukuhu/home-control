package dev.andre.homecontrol.adapters.androidtv.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TlsSocketsTest {

    private final ClientCertificate client = ClientCertificate.generate("client");

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
    void pairingBootstrapRejectsAForgedSelfSignature() throws Exception {
        KeyPair differentSigner = ClientCertificate.generate("different-signer").keyPair();
        X509Certificate forged = certificate(client.keyPair(), differentSigner);

        assertThatThrownBy(() -> TlsSockets.PAIRING_TRUST.checkServerTrusted(
                new X509Certificate[]{forged}, "RSA"))
                .isInstanceOf(CertificateException.class);
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
