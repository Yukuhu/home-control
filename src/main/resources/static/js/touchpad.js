import { classify, withinSlop, HOLD_MS } from "./touchpad-gestures.js";
import { sendKey } from "./remote-transport.js";
import { toast } from "./toast.js";

const MODE_KEY = "homecontrol.remote.mode.v1";
const MODES = ["buttons", "touchpad"];

function readMode() {
    try {
        const value = localStorage.getItem(MODE_KEY);
        return MODES.includes(value) ? value : "buttons";
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
    let gesture = null;          // { id, x, y, holdTimer, holding }
    let available = true;

    const fail = (error) => toast(error.message);

    function setMode(mode) {
        cancel();
        drawer.dataset.mode = mode;
        for (const tab of drawer.querySelectorAll("[data-mode-switch] [data-mode]")) {
            tab.setAttribute("aria-selected", String(tab.dataset.mode === mode));
        }
        writeMode(mode);
    }

    function cancel() {
        if (!gesture) return;
        clearTimeout(gesture.holdTimer);
        if (gesture.holding) sendKey(deviceId, "DPAD_CENTER", { press: "end_long" }).catch(fail);
        try { pad.releasePointerCapture(gesture.id); } catch { /* already released */ }
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
            sendKey(deviceId, "DPAD_CENTER", { press: "start_long" }).catch(fail);
        }, HOLD_MS);
    });

    pad.addEventListener("pointermove", (event) => {
        if (!gesture || event.pointerId !== gesture.id || gesture.holding) return;
        if (!withinSlop(event.clientX - gesture.x, event.clientY - gesture.y)) clearTimeout(gesture.holdTimer);
    });

    pad.addEventListener("pointerup", (event) => {
        if (!gesture || event.pointerId !== gesture.id) return;
        const { x, y, holding } = gesture;
        clearTimeout(gesture.holdTimer);
        gesture = null;
        pad.classList.remove("active");
        if (holding) {
            sendKey(deviceId, "DPAD_CENTER", { press: "end_long" }).catch(fail);
            return;
        }
        const result = classify(event.clientX - x, event.clientY - y);
        if (result.kind === "tap") sendKey(deviceId, "DPAD_CENTER").catch(fail);
        if (result.kind === "swipe") sendKey(deviceId, result.key, { repeat: result.repeat }).catch(fail);
    });

    pad.addEventListener("pointercancel", cancel);
    pad.addEventListener("lostpointercapture", (event) => { if (gesture && event.pointerId === gesture.id) cancel(); });

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

    document.addEventListener("homecontrol:state", (event) => {
        if (event.detail.deviceId === deviceId) setAvailable(event.detail.state.status === "CONNECTED");
    });
    document.addEventListener("homecontrol:stream", (event) => {
        if (!event.detail.connected) setAvailable(false);
    });

    // A backgrounded or locked screen never delivers pointerup: without this a hold started
    // just before that would stay open forever, so the device never sees the long press end.
    // cancel() is idempotent (a no-op without an active gesture), so both listeners are safe
    // to fire on the same tab switch.
    document.addEventListener("visibilitychange", () => { if (document.hidden) cancel(); });
    window.addEventListener("pagehide", () => cancel());

    setMode(readMode());
}
