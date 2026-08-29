package dev.andre.shield.protocol;

import com.google.protobuf.ByteString;
import dev.andre.shield.protocol.pairing.PairingConfiguration;
import dev.andre.shield.protocol.pairing.PairingEncoding;
import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingOption;
import dev.andre.shield.protocol.pairing.PairingRequest;
import dev.andre.shield.protocol.pairing.PairingSecret;
import dev.andre.shield.protocol.pairing.RoleType;

import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.function.Predicate;

/**
 * Drives the Android TV Remote v2 pairing handshake on port 6467.
 *
 * <p>{@link #start()} runs the handshake up to the point where the device puts a six
 * character code on screen; {@link #submitCode(String)} completes it. See spec §5.2 —
 * in particular, the device answers a {@code pairing_option} with its own
 * {@code pairing_option}; there is no acknowledgement message for that step.
 */
public class PairingSession implements AutoCloseable {

    private static final String SERVICE_NAME = "shield-remote";
    private static final int SO_TIMEOUT_MS = 15_000;

    private final String host;
    private final int port;
    private final ClientCertificate credential;

    private SSLSocket socket;
    private MessageStream stream;
    private X509Certificate serverCertificate;

    public PairingSession(String host, int port, ClientCertificate credential) {
        this.host = host;
        this.port = port;
        this.credential = credential;
    }

    public void start() throws IOException {
        socket = TlsSockets.connect(host, port, credential, SO_TIMEOUT_MS);
        stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
        serverCertificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];

        stream.write(ok().setPairingRequest(PairingRequest.newBuilder()
                .setServiceName(SERVICE_NAME)
                .setClientName(SERVICE_NAME)).build());
        expect(PairingMessage::hasPairingRequestAck, "the pairing request acknowledgement");

        stream.write(ok().setPairingOption(PairingOption.newBuilder()
                .addInputEncodings(hexadecimalSixDigits())
                .setPreferredRole(RoleType.ROLE_TYPE_INPUT)).build());
        expect(PairingMessage::hasPairingOption, "the device's pairing options");

        stream.write(ok().setPairingConfiguration(PairingConfiguration.newBuilder()
                .setEncoding(hexadecimalSixDigits())
                .setClientRole(RoleType.ROLE_TYPE_INPUT)).build());
        expect(PairingMessage::hasPairingConfigurationAck, "the pairing configuration acknowledgement");
        // The device is now displaying the code.
    }

    public PairingResult submitCode(String code) {
        byte[] secret;
        try {
            secret = PairingDigest.compute(
                    (RSAPublicKey) credential.certificate().getPublicKey(),
                    (RSAPublicKey) serverCertificate.getPublicKey(),
                    code);
        } catch (PairingDigest.WrongCodeException | IllegalArgumentException e) {
            return new PairingResult.WrongCode();
        }

        try {
            stream.write(ok().setPairingSecret(PairingSecret.newBuilder()
                    .setSecret(ByteString.copyFrom(secret))).build());

            PairingMessage reply = read();
            if (reply.getStatus() == PairingMessage.Status.STATUS_BAD_SECRET) {
                return new PairingResult.WrongCode();
            }
            if (reply.getStatus() != PairingMessage.Status.STATUS_OK || !reply.hasPairingSecretAck()) {
                return new PairingResult.Failed("the device rejected the pairing: " + reply.getStatus());
            }
            return new PairingResult.Paired(serverCertificate);
        } catch (IOException e) {
            return new PairingResult.Failed(
                    "the device closed the connection; it will show a new code on the next attempt");
        }
    }

    public X509Certificate serverCertificate() {
        return serverCertificate;
    }

    private PairingMessage read() throws IOException {
        PairingMessage message = stream.read(PairingMessage.parser());
        if (message == null) {
            throw new EOFException("the device closed the pairing connection");
        }
        return message;
    }

    private void expect(Predicate<PairingMessage> expected, String what) throws IOException {
        PairingMessage message = read();
        if (message.getStatus() != PairingMessage.Status.STATUS_OK) {
            throw new PairingProtocolException(
                    "the device replied " + message.getStatus() + " while waiting for " + what);
        }
        if (!expected.test(message)) {
            throw new PairingProtocolException("expected " + what + " but the device sent something else");
        }
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
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a already-dead pairing socket is not interesting.
            }
        }
    }
}
