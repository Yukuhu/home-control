package dev.andre.homecontrol.adapters.sonos.protocol;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class SonosUrisTest {

    @Test
    void joiningAGroupPointsAtItsCoordinator() {
        assertThat(SonosUris.groupWith("RINCON_000E58A0B1C201400")).isEqualTo("x-rincon:RINCON_000E58A0B1C201400");
        assertThat(SonosUris.groupedTo("x-rincon:RINCON_X")).contains("RINCON_X");
        assertThat(SonosUris.groupedTo("http://h/a.mp3")).isEmpty();
        assertThat(SonosUris.groupedTo(null)).isEmpty();
    }

    @Test
    void filesPlayAsTheyAre() {
        URI flac = URI.create("http://nas.local/music/song.flac");
        URI jellyfin = URI.create("http://192.168.1.20:8096/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.mp3?static=true&ApiKey=t");
        URI https = URI.create("https://radio.example.org/live");

        assertThat(SonosUris.forPlayback(flac, "audio/flac")).isEqualTo(flac);
        assertThat(SonosUris.forPlayback(jellyfin, "audio/mpeg")).isEqualTo(jellyfin);
        assertThat(SonosUris.forPlayback(https, "audio/mpeg")).isEqualTo(https);
    }

    @Test
    void anExtensionlessMp3StreamIsRadio() {
        assertThat(SonosUris.forPlayback(URI.create("http://radio.example.org:8000/live?sid=1"), "audio/mpeg"))
                .isEqualTo(URI.create("x-rincon-mp3radio://radio.example.org:8000/live?sid=1"));
        assertThat(SonosUris.forPlayback(URI.create("http://radio.example.org/"), "audio/mpeg"))
                .isEqualTo(URI.create("x-rincon-mp3radio://radio.example.org/"));
        URI aac = URI.create("http://radio.example.org/live");
        assertThat(SonosUris.forPlayback(aac, "audio/aac")).isEqualTo(aac);
    }
}
