package dev.andre.homecontrol.themes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static dev.andre.homecontrol.themes.ThemeTestPackages.*;

class ThemeArchiveBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"encrypted", "symlink", "duplicate", "truncated"})
    void refusesHostileZipMetadataBeforeReadingFiles(String attack) {
        var files = files("custom", null);
        files.put("assets/a.txt", "first".getBytes(StandardCharsets.UTF_8));
        files.put("assets/b.txt", "second".getBytes(StandardCharsets.UTF_8));
        byte[] archive = zip(files);
        ByteBuffer metadata = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int central = -1;
        for (int i = 0; i < archive.length - 46; i++) {
            if (metadata.getInt(i) == 0x02014b50) { central = i; break; }
        }
        assertThat(central).isNotNegative();
        if (attack.equals("encrypted")) metadata.putShort(central + 8, (short) (metadata.getShort(central + 8) | 1));
        if (attack.equals("symlink")) metadata.putInt(central + 38, 0120777 << 16);
        if (attack.equals("duplicate")) {
            byte[] name = "assets/b.txt".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i <= archive.length - name.length; i++) {
                if (java.util.Arrays.equals(archive, i, i + name.length, name, 0, name.length)) archive[i + 7] = 'a';
            }
        }
        byte[] malicious = attack.equals("truncated") ? java.util.Arrays.copyOf(archive, archive.length - 1) : archive;
        assertThatThrownBy(() -> ThemeArchive.read(malicious)).isInstanceOf(ThemeException.class);
    }

    @Test void enforcesCompressedLimitBeforeParsingAndExpandedLimitWhileStreaming() {
        assertThatThrownBy(() -> ThemeArchive.read(new byte[10 * 1024 * 1024 + 1]))
                .isInstanceOf(ThemeException.class).satisfies(error -> assertThat(((ThemeException) error).status()).isEqualTo(413));
        var files = files("custom", null);
        files.put("assets/one.png", new byte[15 * 1024 * 1024]);
        files.put("assets/two.png", new byte[15 * 1024 * 1024]);
        byte[] bomb = zip(files);
        assertThat(bomb).hasSizeLessThan(100_000);
        assertThatThrownBy(() -> ThemeArchive.read(bomb)).isInstanceOf(ThemeException.class)
                .satisfies(error -> assertThat(((ThemeException) error).status()).isEqualTo(413));
    }
    @Test void rejectsLongPathsWithoutOverflowingTheStack() {
        String longPath = "assets/" + "a/".repeat(20_000) + "image.png";
        assertThatThrownBy(() -> ThemeArchive.path(longPath, false)).isInstanceOf(ThemeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"assets", "assets/images", "assets/images.photo/inside"})
    void acceptsOnlyCanonicalDirectories(String path) {
        String directory = path + "/";
        if (path.contains(".")) {
            assertThatThrownBy(() -> ThemeArchive.path(directory, true)).isInstanceOf(ThemeException.class);
        } else {
            assertThatCode(() -> ThemeArchive.path(directory, true)).doesNotThrowAnyException();
        }
    }

    @Test void acceptsEmptyDirectoriesButRejectsDirectoryContent() {
        var files = files("custom", null);
        files.put("assets/", new byte[0]);
        files.put("assets/images/", new byte[0]);
        byte[] archive = zip(files);
        assertThat(ThemeArchive.read(archive)).containsOnlyKeys("theme.json", "tokens.json", "LICENSE");
        files.put("assets/images/", new byte[] {1});
        byte[] nonemptyDirectory = zip(files);
        assertThatThrownBy(() -> ThemeArchive.read(nonemptyDirectory)).isInstanceOf(ThemeException.class)
                .hasMessageContaining("directories must be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"theme.json", "tokens.json", "LICENSE"})
    void requiresEachSourceDocument(String required) {
        var files = files("custom", null);
        files.remove(required);
        byte[] archive = zip(files);
        assertThatThrownBy(() -> ThemeArchive.read(archive)).isInstanceOf(ThemeException.class)
                .hasMessageContaining(required);
    }

}
