package dev.andre.homecontrol.adapters.upnp.protocol;

import java.util.Map;

/** The device answered, and said no (UPnP error), or answered something unreadable (code 0). */
public class SoapFault extends Exception {

    private static final Map<Integer, String> KNOWN = Map.of(
            401, "Invalid Action", 402, "Invalid Args", 501, "Action Failed",
            701, "Transition not available", 702, "No contents", 705, "Transport is locked",
            714, "Illegal MIME-type", 716, "Resource not found", 718, "Invalid InstanceID");

    private final int errorCode;
    private final String description;

    public SoapFault(int errorCode, String description) {
        this(errorCode, new ResolvedDescription(describe(errorCode, description)));
    }

    private SoapFault(int errorCode, ResolvedDescription resolved) {
        super(message(errorCode, resolved.value()));
        this.errorCode = errorCode;
        this.description = resolved.value();
    }

    private record ResolvedDescription(String value) {
    }

    private static String message(int errorCode, String description) {
        if (errorCode <= 0) {
            return description;
        }
        String prefix = "UPnP error " + errorCode;
        return description.isEmpty() ? prefix : prefix + ": " + description;
    }

    private static String describe(int errorCode, String description) {
        String given = description == null ? "" : description.strip();
        return given.isEmpty() || given.equals("UPnPError") ? KNOWN.getOrDefault(errorCode, given) : given;
    }

    public int errorCode() {
        return errorCode;
    }

    public String description() {
        return description;
    }
}
