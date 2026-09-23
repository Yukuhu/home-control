package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.PlayableResolver;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.JellyfinSessionStrategy;
import dev.andre.homecontrol.core.playback.CastMessageStrategy;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackService;
import dev.andre.homecontrol.playback.PlayAttempt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class JellyfinPlayableResolverTest {

    private static final String ITEM_ID = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";
    private static final PlayableRef.JellyfinItem WANTED =
            new PlayableRef.JellyfinItem(FakeJellyfinServer.SERVER_ID, ITEM_ID, 6_120_000_000L);

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20));
    private final JellyfinSetupService setup = mock(JellyfinSetupService.class);
    private final JellyfinSessions sessions = new JellyfinSessions(client, setup, JellyfinSessions::resolve);
    private final JellyfinStreams streams = new JellyfinStreams(client);
    private final JellyfinPlayableResolver resolver = new JellyfinPlayableResolver(setup, sessions, client, streams);
    private FakeJellyfinServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    private void connected() throws IOException {
        fake = new FakeJellyfinServer();
        fake.respond("GET", "/Sessions", 200, "sessions.json");
        fake.respond("GET", "/Items/" + ITEM_ID, 200, "item-episode.json");
        fake.respond("POST", "/Items/" + ITEM_ID + "/PlaybackInfo", 200, "playback-info-direct.json");
        given(setup.settings()).willReturn(Optional.of(settings()));
        given(setup.connection()).willReturn(Optional.of(new JellyfinConnection(
                fake.url(), FakeJellyfinServer.ACCESS_TOKEN, "hc-test-device", FakeJellyfinServer.USER_ID)));
    }

    private static JellyfinSettings settings() {
        return new JellyfinSettings(URI.create("http://nas:8096"), URI.create("http://192.168.1.20:8096"),
                FakeJellyfinServer.SERVER_ID, "nas", "10.11.2", FakeJellyfinServer.USER_ID, "andre",
                JellyfinSettings.AuthMode.PASSWORD, "hc-test-device", "F007D354", Map.of());
    }

    private static Device device(String name, String host) {
        return new Device("dev-" + name, name, DeviceKind.ANDROID_TV, host, Map.of(), Instant.EPOCH);
    }

    private static ContentItem item(PlayableRef ref) {
        return new ContentItem(ITEM_ID, "jellyfin", ContentKind.EPISODE, "Northern Lights", null, null, List.of(ref));
    }

    @Test
    void aPairedShieldCanPlayEvenWhenJellyfinIsClosed() throws IOException {
        connected();
        fake.respondJson("GET", "/Sessions", 200, "[]");
        Device shield = device("Shield", "10.0.0.5").withAdapter("androidtv", Map.of());

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), shield,
                Set.of(Capability.APP_LINK, Capability.REMOTE_KEYS));

        assertThat(resolution.playables()).hasSize(1);
        assertThat(resolution.playables().getFirst().kindLabel()).isEqualTo("Jellyfin app");
        assertThat(resolution.liveCapabilities()).contains(Capability.JELLYFIN_CLIENT);
        assertThat(fake.requests("GET", "/Items/" + ITEM_ID)).isEmpty();
    }

    @Test
    void aMergedShieldOffersCastOnlyAfterTheUserChoosesToRetry() throws IOException {
        connected();
        fake.respondJson("GET", "/Sessions", 200, "[]");
        Device shield = device("Shield", "10.0.0.5").withAdapter("androidtv", Map.of()).withAdapter("cast", Map.of());
        DeviceManager devices = mock(DeviceManager.class);
        given(devices.device(shield.id())).willReturn(Optional.of(shield));
        given(devices.state(shield.id())).willReturn(DeviceState.unpaired());
        given(devices.capabilities(shield.id())).willReturn(Set.of(Capability.APP_LINK, Capability.REMOTE_KEYS, Capability.CAST_RECEIVER));
        PlaybackService playback = new PlaybackService(devices,
                new PlaybackPlanner(List.of(new JellyfinSessionStrategy(), new CastMessageStrategy())),
                List.of(resolver), List.of(new JellyfinRouteExecutor(sessions, devices, Duration.ofSeconds(1))));

        assertThat(playback.attempt(item(WANTED), shield.id(), Set.of()))
                .isInstanceOfSatisfying(PlayAttempt.Failed.class, failed -> {
                    assertThat(failed.route()).isInstanceOf(Route.JellyfinApp.class);
                    assertThat(failed.remaining()).hasSize(1).allMatch(route -> route instanceof Route.CastMessage);
                });
        verify(devices, never()).execute(anyString(), any());

        assertThat(playback.attempt(item(WANTED), shield.id(), Set.of("jellyfin-app")))
                .isInstanceOfSatisfying(PlayAttempt.Played.class,
                        played -> assertThat(played.route()).isInstanceOf(Route.CastMessage.class));
        verify(devices).execute(eq(shield.id()), isA(Action.CastMessage.class));
    }

    @Test
    void anOpenAppWinsAndNothingElseIsFetched() throws IOException {
        connected();
        Device livingRoom = device("Living Room", "192.168.1.50");

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), livingRoom,
                Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER));

        assertThat(resolution.playables()).containsExactly(
                new PlayableRef.JellyfinSession("1d2c3b4a59687f6e5d4c3b2a19081726", ITEM_ID, 6_120_000_000L, "Android TV"));
        assertThat(resolution.liveCapabilities()).containsExactly(Capability.JELLYFIN_CLIENT);
        assertThat(resolution.notes()).isEmpty();
        assertThat(fake.requests("GET", "/Items/" + ITEM_ID)).isEmpty();
        assertThat(fake.requests("POST", "/Items/" + ITEM_ID + "/PlaybackInfo")).isEmpty();
    }

    @Test
    void aCastDeviceGetsOnlyTheReceiverMessageNoDirectStreamIsFetched() throws IOException {
        connected();
        Device kitchen = device("Kitchen", "10.0.0.9");

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), kitchen,
                Set.of(Capability.CAST_RECEIVER, Capability.VOLUME));

        assertThat(resolution.playables()).hasSize(1);
        PlayableRef.CastMessage message = (PlayableRef.CastMessage) resolution.playables().get(0);
        assertThat(message.receiverAppId()).isEqualTo("F007D354");
        assertThat(message.namespace()).isEqualTo("urn:x-cast:com.connectsdk");
        assertThat(message.message()).containsEntry("accessToken", FakeJellyfinServer.ACCESS_TOKEN)
                .containsEntry("receiverName", "Kitchen");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) message.message().get("options");
        assertThat(options).containsEntry("startPositionTicks", 6_120_000_000L);

        // The direct stream is unreachable from a Cast device (the receiver message always wins in the
        // planner), so PlaybackInfo is never requested for one — that request also carries the API key.
        assertThat(fake.requests("POST", "/Items/" + ITEM_ID + "/PlaybackInfo")).isEmpty();

        assertThat(resolution.liveCapabilities()).isEmpty();
        assertThat(resolution.notes()).containsExactly("no Jellyfin app is open on Kitchen");
    }

    @Test
    void aMediaRendererOnlyDeviceGetsTheStreamAndNoReceiverMessage() throws IOException {
        connected();
        Device kitchen = device("Kitchen", "10.0.0.9");

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), kitchen,
                Set.of(Capability.MEDIA_RENDERER));

        assertThat(resolution.playables()).hasSize(1);
        PlayableRef.StreamUrl stream = (PlayableRef.StreamUrl) resolution.playables().getFirst();
        assertThat(stream.url()).isEqualTo(URI.create("http://192.168.1.20:8096/Videos/" + ITEM_ID
                + "/stream.mp4?static=true&mediaSourceId=" + ITEM_ID + "&ApiKey=" + FakeJellyfinServer.ACCESS_TOKEN));
        assertThat(stream.mimeType()).isEqualTo("video/mp4");
        assertThat(fake.requests("POST", "/Items/" + ITEM_ID + "/PlaybackInfo")).hasSize(1);

        assertThat(resolution.liveCapabilities()).isEmpty();
        assertThat(resolution.notes()).containsExactly("no Jellyfin app is open on Kitchen");
    }

    @Test
    void aMediaRendererGetsTheAudioStreamOnly() throws IOException {
        String trackId = "c0ffee00c0ffee00c0ffee00c0ffee01";
        connected();
        fake.respond("GET", "/Items/" + trackId, 200, "item-track.json");
        fake.respond("POST", "/Items/" + trackId + "/PlaybackInfo", 200, "playback-info-audio.json");
        Device speaker = new Device("upnp-10-0-0-30", "Kitchen Speaker", DeviceKind.UPNP, "10.0.0.30",
                Map.of("upnp", Map.of()), Instant.now());
        PlayableRef.JellyfinItem track = new PlayableRef.JellyfinItem(FakeJellyfinServer.SERVER_ID, trackId, 0);
        ContentItem song = new ContentItem(trackId, "jellyfin", ContentKind.TRACK, "Bunny Song", "The Rabbits", null, List.of(track));

        PlayableResolver.Resolution resolution = resolver.resolve(track, song, speaker,
                java.util.EnumSet.of(Capability.MEDIA_RENDERER, Capability.VOLUME));

        assertThat(resolution.playables()).containsExactly(new PlayableRef.StreamUrl(URI.create("http://192.168.1.20:8096/Audio/"
                + trackId + "/stream.flac?static=true&mediaSourceId=" + trackId + "&ApiKey=" + FakeJellyfinServer.ACCESS_TOKEN),
                "audio/flac"));
        assertThat(resolution.liveCapabilities()).isEmpty();
        assertThat(resolution.notes()).containsExactly("no Jellyfin app is open on Kitchen Speaker");
    }

    @Test
    void aLocalAudioSinkStreamsFromTheServersOwnAddress() throws IOException {
        String trackId = "c0ffee00c0ffee00c0ffee00c0ffee01";
        connected();
        fake.respond("GET", "/Items/" + trackId, 200, "item-track.json");
        fake.respond("POST", "/Items/" + trackId + "/PlaybackInfo", 200, "playback-info-audio.json");
        Device speaker = new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF",
                Map.of("bluetooth", Map.of()), Instant.now());
        PlayableRef.JellyfinItem track = new PlayableRef.JellyfinItem(FakeJellyfinServer.SERVER_ID, trackId, 0);
        ContentItem song = new ContentItem(trackId, "jellyfin", ContentKind.TRACK, "Bunny Song", "The Rabbits", null, List.of(track));

        PlayableResolver.Resolution resolution = resolver.resolve(track, song, speaker,
                java.util.EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME));

        assertThat(resolution.playables()).containsExactly(new PlayableRef.StreamUrl(URI.create("http://nas:8096/Audio/"
                + trackId + "/stream.flac?static=true&mediaSourceId=" + trackId + "&ApiKey=" + FakeJellyfinServer.ACCESS_TOKEN),
                "audio/flac"));
        assertThat(resolution.playables()).noneMatch(ref -> ((PlayableRef.StreamUrl) ref).url().toString().contains("192.0.2.10"));
        assertThat(resolution.notes()).containsExactly("no Jellyfin app is open on JBL Flip 5");
    }

    @Test
    void aMixedDeviceKeepsTheDeviceAddress() throws IOException {
        String trackId = "c0ffee00c0ffee00c0ffee00c0ffee01";
        connected();
        fake.respond("GET", "/Items/" + trackId, 200, "item-track.json");
        fake.respond("POST", "/Items/" + trackId + "/PlaybackInfo", 200, "playback-info-audio.json");
        Device speaker = new Device("mixed-1", "Mixed", DeviceKind.UPNP, "10.0.0.31", Map.of(), Instant.now());
        PlayableRef.JellyfinItem track = new PlayableRef.JellyfinItem(FakeJellyfinServer.SERVER_ID, trackId, 0);
        ContentItem song = new ContentItem(trackId, "jellyfin", ContentKind.TRACK, "Bunny Song", "The Rabbits", null, List.of(track));

        PlayableResolver.Resolution resolution = resolver.resolve(track, song, speaker,
                java.util.EnumSet.of(Capability.MEDIA_RENDERER, Capability.LOCAL_AUDIO_SINK));

        assertThat(resolution.playables()).hasSize(1);
        PlayableRef.StreamUrl stream = (PlayableRef.StreamUrl) resolution.playables().getFirst();
        assertThat(stream.url().toString()).startsWith("http://192.168.1.20:8096/");
    }

    @Test
    void aDeviceThatCanOnlyOpenLinksGetsOnlyTheNote() throws IOException {
        connected();
        Device kitchen = device("Kitchen", "10.0.0.9");

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), kitchen, Set.of(Capability.APP_LINK));

        assertThat(resolution.playables()).isEmpty();
        assertThat(resolution.notes()).containsExactly("no Jellyfin app is open on Kitchen");
        assertThat(fake.requests("GET", "/Items/" + ITEM_ID)).isEmpty();
    }

    @Test
    void aRendererWithoutADirectFormatAddsANote() throws IOException {
        connected();
        fake.respond("POST", "/Items/" + ITEM_ID + "/PlaybackInfo", 200, "playback-info-transcode-only.json");
        Device kitchen = device("Kitchen", "10.0.0.9");

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), kitchen, Set.of(Capability.MEDIA_RENDERER));

        assertThat(resolution.playables()).isEmpty();
        assertThat(resolution.notes()).contains("Jellyfin reports no format this device can play directly");
    }

    @Test
    void notConnectedOrAnotherServerIsExplained() {
        given(setup.settings()).willReturn(Optional.empty());
        given(setup.connection()).willReturn(Optional.empty());
        Device kitchen = device("Kitchen", "10.0.0.9");

        assertThat(resolver.resolve(WANTED, item(WANTED), kitchen, Set.of(Capability.CAST_RECEIVER)).notes())
                .containsExactly("Jellyfin is not connected");

        given(setup.settings()).willReturn(Optional.of(settings()));
        given(setup.connection()).willReturn(Optional.of(new JellyfinConnection(
                URI.create("http://nas:8096"), FakeJellyfinServer.ACCESS_TOKEN, "hc-test-device", FakeJellyfinServer.USER_ID)));
        PlayableRef.JellyfinItem otherServer = new PlayableRef.JellyfinItem("other-server", ITEM_ID, 0);

        assertThat(resolver.resolve(otherServer, item(otherServer), kitchen, Set.of(Capability.CAST_RECEIVER)).notes())
                .containsExactly("this item is from a different Jellyfin server");
    }

    @Test
    void jellyfinDownIsANoteNotAnException() throws IOException {
        connected();
        Device kitchen = device("Kitchen", "10.0.0.9");
        fake.close();

        PlayableResolver.Resolution resolution = resolver.resolve(WANTED, item(WANTED), kitchen, Set.of(Capability.CAST_RECEIVER));

        assertThat(resolution.notes()).anySatisfy(note -> assertThat(note).contains("could not ask Jellyfin which apps are open"));
        assertThat(resolution.notes()).anySatisfy(note -> assertThat(note).contains("could not load the item from Jellyfin"));
        assertThat(resolution.playables()).isEmpty();
    }

    @Test
    void resolvesOnlyJellyfinItems() {
        assertThat(resolver.resolves(new PlayableRef.AppLink(URI.create("https://x"), "web"))).isFalse();
    }
}
