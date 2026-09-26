package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFilePinStoreTest {

    @TempDir
    Path dir;

    private JsonFilePinStore store(String fileName) {
        return new JsonFilePinStore(dir.resolve(fileName));
    }

    @Test
    void aMissingFileHasNoPins() {
        assertThat(store("pinned.json").load()).isEmpty();
    }

    @Test
    void readsTheDocumentedShape() throws IOException {
        Path file = dir.resolve("pinned.json");
        try (InputStream in = getClass().getResourceAsStream("/fixtures/pinned/pinned-v1.json")) {
            Files.write(file, in.readAllBytes());
        }

        List<Pin> pins = new JsonFilePinStore(file).load();

        assertThat(pins).hasSize(3);
        Pin netflix = pins.get(0);
        assertThat(netflix.id()).isEqualTo("p-3f9a1c2b7d4e");
        assertThat(netflix.url()).isEqualTo(URI.create("https://www.netflix.com/title/80057281"));
        assertThat(netflix.service()).isEqualTo("netflix");
        assertThat(netflix.title()).isEqualTo("Stranger Things");
        assertThat(netflix.subtitle()).isEqualTo("Netflix");
        assertThat(netflix.artwork()).isEqualTo(URI.create("https://image.tmdb.org/t/p/w342/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"));
        assertThat(netflix.kind()).isEqualTo(ContentKind.VIDEO);
        assertThat(netflix.upgradeOf()).isEqualTo("tmdb/tv-66732");
        assertThat(netflix.createdAt()).isEqualTo(Instant.parse("2026-09-16T10:00:00Z"));

        Pin dazn = pins.get(1);
        assertThat(dazn.id()).isEqualTo("p-00000000000a");
        assertThat(dazn.service()).isEqualTo("dazn");
        assertThat(dazn.subtitle()).isNull();
        assertThat(dazn.artwork()).isNull();
        assertThat(dazn.upgradeOf()).isNull();

        Pin evilArt = pins.get(2);
        assertThat(evilArt.id()).isEqualTo("p-00000000000c");
        assertThat(evilArt.artwork()).isNull();
        assertThat(evilArt.upgradeOf()).isNull();

        assertThat(pins).extracting(Pin::id).doesNotContain("p-00000000000b");
    }

    @Test
    void roundTripsAndWritesPrettyJson() throws IOException {
        Path file = dir.resolve("pinned.json");
        JsonFilePinStore store = new JsonFilePinStore(file);
        List<Pin> pins = List.of(new Pin("p-aaaaaaaaaaaa", URI.create("https://www.netflix.com/title/1"),
                "netflix", "A Title", "Netflix", null, ContentKind.VIDEO, null, Instant.parse("2026-09-16T10:00:00Z")));

        store.save(pins);
        List<Pin> loaded = store.load();
        store.save(loaded);

        assertThat(store.load()).isEqualTo(loaded);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).contains("\"version\" : 1").contains("\"pins\" : [");
    }

    @Test
    void writesAtomicallyAndLeavesNoTempFiles() throws IOException {
        JsonFilePinStore store = store("pinned.json");
        store.save(List.of());

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("pinned.json");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"version\":2,\"pins\":[]}"})
    void malformedFilesAreNamedErrors(String content) throws IOException {
        Path file = dir.resolve("pinned.json");
        Files.writeString(file, content);

        var preparedReceiver104 = new JsonFilePinStore(file);
        assertThatThrownBy(() -> preparedReceiver104.load())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("fix or delete it");

        if (content.contains("\"version\":2")) {
            assertThatThrownBy(() -> new JsonFilePinStore(file).load())
                    .hasMessageContaining("newer Home Control");
        }
    }

    @Test
    void duplicateIdsKeepTheFirst() {
        Path file = dir.resolve("pinned.json");
        String json = """
                {"version":1,"pins":[
                  {"id":"p-aaaaaaaaaaaa","url":"https://example.org/1","title":"First","kind":"VIDEO"},
                  {"id":"p-aaaaaaaaaaaa","url":"https://example.org/2","title":"Second","kind":"VIDEO"}
                ]}""";
        try {
            Files.writeString(file, json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<Pin> pins = new JsonFilePinStore(file).load();

        assertThat(pins).hasSize(1);
        assertThat(pins.get(0).title()).isEqualTo("First");
    }
}
