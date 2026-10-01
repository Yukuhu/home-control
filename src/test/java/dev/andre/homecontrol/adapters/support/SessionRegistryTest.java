package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    /** Runs the registry's callback when it closes, as a real session does. */
    private record Session(String host, Runnable onClose) {
        void close() {
            onClose.run();
        }
    }

    private final SessionRegistry<Session> sessions = new SessionRegistry<>();

    @Test
    void anOpenSessionIsFoundByWhatItAnswersTo() {
        Session kitchen = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));
        sessions.open("tv-2", onClose -> new Session("192.0.2.20", onClose));

        assertThat(sessions.matching(session -> session.host().equals("192.0.2.10"))).containsExactly(kitchen);
    }

    @Test
    void aClosedSessionIsGone() {
        Session session = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));

        session.close();

        assertThat(sessions.matching(any -> true)).isEmpty();
    }

    @Test
    void aReplacedSessionThatClosesLaterLeavesItsReplacement() {
        Session replaced = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));
        Session replacement = sessions.open("tv-1", onClose -> new Session("192.0.2.10", onClose));

        replaced.close();

        assertThat(sessions.matching(any -> true)).containsExactly(replacement);
    }
}
