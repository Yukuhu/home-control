package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppLinksTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=abc, youtube",
            "https://youtu.be/abc, youtube",
            "https://m.youtube.com/watch?v=abc, youtube",
            "https://www.netflix.com/title/80057281, netflix",
            "https://app.primevideo.com/detail?gti=amzn1.dv.gti.1, primevideo",
            "https://www.amazon.de/gp/video/detail/B08XYZ, primevideo",
            "https://www.amazon.co.uk/gp/video/detail/B08XYZ, primevideo",
            "https://www.dazn.com/de-DE/home, dazn",
            "https://example.org/anything, web",
            "https://notyoutube.com/watch?v=abc, web",
            "https://www.notnetflix.com/title/1, web",
            "https://amazon.evil.example/gp/video/detail/B08XYZ, web",
            "https://notamazon.de/gp/video/detail/B08XYZ, web",
    })
    void detectsTheServiceFromTheHost(String url, String service) {
        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.playables()).singleElement()
                .isEqualTo(new PlayableRef.AppLink(URI.create(url), service));
        assertThat(item.sourceId()).isEqualTo("manual");
        assertThat(item.kind()).isEqualTo(ContentKind.VIDEO);
        assertThat(item.title()).isEqualTo(URI.create(url).getHost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x/y", "not a url", "", "   ", "javascript:alert(1)", "file:///etc/passwd",
            "intent://x#Intent;end", "//example.org/a", "http:opaque"})
    void rejectsAnythingThatIsNotAnHttpUrl(String url) {
        assertThatThrownBy(() -> AppLinks.fromUrl(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingLink() {
        assertThatThrownBy(() -> AppLinks.fromUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
