package dev.andre.shield.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataDirectoryTest {

    @TempDir
    Path dir;

    @Test
    void createsAndVerifiesAWritableDirectoryWithoutLeavingAProbe() throws Exception {
        Path data = dir.resolve("data");

        new DataDirectory(data).verifyWritable();

        assertThat(data).isDirectory();
        try (var files = Files.list(data)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void namesTheBlockedPathWhenTheDirectoryCannotBeCreated() throws Exception {
        Path blocked = dir.resolve("not-a-directory");
        Files.writeString(blocked, "occupied");

        assertThatThrownBy(() -> new DataDirectory(blocked).verifyWritable())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(blocked.toString())
                .hasMessageContaining("bind-mounted")
                .hasCauseInstanceOf(Exception.class);
    }
}
