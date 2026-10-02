package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;

/** A receiver answered and said no; the message is its reason. */
public class CastRefusedException extends DeviceRefusedException {

    public CastRefusedException(String reason) {
        super(reason);
    }
}
