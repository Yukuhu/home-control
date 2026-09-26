package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.HomeControlConfiguration;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.playback.AppLinkStrategy;
import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.CastLoadStrategy;
import dev.andre.homecontrol.core.playback.CastMessageStrategy;
import dev.andre.homecontrol.core.playback.CastStreamStrategy;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.LocalAudioSinkStrategy;
import dev.andre.homecontrol.core.playback.MediaRendererStrategy;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteExecutor;
import dev.andre.homecontrol.core.playback.RouteKeys;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.core.playback.YouTubeLoungeStrategy;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlaybackServiceTest {

    private final DeviceManager devices = mock(DeviceManager.class);
    private final PlaybackService defaultService = new PlaybackService(devices,
            new PlaybackPlanner(List.of(new AppLinkStrategy())));
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());
    private final RouteExecutor executor = mock(RouteExecutor.class);

    private static final PlayableRef.JellyfinItem WANTED = new PlayableRef.JellyfinItem("srv", "item-1", 600L);
    private static final ContentItem JELLYFIN_ITEM = new ContentItem("item-1", "jellyfin", ContentKind.EPISODE,
            "Northern Lights", null, null, List.of(WANTED));

    private static PlayableResolver resolverReturning(PlayableResolver.Resolution resolution) {
        return new PlayableResolver() {
            @Override
            public boolean resolves(PlayableRef ref) {
                return ref instanceof PlayableRef.JellyfinItem;
            }

            @Override
            public Resolution resolve(PlayableRef ref, ContentItem item, Device device, Set<Capability> capabilities) {
                return resolution;
            }
        };
    }

    @Test
    void workflowPlansWithoutExecutingAndPlayDelegatesExactlyOnce() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(Set.of(Capability.CAST_RECEIVER));
        var route = new Route.WorkflowCast("w-0123456789ab", 1, "single");
        var item = new ContentItem("w-0123456789ab", "workflows", ContentKind.VIDEO, "News", null, null,
                List.of(new PlayableRef.WorkflowCast("w-0123456789ab", 1, "single")));
        given(executor.executes(route)).willReturn(true);
        var workflows = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new dev.andre.homecontrol.core.playback.WorkflowCastStrategy())),
                List.of(), List.of(executor));
        assertThat(workflows.plan(item, "shield")).isEqualTo(route);
        assertThat(workflows.preview(item, "shield").routes()).containsExactly(route);
        verify(executor, never()).execute(any(), any());
        assertThat(workflows.play(item, "shield")).isEqualTo(route);
        verify(executor).execute(route, shield);
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void executesARenderRoute() {
        Device speaker = new Device("upnp-10-0-0-30", "Kitchen Speaker", DeviceKind.UPNP, "10.0.0.30",
                Map.of("upnp", Map.of()), Instant.now());
        given(devices.device("upnp-10-0-0-30")).willReturn(Optional.of(speaker));
        given(devices.capabilities("upnp-10-0-0-30")).willReturn(EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME));
        PlaybackService renderers = new PlaybackService(devices, new PlaybackPlanner(List.of(new AppLinkStrategy(),
                new CastLoadStrategy(), new CastStreamStrategy(), new MediaRendererStrategy())));

        Route route = renderers.play(AppLinks.fromUrl("http://nas.local/music/song.flac"), "upnp-10-0-0-30");

        assertThat(route).isInstanceOf(Route.Render.class);
        verify(devices).execute("upnp-10-0-0-30",
                new Action.PlayMedia(URI.create("http://nas.local/music/song.flac"), "audio/flac", "song.flac", null));
    }

    @Test
    void executesALocalPlaybackRoute() {
        Device speaker = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", Map.of()), Instant.now());
        given(devices.device("bluetooth-aa-bb-cc-dd-ee-ff")).willReturn(Optional.of(speaker));
        given(devices.capabilities("bluetooth-aa-bb-cc-dd-ee-ff")).willReturn(EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME));
        PlaybackService local = new PlaybackService(devices, new PlaybackPlanner(List.of(new AppLinkStrategy(),
                new CastStreamStrategy(), new MediaRendererStrategy(), new LocalAudioSinkStrategy())));

        Route route = local.play(AppLinks.fromUrl("http://nas.local/music/song.mp3"), "bluetooth-aa-bb-cc-dd-ee-ff");

        assertThat(route).isInstanceOf(Route.PlayLocally.class);
        verify(devices).execute("bluetooth-aa-bb-cc-dd-ee-ff",
                new Action.PlayMedia(URI.create("http://nas.local/music/song.mp3"), "audio/mpeg", "song.mp3", null));
    }

    @Test
    void aResolvedSessionRunsThroughItsExecutorNotTheDevice() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        given(executor.executes(any())).willReturn(true);
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(
                        List.of(new PlayableRef.JellyfinSession("s1", "item-1", 600L, "Android TV")),
                        Set.of(Capability.JELLYFIN_CLIENT), List.of()))),
                List.of(executor));

        Route route = service.play(JELLYFIN_ITEM, "shield");

        assertThat(route).isEqualTo(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"));
        verify(executor).execute(route, shield);
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void resolverNotesExplainWhyNothingRoutes() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(List.of(), Set.of(),
                        List.of("no Jellyfin app is open on Shield")))),
                List.of());

        assertThatThrownBy(() -> service.play(JELLYFIN_ITEM, "shield"))
                .isInstanceOf(UnroutableException.class)
                .hasMessage("Shield: no Jellyfin app is open on Shield");
    }

    @Test
    void notesPrefixThePlannersOwnReasons() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(
                        List.of(new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4?ApiKey=k"), "video/mp4")),
                        Set.of(), List.of("no Jellyfin app is open on Shield")))),
                List.of());

        assertThat(service.plan(JELLYFIN_ITEM, "shield")).isEqualTo(new Route.Unroutable(
                "no Jellyfin app is open on Shield; this device cannot play a direct stream"));
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void plansAndExecutesTheRoute() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");

        Route route = defaultService.play(AppLinks.fromUrl(uri.toString()), "shield");

        assertThat(route).isEqualTo(new Route.OpenAppLink(uri, "youtube"));
        verify(devices).execute("shield", new Action.OpenAppLink(uri));
    }

    @Test
    void namesTheDeviceWhenNothingRoutes() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.noneOf(Capability.class));

        var preparedArg191_0 = AppLinks.fromUrl("https://example.org/a");
        assertThatThrownBy(() -> defaultService.play(preparedArg191_0, "shield"))
                .isInstanceOf(UnroutableException.class)
                .hasMessageContaining("Shield")
                .hasMessageContaining("cannot open app links");
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void anUnknownDeviceIsNotFound() {
        given(devices.device("ghost")).willReturn(Optional.empty());

        var preparedArg202_0 = AppLinks.fromUrl("https://example.org/a");
        assertThatThrownBy(() -> defaultService.play(preparedArg202_0, "ghost"))
                .isInstanceOf(DeviceNotFoundException.class);
    }

    @Test
    void executesACastRoute() {
        PlaybackService castService = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastLoadStrategy(), new CastStreamStrategy())));
        Device castDevice = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now());
        given(devices.device("kitchen")).willReturn(Optional.of(castDevice));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        Route route = castService.play(AppLinks.fromUrl("http://nas.local/films/bunny.mp4"), "kitchen");

        assertThat(route).isInstanceOfSatisfying(Route.Cast.class, cast -> {
            assertThat(cast.receiverAppId()).isEqualTo("CC1AD845");
            verify(devices).execute("kitchen", cast.action());
        });
    }

    @Test
    void previewResolvesOnceAndListsRoutesWithoutExecuting() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER));
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastStreamStrategy())));
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(uri, "youtube"),
                        new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4"), "video/mp4")));

        PlaybackPreview preview = service.preview(item, "shield");

        assertThat(preview.routes()).extracting(RouteKeys::key).containsExactly("app-link", "cast:CC1AD845");
        assertThat(preview.reason()).isNull();
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void previewExplainsWhenNothingRoutes() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.noneOf(Capability.class));
        PlaybackService service = new PlaybackService(devices, new PlaybackPlanner(List.of(new AppLinkStrategy())));
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(URI.create("https://x"), "web")));

        PlaybackPreview preview = service.preview(item, "shield");

        assertThat(preview.routes()).isEmpty();
        assertThat(preview.reason()).contains("cannot open app links");
    }

    @Test
    void attemptPlaysTheFirstRouteAndReportsTheRest() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER));
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastStreamStrategy())));
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(uri, "youtube"),
                        new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4"), "video/mp4")));

        PlayAttempt attempt = service.attempt(item, "shield", Set.of());

        assertThat(attempt).isInstanceOfSatisfying(PlayAttempt.Played.class, played -> {
            assertThat(RouteKeys.key(played.route())).isEqualTo("app-link");
            assertThat(played.remaining()).extracting(RouteKeys::key).containsExactly("cast:CC1AD845");
        });
        verify(devices).execute("shield", new Action.OpenAppLink(uri));
    }

    @Test
    void attemptReportsTheFailedRouteAndTheNextOne() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER));
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastStreamStrategy())));
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube"),
                        new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4"), "video/mp4")));
        willThrow(new ActionFailedException("Shield refused to open the link"))
                .given(devices).execute(eq("shield"), any(Action.OpenAppLink.class));

        PlayAttempt attempt = service.attempt(item, "shield", Set.of());

        assertThat(attempt).isInstanceOfSatisfying(PlayAttempt.Failed.class, failed -> {
            assertThat(RouteKeys.key(failed.route())).isEqualTo("app-link");
            assertThat(failed.remaining()).extracting(RouteKeys::key).containsExactly("cast:CC1AD845");
            assertThat(failed.cause()).hasMessage("Shield refused to open the link");
        });
        verify(devices, never()).execute(eq("shield"), any(Action.CastLoad.class));
    }

    @Test
    void attemptSkipsRoutesByKey() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER));
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastStreamStrategy())));
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube"),
                        new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4"), "video/mp4")));

        PlayAttempt attempt = service.attempt(item, "shield", Set.of("app-link"));

        assertThat(attempt).isInstanceOfSatisfying(PlayAttempt.Played.class,
                played -> assertThat(played.route()).isInstanceOf(Route.Cast.class));
        verify(devices).execute(eq("shield"), any(Action.CastLoad.class));
    }

    @Test
    void attemptWithEverythingSkippedIsUnroutable() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER));
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastStreamStrategy())));
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube"),
                        new PlayableRef.StreamUrl(URI.create("http://nas/x.mp4"), "video/mp4")));

        PlayAttempt attempt = service.attempt(item, "shield", Set.of("app-link", "cast:CC1AD845"));

        assertThat(attempt).isEqualTo(new PlayAttempt.Unroutable(shield, "no other way to play this"));
    }

    @Test
    void offlineAndUnsupportedAreFailuresToo() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        PlaybackService service = new PlaybackService(devices, new PlaybackPlanner(List.of(new AppLinkStrategy())));
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null,
                List.of(new PlayableRef.AppLink(URI.create("https://x"), "web")));
        willThrow(new DeviceOfflineException("Shield is not connected"))
                .given(devices).execute(eq("shield"), any(Action.OpenAppLink.class));

        assertThat(service.attempt(item, "shield", Set.of()))
                .isInstanceOfSatisfying(PlayAttempt.Failed.class,
                        failed -> assertThat(failed.cause()).isInstanceOf(DeviceOfflineException.class));

        willThrow(new IllegalStateException("boom")).given(devices).execute(eq("shield"), any(Action.OpenAppLink.class));

        assertThatThrownBy(() -> service.attempt(item, "shield", Set.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sourceSideRoutesUseTheirExecutor() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        given(executor.executes(any())).willReturn(true);
        PlaybackService service = new PlaybackService(devices, new HomeControlConfiguration().playbackPlanner(),
                List.of(resolverReturning(new PlayableResolver.Resolution(
                        List.of(new PlayableRef.JellyfinSession("s1", "item-1", 600L, "Android TV")),
                        Set.of(Capability.JELLYFIN_CLIENT), List.of()))),
                List.of(executor));

        PlayAttempt attempt = service.attempt(JELLYFIN_ITEM, "shield", Set.of());

        assertThat(attempt).isInstanceOfSatisfying(PlayAttempt.Played.class,
                played -> assertThat(played.route()).isEqualTo(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV")));
        verify(executor).execute(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"), shield);

        willThrow(new ActionFailedException("Jellyfin refused")).given(executor).execute(any(), any());

        assertThat(service.attempt(JELLYFIN_ITEM, "shield", Set.of()))
                .isInstanceOfSatisfying(PlayAttempt.Failed.class,
                        failed -> assertThat(failed.cause()).hasMessage("Jellyfin refused"));
    }

    @Test
    void executesACastMessageRoute() {
        PlaybackPlanner castMessagePlanner = new PlaybackPlanner(List.of(new CastMessageStrategy()));
        PlaybackService castMessageService = new PlaybackService(devices, castMessagePlanner);
        Device castDevice = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now());
        given(devices.device("kitchen")).willReturn(Optional.of(castDevice));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER));
        PlayableRef.CastMessage message = new PlayableRef.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                Map.of("command", "PlayNow"), "the Jellyfin receiver");
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(message));

        Route route = castMessageService.play(item, "kitchen");

        assertThat(route).isInstanceOfSatisfying(Route.CastMessage.class,
                cast -> verify(devices).execute("kitchen", cast.action()));
    }

    private final Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9",
            Map.of("cast", Map.of()), Instant.now());
    private static final URI WATCH = URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
    private static final ContentItem LOUNGE_ITEM = new ContentItem("aqz-KE-bpKQ", "youtube", ContentKind.VIDEO,
            "Big Buck Bunny", null, null, List.of(new PlayableRef.YouTubeLounge("aqz-KE-bpKQ")));

    @Test
    void aLoungeRouteRunsThroughItsExecutor() {
        given(devices.device("kitchen")).willReturn(Optional.of(kitchen));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER));
        given(executor.executes(any())).willReturn(true);
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new YouTubeLoungeStrategy())), List.of(), List.of(executor));

        Route route = service.play(LOUNGE_ITEM, "kitchen");

        assertThat(route).isEqualTo(new Route.YouTubeLounge("aqz-KE-bpKQ"));
        verify(executor).execute(route, kitchen);
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void withoutAnExecutorTheLoungeRouteIsSwitchedOff() {
        given(devices.device("kitchen")).willReturn(Optional.of(kitchen));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER));
        PlaybackService service = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new YouTubeLoungeStrategy())), List.of(), List.of());

        assertThatThrownBy(() -> service.play(LOUNGE_ITEM, "kitchen"))
                .isInstanceOf(UnroutableException.class)
                .hasMessage("Kitchen: YouTube is switched off on this server");
    }

    @Test
    void attemptFallsThroughToLoungeAfterAFailedAppLink() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER));
        given(executor.executes(any())).willReturn(true);
        PlaybackService service = new PlaybackService(devices,
                new HomeControlConfiguration().playbackPlanner(), List.of(), List.of(executor));
        ContentItem item = LOUNGE_ITEM.withPlayables(List.of(new PlayableRef.AppLink(WATCH, "youtube"),
                new PlayableRef.YouTubeLounge("aqz-KE-bpKQ")));
        willThrow(new ActionFailedException("Shield refused to open the link"))
                .given(devices).execute(eq("shield"), any(Action.OpenAppLink.class));

        assertThat(service.attempt(item, "shield", Set.of()))
                .isInstanceOfSatisfying(PlayAttempt.Failed.class, failed -> {
                    assertThat(RouteKeys.key(failed.route())).isEqualTo("app-link");
                    assertThat(failed.remaining()).containsExactly(new Route.YouTubeLounge("aqz-KE-bpKQ"));
                });
        verify(executor, never()).execute(any(), any());

        assertThat(service.attempt(item, "shield", Set.of("app-link")))
                .isInstanceOfSatisfying(PlayAttempt.Played.class,
                        played -> assertThat(played.route()).isEqualTo(new Route.YouTubeLounge("aqz-KE-bpKQ")));
        verify(executor).execute(new Route.YouTubeLounge("aqz-KE-bpKQ"), shield);
    }
}
