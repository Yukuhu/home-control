package dev.andre.homecontrol.adapters.androidtv.protocol;

import dev.andre.homecontrol.adapters.androidtv.protocol.pairing.PairingMessage;
import dev.andre.homecontrol.adapters.androidtv.protocol.pairing.PairingOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PairingSessionTest {

    private FakePairingServer device;
    private PairingSession session;
    private final ClientCertificate credential = ClientCertificate.generate("shield-remote");

    @BeforeEach
    void startDevice() throws Exception {
        device = new FakePairingServer();
        session = new PairingSession("127.0.0.1", device.port(), credential);
    }

    @AfterEach
    void stopDevice() throws Exception {
        session.close();
        device.close();
    }

    @Test
    void pairsWhenTheDisplayedCodeIsEntered() throws Exception {
        session.start();

        PairingResult result = session.submitCode(device.awaitDisplayedCode());

        assertThat(result).isInstanceOf(PairingResult.Paired.class);
        assertThat(device.receivedSecret()).isNotNull();
    }

    @Test
    void exposesTheDeviceCertificateForPinning() throws Exception {
        session.start();

        assertThat(session.serverCertificate()).isEqualTo(device.certificate());
    }

    @Test
    void rejectsAWrongCodeWithoutSendingASecretToTheDevice() throws Exception {
        session.start();
        String displayed = device.awaitDisplayedCode();

        // Same nonce, deliberately wrong check byte.
        int wrongCheckByte = (Integer.parseInt(displayed.substring(0, 2), 16) + 1) & 0xFF;
        String wrong = "%02X".formatted(wrongCheckByte) + displayed.substring(2);

        PairingResult result = session.submitCode(wrong);

        assertThat(result).isInstanceOf(PairingResult.WrongCode.class);
        assertThat(device.receivedSecret())
                .as("a locally detectable wrong code must never reach the device")
                .isNull();
    }

    @Test
    void rejectsAMalformedCode() throws Exception {
        session.start();
        device.awaitDisplayedCode();

        assertThat(session.submitCode("12345")).isInstanceOf(PairingResult.WrongCode.class);
    }

    @Test
    void aSecretTheDeviceDoesNotAcceptIsAWrongCode() throws Exception {
        session.start();
        device.awaitDisplayedCode();
        // A code for another nonce passes the local check byte, but its secret is not the one the device expects.
        RSAPublicKey clientKey = (RSAPublicKey) credential.certificate().getPublicKey();
        RSAPublicKey deviceKey = (RSAPublicKey) device.certificate().getPublicKey();
        byte[] otherSecret = PairingDigest.digest(
                PairingDigest.unsignedBytes(clientKey.getModulus()),
                PairingDigest.unsignedBytes(clientKey.getPublicExponent()),
                PairingDigest.unsignedBytes(deviceKey.getModulus()),
                PairingDigest.unsignedBytes(deviceKey.getPublicExponent()),
                "000000");
        String otherCode = HexFormat.of().withUpperCase().formatHex(new byte[]{otherSecret[0]}) + "0000";

        PairingResult result = session.submitCode(otherCode);

        assertThat(result).isInstanceOf(PairingResult.WrongCode.class);
        assertThat(device.receivedSecret()).isEqualTo(otherSecret);
    }

    @Test
    void closingASessionThatNeverStartedIsHarmless() {
        PairingSession unstarted = new PairingSession("127.0.0.1", device.port(), credential);

        assertThatCode(unstarted::close).doesNotThrowAnyException();
        assertThat(unstarted.serverCertificate()).isNull();
    }

    @Test
    void aDeviceThatHangsUpDuringTheExchangeEndsTheStart() throws Exception {
        try (ScriptedDevice hangsUp = new ScriptedDevice(stream -> { })) {
            PairingSession pairing = new PairingSession("127.0.0.1", hangsUp.port(), credential);

            assertThatThrownBy(pairing::start)
                    .isInstanceOf(EOFException.class)
                    .hasMessage("the device closed the pairing connection");
            pairing.close();
        }
    }

    @Test
    void aDeviceThatAnswersWithAnotherMessageIsAProtocolError() throws Exception {
        try (ScriptedDevice confused = new ScriptedDevice(stream -> write(stream, PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingOption(PairingOption.getDefaultInstance())
                .build()))) {
            PairingSession pairing = new PairingSession("127.0.0.1", confused.port(), credential);

            assertThatThrownBy(pairing::start)
                    .isInstanceOf(PairingProtocolException.class)
                    .hasMessage("expected the pairing request acknowledgement but the device sent something else");
            pairing.close();
        }
    }

    private static void write(MessageStream stream, PairingMessage message) {
        try {
            stream.write(message);
        } catch (IOException _) {
            // The client went away; the test asserts on what it saw.
        }
    }

    /** A pairing device that reads the client's first message, answers it with {@code answer}, then hangs up. */
    private static final class ScriptedDevice implements AutoCloseable {
        private final SSLServerSocket listener;

        ScriptedDevice(Consumer<MessageStream> answer) throws Exception {
            listener = (SSLServerSocket) TlsSockets.context(ClientCertificate.generate("scripted-device"))
                    .getServerSocketFactory().createServerSocket(0);
            listener.setWantClientAuth(true);
            Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    MessageStream stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
                    stream.read(PairingMessage.parser());
                    answer.accept(stream);
                } catch (IOException _) {
                    // The client went away; the test asserts on what it saw.
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
