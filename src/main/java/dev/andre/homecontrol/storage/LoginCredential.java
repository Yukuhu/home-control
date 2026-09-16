package dev.andre.homecontrol.storage;

import java.util.Objects;

/** The login password hash (PHC string) and an opaque version that changes with every new password. */
public record LoginCredential(String passwordHash, String version) {

    public LoginCredential {
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(version, "version");
    }

    @Override
    public String toString() {
        return "LoginCredential[version=" + version + "]";
    }
}
