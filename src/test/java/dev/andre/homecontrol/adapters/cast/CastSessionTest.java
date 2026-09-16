package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class CastSessionTest {

    /** heartbeat 1 s, stale 3 s, backoff 1–2 s, command 2 s, load 5 s, media poll 1 s. */
    static final CastProperties PROPERTIES = new CastProperties(true, 1, 3, 1, 2, 2, 5, 1);

    private final List<DeviceState> seen = new CopyOnWriteArrayList<>();
    private FakeCastReceiver receiver;
    private CastSession session;

    static Device device(int port) {
        return new Device("cast-127-0-0-1", "Living Room TV", DeviceKind.CAST, "127.0.0.1",
                Map.of("cast", Map.of("port", String.valueOf(port))), Instant.now());
    }

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = new FakeCastReceiver();
    }

    @AfterEach
    void stop() throws Exception {
        if (session != null) {
            session.close();
        }
        receiver.close();
    }

    private CastSession start(int port) {
        session = new CastSession(device(port), PROPERTIES, seen::add);
        session.start();
        return session;
    }

    private void awaitStatus() {
        await().until(() -> session.state().connected() && session.state().volumeMax() == 100);
    }

    @Test
    void connectsAndReportsVolumeMuteAndTheForegroundApp() {
        receiver.setVolume(0.25, true);
        receiver.runApp("233637DE", "YouTube");

        start(receiver.port());

        await().until(() -> "YouTube".equals(session.state().currentApp()));
        DeviceState state = session.state();
        assertThat(state.status()).isEqualTo(DeviceStatus.CONNECTED);
        assertThat(state.powerOn()).isTrue();
        assertThat(state.volumeLevel()).isEqualTo(25);
        assertThat(state.volumeMax()).isEqualTo(100);
        assertThat(state.muted()).isTrue();
        assertThat(seen).extracting(DeviceState::status).startsWith(DeviceStatus.CONNECTING);
    }

    @Test
    void theIdleScreenIsNotACurrentApp() {
        start(receiver.port());

        awaitStatus();

        assertThat(session.state().currentApp()).isNull();
    }

    @Test
    void followsUnsolicitedReceiverStatus() throws Exception {
        start(receiver.port());
        awaitStatus();

        receiver.setVolume(0.8, false);
        receiver.pushReceiverStatus();

        await().until(() -> session.state().volumeLevel() == 80);
    }

    @Test
    void reconnectsAfterTheReceiverHangsUp() throws Exception {
        start(receiver.port());
        awaitStatus();

        receiver.dropConnection();

        await().until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));
        await().until(() -> receiver.connections() == 2 && session.state().connected());
    }

    @Test
    void aSilentReceiverIsDetectedAndReconnectedWhenItAnswersAgain() {
        start(receiver.port());
        awaitStatus();

        receiver.goSilent();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED));

        receiver.setVolume(0.9, false);
        receiver.resume();

        await().atMost(Duration.ofSeconds(15)).until(() -> session.state().connected() && session.state().volumeLevel() == 90);
    }

    @Test
    void anUnreachableReceiverIsDisconnectedAndRetriedUntilItAppears() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        start(port);

        await().until(() -> session.state().status() == DeviceStatus.DISCONNECTED
                && seen.stream().anyMatch(state -> state.status() == DeviceStatus.CONNECTING));

        try (FakeCastReceiver late = new FakeCastReceiver(port)) {
            await().atMost(Duration.ofSeconds(10)).until(() -> session.state().connected());
        }
    }

    @Test
    void connectsToTheReceiversOwnAddressNotTheDevices() {
        // Merged into a TV by name: the TV's address (TEST-NET, unroutable) is not the receiver's.
        Device tv = new Device("10-0-0-5", "Living Room TV", DeviceKind.ANDROID_TV, "192.0.2.1",
                Map.of("androidtv", Map.of("port", "6466"),
                        "cast", Map.of("host", "127.0.0.1", "port", String.valueOf(receiver.port()))), Instant.now());
        session = new CastSession(tv, PROPERTIES, seen::add);
        session.start();

        awaitStatus();
    }

    @Test
    void aClosedSessionPublishesNothingAndDoesNotReconnect() throws Exception {
        start(receiver.port());
        awaitStatus();

        session.close();
        int published = seen.size();
        Thread.sleep(2_500);

        assertThat(seen).hasSize(published);
        assertThat(receiver.connections()).isEqualTo(1);
    }

    @Test
    void remoteKeysAndAppLinksAreNotCastActions() {
        start(receiver.port());

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME)))
                .isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://youtube.com"))))
                .isInstanceOf(UnsupportedActionException.class);
    }

    @Test
    void setsTheVolumeAsAFractionOfOne() {
        start(receiver.port());
        awaitStatus();

        session.execute(new Action.SetVolume(30));

        assertThat(receiver.last(RECEIVER, "SET_VOLUME").orElseThrow().payload().path("volume").path("level").asDouble(-1))
                .isEqualTo(0.3);
        await().until(() -> session.state().volumeLevel() == 30);
    }

    @Test
    void mutesAndUnmutes() {
        start(receiver.port());
        awaitStatus();

        session.execute(new Action.Mute(true));
        await().until(() -> session.state().muted());
        session.execute(new Action.Mute(false));

        await().until(() -> !session.state().muted());
        assertThat(receiver.muted()).isFalse();
    }

    @Test
    void stopsTheForegroundApp() {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        start(receiver.port());
        await().until(() -> "Default Media Receiver".equals(session.state().currentApp()));

        session.execute(new Action.Stop());

        assertThat(receiver.last(RECEIVER, "STOP").orElseThrow().payload().path("sessionId").asString(""))
                .isEqualTo("session-1");
        await().until(() -> session.state().currentApp() == null);
    }

    @Test
    void stoppingWhileNothingIsCastingSendsNothing() {
        start(receiver.port());
        awaitStatus();

        session.execute(new Action.Stop());

        assertThat(receiver.received(RECEIVER, "STOP")).isEmpty();
    }

    @Test
    void aStopTheReceiverRejectsIsAFailureWithItsReason() {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        start(receiver.port());
        await().until(() -> "Default Media Receiver".equals(session.state().currentApp()));
        // Another sender replaced the app; the session still knows only the old session id.
        receiver.runApp("233637DE", "YouTube");

        assertThatThrownBy(() -> session.execute(new Action.Stop()))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Living Room TV refused to stop Default Media Receiver (INVALID_REQUEST: INVALID_SESSION_ID)");
    }

    @Test
    void aCommandTheReceiverNeverAnswersFailsWithAReason() {
        receiver.ignore("SET_VOLUME");
        start(receiver.port());
        awaitStatus();

        assertThatThrownBy(() -> session.execute(new Action.SetVolume(10)))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("did not answer");
    }

    @Test
    void commandsWhileDisconnectedAreRejectedNotQueued() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        start(port);
        await().until(() -> session.state().status() == DeviceStatus.DISCONNECTED);

        assertThatThrownBy(() -> session.execute(new Action.SetVolume(10)))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("not connected");
    }
}
