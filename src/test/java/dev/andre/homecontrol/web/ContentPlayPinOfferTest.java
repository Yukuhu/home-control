package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackPreview;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A second test class (not a nested one) so {@link ContentPlayPreviewTest} keeps running without a {@link PinnedLinks} bean. */
@WebMvcTest(ContentPlayController.class)
class ContentPlayPinOfferTest {

    private static final String ITEM_ID = "tv-66732";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    ContentSources sources;

    @MockitoBean
    PlaybackService playback;

    @MockitoBean
    PinnedLinks pinnedLinks;

    private final ContentSource tmdb = mock(ContentSource.class);
    private final Device living = new Device("living", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());

    private void known(ContentItem item) {
        given(devices.device("living")).willReturn(Optional.of(living));
        given(sources.find("tmdb")).willReturn(Optional.of(tmdb));
        given(tmdb.item(ITEM_ID)).willReturn(Optional.of(item));
        given(pinnedLinks.linkFor("tmdb", ITEM_ID)).willReturn(Optional.empty());
    }

    @Test
    void offersAPinForAnAppHomeItem() throws Exception {
        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");
        ContentItem item = new ContentItem(ITEM_ID, "tmdb", ContentKind.VIDEO, "Stranger Things", null, null,
                List.of(netflixHome));
        known(item);
        given(playback.preview(item, "living")).willReturn(new PlaybackPreview(living,
                List.of(new Route.OpenAppLink(netflixHome.uri(), netflixHome.service())), null));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "tmdb").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.description").value("Open the Netflix app (not this title)"))
                .andExpect(jsonPath("$.pin.upgradeOf").value("tmdb/" + ITEM_ID))
                .andExpect(jsonPath("$.pin.service").value("netflix"))
                .andExpect(jsonPath("$.pin.serviceName").value("Netflix"));
    }

    @Test
    void offersAPinWithoutServiceForAnUnplayableItem() throws Exception {
        ContentItem item = new ContentItem(ITEM_ID, "tmdb", ContentKind.VIDEO, "Matrix", null, null, List.of());
        known(item);
        given(playback.preview(item, "living")).willReturn(new PlaybackPreview(living, List.of(), "no route"));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "tmdb").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playable").value(false))
                .andExpect(jsonPath("$.pin.service").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pin.serviceName").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void noPinForTitleLinks() throws Exception {
        PlayableRef.AppLink netflixTitle = ServiceLinks.appLink(ServiceLinks.netflixTitle("80057281"));
        ContentItem item = new ContentItem(ITEM_ID, "tmdb", ContentKind.VIDEO, "Matrix", null, null, List.of(netflixTitle));
        known(item);
        given(playback.preview(item, "living")).willReturn(new PlaybackPreview(living,
                List.of(new Route.OpenAppLink(netflixTitle.uri(), netflixTitle.service())), null));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "tmdb").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").value(org.hamcrest.Matchers.nullValue()));
    }
}
