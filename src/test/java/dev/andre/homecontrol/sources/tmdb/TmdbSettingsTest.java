package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbSettingsTest {

    @TempDir
    Path dir;

    private JsonFileSourceSettings store() {
        return new JsonFileSourceSettings(dir.resolve("sources.json"));
    }

    @Test
    void roundTripsThroughSourcesJson() {
        var settings = new TmdbSettings(TmdbCredential.Kind.API_KEY, Instant.parse("2026-09-01T10:15:30Z"));

        store().put(TmdbSettings.SOURCE_ID, settings);

        assertThat(store().get(TmdbSettings.SOURCE_ID, TmdbSettings.class, TmdbSettings::fromVersionOne)).contains(settings);
    }

    @Test
    void versionOneSettingsWithoutAKnownCredentialKindAreNotConnected() {
        assertThat(TmdbSettings.fromVersionOne(null)).isEmpty();
        assertThat(TmdbSettings.fromVersionOne(Map.of())).isEmpty();
        assertThat(TmdbSettings.fromVersionOne(Map.of("connectedAt", "2026-09-01T10:15:30Z"))).isEmpty();
        assertThat(TmdbSettings.fromVersionOne(Map.of("credentialKind", "SESSION"))).isEmpty();
        assertThat(TmdbSettings.fromVersionOne(Map.of("credentialKind", "bearer"))).isEmpty();
    }

    @Test
    void aMissingOrDamagedVersionOneConnectionTimeKeepsTheConnection() {
        assertThat(TmdbSettings.fromVersionOne(Map.of("credentialKind", "BEARER")))
                .contains(new TmdbSettings(TmdbCredential.Kind.BEARER, Instant.EPOCH));
        assertThat(TmdbSettings.fromVersionOne(Map.of("credentialKind", "BEARER", "connectedAt", "yesterday")))
                .contains(new TmdbSettings(TmdbCredential.Kind.BEARER, Instant.EPOCH));
    }

    @Test
    void theVersionOneFixtureConvertsAndRoundTripsThroughTheStore() throws IOException {
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), dir.resolve("sources.json"));

        TmdbSettings converted = store().get(TmdbSettings.SOURCE_ID, TmdbSettings.class, TmdbSettings::fromVersionOne)
                .orElseThrow();

        assertThat(converted).isEqualTo(new TmdbSettings(TmdbCredential.Kind.API_KEY, Instant.parse("2026-09-02T10:00:00Z")));
        assertThat(store().get(TmdbSettings.SOURCE_ID, TmdbSettings.class, TmdbSettings::fromVersionOne)).contains(converted);
    }
}
