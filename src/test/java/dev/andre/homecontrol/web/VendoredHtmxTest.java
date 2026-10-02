package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one third-party script the pages load, vendored rather than fetched: htmx 2.0.10, byte for byte as published at
 * https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js. An upgrade replaces the file and updates the version
 * and checksum here and in docs/dev/architecture.md.
 */
class VendoredHtmxTest {

    @Test
    void htmxIsTheRecordedRelease() throws Exception {
        byte[] script;
        try (InputStream in = VendoredHtmxTest.class.getResourceAsStream("/static/vendor/htmx.min.js")) {
            assertThat(in).as("the vendored htmx on the classpath").isNotNull();
            script = in.readAllBytes();
        }

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script)))
                .isEqualTo("71ea67185bfa8c98c39d31717c6fce5d852370fcdfd129db4543774d3145c0de");
        assertThat(new String(script, StandardCharsets.UTF_8)).contains("version:\"2.0.10\"");
    }
}
