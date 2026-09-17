package dev.andre.homecontrol.adapters.bluetooth.bluez;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.*;
import static org.assertj.core.api.Assertions.assertThat;

class BluezFailuresTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("org.freedesktop.dbus.errors.ServiceUnknown",
                        "The name org.bluez was not provided by any .service files", BLUEZ_NOT_RUNNING),
                Arguments.of("org.freedesktop.DBus.Error.AccessDenied", "Rejected send message, 1 matched rules", ACCESS_DENIED),
                Arguments.of("org.freedesktop.dbus.exceptions.DBusException", "Failed to auth", ACCESS_DENIED),
                Arguments.of("org.bluez.Error.Failed", "br-connection-profile-unavailable", NO_AUDIO_PROFILE),
                Arguments.of("org.bluez.exceptions.BluezAuthenticationFailedException", "Authentication Failed", PAIRING_REJECTED),
                Arguments.of("org.bluez.Error.AuthenticationTimeout", "Authentication Timeout", PAIRING_REJECTED),
                Arguments.of("org.bluez.Error.AuthenticationCanceled", "", PAIRING_REJECTED),
                Arguments.of("org.bluez.Error.AlreadyExists", "Already Exists", ALREADY_DONE),
                Arguments.of("org.bluez.exceptions.BluezAlreadyConnectedException", null, ALREADY_DONE),
                Arguments.of("org.bluez.Error.NotConnected", "Not Connected", ALREADY_DONE),
                Arguments.of("org.bluez.Error.InProgress", "Operation already in progress", BUSY),
                Arguments.of("org.bluez.Error.NotReady", "Resource Not Ready", ADAPTER_OFF),
                Arguments.of("org.bluez.Error.DoesNotExist", "Does Not Exist", NOT_FOUND),
                Arguments.of("org.freedesktop.DBus.Error.UnknownObject",
                        "Method \"Pair\" with signature \"\" on interface \"org.bluez.Device1\" doesn't exist", NOT_FOUND),
                Arguments.of("org.bluez.Error.Failed", "br-connection-page-timeout", UNREACHABLE),
                Arguments.of("org.bluez.Error.Failed", "Host is down", UNREACHABLE),
                Arguments.of("org.bluez.Error.ConnectionAttemptFailed", "Page Timeout", UNREACHABLE),
                Arguments.of("org.freedesktop.DBus.Error.NoReply", "Did not receive a reply", TIMEOUT),
                Arguments.of("org.bluez.Error.Failed", "Input/output error", FAILED),
                Arguments.of(null, null, FAILED));
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @MethodSource("cases")
    void classifiesTheError(String errorName, String message, BluezFailure expected) {
        assertThat(BluezFailures.classify(errorName, message)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void messagesNameTheFix() {
        assertThat(BluezFailures.message(BLUEZ_NOT_RUNNING, "x")).contains("systemctl enable --now bluetooth");
        assertThat(BluezFailures.message(NO_ADAPTER, "x")).contains("rfkill unblock bluetooth");
        assertThat(BluezFailures.message(ADAPTER_OFF, "x")).contains("rfkill unblock bluetooth");
        assertThat(BluezFailures.message(NO_AUDIO_PROFILE, "x")).contains("PipeWire").contains("docs/bluetooth-speakers.md");
        assertThat(BluezFailures.message(ACCESS_DENIED, "x")).contains("apparmor:unconfined");
        assertThat(BluezFailures.message(PAIRING_REJECTED, "x")).contains("pairing mode");
        assertThat(BluezFailures.message(UNREACHABLE, "x")).contains("Switch it on");
        assertThat(BluezFailures.noSocket(Path.of("/run/dbus/system_bus_socket")))
                .contains("/run/dbus/system_bus_socket").contains("Mount /run/dbus");
    }
}
