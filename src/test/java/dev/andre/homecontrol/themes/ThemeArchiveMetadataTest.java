package dev.andre.homecontrol.themes;

import dev.andre.homecontrol.config.Json;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import static dev.andre.homecontrol.themes.ThemeTestPackages.files;
import static dev.andre.homecontrol.themes.ThemeTestPackages.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeArchiveMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {"disk", "directory-disk", "disk-count", "no-entries", "directory-offset", "directory-size",
            "entry-signature", "entry-length", "entry-disk", "local-offset", "local-signature", "method", "flags", "entry-count"})
    void rejectsInconsistentZipMetadata(String invalid) {
        byte[] archive = zip(files("custom", null));
        ByteBuffer data = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int end = archive.length - 22;
        int central = data.getInt(end + 16);
        switch (invalid) {
            case "disk" -> data.putShort(end + 4, (short) 1);
            case "directory-disk" -> data.putShort(end + 6, (short) 1);
            case "disk-count" -> data.putShort(end + 8, (short) 1);
            case "no-entries" -> data.putShort(end + 8, (short) 0).putShort(end + 10, (short) 0);
            case "directory-offset" -> data.putInt(end + 16, -1);
            case "directory-size" -> data.putInt(end + 12, 1);
            case "entry-signature" -> data.putInt(central, 0);
            case "entry-length" -> data.putShort(central + 28, (short) -1);
            case "entry-disk" -> data.putShort(central + 34, (short) 1);
            case "local-offset" -> data.putInt(central + 42, -1);
            case "local-signature" -> data.putInt(0, 0);
            case "method" -> data.putShort(central + 10, (short) 9);
            case "flags" -> data.putShort(central + 8, (short) 0x40);
            case "entry-count" -> data.putShort(end + 8, (short) 2).putShort(end + 10, (short) 2);
            default -> throw new AssertionError(invalid);
        }
        assertThatThrownBy(() -> ThemeArchive.read(archive)).isInstanceOf(ThemeException.class);
    }

    @Test void rejectsLocalFilenameThatDisagreesWithTheCentralDirectory() {
        byte[] archive = zip(files("custom", null));
        archive[30] = 'x';
        assertThatThrownBy(() -> ThemeArchive.read(archive)).isInstanceOf(ThemeException.class);
    }

    @Test void rejectsCorruptCompressedContent() {
        byte[] archive = zip(files("custom", null));
        ByteBuffer data = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int content = 30 + Short.toUnsignedInt(data.getShort(26)) + Short.toUnsignedInt(data.getShort(28));
        archive[content] = (byte) 0xff;
        assertThatThrownBy(() -> ThemeArchive.read(archive)).isInstanceOf(ThemeException.class).hasMessageContaining("damaged");
    }

    @ParameterizedTest
    @CsvSource({"formatVersion,0", "formatVersion,1.0", "formatVersion,4294967297", "themeApiVersion,2",
            "themeApiVersion,null", "themeApiVersion,4294967297", "id,42", "name,null", "version,false"})
    void rejectsInvalidManifestTypesAndVersions(String field, String value) {
        ObjectNode manifest = manifest();
        manifest.set(field, Json.MAPPER.readTree(value));
        byte[] bytes = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatThrownBy(() -> ThemeArchive.manifest(bytes, false)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "null"})
    void requiresExactlyTheManifestFields(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ThemeArchive.manifest(bytes, false)).isInstanceOf(ThemeException.class);
    }

    @Test void rejectsUnknownManifestFields() {
        ObjectNode manifest = manifest();
        manifest.put("extra", "value");
        byte[] bytes = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatThrownBy(() -> ThemeArchive.manifest(bytes, false)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @CsvSource({"id,64", "name,120", "version,64", "author,120", "description,2000", "license,200"})
    void enforcesEachMetadataLengthBound(String field, int maximum) {
        ObjectNode manifest = manifest();
        manifest.put(field, "a".repeat(maximum));
        byte[] atLimit = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatCode(() -> ThemeArchive.manifest(atLimit, false)).doesNotThrowAnyException();
        manifest.put(field, "a".repeat(maximum + 1));
        byte[] tooLong = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatThrownBy(() -> ThemeArchive.manifest(tooLong, false)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "version", "license"})
    void rejectsBlankRequiredMetadata(String field) {
        ObjectNode manifest = manifest();
        manifest.put(field, "  ");
        byte[] bytes = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatThrownBy(() -> ThemeArchive.manifest(bytes, false)).isInstanceOf(ThemeException.class);
    }

    @Test void permitsBlankOptionalMetadataAndRejectsControlCharacters() {
        ObjectNode manifest = manifest();
        manifest.put("author", "").put("description", "");
        byte[] bytes = Json.MAPPER.writeValueAsBytes(manifest);
        assertThat(ThemeArchive.manifest(bytes, false).author()).isEmpty();
        manifest.put("description", "before\nafter");
        byte[] control = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatThrownBy(() -> ThemeArchive.manifest(control, false)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Uppercase", "1starts-with-number", "has_underscore", "é"})
    void rejectsNoncanonicalIds(String id) {
        ObjectNode manifest = manifest();
        manifest.put("id", id);
        byte[] bytes = Json.MAPPER.writeValueAsBytes(manifest);
        assertThatThrownBy(() -> ThemeArchive.manifest(bytes, false)).isInstanceOf(ThemeException.class).hasMessageContaining("ID");
    }

    @Test void rejectsOversizedJsonAndMalformedUtf8BeforeParsing() {
        byte[] oversized = new byte[128 * 1024 + 1];
        Arrays.fill(oversized, (byte) ' ');
        assertThatThrownBy(() -> ThemeArchive.json(oversized)).isInstanceOf(ThemeException.class)
                .satisfies(error -> assertThat(((ThemeException) error).status()).isEqualTo(413));
        byte[] malformed = {(byte) 0xc3, 0x28};
        assertThatThrownBy(() -> ThemeArchive.json(malformed)).isInstanceOf(ThemeException.class).hasMessageContaining("UTF-8");
    }

    private static ObjectNode manifest() {
        return (ObjectNode) Json.MAPPER.readTree(files("custom", null).get("theme.json"));
    }
}
