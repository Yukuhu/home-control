package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import org.junit.jupiter.api.AfterEach;

import java.time.Instant;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The remote's controls beyond the keys: playback, volume, inputs, speaker groups, and the reasons for a refusal. */
class DeviceControlsE2eTest extends E2eApplicationTest {

    @AfterEach
    void forgetTheExtraDevices() {
        enrollment.forget("den");
    }

    private void adoptDen(String caps, String fail, Map<String, String> extra) {
        Map<String, String> settings = new java.util.LinkedHashMap<>(Map.of("caps", caps, "fail", fail));
        settings.putAll(extra);
        enrollment.adopt(new Device("den", "Den Speaker", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("e2e-fake", settings), Instant.now()));
    }

    private static Locator openRemote(Page page) {
        page.navigate("/?device=den");
        page.locator(".drawer-toggle").click();
        Locator remote = page.locator("#remote-drawer");
        assertThat(remote).isVisible();
        return remote;
    }

    private static Locator button(Locator scope, String name) {
        return scope.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name).setExact(true));
    }

    private static void slideTo(Locator slider, int level) {
        // A range input has no text to fill: set it and send the change its user would.
        slider.evaluate("(input, level) => { input.value = String(level);"
                + " input.dispatchEvent(new Event('change', { bubbles: true })); }", level);
    }

    @BrowserTest
    void playbackButtonsAndTheVolumeSliderReachARenderer(String browser) {
        adoptDen("MEDIA_RENDERER,VOLUME", "", Map.of());
        try (BrowserSession session = open(browser)) {
            Locator playback = openRemote(session.page()).locator("section.renderer");

            button(playback, "Pause").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Pause()));
            button(playback, "Play").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Resume()));
            button(playback, "Mute").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Mute(true)));
            button(playback, "Unmute").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Mute(false)));
            slideTo(playback.locator("#volume-den"), 35);
            await().until(() -> fakeDevices.recorded("den").contains(new Action.SetVolume(35)));
            button(playback, "Stop").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Stop()));

            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("den")).containsExactly(
                    new Action.Pause(), new Action.Resume(), new Action.Mute(true), new Action.Mute(false),
                    new Action.SetVolume(35), new Action.Stop());
        }
    }

    @BrowserTest
    void aCastReceiverOffersVolumeMuteAndStopCasting(String browser) {
        adoptDen("CAST_RECEIVER,VOLUME", "", Map.of());
        try (BrowserSession session = open(browser)) {
            Locator remote = openRemote(session.page());
            Locator cast = remote.locator("section.cast");
            assertThat(remote.locator("section.renderer")).hasCount(0);

            slideTo(cast.locator("#volume-den"), 60);
            await().until(() -> fakeDevices.recorded("den").contains(new Action.SetVolume(60)));
            button(cast, "Mute").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Mute(true)));
            button(cast, "Stop casting").click();
            await().until(() -> fakeDevices.recorded("den").contains(new Action.Stop()));
        }
    }

    @BrowserTest
    void anInputButtonSwitchesTheSource(String browser) {
        adoptDen("REMOTE_KEYS,INPUTS", "", Map.of());
        try (BrowserSession session = open(browser)) {
            Locator inputs = openRemote(session.page()).getByRole(AriaRole.REGION,
                    new Locator.GetByRoleOptions().setName("Inputs"));
            assertThat(inputs.getByRole(AriaRole.BUTTON)).hasText(new String[] {"HDMI 1", "Game console"});

            button(inputs, "Game console").click();

            await().until(() -> fakeDevices.recorded("den").contains(new Action.SelectInput("HDMI_2")));
        }
    }

    @BrowserTest
    void aSpeakerOnItsOwnJoinsAnotherGroup(String browser) {
        adoptDen("GROUPING,MEDIA_RENDERER", "", Map.of());
        try (BrowserSession session = open(browser)) {
            Locator group = openRemote(session.page()).getByRole(AriaRole.REGION,
                    new Locator.GetByRoleOptions().setName("Speaker group"));
            assertThat(group).containsText("Playing on its own.");
            assertThat(button(group, "Leave group")).hasCount(0);

            button(group, "Join Kitchen").click();

            await().until(() -> fakeDevices.recorded("den").contains(new Action.JoinGroup("kitchen-speaker")));
        }
    }

    @BrowserTest
    void aGroupedSpeakerNamesItsGroupAndLeavesIt(String browser) {
        adoptDen("GROUPING,MEDIA_RENDERER", "", Map.of("group", "joined"));
        try (BrowserSession session = open(browser)) {
            Locator group = openRemote(session.page()).getByRole(AriaRole.REGION,
                    new Locator.GetByRoleOptions().setName("Speaker group"));
            assertThat(group).containsText("Playing together: Kitchen + Den Speaker");

            button(group, "Leave group").click();

            await().until(() -> fakeDevices.recorded("den").contains(new Action.LeaveGroup()));
        }
    }

    @BrowserTest
    void aCommandTheDeviceRefusesShowsItsReason(String browser) {
        adoptDen("MEDIA_RENDERER,VOLUME", "Pause", Map.of());
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            Locator playback = openRemote(page).locator("section.renderer");

            button(playback, "Pause").click();

            assertThat(page.locator("#toast .toast-text")).hasText("Den Speaker refused to Pause");
        }
    }

    @BrowserTest
    void aKeyboardShortcutTheDeviceRefusesShowsItsReason(String browser) {
        adoptDen("REMOTE_KEYS", "PressKey", Map.of());
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=den");
            page.locator("main h1").click();

            page.keyboard().press("h");

            assertThat(page.locator("#toast .toast-text")).hasText("Den Speaker refused to PressKey");
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("den")).hasSize(1);
        }
    }

    @BrowserTest
    void aLinkOpenedFromTheRemoteShowsTheDevicesAnswer(String browser) {
        adoptDen("APP_LINK,REMOTE_KEYS", "", Map.of());
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            Locator remote = openRemote(page);

            remote.getByLabel("Link to play on this device").fill("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
            button(remote.locator("section.open-link"), "Play").click();

            assertThat(remote.locator("#open-result")).hasText("Open in the YouTube app");
            await().until(() -> fakeDevices.recorded("den").stream().anyMatch(Action.OpenAppLink.class::isInstance));
        }
    }
}
