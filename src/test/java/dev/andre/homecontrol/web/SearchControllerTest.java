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

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mockMvc.perform(get("/search/results"))
                .andExpect(status().isOk())
                .andExpect(content().string(blankOrNullString()));

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
}
