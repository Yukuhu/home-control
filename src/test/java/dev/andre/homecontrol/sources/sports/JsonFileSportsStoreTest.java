package dev.andre.homecontrol.sources.sports;

import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFileSportsStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("sports.json");
    }

    @Test
    void aMissingFileIsEmpty() {
        assertThat(new JsonFileSportsStore(file()).load()).isEqualTo(SportsSettings.empty());
    }

    @Test
    void readsTheDocumentedShape() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sports/sports-v1.json")) {
            Files.write(file(), in.readAllBytes());
        }

        SportsSettings settings = new JsonFileSportsStore(file()).load();

        assertThat(settings.timeZone()).isEqualTo("Europe/Berlin");
        assertThat(settings.calendars()).hasSize(2);
        SportsSettings.CalendarEntry first = settings.calendars().get(0);
        assertThat(first.id()).isEqualTo("c-3f9a1c2b7d4e");
        assertThat(first.label()).isEqualTo("Bundesliga 2026/27");
        assertThat(first.host()).isEqualTo("calendar.example.org");
        assertThat(first.provider()).isEqualTo("dazn");
        assertThat(first.addedAt()).isEqualTo(Instant.parse("2026-09-16T10:00:00Z"));
        SportsSettings.CalendarEntry second = settings.calendars().get(1);
        assertThat(second.id()).isEqualTo("c-00000000000a");
        assertThat(second.provider()).isNull();
        assertThat(second.addedAt()).isEqualTo(Instant.EPOCH);

        assertThat(settings.keyKind()).isEqualTo(SportsSettings.KeyKind.FREE);
        assertThat(settings.competitions()).hasSize(2);
        SportsSettings.CompetitionEntry comp1 = settings.competitions().get(0);
        assertThat(comp1.leagueId()).isEqualTo("4331");
        assertThat(comp1.name()).isEqualTo("German Bundesliga");
        SportsSettings.CompetitionEntry comp2 = settings.competitions().get(1);
        assertThat(comp2.name()).isEqualTo("Competition 4328");
        assertThat(comp2.badge()).isNull();
        assertThat(comp2.provider()).isNull();
    }

    @Test
    void roundTripsAndWritesPrettyJson() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sports/sports-v1.json")) {
            Files.write(file(), in.readAllBytes());
        }
        JsonFileSportsStore store = new JsonFileSportsStore(file());
        SportsSettings loaded = store.load();

        store.save(loaded);

        assertThat(store.load()).isEqualTo(loaded);
        String text = Files.readString(file(), StandardCharsets.UTF_8);
        assertThat(text).contains("\"version\" : 1").contains("\"theSportsDb\" : {").contains("\"key\" : \"free\"");
        assertThat(text).doesNotContain("http://").doesNotContain("https://calendar");
    }

    @Test
    void writesAtomicallyAndLeavesNoTempFiles() throws IOException {
        JsonFileSportsStore store = new JsonFileSportsStore(file());
        store.save(SportsSettings.empty());

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("sports.json");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"version\":2}"})
    void malformedFilesAreNamedErrors(String content) throws IOException {
        Files.writeString(file(), content);

        assertThatThrownBy(() -> new JsonFileSportsStore(file()).load())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file().toString())
                .hasMessageContaining("fix or delete it");
    }

    @Test
    void newerVersionNamesTheReason() throws IOException {
        Files.writeString(file(), "{\"version\":2}");
        assertThatThrownBy(() -> new JsonFileSportsStore(file()).load())
                .hasMessageContaining("newer Home Control");
    }

    @Test
    void invalidTimeZonesAreDropped() throws IOException {
        Files.writeString(file(), "{\"version\":1,\"timeZone\":\"Mars/Base\",\"calendars\":[],\"theSportsDb\":{\"key\":\"free\",\"competitions\":[]}}");

        assertThat(new JsonFileSportsStore(file()).load().timeZone()).isNull();
    }
}
