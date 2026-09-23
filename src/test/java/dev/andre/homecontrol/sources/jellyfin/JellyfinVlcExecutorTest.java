package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.*;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
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
    void resumesVideoWithFreshJellyfinPositionAndCopiesSelectedTracks() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Video","RunTimeTicks":14400000000,
                 "UserData":{"PlaybackPositionTicks":6123456789,"Played":false}}
                """));
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[{"Id":"source+1","Container":"mkv","SupportsDirectPlay":true,
                 "SupportsDirectStream":true,"DefaultAudioStreamIndex":2,"DefaultSubtitleStreamIndex":4,
                 "MediaStreams":[
                   {"Index":0,"Type":"Video","Codec":"hevc"},
                   {"Index":2,"Type":"Audio","Codec":"ac3"},
                   {"Index":4,"Type":"Subtitle","Codec":"ass","IsTextSubtitleStream":true},
                   {"Index":7,"Type":"Subtitle","Codec":"mov_text","IsTextSubtitleStream":true}]}]}
                """));

        executor.execute(new Route.JellyfinVlc(ID), shield);

        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID + "/stream.mkv?static=false&startTimeTicks=6123456789"
                        + "&videoCodec=copy&audioCodec=copy&subtitleMethod=Embed&subtitleCodec=copy"
                        + "&audioStreamIndex=2&subtitleStreamIndex=4"
                        + "&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
        verify(devices, times(1)).execute(anyString(), any());
        var request = ArgumentCaptor.forClass(JsonNode.class);
        verify(client).post(any(), eq("/Items/" + ID + "/PlaybackInfo"), anyMap(), request.capture());
        assertThat(request.getValue().path("StartTimeTicks").asLong()).isEqualTo(6_123_456_789L);
        assertThat(request.getValue().path("EnableDirectStream").asBoolean()).isTrue();
        assertThat(request.getValue().path("EnableTranscoding").asBoolean()).isFalse();
    }

    @Test
    void resumesWithoutRequestingSubtitlesWhenJellyfinHasNoneSelected() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Video","UserData":{"PlaybackPositionTicks":6120000000}}
                """));
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[{"Id":"source+1","SupportsDirectPlay":true,"SupportsDirectStream":true}]}
                """));

        executor.execute(new Route.JellyfinVlc(ID), shield);

        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID + "/stream.mkv?static=false&startTimeTicks=6120000000"
                        + "&videoCodec=copy&audioCodec=copy&subtitleMethod=Embed&subtitleCodec=copy&subtitleStreamIndex=-1"
                        + "&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
    }

    @Test
    void convertsSelectedMp4TextSubtitlesToAMatroskaCompatibleFormat() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Video","UserData":{"PlaybackPositionTicks":6120000000}}
                """));
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[{"Id":"source+1","Container":"mp4","SupportsDirectPlay":true,
                 "SupportsDirectStream":true,"DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":2,
                 "MediaStreams":[
                   {"Index":0,"Type":"Video","Codec":"h264"},
                   {"Index":1,"Type":"Audio","Codec":"aac"},
                   {"Index":2,"Type":"Subtitle","Codec":"mov_text","IsTextSubtitleStream":true}]}]}
                """));

        executor.execute(new Route.JellyfinVlc(ID), shield);

        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID + "/stream.mkv?static=false&startTimeTicks=6120000000"
                        + "&videoCodec=copy&audioCodec=copy&subtitleMethod=Embed&subtitleCodec=srt"
                        + "&audioStreamIndex=1&subtitleStreamIndex=2"
                        + "&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 14_400_000_000L, Long.MAX_VALUE})
    void usesOriginalStreamWhenVideoHasNoUsableResumePoint(long position) {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Video","RunTimeTicks":14400000000,"UserData":{"PlaybackPositionTicks":%d}}
                """.formatted(position)));

        executor.execute(new Route.JellyfinVlc(ID), shield);

        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID + "/stream?static=true&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
    }

    @Test
    void completedVideoStartsFromBeginningEvenIfJellyfinRetainsAPosition() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Video","UserData":{"PlaybackPositionTicks":6120000000,"Played":true}}
                """));

        executor.execute(new Route.JellyfinVlc(ID), shield);

        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Videos/" + ID + "/stream?static=true&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
    }

    @Test
    void audioContinuesToUseOriginalStream() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Audio","UserData":{"PlaybackPositionTicks":6120000000}}
                """));

        executor.execute(new Route.JellyfinVlc(ID), shield);

        verify(devices).execute("shield", new Action.OpenAppLink(URI.create(
                "vlc://https://nas.lan/jellyfin/Audio/" + ID + "/stream?static=true&mediaSourceId=source%2B1&api_key=secret%2B%26token")));
    }

    @Test
    void refusesResumeWhenJellyfinDoesNotOfferDirectStreaming() {
        when(client.get(any(), eq("/Items/" + ID), anyMap())).thenReturn(json.readTree("""
                {"MediaType":"Video","UserData":{"PlaybackPositionTicks":6120000000}}
                """));

        assertThatThrownBy(() -> executor.execute(new Route.JellyfinVlc(ID), shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("resume");
        verifyNoInteractions(devices);
    }

    @Test
    void refusesMediaThatRequiresTranscodingOrOpeningALiveStream() {
        when(client.post(any(), anyString(), anyMap(), any())).thenReturn(json.readTree("""
                {"MediaSources":[
                  {"Id":"transcode","SupportsDirectPlay":false},
                  {"Id":"live","SupportsDirectPlay":true,"RequiresOpening":true}
                ]}
                """));
        assertThatThrownBy(() -> executor.execute(new Route.JellyfinVlc(ID), shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("no direct stream");
        verifyNoInteractions(devices);
    }

    @Test
    void aFailedWakeNeverSendsAPlaybackLink() {
        when(devices.state("shield")).thenReturn(DeviceState.initial().withStatus(DeviceStatus.CONNECTED));
        assertThatThrownBy(() -> executor.execute(new Route.JellyfinVlc(ID), shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("ready");
        verify(devices, never()).execute(anyString(), isA(Action.OpenAppLink.class));
    }

    @Test
    void aFailedLinkWriteDoesNotLeakCredentialsOrRetryPlayback() {
        doThrow(new DeviceOfflineException("failed opening vlc://https://nas/?api_key=secret-token"))
                .when(devices).execute(anyString(), isA(Action.OpenAppLink.class));
        assertThatThrownBy(() -> executor.execute(new Route.JellyfinVlc(ID), shield))
                .isInstanceOf(DeviceOfflineException.class).hasMessageNotContaining("secret-token")
                .hasMessageNotContaining("api_key");
        verify(devices, times(1)).execute(anyString(), isA(Action.OpenAppLink.class));
    }

    @Test
    void stalledStreamLookupIsCancelledAndCannotPlayLater() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        when(client.get(any(), anyString(), anyMap())).thenAnswer(_ -> {
            try { Thread.sleep(5_000); }
            catch (InterruptedException e) { cancelled.countDown(); Thread.currentThread().interrupt(); }
            return json.readTree("{\"MediaType\":\"Video\"}");
        });
        var bounded = new JellyfinVlcExecutor(setup, client, devices, Duration.ofMillis(100));
        assertThatThrownBy(() -> bounded.execute(new Route.JellyfinVlc(ID), shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("in time");
        assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(devices);
    }
}
