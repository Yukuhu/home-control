package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.testsupport.Planners;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Review focus 2: the ladder's order is the rungs', whatever order the strategies arrive in. */
class PlaybackPlannerLadderTest {

    private static final ContentItem ITEM = new ContentItem("x", "test", ContentKind.VIDEO, "Title", null, null, List.of(
            new PlayableRef.StreamUrl(URI.create("http://nas.local/a.mp4"), "video/mp4"),
            new PlayableRef.CastLoad("F007D354", Map.of("contentId", "item-1")),
            new PlayableRef.CastMessage(new Action.CastMessage("F007D354", "urn:x-cast:com.connectsdk", Map.of()),
                    "the Jellyfin receiver"),
            new PlayableRef.AppLink(URI.create("https://www.youtube.com/watch?v=abc"), "youtube")));
    private static final Set<Capability> CAST_RECEIVER_WITH_APP_LINKS = EnumSet.of(Capability.APP_LINK, Capability.CAST_RECEIVER);

    @Test
    void anyOrderOfStrategiesPlansTheSame() {
        List<RouteStrategy> ladder = Planners.strategies();
        List<RouteStrategy> shuffled = new ArrayList<>(ladder);
        Collections.shuffle(shuffled, new Random(3));

        List<String> inLadderOrder = keys(ladder);

        assertThat(inLadderOrder).containsExactly("app-link", "cast-message:F007D354", "cast:F007D354", "cast:CC1AD845");
        assertThat(keys(ladder.reversed())).isEqualTo(inLadderOrder);
        assertThat(keys(shuffled)).isEqualTo(inLadderOrder);
    }

    private static List<String> keys(List<RouteStrategy> strategies) {
        return new PlaybackPlanner(strategies).plan(ITEM, CAST_RECEIVER_WITH_APP_LINKS).routes().stream().map(Route::key).toList();
    }
}
