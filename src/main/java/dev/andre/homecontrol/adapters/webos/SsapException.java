package dev.andre.homecontrol.adapters.webos;

import java.io.IOException;

/** The TV answered, but with an error or {@code returnValue: false}. The connection itself is fine. */
class SsapException extends IOException {
    SsapException(String message) {
        super(message);
    }
}
