package dev.andre.homecontrol.adapters.bluetooth.bluez;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbusBluezClientTest {

    @Test
    void constructingTouchesNothing() {
        DbusBluezClient client = new DbusBluezClient("unix:path=/nonexistent/hc-bus.sock",
                Optional.of(Path.of("/nonexistent/hc-bus.sock")), Duration.ofSeconds(2));
        assertThatCode(client::close).doesNotThrowAnyException();
    }

    @Test
    void aMissingSocketIsNamed() {
        DbusBluezClient client = new DbusBluezClient("unix:path=/nonexistent/hc-bus.sock",
                Optional.of(Path.of("/nonexistent/hc-bus.sock")), Duration.ofSeconds(2));
        assertThatThrownBy(client::adapters)
                .isInstanceOf(BluezException.class)
                .extracting(e -> ((BluezException) e).failure()).isEqualTo(BluezFailure.NO_DBUS_SOCKET);
        assertThatThrownBy(client::adapters).hasMessageContaining("/nonexistent/hc-bus.sock");
    }

    @Test
    void aSocketNobodyServesIsAFailureNotACrash(@TempDir Path temp) throws Exception {
        Path socket = temp.resolve("bus.sock");
        Files.createFile(socket);
        DbusBluezClient client = new DbusBluezClient("unix:path=" + socket, Optional.of(socket), Duration.ofSeconds(2));

        assertThatThrownBy(client::adapters)
                .isInstanceOf(BluezException.class)
                .satisfies(e -> assertThat(((BluezException) e).failure()).isNotEqualTo(BluezFailure.NO_DBUS_SOCKET))
                .hasMessageContaining("D-Bus");

        // No cached broken connection: a second call fails again rather than hanging or NPEing.
        assertThatThrownBy(client::adapters).isInstanceOf(BluezException.class);
    }
}
