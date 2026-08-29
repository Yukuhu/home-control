package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.protocol.FakePairingServer;
import dev.andre.shield.protocol.PairingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PairingServiceTest {

    @TempDir
    Path dir;

    private FakePairingServer fakeDevice;
    private PairingService service;
    private DeviceSessionManager sessions;

    @BeforeEach
    void setUp() throws Exception {
        fakeDevice = new FakePairingServer();
        ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
        sessions = mock(DeviceSessionManager.class);
        service = new PairingService(
                new CertificateStore(properties.keystoreFile(), "shield".toCharArray()),
                sessions);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.cancel();
        fakeDevice.close();
    }

    @Test
    void pairsAndHandsTheDeviceToTheSessionManager() throws Exception {
        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");

        PairingResult result = service.submit(fakeDevice.awaitDisplayedCode());

        assertThat(result).isInstanceOf(PairingResult.Paired.class);
        verify(sessions).adopt(org.mockito.ArgumentMatchers.argThat(device ->
                device.name().equals("Living Room Shield")
                        && device.host().equals("127.0.0.1")
                        && device.port() == 6466
                        && device.certificateFingerprint() != null));
    }

    @Test
    void reportsAWrongCodeAndEndsTheSession() throws Exception {
        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");
        String displayed = fakeDevice.awaitDisplayedCode();
        int wrongCheckByte = (Integer.parseInt(displayed.substring(0, 2), 16) + 1) & 0xFF;

        PairingResult result = service.submit("%02X".formatted(wrongCheckByte) + displayed.substring(2));

        assertThat(result).isInstanceOf(PairingResult.WrongCode.class);
        assertThat(service.inProgress())
                .as("the device shows a new code, so the flow must restart")
                .isFalse();
    }

    @Test
    void reportsWhenNoPairingIsInFlight() {
        assertThat(service.submit("70B2C3")).isInstanceOf(PairingResult.Failed.class);
    }
}
