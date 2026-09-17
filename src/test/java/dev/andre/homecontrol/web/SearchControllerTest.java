package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.SearchOutcome;
import dev.andre.homecontrol.content.SearchService;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SearchService search;

    private static ContentSource sourceStub(String id, String name) {
        ContentSource source = mock(ContentSource.class);
        given(source.id()).willReturn(id);
        given(source.displayName()).willReturn(name);
        return source;
    }

    private static ContentItem item(String id, String sourceId, String title) {
        return new ContentItem(id, sourceId, ContentKind.MOVIE, title, null, null, List.of());
    }

    @Test
    void blankQueryRendersNothing() throws Exception {
        // Truly empty, not just blank: #search-results must be CSS :empty for the dashboard's
        // "hide the rails while search results are showing" rule to let them back through once
        // the query is cleared (see fragments/search.html's th:if on the fragment's own root).
        mockMvc.perform(get("/search/results"))
                .andExpect(status().isOk())
                .andExpect(content().string(emptyString()));

        verify(search, never()).search(any(), anyInt());
    }

    @Test
    void oneCharacterAsksForMore() throws Exception {
        mockMvc.perform(get("/search/results").param("q", "b"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type at least 2 characters")));

        verify(search, never()).search(any(), anyInt());
    }

    @Test
    void tooLongIsExplained() throws Exception {
        mockMvc.perform(get("/search/results").param("q", "a".repeat(101)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Search for 2 to 100 characters")));

        verify(search, never()).search(any(), anyInt());
    }

    @Test
    void rendersOneRailPerSourceWithTiles() throws Exception {
        ContentSource jellyfin = sourceStub("jellyfin", "Jellyfin");
        SearchOutcome outcome = new SearchOutcome("bunny",
                List.of(new SearchOutcome.Hits(jellyfin, List.of(item("item-1", "jellyfin", "Big Buck Bunny")))),
                List.of());
        given(search.search("bunny", 20)).willReturn(outcome);

        mockMvc.perform(get("/search/results").param("q", "bunny"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"rail search-hits\"")))
                .andExpect(content().string(containsString("data-source=\"jellyfin\"")))
                .andExpect(content().string(containsString("class=\"tile\"")))
                .andExpect(content().string(containsString("data-item=\"item-1\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("playables"))));
    }

    @Test
    void rendersFailuresCompactly() throws Exception {
        ContentSource tmdb = sourceStub("tmdb", "TMDB");
        SearchOutcome outcome = new SearchOutcome("bunny", List.of(),
                List.of(new SearchOutcome.Failure(tmdb, "TMDB did not answer in time")));
        given(search.search("bunny", 20)).willReturn(outcome);

        mockMvc.perform(get("/search/results").param("q", "bunny"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"rail-error\"")))
                .andExpect(content().string(containsString("TMDB did not answer in time")));
    }

    @Test
    void saysWhenNothingWasFound() throws Exception {
        SearchOutcome outcome = new SearchOutcome("<b>", List.of(), List.of());
        given(search.search("<b>", 20)).willReturn(outcome);

        mockMvc.perform(get("/search/results").param("q", "<b>"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing found for")))
                .andExpect(content().string(containsString("&lt;b&gt;")));
    }

    @Test
    void trimsTheQuery() throws Exception {
        given(search.search("bunny", 20)).willReturn(new SearchOutcome("bunny", List.of(), List.of()));

        mockMvc.perform(get("/search/results").param("q", "  bunny "))
                .andExpect(status().isOk());

        verify(search).search("bunny", 20);
    }

    @Test
    void offersOnDemandSourcesAfterResults() throws Exception {
        ContentSource jellyfin = sourceStub("jellyfin", "Jellyfin");
        SearchOutcome outcome = new SearchOutcome("star",
                List.of(new SearchOutcome.Hits(jellyfin, List.of(item("item-1", "jellyfin", "Star Wars")))), List.of());
        given(search.search("star", 20)).willReturn(outcome);
        ContentSource youtube = sourceStub("youtube", "YouTube");
        given(youtube.searchNote()).willReturn(Optional.of("19 of 20 YouTube searches left today"));
        given(search.onDemandSources()).willReturn(List.of(youtube));

        mockMvc.perform(get("/search/results").param("q", "star"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-post=\"/search/results/youtube?q=star\"")))
                .andExpect(content().string(containsString("Search YouTube")))
                .andExpect(content().string(containsString("19 of 20 YouTube searches left today")));
    }

    @Test
    void onDemandSourcesAlsoWhenNothingFound() throws Exception {
        given(search.search("star", 20)).willReturn(new SearchOutcome("star", List.of(), List.of()));
        ContentSource youtube = sourceStub("youtube", "YouTube");
        given(search.onDemandSources()).willReturn(List.of(youtube));

        mockMvc.perform(get("/search/results").param("q", "star"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing found for")))
                .andExpect(content().string(containsString("Search YouTube")));
    }

    @Test
    void noButtonForShortQueries() throws Exception {
        mockMvc.perform(get("/search/results").param("q", "s"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type at least 2 characters")));

        verify(search, never()).onDemandSources();
    }

    @Test
    void sourceResultsFragment() throws Exception {
        ContentSource youtube = sourceStub("youtube", "YouTube");
        SearchOutcome outcome = new SearchOutcome("star",
                List.of(new SearchOutcome.Hits(youtube, List.of(item("item-1", "youtube", "Star video")))), List.of());
        given(search.searchSource("youtube", "star", 20)).willReturn(outcome);

        mockMvc.perform(post("/search/results/youtube").param("q", "star"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"rail search-hits\"")))
                .andExpect(content().string(containsString("data-item=\"item-1\"")));

        SearchOutcome failed = new SearchOutcome("star", List.of(),
                List.of(new SearchOutcome.Failure(youtube, "YouTube search failed")));
        given(search.searchSource("youtube", "star", 20)).willReturn(failed);
        mockMvc.perform(post("/search/results/youtube").param("q", "star"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"rail-error\"")))
                .andExpect(content().string(containsString("YouTube search failed")));

        given(search.searchSource("nope", "star", 20)).willThrow(new IllegalArgumentException("No searchable source nope"));
        mockMvc.perform(post("/search/results/nope").param("q", "star"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No searchable source nope")));

        mockMvc.perform(post("/search/results/youtube").param("q", "s"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Type at least 2 characters")));
        verify(search, never()).searchSource("youtube", "s", 20);
    }

    @Test
    void onDemandSearchIsNoLongerAGet() throws Exception {
        // A GET must not be able to spend the on-demand source's quota; only the POST mapping exists.
        mockMvc.perform(get("/search/results/youtube").param("q", "star"))
                .andExpect(status().isMethodNotAllowed());
        verify(search, never()).searchSource(any(), any(), anyInt());
    }
}
