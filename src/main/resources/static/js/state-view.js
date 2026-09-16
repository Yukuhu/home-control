// Paints one device's live state into whichever elements the page has for it. The device
// strip has a badge and app label per device; the drawer additionally has a volume label
// for the selected device. Missing elements are skipped so the same module serves both.
export function applyState(deviceId, state) {
    const status = document.getElementById(`status-${deviceId}`);
    if (status) {
        status.textContent = state.status;
        status.classList.toggle("ok", state.status === "CONNECTED");
        status.classList.toggle("off", state.status !== "CONNECTED");
    }
    const app = document.getElementById(`app-${deviceId}`);
    if (app) app.textContent = state.currentApp || "Nothing playing";
    const volume = document.getElementById(`vol-${deviceId}`);
    if (volume) volume.textContent = state.muted ? "muted" : `vol ${state.volumeLevel}`;
}

export function subscribe(onState) {
    const source = new EventSource("/events");
    source.addEventListener("state", (event) => {
        const { deviceId, state } = JSON.parse(event.data);
        onState(deviceId, state);
    });
    return source;
}
