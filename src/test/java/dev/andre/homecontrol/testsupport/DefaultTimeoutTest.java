package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the JUnit configuration every test runs under: a hang fails the test within a minute. */
class DefaultTimeoutTest {

    @Test
    void everyTestHasASixtySecondTimeoutUnlessItDeclaresOne() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = DefaultTimeoutTest.class.getResourceAsStream("/junit-platform.properties")) {
            assertThat(in).as("junit-platform.properties on the test classpath").isNotNull();
            properties.load(in);
        }

        assertThat(properties)
                .containsEntry("junit.jupiter.execution.timeout.default", "60 s")
                .containsEntry("junit.jupiter.execution.timeout.mode", "disabled_on_debug");
    }
}
