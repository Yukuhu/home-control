package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StatusesTest {

    @ParameterizedTest
    @CsvSource({"401, UNAUTHORIZED", "403, UNAUTHORIZED", "404, NOT_FOUND", "410, NOT_FOUND", "429, RATE_LIMITED",
            "500, SERVER_ERROR", "503, SERVER_ERROR", "400, BAD_RESPONSE", "302, BAD_RESPONSE", "418, BAD_RESPONSE"})
    void mapsAnUnsuccessfulStatusToItsKind(int status, Kind kind) {
        assertThat(Statuses.kindOf(status)).isEqualTo(kind);
    }
}
