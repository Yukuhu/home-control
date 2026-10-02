package dev.andre.homecontrol.testsupport;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Recorded device and service responses, read from the classpath under {@code fixtures/}. A module's tests run in the
 * module's own directory, so a path relative to it would find only that module's recordings; the classpath holds the
 * recordings of every module the tests depend on.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** A recording as UTF-8 text, for example {@code read("upnp/didl-track.xml")}. */
    public static String read(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    /** A recording's bytes, as stored. */
    public static byte[] bytes(String name) throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new FileNotFoundException("No recording fixtures/" + name + " on the classpath");
            }
            return in.readAllBytes();
        }
    }
}
