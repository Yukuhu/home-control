package dev.andre.homecontrol.adapters.tizen;

import java.io.IOException;

/** The TV answered the DIAL request and refused. */
class DialException extends IOException {
    DialException(String message) {
        super(message);
    }
}
