package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Holds one live connection. Every connection leaves the slot exactly once, replaced, taken or refused, and the slot
 * closes it then; callers never close a connection they put in. After {@link #close()} the slot closes anything set
 * into it at once.
 */
public final class ConnectionSlot<C extends AutoCloseable> {

    private static final Logger log = LoggerFactory.getLogger(ConnectionSlot.class);

    private final String name;
    private C current; // guarded by this
    private boolean closed; // guarded by this

    public ConnectionSlot(String name) {
        this.name = name;
    }

    /** Makes {@code connection} the current one and closes the one before; false, and it is closed, once the slot is. */
    public boolean set(C connection) {
        C givenUp;
        boolean accepted;
        synchronized (this) {
            accepted = !closed;
            givenUp = accepted ? current : connection;
            if (accepted) {
                current = connection;
            }
        }
        closeQuietly(givenUp);
        return accepted;
    }

    public synchronized Optional<C> current() {
        return Optional.ofNullable(current);
    }

    /** Removes and closes {@code expected} if it is still the current connection. */
    public boolean takeIf(C expected) {
        synchronized (this) {
            if (current == null || current != expected) {
                return false;
            }
            current = null;
        }
        closeQuietly(expected);
        return true;
    }

    /** Removes and closes the current connection; every later {@link #set} is refused. */
    public void close() {
        C taken;
        synchronized (this) {
            closed = true;
            taken = current;
            current = null;
        }
        closeQuietly(taken);
    }

    private void closeQuietly(C connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception e) {
            log.debug("{}: closing a connection failed: {}", name, e.getMessage());
        }
    }
}
