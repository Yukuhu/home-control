package dev.andre.homecontrol.adapters.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * One device's loop on its own {@link SessionLoop}: connect with a doubling {@link Backoff}, then poll at the delay the
 * link chooses. An {@link IOException} from a poll means the device is gone; a runtime failure is logged and polling
 * continues. A link handles its protocol's own refusals. All {@link Link} callbacks run on the loop thread.
 */
public final class ReconnectingPoller implements AutoCloseable {

    public interface Link {
        void connect() throws IOException;

        void poll() throws IOException;

        Duration nextPollDelay();

        void disconnected(Exception cause);
    }

    private static final Logger log = LoggerFactory.getLogger(ReconnectingPoller.class);

    private final String name;
    private final Link link;
    private final Backoff backoff;
    private final SessionLoop loop;

    private volatile boolean connected;
    private volatile boolean closed;

    public ReconnectingPoller(String name, Duration initialBackoff, Duration maxBackoff, Link link) {
        this.name = name;
        this.link = link;
        this.backoff = new Backoff(initialBackoff, maxBackoff);
        this.loop = new SessionLoop(name);
    }

    public void start() {
        loop.execute(this::attemptConnect);
    }

    public boolean connected() {
        return connected && !closed;
    }

    public void pollNow() {
        loop.execute(() -> {
            if (connected) {
                loop.cancelPending();
                pollOnce();
            }
        });
    }

    public void reconnectNow() {
        loop.execute(() -> {
            if (!connected) {
                loop.cancelPending();
                backoff.reset();
                attemptConnect();
            }
        });
    }

    private void attemptConnect() {
        if (closed) {
            return;
        }
        try {
            link.connect();
            connected = true;
            backoff.reset();
            loop.schedule(this::pollOnce, link.nextPollDelay());
        } catch (IOException | RuntimeException e) {
            connected = false;
            link.disconnected(e);
            loop.schedule(this::attemptConnect, backoff.next());
        }
    }

    private void pollOnce() {
        if (closed || !connected) {
            return;
        }
        try {
            link.poll();
        } catch (IOException e) {
            connected = false;
            link.disconnected(e);
            loop.schedule(this::attemptConnect, backoff.next());
            return;
        } catch (RuntimeException e) {
            log.debug("{}: poll failed: {}", name, e.getMessage());
        }
        loop.schedule(this::pollOnce, link.nextPollDelay());
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        loop.close();
    }
}
