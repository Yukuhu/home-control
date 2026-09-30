package dev.andre.homecontrol.config;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one mapper refuses JSON built to exhaust the stack or the parser. */
class JsonTest {

    @Test
    void readsNestingUpTo64Levels() {
        assertThat(Json.MAPPER.readTree("[".repeat(64) + "]".repeat(64)).isArray()).isTrue();
    }

    @Test
    void refusesDeeperNesting() {
        String deep = "[".repeat(65) + "]".repeat(65);

        assertThatThrownBy(() -> Json.MAPPER.readTree(deep)).isInstanceOf(JacksonException.class);
    }

    @Test
    void refusesANumberLongerThan1000Characters() {
        String huge = "1".repeat(1_001);

        assertThatThrownBy(() -> Json.MAPPER.readTree(huge)).isInstanceOf(JacksonException.class);
    }
}
