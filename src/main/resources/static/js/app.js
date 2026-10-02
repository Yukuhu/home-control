import { applyState, subscribe } from "./state-view.js";
import { sendKey } from "./remote-transport.js";
import { watchRails } from "./rails.js";
import { toast } from "./toast.js";
import { initPlaySheet } from "./play-sheet.js";
import { initTouchpad } from "./touchpad.js";
import { initPwa } from "./pwa.js";
import { initRemoteDrawer } from "./remote-drawer.js";

const selectedDevice = () => document.body.dataset.device;

subscribe(applyState);
watchRails();
initPlaySheet();
initTouchpad();
initPwa();

// Keep the active device and its Remote action in view on a narrow device strip.
document.querySelector(".chip.selected")?.closest(".chip-group")
    ?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "instant" });

initRemoteDrawer();

document.addEventListener("homecontrol:stream", ({ detail }) => {
    const status = document.getElementById("connection-status");
    if (!status) return;
    status.textContent = detail.connected ? "Live updates" : "Reconnecting…";
    status.classList.toggle("connected", detail.connected);
});

// htmx drives the buttons and the open-link form; failures carry the server's reason.
document.body.addEventListener("htmx:responseError", (event) => {
    toast(event.detail.xhr.responseText || "The device is not connected");
});
document.body.addEventListener("htmx:sendError", () => toast("Cannot reach the server"));

// The search box searches as you type; Enter must not submit the form and reload the page.
document.querySelector("form.search")?.addEventListener("submit", (event) => event.preventDefault());

// Keyboard control for desktop use, always aimed at the selected device.
const KEYS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT",
    ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", Backspace: "BACK",
    " ": "PLAY_PAUSE", h: "HOME", m: "VOLUME_MUTE",
};

document.addEventListener("keydown", (event) => {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
    // Native activation and text editing take precedence over remote shortcuts.
    if (event.target.closest("input, textarea, select, button, a, summary, #play-sheet, [contenteditable]:not([contenteditable=false]), #touchpad")) return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    sendKey(selectedDevice(), key).catch((error) => toast(error.message));
});
