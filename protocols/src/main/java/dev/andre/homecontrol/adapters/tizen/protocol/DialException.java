package dev.andre.homecontrol.adapters.tizen.protocol;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;


/** The TV answered the DIAL request and refused. */
public class DialException extends DeviceRefusedException {
    DialException(String message) {
        super(message);
    }
}
