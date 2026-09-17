package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.content.SearchOutcome;
import dev.andre.homecontrol.content.SearchService;
import dev.andre.homecontrol.core.content.ContentSource;
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

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
class ContentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContentSources sources;

    @MockitoBean
    RailCache rails;

    @MockitoBean
    SearchService searchService;

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
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.READY, List.of(item),
                Instant.parse("2026-09-16T09:00:00Z"), null, false, 1);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        String body = mockMvc.perform(get("/sources/jellyfin/rails/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.items[0].id").value("item-1"))
                .andExpect(jsonPath("$.items[0].kind").value("EPISODE"))
                .andExpect(jsonPath("$.items[0].artwork").value("/sources/jellyfin/images/item-1/Primary?tag=t"))
                .andExpect(jsonPath("$.items[0].progress").value(0.5))
                .andExpect(jsonPath("$.fetchedAt").exists())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("playables", "resumeTicks", "srv");
    }

    @Test
    void aLoadingRailIs202() throws Exception {
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "resume", "Continue watching");
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.LOADING, List.of(), null, null, false, 1);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/sources/jellyfin/rails/resume"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("LOADING"));
    }

    @Test
    void aFailedRailWithoutItemsIs502WithTheReason() throws Exception {
        String message = "Could not reach Jellyfin at http://nas:8096 (connection refused). …";
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "resume", "Continue watching");
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.FAILED, List.of(), null, message, false, 2);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/sources/jellyfin/rails/resume"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(message));
    }

    @Test
    void aFailedRailWithItemsIsServedWithItsError() throws Exception {
        String message = "Jellyfin could not load Continue watching";
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "resume", "Continue watching");
        ContentItem item = new ContentItem("item-1", "jellyfin", ContentKind.MOVIE, "Old Item", null, null, List.of());
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.FAILED, List.of(item),
                Instant.parse("2026-09-16T09:00:00Z"), message, false, 3);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/sources/jellyfin/rails/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(message))
                .andExpect(jsonPath("$.items[0].id").value("item-1"));
    }

    @Test
    void aRailFromAnUnknownSourceIs404() throws Exception {
        given(rails.snapshot("jellyfinx", "resume")).willReturn(Optional.empty());
        given(sources.find("jellyfinx")).willReturn(Optional.empty());

        mockMvc.perform(get("/sources/jellyfinx/rails/resume"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No content source jellyfinx"));
    }

    @Test
    void unknownRailIs404WithTheSourceName() throws Exception {
        given(rails.snapshot("jellyfin", "nope")).willReturn(Optional.empty());
        ContentSource source = mock(ContentSource.class);
        given(source.displayName()).willReturn("Jellyfin");
        given(sources.find("jellyfin")).willReturn(Optional.of(source));

        mockMvc.perform(get("/sources/jellyfin/rails/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Jellyfin has no rail 'nope'"));

        verify(source, never()).rail(any());
    }

    @Test
    void refreshStartsAFetchAndAnswers202() throws Exception {
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "resume", "Continue watching");
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.READY, List.of(),
                Instant.parse("2026-09-16T09:00:00Z"), null, true, 4);
        given(rails.refresh("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(post("/sources/jellyfin/rails/resume/refresh"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("READY"));

        verify(rails).refresh("jellyfin", "resume");
    }

    @Test
    void refreshOfAnUnknownRailIs404() throws Exception {
        given(rails.refresh("jellyfin", "nope")).willReturn(Optional.empty());
        given(sources.find("jellyfin")).willReturn(Optional.empty());

        mockMvc.perform(post("/sources/jellyfin/rails/nope/refresh"))
                .andExpect(status().isNotFound());
    }

    private static ContentSource searchableMock(String id, String name) {
        ContentSource source = mock(ContentSource.class);
        given(source.id()).willReturn(id);
        given(source.displayName()).willReturn(name);
        return source;
    }

    @Test
    void searchesEverySearchableSource() throws Exception {
        ContentSource jellyfin = searchableMock("jellyfin", "Jellyfin");
        ContentItem item = new ContentItem("item-1", "jellyfin", ContentKind.MOVIE, "Big Buck Bunny", null, null, List.of());
        SearchOutcome outcome = new SearchOutcome("bunny", List.of(new SearchOutcome.Hits(jellyfin, List.of(item))), List.of());
        given(searchService.search("bunny", 20)).willReturn(outcome);

        String body = mockMvc.perform(get("/search").param("q", "bunny"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("bunny"))
                .andExpect(jsonPath("$.results[0].sourceId").value("jellyfin"))
                .andExpect(jsonPath("$.results[0].sourceName").value("Jellyfin"))
                .andExpect(jsonPath("$.results[0].items[0].title").value("Big Buck Bunny"))
                .andReturn().getResponse().getContentAsString();

        verify(searchService).search("bunny", 20);
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("playables");
    }

    @Test
    void aFailingSourceIsReportedBesideTheOthers() throws Exception {
        ContentSource jellyfin = searchableMock("jellyfin", "Jellyfin");
        ContentItem item = new ContentItem("item-1", "jellyfin", ContentKind.MOVIE, "Big Buck Bunny", null, null, List.of());
        ContentSource tmdb = searchableMock("tmdb", "TMDB");
        SearchOutcome outcome = new SearchOutcome("bunny", List.of(new SearchOutcome.Hits(jellyfin, List.of(item))),
                List.of(new SearchOutcome.Failure(tmdb, "TMDB is unreachable")));
        given(searchService.search("bunny", 20)).willReturn(outcome);

        mockMvc.perform(get("/search").param("q", "bunny"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.errors[0].sourceId").value("tmdb"))
                .andExpect(jsonPath("$.errors[0].message").value("TMDB is unreachable"));
    }

    @Test
    void rejectsQueriesThatAreTooShortOrTooLong() throws Exception {
        mockMvc.perform(get("/search").param("q", " a "))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Search for 2 to 100 characters"));

        mockMvc.perform(get("/search").param("q", "a".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Search for 2 to 100 characters"));

        mockMvc.perform(get("/search"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Search for 2 to 100 characters"));
    }

    @Test
    void clampsTheLimit() throws Exception {
        given(searchService.search("bunny", 50)).willReturn(new SearchOutcome("bunny", List.of(), List.of()));
        given(searchService.search("bunny", 1)).willReturn(new SearchOutcome("bunny", List.of(), List.of()));

        mockMvc.perform(get("/search").param("q", "bunny").param("limit", "500")).andExpect(status().isOk());
        verify(searchService).search("bunny", 50);

        mockMvc.perform(get("/search").param("q", "bunny").param("limit", "0")).andExpect(status().isOk());
        verify(searchService).search("bunny", 1);
    }

    @Test
    void searchWithSourceUsesSearchSource() throws Exception {
        ContentSource youtube = searchableMock("youtube", "YouTube");
        ContentItem item = new ContentItem("item-1", "youtube", ContentKind.VIDEO, "Bunny", null, null, List.of());
        SearchOutcome outcome = new SearchOutcome("star", List.of(new SearchOutcome.Hits(youtube, List.of(item))), List.of());
        given(searchService.searchSource("youtube", "star", 20)).willReturn(outcome);

        mockMvc.perform(post("/search").param("q", "star").param("source", "youtube"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].sourceId").value("youtube"));

        verify(searchService).searchSource("youtube", "star", 20);

        given(searchService.searchSource("nope", "star", 20))
                .willThrow(new IllegalArgumentException("No searchable source nope"));
        mockMvc.perform(post("/search").param("q", "star").param("source", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("No searchable source nope"));
    }

    @Test
    void onDemandSourceSearchIsNoLongerAGet() throws Exception {
        // A GET must not be able to spend a source's quota; only the POST mapping accepts `source`,
        // so a `source` param on a GET is simply ignored and falls through to the unified search.
        given(searchService.search("star", 20)).willReturn(new SearchOutcome("star", List.of(), List.of()));

        mockMvc.perform(get("/search").param("q", "star").param("source", "youtube"))
                .andExpect(status().isOk());

        verify(searchService, never()).searchSource(any(), any(), anyInt());
        verify(searchService).search("star", 20);
    }
}
