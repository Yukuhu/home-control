package dev.andre.shield.protocol;

import com.google.protobuf.ByteString;
import dev.andre.shield.protocol.pairing.PairingConfigurationAck;
import dev.andre.shield.protocol.pairing.PairingEncoding;
import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingOption;
import dev.andre.shield.protocol.pairing.PairingRequestAck;
import dev.andre.shield.protocol.pairing.PairingSecretAck;
import dev.andre.shield.protocol.pairing.RoleType;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** An in-process stand-in for the Shield's pairing port. */
public class FakePairingServer implements AutoCloseable {

    /** Fixed so the displayed code is reproducible across runs. */
    private static final String NONCE = "B2C3";

    private final ClientCertificate identity = ClientCertificate.generate("fake-shield");
    private final SSLServerSocket serverSocket;
    private final CountDownLatch codeDisplayed = new CountDownLatch(1);
    private final AtomicInteger connections = new AtomicInteger();

    private volatile String displayedCode;
    private volatile byte[] expectedSecret;
    private volatile byte[] receivedSecret;

    public FakePairingServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(identity),
                new TrustManager[]{TlsSockets.ACCEPT_ANY}, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
        serverSocket.setWantClientAuth(true);
        Thread.ofVirtual().name("fake-pairing-server").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int connections() {
        return connections.get();
    }

    public X509Certificate certificate() {
        return identity.certificate();
    }

    /** The six character code this device is "showing on screen". */
    public String awaitDisplayedCode() throws InterruptedException {
        if (!codeDisplayed.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("pairing never reached the code display step");
        }
        return displayedCode;
    }

    public byte[] receivedSecret() {
        return receivedSecret;
    }

    private void serve() {
        try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
            connections.incrementAndGet();
            MessageStream stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
            X509Certificate clientCertificate =
                    (X509Certificate) socket.getSession().getPeerCertificates()[0];

            PairingMessage message;
            while ((message = stream.read(PairingMessage.parser())) != null) {
                if (message.hasPairingRequest()) {
                    stream.write(ok().setPairingRequestAck(
                            PairingRequestAck.newBuilder().setServerName("fake-shield")).build());
                } else if (message.hasPairingOption()) {
                    stream.write(ok().setPairingOption(PairingOption.newBuilder()
                            .addInputEncodings(hexadecimalSixDigits())
                            .setPreferredRole(RoleType.ROLE_TYPE_INPUT)).build());
                } else if (message.hasPairingConfiguration()) {
                    displayCode(clientCertificate);
                    stream.write(ok().setPairingConfigurationAck(
                            PairingConfigurationAck.getDefaultInstance()).build());
                } else if (message.hasPairingSecret()) {
                    receivedSecret = message.getPairingSecret().getSecret().toByteArray();
                    if (Arrays.equals(receivedSecret, expectedSecret)) {
                        stream.write(ok().setPairingSecretAck(PairingSecretAck.newBuilder()
                                .setSecret(ByteString.copyFrom(expectedSecret))).build());
                    } else {
                        stream.write(PairingMessage.newBuilder()
                                .setProtocolVersion(2)
                                .setStatus(PairingMessage.Status.STATUS_BAD_SECRET)
                                .build());
                    }
                    return;
                }
            }
        } catch (Exception e) {
            // The connection ended; tests assert on observed state, not on this thread.
        }
    }

    private void displayCode(X509Certificate clientCertificate) {
        RSAPublicKey clientKey = (RSAPublicKey) clientCertificate.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) identity.certificate().getPublicKey();

        expectedSecret = PairingDigest.digest(
                PairingDigest.unsignedBytes(clientKey.getModulus()),
                PairingDigest.unsignedBytes(clientKey.getPublicExponent()),
                PairingDigest.unsignedBytes(serverKey.getModulus()),
                PairingDigest.unsignedBytes(serverKey.getPublicExponent()),
                "00" + NONCE);

        displayedCode = HexFormat.of().withUpperCase()
                .formatHex(new byte[]{expectedSecret[0]}) + NONCE;
        codeDisplayed.countDown();
    }

    private static PairingEncoding hexadecimalSixDigits() {
        return PairingEncoding.newBuilder()
                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                .setSymbolLength(6)
                .build();
    }

    private static PairingMessage.Builder ok() {
        return PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK);
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
    }
}
