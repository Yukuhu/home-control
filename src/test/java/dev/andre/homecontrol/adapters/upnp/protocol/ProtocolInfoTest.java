package dev.andre.homecontrol.adapters.upnp.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolInfoTest {

    @Test
    void matchesExactTypesAliasesAndWildcards() {
        ProtocolInfo sink = ProtocolInfo.parseSink("http-get:*:audio/mpeg:*,http-get:*:audio/x-flac:DLNA.ORG_PN=FLAC,"
                + "rtsp-rtp-udp:*:video/mp4:*,http-get:*:audio/L16;rate=44100;channels=2:*");

        assertThat(sink.known()).isTrue();
        assertThat(sink.match("audio/mpeg")).contains("audio/mpeg");
        assertThat(sink.match("audio/flac")).contains("audio/x-flac");
        assertThat(sink.match("video/mp4")).isEmpty();
        assertThat(sink.match("audio/L16")).contains("audio/L16;rate=44100;channels=2");
        assertThat(sink.match("AUDIO/MPEG")).contains("audio/mpeg");
    }

    @Test
    void aWildcardEntryAcceptsAnything() {
        assertThat(ProtocolInfo.parseSink("http-get:*:*:*").match("video/webm")).contains("video/webm");
    }

    @Test
    void anUnknownSinkAcceptsAnything() {
        assertThat(ProtocolInfo.UNKNOWN.match("audio/ogg")).contains("audio/ogg");
        assertThat(ProtocolInfo.parseSink("").known()).isFalse();
        assertThat(ProtocolInfo.parseSink(null).known()).isFalse();
    }
}
