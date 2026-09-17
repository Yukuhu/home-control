package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.CastAppQuery;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.UnsupportedActionException;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class YouTubeLoungeRouteExecutorTest {

    private static final Route.YouTubeLounge ROUTE = new Route.YouTubeLounge("aqz-KE-bpKQ");
    private static final CastAppQuery MDX_STATUS = new CastAppQuery("233637DE", "urn:x-cast:com.google.youtube.mdx",
            Map.of("type", "getMdxSessionStatus"), "mdxSessionStatus");
    private static final String REMOTE = "4f1c2d3e-5a6b-4c7d-8e9f-0a1b2c3d4e5f";

    private final Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9",
            Map.of("cast", Map.of()), Instant.now());
    private final DeviceManager devices = mock(DeviceManager.class);
    private final YouTubeSetupService setup = mock(YouTubeSetupService.class);
    private final AtomicReference<YouTubeSettings> settings = new AtomicReference<>();
    private FakeGoogleServer fake;
    private YouTubeLoungeRouteExecutor executor;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mdxReply() {
        return JsonMapper.builder().build().readValue(FakeGoogleServer.fixture("cast-mdx-session-status.json"), Map.class);
    }

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeGoogleServer().loungeAccepts();
        settings.set(YouTubeSettings.EMPTY.withLoungeDevice("kitchen", true).withLoungeRemoteId(REMOTE));
        given(setup.settings()).willAnswer(invocation -> settings.get());
        willAnswer(invocation -> {
            settings.set(invocation.getArgument(0));
            return null;
        }).given(setup).save(any());
        executor = new YouTubeLoungeRouteExecutor(devices,
                new LoungeClient(new YouTubeHttp(fake.properties()), fake.properties().loungeBaseUrl()), setup);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void castsThroughTheLounge() {
        given(devices.query("kitchen", MDX_STATUS)).willReturn(mdxReply());

        executor.execute(ROUTE, kitchen);

        assertThat(fake.requests()).extracting(FakeGoogleServer.Recorded::path).containsExactly(
                "/lounge/pairing/get_lounge_token_batch", "/lounge/bc/bind", "/lounge/bc/bind");
        List<FakeGoogleServer.Recorded> binds = fake.requests("/lounge/bc/bind");
        assertThat(binds.getFirst().query()).containsEntry("RID", "1");
        assertThat(binds.get(1).query()).containsEntry("RID", "2");
        assertThat(binds.get(1).form()).containsEntry("req0_videoId", "aqz-KE-bpKQ");
        verify(devices).query("kitchen", MDX_STATUS);
        verify(setup, never()).save(any());
    }

    @Test
    void aRemoteIdIsCreatedOnceAndReused() {
        settings.set(YouTubeSettings.EMPTY.withLoungeDevice("kitchen", true));
        given(devices.query("kitchen", MDX_STATUS)).willReturn(mdxReply());

        executor.execute(ROUTE, kitchen);

        String remoteId = settings.get().loungeRemoteId();
        assertThat(remoteId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(fake.requests("/lounge/bc/bind").getFirst().form()).containsEntry("id", remoteId);

        executor.execute(ROUTE, kitchen);

        assertThat(fake.requests("/lounge/bc/bind").get(2).form()).containsEntry("id", remoteId);
        verify(setup, times(1)).save(any());
    }

    @Test
    void receiverProblemsAreExplicit() {
        given(devices.query("kitchen", MDX_STATUS)).willThrow(
                new ActionFailedException("Kitchen did not answer in time when asked to answer mdxSessionStatus"));

        assertThatThrownBy(() -> executor.execute(ROUTE, kitchen))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageStartingWith("Kitchen: YouTube Cast (best effort, unofficial API) failed: the YouTube receiver did not answer (")
                .hasMessageContaining("did not answer in time when asked to answer mdxSessionStatus")
                .hasMessageEndingWith("switch YouTube Cast off for this device in Setup.");

        willReturn(Map.of("type", "mdxSessionStatus", "data", Map.of())).given(devices).query("kitchen", MDX_STATUS);

        assertThatThrownBy(() -> executor.execute(ROUTE, kitchen))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Kitchen: YouTube Cast (best effort, unofficial API) failed: the YouTube receiver did not report"
                        + " a screen id. Use the YouTube app route, or switch YouTube Cast off for this device in Setup.");
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void offlineAndUnsupportedPassThrough() {
        DeviceOfflineException offline = new DeviceOfflineException("Kitchen is not connected");
        given(devices.query(eq("kitchen"), any())).willThrow(offline);

        assertThatThrownBy(() -> executor.execute(ROUTE, kitchen)).isSameAs(offline);

        UnsupportedActionException unsupported = new UnsupportedActionException("Kitchen is not a Cast receiver");
        willThrow(unsupported).given(devices).query(eq("kitchen"), any());

        assertThatThrownBy(() -> executor.execute(ROUTE, kitchen)).isSameAs(unsupported);
    }

    @Test
    void loungeFailuresAreExplicit() {
        given(devices.query("kitchen", MDX_STATUS)).willReturn(mdxReply());
        fake.respond("POST", "/lounge/bc/bind", FakeGoogleServer.Canned.json(401, "{}"));

        assertThatThrownBy(() -> executor.execute(ROUTE, kitchen))
                .isInstanceOf(ActionFailedException.class)
                .hasMessageContaining("failed: bind: YouTube rejected the lounge token")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("AGdO5p8FixtureLoungeToken"));
    }

    @Test
    void executesOnlyLoungeRoutes() {
        assertThat(executor.executes(new Route.YouTubeLounge("x"))).isTrue();
        assertThat(executor.executes(new Route.OpenAppLink(URI.create("https://www.youtube.com/watch?v=x"), "youtube")))
                .isFalse();
    }
}
