package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RailController.class)
class RailControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RailCache rails;

    @MockitoBean
    ContentSources sources;

    private static RailDescriptor resume() {
        return new RailDescriptor("jellyfin", "resume", "Continue watching");
    }

    private static ContentItem item() {
        return new ContentItem("item-1", "jellyfin", ContentKind.MOVIE, "Some Movie", "S1:E1",
                URI.create("/content/jellyfin/artwork/item-1"), List.of(), 0.5);
    }

    private void mockJellyfin() {
        ContentSource jellyfin = org.mockito.Mockito.mock(ContentSource.class);
        given(jellyfin.displayName()).willReturn("Jellyfin");
        given(sources.find("jellyfin")).willReturn(Optional.of(jellyfin));
        given(sources.all()).willReturn(List.of(jellyfin));
    }

    @Test
    void rendersAReadyRailAsTiles() throws Exception {
        mockJellyfin();
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.READY, List.of(item()),
                Instant.parse("2026-09-17T00:00:00Z"), null, false, 7);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/rails/jellyfin/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"rail-jellyfin-resume\"")))
                .andExpect(content().string(containsString("data-version=\"7\"")))
                .andExpect(content().string(containsString("data-status=\"READY\"")))
                .andExpect(content().string(containsString("class=\"tile\"")))
                .andExpect(content().string(containsString("data-source=\"jellyfin\"")))
                .andExpect(content().string(containsString("data-item=\"item-1\"")))
                .andExpect(content().string(containsString("Continue watching")))
                .andExpect(content().string(containsString("/content/jellyfin/artwork/item-1")))
                .andExpect(content().string(containsString("width:50%")))
                .andExpect(content().string(not(containsString("JellyfinItem"))))
                .andExpect(content().string(not(containsString("resumeTicks"))))
                .andExpect(content().string(not(containsString("playables"))));
    }

    @Test
    void liveEventTilesCarryTheirTimes() throws Exception {
        mockJellyfin();
        ContentItem event = new ContentItem("sports:1", "jellyfin", ContentKind.LIVE_EVENT, "Game", null,
                null, List.of(), null, Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:25:00Z"));
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.READY, List.of(item(), event),
                Instant.parse("2026-09-17T00:00:00Z"), null, false, 7);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        var result = mockMvc.perform(get("/rails/jellyfin/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-starts-at=\"2026-09-19T13:30:00Z\"")))
                .andExpect(content().string(containsString("data-ends-at=\"2026-09-19T15:25:00Z\"")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // the MOVIE tile (item()) has no times
        String movieTile = body.substring(0, body.indexOf("data-item=\"sports:1\""));
        org.junit.jupiter.api.Assertions.assertFalse(movieTile.contains("data-starts-at"));
    }

    @Test
    void aFailedRailIsACompactErrorWithRetryNeverAGap() throws Exception {
        mockJellyfin();
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.FAILED, List.of(), null,
                "Could not reach Jellyfin at http://nas:8096", false, 7);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/rails/jellyfin/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"rail-error\"")))
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(containsString("Could not reach Jellyfin at http://nas:8096")))
                .andExpect(content().string(containsString("hx-post=\"/rails/jellyfin/resume/refresh\"")))
                .andExpect(content().string(containsString("Retry")))
                .andExpect(content().string(not(containsString("class=\"tiles\""))));
    }

    @Test
    void aFailedRailWithItemsKeepsThemAndOffersRetry() throws Exception {
        mockJellyfin();
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.FAILED, List.of(item()), null,
                "Could not reach Jellyfin at http://nas:8096", false, 7);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/rails/jellyfin/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"tiles\"")))
                .andExpect(content().string(containsString("refresh")))
                .andExpect(content().string(containsString("rail-stale")));
    }

    @Test
    void aLoadingRailShowsSkeletons() throws Exception {
        mockJellyfin();
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.LOADING, List.of(), null, null, false, 7);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        var result = mockMvc.perform(get("/rails/jellyfin/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-busy=\"true\"")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int count = body.split("tile skeleton", -1).length - 1;
        org.junit.jupiter.api.Assertions.assertEquals(3, count);
    }

    @Test
    void anEmptyRailSaysSo() throws Exception {
        mockJellyfin();
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.READY, List.of(),
                Instant.parse("2026-09-17T00:00:00Z"), null, false, 7);
        given(rails.snapshot("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(get("/rails/jellyfin/resume"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing here right now")));
    }

    @Test
    void retryStartsARefreshAndReturnsTheRail() throws Exception {
        mockJellyfin();
        RailSnapshot snapshot = new RailSnapshot(resume(), RailStatus.LOADING, List.of(), null, null, true, 8);
        given(rails.refresh("jellyfin", "resume")).willReturn(Optional.of(snapshot));

        mockMvc.perform(post("/rails/jellyfin/resume/refresh"))
                .andExpect(status().isOk());

        verify(rails, times(1)).refresh("jellyfin", "resume");
    }

    @Test
    void unknownRailIs404() throws Exception {
        given(rails.snapshot("jellyfin", "missing")).willReturn(Optional.empty());

        mockMvc.perform(get("/rails/jellyfin/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theWholeSectionKeepsCacheOrder() throws Exception {
        mockJellyfin();
        RailSnapshot b = new RailSnapshot(new RailDescriptor("jellyfin", "b", "B rail"),
                RailStatus.READY, List.of(), Instant.now(), null, false, 1);
        RailSnapshot a = new RailSnapshot(new RailDescriptor("jellyfin", "a", "A rail"),
                RailStatus.READY, List.of(), Instant.now(), null, false, 2);
        given(rails.snapshots()).willReturn(List.of(b, a));

        String body = mockMvc.perform(get("/rails"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int bIndex = body.indexOf("rail-jellyfin-b");
        int aIndex = body.indexOf("rail-jellyfin-a");
        org.junit.jupiter.api.Assertions.assertTrue(bIndex >= 0 && aIndex >= 0 && bIndex < aIndex);
    }

    @Test
    void domIdsAreSafe() {
        RailSnapshot snapshot = new RailSnapshot(new RailDescriptor("jellyfin", "new/up", "New & up"),
                RailStatus.READY, List.of(), Instant.now(), null, false, 1);
        mockJellyfin();

        RailView view = RailView.of(snapshot, sources);

        org.junit.jupiter.api.Assertions.assertEquals("rail-jellyfin-new-up", view.domId());
    }
}
