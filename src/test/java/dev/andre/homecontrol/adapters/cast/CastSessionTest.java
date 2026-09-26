package dev.andre.homecontrol.adapters.cast;

import dev.andre.homecontrol.adapters.cast.protocol.CastIncoming;
import dev.andre.homecontrol.adapters.cast.protocol.FakeCastReceiver;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.CastLoads;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.CONNECTION;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
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

        var failingAction185 = new Action.PressKey(RemoteKey.HOME);
        assertThatThrownBy(() -> session.execute(failingAction185))
                .isInstanceOf(UnsupportedActionException.class);
        var failingAction187 = new Action.OpenAppLink(URI.create("https://youtube.com"));
        assertThatThrownBy(() -> session.execute(failingAction187))
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

        var failingAction247 = new Action.Stop();
        assertThatThrownBy(() -> session.execute(failingAction247))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Living Room TV refused to stop Default Media Receiver (INVALID_REQUEST: INVALID_SESSION_ID)");
    }

    @Test
    void aCommandTheReceiverNeverAnswersFailsWithAReason() {
        receiver.ignore("SET_VOLUME");
        start(receiver.port());
        awaitStatus();

        var failingAction258 = new Action.SetVolume(10);
        assertThatThrownBy(() -> session.execute(failingAction258))
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

        var failingAction272 = new Action.SetVolume(10);
        assertThatThrownBy(() -> session.execute(failingAction272))
                .isInstanceOf(DeviceOfflineException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    void aRefusalNamesTheAppIdWhenTheAppHasNoDisplayName() {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "");
        start(receiver.port());
        await().until(() -> session.state().connected() && session.state().currentApp() != null);
        receiver.runApp("233637DE", "YouTube");

        var failingAction284 = new Action.Stop();
        assertThatThrownBy(() -> session.execute(failingAction284))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Living Room TV refused to stop CC1AD845 (INVALID_REQUEST: INVALID_SESSION_ID)");
    }

    private static Action.CastLoad bunny() {
        return new Action.CastLoad(CastLoads.DEFAULT_MEDIA_RECEIVER, CastLoads.defaultMediaReceiver(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4"), "Big Buck Bunny"));
    }

    private void awaitFollowing(String title) {
        await().until(() -> session.state().nowPlaying() != null && title.equals(session.state().nowPlaying().title()));
    }

    @Test
    void launchesTheDefaultMediaReceiverAndLoadsTheStream() {
        start(receiver.port());
        awaitStatus();

        session.execute(bunny());

        assertThat(receiver.last(RECEIVER, "LAUNCH").orElseThrow().payload().path("appId").asString("")).isEqualTo("CC1AD845");
        CastIncoming load = receiver.last(MEDIA, "LOAD").orElseThrow();
        assertThat(load.destinationId()).isEqualTo("transport-1");
        assertThat(load.payload().path("sessionId").asString("")).isEqualTo("session-1");
        assertThat(load.payload().path("media").path("contentId").asString("")).isEqualTo("http://nas.local/films/bunny.mp4");
        assertThat(load.payload().path("media").path("contentType").asString("")).isEqualTo("video/mp4");
        assertThat(load.payload().path("autoplay").asBoolean(false)).isTrue();
        assertThat(receiver.virtualConnections()).contains("transport-1");
        awaitFollowing("Big Buck Bunny");
        NowPlaying playing = session.state().nowPlaying();
        assertThat(playing.state()).isEqualTo(PlaybackState.PLAYING);
        assertThat(playing.durationSeconds()).isEqualTo(596.5);
        assertThat(session.state().currentApp()).isEqualTo("Default Media Receiver");
    }

    @Test
    void reusesTheReceiverAppWhenItIsAlreadyRunning() {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        start(receiver.port());
        await().until(() -> "Default Media Receiver".equals(session.state().currentApp()));

        session.execute(bunny());

        assertThat(receiver.received(RECEIVER, "LAUNCH")).isEmpty();
        assertThat(receiver.last(MEDIA, "LOAD").orElseThrow().destinationId()).isEqualTo("transport-1");
    }

    @Test
    void aRefusedLaunchFailsWithTheReceiversReason() {
        receiver.refuseLaunch(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
        start(receiver.port());
        awaitStatus();

        var preparedArg350_0 = bunny();
        assertThatThrownBy(() -> session.execute(preparedArg350_0))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("LAUNCH_ERROR: NOT_FOUND");
        assertThat(receiver.received(MEDIA, "LOAD")).isEmpty();
    }

    @Test
    void aFailedLoadFailsWithTheReceiversAnswer() {
        receiver.failNextLoad();
        start(receiver.port());
        awaitStatus();

        var failedLoad = bunny();
        assertThatThrownBy(() -> session.execute(failedLoad))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("LOAD_FAILED");
    }

    @Test
    void loadingWhileDisconnectedIsRejectedNotQueued() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        start(port);
        await().until(() -> session.state().status() == DeviceStatus.DISCONNECTED);

        var preparedArg364_0 = bunny();
        assertThatThrownBy(() -> session.execute(preparedArg364_0)).isInstanceOf(DeviceOfflineException.class);
    }

    @Test
    void followsMediaStartedByAnotherSender() throws Exception {
        start(receiver.port());
        awaitStatus();

        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        receiver.startMedia("Song", "PLAYING", 12.0);
        receiver.pushReceiverStatus();

        awaitFollowing("Song");
        assertThat(session.state().nowPlaying().positionSeconds()).isGreaterThanOrEqualTo(12.0);
        assertThat(receiver.last(CONNECTION, "CONNECT").orElseThrow().destinationId()).isEqualTo("transport-1");
    }

    @Test
    void aPartialStatusKeepsTheTitleAndIdleClearsNowPlaying() throws Exception {
        start(receiver.port());
        awaitStatus();
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        receiver.startMedia("Song", "PLAYING", 12.0);
        receiver.pushReceiverStatus();
        awaitFollowing("Song");

        receiver.setMediaState("PAUSED", 30.0);
        receiver.pushMediaStatus(false);

        await().until(() -> session.state().nowPlaying().state() == PlaybackState.PAUSED);
        assertThat(session.state().nowPlaying().title()).isEqualTo("Song");
        assertThat(session.state().nowPlaying().positionSeconds()).isEqualTo(30.0);

        receiver.setMediaState("IDLE", 0);
        receiver.pushMediaStatus(false);

        await().until(() -> session.state().nowPlaying() == null);
    }

    @Test
    void pollsThePositionWhilePlaying() throws Exception {
        start(receiver.port());
        awaitStatus();
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");
        receiver.startMedia("Song", "PLAYING", 12.0);
        receiver.pushReceiverStatus();
        awaitFollowing("Song");
        int before = receiver.received(MEDIA, "GET_STATUS").size();

        await().until(() -> receiver.received(MEDIA, "GET_STATUS").size() >= before + 2);
    }

    @Test
    void stoppingTheAppClearsNowPlaying() {
        start(receiver.port());
        awaitStatus();
        session.execute(bunny());
        awaitFollowing("Big Buck Bunny");

        session.execute(new Action.Stop());

        await().until(() -> session.state().nowPlaying() == null && session.state().currentApp() == null);
    }

    @Test
    void aDroppedConnectionClearsNowPlaying() throws Exception {
        start(receiver.port());
        awaitStatus();
        session.execute(bunny());
        awaitFollowing("Big Buck Bunny");

        receiver.dropConnection();

        await().until(() -> seen.stream().anyMatch(state -> state.status() == DeviceStatus.DISCONNECTED
                && state.nowPlaying() == null));
    }

    private static final String NS = "urn:x-cast:com.connectsdk";

    private static Action.CastMessage playNow() {
        return new Action.CastMessage("F007D354", NS,
                Map.of("command", "PlayNow", "options", Map.of("items", List.of(Map.of("Id", "abc")))));
    }

    @Test
    void aCustomMessageLaunchesTheReceiverAndIsSentUnchanged() {
        receiver.appSpeaks("F007D354", NS);
        start(receiver.port());
        awaitStatus();

        session.execute(playNow());

        assertThat(receiver.last(RECEIVER, "LAUNCH").orElseThrow().payload().path("appId").asString("")).isEqualTo("F007D354");
        assertThat(receiver.virtualConnections()).contains("transport-1");
        List<CastIncoming> sent = receiver.received(NS, "");
        assertThat(sent).hasSize(1);
        CastIncoming message = sent.getFirst();
        assertThat(message.payload().path("command").asString("")).isEqualTo("PlayNow");
        assertThat(message.payload().path("options").path("items").get(0).path("Id").asString("")).isEqualTo("abc");
        assertThat(message.payload().has("requestId")).isFalse();
        assertThat(message.payload().has("sessionId")).isFalse();
        assertThat(message.payload().has("type")).isFalse();
    }

    @Test
    void aRunningReceiverIsReused() {
        receiver.appSpeaks("F007D354", NS);
        receiver.runApp("F007D354", "Jellyfin");
        start(receiver.port());
        await().until(() -> "Jellyfin".equals(session.state().currentApp()));

        session.execute(playNow());

        assertThat(receiver.received(RECEIVER, "LAUNCH")).isEmpty();
    }

    @Test
    void aSynchronousReceiverErrorFailsTheAction() {
        receiver.appSpeaks("F007D354", NS);
        receiver.answerCustom(NS, (ObjectNode) JsonMapper.builder().build().readTree("""
                {"type":"error","message":"Missing one or more required params - command,options,userId,accessToken,serverAddress"}
                """));
        start(receiver.port());
        awaitStatus();

        var preparedArg499_0 = playNow();
        assertThatThrownBy(() -> session.execute(preparedArg499_0))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("refused to play it (Missing one or more required params");
    }

    @Test
    void aReceiverThatNeverSpeaksTheNamespaceTimesOut() {
        start(receiver.port());
        awaitStatus();

        var unansweredPlayback = playNow();
        assertThatThrownBy(() -> session.execute(unansweredPlayback))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("did not answer in time");
    }

    @Test
    void anOfflineReceiverRejectsTheMessage() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        start(port);
        await().until(() -> session.state().status() == DeviceStatus.DISCONNECTED);

        var preparedArg513_0 = playNow();
        assertThatThrownBy(() -> session.execute(preparedArg513_0))
                .isInstanceOf(DeviceOfflineException.class);
    }

    private static final String MDX = "urn:x-cast:com.google.youtube.mdx";
    private static final CastAppQuery MDX_STATUS = new CastAppQuery("233637DE", MDX,
            Map.of("type", "getMdxSessionStatus"), "mdxSessionStatus");

    private static ObjectNode mdxFixture() throws Exception {
        try (var in = CastSessionTest.class.getResourceAsStream("/fixtures/youtube/cast-mdx-session-status.json")) {
            return (ObjectNode) JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void aQueryLaunchesTheAppAndReturnsItsReply() throws Exception {
        receiver.appSpeaks("233637DE", MDX);
        receiver.answerCustom(MDX, mdxFixture());
        start(receiver.port());
        awaitStatus();

        Map<String, Object> reply = session.query(MDX_STATUS);

        assertThat(reply).containsEntry("type", "mdxSessionStatus");
        assertThat(reply.get("data")).isInstanceOfSatisfying(Map.class,
                data -> assertThat(data).containsEntry("screenId", "fixture-screen-6hq3r1ukd0n5mc3t2v8p"));
        assertThat(receiver.last(RECEIVER, "LAUNCH").orElseThrow().payload().path("appId").asString("")).isEqualTo("233637DE");
        List<CastIncoming> sent = receiver.received(MDX, "getMdxSessionStatus");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().payload().size()).isEqualTo(1);
    }

    @Test
    void anErrorReplyFailsTheQuery() {
        receiver.appSpeaks("233637DE", MDX);
        receiver.answerCustom(MDX, (ObjectNode) JsonMapper.builder().build().readTree("""
                {"type":"error","message":"nope"}
                """));
        start(receiver.port());
        awaitStatus();

        assertThatThrownBy(() -> session.query(MDX_STATUS))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("refused the request (nope)");
    }

    @Test
    void noReplyTimesOut() {
        receiver.appSpeaks("233637DE", MDX);
        start(receiver.port());
        awaitStatus();

        assertThatThrownBy(() -> session.query(MDX_STATUS))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Living Room TV did not answer in time when asked to answer mdxSessionStatus");
    }

    @Test
    void aDisconnectedSessionIsOffline() {
        session = new CastSession(device(receiver.port()), PROPERTIES, seen::add);

        assertThatThrownBy(() -> session.query(MDX_STATUS)).isInstanceOf(DeviceOfflineException.class);
    }
}
