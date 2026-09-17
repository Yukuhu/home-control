package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.playback.Route;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JellyfinRouteExecutorTest {

    private final JellyfinSessions sessions = mock(JellyfinSessions.class);
    private final JellyfinRouteExecutor executor = new JellyfinRouteExecutor(sessions);
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());

    @Test
    void executesOnlySessionRoutes() {
        assertThat(executor.executes(new Route.JellyfinSession("s1", "item-1", 0, "Android TV"))).isTrue();
        assertThat(executor.executes(new Route.Cast("CC1AD845", Map.of()))).isFalse();
    }

    @Test
    void tellsTheSessionToPlay() {
        executor.execute(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"), shield);

        verify(sessions).playNow("s1", "item-1", 600L);
    }

    @Test
    void aClosedSessionOrUnreachableServerIsAFailedAction() {
        willThrow(new JellyfinException(JellyfinException.Kind.NOT_FOUND, "The Jellyfin app on that device has closed its session"))
                .given(sessions).playNow("s1", "item-1", 600L);

        assertThatThrownBy(() -> executor.execute(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"), shield))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Jellyfin could not start playback on Shield (The Jellyfin app on that device has closed its session)");
    }
}
