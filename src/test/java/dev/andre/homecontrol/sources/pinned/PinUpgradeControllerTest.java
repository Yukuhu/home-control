package dev.andre.homecontrol.sources.pinned;

import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PinUpgradeController.class)
class PinUpgradeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PinnedShortcuts pins;

    @Test
    void pinsAndAnswersJson() throws Exception {
        Pin pin = new Pin("p-3f9a1c2b7d4e", URI.create("https://www.netflix.com/title/80057281"), "netflix",
                "Stranger Things", "Netflix", null, ContentKind.VIDEO, "tmdb/tv-66732", Instant.parse("2026-09-16T10:00:00Z"));
        given(pins.addUpgrade("https://www.netflix.com/title/80057281", "tmdb/tv-66732")).willReturn(pin);

        mockMvc.perform(post("/setup/sources/pinned/upgrade")
                        .param("url", "https://www.netflix.com/title/80057281")
                        .param("upgradeOf", "tmdb/tv-66732"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p-3f9a1c2b7d4e"))
                .andExpect(jsonPath("$.title").value("Stranger Things"))
                .andExpect(jsonPath("$.message").value("Pinned Stranger Things. It now opens directly."));

        verify(pins).addUpgrade("https://www.netflix.com/title/80057281", "tmdb/tv-66732");
    }

    @Test
    void userErrorsAre400WithAMessage() throws Exception {
        willThrow(new IllegalArgumentException("This item already opens directly")).given(pins).addUpgrade(any(), any());

        mockMvc.perform(post("/setup/sources/pinned/upgrade").param("url", "x").param("upgradeOf", "tmdb/tv-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This item already opens directly"));
    }

    @Test
    void missingParametersAre400() throws Exception {
        mockMvc.perform(post("/setup/sources/pinned/upgrade").param("url", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storageFailuresAre500WithoutDetails() throws Exception {
        willThrow(new StorageException("disk /data/pinned.json full", null)).given(pins).addUpgrade(any(), any());

        String body = mockMvc.perform(post("/setup/sources/pinned/upgrade").param("url", "x").param("upgradeOf", "tmdb/tv-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Could not save the pinned link"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("/data");
    }
}
