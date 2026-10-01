package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastAppsTest {

    private static final String NS = "urn:x-cast:com.example.test";
    private static final String CUSTOM_APP = "F007D354";
    private static final Map<String, Object> MEDIA_BODY = Map.of("media",
            Map.of("contentId", "http://nas.local/films/bunny.mp4", "contentType", "video/mp4", "streamType", "BUFFERED"),
            "autoplay", true);

    private final CastConnection.Listener ignored = new CastConnection.Listener() {
        @Override
        public void onMessage(CastIncoming message) {
            // The calls under test wait for their own answers.
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            // Nothing to follow.
        }
    };

    private FakeCastReceiver receiver;
    private CastConnection connection;
    private CastApps apps;

    @BeforeEach
    void connect() throws Exception {
        receiver = new FakeCastReceiver();
        connection = CastConnection.open("127.0.0.1", receiver.port(), Duration.ofSeconds(5), Duration.ofSeconds(10),
                ignored);
        apps = new CastApps(connection, Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(300));
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        receiver.close();
    }

    /** The receiver's status as a session following it would know it. */
    private ReceiverStatus status() throws Exception {
        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(),
                Duration.ofSeconds(2));
        return ReceiverStatus.parse(reply.payload().path("status"));
    }

    private static ObjectNode json(String text) {
        return (ObjectNode) JsonMapper.builder().build().readTree(text);
    }

    @Test
    void anAppTheStatusListsIsUsedWithoutLaunching() throws Exception {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");

        ReceiverStatus.ReceiverApp app = apps.running(status(), FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThat(app.appId()).isEqualTo(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
        assertThat(receiver.received(RECEIVER, "LAUNCH")).isEmpty();
    }

    @Test
    void anAppThatIsNotRunningIsLaunched() throws Exception {
        ReceiverStatus.ReceiverApp app = apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThat(app.appId()).isEqualTo(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
        assertThat(receiver.last(RECEIVER, "LAUNCH").orElseThrow().payload().path("appId").asString(""))
                .isEqualTo(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
    }

    @Test
    void aRefusedLaunchIsARefusalWithTheReceiversReason() {
        receiver.refuseLaunch(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThatThrownBy(() -> apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER))
                .isInstanceOf(CastRefusedException.class)
                .hasMessageContaining("LAUNCH_ERROR: NOT_FOUND");
    }

    @Test
    void loadsMediaOnTheAppsTransport() throws Exception {
        ReceiverStatus.ReceiverApp app = apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        apps.load(app, MEDIA_BODY);

        assertThat(receiver.last(MEDIA, "LOAD").orElseThrow().destinationId()).isEqualTo(app.transportId());
        assertThat(receiver.virtualConnections()).contains(app.transportId());
    }

    @Test
    void aFailedLoadIsARefusal() throws Exception {
        receiver.failNextLoad();
        ReceiverStatus.ReceiverApp app = apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThatThrownBy(() -> apps.load(app, MEDIA_BODY))
                .isInstanceOf(CastRefusedException.class)
                .hasMessageContaining("LOAD_FAILED");
    }

    @Test
    void aStaleStopIsARefusalWithTheReceiversReason() {
        assertThatThrownBy(() -> apps.receiverCommand(CastPayloads.stop("no-such-session")))
                .isInstanceOf(CastRefusedException.class)
                .hasMessage("INVALID_REQUEST: INVALID_SESSION_ID");
    }

    @Test
    void aReceiverCommandThatIsNeverAnsweredTimesOut() {
        receiver.ignore("SET_VOLUME");

        assertThatThrownBy(() -> apps.receiverCommand(CastPayloads.setVolumeLevel(0.5)))
                .isInstanceOf(DeviceTimeoutException.class);
    }

    @Test
    void anAppThatSpeaksTheNamespaceIsReturnedAtOnce() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        assertThat(apps.speaking(app, NS).speaks(NS)).isTrue();
    }

    @Test
    void anAppThatNeverSpeaksTheNamespaceTimesOut() throws Exception {
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        assertThatThrownBy(() -> apps.speaking(app, NS)).isInstanceOf(DeviceTimeoutException.class);
    }

    @Test
    void aCustomMessageNobodyRejectsIsTaken() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        apps.send(app, NS, Map.of("command", "PlayNow"));

        assertThat(receiver.received(NS, "")).hasSize(1);
    }

    @Test
    void anErrorAnswerToACustomMessageIsARefusalWithItsMessage() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        receiver.answerCustom(NS, json("""
                {"type":"error","message":"Missing one or more required params"}
                """));
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);
        Map<String, Object> playNow = Map.of("command", "PlayNow");

        assertThatThrownBy(() -> apps.send(app, NS, playNow))
                .isInstanceOf(CastRefusedException.class)
                .hasMessage("Missing one or more required params");
    }

    @Test
    void aQueryReturnsTheReplyOfTheAskedType() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        receiver.answerCustom(NS, json("""
                {"type":"pong","value":7}
                """));
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        Map<String, Object> reply = apps.query(app, NS, Map.of("type", "ping"), "pong");

        assertThat(reply).containsEntry("value", 7);
    }

    @Test
    void anErrorReplyToAQueryIsARefusal() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        receiver.answerCustom(NS, json("""
                {"type":"error","message":"nope"}
                """));
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);
        Map<String, Object> ping = Map.of("type", "ping");

        assertThatThrownBy(() -> apps.query(app, NS, ping, "pong"))
                .isInstanceOf(CastRefusedException.class)
                .hasMessage("nope");
    }
}
