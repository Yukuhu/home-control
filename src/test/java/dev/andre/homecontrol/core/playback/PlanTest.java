package dev.andre.homecontrol.core.playback;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    private static final Route RENDER = new Route.Render(URI.create("http://nas.local/a.flac"), "audio/flac", "A", null);
    private static final Route LOCAL = new Route.PlayLocally(URI.create("http://nas.local/a.flac"), "audio/flac", "A", null);

    @Test
    void aPlanWithoutRoutesOffersItsReason() {
        assertThat(new Plan(List.of(), "this device cannot open app links").first())
                .isEqualTo(new Route.Unroutable("this device cannot open app links"));
    }

    @Test
    void aPlanWithRoutesOffersTheFirst() {
        assertThat(new Plan(List.of(RENDER, LOCAL), null).first()).isEqualTo(RENDER);
    }

    @Test
    void itsRoutesCannotBeChanged() {
        List<Route> routes = new ArrayList<>(List.of(RENDER));
        Plan plan = new Plan(routes, null);

        routes.add(LOCAL);

        List<Route> kept = plan.routes();
        assertThat(kept).containsExactly(RENDER);
        assertThatThrownBy(() -> kept.add(LOCAL)).isInstanceOf(UnsupportedOperationException.class);
    }
}
