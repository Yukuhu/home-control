package dev.andre.homecontrol.adapters.webos.protocol;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;


/** The TV answered, but with an error or {@code returnValue: false}. The connection itself is fine. */
public class SsapException extends DeviceRefusedException {
    SsapException(String message) {
        super(message);
    }
}
