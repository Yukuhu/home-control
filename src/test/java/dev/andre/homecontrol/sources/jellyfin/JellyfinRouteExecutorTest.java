package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.playback.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final DeviceCommands commands = mock(DeviceCommands.class);
    // Retry (1 s) intentionally outlasts the startup timeout (500 ms), as RETRY (2 s) outlasted the old 1 s timeout:
    // aLaunchThatNeverReachesJellyfinCannotUseAnOldSession relies on a single launch attempt before giving up.
    private final JellyfinRouteExecutor executor = new JellyfinRouteExecutor(sessions, devices, commands, Duration.ofMillis(500),
            Duration.ofSeconds(1), Duration.ofMillis(10));
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

    /** The Android TV module is on, so the Shield runs Android apps, unless a test says otherwise. */
    @BeforeEach
    void androidTvIsOn() {
        given(devices.capabilities("shield")).willReturn(Set.of(Capability.REMOTE_KEYS, Capability.APP_LINK,
                Capability.ANDROID_APPS));
    }

    @Test
    void productionWaitsAreTwoSecondsBetweenCommandsAndAQuarterSecondPerStep() {
        assertThat(JellyfinRouteExecutor.RETRY).isEqualTo(Duration.ofSeconds(2));
        assertThat(JellyfinRouteExecutor.PAUSE_STEP).isEqualTo(Duration.ofMillis(250));
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
        verifyNoInteractions(commands);
        verify(devices, never()).state(anyString());
    }

    /**
     * Android TV switched off: nothing can wake the device or launch Android's Jellyfin app, even when another adapter
     * of a merged device (webOS, Tizen) offers keys and app links, so the open session plays as is.
     */
    @Test
    void aDeviceWhoseAndroidTvModuleIsOffPlaysTheOpenSessionDirectly() {
        given(devices.capabilities("shield")).willReturn(Set.of(Capability.APP_LINK, Capability.REMOTE_KEYS));

        executor.execute(new Route.JellyfinSession("s1", "item-1", 600L, "Android TV"), shield);

        verify(sessions).playNow("s1", "item-1", 600L);
        verify(commands, never()).execute(anyString(), any());
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
                .when(commands).execute(eq("shield"), isA(Action.PressKey.class));
        doAnswer(_ -> { state.set(state.get().withCurrentApp("org.jellyfin.androidtv")); return null; })
                .when(commands).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.empty(), Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinSession("stale", "item-1", 600L, "Android TV"), shield);

        var order = inOrder(commands, sessions);
        order.verify(commands).execute(eq("shield"), argThat(action -> action instanceof Action.PressKey key && key.key().code() == 224));
        order.verify(commands).execute("shield", LAUNCH);
        order.verify(sessions).playNow("fresh", "item-1", 600L);
        verify(sessions, never()).playNow(eq("stale"), anyString(), anyLong());
    }

    @Test
    void anAwakeShieldWithJellyfinClosedOnlyNeedsAnAppLaunch() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doAnswer(_ -> { state.set(ready()); return null; }).when(commands).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(commands).execute("shield", LAUNCH);
        verify(commands, never()).execute(eq("shield"), isA(Action.PressKey.class));
        verify(sessions).playNow("fresh", "item-1", 0);
    }

    @Test
    void anAlreadyReadyAppPlaysWithoutWakeOrLaunch() {
        given(devices.state("shield")).willReturn(ready());
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinSession("old-preview", "item-1", 600L, "Android TV"), shield);

        verify(commands, never()).execute(anyString(), any());
        verify(sessions).playNow("fresh", "item-1", 600L);
    }

    @Test
    void aSleepingShieldWithJellyfinStillOpenOnlyNeedsWake() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withPower(false));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doAnswer(_ -> { state.set(ready()); return null; })
                .when(commands).execute(eq("shield"), isA(Action.PressKey.class));
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 600L), shield);

        verify(commands, never()).execute(eq("shield"), isA(Action.OpenAppLink.class));
        verify(sessions).playNow("fresh", "item-1", 600L);
    }

    @Test
    void aReconnectingShieldCanBecomeReadyDuringStartup() {
        given(devices.state("shield")).willReturn(DeviceState.initial(), ready());
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(sessions).playNow("fresh", "item-1", 0);
        verify(commands, never()).execute(anyString(), any());
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
        }).doAnswer(_ -> { state.set(ready()); return null; }).when(commands).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 600L), shield);

        verify(commands, times(2)).execute("shield", LAUNCH);
        verify(sessions).playNow("fresh", "item-1", 600L);
    }

    @Test
    void retriesLaunchWhenTheConnectionDropsDuringTheWrite() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doThrow(new DeviceOfflineException("connection dropped"))
                .doAnswer(_ -> { state.set(ready()); return null; }).when(commands).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));

        executor.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(sessions).playNow("fresh", "item-1", 0);
    }

    @Test
    void retriesAnUnconfirmedLaunchEvenWhenNoDisconnectWasObserved() {
        AtomicReference<DeviceState> state = new AtomicReference<>(ready().withCurrentApp("launcher"));
        given(devices.state("shield")).willAnswer(_ -> state.get());
        doNothing().doAnswer(_ -> { state.set(ready()); return null; }).when(commands).execute("shield", LAUNCH);
        given(sessions.sessionFor(shield)).willReturn(Optional.of(session("fresh")));
        var startup = new JellyfinRouteExecutor(sessions, devices, commands, Duration.ofSeconds(4),
                Duration.ofMillis(100), Duration.ofMillis(10));

        startup.execute(new Route.JellyfinApp("item-1", 0), shield);

        verify(commands, times(2)).execute("shield", LAUNCH);
        verify(sessions).playNow("fresh", "item-1", 0);
    }

    @Test
    void aLaunchThatNeverReachesJellyfinCannotUseAnOldSession() {
        given(devices.state("shield")).willReturn(ready().withCurrentApp("launcher"));
        Route route = new Route.JellyfinSession("stale", "item-1", 0, "Android TV");

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(ActionFailedException.class).hasMessageContaining("installed");

        verify(commands).execute("shield", LAUNCH);
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

        verify(commands, never()).execute(eq("shield"), isA(Action.OpenAppLink.class));
        verifyNoInteractions(sessions);
    }

    @Test
    void anOfflineDeviceDoesNotReceiveCommands() {
        given(devices.state("shield")).willReturn(DeviceState.initial());
        Route route = new Route.JellyfinApp("item-1", 0);

        assertThatThrownBy(() -> executor.execute(route, shield))
                .isInstanceOf(DeviceOfflineException.class).hasMessageContaining("connect");

        verify(commands, never()).execute(anyString(), any());
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
        var bounded = new JellyfinRouteExecutor(sessions, devices, commands, Duration.ofMillis(100),
                Duration.ofMillis(100), Duration.ofMillis(10));
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
