package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CastLoadsTest {

    @Test
    void buildsADefaultMediaReceiverLoadForAStream() {
        Map<String, Object> load = CastLoads.defaultMediaReceiver(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/films/bunny.mp4"), "video/mp4"), "Big Buck Bunny");

        assertThat(load).containsEntry("autoplay", true).containsEntry("currentTime", 0);
        assertThat(load.get("media")).isEqualTo(Map.of(
                "contentId", "http://nas.local/films/bunny.mp4",
                "contentUrl", "http://nas.local/films/bunny.mp4",
                "contentType", "video/mp4",
                "streamType", "BUFFERED",
                "metadata", Map.of("metadataType", 0, "title", "Big Buck Bunny")));
    }

    @Test
    void omitsABlankTitleAndAssumesMp4WithoutAMimeType() {
        Map<String, Object> load = CastLoads.defaultMediaReceiver(
                new PlayableRef.StreamUrl(URI.create("http://nas.local/x"), null), " ");

        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) load.get("media");
        assertThat(media).containsEntry("contentType", "video/mp4");
        assertThat(media.get("metadata")).isEqualTo(Map.of("metadataType", 0));
        assertThat(CastLoads.DEFAULT_MEDIA_RECEIVER).isEqualTo("CC1AD845");
    }
}
