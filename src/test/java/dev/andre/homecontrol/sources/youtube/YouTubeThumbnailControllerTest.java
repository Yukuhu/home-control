package dev.andre.homecontrol.sources.youtube;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(YouTubeThumbnailController.class)
@Import(YouTubeThumbnailControllerTest.Config.class)
class YouTubeThumbnailControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        YouTubeProperties youTubeProperties() {
            return new YouTubeProperties(true, URI.create("http://oauth.test"), URI.create("http://api.test"),
                    URI.create("http://lounge.test"), URI.create("http://thumbs.test"), 2, 5, 10000, 20, 30, 30, 5,
                    Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    YouTubeHttp http;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    @Test
    void proxiesTheMediumThumbnail() throws Exception {
        given(http.get(URI.create("http://thumbs.test/vi/aqz-KE-bpKQ/mqdefault.jpg"), Map.of()))
                .willReturn(new YouTubeHttp.Response(200, "image/jpeg", JPEG));

        mockMvc.perform(get("/sources/youtube/thumbnails/aqz-KE-bpKQ"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=86400, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(JPEG));
    }

    @Test
    void aNonImageUpstreamResponseIs502() throws Exception {
        given(http.get(URI.create("http://thumbs.test/vi/aqz-KE-bpKQ/mqdefault.jpg"), Map.of()))
                .willReturn(new YouTubeHttp.Response(200, "text/html; charset=UTF-8", "<html></html>".getBytes()));

        mockMvc.perform(get("/sources/youtube/thumbnails/aqz-KE-bpKQ"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Could not load the thumbnail"));
    }

    @Test
    void rejectsInvalidIds() throws Exception {
        mockMvc.perform(get("/sources/youtube/thumbnails/..%2F")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/sources/youtube/thumbnails/abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(http);
    }

    @Test
    void missingIs404AndFailuresAre502() throws Exception {
        given(http.get(any(), any())).willReturn(new YouTubeHttp.Response(404, "text/plain", new byte[0]));
        mockMvc.perform(get("/sources/youtube/thumbnails/aqz-KE-bpKQ")).andExpect(status().isNotFound());

        given(http.get(any(), any())).willThrow(new YouTubeException(YouTubeException.Kind.UNREACHABLE, "Could not reach thumbs.test"));
        mockMvc.perform(get("/sources/youtube/thumbnails/aqz-KE-bpKQ"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Could not load the thumbnail"));
    }
}
