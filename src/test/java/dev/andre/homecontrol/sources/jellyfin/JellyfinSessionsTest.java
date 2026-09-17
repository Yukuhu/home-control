package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class JellyfinSessionsTest {

    private static final String ITEM_ID = "3f2a1b2c3d4e5f60718293a4b5c6d7e8";

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20));
    private final JellyfinSetupService setup = mock(JellyfinSetupService.class);
    private final JellyfinSessions sessions = new JellyfinSessions(client, setup, JellyfinSessions::resolve);
    private FakeJellyfinServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    private void connected(Map<String, String> sessionLinks) throws IOException {
        fake = new FakeJellyfinServer();
        fake.respond("GET", "/Sessions", 200, "sessions.json");
        given(setup.connection()).willReturn(Optional.of(
                new JellyfinConnection(fake.url(), "tok", "hc-test-device", FakeJellyfinServer.USER_ID)));
        given(setup.settings()).willReturn(Optional.of(settingsWithLinks(sessionLinks)));
    }

    private static JellyfinSettings settingsWithLinks(Map<String, String> links) {
        return new JellyfinSettings(URI.create("http://nas:8096"), URI.create("http://nas:8096"),
                "server-1", "nas", "10.11.2", FakeJellyfinServer.USER_ID, "andre",
                JellyfinSettings.AuthMode.PASSWORD, "hc-test-device", "F007D354", links);
    }

    private static Device device(String name, String host) {
        return new Device("dev-" + name, name, DeviceKind.ANDROID_TV, host, Map.of(), Instant.EPOCH);
    }

    private static JellyfinSession session(String id, String deviceId, String name, String address, Instant lastActivity) {
        return new JellyfinSession(id, deviceId, name, "Test client", address, lastActivity, true);
    }

    @Test
    void listsControllableSessionsExceptOurOwn() throws IOException {
        connected(Map.of());

        List<JellyfinSession> controllable = sessions.controllable();

        assertThat(controllable).extracting(JellyfinSession::id)
                .containsExactly("1d2c3b4a59687f6e5d4c3b2a19081726", "2e3d4c5b6a7980f1e2d3c4b5a6978801");
        JellyfinSession first = controllable.getFirst();
        assertThat(first.client()).isEqualTo("Android TV");
        assertThat(first.deviceName()).isEqualTo("SHIELD Android TV");
        assertThat(first.remoteAddress()).isEqualTo("192.168.1.50");
        assertThat(first.lastActivity()).isEqualTo(Instant.parse("2026-09-16T08:30:11.1234567Z"));

        FakeJellyfinServer.Recorded recorded = fake.last("GET", "/Sessions");
        assertThat(recorded.query()).isEqualTo(Map.of("controllableByUserId", FakeJellyfinServer.USER_ID));
        assertThat(recorded.header("authorization")).contains("Token=\"tok\"");
    }

    @Test
    void matchesTheDeviceByAddress() throws IOException {
        connected(Map.of());

        Optional<JellyfinSession> match = sessions.sessionFor(device("Living Room", "192.168.1.50"));

        assertThat(match).isPresent();
        assertThat(match.orElseThrow().id()).isEqualTo("1d2c3b4a59687f6e5d4c3b2a19081726");
    }

    @Test
    void anExplicitLinkWinsAndIsAuthoritative() throws IOException {
        connected(Map.of("dev-Living Room", "TW96aWxsYS81LjAgKFgxMTsgTGludXg"));

        Optional<JellyfinSession> linked = sessions.sessionFor(device("Living Room", "192.168.1.50"));
        assertThat(linked).isPresent();
        assertThat(linked.orElseThrow().id()).isEqualTo("2e3d4c5b6a7980f1e2d3c4b5a6978801");

        fake.close();
        connected(Map.of("dev-Living Room", "gone-device"));
        assertThat(sessions.sessionFor(device("Living Room", "192.168.1.50"))).isEmpty();
    }

    @Test
    void fallsBackToAUniqueDeviceName() throws IOException {
        connected(Map.of());

        Optional<JellyfinSession> byName = sessions.sessionFor(device("firefox", "10.9.9.9"));
        assertThat(byName).isPresent();
        assertThat(byName.orElseThrow().deviceName()).isEqualTo("Firefox");

        Instant now = Instant.parse("2026-09-16T09:00:00Z");
        List<JellyfinSession> twins = List.of(
                session("s1", "d1", "Twin", "10.1.1.1", now),
                session("s2", "d2", "Twin", "10.1.1.2", now));
        assertThat(JellyfinSessions.match("Twin", Set.of("10.0.0.1"), twins, null)).isEmpty();
    }

    @Test
    void amongSeveralAddressMatchesPrefersTheNameThenTheMostRecent() {
        Instant t0800 = Instant.parse("2026-09-16T08:00:00Z");
        Instant t0900 = Instant.parse("2026-09-16T09:00:00Z");
        Instant t0700 = Instant.parse("2026-09-16T07:00:00Z");
        List<JellyfinSession> atAddress = List.of(
                session("sa", "da", "A", "172.17.0.1", t0800),
                session("sb", "db", "B", "172.17.0.1", t0900),
                session("ss", "ds", "Shield", "172.17.0.1", t0700));

        assertThat(JellyfinSessions.match("Shield", Set.of("172.17.0.1"), atAddress, null))
                .map(JellyfinSession::id).contains("ss");
        assertThat(JellyfinSessions.match("Other", Set.of("172.17.0.1"), atAddress, null))
                .map(JellyfinSession::id).contains("sb");
    }

    @Test
    void normalizesAddresses() {
        assertThat(JellyfinSessions.normalizeAddress("::ffff:192.168.1.50")).isEqualTo("192.168.1.50");
        assertThat(JellyfinSessions.normalizeAddress("192.168.1.50:51234")).isEqualTo("192.168.1.50");
        assertThat(JellyfinSessions.normalizeAddress("[FE80::1%eth0]:8096")).isEqualTo("fe80::1");
        assertThat(JellyfinSessions.normalizeAddress("FE80::1")).isEqualTo("fe80::1");
        assertThat(JellyfinSessions.normalizeAddress(" 10.0.0.2 ")).isEqualTo("10.0.0.2");
    }

    @Test
    void playNowSendsTheDocumentedCommand() throws IOException {
        connected(Map.of());
        fake.respondJson("POST", "/Sessions/1d2c3b4a59687f6e5d4c3b2a19081726/Playing", 204, null);

        sessions.playNow("1d2c3b4a59687f6e5d4c3b2a19081726", ITEM_ID, 6_120_000_000L);

        FakeJellyfinServer.Recorded recorded = fake.last("POST", "/Sessions/1d2c3b4a59687f6e5d4c3b2a19081726/Playing");
        assertThat(recorded.query()).isEqualTo(Map.of(
                "playCommand", "PlayNow", "itemIds", ITEM_ID, "startPositionTicks", "6120000000"));
        assertThat(recorded.body()).isEmpty();

        sessions.playNow("1d2c3b4a59687f6e5d4c3b2a19081726", ITEM_ID, 0);
        FakeJellyfinServer.Recorded second = fake.last("POST", "/Sessions/1d2c3b4a59687f6e5d4c3b2a19081726/Playing");
        assertThat(second.query()).isEqualTo(Map.of("playCommand", "PlayNow", "itemIds", ITEM_ID));
    }

    @Test
    void aClosedSessionIsNamed() throws IOException {
        connected(Map.of());
        fake.respondJson("POST", "/Sessions/1d2c3b4a59687f6e5d4c3b2a19081726/Playing", 404, "{}");

        assertThatThrownBy(() -> sessions.playNow("1d2c3b4a59687f6e5d4c3b2a19081726", ITEM_ID, 0))
                .isInstanceOf(JellyfinException.class)
                .extracting(e -> ((JellyfinException) e).kind())
                .isEqualTo(JellyfinException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> sessions.playNow("1d2c3b4a59687f6e5d4c3b2a19081726", ITEM_ID, 0))
                .hasMessage("The Jellyfin app on that device has closed its session");
    }

    @Test
    void sessionIdsAndItemIdsAreValidated() throws IOException {
        connected(Map.of());

        assertThatThrownBy(() -> sessions.playNow("../x", ITEM_ID, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sessions.playNow(ITEM_ID, "../y", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.requests()).isEmpty();
    }
}
