package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.content.PinOffers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins routing behaviour for {@link ContentKind#LIVE_EVENT} items: they route exactly like any other
 * item with an app-link playable (spec §5.3 does not special-case a kind), which is the point of this
 * test — it should already pass unchanged before any sports-specific production code is touched.
 */
class LiveEventRoutingTest {

    private static final Pattern UPGRADE_OF = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}/[A-Za-z0-9._:-]{1,128}$");

    private static PlaybackPlanner planner() {
        // Same strategy list and order as HomeControlConfiguration#playbackPlanner (spec §5.3):
        // an open Jellyfin app, an app link, then Cast in its own preference order.
        return new PlaybackPlanner(List.of(new JellyfinSessionStrategy(), new AppLinkStrategy(),
                new YouTubeLoungeStrategy(), new CastMessageStrategy(), new CastLoadStrategy(),
                new CastStreamStrategy()));
    }

    private static ContentItem liveEvent(List<PlayableRef> playables) {
        return new ContentItem("tsdb:2508361", "sports", ContentKind.LIVE_EVENT, "Werder Bremen vs Augsburg", null,
                null, playables, null, Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:30:00Z"));
    }

    @Test
    void aDaznEventOpensTheDaznAppOnAppLinkDevices() {
        ContentItem item = liveEvent(List.of(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn")));
        Set<Capability> capabilities = Set.of(Capability.REMOTE_KEYS, Capability.APP_LINK);

        Route route = planner().plan(item, capabilities);

        assertThat(route).isEqualTo(new Route.OpenAppLink(URI.create("https://www.dazn.com/"), "dazn"));
        assertThat(route.describe()).isEqualTo("Open the DAZN app (not this title)");
        assertThat(planner().routes(item, capabilities)).containsExactly(route);
    }

    @Test
    void aPastedEventLinkOpensTheEvent() {
        URI link = URI.create("https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j");
        ContentItem item = liveEvent(List.of(new PlayableRef.AppLink(link, "dazn")));

        Route route = planner().plan(item, Set.of(Capability.APP_LINK));

        assertThat(route.describe()).isEqualTo("Open in the DAZN app");
    }

    @Test
    void anUnmappedEventHasNothingToPlay() {
        ContentItem item = liveEvent(List.of());

        Route route = planner().plan(item, Set.of(Capability.APP_LINK));

        assertThat(route).isEqualTo(new Route.Unroutable("This item has nothing playable"));
        assertThat(planner().routes(item, Set.of(Capability.APP_LINK))).isEmpty();
    }

    @Test
    void devicesWithoutAppLinksExplainWhy() {
        ContentItem item = liveEvent(List.of(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn")));

        Route noAppLink = planner().plan(item, Set.of(Capability.CAST_RECEIVER, Capability.VOLUME));
        Route noneAtAll = planner().plan(item, Set.of(Capability.MEDIA_RENDERER));

        assertThat(noAppLink).isInstanceOf(Route.Unroutable.class);
        assertThat(((Route.Unroutable) noAppLink).reason()).contains("cannot open app links");
        assertThat(noneAtAll).isInstanceOf(Route.Unroutable.class);
        assertThat(((Route.Unroutable) noneAtAll).reason()).contains("cannot open app links");
    }

    private static Stream<Set<Capability>> capabilitySets() {
        return Stream.of(Set.of(), Set.of(Capability.APP_LINK), Set.of(Capability.CAST_RECEIVER),
                Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER, Capability.MEDIA_RENDERER));
    }

    @ParameterizedTest
    @MethodSource("capabilitySets")
    void routingIgnoresKindAndTime(Set<Capability> capabilities) {
        List<PlayableRef> playables = List.of(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn"));
        ContentItem video = new ContentItem("v1", "sports", ContentKind.VIDEO, "Title", null, null, playables);
        ContentItem past = new ContentItem("v1", "sports", ContentKind.LIVE_EVENT, "Title", null, null, playables,
                null, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-01T02:00:00Z"));
        ContentItem present = new ContentItem("v1", "sports", ContentKind.LIVE_EVENT, "Title", null, null, playables,
                null, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        ContentItem future = new ContentItem("v1", "sports", ContentKind.LIVE_EVENT, "Title", null, null, playables,
                null, Instant.parse("2099-01-01T00:00:00Z"), Instant.parse("2099-01-01T02:00:00Z"));

        Route videoRoute = planner().plan(video, capabilities);
        for (ContentItem item : List.of(past, present, future)) {
            assertThat(planner().plan(item, capabilities)).isEqualTo(videoRoute);
            assertThat(planner().routes(item, capabilities)).isEqualTo(planner().routes(video, capabilities));
        }
    }

    @Test
    void pinOffersForEvents() {
        ContentItem daznHome = liveEvent(List.of(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn")));
        assertThat(PinOffers.offer(daznHome)).contains(new PinOffers.Offer("sports/tsdb:2508361", "dazn"));

        ContentItem noPlayables = liveEvent(List.of());
        assertThat(PinOffers.offer(noPlayables)).contains(new PinOffers.Offer("sports/tsdb:2508361", null));

        ContentItem pastedLink = liveEvent(List.of(new PlayableRef.AppLink(
                URI.create("https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j"), "dazn")));
        assertThat(PinOffers.offer(pastedLink)).isEmpty();

        ContentItem icsEvent = new ContentItem("ics:c-3f9a1c2b7d4e:069e696917c4a665", "sports", ContentKind.LIVE_EVENT,
                "Title", null, null, List.of(new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn")));
        PinOffers.Offer offer = PinOffers.offer(icsEvent).orElseThrow();
        assertThat(offer.upgradeOf()).isEqualTo("sports/ics:c-3f9a1c2b7d4e:069e696917c4a665");
        assertThat(UPGRADE_OF.matcher(offer.upgradeOf()).matches()).isTrue();
    }

    private static Stream<Arguments> liveEventMatrixRows() {
        PlayableRef dazn = new PlayableRef.AppLink(URI.create("https://www.dazn.com/"), "dazn");
        PlayableRef pastedDazn = new PlayableRef.AppLink(
                URI.create("https://www.dazn.com/de-DE/fixture/ContentId:1a2b3c4d5e6f7g8h9i0j"), "dazn");
        PlayableRef primeVideo = new PlayableRef.AppLink(URI.create("https://app.primevideo.com/"), "primevideo");
        PlayableRef netflix = new PlayableRef.AppLink(URI.create("https://www.netflix.com/browse"), "netflix");
        Set<Capability> appLink = Set.of(Capability.APP_LINK);
        Set<Capability> shield = Set.of(Capability.REMOTE_KEYS, Capability.POWER, Capability.VOLUME, Capability.APP_LINK);
        Set<Capability> castOnly = Set.of(Capability.CAST_RECEIVER, Capability.VOLUME);
        Set<Capability> none = Set.of();

        return Stream.of(
                Arguments.of(List.of(dazn), appLink, "Open the DAZN app (not this title)", 1),
                Arguments.of(List.of(dazn), shield, "Open the DAZN app (not this title)", 1),
                Arguments.of(List.of(dazn), castOnly, "cannot open app links", 0),
                Arguments.of(List.of(dazn), none, "cannot open app links", 0),
                Arguments.of(List.of(pastedDazn), appLink, "Open in the DAZN app", 1),
                Arguments.of(List.of(primeVideo), appLink, "Open the Prime Video app (not this title)", 1),
                Arguments.of(List.of(netflix), appLink, "Open the Netflix app (not this title)", 1),
                Arguments.of(List.of(), appLink, "This item has nothing playable", 0),
                Arguments.of(List.of(), none, "This item has nothing playable", 0));
    }

    @ParameterizedTest
    @MethodSource("liveEventMatrixRows")
    void liveEventMatrix(List<PlayableRef> playables, Set<Capability> capabilities, String expectedDescribeContains,
                         int expectedRouteCount) {
        ContentItem event = new ContentItem("tsdb:2508361", "sports", ContentKind.LIVE_EVENT, "Werder Bremen vs Augsburg",
                null, null, playables, null, Instant.parse("2026-09-19T13:30:00Z"), Instant.parse("2026-09-19T15:25:00Z"));
        ContentItem video = new ContentItem("tsdb:2508361", "sports", ContentKind.VIDEO, "Werder Bremen vs Augsburg",
                null, null, playables);

        for (ContentItem item : List.of(event, video)) {
            Route route = planner().plan(item, capabilities);
            assertThat(route.describe()).as(item.kind() + ": " + expectedDescribeContains).contains(expectedDescribeContains);
            assertThat(planner().routes(item, capabilities)).as(item.kind().toString()).hasSize(expectedRouteCount);
        }
    }
}
