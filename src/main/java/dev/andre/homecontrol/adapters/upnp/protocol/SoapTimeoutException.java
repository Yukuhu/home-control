package dev.andre.homecontrol.adapters.upnp.protocol;

import java.io.IOException;

/** The device accepted the connection but did not answer in time. */
public class SoapTimeoutException extends IOException {
    public SoapTimeoutException(String message) {
        super(message);
    }
}
