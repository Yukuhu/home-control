package dev.andre.homecontrol.storage;

import java.nio.file.Path;

/** The data directory holds something that the user this process runs as may not read and write. */
public final class UnusableDataDirectoryException extends RuntimeException {

    private final transient Path directory;
    private final transient Path unusable;

    public UnusableDataDirectoryException(Path directory, Path unusable, String user) {
        super("The data directory " + directory + " cannot be used: this process, running as " + user
                + ", may not read and write " + unusable);
        this.directory = directory;
        this.unusable = unusable;
    }

    public Path directory() {
        return directory;
    }

    public Path unusable() {
        return unusable;
    }

    /** What to do about it, for whoever starts the app. */
    public String remedy() {
        return "The container image runs as user 1000, not as root as older versions did. "
                + "Hand a directory that an older version wrote over to that user, on the host:\n\n"
                + "    chown -R 1000:1000 <the directory mounted at " + directory + ">\n\n"
                + "To keep running as root instead, start the container with --user 0:0 "
                + "(in a Compose file: user: \"0:0\").";
    }
}
