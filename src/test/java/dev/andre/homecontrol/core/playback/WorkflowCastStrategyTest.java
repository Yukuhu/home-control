package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCastStrategyTest {
    @Test void workflowOnlyRoutesToCastEvenOnMergedDevices() {
        var item = new ContentItem("w-0123456789ab", "workflows", ContentKind.VIDEO,
                "News", null, null, List.of(new PlayableRef.WorkflowCast("w-0123456789ab", 1, "single")));
        var strategy = new WorkflowCastStrategy();
        assertThat(strategy.route(item, Set.of(Capability.APP_LINK))).isEmpty();
        var route = strategy.route(item, Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).orElseThrow();
        assertThat(RouteKeys.key(route)).isEqualTo("workflow-cast");
        assertThat(route.describe()).isEqualTo("Cast with the Default Media Receiver");
        var planner = new PlaybackPlanner(List.of(new AppLinkStrategy(), strategy, new CastStreamStrategy(),
                new MediaRendererStrategy(), new LocalAudioSinkStrategy()));
        assertThat(planner.routes(item, Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER))).containsExactly(route);
        assertThat(planner.plan(item, Set.of(Capability.APP_LINK))).isInstanceOfSatisfying(Route.Unroutable.class,
                missing -> assertThat(missing.reason()).contains("not a Cast receiver"));
    }
}
