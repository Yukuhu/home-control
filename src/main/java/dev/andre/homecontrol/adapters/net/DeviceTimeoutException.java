package dev.andre.homecontrol.adapters.net;

import java.io.IOException;

/** The device is reachable but did not answer a request in time. */
public class DeviceTimeoutException extends IOException {
    public DeviceTimeoutException(String message) {
        super(message);
    }
}
