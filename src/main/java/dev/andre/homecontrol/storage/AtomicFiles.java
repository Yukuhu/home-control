package dev.andre.homecontrol.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Durable, atomic, owner-only writes: every file under /data is written through here. */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Writes {@code bytes} to a 0600 temp file in the target's directory, forces it to disk, moves
     * it over the target atomically and then syncs the directory, so a power cut leaves either the
     * old file or the complete new one. {@code replace} false fails if the target already exists.
     */
    public static void write(Path target, byte[] bytes, boolean replace) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Path temp = createTemp(directory, "." + target.getFileName() + "-");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (replace) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                createExclusively(temp, target);
            }
            syncDirectory(directory);
        } finally {
            try {
                Files.deleteIfExists(temp); // gone after a move; the spare name after a link; a leftover after a failure
            } catch (IOException _) {
                // a stray temp file is harmless; the next write creates a new one
            }
        }
    }

    /**
     * An atomic rename replaces an existing target on Linux even without {@code REPLACE_EXISTING}; a hard link never
     * does. Where the file system has no hard links, a plain move still refuses an existing target, though a second
     * writer racing it could slip in between its check and its rename.
     */
    private static void createExclusively(Path temp, Path target) throws IOException {
        try {
            Files.createLink(target, temp);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (UnsupportedOperationException | IOException _) {
            Files.move(temp, target);
        }
    }

    public static Path createTemp(Path directory, String prefix) throws IOException {
        Files.createDirectories(directory);
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(directory, prefix, ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(directory, prefix, ".tmp");
    }

    /** Best effort: makes the rename itself durable where the platform allows opening a directory. */
    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException _) {
            // not supported here (e.g. Windows); the file itself is already on disk
        }
    }
}
