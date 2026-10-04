package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.*;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.playback.DelegatedRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.andre.homecontrol.core.content.ContentSourceException;
import java.io.IOException;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JellyfinVlcExecutorTest {
    private static final String ID = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";
    private final JellyfinSetupService setup = mock(JellyfinSetupService.class);
    private final JellyfinClient client = mock(JellyfinClient.class);
    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final DeviceCommands commands = mock(DeviceCommands.class);
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.EPOCH);
    private final JellyfinVlcExecutor executor = new JellyfinVlcExecutor(setup, client, devices, commands, Duration.ofSeconds(1));
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void connected() {
        var settings = new JellyfinSettings(URI.create("http://internal:8096"), URI.create("https://nas.lan/jellyfin"),
                "server", "nas", "10.11.2", ID, "user", JellyfinSettings.AuthMode.API_KEY, "hc", "F007D354", Map.of());
        when(setup.settings()).thenReturn(Optional.of(settings));
        when(setup.connection()).thenReturn(Optional.of(new JellyfinConnection(settings.serverUrl(), "secret+&token", "hc", ID)));
        when(devices.state("shield")).thenReturn(DeviceState.initial().withStatus(DeviceStatus.CONNECTED).withPower(true));
        when(devices.capabilities("shield")).thenReturn(Set.of(Capability.REMOTE_KEYS, Capability.APP_LINK,
                Capability.ANDROID_APPS));
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("{\"MediaType\":\"Video\"}"));
        when(client.post(any(), eq("/Items/" + ID + "/PlaybackInfo"), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[{"Id":"source+1","Container":"mkv","SupportsDirectPlay":true}]}
                """));
    }

    @Test
    void opensOriginalMkvWithDeviceFacingAddressAndEncodedCredentialsOnce() {
        executor.execute(new JellyfinRoute.Vlc(ID), shield);
        // ApiKey, not the legacy api_key a server with legacy authorization switched off refuses.
        verify(commands).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID
                        + "/stream?static=true&mediaSourceId=source%2B1&ApiKey=secret%2B%26token")));
        verify(commands, times(1)).execute(anyString(), any());
    }

    @Test
    void tellsTheDeviceWhatItLaunchedSoItCanShowTheTitle() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"Id":"%s","Type":"Episode","MediaType":"Video","Name":"Pilot","SeriesName":"Severance",
                 "ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":34200000000}
                """.formatted(ID)));

        executor.execute(new JellyfinRoute.Vlc(ID), shield);

        var sent = org.mockito.ArgumentCaptor.forClass(Action.class);
        verify(commands).execute(eq("shield"), sent.capture());
        assertThat(((Action.OpenAppLink) sent.getValue()).media())
                .isEqualTo(new LaunchedMedia("org.videolan.vlc", "Severance · S1:E1 · Pilot", 3420.0));
    }

    @Test
    void launchesWithoutAHintWhenJellyfinGivesTheItemNoName() {
        executor.execute(new JellyfinRoute.Vlc(ID), shield);

        var sent = org.mockito.ArgumentCaptor.forClass(Action.class);
        verify(commands).execute(eq("shield"), sent.capture());
        assertThat(((Action.OpenAppLink) sent.getValue()).media()).isNull();
    }

    @Test
    void refusesMediaThatRequiresTranscodingOrOpeningALiveStream() {
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[
                  {"Id":"transcode","SupportsDirectPlay":false},
                  {"Id":"live","SupportsDirectPlay":true,"RequiresOpening":true}
                ]}
                """));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("no direct stream");
        verifyNoInteractions(commands);
        verify(devices, never()).state(anyString());
    }

    @Test
    void aFailedWakeNeverSendsAPlaybackLink() {
        when(devices.state("shield")).thenReturn(DeviceState.initial().withStatus(DeviceStatus.CONNECTED));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("ready");
        verify(commands, never()).execute(anyString(), isA(Action.OpenAppLink.class));
    }

    @Test
    void aFailedLinkWriteDoesNotLeakCredentialsOrRetryPlayback() {
        doThrow(new DeviceOfflineException("failed opening vlc://https://nas/?ApiKey=secret-token"))
                .when(commands).execute(anyString(), isA(Action.OpenAppLink.class));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(DeviceOfflineException.class).hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("ApiKey");
        verify(commands, times(1)).execute(anyString(), isA(Action.OpenAppLink.class));
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
        var bounded = new JellyfinVlcExecutor(setup, client, devices, commands, Duration.ofMillis(100));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);
        try {
            assertThatThrownBy(() -> bounded.execute(route, shield))
                    .isInstanceOf(ActionFailedException.class).hasMessageContaining("in time");
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(commands);
            verify(devices, never()).state(anyString());
        } finally {
            releaseLookup.countDown();
        }
    }

    @Test
    void aDeviceThatRunsNoAndroidAppsIsRefused() {
        when(devices.capabilities("shield")).thenReturn(Set.of(Capability.REMOTE_KEYS, Capability.APP_LINK));
        var route = new JellyfinRoute.Vlc(ID);

        assertThatThrownBy(() -> executor.execute(route, shield)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(commands);
        verify(devices, never()).state(anyString());
    }

    @Test
    void theStartupTimeoutMustBePositive() {
        assertThatThrownBy(() -> new JellyfinVlcExecutor(setup, client, devices, commands, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Startup timeout must be positive");
    }

    @Test
    void anotherJellyfinRouteIsRefused() {
        var route = new JellyfinRoute.App(ID, 0);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("VLC needs an Android TV device");
        verifyNoInteractions(commands, client);
    }

    @Test
    void anUnconfiguredOrDisconnectedJellyfinCannotPrepareAStream() {
        String prepare = "Could not prepare the Jellyfin stream for VLC; check the Jellyfin connection and media availability";
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);
        when(setup.connection()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessage(prepare);

        when(setup.settings()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessage(prepare);
        verifyNoInteractions(commands, client);
    }

    @Test
    void aJellyfinServerThatRefusesTheItemCannotPrepareAStream() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenThrow(new JellyfinException(
                ContentSourceException.Kind.NOT_FOUND, "Jellyfin has no such item"));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Could not prepare the Jellyfin stream for VLC; check the Jellyfin connection and media availability");
        verifyNoInteractions(commands);
    }

    @Test
    void aLookupThatFailsUnexpectedlyCannotPrepareAStream() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenAnswer(_ -> {
            throw new IOException("stream closed");
        });
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessage("Could not prepare the Jellyfin stream for VLC");
        verifyNoInteractions(commands);
    }

    @Test
    void onlyVideoAndAudioItemsOpenInVlc() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("{\"MediaType\":\"Photo\"}"));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessage("VLC can open Jellyfin video and audio items only");
        verifyNoInteractions(commands);
    }

    @Test
    void anAudioItemOpensItsAudioStreamAndItsTitleWithoutALength() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree(
                "{\"Type\":\"Movie\",\"MediaType\":\"Audio\",\"Name\":\"Radio play\"}"));

        executor.execute(new JellyfinRoute.Vlc(ID), shield);

        var sent = ArgumentCaptor.forClass(Action.class);
        verify(commands).execute(eq("shield"), sent.capture());
        Action.OpenAppLink link = (Action.OpenAppLink) sent.getValue();
        assertThat(link.uri()).hasToString("vlc://https://nas.lan/jellyfin/Audio/" + ID
                + "/stream?static=true&mediaSourceId=source%2B1&ApiKey=secret%2B%26token");
        assertThat(link.media()).isEqualTo(new LaunchedMedia("org.videolan.vlc", "Radio play", null));
    }

    @Test
    void skipsSourcesVlcCannotOpenAsIsAndUsesTheFirstThatItCan() {
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[
                  {"Id":"","SupportsDirectPlay":true},
                  {"Id":"closing","SupportsDirectPlay":true,"RequiresClosing":true},
                  {"Id":"endless","SupportsDirectPlay":true,"IsInfiniteStream":true},
                  {"Id":"file","SupportsDirectPlay":true}
                ]}
                """));

        executor.execute(new JellyfinRoute.Vlc(ID), shield);

        var sent = ArgumentCaptor.forClass(Action.class);
        verify(commands).execute(eq("shield"), sent.capture());
        assertThat(((Action.OpenAppLink) sent.getValue()).uri().toString()).contains("mediaSourceId=file&");
    }

    @Test
    void aDeviceThatMustBePairedAgainCannotOpenVlc() {
        when(devices.state("shield")).thenReturn(DeviceState.initial().withStatus(DeviceStatus.UNPAIRED));
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessage("Shield must be paired before VLC can start");
        verifyNoInteractions(commands);
    }

    @Test
    void wakesASleepingDeviceEvenAfterAWakeThatWasLostThenSendsTheLinkOnce() {
        DeviceState off = DeviceState.initial().withStatus(DeviceStatus.CONNECTED).withPower(false);
        DeviceState on = off.withPower(true);
        when(devices.state("shield")).thenReturn(DeviceState.initial(), off, on);
        doThrow(new DeviceOfflineException("Shield is not connected"))
                .when(commands).execute("shield", new Action.PressKey(RemoteKey.WAKEUP));
        var patient = new JellyfinVlcExecutor(setup, client, devices, commands, Duration.ofSeconds(10));

        patient.execute(new JellyfinRoute.Vlc(ID), shield);

        verify(commands, times(1)).execute("shield", new Action.PressKey(RemoteKey.WAKEUP));
        verify(commands, times(1)).execute(eq("shield"), isA(Action.OpenAppLink.class));
    }

    @Test
    void anInterruptedStartSendsNothing() {
        DelegatedRoute route = new JellyfinRoute.Vlc(ID);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.execute(route, shield))
                    .isInstanceOf(ActionFailedException.class).hasMessage("VLC startup was interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(commands);
        } finally {
            Thread.interrupted();
        }
    }
}
