import { on } from "./events.js";

const lastStates = new Map();

export function stateOf(deviceId) {
    return lastStates.get(deviceId);
}

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
    // The play sheet (Task 3) shows a device's status wherever it marks the element up
    // this way, independent of the strip's fixed status-<id> ids.
    document.querySelectorAll(`[data-status-for="${CSS.escape(deviceId)}"]`).forEach((el) => {
        el.textContent = state.status;
        el.classList.toggle("ok", state.status === "CONNECTED");
        el.classList.toggle("off", state.status !== "CONNECTED");
    });
    const app = document.getElementById(`app-${deviceId}`);
    if (app) app.textContent = describePlaying(state);
    const volume = document.getElementById(`vol-${deviceId}`);
    if (volume) volume.textContent = state.muted ? "muted" : `vol ${state.volumeLevel}`;
    const slider = document.getElementById(`volume-${deviceId}`);
    // Leave the thumb alone while the user is dragging it; their change event follows.
    // The slider is always 0-100 (what SetVolume takes); a merged device's composed volumeMax
    // may be another adapter's own scale (Android TV's volume steps, say), so the level is
    // rescaled onto that 0-100 range rather than shown as-is.
    if (slider && !slider.matches(":active")) {
        slider.value = state.volumeMax > 0 ? Math.round((state.volumeLevel * 100) / state.volumeMax) : 0;
    }
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
    on("state", ({ deviceId, state }) => {
        lastStates.set(deviceId, state);
        onState(deviceId, state);
        document.dispatchEvent(new CustomEvent("homecontrol:state", { detail: { deviceId, state } }));
    });
}
