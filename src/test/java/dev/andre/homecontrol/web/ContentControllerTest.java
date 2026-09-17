package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.Rail;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
class ContentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContentSources sources;

    private static ContentSource jellyfin(boolean available, boolean searchable, List<RailDescriptor> rails) {
        return new ContentSource() {
            @Override
            public String id() {
                return "jellyfin";
            }

            @Override
            public String displayName() {
                return "Jellyfin";
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public List<RailDescriptor> rails() {
                return rails;
            }

            @Override
            public Rail rail(String railId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<ContentItem> item(String itemId) {
                return Optional.empty();
            }

            @Override
            public boolean searchable() {
                return searchable;
            }
        };
    }

    @Test
    void listsSourcesWithTheirRails() throws Exception {
        RailDescriptor resume = new RailDescriptor("jellyfin", "resume", "Continue watching");
        given(sources.all()).willReturn(List.of(jellyfin(true, false, List.of(resume))));

        mockMvc.perform(get("/sources"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"id":"jellyfin","name":"Jellyfin","available":true,"searchable":false,
                          "rails":[{"id":"resume","title":"Continue watching"}]}]
                        """));
    }

    @Test
    void servesARailAsViewsWithoutPlayableReferences() throws Exception {
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "resume", "Continue watching");
        ContentItem item = new ContentItem("item-1", "jellyfin", ContentKind.EPISODE, "Title", "Sub",
                URI.create("/sources/jellyfin/images/item-1/Primary?tag=t"),
                List.of(new PlayableRef.JellyfinItem("srv", "item-1", 99)), 0.5);
        ContentSource source = new ContentSource() {
            @Override
            public String id() {
                return "jellyfin";
            }

            @Override
            public String displayName() {
                return "Jellyfin";
            }

            @Override
            public List<RailDescriptor> rails() {
                return List.of(descriptor);
            }

            @Override
            public Rail rail(String railId) {
                return new Rail(descriptor, List.of(item), Instant.parse("2026-09-16T09:00:00Z"));
            }

            @Override
            public Optional<ContentItem> item(String itemId) {
                return Optional.empty();
            }
        };
        given(sources.find("jellyfin")).willReturn(Optional.of(source));

        String body = mockMvc.perform(get("/sources/jellyfin/rails/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("item-1"))
                .andExpect(jsonPath("$.items[0].kind").value("EPISODE"))
                .andExpect(jsonPath("$.items[0].artwork").value("/sources/jellyfin/images/item-1/Primary?tag=t"))
                .andExpect(jsonPath("$.items[0].progress").value(0.5))
                .andExpect(jsonPath("$.fetchedAt").exists())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("playables", "resumeTicks", "srv");
    }

    @Test
    void unknownSourceOrRailIs404() throws Exception {
        given(sources.find("jellyfinx")).willReturn(Optional.empty());

        mockMvc.perform(get("/sources/jellyfinx/rails/resume"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No content source jellyfinx"));

        ContentSource source = jellyfin(true, false, List.of());
        given(sources.find("jellyfin")).willReturn(Optional.of(new ContentSource() {
            @Override
            public String id() {
                return "jellyfin";
            }

            @Override
            public String displayName() {
                return "Jellyfin";
            }

            @Override
            public List<RailDescriptor> rails() {
                return List.of();
            }

            @Override
            public Rail rail(String railId) {
                throw new IllegalArgumentException("Jellyfin has no rail 'ghost'");
            }

            @Override
            public Optional<ContentItem> item(String itemId) {
                return Optional.empty();
            }
        }));

        mockMvc.perform(get("/sources/jellyfin/rails/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Jellyfin has no rail 'ghost'"));
    }

    @Test
    void anUpstreamFailureIs502WithTheReason() throws Exception {
        String message = "Could not reach Jellyfin at http://nas:8096 (connection refused). …";
        given(sources.find("jellyfin")).willReturn(Optional.of(new ContentSource() {
            @Override
            public String id() {
                return "jellyfin";
            }

            @Override
            public String displayName() {
                return "Jellyfin";
            }

            @Override
            public List<RailDescriptor> rails() {
                return List.of();
            }

            @Override
            public Rail rail(String railId) {
                throw new ContentSourceException(message);
            }

            @Override
            public Optional<ContentItem> item(String itemId) {
                return Optional.empty();
            }
        }));

        mockMvc.perform(get("/sources/jellyfin/rails/resume"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(message));
    }
}
