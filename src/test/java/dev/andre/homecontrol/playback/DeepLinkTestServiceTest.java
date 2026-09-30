package dev.andre.homecontrol.playback;

import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.ActionFailedException;
import dev.andre.homecontrol.core.Capability;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.DeviceNotFoundException;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStateChangedEvent;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.ForegroundAppReporting;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepLinkTestServiceTest {

    private static final URI VIDEO = URI.create("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
    private static final DeviceState HOME = new DeviceState(DeviceStatus.CONNECTED, true, "com.webos.app.home",
            12, 100, false, Instant.now());

    private final DeviceQueries devices = mock(DeviceQueries.class);
    private final DeviceCommands commands = mock(DeviceCommands.class);
    private DeepLinkTestService service = new DeepLinkTestService(devices, commands,
            new DeepLinkTestProperties(VIDEO, Duration.ofMillis(300)));

    @BeforeEach
    void anLgTvOnItsHomeScreen() {
        when(devices.device("lg")).thenReturn(Optional.of(
                new Device("lg", "LG TV", DeviceKind.WEBOS, "10.0.0.60", Map.of("webos", Map.of()), Instant.now())));
        when(devices.capabilities("lg")).thenReturn(EnumSet.of(Capability.APP_LINK));
        when(devices.state("lg")).thenReturn(HOME);
        when(devices.foregroundAppReporting("lg")).thenReturn(ForegroundAppReporting.LIVE);
    }

    /** The TV answers the link with a state event, from another thread as a real adapter does. */
    private void tvAnswersWith(String deviceId, DeviceState state) {
        doAnswer(call -> {
            Thread.ofVirtual().start(() -> service.onStateChanged(new DeviceStateChangedEvent(deviceId, state)));
            return null;
        }).when(commands).execute(eq("lg"), any());
    }

    @Test
    void anUnknownDeviceIsNotFound() {
        when(devices.device("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run("ghost"))
                .isInstanceOf(DeviceNotFoundException.class).hasMessage("No device with id ghost");
    }

    @Test
    void reportsTheNewForegroundApp() {
        tvAnswersWith("lg", HOME.withCurrentApp("youtube.leanback.v4"));

        DeepLinkTestResult result = service.run("lg");

        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.APP_CHANGED);
        assertThat(result.appBefore()).isEqualTo("com.webos.app.home");
        assertThat(result.appAfter()).isEqualTo("youtube.leanback.v4");
        assertThat(result.message()).contains("switched from com.webos.app.home to youtube.leanback.v4")
                .contains("check the screen");
        verify(commands).execute("lg", new Action.OpenAppLink(VIDEO));
    }

    @Test
    void stateEventsThatKeepTheSameAppAreNotAChange() {
        tvAnswersWith("lg", HOME.withVolume(13, 100, false));

        DeepLinkTestResult result = service.run("lg");

        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.NO_CHANGE);
        assertThat(result.message()).contains("did not change").contains("still com.webos.app.home");
    }

    @Test
    void eventsOfOtherDevicesAreIgnored() {
        tvAnswersWith("other", HOME.withCurrentApp("youtube.leanback.v4"));

        assertThat(service.run("lg").outcome()).isEqualTo(DeepLinkTestResult.Outcome.NO_CHANGE);
    }

    @Test
    void aPolledDeviceSaysSo() {
        when(devices.foregroundAppReporting("lg")).thenReturn(ForegroundAppReporting.POLLED);
        tvAnswersWith("lg", HOME.withCurrentApp("YouTube"));

        DeepLinkTestResult result = service.run("lg");

        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.APP_CHANGED);
        assertThat(result.message()).contains("polled");
    }

    @Test
    void aPolledDeviceWithoutChangeBlamesNothing() {
        when(devices.foregroundAppReporting("lg")).thenReturn(ForegroundAppReporting.POLLED);

        DeepLinkTestResult result = service.run("lg");

        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.NO_CHANGE);
        assertThat(result.message()).contains("may be unavailable on this model");
    }

    @Test
    void aPolledDeviceAlreadyShowingAnAppSaysSoAndHowToRetest() {
        when(devices.foregroundAppReporting("lg")).thenReturn(ForegroundAppReporting.POLLED);
        when(devices.state("lg")).thenReturn(HOME.withCurrentApp("YouTube"));

        DeepLinkTestResult result = service.run("lg");

        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.NO_CHANGE);
        assertThat(result.message()).contains("still YouTube").contains("go to the Home screen and test again");
    }

    @Test
    void aPolledDeviceWithoutAReportedAppDoesNotClaimOne() {
        when(devices.foregroundAppReporting("lg")).thenReturn(ForegroundAppReporting.POLLED);
        when(devices.state("lg")).thenReturn(HOME.withCurrentApp(null));

        assertThat(service.run("lg").message()).doesNotContain("still");
    }

    @Test
    void aDeviceThatCannotReportItsAppIsNotObservable() {
        when(devices.foregroundAppReporting("lg")).thenReturn(ForegroundAppReporting.NONE);

        DeepLinkTestResult result = service.run("lg");

        // Only the path that never waits for the app to change returns NOT_OBSERVABLE.
        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.NOT_OBSERVABLE);
        assertThat(result.message()).contains("does not report which app is in front");
        verify(commands).execute("lg", new Action.OpenAppLink(VIDEO));
    }

    @Test
    void aFailedSendIsReportedWithTheReason() {
        doThrow(new ActionFailedException("LG TV could not open the link: 500 Application error"))
                .when(commands).execute(eq("lg"), any());
        DeepLinkTestResult refused = service.run("lg");
        assertThat(refused.outcome()).isEqualTo(DeepLinkTestResult.Outcome.FAILED);
        assertThat(refused.message()).contains("500 Application error");

        doThrow(new DeviceOfflineException("LG TV is not connected")).when(commands).execute(eq("lg"), any());
        DeepLinkTestResult offline = service.run("lg");
        assertThat(offline.outcome()).isEqualTo(DeepLinkTestResult.Outcome.FAILED);
        assertThat(offline.message()).contains("LG TV is not connected");
    }

    @Test
    void aDeviceForgottenBeforeItsCapabilitiesAreReadIsNotFoundNotUnsupported() {
        when(devices.device("lg")).thenReturn(Optional.of(
                new Device("lg", "LG TV", DeviceKind.WEBOS, "10.0.0.60", Map.of("webos", Map.of()), Instant.now())),
                Optional.empty());
        when(devices.capabilities("lg")).thenReturn(EnumSet.noneOf(Capability.class));

        assertThatThrownBy(() -> service.run("lg"))
                .isInstanceOf(DeviceNotFoundException.class).hasMessage("No device with id lg");
        verify(commands, never()).execute(anyString(), any());
    }

    @Test
    void aDeviceForgottenBeforeTheLinkIsSentIsNotFoundAndReleasesTheDevice() {
        doThrow(new DeviceNotFoundException("No device with id lg")).when(commands).execute(eq("lg"), any());

        assertThatThrownBy(() -> service.run("lg"))
                .isInstanceOf(DeviceNotFoundException.class).hasMessage("No device with id lg");

        doThrow(new ActionFailedException("LG TV could not open the link")).when(commands).execute(eq("lg"), any());
        assertThat(service.run("lg").message()).contains("could not open the link");
    }

    @Test
    void anUnexpectedFailureIsReportedWithoutItsInternals() {
        doThrow(new IllegalStateException("Cannot invoke \"Session.send()\" because \"this.session\" is null"))
                .when(commands).execute(eq("lg"), any());

        DeepLinkTestResult failed = service.run("lg");

        assertThat(failed.outcome()).isEqualTo(DeepLinkTestResult.Outcome.FAILED);
        assertThat(failed.message())
                .isEqualTo("The test link was not opened: an unexpected error, which the server's log describes")
                .doesNotContain("session");
    }

    @Test
    void aDeviceWithoutAppLinksIsRejected() {
        when(devices.capabilities("lg")).thenReturn(EnumSet.of(Capability.REMOTE_KEYS));

        assertThatThrownBy(() -> service.run("lg"))
                .isInstanceOf(UnsupportedActionException.class)
                .hasMessageContaining("cannot open app links");
        verify(commands, never()).execute(anyString(), any());
    }

    @Test
    void aSecondTestOnTheSameDeviceWhileOneRunsIsRefused() throws Exception {
        service = new DeepLinkTestService(devices, commands, new DeepLinkTestProperties(VIDEO, Duration.ofSeconds(1)));
        AtomicReference<DeepLinkTestResult> first = new AtomicReference<>();
        Thread running = Thread.ofVirtual().start(() -> first.set(service.run("lg")));
        await().atMost(Duration.ofSeconds(5)).until(() -> mockingDetails(commands).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("execute")));

        DeepLinkTestResult second = service.run("lg");

        assertThat(second.outcome()).isEqualTo(DeepLinkTestResult.Outcome.FAILED);
        assertThat(second.message()).contains("already running");
        running.join();
        assertThat(first.get().outcome()).isEqualTo(DeepLinkTestResult.Outcome.NO_CHANGE);
    }

    @Test
    void aLiveDeviceWithoutAReportedAppDoesNotClaimOneEither() {
        when(devices.state("lg")).thenReturn(HOME.withCurrentApp(null));

        DeepLinkTestResult result = service.run("lg");

        assertThat(result.outcome()).isEqualTo(DeepLinkTestResult.Outcome.NO_CHANGE);
        assertThat(result.message()).isEqualTo("The app in front did not change within 1 seconds. If that already"
                + " was YouTube, go to the Home screen and test again; otherwise the YouTube app may be missing or"
                + " LG TV ignored the link; check the screen.");
    }
}
