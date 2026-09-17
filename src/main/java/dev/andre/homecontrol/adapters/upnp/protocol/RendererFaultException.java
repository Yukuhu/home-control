package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.core.ActionFailedException;

/** A renderer refused a command with a UPnP error; the code lets adapters react (Sonos 800: not the coordinator). */
public class RendererFaultException extends ActionFailedException {

    private final int errorCode;

    public RendererFaultException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }
}
