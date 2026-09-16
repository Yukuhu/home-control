package dev.andre.homecontrol.adapters.cast.protocol;

import java.io.IOException;

/** The channel is up but the receiver did not answer a request in time. */
public class CastTimeoutException extends IOException {
    public CastTimeoutException(String message) {
        super(message);
    }
}
