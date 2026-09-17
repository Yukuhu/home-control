package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JellyfinStreamsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final URI DEVICE_SERVER_URL = URI.create("http://192.168.1.20:8096");

    private final JellyfinClient client = new JellyfinClient(new JellyfinProperties(true, 2, 5, 20));
    private final JellyfinStreams streams = new JellyfinStreams(client);
    private FakeJellyfinServer fake;

    @AfterEach
    void closeFake() {
        if (fake != null) {
            fake.close();
        }
    }

    private static JsonNode fixture(String name) {
        return MAPPER.readTree(FakeJellyfinServer.fixture(name));
    }

    @Test
    void asksJellyfinWhetherTheReceiverCanPlayTheFileAsIs() throws IOException {
        fake = new FakeJellyfinServer().respond("POST",
                "/Items/3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b/PlaybackInfo", 200, "playback-info-direct.json");
        JellyfinConnection connection = new JellyfinConnection(fake.url(), "tok", "dev", FakeJellyfinServer.USER_ID);

        Optional<PlayableRef.StreamUrl> stream = streams.directStream(connection, DEVICE_SERVER_URL, fixture("item-episode.json"));

        assertThat(stream).isPresent();
        FakeJellyfinServer.Recorded recorded = fake.last("POST", "/Items/3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b/PlaybackInfo");
        JsonNode expected = MAPPER.readTree("""
                {
                  "UserId": "%s",
                  "MaxStreamingBitrate": 120000000,
                  "StartTimeTicks": 0,
                  "EnableDirectPlay": true,
                  "EnableDirectStream": false,
                  "EnableTranscoding": false,
                  "AllowVideoStreamCopy": false,
                  "AllowAudioStreamCopy": false,
                  "AutoOpenLiveStream": false,
                  "DeviceProfile": {
                    "Name": "Home Control direct play",
                    "MaxStreamingBitrate": 120000000,
                    "MaxStaticBitrate": 120000000,
                    "MusicStreamingTranscodingBitrate": 192000,
                    "DirectPlayProfiles": [
                      { "Container": "mp4,m4v", "Type": "Video", "VideoCodec": "h264", "AudioCodec": "aac,mp3" },
                      { "Container": "webm", "Type": "Video", "VideoCodec": "vp8,vp9", "AudioCodec": "vorbis,opus" },
                      { "Container": "mp3", "Type": "Audio", "AudioCodec": "mp3" },
                      { "Container": "m4a,mp4", "Type": "Audio", "AudioCodec": "aac" },
                      { "Container": "flac", "Type": "Audio", "AudioCodec": "flac" },
                      { "Container": "ogg,webm", "Type": "Audio", "AudioCodec": "vorbis,opus" }
                    ],
                    "TranscodingProfiles": [],
                    "ContainerProfiles": [],
                    "CodecProfiles": [],
                    "SubtitleProfiles": []
                  }
                }
                """.formatted(FakeJellyfinServer.USER_ID));
        assertThat(MAPPER.readTree(recorded.body())).isEqualTo(expected);
        assertThat(recorded.header("content-type")).startsWith("application/json");
        assertThat(recorded.header("authorization")).contains("Token=\"tok\"");
    }

    @Test
    void buildsAStaticVideoUrlOnTheDeviceAddress() {
        Optional<PlayableRef.StreamUrl> stream = JellyfinStreams.fromPlaybackInfo(
                DEVICE_SERVER_URL, "t k", fixture("item-episode.json"), fixture("playback-info-direct.json"));

        assertThat(stream).contains(new PlayableRef.StreamUrl(
                URI.create("http://192.168.1.20:8096/Videos/3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b/stream.mp4"
                        + "?static=true&mediaSourceId=3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b&ApiKey=t+k"),
                "video/mp4"));
    }

    @Test
    void anAudioItemUsesTheAudioStream() {
        JsonNode item = MAPPER.readTree("""
                {"Id":"c0ffee00c0ffee00c0ffee00c0ffee01","MediaType":"Audio"}
                """);

        Optional<PlayableRef.StreamUrl> stream = JellyfinStreams.fromPlaybackInfo(
                DEVICE_SERVER_URL, "t", item, fixture("playback-info-audio.json"));

        assertThat(stream).contains(new PlayableRef.StreamUrl(
                URI.create("http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac"
                        + "?static=true&mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&ApiKey=t"),
                "audio/flac"));
    }

    @Test
    void noDirectlyPlayableSourceMeansNoStream() {
        JsonNode item = fixture("item-episode.json");

        assertThat(JellyfinStreams.fromPlaybackInfo(DEVICE_SERVER_URL, "t", item, fixture("playback-info-transcode-only.json")))
                .isEmpty();
        assertThat(JellyfinStreams.fromPlaybackInfo(DEVICE_SERVER_URL, "t", item, MAPPER.readTree("""
                {"MediaSources":[]}
                """))).isEmpty();
        assertThat(JellyfinStreams.fromPlaybackInfo(DEVICE_SERVER_URL, "t", item, MAPPER.readTree("""
                {"MediaSources":[{"Id":"x","SupportsDirectPlay":true,"Container":"avi"}]}
                """))).isEmpty();
    }

    @Test
    void theStreamUrlNeverPrintsItsKey() {
        String printed = new PlayableRef.StreamUrl(
                URI.create("http://h:8096/Videos/x/stream.mp4?static=true&ApiKey=secret-key"), "video/mp4").toString();

        assertThat(printed).contains("http://h:8096/Videos/x/stream.mp4").doesNotContain("secret-key");
    }
}
