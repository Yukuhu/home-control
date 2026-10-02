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
        assertThat(bomb.length).isLessThan(100_000);
        assertThatThrownBy(() -> ThemeArchive.read(bomb)).isInstanceOf(ThemeException.class)
                .satisfies(error -> assertThat(((ThemeException) error).status()).isEqualTo(413));
    }
}
