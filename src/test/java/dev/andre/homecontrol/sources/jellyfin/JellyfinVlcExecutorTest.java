package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.*;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JellyfinVlcExecutorTest {
    private static final String ID = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";
    private final JellyfinSetupService setup = mock(JellyfinSetupService.class);
    private final JellyfinClient client = mock(JellyfinClient.class);
    private final DeviceManager devices = mock(DeviceManager.class);
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.EPOCH);
    private final JellyfinVlcExecutor executor = new JellyfinVlcExecutor(setup, client, devices, Duration.ofSeconds(1));
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void connected() {
        var settings = new JellyfinSettings(URI.create("http://internal:8096"), URI.create("https://nas.lan/jellyfin"),
                "server", "nas", "10.11.2", ID, "user", JellyfinSettings.AuthMode.API_KEY, "hc", "F007D354", Map.of());
        when(setup.settings()).thenReturn(Optional.of(settings));
        when(setup.connection()).thenReturn(Optional.of(new JellyfinConnection(settings.serverUrl(), "secret+&token", "hc", ID)));
        when(devices.state("shield")).thenReturn(DeviceState.initial().withStatus(DeviceStatus.CONNECTED).withPower(true));
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("{\"MediaType\":\"Video\"}"));
        when(client.post(any(), eq("/Items/" + ID + "/PlaybackInfo"), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[{"Id":"source+1","Container":"mkv","SupportsDirectPlay":true}]}
                """));
    }

    @Test
    void opensOriginalMkvWithDeviceFacingAddressAndEncodedCredentialsOnce() {
        executor.execute(new Route.JellyfinVlc(ID), shield);
        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID + "/stream?static=true&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
        verify(devices, times(1)).execute(anyString(), any());
    }

    @Test
    void refusesMediaThatRequiresTranscodingOrOpeningALiveStream() {
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[
                  {"Id":"transcode","SupportsDirectPlay":false},
                  {"Id":"live","SupportsDirectPlay":true,"RequiresOpening":true}
                ]}
                """));
        Route route = new Route.JellyfinVlc(ID);
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("no direct stream");
        verifyNoInteractions(devices);
    }

    @Test
    void aFailedWakeNeverSendsAPlaybackLink() {
        when(devices.state("shield")).thenReturn(DeviceState.initial().withStatus(DeviceStatus.CONNECTED));
        Route route = new Route.JellyfinVlc(ID);
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("ready");
        verify(devices, never()).execute(anyString(), isA(Action.OpenAppLink.class));
    }

    @Test
    void aFailedLinkWriteDoesNotLeakCredentialsOrRetryPlayback() {
        doThrow(new DeviceOfflineException("failed opening vlc://https://nas/?api_key=secret-token"))
                .when(devices).execute(anyString(), isA(Action.OpenAppLink.class));
        Route route = new Route.JellyfinVlc(ID);
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(DeviceOfflineException.class).hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("api_key");
        verify(devices, times(1)).execute(anyString(), isA(Action.OpenAppLink.class));
    }

    @Test
    void stalledStreamLookupIsCancelledAndCannotPlayLater() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        when(client.get(any(), anyString(), anyMap())).thenAnswer(_ -> {
            try { releaseLookup.await(); }
            catch (InterruptedException _) { cancelled.countDown(); Thread.currentThread().interrupt(); }
            return json.readTree("{\"MediaType\":\"Video\"}");
        });
        var bounded = new JellyfinVlcExecutor(setup, client, devices, Duration.ofMillis(100));
        Route route = new Route.JellyfinVlc(ID);
        try {
            assertThatThrownBy(() -> bounded.execute(route, shield))
                    .isInstanceOf(ActionFailedException.class).hasMessageContaining("in time");
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(devices);
        } finally {
            releaseLookup.countDown();
        }
    }
}
