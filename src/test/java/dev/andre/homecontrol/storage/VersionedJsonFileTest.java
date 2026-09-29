package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class VersionedJsonFileTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record Note(String text) {
    }

    @TempDir
    Path dir;

    private Path path() {
        return dir.resolve("notes.json");
    }

    private VersionedJsonFile<Note> notes() {
        return new VersionedJsonFile<>(path(), "the notes", 3, () -> new Note(""),
                root -> new Note(root.path("text").asString()),
                note -> MAPPER.createObjectNode().put("text", note.text()))
                .migrate(1, v1 -> MAPPER.createObjectNode().put("version", 2).put("body", v1.path("note").asString()))
                .migrate(2, v2 -> MAPPER.createObjectNode().put("version", 3).put("text", v2.path("body").asString()));
    }

    @Test
    void aMissingFileIsEmptyAndNotCreated() {
        assertThat(notes().read()).isEqualTo(new Note(""));
        assertThat(path()).doesNotExist();
    }

    @Test
    void anUpdateWritesTheVersionFirstAndIsReadBack() throws Exception {
        VersionedJsonFile<Note> notes = notes();

        notes.update(note -> new Note("milk"));

        JsonNode root = MAPPER.readTree(Files.readAllBytes(path()));
        List<String> names = new ArrayList<>(root.propertyNames());
        assertThat(names).containsExactly("version", "text");
        assertThat(root.path("version").asInt()).isEqualTo(3);
        assertThat(notes().read()).isEqualTo(new Note("milk"));
    }

    @Test
    void theSnapshotIsTheValueLastWritten() throws Exception {
        VersionedJsonFile<Note> notes = notes();
        notes.write(new Note("milk"));

        Files.writeString(path(), "{\"version\":3,\"text\":\"changed behind its back\"}");

        assertThat(notes.read()).isEqualTo(new Note("milk"));
    }

    @Test
    void anOlderVersionMigratesStepByStepAndKeepsItsOriginalOnce() throws Exception {
        Files.writeString(path(), "{\"version\":1,\"note\":\"bread\"}");

        assertThat(notes().read()).isEqualTo(new Note("bread"));

        assertThat(dir.resolve("notes.v1.json")).hasContent("{\"version\":1,\"note\":\"bread\"}");
        assertThat(MAPPER.readTree(Files.readAllBytes(path())).path("version").asInt()).isEqualTo(3);

        Files.writeString(path(), "{\"version\":1,\"note\":\"later\"}");
        assertThat(notes().read()).isEqualTo(new Note("later"));
        assertThat(dir.resolve("notes.v1.json")).hasContent("{\"version\":1,\"note\":\"bread\"}");
    }

    @Test
    void aNewerVersionIsRefusedByName() throws Exception {
        Files.writeString(path(), "{\"version\":4}");

        assertThatThrownBy(notes()::read)
                .isInstanceOf(StorageException.class)
                .hasMessage("the notes in " + path() + " was written by a newer Home Control (version 4);"
                        + " upgrade Home Control or restore a backup");
    }

    @Test
    void unreadableJsonNamesTheFile() throws Exception {
        Files.writeString(path(), "not json");

        assertThatThrownBy(notes()::read)
                .isInstanceOf(StorageException.class)
                .hasMessage("Could not read the notes in " + path() + "; fix or delete it");
    }

    @Test
    void aVersionWithoutAStepIsUnreadable() throws Exception {
        Files.writeString(path(), "{\"version\":0}");

        assertThatThrownBy(notes()::read)
                .isInstanceOf(StorageException.class)
                .hasMessage("Could not read the notes in " + path() + "; fix or delete it");
    }

    @Test
    void aDocumentWithoutAVersionIsUnreadable() throws Exception {
        Files.writeString(path(), "{\"text\":\"milk\"}");

        assertThatThrownBy(notes()::read)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("fix or delete it");
    }

    @Test
    void aReaderThatRejectsTheDocumentIsUnreadable() throws Exception {
        Files.writeString(path(), "{\"version\":3}");
        VersionedJsonFile<Note> strict = new VersionedJsonFile<>(path(), "the notes", 3, () -> new Note(""),
                root -> {
                    throw new IllegalArgumentException("text is required");
                }, note -> MAPPER.createObjectNode());

        assertThatThrownBy(strict::read).isInstanceOf(StorageException.class).hasMessageContaining("fix or delete it");
    }

    @Test
    void aCustomVersionLookupReadsFilesThatPredateTheField() throws Exception {
        Files.writeString(path(), "[\"eggs\"]");
        VersionedJsonFile<Note> notes = new VersionedJsonFile<>(path(), "the notes", 2, () -> new Note(""),
                root -> new Note(root.path("text").asString()),
                note -> MAPPER.createObjectNode().put("text", note.text()))
                .versionOf(root -> root.isArray() ? 1 : VersionedJsonFile.versionField(root))
                .migrate(1, array -> MAPPER.createObjectNode().put("text", array.path(0).asString()));

        assertThat(notes.read()).isEqualTo(new Note("eggs"));
        assertThat(dir.resolve("notes.v1.json")).hasContent("[\"eggs\"]");
    }

    @Test
    void deleteRemovesTheFileAndForgetsTheSnapshot() {
        VersionedJsonFile<Note> notes = notes();
        notes.write(new Note("milk"));

        notes.delete();

        assertThat(path()).doesNotExist();
        assertThat(notes.read()).isEqualTo(new Note(""));
    }

    @Test
    void writesAreOwnerOnly() throws Exception {
        assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");

        notes().write(new Note("milk"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path()))).isEqualTo("rw-------");
    }
}
