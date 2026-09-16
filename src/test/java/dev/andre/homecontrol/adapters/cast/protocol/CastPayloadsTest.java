package dev.andre.homecontrol.adapters.cast.protocol;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CastPayloadsTest {

    private static JsonNode json(String text) {
        return CastPayloads.parse(text);
    }

    @Test
    void connectIdentifiesTheSenderLikePychromecast() {
        assertThat(CastPayloads.connect()).isEqualTo(json("""
                {"type":"CONNECT","origin":{},"userAgent":"home-control",
                 "senderInfo":{"sdkType":2,"version":"15.605.1.3","browserVersion":"44.0.2403.30",
                               "platform":4,"systemVersion":"Macintosh; Intel Mac OS X10_10_3","connectionType":1}}
                """));
    }

    @Test
    void platformMessages() {
        assertThat(CastPayloads.close()).isEqualTo(json("{\"type\":\"CLOSE\"}"));
        assertThat(CastPayloads.ping()).isEqualTo(json("{\"type\":\"PING\"}"));
        assertThat(CastPayloads.pong()).isEqualTo(json("{\"type\":\"PONG\"}"));
        assertThat(CastPayloads.getStatus()).isEqualTo(json("{\"type\":\"GET_STATUS\"}"));
    }

    @Test
    void receiverCommands() {
        assertThat(CastPayloads.launch("CC1AD845")).isEqualTo(json("{\"type\":\"LAUNCH\",\"appId\":\"CC1AD845\"}"));
        assertThat(CastPayloads.stop("s-1")).isEqualTo(json("{\"type\":\"STOP\",\"sessionId\":\"s-1\"}"));
        assertThat(CastPayloads.setVolumeLevel(0.3)).isEqualTo(json("{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":0.3}}"));
        assertThat(CastPayloads.setVolumeLevel(1.7)).isEqualTo(json("{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":1.0}}"));
        assertThat(CastPayloads.setMuted(true)).isEqualTo(json("{\"type\":\"SET_VOLUME\",\"volume\":{\"muted\":true}}"));
    }

    @Test
    void loadAddsTypeAndSessionAndDropsAnyRequestIdFromTheBody() {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("contentId", "http://nas.local/a.mp4");
        media.put("contentType", "video/mp4");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "SOMETHING_ELSE");
        body.put("requestId", 99);
        body.put("media", media);
        body.put("autoplay", true);

        assertThat(CastPayloads.load("s-1", body)).isEqualTo(json("""
                {"type":"LOAD","sessionId":"s-1","autoplay":true,
                 "media":{"contentId":"http://nas.local/a.mp4","contentType":"video/mp4"}}
                """));
    }

    @Test
    void mediaCommands() {
        // Compared as text: a long-valued node and a parsed int node are not equal as trees.
        assertThat(CastPayloads.toJson(CastPayloads.pause(3))).isEqualTo("{\"type\":\"PAUSE\",\"mediaSessionId\":3}");
        assertThat(CastPayloads.toJson(CastPayloads.play(3))).isEqualTo("{\"type\":\"PLAY\",\"mediaSessionId\":3}");
        assertThat(CastPayloads.toJson(CastPayloads.stopMedia(3))).isEqualTo("{\"type\":\"STOP\",\"mediaSessionId\":3}");
    }

    @Test
    void incomingMessagesExposeTypeRequestIdAndFailure() {
        CastIncoming reply = new CastIncoming(CastNamespaces.RECEIVER, "receiver-0", "sender-0",
                json("{\"type\":\"LAUNCH_ERROR\",\"requestId\":4,\"reason\":\"NOT_FOUND\"}"));
        CastIncoming broadcast = new CastIncoming(CastNamespaces.MEDIA, "transport-1", "*",
                json("{\"type\":\"LOAD_FAILED\"}"));

        assertThat(reply.type()).isEqualTo("LAUNCH_ERROR");
        assertThat(reply.requestId()).isEqualTo(4);
        assertThat(reply.describeFailure()).isEqualTo("LAUNCH_ERROR: NOT_FOUND");
        assertThat(broadcast.requestId()).isZero();
        assertThat(broadcast.describeFailure()).isEqualTo("LOAD_FAILED");
    }
}
