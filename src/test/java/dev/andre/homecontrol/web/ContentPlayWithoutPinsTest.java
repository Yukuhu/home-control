package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import dev.andre.homecontrol.playback.PlaybackPreview;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Without the pinned module there is no {@code PinnedLinks} bean, and the route preview offers no pin. A standalone
 * MockMvc, because the shared web slice has a {@code PinnedLinks}.
 */
class ContentPlayWithoutPinsTest {

    private static final String ITEM_ID = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";

    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final ContentSources sources = mock(ContentSources.class);
    private final PlaybackService playback = mock(PlaybackService.class);
    private final ContentSource tmdb = mock(ContentSource.class);
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new ContentPlayController(devices, sources, playback, beans.getBeanProvider(PinnedLinks.class))).build();

    @Test
    void noPinWithoutThePinnedModule() throws Exception {
        Device living = new Device("living", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of()), Instant.now());
        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");
        ContentItem appHomeItem = new ContentItem(ITEM_ID, "tmdb", ContentKind.VIDEO, "Stranger Things", null, null,
                List.of(netflixHome));
        given(devices.device("living")).willReturn(Optional.of(living));
        given(sources.find("tmdb")).willReturn(Optional.of(tmdb));
        given(tmdb.item(ITEM_ID)).willReturn(Optional.of(appHomeItem));
        given(playback.preview(appHomeItem, "living")).willReturn(new PlaybackPreview(living,
                List.of(new Route.OpenAppLink(netflixHome.uri(), netflixHome.service())), null));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "tmdb").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").value(nullValue()));
    }
}
