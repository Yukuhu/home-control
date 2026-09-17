package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoungeClientTest {

    static final String SCREEN = "fixture-screen-6hq3r1ukd0n5mc3t2v8p";
    static final String TOKEN = "AGdO5p8FixtureLoungeToken-xYz0123456789";
    static final String REMOTE = "4f1c2d3e-5a6b-4c7d-8e9f-0a1b2c3d4e5f";
    static final LoungeClient.LoungeSession SESSION =
            new LoungeClient.LoungeSession("8A3F2E1D0C9B8A77", "fixture-gsessionid-Qm9vYmFy", 4);

    private FakeGoogleServer fake;
    private LoungeClient client;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer().loungeAccepts();
        client = new LoungeClient(new YouTubeHttp(fake.properties()), fake.properties().loungeBaseUrl());
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void getsALoungeToken() {
        assertThat(client.loungeToken(SCREEN)).isEqualTo(TOKEN);

        FakeGoogleServer.Recorded request = fake.requests("/lounge/pairing/get_lounge_token_batch").getFirst();
        assertThat(request.body()).isEqualTo("screen_ids=" + SCREEN);
        assertThat(request.header("content-type")).startsWith("application/x-www-form-urlencoded");
    }

    @Test
    void bindsWithTheDocumentedForm() {
        LoungeClient.LoungeSession session = client.bind(TOKEN, REMOTE);

        FakeGoogleServer.Recorded request = fake.requests("/lounge/bc/bind").getFirst();
        assertThat(request.rawQuery()).isEqualTo("RID=1&VER=8&CVER=1&auth_failure_option=send_error");
        assertThat(request.body()).isEqualTo("app=web&mdx-version=3&name=Home+Control&id=" + REMOTE
                + "&device=REMOTE_CONTROL&capabilities=que%2Cdsdtr%2Catp&magnaKey=cloudPairedDevice&ui=false&theme=cl"
                + "&loungeIdToken=" + TOKEN);
        assertThat(session.sid()).isEqualTo("8A3F2E1D0C9B8A77");
        assertThat(session.gsessionId()).isEqualTo("fixture-gsessionid-Qm9vYmFy");
        assertThat(session.lastEventId()).isEqualTo(4);
    }

    @Test
    void parseBindHandlesChunksAcrossLines() {
        assertThat(LoungeClient.parseBind(FakeGoogleServer.fixture("lounge-bind.txt"))).isEqualTo(SESSION);
        assertThat(LoungeClient.parseBind(FakeGoogleServer.fixture("lounge-bind.txt").replace("\n", "\r\n")))
                .isEqualTo(SESSION);
        assertThatThrownBy(() -> LoungeClient.parseBind("12\n[[0,[\"noop\"]]]\n"))
                .isInstanceOf(LoungeException.class)
                .hasMessage("bind: YouTube's answer had no session");
        assertThatThrownBy(() -> LoungeClient.parseBind("<html>not a lounge</html>"))
                .isInstanceOf(LoungeException.class)
                .hasMessage("bind: YouTube's answer had no session");
    }

    @Test
    void setsThePlaylist() {
        client.setPlaylist(TOKEN, SESSION, "aqz-KE-bpKQ");

        FakeGoogleServer.Recorded request = fake.requests("/lounge/bc/bind").getFirst();
        assertThat(request.rawQuery()).isEqualTo("name=Home+Control&loungeIdToken=" + TOKEN
                + "&SID=8A3F2E1D0C9B8A77&AID=4&gsessionid=fixture-gsessionid-Qm9vYmFy&device=REMOTE_CONTROL"
                + "&app=youtube-desktop&VER=8&v=2&RID=2");
        assertThat(request.body()).isEqualTo("count=1&ofs=1&req0__sc=setPlaylist&req0_videoId=aqz-KE-bpKQ");
    }

    static Stream<Arguments> failures() {
        Consumer<LoungeClient> token = c -> c.loungeToken(SCREEN);
        Consumer<LoungeClient> bind = c -> c.bind(TOKEN, REMOTE);
        Consumer<LoungeClient> play = c -> c.setPlaylist(TOKEN, SESSION, "aqz-KE-bpKQ");
        return Stream.of(
                Arguments.of("token 500", (Consumer<FakeGoogleServer>) f -> f.respond("POST",
                        "/lounge/pairing/get_lounge_token_batch", FakeGoogleServer.Canned.json(500, "{}")),
                        token, "lounge token: YouTube answered HTTP 500"),
                Arguments.of("no screens", (Consumer<FakeGoogleServer>) f -> f.respond("POST",
                        "/lounge/pairing/get_lounge_token_batch", FakeGoogleServer.Canned.json(200, "{\"screens\":[]}")),
                        token, "lounge token: YouTube did not issue a lounge token for this screen"),
                Arguments.of("bind 401", (Consumer<FakeGoogleServer>) f -> f.respond("POST", "/lounge/bc/bind",
                        FakeGoogleServer.Canned.json(401, "{}")),
                        bind, "bind: YouTube rejected the lounge token"),
                Arguments.of("play 410", (Consumer<FakeGoogleServer>) f -> f.respond("POST", "/lounge/bc/bind",
                        FakeGoogleServer.Canned.json(410, "{}")),
                        play, "setPlaylist: YouTube dropped the lounge session (HTTP 410)"),
                Arguments.of("closed server", (Consumer<FakeGoogleServer>) FakeGoogleServer::close,
                        token, "lounge token: could not reach YouTube"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failuresNameTheStep(String name, Consumer<FakeGoogleServer> setup, Consumer<LoungeClient> call, String message) {
        setup.accept(fake);

        assertThatThrownBy(() -> call.accept(client))
                .isInstanceOf(LoungeException.class)
                .hasMessage(message)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain(TOKEN, "8A3F2E1D0C9B8A77", "fixture-gsessionid-Qm9vYmFy"));
    }

    @Test
    void sessionToStringIsRedacted() {
        assertThat(SESSION.toString())
                .doesNotContain("8A3F2E1D0C9B8A77", "fixture-gsessionid-Qm9vYmFy")
                .contains("4");
    }
}
