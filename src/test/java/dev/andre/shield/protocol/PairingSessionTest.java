package dev.andre.shield.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
