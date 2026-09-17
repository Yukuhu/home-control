package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One UPnP action call. Argument order is significant on the wire. */
public record SoapRequest(String serviceType, String action, Map<String, String> arguments) {

    public SoapRequest {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** Arguments can hold stream URLs with credentials; never print them. */
    @Override
    public String toString() {
        return "SoapRequest[" + serviceType + "#" + action + "]";
    }
}
