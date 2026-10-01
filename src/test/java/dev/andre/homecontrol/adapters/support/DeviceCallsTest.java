package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;
import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceCallsTest {

    @Test
    void aTimeoutIsAFailedAction() {
        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "set the volume", () -> {
            throw new DeviceTimeoutException("no answer within 1000 ms");
        })).isInstanceOf(ActionFailedException.class)
                .hasMessage("Kitchen TV did not answer in time when asked to set the volume");
    }

    @Test
    void aRefusalIsAFailedActionWithTheDevicesReason() {
        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "set the volume", () -> {
            throw new DeviceRefusedException("returnValue false");
        })).isInstanceOf(ActionFailedException.class)
                .hasMessage("Kitchen TV refused to set the volume: returnValue false");
    }

    @Test
    void anyOtherIoFailureMeansTheDeviceIsOffline() {
        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "set the volume", () -> {
            throw new IOException("Connection reset");
        })).isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Kitchen TV could not be reached to set the volume");
    }

    @Test
    void aResultPassesThrough() {
        assertThat(DeviceCalls.run("Kitchen TV", "read the volume", () -> 42)).isEqualTo(42);
    }

    @Test
    void aCallWithoutResultRuns() {
        AtomicBoolean ran = new AtomicBoolean();

        DeviceCalls.run("Kitchen TV", "mute", () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void runtimeFailuresPassThroughUntouched() {
        IllegalStateException bug = new IllegalStateException("a bug");

        assertThatThrownBy(() -> DeviceCalls.run("Kitchen TV", "mute", () -> {
            throw bug;
        })).isSameAs(bug);
    }

    @Test
    void notConnectedNamesTheDevice() {
        assertThat(DeviceCalls.notConnected("Kitchen TV")).isInstanceOf(DeviceOfflineException.class)
                .hasMessage("Kitchen TV is not connected");
    }
}
