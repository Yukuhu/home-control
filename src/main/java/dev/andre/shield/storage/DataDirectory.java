package dev.andre.shield.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
