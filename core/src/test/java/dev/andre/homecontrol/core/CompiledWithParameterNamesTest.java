package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledWithParameterNamesTest {

    @Test
    void parameterNamesAreKeptAsInTheApp() throws NoSuchMethodException {
        // Spring Boot's plugin compiles the app with -parameters, and the app reads parameter names by reflection.
        // core is compiled the same way, so a class behaves the same in either project.
        Parameter host = Hosts.class.getMethod("isValid", String.class).getParameters()[0];

        assertThat(host.isNamePresent()).isTrue();
        assertThat(host.getName()).isEqualTo("host");
    }
}
