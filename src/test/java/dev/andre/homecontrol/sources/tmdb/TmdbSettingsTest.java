package dev.andre.homecontrol.sources.tmdb;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbSettingsTest {

    @Test
    void roundTripsThroughSourcesJson() {
        var settings = new TmdbSettings(TmdbCredential.Kind.API_KEY, Instant.parse("2026-09-01T10:15:30Z"));

        assertThat(settings.toMap()).containsExactly(
                Map.entry("credentialKind", "API_KEY"), Map.entry("connectedAt", "2026-09-01T10:15:30Z"));
        assertThat(TmdbSettings.from(settings.toMap())).contains(settings);
    }

    @Test
    void settingsWithoutAKnownCredentialKindAreNotConnected() {
        assertThat(TmdbSettings.from(null)).isEmpty();
        assertThat(TmdbSettings.from(Map.of())).isEmpty();
        assertThat(TmdbSettings.from(Map.of("connectedAt", "2026-09-01T10:15:30Z"))).isEmpty();
        assertThat(TmdbSettings.from(Map.of("credentialKind", "SESSION"))).isEmpty();
        assertThat(TmdbSettings.from(Map.of("credentialKind", "bearer"))).isEmpty();
    }

    @Test
    void aMissingOrDamagedConnectionTimeKeepsTheConnection() {
        assertThat(TmdbSettings.from(Map.of("credentialKind", "BEARER")))
                .contains(new TmdbSettings(TmdbCredential.Kind.BEARER, Instant.EPOCH));
        assertThat(TmdbSettings.from(Map.of("credentialKind", "BEARER", "connectedAt", "yesterday")))
                .contains(new TmdbSettings(TmdbCredential.Kind.BEARER, Instant.EPOCH));
    }
}
