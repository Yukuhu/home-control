// Paints one device's live state into whichever elements the page has for it. The device
// strip has a badge and app label per device; the drawer additionally has a volume label
// and, for Cast receivers, a volume slider for the selected device. Missing elements are
// skipped so the same module serves both.
export function applyState(deviceId, state) {
    const status = document.getElementById(`status-${deviceId}`);
    if (status) {
        status.textContent = state.status;
        status.classList.toggle("ok", state.status === "CONNECTED");
        status.classList.toggle("off", state.status !== "CONNECTED");
    }
    const app = document.getElementById(`app-${deviceId}`);
    if (app) app.textContent = describePlaying(state);
    const volume = document.getElementById(`vol-${deviceId}`);
    if (volume) volume.textContent = state.muted ? "muted" : `vol ${state.volumeLevel}`;
    const slider = document.getElementById(`volume-${deviceId}`);
    // Leave the thumb alone while the user is dragging it; their change event follows.
    if (slider && !slider.matches(":active")) slider.value = state.volumeLevel;
}

// Media reported by the device (Cast) wins over the foreground app name (Android TV).
export function describePlaying(state) {
    const playing = state.nowPlaying;
    if (!playing) return state.currentApp || "Nothing playing";
    const position = formatTime(playing.positionSeconds)
        + (playing.durationSeconds ? ` / ${formatTime(playing.durationSeconds)}` : "");
    const paused = playing.state === "PAUSED" ? " (paused)" : "";
    return `${playing.title}${paused} · ${position}`;
}

function formatTime(seconds) {
    const whole = Math.max(0, Math.floor(seconds || 0));
    return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, "0")}`;
}

export function subscribe(onState) {
    const source = new EventSource("/events");
    source.addEventListener("state", (event) => {
        const { deviceId, state } = JSON.parse(event.data);
        onState(deviceId, state);
    });
    return source;
}
