package dev.andre.homecontrol.storage;

import com.sun.security.auth.module.UnixSystem;

/** The user and group this process runs as. Inside a container the user often has no name. */
public record ProcessUser(String name, long uid, long gid) {

    public static ProcessUser current() {
        UnixSystem system = new UnixSystem();
        return new ProcessUser(system.getUsername(), system.getUid(), system.getGid());
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
