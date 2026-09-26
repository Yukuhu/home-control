import { classify, withinSlop, HOLD_MS } from "./touchpad-gestures.js";
import { sendKey } from "./remote-transport.js";
import { toast } from "./toast.js";
import { bindChoiceKeys } from "./choice-keys.js";

const MODE_KEY = "homecontrol.remote.mode.v1";
const MODES = new Set(["buttons", "touchpad"]);

function readMode() {
    try {
        const value = localStorage.getItem(MODE_KEY);
        return MODES.has(value) ? value : "buttons";
    } catch {
        return "buttons";
    }
}

function writeMode(mode) {
    try { localStorage.setItem(MODE_KEY, mode); } catch { /* private mode: keep it for this page only */ }
}

export function initTouchpad(root = document) {
    const drawer = root.getElementById("remote-drawer");
    const pad = root.getElementById("touchpad");
    if (!drawer || !pad) return;
    const deviceId = pad.dataset.device;
    let gesture = null;          // { id, x, y, holdTimer, holding, start }
    let available = true;

    const fail = (error) => toast(error.message);

    // END must never reach the device before START: releasing (or cancelling) a hold waits for
    // START's own request to settle — success or failure — before even sending END's, so a quick
    // release right after the hold fires can never let END's response outrun START's.
    function sendEndLong(start, keepalive) {
        return (start ?? Promise.resolve()).catch(() => {})
            .finally(() => sendKey(deviceId, "DPAD_CENTER", { press: "end_long", keepalive }).catch(fail));
    }

    function setMode(mode) {
        cancel();
        drawer.dataset.mode = mode;
        for (const tab of drawer.querySelectorAll("[data-mode-switch] [data-mode]")) {
            tab.setAttribute("aria-selected", String(tab.dataset.mode === mode));
            tab.tabIndex = tab.dataset.mode === mode ? 0 : -1;
        }
        writeMode(mode);
    }

    function cancel({ keepalive = false } = {}) {
        if (!gesture) return;
        const { start, holding, id } = gesture;
        clearTimeout(gesture.holdTimer);
        if (holding) sendEndLong(start, keepalive);
        try { pad.releasePointerCapture(id); } catch { /* already released */ }
        gesture = null;
        pad.classList.remove("active");
    }

    function setAvailable(value) {
        available = value;
        pad.setAttribute("aria-disabled", String(!value));
        drawer.querySelector(".remote-controls")?.setAttribute("aria-disabled", String(!value));
        if (!value) cancel();
    }

    pad.addEventListener("pointerdown", (event) => {
        if (gesture || event.button > 0) return;
        if (!available) {
            toast("The device is not connected");
            return;
        }
        event.preventDefault();
        pad.setPointerCapture(event.pointerId);
        pad.classList.add("active");
        gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, holding: false };
        gesture.holdTimer = setTimeout(() => {
            if (!gesture) return;
            gesture.holding = true;
            gesture.start = sendKey(deviceId, "DPAD_CENTER", { press: "start_long" });
            gesture.start.catch(fail);
        }, HOLD_MS);
    });

    pad.addEventListener("pointermove", (event) => {
        if (event.pointerId !== gesture?.id || gesture.holding) return;
        if (!withinSlop(event.clientX - gesture.x, event.clientY - gesture.y)) clearTimeout(gesture.holdTimer);
    });

    pad.addEventListener("pointerup", (event) => {
        if (event.pointerId !== gesture?.id) return;
        const { x, y, holding, start } = gesture;
        clearTimeout(gesture.holdTimer);
        gesture = null;
        pad.classList.remove("active");
        if (holding) {
            sendEndLong(start, false);
            return;
        }
        const result = classify(event.clientX - x, event.clientY - y);
        if (result.kind === "tap") sendKey(deviceId, "DPAD_CENTER").catch(fail);
        if (result.kind === "swipe") sendKey(deviceId, result.key, { repeat: result.repeat }).catch(fail);
    });

    pad.addEventListener("pointercancel", () => cancel());
    drawer.addEventListener("close", () => cancel());
    pad.addEventListener("lostpointercapture", (event) => { if (event.pointerId === gesture?.id) cancel(); });

    // Keyboard path without gestures (vNext §5.7).
    pad.addEventListener("keydown", (event) => {
        const keys = { ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT", ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", " ": "DPAD_CENTER" };
        if (!keys[event.key]) return;
        event.preventDefault();
        event.stopPropagation();
        sendKey(deviceId, keys[event.key]).catch(fail);
    });

    drawer.querySelector("[data-mode-switch]")?.addEventListener("click", (event) => {
        const tab = event.target.closest("[data-mode]");
        if (tab) setMode(tab.dataset.mode);
    });
    bindChoiceKeys(drawer.querySelector("[data-mode-switch]"), "[data-mode]",
        (tab) => setMode(tab.dataset.mode));

    document.addEventListener("homecontrol:state", (event) => {
        if (event.detail.deviceId === deviceId) setAvailable(event.detail.state.status === "CONNECTED");
    });
    document.addEventListener("homecontrol:stream", (event) => {
        if (!event.detail.connected) setAvailable(false);
    });

    // A backgrounded or locked screen never delivers pointerup: without this a hold started
    // just before that would stay open forever, so the device never sees the long press end.
    // cancel() is idempotent (a no-op without an active gesture), so both listeners are safe
    // to fire on the same tab switch. pagehide's END_LONG needs keepalive: the page may already
    // be gone by the time a normal fetch would otherwise be aborted mid-flight.
    document.addEventListener("visibilitychange", () => { if (document.hidden) cancel(); });
    window.addEventListener("pagehide", () => cancel({ keepalive: true }));

    setMode(readMode());
}
