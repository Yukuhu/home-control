package dev.andre.homecontrol.adapters.net;

import java.io.IOException;

/** The device answered, and refused the request. The connection itself is fine. */
public class DeviceRefusedException extends IOException {
    public DeviceRefusedException(String message) {
        super(message);
    }
}
