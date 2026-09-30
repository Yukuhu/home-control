package dev.andre.homecontrol.sources.sports.settings;

import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading odd {@code sports.json} content and failing writes. */
class JsonFileSportsStoreEdgeCaseTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("sports.json");
    }

    @Test
    void nonTextOrBlankTimeZonesAreUnset() throws IOException {
        Files.writeString(file(), "{\"version\":1,\"timeZone\":42}");
        assertThat(new JsonFileSportsStore(file()).load().timeZone()).isNull();

        Files.writeString(file(), "{\"version\":1,\"timeZone\":\" \"}");
        assertThat(new JsonFileSportsStore(file()).load().timeZone()).isNull();
    }

    @Test
    void invalidAndRepeatedCompetitionsAreSkipped() throws IOException {
        Files.writeString(file(), """
                {"version":1,"theSportsDb":{"key":"personal","competitions":[
                  {"leagueId":"4331","name":"German Bundesliga"},
                  {"leagueId":"not-a-number","name":"Broken"},
                  {"leagueId":"4331","name":"Duplicate"},
                  {"leagueId":"4328","name":"English Premier League"}
                ]}}
                """);

        SportsSettings settings = new JsonFileSportsStore(file()).load();

        assertThat(settings.keyKind()).isEqualTo(SportsSettings.KeyKind.PERSONAL);
        assertThat(settings.competitions()).extracting(SportsSettings.CompetitionEntry::name)
                .containsExactly("German Bundesliga", "English Premier League");
    }

    @Test
    void savesEveryOptionalCompetitionField() {
        JsonFileSportsStore store = new JsonFileSportsStore(file());
        SportsSettings settings = SportsSettings.empty().withKeyKind(SportsSettings.KeyKind.PERSONAL)
                .withCompetitions(List.of(new SportsSettings.CompetitionEntry("4331", "German Bundesliga", "Soccer",
                        "Germany", URI.create("https://r2.thesportsdb.com/badge.png"), "dazn",
                        Instant.parse("2026-09-16T10:05:00Z"))));

        store.save(settings);

        assertThat(store.load()).isEqualTo(settings);
    }

    @Test
    void aFailedMoveLeavesNoTempFileBehind() throws IOException {
        Files.createDirectories(file().resolve("occupied"));
        JsonFileSportsStore store = new JsonFileSportsStore(file());
        SportsSettings empty = SportsSettings.empty();

        assertThatThrownBy(() -> store.save(empty))
                .isInstanceOf(StorageException.class)
                .hasMessageStartingWith("Could not write sports settings to " + file() + ";");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("sports.json");
        }
    }

    @Test
    void anUnwritableFolderIsAStorageError() throws IOException {
        Path notAFolder = Files.writeString(dir.resolve("plain-file"), "x");
        JsonFileSportsStore store = new JsonFileSportsStore(notAFolder.resolve("sports.json"));
        SportsSettings empty = SportsSettings.empty();

        assertThatThrownBy(() -> store.save(empty))
                .isInstanceOf(StorageException.class)
                .hasMessageStartingWith("Could not write sports settings to ");
    }
}
