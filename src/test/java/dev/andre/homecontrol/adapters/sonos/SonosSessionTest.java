package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.GroupListing;
import dev.andre.homecontrol.core.GroupMember;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.SpeakerGroup;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.testsupport.RecordingStateListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dev.andre.homecontrol.adapters.sonos.SonosDiscoveryTest.KITCHEN;
import static dev.andre.homecontrol.adapters.sonos.SonosDiscoveryTest.LIVING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class SonosSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** Poll 100 ms idle and playing, topology every 1 s, command 1 s, reconnect 50–200 ms. */
    private final SonosTimings timings = new SonosTimings(Duration.ofMillis(100), Duration.ofMillis(100),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200));
    private final FakeSonosHousehold household = new FakeSonosHousehold();
    private final RecordingStateListener states = new RecordingStateListener();
    private final List<SonosSession> sessions = new CopyOnWriteArrayList<>();
    private final Action.PlayMedia song = new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/song.flac"),
            "audio/flac", "Bunny Song", "The Rabbits");
    private FakeSonosPlayer living;
    private FakeSonosPlayer kitchen;

    @BeforeEach
    void setUp() throws IOException {
        living = household.addPlayer("127.0.0.2", LIVING, "Living Room");
        kitchen = household.addPlayer("127.0.0.3", KITCHEN, "Kitchen");
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(SonosSession::close);
        household.close();
    }

    private SonosSession connected(FakeSonosPlayer player) {
        return connected(player, states);
    }

    private SonosSession connected(FakeSonosPlayer player, Consumer<DeviceState> onChange) {
        SonosSession session = new SonosSession(player.device("sonos-" + player.uuid()), timings,
                SoapClient.httpClient(Duration.ofSeconds(1)), onChange, () -> { }, Clock.systemUTC());
        sessions.add(session);
        session.start();
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
        return session;
    }

    @Test
    void connectsAndReadsTheSpeakersOwnVolume() {
        SonosSession session = connected(kitchen);

        assertThat(session.state().volumeLevel()).isEqualTo(20);
    }

    @Test
    void aFailingStateListenerDoesNotStopPolling() {
        SonosSession session = connected(kitchen, state -> {
            states.accept(state);
            throw new IllegalStateException("a subscriber failed");
        });

        assertThat(session.state().status()).isEqualTo(DeviceStatus.CONNECTED);
    }

    @Test
    void aListenerThatFailedOnceStillReceivesLaterUpdates() {
        AtomicBoolean failed = new AtomicBoolean();
        connected(kitchen, state -> {
            states.accept(state);
            if (state.status() == DeviceStatus.CONNECTED && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("listener bug");
            }
        });
        await().atMost(WAIT).until(failed::get);

        kitchen.setVolume(35);

        await().atMost(WAIT).until(() -> states.last().volumeLevel() == 35);
    }

    @Test
    void aStandaloneSpeakerPlaysItself() {
        connected(living).execute(song);

        assertThat(living.commandNames()).containsExactly("SetAVTransportURI", "Play");
        assertThat(living.currentMetadata()).contains("protocolInfo=\"http-get:*:audio/flac:*\"");
    }

    @Test
    void aGroupMemberPlaysThroughItsCoordinator() {
        household.join(KITCHEN, LIVING);
        SonosSession session = connected(kitchen);

        session.execute(song);
        assertThat(living.commandNames()).containsExactly("SetAVTransportURI", "Play");
        assertThat(kitchen.commandNames()).isEmpty();

        session.execute(new Action.Pause());
        assertThat(living.commandNames()).last().isEqualTo("Pause");
    }

    @Test
    void volumeStaysWithTheSpeaker() {
        household.join(KITCHEN, LIVING);
        SonosSession session = connected(kitchen);

        session.execute(new Action.SetVolume(33));

        assertThat(kitchen.volume()).isEqualTo(33);
        assertThat(living.volume()).isEqualTo(20);
    }

    @Test
    void joinsAndLeavesGroups() {
        SonosSession session = connected(kitchen);
        assertThat(session.feature(GroupListing.class)).containsSame(session);

        session.execute(new Action.JoinGroup(LIVING));
        assertThat(kitchen.calls("SetAVTransportURI").getLast().argument("CurrentURI")).isEqualTo("x-rincon:RINCON_000E58A0B1C201400");
        assertThat(kitchen.calls("SetAVTransportURI").getLast().argument("CurrentURIMetaData")).isEmpty();
        assertThat(household.coordinatorOf(KITCHEN)).isEqualTo(LIVING);
        await().atMost(WAIT).untilAsserted(() -> {
            SpeakerTopology topology = session.speakerTopology().orElseThrow();
            assertThat(topology.grouped()).isTrue();
            assertThat(topology.ownGroup().orElseThrow().label()).isEqualTo("Living Room + Kitchen");
        });

        session.execute(new Action.LeaveGroup());
        assertThat(kitchen.commandNames()).last().isEqualTo("BecomeCoordinatorOfStandaloneGroup");
        await().atMost(WAIT).untilAsserted(() -> assertThat(session.speakerTopology().orElseThrow().grouped()).isFalse());
    }

    @Test
    void joiningTheOwnGroupAndLeavingAloneChangeNothing() {
        SonosSession session = connected(kitchen);

        session.execute(new Action.LeaveGroup());
        session.execute(new Action.JoinGroup(KITCHEN));

        assertThat(kitchen.commandNames()).isEmpty();
    }

    @Test
    void joiningAnUnknownSpeakerFails() {
        SonosSession session = connected(kitchen);

        var joinUnknown = new Action.JoinGroup("RINCON_NOPE");
        assertThatThrownBy(() -> session.execute(joinUnknown))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("cannot find that speaker");
    }

    @Test
    void reportsTheHouseholdTopology() {
        SonosSession session = connected(living);

        SpeakerTopology topology = session.speakerTopology().orElseThrow();
        assertThat(topology.selfId()).isEqualTo(LIVING);
        assertThat(topology.groups()).hasSize(2);
        assertThat(topology.otherGroups()).containsExactly(new SpeakerGroup(KITCHEN, List.of(new GroupMember(KITCHEN, "Kitchen"))));
    }

    @Test
    void rejectsWhatASpeakerCannotDo() {
        SonosSession session = connected(living);

        var homeKey = new Action.PressKey(RemoteKey.HOME);
        assertThatThrownBy(() -> session.execute(homeKey)).isInstanceOf(UnsupportedActionException.class);
        var appLink = new Action.OpenAppLink(URI.create("https://x"));
        assertThatThrownBy(() -> session.execute(appLink))
                .isInstanceOf(UnsupportedActionException.class);
        var selectInput = new Action.SelectInput("HDMI_1");
        assertThatThrownBy(() -> session.execute(selectInput)).isInstanceOf(UnsupportedActionException.class);
        var video = new Action.PlayMedia(URI.create("http://h/film.mp4"), "video/mp4", "Film", null);
        assertThatThrownBy(() -> session.execute(video))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessage("Living Room cannot play video/mp4");
    }

    @Test
    void groupMembersShowTheCoordinatorsNowPlaying() {
        household.join(KITCHEN, LIVING);
        SonosSession kitchenSession = connected(kitchen);

        connected(living).execute(song);

        await().atMost(WAIT).untilAsserted(() -> assertThat(kitchenSession.state().nowPlaying()).isNotNull()
                .extracting(dev.andre.homecontrol.core.NowPlaying::title).isEqualTo("Bunny Song"));
    }

    @Test
    void aVanishedCoordinatorMakesTheMemberOffline() {
        household.join(KITCHEN, LIVING);
        SonosSession session = connected(kitchen);

        living.hangUp(true);
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.DISCONNECTED);

        living.hangUp(false);
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void aStaleCoordinatorIsLookedUpAgainWhenTheSpeakerSaysItIsNotOne() {
        SonosSession session = new SonosSession(kitchen.device("sonos-" + KITCHEN),
                new SonosTimings(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofHours(1),
                        Duration.ofSeconds(1), Duration.ofMillis(50), Duration.ofMillis(200)),
                SoapClient.httpClient(Duration.ofSeconds(1)), states, () -> { }, Clock.systemUTC());
        sessions.add(session);
        session.start();
        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.CONNECTED);
        household.join(KITCHEN, LIVING); // grouped in the Sonos app; this session has not re-read the topology

        session.execute(new Action.Pause());

        assertThat(kitchen.calls("Pause")).hasSize(1);
        assertThat(living.commandNames()).containsExactly("Pause");
    }

    @Test
    void isOfflineWhileThePlayerIsGone() {
        SonosSession session = connected(living);

        living.hangUp(true);

        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.DISCONNECTED);
        var pause = new Action.Pause();
        assertThatThrownBy(() -> session.execute(pause)).isInstanceOf(DeviceOfflineException.class);
        assertThat(session.speakerTopology()).isEmpty();
    }
}
