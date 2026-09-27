package dev.andre.homecontrol.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public final class DataDirectory {

    private final Path path;

    public DataDirectory(Path path) {
        this.path = path;
    }

    public void verifyWritable() {
        try {
            Files.createDirectories(path);
            Path probe = Files.createTempFile(path, ".shield-write-check-", ".tmp");
            Files.delete(probe);
        } catch (IOException e) {
            throw new StorageException(
                    "Shield data directory is not writable: " + path
                            + "; check that /data is bind-mounted and writable",
                    e);
        }
    }

    /**
     * Fails if the directory holds anything this process may not read and write. The image ran
     * as root once and does not any more, so a directory an older version wrote belongs to root.
     * Without this check the first sign of that is a key file that "must hold 32 bytes".
     * A directory that does not exist yet is usable: it is created when something is stored.
     */
    public void verifyUsable() {
        if (Files.notExists(path)) {
            return;
        }
        firstUnusable().ifPresent(unusable -> {
            throw new UnusableDataDirectoryException(path, unusable, System.getProperty("user.name"));
        });
    }

    private Optional<Path> firstUnusable() {
        if (!Files.isDirectory(path)) {
            return Optional.of(path);
        }
        try (Stream<Path> entries = Files.walk(path)) {
            return entries.filter(entry -> !usable(entry)).findFirst();
        } catch (IOException | UncheckedIOException _) {
            // A directory that cannot be listed is reported as a whole.
            return Optional.of(path);
        }
    }

    private static boolean usable(Path entry) {
        boolean listable = !Files.isDirectory(entry) || Files.isExecutable(entry);
        return Files.isReadable(entry) && Files.isWritable(entry) && listable;
    }
}
