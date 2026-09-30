package org.bluez;

import org.freedesktop.dbus.exceptions.DBusExecutionException;

/**
 * Test-only errors with BlueZ's wire names. dbus-java serializes runtime exception class names,
 * replacing '$' with '.', but wraps bluez-dbus's checked exceptions in a nameless generic error.
 */
public final class Error {
    private Error() { }

    public static final class AlreadyExists extends DBusExecutionException {
        public AlreadyExists(String message) { super(message); }
    }

    public static final class AlreadyConnected extends DBusExecutionException {
        public AlreadyConnected(String message) { super(message); }
    }

    public static final class NotConnected extends DBusExecutionException {
        public NotConnected(String message) { super(message); }
    }

    public static final class InProgress extends DBusExecutionException {
        public InProgress(String message) { super(message); }
    }

    public static final class AuthenticationRejected extends DBusExecutionException {
        public AuthenticationRejected(String message) { super(message); }
    }

    public static final class ConnectionAttemptFailed extends DBusExecutionException {
        public ConnectionAttemptFailed(String message) { super(message); }
    }

    public static final class Failed extends DBusExecutionException {
        public Failed(String message) { super(message); }
    }
}
