import { applyState, subscribe } from "./state-view.js";
import { sendKey } from "./remote-transport.js";
import { watchRails } from "./rails.js";
import { toast } from "./toast.js";
import { initPlaySheet } from "./play-sheet.js";
import { initTouchpad } from "./touchpad.js";
import { initPwa } from "./pwa.js";

const selectedDevice = () => document.body.dataset.device;

subscribe(applyState);
watchRails();
initPlaySheet();
initTouchpad();
initPwa();

// Keep the active device and its Remote action in view on a narrow device strip.
document.querySelector(".chip.selected")?.closest(".chip-group")
    ?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "instant" });

const drawer = document.getElementById("remote-drawer");
const desktopRemote = window.matchMedia("(min-width: 64rem)");

function showDrawer() {
    // A native modal keeps the phone remote above the page and background controls inert.
    if (desktopRemote.matches) drawer.show();
    else drawer.showModal();
    // Toasts outside a modal would be hidden behind its backdrop.
    const toastElement = document.getElementById("toast");
    if (toastElement) (desktopRemote.matches ? document.body : drawer).append(toastElement);
}

function setDrawer(open) {
    if (!drawer) return;
    drawer.hidden = !open;
    if (open && !drawer.open) {
        showDrawer();
        drawer.scrollTop = 0;
    } else if (!open) {
        drawer.close();
        const toastElement = drawer.querySelector("#toast");
        if (toastElement) document.body.append(toastElement);
    }
    document.querySelectorAll('[aria-controls="remote-drawer"].drawer-toggle')
        .forEach((b) => b.setAttribute("aria-expanded", String(open)));
    if (open) drawer.querySelector(".drawer-close")?.focus({ preventScroll: true });
    else document.querySelector(".drawer-toggle")?.focus({ preventScroll: true });
}
drawer?.addEventListener("close", () => {
    // close events are queued; a breakpoint change may already have reopened the dialog.
    if (!drawer.open) setDrawer(false);
});
desktopRemote.addEventListener("change", () => {
    if (!drawer?.open) return;
    const focused = document.activeElement;
    const scrollTop = drawer.scrollTop;
    drawer.close();
    showDrawer();
    drawer.scrollTop = scrollTop;
    if (drawer.contains(focused)) focused.focus({ preventScroll: true });
});
if (drawer && !drawer.hidden) setDrawer(true);
document.addEventListener("click", (event) => {
    if (event.target.closest(".drawer-toggle")) setDrawer(document.getElementById("remote-drawer").hidden);
    if (event.target.closest(".drawer-close")) setDrawer(false);
});

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

// Keyboard control for desktop use, always aimed at the selected device.
const KEYS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT",
    ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", Backspace: "BACK",
    " ": "PLAY_PAUSE", h: "HOME", m: "VOLUME_MUTE",
};

document.addEventListener("keydown", (event) => {
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === "Escape" && !document.querySelector("dialog[open]:not(#remote-drawer)")) {
        if (drawer && !drawer.hidden) { event.preventDefault(); setDrawer(false); }
        return;
    }
    // Native activation and text editing take precedence over remote shortcuts.
    if (event.target.closest("input, textarea, select, button, a, summary, #play-sheet, [contenteditable]:not([contenteditable=false]), #touchpad")) return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    sendKey(selectedDevice(), key).catch((error) => toast(error.message));
});
