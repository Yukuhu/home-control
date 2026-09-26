package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CastConnectionTest {

    private final List<CastIncoming> messages = new CopyOnWriteArrayList<>();
    private final List<CastDisconnectCause> disconnects = new CopyOnWriteArrayList<>();
    private final CastConnection.Listener listener = new CastConnection.Listener() {
        @Override
        public void onMessage(CastIncoming message) {
            messages.add(message);
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            disconnects.add(cause);
        }
    };

    private FakeCastReceiver receiver;
    private CastConnection connection;

    @BeforeEach
    void connect() throws Exception {
        receiver = new FakeCastReceiver();
        connection = CastConnection.open("127.0.0.1", receiver.port(), Duration.ofSeconds(1), Duration.ofSeconds(3), listener);
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        receiver.close();
    }

    @Test
    void opensTheVirtualConnectionToThePlatformReceiverFirst() {
        await().until(() -> receiver.virtualConnections().contains(PLATFORM_RECEIVER_ID));

        CastIncoming connect = receiver.last(CONNECTION, "CONNECT").orElseThrow();
        assertThat(connect.sourceId()).isEqualTo("sender-0");
        assertThat(connect.payload().path("userAgent").asString("")).isEqualTo("home-control");
    }

    @Test
    void sendsHeartbeatPingsAndAnswersTheReceiversPing() throws Exception {
        await().until(() -> receiver.pings() >= 1);

        receiver.ping();

        await().until(() -> receiver.pongs() == 1);
    }

    @Test
    void correlatesARequestWithItsReplyAndStillTellsTheListener() throws Exception {
        receiver.setVolume(0.25, true);

        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofSeconds(3));

        assertThat(reply.type()).isEqualTo("RECEIVER_STATUS");
        assertThat(reply.requestId()).isPositive();
        assertThat(ReceiverStatus.parse(reply.payload().path("status")).volumeLevel()).isEqualTo(0.25);
        await().until(() -> messages.stream().anyMatch(message -> message.requestId() == reply.requestId()));
    }

    @Test
    void anUnansweredRequestTimesOut() {
        receiver.ignore("GET_STATUS");

        assertThatThrownBy(() -> connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofMillis(500)))
                .isInstanceOf(CastTimeoutException.class);
    }

    @Test
    void aSilentReceiverIsReportedStale() {
        receiver.goSilent();

        await().atMost(Duration.ofSeconds(10)).until(() -> disconnects.contains(CastDisconnectCause.STALE));
        assertThat(disconnects).hasSize(1);
    }

    @Test
    void aHangUpIsReportedOnceAndFailsPendingRequests() throws Exception {
        receiver.ignore("GET_STATUS");
        CompletableFuture<Throwable> pending = CompletableFuture.supplyAsync(() -> {
            try {
                connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofSeconds(10));
                return null;
            } catch (IOException e) {
                return e;
            }
        });
        await().until(() -> receiver.last(RECEIVER, "GET_STATUS").isPresent());

        receiver.dropConnection();

        await().until(() -> !disconnects.isEmpty());
        assertThat(disconnects).singleElement().isIn(CastDisconnectCause.CLOSED, CastDisconnectCause.ERROR);
        assertThat(pending.get(5, TimeUnit.SECONDS)).isInstanceOf(IOException.class).isNotInstanceOf(CastTimeoutException.class);
        assertThatThrownBy(() -> connection.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void closingByTheOwnerIsNotReportedAsADisconnect() throws Exception {
        await().until(() -> receiver.pings() >= 1);

        connection.close();

        Thread.sleep(500);
        assertThat(disconnects).isEmpty();
    }

    @Test
    void anUnreachableReceiverFailsToOpen() throws Exception {
        int unused;
        try (ServerSocket probe = new ServerSocket(0)) {
            unused = probe.getLocalPort();
        }

        assertThatThrownBy(() -> CastConnection.open("127.0.0.1", unused, Duration.ofSeconds(1), Duration.ofSeconds(3), listener))
                .isInstanceOf(IOException.class);
    }

    @Test
    void theStaleTimeoutMustExceedTheHeartbeatInterval() {
        var preparedArg144_1 = receiver.port();
        var preparedArg144_2 = Duration.ofSeconds(3);
        var preparedArg144_3 = Duration.ofSeconds(3);
        assertThatThrownBy(() -> CastConnection.open("127.0.0.1", preparedArg144_1, preparedArg144_2, preparedArg144_3, listener))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageWithAnUnreadablePayloadIsSkippedAndTheConnectionSurvives() throws Exception {
        await().until(() -> receiver.virtualConnections().contains(PLATFORM_RECEIVER_ID));

        receiver.sendUnreadablePayload(RECEIVER);
        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(), Duration.ofSeconds(3));

        assertThat(reply.type()).isEqualTo("RECEIVER_STATUS");
        await().until(() -> messages.stream().anyMatch(message -> message.requestId() == reply.requestId()));
        assertThat(messages).hasSize(1);
        assertThat(disconnects).isEmpty();
    }

    @Test
    void anOversizedFrameFromTheReceiverEndsTheConnectionAsAnError() throws Exception {
        await().until(() -> receiver.virtualConnections().contains(PLATFORM_RECEIVER_ID));

        receiver.sendOversizedFrameHeader();

        await().until(() -> !disconnects.isEmpty());
        assertThat(disconnects).containsExactly(CastDisconnectCause.ERROR);
        assertThatThrownBy(() -> connection.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus()))
                .isInstanceOf(IOException.class);
    }
}
