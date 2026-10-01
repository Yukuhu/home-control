package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class RedactedUrisTest {

    @Test
    void dropsTheQueryThatMayHoldAnApiKey() {
        URI stream = URI.create("http://192.168.1.10:8096/Videos/abc/stream.mkv?Static=true&ApiKey=0123456789abcdef");

        assertThat(RedactedUris.withoutQuery(stream)).isEqualTo("http://192.168.1.10:8096/Videos/abc/stream.mkv?…");
    }

    @Test
    void dropsUserInfoAndFragmentToo() {
        URI stream = URI.create("https://user:hunter2@media.example.com/a%20b.mp3#t=10");

        assertThat(RedactedUris.withoutQuery(stream)).isEqualTo("https://media.example.com/a%20b.mp3");
    }

    @Test
    void keepsAUriWithoutQueryAsItIs() {
        assertThat(RedactedUris.withoutQuery(URI.create("http://tv.local/ssap"))).isEqualTo("http://tv.local/ssap");
    }

    @Test
    void aRelativeOrOpaqueUriKeepsOnlyItsPath() {
        assertThat(RedactedUris.withoutQuery(URI.create("/Videos/abc?ApiKey=secret"))).isEqualTo("/Videos/abc?…");
        assertThat(RedactedUris.withoutQuery(URI.create("mailto:someone@example.com"))).doesNotContain("someone");
    }

    @Test
    void aMissingUriIsNamed() {
        assertThat(RedactedUris.withoutQuery(null)).isEqualTo("null");
    }
}
