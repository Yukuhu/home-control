package dev.andre.homecontrol.adapters.tizen;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;


/** The TV answered the DIAL request and refused. */
class DialException extends DeviceRefusedException {
    DialException(String message) {
        super(message);
    }
}
