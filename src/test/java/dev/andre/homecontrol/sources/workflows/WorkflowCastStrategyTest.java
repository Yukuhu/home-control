package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlaybackPlanner;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.RouteStrategies;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCastStrategyTest {
    @Test void workflowOnlyRoutesToCastEvenOnMergedDevices() {
        var item = new ContentItem("w-0123456789ab", "workflows", ContentKind.VIDEO,
                "News", null, null, List.of(new WorkflowCastRef("w-0123456789ab", 1, "single")));
        var strategy = new WorkflowConfiguration().workflowCastStrategy();
        assertThat(strategy.route(item, Set.of(Capability.APP_LINK))).isEmpty();
        var route = strategy.route(item, Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).orElseThrow();
        assertThat(route.key()).isEqualTo("workflow-cast");
        assertThat(route.describe()).isEqualTo("Cast with the Default Media Receiver");
        var planner = new PlaybackPlanner(List.of(RouteStrategies.appLink(), strategy, RouteStrategies.castStream(),
                RouteStrategies.renderer(), RouteStrategies.localSink()));
        assertThat(planner.plan(item, Set.of(Capability.APP_LINK, Capability.CAST_RECEIVER)).routes()).containsExactly(route);
        assertThat(planner.plan(item, Set.of(Capability.APP_LINK)).first()).isInstanceOfSatisfying(Route.Unroutable.class,
                missing -> assertThat(missing.reason()).contains("not a Cast receiver"));
    }
}
