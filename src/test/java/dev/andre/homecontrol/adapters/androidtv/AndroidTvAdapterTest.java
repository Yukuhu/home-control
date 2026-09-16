package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.adapters.androidtv.protocol.FakeRemoteServer;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AndroidTvAdapterTest {

    @TempDir
    Path dir;

    private AndroidTvProperties properties() {
        return new AndroidTvProperties(dir, "shield", false, 10, 1, 4);
    }

    private AndroidTvAdapter adapter(CertificateStore certificates) {
        return new AndroidTvAdapter(certificates, properties(), new MdnsDiscovery(false));
    }

    @Test
    void declaresRemoteKeysPowerVolumeAndAppLink() {
        Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", 6466, null, Instant.now());

        assertThat(adapter(new CertificateStore(properties().keystoreFile(), "shield".toCharArray()))
                .capabilities(device))
                .containsExactlyInAnyOrder(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME,
                        Capability.APP_LINK);
    }

    @Test
    void withoutACredentialTheHandleIsUnpairedAndNoCredentialIsCreated() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
            Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", remote.port(), null, Instant.now());
            List<DeviceState> seen = new CopyOnWriteArrayList<>();

            try (DeviceHandle handle = adapter(certificates).connect(device, seen::add)) {
                assertThat(handle.state().status()).isEqualTo(DeviceStatus.UNPAIRED);
                assertThatThrownBy(() -> handle.execute(new Action.PressKey(RemoteKey.HOME)))
                        .isInstanceOf(DeviceOfflineException.class)
                        .hasMessageContaining("paired");
            }
            assertThat(seen).extracting(DeviceState::status).containsExactly(DeviceStatus.UNPAIRED);
            assertThat(remote.connections()).isZero();
            assertThat(Files.exists(properties().keystoreFile())).isFalse();
        }
    }

    @Test
    void executesAKeyPressOnceConnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate("shield");
            Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", remote.port(), null, Instant.now());

            try (DeviceHandle handle = adapter(certificates).connect(device, state -> { })) {
                await().until(() -> handle.state().status() == DeviceStatus.CONNECTED);

                handle.execute(new Action.PressKey(RemoteKey.DPAD_UP));

                assertThat(remote.nextKeyPress()).isEqualTo(RemoteKey.DPAD_UP.code());
            }
        }
    }

    @Test
    void opensAnAppLinkOnceConnected() throws Exception {
        try (FakeRemoteServer remote = new FakeRemoteServer()) {
            CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
            certificates.loadOrCreate("shield");
            Device device = AndroidTvSettings.device("shield", "Shield", "127.0.0.1", remote.port(), null, Instant.now());

            try (DeviceHandle handle = adapter(certificates).connect(device, state -> { })) {
                await().until(() -> handle.state().status() == DeviceStatus.CONNECTED);

                handle.execute(new Action.OpenAppLink(URI.create("https://www.netflix.com/title/80057281")));

                assertThat(remote.nextAppLink()).isEqualTo("https://www.netflix.com/title/80057281");
            }
        }
    }

    @Test
    void anUnreadableKeystoreFailsAtAdapterStart() {
        new CertificateStore(properties().keystoreFile(), "correct".toCharArray()).loadOrCreate("x");
        AndroidTvAdapter adapter = adapter(new CertificateStore(properties().keystoreFile(), "wrong".toCharArray()));

        assertThatThrownBy(adapter::verifyCredentialStore)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("password")
                .hasMessageContaining(properties().keystoreFile().toString());
    }

    @Test
    void forgetDeletesOnlyThatDevicesCredential() {
        CertificateStore certificates = new CertificateStore(properties().keystoreFile(), "shield".toCharArray());
        certificates.loadOrCreate("forgotten");
        certificates.loadOrCreate("kept");
        Device device = AndroidTvSettings.device("forgotten", "Shield", "127.0.0.1", 6466, null, Instant.now());

        adapter(certificates).forget(device);

        assertThat(certificates.load("forgotten")).isEmpty();
        assertThat(certificates.load("kept")).isPresent();
    }
}
