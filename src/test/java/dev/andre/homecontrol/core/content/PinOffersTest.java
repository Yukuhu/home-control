package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PinOffersTest {

    private static ContentItem item(String sourceId, String id, PlayableRef... playables) {
        return new ContentItem(id, sourceId, ContentKind.VIDEO, "Title", null, null, List.of(playables), null);
    }

    @Test
    void appHomeOnlyItemsOfferThePinWithTheirService() {
        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");

        var offer = PinOffers.offer(item("tmdb", "tv-66732", netflixHome));

        assertThat(offer).contains(new PinOffers.Offer("tmdb/tv-66732", "netflix"));
    }

    @Test
    void itemsWithoutPlayablesOfferAPinWithoutService() {
        var offer = PinOffers.offer(item("tmdb", "movie-603"));

        assertThat(offer).contains(new PinOffers.Offer("tmdb/movie-603", null));
    }

    @Test
    void titleLinksAndOtherRefsOfferNothing() {
        PlayableRef.AppLink netflixTitle =
                new PlayableRef.AppLink(URI.create("https://www.netflix.com/title/1"), "netflix");
        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");

        assertThat(PinOffers.offer(item("tmdb", "movie-1", netflixTitle))).isEmpty();
        assertThat(PinOffers.offer(item("jellyfin", "i", new PlayableRef.JellyfinItem("s", "i", 0)))).isEmpty();
        assertThat(PinOffers.offer(item("tmdb", "movie-1", netflixHome,
                new PlayableRef.StreamUrl(URI.create("http://nas.local/x.mp4"), "video/mp4")))).isEmpty();
    }

    @Test
    void pinnedAndManualItemsOfferNothing() {
        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");

        assertThat(PinOffers.offer(item("pinned", "p-1", netflixHome))).isEmpty();
        assertThat(PinOffers.offer(item("manual", "link:x", netflixHome))).isEmpty();
    }
}
