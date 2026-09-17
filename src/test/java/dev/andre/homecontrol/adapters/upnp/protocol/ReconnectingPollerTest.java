package dev.andre.homecontrol.adapters.upnp.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconnectingPollerTest {

    private final List<ReconnectingPoller> pollers = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() {
        pollers.forEach(ReconnectingPoller::close);
    }

    /** A scriptable link recording when each callback ran. */
    private static final class ScriptedLink implements ReconnectingPoller.Link {
        final List<Long> connects = new CopyOnWriteArrayList<>();
        final List<Long> polls = new CopyOnWriteArrayList<>();
        final List<Exception> disconnects = new CopyOnWriteArrayList<>();
        final AtomicInteger connectFailures = new AtomicInteger();
        final AtomicInteger pollIoFailures = new AtomicInteger();
        volatile Exception everyPollFailure;
        volatile Duration pollDelay = Duration.ofMillis(50);

        @Override
        public void connect() throws Exception {
            connects.add(System.nanoTime());
            if (connectFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IOException("refused");
            }
        }

        @Override
        public void poll() throws Exception {
            polls.add(System.nanoTime());
            if (pollIoFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IOException("gone");
            }
            if (everyPollFailure != null) {
                throw everyPollFailure;
            }
        }

        @Override
        public Duration nextPollDelay() {
            return pollDelay;
        }

        @Override
        public void disconnected(Exception cause) {
            disconnects.add(cause);
        }
    }

    private ReconnectingPoller poller(ScriptedLink link, Duration initial, Duration max) {
        ReconnectingPoller poller = new ReconnectingPoller("test", initial, max, link);
        pollers.add(poller);
        poller.start();
        return poller;
    }

    private ReconnectingPoller poller(ScriptedLink link) {
        return poller(link, Duration.ofMillis(100), Duration.ofMillis(400));
    }

    @Test
    void connectsThenPollsAtTheDelayTheLinkAsks() {
        ScriptedLink link = new ScriptedLink();
        ReconnectingPoller poller = poller(link);

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(link.connects).hasSize(1);
            assertThat(link.polls).hasSizeGreaterThanOrEqualTo(3);
            assertThat(poller.connected()).isTrue();
        });
    }

    @Test
    void aFailedConnectIsRetriedWithDoublingBackoff() {
        ScriptedLink link = new ScriptedLink();
        link.connectFailures.set(3);
        ReconnectingPoller poller = poller(link);

        await().atMost(Duration.ofSeconds(3)).until(poller::connected);

        assertThat(link.disconnects).hasSize(3);
        assertThat(link.connects).hasSize(4);
        long[] minimumGaps = {90, 180, 360};
        for (int i = 0; i < minimumGaps.length; i++) {
            long gapMillis = (link.connects.get(i + 1) - link.connects.get(i)) / 1_000_000;
            assertThat(gapMillis).isGreaterThanOrEqualTo(minimumGaps[i]);
        }
    }

    @Test
    void anIoFailureWhilePollingReconnects() {
        ScriptedLink link = new ScriptedLink();
        link.pollIoFailures.set(1);
        poller(link);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(link.disconnects).hasSize(1);
            assertThat(link.connects).hasSize(2);
            assertThat(link.polls).hasSizeGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void otherPollFailuresKeepPolling() {
        ScriptedLink link = new ScriptedLink();
        link.everyPollFailure = new SoapFault(501, "Action Failed");
        poller(link);

        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).hasSizeGreaterThanOrEqualTo(2));
        int seen = link.polls.size();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).hasSizeGreaterThan(seen));
        assertThat(link.disconnects).isEmpty();
    }

    @Test
    void reconnectNowSkipsTheBackoff() {
        ScriptedLink link = new ScriptedLink();
        link.connectFailures.set(1);
        ReconnectingPoller poller = poller(link, Duration.ofSeconds(10), Duration.ofSeconds(60));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.disconnects).hasSize(1));

        poller.reconnectNow();

        await().atMost(Duration.ofSeconds(1)).until(poller::connected);
    }

    @Test
    void pollNowPollsAtOnce() {
        ScriptedLink link = new ScriptedLink();
        link.pollDelay = Duration.ofSeconds(10);
        ReconnectingPoller poller = poller(link);
        await().atMost(Duration.ofSeconds(1)).until(poller::connected);
        assertThat(link.polls).isEmpty();

        poller.pollNow();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).hasSize(1));
        poller.pollNow();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).hasSize(2));
    }

    @Test
    void closeStopsEverything() throws InterruptedException {
        ScriptedLink link = new ScriptedLink();
        ReconnectingPoller poller = poller(link);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(link.polls).isNotEmpty());

        poller.close();
        Thread.sleep(50);
        int connects = link.connects.size();
        int polls = link.polls.size();
        Thread.sleep(500);

        assertThat(link.connects).hasSize(connects);
        assertThat(link.polls).hasSize(polls);
        assertThat(poller.connected()).isFalse();
    }
}
