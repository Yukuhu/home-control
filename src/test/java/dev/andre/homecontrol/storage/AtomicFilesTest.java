package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class AtomicFilesTest {

    @TempDir
    Path dir;

    @Test
    void writesAFileOnlyItsOwnerCanRead() throws Exception {
        assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        Path target = dir.resolve("devices.json");

        AtomicFiles.write(target, "[]".getBytes(), true);

        assertThat(target).hasContent("[]");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
    }

    @Test
    void replacesAnExistingFileWhenAsked() throws Exception {
        Path target = dir.resolve("pinned.json");
        Files.writeString(target, "old");

        AtomicFiles.write(target, "new".getBytes(), true);

        assertThat(target).hasContent("new");
    }

    @Test
    void neverReplacesAnExistingFileOtherwise() throws Exception {
        Path target = dir.resolve("secret.key");
        Files.writeString(target, "first");

        assertThatThrownBy(() -> AtomicFiles.write(target, "second".getBytes(), false))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(target).hasContent("first");
    }

    @Test
    void leavesNoTempFileBehind() throws Exception {
        Path target = dir.resolve("sources.json");
        AtomicFiles.write(target, "{}".getBytes(), true);
        assertThatThrownBy(() -> AtomicFiles.write(target, "{}".getBytes(), false))
                .isInstanceOf(FileAlreadyExistsException.class);

        try (var files = Files.list(dir)) {
            assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("sources.json");
        }
    }

    @Test
    void createsTheTargetsDirectory() throws Exception {
        Path target = dir.resolve("nested/devices.json");

        AtomicFiles.write(target, "[]".getBytes(), true);

        assertThat(target).hasContent("[]");
    }
}
