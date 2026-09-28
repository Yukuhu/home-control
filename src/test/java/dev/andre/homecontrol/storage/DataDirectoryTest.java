package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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

        var dataDirectory = new DataDirectory(blocked);
        assertThatThrownBy(dataDirectory::verifyWritable)
                .isInstanceOf(StorageException.class)
                .hasMessageContaining(blocked.toString())
                .hasMessageContaining("bind-mounted")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void aDirectoryThatDoesNotExistYetIsUsableAndIsNotCreated() {
        Path data = dir.resolve("data");

        assertThatCode(new DataDirectory(data)::verifyUsable).doesNotThrowAnyException();

        assertThat(data).doesNotExist();
    }

    @Test
    void aDirectoryWhoseFilesThisProcessMayReadAndWriteIsUsable() throws Exception {
        Files.writeString(dir.resolve("devices.json"), "[]");
        Files.writeString(Files.createDirectory(dir.resolve("nested")).resolve("secret.key"), "key");

        assertThatCode(new DataDirectory(dir)::verifyUsable).doesNotThrowAnyException();
    }

    @Test
    void aFileOfAnotherOwnerIsNamedWithTheWayToHandItOver() throws Exception {
        assumeFalse(runsAsRoot(), "root may read and write every file");
        Path key = Files.writeString(dir.resolve("secret.key"), "key");
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("---------"));

        assertThatThrownBy(new DataDirectory(dir)::verifyUsable)
                .isInstanceOfSatisfying(UnusableDataDirectoryException.class, failure -> {
                    assertThat(failure.directory()).isEqualTo(dir);
                    assertThat(failure.unusable()).isEqualTo(key);
                    assertThat(failure).hasMessageContaining(dir.toString()).hasMessageContaining(key.toString())
                            .hasMessageContaining(System.getProperty("user.name"));
                    assertThat(failure.remedy()).contains("chown -R 1000:1000").contains("--user 0:0")
                            .contains("mounted at " + dir);
                });
    }

    @Test
    void aFailedStartSaysWhatIsWrongAndWhatToDoInsteadOfAStackTrace() {
        var failure = new UnusableDataDirectoryException(Path.of("/data"), Path.of("/data/secret.key"), "ubuntu");

        var analysis = new UnusableDataDirectoryFailureAnalyzer()
                .analyze(new IllegalStateException("the context did not start", failure));

        assertThat(analysis.getDescription()).isEqualTo("The data directory /data cannot be used: "
                + "this process, running as ubuntu, may not read and write /data/secret.key.");
        assertThat(analysis.getAction()).isEqualTo(failure.remedy());
        assertThat(analysis.getCause()).isSameAs(failure);
    }

    @Test
    void aFileThatCanBeReadButNotWrittenIsNotUsable() throws Exception {
        assumeFalse(runsAsRoot(), "root may read and write every file");
        Path devices = Files.writeString(dir.resolve("devices.json"), "[]");
        Files.setPosixFilePermissions(devices, PosixFilePermissions.fromString("r--r--r--"));

        assertThatThrownBy(new DataDirectory(dir)::verifyUsable).hasMessageContaining(devices.toString());
    }

    @Test
    void aDirectoryThatCannotBeWrittenToIsNotUsable() throws Exception {
        assumeFalse(runsAsRoot(), "root may read and write every file");
        Path data = Files.createDirectory(dir.resolve("data"));
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(new DataDirectory(data)::verifyUsable)
                    .isInstanceOf(UnusableDataDirectoryException.class)
                    .hasMessageContaining(data.toString());
        } finally {
            Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aPathThatIsNotADirectoryIsNotUsable() throws Exception {
        Path blocked = Files.writeString(dir.resolve("not-a-directory"), "occupied");

        assertThatThrownBy(new DataDirectory(blocked)::verifyUsable)
                .isInstanceOf(UnusableDataDirectoryException.class)
                .hasMessageContaining(blocked.toString());
    }

    @Test
    void resolvesItsFilesInsideItself() {
        DataDirectory data = new DataDirectory(dir);

        assertThat(data.path()).isEqualTo(dir);
        assertThat(data.resolve(DataDirectory.SECRETS)).isEqualTo(dir.resolve("secrets.json"));
        assertThat(List.of(DataDirectory.KEYSTORE, DataDirectory.DEVICES, DataDirectory.SECRETS,
                DataDirectory.SECRET_KEY, DataDirectory.SOURCES, DataDirectory.SPORTS, DataDirectory.PINNED,
                DataDirectory.YOUTUBE_QUOTA)).containsExactly("keystore.p12", "devices.json", "secrets.json",
                "secret.key", "sources.json", "sports.json", "pinned.json", "youtube-quota.json");
    }

    private static boolean runsAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }
}
