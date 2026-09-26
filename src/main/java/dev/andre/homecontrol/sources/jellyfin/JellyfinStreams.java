package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.playback.PlayableRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A direct stream URL for renderers that fetch media themselves (Cast Default Media Receiver now,
 * DLNA later). Jellyfin decides direct-playability against a Cast-shaped device profile; no transcoding.
 */
public class JellyfinStreams {

    private static final String AUDIO = "Audio";

    static final long MAX_BITRATE = 120_000_000L;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<String, String> VIDEO_TYPES = Map.of("mp4", "video/mp4", "m4v", "video/mp4", "webm", "video/webm");
    private static final Map<String, String> AUDIO_TYPES = Map.of("mp3", "audio/mpeg", "m4a", "audio/mp4", "mp4", "audio/mp4",
            "flac", "audio/flac", "ogg", "audio/ogg", "webm", "audio/webm");

    private final JellyfinClient client;

    public JellyfinStreams(JellyfinClient client) {
        this.client = client;
    }

    public Optional<PlayableRef.StreamUrl> directStream(JellyfinConnection connection, URI deviceServerUrl, JsonNode item) {
        String itemId = JellyfinClient.id(item.path("Id").asString(""));
        JsonNode info = client.post(connection, "/Items/" + itemId + "/PlaybackInfo", Map.of(), playbackInfoRequest(connection.userId()));
        return fromPlaybackInfo(deviceServerUrl, connection.token(), item, info);
    }

    public static ObjectNode playbackInfoRequest(String userId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("UserId", userId);
        body.put("MaxStreamingBitrate", MAX_BITRATE);
        body.put("StartTimeTicks", 0);
        body.put("EnableDirectPlay", true);
        body.put("EnableDirectStream", false);
        body.put("EnableTranscoding", false);
        body.put("AllowVideoStreamCopy", false);
        body.put("AllowAudioStreamCopy", false);
        body.put("AutoOpenLiveStream", false);
        ObjectNode profile = body.putObject("DeviceProfile");
        profile.put("Name", "Home Control direct play");
        profile.put("MaxStreamingBitrate", MAX_BITRATE);
        profile.put("MaxStaticBitrate", MAX_BITRATE);
        profile.put("MusicStreamingTranscodingBitrate", 192000);
        ArrayNode direct = profile.putArray("DirectPlayProfiles");
        directPlay(direct, "mp4,m4v", "Video", "h264", "aac,mp3");
        directPlay(direct, "webm", "Video", "vp8,vp9", "vorbis,opus");
        directPlay(direct, "mp3", AUDIO, null, "mp3");
        directPlay(direct, "m4a,mp4", AUDIO, null, "aac");
        directPlay(direct, "flac", AUDIO, null, "flac");
        directPlay(direct, "ogg,webm", AUDIO, null, "vorbis,opus");
        profile.putArray("TranscodingProfiles");
        profile.putArray("ContainerProfiles");
        profile.putArray("CodecProfiles");
        profile.putArray("SubtitleProfiles");
        return body;
    }

    public static Optional<PlayableRef.StreamUrl> fromPlaybackInfo(URI deviceServerUrl, String token, JsonNode item, JsonNode info) {
        boolean audio = AUDIO.equals(item.path("MediaType").asString(""));
        Map<String, String> types = audio ? AUDIO_TYPES : VIDEO_TYPES;
        String itemId = JellyfinClient.id(item.path("Id").asString(""));
        for (JsonNode source : info.path("MediaSources")) {
            String mediaSourceId = source.path("Id").asString("");
            if (!source.path("SupportsDirectPlay").asBoolean(false) || mediaSourceId.isBlank()) {
                continue;
            }
            Optional<String> container = Arrays.stream(source.path("Container").asString("").split(","))
                    .map(part -> part.strip().toLowerCase(Locale.ROOT))
                    .filter(types::containsKey)
                    .findFirst();
            if (container.isEmpty()) {
                continue;
            }
            String url = deviceServerUrl + (audio ? "/Audio/" : "/Videos/") + itemId + "/stream." + container.get()
                    + "?static=true&mediaSourceId=" + encode(mediaSourceId) + "&ApiKey=" + encode(token);
            return Optional.of(new PlayableRef.StreamUrl(URI.create(url), types.get(container.get())));
        }
        return Optional.empty();
    }

    private static void directPlay(ArrayNode profiles, String container, String type, String videoCodec, String audioCodec) {
        ObjectNode profile = profiles.addObject();
        profile.put("Container", container);
        profile.put("Type", type);
        if (videoCodec != null) {
            profile.put("VideoCodec", videoCodec);
        }
        profile.put("AudioCodec", audioCodec);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
