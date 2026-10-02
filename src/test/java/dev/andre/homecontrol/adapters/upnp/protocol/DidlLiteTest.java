package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class DidlLiteTest {

    @Test
    void describesAnAudioTrackForDlnaRenderers() throws IOException {
        assertThat(DidlLite.item(URI.create(SoapClientTest.URL), "audio/flac", "Bunny Song", "The Rabbits", DidlLite.DLNA_STREAMING))
                .isEqualTo(Fixtures.read("upnp/didl-track.xml").strip());
    }

    @Test
    void classesFollowTheMimeType() {
        assertThat(DidlLite.upnpClass("video/mp4")).isEqualTo("object.item.videoItem.movie");
        assertThat(DidlLite.upnpClass("image/jpeg")).isEqualTo("object.item.imageItem.photo");
        assertThat(DidlLite.upnpClass("audio/L16;rate=44100")).isEqualTo("object.item.audioItem.musicTrack");
        assertThat(DidlLite.upnpClass("application/x-mpegURL")).isEqualTo("object.item");
    }

    @Test
    void escapesTitlesAndOmitsAMissingArtist() {
        String didl = DidlLite.item(URI.create("http://h/a.mp3"), "audio/mpeg", "Tom & Jerry <Live>", null, "*");

        assertThat(didl).contains("<dc:title>Tom &amp; Jerry &lt;Live&gt;</dc:title>")
                .contains("protocolInfo=\"http-get:*:audio/mpeg:*\"")
                .doesNotContain("upnp:artist");
        assertThat(DidlLite.item(URI.create("http://h/a.mp3"), "audio/mpeg", " ", null, "*"))
                .contains("<dc:title>Home Control</dc:title>");
    }

    @Test
    void readsTheTitleFromMetadata() throws IOException {
        assertThat(DidlLite.title(Fixtures.read("upnp/position-metadata.xml")))
                .contains("Carrot Waltz");
        assertThat(DidlLite.title(Fixtures.read("upnp/didl-track.xml"))).contains("Bunny Song");
        assertThat(DidlLite.title("NOT_IMPLEMENTED")).isEmpty();
        assertThat(DidlLite.title("")).isEmpty();
        assertThat(DidlLite.title(null)).isEmpty();
        assertThat(DidlLite.title("<DIDL-Lite")).isEmpty();
        assertThat(DidlLite.title("<!DOCTYPE x><x/>")).isEmpty();
    }
}
