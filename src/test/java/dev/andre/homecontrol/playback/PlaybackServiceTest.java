package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.playback.AppLinkStrategy;
import dev.andre.homecontrol.core.playback.AppLinks;
import dev.andre.homecontrol.core.playback.CastLoadStrategy;
import dev.andre.homecontrol.core.playback.CastMessageStrategy;
import dev.andre.homecontrol.core.playback.CastStreamStrategy;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.UnroutableException;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlaybackServiceTest {

    private final DeviceManager devices = mock(DeviceManager.class);
    private final PlaybackService service = new PlaybackService(devices,
            new PlaybackPlanner(List.of(new AppLinkStrategy())));
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());

    @Test
    void plansAndExecutesTheRoute() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.of(Capability.APP_LINK));
        URI uri = URI.create("https://www.youtube.com/watch?v=abc");

        Route route = service.play(AppLinks.fromUrl(uri.toString()), "shield");

        assertThat(route).isEqualTo(new Route.OpenAppLink(uri, "youtube"));
        verify(devices).execute("shield", new Action.OpenAppLink(uri));
    }

    @Test
    void namesTheDeviceWhenNothingRoutes() {
        given(devices.device("shield")).willReturn(Optional.of(shield));
        given(devices.capabilities("shield")).willReturn(EnumSet.noneOf(Capability.class));

        assertThatThrownBy(() -> service.play(AppLinks.fromUrl("https://example.org/a"), "shield"))
                .isInstanceOf(UnroutableException.class)
                .hasMessageContaining("Shield")
                .hasMessageContaining("cannot open app links");
        verify(devices, never()).execute(any(), any());
    }

    @Test
    void anUnknownDeviceIsNotFound() {
        given(devices.device("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.play(AppLinks.fromUrl("https://example.org/a"), "ghost"))
                .isInstanceOf(DeviceNotFoundException.class);
    }

    @Test
    void executesACastRoute() {
        PlaybackService castService = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new AppLinkStrategy(), new CastLoadStrategy(), new CastStreamStrategy())));
        Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now());
        given(devices.device("kitchen")).willReturn(Optional.of(kitchen));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        Route route = castService.play(AppLinks.fromUrl("http://nas.local/films/bunny.mp4"), "kitchen");

        assertThat(route).isInstanceOfSatisfying(Route.Cast.class, cast -> {
            assertThat(cast.receiverAppId()).isEqualTo("CC1AD845");
            verify(devices).execute("kitchen", cast.action());
        });
    }

    @Test
    void executesACastMessageRoute() {
        PlaybackPlanner castMessagePlanner = new PlaybackPlanner(List.of(new CastMessageStrategy()));
        PlaybackService castMessageService = new PlaybackService(devices, castMessagePlanner);
        Device kitchen = new Device("kitchen", "Kitchen", DeviceKind.CAST, "10.0.0.9", Map.of("cast", Map.of()), Instant.now());
        given(devices.device("kitchen")).willReturn(Optional.of(kitchen));
        given(devices.capabilities("kitchen")).willReturn(EnumSet.of(Capability.CAST_RECEIVER));
        PlayableRef.CastMessage message = new PlayableRef.CastMessage("F007D354", "urn:x-cast:com.connectsdk",
                Map.of("command", "PlayNow"), "the Jellyfin receiver");
        ContentItem item = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(message));

        Route route = castMessageService.play(item, "kitchen");

        assertThat(route).isInstanceOfSatisfying(Route.CastMessage.class,
                cast -> verify(devices).execute("kitchen", cast.action()));
    }
}
