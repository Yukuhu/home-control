package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.adapters.upnp.protocol.SoapClient;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.GroupMember;
import dev.andre.homecontrol.core.RemoteKey;
import dev.andre.homecontrol.core.SpeakerGroup;
import dev.andre.homecontrol.core.SpeakerTopology;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.andre.homecontrol.adapters.sonos.SonosDiscoveryTest.KITCHEN;
import static dev.andre.homecontrol.adapters.sonos.SonosDiscoveryTest.LIVING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class SonosSessionTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final SonosProperties properties = new SonosProperties(true, 1, 1, 1, 1, 1, 1, 2);
    private final FakeSonosHousehold household = new FakeSonosHousehold();
    private final List<DeviceState> states = new CopyOnWriteArrayList<>();
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
        SonosSession session = new SonosSession(player.device("sonos-" + player.uuid()), properties,
                SoapClient.httpClient(Duration.ofSeconds(1)), states::add, () -> { });
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

        assertThatThrownBy(() -> session.execute(new Action.JoinGroup("RINCON_NOPE")))
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

        assertThatThrownBy(() -> session.execute(new Action.PressKey(RemoteKey.HOME))).isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.OpenAppLink(URI.create("https://x"))))
                .isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.SelectInput("HDMI_1"))).isInstanceOf(UnsupportedActionException.class);
        assertThatThrownBy(() -> session.execute(new Action.PlayMedia(URI.create("http://h/film.mp4"), "video/mp4", "Film", null)))
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
    void isOfflineWhileThePlayerIsGone() {
        SonosSession session = connected(living);

        living.hangUp(true);

        await().atMost(WAIT).until(() -> session.state().status() == DeviceStatus.DISCONNECTED);
        assertThatThrownBy(() -> session.execute(new Action.Pause())).isInstanceOf(DeviceOfflineException.class);
        assertThat(session.speakerTopology()).isEmpty();
    }
}
