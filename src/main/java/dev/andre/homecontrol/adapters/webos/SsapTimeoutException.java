package dev.andre.homecontrol.adapters.webos;

import java.io.IOException;

/** The connection is up but the TV did not answer this request in time. */
class SsapTimeoutException extends IOException {
    SsapTimeoutException(String message) {
        super(message);
    }
}
