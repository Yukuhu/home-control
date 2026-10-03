package dev.andre.homecontrol.storage;

import com.sun.security.auth.module.UnixSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The user and group this process runs as. Inside a container the user often has no name. */
public record ProcessUser(String name, long uid, long gid) {

    /** On Linux, a process's own directory belongs to the user and group it runs as. */
    private static final Path SELF = Path.of("/proc/self");

    public static ProcessUser current() {
        return of(SELF);
    }

    /**
     * The owner of {@code self}, a file that belongs to this process. Without one (not Linux) the system's account
     * of the user: its gid is the user's primary group, which a container started with another group does not run as.
     */
    static ProcessUser of(Path self) {
        String name = System.getProperty("user.name");
        try {
            return new ProcessUser(name, id(self, "unix:uid"), id(self, "unix:gid"));
        } catch (IOException | UnsupportedOperationException _) {
            UnixSystem system = new UnixSystem();
            return new ProcessUser(name, system.getUid(), system.getGid());
        }
    }

    private static long id(Path file, String attribute) throws IOException {
        return ((Number) Files.getAttribute(file, attribute)).longValue();
    }

    /** "ubuntu (uid 1000, gid 1000)", or "uid 1001, gid 1001" for a user the system has no name for. */
    String describe() {
        String ids = "uid " + uid + ", gid " + gid;
        return name == null || name.isBlank() || name.equals("?") ? ids : name + " (" + ids + ")";
    }

    /** The owner to hand a file to, as {@code chown} takes it. */
    String owner() {
        return uid + ":" + gid;
    }
}
