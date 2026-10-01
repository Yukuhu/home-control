package dev.andre.homecontrol.core.content;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every source reports its failures with one set of kinds. */
class ContentSourceExceptionTest {

    @Test
    void theKindsAreTheSharedSet() {
        assertThat(ContentSourceException.Kind.values()).extracting(Enum::name).containsExactly(
                "INVALID_INPUT", "NOT_CONFIGURED", "UNREACHABLE", "BLOCKED", "UNAUTHORIZED", "REVOKED", "FORBIDDEN",
                "NOT_FOUND", "RATE_LIMITED", "QUOTA_EXHAUSTED", "SERVER_ERROR", "BAD_RESPONSE", "TOO_LARGE");
    }
}
