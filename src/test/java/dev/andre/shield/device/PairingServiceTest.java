package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.protocol.FakePairingServer;
import dev.andre.shield.protocol.PairingResult;
import dev.andre.shield.protocol.RefusingPairingServer;
import dev.andre.shield.storage.DataDirectory;
import dev.andre.shield.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PairingServiceTest {

    @TempDir
    Path dir;

    private FakePairingServer fakeDevice;
    private PairingService service;
    private DeviceSessionManager sessions;
    private CertificateStore certificates;

    @BeforeEach
    void setUp() throws Exception {
        fakeDevice = new FakePairingServer();
        ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
        sessions = mock(DeviceSessionManager.class);
        certificates = new CertificateStore(properties.keystoreFile(), "shield".toCharArray());
        service = new PairingService(certificates, sessions, new DataDirectory(dir));
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

    @Test
    void derivesTheDeviceIdFromTheHostSoRenamingDoesNotDuplicateTheEntry() throws Exception {
        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");
        service.submit(fakeDevice.awaitDisplayedCode());

        try (FakePairingServer rePairedDevice = new FakePairingServer()) {
            // Same host, a different display name, a different in-process fake server —
            // the id must come out identical because it is derived from the host alone.
            service.begin("127.0.0.1", rePairedDevice.port(), "Bedroom Shield");
            service.submit(rePairedDevice.awaitDisplayedCode());
        }

        org.mockito.ArgumentCaptor<Device> captor = org.mockito.ArgumentCaptor.forClass(Device.class);
        verify(sessions, times(2)).adopt(captor.capture());
        List<Device> adopted = captor.getAllValues();

        assertThat(adopted.get(1).id())
                .as("re-pairing the same host under a new name must replace the existing entry, not duplicate it")
                .isEqualTo(adopted.get(0).id());
        assertThat(adopted.get(1).name()).isEqualTo("Bedroom Shield");
    }

    @Test
    void deliberateRepairReusesAnOrphanedCredentialForTheSameHost() throws Exception {
        ClientCertificate orphaned = certificates.loadOrCreate("127-0-0-1");

        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");
        PairingResult result = service.submit(fakeDevice.awaitDisplayedCode());

        assertThat(result).isInstanceOf(PairingResult.Paired.class);
        assertThat(certificates.load("127-0-0-1")).get()
                .extracting(ClientCertificate::certificate)
                .isEqualTo(orphaned.certificate());
    }

    @Test
    void endsTheAttemptEvenWhenAdoptingTheDeviceFails() throws Exception {
        doThrow(new IllegalStateException("the registry is unwritable")).when(sessions).adopt(any());
        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");
        String code = fakeDevice.awaitDisplayedCode();

        assertThatThrownBy(() -> service.submit(code)).isInstanceOf(IllegalStateException.class);

        assertThat(service.inProgress())
                .as("a failure after the code was accepted must not strand the setup page on a dead session")
                .isFalse();
    }

    @Test
    void closesThePairingSocketWhenTheHandshakeFails() throws Exception {
        try (RefusingPairingServer rudeDevice = new RefusingPairingServer()) {
            assertThatThrownBy(() -> service.begin("127.0.0.1", rudeDevice.port(), "Rude Shield"))
                    .isInstanceOf(IOException.class);

            assertThat(rudeDevice.clientHungUp(5))
                    .as("a pairing attempt that fails to start must close its TLS socket, not leak it")
                    .isTrue();
        }
    }

    @Test
    void blocksPairingBeforeCreatingACredentialWhenDataDirectoryIsUnwritable() throws Exception {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "occupied");
        Path keystore = blocked.resolve("keystore.p12");
        PairingService blockedService = new PairingService(
                new CertificateStore(keystore, "shield".toCharArray()),
                sessions,
                new DataDirectory(blocked));

        assertThatThrownBy(() -> blockedService.begin(
                "127.0.0.1", fakeDevice.port(), "Living Room Shield"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(blocked.toString());

        assertThat(Files.exists(keystore)).isFalse();
        assertThat(blockedService.inProgress()).isFalse();
        assertThat(fakeDevice.connections()).isZero();
    }
}
