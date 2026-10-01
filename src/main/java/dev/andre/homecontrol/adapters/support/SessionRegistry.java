package dev.andre.homecontrol.adapters.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/** An adapter's open sessions by device id, for finding the ones a discovery announcement is about. */
public final class SessionRegistry<S> {

    private final Map<String, S> sessions = new ConcurrentHashMap<>();

    /**
     * Creates the session for {@code deviceId} and registers it in place of any before it. {@code create} gets the
     * callback the session runs when it closes; it removes exactly this session, never one opened after it.
     */
    public S open(String deviceId, Function<Runnable, S> create) {
        AtomicReference<S> self = new AtomicReference<>();
        S session = create.apply(() -> sessions.remove(deviceId, self.get()));
        self.set(session);
        sessions.put(deviceId, session);
        return session;
    }

    public List<S> matching(Predicate<S> matches) {
        return sessions.values().stream().filter(matches).toList();
    }
}
