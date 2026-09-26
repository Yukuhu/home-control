package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class JellyfinRouteExecutorTest {

    private final JellyfinSessions sessions = mock(JellyfinSessions.class);
    private final DeviceManager devices = mock(DeviceManager.class);
    private final JellyfinRouteExecutor executor = new JellyfinRouteExecutor(sessions, devices, Duration.ofSeconds(1));
    private final Device shield = new Device("shield", "Shield", DeviceKind.ANDROID_TV, "10.0.0.5",
            Map.of("androidtv", Map.of()), Instant.now());
    private final Device browser = new Device("browser", "Browser", DeviceKind.CAST, "10.0.0.6",
            Map.of(), Instant.now());
    private static final Action.OpenAppLink LAUNCH = new Action.OpenAppLink(URI.create("market://launch?id=org.jellyfin.androidtv"));

    private static DeviceState ready() {
        return DeviceState.initial().withStatus(DeviceStatus.CONNECTED).withPower(true)
                .withCurrentApp("org.jellyfin.androidtv");
    }

    private static JellyfinSession session(String id) {
        return new JellyfinSession(id, "jf-shield", "Shield", "Android TV", "10.0.0.5", Instant.now(), true);
    }

    @Test
    void executesOnlySessionRoutes() {
        assertThat(executor.executes(new Route.JellyfinSession("s1", "item-1", 0, "Android TV"))).isTrue();
        assertThat(executor.executes(new Route.Cast("CC1AD845", Map.of()))).isFalse();
    }

    @Test
    void tellsTheSessionToPlay() {
        executor.execute(new Route.JellyfinSession("s1", "item-1", 600L, "Web"), browser);

        verify(sessions).playNow("s1", "item-1", 600L);
        verifyNoInteractions(devices);
    }

    @Test
    void aClosedSessionOrUnreachableServerIsAFailedAction() {
        willThrow(new JellyfinException(JellyfinException.Kind.NOT_FOUND, "The Jellyfin app on that device has closed its session"))
                .given(sessions).playNow("s1", "item-1", 600L);
        Route route = new Route.JellyfinSession("s1", "item-1", 600L, "Web");

        assertThatThrownBy(() -> executor.execute(route, browser))
                .isInstanceOf(ActionFailedException.class)
                .hasMessage("Jellyfin could not start playback on Browser (The Jellyfin app on that device has closed its session)");
    }

    @Test
    void wakesShieldThenLaunchesJellyfinBeforePlayingTheFreshSession() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withPower(false).withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doAnswer(_ -> { state.set(state.get().withPower(true)); return null; })
                .when(devices).execute(eq("shield"), isA(Action.PressKey.class));
        doAnswer(_ -> { state.set(state.get().withCurrentApp("org.jellyfin.androidtv")); return null; })
                .when(devices).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.empty(), Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinSession("stale", "item-1", 600L, "Android TV"), shield);

        var order = inOrder(devices, sessions);
        order.verify(devices).execute(eq("shield"), argThat(action -> action instanceof Action.PressKey key && key.key().code() == 224));
        order.verify(devices).execute("shield", LAUNCH);
        order.verify(sessions).playNow("fresh", "item-1", 600L);
        verify(sessions, never()).playNow(eq("stale"), anyString(), anyLong());
    }

    @Test
    void anAwakeShieldWithJellyfinClosedOnlyNeedsAnAppLaunch() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doAnswer(_ -> { state.set(ready()); return null; }).when(devices).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(devices).execute("shield", LAUNCH);
        verify(devices, never()).execute(eq("shield"), isA(Action.PressKey.class));
        verify(sessions).playNow("fresh", "item-1", 0);
    }

    @Test
    void anAlreadyReadyAppPlaysWithoutWakeOrLaunch() {
        given(devices.state("shield")).willReturn(ready());
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinSession("old-preview", "item-1", 600L, "Android TV"), shield);

        verify(devices, never()).execute(anyString(), any());
        verify(sessions).playNow("fresh", "item-1", 600L);
    }

    @Test
    void aSleepingShieldWithJellyfinStillOpenOnlyNeedsWake() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withPower(false));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doAnswer(_ -> { state.set(ready()); return null; })
                .when(devices).execute(eq("shield"), isA(Action.PressKey.class));
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 600L), shield);

        verify(devices, never()).execute(eq("shield"), isA(Action.OpenAppLink.class));
        verify(sessions).playNow("fresh", "item-1", 600L);
    }

    @Test
    void aReconnectingShieldCanBecomeReadyDuringStartup() {
        given(devices.state("shield")).willReturn(DeviceState.initial(), ready());
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(sessions).playNow("fresh", "item-1", 0);
        verify(devices, never()).execute(anyString(), any());
    }

    @Test
    void retriesAnUnconfirmedLaunchAfterReconnectBeforePlaying() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> {
            DeviceState observed = state.get();
            if (!observed.connected()) {
                state.set(observed.withStatus(DeviceStatus.CONNECTED));
            }
            return observed;
        });
        doAnswer(_ -> {
            state.set(state.get().withStatus(DeviceStatus.DISCONNECTED));
            return null;
        }).doAnswer(_ -> { state.set(ready()); return null; }).when(devices).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 600L), shield);

        verify(devices, times(2)).execute("shield", LAUNCH);
        verify(sessions).playNow("fresh", "item-1", 600L);
    }

    @Test
    void retriesLaunchWhenTheConnectionDropsDuringTheWrite() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doThrow(new DeviceOfflineException("connection dropped"))
                .doAnswer(_ -> { state.set(ready()); return null; }).when(devices).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(sessions).playNow("fresh", "item-1", 0);
    }

    @Test
    void retriesAnUnconfirmedLaunchEvenWhenNoDisconnectWasObserved() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doNothing().doAnswer(_ -> { state.set(ready()); return null; }).when(devices).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));
        var startup = new JellyfinRouteExecutor(sessions, devices, Duration.ofSeconds(4));

        startup.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(devices, times(2)).execute("shield", LAUNCH);
        verify(sessions).playNow("fresh", "item-1", 0);
    }

    @Test
    void aLaunchThatNeverReachesJellyfinCannotUseAnOldSession() {
        given(devices.state("shield")).willReturn(ready().withCurrentApp("launcher"));
        Route route = new Route.JellyfinSession("stale", "item-1", 0, "Android TV");

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("installed");

        verify(devices).execute("shield", LAUNCH);
        verifyNoInteractions(sessions);
    }

    @Test
    void aMissingSessionTimesOutWithoutSendingPlayback() {
        given(devices.state("shield")).willReturn(ready());
        given(sessions.sessionFor(shield)).willReturn(Optional.empty());
        Route route = new Route.JellyfinApp("item-1", 0);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("Jellyfin").hasMessageContaining("sign in");

        verify(sessions, never()).playNow(anyString(), anyString(), anyLong());
    }

    @Test
    void aFailedWakeNeverLaunchesOrPlays() {
        given(devices.state("shield")).willReturn(ready().withPower(false));
        Route route = new Route.JellyfinApp("item-1", 0);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("wake");

        verify(devices, never()).execute(eq("shield"), isA(Action.OpenAppLink.class));
        verifyNoInteractions(sessions);
    }

    @Test
    void anOfflineDeviceDoesNotReceiveCommands() {
        given(devices.state("shield")).willReturn(DeviceState.initial());
        Route route = new Route.JellyfinApp("item-1", 0);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(DeviceOfflineException.class).hasMessageContaining("connect");

        verify(devices, never()).execute(anyString(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void aStalledSessionLookupCannotOutliveTheStartupDeadlineOrPlayLater() throws Exception {
        given(devices.state("shield")).willReturn(ready());
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        given(sessions.sessionFor(shield)).willAnswer(_ -> {
            try {
                releaseLookup.await();
            } catch (InterruptedException _) {
                cancelled.countDown();
                Thread.currentThread().interrupt();
            }
            return Optional.of(session("late"));
        });
        var bounded = new JellyfinRouteExecutor(sessions, devices, Duration.ofMillis(100));
        Route route = new Route.JellyfinApp("item-1", 0);

        try {
            assertTimeout(Duration.ofSeconds(1), () ->
                    assertThatThrownBy(() -> bounded.execute(route, shield))
                            .isInstanceOf(ActionFailedException.class).hasMessageContaining("ready"));

            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
            verify(sessions, never()).playNow(anyString(), anyString(), anyLong());
        } finally {
            releaseLookup.countDown();
        }
    }

    @Test
    void interruptionStopsStartupWithoutPlayback() {
        given(devices.state("shield")).willReturn(ready());
        given(sessions.sessionFor(shield)).willReturn(Optional.empty());
        Route route = new Route.JellyfinApp("item-1", 0);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.execute(route, shield))
                    .isInstanceOf(ActionFailedException.class).hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(sessions, never()).playNow(anyString(), anyString(), anyLong());
        } finally {
            Thread.interrupted();
        }
    }
}
