package dev.andre.homecontrol.core.content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Streaming services a household can say it subscribes to. Keys match AppLinks service keys where one exists. */
public final class StreamingProviders {

    public static final Map<String, String> KNOWN;

    static {
        Map<String, String> known = new LinkedHashMap<>();
        known.put("netflix", "Netflix");
        known.put("primevideo", "Prime Video");
        known.put("dazn", "DAZN");
        known.put("disneyplus", "Disney+");
        known.put("appletvplus", "Apple TV+");
        known.put("paramountplus", "Paramount+");
        known.put("wowtv", "WOW");
        known.put("joyn", "Joyn");
        known.put("rtlplus", "RTL+");
        KNOWN = Collections.unmodifiableMap(known);
    }

    private StreamingProviders() {
    }
}
