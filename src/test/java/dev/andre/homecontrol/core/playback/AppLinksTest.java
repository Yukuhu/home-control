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
    void rejectsALinkLongerThan2048Characters() {
        String prefix = "https://example.org/";
        String atTheLimit = prefix + "a".repeat(2048 - prefix.length());

        assertThat(AppLinks.fromUrl(atTheLimit).playables()).hasSize(1);
        assertThatThrownBy(() -> AppLinks.fromUrl(atTheLimit + "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That link is too long");
    }

    @Test
    void rejectsAMissingLink() {
        assertThatThrownBy(() -> AppLinks.fromUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDirectMediaLinkAlsoCarriesAStreamUrlAndItsFileNameAsTitle() {
        String url = "https://media.example.org/films/Big%20Buck%20Bunny.MP4?token=1";

        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.playables()).containsExactly(
                new PlayableRef.AppLink(URI.create(url), "web"),
                new PlayableRef.StreamUrl(URI.create(url), "video/mp4"));
        assertThat(item.title()).isEqualTo("Big Buck Bunny.MP4");
        assertThat(item.kind()).isEqualTo(ContentKind.VIDEO);
    }

    @Test
    void aFileNameWithALiteralPlusKeepsItInsteadOfTurningItIntoASpace() {
        String url = "https://media.example.org/films/Big+Buck+Bunny.mp4";

        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.title()).isEqualTo("Big+Buck+Bunny.mp4");
    }

    @Test
    void aLiteralNonAsciiFileNameKeepsItsCharactersInsteadOfMojibake() {
        String url = "https://media.example.org/films/Bücherei.mp4";

        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.title()).isEqualTo("Bücherei.mp4");
    }

    @Test
    void aPercentEncodedNonAsciiFileNameDecodesAsUtf8() {
        String url = "https://media.example.org/films/%C3%BCber.mp4";

        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.title()).isEqualTo("über.mp4");
    }

    @Test
    void aMalformedPercentEscapeInAUrlIsRejectedWithAGenericMessage() {
        // java.net.URI's own parser already refuses a raw "%" that is not a valid escape pair,
        // so fromUrl never even reaches fileName() with one — and the message stays generic
        // rather than leaking java.net.URI's "Malformed escape pair at index …" detail.
        assertThatThrownBy(() -> AppLinks.fromUrl("https://media.example.org/films/100%GG.mp4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That is not a valid link");
    }

    @Test
    void aMalformedPercentEscapeInAFileNameIsKeptLiterallyInsteadOfThrowing() {
        // fileName() is package-visible (like mediaTypeOf/serviceOf) precisely so this — a
        // defensive case java.net.URI already forecloses when the path comes from a pasted
        // URL — stays covered for any other caller that hands it a raw path directly.
        assertThat(AppLinks.fileName("100%GG.mp4")).isEqualTo("100%GG.mp4");
    }

    @Test
    void anAudioLinkIsATrack() {
        ContentItem item = AppLinks.fromUrl("http://nas.local/music/song.flac");

        assertThat(item.playables()).contains(new PlayableRef.StreamUrl(URI.create("http://nas.local/music/song.flac"), "audio/flac"));
        assertThat(item.kind()).isEqualTo(ContentKind.TRACK);
    }

    @Test
    void aPageLinkHasNoStream() {
        assertThat(AppLinks.fromUrl("https://www.youtube.com/watch?v=abc").playables()).singleElement()
                .isInstanceOf(PlayableRef.AppLink.class);
    }

    @Test
    void pastedLinksAreCanonicalised() {
        String url = "https://www.netflix.com/de/title/80057281?s=a";

        ContentItem item = AppLinks.fromUrl(url);

        assertThat(item.playables()).containsExactly(
                new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/80057281"), "netflix"));
        assertThat(item.title()).isEqualTo("www.netflix.com");
        assertThat(item.id()).isEqualTo("link:" + url);
    }

    @Test
    void parseHttpUrlAcceptsHttpAndHttps() {
        assertThat(AppLinks.parseHttpUrl("HTTPS://Example.org/a")).isEqualTo(URI.create("HTTPS://Example.org/a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://x/y", "not a url", "", "   ", "javascript:alert(1)", "file:///etc/passwd",
            "intent://x#Intent;end", "//example.org/a", "http:opaque"})
    void parseHttpUrlRejectsTheSameInputsAsFromUrl(String url) {
        assertThatThrownBy(() -> AppLinks.parseHttpUrl(url)).isInstanceOf(IllegalArgumentException.class);
    }
}
