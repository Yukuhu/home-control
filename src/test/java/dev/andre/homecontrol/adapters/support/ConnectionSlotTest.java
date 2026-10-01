package dev.andre.homecontrol.adapters.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConnectionSlotTest {

    /** Counts how often it was closed. */
    private static class Connection implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
        }
    }

    private final ConnectionSlot<Connection> slot = new ConnectionSlot<>("test");

    @Test
    void holdsTheConnectionSetIntoIt() {
        Connection connection = new Connection();

        assertThat(slot.set(connection)).isTrue();

        assertThat(slot.current()).containsSame(connection);
        assertThat(connection.closes).hasValue(0);
    }

    @Test
    void setClosesTheConnectionItReplaces() {
        Connection first = new Connection();
        Connection second = new Connection();
        slot.set(first);

        slot.set(second);

        assertThat(first.closes).hasValue(1);
        assertThat(second.closes).hasValue(0);
        assertThat(slot.current()).containsSame(second);
    }

    @Test
    void takeIfClosesOnlyTheConnectionItExpects() {
        Connection current = new Connection();
        Connection stale = new Connection();
        slot.set(current);

        assertThat(slot.takeIf(stale)).isFalse();
        assertThat(current.closes).hasValue(0);
        assertThat(slot.takeIf(current)).isTrue();
        assertThat(slot.takeIf(current)).isFalse();

        assertThat(current.closes).hasValue(1);
        assertThat(slot.current()).isEmpty();
    }

    @Test
    void closeClosesTheCurrentConnectionAndRefusesLaterOnes() {
        Connection current = new Connection();
        Connection late = new Connection();
        slot.set(current);

        slot.close();
        boolean accepted = slot.set(late);
        slot.close();

        assertThat(current.closes).hasValue(1);
        assertThat(accepted).isFalse();
        assertThat(late.closes).hasValue(1);
        assertThat(slot.current()).isEmpty();
    }

    @Test
    void racingTakesCloseAConnectionExactlyOnce() {
        for (int round = 0; round < 200; round++) {
            ConnectionSlot<Connection> racing = new ConnectionSlot<>("race");
            Connection connection = new Connection();
            racing.set(connection);
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                pool.submit(() -> racing.takeIf(connection));
                pool.submit(racing::close);
                pool.submit(() -> racing.takeIf(connection));
            }
            assertThat(connection.closes).hasValue(1);
        }
    }

    @Test
    void aConnectionThatFailsToCloseIsStillGivenUp() {
        Connection broken = new Connection() {
            @Override
            public void close() throws IOException {
                super.close();
                throw new IOException("already reset");
            }
        };
        slot.set(broken);

        assertThatCode(slot::close).doesNotThrowAnyException();
        assertThat(slot.current()).isEmpty();
        assertThat(broken.closes).hasValue(1);
    }
}
