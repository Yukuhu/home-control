package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeVideoIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            "https://www.youtube.com/watch?feature=share&v=aqz-KE-bpKQ&t=30",
            "https://m.youtube.com/watch?v=aqz-KE-bpKQ",
            "https://youtu.be/aqz-KE-bpKQ",
            "https://youtu.be/aqz-KE-bpKQ?si=abc",
            "https://www.youtube.com/shorts/aqz-KE-bpKQ",
            "https://www.youtube.com/live/aqz-KE-bpKQ",
            "https://www.youtube.com/embed/aqz-KE-bpKQ"})
    void findsTheVideoId(String url) {
        assertThat(YouTubeVideoIds.fromUrl(URI.create(url))).contains("aqz-KE-bpKQ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/channel/UC123",
            "https://example.org/watch?v=aqz-KE-bpKQ",
            "https://notyoutube.com/watch?v=aqz-KE-bpKQ"})
    void noVideoId(String url) {
        assertThat(YouTubeVideoIds.fromUrl(URI.create(url))).isEmpty();
    }
}
