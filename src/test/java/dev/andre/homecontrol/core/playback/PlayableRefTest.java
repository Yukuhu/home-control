package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** References end up in logs and "no route" explanations; none of them may print a credential. */
class PlayableRefTest {

    @Test
    void aStreamUrlPrintsWithoutItsQuery() {
        var stream = new PlayableRef.StreamUrl(
                URI.create("http://192.168.1.10:8096/Audio/abc/universal?ApiKey=0123456789abcdef"), "audio/mpeg");

        assertThat(stream.toString())
                .isEqualTo("StreamUrl[url=http://192.168.1.10:8096/Audio/abc/universal?…, mimeType=audio/mpeg]")
                .doesNotContain("0123456789abcdef");
    }

    @Test
    void aStreamUrlPrintsWithoutTheUserInfoOfAPastedLink() {
        // AppLinks.parseHttpUrl accepts user:password@ in a hand-pasted media link.
        var stream = new PlayableRef.StreamUrl(URI.create("https://me:hunter2@nas.local:8443/films/movie.mp4"), "video/mp4");

        assertThat(stream.toString())
                .isEqualTo("StreamUrl[url=https://nas.local:8443/films/movie.mp4, mimeType=video/mp4]")
                .doesNotContain("hunter2");
    }

    @Test
    void aStreamUrlWithoutSchemeOrQueryPrintsItsPath() {
        assertThat(new PlayableRef.StreamUrl(URI.create("/media/song.flac"), "audio/flac").toString())
                .isEqualTo("StreamUrl[url=/media/song.flac, mimeType=audio/flac]");
        assertThat(new PlayableRef.StreamUrl(URI.create("https://cdn.example.com/a.mp4"), null).toString())
                .isEqualTo("StreamUrl[url=https://cdn.example.com/a.mp4, mimeType=null]");
    }

    @Test
    void aCastMessagePrintsNeitherItsPayloadNorAToken() {
        var message = new PlayableRef.CastMessage("ABCD1234", "urn:x-cast:com.example.play",
                Map.of("url", "https://media.example.com/live?token=secret-token"), "Example");

        assertThat(message.toString())
                .isEqualTo("CastMessage[receiverAppId=ABCD1234, namespace=urn:x-cast:com.example.play]")
                .doesNotContain("secret-token");
    }

    @Test
    void aCastMessageKeepsAnUnmodifiableCopyOfItsPayload() {
        Map<String, Object> payload = new HashMap<>(Map.of("channel", "news"));
        var message = new PlayableRef.CastMessage("ABCD1234", "urn:x-cast:com.example.play", payload, "Example");

        payload.put("channel", "sports");

        assertThat(message.message()).containsExactly(Map.entry("channel", "news"));
        assertThatThrownBy(() -> message.message().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new PlayableRef.CastMessage("ABCD1234", "urn:x-cast:com.example.play", null, "Example").message())
                .isEmpty();
    }
}
