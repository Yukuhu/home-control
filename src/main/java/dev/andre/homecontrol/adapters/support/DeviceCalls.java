package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;
import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;

import java.io.IOException;

/**
 * Runs a call to a device and says what went wrong in the words of the rest of the system: no answer in time or a
 * refusal is a failed action, any other I/O failure an offline device. {@code what} is a verb phrase such as
 * "set the volume".
 */
public final class DeviceCalls {

    @FunctionalInterface
    public interface Call<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    public interface VoidCall {
        void run() throws IOException;
    }

    private DeviceCalls() {
    }

    public static <T> T run(String deviceName, String what, Call<T> call) {
        try {
            return call.run();
        } catch (DeviceTimeoutException _) {
            throw new ActionFailedException(deviceName + " did not answer in time when asked to " + what);
        } catch (DeviceRefusedException e) {
            throw new ActionFailedException(deviceName + " refused to " + what + ": " + e.getMessage());
        } catch (IOException _) {
            throw new DeviceOfflineException(deviceName + " could not be reached to " + what);
        }
    }

    public static void run(String deviceName, String what, VoidCall call) {
        run(deviceName, what, () -> {
            call.run();
            return null;
        });
    }

    public static DeviceOfflineException notConnected(String deviceName) {
        return new DeviceOfflineException(deviceName + " is not connected");
    }
}
