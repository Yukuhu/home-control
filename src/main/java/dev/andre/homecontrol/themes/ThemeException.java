package dev.andre.homecontrol.themes;

/** A safe, user-facing package error, independent of HTTP infrastructure. */
public final class ThemeException extends RuntimeException {
    private final int status;
    public ThemeException(int status, String message) { super(message); this.status = status; }
    public ThemeException(int status, String message, Throwable cause) { super(message, cause); this.status = status; }
    public int status() { return status; }
    static ThemeException invalid(String message) { return new ThemeException(400, message); }
    static ThemeException tooLarge(String message) { return new ThemeException(413, message); }
}
