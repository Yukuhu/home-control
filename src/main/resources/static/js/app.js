import { applyState, subscribe } from "./state-view.js";
import { sendKey } from "./remote-transport.js";
import { watchRails } from "./rails.js";

const selectedDevice = () => document.body.dataset.device;

subscribe(applyState);
watchRails();

function setDrawer(open) {
    const drawer = document.getElementById("remote-drawer");
    if (!drawer) return;
    drawer.hidden = !open;
    document.querySelectorAll('[aria-controls="remote-drawer"].drawer-toggle')
        .forEach((b) => b.setAttribute("aria-expanded", String(open)));
}
document.addEventListener("click", (event) => {
    if (event.target.closest(".drawer-toggle")) setDrawer(document.getElementById("remote-drawer").hidden);
    if (event.target.closest(".drawer-close")) setDrawer(false);
});

function toast(message) {
    const el = document.getElementById("toast");
    el.textContent = message;
    el.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => (el.hidden = true), 3000);
}

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
    if (event.target.closest("input, textarea, select, dialog")) return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    sendKey(selectedDevice(), key).catch((error) => toast(error.message));
});
