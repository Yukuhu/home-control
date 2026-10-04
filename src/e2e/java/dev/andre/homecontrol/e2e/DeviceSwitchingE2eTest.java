package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.NowPlaying;
import dev.andre.homecontrol.core.PlaybackState;
import dev.andre.homecontrol.core.RemoteKey;

import java.time.Instant;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

class DeviceSwitchingE2eTest extends E2eApplicationTest {

    @BrowserTest
    void tappingAChipTargetsThatDeviceForKeys(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");

            page.locator("a.chip[data-device='bedroom']").click();
            assertThat(page).hasURL(Pattern.compile(".*device=bedroom.*"));

            page.locator(".drawer-toggle").click();
            page.locator("#remote-drawer").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Home")).click();

            await().until(() -> fakeDevices.recorded("bedroom").contains(new Action.PressKey(RemoteKey.HOME, KeyPress.SHORT)));
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living"))
                    .noneMatch(Action.PressKey.class::isInstance);
        }
    }

    @BrowserTest
    void theSheetSwitcherReplansForTheChosenDevice(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");
            page.locator("button.tile[data-item='clip-1']").click();
            assertThat(page.locator("#sheet-route")).hasText("Play on Living Room · Open in the YouTube app");

            page.getByLabel(Pattern.compile("Speaker")).check();
            assertThat(page.locator("[data-sheet-device='speaker']")).isChecked();
            assertThat(page.locator("#sheet-route")).containsText("Cannot play on Speaker");
            assertThat(page.locator("#sheet-play")).isDisabled();

            page.getByLabel(Pattern.compile("Bedroom")).check();
            assertThat(page.locator("[data-sheet-device='bedroom']")).isChecked();
            assertThat(page.locator("#sheet-route")).hasText("Play on Bedroom · Open in the YouTube app");
            assertThat(page.locator("#sheet-play")).isEnabled();

            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded()).isEmpty();
        }
    }

    @BrowserTest
    void liveStateReachesTheStripAndTheSheet(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");

            fakeDevices.push("living", DeviceState.initial());
            assertThat(page.locator("#status-living")).hasText("DISCONNECTED");

            page.locator("button.tile[data-item='clip-1']").click();
            assertThat(page.locator("#play-sheet [data-status-for='living']")).hasText("DISCONNECTED");

            fakeDevices.push("living", new DeviceState(DeviceStatus.CONNECTED, true, "com.example.launcher",
                    0, 0, false, Instant.now()));
            assertThat(page.locator("#status-living")).hasText("CONNECTED");
            assertThat(page.locator("#play-sheet [data-status-for='living']")).hasText("CONNECTED");
        }
    }

    @BrowserTest
    void mediaWithoutAKnownPositionShowsItsLengthOnly(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living");

            fakeDevices.push("living", new DeviceState(DeviceStatus.CONNECTED, true, "org.videolan.vlc",
                    0, 0, false, Instant.now(), new NowPlaying("Severance", PlaybackState.PLAYING, null, 3420.0)));
            assertThat(page.locator("#app-living")).hasText("Severance · 57:00");

            fakeDevices.push("living", new DeviceState(DeviceStatus.CONNECTED, true, "org.videolan.vlc",
                    0, 0, false, Instant.now(), new NowPlaying("Live stream", PlaybackState.PLAYING, null, null)));
            assertThat(page.locator("#app-living")).hasText("Live stream");
        }
    }
}
