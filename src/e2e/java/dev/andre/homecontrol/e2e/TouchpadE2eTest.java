package dev.andre.homecontrol.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.KeyPress;
import dev.andre.homecontrol.core.RemoteKey;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.awaitility.Awaitility.await;

class TouchpadE2eTest extends E2eApplicationTest {

    private static double[] centerOf(Locator pad) {
        BoundingBox box = pad.boundingBox();
        return new double[] {box.x + box.width / 2, box.y + box.height / 2};
    }

    private static void selectTouchpad(Page page) {
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Touchpad")).click();
    }

    @BrowserTest
    void tapAndSwipesSendTheDocumentedKeys(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living&remote=open");
            selectTouchpad(page);
            Locator pad = page.locator("#touchpad");
            double[] c = centerOf(pad);

            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.mouse().up();
            await().until(() -> fakeDevices.recorded("living").contains(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.SHORT)));

            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.mouse().move(c[0] + 120, c[1], new Mouse.MoveOptions().setSteps(6));
            page.mouse().up();
            await().until(() -> fakeDevices.recorded("living").stream()
                    .filter(a -> a.equals(new Action.PressKey(RemoteKey.DPAD_RIGHT, KeyPress.SHORT))).count() == 2);

            int before = fakeDevices.recorded("living").size();
            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.mouse().move(c[0] + 20, c[1]);
            page.mouse().up();
            page.waitForTimeout(500);
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).hasSize(before);
        }
    }

    @BrowserTest
    void holdSendsStartAndEndOfALongPress(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.clock().install();
            page.navigate("/?device=living&remote=open");
            selectTouchpad(page);
            Locator pad = page.locator("#touchpad");
            double[] c = centerOf(pad);

            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.clock().runFor(500);
            page.mouse().up();

            await().until(() -> fakeDevices.recorded("living").size() >= 2);
            var recorded = fakeDevices.recorded("living");
            org.assertj.core.api.Assertions.assertThat(recorded.get(0))
                    .isEqualTo(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG));
            org.assertj.core.api.Assertions.assertThat(recorded.get(1))
                    .isEqualTo(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG));
            org.assertj.core.api.Assertions.assertThat(recorded)
                    .noneMatch(a -> a.equals(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.SHORT)));
        }
    }

    @BrowserTest
    void theModeIsRememberedAcrossReloads(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living&remote=open");
            selectTouchpad(page);

            page.reload();
            assertThat(page.locator("#remote-drawer")).hasAttribute("data-mode", "touchpad");

            page.evaluate("() => localStorage.setItem('homecontrol.remote.mode.v1', 'bogus')");
            page.reload();
            assertThat(page.locator("#remote-drawer")).hasAttribute("data-mode", "buttons");
        }
    }

    @BrowserTest
    void gesturesAreRefusedWhileTheDeviceIsDisconnected(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.navigate("/?device=living&remote=open");
            selectTouchpad(page);
            Locator pad = page.locator("#touchpad");

            fakeDevices.push("living", DeviceState.initial());
            assertThat(pad).hasAttribute("aria-disabled", "true");

            int before = fakeDevices.recorded("living").size();
            double[] c = centerOf(pad);
            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.mouse().up();

            assertThat(page.locator("#toast")).containsText("The device is not connected");
            org.assertj.core.api.Assertions.assertThat(fakeDevices.recorded("living")).hasSize(before);
        }
    }

    /**
     * D6 review finding: backgrounding the tab mid-hold never delivered pointerup, so a long
     * press could stay open forever. touchpad.js now cancels the gesture (sending END_LONG) on
     * both {@code visibilitychange} (hidden) and {@code pagehide}; this proves it end to end.
     */
    @BrowserTest
    void backgroundingTheTabDuringAHoldEndsIt(String browser) {
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.clock().install();
            page.navigate("/?device=living&remote=open");
            selectTouchpad(page);
            Locator pad = page.locator("#touchpad");
            double[] c = centerOf(pad);

            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.clock().runFor(500);
            await().until(() -> fakeDevices.recorded("living").contains(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG)));

            page.evaluate("() => { Object.defineProperty(document, 'hidden', { value: true, configurable: true }); "
                    + "document.dispatchEvent(new Event('visibilitychange')); }");

            await().until(() -> fakeDevices.recorded("living").contains(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG)));

            // The real pointerup that eventually arrives (screen unlocked, tab restored) must not
            // send a second END_LONG: cancel() already cleared the gesture.
            page.mouse().up();
            page.waitForTimeout(200);
            long endLongCount = fakeDevices.recorded("living").stream()
                    .filter(a -> a.equals(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG))).count();
            org.assertj.core.api.Assertions.assertThat(endLongCount).isEqualTo(1);
        }
    }

    /**
     * Review finding: the hold timer fired START_LONG without awaiting it, and pointerup/cancel
     * fired END_LONG independently — a quick release could let END's (faster) request reach the
     * Shield before START's (slower) one, leaving DPAD_CENTER stuck held. {@code delayNextPress}
     * slows only the server's handling of START_LONG, simulating a slow round trip for it alone
     * without touching the network layer — {@code page.route()} was tried first and rejected: it
     * serializes callback delivery for concurrent requests on one page, which masked this exact
     * race instead of reproducing it. touchpad.js must wait for START's request to settle before
     * even sending END's.
     */
    @BrowserTest
    void endLongNeverOutrunsStartLongEvenOnAQuickRelease(String browser) {
        fakeDevices.delayNextPress(KeyPress.START_LONG, java.time.Duration.ofMillis(300));
        try (BrowserSession session = open(browser)) {
            Page page = session.page();
            page.clock().install();
            page.navigate("/?device=living&remote=open");
            selectTouchpad(page);
            Locator pad = page.locator("#touchpad");
            double[] c = centerOf(pad);

            page.mouse().move(c[0], c[1]);
            page.mouse().down();
            page.clock().runFor(500);   // fires the hold timer: issues the (server-delayed) start_long request
            page.mouse().up();          // released immediately, well within that request's round trip

            await().until(() -> fakeDevices.recorded("living").size() >= 2);
            var recorded = fakeDevices.recorded("living");
            org.assertj.core.api.Assertions.assertThat(recorded.get(0))
                    .isEqualTo(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.START_LONG));
            org.assertj.core.api.Assertions.assertThat(recorded.get(1))
                    .isEqualTo(new Action.PressKey(RemoteKey.DPAD_CENTER, KeyPress.END_LONG));
        }
    }
}
