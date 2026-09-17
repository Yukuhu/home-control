package dev.andre.homecontrol.adapters.bluetooth.player;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamRedactionTest {

    @Test
    void hidesQueriesOfUrls() {
        assertThat(StreamRedaction.redact("Failed to open http://192.168.1.20:8096/Audio/x/stream.flac?static=true&ApiKey=secret."))
                .isEqualTo("Failed to open http://192.168.1.20:8096/Audio/x/stream.flac?…");
        assertThat(StreamRedaction.redact("https://h/a.mp3?token=abc def")).isEqualTo("https://h/a.mp3?… def");
        assertThat(StreamRedaction.redact("see http://a/x?k=1 and http://b/y?k=2"))
                .isEqualTo("see http://a/x?… and http://b/y?…");
    }

    @Test
    void hidesLooseKeys() {
        String redacted = StreamRedaction.redact("api_key=abc&x=1 ApiKey=def token=ghi");
        assertThat(redacted).doesNotContain("abc").doesNotContain("def").doesNotContain("ghi");
        assertThat(redacted).contains("api_key=…").contains("ApiKey=…").contains("token=…");
    }

    @Test
    void hidesUrlUserinfo() {
        assertThat(StreamRedaction.redact("failed to open http://user:pass@nas/stream.flac"))
                .isEqualTo("failed to open http://…@nas/stream.flac");
        assertThat(StreamRedaction.redact("see http://a:b@host/x?k=1"))
                .isEqualTo("see http://…@host/x?…");
    }

    @Test
    void leavesOtherTextAlone() {
        assertThat(StreamRedaction.redact("[ao] Failed to initialize audio output"))
                .isEqualTo("[ao] Failed to initialize audio output");
        assertThat(StreamRedaction.redact(null)).isNull();
    }
}
