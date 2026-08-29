// Live device state. The server pushes on every change; a new tab gets the current
// state immediately on connect.
const source = new EventSource("/events");

source.addEventListener("state", (event) => {
    const state = JSON.parse(event.data);

    const status = document.getElementById("status");
    status.textContent = state.status;
    status.classList.toggle("ok", state.status === "CONNECTED");
    status.classList.toggle("off", state.status !== "CONNECTED");

    document.getElementById("current-app").textContent = state.currentApp || "Nothing playing";
    document.getElementById("volume").textContent =
        state.muted ? "muted" : "vol " + state.volumeLevel;
});

// A rejected command means the device is offline. Shared by the button path (htmx fires
// this on any non-2xx) and the keyboard path below, so both report a refused key the same way.
function showOfflineToast() {
    const toast = document.getElementById("toast");
    toast.textContent = "The device is not connected";
    toast.hidden = false;
    setTimeout(() => (toast.hidden = true), 2000);
}

document.body.addEventListener("htmx:responseError", showOfflineToast);

// Keyboard control for desktop use.
const KEYS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT",
    ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", Backspace: "BACK",
    " ": "PLAY_PAUSE", h: "HOME", m: "VOLUME_MUTE",
};

document.addEventListener("keydown", (event) => {
    if (event.target.tagName === "INPUT") return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    fetch("/key/" + key, { method: "POST" })
        .then((response) => {
            if (!response.ok) showOfflineToast();
        })
        .catch(showOfflineToast);
});
