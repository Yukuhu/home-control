package dev.andre.homecontrol.adapters.links;

import dev.andre.homecontrol.core.playback.ServiceLinks;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ContentLinksTest {

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
    void findsTheYouTubeVideoId(String url) {
        assertThat(ContentLinks.youtubeVideoId(URI.create(url))).contains("aqz-KE-bpKQ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/channel/UC123",
            "https://example.org/watch?v=aqz-KE-bpKQ"})
    void noVideoIdWithoutAValidOne(String url) {
        assertThat(ContentLinks.youtubeVideoId(URI.create(url))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.netflix.com/title/80057281",
            "https://www.netflix.com/de/title/80057281",
            "https://www.netflix.com/de-en/title/80057281?s=a",
            "https://www.netflix.com/watch/80057281?trackId=1"})
    void findsTheNetflixTitleId(String url) {
        assertThat(ContentLinks.netflixTitleId(URI.create(url))).contains("80057281");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.netflix.com/browse", "https://example.org/title/80057281"})
    void noNetflixTitleIdOtherwise(String url) {
        assertThat(ContentLinks.netflixTitleId(URI.create(url))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.netflix.com/title/80057281",
            "https://www.netflix.com/de/title/80057281",
            "https://www.netflix.com/de-en/title/80057281?s=a",
            "https://www.netflix.com/watch/80057281?trackId=1",
            "https://www.netflix.com/browse",
            "https://example.org/title/80057281"})
    void agreesWithServiceLinks(String url) {
        URI uri = URI.create(url);
        assertThat(ContentLinks.netflixTitleId(uri)).isEqualTo(ServiceLinks.netflixTitleId(uri));
    }
}
